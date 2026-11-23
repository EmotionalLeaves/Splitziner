package splitziner.parts

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The staves of a many-sided column (a six- or eight-sided plinth): five or more like boards standing round a
 * centre, evenly spaced, each turned to face out. They are its sides, glued up together in one go.
 */
object Staves {
    fun find(live: List<Part>): List<Part> {
        if (live.size < 5) return emptyList()
        // Alike: same volume and the same height band.
        val groups = live.groupBy { p -> Math.round(abs(p.mesh.volume) / 1000.0) to Math.round(p.mesh.bounds.min.z) * 1000 + Math.round(p.mesh.bounds.max.z) }.values
        for (g in groups) {
            if (g.size < 5) continue
            val e = g[0].mesh.bounds.extents
            if (e.z < 0.5 * maxOf(e.x, e.y)) continue
            val cx = g.sumOf { it.mesh.bounds.center.x } / g.size; val cy = g.sumOf { it.mesh.bounds.center.y } / g.size
            val r = g.map { hypot(it.mesh.bounds.center.x - cx, it.mesh.bounds.center.y - cy) }
            if (r.min() < 20.0 || r.max() - r.min() > 0.03 * r.max()) continue
            // Evenly round: the angles between neighbours all alike.
            val ang = g.map { atan2(it.mesh.bounds.center.y - cy, it.mesh.bounds.center.x - cx) }.sorted()
            val gaps = ang.indices.map { i -> if (i + 1 < ang.size) ang[i + 1] - ang[i] else ang[0] + 2 * Math.PI - ang[i] }
            if (gaps.max() - gaps.min() > Math.toRadians(3.0)) continue
            // Thin boards, not posts: a stave's volume is far less than its box's when it is turned off the axes, so
            // judge by the volume against its height and its share of the ring.
            val side = 2 * r.average() * kotlin.math.tan(Math.PI / g.size)
            val t = abs(g[0].mesh.volume) / (e.z * side)
            if (t > 0.25 * side) continue
            return g
        }
        return emptyList()
    }
}
