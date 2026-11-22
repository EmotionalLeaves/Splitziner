package splitziner.sheet

import splitziner.parts.CutListRow

/** A drawing sheet, always landscape. Sizes B to F follow the drawing standard; A is added for home printers. */
class SheetSize(val letter: String, val width: Double, val height: Double, val margin: Double, val zoneCols: Int, val zoneRows: Int) {
    val fieldX0 get() = margin
    val fieldY0 get() = margin
    val fieldX1 get() = width - margin
    val fieldY1 get() = height - margin
    val fieldWidth get() = width - 2 * margin
    val fieldHeight get() = height - 2 * margin

    /** Small sheets get reduced lettering unless the user asks for the standard's full heights. */
    val compact: Boolean get() = letter == "A" || letter == "B"

    companion object {
        private const val IN = 25.4
        val A = SheetSize("A", 11 * IN, 8.5 * IN, 0.375 * IN, 4, 2)
        val B = SheetSize("B", 17 * IN, 11 * IN, 0.375 * IN, 4, 2)
        val C = SheetSize("C", 22 * IN, 17 * IN, 0.5 * IN, 4, 4)
        val D = SheetSize("D", 34 * IN, 22 * IN, 0.5 * IN, 4, 4)
        val E = SheetSize("E", 44 * IN, 34 * IN, 0.5 * IN, 4, 4)
        val F = SheetSize("F", 40 * IN, 28 * IN, 0.5 * IN, 4, 4)

        /** Tried smallest first when the sheet is chosen automatically. F is an odd size, offered only by name. */
        val AUTO = listOf(A, B, C, D, E)
        fun byLetter(l: String): SheetSize? = (AUTO + F).firstOrNull { it.letter == l }
    }
}

/** Lettering heights in mm, from the standard's table (given there in inches). */
class Lettering(val drawingNo: Double, val title: Double, val subtitle: Double, val sectionLetter: Double, val descTitle: Double, val dims: Double, val list: Double) {
    companion object {
        private val FULL = Lettering(6.35, 6.35, 4.83, 6.35, 4.83, 4.06, 3.56)

        /** The standard's heights assume large sheets; on A and B they are reduced so views are not crowded out. */
        private const val COMPACT = 0.75

        fun of(sheet: SheetSize, strict: Boolean): Lettering =
            if (sheet.compact && !strict) FULL.run { Lettering(drawingNo * COMPACT, title * COMPACT, subtitle * COMPACT, sectionLetter * COMPACT, descTitle * COMPACT, dims * COMPACT, list * COMPACT) }
            else FULL
    }
}

data class Revision(val sym: String, val zone: String = "", val description: String = "", val date: String = "", val approval: String = "")

/** What the user types into the title block. Nothing here is invented by the app. */
data class DrawingInfo(
    val title: String = "", val drawingNo: String = "", val rev: String = "-",
    val organisation: String = "", val designer: String = "", val drawn: String = "",
    val checked: String = "", val approved: String = "", val date: String = "",
    val weight: String = "", val notes: List<String> = emptyList(),
    val revisions: List<Revision> = emptyList(),
    /** Null until the user picks or types tolerances; the block then says they are not specified. */
    val toleranceLines: List<String>? = null,
    val softwareVersion: String = "", val sourceFile: String = "",
) {
    companion object {
        /** Letters that are never used for revisions, sections or views: they are too easily misread. */
        const val EXCLUDED_LETTERS = "IOQX"
        fun revisionAllowed(rev: String) = rev.none { it.uppercaseChar() in EXCLUDED_LETTERS }
    }
}

/** Where the title block ended up, so views and lists can be placed around it. */
class TitleArea(val x0: Double, val y0: Double, val x1: Double, val top: Double, val width: Double)

object Furniture {
    const val TITLE_HEIGHT = 47.0
    private val ROWS = doubleArrayOf(9.0, 13.0, 14.0, 11.0)
    private const val CAPTION = 1.6

    fun wrap(text: String, height: Double, width: Double): List<String> {
        val lines = ArrayList<String>(); var cur = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isNotEmpty() && StrokeFont.width(trial.uppercase(), height) > width) { lines += cur; cur = word } else cur = trial
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    /** Border, zone marks, title block, side block and revision block. [sheetNo] starts at 1. */
    fun draw(dl: DisplayList, sheet: SheetSize, L: Lettering, info: DrawingInfo, scaleText: String, units: String, sheetNo: Int, sheetCount: Int): TitleArea {
        val m = sheet.margin; val w = sheet.width; val h = sheet.height
        val fx0 = sheet.fieldX0; val fy0 = sheet.fieldY0; val fx1 = sheet.fieldX1; val fy1 = sheet.fieldY1
        dl.rect(fx0, fy0, fx1, fy1, LineStyle.BORDER)

        // Zones: numbered from the right-hand edge, lettered from the bottom edge.
        val zh = minOf(L.subtitle, m * 0.5)
        for (i in 0 until sheet.zoneCols) {
            val xa = w * i / sheet.zoneCols; val xb = w * (i + 1) / sheet.zoneCols
            val label = "${sheet.zoneCols - i}"
            dl.text((xa + xb) / 2, m / 2, label, zh, HAlign.CENTER, VAlign.CENTER)
            dl.text((xa + xb) / 2, h - m / 2, label, zh, HAlign.CENTER, VAlign.CENTER)
            if (i > 0) { dl.line(xa, 1.0, xa, m); dl.line(xa, h - m, xa, h - 1.0) }
        }
        for (j in 0 until sheet.zoneRows) {
            val ya = h * j / sheet.zoneRows; val yb = h * (j + 1) / sheet.zoneRows
            val label = "${'A' + j}"
            dl.text(m / 2, (ya + yb) / 2, label, zh, HAlign.CENTER, VAlign.CENTER)
            dl.text(w - m / 2, (ya + yb) / 2, label, zh, HAlign.CENTER, VAlign.CENTER)
            if (j > 0) { dl.line(1.0, ya, m, ya); dl.line(w - m, ya, w - 1.0, ya) }
        }

        // Main title block, bottom-right corner of the field.
        val tbw = minOf(178.0, 0.52 * sheet.fieldWidth)
        val x0 = fx1 - tbw; val y0 = fy0
        val first = sheetNo == 1
        val yy = DoubleArray(5); yy[0] = y0
        for (i in 0 until 4) yy[i + 1] = yy[i] + ROWS[i]
        // Continuation sheets carry rows 1 to 3 only.
        val topRow = if (first) 4 else 3
        for (i in 1 until topRow) dl.line(x0, yy[i], fx1, yy[i])
        dl.line(x0, y0, x0, yy[topRow], LineStyle.THICK); dl.line(x0, yy[topRow], fx1, yy[topRow], LineStyle.THICK)

        fun cell(xa: Double, xb: Double, row: Int, caption: String, value: String, height: Double) {
            val top = yy[row + 1]; val bottom = yy[row]
            if (caption.isNotEmpty()) dl.text(xa + 1, top - 0.8, caption, CAPTION, vAlign = VAlign.TOP)
            if (value.isEmpty()) return
            var v = value.uppercase()
            val hgt = minOf(height, top - bottom - CAPTION - 2.2)
            val room = xb - xa - 3
            if (StrokeFont.width(v, hgt) > room) {
                // Letter widths are never squeezed to fit; the text is cut instead and reported.
                while (v.length > 1 && StrokeFont.width(v, hgt) > room) v = v.dropLast(1)
                dl.qa += "\"$value\" does not fit its title block cell and was cut short"
            }
            dl.text(xa + 1.5, bottom + 1.0, v, hgt)
        }
        // Row 1: scale, weight, sheet.
        val c1 = doubleArrayOf(x0, x0 + tbw * 0.30, x0 + tbw * 0.62, fx1)
        dl.line(c1[1], yy[0], c1[1], yy[1]); dl.line(c1[2], yy[0], c1[2], yy[1])
        cell(c1[0], c1[1], 0, "SCALE", scaleText, L.list)
        cell(c1[1], c1[2], 0, "WT", if (first) info.weight else "", L.list)
        cell(c1[2], c1[3], 0, "SHEET", if (first) "SHEET 1 OF $sheetCount" else "SHEET $sheetNo", L.list)
        // Row 2: size, code, drawing number, revision.
        val c2 = doubleArrayOf(x0, x0 + 14, x0 + 40, fx1 - 16, fx1)
        for (i in 1..3) dl.line(c2[i], yy[1], c2[i], yy[2])
        cell(c2[0], c2[1], 1, "SIZE", sheet.letter, L.drawingNo)
        cell(c2[1], c2[2], 1, "CODE", "", L.list)
        cell(c2[2], c2[3], 1, "DWG NO.", info.drawingNo, L.drawingNo)
        cell(c2[3], c2[4], 1, "REV", info.rev, L.drawingNo)
        // Row 3: title. Too long for one line: two lines at the subtitle height.
        dl.text(x0 + 1, yy[3] - 0.8, "TITLE", CAPTION, vAlign = VAlign.TOP)
        val title = info.title.uppercase()
        val room = tbw - 4
        if (title.isNotEmpty()) {
            val one = minOf(L.title, ROWS[2] - CAPTION - 2.2)
            if (StrokeFont.width(title, one) <= room) dl.text(x0 + 1.5, yy[2] + 1.0, title, one)
            else {
                val small = minOf(L.subtitle, (ROWS[2] - CAPTION - 2.6) / 2)
                var lines = wrap(title, small, room)
                if (lines.size > 2) { lines = lines.take(2); dl.qa += "The title is too long for the title block and was cut short" }
                lines.forEachIndexed { i, s -> dl.text(x0 + 1.5, yy[2] + 1.0 + (lines.size - 1 - i) * (small + 0.9), s, small) }
            }
        }
        if (first && info.organisation.isNotEmpty()) dl.text(x0 + 1.5, yy[3] + 2.5, info.organisation, minOf(L.subtitle, ROWS[3] - 4.5))
        dl.register(x0 - 1, y0 - 1, fx1 + 1, yy[topRow], "titleblock")

        var leftEdge = x0
        if (first) {
            // Side block: tolerances on the left, names and projection symbol on the right.
            val lbw = minOf(110.0, sheet.fieldWidth - tbw - 2)
            val lx = x0 - lbw; val top = yy[4]
            dl.line(lx, y0, lx, top, LineStyle.THICK); dl.line(lx, top, x0, top, LineStyle.THICK)
            val mid = lx + lbw * 0.58
            dl.line(mid, y0, mid, top)
            val tol = info.toleranceLines ?: listOf("TOLERANCES: NOT SPECIFIED")
            val lines = listOf("UNLESS OTHERWISE SPECIFIED", "DIMENSIONS ARE IN ${if (units == "in") "INCHES" else "MILLIMETERS"}") + tol
            var ty = top - 2.0
            for (t in lines) for (ln in wrap(t, 1.9, mid - lx - 3)) { if (ty - 1.9 > y0 + 5) dl.text(lx + 1.5, ty, ln, 1.9, vAlign = VAlign.TOP); ty -= 2.8 }
            dl.text(lx + 1.5, y0 + 2, "DO NOT SCALE DRAWING", 1.9)
            val names = listOf("DESIGNER" to info.designer, "DRAWN" to info.drawn, "CHECKED" to info.checked, "APPROVED" to info.approved)
            val rh = (TITLE_HEIGHT - 15) / names.size
            var yr = top
            for ((cap, name) in names) {
                dl.text(mid + 1, yr - 0.8, cap, CAPTION, vAlign = VAlign.TOP)
                if (name.isNotEmpty()) {
                    val nh = minOf(L.list, 2.6, rh - CAPTION - 2.2)
                    val room = x0 - mid - 3
                    // Name and date when both fit; otherwise the name alone, cut if it must be. The date is also in the revision block.
                    var v = "${name.uppercase()}  ${info.date}".trim()
                    if (StrokeFont.width(v, nh) > room) v = name.uppercase()
                    while (v.length > 1 && StrokeFont.width(v, nh) > room) v = v.dropLast(1)
                    dl.text(mid + 1.5, yr - rh + 0.9, v, nh)
                }
                yr -= rh
                dl.line(mid, yr, x0, yr)
            }
            projectionSymbol(dl, (mid + x0) / 2, y0 + 7.5)
            dl.register(lx - 1, y0 - 1, x0, top, "titleblock")
            leftEdge = lx

            // "Produced using" box, where there is room for it.
            if (!sheet.compact && lx - 88.9 > sheet.fieldX0 + 20) {
                val px = lx - 88.9; val ph = 22.4
                dl.line(px, y0, px, y0 + ph, LineStyle.THICK); dl.line(px, y0 + ph, lx, y0 + ph, LineStyle.THICK)
                val rows = listOf("THIS DRAWING WAS PRODUCED USING:", "SOFTWARE: SPLITZINER", "VERSION: ${info.softwareVersion}", "FILE NAME: ${info.sourceFile}")
                rows.forEachIndexed { i, s -> dl.text(px + 1.5, y0 + ph - 2.0 - i * 5.0, s.take(46), 2.2, vAlign = VAlign.TOP) }
                dl.register(px - 1, y0 - 1, lx, y0 + ph, "titleblock")
                leftEdge = px
            }

            // Revision block, top-right corner of the field.
            val revs = info.revisions.ifEmpty {
                listOf(Revision(info.rev, description = if (info.rev in setOf("-", "A", "1")) "INITIAL RELEASE" else "", date = info.date, approval = info.approved))
            }
            val rhh = 6.0; val rx = fx1 - tbw; val ry = fy1
            val bottom = ry - rhh * (revs.size + 2)
            dl.rect(rx, bottom, fx1, ry)
            dl.line(rx, ry - rhh, fx1, ry - rhh)
            dl.text(rx + tbw / 2, ry - rhh / 2, "REVISIONS", 2.6, HAlign.CENTER, VAlign.CENTER)
            val cols = doubleArrayOf(rx, rx + 12, rx + 24, fx1 - 44, fx1 - 22, fx1)
            val heads = listOf("SYM", "ZONE", "DESCRIPTION", "DATE", "APPROVAL")
            for (i in heads.indices) dl.text(cols[i] + 1, ry - rhh * 1.5, heads[i], 1.9, vAlign = VAlign.CENTER)
            revs.forEachIndexed { i, r ->
                dl.line(rx, ry - rhh * (i + 2), fx1, ry - rhh * (i + 2))
                val vals = listOf(r.sym, r.zone, r.description, r.date, r.approval)
                for (k in vals.indices) {
                    var v = vals[k].uppercase()
                    while (v.length > 1 && StrokeFont.width(v, 2.2) > cols[k + 1] - cols[k] - 2) v = v.dropLast(1)
                    if (v.isNotEmpty()) dl.text(cols[k] + 1, ry - rhh * (i + 2.5), v, 2.2, vAlign = VAlign.CENTER)
                }
            }
            for (i in 1..4) dl.line(cols[i], ry - rhh, cols[i], bottom)
            dl.register(rx - 1, bottom - 1, fx1, ry, "revblock")
        }
        return TitleArea(leftEdge, y0, fx1, yy[topRow], tbw)
    }

    /** Third-angle projection symbol: a truncated cone seen from the side, with its end view to the right. */
    private fun projectionSymbol(dl: DisplayList, cx: Double, cy: Double) {
        val tx = cx - 2.0
        dl.polyline(doubleArrayOf(tx - 8, cy - 3.5, tx - 8, cy + 3.5, tx, cy + 2, tx, cy - 2), LineStyle.THIN, true)
        dl.line(tx - 10, cy, tx + 2, cy, LineStyle.CENTER)
        val ex = cx + 8.0
        dl.circle(ex, cy, 3.5); dl.circle(ex, cy, 2.0)
        dl.line(ex - 5, cy, ex + 5, cy, LineStyle.CENTER); dl.line(ex, cy - 5, ex, cy + 5, LineStyle.CENTER)
    }

    fun revisionBlockHeight(info: DrawingInfo): Double = 6.0 * (maxOf(info.revisions.size, 1) + 2)

    fun notesColumnWidth(sheet: SheetSize): Double = if (sheet.compact) 90.0 else 130.0

    /**
     * General notes in the upper-right corner of the field, under the revision block. The standard
     * prefers the upper left and allows this place as an alternate; with the top view above the
     * front view and the side view to its right, the upper right is the corner that is always free.
     * Returns the notes' box as x0, y0, x1, y1 (null when there are none).
     */
    fun notes(dl: DisplayList, sheet: SheetSize, L: Lettering, info: DrawingInfo, notes: List<String>, draw: Boolean = true): DoubleArray? {
        if (notes.isEmpty()) return null
        val colW = notesColumnWidth(sheet)
        val x = sheet.fieldX1 - colW - 4; val top = sheet.fieldY1 - revisionBlockHeight(info) - 5
        val pitch = L.dims * 1.5
        var y = top
        if (draw) dl.text(x, y, "NOTES:", L.descTitle, vAlign = VAlign.TOP)
        y -= L.descTitle + pitch * 0.6
        notes.forEachIndexed { i, n ->
            val prefix = "${i + 1}. "
            val indent = StrokeFont.width(prefix, L.dims) + L.dims * 0.3
            wrap(n, L.dims, colW - indent).forEachIndexed { j, ln ->
                if (draw) { if (j == 0) dl.text(x, y, prefix, L.dims, vAlign = VAlign.TOP); dl.text(x + indent, y, ln, L.dims, vAlign = VAlign.TOP) }
                y -= pitch
            }
        }
        if (draw) dl.register(x - 2, y, x + colW + 2, top + 2, "notes")
        return doubleArrayOf(x - 2, y, x + colW + 2, top + 2)
    }
}

/** The list of material: heading row at the bottom, item 1 just above it, numbers rising upward. */
object ListOfMaterial {
    private const val ROW = 5.5
    private const val CAPTION_ROW = 5.0
    private val HEADS = listOf("ITEM NO.", "REQD", "PART NO.", "DESCRIPTION", "MATERIAL", "MATERIAL SPEC & NO.")

    fun height(rowCount: Int): Double = if (rowCount == 0) 0.0 else (rowCount + 1) * ROW + CAPTION_ROW

    private fun cells(rows: List<CutListRow>, singlePart: Boolean): List<List<String>> = rows.map { r ->
        listOf(if (singlePart) "" else "${r.item}", if (singlePart) "" else "${r.qty}", r.partNo, r.description, r.material, r.materialSpec)
    }

    /** Draws the list with its bottom-right corner at ([xRight], [yBottom]); returns the y of its top edge. */
    fun draw(dl: DisplayList, rows: List<CutListRow>, xRight: Double, yBottom: Double, L: Lettering, singlePart: Boolean): Double {
        if (rows.isEmpty()) return yBottom
        val th = minOf(L.list, ROW - 2.0)
        val data = cells(rows, singlePart)
        val widths = HEADS.indices.map { c -> maxOf(StrokeFont.width(HEADS[c], th * 0.8), data.maxOf { StrokeFont.width(it[c].uppercase(), th) }) + 4 }
        val total = widths.sum()
        val x0 = xRight - total
        var y = yBottom
        dl.text(x0 + total / 2, y + CAPTION_ROW / 2, "LIST OF MATERIAL", th, HAlign.CENTER, VAlign.CENTER)
        y += CAPTION_ROW
        for ((ri, row) in (listOf(HEADS) + data).withIndex()) {
            var x = x0
            for (c in HEADS.indices) {
                dl.rect(x, y, x + widths[c], y + ROW)
                val v = row[c]
                // An empty cell shows a dash, as the standard's examples do.
                if (v.isEmpty()) dl.text(x + widths[c] / 2, y + ROW / 2, "—", th, HAlign.CENTER, VAlign.CENTER)
                else dl.text(x + 2, y + ROW / 2, v, if (ri == 0) th * 0.8 else th, vAlign = VAlign.CENTER)
                x += widths[c]
            }
            y += ROW
        }
        dl.line(x0, yBottom, x0, y, LineStyle.THICK); dl.line(x0, y, xRight, y, LineStyle.THICK)
        dl.register(x0 - 1, yBottom, xRight + 1, y + 1, "partslist")
        return y
    }
}
