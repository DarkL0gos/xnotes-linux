package com.xnotes.format

import com.xnotes.core.platform.identitySet
import com.xnotes.core.platform.identityMap
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.Glue

/**
 * How both codecs write and read what connectors glue to. In memory a connector holds its targets
 * by reference; a file names them by an `id` written on just the items something glues to, numbered
 * as the connectors name them, so an unchanged list writes the same bytes every time.
 */
internal object GlueIds {

    /** The ids for [items]: one for each item a connector among them glues to. */
    fun of(items: List<CanvasItem>): Map<CanvasItem, Int> {
        if (items.none { it is ConnectorItem }) return emptyMap()
        val present = identitySet<CanvasItem>().apply { addAll(items) }
        val ids = identityMap<CanvasItem, Int>()
        for (item in items) {
            if (item !is ConnectorItem) continue
            for (end in listOf(item.start, item.end)) {
                val t = end.target ?: continue
                // A target that has left the list (deleted, erased) is not written: the end goes loose.
                if (t in present && t !in ids) ids[t] = ids.size + 1
            }
        }
        return ids
    }
}

/** A connector end as read: where it sits, and the id and point it names, if glued. */
internal class EndRead(val at: Pt?, val id: Int?, val point: Int)

/**
 * The glue of one item list being read: each item's id as it arrives, and the connectors waiting
 * for the items their ends name, which may come after them.
 */
internal class GlueRead {
    private val byId = HashMap<Int, CanvasItem>()
    private val pending = ArrayList<Triple<ConnectorItem, EndRead?, EndRead?>>()

    fun named(item: CanvasItem, id: Int?) {
        if (id != null) byId[id] = item
    }

    fun waiting(c: ConnectorItem, from: EndRead?, to: EndRead?) {
        pending += Triple(c, from, to)
    }

    /** Glue every waiting end to the item it names; an end naming nothing it can glue to stays loose. */
    fun resolve() {
        for ((c, from, to) in pending) {
            c.start = glued(c, c.start, from)
            c.end = glued(c, c.end, to)
        }
        pending.clear()
    }

    private fun glued(c: ConnectorItem, end: ConnectorEnd, read: EndRead?): ConnectorEnd {
        val target = read?.id?.let { byId[it] } ?: return end
        if (target === c || !Glue.accepts(target)) return end
        return ConnectorEnd(end.at, target, read.point)
    }
}
