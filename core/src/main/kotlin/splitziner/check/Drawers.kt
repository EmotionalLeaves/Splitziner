package splitziner.check

import splitziner.math.Vec3
import splitziner.parts.Part

/**
 * Finds drawer boxes: an open-topped box (a bottom and walls round it, nothing over it) sitting in an
 * opening with parts beside it. Real drawers need room at their sides for slides or runners.
 */
object Drawers {
    /** Ball-bearing slides, the common kind, need 1/2 in (12.7 mm) each side; this a little less, for tolerance. */
    const val SLIDE_GAP = 12.0

    class Drawer(val ids: List<String>, val left: Double, val right: Double)

    fun find(parts: List<Part>): List<Drawer> {
        val live = parts.filter { !it.excluded }
        if (live.size < 6) return emptyList()
        val lo = live.map { it.mesh.bounds.min }; val hi = live.map { it.mesh.bounds.max }
        fun ext(i: Int) = hi[i] - lo[i]
        fun thin(i: Int) = minOf(ext(i).x, ext(i).y, ext(i).z)
        val out = ArrayList<Drawer>(); val used = HashSet<Int>()
        // Candidate bottoms: flat horizontal boards.
        for (b in live.indices) {
            if (b in used) continue
            val e = ext(b)
            if (e.z > 0.3 * minOf(e.x, e.y) || e.z > 25.0) continue
            // Walls standing on or around this bottom, within its footprint (give or take a wall's thickness).
            val walls = live.indices.filter { w ->
                w != b && w !in used && ext(w).z > 2 * e.z && ext(w).z >= minOf(ext(w).x, ext(w).y) &&
                    lo[w].z >= lo[b].z - 30 && lo[w].z <= hi[b].z + 1 &&
                    lo[w].x >= lo[b].x - 30 && hi[w].x <= hi[b].x + 30 && lo[w].y >= lo[b].y - 30 && hi[w].y <= hi[b].y + 30 &&
                    thin(w) < 30
            }
            if (walls.size < 3) continue
            val all = walls + b
            val bl = Vec3(all.minOf { lo[it].x }, all.minOf { lo[it].y }, all.minOf { lo[it].z })
            val bh = Vec3(all.maxOf { hi[it].x }, all.maxOf { hi[it].y }, all.maxOf { hi[it].z })
            // Walls on at least three sides: left and right, plus front or back.
            val sides = walls.map { w -> when {
                ext(w).x <= thin(w) + 0.01 && lo[w].x <= bl.x + 30 -> "L"; ext(w).x <= thin(w) + 0.01 && hi[w].x >= bh.x - 30 -> "R"
                ext(w).y <= thin(w) + 0.01 && lo[w].y <= bl.y + 30 -> "F"; ext(w).y <= thin(w) + 0.01 && hi[w].y >= bh.y - 30 -> "B"; else -> "?" } }.toSet()
            if (!("L" in sides && "R" in sides && ("F" in sides || "B" in sides))) continue
            // Open top: no other part lies over the box within its height.
            val set = all.toSet()
            val lidded = live.indices.any { j -> j !in set && lo[j].z < bh.z - 1 && hi[j].z > bh.z - 0.25 * (bh.z - bl.z) && lo[j].x < bh.x - 1 && hi[j].x > bl.x + 1 && lo[j].y < bh.y - 1 && hi[j].y > bl.y + 1 && ext(j).z < 30 && lo[j].z > bl.z + 0.5 * (bh.z - bl.z) }
            if (lidded) continue
            // In an opening: something outside the box on both sides over most of its height and depth.
            fun beside(left: Boolean): Double? = live.indices.filter { j -> j !in set &&
                lo[j].z < bh.z && hi[j].z > bl.z + 0.3 * (bh.z - bl.z) && lo[j].y < bh.y && hi[j].y > bl.y + 0.3 * (bh.y - bl.y) &&
                (if (left) hi[j].x <= bl.x + 0.5 else lo[j].x >= bh.x - 0.5) }
                .minOfOrNull { j -> if (left) bl.x - hi[j].x else lo[j].x - bh.x }
            val l = beside(true) ?: continue; val r = beside(false) ?: continue
            if (l > 100 || r > 100) continue
            used += set
            out += Drawer(all.map { live[it].id }, maxOf(0.0, l), maxOf(0.0, r))
        }
        return out
    }
}
