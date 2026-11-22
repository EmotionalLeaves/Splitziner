package splitziner.parts

/**
 * Plain names for parts that have none of their own ("Side", "Shelf", "Door", "Drawer bottom"), read from where
 * each part sits and which way it lies. A guess, and only a label: it changes nothing about how a part is made,
 * and a name from the file or from the maker always wins.
 */
object Roles {
    /** A board narrower than this (mm) is a strip (rail, upright), never a shelf, side or top. */
    private const val NARROW = 150.0

    fun names(parts: List<Part>): Map<String, String> {
        val live = parts.filter { !it.excluded }
        if (live.size < 2) return emptyMap()
        val out = HashMap<String, String>()
        runCatching { splitziner.check.Doors.find(live) }.getOrDefault(emptyList()).forEach { out[it.id] = "Door" }
        runCatching { splitziner.check.ShelfPins.find(live) }.getOrDefault(emptyList()).forEach { out[it] = "Adjustable shelf" }
        val byId = live.associateBy { it.id }
        for (dr in runCatching { splitziner.check.Drawers.find(live) }.getOrDefault(emptyList())) {
            val box = dr.boxIds.mapNotNull { byId[it] }
            for (id in dr.ids - dr.boxIds.toSet()) out[id] = "Drawer front"
            val bottom = box.minByOrNull { it.mesh.bounds.min.z + it.mesh.bounds.extents.z }
            val frontY = box.minOf { it.mesh.bounds.min.y }
            for (p in box) {
                val e = p.mesh.bounds.extents; val t = minOf(e.x, e.y, e.z)
                out[p.id] = when {
                    p == bottom && e.z <= t + 0.01 -> "Drawer bottom"
                    e.x <= t + 0.01 -> "Drawer side"
                    // With a separate front fixed on, the box's own front and back are alike: its ends.
                    e.y <= t + 0.01 && dr.boxIds.size != dr.ids.size -> "Drawer box end"
                    e.y <= t + 0.01 && p.mesh.bounds.min.y <= frontY + 0.5 -> "Drawer front"
                    e.y <= t + 0.01 -> "Drawer back"
                    else -> "Drawer part"
                }
            }
        }
        val all = live.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }
        val h = all.extents.z
        // Legs first: square posts standing on the floor, at least twice as tall as they are thick (a bed's legs are
        // short). Rails are then known by the legs they join.
        val legs = HashSet<String>()
        for (p in live) {
            if (p.id in out) continue
            val e = p.mesh.bounds.extents; val dims = listOf(e.x, e.y, e.z).sorted()
            if (dims[1] < 2.5 * dims[0] && e.z >= dims[2] - 0.01 && e.z > 1.5 * dims[1] && p.mesh.bounds.min.z <= all.min.z + 1.0) {
                // One standing away from every outside edge (under a bed's centre rail) is a centre leg.
                val b = p.mesh.bounds; val m = 2 * dims[1]
                val inside = b.min.x > all.min.x + m && b.max.x < all.max.x - m && b.min.y > all.min.y + m && b.max.y < all.max.y - m
                out[p.id] = if (inside) "Centre leg" else "Leg"; legs += p.id
            }
        }
        fun touches(a: Part, o: Part) = (0..2).all { k -> o.mesh.bounds.min[k] <= a.mesh.bounds.max[k] + 1.0 && a.mesh.bounds.min[k] <= o.mesh.bounds.max[k] + 1.0 }
        for (p in live) {
            if (p.id in out) continue
            val b = p.mesh.bounds; val e = b.extents
            val dims = listOf(e.x, e.y, e.z).sorted()
            val t = dims[0]
            // A strip fixed face-on along a bigger board, most of its length (a ledger under slats or a shelf): a cleat.
            if (dims[1] < NARROW) {
                val thinAx = (0..2).minByOrNull { e[it] }!!; val longAx = (0..2).maxByOrNull { e[it] }!!
                val backer = live.firstOrNull { o -> o !== p && touches(p, o) && o.mesh.bounds.extents.let { oe -> oe[longAx] >= e[longAx] - 1.0 && oe[3 - thinAx - longAx] > 1.5 * e[3 - thinAx - longAx] } &&
                    (kotlin.math.abs(o.mesh.bounds.max[thinAx] - b.min[thinAx]) < 1.0 || kotlin.math.abs(o.mesh.bounds.min[thinAx] - b.max[thinAx]) < 1.0) }
                if (backer != null && live.count { o -> o.id in legs && touches(p, o) } >= 2) { out[p.id] = "Cleat"; continue }
            }
            // Square-ish in section and long: a leg standing up, a rail or apron lying down.
            if (dims[1] < 2.5 * t && dims[2] > 4 * dims[1]) {
                out[p.id] = if (e.z >= dims[2] - 0.01 && b.min.z <= all.min.z + 1.0) "Leg" else if (e.z >= dims[2] - 0.01) "Post" else "Rail"
                continue
            }
            if (dims[1] < 2 * t) continue
            // An upright board off the floor joining two or more legs: a rail (a bed's side rail), unless it is an apron.
            val onEdge = (e.x <= t + 0.01 || e.y <= t + 0.01) && e.z < dims[2] - 0.01
            val joinsLegs = legs.isNotEmpty() && b.min.z > all.min.z + 1.0 && live.count { o -> o.id in legs && touches(p, o) } >= 2
            // A shallow upright board high up, right under a flat part: a table's apron or a desk's rail.
            val upright = e.x <= t + 0.01 || e.y <= t + 0.01
            if (upright && e.z < 0.3 * h && b.min.z > all.min.z + 0.3 * h && live.any { o -> o !== p && o.mesh.bounds.extents.z <= minOf(o.mesh.bounds.extents.x, o.mesh.bounds.extents.y) && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 }) {
                out[p.id] = "Apron"; continue
            }
            if (onEdge && joinsLegs) {
                // Round the outside, named by the side it runs along (side rails the long way, end rails across);
                // one down the middle is the centre rail.
                val legT = live.filter { it.id in legs }.maxOf { o -> o.mesh.bounds.extents.let { minOf(it.x, it.y) } }
                val along = if (e.x >= e.y) 0 else 1; val across = 1 - along
                val outer = b.min[across] <= all.min[across] + legT + 1.0 || b.max[across] >= all.max[across] - legT - 1.0
                val longWay = if (all.extents.x >= all.extents.y) 0 else 1
                out[p.id] = if (!outer) "Centre rail" else if (along == longWay) "Side rail" else "End rail"
                continue
            }
            // Several narrow boards alike, lying flat side by side: slats (a bed's, a bench seat's).
            if (dims[1] < NARROW && e.z <= t + 0.01 && live.count { o -> o.mesh.bounds.extents.let { oe -> kotlin.math.abs(oe.x - e.x) < 0.5 && kotlin.math.abs(oe.y - e.y) < 0.5 && kotlin.math.abs(oe.z - e.z) < 0.5 } } >= 4) {
                out[p.id] = "Slat"; continue
            }
            // Narrow boards are not shelves or sides: a strip lying down is a rail, one standing up an upright.
            if (dims[1] < NARROW) {
                val kick = e.y <= t + 0.01 && b.max.z <= all.min.z + 0.2 * h && b.min.z <= all.min.z + 1.0 && b.min.y <= all.min.y + 0.25 * all.extents.y
                out[p.id] = if (kick) "Kick board" else if (e.z >= dims[2] - 0.01) "Upright" else "Rail"
                continue
            }
            out[p.id] = when {
                e.z <= t + 0.01 -> {
                    fun flat(o: Part) = o.mesh.bounds.extents.z <= minOf(o.mesh.bounds.extents.x, o.mesh.bounds.extents.y)
                    // How much of this part another flat part above (or below) it covers, seen from above.
                    fun overlapShare(o: Part): Double {
                        val ob = o.mesh.bounds
                        val ox = maxOf(0.0, minOf(ob.max.x, b.max.x) - maxOf(ob.min.x, b.min.x)); val oy = maxOf(0.0, minOf(ob.max.y, b.max.y) - maxOf(ob.min.y, b.min.y))
                        return ox * oy / (e.x * e.y)
                    }
                    val coveredAbove = live.any { o -> o !== p && flat(o) && o.mesh.bounds.min.z >= b.max.z - 0.5 && overlapShare(o) > 0.5 }
                    val coveredBelow = live.any { o -> o !== p && flat(o) && o.mesh.bounds.max.z <= b.min.z + 0.5 && overlapShare(o) > 0.3 }
                    // A bottom closes a case between its sides; a low board on a frame of legs is a shelf.
                    fun touches(o: Part) = (0..2).all { k -> o.mesh.bounds.min[k] <= b.max[k] + 1.0 && b.min[k] <= o.mesh.bounds.max[k] + 1.0 }
                    val betweenPanels = live.any { o -> o !== p && touches(o) && o.mesh.bounds.extents.let { oe -> oe.x <= minOf(oe.y, oe.z) && oe.z > 0.5 * h && oe.y >= NARROW } }
                    when {
                        // Nothing over most of it and high up: a top (a desk's top, though a small shelf rides above it).
                        !coveredAbove && b.min.z > all.min.z + 0.5 * h -> "Top"
                        !coveredBelow && b.min.z < all.min.z + 0.25 * h && betweenPanels -> "Bottom"
                        else -> "Shelf"
                    }
                }
                e.x <= t + 0.01 -> if (b.min.x <= all.min.x + 1.0 || b.max.x >= all.max.x - 1.0) "Side" else "Divider"
                e.y <= t + 0.01 -> when {
                    b.max.y >= all.max.y - 1.0 -> if (t <= 10.0) "Back panel" else "Back"
                    b.max.z <= all.min.z + 0.2 * h && b.min.z <= all.min.z + 1.0 -> "Kick board"
                    b.min.y <= all.min.y + 1.0 -> "Front"
                    // Standing in the back half, hung from the underside of a top and clear of the floor:
                    // the panel that stiffens a desk and screens the legs, which furniture makers call a modesty panel.
                    (b.min.y + b.max.y) / 2 > all.min.y + 0.5 * all.extents.y && b.min.z > all.min.z + 0.25 * h &&
                        live.any { o -> o !== p && o.mesh.bounds.extents.z <= minOf(o.mesh.bounds.extents.x, o.mesh.bounds.extents.y) && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 } -> "Modesty panel"
                    else -> "Panel"
                }
                else -> continue
            }
        }
        return out
    }
}
