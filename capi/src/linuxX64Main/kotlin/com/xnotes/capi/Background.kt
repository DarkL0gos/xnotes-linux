@file:OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class)

package com.xnotes.capi

import com.xnotes.capi.c.xn_host
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.sysconf

/**
 * Page cache builds off the UI thread, for a host that can be woken from another thread
 * (xn_host.wake): CanvasState's runAsync and postToMain.
 *
 * Work runs on a few threads of the core's own, oldest first, so the visible pages (which the frame
 * asks for before it prefetches their neighbours) start first. What a build hands back for the UI
 * thread waits in [results]; the first result to arrive wakes the host, which calls
 * xn_editor_take_results on its UI thread to run them all.
 */
internal class CBackground(private val host: xn_host) {
    private val lock = Lock()
    private val queue = ArrayDeque<() -> Unit>()
    private val results = ArrayList<() -> Unit>()
    private val workers = List(threadCount()) { Worker.start(name = "xnotes-cache-$it") }
    private val busy = BooleanArray(workers.size)
    private var closed = false

    /** Builds running or waiting. */
    private val outstanding = AtomicInt(0)

    /** Builds running or waiting, and results not taken in yet. */
    val pending: Int get() = outstanding.value + withLock(lock) { results.size }

    /** Any thread: queue [work] and start an idle worker on the queue. */
    fun run(work: () -> Unit) {
        val idle = withLock(lock) {
            if (closed) return
            queue.addLast(work)
            outstanding.incrementAndGet()
            val i = busy.indexOfFirst { !it }
            if (i >= 0) busy[i] = true
            i
        }
        if (idle >= 0) workers[idle].executeAfter(0L) { drainQueue(idle) }
    }

    /** On worker [i]: run queued work until there is none. */
    private fun drainQueue(i: Int) {
        while (true) {
            val job = withLock(lock) {
                if (closed || queue.isEmpty()) {
                    busy[i] = false
                    null
                } else {
                    queue.removeFirst()
                }
            } ?: return
            try {
                job()
            } catch (_: Throwable) {
                // A build that failed leaves its page to be asked for again; the worker carries on.
            } finally {
                outstanding.decrementAndGet()
            }
        }
    }

    /** Any thread: hand [result] to the UI thread, waking the host when it is the first waiting. */
    fun post(result: () -> Unit) {
        val wake = withLock(lock) {
            if (closed) return
            results.add(result)
            results.size == 1
        }
        if (wake) host.wake!!.invoke(host.ctx)
    }

    /** UI thread: run what the workers handed back. */
    fun takeResults() {
        val due = withLock(lock) { results.toList().also { results.clear() } }
        for (r in due) r()
    }

    /** UI thread: drop waiting work and results, and wait for the builds still running. */
    fun close() {
        withLock(lock) {
            closed = true
            outstanding.addAndGet(-queue.size)
            queue.clear()
            results.clear()
        }
        for (w in workers) w.requestTermination(processScheduledJobs = true).result
    }

    private companion object {
        /** A thread per core but one, for the UI, up to three: a page takes one, the screen shows two or three. */
        fun threadCount(): Int = (sysconf(_SC_NPROCESSORS_ONLN).toInt() - 1).coerceIn(1, 3)
    }
}
