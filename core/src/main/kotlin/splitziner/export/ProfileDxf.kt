package splitziner.export

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import splitziner.dims.LengthFormat
import splitziner.edit.Prisms
import splitziner.parts.CutListRow
import splitziner.parts.Part
import java.util.Locale

/**
 * Cutting profiles for a CNC router, laser or plasma cutter: every flat item's true outline, full size in
 * millimetres, with its openings, laid out in rows by thickness. One of each item; the label says how many to
 * cut. Shapes that are not one outline through a thickness (blocks, rods) are left out and listed.
 */
object ProfileDxf {
    /** Space between profiles (mm), enough for a router bit and clamps. */
    private const val GAP = 20.0

    class Result(val dxf: String, val profiles: Int, val leftOut: List<Int>)

    private fun n(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    fun write(parts: List<Part>, rows: List<CutListRow>, fmt: LengthFormat): Result {
        val byItem = parts.filter { !it.excluded && it.item != null }.groupBy { it.item!! }
        class Shape(val row: CutListRow, val g: Geometry, val t: Double)
        val shapes = ArrayList<Shape>(); val leftOut = ArrayList<Int>()
        for (row in rows) {
            val p = byItem[row.item]?.firstOrNull() ?: continue
            val pr = runCatching { Prisms.of(p.mesh) }.getOrNull()
            if (pr == null || pr.outline.isEmpty || pr.thickness > 0.5 * minOf(pr.outline.envelopeInternal.width, pr.outline.envelopeInternal.height)) { leftOut += row.item; continue }
            // Lay the longer side along X and start at the origin.
            var g = pr.outline
            val e = g.envelopeInternal
            if (e.height > e.width) g = org.locationtech.jts.geom.util.AffineTransformation.rotationInstance(Math.PI / 2).transform(g)
            val e2 = g.envelopeInternal
            g = org.locationtech.jts.geom.util.AffineTransformation.translationInstance(-e2.minX, -e2.minY).transform(g)
            shapes += Shape(row, g, pr.thickness)
        }
        // Rows across a 2.4 m wide field, thickest first, tallest first within a thickness.
        shapes.sortWith(compareByDescending<Shape> { Math.round(it.t * 10) }.thenByDescending { it.g.envelopeInternal.height })
        val sb = StringBuilder()
        fun g(code: Int, value: String) { sb.append(code).append('\n').append(value).append('\n') }
        g(0, "SECTION"); g(2, "HEADER"); g(9, "\$ACADVER"); g(1, "AC1009"); g(9, "\$INSUNITS"); g(70, "4"); g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "TABLES"); g(0, "TABLE"); g(2, "LAYER"); g(70, "3")
        for (l in listOf("CUT_OUTSIDE", "CUT_INSIDE", "LABEL")) { g(0, "LAYER"); g(2, l); g(70, "0"); g(62, if (l == "CUT_INSIDE") "1" else "7"); g(6, "CONTINUOUS") }
        g(0, "ENDTAB"); g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "ENTITIES")
        fun ring(ls: LineString, layer: String, ox: Double, oy: Double) {
            g(0, "POLYLINE"); g(8, layer); g(66, "1"); g(70, "1")
            val cs = ls.coordinates
            for (k in 0 until cs.size - 1) { g(0, "VERTEX"); g(8, layer); g(10, n(ox + cs[k].x)); g(20, n(oy + cs[k].y)) }
            g(0, "SEQEND")
        }
        val fieldW = 2400.0
        var x = 0.0; var y = 0.0; var rowH = 0.0
        val d = { v: Double -> fmt.format(v) }
        for (s in shapes) {
            val e = s.g.envelopeInternal
            if (x > 0 && x + e.width > fieldW) { x = 0.0; y -= rowH + GAP + 30; rowH = 0.0 }
            val oy = y - e.height
            for (i in 0 until s.g.numGeometries) {
                val poly = s.g.getGeometryN(i) as? Polygon ?: continue
                ring(poly.exteriorRing, "CUT_OUTSIDE", x, oy)
                for (h in 0 until poly.numInteriorRing) ring(poly.getInteriorRingN(h), "CUT_INSIDE", x, oy)
            }
            val label = "ITEM ${s.row.item}  CUT ${s.row.qty}  ${d(s.t)}${if (fmt.isInch) " IN" else " MM"} THICK"
            g(0, "TEXT"); g(8, "LABEL"); g(10, n(x)); g(20, n(oy - 12)); g(40, n(8.0)); g(1, label)
            x += maxOf(e.width, label.length * 6.0) + GAP; rowH = maxOf(rowH, e.height)
        }
        g(0, "ENDSEC"); g(0, "EOF")
        return Result(sb.toString(), shapes.size, leftOut)
    }
}
