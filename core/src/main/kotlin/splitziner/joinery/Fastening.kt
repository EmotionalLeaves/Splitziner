package splitziner.joinery

import splitziner.check.DesignCheck
import splitziner.check.Tool
import splitziner.dims.LengthFormat
import splitziner.explode.BuildStep
import splitziner.parts.Part
import kotlin.math.abs
import kotlin.math.ceil

/** How the maker joins parts. Each needs certain tools. */
enum class JoinMethod(val label: String, val needs: Set<Tool>) {
    SCREWS("Screws", setOf(Tool.DRILL)),
    POCKET_SCREWS("Pocket screws", setOf(Tool.DRILL, Tool.POCKET_JIG)),
    DOWELS("Glue and dowels", setOf(Tool.DRILL, Tool.DOWEL_JIG, Tool.CLAMPS)),
    GLUE_AND_BRADS("Glue and brads", setOf(Tool.BRAD_NAILER, Tool.CLAMPS));

    companion object {
        /** The method a maker with these tools would usually reach for: pocket screws if they have the jig, else screws. */
        fun defaultFor(tools: Set<Tool>?): JoinMethod = when {
            tools == null -> SCREWS
            POCKET_SCREWS.needs.all { it in tools } -> POCKET_SCREWS
            SCREWS.needs.all { it in tools } -> SCREWS
            GLUE_AND_BRADS.needs.all { it in tools } -> GLUE_AND_BRADS
            else -> SCREWS
        }
        fun parse(s: String?) = entries.firstOrNull { it.name == s }
    }
}

/** One joint: [through] is fastened into [into]; [count] fasteners of [size]. */
/** [at]: for slides, where the cabinet half goes, "down|back" from the cabinet side's top and front edges, formatted. */
class Joint(val through: Part, val into: Part, val count: Int, val size: String, val kind: String, val glueOnly: Boolean, val at: String = "", val ref: Part? = null)

/** [byStep] tells each step's joints (one copy of a sub-assembly made several times); [all] is every joint in the piece. */
class FastenPlan(val method: JoinMethod, val byStep: Map<Int, List<Joint>>, val hardware: List<String>, val all: List<Joint> = byStep.values.flatten())

/**
 * Works out, from where parts touch, what holds each joint. Workshop rules of thumb, not engineering:
 * a screw passes through the thinner part and goes into the other for about 1.5 times that
 * thickness, never so far that it could come out the other side; one fastener every 250 mm along the
 * joint, at least two; never into a part too thin to hold a screw.
 */
object Fastening {
    /** One per drawer and door. The model has none, so the style (and so its size) is left to the maker. */
    const val HANDLE = "handles or knobs, with their screws"
    /** Where a door's handle goes: on the opening edge, so it pulls straight off its hinges. */
    private const val HANDLE_PLACE = "near the edge away from"
    /** One fastener per this much joint length (mm): about 10 in, a common spacing for screws in furniture. */
    private const val SPACING = 250.0

    /** A screw needs at least this much wood around it in the part it goes into (mm), or it splits it. */
    private const val MIN_INTO = 15.0

    /** The thinnest edge a brad is driven into (mm): 3/8 in board takes one; 1/4 in splits. */
    private const val MIN_INTO_BRADS = 9.0

    /** A screw must reach at least this far (mm) into the second part to hold. */
    private const val MIN_BITE = 20.0

    /** Joints shorter than this (mm) are too small to fasten; they are glued. */
    private const val SMALLEST = 20.0

    /** Through-parts thinner than this (mm) take brads or small screws, not ordinary screws. */
    private const val THIN = 8.0

    private val SCREW_IN = doubleArrayOf(0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0)
    private val SCREW_MM = doubleArrayOf(16.0, 20.0, 25.0, 30.0, 35.0, 40.0, 50.0, 60.0, 70.0, 80.0)
    private val BRAD_IN = doubleArrayOf(0.625, 0.75, 1.0, 1.25, 1.5, 2.0)
    private val BRAD_MM = doubleArrayOf(15.0, 20.0, 25.0, 30.0, 40.0, 50.0)

    private fun fraction(inches: Double): String {
        val whole = inches.toInt(); val eighths = Math.round((inches - whole) * 8).toInt()
        val frac = when (eighths) { 0 -> ""; 2 -> "1/4"; 4 -> "1/2"; 6 -> "3/4"; else -> "$eighths/8" }
        return listOfNotNull(if (whole > 0) "$whole" else null, frac.ifEmpty { null }).joinToString("-").ifEmpty { "0" } + " in"
    }

    /**
     * A wood screw's gauge goes with the board it passes through, the usual rule of thumb: #6 (3.5 mm) through
     * stock under 5/8 in, #8 (4 mm) through 5/8 to 1 in, #10 (5 mm) through anything thicker. Written as sold:
     * "#8 x 1-1/2 in", "4 x 40 mm".
     */
    private fun gauged(length: String, through: Double, fmt: LengthFormat): String {
        val g = when { through < 15.5 -> 0; through <= 25.9 -> 1; else -> 2 }
        return if (fmt.isInch) "${listOf("#6", "#8", "#10")[g]} x $length" else "${listOf("3.5", "4", "5")[g]} x $length"
    }

    /** The longest stock length not over [mm]; null when even the shortest is too long. */
    private fun pick(mm: Double, fmt: LengthFormat, brads: Boolean): String? {
        return if (fmt.isInch) (if (brads) BRAD_IN else SCREW_IN).lastOrNull { it * 25.4 <= mm + 0.5 }?.let { fraction(it) }
        else (if (brads) BRAD_MM else SCREW_MM).lastOrNull { it <= mm + 0.5 }?.let { "${it.toInt()} mm" }
    }

    /** A groove goes this share of the way into the grooved part: a common rule for housings is a third to a half. */
    private const val GROOVE_SHARE = 0.4

    /** And never deeper than this (mm, 3/8 in): deeper grooves weaken the part for no gain in hold. */
    private const val GROOVE_MAX = 9.5

    /** Tools that cut a groove along a board. */
    private val GROOVERS = setOf(Tool.ROUTER, Tool.TABLE_SAW, Tool.CNC)

    private fun size(mm: Double, fmt: LengthFormat): String =
        if (fmt.isInch) fraction(maxOf(0.125, mm / 25.4)) else "${Math.round(maxOf(1.0, mm) * 2) / 2.0}".removeSuffix(".0") + " mm"

    fun plan(parts: List<Part>, steps: List<BuildStep>, method: JoinMethod, fmt: LengthFormat, tools: Set<Tool>? = null, housed: List<Pair<String, String>> = emptyList()): FastenPlan {
        val live = parts.filter { !it.excluded }
        val rel = DesignCheck.relations(live)
        val stepOf = HashMap<String, Int>()
        // A part's step is the step it is joined in: its bench step if it has one, else its main step.
        for (st in steps.sortedByDescending { it.step }) for (id in st.partIds) stepOf[id] = st.step
        for (st in steps) if (st.subassembly) for (id in st.partIds) stepOf[id] = st.step
        // Bench steps list only the first copy's parts; every copy of that kind is made there.
        val benchOf = HashMap<String, Int>()
        // A unit is the boards that came from one drawn part: "P02", "P02.2", "P02.3"...
        val keyOf = splitziner.explode.AssemblyPlan.unitKeys(live)
        fun unit(p: Part) = keyOf[p.id] ?: p.id.substringBefore('.')
        val units = live.groupBy { unit(it) }.filter { it.value.size >= 2 }
        val unitKind = units.mapValues { (_, ms) -> ms.mapNotNull { it.item }.sorted() }
        for (st in steps) if (st.subassembly) {
            val kind = live.filter { it.id in st.partIds }.mapNotNull { it.item }.sorted()
            for ((root, k) in unitKind) if (k == kind) for (p in units.getValue(root)) benchOf.putIfAbsent(p.id, st.step)
        }
        for ((id, st) in benchOf) stepOf[id] = st
        // The main step a part goes in: its own step if it is loose, its sub-assembly's placing step otherwise.
        val mainSteps = steps.filter { !it.subassembly }
        fun mainOf(p: Part): Int = p.step?.takeIf { s -> mainSteps.any { it.step == s } } ?: mainSteps.firstOrNull { p.id in it.partIds }?.step ?: (stepOf[p.id] ?: 0)
        // Copies of a sub-assembly made in one bench step: their parts are joined in that step too.
        val idx = live.withIndex().associate { it.value.id to it.index }
        val joints = HashMap<Int, MutableList<Joint>>()
        val totals = LinkedHashMap<String, Int>()
        val seen = HashSet<Long>()
        // A door over the front touches the box's edges but hangs on hinges: it is never screwed on.
        val doors = runCatching { splitziner.check.Doors.find(live) }.getOrDefault(emptyList())
        val pinned = runCatching { splitziner.check.ShelfPins.find(live) }.getOrDefault(emptyList())
        // An adjustable shelf may touch the back panel, but it only rests on its pins.
        val doorIds = doors.map { it.id }.toSet() + pinned
        val drawerOf = HashMap<String, Int>()
        val drawers = runCatching { splitziner.check.Drawers.find(live) }.getOrDefault(emptyList())
        drawers.forEachIndexed { k, d -> for (id in d.ids) drawerOf[id] = k }
        // Applied drawer fronts: screwed on from inside once the box hangs, so the gaps round them can be set.
        val applied = drawers.flatMap { d -> d.ids - d.boxIds.toSet() }.toSet()
        // A thin part lying flush with an edge of the part it is set into (a back panel at the back edge of a side)
        // sits in a rabbet, a step cut along that edge, rather than in a groove.
        fun flush(thin: Int, other: Int): Boolean {
            val a = (0..2).minByOrNull { rel.hi[thin][it] - rel.lo[thin][it] }!!
            return kotlin.math.abs(rel.hi[thin][a] - rel.hi[other][a]) < 1.0 || kotlin.math.abs(rel.lo[thin][a] - rel.lo[other][a]) < 1.0
        }
        for (i in live.indices) for (j in rel.touching[i]) {
            if (!seen.add(minOf(i, j).toLong() * 100000 + maxOf(i, j))) continue
            val a = live[i]; val b = live[j]
            if (a.id in doorIds || b.id in doorIds) continue
            // A drawer's front may rest against the carcass edges when shut; it is never fixed to them.
            if ((drawerOf[a.id] ?: -1) != (drawerOf[b.id] ?: -1)) continue
            if ((a.id in applied) != (b.id in applied)) {
                val front = if (a.id in applied) a else b; val box = if (front === a) b else a
                val ia = idx.getValue(front.id); val ib = idx.getValue(box.id)
                val kk = (0..2).minByOrNull { ax -> minOf(rel.hi[ia][ax], rel.hi[ib][ax]) - maxOf(rel.lo[ia][ax], rel.lo[ib][ax]) }!!
                val tBox = rel.hi[ib][kk] - rel.lo[ib][kk]; val tFront = rel.hi[ia][kk] - rel.lo[ia][kk]
                // Only the box end, whose face meets the front, is screwed through; a side touching it end-on is not.
                if (tBox > (0..2).minOf { ax -> rel.hi[ib][ax] - rel.lo[ib][ax] } + 0.5) continue
                // Through the box end and most of the way into the front, never out of its face.
                val sz = pick(tBox + minOf(1.5 * tBox, tFront - 4.0), fmt, false)?.let { gauged(it, tBox, fmt) } ?: ""
                val wide = (0..2).filter { it != kk }.maxOf { ax -> minOf(rel.hi[ia][ax], rel.hi[ib][ax]) - maxOf(rel.lo[ia][ax], rel.lo[ib][ax]) }
                val n = if (wide > 500) 6 else 4
                joints.getOrPut(mainOf(front)) { ArrayList() }.add(0, Joint(box, front, n, sz, "applied", false))
                if (sz.isNotEmpty()) totals["$sz screws"] = (totals["$sz screws"] ?: 0) + n
                continue
            }
            val lo = rel.lo; val hi = rel.hi
            // The direction they meet along: where their boxes only just meet; the contact is the overlap on the other two.
            val k = (0..2).minByOrNull { ax -> minOf(hi[i][ax], hi[j][ax]) - maxOf(lo[i][ax], lo[j][ax]) }!!
            val gap = minOf(hi[i][k], hi[j][k]) - maxOf(lo[i][k], lo[j][k])
            if (gap > 2.0) continue                     // they overlap rather than abut: a joint the check reports
            val dims = (0..2).filter { it != k }.map { ax -> maxOf(0.0, minOf(hi[i][ax], hi[j][ax]) - maxOf(lo[i][ax], lo[j][ax])) }
            val length = dims.max()
            // Fastened through the part that is thinner along the meeting direction.
            val ea = hi[i][k] - lo[i][k]; val eb = hi[j][k] - lo[j][k]
            val (thr, into, tThrough, depth) = if (ea <= eb) listOf(a, b, ea, eb) else listOf(b, a, eb, ea)
            thr as Part; into as Part; tThrough as Double; depth as Double
            // Parts of one sub-assembly are joined on the bench. Anything else is joined in the main build, when the
            // later of the two is put in place: a frame is not screwed to a part it has not met yet.
            val sameUnit = unit(a) == unit(b) && benchOf.containsKey(a.id) && benchOf.containsKey(b.id)
            val step = if (sameUnit) maxOf(stepOf[a.id] ?: 0, stepOf[b.id] ?: 0) else maxOf(mainOf(a), mainOf(b))
            // Two boards lying in one plane and meeting edge to edge (a panel widened by a strip): nothing can be
            // driven through a board's width, so the edges are glued and clamped.
            fun thinAxis(n: Int) = (0..2).minByOrNull { ax -> hi[n][ax] - lo[n][ax] }!!
            // Ends meeting edges (the corners of a frame) are not this: there a screw goes through the edge into the end.
            fun longAxis(n: Int) = (0..2).maxByOrNull { ax -> hi[n][ax] - lo[n][ax] }!!
            if (thinAxis(i) == thinAxis(j) && thinAxis(i) != k && longAxis(i) != k && longAxis(j) != k && length >= SMALLEST) {
                joints.getOrPut(step) { ArrayList() } += Joint(thr, into, 0, "", "edge", true)
                continue
            }
            val glue = length < SMALLEST
            var count = maxOf(2, ceil(length / SPACING).toInt())
            var size = ""; var kind = ""
            val width = dims.min()
            // Two pieces lying face to face (a laminated frame, a panel on a panel) are glued and clamped;
            // a few fasteners only hold them while the glue sets.
            // A back panel on a shelf's edge is not: that strip is too narrow to clamp a face to.
            // Nor is a rail against a leg: that is a structural joint, screwed.
            val faceToFace = width > 3 * tThrough + 1.0 && width > 40.0 && !thr.name.endsWith("eg") && !into.name.endsWith("eg")
            // A top resting on its supports is never fixed through its visible face: brackets go underneath.
            // Nor is a top with nothing above it (a table top on its aprons): screw heads in it would be on show.
            val ti0 = idx.getValue(thr.id)
            // Slats under a mattress are not on show: only a top (named so, or a wide board) counts.
            val wideTop = thr.name == "Top" || minOf(hi[ti0].x - lo[ti0].x, hi[ti0].y - lo[ti0].y) >= 150.0
            val openTop = wideTop && thr.name != "Slat" && rel.lo.indices.none { n -> n != ti0 && lo[n].z >= hi[ti0].z - 2.0 &&
                minOf(hi[n].x, hi[ti0].x) > maxOf(lo[n].x, lo[ti0].x) && minOf(hi[n].y, hi[ti0].y) > maxOf(lo[n].y, lo[ti0].y) }
            val topDown = k == 2 && (rel.lo[ti0].z >= rel.hi[idx.getValue(into.id)].z - 2.0) && (width > 2 * tThrough || openTop)
            // A thin panel's edge under a top (a back panel stopping beneath it) holds nothing and is held by its
            // rabbet or cleats: no bracket can be fixed to a 1/4 in edge.
            if (topDown && (0..2).minOf { ax -> rel.hi[idx.getValue(into.id)][ax] - rel.lo[idx.getValue(into.id)][ax] } < 9.0) continue
            // A cleat is screwed along the board it carries; where its ends butt legs, nothing more is needed.
            run {
                val cleat = if (thr.name == "Cleat") thr else if (into.name == "Cleat") into else null
                if (cleat != null) { val ci = idx.getValue(cleat.id); val longAx = (0..2).maxByOrNull { hi[ci][it] - lo[ci][it] }!!; if (longAx == k) continue }
            }
            // A board lying on supports near both its ends (a shelf on stretchers, slats on cleats) is fixed down to
            // them; where its ends only butt the legs or rails beside it, nothing is driven in sideways.
            if (k != 2) {
                fun restsAtBothEnds(bi: Int): Boolean {
                    if ((hi[bi].z - lo[bi].z) > minOf(hi[bi].x - lo[bi].x, hi[bi].y - lo[bi].y)) return false
                    val ax = if (hi[bi].x - lo[bi].x >= hi[bi].y - lo[bi].y) 0 else 1
                    val len = hi[bi][ax] - lo[bi][ax]
                    // Supports are real boards: a 1/4 in back panel's edge under a top holds nothing up.
                    val under = rel.lo.indices.filter { n -> n != bi && kotlin.math.abs(hi[n].z - lo[bi].z) < 1.0 && (0..2).minOf { hi[n][it] - lo[n][it] } >= 12.0 &&
                        minOf(hi[n].x, hi[bi].x) > maxOf(lo[n].x, lo[bi].x) && minOf(hi[n].y, hi[bi].y) > maxOf(lo[n].y, lo[bi].y) }
                    // Held under each end by separate pieces (slats on two cleats), or along both long edges
                    // (a shelf on front and back stretchers); a cabinet bottom on one kick board is neither.
                    val ends = under.filter { n -> hi[n][ax] - lo[n][ax] < 0.5 * len }
                    val byEnds = ends.any { n -> lo[n][ax] <= lo[bi][ax] + 0.15 * len } && ends.any { n -> hi[n][ax] >= hi[bi][ax] - 0.15 * len }
                    val ax2 = 1 - ax; val wid = hi[bi][ax2] - lo[bi][ax2]
                    // Along both long edges means two narrow pieces (front and back stretchers), not one divider the full depth.
                    val edges = under.filter { n -> hi[n][ax2] - lo[n][ax2] < 0.5 * wid }
                    val a = edges.firstOrNull { n -> lo[n][ax2] <= lo[bi][ax2] + 0.25 * wid }; val b = edges.firstOrNull { n -> hi[n][ax2] >= hi[bi][ax2] - 0.25 * wid && n != a }
                    return byEnds || (a != null && b != null)
                }
                val ii0 = idx.getValue(into.id)
                if (k == 0 || k == 1) { if (restsAtBothEnds(ti0) && (hi[ti0].z - lo[ti0].z) < (hi[ii0].z - lo[ii0].z)) continue; if (restsAtBothEnds(ii0) && (hi[ii0].z - lo[ii0].z) < (hi[ti0].z - lo[ti0].z)) continue }
            }
            // A leg's end under a top held by aprons needs nothing of its own: the aprons carry the top.
            if (topDown && into.name.endsWith("eg") && live.any { o -> (o.name == "Apron" || o.name.endsWith("ail")) && idx[o.id]?.let { n -> lo[n].z <= hi[ti0].z && hi[n].z >= lo[ti0].z - 2.0 && hi[n].z <= lo[ti0].z + 2.0 } == true }) continue
            if (topDown && !glue) {
                count = maxOf(2, ceil(length / (2 * SPACING)).toInt())
                joints.getOrPut(step) { ArrayList() } += Joint(thr, into, count, "corner brackets", "brackets", false)
                totals["corner brackets, with short screws"] = (totals["corner brackets, with short screws"] ?: 0) + count
                continue
            }
            if (faceToFace) count = maxOf(2, ceil(length / (3 * SPACING)).toInt())
            // The wood around the screw in the part it goes into: that part's thinner size across the screw.
            val ii = idx.getValue(into.id)
            val around = (0..2).filter { it != k }.minOf { ax -> rel.hi[ii][ax] - rel.lo[ii][ax] }
            // The same holds for the part the screw would pass through: a 1/4 in board met on its edge would have the
            // screw running along inside it, and splits. Then that part is the thin one, set into the other.
            val ti = idx.getValue(thr.id)
            val aroundThr = (0..2).filter { it != k }.minOf { ax -> rel.hi[ti][ax] - rel.lo[ti][ax] }
            // Brads are thin and forgive a thinner edge than screws do, but a 1/4 in panel's edge still splits.
            // With screws, an edge too thin for a screw but thick enough for a brad (a 1/2 in drawer side met by its
            // end) is glued and pinned instead, as drawer boxes are; only an edge too thin even for that is set in.
            val pinInstead = method == JoinMethod.SCREWS && !glue && !faceToFace && minOf(around, aroundThr) < MIN_INTO && minOf(around, aroundThr) >= MIN_INTO_BRADS
            val minInto = if (method == JoinMethod.GLUE_AND_BRADS || pinInstead) MIN_INTO_BRADS else MIN_INTO
            if (!glue && !faceToFace && aroundThr < minInto && aroundThr < around) {
                val grooveDepth = minOf(GROOVE_SHARE * depth, GROOVE_MAX)
                val canGroove = tools == null || tools.any { it in GROOVERS }
                val spec = if (grooveDepth < 3.0) "too thin" else if (!canGroove) "no tools" else "${size(aroundThr, fmt)}|${size(grooveDepth, fmt)}|${if (flush(ti, ii)) "rabbet" else "groove"}"
                joints.getOrPut(step) { ArrayList() } += Joint(into, thr, 0, spec, "thin", false)
                continue
            }
            if (!glue && !faceToFace && around < minInto) {
                // The part it would go into is meeting edge-on and too thin to hold anything: nothing can be driven
                // into that edge. It is glued, in a groove or against a cleat, which is a joint for the maker to choose.
                // The groove is cut in the face it meets, as wide as the thin part is thick; the thin part grows into it.
                val grooveDepth = minOf(GROOVE_SHARE * tThrough, GROOVE_MAX)
                val canGroove = tools == null || tools.any { it in GROOVERS }
                val spec = if (grooveDepth < 3.0) "too thin" else if (!canGroove) "no tools" else "${size(around, fmt)}|${size(grooveDepth, fmt)}|${if (flush(ii, ti)) "rabbet" else "groove"}"
                joints.getOrPut(step) { ArrayList() } += Joint(thr, into, 0, spec, "thin", false)
                continue
            }
            if (!glue) when (method) {
                JoinMethod.SCREWS -> {
                    val brads = tThrough < THIN || pinInstead
                    // Through the first part, then into the second for 1.5 times that thickness but never within 3 mm of its far side.
                    val want = tThrough + minOf(1.5 * tThrough, depth - 3.0)
                    size = pick(want, fmt, brads)?.let { if (brads) it else gauged(it, tThrough, fmt) } ?: ""; kind = if (brads) "brads" else "screws"
                    // A part too thick for any stock screw to pass through and still bite (a 3-1/2 in leg) is fixed
                    // with brackets in the inside corner instead.
                    val longest = if (fmt.isInch) SCREW_IN.last() * 25.4 else SCREW_MM.last()
                    if (!brads && tThrough + MIN_BITE > longest) {
                        joints.getOrPut(step) { ArrayList() } += Joint(thr, into, count, "corner brackets", "corner", false)
                        totals["corner brackets, with short screws"] = (totals["corner brackets, with short screws"] ?: 0) + count
                        continue
                    }
                }
                JoinMethod.POCKET_SCREWS -> {
                    // A pocket screw runs at an angle through the end of the joining part; its length follows that part's thickness.
                    // The pocket goes in the part whose end meets the other (the "into" part here), so its thickness sets the screw.
                    val t = (0..2).minOf { ax -> rel.hi[ii][ax] - rel.lo[ii][ax] }
                    size = if (fmt.isInch) fraction(when { t < 16 -> 1.0; t < 22 -> 1.25; t < 30 -> 1.5; else -> 2.5 }) else "${when { t < 16 -> 25; t < 22 -> 32; t < 30 -> 38; else -> 63 }} mm"
                    kind = "pocket screws"
                }
                JoinMethod.DOWELS -> {
                    val d = (tThrough / 2).coerceAtMost(12.0)
                    // Fluted dowel pins as sold, diameter and length: 3/8 x 1-1/2 in, 1/4 x 1-1/4 in; 8 x 40 mm, 6 x 30 mm.
                    size = if (fmt.isInch) (if (d >= 9.5) "3/8 x 1-1/2 in dowels" else "1/4 x 1-1/4 in dowels") else (if (d >= 8) "8 x 40 mm dowels" else "6 x 30 mm dowels")
                    kind = "dowels"
                }
                JoinMethod.GLUE_AND_BRADS -> { size = pick(tThrough + minOf(tThrough, depth - 3.0), fmt, true) ?: ""; kind = "brads" }
            }
            val glueOnly = glue || size.isEmpty()
            if (faceToFace && !glueOnly && method != JoinMethod.DOWELS) { kind = "brads"; size = pick(tThrough + minOf(tThrough, depth - 3.0), fmt, true) ?: size }
            joints.getOrPut(step) { ArrayList() } += Joint(thr, into, count, size, if (faceToFace) "laminate" else kind, glueOnly)
            if (faceToFace && !glueOnly) { val key = "$size brads"; totals[key] = (totals[key] ?: 0) + count; continue }
            if (!glueOnly) { val key = if (kind == "dowels") size else "$size $kind"; totals[key] = (totals[key] ?: 0) + count }
        }
        // Housing joints the user kept: the part is glued into a groove cut in the other.
        val byId = live.associateBy { it.id }
        for ((pid, gid) in housed) {
            val p = byId[pid] ?: continue; val g = byId[gid] ?: continue
            val sameUnit = unit(p) == unit(g) && benchOf.containsKey(p.id) && benchOf.containsKey(g.id)
            val step = if (sameUnit) maxOf(stepOf[p.id] ?: 0, stepOf[g.id] ?: 0) else maxOf(mainOf(p), mainOf(g))
            joints.getOrPut(step) { ArrayList() } += Joint(g, p, 0, "", "housed", true)
        }
        // Drawers hang on a pair of slides each, fitted in the step the drawer goes in.
        for (dr in runCatching { splitziner.check.Drawers.find(live) }.getOrDefault(emptyList())) {
            val ps = live.filter { it.id in dr.ids }
            val len = splitziner.check.Drawers.slideLength(splitziner.check.Drawers.depthOf(live, dr), fmt.isInch) ?: continue
            val step = ps.maxOf { mainOf(it) }
            val box = ps.filter { it.id in dr.boxIds }.maxByOrNull { kotlin.math.abs(it.mesh.volume) } ?: ps.maxByOrNull { kotlin.math.abs(it.mesh.volume) }!!
            // Hung by its box; an applied front goes on in a later step, and its handle with it.
            val front = ps.firstOrNull { it.id in applied }
            // Where the cabinet half goes, read off the model: its centre line level with the middle of the drawer box's
            // height, measured down from the top of the cabinet side beside it, and how far back the box starts.
            val boxParts = ps.filter { it.id in dr.boxIds }.ifEmpty { listOf(box) }
            val bLo = splitziner.math.Vec3(boxParts.minOf { it.mesh.bounds.min.x }, boxParts.minOf { it.mesh.bounds.min.y }, boxParts.minOf { it.mesh.bounds.min.z })
            val bHi = splitziner.math.Vec3(boxParts.maxOf { it.mesh.bounds.max.x }, boxParts.maxOf { it.mesh.bounds.max.y }, boxParts.maxOf { it.mesh.bounds.max.z })
            val side = live.filter { it.id !in dr.ids && it.mesh.bounds.max.x <= bLo.x + 0.5 && it.mesh.bounds.min.z <= bLo.z && it.mesh.bounds.max.z >= bHi.z &&
                it.mesh.bounds.min.y <= bLo.y + 1.0 && it.mesh.bounds.max.y >= bHi.y - 1.0 }.maxByOrNull { it.mesh.bounds.max.x }
            // Measured up from the panel the drawers stand over (the cabinet bottom), which every column of drawers
            // shares, rather than from the top of whatever upright is beside each one.
            val allDrawerIds = drawers.flatMap { it.ids }.toSet()
            val floorRef = live.filter { it.id !in allDrawerIds && it.mesh.bounds.max.z <= bLo.z + 0.5 && it.mesh.bounds.min.x < bHi.x && it.mesh.bounds.max.x > bLo.x &&
                it.mesh.bounds.min.y < bHi.y && it.mesh.bounds.max.y > bLo.y }.maxByOrNull { it.mesh.bounds.max.z }
            val u = if (fmt.isInch) " in" else " mm"
            val at = side?.let { c -> "${fmt.format((bLo.z + bHi.z) / 2 - (floorRef?.mesh?.bounds?.max?.z ?: c.mesh.bounds.min.z))}$u|${fmt.format(maxOf(0.0, bLo.y - c.mesh.bounds.min.y))}$u" } ?: ""
            joints.getOrPut(ps.filter { it.id in dr.boxIds }.maxOfOrNull { mainOf(it) } ?: step) { ArrayList() }.add(0, Joint(box, front ?: box, 1, len, "slides", false, at, floorRef ?: side))
            totals["pair of $len drawer slides"] = (totals["pair of $len drawer slides"] ?: 0) + 1
            totals[HANDLE] = (totals[HANDLE] ?: 0) + 1
        }
        // Doors hang on hinges, fitted in the step the door goes in.
        for (dr in doors) {
            val p = live.first { it.id == dr.id }
            val n = splitziner.check.Doors.hinges(dr.height)
            // Which edge they go on, as the model has the door, and the usual place along it: the end hinges about
            // 3 in (75 mm) from the top and bottom, a common cabinetmakers' rule, any others evenly between.
            val end = if (fmt.isInch) "3 in" else "75 mm"
            val at = "${if (!dr.paired) "" else if (dr.hingeLeft) "left" else "right"}|$end"
            joints.getOrPut(mainOf(p)) { ArrayList() }.add(0, Joint(p, p, n, "hinges", "hinges", false, at))
            totals["concealed (cup) hinges"] = (totals["concealed (cup) hinges"] ?: 0) + n
            totals[HANDLE] = (totals[HANDLE] ?: 0) + 1
        }
        // Adjustable shelves rest on pins, put in when the shelf goes in.
        for (id in pinned) {
            val p = live.first { it.id == id }
            joints.getOrPut(mainOf(p)) { ArrayList() }.add(0, Joint(p, p, splitziner.check.ShelfPins.PINS, "shelf pins", "pins", false))
            totals["shelf pins"] = (totals["shelf pins"] ?: 0) + splitziner.check.ShelfPins.PINS
        }
        // Tall, shallow pieces get a wall anchor (see the check's "Fix it to the wall").
        if (live.isNotEmpty()) {
            val h = live.maxOf { it.mesh.bounds.max.z } - live.minOf { it.mesh.bounds.min.z }; val dd = live.maxOf { it.mesh.bounds.max.y } - live.minOf { it.mesh.bounds.min.y }
            if (h > 760.0 && h > 2.0 * dd) totals["anti-tip wall strap kit"] = 1
        }
        val every = joints.values.flatten()
        // Identical sub-assemblies are made more than once in one bench step. Every copy's joints are already in
        // the totals; the step itself tells how to make one, so it keeps only the first copy's joints.
        for (st in steps) if (st.subassembly && st.makeCount > 1) {
            val first = st.partIds.toSet()
            joints[st.step]?.let { js -> val own = js.filter { it.through.id in first && it.into.id in first }; if (own.isNotEmpty()) joints[st.step] = own.toMutableList() }
        }
        // By kind (screws, then brads, then the rest), shortest first.
        fun lengthOf(k: String): Double {
            val m = Regex("""^(\d+)?(?:-?(\d)/(\d))?\s*(in|mm)""").find(k) ?: return 0.0
            val whole = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val frac = if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toDouble() / m.groupValues[3].toDouble() else 0.0
            return whole + frac
        }
        fun kindRank(k: String) = when { k == HANDLE -> 6; k.contains("slides") || k.contains("hinges") || k == "shelf pins" -> 5; k.contains("bracket") -> 4; k.contains("pocket") -> 0; k.endsWith("screws") -> 1; k.contains("dowel") -> 2; k.endsWith("brads") -> 3; else -> 4 }
        val hardware = totals.entries.sortedWith(compareBy({ kindRank(it.key) }, { lengthOf(it.key) })).map { (k, n) -> if (k.startsWith("pair of ")) "$n ${if (n == 1) "pair" else "pairs"} of ${k.removePrefix("pair of ")}" else "$n × $k" } + listOf("Wood glue") +
            (if ((method == JoinMethod.SCREWS || method == JoinMethod.POCKET_SCREWS) && totals.keys.any { it.endsWith("screws") || it.contains("brackets") || it.endsWith("brads") }) listOf("Buy about 10% extra fasteners") else emptyList())
        return FastenPlan(method, joints, hardware, every)
    }

    /** A count before a size: "two 2-1/2 in", and past twelve "24 × 5/8 in", so "24 5/8 in" is never read as one size. */
    private fun sized(n: Int, size: String): String = if (n <= 12 || size.isEmpty() || !size[0].isDigit()) "${count(n)} $size" else "$n × $size"

    /** Counts as words up to twelve ("two 2-1/2 in screws"), so a count never runs into a size. */
    private fun count(n: Int): String = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve").getOrElse(n) { "$n" }

    /** Per item, how many pocket holes to drill before assembly (in the part whose end meets the other). */
    /**
     * Per item, the grooves and rabbets to cut in it for the thin parts it holds, as lines for "Making the parts".
     * They are the guide's own option (a cleat does instead), so each says so.
     */
    fun cutLines(plan: FastenPlan, itemName: (Part) -> String): Map<Int, List<String>> {
        val out = LinkedHashMap<Int, MutableList<String>>()
        // One line per size of cut: several thin parts taking the same groove are named together.
        for ((key, js) in plan.all.filter { it.kind == "thin" && it.size.split('|').size >= 2 }.groupBy { it.through.item to it.size }) {
            val host = key.first ?: continue
            val parts = key.second.split('|'); val rabbet = parts.getOrNull(2) == "rabbet"
            val thins = js.groupBy { it.into.item }.values.map { itemName(it[0].into) }
            val thin = if (thins.size == 1) thins[0] else thins.dropLast(1).joinToString(", ") + " and " + thins.last()
            // Joints over all copies of the item; the maker cuts each copy alike.
            val copies = maxOf(1, js[0].through.qty)
            val each = maxOf(1, js.size / copies)
            val places = if (each > 1) (if (copies > 1) " ($each on each)" else " ($each places)") else ""
            val one = thins.size == 1
            val obj = if (one) "it" else "them"; val sits = if (one) "it sits" else "they sit"; val meets = if (one) "its edge meets" else "their edges meet"
            out.getOrPut(host) { ArrayList() } += if (rabbet) "For $thin, if you set $obj in a rabbet: cut a rabbet ${parts[0]} wide and ${parts[1]} deep along the edge where $sits$places."
                else "For $thin, if you set $obj in a groove: cut a groove ${parts[0]} wide and ${parts[1]} deep where $meets this part$places."
        }
        return out
    }

    /** The dowels the plan uses ("3/8 x 1-1/2 in dowels"), for the guide's note on drilling them; null if none. */
    /**
     * Pilot hole sizes for the screw gauges the plan uses, from common woodworking charts: softwood and plywood take
     * the smaller bit, hardwood and MDF the larger. Null when no plain screws are used.
     */
    fun pilotNote(plan: FastenPlan): String? {
        val gauges = plan.all.filter { !it.glueOnly && (it.kind == "screws" || it.kind == "applied") }.map { it.size.substringBefore(" x ", "") }.filter { it.isNotEmpty() }.distinct()
        if (gauges.isEmpty()) return null
        val chart = mapOf("#6" to ("5/64 in" to "3/32 in"), "#8" to ("3/32 in" to "1/8 in"), "#10" to ("7/64 in" to "1/8 in"),
            "3.5" to ("2 mm" to "2.5 mm"), "4" to ("2.5 mm" to "3 mm"), "5" to ("3 mm" to "3.5 mm"))
        val order = listOf("#6", "#8", "#10", "3.5", "4", "5")
        val bits = gauges.sortedBy { order.indexOf(it) }.mapNotNull { g -> chart[g]?.let { (soft, hard) -> "${if (g.startsWith("#")) g else "$g mm"} screws, $soft in softwood and plywood or $hard in hardwood and MDF" } }
        if (bits.isEmpty()) return null
        return "Pilot holes: " + bits.joinToString("; ") + ". Drill them as deep as the screw goes and countersink them so the heads sit flush; near the end of a board they stop it splitting."
    }

    fun dowelSize(plan: FastenPlan): String? = plan.all.firstOrNull { it.kind == "dowels" && !it.glueOnly }?.size

    /** Items that get a groove or rabbet for a thin part, if the maker takes that option over a cleat. */
    fun grooveItems(plan: FastenPlan): List<Int> =
        plan.all.filter { it.kind == "thin" && it.size.count { c -> c == '|' } >= 1 }.mapNotNull { it.through.item }.distinct().sorted()

    fun pocketHoles(plan: FastenPlan): Map<Int, Int> {
        if (plan.method != JoinMethod.POCKET_SCREWS) return emptyMap()
        val out = sortedMapOf<Int, Int>()
        for (j in plan.all) if (!j.glueOnly && j.kind == "pocket screws") { val it = j.into.item ?: continue; out[it] = (out[it] ?: 0) + j.count }
        return out
    }

    /** "item 2 (front)" and "item 2 (back)" as "item 2 (front and back)"; different items as "item 1, item 2 and item 3". */
    private fun joinedNames(names: List<String>): String {
        if (names.size == 1) return names[0]
        val m = names.map { Regex("""^(.*?) \((.*)\)$""").find(it) }
        if (m.all { it != null } && m.map { it!!.groupValues[1] }.distinct().size == 1) {
            val inner = m.map { it!!.groupValues[2] }
            return "${m[0]!!.groupValues[1]} (" + inner.dropLast(1).joinToString(", ") + " and " + inner.last() + ")"
        }
        return names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** What the chosen method drives into an edge, for "too thin to take …". */
    private fun fixings(plan: FastenPlan) = when (plan.method) {
        JoinMethod.GLUE_AND_BRADS -> "brads"
        JoinMethod.DOWELS -> "dowels"
        else -> "screws"
    }

    /** The lines a step gets instead of "then fasten". */
    fun lines(plan: FastenPlan, step: Int, itemName: (Part) -> String, maxLines: Int = 4): List<String> {
        val js = plan.byStep[step].orEmpty()
        if (js.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        // A thin part set into a groove or rabbet in several parts (a drawer bottom in its sides and ends) is told once.
        val cut = js.filter { it.kind == "thin" && it.size.split('|').size >= 2 }
        for ((key, g) in cut.groupBy { itemName(it.into) to it.size }) {
            val parts = key.second.split('|'); val w = parts[0]; val d = parts[1]; val rabbet = parts.getOrNull(2) == "rabbet"
            val where = joinedNames(g.map { itemName(it.through) }.distinct())
            val times = if (g.size > 1) " (${g.size} joints)" else ""
            val thin = key.first.replaceFirstChar { it.uppercase() }
            // Told in full the first time; later steps that set the same part the same way only say so.
            val earlier = plan.byStep.entries.filter { it.key < step }.sortedBy { it.key }.firstOrNull { (_, ej) -> ej.any { it.kind == "thin" && it.size == key.second && itemName(it.into) == key.first } }?.key
            if (earlier != null) {
                out += if (rabbet) "Glue and pin ${key.first} into its rabbet in $where, as in step $earlier."
                    else "Glue ${key.first} into its groove in $where, as in step $earlier."
                continue
            }
            out += if (rabbet) "$thin is too thin to take ${fixings(plan)} at its edge$times. Cut a rabbet (a step along the edge it sits at) $w wide and $d deep in $where, make ${key.first} $d bigger on those edges than the cut list says, and glue and pin it into the rabbet. Or glue a small strip (a cleat) behind it instead."
                else "$thin is too thin to take ${fixings(plan)} at its edge$times. Cut a groove $w wide and $d deep in $where for its edges, make ${key.first} $d bigger on those edges than the cut list says, and glue it in. Or glue a small strip (a cleat) beside it instead."
        }
        // Joints of the same kind between the same items are told once.
        // One item under several names (item 2 as bottom, top and shelf) is one line: "item 2 (bottom, top and shelf)".
        // Plain fixings from one part into several (a side screwed to the bottom and the kick board) are one line;
        // hung parts and the like keep a line per part, as their wording is about that part.
        val plainKinds = setOf("screws", "brads", "pocket screws", "dowels", "corner", "brackets", "laminate", "")
        // Drawers hung in one step are one line however many sizes of box they are (graduated drawers).
        val groups0 = (js - cut.toSet()).groupBy { if (it.kind == "slides") Triple("drawers", "*", it.size + it.kind + (it.into === it.through)) else Triple(if ((it.kind in plainKinds || it.glueOnly) && it.through.item != null) "#${it.through.item}" else itemName(it.through), if (it.kind in plainKinds || it.glueOnly) "*" else it.into.item?.let { n -> "#$n" } ?: itemName(it.into), it.size + it.glueOnly + it.kind) }
        fun joined(names: List<String>): String {
            if (names.size == 1) return names[0]
            val m = names.map { Regex("""^(.*?) \((.*)\)$""").find(it) }
            if (m.any { it == null }) return names.dropLast(1).joinToString(", ") + " and " + names.last()
            // One item under several names: "item 2 (bottom, top and shelf)".
            if (m.map { it!!.groupValues[1] }.distinct().size == 1) {
                val inner = m.map { it!!.groupValues[2] }.distinct()
                return "${m[0]!!.groupValues[1]} (" + (if (inner.size == 1) inner[0] else inner.dropLast(1).joinToString(", ") + " and " + inner.last()) + ")"
            }
            // Several items under one name: "items 42 and 43 (post)".
            if (m.map { it!!.groupValues[2] }.distinct().size == 1 && m.all { it!!.groupValues[1].startsWith("item ") }) {
                val nums = m.map { it!!.groupValues[1].removePrefix("item ") }
                return "items " + nums.dropLast(1).joinToString(", ") + " and " + nums.last() + " (${m[0]!!.groupValues[2]})"
            }
            return names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        // One item under two names (a box's front and back) fixed the same way is one line: "item 2 (front and back)".
        val groups = groups0.entries.associate { (k, g) -> Triple(if (k.first.startsWith("#")) joined(g.map { itemName(it.through) }.distinct()) else k.first, joined(g.map { itemName(it.into) }.distinct()), k.third) to g }
        val room = maxOf(1, maxLines - out.size)
        for ((key, g) in groups.entries.take(room)) {
            val j = g[0]; val n = g.sumOf { it.count }; val times = if (g.size > 1) " (${g.size} joints)" else ""
            out += if (j.kind == "hinges") {
                // Where they go: the hinge edge (from the front), the end ones a set distance in, any others evenly between.
                val per = n / g.size
                val sides = g.map { it.at.substringBefore('|') }.filter { it.isNotEmpty() }
                val end = g.first().at.substringAfter('|', "")
                val edge = when { sides.isEmpty() -> "on the side you want it hinged, "; sides.distinct().size == 1 -> "on its ${sides[0]}-hand edge (seen from the front), "; else -> "on its outer edge, so the pair opens from the middle, " }
                val place = if (end.isEmpty()) "" else " The top and bottom hinges go about $end from the ends" + (if (per > 2) ", the others evenly between" else "") + "."
                if (g.size > 1) "Drill the hinge cups in the back of each ${key.first} ${edge}with a 35 mm bit (using the hinges' template), screw on ${count(per)} hinges each, then hang them and adjust them until the gaps are even.$place Fit each one's handle or knob ${HANDLE_PLACE} its hinges."
                else "Drill the hinge cups in the back of ${key.first} ${edge}with a 35 mm bit (using the hinges' template), screw on ${count(n)} hinges, then hang it and adjust it until the gaps are even.$place Fit its handle or knob ${HANDLE_PLACE} the hinges."
            }
            else if (j.kind == "pins") (if (g.size > 1) "For each ${key.first}, push ${count(n / g.size)} shelf pins into the holes at the height you want, level side to side, and lay it on them."
                else "Push ${count(n)} shelf pins into the holes at the height you want, level side to side, and lay ${key.first} on them.")
            else if (j.kind == "slides") {
                val handle = j.into === j.through
                // Where the cabinet halves go: a level line down from the top of each side, front end set back as the box is.
                val sorted = g.sortedBy { it.through.mesh.bounds.min.z }
                val ats = sorted.map { it.at }.filter { it.isNotEmpty() }.map { it.split('|') }
                val refs = sorted.mapNotNull { it.ref }.distinctBy { it.item ?: it.id }
                val mark = if (ats.size != g.size || ats.isEmpty() || refs.size != 1) "" else {
                    // Drawers side by side share heights: each height is drawn once, for every drawer at it.
                    val ups = ats.map { it[0] }.distinct(); val backs = ats.map { it[1] }.distinct()
                    val from = "up from the top of ${itemName(refs[0])}"
                    val back = if (backs.size == 1) (if (backs[0].startsWith("0 ") || backs[0] == "0") ", its front end flush with the front edge" else ", its front end ${backs[0]} back from the front edge") else ""
                    if (ups.size == 1) " Draw a level line inside the cabinet on each side of the opening, ${ups[0]} $from; the centre of the slide's cabinet half goes on it$back. Centre the drawer halves on the drawer's sides, flush with its front."
                    else " Draw level lines inside the cabinet on each side of the openings, ${ups.dropLast(1).joinToString(", ")} and ${ups.last()} $from, one for each row of drawers from the bottom; the centre of each slide's cabinet half goes on its line$back. Centre the drawer halves on the drawers' sides, flush with their fronts."
                }
                if (g.size > 1) "For each drawer, screw one half of each ${j.size} slide to the inside of the cabinet and the other to the drawer's side, following the slides' instructions, then hang it.$mark" + (if (handle) " Fit each drawer's handle or knob in the middle of its front." else "")
                else "Screw one half of each ${j.size} slide to the inside of the cabinet and the other to the sides of the drawer, following the slides' instructions, then hang the drawer.$mark" + (if (handle) " Fit its handle or knob in the middle of the front." else "")
            }
            else if (j.kind == "applied") (if (g.size > 1) "Fit each ${key.second}: close its drawer, hold the front in place with even gaps all round (double-sided tape or a couple of clamps hold it while you check), then open the drawer and screw the front on from inside with ${sized(n / g.size, j.size)} screws through ${key.first}. Fit each handle or knob in the middle of its front."
                else "Close the drawer and hold ${key.second} in place with even gaps all round (double-sided tape or a couple of clamps hold it while you check), then open the drawer and screw it on from inside with ${sized(n, j.size)} screws through ${key.first}. Fit its handle or knob in the middle of the front.")
            else if (j.kind == "housed") "Glue ${key.second} into the groove cut for it in ${key.first}$times, and clamp until dry."
            else if (j.kind == "edge") "Glue the edge of ${key.first} to the edge of ${key.second}$times and clamp until dry; they lie in one plane, so nothing can be screwed through."
            else if (j.glueOnly) "Glue ${key.first} to ${key.second}$times; ${if (g.size > 1) "the joints are" else "the joint is"} too small for fasteners."
            else if (j.kind == "brackets") "Fix ${key.first} to ${key.second} from underneath with ${count(n)} corner brackets$times." +
                // Solid wood moves across its grain with the seasons; a top fixed tight splits or bows.
                (if (g.any { it.through.material.isNotBlank() && !splitziner.material.Materials.sheetGood(it.through.material) }) " As the top is solid wood, use brackets with slotted holes, or tabletop fasteners, and leave the screws a little loose across the grain so it can move with the seasons." else "")
            else if (j.kind == "corner") "Fix ${key.second} to ${key.first} with ${count(n)} corner brackets in the inside corner$times; ${key.first} is too thick to screw through."
            else if (j.kind == "laminate") "Glue ${key.first} face to face on ${key.second}, clamp, and pin with ${sized(n, j.size)} brads$times."
            else if (j.kind == "thin") {
                val parts = j.size.split('|'); val w = if (parts.size >= 2) parts[0] else ""; val d = if (parts.size >= 2) parts[1] else ""
                val rabbet = parts.getOrNull(2) == "rabbet"
                if (w.isNotEmpty() && rabbet) "${key.second.replaceFirstChar { it.uppercase() }} is too thin to take ${fixings(plan)} at its edge$times. Cut a rabbet (a step along the edge it sits at) $w wide and $d deep in ${key.first}, make ${key.second} $d bigger on that edge than the cut list says, and glue and pin it into the rabbet. Or glue a small strip (a cleat) along ${key.first} behind it."
                else if (w.isNotEmpty()) "${key.second.replaceFirstChar { it.uppercase() }} is too thin to take ${fixings(plan)} at its edge$times. Cut a groove $w wide and $d deep in ${key.first} for its edge, make ${key.second} $d bigger on that edge than the cut list says, and glue it in. Or glue a small strip (a cleat) along ${key.first} beside it."
                else if (j.size == "no tools") "Glue the edge of ${key.second} to ${key.first}$times. It is too thin for fasteners, and a groove needs a router or table saw: glue a small strip (a cleat) along ${key.first} beside it to hold it."
                else "Glue the edge of ${key.second} to ${key.first}$times. Both are too thin for fasteners or a groove: glue a small strip (a cleat) along ${key.first} beside it to hold it."
            }
            else when (plan.method) {
                // Pocket holes go in the part whose end or edge meets the other's face, on a side that will not show.
                JoinMethod.POCKET_SCREWS -> "Fix ${key.second} to ${key.first} with ${sized(n, j.size)} pocket screws through the pocket holes in its end$times."
                JoinMethod.DOWELS -> "Glue and dowel ${key.first} to ${key.second}: ${sized(n, j.size)}$times. Clamp until dry."
                JoinMethod.GLUE_AND_BRADS -> "Glue ${key.first} to ${key.second} and pin with ${sized(n, j.size)} brads$times."
                JoinMethod.SCREWS -> if (j.kind == "brads") "Glue ${key.first} to ${key.second} and fix with ${sized(n, j.size)} brads or small screws$times."
                    else "Drill pilot holes through ${key.first} and screw it to ${key.second} with ${sized(n, j.size)} screws$times."
            }
        }
        if (groups.size > room) {
            // Name the rest, so the reader knows which joints "the same way" means.
            fun how(j: Joint) = when {
                j.kind == "slides" -> "on slides"; j.kind == "applied" -> "screwed on from inside"; j.kind == "hinges" -> "on hinges"; j.kind == "pins" -> "on shelf pins"; j.kind == "edge" -> "edge glued"; j.kind == "housed" -> "glued in its groove"; j.glueOnly -> "glue"; j.kind == "thin" -> "groove or cleat"; j.kind == "brackets" || j.kind == "corner" -> "brackets"; j.kind == "laminate" -> "glue and brads"
                else -> "${count(j.count)} ${j.size} ${j.kind}"
            }
            val rest = groups.entries.drop(room).map { (k, g) -> "${k.first.removePrefix("item ")} to ${k.second.removePrefix("item ")} (${how(g[0])})" }
            out += "Also join, item to item: ${rest.joinToString(", ")}."
        }
        return out
    }
}
