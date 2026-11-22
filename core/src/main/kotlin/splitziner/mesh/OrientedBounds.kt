package splitziner.mesh

import splitziner.math.Vec2
import splitziner.math.Vec3
import kotlin.math.abs

/** A box at any orientation: [extents] measured along [axes], centred on [center]. */
class OrientedBox(val extents: Vec3, val center: Vec3, val axes: List<Vec3>) {
    val volume: Double get() = extents.x * extents.y * extents.z
}

/**
 * Smallest box around a mesh among a few well-chosen orientations: the world axes, and boxes
 * standing on each of the mesh's three largest flat faces. Boards and panels are bounded by
 * their own faces, so one of these is the true stock box.
 */
object OrientedBounds {
    fun of(mesh: Mesh): OrientedBox {
        val b = mesh.bounds
        var best = OrientedBox(b.extents, b.center, listOf(Vec3.axis(0), Vec3.axis(1), Vec3.axis(2)))
        for (n in largestFaceNormals(mesh, 3)) {
            val c = boxOn(mesh, n)
            if (c.volume < best.volume * (1 - 1e-9)) best = c
        }
        return best
    }

    private fun largestFaceNormals(mesh: Mesh, count: Int): List<Vec3> {
        val area = HashMap<Long, Double>()
        val sum = HashMap<Long, Vec3>()
        val n = mesh.faceNormals; val a = mesh.faceAreas
        for (t in 0 until mesh.triangleCount) {
            if (a[t] <= 0) continue
            var v = Vec3(n[3 * t], n[3 * t + 1], n[3 * t + 2])
            // A face and its opposite face describe the same axis.
            val lead = if (abs(v.x) > 1e-6) v.x else if (abs(v.y) > 1e-6) v.y else v.z
            if (lead < 0) v = -v
            // Buckets of about half a degree.
            val key = (Math.round(v.x * 100) + 200) * 1_000_000L + (Math.round(v.y * 100) + 200) * 1_000L + (Math.round(v.z * 100) + 200)
            area[key] = (area[key] ?: 0.0) + a[t]
            sum[key] = (sum[key] ?: Vec3.ZERO) + v * a[t]
        }
        return area.entries.sortedByDescending { it.value }.take(count).map { sum[it.key]!!.normalized() }
    }

    private fun boxOn(mesh: Mesh, n: Vec3): OrientedBox {
        val helper = if (abs(n.x) < 0.9) Vec3.axis(0) else Vec3.axis(1)
        val u0 = (helper cross n).normalized()
        val v0 = n cross u0
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        val pts = ArrayList<Vec2>(mesh.vertexCount)
        for (i in 0 until mesh.vertexCount) {
            val p = mesh.vertex(i)
            val h = p dot n
            if (h < lo) lo = h; if (h > hi) hi = h
            pts += Vec2(p dot u0, p dot v0)
        }
        val hull = convexHull(pts)
        var bestArea = Double.POSITIVE_INFINITY
        var bu = Vec2(1.0, 0.0); var bw = 0.0; var bh = 0.0; var cu = 0.0; var cv = 0.0
        for (i in hull.indices) {
            val d = (hull[(i + 1) % hull.size] - hull[i]).normalized()
            if (d.length == 0.0) continue
            val e = d.perp()
            var a0 = Double.POSITIVE_INFINITY; var a1 = Double.NEGATIVE_INFINITY; var b0 = a0; var b1 = a1
            for (p in hull) { val s = p dot d; val t = p dot e; if (s < a0) a0 = s; if (s > a1) a1 = s; if (t < b0) b0 = t; if (t > b1) b1 = t }
            val area = (a1 - a0) * (b1 - b0)
            if (area < bestArea) { bestArea = area; bu = d; bw = a1 - a0; bh = b1 - b0; cu = (a0 + a1) / 2; cv = (b0 + b1) / 2 }
        }
        val ax1 = u0 * bu.x + v0 * bu.y
        val ax2 = u0 * (-bu.y) + v0 * bu.x
        val center = n * ((lo + hi) / 2) + ax1 * cu + ax2 * cv
        return OrientedBox(Vec3(hi - lo, bw, bh), center, listOf(n, ax1, ax2))
    }

    /** Andrew's monotone chain. */
    fun convexHull(points: List<Vec2>): List<Vec2> {
        val p = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (p.size < 3) return p
        fun cross(o: Vec2, a: Vec2, b: Vec2) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val h = ArrayList<Vec2>()
        for (q in p) { while (h.size >= 2 && cross(h[h.size - 2], h[h.size - 1], q) <= 0) h.removeAt(h.size - 1); h += q }
        val lower = h.size + 1
        for (q in p.reversed().drop(1)) { while (h.size >= lower && cross(h[h.size - 2], h[h.size - 1], q) <= 0) h.removeAt(h.size - 1); h += q }
        h.removeAt(h.size - 1)
        return h
    }
}
