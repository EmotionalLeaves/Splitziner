package splitziner.check

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import splitziner.cutplan.StockSettings
import splitziner.dims.LengthFormat
import splitziner.drawing.PartDrawings
import splitziner.explode.Silhouette
import splitziner.features.HoleFinder
import splitziner.math.Vec2
import splitziner.math.Vec3
import splitziner.mesh.Bvh
import splitziner.mesh.Mesh
import splitziner.mesh.OrientedBounds
import splitziner.parts.CutListRow
import splitziner.parts.Part
import splitziner.parts.PartKind
import splitziner.view.ViewDef
import kotlin.math.abs

enum class Severity { PROBLEM, CHECK, NOTE }

/** A change the app can make for the user to deal with a finding. */
enum class FixKind { SET_THICKNESS, SQUARE_UP, SPLIT_BOARDS, CUT_BACK, HOUSE }

class Fix(val kind: FixKind, val label: String, val partIds: List<String>, val value: Double = 0.0)

/** One thing the check noticed. [partIds] are the parts it is about (for highlighting). */
class Finding(val severity: Severity, val title: String, val detail: String, val partIds: List<String> = emptyList(), val fixes: List<Fix> = emptyList())

/** What making one item involves. */
class ItemWork(val item: Int, val work: Set<Work>, val holeDiameters: List<Double>, val cutouts: Int)

class CheckReport(val findings: List<Finding>, val work: List<ItemWork>, val toolsNeeded: List<String>) {
    val problems: Int get() = findings.count { it.severity == Severity.PROBLEM }
    val checks: Int get() = findings.count { it.severity == Severity.CHECK }
}

/**
 * Looks at the model the way someone about to build it would: can each part be cut from material
 * you can buy, with the tools you have, and does the whole thing hold together? It reasons from
 * shapes alone. It knows nothing of wood species, glue, screws or loads, and says so where that matters.
 */
object DesignCheck {
    /** A cut-back that would take away more than this share of a part is a redesign, not a trim, and is not offered. */
    private const val MAX_CUT_BACK = 0.25

    /** How far a top bunk's guard rails must stand above the mattress (mm): 5 in, the US bunk bed rule. */
    private const val GUARD_ABOVE_MATTRESS = 127.0

    /** A mattress thinner than this (mm, 6 in) is unusual: guard rails that only allow it are too low in practice. */
    private const val MIN_MATTRESS = 152.4

    /** Widest gap between a crib's slats (mm): 2-3/8 in, the US crib standard's limit. */
    private const val CRIB_GAP = 60.3

    /** Widest gap in a stair or balcony railing (mm): the 4 in sphere rule of US residential building codes. */
    private const val RAIL_GAP = 101.6

    /** Thicknesses sheet goods and planed boards are commonly sold in. */
    val STOCK_IN = doubleArrayOf(0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 1.0, 1.25, 1.5)
    val STOCK_MM = doubleArrayOf(3.0, 4.0, 6.0, 9.0, 12.0, 15.0, 18.0, 19.0, 22.0, 25.0, 30.0, 38.0, 40.0)

    /** Parts closer than this are touching (mm). */
    private const val TOUCH = 1.0

    /** Overlaps shallower than this are modelling noise, not a clash (mm). */
    private const val CLASH_MIN = 1.5

    /** Bed slats spanning more than this (mm) between supports want a centre rail: 40 in, past a twin's width. */
    private const val BED_CENTRE = 1016.0

    /** A shelf-like part longer than this many times its thickness may sag: a rule of thumb, not a calculation. */
    private const val SAG_RATIO = 40.0

    fun stockThicknesses(fmt: LengthFormat): DoubleArray = if (fmt.isInch) DoubleArray(STOCK_IN.size) { STOCK_IN[it] * 25.4 } else STOCK_MM

    /** The stock thicknesses just below and above [t] (mm), or a single one when [t] is a stock thickness. */
    fun nearestStock(t: Double, fmt: LengthFormat): List<Double> {
        val s = stockThicknesses(fmt)
        s.firstOrNull { abs(it - t) <= maxOf(0.4, 0.03 * it) }?.let { return listOf(it) }
        return listOfNotNull(s.lastOrNull { it < t }, s.firstOrNull { it > t })
    }

    /** What it takes to make one part, worked out from its shape. */
    fun workFor(p: Part, fmt: LengthFormat): ItemWork {
        val work = LinkedHashSet<Work>()
        val holes = ArrayList<Double>(); var cutouts = 0
        if (p.kind == PartKind.ROD) return ItemWork(p.item ?: 0, setOf(Work.STRAIGHT_CUT), holes, 0)
        val flat = p.kind == PartKind.PANEL || p.kind == PartKind.BOARD || p.t < 0.3 * p.w
        if (!flat) return ItemWork(p.item ?: 0, setOf(Work.CARVE), holes, 0)
        work += if (p.w >= 250.0) Work.SHEET_CUT else Work.STRAIGHT_CUT
        if (nearestStock(p.t, fmt).size != 1) work += Work.THICKNESS
        // A stick with its ends sawn at an angle is a saw-setting job, not a shape to mark out and cut.
        if (p.desc.contains("ENDS CUT AT") || p.desc.contains("EDGE BEVELLED")) { work += Work.ANGLE_CUT; return ItemWork(p.item ?: 0, work, holes, 0) }
        // A tapered leg or a raised panel is a saw or router setting too, with its own tools.
        if (p.desc.contains("TAPERED ON")) { work += Work.TAPER; return ItemWork(p.item ?: 0, work, holes, 0) }
        if (p.desc.contains(", RAISED (")) { work += Work.RAISE; return ItemWork(p.item ?: 0, work, holes, 0) }
        // Laid flat: length along X, thickness along Y, width along Z. Looking along Y shows the outline to cut.
        val m = PartDrawings.squaredUp(p.mesh)
        val e = m.extents
        val outline = runCatching { Silhouette.outline(m, ViewDef.FRONT) }.getOrNull()
        val boxArea = e.x * e.z
        if (outline != null && boxArea > 0) {
            val tol = 0.002 * Math.hypot(e.x, e.z)
            for (i in 0 until outline.numGeometries) {
                val poly = outline.getGeometryN(i) as? Polygon ?: continue
                if (poly.area < 0.01 * boxArea) continue
                val ring = DouglasPeuckerSimplifier.simplify(poly.exteriorRing, tol) as? LineString ?: poly.exteriorRing
                val (smooth, odd) = turns(ring)
                // A run of gentle turns is a curve; a lone corner that is not square is an angled cut.
                if (smooth >= 4) work += Work.CURVED_CUT
                val shell = Silhouette.GF.createPolygon(poly.exteriorRing.coordinates).area
                // Rounded corners take a sliver off the box; only a real bite out of a curved part is also a notch.
                // A curved outline is measured against its own hull, so a round or oval top is not a notch.
                val hull = Silhouette.GF.createPolygon(poly.exteriorRing.coordinates).convexHull().area
                if (if (smooth >= 4) shell < 0.97 * hull else (odd > 0 || shell < 0.995 * boxArea)) work += Work.NOTCH
                for (h in 0 until poly.numInteriorRing) {
                    val r = poly.getInteriorRingN(h)
                    val a = Silhouette.GF.createPolygon(r.coordinates).area
                    if (a < 1e-6 * boxArea) continue
                    // A circle encloses the most area for its edge length; anything well short of that is a slot or window.
                    if (4 * Math.PI * a / (r.length * r.length) > 0.93) { work += Work.HOLES; holes += 2 * Math.sqrt(a / Math.PI) } else { work += Work.CUTOUT; cutouts++ }
                }
            }
        }
        // Holes drilled into an edge do not show in the outline.
        runCatching { HoleFinder.find(m) }.getOrDefault(emptyList()).filter { it.principalAxis != 1 }.forEach { work += Work.HOLES; holes += it.diameter }
        // Faces that lean between the broad face and the edge are a round-over or a bevel.
        val n = m.faceNormals; val a = m.faceAreas
        var lean = 0.0
        for (t in 0 until m.triangleCount) { val ny = abs(n[3 * t + 1]); if (ny > 0.2 && ny < 0.98) lean += a[t] }
        if (lean > 0.01 * m.area) work += Work.EDGE_SHAPE
        return ItemWork(p.item ?: 0, work, holes, cutouts)
    }

    /** Gentle turns (part of a curve) and corners that are neither gentle nor square, around a ring. */
    private fun turns(ring: LineString): Pair<Int, Int> {
        val c = ring.coordinates
        val n = c.size - 1
        if (n < 3) return 0 to 0
        var smooth = 0; var odd = 0
        for (i in 0 until n) {
            val a = c[(i + n - 1) % n]; val b = c[i]; val d = c[(i + 1) % n]
            val u = Vec2(b.x - a.x, b.y - a.y).normalized(); val v = Vec2(d.x - b.x, d.y - b.y).normalized()
            val deg = Math.toDegrees(Math.acos((u dot v).coerceIn(-1.0, 1.0)))
            if (deg > 3 && deg < 40) smooth++ else if (deg >= 40 && abs(deg - 90) > 3) odd++
        }
        return smooth to odd
    }

    fun inside(bvh: Bvh, p: Vec3): Boolean {
        // Three rays, majority vote: one ray through a gap in a leaky surface must not decide it.
        var votes = 0
        for (d in DIRS) {
            var hits = 0; var t = 0.0
            while (hits < 64) { val h = bvh.firstHit(p, d, t) ?: break; hits++; t = h.t + 1e-7 }
            if (hits % 2 == 1) votes++
        }
        return votes >= 2
    }

    private val DIRS = listOf(Vec3(0.5377, 0.2954, 0.7897).normalized(), Vec3(-0.3511, 0.8623, -0.3651).normalized(), Vec3(0.1113, -0.5202, -0.8467).normalized())

    /**
     * Whether [small] running [depth] mm into [big] could be a groove (housing) in [big]: the smaller part goes in no
     * deeper than half of [big]'s thickness, so what is left of [big] behind the groove still holds.
     */
    fun housable(small: Part, big: Part, depth: Double): Boolean {
        if (abs(small.mesh.volume) > abs(big.mesh.volume)) return false
        val bt = listOf(big.mesh.extents.x, big.mesh.extents.y, big.mesh.extents.z).min()
        return depth >= 1.0 && depth <= 0.5 * bt
    }

    fun run(parts: List<Part>, rows: List<CutListRow>, fmt: LengthFormat, tools: Set<Tool>?, stock: StockSettings = StockSettings.defaultFor(fmt), housed: List<Pair<String, String>> = emptyList()): CheckReport {
        val housedPairs = housed.map { setOf(it.first, it.second) }.toSet()
        val live = parts.filter { !it.excluded }
        val out = ArrayList<Finding>()
        val d = { v: Double -> fmt.format(v) }
        // Stock thicknesses need the extra place in inches: 1/8 is .125, not .13.
        val ds = { v: Double -> if (fmt.isInch && fmt.inchStyle == "decimal") fmt.copy(decimals = 3).format(v).let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it } else fmt.format(v) }
        val u = fmt.unitLabel
        fun items(ps: List<Part>) = ps.mapNotNull { it.item }.distinct().sorted().let { if (it.size == 1) "Item ${it[0]}" else "Items ${it.joinToString(", ")}" }
        val byItem = live.filter { it.item != null }.groupBy { it.item!! }

        // --- Each item on its own: material and tools. ---
        val work = byItem.entries.sortedBy { it.key }.map { (_, ps) -> runCatching { workFor(ps[0], fmt) }.getOrDefault(ItemWork(ps[0].item ?: 0, emptySet(), emptyList(), 0)) }
        val workOf = work.associateBy { it.item }

        val flatParts = live.filter { it.kind != PartKind.ROD && (it.kind == PartKind.PANEL || it.kind == PartKind.BOARD || it.t < 0.3 * it.w) }
        for ((_, ps) in flatParts.filter { nearestStock(it.t, fmt).size != 1 }.groupBy { Math.round(it.t * 20) }.entries.sortedBy { it.key }) {
            val t = ps[0].t; val near = nearestStock(t, fmt)
            out += Finding(Severity.CHECK, "${d(t)} $u is not a thickness shops stock",
                "${items(ps)} ${if (ps.size == 1) "is" else "are"} ${d(t)} thick. Common stock is ${near.joinToString(" or ") { ds(it) }}. Change the thickness, or plan to plane material down.",
                ps.map { it.id }, near.map { Fix(FixKind.SET_THICKNESS, "Make ${ds(it)} thick", ps.map { p -> p.id }, it) })
        }
        // Shapes drawn as one solid that are really several boards: a frame from a thick slab, an open box, a channel.
        for (p in live.filter { it.kind == PartKind.SHAPED || it.kind == PartKind.PANEL || it.kind == PartKind.BOARD }.distinctBy { it.item }) {
            val ids = live.filter { it.item == p.item }.map { it.id }
            val (b, _) = runCatching { splitziner.edit.Boards.decompose(p.mesh) }.getOrDefault(null to "")
            if (b == null) continue
            val n = b.pieces.size
            val fix = listOf(Fix(FixKind.SPLIT_BOARDS, "Make it from $n boards", ids))
            val list = b.pieces.map { m -> val e = OrientedBounds.of(m).extents; listOf(e.x, e.y, e.z).sorted() }.joinToString("; ") { (a, bb, c) -> "${d(a)} x ${d(bb)} x ${d(c)}" }
            if (!b.flat) out += Finding(Severity.PROBLEM, "Item ${p.item} is ${n} boards drawn as one solid",
                "It measures ${d(p.t)} x ${d(p.w)} x ${d(p.l)} but is mostly empty, like a box or channel whose sides were joined in the 3D program. It cannot be cut from one piece. Built for real it is $n boards: $list.", ids, fix)
            else if (p.t >= 18.5) out += Finding(Severity.PROBLEM, "Item ${p.item} is a frame cut from one ${d(p.t)} $u slab",
                "Only ${Math.round(b.materialShare * 100)}% of its ${d(p.w)} x ${d(p.l)} outline is material, and no shop sells sheet that thick for it. Built for real it is $n pieces: $list. ${b.note.substringAfter(";", "").trim().replaceFirstChar { it.uppercase() }}".trim(), ids, fix)
            else out += Finding(Severity.CHECK, "Item ${p.item} wastes most of the sheet it is cut from",
                "Only ${Math.round(b.materialShare * 100)}% of its ${d(p.w)} x ${d(p.l)} outline is material; the rest is scrap. It can be made from $n strips instead: $list.", ids, fix)
        }
        for (p in live.filter { it.kind == PartKind.SHAPED && it.t >= 0.3 * it.w }.distinctBy { it.item }) {
            if (runCatching { splitziner.edit.Boards.decompose(p.mesh).first }.getOrNull() != null) continue
            val fill = abs(p.mesh.volume) / maxOf(p.t * p.w * p.l, 1e-9)
            val hollow = fill < 0.5
            out += Finding(if (hollow) Severity.PROBLEM else Severity.CHECK,
                if (hollow) "Item ${p.item} is a hollow shape drawn as one piece" else "Item ${p.item} is a solid block, not a board",
                if (hollow) "It measures ${d(p.t)} x ${d(p.w)} x ${d(p.l)} but is mostly empty, and its walls are not square enough to split into boards automatically. Model its sides as separate parts to get a cut list for it."
                else "It measures ${d(p.t)} x ${d(p.w)} x ${d(p.l)} and is not a simple board or rod. It has to be glued up from layers and shaped, machined, or printed.",
                live.filter { it.item == p.item }.map { it.id })
        }
        for (p in flatParts.distinctBy { it.item }) {
            val fitsSheet = (p.w <= stock.sheetW + 0.5 && p.l <= stock.sheetL + 0.5) || (p.l <= stock.sheetW + 0.5 && p.w <= stock.sheetL + 0.5)
            val isBoard = p.kind == PartKind.BOARD
            if (!isBoard && !fitsSheet) out += Finding(Severity.PROBLEM, "Item ${p.item} is bigger than a sheet",
                "It needs ${d(p.w)} x ${d(p.l)}; your sheets are ${d(stock.sheetW)} x ${d(stock.sheetL)}. It must be made from two pieces joined, or the design made smaller." +
                    // A long, deep, thick top over a run of cabinets is what worktops are sold as.
                    (if (p.name == "Top" && p.t >= 25.0) " Worktops (laminate, solid wood or butcher block) are sold in long lengths about 25 in (600 mm) deep: buy one and cut it to length." else ""), live.filter { it.item == p.item }.map { it.id })
            if (isBoard && p.l > stock.boardLengths.last() + 0.5) out += Finding(Severity.PROBLEM, "Item ${p.item} is longer than your boards",
                "It is ${d(p.l)} long; boards come in lengths up to ${d(stock.boardLengths.last())}.", live.filter { it.item == p.item }.map { it.id })
        }

        // --- Tools. ---
        val needs = LinkedHashMap<Work, MutableList<Int>>()
        for (w in work) for (k in w.work) needs.getOrPut(k) { ArrayList() } += w.item
        val toolsNeeded = ArrayList<String>()
        for ((k, its) in needs.entries.sortedBy { it.key.ordinal }) {
            // Odd thicknesses and solid blocks have findings of their own above; here they only add to the tool list.
            if (k == Work.THICKNESS || k == Work.CARVE) {
                if (tools != null && tools.any { it in k.with }) toolsNeeded += "${tools.filter { it in k.with }.joinToString(" or ") { it.label }} for ${k.label}"
                continue
            }
            val who = (if (its.size == 1) "Item " else "Items ") + its.joinToString(", ")
            val ids = live.filter { it.item in its }.map { it.id }
            val can = k.with.joinToString(", ") { it.label.lowercase() }
            val extra = when (k) {
                Work.HOLES -> work.flatMap { it.holeDiameters }.map { d(it) }.distinct().let { if (it.isEmpty()) "" else " Bit sizes: ${it.joinToString(", ")}." }
                Work.CUTOUT -> " Start each opening from a drilled hole."
                else -> ""
            }
            // Squaring a part up removes its curves, notches and edge shapes: an honest way out when the tool is missing.
            val squarable = k == Work.CURVED_CUT || k == Work.NOTCH || k == Work.EDGE_SHAPE
            val fix = if (squarable) listOf(Fix(FixKind.SQUARE_UP, "Square these parts up", ids)) else emptyList()
            when {
                tools == null -> { toolsNeeded += "${k.label}: $can"; }
                tools.any { it in k.with } -> toolsNeeded += "${tools.filter { it in k.with }.joinToString(" or ") { it.label }} for ${k.label}"
                tools.any { it in k.slowly } -> {
                    val have = tools.filter { it in k.slowly }.joinToString(" or ") { it.label.lowercase() }
                    toolsNeeded += "${tools.filter { it in k.slowly }.joinToString(" or ") { it.label }} for ${k.label} (slow)"
                    out += Finding(Severity.CHECK, "Your tools can manage ${k.label}, but only just", "$who need ${k.label}. Your $have will do it slowly and less accurately. Better: $can.$extra", ids, fix)
                }
                else -> out += Finding(Severity.PROBLEM, "No tool for ${k.label}", "$who need ${k.label}, and nothing in your tool list does that. Any of these would: $can.$extra", ids, fix)
            }
        }
        if (tools == null && needs.isNotEmpty())
            out += Finding(Severity.NOTE, "Choose your tools", "Tell the app which tools you have (Tools, on this tab) and it will check every part against them. This build involves: ${needs.keys.joinToString(", ") { it.label }}.")

        // --- Parts against each other. ---
        val n = live.size
        val rel = relations(live)
        val lo = rel.lo; val hi = rel.hi; val touching = rel.touching; val clashes = rel.clashes
        // Many clashes of the same two items (a pair of drawers in a pair of slides) are told once.
        for ((_, cs) in clashes.filter { setOf(live[it.a].id, live[it.b].id) !in housedPairs }.groupBy { setOf(live[it.a].item, live[it.b].item) }) {
            val c = cs.maxByOrNull { it.depth }!!
            val pa = live[c.a]; val pb = live[c.b]
            // A few millimetres is usually a part drawn slightly too long; more than that changes how the thing goes together.
            val slight = c.depth < 3.5
            val same = pa.item == pb.item
            // Offer to cut either one back to the other's face, where that is possible, saying how much each loses.
            val cutFixes = listOfNotNull(
                runCatching { splitziner.edit.Editor.cutBack(pa, pb) }.getOrNull()?.takeIf { it.second <= MAX_CUT_BACK }?.let { Fix(FixKind.CUT_BACK, "Cut item ${pa.item} back (${Math.round(it.second * 100).coerceAtLeast(1)}% of it)", listOf(pa.id, pb.id)) },
                runCatching { splitziner.edit.Editor.cutBack(pb, pa) }.getOrNull()?.takeIf { it.second <= MAX_CUT_BACK }?.let { Fix(FixKind.CUT_BACK, "Cut item ${pb.item} back (${Math.round(it.second * 100).coerceAtLeast(1)}% of it)", listOf(pb.id, pa.id)) },
            )
            // Or keep it as drawn and cut a groove for it: the overlap is then the joint.
            val (small, big) = if (abs(pa.mesh.volume) <= abs(pb.mesh.volume)) pa to pb else pb to pa
            val houseFix = if (!slight && housable(small, big, c.depth)) listOf(Fix(FixKind.HOUSE, "Keep it: groove item ${big.item} for item ${small.item}", cs.map { if (abs(live[it.a].mesh.volume) <= abs(live[it.b].mesh.volume)) live[it.a].id to live[it.b].id else live[it.b].id to live[it.a].id }.flatMap { listOf(it.first, it.second) })) else emptyList()
            out += Finding(if (slight) Severity.CHECK else Severity.PROBLEM,
                (if (same) "Two of item ${pa.item}" else "Items ${minOf(pa.item ?: 0, pb.item ?: 0)} and ${maxOf(pa.item ?: 0, pb.item ?: 0)}") + (if (slight) " overlap slightly" else " occupy the same space"),
                "They overlap by about ${d(c.depth)} $u" + (if (cs.size > 1) " in ${cs.size} places" else "") + (if (slight) ". Probably one was drawn a touch too long; trim it to fit when building." else ". Two solid parts cannot do that. If it is meant as a joint (a tenon, a housing), one part needs a matching recess; otherwise one of them must be shortened or moved."),
                cs.flatMap { listOf(live[it.a].id, live[it.b].id) }.distinct(), houseFix + cutFixes)
        }
        // Drawers run on slides, which are hardware and not in the model: a drawer box and the front fixed to it
        // float as drawn, and that is right.
        val drawers = runCatching { Drawers.find(live) }.getOrDefault(emptyList())
        val onSlides = HashSet<Int>()
        run {
            val idx = live.indices.associateBy { live[it].id }
            for (dr in drawers) for (g in rel.groups) if (g.any { live[it].id in dr.ids }) onSlides += g
            for (dr in drawers) dr.ids.mapNotNull { idx[it] }.forEach { onSlides += it }
        }
        // Identical drawers (same items, same depth) are told once.
        for ((key, drs) in drawers.groupBy { dr -> live.filter { it.id in dr.ids }.mapNotNull { it.item }.distinct().sorted() to Math.round(Drawers.depthOf(live, dr)) }) {
            val its = key.first; val dr = drs[0]; val n = drs.size
            val depth = Drawers.depthOf(live, dr)
            val slide = Drawers.slideLength(depth, fmt.isInch)
            out += Finding(Severity.NOTE, (if (n == 1) "Drawer" else "$n drawers") + " on slides (items ${its.joinToString(", ")})",
                "Items ${its.joinToString(", ")} make ${if (n == 1) "a drawer. It runs" else "$n drawers, all the same. Each runs"} on a pair of slides, which are hardware and not in the model" + (if (slide != null) ": $slide slides fit the box's depth of ${d(depth)} $u." else ".") + " Fit them following the slides' own instructions.", drs.flatMap { it.ids })
        }
        // Doors hang on hinges, which are hardware too.
        val doors = runCatching { Doors.find(live) }.getOrDefault(emptyList())
        run { val idx = live.indices.associateBy { live[it].id }; for (dr in doors) idx[dr.id]?.let { onSlides += it } }
        for ((it0, ds) in doors.groupBy { dr -> live.first { it.id == dr.id }.item }) {
            val n = ds.size; val hinges = Doors.hinges(ds[0].height)
            out += Finding(Severity.NOTE, if (n == 1) "Door (item $it0)" else "$n doors (item $it0)",
                (if (n == 1) "Item $it0 is a door. It hangs on $hinges hinges" else "Item $it0 is $n doors. Each hangs on $hinges hinges") +
                    ", which are hardware and not in the model. Concealed (cup) hinges need a 35 mm hole in the back of the door; follow the hinges' own template.", ds.map { it.id })
        }
        // A very wide door pulls on its hinges and swings far out into the room: cabinetmakers split it in two.
        for (dr in doors) {
            val p = live.first { it.id == dr.id }; val w = maxOf(p.mesh.bounds.extents.x, p.mesh.bounds.extents.y)
            if (w > Doors.MAX_WIDTH) out += Finding(Severity.CHECK, "Wide door (item ${p.item})",
                "Item ${p.item} is a door ${d(w)} $u wide. Doors much over ${if (fmt.isInch) "24 in" else "600 mm"} wide tend to sag on their hinges and swing a long way out; a pair of doors meeting in the middle is the usual answer (a rule of thumb, not a calculation).", listOf(p.id))
        }
        // Adjustable shelves rest on shelf pins, hardware again.
        val pinned = runCatching { ShelfPins.find(live) }.getOrDefault(emptyList())
        run { val idx = live.indices.associateBy { live[it].id }; for (id in pinned) idx[id]?.let { onSlides += it } }
        for ((it0, ids) in pinned.groupBy { id -> live.first { it.id == id }.item }) {
            val many = ids.size > 1
            out += Finding(Severity.NOTE, if (many) "${ids.size} adjustable shelves (item $it0)" else "Adjustable shelf (item $it0)",
                (if (many) "Item $it0 is ${ids.size} shelves that each rest" else "Item $it0 is a shelf that rests") + " on ${ShelfPins.PINS} shelf pins, which are hardware and not in the model. " +
                    "Drill two rows of holes in the inside of each upright, ${ShelfPins.SETBACK.toInt()} mm in from the front and back edges and ${ShelfPins.PITCH.toInt()} mm apart (the common 32 mm system, which shelf-pin jigs are made for, in inch shops too), " +
                    "the size the pins need (5 mm is the most common) and no deeper than the pins. A jig or a pegboard offcut keeps the rows level.", ids)
        }
        if (n > 1) {
            val alone = (0 until n).filter { touching[it].isEmpty() && it !in onSlides }
            if (alone.isNotEmpty()) out += Finding(Severity.PROBLEM, "${alone.size} part${if (alone.size == 1) " touches" else "s touch"} nothing",
                "${items(alone.map { live[it] })}: no other part is within ${d(TOUCH)} $u. In the model ${if (alone.size == 1) "it floats" else "they float"} in the air; built for real ${if (alone.size == 1) "it needs" else "they need"} something to hold ${if (alone.size == 1) "it" else "them"}.",
                alone.map { live[it].id })
            // Groups of parts joined to each other but to nothing else.
            val groups = rel.groups
            val islands = groups.filter { it.size > 1 }.sortedByDescending { it.size }.drop(1).filter { g -> g.none { it in onSlides } }
            for (g in islands) out += Finding(Severity.PROBLEM, "${g.size} parts are joined to each other but not to the rest",
                "${items(g.map { live[it] })} touch each other and nothing else, so as drawn they are a separate object.", g.map { live[it].id })
        }

        // --- The whole thing. ---
        if (live.isNotEmpty()) {
            val floor = lo.minOf { it.z }
            // Weight follows volume times the typical density of each item's material when every item has a known one;
            // otherwise every part is taken as the same material. Good enough to spot a top-heavy design.
            val rho = rows.associate { it.item to splitziner.material.Materials.density(it.material) }
            val byMaterial = live.all { p -> p.item != null && rho[p.item] != null }
            val basis = if (byMaterial) "using each item's material" else "taking every part as the same material"
            var m = 0.0; var cx = 0.0; var cy = 0.0
            for (p in live) { val v = (if (p.watertight) abs(p.mesh.volume) else p.t * p.w * p.l * 0.5) * (if (byMaterial) rho[p.item]!! else 1.0); val c = p.mesh.bounds.center; m += v; cx += v * c.x; cy += v * c.y }
            val feet = ArrayList<Vec2>()
            for (p in live) if (p.mesh.bounds.min.z <= floor + 2.0) for (v in 0 until p.mesh.vertexCount) if (p.mesh.vertices[3 * v + 2] <= floor + 2.0) feet += Vec2(p.mesh.vertices[3 * v], p.mesh.vertices[3 * v + 1])
            val hull = OrientedBounds.convexHull(feet)
            if (m > 0 && hull.size >= 3) {
                val c = Vec2(cx / m, cy / m)
                // Distance from the balance point to the nearest edge of the footprint; negative when outside.
                var margin = Double.MAX_VALUE
                for (k in hull.indices) {
                    val p = hull[k]; val q = hull[(k + 1) % hull.size]
                    val e = (q - p).normalized()
                    margin = minOf(margin, e.x * (c.y - p.y) - e.y * (c.x - p.x))
                }
                val size = Math.hypot(hi.maxOf { it.x } - lo.minOf { it.x }, hi.maxOf { it.y } - lo.minOf { it.y })
                // Made to hang (a back board carrying pegs or a shelf on brackets): it is not meant to stand at all.
                // Or it touches the floor only along a strip at its back (a picture ledge's back board): a wall piece.
                val yMin = lo.minOf { it.y }; val yMax = hi.maxOf { it.y }; val depth = yMax - yMin
                val strip = feet.isNotEmpty() && depth <= splitziner.parts.Roles.WALL_DEPTH && feet.maxOf { it.y } >= yMax - 1.0 && feet.maxOf { it.y } - feet.minOf { it.y } <= maxOf(40.0, 0.25 * depth)
                val hangs = live.any { it.name == "Back board" } || live.any { it.name == "Wall cleat" || it.name == "Hanging rail" } || strip
                if (margin < 0 && hangs) { val bb = live.firstOrNull { it.name == "Back board" }
                    out += Finding(Severity.CHECK, "Hang it on the wall", "It is made to hang, not to stand. Screw ${bb?.let { "the back board (item ${it.item})" } ?: "it"} to the wall into the studs, " +
                        "two ${if (fmt.isInch) "#10 x 3 in" else "5 x 75 mm"} screws at each stud, or into masonry with plugs made for it. Plasterboard anchors alone are only for light loads: coats and bags pull hard.", listOfNotNull(bb?.id)) }
                else if (margin < 0) out += Finding(Severity.PROBLEM, "It would tip over", "The balance point is ${d(-margin)} $u outside the area its feet cover ($basis). It needs a wider base, a counterweight, or fixing to a wall.")
                else if (margin < 0.04 * size) out += Finding(Severity.CHECK, "It stands, but close to tipping", "The balance point is only ${d(margin)} $u inside the edge of its footprint ($basis). A push or a load on the overhang could tip it.")
            } else if (hull.size < 3) out += Finding(Severity.CHECK, "It rests on a line or a point", "The parts that reach the floor do not make an area to stand on.")
        }
        // Gaps between spindles: a child's head must not pass. The widest clear gap, measured along the row.
        run {
            val sp = live.filter { it.name == "Spindle" }
            if (sp.size < 3) return@run
            var widest = 0.0
            for (g in sp.groupBy { Math.round(it.mesh.bounds.min.z) to Math.round(it.mesh.bounds.max.z) }.values) {
                if (g.size < 2) continue
                val b = g.map { it.mesh.bounds }
                val ax = (0..1).maxByOrNull { k -> b.maxOf { it.center[k] } - b.minOf { it.center[k] } }!!
                val sorted = b.sortedBy { it.min[ax] }
                for ((x, y) in sorted.zipWithNext()) if (kotlin.math.abs(x.min[1 - ax] - y.min[1 - ax]) < 1.0) widest = maxOf(widest, y.min[ax] - x.max[ax])
            }
            if (widest > CRIB_GAP) out += Finding(Severity.CHECK, "Spindles ${d(widest)} $u apart",
                "The widest gap between the spindles is ${d(widest)} $u. If this is a crib, the gaps must be no more than ${d(CRIB_GAP)} $u (2-3/8 in, 6 cm, the US crib rule) so a baby's head cannot pass through." +
                    (if (widest > RAIL_GAP) " On a stair or balcony railing, the usual building-code limit is 4 in (10 cm)." else ""))
        }
        // A top bunk's guard rails must stand well above the mattress, so a sleeper cannot roll over them.
        run {
            val guards = live.filter { it.name == "Guard rail" }; if (guards.isEmpty()) return@run
            val top = guards.maxOf { it.mesh.bounds.max.z }
            val deck = live.filter { it.name == "Slat" && it.mesh.bounds.max.z < top }.maxOfOrNull { it.mesh.bounds.max.z } ?: return@run
            val above = top - deck
            val room = above - GUARD_ABOVE_MATTRESS
            out += Finding(Severity.CHECK, "Mattress for the top bunk: ${if (room > 0) "no thicker than ${d(room)} $u" else "the guard rails are too low"}",
                "The guard rails stand ${d(above)} $u above the slats. US bunk bed rules ask for the top of the guard rails to be at least 5 in (127 mm) above the top of the mattress, so a sleeper cannot roll over them" +
                    (if (room > 0) ": use a mattress no thicker than ${d(room)} $u." else ". Raise the guard rails." ) +
                    (if (room in 0.0..MIN_MATTRESS) " That is thinner than most mattresses: raise the guard rails." else ""))
        }
        // A lid that can fall shut traps fingers, and in a toy box can trap a child.
        if (live.any { it.name == "Lid" }) out += Finding(Severity.CHECK, "Hold the lid open",
            "A lid that can drop shut traps fingers, and a child leaning into a toy box can be caught under it. Fit a lid support that holds the lid open at any angle (not a plain chain or a stay that only locks fully open), and in a toy box leave a gap or air holes so a child shut inside can breathe.")
        // Tall, shallow furniture can be pulled over (by a child climbing it, or a drawer pulled out): anchor it.
        if (live.isNotEmpty()) run {
            val all = live.map { it.mesh.bounds }
            val h = all.maxOf { it.max.z } - all.minOf { it.min.z }; val dd = all.maxOf { it.max.y } - all.minOf { it.min.y }
            if (live.any { it.name == "Hanging rail" }) out += Finding(Severity.CHECK, "Hang it from the studs",
                "It has a hanging rail across the back: it is screwed to the wall through that rail, into the studs (or with plugs made for masonry). Plasterboard alone will not hold a loaded cabinet.")
            // A crate (battens across a lid) is packing, not furniture: it is not stood in a room.
            else if (h > 760.0 && h > 2.0 * dd && live.none { it.name == "Batten" }) out += Finding(Severity.CHECK, "Fix it to the wall",
                "It is ${d(h)} $u tall and only ${d(dd)} $u deep. Furniture this shape can be pulled over, by a child climbing it or a heavy drawer pulled out. Fix it to the wall with an anti-tip strap or bracket into a stud." +
                    // Nothing that pulls out: it may stand away from any wall (a display plinth), so weight is the other answer.
                    (if (live.none { it.name in setOf("Door", "Drawer front", "Sliding door") || it.name.startsWith("Drawer") || it.name.startsWith("Door") })
                        " If it has to stand away from a wall (a display plinth, a room divider), weigh its base down instead: a shelf low inside loaded with sandbags or bricks keeps it from tipping." else ""))
        }
        // A case (two upright sides) with no back panel racks: pushed sideways, it leans like a parallelogram.
        if (live.size >= 3) run {
            val all = live.map { it.mesh.bounds }
            val mx0 = all.minOf { it.min.x }; val mx1 = all.maxOf { it.max.x }; val my0 = all.minOf { it.min.y }; val my1 = all.maxOf { it.max.y }
            val mz0 = all.minOf { it.min.z }; val mz1 = all.maxOf { it.max.z }
            val w = mx1 - mx0; val h = mz1 - mz0; val dd = my1 - my0
            fun upright(i: Int, thinAxis: Int): Boolean { val e = all[i].extents; val t = listOf(e.x, e.y, e.z)[thinAxis]; return t < 0.25 * minOf(listOf(e.x, e.y, e.z).filterIndexed { k, _ -> k != thinAxis }.min(), 1e9) }
            val sides = live.indices.filter { upright(it, 0) && all[it].extents.z > 0.7 * h && all[it].extents.y > 0.5 * dd }
            val leftSide = sides.any { all[it].min.x <= mx0 + 0.1 * w }; val rightSide = sides.any { all[it].max.x >= mx1 - 0.1 * w }
            if (!(leftSide && rightSide) || h < 500.0) return@run
            // A back: thin front to back, covering most of the back's area.
            val backArea = live.indices.filter { upright(it, 1) }.sumOf { all[it].extents.x * all[it].extents.z }
            if (backArea >= 0.5 * w * h) return@run
            // A wide rail or modesty panel across the back, fixed to both sides, braces it as well.
            if (live.indices.any { upright(it, 1) && all[it].extents.x >= 0.85 * (w - 2 * 25.0) && all[it].extents.z >= 0.2 * h }) return@run
            out += Finding(Severity.CHECK, "It may rack (lean sideways)",
                "Its sides stand on their own with no back panel. Pushed from the side, a case like this leans over like a parallelogram unless something holds it square. A back of 1/4 in (6 mm) sheet fixed all round, a diagonal brace, or a wide rail glued and screwed at the top and bottom of the back stops it.")
        }
        // Drawers need room at their sides for slides or runners.
        // Identical drawers with the same room are told once.
        val tight = drawers.filter { minOf(it.left, it.right) < Drawers.SLIDE_GAP }
        for ((key, drs) in tight.groupBy { dr -> Triple(live.filter { it.id in dr.ids }.mapNotNull { it.item }.distinct().sorted(), Math.round(dr.left * 10), Math.round(dr.right * 10)) }) {
            val its = key.first; val dr = drs[0]; val n = drs.size
            val (u2, k) = if (fmt.isInch) "1/2 in" to 12.7 else "12.5 mm" to 12.5
            out += Finding(Severity.CHECK, "No room for drawer slides (items ${its.joinToString(", ")}${if (n > 1) ", $n drawers" else ""})",
                "Items ${its.joinToString(", ")} look like ${if (n == 1) "a drawer box" else "$n drawer boxes"}, with ${d(dr.left)} $u on the left and ${d(dr.right)} $u on the right. Ball-bearing slides, the usual kind, need about $u2 each side, so ${if (n == 1) "the box" else "each box"} would be ${d(2 * k - dr.left - dr.right)} $u narrower. Wooden runners or a drawer that slides on its bottom need only a little play. Decide which before cutting the drawer parts.",
                drs.flatMap { it.ids })
        }
        val sagging = HashSet<Int?>()
        for ((i, p) in live.withIndex()) {
            if (p.kind != PartKind.PANEL && p.kind != PartKind.BOARD) continue
            // A shelf on pins touches nothing in the model but is held at both ends.
            val onPins = p.id in pinned
            if (p.item in sagging || (touching[i].isEmpty() && !onPins)) continue
            // Lying flat (its thickness is vertical): how far does it run between the parts that hold it?
            val box = OrientedBounds.of(p.mesh)
            val k = (0..2).minByOrNull { box.extents[it] }!!
            if (abs(box.axes[k].z) < 0.95) continue
            val axis = if (hi[i].x - lo[i].x >= hi[i].y - lo[i].y) 0 else 1
            val a0 = lo[i][axis]; val a1 = hi[i][axis]
            val held = if (onPins) listOf(a0 to a0, a1 to a1) else touching[i].map { j -> maxOf(lo[j][axis], a0) to minOf(hi[j][axis], a1) }.filter { it.second >= it.first }.sortedBy { it.first }
            var span = 0.0; var reach = a0
            // Overhang past the last support counts double: a cantilever bends like a span twice as long.
            if (held.isNotEmpty()) span = 2 * (held[0].first - a0)
            for ((from, to) in held) { span = maxOf(span, from - reach); reach = maxOf(reach, to) }
            span = maxOf(span, 2 * (a1 - reach))
            // A bed's slats over a span this wide want a centre rail on legs, not thicker slats: the usual practice
            // for a full or larger bed, and what mattress makers' warranties ask for.
            if (p.name == "Slat" && span > BED_CENTRE && live.any { it.name == "Side rail" || it.name == "Cleat" }) {
                sagging += p.item
                out += Finding(Severity.CHECK, "The slats need a centre support", "The slats run about ${d(span)} $u from side to side with nothing under the middle. A bed this wide (full size or larger) " +
                    "needs a centre rail running head to foot under the slats, with at least one leg down to the floor; most mattress warranties ask for one. It halves the slats' span.",
                    live.filter { it.item == p.item }.map { it.id })
                continue
            }
            if (span > SAG_RATIO * p.t) {
                sagging += p.item
                // The thinnest stock board that brings the span within the rule, offered as a one-tap change.
                val need = stockThicknesses(fmt).filter { it * SAG_RATIO >= span && it > p.t }.minOrNull()
                val ids = live.filter { it.item == p.item }.map { it.id }
                out += Finding(Severity.CHECK, "Item ${p.item} may sag", "It lies flat and runs about ${d(span)} $u between supports at ${d(p.t)} thick: ${Math.round(span / p.t)} times its thickness. As a rule of thumb a loaded shelf wants support at least every ${SAG_RATIO.toInt()} thicknesses. The app cannot tell how much it will carry." +
                    (if (need != null) " Making it ${d(need)} $u thick, or adding a support in the middle, keeps it within the rule." else " Add a support in the middle."),
                    ids, if (need != null) listOf(Fix(FixKind.SET_THICKNESS, "Make it ${d(need)} $u thick", ids, need)) else emptyList())
            }
        }
        val leaky = live.filter { !it.watertight }
        if (leaky.isNotEmpty()) out += Finding(Severity.NOTE, "${leaky.size} part${if (leaky.size == 1) " has" else "s have"} gaps in the surface",
            "${items(leaky)}. The 3D file leaves small holes in these surfaces, so their sizes are read from the outside and their weight is estimated. The drawings are not affected.", leaky.map { it.id })
        out += Finding(Severity.NOTE, "Joint strength is not checked", "The build guide says how each joint is fixed (choose screws, pocket screws, dowels or brads under More > Joining on the Guide tab), sized by workshop rules of thumb. Nothing here calculates whether a joint is strong enough for the load it will carry.")
        return CheckReport(out.sortedBy { it.severity.ordinal }, work, toolsNeeded)
    }

    class Clash(val a: Int, val b: Int, val depth: Double)

    /** How the parts sit against each other: who touches whom, who overlaps whom, and which groups hang together. */
    class Relations(val lo: List<Vec3>, val hi: List<Vec3>, val touching: Array<HashSet<Int>>, val clashes: List<Clash>, val groups: List<List<Int>>) {
        /** Groups other than the largest: parts of the model that are not joined to its main body. */
        val islands: List<List<Int>> get() = groups.sortedByDescending { it.size }.drop(1)
    }

    /**
     * What is known about one pair of meshes: clash depth (or -1 for none) and whether they touch. A caller
     * that changes a few parts at a time (the fixer) passes the same memo, so unchanged pairs are not tested again.
     */
    class PairMemo { internal val map = HashMap<Long, DoubleArray>(); internal val ids = java.util.IdentityHashMap<Mesh, Int>() }

    fun relations(live: List<Part>, memo: PairMemo? = null): Relations {
        val n = live.size
        val bvh = live.map { it.mesh.bvh }
        val lo = live.map { it.mesh.bounds.min }; val hi = live.map { it.mesh.bounds.max }
        val touching = Array(n) { HashSet<Int>() }
        val clashes = ArrayList<Clash>()
        fun idOf(m: Mesh): Int = memo!!.ids.getOrPut(m) { memo.ids.size }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (!(0..2).all { lo[i][it] <= hi[j][it] + TOUCH && lo[j][it] <= hi[i][it] + TOUCH }) continue
            val key = if (memo != null) { val a = idOf(live[i].mesh); val b = idOf(live[j].mesh); minOf(a, b).toLong() * 1_000_000 + maxOf(a, b) } else 0L
            memo?.map?.get(key)?.let { r ->
                if (r[0] >= 0) clashes += Clash(i, j, r[0])
                if (r[1] > 0) { touching[i] += j; touching[j] += i }
                null
            } ?: run pair@{
            // The space both boxes share, pulled in a little so faces that merely touch do not count.
            val a = Vec3(maxOf(lo[i].x, lo[j].x), maxOf(lo[i].y, lo[j].y), maxOf(lo[i].z, lo[j].z))
            val b = Vec3(minOf(hi[i].x, hi[j].x), minOf(hi[i].y, hi[j].y), minOf(hi[i].z, hi[j].z))
            val ext = b - a
            var both = 0
            // Sample points about every 6 mm, so a small overlap in a long shared space is not stepped over.
            val gx = Math.ceil(ext.x / 6.0).toInt().coerceIn(4, 20); val gy = Math.ceil(ext.y / 6.0).toInt().coerceIn(4, 20); val gz = Math.ceil(ext.z / 6.0).toInt().coerceIn(4, 20)
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = minX; var maxY = maxX; var minZ = minX; var maxZ = maxX
            if (ext.x > CLASH_MIN && ext.y > CLASH_MIN && ext.z > CLASH_MIN) {
                val pad = CLASH_MIN / 3
                for (x in 0 until gx) for (y in 0 until gy) for (z in 0 until gz) {
                    val p = Vec3(a.x + pad + (ext.x - 2 * pad) * (x + 0.5) / gx, a.y + pad + (ext.y - 2 * pad) * (y + 0.5) / gy, a.z + pad + (ext.z - 2 * pad) * (z + 0.5) / gz)
                    if (inside(bvh[i], p) && inside(bvh[j], p)) {
                        both++
                        minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x); minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y); minZ = minOf(minZ, p.z); maxZ = maxOf(maxZ, p.z)
                    }
                }
            }
            if (both >= 2) {
                // Depth of the clash: the thinnest way through the shared space, to the nearest sample spacing.
                val depth = minOf(maxX - minX + ext.x / gx, maxY - minY + ext.y / gy, maxZ - minZ + ext.z / gz)
                clashes += Clash(i, j, depth); touching[i] += j; touching[j] += i
                memo?.map?.put(key, doubleArrayOf(depth, 1.0))
            } else if (facesMeet(bvh[i], bvh[j], a, b) || near(live[i], bvh[j]) || near(live[j], bvh[i])) { touching[i] += j; touching[j] += i; memo?.map?.put(key, doubleArrayOf(-1.0, 1.0)) }
            else memo?.map?.put(key, doubleArrayOf(-1.0, 0.0))
            }
        }
        val seen = BooleanArray(n); val groups = ArrayList<List<Int>>()
        for (s in 0 until n) {
            if (seen[s]) continue
            val g = ArrayList<Int>(); val q = ArrayDeque<Int>(); q += s; seen[s] = true
            while (q.isNotEmpty()) { val x = q.removeFirst(); g += x; for (y in touching[x]) if (!seen[y]) { seen[y] = true; q += y } }
            groups += g
        }
        return Relations(lo, hi, touching, clashes, groups)
    }

    /** How far [group] can travel along [d] before it meets one of [others] (mm); null if nothing is in the way. */
    /** The nearest part in each of the six directions from [p], and the clear space to it (mm): for reading fits in 3D. */
    class Clearance(val direction: String, val gap: Double, val item: Int?, val id: String)

    fun clearances(p: Part, parts: List<Part>, maxGap: Double = 600.0): List<Clearance> {
        val a = p.mesh.bounds
        val out = ArrayList<Clearance>()
        val dirs = listOf("left" to Vec3(-1.0, 0.0, 0.0), "right" to Vec3(1.0, 0.0, 0.0), "front" to Vec3(0.0, -1.0, 0.0), "back" to Vec3(0.0, 1.0, 0.0), "below" to Vec3(0.0, 0.0, -1.0), "above" to Vec3(0.0, 0.0, 1.0))
        for ((name, d) in dirs) {
            val k = if (d.x != 0.0) 0 else if (d.y != 0.0) 1 else 2
            val cands = parts.filter { q -> q.id != p.id && !q.excluded }.filter { q ->
                val b = q.mesh.bounds
                (0..2).all { ax -> ax == k || (b.min[ax] < a.max[ax] && b.max[ax] > a.min[ax]) } &&
                    (if (d[k] > 0) b.min[k] >= a.max[k] - 1.0 && b.min[k] - a.max[k] <= maxGap else b.max[k] <= a.min[k] + 1.0 && a.min[k] - b.max[k] <= maxGap)
            }
            var best: Clearance? = null
            for (q in cands.sortedBy { q -> if (d[k] > 0) q.mesh.bounds.min[k] else -q.mesh.bounds.max[k] }.take(6)) {
                val g = gap(listOf(p), listOf(q), d) ?: continue
                if (g <= maxGap && (best == null || g < best.gap)) best = Clearance(name, g, q.item, q.id)
            }
            best?.let { out += it }
        }
        return out
    }

    fun gap(group: List<Part>, others: List<Part>, d: Vec3): Double? {
        val bvh = others.map { Bvh(it.mesh) }
        var best: Double? = null
        for (p in group) {
            val m = p.mesh; val stride = maxOf(1, m.vertexCount / 2000)
            var i = 0
            while (i < m.vertexCount) {
                val o = m.vertex(i)
                for (b in bvh) { val h = b.firstHit(o, d, -1e-9) ?: continue; if (best == null || h.t < best) best = h.t }
                i += stride
            }
            val c = m.faceCenters; val fs = maxOf(1, m.triangleCount / 2000)
            var t = 0
            while (t < m.triangleCount) {
                val o = Vec3(c[3 * t], c[3 * t + 1], c[3 * t + 2])
                for (b in bvh) { val h = b.firstHit(o, d, -1e-9) ?: continue; if (best == null || h.t < best) best = h.t }
                t += fs
            }
        }
        return best
    }

    /** True when some point of [a]'s surface lies within [TOUCH] of the other part's surface. */
    /**
     * Two faces lying against each other where their boxes cross (a slat across the middle of a rail): the shared
     * box is a thin slab, and just either side of it one part is inside and the other is. [near] samples each
     * part's own faces at their corners and middles, which a crossing in the middle of both can fall between.
     */
    private fun facesMeet(bi: Bvh, bj: Bvh, a: Vec3, b: Vec3): Boolean {
        val ext = b - a
        val k = (0..2).firstOrNull { ax -> ext[ax] <= 2 * TOUCH && (0..2).filter { it != ax }.all { ext[it] > 1.0 } } ?: return false
        val (u, v) = (0..2).filter { it != k }
        val mid = (a[k] + b[k]) / 2
        for (iu in 0 until 4) for (iv in 0 until 4) {
            val pu = a[u] + ext[u] * (iu + 0.5) / 4; val pv = a[v] + ext[v] * (iv + 0.5) / 4
            fun at(off: Double): Vec3 { val arr = DoubleArray(3); arr[k] = mid + off; arr[u] = pu; arr[v] = pv; return Vec3(arr[0], arr[1], arr[2]) }
            val lo = at(-TOUCH); val hi = at(TOUCH)
            if ((inside(bi, lo) && inside(bj, hi)) || (inside(bj, lo) && inside(bi, hi))) return true
        }
        return false
    }

    private fun near(a: Part, other: Bvh): Boolean {
        val m = a.mesh
        val c = m.faceCenters; val nr = m.faceNormals
        val stride = maxOf(1, m.triangleCount / 3000)
        var t = 0
        while (t < m.triangleCount) {
            // From just behind the face, look out along its normal: a neighbour's face lying against it is hit at once.
            val nrm = Vec3(nr[3 * t], nr[3 * t + 1], nr[3 * t + 2])
            for (k in 0..3) {
                val p = if (k == 3) Vec3(c[3 * t], c[3 * t + 1], c[3 * t + 2]) else m.vertex(m.triangles[3 * t + k]).let { v -> v + (Vec3(c[3 * t], c[3 * t + 1], c[3 * t + 2]) - v) * 0.02 }
                val h = other.firstHit(p - nrm * TOUCH, nrm, -1e-9)
                if (h != null && h.t <= 2 * TOUCH) return true
            }
            t += stride
        }
        // A plain board can meet a sloping face along a line only (a ledge across leaning easel legs): look out from
        // points all along each edge of its faces too, every ~10 mm. Only for simple shapes, where it is cheap.
        if (m.triangleCount <= EDGE_SAMPLE_TRIS) for (tt in 0 until m.triangleCount) {
            val nrm = Vec3(nr[3 * tt], nr[3 * tt + 1], nr[3 * tt + 2])
            val ctr = Vec3(c[3 * tt], c[3 * tt + 1], c[3 * tt + 2])
            for (e in 0..2) {
                val v0 = m.vertex(m.triangles[3 * tt + e]); val v1 = m.vertex(m.triangles[3 * tt + (e + 1) % 3])
                val steps = ((v1 - v0).length / 10.0).toInt().coerceIn(1, 200)
                for (q in 1 until steps) {
                    // Pulled half a millimetre inside the face, not a share of it: on a long face a share is far from the edge.
                    val p = (v0 + (v1 - v0) * (q.toDouble() / steps)).let { e0 -> (ctr - e0).let { d -> if (d.length < 1e-9) e0 else e0 + d.normalized() * minOf(0.5, d.length / 2) } }
                    val h = other.firstHit(p - nrm * TOUCH, nrm, -1e-9)
                    if (h != null && h.t <= 2 * TOUCH) return true
                }
            }
        }
        return false
    }

    /** Parts with at most this many triangles (boards, blocks) are sampled along their edges for line contacts. */
    private const val EDGE_SAMPLE_TRIS = 64
}
