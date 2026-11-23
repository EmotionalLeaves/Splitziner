package splitziner.parts

/**
 * A folding screen: three or more (or two) tall flat panels standing in a row, edge to edge with a hinge's gap
 * between them. Each panel is built on its own; they are hinged together, and it stands folded, in a zig-zag.
 */
object Screens {
    /** Boxes this close (mm) are one panel. */
    private const val TOUCH = 0.5

    /** The gap a hinge leaves between two panels (mm): more than touching, less than a quarter inch. */
    private val HINGE_GAP = 0.5..6.5

    /** The panels, in order along the row, or null when it is not a screen. */
    fun leaves(live: List<Part>): List<List<Part>>? {
        if (live.size < 4) return null
        val n = live.size
        val parent = IntArray(n) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        val b = live.map { it.mesh.bounds }
        for (i in 0 until n) for (j in i + 1 until n)
            if ((0..2).all { k -> b[i].min[k] <= b[j].max[k] + TOUCH && b[j].min[k] <= b[i].max[k] + TOUCH }) parent[root(i)] = root(j)
        val groups = (0 until n).groupBy { root(it) }.values.map { g -> g.map { live[it] } }
        if (groups.size < 2 || groups.any { it.size < 2 }) return null
        val boxes = groups.map { g -> g.map { it.mesh.bounds }.reduce { x, y -> x.union(y) } }
        // All standing flat in one plane (thin across it), tall, the same height.
        val thin = (0..1).firstOrNull { ax -> boxes.all { bx -> bx.extents[ax] < 0.15 * bx.extents[1 - ax] && bx.extents.z > 2 * bx.extents[1 - ax] * 0.5 } } ?: return null
        val along = 1 - thin
        if (boxes.any { kotlin.math.abs(it.extents.z - boxes[0].extents.z) > 2.0 || kotlin.math.abs(it.min.z - boxes[0].min.z) > 2.0 }) return null
        if (boxes.any { it.extents.z < 900.0 }) return null
        val order = boxes.indices.sortedBy { boxes[it].min[along] }
        for ((i, j) in order.zipWithNext()) {
            val gap = boxes[j].min[along] - boxes[i].max[along]
            if (gap !in HINGE_GAP) return null
            if (kotlin.math.abs(boxes[j].min[thin] - boxes[i].min[thin]) > 2.0) return null
        }
        return order.map { groups[it] }
    }
}
