package splitziner.features

import splitziner.math.Vec3
import splitziner.mesh.Mesh
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A round hole found in a part. [start] is the centre of its opening; [axis] points from there
 * into the material. [depth] is null when only one end of the hole could be found.
 */
class Hole(val start: Vec3, val axis: Vec3, val diameter: Double, val depth: Double?, val through: Boolean) {
    val end: Vec3 get() = start + axis * (depth ?: 0.0)
    val center: Vec3 get() = start + axis * ((depth ?: 0.0) / 2)

    /** 0, 1 or 2 when the hole runs along X, Y or Z; null when it is drilled at an angle. */
    val principalAxis: Int? get() = (0..2).firstOrNull { abs(axis[it]) > ALIGNED }

    companion object { const val ALIGNED = 0.9995 }
}

/**
 * Finds round holes. An STL has no circles, only many-sided polygons, so a hole is recognised by
 * its rim: a closed ring of edges around a flat face whose corners all lie on one circle, with the
 * wall behind it facing inward. The diameter is the circle through the rim's corners.
 */
object HoleFinder {
    /** Faces whose normals agree this closely (about one degree) belong to the same flat face. */
    private const val COPLANAR = 0.99985

    /** A ring with fewer corners is treated as a polygon (a hexagonal pocket), not a round hole. */
    const val MIN_SIDES = 8

    /** Corners may sit this far off the fitted circle, as a share of its radius. */
    private const val ROUND_TOL = 0.02

    private class Ring(val center: Vec3, val normal: Vec3, val radius: Double, val cap: Boolean)

    fun find(mesh: Mesh): List<Hole> {
        val tc = mesh.triangleCount
        if (tc < 4 * MIN_SIDES) return emptyList()
        val topo = mesh.topology; val n = mesh.faceNormals
        fun dot(a: Int, b: Int) = n[3 * a] * n[3 * b] + n[3 * a + 1] * n[3 * b + 1] + n[3 * a + 2] * n[3 * b + 2]

        // 1. Flat faces: grow each from a seed triangle, always comparing with the seed so a smooth curve cannot creep in.
        val region = IntArray(tc) { -1 }
        val stack = IntArray(tc)
        var regions = 0
        for (seed in 0 until tc) {
            if (region[seed] >= 0) continue
            region[seed] = regions
            var sp = 0; stack[sp++] = seed
            while (sp > 0) {
                val t = stack[--sp]
                for (k in 0..2) {
                    val e = topo.faceEdges[3 * t + k]
                    if (topo.edgeUseCount[e] != 2) continue
                    val o = if (topo.edgeFaces[2 * e] == t) topo.edgeFaces[2 * e + 1] else topo.edgeFaces[2 * e]
                    if (region[o] < 0 && dot(seed, o) > COPLANAR) { region[o] = regions; stack[sp++] = o }
                }
            }
            regions++
        }

        // 2. Each flat face's outline: the edges where it meets a different face. Stored as (face inside, edge).
        val count = IntArray(regions + 1)
        for (e in 0 until topo.edgeCount) {
            if (topo.edgeUseCount[e] != 2) continue
            val a = topo.edgeFaces[2 * e]; val b = topo.edgeFaces[2 * e + 1]
            if (region[a] != region[b]) { count[region[a] + 1]++; count[region[b] + 1]++ }
        }
        for (r in 0 until regions) count[r + 1] += count[r]
        val fill = count.copyOf()
        val bEdge = IntArray(count[regions]); val bFace = IntArray(count[regions])
        for (e in 0 until topo.edgeCount) {
            if (topo.edgeUseCount[e] != 2) continue
            val a = topo.edgeFaces[2 * e]; val b = topo.edgeFaces[2 * e + 1]
            if (region[a] != region[b]) {
                var i = fill[region[a]]++; bEdge[i] = e; bFace[i] = a
                i = fill[region[b]]++; bEdge[i] = e; bFace[i] = b
            }
        }

        val rings = ArrayList<Ring>()
        for (r in 0 until regions) {
            val lo = count[r]; val hi = count[r + 1]
            if (hi - lo < MIN_SIDES) continue
            // Outline edges at each corner. A corner where more than two meet is a pinch point; rings through it are skipped.
            val at = HashMap<Int, IntArray>()
            for (i in lo until hi) for (c in 0..1) {
                val v = topo.edges[2 * bEdge[i] + c]
                val slot = at.getOrPut(v) { intArrayOf(-1, -1, 0) }
                if (slot[2] < 2) slot[slot[2]] = i
                slot[2]++
            }
            val seen = BooleanArray(hi - lo)
            for (first in lo until hi) {
                if (seen[first - lo]) continue
                val loop = ArrayList<Int>()
                var i = first; var v = topo.edges[2 * bEdge[i]]
                var closed = false; var clean = true
                while (true) {
                    seen[i - lo] = true; loop += i
                    v = if (topo.edges[2 * bEdge[i]] == v) topo.edges[2 * bEdge[i] + 1] else topo.edges[2 * bEdge[i]]
                    val slot = at.getValue(v)
                    if (slot[2] != 2) { clean = false; break }
                    val next = if (slot[0] == i) slot[1] else slot[0]
                    if (next == first) { closed = true; break }
                    if (seen[next - lo]) { clean = false; break }
                    i = next
                }
                if (closed && clean && loop.size >= MIN_SIDES) ring(mesh, loop, bEdge, bFace)?.let { rings += it }
            }
        }
        return pair(rings)
    }

    /** Tests one closed outline: is it a circle, and does the wall behind it face inward? */
    private fun ring(mesh: Mesh, loop: List<Int>, bEdge: IntArray, bFace: IntArray): Ring? {
        val topo = mesh.topology; val n = mesh.faceNormals
        val f0 = bFace[loop[0]]
        val normal = Vec3(n[3 * f0], n[3 * f0 + 1], n[3 * f0 + 2])
        // Each corner once: the first end of every edge would repeat some, so collect distinct ones.
        val ids = LinkedHashSet<Int>()
        for (i in loop) { ids += topo.edges[2 * bEdge[i]]; ids += topo.edges[2 * bEdge[i] + 1] }
        if (ids.size != loop.size) return null
        val pts = ids.map { mesh.vertex(it) }
        val p0 = pts[0]
        val u = (pts[1] - p0).let { it - normal * (it dot normal) }.normalized()
        val w = normal cross u
        // Least-squares circle (Kasa): x^2 + y^2 + a x + b y + c = 0.
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0; var sxz = 0.0; var syz = 0.0; var sz = 0.0
        val xy = pts.map { val d = it - p0; doubleArrayOf(d dot u, d dot w) }
        for (p in xy) {
            val z = p[0] * p[0] + p[1] * p[1]
            sxx += p[0] * p[0]; sxy += p[0] * p[1]; syy += p[1] * p[1]; sx += p[0]; sy += p[1]; sxz += p[0] * z; syz += p[1] * z; sz += z
        }
        val m = pts.size.toDouble()
        val sol = solve3(doubleArrayOf(sxx, sxy, sx, sxy, syy, sy, sx, sy, m), doubleArrayOf(-sxz, -syz, -sz)) ?: return null
        val cx = -sol[0] / 2; val cy = -sol[1] / 2
        val r2 = cx * cx + cy * cy - sol[2]
        if (r2 <= 0) return null
        val r = sqrt(r2)
        val tol = maxOf(ROUND_TOL * r, 0.01)
        for (p in xy) if (abs(sqrt((p[0] - cx) * (p[0] - cx) + (p[1] - cy) * (p[1] - cy)) - r) > tol) return null
        val center = p0 + u * cx + w * cy

        var perimeter = 0.0; var inward = 0; var capVotes = 0
        for (i in loop) {
            val e = bEdge[i]
            val a = mesh.vertex(topo.edges[2 * e]); val b = mesh.vertex(topo.edges[2 * e + 1])
            val len = (b - a).length
            // Long straight runs mean a polygon that merely has its corners on a circle (a square, a hexagon).
            if (len > 0.8 * r) return null
            perimeter += len
            val mid = (a + b) * 0.5; val radial = mid - center
            val inside = bFace[i]
            val wall = if (topo.edgeFaces[2 * e] == inside) topo.edgeFaces[2 * e + 1] else topo.edgeFaces[2 * e]
            if (n[3 * wall] * radial.x + n[3 * wall + 1] * radial.y + n[3 * wall + 2] * radial.z < 0) inward++
            // Which side of the rim the flat face lies on: outside it (the surface the hole is drilled into) or inside (the hole's flat bottom).
            var third = Vec3.ZERO
            for (k in 0..2) { val v = mesh.triangles[3 * inside + k]; if (v != topo.edges[2 * e] && v != topo.edges[2 * e + 1]) third = mesh.vertex(v) }
            if ((third - mid) dot radial < 0) capVotes++
        }
        if (perimeter < 0.9 * 2 * Math.PI * r || perimeter > 1.02 * 2 * Math.PI * r) return null
        if (inward < 0.9 * loop.size) return null
        return Ring(center, normal, r, capVotes > loop.size / 2)
    }

    private fun solve3(a: DoubleArray, b: DoubleArray): DoubleArray? {
        fun det(m: DoubleArray) = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        val d = det(a)
        if (abs(d) < 1e-12) return null
        return DoubleArray(3) { c -> val m = a.copyOf(); for (r in 0..2) m[3 * r + c] = b[r]; det(m) / d }
    }

    /** Joins the rings that lie on one axis into holes: an opening and what it leads to. */
    private fun pair(rings: List<Ring>): List<Hole> {
        class Line(val dir: Vec3, val foot: Vec3, val radius: Double) { val members = ArrayList<Ring>() }
        val lines = ArrayList<Line>()
        for (g in rings) {
            // One direction for both ends of a hole: flip so the largest component is positive.
            val k = (0..2).maxByOrNull { abs(g.normal[it]) }!!
            val d = if (g.normal[k] < 0) -g.normal else g.normal
            val foot = g.center - d * (g.center dot d)
            val tol = ROUND_TOL * g.radius + 0.05
            val line = lines.firstOrNull { abs(it.radius - g.radius) <= tol && (it.dir dot d) > Hole.ALIGNED && (it.foot - foot).length <= tol }
                ?: Line(d, foot, g.radius).also { lines += it }
            line.members += g
        }
        val out = ArrayList<Hole>()
        for (line in lines) {
            val d = line.dir
            // The hole lies on this side of each ring: below a rim (against the face's normal), above a flat bottom.
            fun side(g: Ring): Double { val s = if ((g.normal dot d) > 0) 1.0 else -1.0; return if (g.cap) s else -s }
            val sorted = line.members.sortedBy { it.center dot d }
            var i = 0
            while (i < sorted.size) {
                val a = sorted[i]; val b = sorted.getOrNull(i + 1)
                if (b != null && side(a) > 0 && side(b) < 0) {
                    i += 2
                    if (a.cap && b.cap) continue                       // a closed pocket inside the material; nothing to drill
                    val open = if (a.cap) b else if (b.cap) a else b   // through holes are described from their upper end
                    val other = if (open === a) b else a
                    val depth = (other.center - open.center).length
                    if (depth < 1e-6) continue
                    out += Hole(open.center, (other.center - open.center) / depth, 2 * line.radius, depth, !a.cap && !b.cap)
                } else {
                    i++
                    if (!a.cap) out += Hole(a.center, d * side(a), 2 * line.radius, null, false)
                }
            }
        }
        return out
    }
}
