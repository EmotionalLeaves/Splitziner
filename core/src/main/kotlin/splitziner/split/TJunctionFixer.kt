package splitziner.split

import splitziner.math.Vec3
import splitziner.mesh.Mesh
import splitziner.mesh.MeshOps
import splitziner.mesh.edgeKey

/**
 * Repairs T-junctions: places where one triangle's edge runs through another triangle's corner,
 * so the two surfaces meet in space but share no edge. Each such edge is split at the corner.
 *
 * Only open edges (used by a single triangle) can be part of a T-junction, so only those are tested.
 */
object TJunctionFixer {
    fun fix(mesh: Mesh): Mesh {
        val topo = mesh.topology
        val tol = 1e-6 * mesh.scale
        val openEdges = (0 until topo.edgeCount).filter { topo.edgeUseCount[it] != 2 }
        if (openEdges.isEmpty()) return mesh
        val openVerts = openEdges.flatMap { listOf(topo.edges[2 * it], topo.edges[2 * it + 1]) }.distinct()

        // Uniform grid over the open vertices so each edge only looks at vertices near it.
        val b = mesh.bounds
        val cell = maxOf(mesh.scale / 64, tol * 10)
        fun cellOf(v: Double, lo: Double) = Math.floor((v - lo) / cell).toInt()
        val grid = HashMap<Long, MutableList<Int>>()
        fun gkey(x: Int, y: Int, z: Int) = (x.toLong() and 0x1fffff) or ((y.toLong() and 0x1fffff) shl 21) or ((z.toLong() and 0x1fffff) shl 42)
        for (v in openVerts) {
            val p = mesh.vertex(v)
            grid.getOrPut(gkey(cellOf(p.x, b.min.x), cellOf(p.y, b.min.y), cellOf(p.z, b.min.z))) { ArrayList() } += v
        }

        val splits = HashMap<Long, List<Int>>()
        for (e in openEdges) {
            val ia = topo.edges[2 * e]; val ib = topo.edges[2 * e + 1]
            val a = mesh.vertex(ia); val bb = mesh.vertex(ib)
            val len = (bb - a).length
            if (len <= tol) continue
            val u = (bb - a) / len
            val on = ArrayList<Pair<Double, Int>>()
            val x0 = cellOf(minOf(a.x, bb.x) - tol, b.min.x); val x1 = cellOf(maxOf(a.x, bb.x) + tol, b.min.x)
            val y0 = cellOf(minOf(a.y, bb.y) - tol, b.min.y); val y1 = cellOf(maxOf(a.y, bb.y) + tol, b.min.y)
            val z0 = cellOf(minOf(a.z, bb.z) - tol, b.min.z); val z1 = cellOf(maxOf(a.z, bb.z) + tol, b.min.z)
            for (x in x0..x1) for (y in y0..y1) for (z in z0..z1) {
                for (v in grid[gkey(x, y, z)] ?: continue) {
                    if (v == ia || v == ib) continue
                    val p = mesh.vertex(v)
                    val t = (p - a) dot u
                    if (t <= tol || t >= len - tol) continue
                    if ((p - a - u * t).length < tol) on += t to v
                }
            }
            if (on.isNotEmpty()) splits[edgeKey(ia, ib)] = on.sortedBy { it.first }.map { it.second }.distinct()
        }
        if (splits.isEmpty()) return mesh

        val verts = ArrayList<Double>(mesh.vertices.size + 64).apply { for (d in mesh.vertices) add(d) }
        val tris = ArrayList<Int>(mesh.triangles.size + 64)
        for (t in 0 until mesh.triangleCount) {
            val f = intArrayOf(mesh.triangles[3 * t], mesh.triangles[3 * t + 1], mesh.triangles[3 * t + 2])
            val poly = ArrayList<Int>(6)
            for (k in 0..2) {
                val a = f[k]; val c = f[(k + 1) % 3]
                poly += a
                val s = splits[edgeKey(a, c)] ?: continue
                // Split lists are stored from the lower vertex id to the higher one.
                poly += if (a < c) s else s.reversed()
            }
            if (poly.size == 3) { tris += f[0]; tris += f[1]; tris += f[2]; continue }
            // Fan from the triangle's centre: every split piece of the boundary keeps its own edge.
            val centre: Vec3 = (mesh.vertex(f[0]) + mesh.vertex(f[1]) + mesh.vertex(f[2])) / 3.0
            val ci = verts.size / 3
            verts += centre.x; verts += centre.y; verts += centre.z
            for (i in poly.indices) { tris += poly[i]; tris += poly[(i + 1) % poly.size]; tris += ci }
        }
        return MeshOps.removeDegenerate(Mesh(verts.toDoubleArray(), tris.toIntArray()))
    }
}
