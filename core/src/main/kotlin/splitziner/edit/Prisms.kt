package splitziner.edit

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import org.locationtech.jts.triangulate.polygon.PolygonTriangulator
import splitziner.explode.Silhouette
import splitziner.math.Vec3
import splitziner.mesh.Mesh
import splitziner.mesh.MeshOps
import splitziner.mesh.OrientedBounds
import splitziner.view.ViewDef
import kotlin.math.abs

/**
 * A part that is one outline pushed straight through a thickness: a board, a panel with a cut-out,
 * a bracket sawn from sheet. [view] looks along the thickness; [outline] is in that view's 2D
 * coordinates; the part runs from [lo] to [hi] along the view direction.
 */
class Prism(val view: ViewDef, val outline: Geometry, val lo: Double, val hi: Double) {
    val thickness: Double get() = hi - lo
    fun with(outline: Geometry = this.outline, lo: Double = this.lo, hi: Double = this.hi) = Prism(view, outline, lo, hi)
}

object Prisms {
    /** Outline corners closer to a straight line than this (mm) are dropped. */
    private const val SIMPLIFY = 0.01

    /** The direction with its largest component made positive, so a part and its mirror image agree on "up". */
    fun canonical(a: Vec3): Vec3 { val k = (0..2).maxByOrNull { abs(a[it]) }!!; return if (a[k] < 0) -a else a }

    private fun viewAlong(axis: Vec3, hint: Vec3): ViewDef {
        val a = canonical(axis.normalized())
        var x = hint - a * (hint dot a)
        if (x.length < 1e-6) x = (if (abs(a.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)).let { it - a * (it dot a) }
        return ViewDef("prism", a, x)
    }

    fun range(mesh: Mesh, a: Vec3): Pair<Double, Double> {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in 0 until mesh.vertexCount) { val z = mesh.vertex(i) dot a; if (z < lo) lo = z; if (z > hi) hi = z }
        return lo to hi
    }

    fun outline(mesh: Mesh, view: ViewDef): Geometry {
        var g = Silhouette.outline(mesh, view)
        // A surface with gaps may have faces turned the wrong way; its shadow then needs the faces seen from behind too.
        if (!mesh.isWatertight) runCatching {
            val back = Silhouette.outline(mesh, ViewDef("back", -view.n, view.x))
            g = g.union(org.locationtech.jts.geom.util.AffineTransformation.scaleInstance(1.0, -1.0).transform(back)).buffer(0.0)
        }
        return runCatching { TopologyPreservingSimplifier.simplify(g, SIMPLIFY) }.getOrDefault(g)
    }

    /** The part seen as a prism along [axis], whether or not it truly is one. */
    fun along(mesh: Mesh, axis: Vec3, hint: Vec3): Prism {
        val view = viewAlong(axis, hint)
        val (lo, hi) = range(mesh, view.n)
        return Prism(view, outline(mesh, view), lo, hi)
    }

    /**
     * The part as a prism if it is one: tried through its thinnest direction first. It is one when its
     * outline times its thickness accounts for its volume. Rounded-over edges lose a little, so a few
     * percent short still counts.
     */
    fun of(mesh: Mesh): Prism? {
        val box = OrientedBounds.of(mesh)
        val order = (0..2).sortedBy { box.extents[it] }
        val vol = abs(mesh.volume)
        val leaky = !mesh.isWatertight
        for ((n, k) in order.withIndex()) {
            val p = runCatching { along(mesh, box.axes[k], box.axes[order[2 - (if (n == 2) 1 else 0)]]) }.getOrNull() ?: continue
            val solid = p.outline.area * p.thickness
            if (solid <= 0) continue
            val r = vol / solid
            // A surface with gaps gives an unreliable volume; such a part is taken as a prism only through its thin direction.
            if (r > 0.92 && r < 1.02) return p
            if (leaky && n == 0 && box.extents[k] < 0.35 * box.extents[order[1]]) {
                // Seen from the other side it must show the same outline, as a sawn piece of sheet does.
                val back = runCatching { outline(mesh, ViewDef("back", -p.view.n, p.view.x)) }.getOrNull() ?: continue
                val flipped = org.locationtech.jts.geom.util.AffineTransformation.scaleInstance(1.0, -1.0).transform(back)
                val differ = runCatching { p.outline.symDifference(flipped).area }.getOrDefault(Double.MAX_VALUE)
                if (differ < 0.03 * p.outline.area) return p
            }
        }
        return null
    }

    fun point(v: ViewDef, x: Double, y: Double, z: Double): Vec3 = v.x * x + v.up * y + v.n * z

    /** A clean, closed solid from the outline. */
    fun toMesh(p: Prism): Mesh {
        val soup = ArrayList<Double>()
        fun tri(a: Vec3, b: Vec3, c: Vec3) { for (q in listOf(a, b, c)) { soup += q.x; soup += q.y; soup += q.z } }
        for (i in 0 until p.outline.numGeometries) {
            val poly = p.outline.getGeometryN(i) as? Polygon ?: continue
            if (poly.isEmpty) continue
            val tris = PolygonTriangulator.triangulate(poly)
            for (t in 0 until tris.numGeometries) {
                val c = tris.getGeometryN(t).coordinates
                tri(point(p.view, c[0].x, c[0].y, p.hi), point(p.view, c[1].x, c[1].y, p.hi), point(p.view, c[2].x, c[2].y, p.hi))
                tri(point(p.view, c[0].x, c[0].y, p.lo), point(p.view, c[2].x, c[2].y, p.lo), point(p.view, c[1].x, c[1].y, p.lo))
            }
            val rings = listOf(poly.exteriorRing) + (0 until poly.numInteriorRing).map { poly.getInteriorRingN(it) }
            for (r in rings) {
                val c = r.coordinates
                for (k in 0 until c.size - 1) {
                    val a0 = point(p.view, c[k].x, c[k].y, p.lo); val b0 = point(p.view, c[k + 1].x, c[k + 1].y, p.lo)
                    val a1 = point(p.view, c[k].x, c[k].y, p.hi); val b1 = point(p.view, c[k + 1].x, c[k + 1].y, p.hi)
                    tri(a0, b0, b1); tri(a0, b1, a1)
                }
            }
        }
        if (soup.isEmpty()) return Mesh(DoubleArray(0), IntArray(0))
        return MeshOps.fixWinding(MeshOps.fromSoup(soup.toDoubleArray(), 1e-6))
    }
}
