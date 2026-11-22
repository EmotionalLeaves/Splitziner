package splitziner.mesh

import splitziner.math.Vec3
import kotlin.math.abs

/** A part's working mesh, and how far any point of it may lie from the original surface (mm). */
class Simplified(val mesh: Mesh, val maxError: Double, val originalTriangles: Int)

/**
 * Reduces a mesh to about [target] triangles by merging vertices that share a cell of a grid
 * (Lindstrom's method). Each cell's new vertex is the point closest to all the flat faces that
 * meet there, so sharp corners and creases stay exactly where they were; on curved surfaces a
 * point moves at most one cell diagonal. Thin details smaller than a cell can merge away.
 */
object Simplify {
    /** Largest distance from the original surface a simplified copy may have: 0.05% of the size, at least 0.05 mm. */
    fun tolerance(m: Mesh) = tolerance(m.extents.length)
    fun tolerance(size: Double) = maxOf(0.05, 5e-4 * size)

    /** [tol] defaults to one set by the part's own size; an assembly passes one set by the whole model's. */
    fun run(m: Mesh, target: Int, tol: Double = tolerance(m)): Simplified {
        if (m.triangleCount <= target) return Simplified(m, 0.0, m.triangleCount)
        val ext = m.extents
        // A first guess from the surface area. Coarser while there are too many triangles; finer whenever the
        // result strays from the surface by more than [tol]. Accuracy wins over the triangle budget.
        var cell = maxOf(Math.sqrt(m.area / target) * 0.8, 1e-6 * maxOf(ext.x, ext.y, ext.z))
        var good: Pair<Mesh, Double>? = null
        repeat(12) {
            val r = cluster(m, cell)
            if (r.second <= tol) {
                if (good == null || r.first.triangleCount < good!!.first.triangleCount) good = r
                if (r.first.triangleCount <= target) return Simplified(r.first, r.second, m.triangleCount)
                cell *= Math.sqrt(r.first.triangleCount.toDouble() / target) * 1.05
            } else {
                good?.let { return Simplified(it.first, it.second, m.triangleCount) }
                cell *= 0.6
            }
        }
        val g = good ?: return Simplified(m, 0.0, m.triangleCount)
        return Simplified(g.first, g.second, m.triangleCount)
    }

    /**
     * The simplified mesh and how far off the original surface it is: the largest distance from a new
     * vertex to the plane of any original triangle around it. Sliding along a flat face does not count,
     * because the surface there is unchanged.
     */
    private fun cluster(m: Mesh, cell: Double): Pair<Mesh, Double> {
        val lo = m.bounds.min
        // Which way the surface faces at each vertex. Points facing different ways are never merged, so the
        // two faces of a board thinner than a cell stay apart instead of collapsing into one another.
        val vn = DoubleArray(m.vertexCount * 3)
        run {
            val fn = m.faceNormals; val fa = m.faceAreas
            for (t in 0 until m.triangleCount) for (k in 0..2) { val v = m.triangles[3 * t + k]; for (j in 0..2) vn[3 * v + j] += fn[3 * t + j] * fa[t] }
        }
        val idx = PointIndexer(maxOf(16, m.vertexCount / 4))
        val cellOf = IntArray(m.vertexCount)
        for (v in 0 until m.vertexCount) {
            // One of 26 directions: each component counts as +, - or 0. Points on the two long edges of a thin
            // board's side face lean opposite ways (front and back), so they stay apart too.
            val x = vn[3 * v]; val y = vn[3 * v + 1]; val z = vn[3 * v + 2]
            val len = Math.sqrt(x * x + y * y + z * z).let { if (it > 0) it else 1.0 }
            fun sgn(c: Double) = if (c > 0.3 * len) 2 else if (c < -0.3 * len) 0 else 1
            val facing = sgn(x) * 9 + sgn(y) * 3 + sgn(z)
            cellOf[v] = idx.idOf(Math.floor((m.vertices[3 * v] - lo.x) / cell).toLong() * 32 + facing, Math.floor((m.vertices[3 * v + 1] - lo.y) / cell).toLong(), Math.floor((m.vertices[3 * v + 2] - lo.z) / cell).toLong())
        }
        val nc = idx.size
        // Per cell: the plane quadric (upper triangle of A, then b, then weight) and the mean of its vertices.
        val q = DoubleArray(nc * 10); val mean = DoubleArray(nc * 3); val cnt = IntArray(nc)
        for (v in 0 until m.vertexCount) { val c = cellOf[v]; for (k in 0..2) mean[3 * c + k] += m.vertices[3 * v + k]; cnt[c]++ }
        for (c in 0 until nc) for (k in 0..2) mean[3 * c + k] /= cnt[c]
        val n = m.faceNormals; val a = m.faceAreas
        for (t in 0 until m.triangleCount) {
            val nx = n[3 * t]; val ny = n[3 * t + 1]; val nz = n[3 * t + 2]; val w = a[t]
            if (w <= 0) continue
            val v0 = m.triangles[3 * t]
            val d = -(nx * m.vertices[3 * v0] + ny * m.vertices[3 * v0 + 1] + nz * m.vertices[3 * v0 + 2])
            for (k in 0..2) {
                val c = cellOf[m.triangles[3 * t + k]]
                // Each plane counts once per cell it touches.
                if (k == 1 && c == cellOf[m.triangles[3 * t]]) continue
                if (k == 2 && (c == cellOf[m.triangles[3 * t]] || c == cellOf[m.triangles[3 * t + 1]])) continue
                val o = 10 * c
                q[o] += w * nx * nx; q[o + 1] += w * nx * ny; q[o + 2] += w * nx * nz
                q[o + 3] += w * ny * ny; q[o + 4] += w * ny * nz; q[o + 5] += w * nz * nz
                q[o + 6] -= w * d * nx; q[o + 7] -= w * d * ny; q[o + 8] -= w * d * nz; q[o + 9] += w
            }
        }
        val verts = DoubleArray(nc * 3)
        for (c in 0 until nc) {
            val o = 10 * c
            val mx = mean[3 * c]; val my = mean[3 * c + 1]; val mz = mean[3 * c + 2]
            // Pulling gently toward the mean picks, among the points the planes allow, the one nearest the middle:
            // the corner itself where three faces meet, a point on the crease where two do, the mean on a flat face.
            val eps = 1e-3 * maxOf(q[o] + q[o + 3] + q[o + 5], 1e-12)
            val a00 = q[o] + eps; val a01 = q[o + 1]; val a02 = q[o + 2]; val a11 = q[o + 3] + eps; val a12 = q[o + 4]; val a22 = q[o + 5] + eps
            val b0 = q[o + 6] + eps * mx; val b1 = q[o + 7] + eps * my; val b2 = q[o + 8] + eps * mz
            val det = a00 * (a11 * a22 - a12 * a12) - a01 * (a01 * a22 - a12 * a02) + a02 * (a01 * a12 - a11 * a02)
            var x = mx; var y = my; var z = mz
            if (abs(det) > 1e-30) {
                x = (b0 * (a11 * a22 - a12 * a12) - a01 * (b1 * a22 - a12 * b2) + a02 * (b1 * a12 - a11 * b2)) / det
                y = (a00 * (b1 * a22 - a12 * b2) - b0 * (a01 * a22 - a12 * a02) + a02 * (a01 * b2 - b1 * a02)) / det
                z = (a00 * (a11 * b2 - b1 * a12) - a01 * (a01 * b2 - b1 * a02) + b0 * (a01 * a12 - a11 * a02)) / det
            }
            // Never outside its own cell (with a little slack), so the error bound holds.
            val cx = Math.floor((mx - lo.x) / cell); val cy = Math.floor((my - lo.y) / cell); val cz = Math.floor((mz - lo.z) / cell)
            val s = 0.25 * cell
            verts[3 * c] = x.coerceIn(lo.x + cx * cell - s, lo.x + (cx + 1) * cell + s)
            verts[3 * c + 1] = y.coerceIn(lo.y + cy * cell - s, lo.y + (cy + 1) * cell + s)
            verts[3 * c + 2] = z.coerceIn(lo.z + cz * cell - s, lo.z + (cz + 1) * cell + s)
        }
        var err = 0.0
        for (t in 0 until m.triangleCount) {
            val nx = n[3 * t]; val ny = n[3 * t + 1]; val nz = n[3 * t + 2]
            val v0 = m.triangles[3 * t]
            val d = -(nx * m.vertices[3 * v0] + ny * m.vertices[3 * v0 + 1] + nz * m.vertices[3 * v0 + 2])
            for (k in 0..2) { val c = cellOf[m.triangles[3 * t + k]]; val e = abs(nx * verts[3 * c] + ny * verts[3 * c + 1] + nz * verts[3 * c + 2] + d); if (e > err) err = e }
        }
        // Keep each triangle whose corners land in three different cells, once.
        val seen = PointIndexer(maxOf(16, m.triangleCount / 4))
        var tris = IntArray(maxOf(16, m.triangleCount / 2)); var nt = 0
        for (t in 0 until m.triangleCount) {
            val a0 = cellOf[m.triangles[3 * t]]; val a1 = cellOf[m.triangles[3 * t + 1]]; val a2 = cellOf[m.triangles[3 * t + 2]]
            if (a0 == a1 || a1 == a2 || a0 == a2) continue
            var x = a0; var y = a1; var z = a2
            if (y < x && y < z) { val s0 = x; x = y; y = z; z = s0 } else if (z < x && z < y) { val s0 = x; x = z; z = y; y = s0 }
            val before = seen.size
            if (seen.idOf(x.toLong(), y.toLong(), z.toLong()) != before) continue
            if (3 * nt + 3 > tris.size) tris = tris.copyOf(tris.size * 2)
            tris[3 * nt] = a0; tris[3 * nt + 1] = a1; tris[3 * nt + 2] = a2; nt++
        }
        return MeshOps.removeDegenerate(Mesh(verts, tris.copyOf(3 * nt))) to err
    }
}
