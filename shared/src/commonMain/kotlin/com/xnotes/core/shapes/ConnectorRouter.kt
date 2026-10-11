package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.abs

/**
 * Plans a square-cornered connector's route around the boxes in its way. Each end leaves its own box
 * straight out along one of its ways (a stub, clear of the box by the margin), and the stubs are
 * joined over a grid of lines through every obstacle's edges and the stubs themselves, cheapest
 * first, a bend costing as much as a stretch of line. A box an end sits inside cannot be gone round,
 * so it is dropped; when the search runs out of grid or of budget, a plain elbow joins the stubs and
 * crosses whatever it has to. It never fails. Pure, so it unit-tests.
 */
object ConnectorRouter {

    /**
     * One end of a route: where it is, the ways it may set off (outward normals; none for a loose
     * end, which may go any way), and the box it sets off from, null when it is not on one.
     */
    class Port(val at: Pt, val ways: List<Pt> = emptyList(), val box: Rect? = null)

    /**
     * The route from [from] to [to], every segment along a page axis, keeping [margin] clear of
     * [obstacles] and of the ports' own boxes where it can. The ports' boxes must not be among
     * [obstacles]. [budget] caps the search, past which the plain elbow is taken.
     */
    fun route(from: Port, to: Port, obstacles: List<Rect>, margin: Double, budget: Int = BUDGET): List<Pt> {
        val starts = stubs(from, margin)
        val ends = stubs(to, margin)
        val blocks = ArrayList<Rect>(obstacles.size + 2)
        for (r in obstacles + listOfNotNull(from.box, to.box)) {
            val o = r.outset(margin)
            val ownFrom = r === from.box
            val ownTo = r === to.box
            if (starts.any { !ownFrom && inside(o, it.at) } || ends.any { !ownTo && inside(o, it.at) }) continue
            blocks += o
        }
        val found = search(from, to, starts, ends, blocks, margin * BEND_MARGINS, budget)
        return Elbow.corners(found ?: plain(from, to, starts[0], ends[0]))
    }

    /** Where a port's route leaves its box along each of its ways: the way, the point, the length out. */
    private class Stub(val dir: Int, val at: Pt, val length: Double)

    private fun stubs(p: Port, margin: Double): List<Stub> {
        val dirs = p.ways.filter { it.length() > 1e-9 }.map(::dirOf).distinct()
        if (dirs.isEmpty()) return listOf(Stub(NONE, p.at, 0.0))
        return dirs.map { d ->
            val box = p.box?.outset(margin)
            val out = if (box == null) margin else when (d) {
                RIGHT -> box.right - p.at.x
                DOWN -> box.bottom - p.at.y
                LEFT -> p.at.x - box.left
                else -> p.at.y - box.top
            }
            val len = out.coerceAtLeast(margin)
            Stub(d, p.at + UNIT[d] * len, len)
        }
    }

    private fun search(from: Port, to: Port, starts: List<Stub>, ends: List<Stub>, blocks: List<Rect>, bend: Double, budget: Int): List<Pt>? {
        val xs = lines(blocks.flatMap { listOf(it.left, it.right) } + (starts + ends).map { it.at.x } + (from.at.x + to.at.x) / 2.0)
        val ys = lines(blocks.flatMap { listOf(it.top, it.bottom) } + (starts + ends).map { it.at.y } + (from.at.y + to.at.y) / 2.0)
        val nx = xs.size
        val ny = ys.size
        if (nx.toLong() * ny > MAX_NODES) return null
        val nodes = nx * ny
        // What a block rules out: the nodes strictly inside it and the grid edges through its inside.
        val blocked = BooleanArray(nodes)
        val noRight = BooleanArray(nodes)
        val noDown = BooleanArray(nodes)
        for (b in blocks) {
            val i0 = indexOf(xs, b.left)
            val i1 = indexOf(xs, b.right)
            val j0 = indexOf(ys, b.top)
            val j1 = indexOf(ys, b.bottom)
            for (i in i0..i1) {
                for (j in j0..j1) {
                    val k = i * ny + j
                    val inI = i > i0 && i < i1
                    val inJ = j > j0 && j < j1
                    if (inI && inJ) blocked[k] = true
                    if (i < i1 && inJ) noRight[k] = true
                    if (j < j1 && inI) noDown[k] = true
                }
            }
        }
        fun pointOf(k: Int) = Pt(xs[k / ny], ys[k % ny])
        fun nodeAt(p: Pt) = indexOf(xs, p.x) * ny + indexOf(ys, p.y)
        // A bend on the line halfway between the ends costs a little less, so a step centres itself.
        val midI = indexOf(xs, (from.at.x + to.at.x) / 2.0)
        val midJ = indexOf(ys, (from.at.y + to.at.y) / 2.0)
        fun turn(k: Int) = if (k / ny == midI || k % ny == midJ) bend * (1.0 - MID_DISCOUNT) else bend
        val goals = HashMap<Int, MutableList<Stub>>()
        for (e in ends) goals.getOrPut(nodeAt(e.at)) { ArrayList() }.add(e)
        fun h(k: Int): Double {
            val p = pointOf(k)
            return ends.minOf { abs(it.at.x - p.x) + abs(it.at.y - p.y) + it.length }
        }
        val g = DoubleArray(nodes * STATES) { Double.POSITIVE_INFINITY }
        val came = IntArray(nodes * STATES) { -1 }
        val heap = Heap()
        for (s in starts) {
            val k = nodeAt(s.at)
            val st = k * STATES + s.dir
            if (s.length < g[st]) {
                g[st] = s.length
                heap.push(s.length + h(k), st)
            }
        }
        var best = Double.POSITIVE_INFINITY
        var bestState = -1
        var bestEnd: Stub? = null
        var expanded = 0
        while (heap.size > 0) {
            val f = heap.peekKey()
            val st = heap.pop()
            if (f >= best) break
            val k = st / STATES
            val d = st % STATES
            if (f > g[st] + h(k) + 1e-9) continue
            if (++expanded > budget) return null
            goals[k]?.let { list ->
                for (e in list) {
                    val turns = d != NONE && e.dir != NONE && d != opposite(e.dir)
                    val total = g[st] + e.length + if (turns) turn(k) else 0.0
                    if (total < best) {
                        best = total
                        bestState = st
                        bestEnd = e
                    }
                }
            }
            val i = k / ny
            val j = k % ny
            for (nd in 0 until 4) {
                if (d != NONE && nd == opposite(d)) continue
                val nk = when (nd) {
                    RIGHT -> if (i + 1 < nx && !noRight[k]) k + ny else -1
                    LEFT -> if (i > 0 && !noRight[k - ny]) k - ny else -1
                    DOWN -> if (j + 1 < ny && !noDown[k]) k + 1 else -1
                    else -> if (j > 0 && !noDown[k - 1]) k - 1 else -1
                }
                if (nk < 0 || blocked[nk]) continue
                val cost = g[st] + pointOf(k).manhattanTo(pointOf(nk)) + if (d != NONE && nd != d) turn(k) else 0.0
                val ns = nk * STATES + nd
                if (cost < g[ns]) {
                    g[ns] = cost
                    came[ns] = st
                    heap.push(cost + h(nk), ns)
                }
            }
        }
        if (bestState < 0 || bestEnd == null) return null
        val path = ArrayList<Pt>()
        var st = bestState
        while (st >= 0) {
            path += pointOf(st / STATES)
            st = came[st]
        }
        path.reverse()
        return listOf(from.at) + path + to.at
    }

    /** The plain elbow between two stubs, square to the ways they leave and arrive by. */
    private fun plain(from: Port, to: Port, s: Stub, e: Stub): List<Pt> {
        val a = s.at
        val b = e.at
        val leaveH = if (s.dir == NONE) abs(b.x - a.x) >= abs(b.y - a.y) else s.dir == RIGHT || s.dir == LEFT
        val arriveH = if (e.dir == NONE) leaveH else e.dir == RIGHT || e.dir == LEFT
        val middle = when {
            leaveH && arriveH -> ((a.x + b.x) / 2.0).let { listOf(Pt(it, a.y), Pt(it, b.y)) }
            !leaveH && !arriveH -> ((a.y + b.y) / 2.0).let { listOf(Pt(a.x, it), Pt(b.x, it)) }
            leaveH -> listOf(Pt(b.x, a.y))
            else -> listOf(Pt(a.x, b.y))
        }
        return listOf(from.at, a) + middle + listOf(b, to.at)
    }

    /** [values] sorted, with any within a hair of the one before dropped. */
    private fun lines(values: List<Double>): DoubleArray {
        val sorted = values.filter { it.isFinite() }.sorted()
        val out = ArrayList<Double>(sorted.size)
        for (v in sorted) if (out.isEmpty() || v - out.last() > LINE_EPS) out += v
        return out.toDoubleArray()
    }

    /** The index of the line in [lines] nearest [v]. */
    private fun indexOf(lines: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = lines.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (lines[mid] < v) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && abs(lines[lo - 1] - v) <= abs(lines[lo] - v)) lo - 1 else lo
    }

    private fun inside(r: Rect, p: Pt): Boolean = p.x > r.left && p.x < r.right && p.y > r.top && p.y < r.bottom

    private fun dirOf(n: Pt): Int = if (abs(n.x) >= abs(n.y)) (if (n.x >= 0.0) RIGHT else LEFT) else (if (n.y >= 0.0) DOWN else UP)

    private fun opposite(d: Int): Int = (d + 2) % 4

    /** A min-heap of search states keyed by their estimated cost. */
    private class Heap {
        private var keys = DoubleArray(64)
        private var vals = IntArray(64)
        var size = 0
            private set

        fun push(key: Double, value: Int) {
            if (size == keys.size) {
                keys = keys.copyOf(size * 2)
                vals = vals.copyOf(size * 2)
            }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= key) break
                keys[i] = keys[parent]
                vals[i] = vals[parent]
                i = parent
            }
            keys[i] = key
            vals[i] = value
        }

        fun peekKey(): Double = keys[0]

        fun pop(): Int {
            val top = vals[0]
            size--
            if (size > 0) {
                val key = keys[size]
                val value = vals[size]
                var i = 0
                while (true) {
                    val l = 2 * i + 1
                    if (l >= size) break
                    val c = if (l + 1 < size && keys[l + 1] < keys[l]) l + 1 else l
                    if (keys[c] >= key) break
                    keys[i] = keys[c]
                    vals[i] = vals[c]
                    i = c
                }
                keys[i] = key
                vals[i] = value
            }
            return top
        }
    }

    private const val RIGHT = 0
    private const val DOWN = 1
    private const val LEFT = 2
    private const val UP = 3

    /** No way yet: a loose start, which may set off any way without a bend. */
    private const val NONE = 4
    private const val STATES = 5
    private val UNIT = arrayOf(Pt(1.0, 0.0), Pt(0.0, 1.0), Pt(-1.0, 0.0), Pt(0.0, -1.0))

    /** A bend costs as much line as this many margins. */
    private const val BEND_MARGINS = 3.0

    /** What a bend halfway between the ends saves, as a share of a bend. */
    private const val MID_DISCOUNT = 0.02

    /** How many search states may be expanded before the plain elbow is taken. */
    const val BUDGET = 40_000

    /** Past this many grid nodes the search is not even tried. */
    private const val MAX_NODES = 250_000L
    private const val LINE_EPS = 1e-6
}
