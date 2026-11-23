package splitziner.check

import splitziner.parts.Part
import splitziner.parts.PartKind

/** Pegs: dowels with one end glued into a hole drilled in a board (a coat rack's pegs, a peg rail's). */
object Pegs {
    /** At most this share of a peg's length may be sunk in the board: past it, it is a rod through something. */
    private const val MAX_SUNK = 0.5

    /** The board a rod's end is sunk into, or null when it is not a peg. */
    fun host(r: Part, live: List<Part>): Part? {
        if (r.kind != PartKind.ROD && r.name != "Rod" && r.name != "Peg") return null
        val rb = r.mesh.bounds
        val len = maxOf(rb.extents.x, rb.extents.y, rb.extents.z)
        return live.filter { o -> o !== r && o.kind != PartKind.ROD }.firstOrNull { o ->
            val ob = o.mesh.bounds
            val ov = (0..2).map { k -> minOf(rb.max[k], ob.max[k]) - maxOf(rb.min[k], ob.min[k]) }
            if (ov.any { it <= 0.5 }) return@firstOrNull false
            // Sunk along its own length by less than half of it, and wholly inside the board the other two ways.
            val ax = (0..2).maxByOrNull { rb.extents[it] }!!
            ov[ax] <= MAX_SUNK * len && (0..2).filter { it != ax }.all { k -> rb.min[k] >= ob.min[k] - 0.5 && rb.max[k] <= ob.max[k] + 0.5 }
        }
    }

    fun ids(live: List<Part>): Set<String> = live.filter { host(it, live) != null }.map { it.id }.toSet()
}
