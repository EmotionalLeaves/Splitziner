package splitziner.guide

import splitziner.explode.BuildStep
import splitziner.explode.ExplodeOptions
import splitziner.explode.ExplodedView
import splitziner.parts.CutListRow
import splitziner.parts.Part
import splitziner.sheet.DisplayList
import splitziner.sheet.Furniture
import splitziner.sheet.HAlign
import splitziner.sheet.Image
import splitziner.sheet.LineStyle
import splitziner.sheet.StrokeFont
import splitziner.sheet.VAlign
import splitziner.view.ViewDef

/** One step of the build guide, ready to lay out: a picture, what to gather, and what to do. */
class GuideStep(
    val number: Int,
    val title: String,
    val picture: DisplayList,
    /** Instruction lines; the user can rewrite these. */
    val text: List<String>,
    /** "Item 3 PANEL .75 X 22.00 X 34.50" with how many this step uses. */
    val parts: List<Pair<String, Int>>,
    val check: String?,
)

/** What the user may change about a step's wording. */
data class StepText(val title: String? = null, val lines: List<String>? = null, val check: String? = null)

object GuideSteps {
    /** Step pictures are drawn at this size in mm and scaled onto the page. */
    const val PICTURE_W = 170.0
    const val PICTURE_H = 115.0

    /** A step's picture frames at least this share of the finished piece's width and height. */
    private const val MIN_FRAME = 0.55

    /** How an instruction describes the side a part comes in from, given the way it leaves. */
    /**
     * Where a part goes along a longer one it is fixed to, when nothing else puts it there: "Set item 5 (leg) 3/4 in
     * in from each end of item 2 (side rail)". Read off the model along the longer part's length and up its height;
     * flush ends need no words, and a part held between two others (a cleat between the legs) is placed by them.
     */
    fun placing(cur: List<Part>, fmt: splitziner.dims.LengthFormat, label: (Part) -> String): List<String> {
        val out = LinkedHashMap<String, MutableList<Double>>()
        val u = if (fmt.isInch) " in" else " mm"
        fun b(p: Part) = p.mesh.bounds
        fun touch(a: Part, c: Part) = (0..2).all { k -> b(a).min[k] <= b(c).max[k] + 0.5 && b(c).min[k] <= b(a).max[k] + 0.5 }
        for (s in cur) {
            // The longest part it touches that is longer than it along its own length.
            val host = cur.filter { it !== s && touch(it, s) }.maxByOrNull { h -> (0..2).maxOf { k -> b(h).extents[k] } } ?: continue
            val len = (0..2).maxByOrNull { k -> b(host).extents[k] }!!
            for (k in listOf(len, 2).distinct()) {
                if (b(s).extents[k] >= b(host).extents[k] - 1.0) continue
                val lo = b(s).min[k] - b(host).min[k]; val hi = b(host).max[k] - b(s).max[k]
                if (lo < -0.5 || hi < -0.5 || lo < 1.0 || hi < 1.0) continue
                // Held on both sides along this axis by other parts: they place it.
                val others = cur.filter { it !== s && it !== host && touch(it, s) }
                if (others.any { o -> b(o).max[k] <= b(s).min[k] + 0.5 } && others.any { o -> b(o).min[k] >= b(s).max[k] - 0.5 }) continue
                val near = minOf(lo, hi)
                val where = if (k == 2) (if (lo <= hi) "up from the bottom edge" else "down from the top edge") else "from ${if (cur.count { it.item == s.item } > 1) "each end" else "the end"}"
                out.getOrPut("${label(s)}|$where|${label(host)}") { ArrayList() } += near
            }
        }
        return out.entries.map { (key, ds) ->
            val (what, where, of) = key.split('|')
            val d = ds.distinct().map { Math.round(it * 2) / 2.0 }.distinct()
            if (d.size == 1) "Set ${if (ds.size > 1) "each $what" else what} ${fmt.format(d[0])}$u $where of $of."
            else "Set ${ds.size}× $what ${d.joinToString(" and ") { fmt.format(it) + u }} $where of $of, as drawn."
        }
    }

    private fun whereFrom(x: Double, y: Double, z: Double): String? = when {
        z > 0.5 -> "from above"; z < -0.5 -> "from below"
        y < -0.5 -> "from the front"; y > 0.5 -> "from the back"
        x < -0.5 -> "from the left"; x > 0.5 -> "from the right"
        else -> null
    }

    /** Identifies a step by the parts in it, so the user's wording follows the step when steps are reordered. */
    fun stepKey(partIds: List<String>): String = partIds.sorted().joinToString(",")

    /** [overrides] is keyed by [stepKey]. */
    /**
     * The even gap between like parts laid side by side (slats, spaced boards), or null when they are not evenly
     * spaced or there are fewer than three. Read from the whole model, so the first step that lays some can say it.
     */
    fun evenGap(like: List<Part>): Double? {
        if (like.size < 3) return null
        val b = like.map { it.mesh.bounds }
        // Spread along the axis where their centres differ most.
        val ax = (0..2).maxByOrNull { k -> b.maxOf { it.center[k] } - b.minOf { it.center[k] } } ?: return null
        val sorted = b.sortedBy { it.min[ax] }
        val gaps = sorted.zipWithNext { a, c -> c.min[ax] - a.max[ax] }
        if (gaps.any { it < 3.0 }) return null
        return if (gaps.maxOrNull()!! - gaps.minOrNull()!! <= 1.5) gaps.average() else null
    }

    fun build(parts: List<Part>, steps: List<BuildStep>, overrides: Map<String, StepText> = emptyMap(), fasten: splitziner.joinery.FastenPlan? = null, fmt: splitziner.dims.LengthFormat? = null): List<GuideStep> {
        val gapTold = HashSet<Int>()
        val drawn = parts.filter { !it.excluded }
        val byId = drawn.associateBy { it.id }
        // One view for every step, so the reader keeps their bearings.
        val view: ViewDef = ExplodedView.pickView(drawn)
        // And one framing: every picture leaves room for the whole finished object, so nothing jumps in size between steps.
        val frame = ExplodedView.assembledBounds(drawn, view)
        val done = LinkedHashSet<String>()
        val out = ArrayList<GuideStep>()
        // " (side)" after an item number, when its parts share a name of their own.
        fun named(ps: List<Part>): String = ps.filter { it.name != it.id }.map { it.name }.distinct().singleOrNull()?.let { " (${it.lowercase()})" } ?: ""
        // Sub-assemblies made on the bench are referred to whole in later steps. A unit is the boards that
        // came from one drawn part; its kind (which items, how many of each) says which bench step made it.
        val keyOf = splitziner.explode.AssemblyPlan.unitKeys(drawn)
        fun unit(p: Part) = keyOf[p.id] ?: p.id.substringBefore('.')
        val unitOf = drawn.groupBy { unit(it) }.filter { it.value.size >= 2 }
        fun kind(ms: List<Part>) = ms.mapNotNull { it.item }.sorted()
        val benchOf = HashMap<List<Int>, Int>()
        for (st in steps) if (st.subassembly) benchOf[kind(st.partIds.mapNotNull { byId[it] })] = st.step
        // What a bench-made unit is called: a frame when it has legs (a table's end, a bed's side), else a sub-assembly.
        val frameBench = steps.filter { st -> st.subassembly && st.partIds.any { byId[it]?.name?.endsWith("eg") == true } }.map { it.step }.toSet()
        // How many units from each bench step are already in place, so the next is "the other".
        val placedUnits = HashMap<Int, Int>()
        fun otherOf(bench: Int) = if ((steps.firstOrNull { it.step == bench }?.makeCount ?: 2) - (placedUnits[bench] ?: 0) == 1) "the other" else "another"
        fun unitWord(bench: Int, n: Int) = if (bench in frameBench) (if (n > 1) "frames" else "frame") else (if (n > 1) "sub-assemblies" else "sub-assembly")
        for (st in steps) {
            val cur = st.partIds.mapNotNull { byId[it] }
            // In a main step, each made sub-assembly counts as one thing to fit.
            val units = if (st.subassembly) emptyMap() else cur.groupBy { unit(it) }.filter { (root, _) -> root in unitOf && benchOf.containsKey(kind(unitOf.getValue(root))) }
            val loose = cur.filter { unit(it) !in units }
            val count = LinkedHashMap<Int?, Int>()
            for (p in loose) count[p.item] = (count[p.item] ?: 0) + 1
            val unitCount = LinkedHashMap<Int, MutableList<List<Part>>>()
            for ((root, ms) in units) unitCount.getOrPut(benchOf.getValue(kind(unitOf.getValue(root)))) { ArrayList() } += ms
            // This step's parts pulled out, earlier parts in place and greyed, later parts left out.
            // A sub-assembly is made on the bench: only its own parts, framed to fill the picture.
            val base = !st.subassembly && steps.firstOrNull { !it.subassembly }?.step == st.step
            val scene = (if (st.subassembly) emptyList() else drawn.filter { it.id in done }.map { it.copy(explode = null) }) +
                cur.map { p -> p.copy(explode = if (base) null else if (st.explodeOf.containsKey(p.id)) st.explodeOf[p.id] else p.explode, qty = count[p.item] ?: 1) }
            // Framed on what is built so far, but never closer than about half the finished piece, so early steps
            // with a few parts are big enough to read and the picture does not jump about from step to step.
            val stepFrame = if (st.subassembly) null else {
                val sb = ExplodedView.assembledBounds(scene, view)
                val hw = maxOf((sb[2] - sb[0]) / 2, MIN_FRAME * (frame[2] - frame[0]) / 2); val hh = maxOf((sb[3] - sb[1]) / 2, MIN_FRAME * (frame[3] - frame[1]) / 2)
                val cx = ((sb[0] + sb[2]) / 2).coerceIn(frame[0] + hw, maxOf(frame[0] + hw, frame[2] - hw))
                val cy = ((sb[1] + sb[3]) / 2).coerceIn(frame[1] + hh, maxOf(frame[1] + hh, frame[3] - hh))
                doubleArrayOf(cx - hw, cy - hh, cx + hw, cy + hh)
            }
            val pic = ExplodedView.render(
                scene, PICTURE_W, PICTURE_H,
                ExplodeOptions(balloons = true, arrows = !base, qtyTags = true, highlight = cur.map { it.id }.toSet(), ghost = done.toSet(), textHeight = 3.4,
                    unitCounts = unitCount.values.flatMap { us -> us.flatMap { u -> u.map { it.id to us.size } } }.toMap()),
                view, "step%02d".format(st.step), stepFrame,
            )
            // A picture drawn from behind (the side the parts go in from) says so, or "from the back" reads as a mistake.
            if (view.n.y > 0.1) pic.text(2.0, 2.0, "SEEN FROM THE BACK", 2.6, vAlign = VAlign.BOTTOM)
            val lines = ArrayList<String>()
            for ((bench, us) in unitCount) {
                val sides = us.mapNotNull { u -> u[0].explode?.normalized()?.let { whereFrom(it.x, it.y, it.z) } }.distinct()
                val where = if (base || sides.isEmpty()) "" else " " + sides.joinToString(" and ")
                val drawer = us.all { u -> keyOf[u[0].id]?.startsWith("drawer") == true }
                // The second of two frames made alike is "the other frame".
                val all = if (us.size == 1 && (placedUnits[bench] ?: 0) > 0) otherOf(bench) else if (us.size == 1 && (steps.firstOrNull { it.step == bench }?.makeCount ?: 1) > 1) "one" else when (us.size) { 1 -> "the"; 2 -> "both"; else -> "all ${us.size}" }
                lines += if (drawer) "Slide ${if (us.size > 1) "$all drawers" else "the drawer"} from step $bench in$where."
                    else "${if (base) "Place" else "Fit"} $all ${unitWord(bench, us.size)} from step $bench$where."
            }
            // Consecutive parts that go in the same way are named in one instruction, in order.
            val fits = ArrayList<Triple<String, String, String>>()   // verb, where, what
            for ((item, n) in count) {
                val sides = ArrayList<String>()
                // On the bench a part moves as the bench picture shows it, not as its unit later goes into the build.
                for (p in loose) if (p.item == item) (if (st.explodeOf.containsKey(p.id)) st.explodeOf[p.id] else p.explode)?.let { e -> val d = e.normalized(); whereFrom(d.x, d.y, d.z)?.let { if (it !in sides) sides += it } }
                val where = when {
                    base || (st.step == 1 && !st.subassembly) || sides.isEmpty() -> ""
                    sides.size == 2 && sides.all { it.startsWith("from the ") } -> " from the ${sides[0].removePrefix("from the ")} and ${sides[1].removePrefix("from the ")}"
                    else -> " " + sides.joinToString(", ")
                }
                // On the bench, the part that does not move is laid down first and the others joined to it.
                val still = st.subassembly && cur.any { it.item == item && st.explodeOf.containsKey(it.id) && st.explodeOf[it.id] == null }
                val verb = if (base) "Place" else if (still) "Lay down" else if (st.subassembly) "Join" else "Fit"
                val what = if (item != null) "item $item" + named(loose.filter { it.item == item }) else "the part"
                val one = "${if (n > 1) "$n× " else ""}$what"
                if (still) lines.add(0, "$verb $one.") else fits += Triple(verb, where, one)
            }
            var i = 0
            while (i < fits.size) {
                var j = i
                while (j + 1 < fits.size && fits[j + 1].first == fits[i].first && fits[j + 1].second == fits[i].second) j++
                val run = fits.subList(i, j + 1).map { it.third }
                lines += if (run.size == 1) "${fits[i].first} ${run[0]}${fits[i].second}."
                else {
                    // "items 3, 4 and 7" rather than "item 3, item 4 and item 7"; counts keep their own words.
                    val names = run.map { r -> Regex("""^(\d+)× item (\S+)(?: \((.*)\))?$""").find(r)?.let { m -> "${m.groupValues[2]} (${if (m.groupValues[3].isNotEmpty()) "${m.groupValues[3]}, " else ""}${m.groupValues[1]}×)" } ?: r.removePrefix("item ") }
                    val joined = names.dropLast(1).joinToString(", ") + " and " + names.last()
                    "${fits[i].first} items $joined${fits[i].second}, in that order."
                }
                i = j + 1
            }
            // Like parts laid side by side, evenly spaced (slats): the gap, once, the first time some go in.
            if (fmt != null && !st.subassembly) for ((item, n) in count) {
                if (item == null || n < 2 || item in gapTold) continue
                val like = drawn.filter { it.item == item }
                val flat = like.all { p -> p.mesh.bounds.extents.let { it.z <= minOf(it.x, it.y) } }
                val gap = if (flat) evenGap(like) else null
                if (gap != null) {
                    gapTold += item
                    val g = "${fmt.format(gap)} ${if (fmt.isInch) "in" else "mm"}"
                    // Named from the parts going in now (the shelves), not every part of the item (its bottom and top too).
                    val nm = cur.filter { it.item == item && it.name != it.id }.map { it.name }.distinct().singleOrNull()?.lowercase()
                    val what = if (nm != null) "the ${if (nm.endsWith("helf")) nm.dropLast(1) + "ves" else if (nm.endsWith("s")) nm else nm + "s"} (item $item)" else "the item $item parts"
                    lines += "Space $what $g apart, evenly; a spacer block cut to $g keeps every gap the same."
                }
            }
            // Pieces set out apart (two end frames, a desk's two sides) stand alone until the next step ties them, so the
            // step says how far apart they go, read off the model: outside to outside, and any piece between them.
            if (base && cur.size > 1) {
                fun near(a: Part, b: Part) = (0..2).all { k -> a.mesh.bounds.min[k] <= b.mesh.bounds.max[k] + 0.5 && b.mesh.bounds.min[k] <= a.mesh.bounds.max[k] + 0.5 }
                val comps = ArrayList<List<Part>>(); val left = cur.toMutableList()
                while (left.isNotEmpty()) {
                    val c = arrayListOf(left.removeAt(0)); var grew = true
                    while (grew) { grew = false; val more = left.filter { o -> c.any { near(it, o) } }; if (more.isNotEmpty()) { c += more; left.removeAll(more.toSet()); grew = true } }
                    comps += c
                }
                if (comps.size > 1) {
                    fun lo(c: List<Part>, k: Int) = c.minOf { it.mesh.bounds.min[k] }
                    fun hi(c: List<Part>, k: Int) = c.maxOf { it.mesh.bounds.max[k] }
                    // The axis they stand apart along: where no two of them overlap.
                    val axis = (0..2).firstOrNull { k -> comps.indices.all { i -> comps.indices.all { j -> i == j || hi(comps[i], k) <= lo(comps[j], k) + 0.5 || hi(comps[j], k) <= lo(comps[i], k) + 0.5 } } }
                    fun label(c: List<Part>): String {
                        val u = units.entries.firstOrNull { (_, ms) -> ms.any { it in c } }
                        if (u != null) { val bench = benchOf.getValue(kind(unitOf.getValue(u.key))); return "the ${unitWord(bench, 1)} from step $bench" }
                        return c.first().let { "item ${it.item ?: "-"}" + named(listOf(it)) }
                    }
                    if (fmt != null && axis != null) {
                        val sorted = comps.sortedBy { lo(it, axis) }
                        val u = if (fmt.isInch) " in" else " mm"
                        val span = hi(sorted.last(), axis) - lo(sorted.first(), axis)
                        val mid = (lo(sorted.first(), axis) + hi(sorted.last(), axis)) / 2
                        val between = sorted.drop(1).dropLast(1).map { c ->
                            val centre = (lo(c, axis) + hi(c, axis)) / 2
                            if (kotlin.math.abs(centre - mid) < 2.0) "${label(c)} centred between them"
                            else "${label(c)} ${fmt.format(lo(c, axis) - hi(sorted.first(), axis))}$u in from ${label(sorted.first())}"
                        }
                        // The outer two lined up end to end along the other level axis (a bed's side frames), when the model has them so.
                        val other = (0..1).firstOrNull { it != axis }
                        val level = other != null && kotlin.math.abs(lo(sorted.first(), other) - lo(sorted.last(), other)) < 1.0 && kotlin.math.abs(hi(sorted.first(), other) - hi(sorted.last(), other)) < 1.0
                        lines += "Stand them ${fmt.format(span)}$u apart, outside to outside" + (if (between.isEmpty()) "" else ", with " + between.joinToString(" and ")) +
                            (if (level && axis != 2) ", ends lined up and square to each other;" else ", square to each other;") + " a helper, clamps or a prop holds them up until the next step ties them together."
                    } else lines += "Stand them in place, spaced to the overall sizes on the drawing and square to each other; a helper, clamps or a prop holds them up until the next step ties them together."
                }
            }
            // How the step is held together, when the joining method is known; otherwise the general reminder.
            val how = if (fasten == null) emptyList() else splitziner.joinery.Fastening.lines(fasten, st.step, { p -> "item ${p.item ?: "-"}" + named(listOf(p)) })
            if (st.subassembly) {
                if (fmt != null) lines += placing(cur, fmt, { p -> "item ${p.item ?: "-"}" + named(listOf(p)) })
                lines += if (how.isEmpty()) "Check it is flat and square (equal diagonals), then fasten." else "Check it is flat and square (equal diagonals)."
                lines += how
                if (st.makeCount > 1) lines += "Make ${st.makeCount} of these, all the same."
            } else if (!base || how.isNotEmpty()) {
                // Doors, drawers and shelves on pins are hung, not fixed: their own lines say how to line them up.
                val hung = fasten?.byStep?.get(st.step).orEmpty().let { js -> js.isNotEmpty() && js.all { it.kind in setOf("hinges", "slides", "pins", "applied") } }
                // The general reminder to check square is in "Before you build"; a step only says it when it has no
                // fastening of its own to describe.
                if (!base && !hung && how.isEmpty()) lines += "Check it is square and seated against the parts already installed, then fasten."
                lines += how
            }
            val list = unitCount.map { (bench, us) -> (if (us.all { u -> keyOf[u[0].id]?.startsWith("drawer") == true }) "Drawer from step $bench" else "${unitWord(bench, 1).replaceFirstChar { it.uppercase() }} from step $bench") to us.size } +
                // In ordinary type, as the rest of the guide is: "Item 3 (top): panel 3/4 x 11-3/4 x 30-1/2".
                count.map { (item, n) -> "Item ${item ?: "-"}${named(loose.filter { it.item == item })}: ${loose.first { it.item == item }.desc.lowercase()}" to n }
            val o = overrides[stepKey(st.partIds)]
            val auto = when {
                st.subassembly -> {
                    val its = count.keys.filterNotNull().sorted()  // a bench step has no units, so count holds all its parts
                    val names = if (its.size == 1) "item ${its[0]}" else "items ${its.dropLast(1).joinToString(", ")} and ${its.last()}"
                    val isDrawer = st.partIds.all { keyOf[it]?.startsWith("drawer") == true }
                    if (isDrawer) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} drawer boxes from" else "make the drawer box from"} $names"
                    else if (st.step in frameBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} frames from" else "make the frame from"} $names"
                    else "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} by joining" else "join"} $names"
                }
                else -> {
                    // What goes on, in plain words: "Step 6: the back panel", "Step 5: 4 shelves", "Step 8: hang the drawers".
                    fun plural(w: String, n: Int) = if (n == 1) w else when {
                        w.endsWith("helf") -> w.dropLast(1) + "ves"
                        w.endsWith("s") -> w
                        else -> w + "s"
                    }
                    val bits = ArrayList<String>()
                    // Drawers of several sizes are still just drawers: "hang the 4 drawers".
                    val drawers = unitCount.values.filter { us -> us.all { u -> keyOf[u[0].id]?.startsWith("drawer") == true } }.sumOf { it.size }
                    if (drawers > 0) bits += if (drawers == 1) "hang the drawer" else "hang the $drawers drawers"
                    for ((bench, us) in unitCount) {
                        if (us.all { u -> keyOf[u[0].id]?.startsWith("drawer") == true }) continue
                        bits += if (us.size == 1) "${if ((placedUnits[bench] ?: 0) > 0) otherOf(bench) else "the"} ${unitWord(bench, 1)} from step $bench" else "the ${us.size} ${unitWord(bench, us.size)} from step $bench"
                    }
                    // Parts of one name are counted together, whatever their item numbers: "3 drawer fronts".
                    val byName = LinkedHashMap<String, Int>()
                    for ((item, n) in count) {
                        val nm = loose.filter { it.item == item && it.name != it.id }.map { it.name }.distinct().singleOrNull()?.lowercase()
                        val key = nm ?: "\u0000" + (if (item != null) "item $item" else "part")
                        byName[key] = (byName[key] ?: 0) + n
                    }
                    for ((key, n) in byName) {
                        if (key.startsWith("\u0000")) { val w = key.drop(1); bits += if (n == 1) w else "$n× $w" }
                        else bits += if (n == 1) "the $key" else "${if (base) "the " else ""}$n ${plural(key, n)}"
                    }
                    val said = if (bits.size == 1) bits.firstOrNull() else bits.dropLast(1).joinToString(", ") + " and " + bits.lastOrNull()
                    // The first step says where the build starts: "Step 3: start with the 2 frames from step 1 and ...".
                    // Only made sub-assemblies to set out: "Step 3: set out the 3 frames".
                    val unitsOnly = base && loose.isEmpty() && unitCount.isNotEmpty() && unitCount.values.sumOf { it.size } > 1
                    val nUnits = unitCount.values.sumOf { it.size }
                    if (unitsOnly) "Step ${st.step}: set out the $nUnits ${if (unitCount.keys.all { it in frameBench }) "frames" else "sub-assemblies"}"
                    else if (bits.isEmpty() || bits.size > 3) (if (base) "Step ${st.step}: start here" else "Step ${st.step}")
                    else if (base) "Step ${st.step}: start with $said"
                    else "Step ${st.step}: $said"
                }
            }
            val title = o?.title?.takeIf { it.isNotBlank() }?.let { "Step ${st.step}: $it" } ?: auto
            for ((bench, us) in unitCount) placedUnits[bench] = (placedUnits[bench] ?: 0) + us.size
            // The list says how many of each part the whole step needs, all copies of the sub-assembly included.
            // And the hardware the step uses, so it can be laid out with the parts.
            val hw = LinkedHashMap<String, Int>()
            for (j in fasten?.byStep?.get(st.step).orEmpty()) {
                if (j.glueOnly || j.count <= 0) continue
                val key = when (j.kind) {
                    "brackets", "corner" -> "corner brackets"; "slides" -> "pair of ${j.size} drawer slides"; "hinges" -> "concealed hinges"; "pins" -> "shelf pins"; "applied" -> "${j.size} screws"
                    "laminate" -> "${j.size} brads"; "dowels" -> j.size; "thin", "edge", "housed" -> continue
                    else -> "${j.size} ${j.kind}"
                }
                hw[key] = (hw[key] ?: 0) + (if (j.kind == "slides") 1 else j.count)
                // Each drawer and door gets a handle; its style is the maker's choice.
                if (j.kind == "hinges" || j.kind == "applied" || (j.kind == "slides" && j.into === j.through)) hw[splitziner.joinery.Fastening.HANDLE] = (hw[splitziner.joinery.Fastening.HANDLE] ?: 0) + 1
            }
            val need = (if (st.makeCount > 1) list.map { (k, v) -> k to v * st.makeCount } else list) +
                hw.map { (k, v) -> k.replaceFirstChar { it.uppercase() } to v * maxOf(1, st.makeCount) }
            out += GuideStep(st.step, title, pic, o?.lines?.takeIf { it.isNotEmpty() } ?: lines, need, o?.check?.takeIf { it.isNotBlank() })
            if (!st.subassembly) done += st.partIds
        }
        return out
    }
}

/** Lays the build guide out on pages. Sizes are millimetres; text keeps the writer's capitalisation. */
object GuideLayout {
    private const val NOTICE_BLUE = 0xFF005BBB.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    const val DRAFT_NOTICE = "Assembly order and materials are drafts generated from the 3D model. Confirm them before building."

    class Info(val title: String, val subtitle: String, val overallSize: String, val partCount: Int, val tools: List<String> = emptyList(), val a4: Boolean = false, val hardware: List<String> = emptyList(),
        /** Work done before the first step, and after the last: a page of its own between the cover and the steps. */
        val before: List<String> = emptyList(), val after: List<String> = emptyList(),
        /** How to make each item that needs more than a straight cut: item heading to its steps. */
        val making: List<Pair<String, List<String>>> = emptyList())

    /**
     * The usual work around the build itself. [bandItems] are items whose edges show and are worth banding;
     * [housedItems] items that need a groove cut; [screws] whether anything is screwed (screw holes to fill).
     */
    fun beforeAndAfter(bandItems: List<Int>, groovedItems: List<Int>, screws: Boolean, pocketHoles: Map<Int, Int> = emptyMap(), pinItems: List<Int> = emptyList(), thinItems: List<Int> = emptyList(), dowels: String? = null, glued: List<String> = emptyList()): Pair<List<String>, List<String>> {
        fun list(i: List<Int>) = if (i.size == 1) "item ${i[0]}" else "items ${i.dropLast(1).joinToString(", ")} and ${i.last()}"
        val before = ArrayList<String>()
        before += "Cut every part to the cut list, and pencil its item number on a face that will not show."
        before += glued
        if (thinItems.isNotEmpty()) before += "Thin panels are held in a groove or rabbet, or against a glued strip (a cleat): the steps say where. If you choose grooves or rabbets, cut them in ${list(thinItems)} now, while the boards are loose, and add their depth to the thin panels."
        if (pinItems.isNotEmpty()) before += "Drill the shelf-pin holes in ${list(pinItems.sorted())} (${if (pinItems.size == 1) "its drawing gives" else "their drawings give"} the rows) before anything is joined, with a jig or a drilling guide so the rows match."
        if (groovedItems.isNotEmpty()) before += "Cut the grooves in ${list(groovedItems.sorted())} (${if (groovedItems.size == 1) "its drawing gives" else "their drawings give"} the sizes) before anything is joined: it is far easier on a loose board."
        if (pocketHoles.isNotEmpty()) before += "Drill the pocket holes with the jig set to each board's thickness, on faces that will not show: " +
            pocketHoles.entries.joinToString("; ") { (i, n) -> "item $i, $n holes in all" } + ". Each goes in the end that meets another part's face."
        if (dowels != null) {
            val dia = dowels.substringBefore(" x "); val len = dowels.substringAfter(" x ").substringBefore(" dowels")
            before += "Drill the dowel holes before anything is joined. Hold each pair of parts together and mark across the joint, then drill with a doweling jig: $dia${if (len.contains("mm")) " mm" else " in"} holes, square to the surface, a little over half the dowel's $len into the end or edge and the rest into the face, never more than two-thirds of the way through a board. A dry fit shows whether they line up."
        }
        if (bandItems.isNotEmpty()) before += "If the panels are plywood or particleboard, iron edge banding onto the edges that will show on ${list(bandItems.sorted())} and trim it flush. Banded edges are easier to do now than once it is built."
        before += "Sand the faces that will be hard to reach once it is together."
        before += "Do a dry run of each step, without glue, before the real one. Before fixing each part, check it is square and sits tight against what is already built."
        val after = ArrayList<String>()
        if (screws) after += "Fill any screw holes on show with wood filler and sand flush."
        after += "Sand everything smooth, finer each pass, and break the sharp edges."
        after += "Wipe off the dust and apply the finish you have chosen, following its tin."
        return before to after
    }

    private class Pages(val w: Double, val h: Double, val title: String) {
        val list = ArrayList<DisplayList>()
        fun new(): DisplayList {
            val p = DisplayList(w, h, "guide_page%02d".format(list.size + 1))
            p.upperCase = false
            p.text(12.0, h - 12, title.uppercase(), 3.0, vAlign = VAlign.TOP, bold = true)
            p.line(10.0, h - 17, w - 10, h - 17)
            list += p
            return p
        }
    }

    /** Writes wrapped text; lines after the first are set in by [hang] mm so a list item reads as one block. */
    private fun para(p: DisplayList, x: Double, y: Double, text: String, h: Double, width: Double, bold: Boolean = false, hang: Double = 0.0): Double {
        var yy = y
        val lines = wrapHanging(text, h, width, hang)
        lines.forEachIndexed { i, ln -> p.text(if (i == 0) x else x + hang, yy, ln, h, vAlign = VAlign.TOP, bold = bold); yy -= h * 1.5 }
        return yy
    }

    private fun wrapHanging(text: String, h: Double, width: Double, hang: Double): List<String> {
        if (hang <= 0) return wrapMixed(text, h, width)
        val first = wrapMixed(text, h, width).firstOrNull() ?: return emptyList()
        val rest = text.trim().removePrefix(first).trim()
        return listOf(first) + if (rest.isEmpty()) emptyList() else wrapMixed(rest, h, width - hang)
    }

    private fun wrapMixed(text: String, h: Double, width: Double): List<String> {
        val lines = ArrayList<String>(); var cur = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isNotEmpty() && StrokeFont.width(trial, h) > width) { lines += cur; cur = word } else cur = trial
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    /** A notice box in the style of a safety sign: blue signal panel, then the message. Returns the y below it. */
    private fun notice(p: DisplayList, x: Double, y: Double, w: Double, text: String): Double {
        val ph = 7.5
        p.fill(doubleArrayOf(x, y - ph, x + w, y - ph, x + w, y, x, y), NOTICE_BLUE)
        p.text(x + 3, y - ph / 2, "NOTICE", 4.0, vAlign = VAlign.CENTER, bold = true, argb = WHITE)
        val lines = wrapMixed(text, 3.2, w - 6)
        val bottom = y - ph - lines.size * 4.8 - 3
        p.rect(x, bottom, x + w, y)
        var yy = y - ph - 2
        for (ln in lines) { p.text(x + 3, yy, ln, 3.2, vAlign = VAlign.TOP); yy -= 4.8 }
        return bottom - 4
    }

    /** A short name for each page, for going straight to it: "Cover", "Making the parts", "Steps 3 to 4". */
    fun labels(pages: List<DisplayList>): List<String> = pages.mapIndexed { i, p ->
        val texts = p.prims.filterIsInstance<splitziner.sheet.Text>().map { it.text }
        val steps = texts.mapNotNull { Regex("""^Step (\d+)\b""").find(it)?.groupValues?.get(1)?.toInt() }.distinct().sorted()
        when {
            i == 0 -> "Cover"
            steps.size == 1 -> "Step ${steps[0]}"
            steps.size > 1 -> "Steps ${steps.first()} to ${steps.last()}"
            texts.any { it.equals("Making the parts", true) } -> "Making the parts"
            texts.any { it.equals("Before you build", true) } -> "Before you build"
            else -> "Page ${i + 1}"
        }
    }

    fun pages(info: Info, steps: List<GuideStep>, rows: List<CutListRow>, hero: DisplayList?): List<DisplayList> {
        val w = if (info.a4) 210.0 else 215.9; val h = if (info.a4) 297.0 else 279.4
        val pages = Pages(w, h, info.title)

        // Cover.
        var p = pages.new()
        var y = h - 26
        p.text(w / 2, y, info.title, 9.0, HAlign.CENTER, VAlign.TOP, bold = true); y -= 14
        if (info.subtitle.isNotBlank()) { p.text(w / 2, y, info.subtitle, 4.0, HAlign.CENTER, VAlign.TOP); y -= 8 }
        // Hardware sits beside the summary; tools, when known, under it. The picture shrinks (within reason) so the
        // parts table fits on the cover.
        val hwW = w / 2 - 30
        val hwH = if (info.hardware.isEmpty()) 0.0 else 7 + info.hardware.sumOf { wrapHanging("• $it", 3.0, hwW - 3, 3.0).size * 4.5 }
        val toolW = w - 50
        val toolH = if (info.tools.isEmpty()) 0.0 else 12 + wrapMixed(info.tools.joinToString(", "), 3.0, toolW).size * 4.5
        val tableH = 14 + rows.sumOf { r -> wrapMixed(r.name.ifBlank { "x" }, 2.9, 48.0).size * 4.2 + 0.8 }
        val infoH = maxOf(20.0, hwH)
        val spare = y - 16 - infoH - 3 - 24 - toolH - tableH - 10
        val heroH = spare.coerceIn(45.0, 95.0)
        if (hero != null) { p.prims += Image(25.0, y - heroH, w - 25, y, hero); y -= heroH + 5 }
        val infoTop = y
        for ((k, v) in listOf("SIZE" to info.overallSize, "PARTS" to "${info.partCount}", "STEPS" to "${steps.size}")) {
            p.text(25.0, y, k, 3.2, vAlign = VAlign.TOP, bold = true)
            // A long size ("31-1/2 x 18-7/16 x 35-7/16 in") goes under its label rather than into the hardware list.
            if (45.0 + splitziner.sheet.StrokeFont.width(v, 3.2) > w / 2 + 2) { y -= 5.5; p.text(25.0, y, v, 3.2, vAlign = VAlign.TOP) }
            else p.text(45.0, y, v, 3.2, vAlign = VAlign.TOP)
            y -= 6
        }
        if (info.hardware.isNotEmpty()) {
            var hy = infoTop; val hx = w / 2 + 5
            p.text(hx, hy, "HARDWARE", 3.5, vAlign = VAlign.TOP, bold = true); hy -= 7
            for (it in info.hardware) hy = para(p, hx + 3, hy, "• $it", 3.0, hwW - 3, hang = 3.0)
            y = minOf(y, hy)
        }
        y -= 3
        y = notice(p, 25.0, y, w - 50, DRAFT_NOTICE)
        if (info.tools.isNotEmpty()) {
            p.text(25.0, y, "TOOLS NEEDED", 3.5, vAlign = VAlign.TOP, bold = true); y -= 7
            y = para(p, 25.0, y, info.tools.joinToString(", "), 3.0, toolW) - 3
        }
        var page = p
        // The parts: item, how many, what it is called, its size, its material.
        val colX = doubleArrayOf(25.0, 37.0, 50.0, 100.0, 160.0)
        fun header(pg: DisplayList, yy: Double, cont: Boolean): Double {
            pg.text(25.0, yy, if (cont) "PARTS (CONTINUED)" else "PARTS", 3.5, vAlign = VAlign.TOP, bold = true)
            var y2 = yy - 7
            for ((i, hd) in listOf("ITEM", "QTY", "NAME", "SIZE (T X W X L)", "MATERIAL").withIndex()) pg.text(colX[i], y2, hd, 2.6, vAlign = VAlign.TOP, bold = true)
            y2 -= 4.2
            pg.line(25.0, y2, w - 25, y2, LineStyle.THIN)
            return y2 - 1.5
        }
        var yy = header(page, y, false)
        for (r in rows) {
            val name = r.name.ifBlank { r.description.substringBefore(' ').lowercase().replaceFirstChar { it.uppercase() } }
            val nameLines = wrapMixed(name, 2.9, colX[3] - colX[2] - 2)
            val need = nameLines.size * 4.2 + 0.8
            if (yy - need < 16) { page = pages.new(); yy = header(page, h - 26, true) }
            page.text(colX[0], yy, "${r.item}", 2.9, vAlign = VAlign.TOP, bold = true)
            page.text(colX[1], yy, "${r.qty}", 2.9, vAlign = VAlign.TOP)
            nameLines.forEachIndexed { i, ln -> page.text(colX[2], yy - i * 4.2, ln, 2.9, vAlign = VAlign.TOP) }
            page.text(colX[3], yy, r.stock + (if (r.kind == splitziner.parts.PartKind.ROD) " (rod)" else ""), 2.9, vAlign = VAlign.TOP)
            if (r.material.isNotBlank()) page.text(colX[4], yy, r.material, 2.9, vAlign = VAlign.TOP)
            yy -= need
        }

        // The work before the first step and after the last, on a page of its own.
        if (info.before.isNotEmpty() || info.after.isNotEmpty()) {
            val pg = pages.new(); var yy = h - 26
            val after = info.after + if (info.hardware.any { it.contains("anti-tip") }) listOf("Stand it where it will live and fix it to the wall with the anti-tip strap, screwed into a stud.") else emptyList()
            for ((head, items) in listOf("BEFORE YOU BUILD" to info.before, "WHEN IT IS TOGETHER" to after)) {
                if (items.isEmpty()) continue
                pg.text(25.0, yy, head, 4.0, vAlign = VAlign.TOP, bold = true); yy -= 8
                items.forEachIndexed { i, t -> yy = para(pg, 28.0, yy, "${i + 1}. $t", 3.2, w - 56, hang = 4.0) - 1.5 }
                yy -= 6
            }
        }

        // Making the shaped parts: one block per item, carried over pages as needed.
        if (info.making.isNotEmpty()) {
            var pg = pages.new(); var yy = h - 26
            pg.text(25.0, yy, "MAKING THE PARTS", 4.0, vAlign = VAlign.TOP, bold = true); yy -= 7
            yy = para(pg, 25.0, yy, "Plain boards are only cut to size. These need more:", 3.0, w - 50) - 3
            for ((head, steps) in info.making) {
                val need = 6 + steps.sumOf { wrapHanging("0. $it", 3.0, w - 56, 4.0).size * 4.5 + 1 }
                if (yy - need < 16) { pg = pages.new(); yy = h - 26 }
                pg.text(25.0, yy, head, 3.4, vAlign = VAlign.TOP, bold = true); yy -= 6
                steps.forEachIndexed { i, t -> yy = para(pg, 28.0, yy, "${i + 1}. $t", 3.0, w - 56, hang = 4.0) - 1 }
                yy -= 4
            }
        }

        // Steps: two to a page; one that needs more room than half a page gets a page to itself.
        val usable = h - 17 - 8 - 14
        fun textHeight(s: GuideStep, width: Double): Double =
            s.text.sumOf { wrapMixed("0. $it", 3.2, width).size * 4.8 } + (if (s.check != null) wrapMixed("CHECK: ${s.check}", 3.2, width).size * 4.8 + 2 else 0.0)
        fun listHeight(s: GuideStep, width: Double): Double = 6 + s.parts.sumOf { wrapHanging("${it.second}× ${it.first}", 2.9, width, 5.0).size * 4.35 + 1 }
        var slot = 2
        var cursor = 0.0
        for (s in steps) {
            val fullW = w - 24
            val picW = fullW * 0.62; val listW = fullW - picW - 6
            val th = textHeight(s, fullW)
            val minPic = 55.0
            val wantsFull = 8 + maxOf(minPic, listHeight(s, listW)) + th + 6 > usable / 2
            if (slot >= 2 || wantsFull) { p = pages.new(); slot = 0; cursor = h - 22 }
            val blockH = if (wantsFull) usable else usable / 2
            val topY = cursor
            // A long title is set smaller to stay on the page rather than run off its edge.
            val titleH = (4.5 * fullW / (1.05 * StrokeFont.width(s.title, 4.5))).coerceIn(3.2, 4.5)
            p.text(12.0, topY, s.title, titleH, vAlign = VAlign.TOP, bold = true)
            val picTop = topY - 8
            val ty0: Double
            if (wantsFull) {
                // A step with a page to itself: the picture across the full width, the parts in two columns under it.
                val colW = (fullW - 8) / 2
                val half = (s.parts.size + 1) / 2
                val listH = 6 + maxOf(s.parts.take(half).sumOf { wrapHanging("${it.second}× ${it.first}", 2.9, colW, 5.0).size * 4.35 + 1 },
                    s.parts.drop(half).sumOf { wrapHanging("${it.second}× ${it.first}", 2.9, colW, 5.0).size * 4.35 + 1 })
                val picH = maxOf(minPic, minOf(fullW * GuideSteps.PICTURE_H / GuideSteps.PICTURE_W, usable - 10 - listH - th - 8))
                p.prims += Image(12.0, picTop - picH, 12.0 + fullW, picTop, s.picture)
                var ly = picTop - picH - 4
                p.text(12.0, ly, "PARTS FOR THIS STEP", 3.0, vAlign = VAlign.TOP, bold = true); ly -= 6
                var l1 = ly; var l2 = ly
                s.parts.forEachIndexed { i, (name, n) ->
                    if (i < half) l1 = para(p, 12.0, l1, "$n× $name", 2.9, colW, hang = 5.0) - 1
                    else l2 = para(p, 12.0 + colW + 8, l2, "$n× $name", 2.9, colW, hang = 5.0) - 1
                }
                ty0 = minOf(l1, l2) - 3
            } else {
                // The picture keeps its own proportions; it is never stretched taller than it is drawn, which only left a gap.
                val picBottom = picTop - minOf(blockH - th - 14, picW * GuideSteps.PICTURE_H / GuideSteps.PICTURE_W)
                p.prims += Image(12.0, picBottom, 12.0 + picW, picTop, s.picture)
                var ly = picTop
                p.text(12.0 + picW + 6, ly, "PARTS FOR THIS STEP", 3.0, vAlign = VAlign.TOP, bold = true); ly -= 6
                for ((name, n) in s.parts) ly = para(p, 12.0 + picW + 6, ly, "$n× $name", 2.9, listW, hang = 5.0) - 1
                ty0 = minOf(picBottom, ly - 2) - 3
            }
            // The instructions start below the picture and parts list, and carry on overleaf if the page runs out.
            var ty = ty0
            fun room(text: String, size: Double, bold: Boolean = false) {
                val need = wrapMixed(text, size, fullW).size * size * 1.5
                if (ty - need < 14) { p = pages.new(); ty = h - 22; p.text(12.0, ty, "${s.title} (continued)", 4.0, vAlign = VAlign.TOP, bold = true); ty -= 9; slot = 2 }
                ty = para(p, 12.0, ty, text, size, fullW, bold)
            }
            s.text.forEachIndexed { i, line -> room("${i + 1}. $line", 3.2) }
            if (s.check != null) { ty -= 2; room("CHECK: ${s.check}", 3.2, bold = true) }
            if (slot == 2 && !wantsFull) { cursor = ty - 6; slot = 1; continue }
            if (wantsFull) slot = 2 else { slot++; cursor = topY - blockH - 4; if (slot == 1) p.line(12.0, cursor + 2, w - 12, cursor + 2, LineStyle.THIN) }
        }

        // Page numbers go on last, once the count is known.
        pages.list.forEachIndexed { i, pg -> pg.text(w / 2, 8.0, "PAGE ${i + 1} OF ${pages.list.size}", 2.6, HAlign.CENTER, VAlign.CENTER) }
        return pages.list
    }
}
