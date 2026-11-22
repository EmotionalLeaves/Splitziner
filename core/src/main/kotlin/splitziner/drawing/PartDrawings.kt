package splitziner.drawing

import splitziner.dims.LengthFormat
import splitziner.math.Vec3
import splitziner.mesh.Mesh
import splitziner.mesh.OrientedBounds
import splitziner.parts.CutListRow
import splitziner.parts.Part
import splitziner.sheet.DisplayList
import splitziner.sheet.DrawingInfo
import kotlin.math.abs

/**
 * Detail drawings: one sheet per item of the cut list, showing that part on its own with its
 * dimensions. Each is a drawing in its own right, numbered after the assembly drawing.
 */
object PartDrawings {
    /**
     * Lays a part down the way a drafter would draw it: its length across the sheet, its broad face
     * toward the viewer, its thickness seen from above and from the side. A part that sits at an
     * angle in the assembly is squared up first.
     */
    fun squaredUp(mesh: Mesh): Mesh {
        val box = OrientedBounds.of(mesh)
        val ext = listOf(box.extents.x, box.extents.y, box.extents.z)
        val order = ext.indices.sortedByDescending { ext[it] }          // length, width, thickness
        val aL = box.axes[order[0]]; val aW = box.axes[order[1]]
        // Front view looks along Y, so: length -> X, thickness -> Y, width -> Z. Y is derived to keep the part from being mirrored.
        val aT = aW cross aL
        val c = box.center
        val v = DoubleArray(mesh.vertices.size)
        for (i in 0 until mesh.vertexCount) {
            val p = mesh.vertex(i) - c
            v[3 * i] = p dot aL; v[3 * i + 1] = p dot aT; v[3 * i + 2] = p dot aW
        }
        // Start the part at the origin so its dimensions read from zero.
        var x0 = Double.MAX_VALUE; var y0 = x0; var z0 = x0
        for (i in 0 until mesh.vertexCount) { x0 = minOf(x0, v[3 * i]); y0 = minOf(y0, v[3 * i + 1]); z0 = minOf(z0, v[3 * i + 2]) }
        for (i in 0 until mesh.vertexCount) { v[3 * i] -= x0; v[3 * i + 1] -= y0; v[3 * i + 2] -= z0 }
        return Mesh(v, mesh.triangles)
    }

    /**
     * Items that are plain square boards or panels: no notch, hole or shaping. The list of material gives
     * everything needed to cut them, so a sheet each would only repeat three numbers. Shops do not draw these.
     */
    fun plainItems(parts: List<Part>, rows: List<CutListRow>, fmt: LengthFormat): Set<Int> {
        val byId = parts.associateBy { it.id }
        return rows.filter { row ->
            if (row.kind != splitziner.parts.PartKind.BOARD && row.kind != splitziner.parts.PartKind.PANEL) return@filter false
            val p = row.memberIds.firstNotNullOfOrNull { byId[it] } ?: return@filter false
            val d = runCatching { splitziner.parts.PartDescriber.describe(p.mesh, fmt) }.getOrNull() ?: return@filter false
            p.mesh.isWatertight && !d.desc.contains("NOTCHED") && !d.desc.contains("HOLE")
        }.map { it.item }.toSet()
    }

    /** A general note for the assembly drawing naming the items that have no detail drawing, in ranges ("3-5, 9"). */
    fun plainNote(items: Set<Int>): String? {
        if (items.isEmpty()) return null
        val sorted = items.sorted(); val runs = ArrayList<String>()
        var a = sorted[0]; var b = a
        for (i in sorted.drop(1)) { if (i == b + 1) b = i else { runs += if (a == b) "$a" else "$a-$b"; a = i; b = i } }
        runs += if (a == b) "$a" else "$a-$b"
        val which = if (items.size == 1) "ITEM ${runs[0]} IS A PLAIN SQUARE BOARD" else "ITEMS ${runs.joinToString(", ")} ARE PLAIN SQUARE BOARDS"
        return "$which: CUT TO THE SIZES IN THE LIST OF MATERIAL. NO DETAIL DRAWING."
    }

    fun build(parts: List<Part>, rows: List<CutListRow>, info: DrawingInfo, settings: DrawingSettings, fmt: LengthFormat): List<DrawingResult> =
        items(parts, rows, info, settings, fmt, emptySet()).map { it.second }

    /** Detail drawings for every item except those in [skip], each with the row it details. */
    fun items(parts: List<Part>, rows: List<CutListRow>, info: DrawingInfo, settings: DrawingSettings, fmt: LengthFormat, skip: Set<Int>): List<Pair<CutListRow, DrawingResult>> {
        val byId = parts.associateBy { it.id }
        val out = ArrayList<Pair<CutListRow, DrawingResult>>()
        for (row in rows) {
            if (row.item in skip) continue
            val p = row.memberIds.firstNotNullOfOrNull { byId[it] } ?: continue
            val laid = p.copy(mesh = squaredUp(p.mesh))
            val vs = DrawingBuilder.views(listOf(laid), settings.sections)
            val base = info.drawingNo.ifBlank { "DWG" }
            val itemInfo = info.copy(
                title = "${info.title.ifBlank { "PART" }} — ITEM ${row.item}",
                drawingNo = "$base-${row.item}",
                notes = listOf("ITEM ${row.item} OF ${info.drawingNo.ifBlank { "THE ASSEMBLY DRAWING" }}. QUANTITY REQUIRED: ${row.qty}.") + outlineNotes(laid.mesh, fmt) + info.notes,
            )
            // A detail drawing rarely needs more than a small sheet; a pinned sheet size applies to the assembly only.
            val r = DrawingBuilder.build(listOf(laid), listOf(row), vs, itemInfo, settings.copy(sheet = "auto"), fmt)
            r.sheets.forEach { it.qa.clear() }
            out += row to DrawingResult(r.sheets.map { rename(it, "item%02d".format(row.item)) }, r.sheet, r.scale, r.scaleText, r.qa.map { "Item ${row.item}: $it" })
        }
        return out
    }

    /**
     * What a maker needs to cut a flat part's outline that the views do not dimension: the corner radius, and where
     * each opening is and how big. Read from a slice through the middle of the part's thickness, in the front view's
     * terms (from the lower left corner of the part as drawn). Openings are given by the box around them.
     */
    fun outlineNotes(laid: Mesh, fmt: LengthFormat): List<String> {
        val ext = laid.extents
        if (ext.y > 0.35 * minOf(ext.x, ext.z)) return emptyList()       // not a flat part
        // The slice's own frame: u along Z (the part's width, up the sheet), v along X (its length, across).
        val loops = runCatching { splitziner.section.Slicer.sectionLoops(laid, Vec3(0.0, ext.y / 2, 0.0), Vec3(0.0, 1.0, 0.0)) }.getOrNull() ?: return emptyList()
        if (loops.isEmpty()) return emptyList()
        fun area(l: List<splitziner.math.Vec2>) = abs(splitziner.section.Slicer.loopArea(l))
        val outer = loops.maxByOrNull { area(it) }!!
        val d = { v: Double -> fmt.format(v) }
        val out = ArrayList<String>()
        // Rounded corners: a corner of radius r leaves r(sqrt 2 - 1) between the box corner and the outline.
        val ou0 = outer.minOf { it.x }; val ou1 = outer.maxOf { it.x }; val ov0 = outer.minOf { it.y }; val ov1 = outer.maxOf { it.y }
        val radii = listOf(ou0 to ov0, ou0 to ov1, ou1 to ov0, ou1 to ov1).map { (cu, cv) -> outer.minOf { Math.hypot(it.x - cu, it.y - cv) } / (Math.sqrt(2.0) - 1) }
        val r = radii.average()
        if (r > 0.01 * minOf(ou1 - ou0, ov1 - ov0) && radii.all { abs(it - r) < 0.15 * r }) out += "ALL OUTSIDE CORNERS R${d(r)}."
        // Openings: loops inside the outline.
        val holes = loops.filter { it !== outer && it.all { p -> p.x > ou0 && p.x < ou1 && p.y > ov0 && p.y < ov1 } && area(it) > 1e-4 * area(outer) }
            .sortedWith(compareBy({ it.minOf { p -> p.y } }, { it.minOf { p -> p.x } }))
        for ((i, h) in holes.withIndex()) {
            val u0 = h.minOf { it.x }; val u1 = h.maxOf { it.x }; val v0 = h.minOf { it.y }; val v1 = h.maxOf { it.y }
            val w = v1 - v0; val hgt = u1 - u0
            val shape = when {
                area(h) > 0.97 * w * hgt -> "SQUARE-CORNERED"
                abs(w - hgt) < 0.05 * maxOf(w, hgt) && area(h) > 0.75 * w * hgt -> "ROUND"
                else -> "ROUND-ENDED"
            }
            val name = if (holes.size == 1) "OPENING" else "OPENING ${'A' + i}"
            out += if (shape == "ROUND") "$name: ${d(w)} DIA THRU, CENTRE ${d((v0 + v1) / 2 - ov0)} FROM LEFT EDGE, ${d((u0 + u1) / 2 - ou0)} FROM BOTTOM EDGE."
            else "$name: $shape, ${d(w)} X ${d(hgt)} THRU, ITS BOX STARTING ${d(v0 - ov0)} FROM LEFT EDGE AND ${d(u0 - ou0)} FROM BOTTOM EDGE."
        }
        return out
    }

    private fun rename(d: DisplayList, name: String): DisplayList {
        val n = DisplayList(d.widthMm, d.heightMm, name)
        n.prims += d.prims; n.boxes += d.boxes; n.upperCase = d.upperCase
        return n
    }
}
