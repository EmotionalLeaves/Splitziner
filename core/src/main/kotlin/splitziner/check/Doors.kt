package splitziner.check

import splitziner.math.Vec3
import splitziner.parts.Part

/**
 * Doors: a thin panel at the front of an opening, a hair clear of the parts round it. It hangs on hinges,
 * which are hardware and not in the model, so as drawn it touches nothing.
 */
object Doors {
    /** Clear space round an inset door, at most (mm); a real door needs 1.5-3 mm to swing. */
    private const val MAX_GAP = 6.0

    class Door(val id: String, val height: Double, val hingeLeft: Boolean)

    /** Wider than this (mm) a single door is unusual; the common rule of thumb is about 600 mm (24 in). */
    const val MAX_WIDTH = 620.0

    /** Hinges by door height: two up to 900 mm, three to 1500, four beyond (common cabinetmakers' rule). */
    fun hinges(height: Double) = when { height <= 900 -> 2; height <= 1500 -> 3; else -> 4 }

    fun find(parts: List<Part>): List<Door> {
        val live = parts.filter { !it.excluded }
        if (live.size < 3) return emptyList()
        val b = live.map { it.mesh.bounds }
        val out = ArrayList<Door>()
        for ((i, p) in live.withIndex()) {
            val e = b[i].extents
            if (e.y > 0.15 * minOf(e.x, e.z) || e.y > 30.0) continue
            // Parts beside it on the left and right, a small gap away, overlapping its height and reaching behind it.
            fun near(left: Boolean) = live.indices.filter { j -> j != i && b[j].min.z < b[i].max.z && b[j].max.z > b[i].min.z && b[j].max.y >= b[i].max.y - 0.01 &&
                (if (left) b[j].max.x <= b[i].min.x + 0.01 && b[i].min.x - b[j].max.x <= MAX_GAP else b[j].min.x >= b[i].max.x - 0.01 && b[j].min.x - b[i].max.x <= MAX_GAP) }
                .minOfOrNull { j -> if (left) b[i].min.x - b[j].max.x else b[j].min.x - b[i].max.x }
            val l = near(true); val r = near(false)
            if (l == null || r == null || l < 0.5 || r < 0.5) continue
            // Touching nothing at all (it hangs on its hinges).
            val touches = live.indices.any { j -> j != i && (0..2).all { k -> b[j].min[k] <= b[i].max[k] + 0.05 && b[i].min[k] <= b[j].max[k] + 0.05 } }
            if (touches) continue
            out += Door(p.id, e.z, true)
        }
        // Doors over the front of a box (overlay doors) are the thin panels found next.
        val inset = out.map { it.id }.toSet()
        val inDrawers = runCatching { Drawers.find(live) }.getOrDefault(emptyList()).flatMap { it.ids }.toSet()
        for ((i, p) in live.withIndex()) {
            if (p.id in inset || p.id in inDrawers) continue
            val e = b[i].extents
            if (e.y > 0.15 * minOf(e.x, e.z) || e.y > 30.0) continue
            if (overlay(live, b, i)) out += Door(p.id, e.z, true)
        }
        return out
    }

    /**
     * Whether thin panel [i] hangs over the front of an opening: just behind it (touching, or a few mm off) are
     * the front edges of a box on all four sides, and the box is open in the middle, so most of the panel covers
     * nothing. One side may be another such panel beside it instead (a pair of doors meeting in the middle).
     * A panel on legs (a desk's modesty panel) has nothing behind its bottom edge, so it is not a door.
     */
    private fun overlay(live: List<Part>, b: List<splitziner.math.Aabb>, i: Int): Boolean {
        val d = b[i]; val w = d.extents.x; val h = d.extents.z
        if (w < 100.0 || h < 100.0) return false
        // What lies just behind it, and how much of the panel each covers.
        class Cover(val x0: Double, val x1: Double, val z0: Double, val z1: Double)
        val behind = ArrayList<Cover>()
        for (j in live.indices) {
            if (j == i) continue
            val o = b[j]
            val x0 = maxOf(o.min.x, d.min.x); val x1 = minOf(o.max.x, d.max.x); val z0 = maxOf(o.min.z, d.min.z); val z1 = minOf(o.max.z, d.max.z)
            if (x1 - x0 <= 0.5 || z1 - z0 <= 0.5) continue
            // Anything overlapping it front to back is not behind it; a part right up against it must be behind.
            if (o.min.y < d.max.y - 0.05) { if (o.max.y > d.min.y + 0.05) return false else continue }
            if (o.min.y - d.max.y > MAX_GAP) continue
            behind += Cover(x0, x1, z0, z1)
        }
        if (behind.isEmpty()) return false
        // Mostly open behind: the edges cover under half of it.
        if (behind.sumOf { (it.x1 - it.x0) * (it.z1 - it.z0) } > 0.5 * w * h) return false
        val edge = 0.25
        fun upright(left: Boolean) = behind.any { c -> c.z1 - c.z0 > 0.5 * h && (if (left) c.x1 <= d.min.x + edge * w else c.x0 >= d.max.x - edge * w) }
        fun rail(top: Boolean) = behind.any { c -> c.x1 - c.x0 > 0.5 * w && (if (top) c.z0 >= d.max.z - edge * h else c.z1 <= d.min.z + edge * h) }
        // A twin panel beside it in the same plane, a small gap away.
        fun twin(left: Boolean) = live.indices.any { j ->
            val o = b[j]
            j != i && kotlin.math.abs(o.min.y - d.min.y) < 0.5 && kotlin.math.abs(o.max.y - d.max.y) < 0.5 && o.min.z < d.max.z && o.max.z > d.min.z &&
                (if (left) d.min.x - o.max.x in 0.5..MAX_GAP else o.min.x - d.max.x in 0.5..MAX_GAP)
        }
        return (upright(true) || twin(true)) && (upright(false) || twin(false)) && rail(true) && rail(false) && !(twin(true) && twin(false))
    }
}
