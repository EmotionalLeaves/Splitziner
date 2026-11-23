package splitziner.check

import splitziner.parts.Part

/**
 * A clear cover set on top of a piece (a plinth's acrylic vitrine): five thin sheets, four walls and a lid, standing
 * on the piece's top. It is made by a plastics shop, not in the workshop, so it is kept out of the wood lists.
 */
object Covers {
    /** Thicker than this (mm, 1/4 in and a hair) a sheet is not a cover's acrylic. */
    private const val MAX_SHEET = 6.5

    /** Each sheet is at least this big across (mm), so small trim is never taken for a cover. */
    private const val MIN_SIZE = 50.0

    /** The ids of a cover's five sheets, or empty when there is none. */
    fun find(parts: List<Part>): List<String> {
        val live = parts.filter { !it.excluded }
        val thin = live.filter { p -> val e = p.mesh.bounds.extents; listOf(e.x, e.y, e.z).sorted().let { it[0] <= MAX_SHEET && it[1] >= MIN_SIZE } }
        if (thin.size < 5) return emptyList()
        val rest0 = live - thin.toSet()
        if (rest0.isEmpty()) return emptyList()
        val top = rest0.maxOf { it.mesh.bounds.max.z }
        // Walls standing on the top, a lid lying across them.
        val walls = thin.filter { p -> val e = p.mesh.bounds.extents; e.z > minOf(e.x, e.y) && kotlin.math.abs(p.mesh.bounds.min.z - top) < 0.5 }
        if (walls.size != 4) return emptyList()
        val wallTop = walls.maxOf { it.mesh.bounds.max.z }
        if (walls.any { kotlin.math.abs(it.mesh.bounds.max.z - wallTop) > 0.5 }) return emptyList()
        val lid = thin.filter { p -> val e = p.mesh.bounds.extents; e.z <= minOf(e.x, e.y) && kotlin.math.abs(p.mesh.bounds.min.z - wallTop) < 0.5 }
        if (lid.size != 1) return emptyList()
        val box = (walls + lid).map { it.mesh.bounds }.reduce { a, b -> a.union(b) }
        // Over the piece, not hanging off it: inside what it stands on, give or take a hair.
        val under = rest0.filter { kotlin.math.abs(it.mesh.bounds.max.z - top) < 0.5 }.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }
        if (box.min.x < under.min.x - 1.0 || box.max.x > under.max.x + 1.0 || box.min.y < under.min.y - 1.0 || box.max.y > under.max.y + 1.0) return emptyList()
        // The walls make a ring: two along X at the front and back, two along Y at the sides.
        val alongX = walls.filter { it.mesh.bounds.extents.x > it.mesh.bounds.extents.y }
        if (alongX.size != 2) return emptyList()
        return (walls + lid).map { it.id }
    }
}
