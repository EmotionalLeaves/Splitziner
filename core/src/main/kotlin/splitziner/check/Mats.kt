package splitziner.check

import splitziner.parts.Part

/**
 * A mat in a picture frame: a thin sheet of mat board lying flat with one window cut in it. Bought cut, or cut with a
 * bevel cutter, not made in the workshop, so it is kept out of the wood lists like a frame's glass.
 */
object Mats {
    /** Mat board is about 1/16 in (1.5 mm) thick; anything over this (mm) is not one. */
    private const val MAX_BOARD = 3.2

    /** A mat found: its id, its outside size and its window's size (mm), across and up. */
    class Mat(val id: String, val w: Double, val h: Double, val ww: Double, val wh: Double)

    fun find(parts: List<Part>): List<Mat> {
        val live = parts.filter { !it.excluded }
        if (live.size < 5) return emptyList()
        return live.mapNotNull { p ->
            val e = p.mesh.bounds.extents
            if (e.z > MAX_BOARD || e.z > 0.05 * minOf(e.x, e.y)) return@mapNotNull null
            val pr = runCatching { splitziner.edit.Prisms.of(p.mesh) }.getOrNull() ?: return@mapNotNull null
            val g = pr.outline as? org.locationtech.jts.geom.Polygon ?: return@mapNotNull null
            if (g.numInteriorRing != 1) return@mapNotNull null
            val hole = g.getInteriorRingN(0).envelopeInternal
            // Said smaller side first, outside and window alike (the outline's own axes may be either way round).
            Mat(p.id, minOf(e.x, e.y), maxOf(e.x, e.y), minOf(hole.width, hole.height), maxOf(hole.width, hole.height))
        }
    }
}
