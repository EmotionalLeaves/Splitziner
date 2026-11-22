package splitziner.sheet

/**
 * Single-stroke lettering drawn as line segments, in the manner of hand drafting.
 *
 * Every backend (SVG, PDF, screen, DXF) draws the same strokes, so text looks identical everywhere
 * and its width is known without any platform font. Glyphs sit on a grid 4 wide and 6 tall
 * (capital height 6, baseline 0). The alphabet is this project's own; it has no outside licence.
 */
object StrokeFont {
    private const val CAP = 6.0

    /** Gap between letters, in grid units. */
    private const val GAP = 1.6

    /** Lowercase letters are drawn as capitals at this fraction of the height (small caps). */
    private const val SMALL_CAPS = 0.76

    private class Glyph(val width: Double, val strokes: List<DoubleArray>)

    private fun g(width: Double, s: String) = Glyph(width, if (s.isEmpty()) emptyList() else s.split('|').map { line ->
        line.trim().split(' ').flatMap { p -> p.split(',').map { it.toDouble() } }.toDoubleArray()
    })

    private val GLYPHS: Map<Char, Glyph> = mapOf(
        ' ' to g(2.6, ""),
        'A' to g(4.0, "0,0 2,6 4,0|0.8,2.4 3.2,2.4"),
        'B' to g(4.0, "0,0 0,6 3,6 4,5 4,4 3,3 0,3|3,3 4,2 4,1 3,0 0,0"),
        'C' to g(4.0, "4,5 3,6 1,6 0,5 0,1 1,0 3,0 4,1"),
        'D' to g(4.0, "0,0 0,6 2.5,6 4,4.5 4,1.5 2.5,0 0,0"),
        'E' to g(3.8, "3.8,0 0,0 0,6 3.8,6|0,3 3,3"),
        'F' to g(3.8, "0,0 0,6 3.8,6|0,3 3,3"),
        'G' to g(4.0, "4,5 3,6 1,6 0,5 0,1 1,0 3,0 4,1 4,3 2.2,3"),
        'H' to g(4.0, "0,0 0,6|4,0 4,6|0,3 4,3"),
        'I' to g(2.0, "1,0 1,6|0,0 2,0|0,6 2,6"),
        'J' to g(3.2, "0,1 1,0 2.2,0 3.2,1 3.2,6"),
        'K' to g(4.0, "0,0 0,6|4,6 0,2.5|1.4,3.7 4,0"),
        'L' to g(3.6, "0,6 0,0 3.6,0"),
        'M' to g(4.6, "0,0 0,6 2.3,2.6 4.6,6 4.6,0"),
        'N' to g(4.0, "0,0 0,6 4,0 4,6"),
        'O' to g(4.0, "1,0 0,1 0,5 1,6 3,6 4,5 4,1 3,0 1,0"),
        'P' to g(4.0, "0,0 0,6 3,6 4,5 4,4 3,3 0,3"),
        'Q' to g(4.0, "1,0 0,1 0,5 1,6 3,6 4,5 4,1 3,0 1,0|2.5,1.5 4.2,-0.2"),
        'R' to g(4.0, "0,0 0,6 3,6 4,5 4,4 3,3 0,3|2,3 4,0"),
        'S' to g(4.0, "4,5 3,6 1,6 0,5 0,4 1,3 3,3 4,2 4,1 3,0 1,0 0,1"),
        'T' to g(4.0, "2,0 2,6|0,6 4,6"),
        'U' to g(4.0, "0,6 0,1 1,0 3,0 4,1 4,6"),
        'V' to g(4.0, "0,6 2,0 4,6"),
        'W' to g(5.0, "0,6 1.2,0 2.5,4.2 3.8,0 5,6"),
        'X' to g(4.0, "0,0 4,6|0,6 4,0"),
        'Y' to g(4.0, "0,6 2,3 4,6|2,3 2,0"),
        'Z' to g(4.0, "0,6 4,6 0,0 4,0"),
        '0' to g(4.0, "1,0 0,1 0,5 1,6 3,6 4,5 4,1 3,0 1,0"),
        '1' to g(2.6, "0.3,4.8 1.5,6 1.5,0|0.3,0 2.6,0"),
        '2' to g(4.0, "0,5 1,6 3,6 4,5 4,3.8 0,0 4,0"),
        '3' to g(4.0, "0,5 1,6 3,6 4,5 4,4 3,3 1.5,3|3,3 4,2 4,1 3,0 1,0 0,1"),
        '4' to g(4.0, "3,0 3,6 0,2 4,2"),
        '5' to g(4.0, "4,6 0.3,6 0,3.4 1,3.8 3,3.8 4,2.8 4,1 3,0 1,0 0,1"),
        '6' to g(4.0, "3.8,5.4 3,6 1,6 0,5 0,1 1,0 3,0 4,1 4,2.6 3,3.6 1,3.6 0,2.6"),
        '7' to g(4.0, "0,6 4,6 1.5,0"),
        '8' to g(4.0, "1,3.1 0,4 0,5 1,6 3,6 4,5 4,4 3,3.1 1,3.1 0,2.2 0,1 1,0 3,0 4,1 4,2.2 3,3.1"),
        '9' to g(4.0, "0.2,0.6 1,0 3,0 4,1 4,5 3,6 1,6 0,5 0,3.4 1,2.4 3,2.4 4,3.4"),
        '.' to g(0.8, "0.2,0 0.6,0 0.6,0.4 0.2,0.4 0.2,0"),
        ',' to g(0.8, "0.5,0.4 0.5,0 0.1,-0.9"),
        ':' to g(0.8, "0.2,0 0.6,0 0.6,0.4 0.2,0.4 0.2,0|0.2,3 0.6,3 0.6,3.4 0.2,3.4 0.2,3"),
        ';' to g(0.8, "0.5,0.4 0.5,0 0.1,-0.9|0.2,3 0.6,3 0.6,3.4 0.2,3.4 0.2,3"),
        '-' to g(3.0, "0,3 3,3"),
        '+' to g(3.2, "0,3 3.2,3|1.6,1.4 1.6,4.6"),
        '/' to g(3.4, "0,-0.3 3.4,6.3"),
        '(' to g(1.4, "1.4,6.6 0.4,5 0,3 0.4,1 1.4,-0.6"),
        ')' to g(1.4, "0,6.6 1,5 1.4,3 1,1 0,-0.6"),
        '&' to g(4.2, "4.2,0 1,4.4 1,5.2 1.6,6 2.4,6 3,5.2 3,4.5 0,2 0,1 1,0 2.4,0 4.2,2.4"),
        '\'' to g(0.4, "0.2,6 0.2,4.4"),
        '"' to g(1.6, "0.2,6 0.2,4.4|1.4,6 1.4,4.4"),
        '#' to g(4.2, "1,0 1.6,6|2.6,0 3.2,6|0,2 4,2|0.2,4 4.2,4"),
        '%' to g(4.0, "0,0 4,6|0.2,5.2 0.8,6 1.4,5.2 0.8,4.4 0.2,5.2|2.6,0.8 3.2,1.6 3.8,0.8 3.2,0 2.6,0.8"),
        '=' to g(3.2, "0,2 3.2,2|0,4 3.2,4"),
        '<' to g(3.0, "3,5 0,3 3,1"),
        '>' to g(3.0, "0,5 3,3 0,1"),
        '?' to g(4.0, "0,5 1,6 3,6 4,5 4,4 2,2.6 2,1.8|2,0.4 2,0"),
        '!' to g(0.6, "0.3,6 0.3,1.8|0.3,0.4 0.3,0"),
        '_' to g(4.0, "0,-0.6 4,-0.6"),
        '×' to g(3.0, "0.2,1.2 2.8,4.8|0.2,4.8 2.8,1.2"),                                  // multiplication sign
        '⌀' to g(4.0, "1,1 0.5,2 0.5,4 1,5 3,5 3.5,4 3.5,2 3,1 1,1|0.3,0 3.7,6"),         // diameter
        '±' to g(3.2, "0,3.6 3.2,3.6|1.6,2 1.6,5.2|0,0.8 3.2,0.8"),                         // plus-minus
        '°' to g(1.8, "0.5,4.4 0,5.2 0.5,6 1.3,6 1.8,5.2 1.3,4.4 0.5,4.4"),                 // degree
        '—' to g(4.6, "0,3 4.6,3"),                                                         // long dash
        '•' to g(1.6, "0.3,3 0.8,3.6 1.3,3 0.8,2.4 0.3,3"),                                 // bullet
    )

    private val UNKNOWN = GLYPHS.getValue('?')

    private inline fun walk(text: String, visit: (Glyph, Double) -> Unit): Double {
        var x = 0.0
        var first = true
        for (ch in text) {
            val small = ch.isLowerCase()
            val glyph = GLYPHS[if (small) ch.uppercaseChar() else ch] ?: UNKNOWN
            val k = if (small) SMALL_CAPS else 1.0
            if (!first) x += GAP
            first = false
            visit(glyph, x)
            // The visitor is told where the glyph starts; its scale is recomputed from the character.
            x += glyph.width * k
        }
        return x
    }

    /** Width in millimetres of [text] drawn with capital height [height]. */
    fun width(text: String, height: Double): Double = walk(text) { _, _ -> } * height / CAP

    /** True if every character has a glyph (lowercase counts; it is drawn as small capitals). */
    fun supports(text: String): Boolean = text.all { GLYPHS.containsKey(it) || GLYPHS.containsKey(it.uppercaseChar()) }

    /**
     * Polylines (x0,y0,x1,y1,...) for [text] with its baseline-left corner at ([x], [y]),
     * rotated [rotationDeg] counter-clockwise about that corner.
     */
    fun strokes(text: String, x: Double, y: Double, height: Double, rotationDeg: Double = 0.0): List<DoubleArray> {
        val s = height / CAP
        val rad = Math.toRadians(rotationDeg); val c = Math.cos(rad); val sn = Math.sin(rad)
        val out = ArrayList<DoubleArray>()
        var i = 0
        walk(text) { glyph, gx ->
            val k = if (text[i].isLowerCase()) SMALL_CAPS else 1.0
            for (st in glyph.strokes) {
                val line = DoubleArray(st.size)
                var j = 0
                while (j < st.size) {
                    val lx = (gx + st[j] * k) * s; val ly = st[j + 1] * k * s
                    line[j] = x + lx * c - ly * sn; line[j + 1] = y + lx * sn + ly * c
                    j += 2
                }
                out += line
            }
            i++
        }
        return out
    }
}
