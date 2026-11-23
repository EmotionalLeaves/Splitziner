package splitziner.joinery

import splitziner.check.DesignCheck
import splitziner.check.Tool
import splitziner.dims.LengthFormat
import splitziner.explode.BuildStep
import splitziner.parts.Part
import splitziner.parts.PartKind
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
private val RUNNER_NAMES = setOf("Runner", "Kicker")

/** What holds a lid up: a stay or a torsion hinge rated for its weight, that stops it anywhere it is let go. */
const val LID_SUPPORT = "lid support that holds the lid open at any angle"

/** A top piece this narrow or narrower (mm) is screwed down, not held with brackets: 4 in. */
const val CAP_WIDTH = 101.6

/** What stops a folding back leg (an easel's) sliding out: a light chain or cord between it and the front legs. */
fun foldStop(inch: Boolean) = if (inch) "light chain or cord, about 24 in long" else "light chain or cord, about 600 mm long"

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

    /** Wood left under a counterbore for the screw to pass: 1-1/2 in, the thickness of ordinary framing stock. */
    private const val COUNTERBORE_LEAVE = 38.0

    /** The deepest counterbore asked for: a 3/8 in bit bores cleanly to about 2 in (a 3-1/2 in leg). */
    private const val COUNTERBORE_MAX = 52.0

    /** Panels at least this wide meeting edge to edge over at least this length get connector bolts (12 in). */
    private const val WIDE_JOIN = 300.0

    /** Side rails longer than this (4 ft) make a frame too big to carry assembled: they get knock-down fittings. */
    private const val BED_RAIL_MIN = 1200.0

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
    private fun gauged(length: String, through: Double, fmt: LengthFormat, into: Double = Double.MAX_VALUE): String {
        // Nor heavier than the part it bites into would take by the same rule: a #10 into the end of a 3/4 in bar splits it.
        fun of(t: Double) = when { t < 15.5 -> 0; t <= 25.9 -> 1; else -> 2 }
        val g = minOf(of(through), of(into))
        return if (fmt.isInch) "${listOf("#6", "#8", "#10")[g]} x $length" else "${listOf("3.5", "4", "5")[g]} x $length"
    }

    /** The longest stock length not over [mm]; null when even the shortest is too long. */
    private fun pick(mm: Double, fmt: LengthFormat, brads: Boolean): String? {
        // A brad a hair longer than ideal still holds a 6 mm back panel: the shortest sold is taken within 10%.
        val slack = if (brads) maxOf(0.5, 0.1 * mm) else 0.5
        return if (fmt.isInch) (if (brads) BRAD_IN else SCREW_IN).lastOrNull { it * 25.4 <= mm + slack }?.let { fraction(it) }
        else (if (brads) BRAD_MM else SCREW_MM).lastOrNull { it <= mm + slack }?.let { "${it.toInt()} mm" }
    }

    /** A groove goes this share of the way into the grooved part: a common rule for housings is a third to a half. */
    private const val GROOVE_SHARE = 0.4

    /** And never deeper than this (mm, 3/8 in): deeper grooves weaken the part for no gain in hold. */
    private const val GROOVE_MAX = 9.5

    /** Tools that cut a groove along a board. */
    private val GROOVERS = setOf(Tool.ROUTER, Tool.TABLE_SAW, Tool.CNC)

    /** A cradled panel's strips: glued to the face, never fixed through it. */
    private val CRADLE_PARTS = setOf("Cradle", "Brace")

    /** How far in from a corner a caster's plate goes (mm, 2 in), so the wheel swivels clear of the edge. */
    private const val CASTER_INSET = 50.8

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
        for (st in steps) if (st.subassembly && st.continues == null && steps.none { it.continues == st.step }) {
            val kind = live.filter { it.id in st.partIds }.mapNotNull { it.item }.sorted()
            for ((root, k) in unitKind) if (k == kind) for (p in units.getValue(root)) benchOf.putIfAbsent(p.id, st.step)
        }
        // A unit built over several bench steps: each copy's part goes in the step its counterpart in the first copy
        // does, found by item and by where it sits in its unit.
        for (st0 in steps) if (st0.subassembly && st0.continues == null && steps.any { it.continues == st0.step }) {
            val seq = steps.filter { it.step == st0.step || it.continues == st0.step }
            val firstIds = seq.flatMap { it.partIds }.toSet()
            val kind = live.filter { it.id in firstIds }.mapNotNull { it.item }.sorted()
            val firstRoot = units.entries.firstOrNull { (_, ms) -> ms.any { it.id in firstIds } }?.key ?: continue
            fun origin(ms: List<Part>) = splitziner.math.Vec3(ms.minOf { it.mesh.bounds.min.x }, ms.minOf { it.mesh.bounds.min.y }, ms.minOf { it.mesh.bounds.min.z })
            val o0 = origin(units.getValue(firstRoot))
            for ((root, k) in unitKind) if (k == kind) {
                val ms = units.getValue(root); val o = origin(ms)
                for (st in seq) for (id in st.partIds) {
                    val p0 = live.firstOrNull { it.id == id } ?: continue
                    val rel0 = p0.mesh.bounds.min - o0
                    ms.firstOrNull { q -> q.item == p0.item && (q.mesh.bounds.min - o - rel0).length < 1.5 }?.let { benchOf.putIfAbsent(it.id, st.step) }
                }
            }
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
        val slidingIds = runCatching { splitziner.check.Doors.sliding(live) }.getOrDefault(emptyList())
        val doorIds = doors.map { it.id }.toSet() + pinned + slidingIds
        val doorGroup = HashMap<String, Int>()
        val frameOf = HashMap<String, Int>()
        val appliedFrames = HashSet<Int>()
        val sunkPairs = runCatching { splitziner.check.FramePanels.find(live) }.getOrDefault(emptyList()).flatMap { g -> g.sunk.map { setOf(it.first, it.second) } }.toSet()
        // Each back leg hinges to one part: the block or rail at the top rather than a leg, when it touches one.
        val hingeTo = live.filter { it.name == "Back leg" }.associate { l -> val li = idx.getValue(l.id)
            l.id to rel.touching[li].map { live[it] }.filter { it.name != "Back leg" }.sortedBy { if (it.name == "Leg") 1 else 0 }.firstOrNull()?.id }
        // Board strips only: a post round a panel is joined like any post.
        runCatching { splitziner.check.FramePanels.find(live) }.getOrDefault(emptyList()).forEachIndexed { k, g ->
            val pk = live.first { it.id == g.panel }.mesh.bounds.extents.let { e -> (0..2).minByOrNull { e[it] }!! }
            for (id in g.stiles + g.rails) if (!splitziner.check.FramePanels.isPost(live.first { it.id == id }, pk)) frameOf[id] = k }
        runCatching { splitziner.check.FramePanels.doors(live) }.getOrDefault(emptyList()).forEachIndexed { k, g -> for (id in g.ids) doorGroup[id] = k }
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
            // A panel and the strips its edges sit in meet only in the groove or rabbet: told with the frame below.
            if (setOf(a.id, b.id) in sunkPairs) continue
            // The parts of a frame-and-panel door are joined to each other, never to the case.
            val ga = doorGroup[a.id]; val gb = doorGroup[b.id]
            if (ga != gb) continue
            if (ga == null && (a.id in doorIds || b.id in doorIds)) continue
            // A back leg (an easel's) folds on hinges at its top: one joint, hinged, nothing glued.
            if (a.name == "Back leg" || b.name == "Back leg") {
                val leg = if (a.name == "Back leg") a else b; val to = if (leg === a) b else a
                if (hingeTo[leg.id] != to.id) continue
                val hs = if (fmt.isInch) "2 in" else "50 mm"
                joints.getOrPut(mainOf(leg)) { ArrayList() } += Joint(to, leg, 2, hs, "foldhinge", false)
                totals["pair of $hs butt hinges"] = 1
                totals[foldStop(fmt.isInch)] = 1
                continue
            }
            // A lid is hinged, never fixed: it is dealt with on its own below. So is a wall cleat: it goes on the wall.
            if (a.name == "Lid" || b.name == "Lid" || a.name == "Wall cleat" || b.name == "Wall cleat") continue
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
                // A five-piece front is screwed on as one: one line, named as the unit made on the bench.
                val fg = frameOf[front.id]
                if (fg != null) {
                    if (!appliedFrames.add(fg)) continue
                    val fw = live.filter { frameOf[it.id] == fg }.let { ms -> ms.maxOf { it.mesh.bounds.max.x } - ms.minOf { it.mesh.bounds.min.x } }
                    joints.getOrPut(mainOf(front)) { ArrayList() }.add(0, Joint(box, front, if (fw > 500) 6 else 4, sz, "applied", false, "name:the drawer front from step ${stepOf[front.id] ?: mainOf(front)}"))
                    if (sz.isNotEmpty()) totals["$sz screws"] = (totals["$sz screws"] ?: 0) + (if (fw > 500) 6 else 4)
                    continue
                }
                val n = if (wide > 500) 6 else 4
                joints.getOrPut(mainOf(front)) { ArrayList() }.add(0, Joint(box, front, n, sz, "applied", false))
                if (sz.isNotEmpty()) totals["$sz screws"] = (totals["$sz screws"] ?: 0) + n
                continue
            }
            val lo = rel.lo; val hi = rel.hi
            val envLo = DoubleArray(3) { ax -> lo.minOf { it[ax] } }; val envHi = DoubleArray(3) { ax -> hi.maxOf { it[ax] } }
            // The direction they meet along: where their boxes only just meet; the contact is the overlap on the other two.
            val k = (0..2).minByOrNull { ax -> minOf(hi[i][ax], hi[j][ax]) - maxOf(lo[i][ax], lo[j][ax]) }!!
            val gap = minOf(hi[i][k], hi[j][k]) - maxOf(lo[i][k], lo[j][k])
            // Mitred corners: two pieces with ends cut opposite ways meet on a diagonal, so their boxes overlap at the
            // corner. Glued, closed with tape or a band clamp, and pinned (or splined) for strength.
            val mitreA = Regex("""\((MITRE|BEVEL)\)""").find(a.desc)?.groupValues?.get(1); val mitreB = Regex("""\((MITRE|BEVEL)\)""").find(b.desc)?.groupValues?.get(1)
            if (gap > 2.0 && mitreA != null && mitreA == mitreB) {
                val sameUnit0 = unit(a) == unit(b) && benchOf.containsKey(a.id) && benchOf.containsKey(b.id)
                val st0 = if (sameUnit0) maxOf(stepOf[a.id] ?: 0, stepOf[b.id] ?: 0) else maxOf(mainOf(a), mainOf(b))
                val t0 = minOf(a.t.takeIf { it > 0 } ?: 19.0, b.t.takeIf { it > 0 } ?: 19.0)
                // Pinned across the corner from each side: a brad crosses the mitre on a diagonal, about twice the thickness.
                val sz = pick(2 * t0, fmt, true) ?: ""
                val n = if (mitreA == "BEVEL") 4 else 2
                joints.getOrPut(st0) { ArrayList() } += Joint(a, b, n, sz, "mitre", false, mitreA)
                if (sz.isNotEmpty()) totals["$sz brads"] = (totals["$sz brads"] ?: 0) + n
                continue
            }
            // Parts set at an angle (a bench's leaned-back slats on their back supports) overlap as boxes but may
            // meet face to face in their own frame: measured along one part's own axes, they abut there.
            if (gap > 2.0) {
                val tilt = tiltedContact(a, b) ?: faceContact(a, b) ?: run {
                    // They touch without sharing a face and without overlapping (a square-cut rail against a splayed
                    // leg): screwed through the part it meets end-on, into its end.
                    // A slight overlap (under 3.5 mm, a square end against a sloping face) still counts as meeting.
                    if (rel.clashes.any { c -> ((c.a == i && c.b == j) || (c.a == j && c.b == i)) && c.depth >= 3.5 }) return@run null
                    fun endInside(p: Part, o: Part): Boolean {
                        val pb = p.mesh.bounds; val ob = o.mesh.bounds; val ax = (0..2).maxByOrNull { pb.extents[it] }!!
                        val c = pb.center
                        return listOf(pb.min[ax], pb.max[ax]).any { e -> val q = (0..2).map { if (it == ax) e else c[it] }; (0..2).all { k -> q[k] >= ob.min[k] - 2.0 && q[k] <= ob.max[k] + 2.0 } }
                    }
                    val endOn = if (endInside(a, b)) a else if (endInside(b, a)) b else return@run null
                    val other = if (endOn === a) b else a
                    val tO = (0..2).minOf { other.mesh.bounds.extents[it] }; val tE = endOn.mesh.bounds.extents.let { e -> listOf(e.x, e.y, e.z).sorted()[2] }
                    Triple(if (other === a) tO else tE, if (other === b) tO else tE, 60.0)
                } ?: continue
                val (tA, tB, len) = tilt
                val (thr, into, tT, dep) = if (tA <= tB) listOf(a, b, tA, tB) else listOf(b, a, tB, tA)
                thr as Part; into as Part; tT as Double; dep as Double
                val sameUnit1 = unit(a) == unit(b) && benchOf.containsKey(a.id) && benchOf.containsKey(b.id)
                val st1 = if (sameUnit1) maxOf(stepOf[a.id] ?: 0, stepOf[b.id] ?: 0) else maxOf(mainOf(a), mainOf(b))
                if (len < SMALLEST) { joints.getOrPut(st1) { ArrayList() } += Joint(thr, into, 0, "", "", true); continue }
                val brad = method == JoinMethod.GLUE_AND_BRADS || tT < THIN
                val sz = pick(tT + minOf(1.5 * tT, dep - 3.0), fmt, brad)?.let { if (brad) it else gauged(it, tT, fmt) } ?: ""
                val n = maxOf(2, ceil(len / SPACING).toInt())
                val kd = if (brad) "brads" else "screws"
                joints.getOrPut(st1) { ArrayList() } += Joint(thr, into, n, sz, kd, sz.isEmpty())
                if (sz.isNotEmpty()) totals["$sz $kd"] = (totals["$sz $kd"] ?: 0) + n
                continue
            }
            val dims = (0..2).filter { it != k }.map { ax -> maxOf(0.0, minOf(hi[i][ax], hi[j][ax]) - maxOf(lo[i][ax], lo[j][ax])) }
            val length = dims.max()
            // Fastened through the part that is thinner along the meeting direction.
            val ea = hi[i][k] - lo[i][k]; val eb = hi[j][k] - lo[j][k]
            // Equal thicknesses (a cleat on a bed's side rail): through the one whose far face is inside the piece, so
            // the screw heads do not show on the outside.
            // The inner one is the one on the side of the other towards the middle of the piece.
            val mid = (envLo[k] + envHi[k]) / 2
            fun inner(n: Int): Boolean { val o = if (n == i) j else i; val cn = (lo[n][k] + hi[n][k]) / 2; val co = (lo[o][k] + hi[o][k]) / 2
                return (cn - co) * (mid - co) > 0 }
            // A cleat beside the board it carries (a planter's bottom cleats along its sides) is screwed through from
            // its own side, so the heads stay inside, out of sight.
            val cleatSide = k != 2 && ((a.name == "Cleat") != (b.name == "Cleat"))
            val aFirst = if (cleatSide) a.name == "Cleat" else if (kotlin.math.abs(ea - eb) < 0.5) !(inner(j) && !inner(i)) else ea <= eb
            val (thr, into, tThrough, depth) = if (aFirst) listOf(a, b, ea, eb) else listOf(b, a, eb, ea)
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
                // Leaning parts (a picnic table's legs meeting at their tops) only touch along a corner; their boxes
                // say nothing about an edge joint. Joined through what crosses them, not to each other.
                fun leans(p: Part) = kotlin.math.abs(p.mesh.volume) < 0.85 * p.mesh.bounds.extents.let { it.x * it.y * it.z }
                if (leans(a) || leans(b)) continue
                // Boards stacked on edge in a wall (a planter's, a fence's), each screwed to the same post: held by it,
                // not glued to each other.
                if (k == 2 && thinAxis(i) != 2 && rel.touching[i].any { c -> c in rel.touching[j] && (hi[c][thinAxis(i)] - lo[c][thinAxis(i)]) > 1.5 * (hi[i][thinAxis(i)] - lo[i][thinAxis(i)]) }) continue
                // Two wide panels meeting edge to edge (an L desk's two tops, a worktop round a corner) are pulled
                // together from underneath with worktop connector bolts; glue alone would not hold a butt joint that long.
                fun width(n: Int) = (0..2).map { ax -> hi[n][ax] - lo[n][ax] }.sorted()[1]
                if (width(i) >= WIDE_JOIN && width(j) >= WIDE_JOIN && length >= WIDE_JOIN) {
                    val n = maxOf(2, ceil(length / 300.0).toInt())
                    joints.getOrPut(step) { ArrayList() } += Joint(thr, into, n, "", "edgebolt", false)
                    totals["worktop connector bolts"] = (totals["worktop connector bolts"] ?: 0) + n
                    continue
                }
                joints.getOrPut(step) { ArrayList() } += Joint(thr, into, 0, "", "edge", true)
                continue
            }
            // Mortise and tenon: the tenon is the rail's (cut on it, or glued into its end as a loose tenon) and is
            // glued into the mortise in the leg; nothing else holds the rail there.
            if (a.name == "Tenon" || b.name == "Tenon") {
                val ten = if (a.name == "Tenon") a else b; val other = if (ten === a) b else a
                val tb = ten.mesh.bounds
                val inOther = other.mesh.bounds.let { ob -> (0..2).all { k -> tb.min[k] >= ob.min[k] - 0.5 && tb.max[k] <= ob.max[k] + 0.5 } }
                if (!inOther) joints.getOrPut(step) { ArrayList() } += Joint(other, ten, 1, "", "tenon", true)
                continue
            }
            val tenonBetween = live.any { t -> t.name == "Tenon" && t.mesh.bounds.let { tb ->
                listOf(a, b).any { h -> h.mesh.bounds.let { ob -> (0..2).all { kk -> tb.min[kk] >= ob.min[kk] - 0.5 && tb.max[kk] <= ob.max[kk] + 0.5 } } } &&
                listOf(a, b).any { r -> r.mesh.bounds.let { rb -> (0..2).all { kk -> tb.min[kk] <= rb.max[kk] + 0.5 && rb.min[kk] <= tb.max[kk] + 0.5 } && !(0..2).all { kk -> tb.min[kk] >= rb.min[kk] - 0.5 && tb.max[kk] <= rb.max[kk] + 0.5 } } } } }
            if (tenonBetween) {
                val leg = listOf(a, b).first { h -> live.any { t -> t.name == "Tenon" && t.mesh.bounds.let { tb -> h.mesh.bounds.let { ob -> (0..2).all { kk -> tb.min[kk] >= ob.min[kk] - 0.5 && tb.max[kk] <= ob.max[kk] + 0.5 } } } } }
                val rail = if (leg === a) b else a
                joints.getOrPut(step) { ArrayList() } += Joint(leg, rail, 1, "", "mortise", true)
                continue
            }
            // A hanging rod (a wardrobe's) drops into a socket screwed to the side at each end, so it can be lifted out.
            // A painting panel's face is glued to its cradle and weighed down: nothing through the face, it would show.
            if ((a.name == "Panel face") != (b.name == "Panel face") && (a.name in CRADLE_PARTS || b.name in CRADLE_PARTS)) {
                val face = if (a.name == "Panel face") a else b; val other = if (face === a) b else a
                joints.getOrPut(step) { ArrayList() } += Joint(face, other, 1, "", "faceglue", false)
                continue
            }
            // A caster is screwed on by its plate, with the screws that come with it.
            val caster = if (a.name == "Caster") a else if (b.name == "Caster") b else null
            if (caster != null) {
                val other = if (caster === a) b else a
                // Into the board lying over it (the bottom), not the edge of a side it also touches.
                fun flatOver(o: Part) = o.mesh.bounds.extents.let { e -> e.z <= minOf(e.x, e.y) }
                val onFlat = live.any { o -> o !== caster && o.name != "Caster" && flatOver(o) && kotlin.math.abs(o.mesh.bounds.min.z - caster.mesh.bounds.max.z) < 0.5 && (0..1).all { ax -> o.mesh.bounds.min[ax] < caster.mesh.bounds.max[ax] && o.mesh.bounds.max[ax] > caster.mesh.bounds.min[ax] } }
                if (other.name != "Caster" && (flatOver(other) || !onFlat)) {
                    joints.getOrPut(step) { ArrayList() } += Joint(other, caster, 1, "", "caster", false, size(CASTER_INSET, fmt))
                    totals["casters"] = (totals["casters"] ?: 0) + 1
                }
                continue
            }
            val rod = if (a.kind == PartKind.ROD) a else if (b.kind == PartKind.ROD) b else null
            if (rod != null) {
                val other = if (rod === a) b else a
                val rb = rod.mesh.bounds; val ob = other.mesh.bounds
                val longAx = (0..2).maxByOrNull { rb.extents[it] }!!
                if (longAx == k) {
                    val u = if (fmt.isInch) " in" else " mm"
                    // Where its centre is: down from the underside of the shelf above it, back from the side's front edge.
                    val cz = (rb.min.z + rb.max.z) / 2; val cy = (rb.min.y + rb.max.y) / 2
                    val above = live.filter { o -> o !== rod && o.mesh.bounds.min.z >= rb.max.z - 0.5 && o.mesh.bounds.extents.z <= minOf(o.mesh.bounds.extents.x, o.mesh.bounds.extents.y) &&
                        o.mesh.bounds.min.x < rb.max.x && o.mesh.bounds.max.x > rb.min.x && o.mesh.bounds.min.y < rb.max.y && o.mesh.bounds.max.y > rb.min.y }.minByOrNull { it.mesh.bounds.min.z }
                    val at = (above?.let { "${fmt.format(it.mesh.bounds.min.z - cz)}$u below the underside of ${it.item?.let { n -> "item $n (${it.name.lowercase()})" } ?: it.name.lowercase()}" } ?: "${fmt.format(cz - ob.min.z)}$u up from the bottom of the side") +
                        " and ${fmt.format(cy - ob.min.y)}$u back from the front edge"
                    joints.getOrPut(step) { ArrayList() } += Joint(other, rod, 1, "", "rod", false, at)
                    totals["closet rod sockets"] = (totals["closet rod sockets"] ?: 0) + 1
                    continue
                }
            }
            // A bed's long side rails hook onto its legs or posts with bed rail fasteners, so the bed comes apart to
            // move: a glued and screwed bed frame will not go through a door.
            // The hooked plate goes on the end of the rail that meets the other end-on; the slotted plate on the
            // leg, post or long rail it hooks onto. Only in a bed (slats) with a long side rail.
            if (live.any { it.name == "Slat" } && live.any { it.name == "Side rail" && it.mesh.bounds.extents.let { e -> maxOf(e.x, e.y, e.z) } > BED_RAIL_MIN }) {
                fun endOn(p: Part) = (0..2).maxByOrNull { p.mesh.bounds.extents[it] }!! == k
                val hook = listOf(a, b).firstOrNull { endOn(it) && it.name.endsWith("rail") }
                val plate = if (hook === a) b else if (hook === b) a else null
                if (hook != null && plate != null && !endOn(plate) && (hook.name == "Side rail" || plate.name == "Side rail" || hook.name == "Centre rail" || hook.name == "Guard rail") && (plate.name.endsWith("eg") || plate.name == "Post" || plate.name == "Side rail" || (plate.name == "End rail" && hook.name == "Centre rail"))) {
                    joints.getOrPut(step) { ArrayList() } += Joint(plate, hook, 1, "", "bedrail", false)
                    totals["pairs of bed rail fasteners"] = (totals["pairs of bed rail fasteners"] ?: 0) + 1
                    continue
                }
                // A rail hooked onto a side rail also lies against the leg in that corner: nothing fixes it there,
                // or the bed would not come apart.
                val r0 = listOf(a, b).firstOrNull { it.name.endsWith("rail") }; val o0 = if (r0 === a) b else if (r0 === b) a else null
                if (r0 != null && o0 != null && (o0.name.endsWith("eg") || o0.name == "Post") && live.any { s0 -> s0.name == "Side rail" && s0 !== r0 && unit(s0) == unit(o0) &&
                        s0.mesh.bounds.let { x -> val y = r0.mesh.bounds; (0..2).all { x.min[it] <= y.max[it] + 0.5 && y.min[it] <= x.max[it] + 0.5 } } }) continue
            }
            // Cabinets built whole and set side by side are clamped flush and screwed through one side into the next,
            // with screws shorter than the two sides together; no glue, so they can come apart to move.
            val ua = unit(a); val ub = unit(b)
            if (ua != ub && ua.startsWith("module") && ub.startsWith("module")) {
                val sz = pick(2 * tThrough - 6.0, fmt, false)?.let { gauged(it, tThrough, fmt) } ?: ""
                joints.getOrPut(step) { ArrayList() } += Joint(thr, into, 4, sz, "cabinets", false)
                if (sz.isNotEmpty()) totals["$sz screws"] = (totals["$sz screws"] ?: 0) + 4
                continue
            }
            // Face frames: stiles and rails are joined to each other with pocket screws from the back, the usual way, as
            // screws through a stile's edge would show; the frame is glued and pinned to the case's front edges, and the
            // edges of shelves behind it need nothing.
            val ffA = thr.name == "Stile" || thr.name == "Face frame rail"; val ffB = into.name == "Stile" || into.name == "Face frame rail"
            // The strips of one frame round a panel (a door's, a panelled side's) are joined the same way.
            val sameFrame = frameOf[thr.id] != null && frameOf[thr.id] == frameOf[into.id]
            if ((ffA && ffB) || sameFrame) {
                val n = 2
                // The pocket holes go in the rail's ends, on the back; the screws run into the stile. In a frame round
                // a panel, the holes go in whichever meets the other end-on (a muntin's ends into the rails).
                val endOnThr = (0..2).maxByOrNull { thr.mesh.bounds.extents[it] } == k
                val stile = if (sameFrame && !(ffA && ffB)) (if (endOnThr) into else thr) else if (thr.name == "Stile") thr else into; val rail = if (stile === thr) into else thr
                joints.getOrPut(step) { ArrayList() } += Joint(stile, rail, n, if (fmt.isInch) "1-1/4 in" else "32 mm", "pocket screws", false)
                totals["${if (fmt.isInch) "1-1/4 in" else "32 mm"} pocket screws"] = (totals["${if (fmt.isInch) "1-1/4 in" else "32 mm"} pocket screws"] ?: 0) + n
                continue
            }
            // A case part meeting the inside face of a panelled frame's strip (a chest's end against its front's stile,
            // its bottom against the bottom rail): screws through the strip would show on the outside, so it is
            // pocket-screwed from inside, through pocket holes along the case part's edge.
            val fThr = frameOf[thr.id] != null; val fInto = frameOf[into.id] != null
            if (fThr != fInto) {
                val strip = if (fThr) thr else into; val other = if (fThr) into else thr
                val sThin = (0..2).minByOrNull { strip.mesh.bounds.extents[it] }!!; val oThin = (0..2).minByOrNull { other.mesh.bounds.extents[it] }!!
                if (sThin == k && oThin != k && length >= SMALLEST) {
                    val ps = if (fmt.isInch) "1-1/4 in" else "32 mm"
                    val n = maxOf(2, ceil(length / SPACING).toInt())
                    joints.getOrPut(step) { ArrayList() } += Joint(strip, other, n, ps, "pocketin", false)
                    totals["$ps pocket screws"] = (totals["$ps pocket screws"] ?: 0) + n
                    continue
                }
            }
            if (ffA != ffB) {
                if (length < 150.0) continue
                val ffPart = if (ffA) thr else into; val other = if (ffA) into else thr
                val sz = pick(19.05 + 15.0, fmt, true) ?: ""
                val n = maxOf(2, ceil(length / SPACING).toInt())
                joints.getOrPut(step) { ArrayList() } += Joint(ffPart, other, n, sz, "front", false)
                if (sz.isNotEmpty()) totals["$sz brads"] = (totals["$sz brads"] ?: 0) + n
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
            // Open: most of its face is on show (a desk top with a hutch on its back half still is).
            val covered = rel.lo.indices.filter { n -> n != ti0 && lo[n].z >= hi[ti0].z - 2.0 && lo[n].z <= hi[ti0].z + 2.0 }.sumOf { n ->
                maxOf(0.0, minOf(hi[n].x, hi[ti0].x) - maxOf(lo[n].x, lo[ti0].x)) * maxOf(0.0, minOf(hi[n].y, hi[ti0].y) - maxOf(lo[n].y, lo[ti0].y)) }
            val hutched = rel.lo.indices.any { n -> n != ti0 && lo[n].z >= hi[ti0].z - 2.0 && minOf(hi[n].x, hi[ti0].x) > maxOf(lo[n].x, lo[ti0].x) && minOf(hi[n].y, hi[ti0].y) > maxOf(lo[n].y, lo[ti0].y) }
            val openTop = wideTop && thr.name != "Slat" && (!hutched || (thr.name == "Top" && covered < 0.5 * (hi[ti0].x - lo[ti0].x) * (hi[ti0].y - lo[ti0].y)))
            // A top on flat stretchers (a cabinet's, a worktop on a row of them) is screwed from below, up through the
            // stretchers, with screws that stop well short of its face.
            if (faceToFace && k == 2) {
                val upperI = if (rel.lo[ti0].z >= rel.hi[idx.getValue(into.id)].z - 0.5) ti0 else idx.getValue(into.id)
                val upper = if (upperI == ti0) thr else into; val lower = if (upper === thr) into else thr
                val tl = rel.hi[idx.getValue(lower.id)].z - rel.lo[idx.getValue(lower.id)].z; val tu = rel.hi[upperI].z - rel.lo[upperI].z
                if (upper.name == "Top" && tu > 12.0) {
                    val sz = pick(tl + minOf(0.6 * tu, tu - 6.0), fmt, false)?.let { gauged(it, tl, fmt) } ?: ""
                    joints.getOrPut(step) { ArrayList() } += Joint(lower, upper, count, sz, "up", false)
                    if (sz.isNotEmpty()) totals["$sz screws"] = (totals["$sz screws"] ?: 0) + count
                    continue
                }
            }
            // Slats (a bench seat's, a bed's) are screwed down into what they rest on, never bracketed from below.
            // A narrow cap (a planter's top rail, 4 in wide or less) is simply screwed down: it barely moves with the seasons.
            val narrowCap = (0..2).map { ax -> rel.hi[ti0][ax] - rel.lo[ti0][ax] }.sorted()[1] <= CAP_WIDTH
            // A shelf on wall brackets is screwed down into them and into the back board.
            val onBrackets = live.any { it.name == "Bracket" && rel.touching[ti0].contains(idx.getValue(it.id)) }
            val topDown = k == 2 && thr.name != "Slat" && thr.name != "Tread" && !narrowCap && !onBrackets && (rel.lo[ti0].z >= rel.hi[idx.getValue(into.id)].z - 2.0) && (width > 2 * tThrough || openTop)
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
                // A case's bottom resting on its plinth still holds the case together: its sides, front and back are screwed to it.
                val caseBottom = setOf(thr.name, into.name).let { ns -> "Bottom" in ns && ns.any { it in setOf("Side", "Front", "Back") } }
                if ((k == 0 || k == 1) && !caseBottom) { if (restsAtBothEnds(ti0) && (hi[ti0].z - lo[ti0].z) < (hi[ii0].z - lo[ii0].z)) continue; if (restsAtBothEnds(ii0) && (hi[ii0].z - lo[ii0].z) < (hi[ti0].z - lo[ti0].z)) continue }
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
            val pinInstead = (method == JoinMethod.SCREWS || method == JoinMethod.POCKET_SCREWS) && !glue && !faceToFace && minOf(around, aroundThr) < MIN_INTO && minOf(around, aroundThr) >= MIN_INTO_BRADS
            val minInto = if (method == JoinMethod.GLUE_AND_BRADS || pinInstead) MIN_INTO_BRADS else MIN_INTO
            if (!glue && !faceToFace && aroundThr < minInto && aroundThr < around) {
                val grooveDepth = minOf(GROOVE_SHARE * depth, GROOVE_MAX)
                val canGroove = tools == null || tools.any { it in GROOVERS }
                val spec = if (grooveDepth < 3.0) "too thin" else if (!canGroove) "no tools" else "${size(aroundThr, fmt)}|${size(grooveDepth, fmt)}|${if (flush(ti, ii)) "rabbet" else "groove"}"
                joints.getOrPut(step) { ArrayList() } += Joint(into, thr, 0, spec, "thin", false)
                continue
            }
            // A case's back panel standing on the piece below (a hutch's back on the desk top) needs nothing there.
            if (unit(a) != unit(b) && k == 2 && (thr.name == "Back panel" || into.name == "Back panel")) continue
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
            // Where a pocket or a dowel can go: an end or edge meeting a face. Two faces together, a board lying on its
            // support (a slat on its cleat, a bottom on its kick board) or a thin panel (a 1/4 in back) take plain
            // screws or brads instead.
            val tInto = (0..2).minOf { ax -> rel.hi[ii][ax] - rel.lo[ii][ax] }
            fun flatOn(n: Int) = (0..2).minByOrNull { ax -> rel.hi[n][ax] - rel.lo[n][ax] } == k
            val upper = if (rel.lo[ii].z >= rel.hi[ti].z - 0.5) ii else if (rel.lo[ti].z >= rel.hi[ii].z - 0.5) ti else -1
            val thinPart = minOf(tInto, (0..2).minOf { ax -> rel.hi[ti][ax] - rel.lo[ti][ax] }) < 12.0
            val faceOn = (flatOn(ii) && flatOn(ti)) || (k == 2 && upper >= 0 && flatOn(upper)) || thinPart
            if (!glue) when (method) {
                JoinMethod.SCREWS -> {
                    val brads = tThrough < THIN || pinInstead
                    // Through the first part, then into the second for 1.5 times that thickness but never within 3 mm of its far side.
                    val want = tThrough + minOf(1.5 * tThrough, depth - 3.0)
                    size = pick(want, fmt, brads)?.let { if (brads) it else gauged(it, tThrough, fmt, tInto) } ?: ""; kind = if (brads) "brads" else "screws"
                    // A part too thick for any stock screw to pass through and still bite (a 3 in trestle post) is
                    // counterbored, as joiners do: a plug-sized hole sinks each head so the screw passes only
                    // COUNTERBORE_LEAVE of wood, and a glued wooden plug hides it afterwards.
                    val longest = if (fmt.isInch) SCREW_IN.last() * 25.4 else SCREW_MM.last()
                    // An apron or rail meeting a leg end-on, whatever the leg's size, is pocket-screwed from its inside
                    // face: a screw through the leg into end grain holds little, and its head shows on the leg.
                    if (!brads && (thr.name == "Leg" || thr.name == "Post") && tThrough >= 32.0) {
                        val railT = (0..2).minOf { ax -> rel.hi[ii][ax] - rel.lo[ii][ax] }
                        val railLong = (0..2).maxByOrNull { ax -> rel.hi[ii][ax] - rel.lo[ii][ax] } == k
                        if (railLong && railT <= 25.5) {
                            val ps = if (fmt.isInch) fraction(when { railT < 16 -> 1.0; railT < 22 -> 1.25; else -> 1.5 }) else "${when { railT < 16 -> 25; railT < 22 -> 32; else -> 38 }} mm"
                            joints.getOrPut(step) { ArrayList() } += Joint(thr, into, count, ps, "pocket screws", false)
                            totals["$ps pocket screws"] = (totals["$ps pocket screws"] ?: 0) + count
                            continue
                        }
                    }
                    if (!brads && tThrough + MIN_BITE > longest) {
                        // A board rail meeting a thick leg end-on (a workbench's apron) is pocket-screwed from its
                        // inside face into the leg: far stronger than a long screw driven through the leg into its end grain.
                        val railT = (0..2).minOf { ax -> rel.hi[ii][ax] - rel.lo[ii][ax] }
                        val railLong = (0..2).maxByOrNull { ax -> rel.hi[ii][ax] - rel.lo[ii][ax] } == k
                        if (railLong && railT <= 25.5) {
                            val ps = if (fmt.isInch) fraction(when { railT < 16 -> 1.0; railT < 22 -> 1.25; else -> 1.5 }) else "${when { railT < 16 -> 25; railT < 22 -> 32; else -> 38 }} mm"
                            joints.getOrPut(step) { ArrayList() } += Joint(thr, into, count, ps, "pocket screws", false)
                            totals["$ps pocket screws"] = (totals["$ps pocket screws"] ?: 0) + count
                            continue
                        }
                        val cs = pick(COUNTERBORE_LEAVE + minOf(1.5 * COUNTERBORE_LEAVE, depth - 3.0), fmt, false)
                        // A counterbore deeper than 2 in (a screw down through a 5-1/2 in rail) is no longer practical.
                        if (cs == null || tThrough - COUNTERBORE_LEAVE < 3.0 || tThrough - COUNTERBORE_LEAVE > COUNTERBORE_MAX) {
                            joints.getOrPut(step) { ArrayList() } += Joint(thr, into, count, "corner brackets", "corner", false)
                            totals["corner brackets, with short screws"] = (totals["corner brackets, with short screws"] ?: 0) + count
                            continue
                        }
                        val sz = gauged(cs, COUNTERBORE_LEAVE, fmt)
                        joints.getOrPut(step) { ArrayList() } += Joint(thr, into, count, sz, "counterbored", false, (if (fmt.isInch) fmt.format(tThrough - COUNTERBORE_LEAVE) + " in" else "${Math.round(tThrough - COUNTERBORE_LEAVE)} mm"))
                        totals["$sz screws"] = (totals["$sz screws"] ?: 0) + count
                        val plug = if (fmt.isInch) "3/8 in wood plugs" else "10 mm wood plugs"
                        totals[plug] = (totals[plug] ?: 0) + count
                        continue
                    }
                }
                JoinMethod.POCKET_SCREWS -> {
                    // A pocket screw runs at an angle through the end of the joining part; its length follows that part's thickness.
                    // The pocket goes in the part whose end meets the other (the "into" part here), so its thickness sets the screw.
                    if (pinInstead) { size = pick(tThrough + minOf(1.5 * tThrough, depth - 3.0), fmt, true) ?: ""; kind = "brads" }
                    else if (faceOn) {
                        size = pick(tThrough + minOf(1.5 * tThrough, depth - 3.0), fmt, tThrough < THIN)?.let { if (tThrough < THIN) it else gauged(it, tThrough, fmt) } ?: ""
                        kind = if (tThrough < THIN) "brads" else "screws"
                    } else {
                        size = if (fmt.isInch) fraction(when { tInto < 16 -> 1.0; tInto < 22 -> 1.25; tInto < 30 -> 1.5; else -> 2.5 }) else "${when { tInto < 16 -> 25; tInto < 22 -> 32; tInto < 30 -> 38; else -> 63 }} mm"
                        kind = "pocket screws"
                    }
                }
                JoinMethod.DOWELS -> if (faceOn || pinInstead) {
                    val brad = tThrough < THIN || pinInstead
                    size = pick(tThrough + minOf(1.5 * tThrough, depth - 3.0), fmt, brad)?.let { if (brad) it else gauged(it, tThrough, fmt) } ?: ""
                    kind = if (brad) "brads" else "screws"
                } else {
                    val d = (tThrough / 2).coerceAtMost(12.0)
                    // Fluted dowel pins as sold, diameter and length: 3/8 x 1-1/2 in, 1/4 x 1-1/4 in; 8 x 40 mm, 6 x 30 mm.
                    size = if (fmt.isInch) (if (d >= 9.5) "3/8 x 1-1/2 in dowels" else "1/4 x 1-1/4 in dowels") else (if (d >= 8) "8 x 40 mm dowels" else "6 x 30 mm dowels")
                    kind = "dowels"
                }
                JoinMethod.GLUE_AND_BRADS -> { size = pick(tThrough + minOf(tThrough, depth - 3.0), fmt, true) ?: ""; kind = "brads" }
            }
            val glueOnly = glue || size.isEmpty()
            if (faceToFace && !glueOnly && method != JoinMethod.DOWELS) { kind = "brads"; size = pick(tThrough + minOf(tThrough, depth - 3.0), fmt, true) ?: size }
            // Screwed up through a board into what stands on it (a hutch's sides on a desk top): driven from underneath.
            if (kind == "screws" && k == 2 && !faceToFace && rel.lo[idx.getValue(into.id)].z >= rel.hi[idx.getValue(thr.id)].z - 0.5 &&
                idx.getValue(thr.id).let { ti -> rel.hi[ti].z - rel.lo[ti].z <= (0..2).minOf { ax -> rel.hi[ti][ax] - rel.lo[ti][ax] } + 0.5 }) kind = "under"   // thinnest up and down, square strips included
            joints.getOrPut(step) { ArrayList() } += Joint(thr, into, count, size, if (faceToFace) "laminate" else kind, glueOnly)
            if (faceToFace && !glueOnly) { val key = "$size brads"; totals[key] = (totals[key] ?: 0) + count; continue }
            if (!glueOnly) { val key = if (kind == "dowels") size else if (kind == "under") "$size screws" else "$size $kind"; totals[key] = (totals[key] ?: 0) + count }
        }
        // Housing joints the user kept: the part is glued into a groove cut in the other.
        val byId = live.associateBy { it.id }
        // A panel in the grooves of the frame round it: one line for the whole frame, with the groove's size.
        val framePanels = runCatching { splitziner.check.FramePanels.find(live) }.getOrDefault(emptyList())
        for (fp in framePanels) for (pid in fp.panels.distinctBy { byId[it]?.item }) {
            val p = byId[pid] ?: continue; val st = byId[fp.stiles[0]] ?: continue
            val pb = p.mesh.bounds; val sb = st.mesh.bounds
            val k = (0..2).minByOrNull { pb.extents[it] }!!
            val depth = (0..2).filter { it != k }.minOf { a -> minOf(sb.max[a], pb.max[a]) - maxOf(sb.min[a], pb.min[a]) }
            val step = stepOf[p.id] ?: mainOf(p)
            // Glass goes in a rabbet at the back, last, held by points or stops: never in a groove it could not leave.
            val glass = splitziner.check.FramePanels.isGlass(p)
            val toBack = if (glass) maxOf(sb.max[k] - pb.min[k], pb.max[k] - sb.min[k]).let { full -> (if (sb.max[k] - pb.max[k] < pb.min[k] - sb.min[k]) sb.max[k] - pb.min[k] else pb.max[k] - sb.min[k]) } else 0.0
            joints.getOrPut(step) { ArrayList() } += Joint(st, p, 0, "", if (glass) "glass" else "panel", true, at = "${fmt.format(if (glass) depth else splitziner.check.FramePanels.edgeRange(p, k)?.let { it.second - it.first } ?: pb.extents[k])} ${fmt.unitLabel}|${fmt.format(if (glass) toBack else depth)} ${fmt.unitLabel}|" +
                fp.sunk.filter { it.first == pid && it.second !in fp.stiles }.map { it.second }.distinctBy { byId[it]?.item }.joinToString(","), ref = byId[fp.rails[0]])
        }
        val inFrame = framePanels.flatMap { g -> g.sunk.map { setOf(it.first, it.second) } }.toSet()
        for ((pid, gid) in housed) {
            val p = byId[pid] ?: continue; val g = byId[gid] ?: continue
            // A tenon's mortise is told with its rail; a framed panel above.
            if (p.name == "Tenon" || g.name == "Tenon" || setOf(pid, gid) in inFrame) continue
            // A peg is glued into a hole drilled for it.
            if (p.kind == PartKind.ROD || p.name == "Peg") {
                val pb = p.mesh.bounds; val gb = g.mesh.bounds
                val ax = (0..2).maxByOrNull { pb.extents[it] }!!
                val dia = (0..2).filter { it != ax }.maxOf { pb.extents[it] }
                val depth = minOf(pb.max[ax], gb.max[ax]) - maxOf(pb.min[ax], gb.min[ax])
                val step = maxOf(mainOf(p), mainOf(g))
                joints.getOrPut(step) { ArrayList() } += Joint(g, p, 1, "", "peghole", false, "${fmt.format(dia)} ${fmt.unitLabel}|${fmt.format(depth)} ${fmt.unitLabel}")
                continue
            }
            val sameUnit = unit(p) == unit(g) && benchOf.containsKey(p.id) && benchOf.containsKey(g.id)
            val step = if (sameUnit) maxOf(stepOf[p.id] ?: 0, stepOf[g.id] ?: 0) else maxOf(mainOf(p), mainOf(g))
            joints.getOrPut(step) { ArrayList() } += Joint(g, p, 0, "", "housed", true)
        }
        // Drawers hang on a pair of slides each, fitted in the step the drawer goes in; one whose sides ride on wooden
        // runners just slides in on them.
        for (dr in runCatching { splitziner.check.Drawers.find(live) }.getOrDefault(emptyList())) {
            val ps = live.filter { it.id in dr.ids }
            val onRunners = live.filter { it.name == "Runner" }.count { r -> ps.any { q -> q.name == "Drawer side" && kotlin.math.abs(q.mesh.bounds.min.z - r.mesh.bounds.max.z) < 2.0 &&
                minOf(q.mesh.bounds.max.x, r.mesh.bounds.max.x) > maxOf(q.mesh.bounds.min.x, r.mesh.bounds.min.x) } } >= 2
            if (onRunners) {
                val box = ps.filter { it.id in dr.boxIds }.maxByOrNull { kotlin.math.abs(it.mesh.volume) } ?: ps.first()
                joints.getOrPut(ps.maxOf { mainOf(it) }) { ArrayList() }.add(0, Joint(box, box, 1, "", "runners", false))
                totals[HANDLE] = (totals[HANDLE] ?: 0) + 1
                continue
            }
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
            // A face frame standing proud of the inside of the side: the slide needs a spacer strip behind it to clear the stile.
            val spacer = side?.let { c -> live.filter { st -> st.name == "Stile" && st.mesh.bounds.max.y <= c.mesh.bounds.min.y + 1.0 && st.mesh.bounds.min.x < c.mesh.bounds.max.x && st.mesh.bounds.max.x > c.mesh.bounds.min.x }
                .maxOfOrNull { it.mesh.bounds.max.x - c.mesh.bounds.max.x } }?.takeIf { it > 3.0 }
            val at = side?.let { c -> "${fmt.format((bLo.z + bHi.z) / 2 - (floorRef?.mesh?.bounds?.max?.z ?: c.mesh.bounds.min.z))}$u|${fmt.format(maxOf(0.0, bLo.y - c.mesh.bounds.min.y))}$u" +
                (spacer?.let { "|${fmt.format(it)}$u" } ?: "") } ?: ""
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
            // Set into an opening in a face frame (an inset door): its hinges are made for that. A frame-and-panel
            // door is measured whole.
            val pb = doorGroup[p.id]?.let { g -> live.filter { doorGroup[it.id] == g }.map { it.mesh.bounds }.reduce { x, y -> x.union(y) } } ?: p.mesh.bounds
            val inset = live.any { o -> (o.name == "Stile" || o.name == "Face frame rail") && o.mesh.bounds.let { ob -> ob.min.y < pb.max.y - 1.0 && ob.max.y > pb.min.y + 1.0 &&
                ob.min.z < pb.max.z && ob.max.z > pb.min.z && (kotlin.math.abs(ob.max.x - pb.min.x) < 6.0 || kotlin.math.abs(ob.min.x - pb.max.x) < 6.0) } }
            joints.getOrPut(mainOf(p)) { ArrayList() }.add(0, Joint(p, p, n, if (inset) "inset hinges" else "hinges", "hinges", false, at))
            val hk = if (inset) "inset face-frame hinges" else "concealed (cup) hinges"
            totals[hk] = (totals[hk] ?: 0) + n
            totals[HANDLE] = (totals[HANDLE] ?: 0) + 1
        }
        // Sliding doors run in a double track along the top and bottom of their opening, fitted when they go in.
        if (slidingIds.isNotEmpty()) {
            val ds = live.filter { it.id in slidingIds }
            val span = ds.maxOf { it.mesh.bounds.max.x } - ds.minOf { it.mesh.bounds.min.x }
            val first = ds.maxByOrNull { it.mesh.bounds.min.y }!!   // the back one goes in first
            // Track is sold by length and cut down: at least the opening, in whole millimetres.
            val len = if (fmt.isInch) fmt.format(span) + " in" else "${Math.ceil(span).toInt()} mm"
            joints.getOrPut(ds.maxOf { mainOf(it) }) { ArrayList() }.add(0, Joint(first, ds.minByOrNull { it.mesh.bounds.min.y }!!, ds.size, "", "sliding", false, len))
            totals["double sliding-door track set, at least $len long"] = 1
            totals["flush pulls (recessed handles)"] = (totals["flush pulls (recessed handles)"] ?: 0) + ds.size
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
            // A cabinet with a hanging rail is screwed to the wall through it instead: two screws into each stud it crosses.
            val rail = live.firstOrNull { it.name == "Hanging rail" } ?: live.firstOrNull { it.name == "Wall cleat" }
            if (rail != null) totals[if (fmt.isInch) "#10 x 3 in screws to hang it (into studs)" else "5 x 75 mm screws to hang it (into studs)"] = 2 * maxOf(2, ceil(rail.mesh.bounds.extents.x / 406.4).toInt())
            else if (h > 760.0 && h > 2.0 * dd && splitziner.check.DesignCheck.ballastShelf(live) == null) totals["anti-tip wall strap kit"] = 1
        }
        // A wall cleat is screwed to the wall, and the piece hung on it, last.
        live.firstOrNull { it.name == "Wall cleat" }?.let { wc ->
            val partner = live.firstOrNull { it.name == "French cleat" } ?: return@let
            joints.getOrPut(mainOf(wc)) { ArrayList() } += Joint(wc, partner, 1, "", "wallcleat", false)
        }
        // A lid hangs on a piano hinge along the back, with a support that holds it open at any angle so it cannot
        // drop shut on fingers (or a child's head, in a toy box).
        live.firstOrNull { it.name == "Lid" }?.let { lid ->
            val lb = lid.mesh.bounds
            val under = live.filter { o -> o !== lid && kotlin.math.abs(o.mesh.bounds.max.z - lb.min.z) < 1.0 }
            // The back, or the top rail of a panelled back.
            val back = under.filter { o -> o.name == "Back" || o.name == "Back panel" }.maxByOrNull { it.mesh.bounds.extents.x }
                ?: under.filter { o -> frameOf[o.id] != null && o.mesh.bounds.extents.x >= 0.5 * lb.extents.x && o.mesh.bounds.max.y >= under.maxOf { it.mesh.bounds.max.y } - 1.0 }.maxByOrNull { it.mesh.bounds.extents.x }
                ?: return@let
            // Along a panelled back, the hinge runs the frame's whole width, stiles included.
            val backW = frameOf[back.id]?.let { f -> live.filter { frameOf[it.id] == f }.let { ms -> ms.maxOf { it.mesh.bounds.max.x } - ms.minOf { it.mesh.bounds.min.x } } } ?: back.mesh.bounds.extents.x
            val len = backW - (if (fmt.isInch) 50.8 else 50.0)
            val hinge = "piano hinge, ${fmt.format(len)} ${if (fmt.isInch) "in" else "mm"} long"
            joints.getOrPut(mainOf(lid)) { ArrayList() } += Joint(lid, back, 1, hinge, "lid", false)
            totals[hinge] = 1
            totals[LID_SUPPORT] = 1
        }
        // A case stands on its plinth and is screwed to it down through its bottom: the sides' and front's bottom edges
        // resting on the plinth are not glued or fixed again.
        if (joints.values.flatten().any { j -> setOf(j.through.name, j.into.name) == setOf("Plinth", "Bottom") })
            for (js in joints.values) js.removeAll { j -> (j.through.name == "Plinth") != (j.into.name == "Plinth") && j.through.name != "Bottom" && j.into.name != "Bottom" &&
                (j.through.mesh.bounds.max.z <= j.into.mesh.bounds.min.z + 1.0 || j.into.mesh.bounds.max.z <= j.through.mesh.bounds.min.z + 1.0) }
        // A runner or kicker is held by the screws into its apron; where its ends butt the rails, nothing is glued.
        for (js in joints.values) js.removeAll { j -> j.glueOnly && j.kind.isEmpty() && (j.through.name in RUNNER_NAMES || j.into.name in RUNNER_NAMES) }
        val every = joints.values.flatten()
        // Identical sub-assemblies are made more than once in one bench step. Every copy's joints are already in
        // the totals; the step itself tells how to make one, so it keeps only the first copy's joints.
        for (st in steps) if (st.subassembly && st.makeCount > 1) {
            // The first copy is all the parts its bench steps list, when it is built over several.
            val start = st.continues ?: st.step
            val first = steps.filter { it.step == start || it.continues == start }.flatMap { it.partIds }.toSet()
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
        val hardware = totals.entries.sortedWith(compareBy({ kindRank(it.key) }, { lengthOf(it.key) })).map { (k, n) -> if (k.startsWith("pair of ")) "$n ${if (n == 1) "pair" else "pairs"} of ${k.removePrefix("pair of ")}"
            // Gauge and length read as sold, after the count: "26 screws, #8 x 1-1/2 in", not "26 × #8 x 1-1/2 in screws".
            else Regex("""^((?:#\d+|\d+(?:\.\d+)?) x .+) screws$""").find(k)?.let { m -> "$n screws, ${m.groupValues[1]}" } ?: "$n × $k" } + listOf("Wood glue") +
            (if ((method == JoinMethod.SCREWS || method == JoinMethod.POCKET_SCREWS) && totals.keys.any { it.endsWith("screws") || it.contains("brackets") || it.endsWith("brads") }) listOf("Buy about 10% extra fasteners") else emptyList()) +
            // Outdoor timber: plain steel rusts outdoors, and the copper in treated lumber eats it.
            (if (live.any { splitziner.material.Materials.outdoor(it.material) } && totals.keys.any { it.endsWith("screws") || it.contains("brackets") || it.endsWith("brads") })
                listOf("All fasteners exterior-rated: coated deck screws rated for treated lumber, or stainless steel; and exterior (waterproof) wood glue") else emptyList())
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
        val gauges = plan.all.filter { !it.glueOnly && (it.kind == "screws" || it.kind == "applied" || it.kind == "under" || it.kind == "up" || it.kind == "counterbored" || it.kind == "cabinets") }.map { it.size.substringBefore(" x ", "") }.filter { it.isNotEmpty() }.distinct()
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
        val out = sortedMapOf<Int, Int>()
        for (j in plan.all) if (!j.glueOnly && (j.kind == "pocket screws" || j.kind == "pocketin")) { val it = j.into.item ?: continue; out[it] = (out[it] ?: 0) + j.count }
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

    /**
     * Where two parts at an angle to the world meet face to face in the frame of one of them: each part's thickness
     * across the contact and the contact's longest side, or null when they do not abut.
     */
    private fun tiltedContact(a: Part, b: Part): Triple<Double, Double, Double>? {
        for (frame in listOf(a, b)) {
            val axes = runCatching { splitziner.mesh.OrientedBounds.of(frame.mesh).axes }.getOrNull() ?: continue
            // Only a frame that is not the world's own (the box test already ran there).
            if (axes.all { ax -> maxOf(kotlin.math.abs(ax.x), kotlin.math.abs(ax.y), kotlin.math.abs(ax.z)) > 0.999 }) continue
            fun span(p: Part, ax: splitziner.math.Vec3): Pair<Double, Double> {
                val v = p.mesh.vertices; var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
                for (i in 0 until p.mesh.vertexCount) { val d = v[3 * i] * ax.x + v[3 * i + 1] * ax.y + v[3 * i + 2] * ax.z; if (d < lo) lo = d; if (d > hi) hi = d }
                return lo to hi
            }
            val sa = axes.map { span(a, it) }; val sb = axes.map { span(b, it) }
            val over = (0..2).map { k -> minOf(sa[k].second, sb[k].second) - maxOf(sa[k].first, sb[k].first) }
            val k = (0..2).minByOrNull { over[it] }!!
            if (over[k] > 2.0 || over[k] < -0.5 || (0..2).any { it != k && over[it] <= 1.0 }) continue
            return Triple(sa[k].second - sa[k].first, sb[k].second - sb[k].first, (0..2).filter { it != k }.maxOf { over[it] })
        }
        return null
    }

    /**
     * Where two parts whose boxes overlap (a tread on a notched stringer's step) still meet face to face: a face of
     * one lies on an opposite face of the other, in the same plane, over some area. Gives each part's size across
     * the contact and the contact's longest side, or null.
     */
    private fun faceContact(a: Part, b: Part): Triple<Double, Double, Double>? {
        // Flat faces by (axis, side, plane), each with the box of its triangles in the other two axes.
        fun faces(p: Part, sign: Int): Map<Pair<Int, Long>, DoubleArray> {
            val m = p.mesh; val n = m.faceNormals; val v = m.vertices; val t = m.triangles
            val out = HashMap<Pair<Int, Long>, DoubleArray>()
            for (f in 0 until m.triangleCount) for (k in 0..2) {
                if (n[3 * f + k] * sign < 0.999) continue
                val c = v[3 * t[3 * f] + k]
                val key = k to Math.round(c * 2)
                val (u, w) = (0..2).filter { it != k }
                val r = out.getOrPut(key) { doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE) }
                for (q in 0..2) { val vi = t[3 * f + q]; r[0] = minOf(r[0], v[3 * vi + u]); r[1] = minOf(r[1], v[3 * vi + w]); r[2] = maxOf(r[2], v[3 * vi + u]); r[3] = maxOf(r[3], v[3 * vi + w]) }
            }
            return out
        }
        val fa = faces(a, 1); val fb = faces(b, -1); val fa2 = faces(a, -1); val fb2 = faces(b, 1)
        var best: Triple<Double, Double, Double>? = null; var bestArea = 0.0
        for ((x, y) in listOf(fa to fb, fa2 to fb2)) for ((key, ra) in x) {
            val rb = y[key] ?: continue
            val du = minOf(ra[2], rb[2]) - maxOf(ra[0], rb[0]); val dw = minOf(ra[3], rb[3]) - maxOf(ra[1], rb[1])
            if (du <= 1.0 || dw <= 1.0 || du * dw <= bestArea) continue
            bestArea = du * dw
            val k = key.first
            best = Triple(a.mesh.bounds.extents[k], b.mesh.bounds.extents[k], maxOf(du, dw))
        }
        return best
    }

    /** What the chosen method drives into an edge, for "too thin to take …". */
    private fun fixings(plan: FastenPlan) = when (plan.method) {
        JoinMethod.GLUE_AND_BRADS -> "brads"
        JoinMethod.DOWELS -> "dowels"
        else -> "screws"
    }

    /** The lines a step gets instead of "then fasten". */
    /** The step's joint lines; a line said word for word twice (one glue-up told per joined pair) is said once. */
    fun lines(plan: FastenPlan, step: Int, itemName: (Part) -> String, maxLines: Int = 4): List<String> = linesAll(plan, step, itemName, maxLines).distinct()

    private fun linesAll(plan: FastenPlan, step: Int, itemName: (Part) -> String, maxLines: Int): List<String> {
        // "An inch" in a guide in inches, "25 mm" in one in millimetres (told by the sizes on the shopping list).
        val anInch = if (plan.hardware.any { it.contains(" mm") } && plan.hardware.none { it.contains(" in") }) "25 mm" else "an inch"
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
        val plainKinds = setOf("screws", "brads", "pocket screws", "pocketin", "dowels", "corner", "brackets", "laminate", "counterbored", "under", "")
        // Drawers hung in one step are one line however many sizes of box they are (graduated drawers).
        val groups0 = (js - cut.toSet()).groupBy { if (it.kind == "slides") Triple("drawers", "*", it.size + it.kind + (it.into === it.through)) else if (it.kind == "mitre") Triple("mitres", "*", it.size + it.at) else Triple(if ((it.kind in plainKinds || it.glueOnly) && it.through.item != null) "#${it.through.item}" else itemName(it.through), if (it.kind in plainKinds || it.glueOnly) "*" else it.into.item?.let { n -> "#$n" } ?: itemName(it.into), it.size + it.glueOnly + it.kind) }
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
        // Tenons go into their rails before the rails go into their mortises.
        for ((key, g) in groups.entries.sortedBy { if (it.value[0].kind == "tenon") 0 else 1 }.take(room)) {
            val j = g[0]; val n = g.sumOf { it.count }; val times = if (g.size > 1) " (${g.size} joints)" else ""
            out += if (j.kind == "hinges") {
                // Where they go: the hinge edge (from the front), the end ones a set distance in, any others evenly between.
                val per = n / g.size
                val sides = g.map { it.at.substringBefore('|') }.filter { it.isNotEmpty() }
                val end = g.first().at.substringAfter('|', "")
                val edge = when { sides.isEmpty() -> "on the side you want it hinged, "; sides.distinct().size == 1 -> "on its ${sides[0]}-hand edge (seen from the front), "; else -> "on its outer edge, so the pair opens from the middle, " }
                val place = if (end.isEmpty()) "" else " The top and bottom hinges go about $end from the ends" + (if (per > 2) ", the others evenly between" else "") + "."
                val gap = if (j.at.contains(" in")) "1/16 in" else "2 mm"
                if (j.size == "inset hinges") (if (g.size > 1) "Hang each ${key.first} in its opening in the face frame on ${count(per)} inset face-frame hinges (cup hinges with face-frame plates, or butt hinges let into the stile), ${edge.trimEnd(' ', ',')}. Shim it with $gap card all round so the gaps are even, then fix the hinges and take the shims out.$place Fit each one's handle or knob ${HANDLE_PLACE} its hinges."
                    else "Hang ${key.first} in its opening in the face frame on ${count(n)} inset face-frame hinges (cup hinges with face-frame plates, or butt hinges let into the stile), ${edge.trimEnd(' ', ',')}. Shim it with $gap card all round so the gaps are even, then fix the hinges and take the shims out.$place Fit its handle or knob ${HANDLE_PLACE} the hinges.")
                else if (g.size > 1) "Drill the hinge cups in the back of each ${key.first} ${edge}with a 35 mm bit (using the hinges' template), screw on ${count(per)} hinges each, then hang them and adjust them until the gaps are even.$place Fit each one's handle or knob ${HANDLE_PLACE} its hinges."
                else "Drill the hinge cups in the back of ${key.first} ${edge}with a 35 mm bit (using the hinges' template), screw on ${count(n)} hinges, then hang it and adjust it until the gaps are even.$place Fit its handle or knob ${HANDLE_PLACE} the hinges."
            }
            else if (j.kind == "pins") (if (g.size > 1) "For each ${key.first}, push ${count(n / g.size)} shelf pins into the holes at the height you want, level side to side, and lay it on them."
                else "Push ${count(n)} shelf pins into the holes at the height you want, level side to side, and lay ${key.first} on them.")
            else if (j.kind == "wallcleat") "Screw ${key.first} to the wall first, its sloped edge up and facing the wall, dead level, with two of the hanging screws into each stud it crosses (on masonry, plugs and screws made for it). Then lift the piece so ${key.second} drops over it and the slopes lock together; the cleats hold it tight to the wall."
            else if (j.kind == "lid") "Hinge ${key.first} along the back with the ${j.size}: set it on with its overhangs even, screw one leaf to its underside and the other to the top edge of ${key.second}, about $anInch from each end. Then fit the lid support at one end, following its instructions: it holds the lid open at any angle, so it cannot drop shut on fingers or a child's head."
            else if (j.kind == "runners") "It runs on its runners, not slides: rub candle or paste wax on them and on the bottom edges of its sides so it runs smoothly; plane a side a shaving at a time if it sticks. The kickers over its sides stop it tipping down as it opens. Fit its handle or knob in the middle of the front."
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
                val sp = g.map { it.at.split('|').getOrNull(2) }.firstOrNull { !it.isNullOrEmpty() }
                val spacerNote = if (sp == null) "" else " The face frame stands $sp proud of the inside of the cabinet: first screw a strip $sp thick (a spacer) to each side where the slide goes, so the slide clears the frame, and mount the slide on it."
                (if (g.size > 1) "For each drawer, screw one half of each ${j.size} slide to the inside of the cabinet and the other to the drawer's side, following the slides' instructions, then hang it.$mark" + (if (handle) " Fit each drawer's handle or knob in the middle of its front." else "")
                else "Screw one half of each ${j.size} slide to the inside of the cabinet and the other to the sides of the drawer, following the slides' instructions, then hang the drawer.$mark" + (if (handle) " Fit its handle or knob in the middle of the front." else "")) + spacerNote
            }
            else if (j.kind == "applied" && j.at.startsWith("name:")) {
                val names = g.map { it.at.removePrefix("name:") }.distinct()
                if (g.size > 1) "Fit ${if (names.size == 1) "each of ${names[0].replaceFirst("the drawer front", "the drawer fronts")}" else names.joinToString(" and ")}: close its drawer, hold the front in place with even gaps all round (double-sided tape or a couple of clamps hold it while you check), then open the drawer and screw the front on from inside with ${sized(n / g.size, j.size)} screws through ${key.first}. Fit each handle or knob in the middle of its front."
                else "Close the drawer and hold ${names[0]} in place with even gaps all round (double-sided tape or a couple of clamps hold it while you check), then open the drawer and screw it on from inside with ${sized(n, j.size)} screws through ${key.first}, into its stiles and rails. Fit its handle or knob in the middle of the front."
            }
            else if (j.kind == "applied") (if (g.size > 1) "Fit each ${key.second}: close its drawer, hold the front in place with even gaps all round (double-sided tape or a couple of clamps hold it while you check), then open the drawer and screw the front on from inside with ${sized(n / g.size, j.size)} screws through ${key.first}. Fit each handle or knob in the middle of its front."
                else "Close the drawer and hold ${key.second} in place with even gaps all round (double-sided tape or a couple of clamps hold it while you check), then open the drawer and screw it on from inside with ${sized(n, j.size)} screws through ${key.first}. Fit its handle or knob in the middle of the front.")
            else if (j.kind == "glass") {
                val (w, d) = j.at.split('|').let { it[0] to it.getOrElse(1) { "" } }
                val partsById = plan.all.flatMap { listOf(it.through, it.into) }.associateBy { it.id }
                val others = j.at.split('|').getOrElse(2) { "" }.split(',').mapNotNull { partsById[it] }.map { itemName(it) }.distinct()
                val all = listOf(key.first) + (if (others.isEmpty()) listOf("the rails") else others)
                val spare = if (plan.hardware.any { it.contains(" in") } || d.endsWith(" in")) "1/16 in" else "2 mm"
                "Glue the frame up empty: ${key.second} is glass (or acrylic) and goes in last. Then rout a rabbet $w wide and $d deep round the opening at the back of ${all.dropLast(1).joinToString(", ")} and ${all.last()} with a rabbeting bit, and square its corners with a chisel. " +
                    "Once the finish is dry, set the glass in, cut by a glass shop $spare smaller than the rabbet each way, and hold it with glazing points or thin wood stops pinned to the frame (no glue), so it can be replaced$times."
            }
            else if (j.kind == "panel") {
                val (t, d) = j.at.split('|').let { it[0] to it.getOrElse(1) { "" } }
                val partsById = plan.all.flatMap { listOf(it.through, it.into) }.associateBy { it.id }
                val others = j.at.split('|').getOrElse(2) { "" }.split(',').mapNotNull { partsById[it] }.map { itemName(it) }.distinct().ifEmpty { listOfNotNull(j.ref?.let { itemName(it) }) }
                val all = listOf(key.first) + (if (others.isEmpty()) listOf("the rails") else others)
                "Cut a groove $t wide and $d deep along the inner edges of ${all.dropLast(1).joinToString(", ")} and ${all.last()}, where the drawing shows it. " +
                    "Slide ${key.second} into the grooves as the frame goes together: a solid-wood panel is not glued, so it can swell and shrink across its width (a plywood or MDF panel can be glued in)$times."
            }
            else if (j.kind == "housed") "Glue ${key.second} into the dado or rabbet cut for it in ${key.first}$times (its drawing gives the size), and clamp until dry."
            else if (j.kind == "edgebolt") "Join ${key.first} to ${key.second} edge to edge from underneath with ${count(n)} worktop connector bolts$times: rout the recesses for them with the bolts' template, about $anInch from each end and evenly between, put a few biscuits or dowels in the edges to keep the faces level, then tighten the bolts until the joint closes flush."
            else if (j.kind == "edge") "Glue the edge of ${key.first} to the edge of ${key.second}$times and clamp until dry; they lie in one plane, so nothing can be screwed through."
            else if (j.kind == "tenon") "Glue ${key.second} into a mortise cut in the end of ${key.first}$times, standing out as drawn (a loose tenon); or, if you prefer, cut ${key.first} longer by the tenon's length and cut the tenon on it. Let the glue set before fitting it."
            else if (j.kind == "mortise") "Cut a mortise in ${key.first} for each tenon$times (its drawing gives the size and place), spread glue in the mortises and on the tenons of ${key.second}, push them home and clamp across the joint until dry. Check it is square as you clamp."
            else if (j.glueOnly) "Glue ${key.first} to ${key.second}$times; ${if (g.size > 1) "the joints are" else "the joint is"} too small for fasteners."
            else if (j.kind == "brackets") "Fix ${key.first} to ${key.second} from underneath with ${count(n)} corner brackets$times." +
                // Solid wood moves across its grain with the seasons; a top fixed tight splits or bows.
                (if (g.any { it.through.material.isNotBlank() && !splitziner.material.Materials.sheetGood(it.through.material) }) " As the top is solid wood, use brackets with slotted holes, or tabletop fasteners, and leave the screws a little loose across the grain so it can move with the seasons." else "")
            else if (j.kind == "rod") {
                val ats = g.map { it.at }.distinct()
                "Screw a closet rod socket (a cup or flange sized for the rod) to the inside of ${key.first} at each end of ${key.second}, its centre ${ats.first()}${if (ats.size > 1) " on each side" else ""}; open sockets let the rod lift out. Cut the rod a little short if the sockets need it, then drop it in."
            }
            else if (j.kind == "sliding") "Fit a double sliding-door track across the opening, ${j.at} long: screw the deep channel under the top and the shallow one on the bottom, both set back the same from the front edge (or cut two grooves in each, as the track's maker shows). ${if (key.first == key.second) "Lift one ${key.first} into the back channels first, then the other into the front ones" else "Lift ${key.first} into the back channels first, then ${key.second} into the front ones"}; they overlap where they pass. Fit a flush pull (a recessed handle) to each, so they can slide past each other."
            else if (j.kind == "bedrail") "Hang ${key.second} on ${key.first} with bed rail fasteners, ${if (g.size == 1) "one pair where it meets it" else "one pair at each end"}$times: let the slotted plate into a shallow recess in ${key.first} and screw it in, screw the hooked plate to the end of ${key.second} at the same height, then hook the rail on and tap it down. No glue, so the bed comes apart to move."
            else if (j.kind == "mitre") {
                val names = joinedNames(g.flatMap { listOf(itemName(it.through), itemName(it.into)) }.distinct())
                val corners = if (g.size == 1) "the mitred corner" else "the ${count(g.size)} mitred corners"
                val per = n / g.size
                if (j.at == "BEVEL") "Join $corners of $names: lay the pieces outside face up, points touching end to end, and run tape across each joint. Spread glue in the mitres, fold them up into the box (around its bottom, if the bottom sits inside them) and tape the last corner; check it is square (equal diagonals) and let it dry. Then pin each corner with ${sized(per, j.size)} brads, half from each side, or cut slots across the corners and glue in thin splines for strength."
                else "Join $corners of $names: glue the mitres and close them flat on the bench with a band clamp or corner clamps, checking the diagonals are equal. Once dry, drive ${sized(per, j.size)} brads across each corner from the outside edges (or a V-nail from the back); set the heads and fill."
            }
            else if (j.kind == "under") "From underneath, drill pilot holes up through ${key.first} and screw it to ${key.second} with ${sized(n, j.size)} screws$times."
            else if (j.kind == "counterbored") {
                val bit = if (j.at.endsWith(" in")) "3/8 in" else "10 mm"
                "Counterbore ${key.first} for the screws$times, as it is too thick to screw straight through: at each of ${count(n)} screw positions drill a $bit hole ${j.at} deep, so the head sits down in it, then drill the pilot on through. Glue and screw it to ${key.second} with ${sized(n, j.size)} screws, then glue $bit wooden plugs into the holes and trim them flush."
            }
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
            else if (j.kind == "up") "Screw ${key.second} down from below, up through ${key.first}, with ${sized(n, j.size)} screws$times; drill clearance holes in ${key.first} so the top pulls down tight, and check the screws stop short of the top's face."
            else if (j.kind == "cabinets") "Clamp neighbouring cabinets together with their fronts and tops flush, and screw through ${key.first} into ${key.second} with ${sized(n, j.size)} screws$times: near the front and the back, high and low. No glue, so they can come apart to move."
            else if (j.kind == "pocketin") "Fix ${key.second} to ${key.first} with ${sized(n, j.size)} pocket screws through pocket holes along ${if (key.second.contains(" and ")) "their" else "its"} edge, on the inside, so nothing shows outside$times. Glue the joint too."
            else if (j.kind == "faceglue") "Lay ${key.first} face down on a flat bench. Spread glue along the back edges of the cradle, lower it on with the edges flush all round, and weigh it down evenly (boards with weights on them, or clamps all round) until the glue sets. Nothing goes through the face: a nail or screw there would show in the painting."
            else if (j.kind == "caster") "Turn the piece upside down on a blanket. Screw ${if (g.size > 1) "each caster's plate" else "the caster's plate"} to the underside of ${key.first} with the screws that come with it, its centre about ${j.at} from each edge at the corner, so the wheel swivels clear."
            else if (j.kind == "foldhinge") "Hinge ${key.second} to the back of ${key.first} with a pair of ${j.size} butt hinges, so it folds in flat. Then tie it to the front legs at about knee height with a light chain or a cord, as long as the legs should spread: it stops the legs sliding apart under load."
            else if (j.kind == "peghole") j.at.split('|').let { (dia, dep) -> "Drill a $dia hole $dep deep in ${key.first} for ${if (g.size > 1) "each ${key.second}" else key.second}, where the drawing shows, square to the face (or at the angle drawn): a drill guide or a block drilled square keeps it straight. Glue ${if (g.size > 1) "the pegs" else "it"} in and wipe off the glue that squeezes out$times." }
            else if (j.kind == "front") "Glue ${key.first} to the front edge of ${key.second} and pin it with ${sized(n, j.size)} brads$times; set the brads just below the surface and fill the holes."
            else if (j.kind == "pocket screws" && plan.method != JoinMethod.POCKET_SCREWS) "Fix ${key.second} to ${key.first} with ${sized(n, j.size)} pocket screws through pocket holes in ${if (key.second.contains(" and ")) "their" else "its"} ends, on the ${if (j.through.name == "Stile" || j.into.name == "Stile") "back of the frame" else "face that will not show"}$times (or glue and dowel them)."
            else if (j.kind == "screws" && plan.method != JoinMethod.SCREWS) "Drill pilot holes through ${key.first} and screw it to ${key.second} with ${sized(n, j.size)} screws$times."
            else if (j.kind == "brads" && plan.method != JoinMethod.GLUE_AND_BRADS && plan.method != JoinMethod.SCREWS) "Glue ${key.first} to ${key.second} and fix with ${sized(n, j.size)} brads or small screws$times."
            else when (plan.method) {
                // Pocket holes go in the part whose end or edge meets the other's face, on a side that will not show.
                JoinMethod.POCKET_SCREWS -> if (j.kind == "screws") "Drill pilot holes through ${key.first} and screw it to ${key.second} with ${sized(n, j.size)} screws$times."
                    else if (j.kind == "brads") "Glue ${key.first} to ${key.second} and fix with ${sized(n, j.size)} brads or small screws$times."
                    else "Fix ${key.second} to ${key.first} with ${sized(n, j.size)} pocket screws through the pocket holes in its end$times."
                JoinMethod.DOWELS -> "Glue and dowel ${key.first} to ${key.second}: ${sized(n, j.size)}$times. Clamp until dry."
                JoinMethod.GLUE_AND_BRADS -> "Glue ${key.first} to ${key.second} and pin with ${sized(n, j.size)} brads$times."
                JoinMethod.SCREWS -> if (j.kind == "brads") "Glue ${key.first} to ${key.second} and fix with ${sized(n, j.size)} brads or small screws$times."
                    else "Drill pilot holes through ${key.first} and screw it to ${key.second} with ${sized(n, j.size)} screws$times."
            }
        }
        if (groups.size > room) {
            // Name the rest, so the reader knows which joints "the same way" means.
            fun how(j: Joint) = when {
                j.kind == "slides" -> "on slides"; j.kind == "runners" -> "on wooden runners"; j.kind == "lid" -> "on a piano hinge"; j.kind == "foldhinge" -> "on hinges, to fold"; j.kind == "caster" -> "caster plate screws"; j.kind == "faceglue" -> "glued, weighed down"; j.kind == "peghole" -> "glued in a drilled hole"; j.kind == "glass" -> "glass in a rabbet"; j.kind == "wallcleat" -> "hung on a French cleat"; j.kind == "applied" -> "screwed on from inside"; j.kind == "pocketin" -> "pocket screws from inside"; j.kind == "hinges" -> "on hinges"; j.kind == "pins" -> "on shelf pins"; j.kind == "edge" -> "edge glued"; j.kind == "edgebolt" -> "connector bolts"; j.kind == "sliding" -> "in sliding tracks"; j.kind == "housed" -> "glued in its groove"; j.glueOnly -> "glue"; j.kind == "thin" -> "groove or cleat"; j.kind == "brackets" || j.kind == "corner" -> "brackets"; j.kind == "counterbored" -> "counterbored screws"; j.kind == "rod" -> "in rod sockets"; j.kind == "mitre" -> "glued mitres"; j.kind == "under" -> "screwed from below"; j.kind == "bedrail" -> "on bed rail fasteners"; j.kind == "tenon" -> "loose tenon glued in"; j.kind == "mortise" -> "mortise and tenon"; j.kind == "laminate" || j.kind == "front" -> "glue and brads"; j.kind == "cabinets" -> "screwed together"; j.kind == "up" -> "screwed from below"
                else -> "${count(j.count)} ${j.size} ${j.kind}"
            }
            val rest = groups.entries.sortedBy { if (it.value[0].kind == "tenon") 0 else 1 }.drop(room).map { (k, g) -> "${k.first.removePrefix("item ")} to ${k.second.removePrefix("item ")} (${how(g[0])})" }
            out += "Also join, item to item: ${rest.joinToString(", ")}."
        }
        return out
    }
}
