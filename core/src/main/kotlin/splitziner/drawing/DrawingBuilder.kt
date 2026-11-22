package splitziner.drawing

import splitziner.dims.DimDrawer
import splitziner.dims.DimGroup
import splitziner.dims.DimPlanner
import splitziner.dims.LengthFormat
import splitziner.features.Hole
import splitziner.features.HoleFinder
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
    /** A pictorial view is added to sheet 1 only for assemblies up to this many parts, and only in a gap this big (mm). */
    private const val ISO_MAX_PARTS = 150
    private const val ISO_MIN_MM = 70.0

    /** Preferred scales, largest first; 1/50 and 1/100 are last resorts for very large objects. */
    val SCALES = doubleArrayOf(20.0, 10.0, 4.0, 2.0, 1.0, 1 / 2.0, 1 / 4.0, 1 / 10.0, 1 / 20.0, 1 / 50.0, 1 / 100.0)

    /**
     * The scales a drafter in these units would pick, largest first. Metric drawings step 1, 1/2, 1/5, 1/10 (ISO 5455);
     * inch drawings halve each time (full, half, quarter, eighth), so nothing jumps from 1/4 straight to 1/10.
     */
    fun scalesFor(inch: Boolean): DoubleArray = if (inch) doubleArrayOf(4.0, 2.0, 1.0, 1 / 2.0, 1 / 4.0, 1 / 8.0, 1 / 16.0, 1 / 32.0, 1 / 64.0)
        else doubleArrayOf(10.0, 5.0, 2.0, 1.0, 1 / 2.0, 1 / 5.0, 1 / 10.0, 1 / 20.0, 1 / 50.0, 1 / 100.0)
    private val LAST_RESORT = setOf(1 / 50.0, 1 / 100.0, 1 / 64.0)

    /** Clearance around the group of views. */
    private const val CALL = 8.0

    /** The list of material may take at most this share of the sheet's height before a larger sheet is tried. */
    private const val MAX_LIST_SHARE = 0.30

    /** At most this many further exploded sheets are drawn to give every item a balloon. */
    private const val MAX_EXTRA_EXPLODED = 6

    /** Parts per further exploded view: enough to show how they go together, few enough to read. */
    private const val PARTS_PER_EXTRA = 14

    /** The largest view smaller than this on paper (mm) reads as a thumbnail; a larger sheet is tried first. */
    private const val MIN_VIEW_MM = 80.0

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
        // A part hidden in all three views (a shelf behind doors, between the sides, under the top) would be neither
        // seen nor located: the side view is cut open so its position can be read and dimensioned.
        if (sections == "auto" && meshes.size > 1 && ViewDef.RIGHT.name !in cut && buried(meshes)) {
            val v = ViewDef.RIGHT
            val sec = runCatching { splitziner.section.SectionBuilder.build(meshes, v, "${SECTION_LETTERS[cut.size]}") }.getOrNull()
            if (sec != null) { cut[v.name] = sec; map[v.name] = sec.lines }
        }
        return ViewSet(scene, map, cut)
    }

    /** Whether some part is covered in the front, top and right views alike by parts in front of, above and beside it. */
    private fun buried(meshes: List<Mesh>): Boolean {
        val b = meshes.map { it.bounds }
        val gf = org.locationtech.jts.geom.GeometryFactory()
        fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = gf.toGeometry(org.locationtech.jts.geom.Envelope(x0, x1, y0, y1))
        // Looking along axis k from the side where coordinates are [larger] (or smaller): the others project to (a, c).
        fun covered(i: Int, k: Int, larger: Boolean): Boolean {
            val (a, c) = (0..2).filter { it != k }
            val me = rect(b[i].min[a] + 0.5, b[i].min[c] + 0.5, b[i].max[a] - 0.5, b[i].max[c] - 0.5)
            if (me.isEmpty || me.area <= 0) return false
            val cover = b.indices.filter { j -> j != i && (if (larger) b[j].min[k] >= b[i].max[k] - 0.5 else b[j].max[k] <= b[i].min[k] + 0.5) }
                .map { j -> rect(b[j].min[a], b[j].min[c], b[j].max[a], b[j].max[c]) }
            if (cover.isEmpty()) return false
            // A door's few millimetres of clearance do not let anyone see in.
            return runCatching { org.locationtech.jts.operation.union.CascadedPolygonUnion.union(cover).buffer(3.0).covers(me) }.getOrDefault(false)
        }
        // Front looks from small Y, top from large Z, right from large X.
        return b.indices.any { i -> covered(i, 1, false) && covered(i, 2, true) && covered(i, 0, true) }
    }

    private class Margins(val left: Double, val right: Double, val top: Double, val bottom: Double)

    /** A note pointing at a hole, or at one of several alike: "4X 6.0 DIA THRU". */
    class HoleCallout(val view: String, val text: String, val holes: List<Hole>)

    /** No more than this many different hole sizes are called out on one view. */
    private const val MAX_CALLOUTS = 4

    /** Paper radius below which a hole gets a plain cross instead of a centre-line cross. */
    private const val SMALL_HOLE_MM = 1.5

    fun holeCallouts(holes: List<Hole>, fmt: LengthFormat): List<HoleCallout> {
        val views = listOf("right", "front", "top")
        return holes.filter { it.principalAxis != null }.groupBy {
            val size = fmt.format(it.diameter) + " DIA" + when {
                it.through -> " THRU"
                it.depth != null -> " ${fmt.format(it.depth)} DEEP"
                else -> ""
            }
            views[it.principalAxis!!] to size
        }.entries.sortedByDescending { it.value.size }.map { (key, list) -> HoleCallout(key.first, (if (list.size > 1) "${list.size}X " else "") + key.second, list) }
    }

    private fun rowHeight(th: Double) = 1.9 * th

    private fun margins(view: String, groups: List<DimGroup>, th: Double, sections: Map<String, splitziner.section.SectionGeometry> = emptyMap(), callouts: List<HoleCallout> = emptyList()): Margins {
        val notes = callouts.filter { it.view == view }.take(MAX_CALLOUTS)
        if (notes.isNotEmpty()) {
            val m = margins(view, groups, th, sections)
            // Hole notes sit clear of the dimensions: beside the top view, above the other two.
            return if (view == "top") Margins(m.left, m.right + 12 + notes.maxOf { splitziner.sheet.StrokeFont.width(it.text, th) }, m.top, m.bottom)
            else Margins(m.left, m.right, m.top + 5 + rowHeight(th) * notes.size, m.bottom)
        }
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
    /** [extraHoles] are holes drilled into a single part that its model does not have (hinge cups). */
    fun build(parts: List<Part>, rows: List<CutListRow>, vs: ViewSet, info: DrawingInfo, settings: DrawingSettings, fmt: LengthFormat, planned: List<Part>? = null, extraHoles: List<Hole> = emptyList(),
              /** Letters marking features the notes refer to ("OPENING A"): letter, model point, feature size (mm). */
              tags: List<Triple<String, splitziner.math.Vec3, Double>> = emptyList()): DrawingResult {
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
        // Holes are located and called out on drawings of one part; on an assembly they would bury the views.
        val holes = if (drawn.size == 1) runCatching { HoleFinder.find(drawn[0].mesh) }.getOrDefault(emptyList()) + extraHoles else emptyList()
        val callouts = holeCallouts(holes, fmt)
        val groups = DimPlanner.plan(vs.scene, vs.views, fmt, settings.maxLevelDims, holes)
        fun size(v: ViewLines) = doubleArrayOf(v.bounds[2] - v.bounds[0], v.bounds[3] - v.bounds[1])
        val (wf, hf) = size(front).let { it[0] to it[1] }
        val (wt, ht) = size(top).let { it[0] to it[1] }
        val (wr, hr) = size(right).let { it[0] to it[1] }

        /** Where each view's lower-left corner goes on [sheet] at scale [s], and whether everything fits. */
        class Placed(val origin: Map<String, DoubleArray>, val fits: Boolean, val fill: Double)

        fun place(sheet: SheetSize, s: Double, listH: Double): Placed {
            val th = Lettering.of(sheet, settings.strictLettering).dims
            val f = margins("front", groups, th, vs.sections, callouts); val t = margins("top", groups, th, vs.sections, callouts); val r = margins("right", groups, th, vs.sections, callouts)
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
            // Every view with its dimensions must stay out of that corner, the dimension text included.
            for ((name, m, wh) in listOf(Triple("front", f, wf to hf), Triple("top", t, wt to ht), Triple("right", r, wr to hr))) {
                val o = origin.getValue(name)
                val x1 = o[0] + wh.first * s + m.right + CALL; val y1 = o[1] + wh.second * s + m.top
                val y0 = o[1] - m.bottom - CALL
                if (x1 > keepX && y1 > keepY && y0 < sheet.fieldY1) fits = false
            }
            val fill = maxOf((right - sheet.fieldX0 - 2) / availW, (topEdge - bandTop - 2) / availH)
            return Placed(origin, fits, fill)
        }

        val pinned = SheetSize.byLetter(settings.sheet)
        // "AB": the smallest sheet that works, but no bigger than B (11 x 17 in), which a home or office printer takes.
        val candidates = if (pinned != null) listOf(pinned) else if (settings.sheet == "AB") SheetSize.AUTO.take(2) else SheetSize.AUTO
        val scales = if (settings.scale != null) doubleArrayOf(settings.scale) else scalesFor(fmt.isInch)
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
        // A list too long for the views' sheet goes on sheets of its own, straight after it.
        val listPages: List<List<CutListRow>> = if (chosen.listOnSheet || rows.isEmpty()) emptyList() else {
            val per = maxOf(1, ListOfMaterial.rowsIn(sheet.fieldY1 - 6 - (sheet.fieldY0 + Furniture.TITLE_HEIGHT + 4)))
            rows.chunked(per)
        }
        if (listPages.isNotEmpty()) notes += "LIST OF MATERIAL ON SHEET${if (listPages.size > 1) "S 2 TO ${listPages.size + 1}" else " 2"}."
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

        // Lettered openings: the letter sits in the opening in the front view, or beside it on a short leader.
        if (tags.isNotEmpty()) {
            val o = origin.getValue("front"); val b = vs.views.getValue("front").bounds; val def = defs.getValue("front")
            for ((letter, at, size) in tags) {
                val q = def.project(at); val cx = o[0] + (q.x - b[0]) * s; val cy = o[1] + (q.y - b[1]) * s
                if (size * s > th * 1.6) dl.text(cx, cy, letter, th, splitziner.sheet.HAlign.CENTER, splitziner.sheet.VAlign.CENTER, tag = "dim")
                else {
                    // The leader points into the part, away from its nearer edge, so the letter never sits on the outline.
                    val midY = o[1] + (b[3] - b[1]) * s / 2; val dy = if (cy > midY) -1.0 else 1.0
                    dl.line(cx, cy, cx + 4, cy + 4 * dy)
                    dl.text(cx + 4.8, cy + 4.8 * dy, letter, th, splitziner.sheet.HAlign.LEFT, if (dy > 0) splitziner.sheet.VAlign.BOTTOM else splitziner.sheet.VAlign.TOP, tag = "dim")
                }
            }
        }
        // Holes: centre lines in every view, and a note with the size where the hole shows as a circle.
        if (holes.isNotEmpty()) {
            for ((name, lines) in vs.views) {
                val o = origin.getValue(name); val b = lines.bounds; val def = defs.getValue(name)
                fun paper(p: splitziner.math.Vec3): DoubleArray { val q = def.project(p); return doubleArrayOf(o[0] + (q.x - b[0]) * s, o[1] + (q.y - b[1]) * s) }
                for (h in holes) {
                    if (h.principalAxis == null) continue
                    val along = kotlin.math.abs(h.axis dot def.n)
                    if (along > Hole.ALIGNED) {
                        val c = paper(h.center); val rp = h.diameter / 2 * s
                        val ext = rp + if (rp < SMALL_HOLE_MM) 1.2 else minOf(4.0, maxOf(2.0, 0.4 * rp))
                        val style = if (rp < SMALL_HOLE_MM) LineStyle.THIN else LineStyle.CENTER
                        dl.line(c[0] - ext, c[1], c[0] + ext, c[1], style); dl.line(c[0], c[1] - ext, c[0], c[1] + ext, style)
                        // A hole the model does not have is drawn here: solid from the face it is drilled in, else hidden.
                        if (h in extraHoles) dl.circle(c[0], c[1], rp, if ((h.axis dot def.n) < 0) LineStyle.THICK else LineStyle.HIDDEN)
                    } else if (along < 0.02 && h.depth != null) {
                        val a = paper(h.start); val e = paper(h.end)
                        val len = Math.hypot(e[0] - a[0], e[1] - a[1])
                        if (len > 1e-6) {
                            val ux = (e[0] - a[0]) / len * 3; val uy = (e[1] - a[1]) / len * 3; dl.line(a[0] - ux, a[1] - uy, e[0] + ux, e[1] + uy, LineStyle.CENTER)
                            if (h in extraHoles) {
                                // Its sides and bottom, hidden inside the part.
                                val px = -uy / 3 * h.diameter / 2 * s; val py = ux / 3 * h.diameter / 2 * s
                                dl.line(a[0] + px, a[1] + py, e[0] + px, e[1] + py, LineStyle.HIDDEN); dl.line(a[0] - px, a[1] - py, e[0] - px, e[1] - py, LineStyle.HIDDEN)
                                dl.line(e[0] - px, e[1] - py, e[0] + px, e[1] + py, LineStyle.HIDDEN)
                            }
                        }
                    }
                }
                val notes = callouts.filter { it.view == name }
                if (notes.size > MAX_CALLOUTS) qa += "${notes.size - MAX_CALLOUTS} more hole size(s) in the $name view are not called out"
                val base = margins(name, groups, th, vs.sections)
                val x1 = o[0] + (b[2] - b[0]) * s; val y1 = o[1] + (b[3] - b[1]) * s
                // Every hole this view shows as a circle: a leader must not run across any of them.
                val circles = holes.filter { it.principalAxis != null && kotlin.math.abs(it.axis dot def.n) > Hole.ALIGNED }.map { val c = paper(it.center); doubleArrayOf(c[0], c[1], it.diameter / 2 * s) }
                fun clear(cx: Double, cy: Double, ex: Double, ey: Double): Boolean {
                    val dx = ex - cx; val dy = ey - cy; val l2 = dx * dx + dy * dy
                    for (k in circles) {
                        if (Math.hypot(k[0] - cx, k[1] - cy) < 1e-6) continue
                        val t = (((k[0] - cx) * dx + (k[1] - cy) * dy) / l2).coerceIn(0.0, 1.0)
                        if (Math.hypot(cx + t * dx - k[0], cy + t * dy - k[1]) < k[2] + 1.5) return false
                    }
                    return true
                }
                val usedRows = ArrayList<Double>()
                notes.take(MAX_CALLOUTS).forEachIndexed { i, note ->
                    val tw = splitziner.sheet.StrokeFont.width(note.text, th)
                    fun textBox(ex: Double, ey: Double) = splitziner.sheet.Box(ex + 4, ey - th / 2, ex + 4 + tw, ey + th / 2, "dim")
                    fun free(ex: Double, ey: Double): Boolean {
                        val t = textBox(ex, ey)
                        return t.x1 <= sheet.fieldX1 - 2 && t.y1 <= sheet.fieldY1 - 2 && t.y0 >= sheet.fieldY0 + 2 && dl.overlapArea(t) <= 0.0
                    }
                    // Candidate ends for the leader, best first. The note keeps its place; the leader looks for a hole it can reach cleanly.
                    var pick: DoubleArray? = null
                    var fallback: DoubleArray? = null
                    if (name == "top") {
                        val ex = x1 + base.right + 4
                        for (h in note.holes.sortedByDescending { paper(it.center)[0] }.take(6)) {
                            val c = paper(h.center); val rp = h.diameter / 2 * s
                            for (j in 1..60) for (sgn in doubleArrayOf(1.0, -1.0)) {
                                // Never straight along the row of centres: start a little above or below it.
                                val ey = c[1] + sgn * (1.5 + j * rowHeight(th) / 2)
                                if (usedRows.any { kotlin.math.abs(it - ey) < rowHeight(th) }) continue
                                if (fallback == null) fallback = doubleArrayOf(c[0], c[1], rp, ex, ey)
                                if (pick == null && clear(c[0], c[1], ex, ey) && free(ex, ey)) pick = doubleArrayOf(c[0], c[1], rp, ex, ey)
                            }
                            if (pick != null) break
                        }
                    } else {
                        val ey = y1 + base.top + 3 + rowHeight(th) * i + th / 2
                        for (h in note.holes.sortedByDescending { paper(it.center)[1] }.take(6)) {
                            val c = paper(h.center); val rp = h.diameter / 2 * s
                            // About 60 degrees from the horizontal to begin with, then steeper or shallower as needed.
                            val want = c[0] + (ey - c[1]) * 0.58
                            for (j in 0..40) for (sgn in doubleArrayOf(1.0, -1.0)) {
                                val ex = want + sgn * j * 3.0
                                if (fallback == null) fallback = doubleArrayOf(c[0], c[1], rp, minOf(ex, sheet.fieldX1 - 6 - tw - 4), ey)
                                if (pick == null && ex > o[0] - 10 && clear(c[0], c[1], ex, ey) && free(ex, ey)) pick = doubleArrayOf(c[0], c[1], rp, ex, ey)
                            }
                            if (pick != null) break
                        }
                    }
                    val q = pick ?: fallback ?: return@forEachIndexed
                    if (pick == null) dl.qa += "A hole note could not be placed clear of other items"
                    val ex = q[3]; val ey = q[4]
                    usedRows += ey
                    val dx = ex - q[0]; val dy = ey - q[1]; val len = Math.hypot(dx, dy)
                    if (len > q[2] + 1) {
                        val tx = q[0] + dx / len * q[2]; val ty = q[1] + dy / len * q[2]
                        dl.polyline(doubleArrayOf(tx, ty, ex, ey, ex + 3, ey)); dl.arrow(tx, ty, -dx, -dy)
                    }
                    dl.text(ex + 4, ey, note.text, th, splitziner.sheet.HAlign.LEFT, splitziner.sheet.VAlign.CENTER, tag = "dim")
                }
            }
            val angled = holes.count { it.principalAxis == null }
            if (angled > 0) qa += "$angled hole(s) drilled at an angle are drawn but not dimensioned"
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
        // A pictorial view in a free corner of the sheet, as drafters add when there is room: it shows what
        // the three views add up to. Left off very large assemblies, where it would cost seconds for a small picture.
        if (drawn.size <= ISO_MAX_PARTS) runCatching {
            val tm = margins("top", groups, th, vs.sections, callouts); val rm = margins("right", groups, th, vs.sections, callouts)
            val ot = origin.getValue("top"); val orr = origin.getValue("right")
            val nb = Furniture.notes(DisplayList(1.0, 1.0), sheet, L, info, notes, draw = false)
            val ceiling = (nb?.get(1) ?: (sheet.fieldY1 - Furniture.revisionBlockHeight(info))) - 6
            val x1 = sheet.fieldX1 - 4
            // Two candidate gaps: above the right view (beside the top view), or right of the right view down to the list.
            val above = doubleArrayOf(maxOf(ot[0] + wt * s + tm.right + CALL, orr[0] - rm.left) + 6, orr[1] + hr * s + rm.top + CALL + 6, x1, ceiling)
            val besideX = orr[0] + wr * s + rm.right + CALL + 6
            val besideBottom = sheet.fieldY0 + Furniture.TITLE_HEIGHT + listH + 6
            val beside = doubleArrayOf(besideX, besideBottom, x1, ceiling)
            fun fair(b: DoubleArray) = b[2] - b[0] >= ISO_MIN_MM && b[3] - b[1] >= ISO_MIN_MM * 0.7
            val box = listOf(above, beside).filter { fair(it) }.maxByOrNull { b -> minOf(b[2] - b[0], 1.5 * (b[3] - b[1])) * (b[3] - b[1]) }
            if (box != null) {
                val x0 = box[0]; val y0 = box[1]; val y1 = box[3]; val x1b = box[2]
                val geo = splitziner.explode.ExplodedView.prepare(drawn.map { it.copy(explode = null) })
                val mark = dl.prims.size
                splitziner.explode.ExplodedView.drawInto(dl, geo, x0, y0 + L.dims * 2.5, x1b, y1, splitziner.explode.ExplodeOptions(balloons = false, textHeight = th))
                // The title goes just under the picture as drawn, not at the bottom of the gap it was centred in.
                var bx0 = Double.MAX_VALUE; var bx1 = -Double.MAX_VALUE; var by0 = Double.MAX_VALUE
                for (pr in dl.prims.subList(mark, dl.prims.size)) {
                    val xy = when (pr) { is splitziner.sheet.Segments -> pr.xy; is splitziner.sheet.Polyline -> pr.xy; is splitziner.sheet.Fill -> pr.xy; else -> null } ?: continue
                    for (q in xy.indices step 2) { bx0 = minOf(bx0, xy[q]); bx1 = maxOf(bx1, xy[q]); by0 = minOf(by0, xy[q + 1]) }
                }
                val (cx, ty) = if (bx0 < bx1) (bx0 + bx1) / 2 to by0 - L.dims * 2.5 else (x0 + x1b) / 2 to y0
                dl.text(cx, maxOf(y0, ty), "PICTORIAL VIEW, SCALE: NONE", L.dims, splitziner.sheet.HAlign.CENTER, tag = "section")
            }
        }.onFailure { qa += "Pictorial view left off: ${it.message}" }
        qa += dl.qa
        val sheets = arrayListOf(dl)
        for ((pi, page) in listPages.withIndex()) {
            val d = DisplayList(sheet.width, sheet.height, "list${pi + 1}")
            val t = Furniture.draw(d, sheet, L, info, scaleText(s), fmt.units, sheets.size + 1, 0)
            val top = ListOfMaterial.draw(d, page, sheet.fieldX1, t.top + 4, L, single)
            if (listPages.size > 1) d.text(sheet.fieldX1 - 2, top + 3, "CONTINUED: ${pi + 1} OF ${listPages.size}", L.dims, splitziner.sheet.HAlign.RIGHT)
            sheets += d
        }
        if (sheetCount == 2) {
            val d2 = DisplayList(sheet.width, sheet.height, "sheet${sheets.size + 1}")
            val t2 = Furniture.draw(d2, sheet, L, info, scaleText(s), fmt.units, sheets.size + 1, 0)
            // Big assemblies show only their outer layer pulled away; the build guide shows every step.
            val scene = splitziner.explode.ExplodedView.peel(planned!!.filter { !it.excluded })
            val geo = splitziner.explode.ExplodedView.prepare(scene)
            val shown = HashSet(splitziner.explode.ExplodedView.drawInto(d2, geo, sheet.fieldX0 + 4, t2.top + 12, sheet.fieldX1 - 4, sheet.fieldY1 - 4, splitziner.explode.ExplodeOptions(textHeight = L.dims)))
            d2.text(sheet.fieldX0 + 8, t2.y0 + 14, "EXPLODED VIEW", L.descTitle, tag = "section")
            d2.text(sheet.fieldX0 + 8, t2.y0 + 14 - L.dims * 1.6, "SCALE: NONE", L.dims)
            qa += d2.qa
            sheets += d2
            // Every listed item must be pointed to at least once. Items the overall view cannot show (inside the
            // assembly, or crowded out) get further exploded views of the build steps they belong to.
            val live = planned.filter { !it.excluded }
            var extra = 0
            var after = Int.MIN_VALUE           // steps already given a sheet
            while (extra < MAX_EXTRA_EXPLODED) {
                val missing = rows.map { it.item }.filter { it !in shown }.toSet()
                if (missing.isEmpty()) break
                val firstStep = live.filter { it.item in missing }.mapNotNull { it.step }.filter { it > after }.minOrNull() ?: break
                // Steps from the first one with a missing item, until the picture holds enough parts to be readable.
                val steps = live.mapNotNull { it.step }.distinct().sorted().filter { it >= firstStep }
                val chosenSteps = ArrayList<Int>(); var count = 0
                for (st in steps) { val k = live.count { it.step == st }; if (chosenSteps.isNotEmpty() && count + k > PARTS_PER_EXTRA) break; chosenSteps += st; count += k }
                // As the build guide shows a step: parts built before it in place and grey for context, this step's parts pulled out.
                val earlier = live.filter { (it.step ?: 0) < chosenSteps.first() }
                val subset = live.filter { it.step in chosenSteps }
                val scene = earlier.map { it.copy(explode = null) } + subset
                val d = DisplayList(sheet.width, sheet.height, "sheet${sheets.size + 1}")
                val t = Furniture.draw(d, sheet, L, info, scaleText(s), fmt.units, sheets.size + 1, 0)
                val g = splitziner.explode.ExplodedView.prepare(scene, splitziner.explode.ExplodedView.pickView(live))
                val got = splitziner.explode.ExplodedView.drawInto(d, g, sheet.fieldX0 + 4, t.top + 12, sheet.fieldX1 - 4, sheet.fieldY1 - 4,
                    splitziner.explode.ExplodeOptions(textHeight = L.dims, highlight = subset.map { it.id }.toSet(), ghost = earlier.map { it.id }.toSet()))
                val label = if (chosenSteps.size == 1) "STEP ${chosenSteps[0]}" else "STEPS ${chosenSteps.first()} TO ${chosenSteps.last()}"
                d.text(sheet.fieldX0 + 8, t.y0 + 14, "EXPLODED VIEW, $label", L.descTitle, tag = "section")
                d.text(sheet.fieldX0 + 8, t.y0 + 14 - L.dims * 1.6, "PARTS ADDED IN THE BUILD GUIDE'S $label; EARLIER PARTS SHOWN GREY. SCALE: NONE", L.dims)
                after = chosenSteps.last()
                // A sheet that labels nothing new is not kept; the next steps get their turn.
                if (got.none { it in missing }) continue
                shown += got; sheets += d; extra++
            }
            // Items still hidden (inside a frame, under a top, behind the grey earlier parts) get one last view:
            // just those parts, pulled apart from each other with nothing around them.
            val hidden = rows.map { it.item }.filter { it !in shown }.toSet()
            if (hidden.isNotEmpty()) {
                val alone = live.filter { it.item in hidden }
                val d = DisplayList(sheet.width, sheet.height, "sheet${sheets.size + 1}")
                val t = Furniture.draw(d, sheet, L, info, scaleText(s), fmt.units, sheets.size + 1, 0)
                val spread = runCatching { splitziner.explode.AssemblyPlan.plan(alone.map { it.copy(explode = null, step = null) }).parts }.getOrDefault(alone)
                val g = splitziner.explode.ExplodedView.prepare(spread, splitziner.explode.ExplodedView.pickView(live))
                val got = splitziner.explode.ExplodedView.drawInto(d, g, sheet.fieldX0 + 4, t.top + 12, sheet.fieldX1 - 4, sheet.fieldY1 - 4, splitziner.explode.ExplodeOptions(textHeight = L.dims))
                d.text(sheet.fieldX0 + 8, t.y0 + 14, "DETAIL OF HIDDEN PARTS", L.descTitle, tag = "section")
                d.text(sheet.fieldX0 + 8, t.y0 + 14 - L.dims * 1.6, "PARTS NOT SEEN IN THE OTHER EXPLODED VIEWS, SHOWN ON THEIR OWN. SCALE: NONE", L.dims)
                if (got.any { it in hidden }) { shown += got; sheets += d }
            }
            val unshown = rows.map { it.item }.filter { it !in shown }
            if (unshown.isNotEmpty()) qa += "Item(s) ${unshown.joinToString(", ")} are not identified by a balloon on any sheet"
        }
        // Sheet 1 states how many sheets the drawing has; that is only known now.
        val total = sheets.size
        sheets[0].prims.replaceAll { p -> if (p is splitziner.sheet.Text && p.text.startsWith("SHEET 1 OF")) splitziner.sheet.Text(p.x, p.y, "SHEET 1 OF $total", p.height, p.rotationDeg, p.hAlign, p.vAlign, p.bold, p.argb) else p }
        return DrawingResult(sheets, sheet, s, scaleText(s), qa)
    }
}
