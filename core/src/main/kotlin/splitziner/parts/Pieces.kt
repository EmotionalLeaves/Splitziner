package splitziner.parts

/**
 * A set of separate pieces drawn in one file (nesting plinths, a pair of nightstands): groups of parts that touch
 * each other but stand clear of every other group. Each is named and built as a piece of its own.
 */
object Pieces {
    /** Boxes this close (mm) count as touching when grouping. */
    private const val TOUCH = 2.0

    /** Pieces stand at least this far apart (mm), so a part that merely fails to meet its neighbour is not a piece. */
    private const val CLEAR = 10.0

    /** A piece has at least this many parts: a stray part or two is a modelling slip, not a piece. */
    private const val MIN_PARTS = 3

    /** The pieces, largest first by where they stand (left to right, then front to back), or null for one piece. */
    fun find(live: List<Part>): List<List<Part>>? {
        if (live.size < 2 * MIN_PARTS) return null
        val n = live.size
        val parent = IntArray(n) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; var c = i; while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }; return r }
        val b = live.map { it.mesh.bounds }
        for (i in 0 until n) for (j in i + 1 until n) {
            if ((0..2).all { k -> b[i].min[k] <= b[j].max[k] + TOUCH && b[j].min[k] <= b[i].max[k] + TOUCH }) parent[root(i)] = root(j)
        }
        val groups = (0 until n).groupBy { root(it) }.values.map { g -> g.map { live[it] } }
        if (groups.size < 2 || groups.any { it.size < MIN_PARTS }) return null
        val boxes = groups.map { g -> g.map { it.mesh.bounds }.reduce { x, y -> x.union(y) } }
        for (i in boxes.indices) for (j in i + 1 until boxes.size)
            if ((0..2).all { k -> boxes[i].min[k] < boxes[j].max[k] + CLEAR && boxes[j].min[k] < boxes[i].max[k] + CLEAR }) return null
        return groups.indices.sortedWith(compareBy({ boxes[it].min.x }, { boxes[it].min.y })).map { groups[it] }
    }
}
