package splitziner.export

import splitziner.sheet.Circle
import splitziner.sheet.DisplayList
import splitziner.sheet.Fill
import splitziner.sheet.HAlign
import splitziner.sheet.Image
import splitziner.sheet.Polyline
import splitziner.sheet.Prim
import splitziner.sheet.Segments
import splitziner.sheet.StrokeFont
import splitziner.sheet.Styles
import splitziner.sheet.Text
import splitziner.sheet.VAlign

sealed interface Op

/**
 * One stroked path. [xy] holds points; [starts] marks the index (in points) where each
 * sub-path begins, and [closed] says whether that sub-path returns to its start.
 */
class StrokeOp(val xy: DoubleArray, val starts: IntArray, val closed: BooleanArray, val widthMm: Double, val dashMm: DoubleArray?, val argb: Int) : Op
class FillOp(val xy: DoubleArray, val argb: Int) : Op

/**
 * Turns a display list into plain stroke and fill operations that any backend can draw:
 * text becomes strokes, circles become polygons, nested images are placed and scaled.
 * Consecutive primitives that share a pen are batched into one path.
 */
object RenderOps {
    /** Lettering stroke is a tenth of its height, kept within what prints cleanly. */
    fun textStroke(height: Double, bold: Boolean): Double = ((if (bold) height / 7 else height / 10)).coerceIn(0.25, 0.9)

    private class Pen(val width: Double, val dash: DoubleArray?, val argb: Int) {
        fun same(o: Pen) = width == o.width && argb == o.argb && (dash === o.dash || (dash != null && o.dash != null && dash.contentEquals(o.dash)))
    }

    private class Builder {
        val ops = ArrayList<Op>()
        var pen: Pen? = null
        var pts = ArrayList<Double>(); var starts = ArrayList<Int>(); var closed = ArrayList<Boolean>()

        fun flush() {
            val p = pen
            if (p != null && starts.isNotEmpty()) ops += StrokeOp(pts.toDoubleArray(), starts.toIntArray(), closed.toBooleanArray(), p.width, p.dash, p.argb)
            pen = null; pts = ArrayList(); starts = ArrayList(); closed = ArrayList()
        }

        fun path(p: Pen, xy: DoubleArray, close: Boolean, tx: (Double, Double) -> Pair<Double, Double>) {
            if (xy.size < 4) return
            if (pen?.same(p) != true) { flush(); pen = p }
            starts += pts.size / 2; closed += close
            var i = 0
            while (i < xy.size) { val (x, y) = tx(xy[i], xy[i + 1]); pts += x; pts += y; i += 2 }
        }

        fun fill(xy: DoubleArray, argb: Int, tx: (Double, Double) -> Pair<Double, Double>) {
            flush()
            val out = DoubleArray(xy.size)
            var i = 0
            while (i < xy.size) { val (x, y) = tx(xy[i], xy[i + 1]); out[i] = x; out[i + 1] = y; i += 2 }
            ops += FillOp(out, argb)
        }
    }

    fun flatten(list: DisplayList): List<Op> {
        val b = Builder()
        emit(list, b, 1.0, 0.0, 0.0)
        b.flush()
        return b.ops
    }

    private fun emit(list: DisplayList, b: Builder, k: Double, ox: Double, oy: Double) {
        val tx = { x: Double, y: Double -> Pair(ox + x * k, oy + y * k) }
        // Lines inside a scaled picture thin with it, but never below what a printer can hold.
        fun w(mm: Double) = if (k == 1.0) mm else maxOf(mm * k, 0.15)
        fun dash(d: DoubleArray?) = if (d == null || k == 1.0) d else DoubleArray(d.size) { d[it] * k }
        for (p: Prim in list.prims) when (p) {
            is Segments -> {
                val pen = Pen(w(Styles.width(p.style)), dash(Styles.dash(p.style)), Styles.color(p.style))
                var i = 0
                while (i + 3 < p.xy.size) { b.path(pen, doubleArrayOf(p.xy[i], p.xy[i + 1], p.xy[i + 2], p.xy[i + 3]), false, tx); i += 4 }
            }
            is Polyline -> b.path(Pen(w(Styles.width(p.style)), dash(Styles.dash(p.style)), Styles.color(p.style)), p.xy, p.closed, tx)
            is Circle -> {
                val n = 64
                val xy = DoubleArray(2 * n) { i -> val a = Math.PI * 2 * (i / 2) / n; if (i % 2 == 0) p.cx + p.r * Math.cos(a) else p.cy + p.r * Math.sin(a) }
                b.path(Pen(w(Styles.width(p.style)), dash(Styles.dash(p.style)), Styles.color(p.style)), xy, true, tx)
            }
            is Fill -> b.fill(p.xy, p.argb, tx)
            is Text -> {
                val wd = StrokeFont.width(p.text, p.height)
                // Alignment is applied along and across the text's own direction, then rotated with it.
                val ax = when (p.hAlign) { HAlign.LEFT -> 0.0; HAlign.CENTER -> -wd / 2; HAlign.RIGHT -> -wd }
                val ay = when (p.vAlign) { VAlign.BOTTOM -> 0.0; VAlign.CENTER -> -p.height / 2; VAlign.TOP -> -p.height }
                val rad = Math.toRadians(p.rotationDeg); val c = Math.cos(rad); val s = Math.sin(rad)
                val bx = p.x + ax * c - ay * s; val by = p.y + ax * s + ay * c
                val pen = Pen(w(textStroke(p.height, p.bold)), null, p.argb)
                for (st in StrokeFont.strokes(p.text, bx, by, p.height, p.rotationDeg)) b.path(pen, st, false, tx)
            }
            is Image -> {
                val c = p.child
                val s = minOf((p.x1 - p.x0) / c.widthMm, (p.y1 - p.y0) / c.heightMm)
                val cx = p.x0 + ((p.x1 - p.x0) - c.widthMm * s) / 2; val cy = p.y0 + ((p.y1 - p.y0) - c.heightMm * s) / 2
                emit(c, b, k * s, ox + cx * k, oy + cy * k)
            }
        }
    }
}
