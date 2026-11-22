package splitziner.view

import splitziner.math.Vec2
import splitziner.math.Vec3
import splitziner.mesh.Mesh
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A viewing direction. [n] points from the model toward the viewer and [x] maps to screen-right;
 * screen-up is n × x. A model point p projects to (p·x, p·up).
 */
class ViewDef(val name: String, n: Vec3, x: Vec3) {
    val n: Vec3 = n.normalized()
    val x: Vec3 = x.normalized()
    val up: Vec3 = this.n cross this.x
    fun project(p: Vec3) = Vec2(p dot x, p dot up)

    companion object {
        val FRONT = ViewDef("front", Vec3(0.0, -1.0, 0.0), Vec3(1.0, 0.0, 0.0))
        val TOP = ViewDef("top", Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0))
        val RIGHT = ViewDef("right", Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val ISO = ViewDef("iso", Vec3(1.0, -1.0, 1.0), Vec3(1.0, 1.0, 0.0))
    }
}

/** 2D line work of one view, in view coordinates (model mm). Segments are x0,y0,x1,y1. */
class ViewLines(val visible: DoubleArray, val hidden: DoubleArray) {
    val visibleLength: Double by lazy { length(visible) }
    val hiddenLength: Double by lazy { length(hidden) }

    /** minX, minY, maxX, maxY of all line work; zeros when empty. */
    val bounds: DoubleArray by lazy {
        val b = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (a in listOf(visible, hidden)) { var i = 0; while (i < a.size) { if (a[i] < b[0]) b[0] = a[i]; if (a[i] > b[2]) b[2] = a[i]; if (a[i + 1] < b[1]) b[1] = a[i + 1]; if (a[i + 1] > b[3]) b[3] = a[i + 1]; i += 2 } }
        if (b[0] > b[2]) doubleArrayOf(0.0, 0.0, 0.0, 0.0) else b
    }

    companion object {
        fun length(s: DoubleArray): Double { var l = 0.0; var i = 0; while (i < s.size) { l += sqrt((s[i + 2] - s[i]) * (s[i + 2] - s[i]) + (s[i + 3] - s[i + 1]) * (s[i + 3] - s[i + 1])); i += 4 }; return l }
    }
}

/**
 * Works out which edges of a set of parts are visible from a direction.
 *
 * Candidate edges are creases, silhouette edges (one neighbouring face turned toward the viewer, the
 * other away) and open edges. Each is cut into short pieces; a piece is hidden when a ray from its
 * midpoint toward the viewer hits anything in the scene.
 */
object HiddenLine {
    /** Edges are sampled in pieces of this fraction of the scene size. */
    private const val PIECES = 100.0

    private const val BISECTIONS = 6

    /** 3D segments of one part split by visibility: x0,y0,z0,x1,y1,z1 each. */
    class Split3D(val visible: DoubleArray, val hidden: DoubleArray)

    fun candidateEdges(mesh: Mesh, n: Vec3): IntArray {
        val topo = mesh.topology
        val fn = mesh.faceNormals
        val limit = cos(Math.toRadians(EdgeExtractor.FEATURE_ANGLE_DEG))
        val out = ArrayList<Int>()
        for (e in 0 until topo.edgeCount) {
            if (topo.edgeUseCount[e] != 2) { out += e; continue }
            val a = topo.edgeFaces[2 * e]; val b = topo.edgeFaces[2 * e + 1]
            val da = fn[3 * a] * n.x + fn[3 * a + 1] * n.y + fn[3 * a + 2] * n.z
            val db = fn[3 * b] * n.x + fn[3 * b + 1] * n.y + fn[3 * b + 2] * n.z
            val crease = fn[3 * a] * fn[3 * b] + fn[3 * a + 1] * fn[3 * b + 1] + fn[3 * a + 2] * fn[3 * b + 2] < limit
            if (crease || (da > 1e-6) != (db > 1e-6)) out += e
        }
        return out.toIntArray()
    }

    /** Splits one part's candidate edges into visible and hidden stretches, testing against [scene]. */
    fun split(mesh: Mesh, scene: Mesh, view: ViewDef, sceneScale: Double = scene.scale, pieces: Double = PIECES): Split3D {
        val n = view.n
        val step = sceneScale / pieces
        val lift = n * (1e-3 * sceneScale)
        val bvh = scene.bvh
        val vis = ArrayList<Double>(); val hid = ArrayList<Double>()
        val topo = mesh.topology
        fun hiddenAt(p: Vec3) = bvh.intersectsAny(p + lift, n)
        for (e in candidateEdges(mesh, n)) {
            val a = mesh.vertex(topo.edges[2 * e]); val b = mesh.vertex(topo.edges[2 * e + 1])
            val d = b - a
            val len = d.length
            // Edges pointing straight at the viewer project to a point.
            if ((view.project(b) - view.project(a)).length < 1e-6 * sceneScale) continue
            val k = maxOf(1, ceil(len / step).toInt())
            var runStart = 0.0
            var prevMid = 0.5 / k
            var prev = hiddenAt(a + d * prevMid)
            fun emit(t0: Double, t1: Double, h: Boolean) {
                if (t1 - t0 <= 1e-12) return
                val p = a + d * t0; val q = a + d * t1
                val out = if (h) hid else vis
                out += p.x; out += p.y; out += p.z; out += q.x; out += q.y; out += q.z
            }
            for (i in 1 until k) {
                val mid = (i + 0.5) / k
                val h = hiddenAt(a + d * mid)
                if (h != prev) {
                    // Find where along the edge it changes, between the two sample points.
                    var lo = prevMid; var hi = mid
                    repeat(BISECTIONS) { val m = (lo + hi) / 2; if (hiddenAt(a + d * m) == prev) lo = m else hi = m }
                    val cut = (lo + hi) / 2
                    emit(runStart, cut, prev); runStart = cut; prev = h
                }
                prevMid = mid
            }
            emit(runStart, 1.0, prev)
        }
        return Split3D(vis.toDoubleArray(), hid.toDoubleArray())
    }

    fun project(segs3: DoubleArray, view: ViewDef): DoubleArray {
        val out = DoubleArray(segs3.size / 6 * 4)
        for (i in 0 until segs3.size / 6) {
            val p = view.project(Vec3(segs3[6 * i], segs3[6 * i + 1], segs3[6 * i + 2])); val q = view.project(Vec3(segs3[6 * i + 3], segs3[6 * i + 4], segs3[6 * i + 5]))
            out[4 * i] = p.x; out[4 * i + 1] = p.y; out[4 * i + 2] = q.x; out[4 * i + 3] = q.y
        }
        return out
    }

    /** Line work of a whole scene in one view. [scene] is all meshes concatenated. */
    fun compute(meshes: List<Mesh>, scene: Mesh, view: ViewDef): ViewLines {
        val vis = ArrayList<DoubleArray>(); val hid = ArrayList<DoubleArray>()
        val scale = scene.scale
        for (m in meshes) { val s = split(m, scene, view, scale); vis += project(s.visible, view); hid += project(s.hidden, view) }
        val tol = 1e-5 * scale
        val v = LineMerge.merge(concat(vis), tol)
        // A visible line takes precedence over a hidden one lying under it.
        val h = LineMerge.subtract(LineMerge.merge(concat(hid), tol), v, tol)
        return ViewLines(v, h)
    }

    private fun concat(list: List<DoubleArray>): DoubleArray {
        val out = DoubleArray(list.sumOf { it.size }); var o = 0
        for (a in list) { a.copyInto(out, o); o += a.size }
        return out
    }
}

/** Joins overlapping 2D segments that lie on the same line, and removes one set from under another. */
object LineMerge {
    private class Line(val dx: Double, val dy: Double, val off: Double, val qa: Long, val qb: Long) { val spans = ArrayList<DoubleArray>() }

    private fun key(a: Long, b: Long) = a * 4_000_037L + b

    /** Lines are bucketed by direction and by distance from the origin; near-identical ones are found in neighbouring buckets. */
    private class Index(val tol: Double) {
        val map = HashMap<Long, MutableList<Line>>()
        val all = ArrayList<Line>()

        fun find(dx: Double, dy: Double, off: Double, qa: Long, qb: Long): Line? {
            for (da in -1L..1L) for (db in -1L..1L) for (l in map[key(qa + da, qb + db)] ?: continue) {
                if (Math.abs(l.off - off) < tol && Math.abs(l.dx * dy - l.dy * dx) < 1e-6) return l
            }
            return null
        }

        fun add(segs: DoubleArray) {
            var i = 0
            while (i < segs.size) {
                var dx = segs[i + 2] - segs[i]; var dy = segs[i + 3] - segs[i + 1]
                val len = Math.sqrt(dx * dx + dy * dy)
                if (len < tol) { i += 4; continue }
                dx /= len; dy /= len
                if (dx < -1e-12 || (Math.abs(dx) <= 1e-12 && dy < 0)) { dx = -dx; dy = -dy }
                val off = -segs[i] * dy + segs[i + 1] * dx
                val t0 = segs[i] * dx + segs[i + 1] * dy; val t1 = segs[i + 2] * dx + segs[i + 3] * dy
                val qa = Math.round(dy * 2000); val qb = Math.round(off / (tol * 50))
                val line = find(dx, dy, off, qa, qb) ?: Line(dx, dy, off, qa, qb).also { map.getOrPut(key(qa, qb)) { ArrayList() } += it; all += it }
                line.spans += doubleArrayOf(minOf(t0, t1), maxOf(t0, t1))
                i += 4
            }
        }
    }

    private fun union(spans: List<DoubleArray>, tol: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (sp in spans.sortedBy { it[0] }) {
            if (out.isNotEmpty() && sp[0] <= out.last()[1] + tol) { if (sp[1] > out.last()[1]) out.last()[1] = sp[1] }
            else out += doubleArrayOf(sp[0], sp[1])
        }
        return out
    }

    private fun emit(l: Line, spans: List<DoubleArray>, out: ArrayList<Double>) {
        // A point on the line at parameter t is t*(dx,dy) + off*(-dy,dx).
        for (sp in spans) {
            out += sp[0] * l.dx - l.off * l.dy; out += sp[0] * l.dy + l.off * l.dx
            out += sp[1] * l.dx - l.off * l.dy; out += sp[1] * l.dy + l.off * l.dx
        }
    }

    fun merge(segs: DoubleArray, tol: Double): DoubleArray {
        val idx = Index(tol); idx.add(segs)
        val out = ArrayList<Double>()
        for (l in idx.all) emit(l, union(l.spans, tol), out)
        return out.toDoubleArray()
    }

    /** Parts of [a] not covered by [b]. */
    fun subtract(a: DoubleArray, b: DoubleArray, tol: Double): DoubleArray {
        val ia = Index(tol); ia.add(a)
        val ib = Index(tol); ib.add(b)
        val out = ArrayList<Double>()
        for (l in ia.all) {
            val o = ib.find(l.dx, l.dy, l.off, l.qa, l.qb)
            if (o == null) { emit(l, union(l.spans, tol), out); continue }
            val cover = union(o.spans, tol)
            val rest = ArrayList<DoubleArray>()
            for (sp in union(l.spans, tol)) {
                var t = sp[0]
                for (c in cover) {
                    if (c[1] <= t + tol || c[0] >= sp[1] - tol) continue
                    if (c[0] > t + tol) rest += doubleArrayOf(t, c[0])
                    t = maxOf(t, c[1])
                }
                if (sp[1] > t + tol) rest += doubleArrayOf(t, sp[1])
            }
            emit(l, rest, out)
        }
        return out.toDoubleArray()
    }
}
