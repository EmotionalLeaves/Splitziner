package splitziner.edit

import splitziner.check.DesignCheck
import splitziner.math.Vec3
import splitziner.mesh.Bvh
import splitziner.mesh.Mesh
import splitziner.mesh.MeshOps
import splitziner.mesh.OrientedBounds
import kotlin.math.abs

/**
 * Takes apart a solid that was drawn as one piece but would really be built from several plain
 * boards: a frame, an open box, a channel, an L. Works on parts whose faces are square to one
 * another (rounded corners are allowed and come out square). The answer is checked against the
 * part's own volume and refused when the boards do not account for it.
 */
object Boards {
    /** Planes closer than this (mm) are one. */
    private const val SNAP = 0.3

    /** At most this many planes per direction are used; small steps beyond that are ignored. */
    private const val MAX_LINES = 22

    /** More boards than this is not a part made of boards but something carved. */
    const val MAX_BOARDS = 30

    /** The boards must account for the part's volume this closely. */
    private const val VOLUME_MATCH = 0.12

    /** A board in local coordinates of the part's own box: lo/hi along its three axes. */
    class Piece(val lo: Vec3, val hi: Vec3) { val size: Vec3 get() = hi - lo; val volume get() = size.x * size.y * size.z }

    /** [flat]: members cut through the thickness of a flat part (a frame); otherwise boards making up a solid. */
    class Result(val pieces: List<Mesh>, val note: String, val flat: Boolean, val materialShare: Double)

    /** A flat part is taken apart only when its material covers less than this share of its outline's box. */
    const val FRAME_SHARE = 0.6

    /** Pieces smaller than this share of the whole, and thin, are the corners of rounded shapes, not boards. */
    private const val SLIVER = 0.02

    /** Flat parts are those whose thickness is under this share of their width. */
    private const val FLAT = 0.35

    /** The boards the part would be made from, or null with the reason. */
    fun decompose(mesh: Mesh): Pair<Result?, String> {
        val box = OrientedBounds.of(mesh)
        val order = (0..2).sortedBy { box.extents[it] }
        if (box.extents[order[0]] >= FLAT * box.extents[order[1]]) return solid(mesh)
        val f = flat(mesh, box, order)
        // A frame with a step in its thickness (a rebate for a panel): flat members would fill the step in and
        // collide with what sits in it. Two stacked layers of members keep the shape exactly; use those when they fit.
        // The solid method tests material cell by cell, so it sees the step even when the surface has gaps.
        val r = f.first ?: return f
        val sr = solid(mesh).first ?: return f
        val flatVol = r.pieces.sumOf { abs(it.volume) }; val solidVol = sr.pieces.sumOf { abs(it.volume) }
        val trueVol = if (mesh.isWatertight) abs(mesh.volume) else solidVol
        if (solidVol < 0.97 * flatVol && abs(solidVol - trueVol) < 0.05 * trueVol)
            return Result(sr.pieces, "${sr.pieces.size} pieces in layers, which form the step in its thickness", true, r.materialShare) to ""
        return f
    }

    /** A flat part: the outline seen through its thickness, cut into rectangles that each run through the full thickness. */
    private fun flat(mesh: Mesh, box: splitziner.mesh.OrientedBox, order: List<Int>): Pair<Result?, String> {
        val prism = Prisms.along(mesh, box.axes[order[0]], box.axes[order[2]])
        val outline = prism.outline
        if (outline.isEmpty) return null to "it has no outline"
        val env = outline.envelopeInternal
        val share = outline.area / env.area
        if (share >= FRAME_SHARE) return null to "it is a sheet part; cut it out in one piece"
        // Grid lines from the part's own flat faces that stand square to the outline, biggest first: these
        // are where members really begin and end. The many small faces of a rounded corner do not count.
        val xs = ArrayList<Double>(listOf(env.minX, env.maxX)); val ys = ArrayList<Double>(listOf(env.minY, env.maxY))
        run {
            val n = mesh.faceNormals; val c = mesh.faceCenters; val a = mesh.faceAreas
            // Per plane: total area, and area-weighted position, so the plane sits where the faces really are.
            val byX = HashMap<Long, Double>(); val byY = HashMap<Long, Double>(); val atX = HashMap<Long, Double>(); val atY = HashMap<Long, Double>()
            val vx = prism.view.x; val vy = prism.view.up
            for (t in 0 until mesh.triangleCount) {
                val nx = n[3 * t] * vx.x + n[3 * t + 1] * vx.y + n[3 * t + 2] * vx.z
                val ny = n[3 * t] * vy.x + n[3 * t + 1] * vy.y + n[3 * t + 2] * vy.z
                val px = c[3 * t] * vx.x + c[3 * t + 1] * vx.y + c[3 * t + 2] * vx.z
                val py = c[3 * t] * vy.x + c[3 * t + 1] * vy.y + c[3 * t + 2] * vy.z
                if (abs(nx) > 0.9998) { val k = Math.round(px / SNAP); byX[k] = (byX[k] ?: 0.0) + a[t]; atX[k] = (atX[k] ?: 0.0) + a[t] * px }
                if (abs(ny) > 0.9998) { val k = Math.round(py / SNAP); byY[k] = (byY[k] ?: 0.0) + a[t]; atY[k] = (atY[k] ?: 0.0) + a[t] * py }
            }
            // A plane counts when it is a real face: at least 2% of the area the largest such plane has.
            fun keep(m: Map<Long, Double>, at: Map<Long, Double>, out: MutableList<Double>) { val top = m.values.maxOrNull() ?: return; for ((k, v) in m) if (v >= 0.02 * top) out += at.getValue(k) / v }
            keep(byX, atX, xs); keep(byY, atY, ys)
        }
        val diag = Math.hypot(env.width, env.height)
        fun lines(v: List<Double>): DoubleArray { val s = v.sorted(); val out = ArrayList<Double>(); for (x in s) if (out.isEmpty() || x - out.last() > SNAP) out += x; return out.toDoubleArray() }
        val gx = lines(xs); val gy = lines(ys)
        if (gx.size < 2 || gy.size < 2 || gx.size > 40 || gy.size > 40) return null to "its outline is too intricate"
        val gf = splitziner.explode.Silhouette.GF
        val nx = gx.size - 1; val ny = gy.size - 1
        // A cell is material when most of it is inside the outline, so a rounded corner still counts as corner.
        val inside = Array(nx) { i -> BooleanArray(ny) { j ->
            val cell = gf.toGeometry(org.locationtech.jts.geom.Envelope(gx[i], gx[i + 1], gy[j], gy[j + 1]))
            runCatching { cell.intersection(outline).area > 0.5 * cell.area }.getOrDefault(false)
        } }
        val rects = ArrayList<org.locationtech.jts.geom.Envelope>()
        while (rects.size <= MAX_BOARDS) {
            var best: IntArray? = null; var bestArea = 0.0
            for (i0 in 0 until nx) for (j0 in 0 until ny) {
                if (!inside[i0][j0]) continue
                var jMax = ny
                for (i1 in i0 until nx) {
                    var j = j0
                    while (j < jMax && inside[i1][j]) j++
                    jMax = j
                    if (jMax == j0) break
                    val area = (gx[i1 + 1] - gx[i0]) * (gy[jMax] - gy[j0])
                    if (area > bestArea + 1e-9) { bestArea = area; best = intArrayOf(i0, j0, i1, jMax - 1) }
                }
            }
            val b = best ?: break
            for (i in b[0]..b[2]) for (j in b[1]..b[3]) inside[i][j] = false
            rects += org.locationtech.jts.geom.Envelope(gx[b[0]], gx[b[2] + 1], gy[b[1]], gy[b[3] + 1])
        }
        val total0 = rects.sumOf { it.area }
        rects.removeAll { it.area < SLIVER * total0 && minOf(it.width, it.height) < 0.5 * rects.map { r -> minOf(r.width, r.height) }.sorted()[rects.size / 2] }
        if (rects.size < 2) return null to "it is already one plain board"
        if (rects.size > MAX_BOARDS) return null to "it would take more than $MAX_BOARDS boards"
        val covered = rects.sumOf { it.area }
        if (abs(covered - outline.area) > VOLUME_MATCH * outline.area) return null to "the boards would not match its outline (${Math.round(100 * covered / outline.area)}%)"
        val meshes = rects.map { Prisms.toMesh(prism.with(outline = gf.toGeometry(it))) }
        val stepped = mesh.isWatertight && abs(mesh.volume) < 0.9 * outline.area * prism.thickness
        val note = "${rects.size} pieces" + (if (mesh.triangleCount > 12 * rects.size * 3) "; rounded corners become square" else "") + (if (stepped) "; the step in its thickness (a rebate) is cut after" else "")
        return Result(meshes, note, true, share) to ""
    }

    /** A solid that is not flat (an open box, a channel): split into the largest square blocks of material, biggest first. */
    private fun solid(mesh: Mesh): Pair<Result?, String> = solidBody(mesh)


    private fun planes(m: Mesh, k: Int, lo: Double, hi: Double): DoubleArray {
        val n = m.faceNormals; val c = m.faceCenters; val a = m.faceAreas
        val byPos = HashMap<Long, Double>(); val at = HashMap<Long, Double>()
        for (t in 0 until m.triangleCount) if (abs(n[3 * t + k]) > 0.9998) {
            val key = Math.round(c[3 * t + k] / SNAP); byPos[key] = (byPos[key] ?: 0.0) + a[t]; at[key] = (at[key] ?: 0.0) + a[t] * c[3 * t + k]
        }
        // The biggest planes win when there are too many; the two ends of the part always count.
        val top = byPos.entries.sortedByDescending { it.value }.take(MAX_LINES).map { at.getValue(it.key) / it.value }
        val out = (top + lo + hi).sorted().filter { it >= lo - 1e-9 && it <= hi + 1e-9 }
        val merged = ArrayList<Double>()
        for (x in out) if (merged.isEmpty() || x - merged.last() > SNAP) merged += x else if (x == hi) merged[merged.size - 1] = hi
        merged[0] = lo; merged[merged.size - 1] = hi
        return merged.toDoubleArray()
    }

    /** Further than this (mm) outside every board, a point of the original is material the boards left out. */
    private const val LEFT_OUT = 2.0

    /**
     * Material of [mesh] outside every one of [boards]: when each lump of it is round (a turned foot, a dowel standing
     * out), a cylinder for each, from the board it stands on to its end; and whether anything else was left out.
     */
    private fun roundExtras(mesh: Mesh, boards: List<Mesh>): Pair<List<Mesh>, Boolean> {
        val bb = boards.map { it.bounds }
        val pts = (0 until mesh.vertexCount).map { mesh.vertex(it) }.filter { p -> bb.none { b -> (0..2).all { k -> p[k] >= b.min[k] - LEFT_OUT && p[k] <= b.max[k] + LEFT_OUT } } }
        if (pts.isEmpty()) return emptyList<Mesh>() to false
        // Lumps: points within a few millimetres of each other.
        val n = pts.size; val parent = IntArray(n) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        val order = pts.indices.sortedBy { pts[it].x }
        for (a in order.indices) { var c = a + 1
            while (c < order.size && pts[order[c]].x - pts[order[a]].x <= 6.0) { if ((pts[order[a]] - pts[order[c]]).length <= 25.0) parent[root(order[a])] = root(order[c]); c++ } }
        val lumps = pts.indices.groupBy { root(it) }.values.map { g -> g.map { pts[it] } }
        val out = ArrayList<Mesh>()
        val all = bb.reduce { x, y -> x.union(y) }
        for (l in lumps) {
            if (l.size < 6) continue
            val lb = l.drop(1).fold(splitziner.math.Aabb(l[0], l[0])) { acc, p -> acc.union(splitziner.math.Aabb(p, p)) }
            // The way it stands out: the axis on which it lies beyond the boards.
            // Only what stands out beyond the boards altogether; points between them inside (a hole's rim, a fillet) are as before.
            val k = (0..2).firstOrNull { k -> lb.min[k] >= all.max[k] - LEFT_OUT || lb.max[k] <= all.min[k] + LEFT_OUT } ?: continue
            val (u, v) = (0..2).filter { it != k }
            val cu = (lb.min[u] + lb.max[u]) / 2; val cv = (lb.min[v] + lb.max[v]) / 2
            val r = l.map { Math.hypot(it[u] - cu, it[v] - cv) }
            val ring = l.filter { kotlin.math.abs(Math.hypot(it[u] - cu, it[v] - cv) - r.max()) < 0.05 * r.max() }
            if (r.max() < 3.0 || kotlin.math.abs(lb.extents[u] - lb.extents[v]) > 0.06 * maxOf(lb.extents[u], lb.extents[v]) || ring.size < 0.6 * l.size) return emptyList<Mesh>() to true
            // From the face of the board it stands on to its far end.
            val below = lb.max[k] <= all.min[k] + LEFT_OUT
            val far = if (below) lb.min[k] else lb.max[k]
            val face = bb.filter { b -> b.min[u] <= cu && b.max[u] >= cu && b.min[v] <= cv && b.max[v] >= cv }.let { hs -> if (below) hs.minOfOrNull { it.min[k] } else hs.maxOfOrNull { it.max[k] } } ?: return emptyList<Mesh>() to true
            out += cylinder(k, cu, cv, minOf(face, far), maxOf(face, far), r.max())
        }
        return out to false
    }

    /** A cylinder along axis [k] centred on ([cu], [cv]) in the other two, from [a] to [b]. */
    private fun cylinder(k: Int, cu: Double, cv: Double, a: Double, b: Double, r: Double, n: Int = 24): Mesh {
        val (u, w) = (0..2).filter { it != k }
        fun pt(t: Double, s: Double, along: Double) = DoubleArray(3).also { it[u] = cu + r * kotlin.math.cos(t); it[w] = cv + r * kotlin.math.sin(t); it[k] = along }
        val v = ArrayList<Double>(); val tri = ArrayList<Int>()
        for (i in 0 until n) { val t = 2 * Math.PI * i / n; for (z in listOf(a, b)) pt(t, 0.0, z).forEach { v += it } }
        val c0 = 2 * n; val c1 = 2 * n + 1
        DoubleArray(3).also { it[u] = cu; it[w] = cv; it[k] = a }.forEach { v += it }
        DoubleArray(3).also { it[u] = cu; it[w] = cv; it[k] = b }.forEach { v += it }
        for (i in 0 until n) { val a0 = 2 * i; val a1 = 2 * i + 1; val b0 = 2 * ((i + 1) % n); val b1 = b0 + 1
            tri += listOf(c0, b0, a0, c1, a1, b1, a0, b0, b1, a0, b1, a1) }
        return MeshOps.fixWinding(Mesh(v.toDoubleArray(), tri.toIntArray()))
    }

    private fun solidBody(mesh: Mesh): Pair<Result?, String> {
        val box = OrientedBounds.of(mesh)
        val ax = box.axes
        // Into the part's own frame, so a frame standing at an angle is handled like any other.
        val local = DoubleArray(mesh.vertices.size)
        for (i in 0 until mesh.vertexCount) { val p = mesh.vertex(i) - box.center; for (k in 0..2) local[3 * i + k] = p dot ax[k] }
        val m = Mesh(local, mesh.triangles)
        val b = m.bounds
        val g = Array(3) { k -> planes(m, k, b.min[k], b.max[k]) }
        val nx = g[0].size - 1; val ny = g[1].size - 1; val nz = g[2].size - 1
        if (nx < 1 || ny < 1 || nz < 1) return null to "it has no flat faces to work from"
        val bvh = Bvh(m)
        // A cell is material when most of five points in it are: a small hole through its middle (a bolt hole in a
        // shelf) does not empty the whole cell.
        val inside = Array(nx) { i -> Array(ny) { j -> BooleanArray(nz) { k ->
            fun at(fx: Double, fy: Double, fz: Double) = DesignCheck.inside(bvh, Vec3(g[0][i] + fx * (g[0][i + 1] - g[0][i]), g[1][j] + fy * (g[1][j + 1] - g[1][j]), g[2][k] + fz * (g[2][k + 1] - g[2][k])))
            listOf(at(0.5, 0.5, 0.5), at(0.25, 0.25, 0.25), at(0.75, 0.75, 0.25), at(0.25, 0.75, 0.75), at(0.75, 0.25, 0.75)).count { it } >= 3
        } } }
        val pieces = ArrayList<Piece>()
        val used = Array(nx) { Array(ny) { BooleanArray(nz) } }
        // Prefix counts of free material cells, for an O(1) "is this whole box material" test.
        while (pieces.size <= MAX_BOARDS) {
            val pre = Array(nx + 1) { Array(ny + 1) { IntArray(nz + 1) } }
            var any = false
            for (i in 0 until nx) for (j in 0 until ny) for (k in 0 until nz) {
                val v = if (inside[i][j][k] && !used[i][j][k]) 1 else 0
                if (v == 1) any = true
                pre[i + 1][j + 1][k + 1] = v + pre[i][j + 1][k + 1] + pre[i + 1][j][k + 1] + pre[i + 1][j + 1][k] - pre[i][j][k + 1] - pre[i][j + 1][k] - pre[i + 1][j][k] + pre[i][j][k]
            }
            if (!any) break
            fun count(i0: Int, i1: Int, j0: Int, j1: Int, k0: Int, k1: Int) =
                pre[i1][j1][k1] - pre[i0][j1][k1] - pre[i1][j0][k1] - pre[i1][j1][k0] + pre[i0][j0][k1] + pre[i0][j1][k0] + pre[i1][j0][k0] - pre[i0][j0][k0]
            var best: IntArray? = null; var bestVol = 0.0
            for (i0 in 0 until nx) for (i1 in i0 + 1..nx) for (j0 in 0 until ny) for (j1 in j0 + 1..ny) {
                val area = (g[0][i1] - g[0][i0]) * (g[1][j1] - g[1][j0])
                if (area * (b.max.z - b.min.z) <= bestVol) continue
                // The longest run along the third axis that is all material.
                var k0 = 0
                for (k in 0..nz) {
                    val full = k < nz && count(i0, i1, j0, j1, k, k + 1) == (i1 - i0) * (j1 - j0)
                    if (!full) {
                        if (k > k0) { val v = area * (g[2][k] - g[2][k0]); if (v > bestVol) { bestVol = v; best = intArrayOf(i0, i1, j0, j1, k0, k) } }
                        k0 = k + 1
                    }
                }
            }
            val q = best ?: break
            for (i in q[0] until q[1]) for (j in q[2] until q[3]) for (k in q[4] until q[5]) used[i][j][k] = true
            pieces += Piece(Vec3(g[0][q[0]], g[1][q[2]], g[2][q[4]]), Vec3(g[0][q[1]], g[1][q[3]], g[2][q[5]]))
        }
        if (pieces.size <= MAX_BOARDS) {
            val tot = pieces.sumOf { it.volume }
            val med = pieces.map { minOf(it.size.x, it.size.y, it.size.z) }.sorted()[pieces.size / 2]
            pieces.removeAll { it.volume < SLIVER * tot && minOf(it.size.x, it.size.y, it.size.z) < 0.5 * med }
        }
        if (pieces.size < 2) return null to "it is already one plain board"
        if (pieces.size > MAX_BOARDS) return null to "it would take more than $MAX_BOARDS boards; it is a carved or machined shape"
        val total = pieces.sumOf { it.volume }
        val vol = abs(mesh.volume)
        // A sealed surface gives a true volume to check against; a leaky one only the cells found inside.
        if (mesh.isWatertight && abs(total - vol) > VOLUME_MATCH * vol) return null to "the boards would not match its shape (${Math.round(100 * total / vol)}% of its volume)"
        if (pieces.any { minOf(it.size.x, it.size.y, it.size.z) < 1.0 }) return null to "some pieces would be thinner than 1 mm"
        val meshes = pieces.map { pc ->
            val c = arrayOf(pc.lo, pc.hi)
            val v = DoubleArray(24)
            for (n in 0 until 8) {
                val p = box.center + ax[0] * c[n and 1].x + ax[1] * c[(n shr 1) and 1].y + ax[2] * c[(n shr 2) and 1].z
                v[3 * n] = p.x; v[3 * n + 1] = p.y; v[3 * n + 2] = p.z
            }
            // Corner order as Editor.box: 0 (lo), 1 (+x), 2 (+x+y), 3 (+y), then the same at +z.
            val order = intArrayOf(0, 1, 3, 2, 4, 5, 7, 6)
            val vv = DoubleArray(24) { v[3 * order[it / 3] + it % 3] }
            MeshOps.fixWinding(Mesh(vv, intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)))
        }
        // Round parts standing out of the boards (turned feet under a cabinet) have no flat faces to make cells from, so
        // the grid misses them: kept as round pieces of their own. Anything else left out stops the change.
        val (extras, missed) = roundExtras(mesh, meshes)
        if (missed) return null to "it has curved parts the boards would leave out"
        val rounded = mesh.triangleCount > 12 * pieces.size * 3
        if (extras.isNotEmpty()) return Result(meshes + extras, "${pieces.size} boards and ${extras.size} round ${if (extras.size == 1) "piece" else "pieces"}" + if (rounded) "; rounded corners become square" else "", false, total / (b.extents.x * b.extents.y * b.extents.z)) to ""
        return Result(meshes, "${pieces.size} boards" + if (rounded) "; rounded corners become square" else "", false, total / (b.extents.x * b.extents.y * b.extents.z)) to ""
    }
}
