package splitziner.parts

/**
 * Plain names for parts that have none of their own ("Side", "Shelf", "Door", "Drawer bottom"), read from where
 * each part sits and which way it lies. A guess, and only a label: it changes nothing about how a part is made,
 * and a name from the file or from the maker always wins.
 */
object Roles {
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
                    e.y <= t + 0.01 && p.mesh.bounds.min.y <= frontY + 0.5 -> if (dr.boxIds.size == dr.ids.size) "Drawer front" else "Drawer box front"
                    e.y <= t + 0.01 -> "Drawer back"
                    else -> "Drawer part"
                }
            }
        }
        val all = live.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }
        val h = all.extents.z
        for (p in live) {
            if (p.id in out) continue
            val b = p.mesh.bounds; val e = b.extents
            val dims = listOf(e.x, e.y, e.z).sorted()
            val t = dims[0]
            // Square-ish in section and long: a leg standing up, a rail or apron lying down.
            if (dims[1] < 2.5 * t && dims[2] > 4 * dims[1]) {
                out[p.id] = if (e.z >= dims[2] - 0.01 && b.min.z <= all.min.z + 1.0) "Leg" else if (e.z >= dims[2] - 0.01) "Post" else "Rail"
                continue
            }
            if (dims[1] < 2 * t) continue
            // A shallow upright board high up, right under a flat part: a table's apron or a desk's rail.
            val upright = e.x <= t + 0.01 || e.y <= t + 0.01
            if (upright && e.z < 0.3 * h && b.min.z > all.min.z + 0.3 * h && live.any { o -> o !== p && o.mesh.bounds.extents.z <= minOf(o.mesh.bounds.extents.x, o.mesh.bounds.extents.y) && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 }) {
                out[p.id] = "Apron"; continue
            }
            out[p.id] = when {
                e.z <= t + 0.01 -> when {
                    b.max.z >= all.max.z - 1.0 -> "Top"
                    live.none { o -> o !== p && o.mesh.bounds.extents.z <= minOf(o.mesh.bounds.extents.x, o.mesh.bounds.extents.y) && o.mesh.bounds.max.z <= b.min.z + 0.5 && o.mesh.bounds.extents.x * o.mesh.bounds.extents.y > 0.3 * e.x * e.y } -> "Bottom"
                    else -> "Shelf"
                }
                e.x <= t + 0.01 -> if (b.min.x <= all.min.x + 1.0 || b.max.x >= all.max.x - 1.0) "Side" else "Divider"
                e.y <= t + 0.01 -> when {
                    b.max.y >= all.max.y - 1.0 -> if (t <= 10.0) "Back panel" else "Back"
                    b.max.z <= all.min.z + 0.2 * h && b.min.z <= all.min.z + 1.0 -> "Kick board"
                    b.min.y <= all.min.y + 1.0 -> "Front"
                    else -> "Panel"
                }
                else -> continue
            }
        }
        return out
    }
}
