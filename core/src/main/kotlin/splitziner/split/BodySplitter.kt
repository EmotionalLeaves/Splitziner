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
    const val METHOD_COMPONENTS = "connected components"
    const val METHOD_SINGLE = "single body"

    /** A body needs at least a tetrahedron's worth of triangles. */
    private const val MIN_FACES = 4

    /** Method 1 is trusted only if almost nothing is left over after the last closed body. */
    private const val MAX_LEFTOVER = 0.02

    /** [unitsOverride] is "mm", "cm", "in" or "m" when the user has chosen; null to guess. */
    fun split(raw: RawModel, unitsOverride: String? = null, displayOverride: String? = null): SplitResult {
        val soup = raw.soup
        val nF = raw.triangleCount
        val all = Aabb.of(soup)
        val sc0 = maxOf(all.extents.x, all.extents.y, all.extents.z).let { if (it > 0) it else 1.0 }

        // Vertices that land on the same point of a very fine grid are the same vertex.
        val cell = sc0 * 1e-7
        val indexer = PointIndexer(nF)
        val fi = IntArray(nF * 3)
        var verts = DoubleArray(maxOf(nF, 8) * 3)
        for (i in 0 until nF * 3) {
            val before = indexer.size
            val id = indexer.idOf(Math.round(soup[3 * i] / cell), Math.round(soup[3 * i + 1] / cell), Math.round(soup[3 * i + 2] / cell))
            if (id == before) {
                if (3 * id + 3 > verts.size) verts = verts.copyOf(verts.size * 2)
                verts[3 * id] = soup[3 * i]; verts[3 * id + 1] = soup[3 * i + 1]; verts[3 * id + 2] = soup[3 * i + 2]
            }
            fi[i] = id
        }

        val areas = DoubleArray(nF) { MeshOps.triangleArea(soup, it) }
        val maxArea = areas.maxOrNull() ?: 0.0
        val good = BooleanArray(nF) { areas[it] > maxArea * 1e-10 }
        val degenerate = good.count { !it }

        var method = METHOD_FILE_ORDER
        var groups = splitByFileOrder(soup, fi, good, nF, sc0, all.center)
        if (groups == null) {
            groups = splitByComponents(fi, good, nF)
            method = if (groups.size >= 2) METHOD_COMPONENTS else METHOD_SINGLE
        }

        // Build one mesh per body from the shared vertex ids.
        val rawBodies = groups.map { faces -> bodyMesh(verts, fi, faces) }.filter { it.triangleCount > 0 }

        val bodyBounds = rawBodies.fold(null as Aabb?) { acc, m -> acc?.union(m.bounds) ?: m.bounds } ?: all
        val ext = bodyBounds.extents
        val size = maxOf(ext.x, ext.y, ext.z).let { if (it > 0) it else 1.0 }
        var units = if (unitsOverride != null) UnitGuess.fixed(unitsOverride, size)
        else UnitGuesser.guess(UnitGuesser.lengthsOf(rawBodies, size), size)
        if (displayOverride != null) units = units.copy(designUnits = displayOverride)

        var fixed = 0
        val cleaned = ArrayList<Mesh>()
        for (b in rawBodies) {
            var m = MeshOps.scaled(b, units.scale)
            if (!m.isWatertight) {
                val f = TJunctionFixer.fix(m)
                if (f.isWatertight || f.triangleCount >= m.triangleCount) { if (f.isWatertight) fixed++; m = f }
            }
            if (m.isWatertight) m = MeshOps.fixWinding(m)
            if (m.triangleCount >= MIN_FACES) cleaned += m
        }

        // The same body stored more than once is one part, not several.
        val unique = ArrayList<Mesh>()
        val copies = ArrayList<Int>()
        val buckets = HashMap<Long, MutableList<Int>>()
        var removed = 0
        for (m in cleaned) {
            val tl = 1e-4 * maxOf(m.scale, 1e-9)
            val key = bucketKey(m, tl * 100)
            var twin = -1
            for (u in buckets[key] ?: emptyList<Int>()) if (sameBody(unique[u], m, tl)) { twin = u; break }
            if (twin < 0) for (u in unique.indices.reversed()) {
                // Bucket edges can separate near-identical bodies; fall back to a bounded scan.
                if (unique.size - u > 64) break
                if (sameBody(unique[u], m, tl)) { twin = u; break }
            }
            if (twin >= 0) { copies[twin] = copies[twin] + 1; removed++ }
            else { buckets.getOrPut(key) { ArrayList() } += unique.size; unique += m; copies += 1 }
        }

        val parts = unique.mapIndexed { i, m ->
            val id = "P%02d".format(i + 1)
            Part(id = id, name = id, mesh = m, copies = copies[i])
        }
        val warnings = ArrayList(raw.warnings)
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

    /** Returns each body's triangle indices, or null when the file is not laid out body by body. */
    private fun splitByFileOrder(soup: DoubleArray, fi: IntArray, good: BooleanArray, nF: Int, sc0: Double, origin: Vec3): List<IntArray>? {
        val parity = LongIntMap(1024)
        var odd = 0
        var start = 0
        var nz = 0
        val ranges = ArrayList<IntArray>()
        val minVol = 1e-9 * sc0 * sc0 * sc0
        for (i in 0 until nF) {
            for (k in 0..2) {
                val a = fi[3 * i + k]; val b = fi[3 * i + (k + 1) % 3]
                if (a == b) continue
                // Toggle: an edge seen an odd number of times is still open.
                val now = parity.add(edgeKey(a, b), 1)
                if (now % 2 == 1) odd++ else odd--
            }
            if (good[i]) nz++
            if (odd == 0 && nz >= MIN_FACES) {
                if (abs(MeshOps.soupVolume(soup, start, i + 1, good, origin)) > minVol) {
                    ranges += intArrayOf(start, i + 1); start = i + 1; nz = 0
                }
            }
        }
        if (ranges.size < 2 || nF - start > MAX_LEFTOVER * nF) return null
        if (start < nF) ranges[ranges.size - 1][1] = nF
        return ranges.map { r -> (r[0] until r[1]).filter { good[it] }.toIntArray() }
    }

    /** Groups triangles joined through edges that exactly two triangles share. */
    private fun splitByComponents(fi: IntArray, good: BooleanArray, nF: Int): List<IntArray> {
        val edgeIndex = LongIntMap(nF * 3 / 2 + 16)
        var cnt = IntArray(nF * 3 / 2 + 16); var f0 = IntArray(cnt.size); var f1 = IntArray(cnt.size)
        var n = 0
        var goodCount = 0
        for (t in 0 until nF) {
            if (!good[t]) continue
            goodCount++
            for (k in 0..2) {
                val a = fi[3 * t + k]; val b = fi[3 * t + (k + 1) % 3]
                if (a == b) continue
                val key = edgeKey(a, b)
                var e = edgeIndex.get(key)
                if (e < 0) {
                    e = n++; edgeIndex.put(key, e)
                    if (e >= cnt.size) { cnt = cnt.copyOf(cnt.size * 2); f0 = f0.copyOf(cnt.size); f1 = f1.copyOf(cnt.size) }
                    f0[e] = t
                } else if (cnt[e] == 1) f1[e] = t
                cnt[e]++
            }
        }
        val ds = DisjointSet(nF)
        // Edges shared by more than two triangles join nothing, so boxes touching along an edge stay apart.
        for (e in 0 until n) if (cnt[e] == 2) ds.union(f0[e], f1[e])
        val byRoot = LinkedHashMap<Int, MutableList<Int>>()
        for (t in 0 until nF) if (good[t]) byRoot.getOrPut(ds.find(t)) { ArrayList() } += t
        val comps = byRoot.values.map { it.toIntArray() }
        return if (comps.size >= 2 && comps.size <= maxOf(2, goodCount / 8)) comps
        else listOf((0 until nF).filter { good[it] }.toIntArray())
    }

    private fun bodyMesh(verts: DoubleArray, fi: IntArray, faces: IntArray): Mesh {
        val remap = HashMap<Int, Int>(faces.size * 2)
        val v = ArrayList<Double>(faces.size * 3)
        val tris = IntArray(faces.size * 3)
        for ((j, t) in faces.withIndex()) for (k in 0..2) {
            val g = fi[3 * t + k]
            val local = remap.getOrPut(g) { val id = v.size / 3; v += verts[3 * g]; v += verts[3 * g + 1]; v += verts[3 * g + 2]; id }
            tris[3 * j + k] = local
        }
        return MeshOps.removeDegenerate(Mesh(v.toDoubleArray(), tris))
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
