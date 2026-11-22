package splitziner.drawing

import splitziner.dims.DimDrawer
import splitziner.dims.DimGroup
import splitziner.dims.DimPlanner
import splitziner.dims.LengthFormat
import splitziner.mesh.Mesh
import splitziner.mesh.MeshOps
import splitziner.parts.CutListRow
import splitziner.parts.Part
import splitziner.sheet.DisplayList
import splitziner.sheet.DrawingInfo
import splitziner.sheet.Furniture
import splitziner.sheet.Lettering
import splitziner.sheet.LineStyle
import splitziner.sheet.ListOfMaterial
import splitziner.sheet.SheetSize
import splitziner.view.HiddenLine
import splitziner.view.ViewDef
import splitziner.view.ViewLines

data class DrawingSettings(
    /** "auto" or a sheet letter A to F. */
    val sheet: String = "auto",
    /** Null for automatic. */
    val scale: Double? = null,
    /** "auto", "on" or "off". */
    val hidden: String = "auto",
    /** "auto" cuts a view open when its inside is hidden; "none" never does. */
    val sections: String = "auto",
    val maxLevelDims: Int = 5,
    /** Use the standard's full lettering heights even on small sheets. */
    val strictLettering: Boolean = false,
)

class DrawingResult(val sheets: List<DisplayList>, val sheet: SheetSize, val scale: Double, val scaleText: String, val qa: List<String>)

/** Line work of the three principal views. Costly to compute, so it is kept between redraws. */
class ViewSet(val scene: Mesh, val views: Map<String, ViewLines>, val sections: Map<String, splitziner.section.SectionGeometry> = emptyMap())

object DrawingBuilder {
    /** Preferred scales come first in each direction; 1/50 and 1/100 are last resorts for very large objects. */
    val SCALES = doubleArrayOf(20.0, 10.0, 4.0, 2.0, 1.0, 1 / 2.0, 1 / 4.0, 1 / 10.0, 1 / 20.0, 1 / 50.0, 1 / 100.0)
    private val LAST_RESORT = setOf(1 / 50.0, 1 / 100.0)

    /** Clearance around the group of views. */
    private const val CALL = 8.0

    /** The list of material may take at most this share of the sheet's height before a larger sheet is tried. */
    private const val MAX_LIST_SHARE = 0.30

    /** A view smaller than this on paper is too small to read. */
    private const val MIN_VIEW_MM = 50.0

    /** Views should fill at least this much of the space available to them. */
    private const val MIN_FILL = 0.55

    /** The standard writes scales as fractions, never with a colon. */
    fun scaleText(s: Double): String = if (s >= 1) "${trim(s)}/1" else "1/${trim(1 / s)}"
    private fun trim(v: Double) = if (v == Math.rint(v)) "${v.toLong()}" else "$v"

    /** A view is cut open when its hidden line work outweighs its visible line work by this factor. */
    private const val SECTION_RATIO = 1.5

    /** Section letters in order, without the easily misread I, O, Q and X. */
    private val SECTION_LETTERS = ('A'..'Z').filter { it !in DrawingInfo.EXCLUDED_LETTERS }

    fun views(parts: List<Part>, sections: String = "auto"): ViewSet {
        val meshes = parts.filter { !it.excluded }.map { it.mesh }
        val scene = MeshOps.concatenate(meshes)
        val map = LinkedHashMap<String, ViewLines>()
        for (v in listOf(ViewDef.FRONT, ViewDef.TOP, ViewDef.RIGHT)) map[v.name] = HiddenLine.compute(meshes, scene, v)
        // A front or side view whose inside is hidden behind outer material becomes a full section in its place.
        val cut = LinkedHashMap<String, splitziner.section.SectionGeometry>()
        if (sections == "auto") for (v in listOf(ViewDef.FRONT, ViewDef.RIGHT)) {
            val lines = map.getValue(v.name)
            if (lines.hiddenLength > SECTION_RATIO * lines.visibleLength && lines.hiddenLength > 0) {
                val sec = runCatching { splitziner.section.SectionBuilder.build(meshes, v, "${SECTION_LETTERS[cut.size]}") }.getOrNull() ?: continue
                // Cutting a single solid lump open shows nothing new. A lone part is sectioned only if the cut finds a cavity.
                val hollow = sec.faces.any { (_, g) -> (0 until g.numGeometries).any { (g.getGeometryN(it) as? org.locationtech.jts.geom.Polygon)?.numInteriorRing ?: 0 > 0 } }
                if (meshes.size == 1 && !hollow) continue
                cut[v.name] = sec; map[v.name] = sec.lines
            }
        }
        return ViewSet(scene, map, cut)
    }

    private class Margins(val left: Double, val right: Double, val top: Double, val bottom: Double)

    private fun margins(view: String, groups: List<DimGroup>, th: Double, sections: Map<String, splitziner.section.SectionGeometry> = emptyMap()): Margins {
        fun need(side: String) = groups.filter { it.view == view && it.side == side }.maxOfOrNull { DimDrawer.margin(it, th) } ?: 6.0
        var left = need("left"); var right = need("right"); var top = need("top"); var bottom = need("bottom")
        // "SECTION A-A" sits under a sectioned view.
        if (view in sections) bottom += th * 1.2 + 8
        // Cutting-plane marks, arrows and letters stick out of the top view.
        if (view == "top") {
            if ("front" in sections) { left = maxOf(left, 8 + th * 2); right = maxOf(right, 8 + th * 2); top = maxOf(top, 14 + th * 1.6) }
            if ("right" in sections) { top = maxOf(top, 8 + th * 2); bottom = maxOf(bottom, 8 + th * 2); left = maxOf(left, 14 + th * 1.6) }
        }
        return Margins(left, right, top, bottom)
    }

    fun notesFor(info: DrawingInfo, sheet: SheetSize): List<String> {
        val n = ArrayList<String>()
        n += "DRAWING GENERATED FROM MESH DATA. VERIFY ALL DIMENSIONS BEFORE CUTTING."
        n += "DIMENSIONS ARE FROM THE LOWER LEFT FRONT CORNER UNLESS SHOWN OTHERWISE."
        n += "REMOVE ALL BURRS AND SHARP EDGES."
        n += info.notes.filter { it.isNotBlank() }
        if (sheet.compact) n += listOf("PRODUCED USING SPLITZINER", info.softwareVersion, if (info.sourceFile.isBlank()) "" else "FROM ${info.sourceFile}").filter { it.isNotBlank() }.joinToString(" ") + "."
        return n
    }

    private class Layout(val sheet: SheetSize, val scale: Double, val fill: Double, val listOnSheet: Boolean)

    /**
     * [planned] is the same parts carrying their assembly plan (explode directions and steps). When given
     * for an assembly, a second sheet shows the exploded view with item balloons.
     */
    fun build(parts: List<Part>, rows: List<CutListRow>, vs: ViewSet, info: DrawingInfo, settings: DrawingSettings, fmt: LengthFormat, planned: List<Part>? = null): DrawingResult {
        val drawn = parts.filter { !it.excluded }
        val single = drawn.size <= 1
        val front = vs.views.getValue("front"); val top = vs.views.getValue("top"); val right = vs.views.getValue("right")
        val showHidden = { v: ViewLines ->
            when (settings.hidden) {
                "on" -> true
                "off" -> false
                // In assemblies dashed lines swamp the view; on a single part they are kept while they are the minority.
                else -> single && v.hiddenLength <= v.visibleLength
            }
        }
        val groups = DimPlanner.plan(vs.scene, vs.views, fmt, settings.maxLevelDims)
        fun size(v: ViewLines) = doubleArrayOf(v.bounds[2] - v.bounds[0], v.bounds[3] - v.bounds[1])
        val (wf, hf) = size(front).let { it[0] to it[1] }
        val (wt, ht) = size(top).let { it[0] to it[1] }
        val (wr, hr) = size(right).let { it[0] to it[1] }

        /** Where each view's lower-left corner goes on [sheet] at scale [s], and whether everything fits. */
        class Placed(val origin: Map<String, DoubleArray>, val fits: Boolean, val fill: Double)

        fun place(sheet: SheetSize, s: Double, listH: Double): Placed {
            val th = Lettering.of(sheet, settings.strictLettering).dims
            val f = margins("front", groups, th, vs.sections); val t = margins("top", groups, th, vs.sections); val r = margins("right", groups, th, vs.sections)
            val bandTop = sheet.fieldY0 + Furniture.TITLE_HEIGHT + listH
            // Third-angle layout: top view above the front view, right view to its right.
            val ax0 = sheet.fieldX0 + 2 + CALL + maxOf(f.left, t.left)
            val ay0 = bandTop + 2 + 4 + CALL + maxOf(f.bottom, r.bottom)
            val origin = mapOf(
                "front" to doubleArrayOf(ax0, ay0),
                "right" to doubleArrayOf(ax0 + wf * s + maxOf(f.right, t.right) + r.left + 6, ay0),
                "top" to doubleArrayOf(ax0, ay0 + maxOf(hf, hr) * s + maxOf(f.top, r.top) + t.bottom + 4),
            )
            val right = origin.getValue("right")[0] + wr * s + r.right + CALL
            val topEdge = origin.getValue("top")[1] + ht * s + t.top + CALL
            val availW = sheet.fieldX1 - 2 - (sheet.fieldX0 + 2)
            val availH = sheet.fieldY1 - 2 - (bandTop + 2)
            var fits = right <= sheet.fieldX1 - 2 && topEdge <= sheet.fieldY1 - 2
            // The notes and revision block own the upper-right corner; no view may reach into them.
            val L = Lettering.of(sheet, settings.strictLettering)
            val nb = Furniture.notes(DisplayList(1.0, 1.0), sheet, L, info, notesFor(info, sheet), draw = false)
            val keepX = (nb?.get(0) ?: (sheet.fieldX1 - minOf(178.0, 0.52 * sheet.fieldWidth))) - 3
            val keepY = (nb?.get(1) ?: (sheet.fieldY1 - Furniture.revisionBlockHeight(info))) - 3
            val rightTop = origin.getValue("right")[1] + hr * s + r.top
            val topRight = origin.getValue("top")[0] + wt * s + t.right
            if (right - CALL > keepX && rightTop > keepY) fits = false
            if (topRight > keepX && topEdge - CALL > keepY) fits = false
            val fill = maxOf((right - sheet.fieldX0 - 2) / availW, (topEdge - bandTop - 2) / availH)
            return Placed(origin, fits, fill)
        }

        val pinned = SheetSize.byLetter(settings.sheet)
        val candidates = if (pinned != null) listOf(pinned) else SheetSize.AUTO
        val scales = if (settings.scale != null) doubleArrayOf(settings.scale) else SCALES
        var chosen: Layout? = null
        for ((ci, sheet) in candidates.withIndex()) {
            val listH = ListOfMaterial.height(rows.size)
            val listFits = listH <= MAX_LIST_SHARE * sheet.height
            if (!listFits && pinned == null && ci < candidates.size - 1) continue
            val lh = if (listFits) listH else 0.0
            val s = scales.firstOrNull { place(sheet, it, lh).fits } ?: continue
            val fill = place(sheet, s, lh).fill
            val big = maxOf(wf, hf) * s
            val ok = pinned != null || settings.scale != null || (big >= MIN_VIEW_MM && fill >= MIN_FILL) || s == scales.first()
            if (ok || ci == candidates.size - 1) { chosen = Layout(sheet, s, fill, listFits); break }
        }
        val qa = ArrayList<String>()
        if (chosen == null) {
            val sheet = candidates.last()
            chosen = Layout(sheet, scales.last(), 1.0, ListOfMaterial.height(rows.size) <= MAX_LIST_SHARE * sheet.height)
            qa += "The views do not fit; using sheet ${sheet.letter} at ${scaleText(scales.last())}"
        }
        val sheet = chosen.sheet; val s = chosen.scale
        if (s in LAST_RESORT) qa += "Scale ${scaleText(s)} is not one of the standard's preferred scales"
        val L = Lettering.of(sheet, settings.strictLettering); val th = L.dims

        val dl = DisplayList(sheet.width, sheet.height, "sheet1")
        val notes = notesFor(info, sheet).toMutableList()
        if (!chosen.listOnSheet && rows.isNotEmpty()) notes += "SEE SEPARATE PARTS LIST PL-${info.drawingNo.ifBlank { "1" }}."
        val sheetCount = if (planned != null && !single) 2 else 1
        val title = Furniture.draw(dl, sheet, L, info, scaleText(s), fmt.units, 1, sheetCount)
        Furniture.notes(dl, sheet, L, info, notes)
        val listH = if (chosen.listOnSheet) ListOfMaterial.height(rows.size) else 0.0
        if (chosen.listOnSheet) ListOfMaterial.draw(dl, rows, sheet.fieldX1, title.top, L, single)
        val origin = place(sheet, s, listH).origin
        val defs = mapOf("front" to ViewDef.FRONT, "top" to ViewDef.TOP, "right" to ViewDef.RIGHT)
        val hiddenOff = ArrayList<String>()
        for ((name, lines) in vs.views) {
            val o = origin.getValue(name); val b = lines.bounds
            fun paper(a: DoubleArray): DoubleArray = DoubleArray(a.size) { i -> if (i % 2 == 0) o[0] + (a[i] - b[0]) * s else o[1] + (a[i] - b[1]) * s }
            vs.sections[name]?.let { sec ->
                splitziner.section.Hatch.draw(dl, sec.faces, s) { x, y -> doubleArrayOf(o[0] + (x - b[0]) * s, o[1] + (y - b[1]) * s) }
                val label = dl.text(o[0] + (b[2] - b[0]) * s / 2, o[1] - margins(name, groups, th).bottom - 2, "SECTION ${sec.letter}-${sec.letter}", L.descTitle, splitziner.sheet.HAlign.CENTER, splitziner.sheet.VAlign.TOP, bold = true, tag = "section")
                if (label.x0 < 0) dl.qa += "Section label runs off the sheet"
            }
            if (lines.hidden.isNotEmpty()) { if (showHidden(lines)) dl.segs(paper(lines.hidden), LineStyle.HIDDEN) else hiddenOff += name }
            dl.segs(paper(lines.visible), LineStyle.THICK)
            dl.register(o[0] - 0.5, o[1] - 0.5, o[0] + (b[2] - b[0]) * s + 0.5, o[1] + (b[3] - b[1]) * s + 0.5, "view")
        }
        if (hiddenOff.isNotEmpty() && settings.hidden == "auto") qa += "Hidden lines omitted for clarity in: ${hiddenOff.joinToString(", ")}"

        // Cutting-plane marks on the top view: thick end strokes, arrows in the direction of sight, and the letter.
        run {
            val tl = vs.views.getValue("top"); val o = origin.getValue("top"); val b = tl.bounds
            fun px(x: Double) = o[0] + (x - b[0]) * s
            fun py(y: Double) = o[1] + (y - b[1]) * s
            vs.sections["front"]?.let { sec ->
                // The front section's plane is a horizontal line across the top view; the viewer looks toward +Y (up the sheet).
                val y = py(sec.at); val x0 = px(b[0]) - 5; val x1 = px(b[2]) + 5
                val stroke = minOf(20.0, 0.25 * (x1 - x0))
                dl.line(x0, y, x0 + stroke, y, LineStyle.CUT); dl.line(x1 - stroke, y, x1, y, LineStyle.CUT)
                for (x in doubleArrayOf(x0, x1)) {
                    dl.line(x, y, x, y + 8, LineStyle.CUT); dl.arrow(x, y + 8 + 3, 0.0, 1.0, 3.5, 0.9)
                    dl.text(x, y + 8 + 3 + 3.5, sec.letter, L.sectionLetter * 0.8, splitziner.sheet.HAlign.CENTER, splitziner.sheet.VAlign.BOTTOM, bold = true, tag = "section")
                }
            }
            vs.sections["right"]?.let { sec ->
                // The side section's plane is a vertical line on the top view; the viewer looks toward -X (left on the sheet).
                val x = px(sec.at); val y0 = py(b[1]) - 5; val y1 = py(b[3]) + 5
                val stroke = minOf(20.0, 0.25 * (y1 - y0))
                dl.line(x, y0, x, y0 + stroke, LineStyle.CUT); dl.line(x, y1 - stroke, x, y1, LineStyle.CUT)
                for (y in doubleArrayOf(y0, y1)) {
                    dl.line(x, y, x - 8, y, LineStyle.CUT); dl.arrow(x - 8 - 3, y, -1.0, 0.0, 3.5, 0.9)
                    dl.text(x - 8 - 3 - 3.5, y, sec.letter, L.sectionLetter * 0.8, splitziner.sheet.HAlign.RIGHT, splitziner.sheet.VAlign.CENTER, bold = true, tag = "section")
                }
            }
        }

        val lo = vs.scene.bounds.min
        for (g in groups) {
            val lines = vs.views.getValue(g.view); val o = origin.getValue(g.view); val b = lines.bounds
            val box = doubleArrayOf(o[0], o[1], o[0] + (b[2] - b[0]) * s, o[1] + (b[3] - b[1]) * s)
            DimDrawer.draw(dl, g, lines, defs.getValue(g.view), lo[g.axis], th, box) { x, y -> doubleArrayOf(o[0] + (x - b[0]) * s, o[1] + (y - b[1]) * s) }
        }

        // Report annotations that landed on top of each other; version 1 reports rather than rearranges.
        val checked = dl.boxes.filter { it.tag == "dim" || it.tag == "view" || it.tag == "titleblock" || it.tag == "partslist" || it.tag == "notes" || it.tag == "revblock" }
        var overlaps = 0
        for (i in checked.indices) for (j in i + 1 until checked.size) {
            val a = checked[i]; val c = checked[j]
            if (a.tag == "view" && c.tag == "view") continue
            if (a.tag != "dim" && c.tag != "dim" && a.tag != "view" && c.tag != "view") continue
            if (a.overlap(c) > 0.5) overlaps++
        }
        if (overlaps > 0) qa += "$overlaps annotation overlap(s) on sheet 1; a larger sheet may read better"
        qa += dl.qa
        val sheets = arrayListOf(dl)
        if (sheetCount == 2) {
            val d2 = DisplayList(sheet.width, sheet.height, "sheet2")
            val t2 = Furniture.draw(d2, sheet, L, info, scaleText(s), fmt.units, 2, 2)
            // Big assemblies show only their outer layer pulled away; the build guide shows every step.
            val scene = splitziner.explode.ExplodedView.peel(planned!!.filter { !it.excluded })
            val geo = splitziner.explode.ExplodedView.prepare(scene)
            splitziner.explode.ExplodedView.drawInto(d2, geo, sheet.fieldX0 + 4, t2.top + 12, sheet.fieldX1 - 4, sheet.fieldY1 - 4, splitziner.explode.ExplodeOptions(textHeight = L.dims))
            d2.text(sheet.fieldX0 + 8, t2.y0 + 14, "EXPLODED VIEW", L.descTitle, tag = "section")
            d2.text(sheet.fieldX0 + 8, t2.y0 + 14 - L.dims * 1.6, "SCALE: NONE", L.dims)
            // Every listed item must be pointed to at least once on the drawing.
            val shown = d2.boxes.count { it.tag == "balloon" }
            if (shown < rows.size) qa += "${rows.size - shown} item(s) are not identified by a balloon on sheet 2"
            qa += d2.qa
            sheets += d2
        }
        return DrawingResult(sheets, sheet, s, scaleText(s), qa)
    }
}
