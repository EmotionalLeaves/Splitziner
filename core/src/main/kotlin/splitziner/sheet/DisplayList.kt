package splitziner.sheet

import splitziner.math.Vec2

/** Line conventions. Widths and dash patterns are in [Styles]. */
enum class LineStyle { THICK, THIN, HIDDEN, CENTER, PHANTOM, CUT, BORDER, HATCH, OUTLINE, GHOST, TRACE }

enum class HAlign { LEFT, CENTER, RIGHT }
enum class VAlign { BOTTOM, CENTER, TOP }

object Styles {
    const val BLACK = 0xFF000000.toInt()
    const val GREY = 0xFF9A9A9A.toInt()

    /**
     * Width in mm on paper. The drawing standard asks for two widths, thin and thick, at 1:2,
     * with cutting-plane lines the heaviest on the sheet.
     */
    fun width(s: LineStyle): Double = when (s) {
        LineStyle.THICK -> 0.6; LineStyle.THIN -> 0.3; LineStyle.HIDDEN -> 0.3; LineStyle.CENTER -> 0.3
        LineStyle.PHANTOM -> 0.3; LineStyle.CUT -> 0.8; LineStyle.BORDER -> 0.9; LineStyle.HATCH -> 0.25
        LineStyle.OUTLINE -> 0.85; LineStyle.GHOST -> 0.3; LineStyle.TRACE -> 0.3
    }

    /** Dash, gap, dash, gap... in mm; null for a solid line. */
    fun dash(s: LineStyle): DoubleArray? = when (s) {
        LineStyle.HIDDEN -> doubleArrayOf(3.0, 1.0)
        LineStyle.CENTER -> doubleArrayOf(18.0, 1.5, 3.0, 1.5)
        LineStyle.PHANTOM -> doubleArrayOf(18.0, 1.5, 3.0, 1.5, 3.0, 1.5)
        LineStyle.TRACE -> doubleArrayOf(7.0, 1.2, 1.5, 1.2)
        else -> null
    }

    fun color(s: LineStyle): Int = if (s == LineStyle.GHOST) GREY else BLACK
}

sealed interface Prim
class Segments(val xy: DoubleArray, val style: LineStyle) : Prim
class Polyline(val xy: DoubleArray, val style: LineStyle, val closed: Boolean = false) : Prim
class Fill(val xy: DoubleArray, val argb: Int = Styles.BLACK) : Prim
class Circle(val cx: Double, val cy: Double, val r: Double, val style: LineStyle) : Prim
class Text(
    val x: Double, val y: Double, val text: String, val height: Double, val rotationDeg: Double,
    val hAlign: HAlign, val vAlign: VAlign, val bold: Boolean, val argb: Int,
) : Prim
/** Another display list drawn inside a rectangle of this one, scaled to fit and centred. */
class Image(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val child: DisplayList) : Prim

/** A rectangle something occupies on the sheet, so later annotations can avoid it. */
class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val tag: String) {
    fun overlap(o: Box): Double {
        val w = minOf(x1, o.x1) - maxOf(x0, o.x0); val h = minOf(y1, o.y1) - maxOf(y0, o.y0)
        return if (w > 0 && h > 0) w * h else 0.0
    }
}

/**
 * Everything drawn on one sheet or page, in paper millimetres with the origin at the bottom-left
 * and Y up. Nothing in the engine draws to a screen or file directly; every backend reads this.
 */
class DisplayList(val widthMm: Double, val heightMm: Double, val name: String = "") {
    val prims = ArrayList<Prim>()
    val boxes = ArrayList<Box>()
    val qa = ArrayList<String>()

    /** Drawings are lettered in capitals throughout; build guides keep the writer's capitalisation. */
    var upperCase = true

    fun segs(xy: DoubleArray, style: LineStyle) { if (xy.isNotEmpty()) prims += Segments(xy, style) }
    fun line(x0: Double, y0: Double, x1: Double, y1: Double, style: LineStyle = LineStyle.THIN) { prims += Segments(doubleArrayOf(x0, y0, x1, y1), style) }
    fun polyline(xy: DoubleArray, style: LineStyle = LineStyle.THIN, closed: Boolean = false) { prims += Polyline(xy, style, closed) }
    fun rect(x0: Double, y0: Double, x1: Double, y1: Double, style: LineStyle = LineStyle.THIN) = polyline(doubleArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), style, true)
    fun circle(cx: Double, cy: Double, r: Double, style: LineStyle = LineStyle.THIN) { prims += Circle(cx, cy, r, style) }
    fun fill(xy: DoubleArray, argb: Int = Styles.BLACK) { prims += Fill(xy, argb) }

    /** Adds text and returns the box it occupies. With a [tag] the box is also registered for collision checks. */
    fun text(
        x: Double, y: Double, s: String, height: Double, hAlign: HAlign = HAlign.LEFT, vAlign: VAlign = VAlign.BOTTOM,
        rotationDeg: Double = 0.0, bold: Boolean = false, tag: String? = null, argb: Int = Styles.BLACK,
    ): Box {
        val t = if (upperCase) s.uppercase() else s
        prims += Text(x, y, t, height, rotationDeg, hAlign, vAlign, bold, argb)
        val w = StrokeFont.width(t, height)
        val bx = when (hAlign) { HAlign.LEFT -> x; HAlign.CENTER -> x - w / 2; HAlign.RIGHT -> x - w }
        val by = when (vAlign) { VAlign.BOTTOM -> y; VAlign.CENTER -> y - height / 2; VAlign.TOP -> y - height }
        val box = if (rotationDeg == 90.0) {
            // Reads from the right-hand side of the sheet: the text runs up the page.
            val ox = when (vAlign) { VAlign.BOTTOM -> x - height; VAlign.CENTER -> x - height / 2; VAlign.TOP -> x }
            val oy = when (hAlign) { HAlign.LEFT -> y; HAlign.CENTER -> y - w / 2; HAlign.RIGHT -> y - w }
            Box(ox, oy, ox + height, oy + w, tag ?: "text")
        } else Box(bx, by, bx + w, by + height, tag ?: "text")
        if (tag != null) boxes += box
        return box
    }

    /** Filled arrowhead with its tip at ([tx], [ty]) pointing along ([dx], [dy]): 3 mm long, 1 mm wide. */
    fun arrow(tx: Double, ty: Double, dx: Double, dy: Double, length: Double = 3.0, halfWidth: Double = 0.5) {
        val d = Vec2(dx, dy).normalized(); val p = d.perp()
        val bx = tx - d.x * length; val by = ty - d.y * length
        fill(doubleArrayOf(tx, ty, bx + p.x * halfWidth, by + p.y * halfWidth, bx - p.x * halfWidth, by - p.y * halfWidth))
    }

    fun dot(x: Double, y: Double, r: Double = 0.6) {
        val n = 12
        fill(DoubleArray(2 * n) { i -> val a = Math.PI * 2 * (i / 2) / n; if (i % 2 == 0) x + r * Math.cos(a) else y + r * Math.sin(a) })
    }

    fun register(x0: Double, y0: Double, x1: Double, y1: Double, tag: String): Box = Box(x0, y0, x1, y1, tag).also { boxes += it }

    fun overlapArea(b: Box, ignoreTags: Set<String> = emptySet()): Double =
        boxes.sumOf { if (it.tag in ignoreTags) 0.0 else it.overlap(b) }

    /** True when the straight line from a to b crosses no registered box (other than [ignoreTags]). */
    fun segmentFree(ax: Double, ay: Double, bx: Double, by: Double, ignoreTags: Set<String> = emptySet()): Boolean {
        for (k in boxes) {
            if (k.tag in ignoreTags) continue
            // Sample along the segment; boxes are far larger than the sampling step.
            val steps = 24
            for (i in 1 until steps) {
                val x = ax + (bx - ax) * i / steps; val y = ay + (by - ay) * i / steps
                if (x > k.x0 && x < k.x1 && y > k.y0 && y < k.y1) return false
            }
        }
        return true
    }
}
