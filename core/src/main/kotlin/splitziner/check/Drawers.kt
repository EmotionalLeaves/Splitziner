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

    /** [boxIds] are the box itself, without a false front wider than it; the slides fit the box. */
    class Drawer(val ids: List<String>, val left: Double, val right: Double, val boxIds: List<String> = ids)

    /** The longest standard slide (2 in or 50 mm steps) that fits a drawer [depth] mm deep, as sold: "14 in", "350 mm". */
    fun slideLength(depth: Double, inch: Boolean): String? =
        if (inch) (10..28 step 2).lastOrNull { it * 25.4 <= depth + 1 }?.let { "$it in" } else (250..700 step 50).lastOrNull { it <= depth + 1 }?.let { "$it mm" }

    fun depthOf(parts: List<Part>, d: Drawer): Double = parts.filter { it.id in d.boxIds }.let { ps -> ps.maxOf { it.mesh.bounds.max.y } - ps.minOf { it.mesh.bounds.min.y } }

    fun find(parts: List<Part>): List<Drawer> {
        // A five-piece (frame-and-panel) front is one front: looked at as one piece, known by its first stile.
        val groups = runCatching { FramePanels.find(parts) }.getOrDefault(emptyList())
        if (groups.isEmpty()) return findParts(parts)
        val byId = parts.associateBy { it.id }
        val grouped = groups.flatMap { it.ids }.toSet()
        val merged = parts.filter { it.id !in grouped } + groups.mapNotNull { g ->
            val ms = g.ids.mapNotNull { byId[it] }.filter { !it.excluded }; if (ms.size != g.ids.size) null
            else ms.first { it.id == g.stiles[0] }.copy(mesh = splitziner.mesh.MeshOps.concatenate(ms.map { it.mesh })) }
        val of = groups.associate { it.stiles[0] to it.ids }
        return findParts(merged).map { d -> Drawer(d.ids.flatMap { of[it] ?: listOf(it) }, d.left, d.right, d.boxIds.flatMap { of[it] ?: listOf(it) }) }
    }

    private fun findParts(parts: List<Part>): List<Drawer> {
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
            // How far past the bottom a wall may reach sideways: a front lying in front of it may cover the
            // carcass sides as well (an overlay front), the rest only a wall's thickness or so.
            fun reach(w: Int) = if (ext(w).y <= thin(w) + 0.01 && hi[w].y <= lo[b].y + 0.5) 80.0 else 30.0
            // Walls standing on or around this bottom, within its footprint (give or take a wall's thickness).
            val walls0 = live.indices.filter { w ->
                w != b && w !in used && ext(w).z > 2 * e.z && ext(w).z >= minOf(ext(w).x, ext(w).y) &&
                    lo[w].z >= lo[b].z - 30 && lo[w].z <= hi[b].z + 1 &&
                    lo[w].x >= lo[b].x - reach(w) && hi[w].x <= hi[b].x + reach(w) && lo[w].y >= lo[b].y - reach(w) && hi[w].y <= hi[b].y + 30 &&
                    thin(w) < 30
            }
            // A part much taller than the rest (the carcass's back panel behind the bottom drawer) is not a wall.
            val tops = walls0.map { hi[it].z - lo[b].z }.sorted()
            val walls = if (tops.isEmpty()) walls0 else walls0.filter { hi[it].z - lo[b].z <= 1.5 * tops[tops.size / 2] + 10.0 }
            if (walls.size < 3) continue
            val all = walls + b
            // A false front: thin front to back, in front of the rest and wider than the box. Slides and the room
            // for them are measured from the box behind it.
            val fronts = walls.filter { w -> ext(w).y <= thin(w) + 0.01 && (all - w).let { os -> ext(w).x > os.maxOf { o -> hi[o].x } - os.minOf { o -> lo[o].x } + 1.0 && os.all { o -> lo[o].y >= hi[w].y - 0.5 } } }
            val box = all - fronts.toSet()
            val bl = Vec3(box.minOf { lo[it].x }, all.minOf { lo[it].y }, all.minOf { lo[it].z })
            val bh = Vec3(box.maxOf { hi[it].x }, all.maxOf { hi[it].y }, all.maxOf { hi[it].z })
            // Walls on at least three sides: left and right, plus front or back.
            val sides = walls.map { w -> when {
                ext(w).x <= thin(w) + 0.01 && lo[w].x <= bl.x + 30 -> "L"; ext(w).x <= thin(w) + 0.01 && hi[w].x >= bh.x - 30 -> "R"
                ext(w).y <= thin(w) + 0.01 && lo[w].y <= bl.y + 30 -> "F"; ext(w).y <= thin(w) + 0.01 && hi[w].y >= bh.y - 30 -> "B"; else -> "?" } }.toSet()
            if (!("L" in sides && "R" in sides && ("F" in sides || "B" in sides))) continue
            // Open top: no other part lies over the box within its height.
            val set = all.toSet()
            // Judged by the box: an overlay front may reach up past a rail over the drawer that is not a lid.
            val boxTop = box.maxOf { hi[it].z }; val boxFront = box.minOf { lo[it].y }
            val lidded = live.indices.any { j -> j !in set && lo[j].z < boxTop - 1 && hi[j].z > boxTop - 0.25 * (boxTop - bl.z) && lo[j].x < bh.x - 1 && hi[j].x > bl.x + 1 && lo[j].y < bh.y - 1 && hi[j].y > boxFront + 1 && ext(j).z < 30 && lo[j].z > bl.z + 0.5 * (boxTop - bl.z) }
            if (lidded) continue
            // In an opening: something outside the box on both sides over most of its height and depth.
            fun beside(left: Boolean): Double? = live.indices.filter { j -> j !in set &&
                lo[j].z < bh.z && hi[j].z > bl.z + 0.3 * (bh.z - bl.z) && lo[j].y < bh.y && hi[j].y > bl.y + 0.3 * (bh.y - bl.y) &&
                (if (left) hi[j].x <= bl.x + 0.5 else lo[j].x >= bh.x - 0.5) }
                .minOfOrNull { j -> if (left) bl.x - hi[j].x else lo[j].x - bh.x }
            val l = beside(true) ?: continue; val r = beside(false) ?: continue
            if (l > 100 || r > 100) continue
            used += set
            out += Drawer(all.map { live[it].id }, maxOf(0.0, l), maxOf(0.0, r), box.map { live[it].id })
        }
        return out
    }
}
