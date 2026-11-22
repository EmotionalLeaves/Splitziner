package splitziner.check

import splitziner.parts.Part

/**
 * Adjustable shelves: a flat board between two uprights, a hair short of each, touching nothing but perhaps the
 * back. It rests on four shelf pins pushed into rows of holes in the uprights, which are hardware and not in the
 * model, so as drawn it floats.
 */
object ShelfPins {
    /** Clear space at each end of a shelf on pins, at most (mm); 1-2 mm lets it drop in and lift out. */
    private const val MAX_GAP = 4.0

    /** Pins per shelf: two in each upright, one near the front and one near the back. */
    const val PINS = 4

    /**
     * The holes, as the common 32 mm system drills them: a row near each edge, 37 mm in, holes 32 mm apart.
     * The hole size follows the pins bought; 5 mm is the most common.
     */
    const val SETBACK = 37.0
    const val PITCH = 32.0

    fun find(parts: List<Part>): List<String> = supports(parts).keys.toList()

    /** Each upright that needs pin holes, with the shelves resting on it. */
    fun uprights(parts: List<Part>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for ((shelf, ups) in supports(parts)) for (u in ups) out.getOrPut(u) { ArrayList() } += shelf
        return out
    }

    /** Each adjustable shelf, with the two uprights it rests between. */
    private fun supports(parts: List<Part>): Map<String, List<String>> {
        val live = parts.filter { !it.excluded }
        if (live.size < 3) return emptyMap()
        val b = live.map { it.mesh.bounds }
        val out = LinkedHashMap<String, List<String>>()
        for ((i, p) in live.withIndex()) {
            val e = b[i].extents
            if (e.z > 0.15 * minOf(e.x, e.y) || e.z > 40.0 || e.y < 2 * SETBACK + 20.0) continue
            // Uprights left and right, a small gap away, standing well above and below it and as deep as it.
            fun side(left: Boolean) = live.indices.firstOrNull { j ->
                j != i && b[j].min.z < b[i].min.z - 20.0 && b[j].max.z > b[i].max.z + 20.0 && b[j].min.y <= b[i].min.y + 2.0 && b[j].max.y >= b[i].max.y - 2.0 &&
                    (if (left) b[i].min.x - b[j].max.x in 0.5..MAX_GAP else b[j].min.x - b[i].max.x in 0.5..MAX_GAP)
            }
            val l = side(true) ?: continue; val r = side(false) ?: continue
            // Touching nothing, or only a back panel behind it.
            val touches = live.indices.filter { j -> j != i && (0..2).all { k -> b[j].min[k] <= b[i].max[k] + 0.05 && b[i].min[k] <= b[j].max[k] + 0.05 } }
            if (touches.any { j -> b[j].min.y < b[i].max.y - 0.05 }) continue
            out[p.id] = listOf(live[l].id, live[r].id)
        }
        return out
    }
}
