package splitziner.check

import splitziner.parts.Part

/**
 * Frame-and-panel work (a Shaker door, a panelled side): a thin panel whose edges sit in grooves in the four strips
 * round it, two stiles running the long way and two rails between them. As drawn the panel overlaps each strip by
 * the groove's depth, inside the strip's thickness.
 */
object FramePanels {
    /**
     * One frame: its panels (more than one with a middle rail or muntin), the stiles running the long way at its sides,
     * the rails and any muntins between, and each panel with the strips its edges are sunk in.
     */
    class Group(val panels: List<String>, val stiles: List<String>, val rails: List<String>, val sunk: List<Pair<String, String>>) {
        val panel get() = panels[0]
        val ids get() = panels + stiles + rails
    }

    /** A panel this thin or thinner (mm) can sit in a groove: 1/2 in. */
    private const val MAX_PANEL = 13.0

    /** A panel this narrow at least (mm), both ways: a five-piece drawer front's panel can be under 4 in tall. */
    private const val MIN_PANEL = 50.0

    /** A strip's width is at most this fraction of its length: a short door rail, 2-1/4 x 9 in, still counts. */
    private const val MAX_STRIP_RATIO = 0.6

    /** A groove this deep at most (mm): 5/8 in. */
    private const val MAX_GROOVE = 16.0

    /** The groups that hang as doors. */
    fun doors(parts: List<Part>): List<Group> {
        val groups = find(parts); if (groups.isEmpty()) return groups
        val ids = runCatching { Doors.find(parts).map { it.id }.toSet() }.getOrDefault(emptySet())
        return groups.filter { g -> g.ids.any { it in ids } }
    }

    fun find(parts: List<Part>): List<Group> {
        val live = parts.filter { !it.excluded }
        class One(val panel: Part, val strips: List<Part>)
        val ones = ArrayList<One>()
        for (p in live) {
            val pb = p.mesh.bounds; val pe = pb.extents
            val k = (0..2).minByOrNull { pe[it] }!!
            if (pe[k] > MAX_PANEL || (0..2).filter { it != k }.any { pe[it] < MIN_PANEL }) continue
            val strips = live.filter { s -> s !== p && s.mesh.bounds.let { sb -> val se = sb.extents
                (0..2).all { a -> minOf(sb.max[a], pb.max[a]) - maxOf(sb.min[a], pb.min[a]) > 0.5 } &&
                    sb.min[k] < pb.min[k] - 0.5 && sb.max[k] > pb.max[k] + 0.5 &&
                    (0..2).filter { it != k }.any { a -> val ov = minOf(sb.max[a], pb.max[a]) - maxOf(sb.min[a], pb.min[a]); ov <= MAX_GROOVE && ov < pe[a] - 1.0 } &&
                    // A strip lies flat in the panel's plane (a case's side, on edge to it, is not one), and is long for its width.
                    (0..2).minByOrNull { se[it] } == k &&
                    (0..2).filter { it != k }.sorted().let { (a, b) -> minOf(se[a], se[b]) < MAX_STRIP_RATIO * maxOf(se[a], se[b]) } } }
            if (strips.size >= 4) ones += One(p, strips)
        }
        // Panels sharing a strip (a middle rail between two) are one frame.
        val used = BooleanArray(ones.size)
        val out = ArrayList<Group>()
        for (i in ones.indices) {
            if (used[i]) continue
            val members = arrayListOf(i); used[i] = true
            var grew = true
            while (grew) { grew = false
                for (j in ones.indices) if (!used[j] && members.any { m -> ones[m].strips.any { s -> ones[j].strips.any { it === s } } }) { members += j; used[j] = true; grew = true } }
            val ms = members.map { ones[it] }
            val strips = ms.flatMap { it.strips }.distinct()
            // Stiles run the frame's long way, the full length of it; rails and muntins are the rest.
            val pb = ms.map { it.panel.mesh.bounds }.reduce { a, b -> a.union(b) }
            val k = (0..2).minByOrNull { pb.extents[it] }!!
            val sb = strips.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }
            // Two strips the full length one way: those are the stiles (upright in a door, at the ends of a wide frame).
            val long = (0..2).filter { it != k }.sortedByDescending { sb.extents[it] }.firstOrNull { a -> strips.count { s -> s.mesh.bounds.extents[a] >= sb.extents[a] - 1.0 } == 2 } ?: continue
            val stiles = strips.filter { s -> s.mesh.bounds.extents[long] >= sb.extents[long] - 1.0 }
            val rails = strips - stiles.toSet()
            if (stiles.size == 2 && rails.size >= 2 && out.none { g -> strips.any { it.id in g.ids } })
                out += Group(ms.map { it.panel.id }, stiles.sortedBy { it.mesh.bounds.min.x + it.mesh.bounds.min.y }.map { it.id }, rails.map { it.id },
                    ms.flatMap { o -> o.strips.map { o.panel.id to it.id } })
        }
        return out
    }
}
