package splitziner.drawing

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiLineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.AffineTransformation
import splitziner.dims.LengthFormat
import splitziner.edit.Prisms
import splitziner.parts.CutListRow
import splitziner.parts.Part
import splitziner.sheet.DisplayList
import splitziner.sheet.HAlign
import splitziner.sheet.LineStyle
import splitziner.sheet.VAlign

/**
 * Full-size paper templates of shaped flat parts, to print at 100% on ordinary paper, tape together and trace
 * onto the wood. Each outline is split across as many pages as it needs, with marks to line them up, and
 * every page carries a line of known length to check the printer did not scale it.
 */
object FullSize {
    /** Kept clear at the paper's edge, where many printers cannot print (mm). */
    private const val MARGIN = 12.0

    /** An item needing more pages than this is better marked out from its drawing. */
    private const val MAX_PAGES = 24

    class Result(val pages: List<DisplayList>, val items: List<Int>, val tooBig: List<Int>)

    /**
     * Whether an outline is worth a paper template: it is well short of its box (a round top, an L or V bracket, a
     * curved leg). A rectangle with rounded corners or a notch is quicker to mark out with a rule and square.
     */
    private fun shaped(g: Geometry): Boolean {
        val polys = (0 until g.numGeometries).mapNotNull { g.getGeometryN(it) as? Polygon }
        if (polys.isEmpty()) return false
        // A rectangle with rounded corners, a notch or a slot is nearly its box; its sheet gives the radius and openings.
        if (g.area >= 0.9 * g.envelopeInternal.area) return false
        // Nor is an outline of square-cut straight edges only (a frame, an L, a notched slab): a rule and a square mark
        // it out faster and truer than taped-up paper. A template is for curves and angles.
        val cs = g.coordinates
        var square = true
        for (poly in polys) for (ring in listOf(poly.exteriorRing) + (0 until poly.numInteriorRing).map { poly.getInteriorRingN(it) }) {
            val c = ring.coordinates
            for (i in 0 until c.size - 1) {
                val dx = kotlin.math.abs(c[i + 1].x - c[i].x); val dy = kotlin.math.abs(c[i + 1].y - c[i].y)
                if (minOf(dx, dy) > 0.02 * maxOf(dx, dy) && maxOf(dx, dy) > 1.0) { square = false; break }
            }
        }
        return cs.isNotEmpty() && !square
    }

    /**
     * The diameter when a flat part's outline is a circle (a round table top), or null. A circle is marked out with a
     * trammel from its centre, which beats taping up pages of paper.
     */
    fun circleDiameter(mesh: splitziner.mesh.Mesh): Double? = oval(mesh)?.takeIf { (a, b) -> a - b < 0.01 * a + 1.0 }?.let { (a, b) -> (a + b) / 2 }

    /**
     * The length and width of a flat part whose outline is an ellipse (an oval table top, a circle included), or
     * null. Its axes are the outline's principal axes; every point must sit on the ellipse they give, within 1%.
     */
    fun oval(mesh: splitziner.mesh.Mesh): Pair<Double, Double>? {
        // Only a flat part: a rod or a turned leg is round in section too, but it is cut to length, not marked out.
        val ex = mesh.bounds.extents.let { listOf(it.x, it.y, it.z).sorted() }
        if (ex[0] > 0.3 * ex[1]) return null
        val pr = runCatching { Prisms.of(mesh) }.getOrNull() ?: return null
        val poly = pr.outline.takeIf { it.numGeometries == 1 }?.getGeometryN(0) as? Polygon ?: return null
        if (poly.numInteriorRing > 0) return null
        val c = poly.exteriorRing.coordinates.dropLast(1)
        if (c.size < 16) return null
        val cen = poly.centroid
        // Principal axes from the points' spread.
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in c) { val dx = p.x - cen.x; val dy = p.y - cen.y; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val ang = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
        val ca = kotlin.math.cos(ang); val sa = kotlin.math.sin(ang)
        val uv = c.map { p -> val dx = p.x - cen.x; val dy = p.y - cen.y; (dx * ca + dy * sa) to (-dx * sa + dy * ca) }
        // Semi-axes: the points' reach along each axis, taken as the largest radius near that axis.
        var ra = uv.maxOf { kotlin.math.abs(it.first) }; var rb = uv.maxOf { kotlin.math.abs(it.second) }
        // Near round, the axes are arbitrary and no vertex need sit on them: the circle's radius is the vertices'.
        if (kotlin.math.abs(ra - rb) < 0.02 * maxOf(ra, rb)) { val r = uv.maxOf { kotlin.math.hypot(it.first, it.second) }; ra = r; rb = r }
        if (ra < 1.0 || rb < 1.0) return null
        // A tessellated curve has its vertices on the true outline: each must lie on the ellipse.
        val ok = uv.all { (u, v) -> kotlin.math.abs(kotlin.math.sqrt((u / ra) * (u / ra) + (v / rb) * (v / rb)) - 1) < 0.01 }
        if (!ok) return null
        return if (ra >= rb) 2 * ra to 2 * rb else 2 * rb to 2 * ra
    }

    fun build(parts: List<Part>, rows: List<CutListRow>, fmt: LengthFormat, a4: Boolean = !fmt.isInch): Result {
        val pw = if (a4) 210.0 else 215.9; val ph = if (a4) 297.0 else 279.4
        val tw = pw - 2 * MARGIN; val th = ph - 2 * MARGIN - 22      // room at the foot for the label and check square
        val byItem = parts.filter { !it.excluded && it.item != null }.groupBy { it.item!! }
        val out = ArrayList<DisplayList>(); val items = ArrayList<Int>(); val tooBig = ArrayList<Int>()
        for (row in rows) {
            val p = byItem[row.item]?.firstOrNull() ?: continue
            if (p.name == "Caster") continue
            // Ends sawn at an angle are set on the saw, not traced from paper.
            if (row.description.contains("ENDS CUT AT") || row.description.contains("EDGE BEVELLED") || row.description.contains("RAISED") || row.description.contains("TAPERED") || row.description.contains("SLOPED") || row.description.contains("A RIGHT TRIANGLE") || row.description.contains(" CUT OFF ") || row.description.contains(" ROUNDED TO ") || row.description.contains(", A REGULAR ") || row.description.contains(" NOTCHED OUT ")) continue
            val pr = runCatching { Prisms.of(p.mesh) }.getOrNull() ?: continue
            var g = pr.outline
            if (g.isEmpty || !shaped(g)) continue
            // A circle is swung with a trammel from its centre: its drawing and the making steps say so.
            if (oval(p.mesh) != null) continue
            val e0 = g.envelopeInternal
            if (e0.height > e0.width) g = AffineTransformation.rotationInstance(Math.PI / 2).transform(g)
            val e = g.envelopeInternal
            g = AffineTransformation.translationInstance(-e.minX, -e.minY).transform(g)
            val cols = Math.ceil(e.width / tw).toInt().coerceAtLeast(1); val rws = Math.ceil(e.height / th).toInt().coerceAtLeast(1)
            if (cols * rws > MAX_PAGES) { tooBig += row.item; continue }
            items += row.item
            val edges = g.boundary
            for (r in 0 until rws) for (c in 0 until cols) {
                val d = DisplayList(pw, ph, "template_item${row.item}_${r + 1}_${c + 1}")
                val x0 = c * tw; val y0 = r * th
                val tile = splitziner.explode.Silhouette.GF.toGeometry(org.locationtech.jts.geom.Envelope(x0, x0 + tw, y0, y0 + th))
                val part = runCatching { edges.intersection(tile) }.getOrNull()
                if (part != null) for (i in 0 until part.numGeometries) {
                    val ls = part.getGeometryN(i) as? LineString ?: continue
                    val cs = ls.coordinates
                    d.polyline(DoubleArray(cs.size * 2) { k -> if (k % 2 == 0) MARGIN + cs[k / 2].x - x0 else MARGIN + 22 + cs[k / 2].y - y0 }, LineStyle.THICK)
                }
                // Corner marks: line them up with the neighbouring pages' marks, then tape.
                val bx0 = MARGIN; val by0 = MARGIN + 22; val bx1 = MARGIN + tw; val by1 = by0 + th
                for ((cx, cy) in listOf(bx0 to by0, bx1 to by0, bx0 to by1, bx1 to by1)) { d.line(cx - 5, cy, cx + 5, cy); d.line(cx, cy - 5, cx, cy + 5) }
                d.rect(bx0, by0, bx1, by1, LineStyle.HIDDEN)
                // A square of known size: measure it before cutting to be sure the page printed at full size.
                val sq = if (fmt.isInch) 25.4 else 25.0
                d.line(MARGIN, MARGIN + 8, MARGIN + sq, MARGIN + 8, LineStyle.THICK)
                d.line(MARGIN, MARGIN + 5, MARGIN, MARGIN + 11); d.line(MARGIN + sq, MARGIN + 5, MARGIN + sq, MARGIN + 11)
                d.text(MARGIN + sq + 3, MARGIN + 13, "ITEM ${row.item}, CUT ${row.qty};  PAGE ${r * cols + c + 1} OF ${cols * rws} (ROW ${r + 1}, COLUMN ${c + 1})", 3.2, bold = true)
                d.text(MARGIN + sq + 3, MARGIN + 7, "PRINT AT 100% (\"ACTUAL SIZE\"). THE LINE AT LEFT IS ${if (fmt.isInch) "1 IN" else "25 MM"} LONG: MEASURE IT.", 2.5)
                d.text(MARGIN + sq + 3, MARGIN + 2, "TRIM AT THE DASHED BOX, MATCH THE CROSSES WITH THE NEXT PAGES, TAPE, TRACE.", 2.5)
                out += d
            }
        }
        return Result(out, items, tooBig)
    }
}
