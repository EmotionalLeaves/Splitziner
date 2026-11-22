package splitziner.edit

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import splitziner.math.Vec3
import splitziner.mesh.Mesh
import splitziner.mesh.MeshOps
import splitziner.mesh.OrientedBounds
import splitziner.parts.Part
import splitziner.section.SectionBuilder
import kotlin.math.abs

/** One change to the model. Changes are kept as a list and replayed on the original file, which is never altered. */
sealed class Edit {
    /** Slide parts by [d] (mm). */
    data class Move(val ids: List<String>, val d: Vec3) : Edit()
    /** Make parts [t] mm thick, growing away from whatever they rest against. */
    data class Thickness(val ids: List<String>, val t: Double) : Edit()
    /** Replace parts by plain rectangular blocks of the same outer size. */
    data class SquareUp(val ids: List<String>) : Edit()
    /** Make several overlapping parts of one thickness into a single part. It keeps the first one's id. */
    data class Merge(val ids: List<String>) : Edit()
    /** Cut away the piece of [id] that [by] passes through. */
    data class Trim(val id: String, val by: String) : Edit()
    /** Set a part's outer size: thickness, width, length (mm), about its centre. */
    data class Resize(val id: String, val t: Double, val w: Double, val l: Double) : Edit()
    /** Add a rectangular part with one corner at [min]. */
    data class AddBox(val id: String, val name: String, val min: Vec3, val size: Vec3) : Edit()
    /** Another copy of a part, shifted by [d]. */
    data class Duplicate(val id: String, val newId: String, val d: Vec3) : Edit()
    data class Delete(val ids: List<String>) : Edit()
    /** Cut [id] back so it stops at the face of [by] instead of running into it: a butt joint. */
    data class CutBack(val id: String, val by: String) : Edit()
    /** Replace each part by the plain boards it would be built from (see [Boards]). New pieces get ids "<id>.2", "<id>.3"... */
    data class SplitBoards(val ids: List<String>) : Edit()
    /**
     * [id] runs into [into] on purpose: a groove (housing) is cut in [into] to take it. Nothing is moved; the
     * overlap in the model is the groove. The check stops calling it a clash, and the guide and drawings say so.
     */
    data class House(val id: String, val into: String) : Edit()
    /**
     * Make the whole model longer (or shorter) along [axis] (0 x, 1 y, 2 z) by [by] mm at the plane [at]: every point
     * beyond the plane moves by [by]. Parts the plane crosses grow, parts beyond it move, the rest stay as they are,
     * so board thicknesses and joints are kept.
     */
    data class Stretch(val axis: Int, val at: Double, val by: Double) : Edit()
    /** Turn parts a quarter turn [turns] times about [axis] (0 x, 1 y, 2 z) through the centre of their box, staying on the same floor. */
    data class Rotate(val ids: List<String>, val axis: Int, val turns: Int) : Edit()
}

/** [housed] are (part, the part grooved to take it) pairs, for the parts that still exist. */
class EditResult(val parts: List<Part>, val notes: List<String>, val housed: List<Pair<String, String>> = emptyList())

object Editor {
    /** At most this share of a part may be cut away by a trim; more than that is a design decision, not a tidy-up. */
    const val MAX_TRIM_SHARE = 0.25

    /**
     * Where to stretch the model along [axis]: the middle of the widest stretch between 25% and 75% of its size that
     * no corner of any part lies in, so only plain lengths of board are cut across. Returns the plane and how much the
     * model may be shortened there (the width of that clear stretch), or null if every position crosses a corner.
     */
    fun stretchPlane(parts: List<Part>, axis: Int): Pair<Double, Double>? {
        val live = parts.filter { !it.excluded }
        if (live.isEmpty()) return null
        val cs = live.flatMap { p -> (0 until p.mesh.vertexCount).map { p.mesh.vertices[3 * it + axis] } }.sorted()
        val lo = cs.first(); val hi = cs.last(); val a = lo + 0.25 * (hi - lo); val b = lo + 0.75 * (hi - lo)
        var best: Pair<Double, Double>? = null
        val pts = listOf(a) + cs.filter { it > a && it < b } + listOf(b)
        for (i in 0 until pts.size - 1) {
            val gap = pts[i + 1] - pts[i]
            if (gap > 1.0 && (best == null || gap > best.second)) best = (pts[i] + pts[i + 1]) / 2 to gap
        }
        // The clear stretch may reach past 25-75% only on its own sides; shortening by its width keeps every corner where it was relative to its neighbours.
        return best?.let { (at, gap) -> at to gap * 0.9 }
    }

    /**
     * A shelf [t] mm thick with its top at [z]: spanning between the two upright parts either side of [x] (the
     * middle of the model when null), as deep as both reach front to back. Null when there are no uprights both
     * sides at that height, or the space is already taken.
     */
    fun shelfAt(parts: List<Part>, z: Double, t: Double, x: Double? = null, pins: Boolean = false): Pair<Vec3, Vec3>? =
        fillAt(parts, 2, 0, z, t, x)?.let { (mn, sz) -> if (pins) Vec3(mn.x + PIN_GAP, mn.y, mn.z) to Vec3(sz.x - 2 * PIN_GAP, sz.y, sz.z) else mn to sz }

    /** An adjustable shelf is this much short at each end (mm), so it drops in onto its pins and lifts out again. */
    const val PIN_GAP = 1.5

    /** An upright divider [t] mm thick centred at [x], between the shelves (or top and bottom) above and below the middle. */
    fun dividerAt(parts: List<Part>, x: Double, t: Double, z: Double? = null): Pair<Vec3, Vec3>? = fillAt(parts, 0, 2, x + t / 2, t, z)

    /**
     * A board thin along axis [a], its far face at [pos] along [a], spanning along axis [b] between the nearest parts
     * either side of [at] (the middle when null) that are thin along [b], and front to back (Y) as far as both reach.
     */
    private fun fillAt(parts: List<Part>, a: Int, b: Int, pos: Double, t: Double, at: Double?): Pair<Vec3, Vec3>? {
        val live = parts.filter { !it.excluded }
        if (live.isEmpty()) return null
        val all = live.map { it.mesh.bounds }
        fun lo(i: Int, k: Int) = all[i].min[k]
        fun hi(i: Int, k: Int) = all[i].max[k]
        val c = at ?: (all.minOf { it.min[b] } + all.maxOf { it.max[b] }) / 2
        val p0 = pos - t
        val supports = live.indices.filter { i -> val e = all[i].extents; e[b] < 0.3 * minOf(e[(b + 1) % 3], e[(b + 2) % 3]) && lo(i, a) <= p0 && hi(i, a) >= pos }
        val s0 = supports.filter { hi(it, b) <= c }.maxByOrNull { hi(it, b) } ?: return null
        val s1 = supports.filter { lo(it, b) >= c }.minByOrNull { lo(it, b) } ?: return null
        val b0 = hi(s0, b); val b1 = lo(s1, b)
        var y0 = maxOf(lo(s0, 1), lo(s1, 1)); var y1 = minOf(hi(s0, 1), hi(s1, 1))
        val mid = (y0 + y1) / 2
        for (bb in all) if (bb.min[b] < b1 - 0.5 && bb.max[b] > b0 + 0.5 && bb.min[a] < pos - 0.5 && bb.max[a] > p0 + 0.5 && bb.extents.y < 0.2 * (y1 - y0)) {
            if (bb.min.y >= mid) y1 = minOf(y1, bb.min.y) else if (bb.max.y <= mid) y0 = maxOf(y0, bb.max.y)
        }
        if (b1 - b0 < 50.0 || y1 - y0 < 50.0) return null
        val clash = all.any { bb -> bb.min[b] < b1 - 0.5 && bb.max[b] > b0 + 0.5 && bb.min.y < y1 - 0.5 && bb.max.y > y0 + 0.5 && bb.min[a] < pos - 0.5 && bb.max[a] > p0 + 0.5 }
        if (clash) return null
        val mn = DoubleArray(3); val sz = DoubleArray(3)
        mn[a] = p0; sz[a] = t; mn[b] = b0; sz[b] = b1 - b0; mn[1] = y0; sz[1] = y1 - y0
        return Vec3(mn[0], mn[1], mn[2]) to Vec3(sz[0], sz[1], sz[2])
    }

    /**
     * An inset door for the opening round the point ([x], [z]) on the front: between the uprights left and right and
     * the parts below and above, [gap] mm clear all round, [t] thick, its face flush with the front of the opening.
     */
    fun doorAt(parts: List<Part>, x: Double, z: Double, t: Double, gap: Double = 2.0): Pair<Vec3, Vec3>? {
        val live = parts.filter { !it.excluded }
        if (live.isEmpty()) return null
        val all = live.map { it.mesh.bounds }
        fun thin(i: Int, k: Int): Boolean { val e = all[i].extents; return e[k] < 0.3 * minOf(e[(k + 1) % 3], e[(k + 2) % 3]) }
        val left = live.indices.filter { thin(it, 0) && all[it].max.x <= x && all[it].min.z <= z && all[it].max.z >= z }.maxByOrNull { all[it].max.x } ?: return null
        val right = live.indices.filter { thin(it, 0) && all[it].min.x >= x && all[it].min.z <= z && all[it].max.z >= z }.minByOrNull { all[it].min.x } ?: return null
        val x0 = all[left].max.x; val x1 = all[right].min.x
        val below = live.indices.filter { thin(it, 2) && all[it].max.z <= z && all[it].min.x < x1 && all[it].max.x > x0 }.maxByOrNull { all[it].max.z } ?: return null
        val above = live.indices.filter { thin(it, 2) && all[it].min.z >= z && all[it].min.x < x1 && all[it].max.x > x0 }.minByOrNull { all[it].min.z } ?: return null
        val z0 = all[below].max.z; val z1 = all[above].min.z
        val front = minOf(all[left].min.y, all[right].min.y)
        if (x1 - x0 < 100.0 || z1 - z0 < 100.0) return null
        val mn = Vec3(x0 + gap, front, z0 + gap); val sz = Vec3(x1 - x0 - 2 * gap, t, z1 - z0 - 2 * gap)
        // Nothing may be in the way (a drawer front, a rail).
        val clash = all.any { bb -> bb.min.x < mn.x + sz.x && bb.max.x > mn.x && bb.min.y < mn.y + sz.y && bb.max.y > mn.y && bb.min.z < mn.z + sz.z && bb.max.z > mn.z }
        return if (clash) null else mn to sz
    }

    fun box(min: Vec3, size: Vec3): Mesh {
        val a = min; val b = min + size
        return Mesh(
            doubleArrayOf(a.x, a.y, a.z, b.x, a.y, a.z, b.x, b.y, a.z, a.x, b.y, a.z, a.x, a.y, b.z, b.x, a.y, b.z, b.x, b.y, b.z, a.x, b.y, b.z),
            intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7),
        )
    }

    private fun fresh(p: Part, mesh: Mesh) = Part(p.id, p.name, mesh, p.copies, excluded = p.excluded, material = p.material)

    /** What cutting [a] around [b] would leave, and the share of [a] it removes; null when [b] does not pass right through [a]. */
    fun trimmed(a: Part, b: Part): Pair<Prism, Double>? {
        val pa = Prisms.of(a.mesh) ?: return null
        val (blo, bhi) = Prisms.range(b.mesh, pa.view.n)
        // The other part must cross the whole thickness; a part that only dips in would need a recess, which an outline cannot show.
        if (blo > pa.lo + 0.3 || bhi < pa.hi - 0.3) return null
        val mid = (pa.lo + pa.hi) / 2
        // What the other part occupies in this part's plane. A flat piece lying the same way is its own outline, which
        // survives gaps in its surface; anything else is sliced, falling back to its shadow if the slice does not close.
        val pb = Prisms.of(b.mesh)
        val cut = if (pb != null && abs(pb.view.n dot pa.view.n) > 0.999) Prisms.along(b.mesh, pa.view.n, pa.view.x).outline
            else SectionBuilder.sectionPolygons(b.mesh, pa.view.n * mid, pa.view)?.takeIf { !it.isEmpty } ?: Prisms.along(b.mesh, pa.view.n, pa.view.x).outline
        val rest = runCatching { pa.outline.difference(cut.buffer(0.0)) }.getOrNull() ?: return null
        val area = pa.outline.area
        if (area <= 0 || rest.isEmpty) return null
        val share = 1 - rest.area / area
        // The part must stay in one piece.
        val pieces = (0 until rest.numGeometries).map { rest.getGeometryN(it) }.filter { it is Polygon && it.area > 1e-4 * area }
        if (pieces.size != 1 || share < 1e-6) return null
        return pa.with(outline = pieces[0]) to share
    }

    /**
     * [a] cut back to the face of [b] it runs into, and the share of [a] removed; null when [a] does not
     * enter [b] from one side. The side it enters from is the one with the shallowest overlap.
     */
    fun cutBack(a: Part, b: Part): Pair<Mesh, Double>? {
        val la = a.mesh.bounds; val lb = b.mesh.bounds
        var best: Triple<Int, Boolean, Double>? = null
        for (k in 0..2) {
            val ov = minOf(la.max[k], lb.max[k]) - maxOf(la.min[k], lb.min[k])
            if (ov <= 0) return null
            // Enters from below (a starts before b) or from above (a ends after b), not both.
            val below = la.min[k] < lb.min[k] - 1e-6 && la.max[k] <= lb.max[k] + 1e-6
            val above = la.max[k] > lb.max[k] + 1e-6 && la.min[k] >= lb.min[k] - 1e-6
            if (below == above) continue
            if (best == null || ov < best.third) best = Triple(k, below, ov)
        }
        val (k, below, _) = best ?: return null
        val axis = splitziner.math.Vec3.axis(k)
        // Keep the side of the face that [a] comes from.
        val n = if (below) axis else -axis
        val origin = if (below) axis * lb.min[k] else axis * lb.max[k]
        val view = splitziner.view.ViewDef("cut", n, splitziner.math.Vec3.axis((k + 1) % 3))
        val face = splitziner.section.SectionBuilder.sectionPolygons(a.mesh, origin, view)
        val cut = splitziner.section.SectionBuilder.clipBehind(a.mesh, origin, view, face)
        if (cut.triangleCount == 0) return null
        val v0 = abs(a.mesh.volume)
        val share = if (v0 > 0) 1 - abs(cut.volume) / v0 else 1.0
        return cut to share
    }

    fun apply(parts: List<Part>, edits: List<Edit>): EditResult {
        var cur = parts.map { fresh(it, it.mesh) }
        val notes = ArrayList<String>()
        val housed = ArrayList<Pair<String, String>>()
        for (e in edits) {
            try {
                cur = when (e) {
                    is Edit.Move -> cur.map { if (it.id in e.ids) fresh(it, MeshOps.translated(it.mesh, e.d)) else it }
                    is Edit.Delete -> cur.filter { it.id !in e.ids }
                    is Edit.SquareUp -> cur.map { p ->
                        if (p.id !in e.ids) p else {
                            val b = OrientedBounds.of(p.mesh)
                            val prism = Prisms.along(p.mesh, b.axes[2], b.axes[0])
                            val (x0, x1) = Prisms.range(p.mesh, prism.view.x); val (y0, y1) = Prisms.range(p.mesh, prism.view.up)
                            val rect = splitziner.explode.Silhouette.GF.toGeometry(org.locationtech.jts.geom.Envelope(x0, x1, y0, y1))
                            fresh(p, Prisms.toMesh(prism.with(outline = rect)))
                        }
                    }
                    is Edit.Thickness -> cur.map { p -> if (p.id in e.ids) fresh(p, thick(p, e.t, cur)) else p }
                    is Edit.Resize -> cur.map { p ->
                        if (p.id != e.id) p else {
                            val b = OrientedBounds.of(p.mesh)
                            val order = (0..2).sortedBy { b.extents[it] }
                            val want = doubleArrayOf(e.t, e.w, e.l)
                            val v = DoubleArray(p.mesh.vertices.size)
                            for (i in 0 until p.mesh.vertexCount) {
                                val q = p.mesh.vertex(i) - b.center
                                var r = b.center
                                for ((n, k) in order.withIndex()) { val f = if (b.extents[k] > 1e-9) want[n] / b.extents[k] else 1.0; r += b.axes[k] * ((q dot b.axes[k]) * f) }
                                v[3 * i] = r.x; v[3 * i + 1] = r.y; v[3 * i + 2] = r.z
                            }
                            fresh(p, Mesh(v, p.mesh.triangles))
                        }
                    }
                    is Edit.AddBox -> cur + Part(e.id, e.name, box(e.min, e.size))
                    is Edit.Duplicate -> cur.firstOrNull { it.id == e.id }?.let { cur + Part(e.newId, it.name, MeshOps.translated(it.mesh, e.d), material = it.material) } ?: cur
                    is Edit.Merge -> merge(cur, e, notes)
                    is Edit.House -> { housed += e.id to e.into; cur }
                    is Edit.Rotate -> cur.map { p ->
                        if (p.id !in e.ids) p else {
                            val m = p.mesh; val b = m.bounds; val c = b.center
                            val v = m.vertices.copyOf()
                            val (i, j) = when (e.axis) { 0 -> 1 to 2; 1 -> 2 to 0; else -> 0 to 1 }
                            val k = ((e.turns % 4) + 4) % 4
                            for (n in 0 until m.vertexCount) repeat(k) {
                                val ci = if (i == 0) c.x else if (i == 1) c.y else c.z; val cj = if (j == 0) c.x else if (j == 1) c.y else c.z
                                val a = v[3 * n + i] - ci; val bb = v[3 * n + j] - cj
                                v[3 * n + i] = ci - bb; v[3 * n + j] = cj + a
                            }
                            // Keep it resting at the same height: its lowest point stays where it was.
                            var minZ = Double.MAX_VALUE; for (n in 0 until m.vertexCount) minZ = minOf(minZ, v[3 * n + 2])
                            val dz = b.min.z - minZ; for (n in 0 until m.vertexCount) v[3 * n + 2] += dz
                            fresh(p, Mesh(v, m.triangles))
                        }
                    }
                    is Edit.Stretch -> cur.map { p ->
                        val m = p.mesh
                        if (m.bounds.max[e.axis] <= e.at) p else {
                            val v = m.vertices.copyOf()
                            for (i in 0 until m.vertexCount) if (v[3 * i + e.axis] > e.at) v[3 * i + e.axis] += e.by
                            fresh(p, Mesh(v, m.triangles))
                        }
                    }
                    is Edit.CutBack -> {
                        val a = cur.firstOrNull { it.id == e.id }; val b = cur.firstOrNull { it.id == e.by }
                        val r = if (a != null && b != null) cutBack(a, b) else null
                        if (r == null) { notes += "${e.id} could not be cut back to ${e.by}"; cur }
                        else cur.map { if (it.id == e.id) fresh(it, r.first) else it }
                    }
                    is Edit.SplitBoards -> cur.flatMap { p ->
                        if (p.id !in e.ids) listOf(p) else {
                            val (r, why) = Boards.decompose(p.mesh)
                            if (r == null) { notes += "${p.id} could not be split into boards: $why"; listOf(p) }
                            else r.pieces.mapIndexed { i, m -> Part(if (i == 0) p.id else "${p.id}.${i + 1}", p.name, m, p.copies, material = p.material) }
                        }
                    }
                    is Edit.Trim -> {
                        val a = cur.firstOrNull { it.id == e.id }; val b = cur.firstOrNull { it.id == e.by }
                        val t = if (a != null && b != null) trimmed(a, b) else null
                        if (t == null) { notes += "${e.id} could not be trimmed around ${e.by}"; cur }
                        else cur.map { if (it.id == e.id) fresh(it, Prisms.toMesh(t.first)) else it }
                    }
                }
            } catch (ex: Exception) {
                notes += "A change could not be applied (${e::class.simpleName}): ${ex.message}"
            }
        }
        val ids = cur.map { it.id }.toSet()
        return EditResult(cur, notes, housed.filter { it.first in ids && it.second in ids }.distinct())
    }

    private fun merge(cur: List<Part>, e: Edit.Merge, notes: MutableList<String>): List<Part> {
        val group = e.ids.mapNotNull { id -> cur.firstOrNull { it.id == id } }
        if (group.size < 2) return cur
        val first = Prisms.of(group[0].mesh)
        var merged: Mesh? = null
        if (first != null) {
            // Every member seen along the first one's thickness: they join cleanly only if they share that thickness.
            val all = group.map { Prisms.along(it.mesh, first.view.n, first.view.x) }
            if (all.all { abs(it.lo - first.lo) < 0.6 && abs(it.hi - first.hi) < 0.6 }) {
                var u: Geometry = all[0].outline
                for (p in all.drop(1)) u = u.union(p.outline)
                merged = Prisms.toMesh(first.with(outline = u.buffer(0.0)))
            }
        }
        if (merged == null) { merged = MeshOps.concatenate(group.map { it.mesh }); notes += "${e.ids.joinToString(", ")} were joined as they are; they are not flat pieces of one thickness" }
        val keep = group[0]
        return cur.filter { it.id !in e.ids || it.id == keep.id }.map { if (it.id == keep.id) fresh(it, merged) else it }
    }

    /** The part at a new thickness. The face lying against more of its neighbours stays where it is. */
    private fun thick(p: Part, t: Double, all: List<Part>): Mesh {
        val box = OrientedBounds.of(p.mesh)
        val k = (0..2).minByOrNull { box.extents[it] }!!
        val a = Prisms.canonical(box.axes[k])
        val (lo, hi) = Prisms.range(p.mesh, a)
        val old = hi - lo
        if (old <= 1e-9 || abs(old - t) < 1e-9) return p.mesh
        // How much of each broad face has a neighbour against it, judged by bounding boxes along a principal direction.
        var onLo = 0.0; var onHi = 0.0
        val axis = (0..2).firstOrNull { abs(a[it]) > 0.99 }
        if (axis != null) {
            val b = p.mesh.bounds
            for (o in all) {
                if (o.id == p.id || o.excluded) continue
                val ob = o.mesh.bounds
                var area = 1.0
                for (j in 0..2) if (j != axis) area *= maxOf(0.0, minOf(b.max[j], ob.max[j]) - maxOf(b.min[j], ob.min[j]))
                if (area <= 0) continue
                if (abs(ob.max[axis] - b.min[axis]) < 1.5) onLo += area
                if (abs(ob.min[axis] - b.max[axis]) < 1.5) onHi += area
            }
        }
        val newLo = when { onLo > onHi -> lo; onHi > onLo -> hi - t; else -> (lo + hi) / 2 - t / 2 }
        val prism = Prisms.of(p.mesh)?.takeIf { (it.view.n dot a) > 0.99 }
        if (prism != null) return Prisms.toMesh(prism.with(lo = newLo, hi = newLo + t))
        // Not a plain prism (rounded edges, say): stretch it through its thickness instead.
        val f = t / old
        val v = p.mesh.vertices.copyOf()
        for (i in 0 until p.mesh.vertexCount) {
            val q = p.mesh.vertex(i); val z = q dot a
            val r = q + a * (newLo + (z - lo) * f - z)
            v[3 * i] = r.x; v[3 * i + 1] = r.y; v[3 * i + 2] = r.z
        }
        return Mesh(v, p.mesh.triangles)
    }
}
