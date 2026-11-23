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
    /**
     * Boards may also be bought a quarter and a half longer (8 ft lengths, and 10 and 12 ft; 2.4 m, and 3.0 and
     * 3.6 m), as stores stock them; each kind of board uses the one length that wastes least.
     */
    val longer: Boolean = true,
) {
    /** The lengths a board may be bought in, shortest first. */
    val boardLengths: List<Double> get() {
        if (!longer) return listOf(boardL)
        // Feet stay exact (8, 10, 12 ft); metric lengths are rounded down to the 100 mm stores sell in (3.0, 3.6 m).
        val feet = kotlin.math.abs(boardL / 304.8 - Math.rint(boardL / 304.8)) < 1e-3
        fun round(v: Double) = if (feet) v else Math.floor(v / 100.0) * 100.0
        return listOf(boardL, round(boardL * 1.25), round(boardL * 1.5)).distinct().sorted()
    }

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
    val stockName: String? = null, val stockW: Double = w, val ripTo: Double? = null, val length: Double = 0.0)

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

/** [layered]: panels thicker than any sheet, cut as this many layers of a sheet thickness and glued face to face (item to count and layer thickness). */
class CutPlanResult(val stock: StockSettings, val sheets: List<SheetLayout>, val boards: List<BoardLayout>, val leftOut: List<String>,
    val layered: Map<Int, Pair<Int, Double>> = emptyMap(),
    /** Solid-wood panels glued up edge to edge from boards: item to how many boards. */
    val edgeGlued: Map<Int, Int> = emptyMap(),
    /** Thick sticks of no standard size (a 2-1/2 in square leg), cut from the next standard lumber up: item to its name. */
    val downsized: Map<Int, String> = emptyMap(),
    /** Right triangles cut two from each blank: item to how many blanks the plan holds for it. */
    val paired: Map<Int, Int> = emptyMap())

/**
 * Lays panels out on sheets and boards along bought lengths. Every sheet layout can be cut with
 * straight cuts that run right across the piece being cut (as a hand-held or table saw needs).
 * It is a good layout, not a proven best one.
 */
object CutPlanner {
    private class Free(val x: Double, val y: Double, val w: Double, val l: Double)
    private class Open(val pieces: ArrayList<PlacedPiece> = ArrayList(), val free: ArrayList<Free> = ArrayList())
    /** [fixed]: the grain must run along the sheet's length, so the piece is never turned. */
    private class Want(val item: Int, val w: Double, val l: Double, val fixed: Boolean = false)

    /** A shaped part this thin compared with its width is cut from sheet; thicker ones are left to the maker. */
    private const val FLAT_SHARE = 0.3

    /**
     * Guillotine packing onto as many [sw] x [sl] panels as needed, trying several piece orders and ways of
     * splitting the offcuts and keeping the layout that needs the fewest panels, then has the emptiest last one
     * (which may then be bought as a half or quarter sheet), then the tightest bounding box overall.
     */
    private fun pack(wants: List<Want>, sw: Double, sl: Double, stock: StockSettings): List<Open> {
        val orders = listOf<Comparator<Want>>(
            compareByDescending<Want> { it.w * it.l }.thenByDescending { it.l },
            compareByDescending<Want> { maxOf(it.w, it.l) }.thenByDescending { minOf(it.w, it.l) },
            compareByDescending<Want> { minOf(it.w, it.l) }.thenByDescending { maxOf(it.w, it.l) },
        )
        var best: List<Open>? = null; var bestKey: Triple<Int, Double, Double>? = null
        for (o in orders) for (rule in 0..2) {
            val r = packOnce(wants.sortedWith(o), sw, sl, stock, rule)
            if (r.isEmpty() && wants.isNotEmpty()) return r
            val last = r.lastOrNull()
            val lastUsed = last?.pieces?.sumOf { it.w * it.l } ?: 0.0
            // How far the pieces reach on the last panel: a compact layout leaves a whole offcut.
            val reach = last?.pieces?.maxOfOrNull { it.y + it.l } ?: 0.0
            val key = Triple(r.size, lastUsed, reach)
            if (bestKey == null || key.first < bestKey.first || (key.first == bestKey.first && (key.second < bestKey.second - 1.0 || (abs(key.second - bestKey.second) <= 1.0 && key.third < bestKey.third)))) { best = r; bestKey = key }
        }
        return best ?: emptyList()
    }

    /** One guillotine packing. [rule] 0 keeps the larger offcut whole, 1 always cuts across the sheet first, 2 always along it. */
    private fun packOnce(wants: List<Want>, sw: Double, sl: Double, stock: StockSettings, rule: Int): List<Open> {
        val open = ArrayList<Open>()
        for (p in wants) {
            var best: Triple<Open, Free, Boolean>? = null; var bestScore = Double.MAX_VALUE
            fun consider(o: Open) {
                for (f in o.free) for (rot in if (stock.rotate && !p.fixed) listOf(false, true) else listOf(false)) {
                    // Unrotated, the piece's length runs along the sheet's length.
                    val pw = if (rot) p.l else p.w; val pl = if (rot) p.w else p.l
                    if (pw > f.w + 1e-6 || pl > f.l + 1e-6) continue
                    val score = minOf(f.w - pw, f.l - pl)        // tightest fit on one side wastes least
                    if (score < bestScore - 1e-9) { bestScore = score; best = Triple(o, f, rot) }
                }
            }
            for (o in open) consider(o)
            if (best == null) { if (!(p.w <= sw + 1e-6 && p.l <= sl + 1e-6) && !(stock.rotate && !p.fixed && p.l <= sw + 1e-6 && p.w <= sl + 1e-6)) return emptyList(); val o = Open(); o.free += Free(0.0, 0.0, sw, sl); open += o; consider(o) }
            val (o, f, rot) = best ?: continue
            val pw = if (rot) p.l else p.w; val pl = if (rot) p.w else p.l
            o.pieces += PlacedPiece(p.item, f.x, f.y, pw, pl, rot)
            o.free.remove(f)
            // The saw cut is lost beside the piece, except where the piece reaches the edge of the space.
            val cw = minOf(pw + stock.kerf, f.w); val cl = minOf(pl + stock.kerf, f.l)
            val restW = f.w - cw; val restL = f.l - cl
            // One straight cut frees the larger offcut whole; the other is what remains beside the piece.
            val alongFirst = when (rule) { 1 -> false; 2 -> true; else -> restW * f.l >= restL * f.w }
            if (alongFirst) {
                if (restW > 1e-6) o.free += Free(f.x + cw, f.y, restW, f.l)
                if (restL > 1e-6) o.free += Free(f.x, f.y + cl, cw, restL)
            } else {
                if (restL > 1e-6) o.free += Free(f.x, f.y + cl, f.w, restL)
                if (restW > 1e-6) o.free += Free(f.x + cw, f.y, restW, cl)
            }
        }
        return open
    }

    /** One straight saw cut across a region of the sheet: from (x0, y0) to (x1, y1), in sheet coordinates. */
    class Cut(val n: Int, val x0: Double, val y0: Double, val x1: Double, val y1: Double) { val rip: Boolean get() = abs(x1 - x0) < 1e-6 }

    /**
     * The order to make a layout's cuts in, as a panel saw or a guide rail cuts a sheet: each cut runs right across
     * the piece of sheet being cut. Rips along the sheet's length come first where they free whole strips (as a
     * store cuts sheets), then the strips are cut across. Cuts that would only trim waste off the far edges are
     * left out.
     */
    fun cutOrder(sh: SheetLayout, sheetW: Double, sheetL: Double, kerf: Double): List<Cut> {
        val out = ArrayList<Cut>()
        fun split(x0: Double, y0: Double, x1: Double, y1: Double, ps: List<PlacedPiece>) {
            if (ps.isEmpty()) return
            if (ps.size == 1) {
                // Free the last piece from its offcuts: across its far side, then along it.
                val pc = ps[0]
                if (pc.y + pc.l < y1 - 0.5) out += Cut(out.size + 1, x0, pc.y + pc.l, x1, pc.y + pc.l)
                if (pc.x + pc.w < x1 - 0.5) out += Cut(out.size + 1, pc.x + pc.w, y0, pc.x + pc.w, minOf(y1, pc.y + pc.l))
                return
            }
            // A line that no piece crosses: just past a piece's far edge (where the saw cut goes).
            fun clear(along: Boolean, at: Double) = ps.none { pc -> if (along) pc.x < at - 0.5 && pc.x + pc.w > at + 0.5 else pc.y < at - 0.5 && pc.y + pc.l > at + 0.5 }
            val ripAt = ps.map { it.x + it.w }.filter { it < x1 - 0.5 && it > x0 + 0.5 }.distinct().sorted().firstOrNull { clear(true, it) && ps.any { pc -> pc.x + pc.w <= it + 0.5 } && ps.any { pc -> pc.x >= it - 0.5 } }
            val crossAt = ps.map { it.y + it.l }.filter { it < y1 - 0.5 && it > y0 + 0.5 }.distinct().sorted().firstOrNull { clear(false, it) && ps.any { pc -> pc.y + pc.l <= it + 0.5 } && ps.any { pc -> pc.y >= it - 0.5 } }
            when {
                ripAt != null -> { out += Cut(out.size + 1, ripAt, y0, ripAt, y1); split(x0, y0, ripAt, y1, ps.filter { it.x + it.w <= ripAt + 0.5 }); split(ripAt + kerf, y0, x1, y1, ps.filter { it.x >= ripAt - 0.5 }) }
                crossAt != null -> { out += Cut(out.size + 1, x0, crossAt, x1, crossAt); split(x0, y0, x1, crossAt, ps.filter { it.y + it.l <= crossAt + 0.5 }); split(x0, crossAt + kerf, x1, y1, ps.filter { it.y >= crossAt - 0.5 }) }
                else -> return
            }
        }
        split(0.0, 0.0, sheetW, sheetL, sh.pieces)
        return out
    }

    /** A board at least this wide (mm) of sheet thickness is ripped from a sheet in metric shops, not bought as a length. */
    private const val SHEET_STRIP_MIN = 150.0

    /** Boards this thin (mm) or thinner are hardly sold as timber in metric: they are strips cut from sheet. */
    private const val THIN_STRIP_MAX = 12.5

    /**
     * The thickest sheet sold for furniture: 1 in MDF and plywood; 30 mm in metric. Anything thicker (a 1-1/2 in
     * workbench top) is glued up from layers of a thinner sheet, as it is in practice.
     */
    private const val SHEET_MAX_IN = 25.4
    private const val SHEET_MAX_MM = 30.0

    /**
     * A solid-wood panel (a material that is not a sheet good, at least 5/8 in or 15 mm thick: thinner "oak" is
     * veneered plywood) is glued up from boards no wider than a 1x6 (5-1/2 in) or 150 mm, which keeps it flat.
     * Returns how many boards and how wide each is bought (mm), or null when it is cut from sheet.
     */
    fun edgeGlue(r: CutListRow, inch: Boolean): Pair<Int, Double>? {
        if (r.kind != PartKind.PANEL || r.material.isBlank() || splitziner.material.Materials.sheetGood(r.material) || r.t < 15.0) return null
        if (!splitziner.material.Materials.wood(r.material)) return null
        return if (inch) {
            // 1x6 (or 5/4x6, 2x6): 5-1/2 in actual, with about 1/8 in lost from each edge that is jointed.
            val n = kotlin.math.ceil((r.w + 3.2) / (139.7 - 3.2)).toInt().coerceAtLeast(2)
            n to 139.7
        } else {
            // Metric timber is bought to width: equal strips, 5 mm over for planing the edges straight.
            val n = kotlin.math.ceil(r.w / 150.0).toInt().coerceAtLeast(2)
            n to kotlin.math.ceil(r.w / n + 5.0)
        }
    }

    /** Boards for an edge-glued panel are cut this much long (mm) and the panel cut to length once dry: 1 in, or 25 mm. */
    fun glueOver(inch: Boolean) = if (inch) 25.4 else 25.0

    /** Each layer of a glued-up panel is cut this much over (mm) and the panel trimmed to size once the glue sets: 1/4 in, or 5 mm. */
    fun layerOver(inch: Boolean) = if (inch) 6.35 else 5.0

    /** A panel [t] thick made of 2 or 3 equal layers of a stock sheet thickness, or null when no split is exact. */
    fun layersOf(t: Double, inch: Boolean): Pair<Int, Double>? {
        val stockT = splitziner.check.DesignCheck.stockThicknesses(if (inch) LengthFormat("in") else LengthFormat("mm"))
        val max = if (inch) SHEET_MAX_IN else SHEET_MAX_MM
        if (t <= max + 0.5) return null
        // Two layers of 3/4 in (or 19 mm) first: the common sheet, and the fewest glue lines.
        for (n in 2..3) stockT.filter { it <= max + 0.01 }.sortedBy { abs(it - 19.0) > 0.6 }.firstOrNull { abs(it * n - t) <= maxOf(0.6, 0.02 * t) }?.let { return n to it }
        return null
    }

    fun plan(rows00: List<CutListRow>, stock: StockSettings): CutPlanResult {
        val leftOut = ArrayList<String>()
        // Glass (or acrylic) is not cut from wood stock: a glass shop cuts it, a little under the drawn size so it drops in.
        val glass = rows00.filter { r -> r.name.split(" / ").any { it == "Glass" } }
        // Casters are bought by the piece, not cut.
        val casters = rows00.filter { r -> r.name.split(" / ").all { it == "Caster" } && r.name.isNotEmpty() }
        val rows0 = rows00 - glass.toSet() - casters.toSet()
        // Inch stock (a 4 x 8 ft sheet, or boards in whole feet) means an inch user.
        val inch = kotlin.math.abs(stock.sheetW - 1219.2) < 1.0 || kotlin.math.abs(stock.boardL / 304.8 - Math.rint(stock.boardL / 304.8)) < 1e-3 && stock.lumber
        val d = { v: Double -> if (inch) splitziner.dims.LengthFormat("in", "fraction").format(v) + " in" else "${Math.round(v)} mm" }
        for (r in glass) {
            val under = if (inch) 1.5875 else 2.0
            leftOut += "Glass or acrylic, ${d(r.t)} thick: ${r.qty} piece${if (r.qty == 1) "" else "s"} ${d(r.w - under)} x ${d(r.l - under)} (item ${r.item}), cut by a glass shop ${d(under)} under the drawn size so ${if (r.qty == 1) "it drops" else "they drop"} into the rabbet. Ask for the edges to be smoothed."
        }
        for (r in casters) leftOut += "Casters: ${r.qty}, plate-mounted, about ${d(r.l)} tall overall (item ${r.item}); two with brakes keep the piece still."
        // A panel thicker than any sheet is cut as layers of one, each a piece of its own on the plan.
        val layered = HashMap<Int, Pair<Int, Double>>()
        // A panel of solid wood is not cut from a sheet (oak is not sold 4 x 8): it is glued up edge to edge from boards.
        val edgeGlued = HashMap<Int, Int>()
        val downsized = HashMap<Int, String>()
        // Right triangles are sawn two from one blank: the plan holds half as many blanks (rounded up).
        val paired = HashMap<Int, Int>()
        val rows = rows0.map { r -> if (r.description.contains("A RIGHT TRIANGLE") && r.qty > 1) r.copy(qty = (r.qty + 1) / 2).also { paired[r.item] = it.qty } else r }.map { r ->
            // A thick stick of no standard size (a 2-1/2 in square leg) comes from the next lumber up, planed or ripped down.
            if (stock.lumber && r.kind == PartKind.BOARD && r.t > SHEET_MAX_IN + 0.5 && Lumber.fit(r.t, r.w) == null) {
                Lumber.SIZES.filter { it.t >= r.t - 0.8 && it.w >= r.w - 0.8 }.minByOrNull { it.t * it.w }?.let { sz ->
                    downsized[r.item] = sz.name
                    return@map r.copy(t = sz.t, w = sz.w)
                }
            }
            val glue = edgeGlue(r, stock.lumber)
            if (glue != null) { edgeGlued[r.item] = glue.first; return@map r.copy(kind = PartKind.BOARD, w = glue.second, l = r.l + glueOver(stock.lumber), qty = r.qty * glue.first) }
            val split = if (r.kind == PartKind.PANEL) layersOf(r.t, stock.lumber) else null
            if (split == null) r else { layered[r.item] = split; val o = layerOver(stock.lumber); r.copy(t = split.second, qty = r.qty * split.first, w = r.w + o, l = r.l + o) }
        }
        val sheets = ArrayList<SheetLayout>(); val boards = ArrayList<BoardLayout>()
        fun key(v: Double) = Math.round(v * 100)

        // In inches, boards come from standard lumber when their thickness is a lumber thickness (3/4 or 1-1/2 in).
        // Boards of other thicknesses (a 1/2 in or 1/4 in strip) are cut as strips from sheet goods, as they are in practice.
        val lumberish = { r: CutListRow -> stock.lumber && r.kind == PartKind.BOARD && Lumber.fit(r.t, r.w) != null }
        // A thickness within a hair of a stock one (1.02 in drawn for 1 in) is that stock thickness.
        val stockT = splitziner.check.DesignCheck.stockThicknesses(if (stock.lumber) LengthFormat("in") else LengthFormat("mm"))
        fun snapT(t: Double) = stockT.firstOrNull { abs(it - t) <= maxOf(0.4, 0.03 * it) } ?: t
        // Metric shops have no standard board widths to speak of: a board of sheet thickness is ripped from the sheet
        // when it is wide (a drawer side, a drawer front), or when sheets of that thickness are bought anyway (a kick
        // board from the carcass sheet). Solid timber stays as lengths, and so does anything longer than a sheet.
        val sheetThick = rows.filter { it.kind == PartKind.PANEL }.map { key(snapT(it.t)) to it.material }.toSet()
        val fromSheet = { r: CutListRow ->
            // In inches, a board that would have to be ripped from lumber comes from the sheet of its thickness
            // when that sheet is bought anyway (drawer fronts from the carcass plywood); exact lumber widths stay lumber.
            (stock.lumber && r.kind == PartKind.BOARD && splitziner.material.Materials.sheetGood(r.material) && r.l <= stock.sheetL &&
                (key(snapT(r.t)) to r.material) in sheetThick && Lumber.fit(r.t, r.w)?.let { abs(it.w - r.w) > 0.8 } == true) ||
            !stock.lumber && r.kind == PartKind.BOARD && splitziner.material.Materials.sheetGood(r.material) && stockT.any { s -> abs(s - snapT(r.t)) < 1e-6 } && snapT(r.t) <= 25.5 &&
                r.l <= stock.sheetL && (r.w >= SHEET_STRIP_MIN || snapT(r.t) <= THIN_STRIP_MAX || (key(snapT(r.t)) to r.material) in sheetThick)
        }
        val flat = rows.filter { it.kind == PartKind.PANEL || (it.kind == PartKind.SHAPED && it.t < FLAT_SHARE * it.w) || (stock.lumber && it.kind == PartKind.BOARD && !lumberish(it)) || fromSheet(it) }
        for (r in rows) if (r.kind == PartKind.SHAPED && r !in flat) leftOut += "Item ${r.item} is a shaped block; it is not in the plan."
        // Panels thicker than any sheet sold are glued up from boards (or bought as a butcher-block blank), not cut from sheet.
        val tooThick = flat.filter { snapT(it.t) > stockT.max() + 0.5 }
        for (r in tooThick) leftOut += "Item ${r.item} is thicker than any sheet sold: glue it up from boards, or buy it as a butcher-block panel."
        // Narrow boards that are on the sheet only because it is bought anyway are a convenience: if they would need
        // another panel, they are bought as lengths after all.
        val optional = { r: CutListRow -> fromSheet(r) && (stock.lumber || (r.w < SHEET_STRIP_MIN && snapT(r.t) > THIN_STRIP_MAX)) }
        val bounced = HashSet<Int>()
        for ((group, members) in (flat - tooThick.toSet()).groupBy { key(snapT(it.t)) to it.material }.entries.sortedBy { it.key.first }) {
            fun wantsOf(rs: List<CutListRow>): List<Want> {
                val wants = ArrayList<Want>()
                for (r in rs) repeat(r.qty) { wants += Want(r.item, r.w, r.l, r.grain) }
                // Biggest first: large pieces are the hard ones to place, small ones fill what is left.
                return wants.sortedWith(compareByDescending<Want> { it.w * it.l }.thenByDescending { it.l })
            }
            val placeable = members.filter { r ->
                val fits = (r.w <= stock.sheetW + 1e-6 && r.l <= stock.sheetL + 1e-6) || (stock.rotate && !r.grain && r.l <= stock.sheetW + 1e-6 && r.w <= stock.sheetL + 1e-6)
                if (!fits) leftOut += "Item ${r.item} is larger than a sheet; it is not in the plan." + (if (r.name.contains("Top") && r.t * (layered[r.item]?.first ?: 1) >= 25.0) " Buy it as a ready-made worktop (laminate, solid wood or butcher block) and cut it to length." else "")
                fits
            }
            var take = placeable.filter { !optional(it) }
            val baseCount = if (take.isEmpty()) 0 else pack(wantsOf(take), stock.sheetW, stock.sheetL, stock).size
            for (r in placeable.filter { optional(it) }.sortedByDescending { it.w * it.l * it.qty }) {
                val n = pack(wantsOf(take + r), stock.sheetW, stock.sheetL, stock).size
                if (take.isEmpty() || n <= baseCount) take = take + r else bounced += r.item
            }
            val wants = wantsOf(take)
            for (o in pack(wants, stock.sheetW, stock.sheetL, stock)) {
                // A sheet with little on it is bought smaller where everything fits: stores sell half and quarter panels.
                val wantsHere = o.pieces.map { Want(it.item, if (it.rotated) it.l else it.w, if (it.rotated) it.w else it.l, rows.any { r -> r.item == it.item && r.grain }) }
                    .sortedWith(compareByDescending<Want> { it.w * it.l }.thenByDescending { it.l })
                val smaller = listOf(Triple(stock.sheetW / 2, stock.sheetL / 2, "quarter sheet"), Triple(stock.sheetW, stock.sheetL / 2, "half sheet"))
                    .firstNotNullOfOrNull { (sw, sl, name) -> pack(wantsHere, sw, sl, stock).singleOrNull()?.let { Triple(it, sw to sl, name) } }
                val (fin, dims, name) = smaller ?: Triple(o, stock.sheetW to stock.sheetL, "sheet")
                sheets += SheetLayout(group.first / 100.0, group.second, fin.pieces, fin.pieces.sumOf { it.w * it.l } / (dims.first * dims.second), dims.first, dims.second, name)
            }
        }

        val long = rows.filter { (it.kind == PartKind.BOARD && (!stock.lumber || lumberish(it)) && (!fromSheet(it) || it.item in bounced)) || it.kind == PartKind.ROD }
        // Pieces that come from the same standard board are cut from the same lengths, ripped narrower where needed.
        // Metric timber is sold in whole millimetres: a board drawn 19.05 thick (3/4 in) is a 19 mm board, so it joins
        // the other 19 mm boards rather than standing apart as "19.1".
        fun mmT(t: Double) = if (stock.lumber) t else snapT(t)
        fun stockKey(r: CutListRow): List<Any> { val lb = if (r.kind == PartKind.BOARD && stock.lumber) Lumber.fit(r.t, r.w) else null; return if (lb != null) listOf(r.kind, lb.name, r.material) else listOf(r.kind, key(mmT(r.t)), key(r.w), r.material) }
        for ((_, members) in long.groupBy { stockKey(it) }.entries.sortedBy { (key(it.value[0].t)) * 100000 + key(it.value[0].w) }) {
            val first = members[0]
            val lb = if (first.kind == PartKind.BOARD && stock.lumber) Lumber.fit(first.t, first.w) else null
            val longest = stock.boardLengths.last()
            val wants = ArrayList<Pair<Int, Double>>()
            for (r in members) {
                if (r.l > longest + 1e-6) { leftOut += "Item ${r.item} is longer than the stock length; it is not in the plan."; continue }
                repeat(r.qty) { wants += r.item to r.l }
            }
            wants.sortByDescending { it.second }
            fun packAt(len: Double): Pair<List<List<Pair<Int, Double>>>, List<Double>>? {
                if (wants.any { it.second > len + 1e-6 }) return null
                val bins = ArrayList<ArrayList<Pair<Int, Double>>>(); val left = ArrayList<Double>()
                for (c in wants) {
                    // First length with room: the classic "longest first" rule, within about a fifth of the fewest possible.
                    var i = left.indexOfFirst { it >= c.second - 1e-6 }
                    if (i < 0) { bins += ArrayList<Pair<Int, Double>>(); left += len; i = bins.size - 1 }
                    bins[i] += c; left[i] = left[i] - c.second - stock.kerf
                }
                return bins to left
            }
            // The length that buys least wood in all; on a tie, fewer pieces to carry, then the shorter length.
            val (len, packed) = stock.boardLengths.mapNotNull { L -> packAt(L)?.let { L to it } }
                .minWithOrNull(compareBy({ it.first * it.second.first.size }, { it.second.first.size }, { it.first })) ?: continue
            val (bins, left) = packed
            val widest = members.maxOf { it.w }
            bins.forEachIndexed { i, cuts ->
                // A length only part used (the last one, often) is bought in the shortest length that still holds its pieces.
                val used = len - left[i] - stock.kerf
                val own = stock.boardLengths.firstOrNull { it >= used - 1e-6 } ?: len
                val off = maxOf(0.0, left[i] - (len - own))
                boards += if (lb != null) BoardLayout(first.kind, lb.t, lb.w, first.material, cuts, off, lb.name, lb.w, if (members.any { lb.w - it.w > 0.8 }) widest else null, own)
                else BoardLayout(first.kind, mmT(first.t), first.w, first.material, cuts, off, length = own)
            }
        }
        return CutPlanResult(stock, sheets, boards, leftOut, layered, edgeGlued, downsized, paired)
    }

    /** "Before you build" for each panel glued up from layers of sheet: how to make it. */
    fun gluingSteps(rows: List<CutListRow>, fmt: LengthFormat): List<String> = rows.filter { it.kind == PartKind.PANEL }.mapNotNull { r ->
        edgeGlue(r, fmt.isInch)?.let { (n, _) ->
            val tf = if (fmt.isInch) LengthFormat("in", inchStyle = "fraction", fractionDenominator = 8) else fmt
            val u = if (fmt.isInch) " in" else " mm"
            val name = r.name.takeIf { it.isNotBlank() }?.let { " (${it.lowercase()})" } ?: ""
            return@mapNotNull "Glue up item ${r.item}$name edge to edge from $n boards, each cut ${tf.format(glueOver(fmt.isInch))}$u longer than the cut list (the cutting plan allows for it). " +
                "Lay them out so the grain looks good and the growth rings on the ends alternate up and down, plane or joint the meeting edges straight, then glue and clamp them flat, with clamps above and below. " +
                "Once dry, scrape off the squeezed-out glue, flatten the faces, and cut it to the cut list's width and length."
        }
        val v = layersOf(r.t, fmt.isInch) ?: return@mapNotNull null
        val tf = if (fmt.isInch) LengthFormat("in", inchStyle = "fraction", fractionDenominator = 8) else fmt
        val u = if (fmt.isInch) " in" else " mm"
        val name = r.name.takeIf { it.isNotBlank() }?.let { " (${it.lowercase()})" } ?: ""
        "Glue up item ${r.item}$name from ${v.first} layers of ${tf.format(v.second)}$u sheet, each cut ${tf.format(layerOver(fmt.isInch))}$u over the cut list's width and length (the cutting plan allows for it). " +
            "Spread glue thinly over the faces that meet, line the layers up, and screw them together from the face that will be underneath, about every ${if (fmt.isInch) "8 in" else "200 mm"}, with screws a little shorter than the layers together. " +
            "Once the glue has set, trim the edges flush and to size together."
    }

    /** What each glued-up panel is made of, for the plan, the shopping list and the guide. */
    fun layerNotes(r: CutPlanResult, fmt: LengthFormat): List<String> = r.paired.entries.sortedBy { it.key }.map { (item, n) ->
        "Item $item: each of its $n piece${if (n == 1) "" else "s"} on the plan is a blank for two triangles, sawn corner to corner."
    } + r.downsized.entries.sortedBy { it.key }.map { (item, name) ->
        "Item $item is no standard lumber size: cut it from $name and plane or rip it down to the cut list's size."
    } + r.edgeGlued.entries.sortedBy { it.key }.map { (item, n) ->
        "Item $item is solid wood, glued up edge to edge from $n boards cut a little long, then cut to size (or buy an edge-glued panel that size)."
    } + r.layered.entries.sortedBy { it.key }.map { (item, v) ->
        val tf = if (fmt.isInch) LengthFormat("in", inchStyle = "fraction", fractionDenominator = 8) else fmt
        val u = if (fmt.isInch) " in" else " mm"
        "Item $item is cut as ${v.first} layers of ${tf.format(v.second)}$u sheet, glued face to face to make it ${tf.format(v.first * v.second)}$u thick (or buy it as a butcher-block or worktop panel that thick)."
    }

    /** One kind of stock to buy: how many, and what one of them is ("sheet 3/4 in thick, 48 x 96"), which also keys its price. */
    class Buy(val count: Int, val one: String, val line: String)

    fun shoppingItems(r: CutPlanResult, fmt: LengthFormat): List<Buy> {
        val out = ArrayList<Buy>()
        fun mat(m: String) = if (m.isBlank()) "" else " ($m)"
        // Sheet thicknesses as sold: 3/8 in, not .37.
        val tf = if (fmt.isInch) fmt.copy(inchStyle = "fraction", fractionDenominator = 32) else fmt
        for ((k, v) in r.sheets.groupBy { Triple(it.thickness, it.material, it.size) }) {
            val (w, l) = v[0].let { if (it.w > 0) it.w to it.l else r.stock.sheetW to r.stock.sheetL }
            val what = "${tf.format(k.first)}${if (fmt.isInch) " in" else " mm"} thick, ${fmt.format(w)} x ${fmt.format(l)}${mat(k.second)}"
            out += Buy(v.size, "${k.third} $what", "${v.size} ${k.third}${if (v.size == 1) "" else "s"} $what")
        }
        for ((k, v) in r.boards.groupBy { listOf(it.kind, it.stockName ?: "${it.t}x${it.w}", it.material, it.length) }) {
            val b = v[0]
            val bl = if (b.length > 0) b.length else r.stock.boardL
            val section = if (b.kind == PartKind.ROD) "rod ${fmt.format((b.t + b.w) / 2)} dia"
                else if (b.stockName != null) "${b.stockName} (actual ${fmt.format(b.t)} x ${fmt.format(b.w)})"
                else "${fmt.format(b.t)} x ${fmt.format(b.w)}"
            val rip = if (b.stockName != null && b.ripTo != null) ", some pieces ripped narrower" else ""
            out += Buy(v.size, "length of $section, ${fmt.format(bl)} long${mat(k[2] as String)}",
                "${v.size} length${if (v.size == 1) "" else "s"} of $section$rip, ${fmt.format(bl)} long${mat(k[2] as String)}")
        }
        return out
    }

    fun shoppingList(r: CutPlanResult, fmt: LengthFormat): List<String> = shoppingItems(r, fmt).map { it.line }
}

/** Draws the plan on pages: what to buy, then each sheet to scale, then each length as a bar. */
object CutPlanLayout {
    const val NOTICE = "This is a suggested layout. Check it against the stock you really have before cutting. Where grain matters, tick it on the item in the cut list and the plan will keep that part upright on the sheet."

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
        y = para(p, 23.0, y, "Sheets ${d(s.sheetW)} x ${d(s.sheetL)}, or a half or quarter of one where everything fits (many stores sell these). Boards and rods in lengths of ${if (s.longer) "${d(s.boardL)}, ${d(s.boardL * 1.25)} or ${d(s.boardL * 1.5)}, whichever wastes least" else d(s.boardL)}. Saw cut ${d(s.kerf)} wide." + (if (s.rotate) " Panels may be turned to fit." else " Panels keep their length along the sheet.") +
            (if (rows.any { it.grain }) " Pieces marked G have their grain along their length: they run up the sheet as drawn." else ""), 3.0, w - 46) - 4
        p.text(20.0, y, "TO BUY", 3.5, vAlign = VAlign.TOP, bold = true); y -= 6.5
        val buy = CutPlanner.shoppingList(r, fmt) + extraBuy
        for (b in buy.ifEmpty { listOf("Nothing in this project is cut from sheets or lengths.") }) y = para(p, 23.0, y, "• $b", 3.0, w - 46)
        val glued = CutPlanner.layerNotes(r, fmt)
        if (glued.isNotEmpty()) {
            y -= 4; p.text(20.0, y, "NOTES", 3.5, vAlign = VAlign.TOP, bold = true); y -= 6.5
            for (b in glued) y = para(p, 23.0, y, "• $b", 3.0, w - 46)
        }
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
            // Every panel at one scale, so a half sheet looks half the size of a full one; each hangs from the top of the page.
            val sw = if (sh.w > 0) sh.w else s.sheetW; val sl = if (sh.l > 0) sh.l else s.sheetL
            val x0 = 20.0 + (i % 2) * (cellW + 10); val y0 = maxOf(18.0, h - 24 - 16 - sl * k)
            val top = y0 + sl * k
            p.text(x0, top + 9, "Sheet ${i + 1} of ${r.sheets.size}: ${d(sh.thickness)} thick" + (if (sh.material.isBlank()) "" else ", ${sh.material}"), 3.2, bold = true)
            p.text(x0, top + 3.5, (if (sh.size == "sheet") "" else "${sh.size.replaceFirstChar { it.uppercase() }}, ") + "${d(sw)} x ${d(sl)}, ${Math.round(sh.used * 100)}% used", 2.8)
            p.rect(x0, y0, x0 + sw * k, top, LineStyle.THIN)
            for (pc in sh.pieces) {
                val ax = x0 + pc.x * k; val ay = y0 + pc.y * k; val pw = pc.w * k; val pl = pc.l * k
                p.rect(ax, ay, ax + pw, ay + pl, LineStyle.THICK)
                val g = rows.any { it.item == pc.item && it.grain }
                label(p, ax, ay, pw, pl, "${pc.item}" + if (g) " G" else "", "${d(pc.w)} x ${d(pc.l)}")
            }
            // The cuts in order, numbered in a small box at the start of each, so the sheet can be broken down
            // (or the store asked to make the first rips) without working it out at the saw.
            val cuts = CutPlanner.cutOrder(sh, sw, sl, s.kerf)
            if (cuts.size >= 2) for (c in cuts) {
                val cx = x0 + c.x0 * k; val cy = y0 + c.y0 * k
                val bx = if (c.rip) cx else cx + 2.6; val by = if (c.rip) cy + 2.6 else cy
                p.rect(bx - 2.1, by - 1.6, bx + 2.1, by + 1.6, LineStyle.THIN)
                p.text(bx, by, "${c.n}", 2.2, HAlign.CENTER, VAlign.CENTER)
            }
            if (cuts.size >= 2 && i % 2 == 0) p.text(20.0, 10.0, "Boxed numbers: the order of the cuts, each right across the piece being cut.", 2.4)
        }

        // Lengths as bars.
        if (r.boards.isNotEmpty()) {
            p = page(); y = h - 26
            val barW = w - 40 - 34; val kb = barW / (r.boards.maxOfOrNull { it.length.takeIf { l -> l > 0 } ?: s.boardL } ?: s.boardL)
            var lastKey: List<Any>? = null
            var n = 0
            for (b in r.boards.sortedWith(compareBy({ it.stockName ?: "" }, { it.t }, { it.w }, { -it.length }))) {
                val key = listOf(b.kind, b.stockName ?: "${b.t}x${b.w}", b.material, b.length)
                val newGroup = key != lastKey
                if (y - (if (newGroup) 22 else 13) < 16) { p = page(); y = h - 26 }
                if (newGroup) {
                    val count = r.boards.count { listOf(it.kind, it.stockName ?: "${it.t}x${it.w}", it.material, it.length) == key }
                    val section = if (b.kind == PartKind.ROD) "Rod ${d((b.t + b.w) / 2)} dia" else if (b.stockName != null) "${b.stockName} (actual ${d(b.t)} x ${d(b.w)})" else "${d(b.t)} x ${d(b.w)}"
                    y -= 3
                    val bl = if (b.length > 0) b.length else s.boardL
                    p.text(20.0, y, "$section" + (if (b.material.isBlank()) "" else ", ${b.material}") + ": $count length${if (count == 1) "" else "s"} of ${d(bl)}", 3.2, vAlign = VAlign.TOP, bold = true)
                    y -= 7; lastKey = key; n = 0
                }
                n++
                val bh = 7.0
                p.text(18.0, y - bh / 2, "$n", 2.8, HAlign.RIGHT, VAlign.CENTER)
                // Each bar drawn to its own length, on one scale, so a 12 ft length looks longer than an 8 ft one.
                val thisL = if (b.length > 0) b.length else s.boardL
                p.rect(20.0, y - bh, 20.0 + thisL * kb, y, LineStyle.THIN)
                var x = 0.0
                for ((item, len) in b.cuts) {
                    val ax = 20.0 + x * kb; val pw = len * kb
                    p.rect(ax, y - bh, ax + pw, y, LineStyle.THICK)
                    label(p, ax, y - bh, pw, bh, "$item", d(len), oneLine = true)
                    x += len + s.kerf
                }
                p.text(20.0 + thisL * kb + 2, y - bh / 2, if (b.offcut > 0.5) "${d(b.offcut)} left" else "", 2.6, vAlign = VAlign.CENTER)
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
        // Number above size needs room for both with a margin; a low, wide piece takes them on one line instead.
        val sizeFits = StrokeFont.width(size, 2.4) < w - 2 && h > big + 9
        if (!sizeFits && h >= 4.0) {
            // Largest lettering that fits on one line, down to a readable minimum.
            for ((ih, sh) in listOf(minOf(4.0, h - 2.0) to 2.4, minOf(3.0, h - 1.5) to 2.0)) {
                val line = StrokeFont.width(item, ih) + 2 + StrokeFont.width(size, sh)
                if (line < w - 2) {
                    val x0 = cx - line / 2
                    p.text(x0, cy, item, ih, vAlign = VAlign.CENTER, bold = true)
                    p.text(x0 + StrokeFont.width(item, ih) + 2, cy, size, sh, vAlign = VAlign.CENTER)
                    return
                }
            }
        }
        // A tall, narrow piece: the number above its size, the size turned to run along the piece.
        val sizeLen = StrokeFont.width(size, 2.4)
        if (!sizeFits && w > 4.0 && big >= 1.8 && StrokeFont.width(item, big) < w - 1 && sizeLen + big + 4 < h) {
            val top = cy + (big + 2 + sizeLen) / 2
            p.text(cx, top - big / 2, item, big, HAlign.CENTER, VAlign.CENTER, bold = true)
            p.text(cx, top - big - 2 - sizeLen / 2, size, 2.4, HAlign.CENTER, VAlign.CENTER, rotationDeg = 90.0)
            return
        }
        val sizeFitsTurned = false
        if (!(big >= 1.8 && StrokeFont.width(item, big) < w - 1)) {
            // A narrow strip (a kick board, a rail) still says which item it is: along its length when the
            // lettering fits across it, else small beside it with a short leader, so no piece goes unnamed.
            // Upright, never turned: a turned 8 reads as a sign, not a number.
            val small = listOf(2.6, 2.2, 1.8, 1.5).firstOrNull { StrokeFont.width(item, it) < w - 0.5 && it < h - 1 }
            if (small != null) p.text(cx, cy, item, small, HAlign.CENTER, VAlign.CENTER, bold = true)
            else if (h >= 1.0) { p.line(x + w, cy, x + w + 2.0, cy, LineStyle.THIN); p.text(x + w + 2.4, cy, item, 2.2, vAlign = VAlign.CENTER, bold = true) }
            return
        }
        if (big >= 1.8 && StrokeFont.width(item, big) < w - 1) p.text(cx, cy + (if (sizeFits) 1.8 else 0.0), item, big, HAlign.CENTER, VAlign.CENTER, bold = true)
        if (sizeFits) p.text(cx, cy - big / 2 - 0.8, size, 2.4, HAlign.CENTER, VAlign.TOP)
        else if (sizeFitsTurned) p.text(x + w - 1.5, cy, size, 2.4, HAlign.CENTER, VAlign.BOTTOM, rotationDeg = 90.0)
    }
}
