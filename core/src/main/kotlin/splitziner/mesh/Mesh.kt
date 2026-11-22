package splitziner.mesh

import splitziner.math.Aabb
import splitziner.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Indexed triangle mesh, immutable after construction.
 * [vertices] is x0,y0,z0,x1,... and [triangles] is a0,b0,c0,a1,... wound counter-clockwise seen from outside.
 */
class Mesh(val vertices: DoubleArray, val triangles: IntArray) {
    val vertexCount: Int get() = vertices.size / 3
    val triangleCount: Int get() = triangles.size / 3

    val bounds: Aabb by lazy { Aabb.of(vertices) }
    val extents: Vec3 get() = bounds.extents

    /** Length of the bounding-box diagonal; the reference length for relative tolerances. */
    val scale: Double get() = bounds.diagonal

    fun vertex(i: Int) = Vec3(vertices[3 * i], vertices[3 * i + 1], vertices[3 * i + 2])

    /** Unit normal per triangle (zero for degenerate triangles), as nx,ny,nz triples. */
    val faceNormals: DoubleArray by lazy {
        val n = DoubleArray(triangles.size)
        val a = faceAreas
        for (t in 0 until triangleCount) {
            val c = rawCross(t)
            val l = 2 * a[t]
            if (l > 0) { n[3 * t] = c.x / l; n[3 * t + 1] = c.y / l; n[3 * t + 2] = c.z / l }
        }
        n
    }

    val faceAreas: DoubleArray by lazy { DoubleArray(triangleCount) { rawCross(it).length / 2 } }

    /** Centroid of each triangle, as x,y,z triples. */
    val faceCenters: DoubleArray by lazy {
        val c = DoubleArray(triangles.size)
        for (t in 0 until triangleCount) for (k in 0..2) {
            c[3 * t + k] = (vertices[3 * triangles[3 * t] + k] + vertices[3 * triangles[3 * t + 1] + k] +
                vertices[3 * triangles[3 * t + 2] + k]) / 3
        }
        c
    }

    val area: Double by lazy { faceAreas.sum() }

    /** Signed volume: positive when the triangles are wound outward. Only meaningful for closed meshes. */
    val volume: Double by lazy {
        // Measured from the bounds centre so large coordinates do not cost precision.
        val o = bounds.center
        var s = 0.0
        for (t in 0 until triangleCount) {
            val a = vertex(triangles[3 * t]) - o; val b = vertex(triangles[3 * t + 1]) - o; val c = vertex(triangles[3 * t + 2]) - o
            s += a dot (b cross c)
        }
        s / 6
    }

    val topology: Topology by lazy { Topology.build(this) }

    /** True when every edge is shared by exactly two triangles. */
    val isWatertight: Boolean by lazy { triangleCount >= 4 && topology.edgeUseCount.all { it == 2 } }

    val bvh: Bvh by lazy { Bvh(this) }

    private fun rawCross(t: Int): Vec3 {
        val a = vertex(triangles[3 * t]); val b = vertex(triangles[3 * t + 1]); val c = vertex(triangles[3 * t + 2])
        return (b - a) cross (c - a)
    }
}

/** Unique undirected edges of a mesh and the triangles that use them. */
class Topology(
    /** v0,v1 pairs with v0 < v1. */
    val edges: IntArray,
    /** Per edge: the first two triangles using it; -1 where there is no second one. */
    val edgeFaces: IntArray,
    /** Per edge: how many triangles use it (1 = boundary, 2 = manifold, more = non-manifold). */
    val edgeUseCount: IntArray,
    /** Per triangle: the indices of its three edges (ab, bc, ca). */
    val faceEdges: IntArray,
) {
    val edgeCount: Int get() = edgeUseCount.size

    companion object {
        fun build(m: Mesh): Topology {
            val tc = m.triangleCount
            val map = LongIntMap(tc * 3 / 2 + 16)
            var count = IntArray(tc * 3 / 2 + 8)
            var edges = IntArray(count.size * 2)
            var faces = IntArray(count.size * 2)
            val faceEdges = IntArray(tc * 3)
            var n = 0
            for (t in 0 until tc) for (k in 0..2) {
                val a = m.triangles[3 * t + k]; val b = m.triangles[3 * t + (k + 1) % 3]
                val key = edgeKey(a, b)
                var e = map.get(key)
                if (e < 0) {
                    e = n++
                    map.put(key, e)
                    if (e >= count.size) { count = count.copyOf(count.size * 2); edges = edges.copyOf(edges.size * 2); faces = faces.copyOf(faces.size * 2) }
                    edges[2 * e] = minOf(a, b); edges[2 * e + 1] = maxOf(a, b)
                    faces[2 * e] = t; faces[2 * e + 1] = -1
                } else if (count[e] == 1) faces[2 * e + 1] = t
                count[e]++
                faceEdges[3 * t + k] = e
            }
            return Topology(edges.copyOf(2 * n), faces.copyOf(2 * n), count.copyOf(n), faceEdges)
        }
    }
}

object MeshOps {
    /** Builds an indexed mesh from unindexed triangles (9 doubles each), merging vertices on a grid of [cell]. */
    fun fromSoup(soup: DoubleArray, cell: Double): Mesh {
        val nT = soup.size / 9
        val idx = PointIndexer(nT)
        val tris = IntArray(nT * 3)
        var verts = DoubleArray(maxOf(nT, 8) * 3)
        for (i in 0 until nT * 3) {
            val x = soup[3 * i]; val y = soup[3 * i + 1]; val z = soup[3 * i + 2]
            val before = idx.size
            val id = idx.idOf(Math.round(x / cell), Math.round(y / cell), Math.round(z / cell))
            if (id == before) {
                if (3 * id + 3 > verts.size) verts = verts.copyOf(verts.size * 2)
                verts[3 * id] = x; verts[3 * id + 1] = y; verts[3 * id + 2] = z
            }
            tris[i] = id
        }
        return removeDegenerate(Mesh(verts.copyOf(idx.size * 3), tris))
    }

    /** Re-merges vertices closer than [tol] and drops triangles that collapse. */
    fun mergeVertices(m: Mesh, tol: Double): Mesh {
        val cell = if (tol > 0) tol else 1e-12
        val idx = PointIndexer(m.vertexCount)
        val remap = IntArray(m.vertexCount)
        var verts = DoubleArray(m.vertices.size)
        for (v in 0 until m.vertexCount) {
            val before = idx.size
            val id = idx.idOf(Math.round(m.vertices[3 * v] / cell), Math.round(m.vertices[3 * v + 1] / cell), Math.round(m.vertices[3 * v + 2] / cell))
            if (id == before) for (k in 0..2) verts[3 * id + k] = m.vertices[3 * v + k]
            remap[v] = id
        }
        verts = verts.copyOf(idx.size * 3)
        return removeDegenerate(Mesh(verts, IntArray(m.triangles.size) { remap[m.triangles[it]] }))
    }

    /** Drops triangles that use a vertex twice, and vertices no triangle uses. */
    fun removeDegenerate(m: Mesh): Mesh {
        var keep = 0
        val tris = IntArray(m.triangles.size)
        for (t in 0 until m.triangleCount) {
            val a = m.triangles[3 * t]; val b = m.triangles[3 * t + 1]; val c = m.triangles[3 * t + 2]
            if (a == b || b == c || c == a) continue
            tris[3 * keep] = a; tris[3 * keep + 1] = b; tris[3 * keep + 2] = c; keep++
        }
        val used = IntArray(m.vertexCount) { -1 }
        var nv = 0
        for (i in 0 until keep * 3) { val v = tris[i]; if (used[v] < 0) used[v] = nv++; }
        if (keep == m.triangleCount && nv == m.vertexCount) return m
        val verts = DoubleArray(nv * 3)
        for (v in 0 until m.vertexCount) if (used[v] >= 0) for (k in 0..2) verts[3 * used[v] + k] = m.vertices[3 * v + k]
        return Mesh(verts, IntArray(keep * 3) { used[tris[it]] })
    }

    fun scaled(m: Mesh, f: Double): Mesh = if (f == 1.0) m else Mesh(DoubleArray(m.vertices.size) { m.vertices[it] * f }, m.triangles)

    fun translated(m: Mesh, o: Vec3): Mesh =
        Mesh(DoubleArray(m.vertices.size) { m.vertices[it] + o[it % 3] }, m.triangles)

    fun concatenate(meshes: List<Mesh>): Mesh {
        val verts = DoubleArray(meshes.sumOf { it.vertices.size })
        val tris = IntArray(meshes.sumOf { it.triangles.size })
        var vo = 0; var to = 0
        for (m in meshes) {
            m.vertices.copyInto(verts, vo)
            val base = vo / 3
            for (i in m.triangles.indices) tris[to + i] = m.triangles[i] + base
            vo += m.vertices.size; to += m.triangles.size
        }
        return Mesh(verts, tris)
    }

    /**
     * Makes neighbouring triangles agree on winding, then flips each closed component whose
     * volume is negative so normals point outward. Only reliable on watertight meshes.
     */
    fun fixWinding(m: Mesh): Mesh {
        val topo = m.topology
        val tc = m.triangleCount
        val tris = m.triangles.copyOf()
        val comp = IntArray(tc) { -1 }
        val stack = IntArray(tc)
        var nComp = 0
        for (seed in 0 until tc) {
            if (comp[seed] >= 0) continue
            var sp = 0; stack[sp++] = seed; comp[seed] = nComp
            while (sp > 0) {
                val t = stack[--sp]
                for (k in 0..2) {
                    val e = topo.faceEdges[3 * t + k]
                    if (topo.edgeUseCount[e] != 2) continue
                    val o = if (topo.edgeFaces[2 * e] == t) topo.edgeFaces[2 * e + 1] else topo.edgeFaces[2 * e]
                    if (o < 0 || comp[o] >= 0) continue
                    // Consistent winding means the shared edge runs in opposite directions in the two triangles.
                    // Read the edge from the topology, not from position k: t may have been flipped already.
                    val a = topo.edges[2 * e]; val b = topo.edges[2 * e + 1]
                    var dirT = false; var dirO = false
                    for (j in 0..2) {
                        if (tris[3 * t + j] == a && tris[3 * t + (j + 1) % 3] == b) dirT = true
                        if (tris[3 * o + j] == a && tris[3 * o + (j + 1) % 3] == b) dirO = true
                    }
                    if (dirT == dirO) { val tmp = tris[3 * o + 1]; tris[3 * o + 1] = tris[3 * o + 2]; tris[3 * o + 2] = tmp }
                    comp[o] = nComp; stack[sp++] = o
                }
            }
            nComp++
        }
        val vol = DoubleArray(nComp)
        val o = m.bounds.center
        for (t in 0 until tc) {
            val a = m.vertex(tris[3 * t]) - o; val b = m.vertex(tris[3 * t + 1]) - o; val c = m.vertex(tris[3 * t + 2]) - o
            vol[comp[t]] += a dot (b cross c)
        }
        for (t in 0 until tc) if (vol[comp[t]] < 0) { val tmp = tris[3 * t + 1]; tris[3 * t + 1] = tris[3 * t + 2]; tris[3 * t + 2] = tmp }
        return Mesh(m.vertices, tris)
    }

    /** Signed volume of unindexed triangles [first, last) in a soup array, measured from [origin]. */
    fun soupVolume(soup: DoubleArray, first: Int, last: Int, good: BooleanArray, origin: Vec3): Double {
        var s = 0.0
        for (t in first until last) {
            if (!good[t]) continue
            val i = 9 * t
            val ax = soup[i] - origin.x; val ay = soup[i + 1] - origin.y; val az = soup[i + 2] - origin.z
            val bx = soup[i + 3] - origin.x; val by = soup[i + 4] - origin.y; val bz = soup[i + 5] - origin.z
            val cx = soup[i + 6] - origin.x; val cy = soup[i + 7] - origin.y; val cz = soup[i + 8] - origin.z
            s += ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)
        }
        return s / 6
    }

    fun triangleArea(soup: DoubleArray, t: Int): Double {
        val i = 9 * t
        val ux = soup[i + 3] - soup[i]; val uy = soup[i + 4] - soup[i + 1]; val uz = soup[i + 5] - soup[i + 2]
        val vx = soup[i + 6] - soup[i]; val vy = soup[i + 7] - soup[i + 1]; val vz = soup[i + 8] - soup[i + 2]
        val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
        return sqrt(cx * cx + cy * cy + cz * cz) / 2
    }

    fun nearlyEqual(a: Double, b: Double, rel: Double, absTol: Double = 0.0) = abs(a - b) <= rel * maxOf(abs(a), abs(b)) + absTol
}
