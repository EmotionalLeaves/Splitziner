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

    fun hull(mesh: Mesh, view: ViewDef): Geometry {
        val pts = Array(mesh.vertexCount) { val p = view.project(mesh.vertex(it)); Coordinate(p.x, p.y) }
        return GF.createMultiPointFromCoords(pts).convexHull()
    }

    fun translated(g: Geometry, dx: Double, dy: Double): Geometry = AffineTransformation.translationInstance(dx, dy).transform(g)
}
