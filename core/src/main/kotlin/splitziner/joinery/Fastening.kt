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
class Joint(val through: Part, val into: Part, val count: Int, val size: String, val kind: String, val glueOnly: Boolean)

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
                // Through the box end and most of the way into the front, never out of its face.
                val sz = pick(tBox + minOf(1.5 * tBox, tFront - 4.0), fmt, false) ?: ""
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
            val faceToFace = width > 3 * tThrough + 1.0 && width > 40.0
            // A top resting on its supports is never fixed through its visible face: brackets go underneath.
            // Nor is a top with nothing above it (a table top on its aprons): screw heads in it would be on show.
            val ti0 = idx.getValue(thr.id)
            val openTop = rel.lo.indices.none { n -> n != ti0 && lo[n].z >= hi[ti0].z - 2.0 &&
                minOf(hi[n].x, hi[ti0].x) > maxOf(lo[n].x, lo[ti0].x) && minOf(hi[n].y, hi[ti0].y) > maxOf(lo[n].y, lo[ti0].y) }
            val topDown = k == 2 && (rel.lo[ti0].z >= rel.hi[idx.getValue(into.id)].z - 2.0) && (width > 2 * tThrough || openTop)
            // A leg's end under a top held by aprons needs nothing of its own: the aprons carry the top.
            if (topDown && into.name == "Leg" && live.any { o -> (o.name == "Apron" || o.name == "Rail") && idx[o.id]?.let { n -> lo[n].z <= hi[ti0].z && hi[n].z >= lo[ti0].z - 2.0 && hi[n].z <= lo[ti0].z + 2.0 } == true }) continue
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
            if (!glue && !faceToFace && aroundThr < MIN_INTO && aroundThr < around && method != JoinMethod.GLUE_AND_BRADS) {
                val grooveDepth = minOf(GROOVE_SHARE * depth, GROOVE_MAX)
                val canGroove = tools == null || tools.any { it in GROOVERS }
                val spec = if (grooveDepth < 3.0) "too thin" else if (!canGroove) "no tools" else "${size(aroundThr, fmt)}|${size(grooveDepth, fmt)}|${if (flush(ti, ii)) "rabbet" else "groove"}"
                joints.getOrPut(step) { ArrayList() } += Joint(into, thr, 0, spec, "thin", false)
                continue
            }
            if (!glue && !faceToFace && around < MIN_INTO && method != JoinMethod.GLUE_AND_BRADS) {
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
                    val brads = tThrough < THIN
                    // Through the first part, then into the second for 1.5 times that thickness but never within 3 mm of its far side.
                    val want = tThrough + minOf(1.5 * tThrough, depth - 3.0)
                    size = pick(want, fmt, brads) ?: ""; kind = if (brads) "brads" else "screws"
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
                    size = if (fmt.isInch) fraction(if (d >= 9.5) 0.375 else 0.25) + " dowels" else "${if (d >= 8) 8 else 6} mm dowels"
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
            joints.getOrPut(ps.filter { it.id in dr.boxIds }.maxOfOrNull { mainOf(it) } ?: step) { ArrayList() }.add(0, Joint(box, front ?: box, 1, len, "slides", false))
            totals["pair of $len drawer slides"] = (totals["pair of $len drawer slides"] ?: 0) + 1
            totals[HANDLE] = (totals[HANDLE] ?: 0) + 1
        }
        // Doors hang on hinges, fitted in the step the door goes in.
        for (dr in doors) {
            val p = live.first { it.id == dr.id }
            val n = splitziner.check.Doors.hinges(dr.height)
            joints.getOrPut(mainOf(p)) { ArrayList() }.add(0, Joint(p, p, n, "hinges", "hinges", false))
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

    /** Counts as words up to twelve ("two 2-1/2 in screws"), so a count never runs into a size. */
    private fun count(n: Int): String = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve").getOrElse(n) { "$n" }

    /** Per item, how many pocket holes to drill before assembly (in the part whose end meets the other). */
    /**
     * Per item, the grooves and rabbets to cut in it for the thin parts it holds, as lines for "Making the parts".
     * They are the guide's own option (a cleat does instead), so each says so.
     */
    fun cutLines(plan: FastenPlan, itemName: (Part) -> String): Map<Int, List<String>> {
        val out = LinkedHashMap<Int, MutableList<String>>()
        for ((key, js) in plan.all.filter { it.kind == "thin" && it.size.split('|').size >= 2 }.groupBy { Triple(it.through.item, it.into.item, it.size) }) {
            val host = key.first ?: continue
            val parts = key.third.split('|'); val rabbet = parts.getOrNull(2) == "rabbet"
            val thin = itemName(js[0].into)
            // Joints over all copies of the item; the maker cuts each copy alike.
            val copies = maxOf(1, js[0].through.qty)
            val each = maxOf(1, js.size / copies)
            val places = if (each > 1) " ($each on each)" else ""
            out.getOrPut(host) { ArrayList() } += if (rabbet) "For $thin, if you set it in a rabbet: cut a rabbet ${parts[0]} wide and ${parts[1]} deep along the edge where it sits$places."
                else "For $thin, if you set it in a groove: cut a groove ${parts[0]} wide and ${parts[1]} deep where its edge meets this part$places."
        }
        return out
    }

    /** Items that get a groove or rabbet for a thin part, if the maker takes that option over a cleat. */
    fun grooveItems(plan: FastenPlan): List<Int> =
        plan.all.filter { it.kind == "thin" && it.size.count { c -> c == '|' } >= 1 }.mapNotNull { it.through.item }.distinct().sorted()

    fun pocketHoles(plan: FastenPlan): Map<Int, Int> {
        if (plan.method != JoinMethod.POCKET_SCREWS) return emptyMap()
        val out = sortedMapOf<Int, Int>()
        for (j in plan.all) if (!j.glueOnly && j.kind == "pocket screws") { val it = j.into.item ?: continue; out[it] = (out[it] ?: 0) + j.count }
        return out
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
            val hosts = g.map { itemName(it.through) }.distinct()
            val where = if (hosts.size == 1) hosts[0] else hosts.dropLast(1).joinToString(", ") + " and " + hosts.last()
            val times = if (g.size > 1) " (${g.size} joints)" else ""
            val thin = key.first.replaceFirstChar { it.uppercase() }
            out += if (rabbet) "$thin is too thin to take screws at its edge$times. Cut a rabbet (a step along the edge it sits at) $w wide and $d deep in $where, make ${key.first} $d bigger on those edges than the cut list says, and glue and pin it into the rabbet. Or glue a small strip (a cleat) behind it instead."
                else "$thin is too thin to take screws at its edge$times. Cut a groove $w wide and $d deep in $where for its edges, make ${key.first} $d bigger on those edges than the cut list says, and glue it in. Or glue a small strip (a cleat) beside it instead."
        }
        // Joints of the same kind between the same items are told once.
        val groups = (js - cut.toSet()).groupBy { Triple(itemName(it.through), itemName(it.into), it.size + it.glueOnly) }
        val room = maxOf(1, maxLines - out.size)
        for ((key, g) in groups.entries.take(room)) {
            val j = g[0]; val n = g.sumOf { it.count }; val times = if (g.size > 1) " (${g.size} joints)" else ""
            out += if (j.kind == "hinges") (if (g.size > 1) "Drill the hinge cups in the back of each ${key.first} with a 35 mm bit (using the hinges' template), screw on ${count(n / g.size)} hinges each, then hang them and adjust them until the gaps are even. Fit each one's handle or knob ${HANDLE_PLACE} its hinges."
                else "Drill the hinge cups in the back of ${key.first} with a 35 mm bit (using the hinges' template), screw on ${count(n)} hinges, then hang it and adjust it until the gaps are even. Fit its handle or knob ${HANDLE_PLACE} the hinges.")
            else if (j.kind == "pins") (if (g.size > 1) "For each ${key.first}, push ${count(n / g.size)} shelf pins into the holes at the height you want, level side to side, and lay it on them."
                else "Push ${count(n)} shelf pins into the holes at the height you want, level side to side, and lay ${key.first} on them.")
            else if (j.kind == "slides") {
                val handle = j.into === j.through
                if (g.size > 1) "For each drawer, screw one half of each ${j.size} slide to the inside of the cabinet and the other to the drawer's side, following the slides' instructions, then hang it." + (if (handle) " Fit each drawer's handle or knob in the middle of its front." else "")
                else "Screw one half of each ${j.size} slide to the inside of the cabinet and the other to the sides of the drawer, following the slides' instructions, then hang the drawer." + (if (handle) " Fit its handle or knob in the middle of the front." else "")
            }
            else if (j.kind == "applied") (if (g.size > 1) "Fit each ${key.second}: close its drawer, hold the front in place with even gaps all round (double-sided tape or a couple of clamps hold it while you check), then open the drawer and screw the front on from inside with ${count(n / g.size)} ${j.size} screws through ${key.first}. Fit each handle or knob in the middle of its front."
                else "Close the drawer and hold ${key.second} in place with even gaps all round (double-sided tape or a couple of clamps hold it while you check), then open the drawer and screw it on from inside with ${count(n)} ${j.size} screws through ${key.first}. Fit its handle or knob in the middle of the front.")
            else if (j.kind == "housed") "Glue ${key.second} into the groove cut for it in ${key.first}$times, and clamp until dry."
            else if (j.kind == "edge") "Glue the edge of ${key.first} to the edge of ${key.second}$times and clamp until dry; they lie in one plane, so nothing can be screwed through."
            else if (j.glueOnly) "Glue ${key.first} to ${key.second}$times; the joint is too small for fasteners."
            else if (j.kind == "brackets") "Fix ${key.first} to ${key.second} from underneath with ${count(n)} corner brackets$times."
            else if (j.kind == "corner") "Fix ${key.second} to ${key.first} with ${count(n)} corner brackets in the inside corner$times; ${key.first} is too thick to screw through."
            else if (j.kind == "laminate") "Glue ${key.first} face to face on ${key.second}, clamp, and pin with ${count(n)} ${j.size} brads$times."
            else if (j.kind == "thin") {
                val parts = j.size.split('|'); val w = if (parts.size >= 2) parts[0] else ""; val d = if (parts.size >= 2) parts[1] else ""
                val rabbet = parts.getOrNull(2) == "rabbet"
                if (w.isNotEmpty() && rabbet) "${key.second.replaceFirstChar { it.uppercase() }} is too thin to take screws at its edge$times. Cut a rabbet (a step along the edge it sits at) $w wide and $d deep in ${key.first}, make ${key.second} $d bigger on that edge than the cut list says, and glue and pin it into the rabbet. Or glue a small strip (a cleat) along ${key.first} behind it."
                else if (w.isNotEmpty()) "${key.second.replaceFirstChar { it.uppercase() }} is too thin to take screws at its edge$times. Cut a groove $w wide and $d deep in ${key.first} for its edge, make ${key.second} $d bigger on that edge than the cut list says, and glue it in. Or glue a small strip (a cleat) along ${key.first} beside it."
                else if (j.size == "no tools") "Glue the edge of ${key.second} to ${key.first}$times. It is too thin for fasteners, and a groove needs a router or table saw: glue a small strip (a cleat) along ${key.first} beside it to hold it."
                else "Glue the edge of ${key.second} to ${key.first}$times. Both are too thin for fasteners or a groove: glue a small strip (a cleat) along ${key.first} beside it to hold it."
            }
            else when (plan.method) {
                // Pocket holes go in the part whose end or edge meets the other's face, on a side that will not show.
                JoinMethod.POCKET_SCREWS -> "Fix ${key.second} to ${key.first} with ${count(n)} ${j.size} pocket screws through the pocket holes in its end$times."
                JoinMethod.DOWELS -> "Glue and dowel ${key.first} to ${key.second}: ${count(n)} ${j.size}$times. Clamp until dry."
                JoinMethod.GLUE_AND_BRADS -> "Glue ${key.first} to ${key.second} and pin with ${count(n)} ${j.size} brads$times."
                JoinMethod.SCREWS -> if (j.kind == "brads") "Glue ${key.first} to ${key.second} and fix with ${count(n)} ${j.size} brads or small screws$times."
                    else "Drill pilot holes through ${key.first} and screw it to ${key.second} with ${count(n)} ${j.size} screws$times."
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
