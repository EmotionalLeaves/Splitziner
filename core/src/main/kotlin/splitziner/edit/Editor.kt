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
}

class EditResult(val parts: List<Part>, val notes: List<String>)

object Editor {
    /** At most this share of a part may be cut away by a trim; more than that is a design decision, not a tidy-up. */
    const val MAX_TRIM_SHARE = 0.25

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

    fun apply(parts: List<Part>, edits: List<Edit>): EditResult {
        var cur = parts.map { fresh(it, it.mesh) }
        val notes = ArrayList<String>()
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
        return EditResult(cur, notes)
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
