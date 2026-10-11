package com.xnotes.desktop

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.session.SessionDocument
import com.xnotes.session.SessionStore
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Per-user desktop paths (XDG). Source assets stay in the cache while their document is open; the
 * working session (unsaved edits, restored after a crash) is state, so it lives under the state dir.
 */
internal class DesktopPaths(private val overrideRoot: File? = null) {
    private val home = System.getProperty("user.home")
    val config = overrideRoot?.resolve("config") ?: xdg("XDG_CONFIG_HOME", ".config").resolve("xnotes")
    val cache = overrideRoot?.resolve("cache") ?: xdg("XDG_CACHE_HOME", ".cache").resolve("xnotes")
    val state = overrideRoot?.resolve("state") ?: xdg("XDG_STATE_HOME", ".local/state").resolve("xnotes")

    private fun xdg(variable: String, fallback: String): File {
        val path = System.getenv(variable)?.takeIf { it.isNotBlank() }
        return if (path != null && File(path).isAbsolute) File(path) else File(home, fallback)
    }
}

/** An open document; [file] is null for one that has never been saved (e.g. restored untitled). */
internal sealed interface OpenDocument {
    val file: File?
    val dirty: Boolean
    val title: String get() = file?.name ?: "Без имени"

    data class Note(override val file: File?, val value: Document) : OpenDocument {
        override val dirty get() = value.dirty
    }

    data class Canvas(override val file: File?, val value: InfiniteDocument) : OpenDocument {
        override val dirty get() = value.dirty
    }
}

/** A session restored at launch, with the host view state it was checkpointed with. */
internal class RestoredSession(val document: OpenDocument, val view: Map<String, Double>)

/** Reads assets into a private working directory and writes bundles without touching source on failure. */
internal class DesktopStorage(private val paths: DesktopPaths = DesktopPaths()) : AutoCloseable {
    private val noteCodec = DocumentCodec(DesktopImageCodec, DesktopTextMeasurer)
    private val canvasCodec = CanvasCodec(DesktopImageCodec, DesktopTextMeasurer)
    private val session = SessionStore(paths.state.resolve("session"), noteCodec, canvasCodec)
    private var assets: File? = null

    fun open(file: File): OpenDocument {
        require(file.isFile) { "Файл не найден: $file" }
        return withWorkDir { work ->
            when (file.extension.lowercase()) {
                "xnote" -> file.inputStream().buffered().use {
                    OpenDocument.Note(file, noteCodec.read(it, work, work).apply {
                        path = file.absolutePath
                        displayName = file.name
                    })
                }
                "xcanvas" -> file.inputStream().buffered().use {
                    OpenDocument.Canvas(file, canvasCodec.read(it, work).apply {
                        path = file.absolutePath
                        displayName = file.name
                    })
                }
                else -> error("Поддерживаются только .xnote и .xcanvas")
            }
        }
    }

    /** Checkpoint [doc] with its unsaved edits and the host [view], so a crash or kill loses nothing. */
    fun saveSession(doc: OpenDocument, view: Map<String, Double>) = session.save(
        when (doc) {
            is OpenDocument.Note -> SessionDocument.Note(doc.value)
            is OpenDocument.Canvas -> SessionDocument.Canvas(doc.value)
        },
        view,
    )

    /** The checkpointed session, if one holds unsaved edits; it becomes the open document. */
    fun restoreSession(): RestoredSession? {
        if (!session.exists()) return null
        return runCatching {
            withWorkDir { work ->
                val snapshot = session.load(work) ?: error("session unreadable")
                val restored = when (val d = snapshot.document) {
                    is SessionDocument.Note -> OpenDocument.Note(d.path?.let(::File), d.document)
                    is SessionDocument.Canvas -> OpenDocument.Canvas(d.path?.let(::File), d.document)
                }
                check(restored.dirty) { "session holds no unsaved edits" }
                RestoredSession(restored, snapshot.view)
            }
        }.getOrNull()
    }

    /** Whether a checkpointed session exists, without loading its document. */
    fun hasSession(): Boolean = session.exists()

    fun clearSession() = session.clear()

    /** Run [read] with a fresh asset dir that becomes the open document's on success. */
    private fun <T> withWorkDir(read: (File) -> T): T {
        val work = Files.createTempDirectory(paths.cache.apply { mkdirs() }.toPath(), "open-").toFile()
        try {
            val result = read(work)
            clearAssets()
            assets = work
            return result
        } catch (t: Throwable) {
            work.deleteRecursively()
            throw t
        }
    }

    fun save(doc: OpenDocument, target: File) {
        val expected = when (doc) {
            is OpenDocument.Note -> "xnote"
            is OpenDocument.Canvas -> "xcanvas"
        }
        require(target.extension.lowercase() == expected) { "Требуется расширение .$expected" }
        val absolute = target.absoluteFile
        val parent = absolute.parentFile ?: error("Нет каталога назначения")
        require(parent.isDirectory) { "Каталог не найден: $parent" }
        val tmp = Files.createTempFile(parent.toPath(), ".xnotes-", ".tmp")
        try {
            Files.newOutputStream(tmp).buffered().use { out ->
                when (doc) {
                    is OpenDocument.Note -> noteCodec.write(doc.value, out)
                    is OpenDocument.Canvas -> canvasCodec.write(doc.value, out)
                }
            }
            try {
                Files.move(tmp, absolute.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, absolute.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            when (doc) {
                is OpenDocument.Note -> { doc.value.path = absolute.path; doc.value.displayName = absolute.name; doc.value.dirty = false }
                is OpenDocument.Canvas -> { doc.value.path = absolute.path; doc.value.displayName = absolute.name; doc.value.dirty = false }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun clearAssets() {
        assets?.deleteRecursively()
        assets = null
    }

    override fun close() = clearAssets()
}
