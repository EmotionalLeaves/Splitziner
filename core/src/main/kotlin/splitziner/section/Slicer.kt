package splitziner.section

import splitziner.math.Vec2
import splitziner.math.Vec3
import splitziner.mesh.Mesh
import kotlin.math.abs

/** Cuts meshes with planes. */
object Slicer {
    /**
     * The 3D segments where the mesh crosses the plane through [origin] with normal [n],
     * as x0,y0,z0,x1,y1,z1 per segment. The plane is nudged back slightly so it never
     * lies exactly on a face, which would give ragged results.
     */
    fun sectionSegments(mesh: Mesh, origin: Vec3, n: Vec3): DoubleArray {
        val nn = n.normalized()
        val d0 = (origin dot nn) - 1e-5 * mesh.scale
        val out = ArrayList<Double>()
        val v = mesh.vertices
        val dist = DoubleArray(mesh.vertexCount) { v[3 * it] * nn.x + v[3 * it + 1] * nn.y + v[3 * it + 2] * nn.z - d0 }
        val pts = DoubleArray(6)
        for (t in 0 until mesh.triangleCount) {
            var c = 0
            for (k in 0..2) {
                val a = mesh.triangles[3 * t + k]; val b = mesh.triangles[3 * t + (k + 1) % 3]
                val da = dist[a]; val db = dist[b]
                if ((da < 0) == (db < 0)) continue
                val s = da / (da - db)
                if (c < 2) for (j in 0..2) pts[3 * c + j] = v[3 * a + j] + (v[3 * b + j] - v[3 * a + j]) * s
                c++
            }
            if (c == 2) for (j in 0 until 6) out += pts[j]
        }
        return out.toDoubleArray()
    }

    /** Projects section segments into the plane's own 2D frame and chains them into closed loops. */
    fun sectionLoops(mesh: Mesh, origin: Vec3, n: Vec3): List<List<Vec2>> {
        val nn = n.normalized()
        val helper = if (abs(nn.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val x = (helper cross nn).normalized()
        val y = nn cross x
        return chainLoops(sectionSegments(mesh, origin, nn), x, y, 1e-6 * mesh.scale)
    }

    fun chainLoops(segs: DoubleArray, x: Vec3, y: Vec3, tol: Double): List<List<Vec2>> {
        val n = segs.size / 6
        if (n == 0) return emptyList()
        val q = if (tol > 0) tol * 10 else 1e-9
        fun key(p: Vec2) = (Math.round(p.x / q) * 73856093L) xor (Math.round(p.y / q) * 19349663L)
        val a = ArrayList<Vec2>(n); val b = ArrayList<Vec2>(n)
        for (i in 0 until n) {
            val p = Vec3(segs[6 * i], segs[6 * i + 1], segs[6 * i + 2]); val r = Vec3(segs[6 * i + 3], segs[6 * i + 4], segs[6 * i + 5])
            a += Vec2(p dot x, p dot y); b += Vec2(r dot x, r dot y)
        }
        val ends = HashMap<Long, MutableList<Int>>()
        for (i in 0 until n) { ends.getOrPut(key(a[i])) { ArrayList() } += i; ends.getOrPut(key(b[i])) { ArrayList() } += i }
        val used = BooleanArray(n)
        val loops = ArrayList<List<Vec2>>()
        for (s in 0 until n) {
            if (used[s]) continue
            used[s] = true
            val loop = ArrayList<Vec2>(); loop += a[s]
            var cur = b[s]
            var guard = 0
            while (guard++ <= n) {
                loop += cur
                val next = ends[key(cur)]?.firstOrNull { !used[it] } ?: break
                used[next] = true
                cur = if (key(a[next]) == key(cur)) b[next] else a[next]
            }
            if (loop.size >= 4 && key(loop.first()) == key(loop.last())) loops += loop.dropLast(1)
        }
        return loops
    }

    fun loopArea(loop: List<Vec2>): Double {
        var s = 0.0
        for (i in loop.indices) { val p = loop[i]; val r = loop[(i + 1) % loop.size]; s += p.x * r.y - r.x * p.y }
        return s / 2
    }
}
