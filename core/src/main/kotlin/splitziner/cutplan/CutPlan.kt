package splitziner.cutplan

import splitziner.dims.LengthFormat
import splitziner.parts.CutListRow
import splitziner.parts.PartKind
import splitziner.sheet.DisplayList
import splitziner.sheet.HAlign
import splitziner.sheet.LineStyle
import splitziner.sheet.StrokeFont
import splitziner.sheet.VAlign
import kotlin.math.abs

/** The stock the plan cuts from. These are the user's to set; the defaults are common sizes, not knowledge of the project. */
data class StockSettings(
    /** Sheet goods, mm. */
    val sheetW: Double = 1220.0, val sheetL: Double = 2440.0,
    /** Length boards and rods are bought in, mm. */
    val boardL: Double = 2440.0,
    /** Width of the saw cut, mm. */
    val kerf: Double = 3.0,
    /** False keeps every panel's length along the sheet's length, for grain or pattern. */
    val rotate: Boolean = true,
    /** Boards come from standard North American lumber (1x4, 2x6...) and other boards are cut from sheet. */
    val lumber: Boolean = false,
) {
    companion object {
        /** A 4 x 8 ft sheet, 8 ft lengths and a 1/8 in saw cut. */
        val INCH = StockSettings(1219.2, 2438.4, 2438.4, 3.175, lumber = true)
        fun defaultFor(fmt: LengthFormat) = if (fmt.isInch) INCH else StockSettings()
    }
}

/** One piece on a sheet. x runs across the sheet's width, y along its length; w and l are the piece's sizes in those directions. */
class PlacedPiece(val item: Int, val x: Double, val y: Double, val w: Double, val l: Double, val rotated: Boolean)

/** [w] x [l] is the panel bought: a full sheet, or a half or quarter one when everything fits on that ([size] says which). */
class SheetLayout(val thickness: Double, val material: String, val pieces: List<PlacedPiece>, val used: Double,
    val w: Double = 0.0, val l: Double = 0.0, val size: String = "sheet")

/** One bought length and the pieces cut from it, in order. */
/** [stockName] is what to ask for ("1x4"), when the pieces come from standard lumber; [ripTo] is set when they are ripped narrower from it. */
class BoardLayout(val kind: PartKind, val t: Double, val w: Double, val material: String, val cuts: List<Pair<Int, Double>>, val offcut: Double,
    val stockName: String? = null, val stockW: Double = w, val ripTo: Double? = null)

/**
 * Standard North American lumber: what it is called and what it really measures. A 1x4 is
 * 3/4 x 3-1/2 in; a 2x4 is 1-1/2 x 3-1/2 in.
 */
object Lumber {
    class Size(val name: String, val t: Double, val w: Double)
    private const val IN = 25.4
    private val WIDTHS = listOf(2 to 1.5, 3 to 2.5, 4 to 3.5, 6 to 5.5, 8 to 7.25, 10 to 9.25, 12 to 11.25)
    // 5/4 ("five-quarter") boards are a full inch thick.
    val SIZES: List<Size> = listOf("1" to 0.75, "5/4" to 1.0, "2" to 1.5).flatMap { (nt, at) -> WIDTHS.map { (nw, aw) -> Size("${nt}x$nw", at * IN, aw * IN) } } +
        // Posts, for legs.
        listOf(Size("4x4", 3.5 * IN, 3.5 * IN), Size("4x6", 3.5 * IN, 5.5 * IN))

    /** The narrowest standard board of this thickness at least [w] wide, or null if the thickness is not a lumber thickness. */
    fun fit(t: Double, w: Double): Size? = SIZES.filter { abs(it.t - t) < maxOf(0.8, 0.03 * it.t) && it.w >= w - 0.8 }.minByOrNull { it.w }
}

class CutPlanResult(val stock: StockSettings, val sheets: List<SheetLayout>, val boards: List<BoardLayout>, val leftOut: List<String>)

/**
 * Lays panels out on sheets and boards along bought lengths. Every sheet layout can be cut with
 * straight cuts that run right across the piece being cut (as a hand-held or table saw needs).
 * It is a good layout, not a proven best one.
 */
object CutPlanner {
    private class Free(val x: Double, val y: Double, val w: Double, val l: Double)
    private class Open(val pieces: ArrayList<PlacedPiece> = ArrayList(), val free: ArrayList<Free> = ArrayList())
    private class Want(val item: Int, val w: Double, val l: Double)

    /** A shaped part this thin compared with its width is cut from sheet; thicker ones are left to the maker. */
    private const val FLAT_SHARE = 0.3

    /** Guillotine packing of [wants] (biggest first) onto as many [sw] x [sl] panels as needed. */
    private fun pack(wants: List<Want>, sw: Double, sl: Double, stock: StockSettings): List<Open> {
        val open = ArrayList<Open>()
        for (p in wants) {
            var best: Triple<Open, Free, Boolean>? = null; var bestScore = Double.MAX_VALUE
            fun consider(o: Open) {
                for (f in o.free) for (rot in if (stock.rotate) listOf(false, true) else listOf(false)) {
                    // Unrotated, the piece's length runs along the sheet's length.
                    val pw = if (rot) p.l else p.w; val pl = if (rot) p.w else p.l
                    if (pw > f.w + 1e-6 || pl > f.l + 1e-6) continue
                    val score = minOf(f.w - pw, f.l - pl)        // tightest fit on one side wastes least
                    if (score < bestScore - 1e-9) { bestScore = score; best = Triple(o, f, rot) }
                }
            }
            for (o in open) consider(o)
            if (best == null) { if (!(p.w <= sw + 1e-6 && p.l <= sl + 1e-6) && !(stock.rotate && p.l <= sw + 1e-6 && p.w <= sl + 1e-6)) return emptyList(); val o = Open(); o.free += Free(0.0, 0.0, sw, sl); open += o; consider(o) }
            val (o, f, rot) = best ?: continue
            val pw = if (rot) p.l else p.w; val pl = if (rot) p.w else p.l
            o.pieces += PlacedPiece(p.item, f.x, f.y, pw, pl, rot)
            o.free.remove(f)
            // The saw cut is lost beside the piece, except where the piece reaches the edge of the space.
            val cw = minOf(pw + stock.kerf, f.w); val cl = minOf(pl + stock.kerf, f.l)
            val restW = f.w - cw; val restL = f.l - cl
            // One straight cut frees the larger offcut whole; the other is what remains beside the piece.
            if (restW * f.l >= restL * f.w) {
                if (restW > 1e-6) o.free += Free(f.x + cw, f.y, restW, f.l)
                if (restL > 1e-6) o.free += Free(f.x, f.y + cl, cw, restL)
            } else {
                if (restL > 1e-6) o.free += Free(f.x, f.y + cl, f.w, restL)
                if (restW > 1e-6) o.free += Free(f.x + cw, f.y, restW, cl)
            }
        }
        return open
    }

    fun plan(rows: List<CutListRow>, stock: StockSettings): CutPlanResult {
        val leftOut = ArrayList<String>()
        val sheets = ArrayList<SheetLayout>(); val boards = ArrayList<BoardLayout>()
        fun key(v: Double) = Math.round(v * 100)

        // In inches, boards come from standard lumber when their thickness is a lumber thickness (3/4 or 1-1/2 in).
        // Boards of other thicknesses (a 1/2 in or 1/4 in strip) are cut as strips from sheet goods, as they are in practice.
        val lumberish = { r: CutListRow -> stock.lumber && r.kind == PartKind.BOARD && Lumber.fit(r.t, r.w) != null }
        val flat = rows.filter { it.kind == PartKind.PANEL || (it.kind == PartKind.SHAPED && it.t < FLAT_SHARE * it.w) || (stock.lumber && it.kind == PartKind.BOARD && !lumberish(it)) }
        for (r in rows) if (r.kind == PartKind.SHAPED && r !in flat) leftOut += "Item ${r.item} is a shaped block; it is not in the plan."
        // A thickness within a hair of a stock one (1.02 in drawn for 1 in) is that stock thickness.
        val stockT = splitziner.check.DesignCheck.stockThicknesses(if (stock.lumber) LengthFormat("in") else LengthFormat("mm"))
        fun snapT(t: Double) = stockT.firstOrNull { abs(it - t) <= maxOf(0.4, 0.03 * it) } ?: t
        // Panels thicker than any sheet sold are glued up from boards (or bought as a butcher-block blank), not cut from sheet.
        val tooThick = flat.filter { snapT(it.t) > stockT.max() + 0.5 }
        for (r in tooThick) leftOut += "Item ${r.item} is thicker than any sheet sold: glue it up from boards, or buy it as a butcher-block panel."
        for ((group, members) in (flat - tooThick.toSet()).groupBy { key(snapT(it.t)) to it.material }.entries.sortedBy { it.key.first }) {
            val wants = ArrayList<Want>()
            for (r in members) {
                val fits = (r.w <= stock.sheetW + 1e-6 && r.l <= stock.sheetL + 1e-6) || (stock.rotate && r.l <= stock.sheetW + 1e-6 && r.w <= stock.sheetL + 1e-6)
                if (!fits) { leftOut += "Item ${r.item} is larger than a sheet; it is not in the plan."; continue }
                repeat(r.qty) { wants += Want(r.item, r.w, r.l) }
            }
            // Biggest first: large pieces are the hard ones to place, small ones fill what is left.
            wants.sortWith(compareByDescending<Want> { it.w * it.l }.thenByDescending { it.l })
            for (o in pack(wants, stock.sheetW, stock.sheetL, stock)) {
                // A sheet with little on it is bought smaller where everything fits: stores sell half and quarter panels.
                val wantsHere = o.pieces.map { Want(it.item, if (it.rotated) it.l else it.w, if (it.rotated) it.w else it.l) }
                    .sortedWith(compareByDescending<Want> { it.w * it.l }.thenByDescending { it.l })
                val smaller = listOf(Triple(stock.sheetW / 2, stock.sheetL / 2, "quarter sheet"), Triple(stock.sheetW, stock.sheetL / 2, "half sheet"))
                    .firstNotNullOfOrNull { (sw, sl, name) -> pack(wantsHere, sw, sl, stock).singleOrNull()?.let { Triple(it, sw to sl, name) } }
                val (fin, dims, name) = smaller ?: Triple(o, stock.sheetW to stock.sheetL, "sheet")
                sheets += SheetLayout(group.first / 100.0, group.second, fin.pieces, fin.pieces.sumOf { it.w * it.l } / (dims.first * dims.second), dims.first, dims.second, name)
            }
        }

        val long = rows.filter { (it.kind == PartKind.BOARD && (!stock.lumber || lumberish(it))) || it.kind == PartKind.ROD }
        // Pieces that come from the same standard board are cut from the same lengths, ripped narrower where needed.
        fun stockKey(r: CutListRow): List<Any> { val lb = if (r.kind == PartKind.BOARD && stock.lumber) Lumber.fit(r.t, r.w) else null; return if (lb != null) listOf(r.kind, lb.name, r.material) else listOf(r.kind, key(r.t), key(r.w), r.material) }
        for ((_, members) in long.groupBy { stockKey(it) }.entries.sortedBy { (key(it.value[0].t)) * 100000 + key(it.value[0].w) }) {
            val first = members[0]
            val lb = if (first.kind == PartKind.BOARD && stock.lumber) Lumber.fit(first.t, first.w) else null
            val wants = ArrayList<Pair<Int, Double>>()
            for (r in members) {
                if (r.l > stock.boardL + 1e-6) { leftOut += "Item ${r.item} is longer than the stock length; it is not in the plan."; continue }
                repeat(r.qty) { wants += r.item to r.l }
            }
            wants.sortByDescending { it.second }
            val bins = ArrayList<ArrayList<Pair<Int, Double>>>(); val left = ArrayList<Double>()
            for (c in wants) {
                // First length with room: the classic "longest first" rule, within about a fifth of the fewest possible.
                var i = left.indexOfFirst { it >= c.second - 1e-6 }
                if (i < 0) { bins += ArrayList<Pair<Int, Double>>(); left += stock.boardL; i = bins.size - 1 }
                bins[i] += c; left[i] = left[i] - c.second - stock.kerf
            }
            val widest = members.maxOf { it.w }
            bins.forEachIndexed { i, cuts ->
                boards += if (lb != null) BoardLayout(first.kind, lb.t, lb.w, first.material, cuts, maxOf(0.0, left[i]), lb.name, lb.w, if (members.any { lb.w - it.w > 0.8 }) widest else null)
                else BoardLayout(first.kind, first.t, first.w, first.material, cuts, maxOf(0.0, left[i]))
            }
        }
        return CutPlanResult(stock, sheets, boards, leftOut)
    }

    /** What to buy, one line per kind of stock. */
    fun shoppingList(r: CutPlanResult, fmt: LengthFormat): List<String> {
        val out = ArrayList<String>()
        fun mat(m: String) = if (m.isBlank()) "" else " ($m)"
        // Sheet thicknesses as sold: 3/8 in, not .37.
        val tf = if (fmt.isInch) fmt.copy(inchStyle = "fraction", fractionDenominator = 32) else fmt
        for ((k, v) in r.sheets.groupBy { Triple(it.thickness, it.material, it.size) }) {
            val (w, l) = v[0].let { if (it.w > 0) it.w to it.l else r.stock.sheetW to r.stock.sheetL }
            out += "${v.size} ${k.third}${if (v.size == 1) "" else "s"} ${tf.format(k.first)}${if (fmt.isInch) " in" else " mm"} thick, ${fmt.format(w)} x ${fmt.format(l)}${mat(k.second)}"
        }
        for ((k, v) in r.boards.groupBy { listOf(it.kind, it.stockName ?: "${it.t}x${it.w}", it.material) }) {
            val b = v[0]
            val section = if (b.kind == PartKind.ROD) "rod ${fmt.format((b.t + b.w) / 2)} dia"
                else if (b.stockName != null) "${b.stockName} (actual ${fmt.format(b.t)} x ${fmt.format(b.w)})" + if (b.ripTo != null) ", some pieces ripped narrower" else ""
                else "${fmt.format(b.t)} x ${fmt.format(b.w)}"
            out += "${v.size} length${if (v.size == 1) "" else "s"} of $section, ${fmt.format(r.stock.boardL)} long${mat(k[2] as String)}"
        }
        return out
    }
}

/** Draws the plan on pages: what to buy, then each sheet to scale, then each length as a bar. */
object CutPlanLayout {
    const val NOTICE = "This is a suggested layout. Check it against the stock you really have, and decide grain direction yourself, before cutting."

    private fun wrap(text: String, h: Double, width: Double): List<String> {
        val lines = ArrayList<String>(); var cur = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isNotEmpty() && StrokeFont.width(trial, h) > width) { lines += cur; cur = word } else cur = trial
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    fun pages(r: CutPlanResult, rows: List<CutListRow>, title: String, fmt: LengthFormat, a4: Boolean = !fmt.isInch, extraBuy: List<String> = emptyList()): List<DisplayList> {
        val w = if (a4) 210.0 else 215.9; val h = if (a4) 297.0 else 279.4
        val list = ArrayList<DisplayList>()
        fun page(): DisplayList {
            val p = DisplayList(w, h, "cutplan_page%02d".format(list.size + 1))
            p.upperCase = false
            p.text(12.0, h - 12, "${title.uppercase()} - CUTTING PLAN", 3.0, vAlign = VAlign.TOP, bold = true)
            p.line(10.0, h - 17, w - 10, h - 17)
            list += p
            return p
        }
        fun para(p: DisplayList, x: Double, y: Double, text: String, th: Double, width: Double, bold: Boolean = false): Double {
            var yy = y
            for (ln in wrap(text, th, width)) { p.text(x, yy, ln, th, vAlign = VAlign.TOP, bold = bold); yy -= th * 1.5 }
            return yy
        }
        val d = { v: Double -> fmt.format(v) }
        val s = r.stock

        var p = page()
        var y = h - 24
        p.text(w / 2, y, "Cutting plan", 8.0, HAlign.CENTER, VAlign.TOP, bold = true); y -= 14
        p.rect(20.0, y - wrap(NOTICE, 3.2, w - 46).size * 4.8 - 5, w - 20, y)
        y = para(p, 23.0, y - 2.5, NOTICE, 3.2, w - 46) - 7
        p.text(20.0, y, "STOCK ASSUMED", 3.5, vAlign = VAlign.TOP, bold = true); y -= 6.5
        y = para(p, 23.0, y, "Sheets ${d(s.sheetW)} x ${d(s.sheetL)}, or a half or quarter of one where everything fits (many stores sell these). Boards and rods in lengths of ${d(s.boardL)}. Saw cut ${d(s.kerf)} wide." + (if (s.rotate) " Panels may be turned to fit." else " Panels keep their length along the sheet."), 3.0, w - 46) - 4
        p.text(20.0, y, "TO BUY", 3.5, vAlign = VAlign.TOP, bold = true); y -= 6.5
        val buy = CutPlanner.shoppingList(r, fmt) + extraBuy
        for (b in buy.ifEmpty { listOf("Nothing in this project is cut from sheets or lengths.") }) y = para(p, 23.0, y, "• $b", 3.0, w - 46)
        if (r.leftOut.isNotEmpty()) {
            y -= 4; p.text(20.0, y, "NOT IN THE PLAN", 3.5, vAlign = VAlign.TOP, bold = true); y -= 6.5
            for (b in r.leftOut) y = para(p, 23.0, y, "• $b", 3.0, w - 46)
        }
        y -= 4; p.text(20.0, y, "ITEMS", 3.5, vAlign = VAlign.TOP, bold = true); y -= 6.5
        for (row in rows) {
            if (y < 22) { p = page(); y = h - 24 }
            y = para(p, 23.0, y, "${row.item}: ${row.qty} x ${row.description}", 2.8, w - 46)
        }

        // Sheets, two to a page, each drawn to scale with its length up the page.
        val cellW = (w - 40 - 10) / 2; val cellH = h - 24 - 16 - 14
        val k = minOf(cellW / s.sheetW, cellH / s.sheetL)
        r.sheets.forEachIndexed { i, sh ->
            if (i % 2 == 0) p = page()
            val x0 = 20.0 + (i % 2) * (cellW + 10); val y0 = 18.0
            // Every panel at one scale, so a half sheet looks half the size of a full one.
            val sw = if (sh.w > 0) sh.w else s.sheetW; val sl = if (sh.l > 0) sh.l else s.sheetL
            val top = y0 + sl * k
            p.text(x0, top + 9, "Sheet ${i + 1} of ${r.sheets.size}: ${d(sh.thickness)} thick" + (if (sh.material.isBlank()) "" else ", ${sh.material}"), 3.2, bold = true)
            p.text(x0, top + 3.5, (if (sh.size == "sheet") "" else "${sh.size.replaceFirstChar { it.uppercase() }}, ") + "${d(sw)} x ${d(sl)}, ${Math.round(sh.used * 100)}% used", 2.8)
            p.rect(x0, y0, x0 + sw * k, top, LineStyle.THIN)
            for (pc in sh.pieces) {
                val ax = x0 + pc.x * k; val ay = y0 + pc.y * k; val pw = pc.w * k; val pl = pc.l * k
                p.rect(ax, ay, ax + pw, ay + pl, LineStyle.THICK)
                label(p, ax, ay, pw, pl, "${pc.item}", "${d(pc.w)} x ${d(pc.l)}")
            }
        }

        // Lengths as bars.
        if (r.boards.isNotEmpty()) {
            p = page(); y = h - 26
            val barW = w - 40 - 34; val kb = barW / s.boardL
            var lastKey: List<Any>? = null
            var n = 0
            for (b in r.boards) {
                val key = listOf(b.kind, b.stockName ?: "${b.t}x${b.w}", b.material)
                val newGroup = key != lastKey
                if (y - (if (newGroup) 22 else 13) < 16) { p = page(); y = h - 26 }
                if (newGroup) {
                    val count = r.boards.count { listOf(it.kind, it.stockName ?: "${it.t}x${it.w}", it.material) == key }
                    val section = if (b.kind == PartKind.ROD) "Rod ${d((b.t + b.w) / 2)} dia" else if (b.stockName != null) "${b.stockName} (actual ${d(b.t)} x ${d(b.w)})" else "${d(b.t)} x ${d(b.w)}"
                    y -= 3
                    p.text(20.0, y, "$section" + (if (b.material.isBlank()) "" else ", ${b.material}") + ": $count length${if (count == 1) "" else "s"} of ${d(s.boardL)}", 3.2, vAlign = VAlign.TOP, bold = true)
                    y -= 7; lastKey = key; n = 0
                }
                n++
                val bh = 7.0
                p.text(18.0, y - bh / 2, "$n", 2.8, HAlign.RIGHT, VAlign.CENTER)
                p.rect(20.0, y - bh, 20.0 + barW, y, LineStyle.THIN)
                var x = 0.0
                for ((item, len) in b.cuts) {
                    val ax = 20.0 + x * kb; val pw = len * kb
                    p.rect(ax, y - bh, ax + pw, y, LineStyle.THICK)
                    label(p, ax, y - bh, pw, bh, "$item", d(len), oneLine = true)
                    x += len + s.kerf
                }
                p.text(20.0 + barW + 2, y - bh / 2, if (b.offcut > 0.5) "${d(b.offcut)} left" else "", 2.6, vAlign = VAlign.CENTER)
                y -= bh + 4
            }
        }
        return list
    }

    /** Item number and size inside a piece, as much of it as fits. */
    private fun label(p: DisplayList, x: Double, y: Double, w: Double, h: Double, item: String, size: String, oneLine: Boolean = false) {
        val cx = x + w / 2; val cy = y + h / 2
        if (oneLine) {
            val both = "$item: $size"
            when {
                StrokeFont.width(both, 2.6) < w - 2 -> p.text(cx, cy, both, 2.6, HAlign.CENTER, VAlign.CENTER)
                StrokeFont.width(item, 2.6) < w - 1 -> p.text(cx, cy, item, 2.6, HAlign.CENTER, VAlign.CENTER, bold = true)
            }
            return
        }
        val big = minOf(5.0, h * 0.5, w * 0.5)
        val sizeFits = StrokeFont.width(size, 2.4) < w - 2 && h > big + 6
        val sizeFitsTurned = !sizeFits && StrokeFont.width(size, 2.4) < h - 2 && w > 2 * big + 4
        if (big >= 1.8 && StrokeFont.width(item, big) < w - 1) p.text(cx, cy + (if (sizeFits) 1.8 else 0.0), item, big, HAlign.CENTER, VAlign.CENTER, bold = true)
        if (sizeFits) p.text(cx, cy - big / 2 - 0.8, size, 2.4, HAlign.CENTER, VAlign.TOP)
        else if (sizeFitsTurned) p.text(x + w - 1.5, cy, size, 2.4, HAlign.CENTER, VAlign.TOP, rotationDeg = 90.0)
    }
}
