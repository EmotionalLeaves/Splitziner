package splitziner.export

import splitziner.sheet.DisplayList
import java.util.Locale

/** Writes a display list as SVG. Output is deterministic, so it can be compared byte for byte in tests. */
object SvgWriter {
    private fun n(v: Double): String = String.format(Locale.ROOT, "%.3f", v)
    private fun color(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    fun write(list: DisplayList): String {
        val w = list.widthMm; val h = list.heightMm
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${n(w)}mm\" height=\"${n(h)}mm\" viewBox=\"0 0 ${n(w)} ${n(h)}\">\n")
        sb.append("<rect width=\"${n(w)}\" height=\"${n(h)}\" fill=\"#FFFFFF\"/>\n")
        // Paper space has Y up; SVG has Y down.
        sb.append("<g transform=\"translate(0,${n(h)}) scale(1,-1)\">\n")
        for (op in RenderOps.flatten(list)) when (op) {
            is StrokeOp -> {
                sb.append("<path fill=\"none\" stroke=\"${color(op.argb)}\" stroke-width=\"${n(op.widthMm)}\"")
                if (op.dashMm != null) sb.append(" stroke-dasharray=\"${op.dashMm.joinToString(",") { n(it) }}\" stroke-linecap=\"butt\"")
                else sb.append(" stroke-linecap=\"round\"")
                sb.append(" stroke-linejoin=\"round\" d=\"")
                for (s in op.starts.indices) {
                    val from = op.starts[s]; val to = if (s + 1 < op.starts.size) op.starts[s + 1] else op.xy.size / 2
                    for (i in from until to) sb.append(if (i == from) "M" else "L").append(n(op.xy[2 * i])).append(' ').append(n(op.xy[2 * i + 1]))
                    if (op.closed[s]) sb.append('Z')
                }
                sb.append("\"/>\n")
            }
            is FillOp -> {
                sb.append("<path fill=\"${color(op.argb)}\" stroke=\"none\" d=\"")
                var i = 0
                while (i < op.xy.size) { sb.append(if (i == 0) "M" else "L").append(n(op.xy[i])).append(' ').append(n(op.xy[i + 1])); i += 2 }
                sb.append("Z\"/>\n")
            }
        }
        sb.append("</g>\n</svg>\n")
        return sb.toString()
    }
}
