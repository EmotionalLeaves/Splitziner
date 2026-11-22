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
        return out
    }
}
