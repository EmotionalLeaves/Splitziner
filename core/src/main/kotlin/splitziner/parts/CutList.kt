package splitziner.parts

import splitziner.dims.LengthFormat
import splitziner.mesh.Mesh
import splitziner.mesh.OrientedBounds
import splitziner.math.Vec3
import splitziner.section.Slicer
import kotlin.math.PI
import kotlin.math.abs

/** Stock-style description of one part. Sizes in millimetres. */
class PartDescription(val kind: PartKind, val t: Double, val w: Double, val l: Double, val fill: Double, val desc: String,
    /** Two of these nest head to tail in a blank this wide (a sloped side's two widths added): null when they do not. */
    val pairW: Double? = null)

data class CutListRow(
    val item: Int,
    val qty: Int,
    val partNo: String,
    val description: String,
    val material: String,
    val materialSpec: String,
    /** "T x W x L" in display units. */
    val stock: String,
    val kind: PartKind,
    val t: Double, val w: Double, val l: Double,
    val memberIds: List<String>,
    /** The grain must run along this part's length. */
    val grain: Boolean = false,
    /** What the parts are called ("Side", "Bottom / Top"), when they have names of their own; else empty. */
    val name: String = "",
    /** Two nest head to tail in a blank this wide by [l]; null when they do not. */
    val pairW: Double? = null,
)

/** Total length to buy of one stock cross-section, before saw kerf and waste. */
data class StockSummaryRow(val kind: PartKind, val thickness: String, val width: String, val totalLength: String, val pieces: Int)

class CutListResult(val parts: List<Part>, val rows: List<CutListRow>, val stock: List<StockSummaryRow>, val warnings: List<String>)

object PartDescriber {
    /** A flat part at least this wide (mm) is called a panel rather than a board. */
    private const val PANEL_MIN_WIDTH = 250.0

    /** A plain rectangular block fills its box; anything under this has been shaped. */
    private const val SOLID_FILL = 0.96

    /** A board that keeps at least this much of its stock, with notches sawn right through, is a notched board. */
    private const val NOTCHED_BOARD_FILL = 0.7

    /** Under this a board has a notch or hole worth mentioning. */
    private const val NOTCH_FILL = 0.995

    /**
     * For a long straight stick whose two end faces are flat but not square to its length: the angles (degrees) the
     * ends lean off square across its thickness and across its width. Null for square ends or anything else.
     */
    /**
     * The bevel (degrees off square) of a board whose cross-section, the same all along, is a rectangle with one edge
     * cut sloping right across the thickness; null for anything else.
     */
    fun bevelledEdge(m: Mesh, obb: splitziner.mesh.OrientedBox): Double? {
        val e = listOf(obb.extents.x, obb.extents.y, obb.extents.z)
        val li = e.indexOf(e.max()); val ln = e[li]
        val rest = e.filterIndexed { i, _ -> i != li }.sorted()
        if (ln < 3 * rest[1] || rest[0] < 3.0) return null
        val axis = obb.axes[li]
        // The same section at a quarter, the middle and three quarters along.
        val secs = listOf(-0.25, 0.0, 0.25).map { f -> Slicer.sectionLoops(m, obb.center + axis * (f * ln), axis) }
        if (secs.any { it.size != 1 }) return null
        val areas = secs.map { abs(Slicer.loopArea(it[0])) }
        if (areas.max() - areas.min() > 0.01 * areas.max()) return null
        // Corners of the middle section, dropping points on straight runs.
        val lp = secs[1][0]
        val pts = lp.indices.filter { i -> val a = lp[(i + lp.size - 1) % lp.size]; val b = lp[i]; val c = lp[(i + 1) % lp.size]
            abs((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)) > 1e-3 * (rest[1] * rest[1]) }.map { lp[it] }
        if (pts.size != 4) return null
        val edges = pts.indices.map { i -> val a = pts[i]; val b = pts[(i + 1) % 4]; splitziner.math.Vec2(b.x - a.x, b.y - a.y) }
        // Square edges run along one of two directions at right angles; one edge, and only one, slopes.
        val ref = edges.maxByOrNull { it.length }!!.normalized()
        fun off(v: splitziner.math.Vec2): Double { val c = abs(v.normalized().x * ref.x + v.normalized().y * ref.y).coerceIn(0.0, 1.0); val deg = Math.toDegrees(kotlin.math.acos(c)); return minOf(deg, 90 - deg) }
        val sloped = edges.filter { off(it) > 1.0 }
        if (sloped.size != 1) return null
        // The slope crosses the whole thickness: its run across the faces' normal equals the board's thickness.
        val s = sloped[0]; val across = abs(s.x * -ref.y + s.y * ref.x)
        if (abs(across - rest[0]) > 0.05 * rest[0]) return null
        val along = abs(s.x * ref.x + s.y * ref.y)
        return Math.toDegrees(kotlin.math.atan2(along, across))
    }

    /**
     * A board with both long edges bevelled the same, through its whole thickness, wider on one face than the other
     * (the staves of a six- or eight-sided plinth, which meet in mitres): the angle off square (degrees), or null.
     */
    fun bevelledEdges(m: Mesh, obb: splitziner.mesh.OrientedBox): Double? {
        val e = listOf(obb.extents.x, obb.extents.y, obb.extents.z)
        val li = e.indexOf(e.max()); val ln = e[li]
        val rest = e.filterIndexed { i, _ -> i != li }.sorted()
        if (ln < 1.5 * rest[1] || rest[0] < 3.0) return null
        val axis = obb.axes[li]
        val secs = listOf(-0.25, 0.0, 0.25).map { f -> Slicer.sectionLoops(m, obb.center + axis * (f * ln), axis) }
        if (secs.any { it.size != 1 }) return null
        val areas = secs.map { abs(Slicer.loopArea(it[0])) }
        if (areas.max() - areas.min() > 0.01 * areas.max()) return null
        val lp = secs[1][0]
        val pts = lp.indices.filter { i -> val a = lp[(i + lp.size - 1) % lp.size]; val b = lp[i]; val c = lp[(i + 1) % lp.size]
            abs((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)) > 1e-3 * (rest[1] * rest[1]) }.map { lp[it] }
        if (pts.size != 4) return null
        val edges = pts.indices.map { i -> val a = pts[i]; val b = pts[(i + 1) % 4]; splitziner.math.Vec2(b.x - a.x, b.y - a.y) }
        // The two faces are the long edges of the section, parallel to each other; the other two slope.
        val ref = edges.maxByOrNull { it.length }!!.normalized()
        fun off(v: splitziner.math.Vec2): Double { val c = abs(v.normalized().x * ref.x + v.normalized().y * ref.y).coerceIn(0.0, 1.0); val deg = Math.toDegrees(kotlin.math.acos(c)); return minOf(deg, 90 - deg) }
        val faces = edges.filter { off(it) <= 0.5 && abs(abs(it.normalized().x * ref.x + it.normalized().y * ref.y) - 1.0) < 1e-3 }
        val sloped = edges.filter { off(it) > 1.0 }
        if (faces.size != 2 || sloped.size != 2) return null
        val t = abs(faces[0].let { f -> val n = splitziner.math.Vec2(-ref.y, ref.x); (pts[0].x - pts[2].x) * n.x + (pts[0].y - pts[2].y) * n.y })
        val runs = sloped.map { s -> abs(s.x * ref.x + s.y * ref.y) to abs(s.x * -ref.y + s.y * ref.x) }
        if (runs.any { (_, across) -> abs(across - rest[0]) > 0.05 * rest[0] }) return null
        val degs = runs.map { (along, across) -> Math.toDegrees(kotlin.math.atan2(along, across)) }
        // A ring of five or more sides needs 36 degrees or less; steeper is something else (a raised panel's field).
        if (abs(degs[0] - degs[1]) > 1.0 || degs.max() > 45.0) return null
        // Wider on one face: the faces differ by both runs (a parallelogram's are equal, ripped the same way).
        if (abs(abs(faces[0].length - faces[1].length) - (runs[0].first + runs[1].first)) > 0.05 * (runs[0].first + runs[1].first) + 0.2) return null
        if (t <= 0.0) return null
        return degs.average()
    }

    /**
     * A raised panel (a frame-and-panel door's): flat on the back, a thin tongue all round its edge to sit in the
     * frame's grooves, and bevelled up from it to a flat field in the middle. Returns the tongue's thickness and the
     * bevel's width (mm), or null for anything else.
     */
    fun raisedPanel(m: Mesh, obb: splitziner.mesh.OrientedBox): Pair<Double, Double>? {
        val e = doubleArrayOf(obb.extents.x, obb.extents.y, obb.extents.z)
        val k = e.indices.minByOrNull { e[it] }!!
        val (a, b) = e.indices.filter { it != k }
        if (e[k] > 32.0 || e[a] < 100.0 || e[b] < 100.0) return null
        val v = m.vertices
        val tol = 0.3
        val pts = (0 until m.vertexCount).map { i -> val p = Vec3(v[3 * i], v[3 * i + 1], v[3 * i + 2]) - obb.center
            doubleArrayOf(p dot obb.axes[a], p dot obb.axes[b], p dot obb.axes[k]) }
        fun onEdge(q: DoubleArray) = abs(abs(q[0]) - e[a] / 2) < tol || abs(abs(q[1]) - e[b] / 2) < tol
        val edge = pts.filter { onEdge(it) }; val inner = pts.filter { !onEdge(it) }
        if (edge.size < 8 || inner.size < 4) return null
        // The edge is the tongue, both its faces flat; one of them is the back.
        val eLo = edge.minOf { it[2] }; val eHi = edge.maxOf { it[2] }
        if (edge.any { abs(it[2] - eLo) > tol && abs(it[2] - eHi) > tol }) return null
        val tongue = eHi - eLo
        val backLow = abs(eLo + e[k] / 2) < tol
        if (!backLow && abs(eHi - e[k] / 2) >= tol) return null
        if (tongue < 3.0 || tongue > 0.75 * e[k]) return null
        // The field: every point off the edge is on the far face, set in the same distance all round.
        val far = if (backLow) e[k] / 2 else -e[k] / 2
        if (inner.any { abs(it[2] - far) > tol }) return null
        val insetA = e[a] / 2 - inner.maxOf { abs(it[0]) }; val insetB = e[b] / 2 - inner.maxOf { abs(it[1]) }
        if (insetA < 6.0 || abs(insetA - insetB) > 2.0) return null
        return tongue to (insetA + insetB) / 2
    }

    /** A taper: the foot's two sizes, where it starts (from the big end), and on how many faces. */
    class Taper(val footT: Double, val footW: Double, val from: Double, val faces: Int, val adjacent: Boolean, val bigEndHigh: Boolean)

    /**
     * A stick whose square section stays the same from one end for a way, then shrinks evenly to the other end
     * (a tapered leg). Read from rectangular cross-sections along its length; null for anything else.
     */
    fun tapered(m: Mesh, obb: splitziner.mesh.OrientedBox): Taper? {
        val e = listOf(obb.extents.x, obb.extents.y, obb.extents.z)
        val li = e.indexOf(e.max()); val ln = e[li]
        val rest = e.filterIndexed { i, _ -> i != li }.sorted()
        if (ln < 4 * rest[1] || rest[0] < 12.0) return null
        val axis = obb.axes[li]
        class S(val f: Double, val x0: Double, val x1: Double, val y0: Double, val y1: Double)
        val fs = (0..20).map { 0.01 + 0.98 * it / 20.0 }
        val secs = fs.map { f ->
            val loops = Slicer.sectionLoops(m, obb.center + axis * ((f - 0.5) * ln), axis)
            if (loops.size != 1) return null
            val lp = loops[0]
            val x0 = lp.minOf { it.x }; val x1 = lp.maxOf { it.x }; val y0 = lp.minOf { it.y }; val y1 = lp.maxOf { it.y }
            // A rectangle: it fills its own box.
            if (abs(Slicer.loopArea(lp)) < 0.98 * (x1 - x0) * (y1 - y0)) return null
            S(f, x0, x1, y0, y1)
        }
        val big = secs.maxByOrNull { (it.x1 - it.x0) * (it.y1 - it.y0) }!!
        val bigLow = (secs.first().x1 - secs.first().x0) * (secs.first().y1 - secs.first().y0) >= (secs.last().x1 - secs.last().x0) * (secs.last().y1 - secs.last().y0)
        val ordered = if (bigLow) secs else secs.reversed()   // from the big end
        val foot = ordered.last()
        val shrink = (big.x1 - big.x0) - (foot.x1 - foot.x0) + (big.y1 - big.y0) - (foot.y1 - foot.y0)
        if (shrink < 3.0) return null
        // Square from the big end to where the taper starts, then straight lines to the foot.
        fun same(a: S, b: S) = abs(a.x0 - b.x0) < 0.3 && abs(a.x1 - b.x1) < 0.3 && abs(a.y0 - b.y0) < 0.3 && abs(a.y1 - b.y1) < 0.3
        val k0 = ordered.indexOfLast { same(it, ordered[0]) }
        if (k0 < 1 || k0 >= ordered.size - 3) return null
        val tail = ordered.drop(k0 + 1)
        // Evenly: each side moves linearly with distance.
        for (sel in listOf<(S) -> Double>({ it.x0 }, { it.x1 }, { it.y0 }, { it.y1 })) {
            val a = tail.first(); val b = tail.last()
            for (q in tail) { val t = (q.f - a.f) / (b.f - a.f); if (abs(sel(q) - (sel(a) + t * (sel(b) - sel(a)))) > 0.5) return null }
        }
        // Where it starts: extrapolate the moving sides back to the full size.
        val a = tail.first(); val b = tail.last()
        val moved = listOf(abs(a.x0 - b.x0), abs(a.x1 - b.x1), abs(a.y0 - b.y0), abs(a.y1 - b.y1))
        val faces = moved.count { it > 0.3 }
        val i = moved.indexOf(moved.max())
        val sel = listOf<(S) -> Double>({ it.x0 }, { it.x1 }, { it.y0 }, { it.y1 })[i]
        val slope = (sel(b) - sel(a)) / (b.f - a.f)
        val fStart = a.f - (sel(a) - sel(ordered[0])) / slope
        val from = abs(if (bigLow) fStart else 1 - fStart) * ln
        val footSize = listOf(foot.x1 - foot.x0, foot.y1 - foot.y0).sorted()
        // Extrapolated to the very end, not the last section a hair from it.
        val endScale = (1.0 - (if (bigLow) a.f else 1 - a.f)) / (abs(b.f - a.f))
        val fx = (a.x1 - a.x0) + ((b.x1 - b.x0) - (a.x1 - a.x0)) * endScale
        val fy = (a.y1 - a.y0) + ((b.y1 - b.y0) - (a.y1 - a.y0)) * endScale
        val fsz = listOf(fx, fy).sorted()
        val adjacent = faces == 2 && ((moved[0] > 0.3 || moved[1] > 0.3) && (moved[2] > 0.3 || moved[3] > 0.3))
        // Which end is thick, as the part stands in the model.
        val bigZ = (obb.center + axis * ((if (bigLow) -0.5 else 0.5) * ln)).z; val smallZ = (obb.center + axis * ((if (bigLow) 0.5 else -0.5) * ln)).z
        return Taper(fsz[0].takeIf { it > 0 } ?: footSize[0], fsz[1].takeIf { it > 0 } ?: footSize[1], from, faces, adjacent, bigZ > smallZ + 1.0)
    }

    fun angledEnds(m: Mesh, obb: splitziner.mesh.OrientedBox): Pair<Double, Double>? {
        val e = listOf(obb.extents.x, obb.extents.y, obb.extents.z)
        val order = e.indices.sortedBy { e[it] }
        val a0 = obb.axes[order[0]]; val a1 = obb.axes[order[1]]; val a2 = obb.axes[order[2]]
        // Long against its width; a mitred box side can be only twice as long as it is high.
        if (e[order[2]] < 1.8 * e[order[1]]) return null
        val n = m.faceNormals; val area = m.faceAreas
        var endArea = 0.0; var sideArea = 0.0
        val angles = ArrayList<Pair<Double, Double>>()
        for (t in 0 until m.triangleCount) {
            val v = Vec3(n[3 * t], n[3 * t + 1], n[3 * t + 2])
            val along = abs(v dot a2)
            if (along > 0.5) {
                endArea += area[t]
                angles += Math.toDegrees(kotlin.math.atan(abs(v dot a0) / along)) to Math.toDegrees(kotlin.math.atan(abs(v dot a1) / along))
            } else if (along < 0.2) sideArea += area[t] else return null
        }
        if (angles.isEmpty() || endArea <= 0) return null
        val x0 = angles.maxOf { it.first }; val x1 = angles.maxOf { it.second }
        // Square ends, or ends not flat (a tenon, a rounded end): not this.
        if (maxOf(x0, x1) < 1.0 || angles.any { abs(it.first - x0) > 1.0 || abs(it.second - x1) > 1.0 }) return null
        return x0 to x1
    }

    /**
     * True when a stick's two angled ends lean opposite ways (a mitred frame's piece, a trapezoid), false when they
     * are parallel (a splayed leg sawn level top and bottom).
     */
    fun mirroredEnds(m: Mesh, obb: splitziner.mesh.OrientedBox): Boolean {
        val e = listOf(obb.extents.x, obb.extents.y, obb.extents.z)
        val order = e.indices.sortedBy { e[it] }
        val a0 = obb.axes[order[0]]; val a1 = obb.axes[order[1]]; val a2 = obb.axes[order[2]]
        val n = m.faceNormals; val area = m.faceAreas
        // Each end's lean across the thickness and the width, as the slope of its face; weighted by area.
        val lean = Array(2) { DoubleArray(2) }
        for (t in 0 until m.triangleCount) {
            val v = Vec3(n[3 * t], n[3 * t + 1], n[3 * t + 2]); val along = v dot a2
            if (abs(along) <= 0.5) continue
            val end = if (along > 0) 1 else 0
            lean[end][0] += area[t] * (v dot a0) / along; lean[end][1] += area[t] * (v dot a1) / along
        }
        // Parallel planes have the same slope seen from each end; mirrored ones the opposite.
        return (0..1).any { k -> lean[0][k] * lean[1][k] < 0 && abs(lean[0][k]) > 1e-6 }
    }

    /**
     * A flat part whose outline is a right trapezoid: a rectangle with one edge sawn on a slope from one end to the
     * other. Returns the two widths at the ends (short, long), or null.
     */
    fun slopedEdge(m: Mesh, pre: org.locationtech.jts.geom.Geometry? = null): Pair<Double, Double>? {
        val g = pre ?: runCatching { splitziner.edit.Prisms.of(m) }.getOrNull()?.outline ?: return null
        if (g.numGeometries != 1 || g !is org.locationtech.jts.geom.Polygon || g.numInteriorRing != 0) return null
        val c = org.locationtech.jts.simplify.DouglasPeuckerSimplifier.simplify(g, 0.3).coordinates.dropLast(1)
        if (c.size != 4) return null
        fun v(i: Int, j: Int) = (c[j % 4].x - c[i % 4].x) to (c[j % 4].y - c[i % 4].y)
        fun len(a: Pair<Double, Double>) = Math.hypot(a.first, a.second)
        // The angle at each corner: square or not.
        val square = (0..3).map { i -> val a = v(i, i + 3); val b = v(i, i + 1); kotlin.math.abs(a.first * b.first + a.second * b.second) < 0.01 * len(a) * len(b) }
        if (square.count { it } != 2) return null
        // The two square corners are neighbours: the edge between them is the square end, its neighbours the long sides.
        val k = (0..3).firstOrNull { square[it] && square[(it + 1) % 4] } ?: return null
        val a = len(v(k + 3, k)); val b = len(v(k + 1, k + 2))
        if (kotlin.math.abs(a - b) < 3.0) return null
        return minOf(a, b) to maxOf(a, b)
    }

    /**
     * A flat part whose outline is its rectangle with one or more corners sawn off in a straight line, all the same
     * size: how many, and the cut's two legs along the edges. Null otherwise.
     */
    fun clippedCorners(m: Mesh, pre: org.locationtech.jts.geom.Geometry? = null): Pair<Int, Pair<Double, Double>>? {
        val g = pre ?: runCatching { splitziner.edit.Prisms.of(m) }.getOrNull()?.outline ?: return null
        if (g.numGeometries != 1 || g !is org.locationtech.jts.geom.Polygon || g.numInteriorRing != 0) return null
        val c = org.locationtech.jts.simplify.DouglasPeuckerSimplifier.simplify(g, 0.3).coordinates.dropLast(1)
        if (c.size < 5 || c.size > 8) return null
        val x0 = c.minOf { it.x }; val x1 = c.maxOf { it.x }; val y0 = c.minOf { it.y }; val y1 = c.maxOf { it.y }
        val tol = 0.5
        fun onX(p: org.locationtech.jts.geom.Coordinate) = kotlin.math.abs(p.x - x0) < tol || kotlin.math.abs(p.x - x1) < tol
        fun onY(p: org.locationtech.jts.geom.Coordinate) = kotlin.math.abs(p.y - y0) < tol || kotlin.math.abs(p.y - y1) < tol
        // Every corner of the outline is on the rectangle's edges; the box corners it misses are the clipped ones.
        if (c.any { !onX(it) && !onY(it) }) return null
        val corners = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        val missing = corners.filter { (cx, cy) -> c.none { kotlin.math.abs(it.x - cx) < tol && kotlin.math.abs(it.y - cy) < tol } }
        if (missing.isEmpty() || c.size != 4 + missing.size) return null
        val legs = missing.map { (cx, cy) ->
            val a = c.filter { kotlin.math.abs(it.y - cy) < tol }.minOf { kotlin.math.abs(it.x - cx) }
            val b = c.filter { kotlin.math.abs(it.x - cx) < tol }.minOf { kotlin.math.abs(it.y - cy) }
            a to b }
        // A clip, not a notch: each leg well short of the side it is on; all the same size.
        if (legs.any { (a, b) -> a < 3.0 || b < 3.0 || a > 0.5 * (x1 - x0) || b > 0.5 * (y1 - y0) }) return null
        val first = legs[0]
        if (legs.any { kotlin.math.abs(it.first - first.first) > 0.5 || kotlin.math.abs(it.second - first.second) > 0.5 }) return null
        return missing.size to first
    }

    /**
     * A flat part whose outline is its rectangle with one or more corners rounded to one radius: how many, and the
     * radius. Every point of the outline is on the rectangle's edges or on a rounded corner's arc. Null otherwise.
     */
    fun roundedCorners(m: Mesh, pre: org.locationtech.jts.geom.Geometry? = null): Pair<Int, Double>? {
        val g = pre ?: runCatching { splitziner.edit.Prisms.of(m) }.getOrNull()?.outline ?: return null
        if (g.numGeometries != 1 || g !is org.locationtech.jts.geom.Polygon || g.numInteriorRing != 0) return null
        val c = g.coordinates.dropLast(1)
        if (c.size < 12) return null
        val x0 = c.minOf { it.x }; val x1 = c.maxOf { it.x }; val y0 = c.minOf { it.y }; val y1 = c.maxOf { it.y }
        val tol = 0.5
        val corners = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        val missing = corners.filter { (cx, cy) -> c.none { kotlin.math.abs(it.x - cx) < tol && kotlin.math.abs(it.y - cy) < tol } }
        if (missing.isEmpty()) return null
        // Each rounded corner takes r squared times (1 - pi/4) off the rectangle.
        val deficit = (x1 - x0) * (y1 - y0) - g.area
        val r0 = Math.sqrt(deficit / missing.size / (1 - Math.PI / 4))
        // A circle or a slot is not a rectangle with rounded corners.
        if (r0 < 5.0 || r0 > 0.4 * minOf(x1 - x0, y1 - y0)) return null
        // The radius itself: where the straight edges stop short of a rounded corner.
        val r = missing.flatMap { (cx, cy) -> listOf(
            c.filter { kotlin.math.abs(it.y - cy) < tol }.minOfOrNull { kotlin.math.abs(it.x - cx) },
            c.filter { kotlin.math.abs(it.x - cx) < tol }.minOfOrNull { kotlin.math.abs(it.y - cy) }) }.filterNotNull().average()
        if (r.isNaN() || kotlin.math.abs(r - r0) > 0.15 * r0) return null
        val centres = missing.map { (cx, cy) -> (if (cx == x0) x0 + r else x1 - r) to (if (cy == y0) y0 + r else y1 - r) }
        val arcTol = maxOf(1.0, 0.04 * r)
        val ok = c.all { p ->
            val onEdge = kotlin.math.abs(p.x - x0) < tol || kotlin.math.abs(p.x - x1) < tol || kotlin.math.abs(p.y - y0) < tol || kotlin.math.abs(p.y - y1) < tol
            onEdge || centres.any { (cx, cy) -> kotlin.math.abs(Math.hypot(p.x - cx, p.y - cy) - r) < arcTol } }
        return if (ok) missing.size to r else null
    }

    /** A flat part whose outline is a regular polygon of five to twelve sides (a hexagonal top): sides and their length (mm). */
    fun regularPolygon(m: Mesh, pre: org.locationtech.jts.geom.Geometry? = null): Pair<Int, Double>? {
        val g = pre ?: runCatching { splitziner.edit.Prisms.of(m) }.getOrNull()?.outline ?: return null
        if (g.numGeometries != 1 || g !is org.locationtech.jts.geom.Polygon || g.numInteriorRing != 0) return null
        val c = org.locationtech.jts.simplify.DouglasPeuckerSimplifier.simplify(g, 0.3).coordinates.dropLast(1)
        val n = c.size
        if (n !in 5..12) return null
        val sides = (0 until n).map { i -> Math.hypot(c[(i + 1) % n].x - c[i].x, c[(i + 1) % n].y - c[i].y) }
        if (sides.max() - sides.min() > 0.01 * sides.average()) return null
        // Every corner the same: the polygon's area is that of the regular one with these sides.
        val ideal = n * sides.average() * sides.average() / (4 * kotlin.math.tan(PI / n))
        if (kotlin.math.abs(g.area - ideal) > 0.01 * ideal) return null
        return n to sides.average()
    }

    /** A flat part whose outline is a triangle with one square corner. */
    fun rightTriangle(m: Mesh, pre: org.locationtech.jts.geom.Geometry? = null): Boolean {
        val g = pre ?: runCatching { splitziner.edit.Prisms.of(m) }.getOrNull()?.outline ?: return false
        if (g.numGeometries != 1 || g !is org.locationtech.jts.geom.Polygon || g.numInteriorRing != 0) return false
        val c = org.locationtech.jts.simplify.DouglasPeuckerSimplifier.simplify(g, 0.3).coordinates.dropLast(1)
        if (c.size != 3) return false
        return (0..2).count { i -> val a = (c[(i + 2) % 3].x - c[i].x) to (c[(i + 2) % 3].y - c[i].y); val b = (c[(i + 1) % 3].x - c[i].x) to (c[(i + 1) % 3].y - c[i].y)
            kotlin.math.abs(a.first * b.first + a.second * b.second) < 0.01 * Math.hypot(a.first, a.second) * Math.hypot(b.first, b.second) } == 1
    }

    fun describe(m: Mesh, fmt: LengthFormat): PartDescription {
        val ext = m.extents
        val obb = OrientedBounds.of(m)
        // Prefer the plain axis-aligned box unless the oriented one is clearly tighter.
        val use = if (ext.x * ext.y * ext.z <= obb.volume * 1.02) doubleArrayOf(ext.x, ext.y, ext.z)
        else doubleArrayOf(obb.extents.x, obb.extents.y, obb.extents.z)
        use.sort()
        var t = use[0]; var w = use[1]; val l = use[2]
        val vol = abs(m.volume)
        val fill = vol / maxOf(t * w * l, 1e-12)
        val d = { v: Double -> fmt.format(v) }

        if (!m.isWatertight) {
            // Small gaps barely change the measured volume, so the part is still described by it. A surface that is
            // mostly missing (many open edges, or a volume no box of this size could hold) is listed by its outer size only.
            val topo = m.topology
            val open = (0 until topo.edgeCount).count { topo.edgeUseCount[it] == 1 }
            if (open > 0.2 * topo.edgeCount || fill > 1.02 || fill < 0.01)
                return PartDescription(PartKind.SHAPED, t, w, l, 0.0, "SHAPED, ENVELOPE ${d(t)} X ${d(w)} X ${d(l)}")
        }

        // A leg tapered on some of its faces (a Shaker table's): a square stick with the taper named.
        tapered(m, obb)?.let { tp ->
            val faces = when (tp.faces) { 1 -> "ONE FACE"; 2 -> if (tp.adjacent) "TWO ADJACENT FACES" else "TWO OPPOSITE FACES"; 3 -> "THREE FACES"; else -> "ALL FOUR FACES" }
            return PartDescription(PartKind.BOARD, t, w, l, fill, "BOARD ${d(t)} X ${d(w)} X ${d(l)}, TAPERED ON $faces TO ${d(tp.footT)} X ${d(tp.footW)}, STARTING ${d(tp.from)} FROM THE ${if (tp.bigEndHigh) "TOP" else "THICK END"}")
        }
        var dia: Double? = null
        // A cylinder fills pi/4 (0.785) of its box.
        if (abs(t - w) < 0.05 * w && fill > 0.72 && fill < 0.84) dia = (t + w) / 2
        else if (l > 3 * w && fill < SOLID_FILL) {
            // Round bar with angled ends: look at the cross-section halfway along.
            val axis = obb.axes[listOf(obb.extents.x, obb.extents.y, obb.extents.z).let { e -> e.indexOf(e.max()) }]
            val loops = Slicer.sectionLoops(m, obb.center, axis)
            if (loops.size == 1) {
                val lp = loops[0]
                val w2 = lp.maxOf { it.x } - lp.minOf { it.x }; val h2 = lp.maxOf { it.y } - lp.minOf { it.y }
                val a2 = abs(Slicer.loopArea(lp))
                if (abs(w2 - h2) < 0.08 * maxOf(w2, h2) && abs(a2 / (PI / 4 * w2 * h2) - 1) < 0.06) dia = (w2 + h2) / 2
            }
        }
        if (dia != null) {
            t = dia; w = dia
            return PartDescription(PartKind.ROD, t, w, l, fill, "ROD ⌀${d(dia)} X ${d(l)} LG")
        }
        // A stick whose ends are sawn at an angle (a splayed leg cut level top and bottom): its ends are named, with
        // the angle off square each way, so the maker sets the saw; it is not "notched".
        angledEnds(m, obb)?.let { (a1, a2) ->
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            fun deg(v: Double) = (Math.round(v * 2) / 2.0).let { if (it == Math.floor(it)) "${it.toInt()}" else "$it" } + "°"
            // Opposite ways: a mitre across the width (a picture frame) or a bevel across the thickness (a box side).
            val mirrored = mirroredEnds(m, obb)
            val ends = if (a1 >= 0.5 && a2 >= 0.5) "ENDS CUT AT ${deg(a1)} AND ${deg(a2)} (COMPOUND)"
                else if (mirrored) "ENDS CUT AT ${deg(maxOf(a1, a2))} (${if (a1 > a2) "BEVEL" else "MITRE"})" else "ENDS CUT AT ${deg(maxOf(a1, a2))}"
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}, $ends")
        }
        // A strip ripped with the blade tilted along one edge (a French cleat): the same cross-section all along, a
        // rectangle with one side sloped right across the thickness. Named with its angle off square, not "notched".
        bevelledEdge(m, obb)?.let { deg ->
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            val a = (Math.round(deg * 2) / 2.0).let { if (it == Math.floor(it)) "${it.toInt()}" else "$it" } + "°"
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}, ONE EDGE BEVELLED AT $a")
        }
        // A raised panel: named with its edge (the tongue that goes in the grooves) and its bevel, not "shaped".
        raisedPanel(m, obb)?.let { (tongue, bevel) ->
            return PartDescription(PartKind.PANEL, t, w, l, fill, "PANEL ${d(t)} X ${d(w)} X ${d(l)}, RAISED (EDGE ${d(tongue)} THK, BEVEL ${d(bevel)} WIDE)")
        }
        // A stave of a many-sided plinth: both long edges bevelled to meet its neighbours in mitres. Named with the
        // angle (the saw's tilt) and the narrow face, so it is ripped, not traced; "(BEVEL)" marks the mitre for joining.
        bevelledEdges(m, obb)?.let { deg ->
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            val a = (Math.round(deg * 2) / 2.0).let { if (it == Math.floor(it)) "${it.toInt()}" else "$it" } + "°"
            val narrow = w - 2 * t * kotlin.math.tan(Math.toRadians(deg))
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}, LONG EDGES CUT AT $a (BEVEL), NARROW FACE ${d(narrow)}")
        }
        // The outline through the thickness, worked out once for the shape words below.
        val outline by lazy { runCatching { splitziner.edit.Prisms.of(m) }.getOrNull()?.outline }
        // A board with one edge sawn on a slope (a print bin's side): four corners, two of them square, the sloped
        // edge between the long and the short end. Marked and sawn along a straight line, not traced from a template.
        // A right triangle (a gusset, a shelf bracket): a rectangle sawn corner to corner, two from one blank.
        // A regular polygon (a hexagonal plinth's top): named with its sides, marked out with a compass.
        outline?.let { regularPolygon(m, it) }?.let { (n, side) ->
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            val shape = when (n) { 5 -> "PENTAGON"; 6 -> "HEXAGON"; 8 -> "OCTAGON"; else -> "$n-SIDED POLYGON" }
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}, A REGULAR $shape, SIDES ${d(side)}")
        }
        if (outline?.let { rightTriangle(m, it) } == true) {
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}, A RIGHT TRIANGLE")
        }
        // Corners rounded to one radius (a table top's): named with the radius.
        outline?.let { roundedCorners(m, it) }?.let { (n, r) ->
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}, ${if (n == 1) "ONE CORNER" else "$n CORNERS"} ROUNDED TO ${d(r)} RADIUS")
        }
        // Corners sawn off straight (a shelf with its front corners clipped): named with the cut's size.
        outline?.let { clippedCorners(m, it) }?.let { (n, ab) ->
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            val (ca, cb) = ab
            val size = if (kotlin.math.abs(ca - cb) < 0.5) "${d(ca)}" else "${d(minOf(ca, cb))} X ${d(maxOf(ca, cb))}"
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}, ${if (n == 1) "ONE CORNER" else "$n CORNERS"} CUT OFF $size")
        }
        outline?.let { slopedEdge(m, it) }?.let { (lo, hi) ->
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}, ONE EDGE SLOPED FROM ${d(hi)} TO ${d(lo)}", pairW = lo + hi)
        }
        if (fill > SOLID_FILL) {
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            // What is missing from the plain board: round holes are named as holes, anything else as a notch.
            val holes = runCatching { splitziner.features.HoleFinder.find(m) }.getOrDefault(emptyList())
            val drilled = holes.sumOf { Math.PI * it.diameter * it.diameter / 4 * (it.depth ?: 0.0) }
            val missing = t * w * l - vol
            val notch = if (fill < NOTCH_FILL && drilled < 0.9 * missing) ", NOTCHED" else ""
            val holeText = when (holes.size) { 0 -> ""; 1 -> ", 1 HOLE"; else -> ", ${holes.size} HOLES" }
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}$notch$holeText")
        }
        // A stick or board with a notch or a shaped end, sawn right through: still that board, notched.
        // Only when most of the board is left; a part mostly cut away is a shape cut from sheet.
        if (fill > NOTCHED_BOARD_FILL && w < PANEL_MIN_WIDTH && l >= 2.5 * w && m.isWatertight && runCatching { splitziner.edit.Prisms.of(m) }.getOrNull() != null)
            return PartDescription(PartKind.BOARD, t, w, l, fill, "BOARD ${d(t)} X ${d(w)} X ${d(l)}, NOTCHED")
        val round = if (t < 0.3 * w) runCatching { splitziner.drawing.FullSize.circleDiameter(m) }.getOrNull() else null
        val oval = if (round == null && t < 0.3 * w) runCatching { splitziner.drawing.FullSize.oval(m) }.getOrNull() else null
        val desc = if (round != null) "ROUND, ${d(round)} DIA X ${d(t)} THK" else if (oval != null) "OVAL, ${d(oval.first)} X ${d(oval.second)} X ${d(t)} THK" else if (t < 0.3 * w) "SHAPED, ${d(t)} THK, CUT FROM ${d(w)} X ${d(l)}" else "SHAPED, ENVELOPE ${d(t)} X ${d(w)} X ${d(l)}"
        return PartDescription(PartKind.SHAPED, t, w, l, fill, desc)
    }
}

/** Per-item fields the user fills in; keyed by [CutList.itemKey]. */
/** [grain]: the grain must run along the part's length, so the cutting plan never turns it on the sheet. */
data class ItemOverride(val material: String = "", val materialSpec: String = "", val partNo: String = "", val grain: Boolean? = null)

/** Parts whose grain runs along their length unless the user unticks it: what is looked at face on. */
val SHOW_GRAIN = setOf("Door", "Door panel", "Door stile", "Door rail", "Drawer front", "Sliding door", "Lid", "Headboard", "Footboard")

object CutList {
    private val KIND_ORDER = mapOf(PartKind.PANEL to 0, PartKind.BOARD to 1, PartKind.SHAPED to 2, PartKind.ROD to 3)

    private class Group(val d: PartDescription, val vol: Double) { val members = ArrayList<Part>() }

    /** Identifies "the same kind of piece" across re-runs, so user edits survive. */
    fun itemKey(kind: PartKind, t: Double, w: Double, l: Double): String =
        "$kind:${Math.round(t * 2)}:${Math.round(w * 2)}:${Math.round(l * 2)}"

    fun build(parts: List<Part>, fmt: LengthFormat, overrides: Map<String, ItemOverride> = emptyMap()): CutListResult {
        val groups = ArrayList<Group>()
        val warnings = ArrayList<String>()
        for (p in parts) {
            if (p.excluded) continue
            val d = PartDescriber.describe(p.mesh, fmt)
            if (!p.watertight) warnings += "${p.name} has gaps in its surface, so its shape is estimated"
            val vol = abs(p.mesh.volume)
            // Sizes are sorted, so identical parts match in any orientation and when mirrored.
            val g = groups.firstOrNull {
                it.d.kind == d.kind && maxOf(abs(it.d.t - d.t), abs(it.d.w - d.w), abs(it.d.l - d.l)) < 0.5 &&
                    abs(it.vol - vol) <= 0.01 * vol + 1e-6
            } ?: Group(d, vol).also { groups += it }
            g.members += p
        }
        val sorted = groups.sortedWith(compareBy<Group>({ KIND_ORDER[it.d.kind] ?: 9 }, { -it.vol }))
        val d = { v: Double -> fmt.format(v) }
        val rows = ArrayList<CutListRow>()
        val itemOf = HashMap<String, Triple<Int, Int, PartDescription>>()
        sorted.forEachIndexed { i, g ->
            val item = i + 1
            val o = overrides[itemKey(g.d.kind, g.d.t, g.d.w, g.d.l)] ?: ItemOverride()
            for (m in g.members) itemOf[m.id] = Triple(item, g.members.size, g.d)
            rows += CutListRow(
                // Casters are bought, not made: said so, with the height the model gives them.
                item = item, qty = g.members.size, partNo = o.partNo, description = if (g.members.all { it.name == "Caster" }) "${d(g.d.l)} ${if (fmt.isInch) "IN" else "MM"} TALL OVERALL (BUY)" else g.d.desc,
                material = o.material, materialSpec = o.materialSpec,
                stock = "${d(g.d.t)} x ${d(g.d.w)} x ${d(g.d.l)}", kind = g.d.kind, pairW = g.d.pairW,
                // Unless the user says otherwise, the faces people look at have their grain running the long way: a
                // door, its panel and stiles, a drawer front (a plywood door with the grain across looks wrong).
                t = g.d.t, w = g.d.w, l = g.d.l, memberIds = g.members.map { it.id }, grain = o.grain ?: g.members.all { it.name in SHOW_GRAIN || it.name.startsWith("Drawer front") || it.name.startsWith("Door") },
                name = g.members.filter { it.name != it.id }.map { it.name }.distinct().sorted().let { if (it.size <= 3) it.joinToString(" / ") else "" },
            )
        }
        val outParts = parts.map { p ->
            val x = itemOf[p.id] ?: return@map p
            // The material chosen for its item goes with each part, so joining and the guide can tell solid wood from sheet.
            p.copy(kind = x.third.kind, t = x.third.t, w = x.third.w, l = x.third.l, fill = x.third.fill, desc = x.third.desc, item = x.first, qty = x.second,
                material = rows[x.first - 1].material.ifBlank { p.material })
        }
        // Boards and rods are bought by length: total it per cross-section.
        val stock = rows.filter { it.kind == PartKind.BOARD || it.kind == PartKind.ROD }
            .groupBy { Triple(it.kind, d(it.t), d(it.w)) }
            .map { (k, rs) -> StockSummaryRow(k.first, k.second, k.third, d(rs.sumOf { it.l * it.qty }), rs.sumOf { it.qty }) }
        return CutListResult(outParts, rows, stock, warnings)
    }
}
