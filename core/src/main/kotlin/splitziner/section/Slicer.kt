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

/** A full section of an assembly: what is left behind the cutting plane, and each part's cut face. */
class SectionGeometry(
    /** Section letter: "A" for SECTION A-A. */
    val letter: String,
    /** Model axis the cutting plane is perpendicular to, and where it sits on that axis. */
    val axis: Int,
    val at: Double,
    /** Cut faces in view coordinates, tagged with the index of the part they belong to. */
    val faces: List<Pair<Int, org.locationtech.jts.geom.Geometry>>,
    val lines: splitziner.view.ViewLines,
)

object SectionBuilder {
    private val GF = org.locationtech.jts.geom.GeometryFactory()

    /**
     * Where to cut along axis [k]: near the middle, but not on a face (coincident faces give ragged
     * sections) and through big members rather than small ones.
     */
    fun cutPosition(meshes: List<Mesh>, k: Int): Double {
        val lo = meshes.map { it.bounds.min[k] }; val hi = meshes.map { it.bounds.max[k] }
        val l0 = lo.min(); val l1 = hi.max()
        val span = maxOf(l1 - l0, 1e-9); val tol = 1e-3 * span
        var best = l0 + span / 2; var bestScore = Double.MAX_VALUE
        for (i in 0..40) {
            val z = l0 + span * (0.3 + 0.4 * i / 40.0)
            var coinc = 0; var small = 0
            for (j in meshes.indices) {
                if (abs(lo[j] - z) < 2 * tol || abs(hi[j] - z) < 2 * tol) coinc++
                if (lo[j] < z - tol && hi[j] > z + tol && hi[j] - lo[j] < 0.1 * span) small++
            }
            val score = 10.0 * coinc + 3.0 * small + abs(z - (l0 + l1) / 2) / span
            if (score < bestScore - 1e-9) { bestScore = score; best = z }
        }
        return best
    }

    /** The cut face of one mesh as a polygon (with holes) in the view's own 2D coordinates. */
    fun sectionPolygons(mesh: Mesh, origin: Vec3, view: splitziner.view.ViewDef): org.locationtech.jts.geom.Geometry? {
        val segs = Slicer.sectionSegments(mesh, origin, view.n)
        if (segs.isEmpty()) return null
        val q = 1e-6
        fun r(v: Double) = Math.rint(v / q) * q
        val lines = ArrayList<org.locationtech.jts.geom.Geometry>()
        for (i in 0 until segs.size / 6) {
            val a = view.project(Vec3(segs[6 * i], segs[6 * i + 1], segs[6 * i + 2])); val b = view.project(Vec3(segs[6 * i + 3], segs[6 * i + 4], segs[6 * i + 5]))
            val ca = org.locationtech.jts.geom.Coordinate(r(a.x), r(a.y)); val cb = org.locationtech.jts.geom.Coordinate(r(b.x), r(b.y))
            if (ca.equals2D(cb)) continue
            lines += GF.createLineString(arrayOf(ca, cb))
        }
        if (lines.isEmpty()) return null
        return try {
            val noded = org.locationtech.jts.operation.union.UnaryUnionOp.union(lines)
            val pz = org.locationtech.jts.operation.polygonize.Polygonizer()
            pz.add(noded)
            // Nested loops alternate between material and hole, which symmetric difference reproduces.
            var area: org.locationtech.jts.geom.Geometry? = null
            for (p in pz.polygons) { val g = p as org.locationtech.jts.geom.Geometry; area = area?.symDifference(g) ?: g }
            area
        } catch (e: Exception) { null }
    }

    /**
     * The part of [mesh] behind the plane (away from the viewer), with the opening closed by a cap.
     * Without the cap, sight lines from edges at the back would escape through the opening and
     * hidden edges would be drawn as visible.
     */
    fun clipBehind(mesh: Mesh, origin: Vec3, view: splitziner.view.ViewDef, face: org.locationtech.jts.geom.Geometry?): Mesh {
        val n = view.n
        val d0 = (origin dot n) - 1e-5 * mesh.scale
        val verts = ArrayList<Double>(); val tris = ArrayList<Int>()
        fun add(p: Vec3): Int { verts += p.x; verts += p.y; verts += p.z; return verts.size / 3 - 1 }
        for (t in 0 until mesh.triangleCount) {
            val p = Array(3) { mesh.vertex(mesh.triangles[3 * t + it]) }
            val d = DoubleArray(3) { (p[it] dot n) - d0 }
            val keep = ArrayList<Vec3>(4)
            for (k in 0..2) {
                val a = p[k]; val b = p[(k + 1) % 3]; val da = d[k]; val db = d[(k + 1) % 3]
                if (da <= 0) keep += a
                if ((da <= 0) != (db <= 0)) keep += a + (b - a) * (da / (da - db))
            }
            if (keep.size < 3) continue
            val ids = keep.map { add(it) }
            for (k in 1 until ids.size - 1) { tris += ids[0]; tris += ids[k]; tris += ids[k + 1] }
        }
        if (face != null && !face.isEmpty) {
            try {
                val tri = org.locationtech.jts.triangulate.polygon.ConstrainedDelaunayTriangulator.triangulate(face)
                for (i in 0 until tri.numGeometries) {
                    val c = tri.getGeometryN(i).coordinates
                    if (c.size < 4) continue
                    // Back to 3D: a point at (x, y) in the view's frame lies at x*X + y*UP + d0*N.
                    val q = (0..2).map { view.x * c[it].x + view.up * c[it].y + n * d0 }
                    val ccw = (c[1].x - c[0].x) * (c[2].y - c[0].y) - (c[2].x - c[0].x) * (c[1].y - c[0].y) > 0
                    val ids = q.map { add(it) }
                    // The cap faces the viewer.
                    if (ccw) { tris += ids[0]; tris += ids[1]; tris += ids[2] } else { tris += ids[0]; tris += ids[2]; tris += ids[1] }
                }
            } catch (e: Exception) { /* an uncapped section still draws; it may show a few extra lines */ }
        }
        // Merging vertices joins the pieces back into shared edges, so the seams inside flat faces
        // (between the triangles a face was cut into, and inside the cap) are not mistaken for edges to draw.
        return splitziner.mesh.MeshOps.mergeVertices(Mesh(verts.toDoubleArray(), tris.toIntArray()), 2e-6 * maxOf(mesh.scale, 1.0))
    }

    fun build(meshes: List<Mesh>, view: splitziner.view.ViewDef, letter: String): SectionGeometry? {
        val k = if (abs(view.n.x) > 0.5) 0 else if (abs(view.n.y) > 0.5) 1 else 2
        val at = cutPosition(meshes, k)
        val origin = Vec3.axis(k) * at
        val on = view.n dot origin
        val kept = ArrayList<Mesh>(); val faces = ArrayList<Pair<Int, org.locationtech.jts.geom.Geometry>>()
        for ((i, m) in meshes.withIndex()) {
            var dMin = Double.MAX_VALUE; var dMax = -Double.MAX_VALUE
            for (v in 0 until m.vertexCount) { val d = (m.vertex(v) dot view.n) - on; if (d < dMin) dMin = d; if (d > dMax) dMax = d }
            if (dMin >= -1e-9) continue                       // entirely in front of the plane: cut away
            if (dMax <= 1e-9) { kept += m; continue }         // entirely behind: kept whole
            val face = sectionPolygons(m, origin, view)
            val half = clipBehind(m, origin, view, face)
            if (half.triangleCount > 0) kept += half
            if (face != null && !face.isEmpty) faces += i to face
        }
        if (kept.isEmpty() || faces.isEmpty()) return null
        val scene = splitziner.mesh.MeshOps.concatenate(kept)
        val lines = splitziner.view.HiddenLine.compute(kept, scene, view)
        // A section shows no hidden lines.
        return SectionGeometry(letter, k, at, faces, splitziner.view.ViewLines(lines.visible, DoubleArray(0)))
    }
}

/** Section lining: the general-purpose pattern of evenly spaced thin lines, whatever the material. */
object Hatch {
    private val ANGLES = doubleArrayOf(45.0, 135.0, 30.0, 120.0, 60.0, 150.0)
    private val GF = org.locationtech.jts.geom.GeometryFactory()

    /**
     * Draws the cut faces. [toPaper] maps view coordinates to the sheet and [s] is the drawing scale.
     * Touching parts are lined at different angles; faces too thin to line are filled solid.
     */
    fun draw(dl: splitziner.sheet.DisplayList, faces: List<Pair<Int, org.locationtech.jts.geom.Geometry>>, s: Double, toPaper: (Double, Double) -> DoubleArray) {
        val used = ArrayList<Pair<org.locationtech.jts.geom.Geometry, Double>>()
        val o = toPaper(0.0, 0.0)
        val tx = org.locationtech.jts.geom.util.AffineTransformation().scale(s, s).translate(o[0], o[1])
        faces.forEachIndexed { idx, (_, face) ->
            val pa = tx.transform(face)
            val near = used.filter { it.first.distance(pa) < 0.3 }.map { it.second }.toSet()
            val angle = ANGLES.firstOrNull { it !in near } ?: ANGLES[idx % ANGLES.size]
            used += pa to angle
            for (gi in 0 until pa.numGeometries) {
                val g = pa.getGeometryN(gi) as? org.locationtech.jts.geom.Polygon ?: continue
                if (g.area <= 0) continue
                // Mean width of the face on paper.
                val w = 2 * g.area / maxOf(g.length, 1e-9)
                if (w < 2.5) {
                    // Too thin to line: fill it, shrunk a hair so neighbouring thin parts stay distinct.
                    val gg = g.buffer(-0.15)
                    for (k in 0 until gg.numGeometries) {
                        val q = gg.getGeometryN(k) as? org.locationtech.jts.geom.Polygon ?: continue
                        if (q.area <= 0) continue
                        dl.fill(q.exteriorRing.coordinates.flatMap { listOf(it.x, it.y) }.toDoubleArray())
                        for (h in 0 until q.numInteriorRing) dl.fill(q.getInteriorRingN(h).coordinates.flatMap { listOf(it.x, it.y) }.toDoubleArray(), 0xFFFFFFFF.toInt())
                    }
                } else {
                    val spacing = if (w > 6) 2.5 else 1.5
                    val c = g.centroid.coordinate
                    val env = g.envelopeInternal
                    val r = Math.hypot(env.width, env.height)
                    val rad = Math.toRadians(angle); val ux = Math.cos(rad); val uy = Math.sin(rad)
                    val out = ArrayList<Double>()
                    var off = -r
                    while (off <= r) {
                        // A line through the centre, shifted sideways by [off], long enough to cross the whole face.
                        val px = c.x - uy * off; val py = c.y + ux * off
                        val ln = GF.createLineString(arrayOf(org.locationtech.jts.geom.Coordinate(px - ux * r, py - uy * r), org.locationtech.jts.geom.Coordinate(px + ux * r, py + uy * r)))
                        val cut = try { g.intersection(ln) } catch (e: Exception) { null }
                        if (cut != null) for (k in 0 until cut.numGeometries) {
                            val piece = cut.getGeometryN(k)
                            if (piece is org.locationtech.jts.geom.LineString && piece.length > 0) {
                                val cs = piece.coordinates
                                out += cs.first().x; out += cs.first().y; out += cs.last().x; out += cs.last().y
                            }
                        }
                        off += spacing
                    }
                    dl.segs(out.toDoubleArray(), splitziner.sheet.LineStyle.HATCH)
                    // The outline of the cut face itself.
                    dl.polyline(g.exteriorRing.coordinates.flatMap { listOf(it.x, it.y) }.toDoubleArray(), splitziner.sheet.LineStyle.THICK, true)
                }
            }
        }
    }
}
