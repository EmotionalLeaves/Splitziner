package splitziner.drawing

import splitziner.dims.LengthFormat
import splitziner.math.Vec3
import splitziner.mesh.Mesh
import splitziner.mesh.OrientedBounds
import splitziner.parts.CutListRow
import splitziner.parts.Part
import splitziner.sheet.DisplayList
import splitziner.sheet.DrawingInfo

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

    fun build(parts: List<Part>, rows: List<CutListRow>, info: DrawingInfo, settings: DrawingSettings, fmt: LengthFormat): List<DrawingResult> {
        val byId = parts.associateBy { it.id }
        val out = ArrayList<DrawingResult>()
        for (row in rows) {
            val p = row.memberIds.firstNotNullOfOrNull { byId[it] } ?: continue
            val laid = p.copy(mesh = squaredUp(p.mesh))
            val vs = DrawingBuilder.views(listOf(laid), settings.sections)
            val base = info.drawingNo.ifBlank { "DWG" }
            val itemInfo = info.copy(
                title = "${info.title.ifBlank { "PART" }} — ITEM ${row.item}",
                drawingNo = "$base-${row.item}",
                notes = listOf("ITEM ${row.item} OF ${info.drawingNo.ifBlank { "THE ASSEMBLY DRAWING" }}. QUANTITY REQUIRED: ${row.qty}.") + info.notes,
            )
            // A detail drawing rarely needs more than a small sheet; a pinned sheet size applies to the assembly only.
            val r = DrawingBuilder.build(listOf(laid), listOf(row), vs, itemInfo, settings.copy(sheet = "auto"), fmt)
            r.sheets.forEach { it.qa.clear() }
            out += DrawingResult(r.sheets.map { rename(it, "item%02d".format(row.item)) }, r.sheet, r.scale, r.scaleText, r.qa.map { "Item ${row.item}: $it" })
        }
        return out
    }

    private fun rename(d: DisplayList, name: String): DisplayList {
        val n = DisplayList(d.widthMm, d.heightMm, name)
        n.prims += d.prims; n.boxes += d.boxes; n.upperCase = d.upperCase
        return n
    }
}
