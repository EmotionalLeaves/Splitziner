package splitziner.explode

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.operation.union.UnaryUnionOp
import splitziner.mesh.Mesh
import splitziner.view.ViewDef

/** The outline a part casts on the screen, as a polygon. Used to keep exploded parts clear of each other. */
object Silhouette {
    val GF = GeometryFactory()

    /** Beyond this many triangles the exact union is too slow on a phone; the convex outline stands in. */
    private const val MAX_TRIANGLES = 20_000

    /**
     * A part's outline in a view never changes; guide pictures ask for the same ones step after step.
     * Kept per mesh (weakly, so they go when the mesh does) and per view direction.
     */
    private val cache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Mesh, HashMap<String, Geometry>>())

    fun outline(mesh: Mesh, view: ViewDef): Geometry {
        val key = "${view.n.x},${view.n.y},${view.n.z},${view.x.x},${view.x.y},${view.x.z}"
        cache[mesh]?.get(key)?.let { return it }
        val g = outlineUncached(mesh, view)
        synchronized(cache) { cache.getOrPut(mesh) { HashMap() }[key] = g }
        return g
    }

    /**
     * A closed part that fills its oriented box is (nearly) a box, and its outline is close to the convex hull of its
     * corners: far quicker than joining hundreds of triangles. Only for pictures, where a small notch lost does not
     * matter; editing and cutting need the true outline.
     */
    private fun boxLike(mesh: Mesh): Boolean {
        if (!mesh.isWatertight) return false
        val e = splitziner.mesh.OrientedBounds.of(mesh).extents
        val v = e.x * e.y * e.z
        return v > 0 && Math.abs(mesh.volume) > 0.995 * v
    }

    private fun outlineUncached(mesh: Mesh, view: ViewDef): Geometry {
        val n = mesh.faceNormals
        val front = (0 until mesh.triangleCount).filter { t -> n[3 * t] * view.n.x + n[3 * t + 1] * view.n.y + n[3 * t + 2] * view.n.z > 1e-9 }
        if (front.size > MAX_TRIANGLES) return hull(mesh, view)
        val snap = maxOf(mesh.scale, 1e-9) * 1e-7
        fun q(v: Double) = Math.rint(v / snap) * snap
        val polys = ArrayList<Geometry>(front.size)
        for (t in front) {
            val c = Array(3) { k -> val p = view.project(mesh.vertex(mesh.triangles[3 * t + k])); Coordinate(q(p.x), q(p.y)) }
            val area2 = (c[1].x - c[0].x) * (c[2].y - c[0].y) - (c[2].x - c[0].x) * (c[1].y - c[0].y)
            if (Math.abs(area2) / 2 <= 1e-9) continue
            polys += GF.createPolygon(arrayOf(c[0], c[1], c[2], c[0]))
        }
        if (polys.isEmpty()) return hull(mesh, view)
        return try { UnaryUnionOp.union(polys).buffer(0.0) } catch (e: Exception) { hull(mesh, view) }
    }

    /**
     * Outlines of many meshes, for pictures. Copies of one shape that differ only by where they sit (599 identical bolts) are
     * traced once and the outline moved, which is most of the work saved on big repetitive assemblies.
     */
    fun outlines(meshes: List<Mesh>, view: ViewDef): List<Geometry> {
        // A shape's key: its size and a few vertices relative to its first; equal keys are checked properly below.
        fun same(a: Mesh, b: Mesh): Boolean {
            if (a.vertexCount != b.vertexCount || a.triangleCount != b.triangleCount) return false
            val tol = 1e-6 * maxOf(a.scale, 1e-9)
            val dx = b.vertices[0] - a.vertices[0]; val dy = b.vertices[1] - a.vertices[1]; val dz = b.vertices[2] - a.vertices[2]
            val step = maxOf(1, a.vertexCount / 64)
            for (i in 0 until a.vertexCount step step) {
                if (Math.abs(b.vertices[3 * i] - a.vertices[3 * i] - dx) > tol || Math.abs(b.vertices[3 * i + 1] - a.vertices[3 * i + 1] - dy) > tol || Math.abs(b.vertices[3 * i + 2] - a.vertices[3 * i + 2] - dz) > tol) return false
            }
            return true
        }
        val leader = IntArray(meshes.size) { it }
        val byShape = HashMap<Long, MutableList<Int>>()
        for ((i, m) in meshes.withIndex()) {
            val e = m.extents
            val key = (m.triangleCount.toLong() * 1_000_003L) xor java.lang.Double.doubleToLongBits(Math.rint(e.x * 1e3) + 7 * Math.rint(e.y * 1e3) + 13 * Math.rint(e.z * 1e3))
            val list = byShape.getOrPut(key) { ArrayList() }
            val l = list.firstOrNull { same(meshes[it], m) }
            if (l != null) leader[i] = l else list += i
        }
        val leaders = leader.indices.filter { leader[it] == it }
        val traced = HashMap<Int, Geometry>()
        fun pic(m: Mesh) = if (boxLike(m)) hull(m, view) else outline(m, view)
        val computed = if (leaders.size < 16) leaders.map { it to pic(meshes[it]) }
            else leaders.parallelStream().map { it to pic(meshes[it]) }.collect(java.util.stream.Collectors.toList())
        for ((i, g) in computed) traced[i] = g
        return meshes.indices.map { i ->
            val l = leader[i]
            if (l == i) traced.getValue(i) else {
                val d = splitziner.math.Vec3(meshes[i].vertices[0] - meshes[l].vertices[0], meshes[i].vertices[1] - meshes[l].vertices[1], meshes[i].vertices[2] - meshes[l].vertices[2])
                translated(traced.getValue(l), d dot view.x, d dot view.up)
            }
        }
    }

    fun hull(mesh: Mesh, view: ViewDef): Geometry {
        val pts = Array(mesh.vertexCount) { val p = view.project(mesh.vertex(it)); Coordinate(p.x, p.y) }
        return GF.createMultiPointFromCoords(pts).convexHull()
    }

    fun translated(g: Geometry, dx: Double, dy: Double): Geometry = AffineTransformation.translationInstance(dx, dy).transform(g)
}
