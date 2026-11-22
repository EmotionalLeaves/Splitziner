package splitziner.cutplan

import splitziner.dims.LengthFormat
import splitziner.parts.Part
import splitziner.parts.PartKind
import kotlin.math.abs

/**
 * Plywood and particleboard edges show their layers; makers cover the ones on view with iron-on edge banding.
 * This adds up the edges of sheet-cut panels that no other part covers, leaving out edges facing the back
 * (taken to stand against a wall) and the floor. Square panels lined up with the model
 * are measured; angled or shaped ones are left out of the sum.
 */
object EdgeBanding {
    /** Bought with this much extra for trimming the ends. */
    private const val ALLOWANCE = 1.1

    class Estimate(val lengthMm: Double, val items: List<Int>)

    fun estimate(parts: List<Part>): Estimate {
        val live = parts.filter { !it.excluded }
        val lo = live.map { it.mesh.bounds.min }; val hi = live.map { it.mesh.bounds.max }
        var total = 0.0; val items = sortedSetOf<Int>()
        for ((i, p) in live.withIndex()) {
            if (p.kind != PartKind.PANEL) continue
            val e = doubleArrayOf(hi[i].x - lo[i].x, hi[i].y - lo[i].y, hi[i].z - lo[i].z)
            // Only a plain box lined up with the axes: its box is its shape.
            if (abs(p.mesh.volume) < 0.9 * e[0] * e[1] * e[2]) continue
            val k = (0..2).minByOrNull { e[it] }!!
            val tol = maxOf(0.5, 0.2 * e[k])
            for (a in (0..2).filter { it != k }) for (side in 0..1) {
                // Edges facing the back (against a wall) or the floor are not counted.
                if ((a == 1 && side == 1) || (a == 2 && side == 0)) continue
                val along = (0..2).first { it != k && it != a }
                val face = if (side == 0) lo[i][a] else hi[i][a]
                // How much of this edge another part sits against.
                var covered = 0.0
                for (j in live.indices) {
                    if (j == i) continue
                    val touches = if (side == 0) abs(hi[j][a] - face) <= tol || (lo[j][a] < face && hi[j][a] > face) else abs(lo[j][a] - face) <= tol || (lo[j][a] < face && hi[j][a] > face)
                    if (!touches) continue
                    if (lo[j][k] > hi[i][k] - 0.1 || hi[j][k] < lo[i][k] + 0.1) continue
                    covered += maxOf(0.0, minOf(hi[i][along], hi[j][along]) - maxOf(lo[i][along], lo[j][along]))
                }
                if (covered < 0.5 * e[along]) { total += e[along]; p.item?.let { items += it } }
            }
        }
        return Estimate(total * ALLOWANCE, items.toList())
    }

    /** The shopping line, or null when no panel edge shows. */
    fun line(parts: List<Part>, fmt: LengthFormat): String? {
        val est = estimate(parts)
        if (est.lengthMm < 1.0) return null
        val len = if (fmt.isInch) "${Math.round(est.lengthMm / 304.8)} ft" else "%.1f m".format(java.util.Locale.ROOT, est.lengthMm / 1000)
        val which = if (est.items.size <= 8) " of items ${est.items.joinToString(", ")}" else ""
        return "Edge banding, if the sheets are plywood or particleboard: about $len for the edges on view$which (not the back or underneath; includes a little extra for trimming)"
    }
}
