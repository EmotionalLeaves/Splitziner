package splitziner.split

import splitziner.io.RawModel
import splitziner.math.Aabb
import splitziner.math.Vec3
import splitziner.mesh.DisjointSet
import splitziner.mesh.LongIntMap
import splitziner.mesh.Mesh
import splitziner.mesh.MeshOps
import splitziner.mesh.PointIndexer
import splitziner.mesh.edgeKey
import splitziner.parts.Part
import kotlin.math.abs

data class SplitReport(
    val faces: Int,
    val degenerateFaces: Int,
    /** How the bodies were told apart. */
    val method: String,
    val units: UnitGuess,
    /** Bodies stored in the file, duplicates included. */
    val bodies: Int,
    val parts: Int,
    val duplicatesRemoved: Int,
    val watertightParts: Int,
    val tJunctionFixedParts: Int,
    val warnings: List<String>,
)

class SplitResult(val parts: List<Part>, val report: SplitReport)

/**
 * Separates an STL that holds a whole assembly into its parts.
 *
 * Exporters usually write each body's triangles one after another with no shared vertices,
 * sometimes several times over. A closed surface uses every edge an even number of times, so
 * walking the file in order and cutting wherever the running surface closes recovers the bodies
 * without fusing parts that merely touch.
 */
object BodySplitter {
    const val METHOD_FILE_ORDER = "file order (each body closes)"
    const val METHOD_MARKED = "part marks in the file"
    const val METHOD_COMPONENTS = "connected components"
    const val METHOD_SINGLE = "single body"

    /** A body needs at least a tetrahedron's worth of triangles. */
    private const val MIN_FACES = 4

    /** Method 1 is trusted only if almost nothing is left over after the last closed body. */
    private const val MAX_LEFTOVER = 0.02

    /** [unitsOverride] is "mm", "cm", "in" or "m" when the user has chosen; null to guess. */
    fun split(raw: RawModel, unitsOverride: String? = null, displayOverride: String? = null): SplitResult {
        val nF = raw.triangleCount
        val all = raw.bounds()
        val sc0 = maxOf(all.extents.x, all.extents.y, all.extents.z).let { if (it > 0) it else 1.0 }

        // Indexing has its own function so its large lookup tables are freed when it returns.
        val ix = index(raw, sc0)
        val verts = ix.verts; val ut = ix.ut; val mult = ix.mult; val nU = ix.nU; val degenerate = ix.degenerate; val warningsIn = ix.warnings

        var method = METHOD_FILE_ORDER
        // The file says which triangles make each part: take its word.
        val marked = ix.group?.let { g -> (0 until nU).groupBy { g[it] }.values.map { it.toIntArray() }.takeIf { it.size >= 2 } }
        var groups = marked ?: splitByFileOrder(verts, ut, nU, sc0, all.center)
        if (marked != null) method = METHOD_MARKED
        if (groups == null) {
            groups = splitByComponents(verts, ut, nU, sc0)
            method = if (groups.size >= 2) METHOD_COMPONENTS else METHOD_SINGLE
        }
        // A body's copy count is how often its triangles were stored (the middle value, to shrug off strays).
        val groupCopies = groups.map { g -> g.map { mult[it] }.sorted()[g.size / 2] }

        // Build one mesh per body from the shared vertex ids.
        val remap = IntArray(verts.size / 3) { -1 }
        val rawBodies = ArrayList<Mesh?>(); val bodyCopies = ArrayList<Int>()
        for ((i, faces) in groups.withIndex()) { val m = bodyMesh(verts, ut, faces, remap); if (m.triangleCount > 0) { rawBodies += m; bodyCopies += groupCopies[i] } }

        val bodyBounds = rawBodies.fold(null as Aabb?) { acc, m -> acc?.union(m!!.bounds) ?: m!!.bounds } ?: all
        val ext = bodyBounds.extents
        val size = maxOf(ext.x, ext.y, ext.z).let { if (it > 0) it else 1.0 }
        var units = if (unitsOverride != null) UnitGuess.fixed(unitsOverride, size)
        else UnitGuesser.guess(UnitGuesser.lengthsOf(rawBodies.map { it!! }, size), size, mmOnly = raw.unitsKnown)
        if (displayOverride != null) units = units.copy(designUnits = displayOverride)

        var fixed = 0
        val cleaned = ArrayList<Mesh>()
        val cleanedCopies = ArrayList<Int>()
        for (bi in rawBodies.indices) {
            var m = MeshOps.scaled(rawBodies[bi]!!, units.scale)
            // Each stage replaces the last; the earlier copies are let go at once.
            rawBodies[bi] = null
            if (!m.isWatertight) {
                val f = TJunctionFixer.fix(m)
                if (f.isWatertight || f.triangleCount >= m.triangleCount) { if (f.isWatertight) fixed++; m = f }
            }
            if (m.isWatertight) m = MeshOps.fixWinding(m)
            if (m.triangleCount >= MIN_FACES) { cleaned += m; cleanedCopies += bodyCopies[bi] }
        }

        // The same body stored more than once is one part, not several.
        val unique = ArrayList<Mesh>()
        val copies = ArrayList<Int>()
        val buckets = HashMap<Long, MutableList<Int>>()
        var removed = 0
        for ((ci, m) in cleaned.withIndex()) {
            val tl = 1e-4 * maxOf(m.scale, 1e-9)
            val key = bucketKey(m, tl * 100)
            var twin = -1
            for (u in buckets[key] ?: emptyList<Int>()) if (sameBody(unique[u], m, tl)) { twin = u; break }
            if (twin < 0) for (u in unique.indices.reversed()) {
                // Bucket edges can separate near-identical bodies; fall back to a bounded scan.
                if (unique.size - u > 64) break
                if (sameBody(unique[u], m, tl)) { twin = u; break }
            }
            if (twin >= 0) { copies[twin] = copies[twin] + cleanedCopies[ci]; removed += cleanedCopies[ci] }
            else { buckets.getOrPut(key) { ArrayList() } += unique.size; unique += m; copies += cleanedCopies[ci]; removed += cleanedCopies[ci] - 1 }
        }

        val parts = unique.mapIndexed { i, m ->
            val id = "P%02d".format(i + 1)
            Part(id = id, name = id, mesh = m, copies = copies[i])
        }
        val warnings = ArrayList(warningsIn)
        if (removed > 0) warnings += "$removed coincident duplicate bodies removed (same body stored more than once in the file)"
        val open = parts.count { !it.watertight }
        if (open > 0) warnings += "$open part(s) have gaps in their surface; their volume and cut faces may be unreliable"
        val report = SplitReport(
            faces = nF, degenerateFaces = degenerate, method = method, units = units,
            bodies = parts.size + removed, parts = parts.size, duplicatesRemoved = removed,
            watertightParts = parts.size - open, tJunctionFixedParts = fixed, warnings = warnings,
        )
        return SplitResult(parts, report)
    }

    private fun volumeOf(verts: DoubleArray, ut: IntArray, first: Int, last: Int, o: Vec3): Double {
        var s = 0.0
        for (t in first until last) {
            val a = 3 * ut[3 * t]; val b = 3 * ut[3 * t + 1]; val c = 3 * ut[3 * t + 2]
            val ax = verts[a] - o.x; val ay = verts[a + 1] - o.y; val az = verts[a + 2] - o.z
            val bx = verts[b] - o.x; val by = verts[b + 1] - o.y; val bz = verts[b + 2] - o.z
            val cx = verts[c] - o.x; val cy = verts[c + 1] - o.y; val cz = verts[c + 2] - o.z
            s += ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)
        }
        return s / 6
    }

    /** Returns each body's triangle indices, or null when the file is not laid out body by body. */
    private fun splitByFileOrder(verts: DoubleArray, ut: IntArray, nU: Int, sc0: Double, origin: Vec3): List<IntArray>? {
        val parity = LongIntMap(1024)
        var odd = 0
        var start = 0
        val ranges = ArrayList<IntArray>()
        val minVol = 1e-9 * sc0 * sc0 * sc0
        for (i in 0 until nU) {
            for (k in 0..2) {
                // Toggle: an edge seen an odd number of times is still open.
                val now = parity.add(edgeKey(ut[3 * i + k], ut[3 * i + (k + 1) % 3]), 1)
                if (now % 2 == 1) odd++ else odd--
            }
            if (odd == 0 && i + 1 - start >= MIN_FACES && abs(volumeOf(verts, ut, start, i + 1, origin)) > minVol) {
                ranges += intArrayOf(start, i + 1); start = i + 1
            }
        }
        if (ranges.size < 2 || nU - start > MAX_LEFTOVER * nU) return null
        if (start < nU) ranges[ranges.size - 1][1] = nU
        return ranges.map { r -> IntArray(r[1] - r[0]) { r[0] + it } }
    }

    /**
     * Groups triangles joined through edges that exactly two triangles share. Pieces left open by
     * T-junctions (one piece's corner sitting on another piece's open edge) are then joined, so a
     * body whose faces were written with mismatched edges still comes out whole.
     */
    private fun splitByComponents(verts: DoubleArray, ut: IntArray, nU: Int, sc0: Double): List<IntArray> {
        val edgeIndex = LongIntMap(nU * 3 / 2 + 16)
        var cnt = IntArray(nU * 3 / 2 + 16); var f0 = IntArray(cnt.size); var f1 = IntArray(cnt.size); var ea = IntArray(cnt.size); var eb = IntArray(cnt.size)
        var n = 0
        for (t in 0 until nU) for (k in 0..2) {
            val a = ut[3 * t + k]; val b = ut[3 * t + (k + 1) % 3]
            val key = edgeKey(a, b)
            var e = edgeIndex.get(key)
            if (e < 0) {
                e = n++; edgeIndex.put(key, e)
                if (e >= cnt.size) { val c = cnt.size * 2; cnt = cnt.copyOf(c); f0 = f0.copyOf(c); f1 = f1.copyOf(c); ea = ea.copyOf(c); eb = eb.copyOf(c) }
                f0[e] = t; ea[e] = a; eb[e] = b
            } else if (cnt[e] == 1) f1[e] = t
            cnt[e]++
        }
        val ds = DisjointSet(nU)
        for (e in 0 until n) if (cnt[e] == 2) ds.union(f0[e], f1[e])

        // An edge shared by four or more triangles is where separate bodies touch (two boards face to
        // face share all four edges of the touching face). Each triangle is paired with its true
        // neighbour: the first one met when turning about the edge into the solid behind it. That
        // keeps the touching bodies apart while keeping each one whole.
        val crowded = HashMap<Int, MutableList<Int>>()
        for (t in 0 until nU) for (k in 0..2) {
            val e = edgeIndex.get(edgeKey(ut[3 * t + k], ut[3 * t + (k + 1) % 3]))
            if (cnt[e] >= 3) crowded.getOrPut(e) { ArrayList() } += t
        }
        for ((e, tris) in crowded) {
            val a = Vec3(verts[3 * ea[e]], verts[3 * ea[e] + 1], verts[3 * ea[e] + 2]); val b = Vec3(verts[3 * eb[e]], verts[3 * eb[e] + 1], verts[3 * eb[e] + 2])
            val u = (b - a).normalized()
            val e1 = ((if (abs(u.x) < 0.9) Vec3.axis(0) else Vec3.axis(1)) cross u).normalized()
            val e2 = u cross e1
            val forward = BooleanArray(tris.size); val angle = DoubleArray(tris.size)
            for ((i, t) in tris.withIndex()) {
                var third = -1
                for (k in 0..2) {
                    val p = ut[3 * t + k]; val q = ut[3 * t + (k + 1) % 3]
                    if (p == ea[e] && q == eb[e]) { forward[i] = true; third = ut[3 * t + (k + 2) % 3] }
                    if (p == eb[e] && q == ea[e]) { forward[i] = false; third = ut[3 * t + (k + 2) % 3] }
                }
                val c = Vec3(verts[3 * third], verts[3 * third + 1], verts[3 * third + 2]) - a
                val w = c - u * (c dot u)
                angle[i] = Math.atan2(w dot e2, w dot e1)
            }
            for (i in tris.indices) {
                if (!forward[i]) continue
                // For a triangle running a->b its outside is toward increasing angle, so its solid lies toward decreasing angle.
                var best = -1; var bestTurn = Double.MAX_VALUE
                for (j in tris.indices) {
                    if (forward[j]) continue
                    var turn = angle[i] - angle[j]
                    while (turn < 1e-7) turn += 2 * Math.PI     // a triangle lying exactly on this one is the last to be met, not the first
                    if (turn < bestTurn) { bestTurn = turn; best = j }
                }
                if (best >= 0) ds.union(tris[i], tris[best])
            }
        }

        // T-junctions: an open edge of one piece with another piece's open-edge vertex lying along it.
        val open = (0 until n).filter { cnt[it] == 1 }
        if (open.isNotEmpty() && open.size < 400_000) {
            val tol = 1e-6 * sc0
            val cell = maxOf(sc0 / 64, tol * 10)
            fun c(v: Double) = Math.floor(v / cell).toLong()
            fun gk(x: Long, y: Long, z: Long) = (x and 0x1fffff) or ((y and 0x1fffff) shl 21) or ((z and 0x1fffff) shl 42)
            val grid = HashMap<Long, MutableList<Int>>()        // open-edge vertices, each with the triangle it belongs to
            val owner = HashMap<Int, Int>()
            for (e in open) for (v in intArrayOf(ea[e], eb[e])) if (owner.put(v, f0[e]) == null) {
                grid.getOrPut(gk(c(verts[3 * v]), c(verts[3 * v + 1]), c(verts[3 * v + 2]))) { ArrayList() } += v
            }
            for (e in open) {
                val a = Vec3(verts[3 * ea[e]], verts[3 * ea[e] + 1], verts[3 * ea[e] + 2]); val b = Vec3(verts[3 * eb[e]], verts[3 * eb[e] + 1], verts[3 * eb[e] + 2])
                val len = (b - a).length
                if (len <= tol) continue
                val u = (b - a) / len
                for (x in c(minOf(a.x, b.x) - tol)..c(maxOf(a.x, b.x) + tol)) for (y in c(minOf(a.y, b.y) - tol)..c(maxOf(a.y, b.y) + tol)) for (z in c(minOf(a.z, b.z) - tol)..c(maxOf(a.z, b.z) + tol)) {
                    for (v in grid[gk(x, y, z)] ?: continue) {
                        if (v == ea[e] || v == eb[e]) continue
                        val p = Vec3(verts[3 * v], verts[3 * v + 1], verts[3 * v + 2])
                        val t = (p - a) dot u
                        if (t <= tol || t >= len - tol) continue
                        if ((p - a - u * t).length < tol) ds.union(f0[e], owner.getValue(v))
                    }
                }
            }
        }

        val byRoot = LinkedHashMap<Int, MutableList<Int>>()
        for (t in 0 until nU) byRoot.getOrPut(ds.find(t)) { ArrayList() } += t
        val comps = byRoot.values.map { it.toIntArray() }
        return if (comps.size >= 2 && comps.size <= maxOf(2, nU / 8)) comps else listOf(IntArray(nU) { it })
    }

    private class Indexed(val verts: DoubleArray, val ut: IntArray, val mult: IntArray, val nU: Int, val degenerate: Int, val warnings: List<String>, val group: IntArray?)

    private fun index(raw: RawModel, sc0: Double): Indexed {
        val nF = raw.triangleCount
        // Vertices that land on the same point of a very fine grid are the same vertex.
        val cell = sc0 * 1e-7
        // A closed surface has about half as many vertices as triangles; the tables grow if there are more.
        val indexer = PointIndexer(maxOf(nF / 2, 16))
        val fi = IntArray(nF * 3)
        var verts = DoubleArray(maxOf(nF / 2, 8) * 3)
        for (i in 0 until nF * 3) {
            val before = indexer.size
            val x = raw.coord(3 * i); val y = raw.coord(3 * i + 1); val z = raw.coord(3 * i + 2)
            val id = indexer.idOf(Math.round(x / cell), Math.round(y / cell), Math.round(z / cell))
            if (id == before) {
                if (3 * id + 3 > verts.size) verts = verts.copyOf(verts.size * 2)
                verts[3 * id] = x; verts[3 * id + 1] = y; verts[3 * id + 2] = z
            }
            fi[i] = id
        }
        verts = verts.copyOf(indexer.size * 3)

        val areas = FloatArray(nF) { raw.area(it).toFloat() }
        // Everything needed from the raw triangles is in [verts], [fi] and [areas] now.
        val warnings = raw.warnings
        val groupsIn = raw.groups
        raw.release()
        val maxArea = (areas.maxOrNull() ?: 0f).toDouble()
        val degenerate = (0 until nF).count { areas[it] <= maxArea * 1e-10 }

        // Exporters sometimes write every body two or three times over. Exact repeats are dropped
        // triangle by triangle before anything else, so each body is seen once. Only triangles with
        // the same winding count as repeats: two boards stacked face to face share triangles that
        // face opposite ways, and both must be kept.
        val triIndex = PointIndexer(nF)
        val firstOf = IntArray(nF)             // the file triangle each unique triangle was first seen as
        var ut = IntArray(nF * 3)              // unique triangles' vertex ids, in first-seen order
        var mult = IntArray(nF)                // how many times each was stored
        var nU = 0
        for (t in 0 until nF) {
            if (areas[t] <= maxArea * 1e-10) continue
            var a = fi[3 * t]; var b = fi[3 * t + 1]; var c = fi[3 * t + 2]
            if (a == b || b == c || c == a) continue
            // Rotate so the smallest id leads; the cyclic order (and so the winding) is kept.
            if (b < a && b < c) { val x = a; a = b; b = c; c = x } else if (c < a && c < b) { val x = a; a = c; c = b; b = x }
            val before = triIndex.size
            val id = triIndex.idOf(a.toLong(), b.toLong(), c.toLong())
            if (id == before) { ut[3 * id] = fi[3 * t]; ut[3 * id + 1] = fi[3 * t + 1]; ut[3 * id + 2] = fi[3 * t + 2]; nU = id + 1; firstOf[id] = t }
            mult[id]++
        }
        ut = ut.copyOf(nU * 3); mult = mult.copyOf(nU)
        val group = groupsIn?.let { g -> IntArray(nU) { g[firstOf[it]] } }

        return Indexed(verts, ut, mult, nU, degenerate, warnings, group)
    }

    private fun bodyMesh(verts: DoubleArray, fi: IntArray, faces: IntArray, remap: IntArray): Mesh {
        // [remap] is shared between bodies and handed back all -1, so building many bodies costs no extra memory.
        var v = DoubleArray(maxOf(faces.size / 2, 4) * 3); var nv = 0
        val tris = IntArray(faces.size * 3)
        for ((j, t) in faces.withIndex()) for (k in 0..2) {
            val g = fi[3 * t + k]
            var local = remap[g]
            if (local < 0) {
                local = nv++; remap[g] = local
                if (3 * nv > v.size) v = v.copyOf(v.size * 2)
                v[3 * local] = verts[3 * g]; v[3 * local + 1] = verts[3 * g + 1]; v[3 * local + 2] = verts[3 * g + 2]
            }
            tris[3 * j + k] = local
        }
        for (t in faces) for (k in 0..2) remap[fi[3 * t + k]] = -1
        return MeshOps.removeDegenerate(Mesh(v.copyOf(3 * nv), tris))
    }

    private fun bucketKey(m: Mesh, q: Double): Long {
        val c = m.bounds.center
        val step = if (q > 0) q else 1.0
        return (Math.round(c.x / step) * 73856093L) xor (Math.round(c.y / step) * 19349663L) xor (Math.round(c.z / step) * 83492791L)
    }

    private fun sameBody(a: Mesh, b: Mesh, tl: Double): Boolean {
        val ba = a.bounds; val bb = b.bounds
        for (k in 0..2) if (abs(ba.min[k] - bb.min[k]) >= tl || abs(ba.max[k] - bb.max[k]) >= tl) return false
        if (abs(abs(a.volume) - abs(b.volume)) > 1e-4 * abs(b.volume) + 1e-9) return false
        return abs(a.area - b.area) <= 1e-4 * b.area
    }
}
