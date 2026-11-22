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

class FastenPlan(val method: JoinMethod, val byStep: Map<Int, List<Joint>>, val hardware: List<String>)

/**
 * Works out, from where parts touch, what holds each joint. Workshop rules of thumb, not engineering:
 * a screw passes through the thinner part and goes into the other for about 1.5 times that
 * thickness, never so far that it could come out the other side; one fastener every 250 mm along the
 * joint, at least two; never into a part too thin to hold a screw.
 */
object Fastening {
    /** One fastener per this much joint length (mm): about 10 in, a common spacing for screws in furniture. */
    private const val SPACING = 250.0

    /** A screw needs at least this much wood around it in the part it goes into (mm), or it splits it. */
    private const val MIN_INTO = 15.0

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

    fun plan(parts: List<Part>, steps: List<BuildStep>, method: JoinMethod, fmt: LengthFormat): FastenPlan {
        val live = parts.filter { !it.excluded }
        val rel = DesignCheck.relations(live)
        val stepOf = HashMap<String, Int>()
        // A part's step is the step it is joined in: its bench step if it has one, else its main step.
        for (st in steps.sortedByDescending { it.step }) for (id in st.partIds) stepOf[id] = st.step
        for (st in steps) if (st.subassembly) for (id in st.partIds) stepOf[id] = st.step
        // Copies of a sub-assembly made in one bench step: their parts are joined in that step too.
        val idx = live.withIndex().associate { it.value.id to it.index }
        val joints = HashMap<Int, MutableList<Joint>>()
        val totals = LinkedHashMap<String, Int>()
        val seen = HashSet<Long>()
        for (i in live.indices) for (j in rel.touching[i]) {
            if (!seen.add(minOf(i, j).toLong() * 100000 + maxOf(i, j))) continue
            val a = live[i]; val b = live[j]
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
            val step = maxOf(stepOf[a.id] ?: 0, stepOf[b.id] ?: 0)
            val glue = length < SMALLEST
            var count = maxOf(2, ceil(length / SPACING).toInt())
            var size = ""; var kind = ""
            val width = dims.min()
            // Two pieces lying face to face (a laminated frame, a panel on a panel) are glued and clamped;
            // a few fasteners only hold them while the glue sets.
            val faceToFace = width > 3 * tThrough
            // A top resting on its supports is never fixed through its visible face: brackets go underneath.
            val topDown = k == 2 && (rel.lo[idx.getValue(thr.id)].z >= rel.hi[idx.getValue(into.id)].z - 2.0) && width > 2 * tThrough
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
            if (!glue && !faceToFace && around < MIN_INTO && method != JoinMethod.GLUE_AND_BRADS) {
                // The part it would go into is meeting edge-on and too thin to hold anything: nothing can be driven
                // into that edge. It is glued, in a groove or against a cleat, which is a joint for the maker to choose.
                joints.getOrPut(step) { ArrayList() } += Joint(thr, into, 0, "", "thin", false)
                continue
            }
            if (!glue) when (method) {
                JoinMethod.SCREWS -> {
                    val brads = tThrough < THIN
                    // Through the first part, then into the second for 1.5 times that thickness but never within 3 mm of its far side.
                    val want = tThrough + minOf(1.5 * tThrough, depth - 3.0)
                    size = pick(want, fmt, brads) ?: ""; kind = if (brads) "brads" else "screws"
                }
                JoinMethod.POCKET_SCREWS -> {
                    // A pocket screw runs at an angle through the end of the joining part; its length follows that part's thickness.
                    val t = tThrough
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
        // Identical sub-assemblies are made more than once: their fasteners count that many times.
        for (st in steps) if (st.subassembly && st.makeCount > 1) {
            for (jt in joints[st.step].orEmpty()) if (!jt.glueOnly && jt.kind != "thin") {
                val key = when (jt.kind) { "dowels" -> jt.size; "brackets" -> "corner brackets, with short screws"; "laminate" -> "${jt.size} brads"; else -> "${jt.size} ${jt.kind}" }
                totals[key] = (totals[key] ?: 0) + jt.count * (st.makeCount - 1)
            }
        }
        // By kind (screws, then brads, then the rest), shortest first.
        fun lengthOf(k: String): Double {
            val m = Regex("""^(\d+)?(?:-?(\d)/(\d))?\s*(in|mm)""").find(k) ?: return 0.0
            val whole = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val frac = if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toDouble() / m.groupValues[3].toDouble() else 0.0
            return whole + frac
        }
        fun kindRank(k: String) = when { k.contains("bracket") -> 4; k.contains("pocket") -> 0; k.endsWith("screws") -> 1; k.contains("dowel") -> 2; k.endsWith("brads") -> 3; else -> 4 }
        val hardware = totals.entries.sortedWith(compareBy({ kindRank(it.key) }, { lengthOf(it.key) })).map { (k, n) -> "$n × $k" } + listOf("Wood glue") +
            (if (method == JoinMethod.SCREWS || method == JoinMethod.POCKET_SCREWS) listOf("Buy about 10% extra fasteners") else emptyList())
        return FastenPlan(method, joints, hardware)
    }

    /** The lines a step gets instead of "then fasten". */
    fun lines(plan: FastenPlan, step: Int, itemName: (Part) -> String, maxLines: Int = 4): List<String> {
        val js = plan.byStep[step].orEmpty()
        if (js.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        // Joints of the same kind between the same items are told once.
        val groups = js.groupBy { Triple(itemName(it.through), itemName(it.into), it.size + it.glueOnly) }
        for ((key, g) in groups.entries.take(maxLines)) {
            val j = g[0]; val n = g.sumOf { it.count }; val times = if (g.size > 1) " (${g.size} joints)" else ""
            out += if (j.glueOnly) "Glue ${key.first} to ${key.second}$times; the joint is too small for fasteners."
            else if (j.kind == "brackets") "Fix ${key.first} to ${key.second} from underneath with $n corner brackets$times."
            else if (j.kind == "laminate") "Glue ${key.first} face to face on ${key.second}, clamp, and pin with $n ${j.size} brads$times."
            else if (j.kind == "thin") "Glue the edge of ${key.second} to ${key.first}$times. It is too thin for fasteners: set it in a groove, or glue a small strip (a cleat) alongside to hold it."
            else when (plan.method) {
                JoinMethod.POCKET_SCREWS -> "Drill ${n} pocket holes in ${key.first} and fix it to ${key.second} with ${j.size} pocket screws$times."
                JoinMethod.DOWELS -> "Glue and dowel ${key.first} to ${key.second}: $n ${j.size}$times. Clamp until dry."
                JoinMethod.GLUE_AND_BRADS -> "Glue ${key.first} to ${key.second} and pin with $n ${j.size} brads$times."
                JoinMethod.SCREWS -> if (j.kind == "brads") "Glue ${key.first} to ${key.second} and fix with $n ${j.size} brads or small screws$times."
                    else "Drill pilot holes through ${key.first} and screw it to ${key.second} with $n ${j.size} screws$times."
            }
        }
        if (groups.size > maxLines) out += "Fasten the other ${groups.size - maxLines} joint${if (groups.size - maxLines == 1) "" else "s"} of this step the same way."
        return out
    }
}
