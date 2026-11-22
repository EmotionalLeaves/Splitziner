package splitziner.export

import splitziner.sheet.Circle
import splitziner.sheet.DisplayList
import splitziner.sheet.Fill
import splitziner.sheet.HAlign
import splitziner.sheet.Image
import splitziner.sheet.LineStyle
import splitziner.sheet.Polyline
import splitziner.sheet.Segments
import splitziner.sheet.StrokeFont
import splitziner.sheet.Styles
import splitziner.sheet.Text
import splitziner.sheet.VAlign
import java.util.Locale

/**
 * Writes a sheet as ASCII DXF (R12 structure), one layer per line style, in paper millimetres.
 * It is a record of the drawing for CAD programs, not a cutting profile.
 */
object DxfWriter {
    private fun n(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    fun layerOf(s: LineStyle): String = if (s == LineStyle.THICK) "VISIBLE" else s.name

    fun write(list: DisplayList): String {
        val sb = StringBuilder()
        fun g(code: Int, value: String) { sb.append(code).append('\n').append(value).append('\n') }
        g(0, "SECTION"); g(2, "HEADER")
        g(9, "\$ACADVER"); g(1, "AC1009")
        g(9, "\$EXTMIN"); g(10, n(0.0)); g(20, n(0.0))
        g(9, "\$EXTMAX"); g(10, n(list.widthMm)); g(20, n(list.heightMm))
        g(0, "ENDSEC")

        g(0, "SECTION"); g(2, "TABLES")
        val dashed = LineStyle.entries.filter { Styles.dash(it) != null }
        g(0, "TABLE"); g(2, "LTYPE"); g(70, "${dashed.size + 1}")
        g(0, "LTYPE"); g(2, "CONTINUOUS"); g(70, "0"); g(3, "Solid line"); g(72, "65"); g(73, "0"); g(40, "0.0")
        for (s in dashed) {
            val d = Styles.dash(s)!!
            g(0, "LTYPE"); g(2, s.name); g(70, "0"); g(3, s.name.lowercase()); g(72, "65"); g(73, "${d.size}"); g(40, n(d.sum()))
            // Dashes are positive lengths, gaps negative.
            d.forEachIndexed { i, v -> g(49, n(if (i % 2 == 0) v else -v)) }
        }
        g(0, "ENDTAB")
        val layers = LineStyle.entries.map { layerOf(it) to (if (Styles.dash(it) != null) it.name else "CONTINUOUS") } + listOf("TEXT" to "CONTINUOUS", "FILL" to "CONTINUOUS")
        g(0, "TABLE"); g(2, "LAYER"); g(70, "${layers.size}")
        for ((name, lt) in layers) { g(0, "LAYER"); g(2, name); g(70, "0"); g(62, "7"); g(6, lt) }
        g(0, "ENDTAB"); g(0, "ENDSEC")

        g(0, "SECTION"); g(2, "ENTITIES")
        fun line(layer: String, x0: Double, y0: Double, x1: Double, y1: Double) { g(0, "LINE"); g(8, layer); g(10, n(x0)); g(20, n(y0)); g(11, n(x1)); g(21, n(y1)) }
        fun emit(dl: DisplayList, k: Double, ox: Double, oy: Double) {
            fun x(v: Double) = ox + v * k
            fun y(v: Double) = oy + v * k
            for (p in dl.prims) when (p) {
                is Segments -> { var i = 0; while (i + 3 < p.xy.size) { line(layerOf(p.style), x(p.xy[i]), y(p.xy[i + 1]), x(p.xy[i + 2]), y(p.xy[i + 3])); i += 4 } }
                is Polyline -> {
                    val c = p.xy.size / 2
                    for (i in 0 until c - 1) line(layerOf(p.style), x(p.xy[2 * i]), y(p.xy[2 * i + 1]), x(p.xy[2 * i + 2]), y(p.xy[2 * i + 3]))
                    if (p.closed && c > 2) line(layerOf(p.style), x(p.xy[2 * c - 2]), y(p.xy[2 * c - 1]), x(p.xy[0]), y(p.xy[1]))
                }
                is Circle -> { g(0, "CIRCLE"); g(8, layerOf(p.style)); g(10, n(x(p.cx))); g(20, n(y(p.cy))); g(40, n(p.r * k)) }
                is Fill -> {
                    g(0, "POLYLINE"); g(8, "FILL"); g(66, "1"); g(70, "1")
                    var i = 0
                    while (i < p.xy.size) { g(0, "VERTEX"); g(8, "FILL"); g(10, n(x(p.xy[i]))); g(20, n(y(p.xy[i + 1]))); i += 2 }
                    g(0, "SEQEND")
                }
                is Text -> {
                    // Lettering goes out as the same strokes the sheet shows, so it looks identical in any CAD program.
                    val wd = StrokeFont.width(p.text, p.height)
                    val ax = when (p.hAlign) { HAlign.LEFT -> 0.0; HAlign.CENTER -> -wd / 2; HAlign.RIGHT -> -wd }
                    val ay = when (p.vAlign) { VAlign.BOTTOM -> 0.0; VAlign.CENTER -> -p.height / 2; VAlign.TOP -> -p.height }
                    val rad = Math.toRadians(p.rotationDeg); val c = Math.cos(rad); val s = Math.sin(rad)
                    for (st in StrokeFont.strokes(p.text, p.x + ax * c - ay * s, p.y + ax * s + ay * c, p.height, p.rotationDeg)) {
                        var i = 0
                        while (i + 3 < st.size) { line("TEXT", x(st[i]), y(st[i + 1]), x(st[i + 2]), y(st[i + 3])); i += 2 }
                    }
                }
                is Image -> {
                    val ch = p.child
                    val s = minOf((p.x1 - p.x0) / ch.widthMm, (p.y1 - p.y0) / ch.heightMm)
                    emit(ch, k * s, x(p.x0 + ((p.x1 - p.x0) - ch.widthMm * s) / 2), y(p.y0 + ((p.y1 - p.y0) - ch.heightMm * s) / 2))
                }
            }
        }
        emit(list, 1.0, 0.0, 0.0)
        g(0, "ENDSEC"); g(0, "EOF")
        return sb.toString()
    }
}
