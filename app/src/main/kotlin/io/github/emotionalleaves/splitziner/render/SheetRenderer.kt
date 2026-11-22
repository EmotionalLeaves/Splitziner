package io.github.emotionalleaves.splitziner.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import splitziner.export.FillOp
import splitziner.export.Op
import splitziner.export.RenderOps
import splitziner.export.StrokeOp
import splitziner.sheet.DisplayList
import java.io.File

/** A sheet turned into paths once, so it can be drawn again and again while the user pans and zooms. */
class PreparedSheet(val widthMm: Float, val heightMm: Float, val name: String, private val items: List<Item>) {
    class Item(val path: Path, val fill: Boolean, val width: Float, val dash: FloatArray?, val color: Int)

    /** Draws at [pxPerMm] with the sheet's top-left corner at the canvas origin. */
    fun draw(canvas: Canvas, pxPerMm: Float, paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)) {
        canvas.save()
        // Paper space has Y up; the canvas has Y down.
        canvas.translate(0f, heightMm * pxPerMm)
        canvas.scale(pxPerMm, -pxPerMm)
        for (it in items) {
            paint.color = it.color
            if (it.fill) { paint.style = Paint.Style.FILL; paint.pathEffect = null }
            else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = it.width
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = if (it.dash == null) Paint.Cap.ROUND else Paint.Cap.BUTT
                paint.pathEffect = it.dash?.let { d -> DashPathEffect(d, 0f) }
            }
            canvas.drawPath(it.path, paint)
        }
        canvas.restore()
    }

    companion object {
        fun of(list: DisplayList): PreparedSheet {
            val items = ArrayList<Item>()
            for (op: Op in RenderOps.flatten(list)) when (op) {
                is StrokeOp -> {
                    val p = Path()
                    for (s in op.starts.indices) {
                        val from = op.starts[s]; val to = if (s + 1 < op.starts.size) op.starts[s + 1] else op.xy.size / 2
                        for (i in from until to) {
                            val x = op.xy[2 * i].toFloat(); val y = op.xy[2 * i + 1].toFloat()
                            if (i == from) p.moveTo(x, y) else p.lineTo(x, y)
                        }
                        if (op.closed[s]) p.close()
                    }
                    items += Item(p, false, op.widthMm.toFloat(), op.dashMm?.let { d -> FloatArray(d.size) { d[it].toFloat() } }, op.argb)
                }
                is FillOp -> {
                    val p = Path()
                    var i = 0
                    while (i < op.xy.size) { if (i == 0) p.moveTo(op.xy[i].toFloat(), op.xy[i + 1].toFloat()) else p.lineTo(op.xy[i].toFloat(), op.xy[i + 1].toFloat()); i += 2 }
                    p.close()
                    items += Item(p, true, 0f, null, op.argb)
                }
            }
            return PreparedSheet(list.widthMm.toFloat(), list.heightMm.toFloat(), list.name, items)
        }
    }
}

object PdfExporter {
    /** Points per millimetre: PDF pages are measured in 1/72 inch. */
    private const val PT_PER_MM = 72f / 25.4f

    /** Writes one page per sheet, as vector paths. Returns the finished file. */
    fun write(context: Context, sheets: List<PreparedSheet>, fileName: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val doc = PdfDocument()
        try {
            sheets.forEachIndexed { i, s ->
                val info = PdfDocument.PageInfo.Builder(Math.round(s.widthMm * PT_PER_MM), Math.round(s.heightMm * PT_PER_MM), i + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawColor(Color.WHITE)
                s.draw(page.canvas, PT_PER_MM)
                doc.finishPage(page)
            }
            // Written under a temporary name first, so a failed export never leaves half a PDF behind.
            val tmp = File(dir, "$fileName.tmp")
            tmp.outputStream().use { doc.writeTo(it) }
            val out = File(dir, fileName)
            out.delete(); tmp.renameTo(out)
            return out
        } finally {
            doc.close()
        }
    }
}

object ImageExporter {
    /** Largest picture made, in pixels. Bigger sheets are rendered at fewer dots per inch to stay under it. */
    private const val MAX_PIXELS = 24_000_000f

    /** Renders one sheet as a PNG at up to 200 dots per inch. Returns the file and the resolution used. */
    fun writePng(context: Context, sheet: PreparedSheet, fileName: String): Pair<File, Int> {
        val inchesW = sheet.widthMm / 25.4f; val inchesH = sheet.heightMm / 25.4f
        var dpi = 200f
        if (inchesW * inchesH * dpi * dpi > MAX_PIXELS) dpi = Math.sqrt((MAX_PIXELS / (inchesW * inchesH)).toDouble()).toFloat()
        val w = Math.round(inchesW * dpi); val h = Math.round(inchesH * dpi)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            sheet.draw(canvas, dpi / 25.4f)
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val out = File(dir, fileName)
            out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return out to Math.round(dpi)
        } finally {
            bmp.recycle()
        }
    }
}
