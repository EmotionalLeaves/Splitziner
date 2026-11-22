package splitziner.dims

import splitziner.math.Vec3
import splitziner.mesh.Mesh
import splitziner.sheet.DisplayList
import splitziner.sheet.HAlign
import splitziner.sheet.LineStyle
import splitziner.sheet.StrokeFont
import splitziner.sheet.VAlign
import splitziner.view.ViewDef
import splitziner.view.ViewLines
import kotlin.math.abs

/** One dimension: a model coordinate on an axis, and the text to show (its distance from the datum). */
class DimItem(
    val coord: Double, val text: String, val kind: String,
    /** View coordinates the extension line starts from, for features that are not an edge (a hole's centre). */
    val anchor: DoubleArray? = null,
)

/** Dimensions that share a view, a direction and a side, drawn as one stack. */
class DimGroup(
    val view: String,
    /** True when the dimension measures along screen X (its dimension line is horizontal). */
    val horizontal: Boolean,
    val axis: Int,
    /** "bottom", "top", "left" or "right" of the view. */
    val side: String,
    val items: List<DimItem>,
    /** "baseline" or "ordinate". */
    val style: String,
)

/**
 * Chooses what to dimension: the overall size, and the main flat levels (shelf tops, steps),
 * all measured from one datum corner, with no two dimensions showing the same value.
 */
object DimPlanner {
    /** A level must cover at least this share of the largest level on its axis to earn a dimension. */
    private const val MIN_LEVEL_SHARE = 0.15

    /** A level must also cover at least this share of the model's whole surface. */
    private const val MIN_LEVEL_OF_SURFACE = 0.002

    /** More than this many dimensions in a stack switches it from baseline to ordinate style. */
    private const val MAX_BASELINE = 5

    /** Inner flat levels facing the positive direction of each axis, largest first. */
    fun levels(scene: Mesh, lo: Vec3, hi: Vec3, maxPerAxis: Int): List<List<Double>> = (0..2).map { k ->
        val n = scene.faceNormals; val c = scene.faceCenters; val a = scene.faceAreas
        val area = HashMap<Long, Double>()
        for (t in 0 until scene.triangleCount) if (n[3 * t + k] > 0.9999) {
            val key = Math.round(c[3 * t + k] * 1000)
            area[key] = (area[key] ?: 0.0) + a[t]
        }
        val max = area.values.maxOrNull() ?: 0.0
        // On sculpted shapes a "level" can be one stray facet; a real one is a visible share of the whole surface.
        val floor = MIN_LEVEL_OF_SURFACE * scene.area
        area.entries.filter { it.value >= MIN_LEVEL_SHARE * max && it.value >= floor }.map { it.key / 1000.0 to it.value }
            .filter { it.first > lo[k] + 1e-6 && it.first < hi[k] - 1e-6 }
            .sortedByDescending { it.second }.take(maxPerAxis).map { it.first }
    }

    /** True when the view has a visible edge lying exactly at screen coordinate [sc] across the measuring direction. */
    fun hasEdgeAt(lines: ViewLines, horizontal: Boolean, sc: Double): Boolean = anchor(lines, horizontal, sc, true) != null

    /**
     * End of a visible edge at screen coordinate [sc], nearest the low side ([low]) or the high side.
     * For a horizontal dimension the edge is vertical (constant x); the anchor is its lowest or highest point.
     */
    fun anchor(lines: ViewLines, horizontal: Boolean, sc: Double, low: Boolean): DoubleArray? {
        val s = lines.visible
        val tol = 1e-3 * maxOf(1.0, abs(sc)) + 1e-4
        val a = if (horizontal) 0 else 1; val o = 1 - a
        var best: DoubleArray? = null
        var i = 0
        while (i < s.size) {
            if (abs(s[i + a] - sc) < tol && abs(s[i + 2 + a] - sc) < tol) {
                for (e in 0..1) {
                    val p = doubleArrayOf(s[i + 2 * e], s[i + 2 * e + 1])
                    if (best == null || (low && p[o] < best[o]) || (!low && p[o] > best[o])) best = p
                }
            }
            i += 4
        }
        return best
    }

    private class Placement(val view: String, val horizontal: Boolean, val side: String)

    // For each model axis: where its dimensions go first, and where they go if the first view cannot show them.
    private val PLACES = listOf(
        listOf(Placement("front", true, "bottom"), Placement("top", true, "top")),
        listOf(Placement("right", true, "bottom"), Placement("top", false, "left")),
        listOf(Placement("front", false, "left"), Placement("right", false, "right")),
    )

    /** At most this many hole positions are dimensioned per axis; a pegboard would otherwise bury the view. */
    const val MAX_HOLE_DIMS = 12

    // The view that shows a hole along each model axis as a circle.
    private val CIRCLE_VIEW = listOf(ViewDef.RIGHT, ViewDef.FRONT, ViewDef.TOP)

    fun plan(scene: Mesh, views: Map<String, ViewLines>, fmt: LengthFormat, maxLevels: Int, holes: List<splitziner.features.Hole> = emptyList()): List<DimGroup> {
        val lo = scene.bounds.min; val hi = scene.bounds.max
        val lv = levels(scene, lo, hi, maxLevels)
        val groups = LinkedHashMap<String, MutableList<DimItem>>()
        val meta = HashMap<String, Placement>()
        for (k in 0..2) {
            val used = HashSet<String>()
            fun add(pl: Placement, coord: Double, kind: String, anchor: DoubleArray? = null): Boolean {
                val text = fmt.format(abs(coord - lo[k]))
                // One dimension per displayed value: 36.000 and 36.004 would both read 36.00.
                if (!used.add(text)) return false
                val key = "${pl.view}/${pl.horizontal}/$k"
                groups.getOrPut(key) { ArrayList() } += DimItem(coord, text, kind, anchor); meta[key] = pl
                return true
            }
            used += fmt.format(0.0)
            add(PLACES[k][0], hi[k], "overall")
            for (c in lv[k]) {
                val pl = PLACES[k].firstOrNull { p -> views[p.view]?.let { hasEdgeAt(it, p.horizontal, c) } == true } ?: continue
                add(pl, c, "face")
            }
            // Where to drill: each hole's centre, in the view that shows the hole as a circle.
            var holeDims = 0
            for (d in 0..2) {
                if (d == k) continue
                val view = CIRCLE_VIEW[d]
                val pl = PLACES[k].firstOrNull { it.view == view.name } ?: continue
                val low = pl.side == "bottom" || pl.side == "left"
                // Of several holes in line, the one nearest the dimension's side carries the extension line.
                val here = holes.filter { it.principalAxis == d }.map { it to view.project(it.center) }
                    .sortedBy { (_, p) -> (if (pl.horizontal) p.y else p.x) * (if (low) 1 else -1) }
                for ((h, p) in here) {
                    if (holeDims >= MAX_HOLE_DIMS) break
                    if (add(pl, h.center[k], "hole", doubleArrayOf(p.x, p.y))) holeDims++
                }
            }
        }
        return groups.map { (key, items) ->
            val pl = meta.getValue(key); val k = key.substringAfterLast('/').toInt()
            DimGroup(pl.view, pl.horizontal, k, pl.side, items.sortedBy { abs(it.coord - lo[k]) }, if (items.size > MAX_BASELINE) "ordinate" else "baseline")
        }
    }
}

/** Draws dimension stacks on paper. Constants are millimetres on the sheet. */
object DimDrawer {
    /** From the view edge to the first dimension line. */
    const val FIRST = 10.0
    /** Between dimension lines. */
    const val STEP = 7.0
    /** Gap between the feature and the start of an extension line. */
    private const val EXT_GAP = 1.5
    /** How far an extension line runs past the dimension line. */
    private const val EXT_OVER = 2.0
    private const val ARROW = 3.0

    private fun pitch(g: DimGroup, th: Double): Double =
        if (g.horizontal) maxOf(STEP, 2.2 * th) else maxOf(STEP, g.items.maxOf { StrokeFont.width(it.text, th) } * 1.5 + 4)

    /** Paper needed beside the view for this stack. */
    fun margin(g: DimGroup, th: Double): Double {
        val widest = g.items.maxOf { StrokeFont.width(it.text, th) }
        return if (g.style == "ordinate") FIRST + widest + 6 + (if (g.horizontal) 0.0 else 0.0)
        else first(g, th) + pitch(g, th) * (g.items.size - 1) + (if (g.horizontal) th else widest + 1.5) + 3
    }

    /** Distance from the view to the first dimension line: a vertical value centred on its line must clear the view. */
    private fun first(g: DimGroup, th: Double): Double =
        if (g.horizontal) FIRST else maxOf(FIRST, g.items.maxOf { StrokeFont.width(it.text, th) } / 2 + 3.5)

    /**
     * [toPaper] maps view coordinates to the sheet; [box] is the view's paper box (x0,y0,x1,y1);
     * [datum] is the datum corner's screen coordinate on this group's measuring axis.
     */
    fun draw(dl: DisplayList, g: DimGroup, lines: ViewLines, view: ViewDef, datum: Double, th: Double, box: DoubleArray, toPaper: (Double, Double) -> DoubleArray) {
        val low = g.side == "bottom" || g.side == "left"
        val sign = if (low) -1.0 else 1.0
        // Where on the view an extension line starts: the end of a real edge if there is one, else the view's edge.
        fun anchorPaper(coord: Double): DoubleArray {
            val a = DimPlanner.anchor(lines, g.horizontal, coord, low)
            if (a != null) return toPaper(a[0], a[1])
            val b = lines.bounds
            return if (g.horizontal) toPaper(coord, if (low) b[1] else b[3]) else toPaper(if (low) b[0] else b[2], coord)
        }
        val edge = if (g.horizontal) (if (low) box[1] else box[3]) else (if (low) box[0] else box[2])
        val d0 = anchorPaper(datum)
        fun anchorOf(it: DimItem): DoubleArray = it.anchor?.let { a -> toPaper(a[0], a[1]) } ?: anchorPaper(it.coord)

        if (g.style == "ordinate") {
            val reach = edge + sign * (FIRST + 2)
            val all = listOf(DimItem(datum, "0", "datum")) + g.items
            // Labels too close together are pushed apart, each by half the shortfall, until they fit.
            val pos = all.map { val p = anchorOf(it); if (g.horizontal) p[0] else p[1] }.toDoubleArray()
            val order = pos.indices.sortedBy { pos[it] }
            val gap = 1.6 * th
            repeat(60) {
                var moved = false
                for (j in 1 until order.size) {
                    val a = order[j - 1]; val b = order[j]
                    if (pos[b] - pos[a] < gap) { val s = (gap - (pos[b] - pos[a])) / 2 + 1e-6; pos[a] -= s; pos[b] += s; moved = true }
                }
                if (!moved) return@repeat
            }
            all.forEachIndexed { i, it ->
                val p = anchorOf(it)
                if (g.horizontal) {
                    val jog = edge + sign * (FIRST - 4)
                    dl.polyline(doubleArrayOf(p[0], p[1] + sign * EXT_GAP, p[0], jog, pos[i], jog + sign * 2, pos[i], reach))
                    dl.text(pos[i], reach + sign * 1.2, it.text, th, if (low) HAlign.RIGHT else HAlign.LEFT, VAlign.CENTER, rotationDeg = 90.0, tag = "dim")
                } else {
                    val jog = edge + sign * (FIRST - 4)
                    dl.polyline(doubleArrayOf(p[0] + sign * EXT_GAP, p[1], jog, p[1], jog + sign * 2, pos[i], reach, pos[i]))
                    dl.text(reach + sign * 1.2, pos[i], it.text, th, if (low) HAlign.RIGHT else HAlign.LEFT, VAlign.CENTER, tag = "dim")
                }
            }
            return
        }

        val step = pitch(g, th)
        g.items.forEachIndexed { i, it ->
            val off = edge + sign * (first(g, th) + step * i)
            val p = anchorOf(it)
            val tw = StrokeFont.width(it.text, th)
            if (g.horizontal) {
                dl.line(p[0], p[1] + sign * EXT_GAP, p[0], off + sign * EXT_OVER)
                if (i == g.items.size - 1) dl.line(d0[0], d0[1] + sign * EXT_GAP, d0[0], off + sign * EXT_OVER)
                val x0 = minOf(d0[0], p[0]); val x1 = maxOf(d0[0], p[0])
                linear(dl, x0, off, x1, off, it.text, th, tw / 2 + 1, tw, sign)
            } else {
                dl.line(p[0] + sign * EXT_GAP, p[1], off + sign * EXT_OVER, p[1])
                if (i == g.items.size - 1) dl.line(d0[0] + sign * EXT_GAP, d0[1], off + sign * EXT_OVER, d0[1])
                val y0 = minOf(d0[1], p[1]); val y1 = maxOf(d0[1], p[1])
                linear(dl, off, y0, off, y1, it.text, th, th / 2 + 1.2, tw, sign)
            }
        }
    }

    /** One dimension line from a to b with arrowheads and its value, horizontal text in a break of the line. */
    private fun linear(dl: DisplayList, ax: Double, ay: Double, bx: Double, by: Double, text: String, th: Double, halfBreak: Double, tw: Double, outward: Double) {
        val horiz = ay == by
        val len = if (horiz) bx - ax else by - ay
        val ux = if (horiz) 1.0 else 0.0; val uy = if (horiz) 0.0 else 1.0
        val mx = (ax + bx) / 2; val my = (ay + by) / 2
        val need = (if (horiz) tw + 2 else th + 2) + 2 * ARROW + 2
        if (len >= need) {
            dl.arrow(ax, ay, -ux, -uy); dl.arrow(bx, by, ux, uy)
            dl.line(ax, ay, mx - ux * halfBreak, my - uy * halfBreak); dl.line(mx + ux * halfBreak, my + uy * halfBreak, bx, by)
            dl.text(mx, my, text, th, HAlign.CENTER, VAlign.CENTER, tag = "dim")
        } else {
            // Too short for the value: arrows point inward from outside.
            dl.arrow(ax, ay, ux, uy); dl.arrow(bx, by, -ux, -uy)
            dl.line(ax - ux * 5, ay - uy * 5, bx + ux * 5, by + uy * 5)
            // Horizontal: the value sits beyond the second extension line. Vertical: beside the line, on the
            // side away from the view, where the spacing between dimension lines leaves room for it.
            if (horiz) dl.text(bx + 6, by, text, th, HAlign.LEFT, VAlign.CENTER, tag = "dim")
            // Vertical: above the upper extension line, on the side away from the view, so neither extension line
            // runs through the value.
            else dl.text(ax + outward * 1.5, by + 5 + th / 2, text, th, if (outward < 0) HAlign.RIGHT else HAlign.LEFT, VAlign.CENTER, tag = "dim")
        }
    }
}
