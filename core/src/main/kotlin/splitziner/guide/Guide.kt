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
    /** A build step names at most this many positions; more, and the picture says it better. */
    private const val MAX_PLACING = 2

    /** More copies than this spread along one part are not listed one by one: their even spacing is said, or the picture shows them. */
    private const val MAX_LISTED = 5

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
    fun placing(cur: List<Part>, fmt: splitziner.dims.LengthFormat, label: (Part) -> String, built: List<Part> = emptyList(), skipItems: Set<Int?> = emptySet()): List<String> {
        val out = LinkedHashMap<String, MutableList<Double>>()
        val flush = HashMap<String, String>(); val axisOf = HashMap<String, Int>()
        val u = if (fmt.isInch) " in" else " mm"
        fun b(p: Part) = p.mesh.bounds
        fun touch(a: Part, c: Part) = (0..2).all { k -> b(a).min[k] <= b(c).max[k] + 0.5 && b(c).min[k] <= b(a).max[k] + 0.5 }
        val bench = built.isEmpty()
        val pool = cur + built
        fun many(s: Part) = cur.count { it.item == s.item } > 1
        fun hostOf(s: Part) = pool.filter { it !== s && touch(it, s) && (bench || it.item != s.item) }.maxByOrNull { h -> (0..2).maxOf { k -> b(h).extents[k] } }
        // Three or more copies of a part spread along one host (battens across a lid, cleats along a wall) are one line
        // giving each one's place from the same end, flush ones included, so none is left unplaced.
        val spread = HashSet<String>(); val spreadLines = ArrayList<String>()
        for ((_, same) in cur.filter { it.item != null && it.item !in skipItems }.groupBy { it.item to hostOf(it)?.id }) {
            if (same.size < 3) continue
            val host = hostOf(same[0]) ?: continue
            val contact = (0..2).minByOrNull { k -> minOf(b(same[0]).max[k], b(host).max[k]) - maxOf(b(same[0]).min[k], b(host).min[k]) }!!
            for (k in (0..2).filter { it != contact }) {
                if (same.any { b(it).extents[k] >= b(host).extents[k] - 1.0 }) continue
                val offs = same.map { b(it).min[k] - b(host).min[k] }.sorted()
                if (offs.map { Math.round(it) }.distinct().size < same.size) continue
                same.forEach { spread += "${it.id}|$k" }
                val edge = when (k) { 2 -> "bottom"; 1 -> "front"; else -> "left" }
                val from = when (k) { 2 -> "bottom edge"; 1 -> "front edge"; else -> "left end" }
                // Many of them: the even spacing if there is one (said once), else the drawing shows them better than a list.
                val pitch = offs.zipWithNext { a, c -> c - a }
                if (same.size > MAX_LISTED) {
                    if (pitch.max() - pitch.min() > 1.5) continue
                    val p = pitch.average(); val gap = p - b(same[0]).extents[k]
                    val first = offs[0].let { if (it < 1.0) "the first flush with its $from" else "the first ${fmt.format(Math.round(it * 2) / 2.0)}$u from its $from" }
                    spreadLines += "Space ${same.size}× ${label(same[0])} evenly along ${label(host)}, ${fmt.format(gap)}$u apart (${fmt.format(p)}$u centre to centre), $first: a spacer block cut to the gap keeps them even."
                    continue
                }
                val at = offs.map { if (it < 1.0) "flush" else fmt.format(Math.round(it * 2) / 2.0) + u }
                spreadLines += "Set ${same.size}× ${label(same[0])} along ${label(host)}, their $edge edges ${at.dropLast(1).joinToString(", ")} and ${at.last()} from its $from."
            }
        }
        for (s in cur) {
            // Doors and drawer fronts are placed by their hinges or by eye, with even gaps; offsets would only mislead.
            if (s.item in skipItems || s.name == "Door" || s.name == "Drawer front" || s.name == "Rod" || s.name == "Tenon" || s.name == "Sliding door" || s.name == "Caster") continue
            // The longest part it touches that is longer than it along its own length; on the bench any part of the
            // unit, in the build a part already there or going in with it.
            val host = pool.filter { it !== s && touch(it, s) && (bench || it.item != s.item) }.maxByOrNull { h -> (0..2).maxOf { k -> b(h).extents[k] } } ?: continue
            val len = (0..2).maxByOrNull { k -> b(host).extents[k] }!!
            // They meet face to face across one axis; the part is placed along the other two.
            val contact = (0..2).minByOrNull { k -> minOf(b(s).max[k], b(host).max[k]) - maxOf(b(s).min[k], b(host).min[k]) }!!
            // Resting on something (slats on their cleats, a shelf on its supports): that sets its height.
            val supported = pool.any { o -> o !== s && kotlin.math.abs(b(o).max.z - b(s).min.z) < 0.5 && touch(o, s) }
            for (k in (0..2).filter { it != contact }) {
                // One of two copies flush with an edge: said, so the other's offset is not read as both's (a board
                // resting on the other is still flush with the top).
                run {
                    val lo0 = b(s).min[k] - b(host).min[k]; val hi0 = b(host).max[k] - b(s).max[k]
                    if (b(s).extents[k] < b(host).extents[k] - 1.0 && (lo0 < 1.0) != (hi0 < 1.0) && (k != len || k == 2) && cur.count { it.item == s.item } == 2) {
                        val edge = when (k) { 2 -> if (lo0 < 1.0) "bottom edge" else "top edge"; 1 -> if (lo0 < 1.0) "front edge" else "back edge"; else -> if (lo0 < 1.0) "left edge" else "right edge" }
                        flush["${label(s)}|$k|${label(host)}"] = edge
                    }
                }
                if ("${s.id}|$k" in spread) continue
                if (k == 2 && supported) continue
                // A shaped host (a stepped stringer) has no plain top or bottom edge to measure up or down from.
                if (k == 2 && (host.name == "Stringer" || host.kind == splitziner.parts.PartKind.SHAPED)) continue
                if (b(s).extents[k] >= b(host).extents[k] - 1.0) continue
                val lo = b(s).min[k] - b(host).min[k]; val hi = b(host).max[k] - b(s).max[k]
                // Hung flush with the top of what holds it (a modesty panel under a desk top, between its sides): said,
                // or nothing gives its height.
                if (k == 2 && hi < 1.0 && lo >= 1.0 && !many(s)) { out.getOrPut("${label(s)}|flush with the top edge|${label(host)}") { ArrayList() } += 0.0; continue }
                if (lo < 1.0 || hi < 1.0) continue
                // Held on both sides along this axis by other parts: they place it.
                // Not parts running along this axis into it end-on (a stretcher's short braces butting its cross brace):
                // it places them.
                val others = pool.filter { it !== s && it !== host && touch(it, s) && (0..2).maxByOrNull { a -> b(it).extents[a] } != k }
                if (others.any { o -> b(o).max[k] <= b(s).min[k] + 0.5 } && others.any { o -> b(o).min[k] >= b(s).max[k] - 0.5 }) continue
                val near = minOf(lo, hi)
                val many = cur.count { it.item == s.item } > 1
                // Centred: said so, which is how a maker would mark it.
                if (kotlin.math.abs(lo - hi) < 1.0) { out.getOrPut("${label(s)}|centred on the ${if (k == 2) "height" else "width"}|${label(host)}") { ArrayList() } += -1.0; continue }
                val where = when {
                    k == 2 -> if (lo <= hi) "up from the bottom edge" else "down from the top edge"
                    // The end it is measured from, by name; copies set alike from both ends become "from each end" below.
                    k == len -> if (!many) "from the end" else if (k == 0) (if (lo <= hi) "from the left end" else "from the right end") else (if (lo <= hi) "from the front end" else "from the back end")
                    k == 1 -> if (lo <= hi) "from the front edge" else "from the back edge"
                    else -> if (lo <= hi) "from the left edge" else "from the right edge"
                }
                out.getOrPut("${label(s)}|$where|${label(host)}") { ArrayList() } += near
                axisOf["${label(s)}|$where|${label(host)}"] = k
            }
        }
        // Copies set the same distances from both ends of a host are one line, "from each end".
        for (pair in listOf("left" to "right", "front" to "back")) for (key in out.keys.toList()) {
            val (what, where, of) = key.split('|')
            if (where != "from the ${pair.first} end") continue
            val other = "$what|from the ${pair.second} end|$of"
            val a1 = out[key] ?: continue; val a2 = out[other] ?: continue
            out.remove(key); out.remove(other)
            // Different distances from the two ends: one line naming each end.
            if (a1.map { Math.round(it * 2) }.sorted() != a2.map { Math.round(it * 2) }.sorted()) {
                out["$what|ends:${pair.first}:${pair.second}:${a1.size}|$of"] = (a1 + a2).toMutableList(); continue
            }
            out["$what|from each end|$of"] = (a1 + a2).toMutableList()
            axisOf["$what|from each end|$of"] = axisOf[key] ?: 0
        }
        // Copies of one part set the same way against different neighbours (two shelves, each beside a different upright)
        // are one line naming both.
        val merged = LinkedHashMap<String, MutableList<Double>>()
        for ((key, ds) in out) {
            val (what, where, of) = key.split('|')
            val twin = merged.keys.firstOrNull { k -> k.split('|').let { it[0] == what && it[1] == where && it[2] != of } && merged.getValue(k).map { Math.round(it * 2) } == ds.map { Math.round(it * 2) } }
            if (twin != null) { val ex = merged.remove(twin)!!; merged["$what|$where|${twin.substringAfterLast('|')} and $of"] = (ex + ds).toMutableList() } else merged[key] = ds
        }
        return spreadLines + merged.entries.flatMap { (key, ds) ->
            val (what, where, of) = key.split('|')
            val d = ds.map { Math.round(it * 2) / 2.0 }.distinct().sorted()
            val fl = axisOf[key]?.let { flush["$what|$it|$of"] }
            if (fl != null && ds.size == 1 && !where.startsWith("centred"))
                return@flatMap listOf("Set one $what flush with the $fl of $of, and the other ${fmt.format(d[0])}$u $where.")
            listOf(run {
            if (where.startsWith("ends:")) {
                val (_, e1, e2, n1) = where.split(':')
                fun list(xs: List<Double>) = xs.map { Math.round(it * 2) / 2.0 }.distinct().sorted().let { v -> if (v.size == 1) fmt.format(v[0]) + u else v.dropLast(1).joinToString(", ") { fmt.format(it) + u } + " and " + fmt.format(v.last()) + u }
                "Set ${if (ds.size > 1) "${ds.size}× " else ""}$what ${list(ds.take(n1.toInt()))} from the $e1 end and ${list(ds.drop(n1.toInt()))} from the $e2 end of $of."
            }
            else if (where.startsWith("flush")) "Set $what $where of $of."
            else if (where.startsWith("centred")) "Centre ${if (ds.size > 1) "each $what" else what} on the ${where.substringAfterLast(' ')} of $of."
            else if (d.size == 1) "Set ${if (ds.size > 1) "each $what" else what} ${fmt.format(d[0])}$u $where of $of."
            else "Set ${ds.size}× $what ${d.dropLast(1).joinToString(", ") { fmt.format(it) + u }} and ${fmt.format(d.last())}$u $where of $of, as drawn."
            })
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
    /**
     * A step's sentence put the way it is read at the bench: the part's name first, its item number after it.
     * "Screw item 1 (side) to item 2 (stud)" reads "Screw the side (1) to the stud (2)"; "4× item 2 (leg)" reads
     * "4× leg (item 2)"; "items 8 and 9 (plinth)" reads "the plinth (items 8 and 9)".
     */
    /** The plural a maker would say: "shelves", "feet", and each half of a pair of names ("fronts and backs"). */
    fun pluralOf(w: String): String = if (" and " in w) w.split(" and ").joinToString(" and ") { pluralOf(it) } else if (", " in w) w.split(", ").joinToString(", ") { pluralOf(it) } else when {
        w.endsWith("shelf") || w.endsWith("helf") -> w.dropLast(1) + "ves"; w.endsWith("foot") -> w.dropLast(4) + "feet"; w.endsWith("s") || w.endsWith("x") -> w; else -> w + "s" }

    fun readable(line: String): String {
        var s = line
        fun plural0(w: String) = pluralOf(w)
        // "items 1 (crate lid) and 4 (batten, 3×)": each by name, its count first, in order.
        s = Regex("""\bitems ((?:\d+ \([^()]+\)(?:, | and ))+\d+ \([^()]+\))""").replace(s) { m ->
            val each = Regex("""(\d+) \(([^(),]+)(?:, (\d+)×)?\)""").findAll(m.groupValues[1]).toList()
            if (each.isEmpty()) m.value else each.joinToString(", ").let { _ ->
                val names = each.map { e -> val n = e.groupValues[3].toIntOrNull(); if (n != null && n > 1) "the $n ${plural0(e.groupValues[2])} (${e.groupValues[1]})" else "the ${e.groupValues[2]} (${e.groupValues[1]})" }
                names.dropLast(1).joinToString(", ") + " and " + names.last() } }
        // "2× item 2 (leg)": "the 2 legs (2)".
        s = Regex("""(\d+)× item (\d+) \(([^()]+)\)""").replace(s) { m -> "the ${m.groupValues[1]} ${plural0(m.groupValues[3])} (${m.groupValues[2]})" }
        s = Regex("""\bitems (\d+(?:, \d+)* and \d+) \(([^(),]+)\)""").replace(s) { m -> "the ${m.groupValues[2]} (items ${m.groupValues[1]})" }
        // After a count or "each" the name stands without "the", in the plural after more than one.
        fun plural(w: String) = pluralOf(w)
        val many = Regex("""\b(the two|the [3-9]|the \d\d+|two|three|four|five|six|[2-9]|\d\d+|all \d+|both) item (\d+) \(([^()]+)\)""")
        s = many.replace(s) { m -> "${m.groupValues[1]} ${plural(m.groupValues[3])} (${m.groupValues[2]})" }
        s = Regex("""\b(each|every|one|another|its|either) item (\d+) \(([^()]+)\)""").replace(s) { m -> "${m.groupValues[1]} ${m.groupValues[3]} (${m.groupValues[2]})" }
        s = Regex("""\b[Ii]tem (\d+) \(([^()]+)\)""").replace(s) { m -> "the ${m.groupValues[2]} (${m.groupValues[1]})" }
        // "the slats (item 5)": the number alone after a name, as everywhere else.
        s = Regex("""([a-z]) \(item (\d+)\)""").replace(s) { m -> "${m.groupValues[1]} (${m.groupValues[2]})" }
        // At the start of a sentence: "The side (1) ...".
        s = Regex("""(^|[.] )the """).replace(s) { m -> m.groupValues[1] + "The " }
        return s
    }

    fun evenGap(like: List<Part>, others: List<Part> = emptyList()): Double? {
        if (like.size < 2) return null
        val b = like.map { it.mesh.bounds }
        // Spread along the axis where their centres differ most.
        val ax = (0..2).maxByOrNull { k -> b.maxOf { it.center[k] } - b.minOf { it.center[k] } } ?: return null
        val sorted = b.sortedBy { it.min[ax] }
        val gaps = sorted.zipWithNext { a, c -> c.min[ax] - a.max[ax] }
        if (gaps.any { it < 3.0 }) return null
        // A gap with something in it (the divider between two bays' tops) is not a space to set with a block.
        for ((a, c) in sorted.zipWithNext()) if (others.any { o -> val ob = o.mesh.bounds
            ob.min[ax] < c.min[ax] - 0.5 && ob.max[ax] > a.max[ax] + 0.5 && (0..2).filter { it != ax }.all { k -> ob.min[k] < minOf(a.max[k], c.max[k]) - 0.5 && ob.max[k] > maxOf(a.min[k], c.min[k]) + 0.5 } }) return null
        if (like.size < 3) return null
        return if (gaps.maxOrNull()!! - gaps.minOrNull()!! <= 1.5) gaps.average() else null
    }

    fun build(parts: List<Part>, steps: List<BuildStep>, overrides: Map<String, StepText> = emptyMap(), fasten: splitziner.joinery.FastenPlan? = null, fmt: splitziner.dims.LengthFormat? = null): List<GuideStep> {
        val gapTold = HashSet<Int>()
        val levelTold = HashSet<String>(); val levelItems = HashSet<Int>()
        val drawn = parts.filter { !it.excluded }
        val byId = drawn.associateBy { it.id }
        // One view for every step, so the reader keeps their bearings.
        val view: ViewDef = ExplodedView.pickView(drawn)
        // And one framing: every picture leaves room for the whole finished object, so nothing jumps in size between steps.
        val frame = ExplodedView.assembledBounds(drawn, view)
        val done = LinkedHashSet<String>()
        val out = ArrayList<GuideStep>()
        // " (side)" after an item number, when its parts share a name of their own.
        // "item 2 (side)"; one item under a few names reads "item 2 (front and back)".
        val shownNames = splitziner.parts.Distinct.names(drawn)
        fun named(ps: List<Part>): String = ps.filter { it.name != it.id }.map { splitziner.parts.Distinct.shown(it, shownNames).lowercase().replace("french", "French") }.distinct().let { ns ->
            when (ns.size) { 1 -> " (${ns[0]})"; 2, 3 -> " (" + ns.dropLast(1).joinToString(", ") + " and " + ns.last() + ")"; else -> "" } }
        // Sub-assemblies made on the bench are referred to whole in later steps. A unit is the boards that
        // came from one drawn part; its kind (which items, how many of each) says which bench step made it.
        val keyOf = splitziner.explode.AssemblyPlan.unitKeys(drawn)
        fun unit(p: Part) = keyOf[p.id] ?: p.id.substringBefore('.')
        val unitOf = drawn.groupBy { unit(it) }.filter { it.value.size >= 2 }
        fun kind(ms: List<Part>) = ms.mapNotNull { it.item }.sorted()
        val benchOf = HashMap<List<Int>, Int>()
        // A unit built over several bench steps is known by all its parts, and referred to by the step that finishes it.
        for (st in steps) if (st.subassembly && st.continues == null) {
            val seq = steps.filter { it.step == st.step || it.continues == st.step }
            benchOf[kind(seq.flatMap { it.partIds }.mapNotNull { byId[it] })] = seq.maxOf { it.step }
        }
        fun seqOf(st: splitziner.explode.BuildStep) = steps.filter { o -> o.subassembly && (o.step == (st.continues ?: st.step) || o.continues == (st.continues ?: st.step)) }
        // What a bench-made unit is called: a frame when it has legs (a table's end, a bed's side), else a sub-assembly.
        // A trestle's ends (foot, post and top rail) are frames too.
        val trestleBench = steps.filter { st -> st.subassembly && st.partIds.any { byId[it]?.name == "Post" } && st.partIds.any { byId[it]?.name == "Foot" } }.map { it.step }.toSet()
        val frameBench = steps.filter { st -> st.subassembly && st.partIds.any { byId[it]?.name?.endsWith("eg") == true } }.map { it.step }.toSet() + trestleBench
        // How many units from each bench step are already in place, so the next is "the other".
        val placedUnits = HashMap<Int, Int>()
        fun otherOf(bench: Int) = if ((steps.firstOrNull { it.step == bench }?.makeCount ?: 2) - (placedUnits[bench] ?: 0) == 1) "the other" else "another"
        // A folding screen's panels, each made on the bench and hinged to the next.
        val screen = runCatching { splitziner.parts.Screens.leaves(drawn) }.getOrNull() != null
        val screenBench = if (screen) steps.filter { it.subassembly }.map { it.step }.toSet() else emptySet()
        val faceBench = steps.filter { st -> st.subassembly && st.partIds.any { byId[it]?.name == "Stile" } && st.partIds.none { keyOf[it]?.startsWith("fpanel") == true || keyOf[it]?.startsWith("door") == true } }.map { it.step }.toSet()
        // Repeated cabinets in a row: each built whole, called a cabinet when it is one (sides and a bottom).
        val moduleBench = steps.filter { st -> st.subassembly && st.partIds.any { keyOf[it]?.startsWith("module") == true } }.map { it.step }.toSet()
        // A rail with its tenons glued in: called by the rail's name ("the 2 aprons from step 2").
        val tenonRail = steps.filter { st -> st.subassembly && st.partIds.any { byId[it]?.name == "Tenon" } && st.partIds.count { byId[it]?.name != "Tenon" } == 1 }
            .associate { st -> st.step to (st.partIds.mapNotNull { byId[it] }.first { it.name != "Tenon" }.name.lowercase()) }
        // A case built whole and set on the top of the piece below: a hutch.
        val upperBench = steps.filter { st -> st.subassembly && st.partIds.any { keyOf[it] == "upper" } }.map { it.step }.toSet()
        // Sides and rungs made up flat on the bench: a ladder.
        // A frame-and-panel door glued up flat: "make 2 doors", later "hang both doors".
        val doorBench = steps.filter { st -> st.subassembly && st.partIds.isNotEmpty() && st.partIds.all { keyOf[it]?.startsWith("door") == true } }.map { it.step }.toSet()
        val dfrontBench = steps.filter { st -> st.subassembly && st.partIds.isNotEmpty() && st.partIds.all { keyOf[it]?.startsWith("dfront") == true } }.map { it.step }.toSet()
        // Cleats screwed to a panel flat on the bench (a plinth's front and back): "make 2 cleated panels".
        // A painting panel's cradle, screwed up on the bench before it is glued to the face: "make the cradle".
        // A shadow box's box of four sides, made before the face frame goes on it: "make the box".
        val sboxBench = steps.filter { st -> st.subassembly && st.partIds.isNotEmpty() && st.partIds.all { keyOf[it]?.startsWith("sbox") == true } }.map { it.step }.toSet()
        val cradleBench = steps.filter { st -> st.subassembly && st.partIds.isNotEmpty() && st.partIds.all { keyOf[it]?.startsWith("cradle") == true } }.map { it.step }.toSet()
        val cpanelBench = steps.filter { st -> st.subassembly && st.partIds.isNotEmpty() && st.partIds.all { keyOf[it]?.startsWith("cpanel") == true } }.map { it.step }.toSet()
        val fpanelBench = steps.filter { st -> st.subassembly && st.partIds.isNotEmpty() && st.partIds.all { keyOf[it]?.startsWith("fpanel") == true } }.map { it.step }.toSet()
        // A panelled frame on posts in a bed is its headboard (the taller) or footboard.
        val postFrames = fpanelBench.filter { b -> steps.first { it.step == b }.partIds.any { byId[it]?.name in setOf("Leg", "Post") } }
        val bedEnds: Map<Int, String> = if (drawn.none { it.name == "Slat" } || postFrames.isEmpty()) emptyMap() else {
            val tall = postFrames.maxByOrNull { b -> steps.first { it.step == b }.partIds.mapNotNull { byId[it] }.maxOf { it.mesh.bounds.max.z } }
            postFrames.associateWith { if (it == tall) "headboard" else "footboard" }
        }
        val ladderBench = steps.filter { st -> st.subassembly && st.partIds.any { byId[it]?.name == "Rung" } }.map { it.step }.toSet()
        // Boards on edge screwed up in a ring that the case stands on: a plinth.
        val plinthBench = steps.filter { st -> st.subassembly && st.partIds.all { byId[it]?.name == "Plinth" } }.map { it.step }.toSet()
        // Two rails with spindles between them, made flat: a slatted panel (a crib's side). With legs it is a frame.
        val slattedBench = steps.filter { st -> st.subassembly && st.partIds.any { keyOf[it]?.startsWith("slatted") == true } }.map { it.step }.toSet()
        val cabinetWord = drawn.any { keyOf[it.id]?.startsWith("module") == true && it.name in setOf("Bottom", "Side", "Divider") }
        // Other sub-assemblies are lettered, as in a maker's manual: "make sub-assembly A", later "sub-assembly A".
        val special = moduleBench + faceBench + frameBench + upperBench + tenonRail.keys + ladderBench + slattedBench + plinthBench + doorBench + fpanelBench + dfrontBench + cpanelBench + cradleBench + sboxBench
        val letterOf = HashMap<Int, String>()
        for (st in steps) if (st.subassembly && st.continues == null && st.step !in special && !st.partIds.all { keyOf[it]?.startsWith("drawer") == true }) {
            val seq = steps.filter { it.step == st.step || it.continues == st.step }.map { it.step }
            val letter = if (letterOf.size < 26) ('A' + letterOf.values.distinct().size).toString() else "${letterOf.values.distinct().size + 1}"
            for (s0 in seq) letterOf[s0] = letter
        }
        fun unitWord(bench: Int, n: Int) = if (bench in screenBench) (if (n > 1) "screen panels" else "screen panel") else if (bench in letterOf) (if (n > 1) "sub-assemblies ${letterOf[bench]}" else "sub-assembly ${letterOf[bench]}") else if (bench in tenonRail) tenonRail.getValue(bench).let { if (n > 1) it + "s" else it } else if (bench in upperBench) (if (n > 1) "hutches" else "hutch") else if (bench in ladderBench) (if (n > 1) "ladders" else "ladder") else if (bench in doorBench) (if (n > 1) "doors" else "door") else if (bench in bedEnds) (if (n > 1) bedEnds.getValue(bench) + "s" else bedEnds.getValue(bench)) else if (bench in fpanelBench) (if (n > 1) "panelled frames" else "panelled frame") else if (bench in cpanelBench) (if (n > 1) "cleated panels" else "cleated panel") else if (bench in cradleBench) (if (n > 1) "cradles" else "cradle") else if (bench in sboxBench) (if (n > 1) "boxes" else "box") else if (bench in dfrontBench) (if (n > 1) "drawer fronts" else "drawer front") else if (bench in plinthBench) (if (n > 1) "plinths" else "plinth") else if (bench in slattedBench) (if (n > 1) "slatted panels" else "slatted panel") else if (bench in moduleBench) (if (cabinetWord) (if (n > 1) "cabinets" else "cabinet") else (if (n > 1) "units" else "unit")) else if (bench in faceBench) (if (n > 1) "face frames" else "face frame") else if (bench in trestleBench) (if (n > 1) "trestle ends" else "trestle end") else if (bench in frameBench) (if (n > 1) "frames" else "frame") else (if (n > 1) "sub-assemblies" else "sub-assembly")
        for (st in steps) {
            val cur = st.partIds.mapNotNull { byId[it] }
            // In a main step, each made sub-assembly counts as one thing to fit.
            val units = if (st.subassembly) emptyMap() else cur.groupBy { unit(it) }.filter { (root, _) -> root in unitOf && benchOf.containsKey(kind(unitOf.getValue(root))) }
            val loose = cur.filter { unit(it) !in units }
            val count = LinkedHashMap<Int?, Int>()
            // Named bottom up, so nothing is listed before what it sits on (the kick board, then the bottom, then the divider on it).
            // Then each item goes in only once something it touches is there: a divider that stands only on the
            // shelves either side of it follows them, though it is lower.
            run {
                fun touch(a: Part, b: Part): Boolean { val x = a.mesh.bounds; val y = b.mesh.bounds; return (0..2).all { x.min[it] <= y.max[it] + 0.5 && y.min[it] <= x.max[it] + 0.5 } }
                val there = ArrayList<Part>()
                val before = if (st.subassembly) seqOf(st).filter { it.step < st.step }.flatMap { it.partIds }.toSet() else done
                there += before.mapNotNull { byId[it] }
                there += units.values.flatten()
                if (st.subassembly) there += cur.filter { st.explodeOf.containsKey(it.id) && st.explodeOf[it.id] == null }
                val left = loose.sortedBy { it.mesh.bounds.min.z }.groupBy { it.item }.toMutableMap()
                val ordered = left.keys.toMutableList()
                while (ordered.isNotEmpty()) {
                    val next = if (there.isEmpty()) ordered[0] else ordered.firstOrNull { k -> left.getValue(k).any { p -> there.any { touch(p, it) } } } ?: ordered[0]
                    ordered.remove(next); val ps = left.getValue(next); there += ps
                    count[next] = ps.size
                }
            }
            val unitCount = LinkedHashMap<Int, MutableList<List<Part>>>()
            for ((root, ms) in units) unitCount.getOrPut(benchOf.getValue(kind(unitOf.getValue(root)))) { ArrayList() } += ms
            // This step's parts pulled out, earlier parts in place and greyed, later parts left out.
            // A sub-assembly is made on the bench: only its own parts, framed to fill the picture.
            // The first step of the main build; a pure base step puts parts down and nothing more. When the first part
            // put down shares its step with the parts fitted to it, it is placed and they are fitted.
            // Each of a set of separate pieces starts afresh.
            val firstMain = !st.subassembly && steps.firstOrNull { !it.subassembly && it.piece == st.piece }?.step == st.step
            // Unit ids in [placed] stand for their parts.
            val placedHere = if (firstMain && st.placed.isNotEmpty()) cur.filter { p -> p.id in st.placed || (keyOf[p.id] ?: "") in st.placed }.map { it.id }.toSet() else emptySet()
            val base = firstMain && placedHere.isEmpty()
            // On the bench, a later step of a big unit shows what is already joined, greyed.
            val benchDone = if (st.subassembly) seqOf(st).filter { it.step < st.step }.flatMap { it.partIds }.toSet() else emptySet()
            // One of a set of separate pieces is drawn alone: the pieces already built are elsewhere in the room.
            val pieceIds = if (st.pieces > 1) steps.filter { it.piece == st.piece }.flatMap { it.partIds }.toSet() else null
            val shownDone = if (pieceIds == null) done.toSet() else done.filter { it in pieceIds }.toSet()
            val frame = if (pieceIds == null) frame else ExplodedView.assembledBounds(drawn.filter { it.id in pieceIds }, view)
            val scene = (if (st.subassembly) drawn.filter { it.id in benchDone }.map { it.copy(explode = null) } else drawn.filter { it.id in shownDone }.map { it.copy(explode = null) }) +
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
                ExplodeOptions(balloons = true, arrows = !base, qtyTags = true, highlight = cur.map { it.id }.toSet(), ghost = if (st.subassembly) benchDone else shownDone, textHeight = 3.4,
                    unitCounts = unitCount.values.flatMap { us -> us.flatMap { u -> u.map { it.id to us.size } } }.toMap()),
                view, "step%02d".format(st.step), stepFrame,
            )
            // A picture drawn from behind (the side the parts go in from) says so, or "from the back" reads as a mistake.
            if (view.n.y > 0.1) pic.text(2.0, 2.0, "SEEN FROM THE BACK", 2.6, vAlign = VAlign.BOTTOM)
            val lines = ArrayList<String>()
            for ((bench, us) in unitCount) {
                val sides = us.mapNotNull { u -> u[0].explode?.normalized()?.let { whereFrom(it.x, it.y, it.z) } }.distinct()
                val put = base || us.all { u -> u.all { it.id in placedHere } }
                val where = if (put || sides.isEmpty()) "" else " " + sides.joinToString(" and ")
                val drawer = us.all { u -> keyOf[u[0].id]?.startsWith("drawer") == true }
                // The second of two frames made alike is "the other frame".
                val all = if (us.size == 1 && (placedUnits[bench] ?: 0) > 0) otherOf(bench) else if (us.size == 1 && (steps.firstOrNull { it.step == bench }?.makeCount ?: 1) > 1) "one" else when (us.size) { 1 -> "the"; 2 -> "both"; else -> "all ${us.size}" }
                // A five-piece drawer front is told by the line that screws it on.
                if (bench in dfrontBench && !put) continue
                val door = bench in doorBench
                lines += if (bench in screenBench) "Lay $all ${unitWord(bench, us.size)} from step $bench face down side by side, in order, their edges together."
                    else if (door) "Hang ${if (us.size > 1) "$all doors" else "the door"} from step $bench."
                    else if (drawer) "Slide ${if (us.size > 1) "$all drawers" else "the drawer"} from step $bench in$where."
                    else "${if (put) "Place" else "Fit"} $all ${unitWord(bench, us.size)} from step $bench$where."
            }
            // Consecutive parts that go in the same way are named in one instruction, in order.
            val fits = ArrayList<Triple<String, String, String>>()   // verb, where, what
            val late = ArrayList<Triple<String, String, String>>(); val lateTop = ArrayList<Triple<String, String, String>>()
            for ((item, n) in count) {
                // A wall cleat is not fitted to the piece: its own line says it goes on the wall.
                if (loose.filter { it.item == item }.all { it.name == "Wall cleat" }) continue
                val sides = ArrayList<String>()
                // On the bench a part moves as the bench picture shows it, not as its unit later goes into the build.
                for (p in loose) if (p.item == item) (if (st.explodeOf.containsKey(p.id)) st.explodeOf[p.id] else p.explode)?.let { e -> val d = e.normalized(); whereFrom(d.x, d.y, d.z)?.let { if (it !in sides) sides += it } }
                val where = when {
                    base || (st.step == 1 && !st.subassembly && placedHere.isEmpty()) || sides.isEmpty() -> ""
                    sides.size == 2 && sides.all { it.startsWith("from the ") } -> " from the ${sides[0].removePrefix("from the ")} and ${sides[1].removePrefix("from the ")}"
                    sides.size == 2 && sides.all { it.startsWith("from ") } -> " from ${sides[0].removePrefix("from ")} and ${sides[1].removePrefix("from ")}"
                    else -> " " + sides.joinToString(", ")
                }
                // On the bench, the part that does not move is laid down first and the others joined to it.
                val stillN = if (st.subassembly) cur.count { it.item == item && st.explodeOf.containsKey(it.id) && st.explodeOf[it.id] == null } else cur.count { it.item == item && it.id in placedHere }
                val still = stillN > 0
                val verb = if (base) "Place" else if (still && !st.subassembly) "Place" else if (still) "Lay down" else if (st.subassembly) "Join" else "Fit"
                val what = if (item != null) "item $item" + named(loose.filter { it.item == item }) else "the part"
                // Some of an item laid down and the rest joined on (a slatted panel's bottom rail, then its top rail on the
                // spindles): each said where it happens, the joined ones after everything else.
                val moving = n - stillN
                // On a bench with spindles, a rail resting on top of them goes on after them, whatever else of its item
                // goes on before (a chair back's top rail, while its twin is the apron below).
                val spTop = cur.filter { it.name == "Spindle" }.maxOfOrNull { it.mesh.bounds.max.z }
                val onTop = if (st.subassembly && spTop != null) cur.filter { it.item == item && it.name != "Spindle" && st.explodeOf[it.id] != null && it.mesh.bounds.min.z >= spTop - 0.5 } else emptyList()
                if (onTop.isNotEmpty() && onTop.size < n && !still) {
                    val rest = n - onTop.size
                    fun wh(ps: List<Part>) = ps.mapNotNull { p -> st.explodeOf[p.id]?.normalized()?.let { d -> whereFrom(d.x, d.y, d.z) } }.distinct().let { if (it.isEmpty()) "" else " " + it.joinToString(" and ") }
                    val restParts = cur.filter { it.item == item && it !in onTop }
                    fits += Triple(verb, wh(restParts), "${if (rest > 1) "$rest× " else ""}item $item" + named(restParts))
                    lateTop += Triple("Join", wh(onTop), "${if (onTop.size > 1) "${onTop.size}× " else ""}item $item" + named(onTop))
                    continue
                }
                if (still && moving > 0 && moving < n) {
                    lines.add(0, "$verb ${if (stillN > 1) "$stillN× " else ""}$what.")
                    fun moveOf(p: Part) = if (st.subassembly) st.explodeOf[p.id] else if (p.id in placedHere) null else p.explode
                    val mv = cur.filter { it.item == item && moveOf(it) != null }
                    val mWhere = mv.mapNotNull { p -> moveOf(p)?.normalized()?.let { d -> whereFrom(d.x, d.y, d.z) } }.distinct().let { if (it.isEmpty()) "" else " " + it.joinToString(" and ") }
                    late += Triple(if (st.subassembly) "Join" else "Fit", mWhere, "${if (moving > 1) "$moving× " else ""}$what")
                    continue
                }
                val one = "${if (n > 1) "$n× " else ""}$what"
                if (still) lines.add(0, "$verb $one.") else fits += Triple(verb, where, one)
            }
            fits += lateTop; fits += late
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
                val all = drawn.filter { it.item == item }
                // Or spaced within one level: a bunk bed's two decks of slats are each evenly spaced, not all together.
                val here = cur.filter { it.item == item }
                val level = all.filter { here.any { h -> kotlin.math.abs(h.mesh.bounds.min.z - it.mesh.bounds.min.z) < 1.0 } }
                val levelKey = "$item@${Math.round(here.first().mesh.bounds.min.z)}"
                if (levelKey in levelTold) continue
                val flat = all.all { p -> p.mesh.bounds.extents.let { it.z <= minOf(it.x, it.y) } }
                val whole = if (flat) evenGap(all, drawn) else null
                // Or within one stack: a bay of a bookcase whose shelves line up over each other.
                val h0 = here.first().mesh.bounds
                val stack = all.filter { o -> o.mesh.bounds.min.x < h0.max.x - 0.5 && o.mesh.bounds.max.x > h0.min.x + 0.5 && o.mesh.bounds.min.y < h0.max.y - 0.5 && o.mesh.bounds.max.y > h0.min.y + 0.5 }
                val stackGap = if (whole == null && flat && stack.size in 3 until all.size) evenGap(stack, drawn) else null
                if (stackGap != null) { levelTold += levelKey; levelItems += item }
                // The ones at this height may lie flat while others of the item stand (a planter's slats, cut like its end boards).
                val levelFlat = level.all { p -> p.mesh.bounds.extents.let { it.z <= minOf(it.x, it.y) } }
                val gap = whole ?: stackGap ?: if (levelFlat && level.size >= 3 && level.size < all.size) evenGap(level, drawn) else null
                if (gap != null) {
                    if (whole != null) gapTold += item else { levelTold += levelKey; levelItems += item }
                    val g = "${fmt.format(gap)} ${if (fmt.isInch) "in" else "mm"}"
                    // Named from the parts going in now (the shelves), not every part of the item (its bottom and top too).
                    val nms = cur.filter { it.item == item && it.name != it.id }.map { it.name.lowercase() }.distinct()
                    val nm = nms.singleOrNull()
                    fun pl(w: String) = if (cur.count { it.item == item && it.name.lowercase() == w } < 2) w else if (w.endsWith("helf")) w.dropLast(1) + "ves" else if (w.endsWith("s")) w else w + "s"
                    val what = if (nm == null && nms.size in 2..3) "the " + nms.dropLast(1).joinToString(", ") { pl(it) } + " and " + pl(nms.last()) + " (item $item)" else if (nm != null) "the ${if (nm.endsWith("helf")) nm.dropLast(1) + "ves" else if (nm.endsWith("s")) nm else nm + "s"} (item $item)" else "the item $item parts"
                    lines += "Space $what $g apart, evenly; a spacer block cut to $g keeps every gap the same."
                }
            }
            // Pieces set out apart (two end frames, a desk's two sides) stand alone until the next step ties them, so the
            // step says how far apart they go, read off the model: outside to outside, and any piece between them.
            // A screen's panels are laid face down side by side to be hinged: the hinge line says how.
            if (base && cur.size > 1 && !screen) {
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
                        val inner = sorted.drop(1).dropLast(1)
                        // A row of the same part (posts of a slatted wall): placed by their spacing, not each one "from the" other.
                        val sameRow = inner.size >= 1 && sorted.map { label(it) }.distinct().size == 1
                        val between = if (sameRow) {
                            val centres = sorted.map { (lo(it, axis) + hi(it, axis)) / 2 }
                            val pitch = centres.zipWithNext { a, c -> c - a }
                            if (pitch.max() - pitch.min() <= 1.5) listOf("the ${if (inner.size == 1) "one" else "${inner.size}"} between evenly spaced, ${fmt.format(pitch.average())}$u centre to centre")
                            else listOf("the ${if (inner.size == 1) "one" else "${inner.size}"} between at ${inner.joinToString(", ") { fmt.format((lo(it, axis) + hi(it, axis)) / 2 - lo(sorted.first(), axis)) + u }} to ${if (inner.size == 1) "its" else "their"} centres from the outside of the first")
                        } else inner.map { c ->
                            val centre = (lo(c, axis) + hi(c, axis)) / 2
                            if (kotlin.math.abs(centre - mid) < 2.0) "${label(c)} centred between them"
                            else "${label(c)} ${fmt.format(lo(c, axis) - hi(sorted.first(), axis))}$u in from ${label(sorted.first())}"
                        }
                        // The outer two lined up end to end along the other level axis (a bed's side frames), when the model has them so.
                        val other = (0..1).firstOrNull { it != axis }
                        val level = other != null && kotlin.math.abs(lo(sorted.first(), other) - lo(sorted.last(), other)) < 1.0 && kotlin.math.abs(hi(sorted.first(), other) - hi(sorted.last(), other)) < 1.0
                        // Pieces lying flat (a picture frame's sides) are laid out on the bench; nothing needs holding up.
                        val lying = comps.all { c -> c.all { p -> p.mesh.bounds.extents.let { e -> e.z <= minOf(e.x, e.y) } } }
                        lines += (if (lying) "Lay them flat " else "Stand them ") + "${fmt.format(span)}$u apart, outside to outside" + (if (between.isEmpty()) "" else ", with " + between.joinToString(" and ")) +
                            (if (level && axis != 2) ", ends lined up and square to each other" else ", square to each other") +
                            (if (lying) "." else "; a helper, clamps or a prop holds them up until the next step ties them together.")
                    } else lines += "Stand them in place, spaced to the overall sizes on the drawing and square to each other; a helper, clamps or a prop holds them up until the next step ties them together."
                }
            }
            // Spindles standing in rows on a base board (a drying rack's dividers): the rows, the pitch and where the first
            // stands, rather than each one's place; and the rails that come down on them marked with the base.
            var rowsTold = false
            if (fmt != null && !st.subassembly) {
                val spind = cur.filter { it.name == "Spindle" }
                val sItem = spind.map { it.item }.distinct().singleOrNull()
                val baseB = if (spind.size >= 3 && sItem != null) drawn.firstOrNull { o -> o.id in done && o.name != "Spindle" && spind.all { s -> kotlin.math.abs(o.mesh.bounds.max.z - s.mesh.bounds.min.z) < 1.0 &&
                    s.mesh.bounds.center.x in o.mesh.bounds.min.x..o.mesh.bounds.max.x && s.mesh.bounds.center.y in o.mesh.bounds.min.y..o.mesh.bounds.max.y } } else null
                if (baseB != null) {
                    val ob = baseB.mesh.bounds
                    val ax = if (ob.extents.x >= ob.extents.y) 0 else 1; val cross = 1 - ax
                    val rows = spind.groupBy { kotlin.math.round(it.mesh.bounds.center[cross] / 2.0) }.values.sortedBy { r -> r[0].mesh.bounds.center[cross] }
                    val cs = rows.map { r -> r.map { it.mesh.bounds.center[ax] }.sorted() }
                    val pitches = cs.flatMap { c -> c.zipWithNext { a, b -> b - a } }
                    val even = pitches.isNotEmpty() && pitches.maxOrNull()!! - pitches.minOrNull()!! <= 1.5 && cs.all { kotlin.math.abs(it.first() - cs[0].first()) < 1.0 }
                    if (even && rows.size <= 3) {
                        val u = if (fmt.isInch) " in" else " mm"
                        val bName = "item ${baseB.item ?: "-"}" + named(listOf(baseB))
                        val endWord = if (ax == 0) "left end" else "front edge"
                        val edgeWord = { c: Double -> val f = c - ob.min[cross]; val b = ob.max[cross] - c
                            if (cross == 1) (if (f <= b) "${fmt.format(f)}$u from the front edge" else "${fmt.format(b)}$u from the back edge")
                            else (if (f <= b) "${fmt.format(f)}$u from the left end" else "${fmt.format(b)}$u from the right end") }
                        val rowText = rows.map { r -> edgeWord(r.map { it.mesh.bounds.center[cross] }.average()) }
                        lines += (if (rows.size == 1) "Stand ${spind.size}× item $sItem" + named(spind.take(1)) + " in a row, centred ${rowText[0]}"
                            else "Stand item $sItem" + named(spind.take(1)) + " in ${rows.size} rows of ${rows.map { it.size }.distinct().joinToString(" and ")}, centred ${rowText.joinToString(" and ")}") +
                            ": centres ${fmt.format(pitches.average())}$u apart, the first ${fmt.format(cs[0].first() - ob.min[ax])}$u from the $endWord of $bName."
                        val tops = drawn.filter { o -> o.name != "Spindle" && spind.any { s -> kotlin.math.abs(o.mesh.bounds.min.z - s.mesh.bounds.max.z) < 1.0 &&
                            s.mesh.bounds.center.x in o.mesh.bounds.min.x..o.mesh.bounds.max.x && s.mesh.bounds.center.y in o.mesh.bounds.min.y..o.mesh.bounds.max.y } }
                        val tItems = tops.mapNotNull { it.item }.distinct()
                        if (tItems.isNotEmpty()) lines += "Mark the holes in " + tItems.joinToString(" and ") { ti -> "item $ti" + named(tops.filter { it.item == ti }.take(1)) } + " from $bName before drilling either: lay ${if (tops.size > 1) "each" else "it"} on $bName where it will sit above it and square the marks across, so every spindle stands straight."
                        rowsTold = true
                    }
                }
            }
            // Splayed legs with compound-cut ends (a sawhorse's): how to set each one, since no square shows it.
            if (!st.subassembly) cur.filter { it.name == "Leg" && it.desc.contains("(COMPOUND)") }.let { ls ->
                val m = ls.firstNotNullOfOrNull { Regex("""ENDS CUT AT (\S+) AND (\S+) \(COMPOUND\)""").find(it.desc) }
                val beam = drawn.firstOrNull { it.name == "Beam" }
                if (ls.isNotEmpty() && m != null) lines += "Set each item ${ls[0].item ?: "-"}" + named(listOf(ls[0])) + " splayed as drawn (its ends are cut at ${m.groupValues[1]} and ${m.groupValues[2]}), so both cut ends lie flat" +
                    (beam?.let { b -> ": the top end flush with the top of item ${b.item ?: "-"}" + named(listOf(b)) + ", the foot flat on the floor" } ?: "") + ". Clamp it, check with a bevel gauge, then fix it."
            }
            // A stud wall's frame, laid out flat: the studs' spacing on centre, the usual way framers set them out.
            if (fmt != null && cur.count { it.name == "Stud" } >= 2 && cur.any { it.name == "Plate" }) {
                val studs = cur.filter { it.name == "Stud" }; val plate = cur.first { it.name == "Plate" }
                val ax = if (plate.mesh.bounds.extents.x >= plate.mesh.bounds.extents.y) 0 else 1
                val cs = studs.map { it.mesh.bounds.center[ax] }.sorted(); val u = if (fmt.isInch) " in" else " mm"
                val gaps = cs.zipWithNext { a, b -> b - a }
                val sName = "item ${studs[0].item ?: "-"}" + named(listOf(studs[0])); val pName = "item ${plate.item ?: "-"}" + named(listOf(plate))
                lines += "Lay the two $pName on edge on a flat floor, ${fmt.format(studs[0].mesh.bounds.extents.z)}$u apart inside, and the ${studs.size} $sName between them: one flush with each end" +
                    (if (gaps.isNotEmpty() && gaps.max() - gaps.min() <= 1.5) ", the rest ${fmt.format(gaps[0])}$u apart on centre"
                        else if (cs.size > 2) ", the others' centres ${cs.drop(1).dropLast(1).joinToString(", ") { fmt.format(it - plate.mesh.bounds.min[ax]) + u }} from the ${if (ax == 0) "left" else "front"} end of the plates" else "") +
                    ". Check the frame is square (equal diagonals) before the skin goes on: the skin then holds it so."
            }
            // A case's bottom laid on its plinth: how far it overhangs the plinth on each side, so the base sits set in evenly.
            if (fmt != null && !st.subassembly) {
                val plinth = drawn.filter { it.name == "Plinth" }
                val pb = plinth.map { it.mesh.bounds }.reduceOrNull { x, y -> x.union(y) }
                val onIt = if (pb == null) null else cur.firstOrNull { p -> p.name != "Plinth" && kotlin.math.abs(p.mesh.bounds.min.z - pb.max.z) < 1.0 && p.mesh.bounds.extents.let { e -> e.z < minOf(e.x, e.y) } &&
                    p.mesh.bounds.min.x <= pb.min.x + 0.5 && p.mesh.bounds.max.x >= pb.max.x - 0.5 && p.mesh.bounds.min.y <= pb.min.y + 0.5 && p.mesh.bounds.max.y >= pb.max.y - 0.5 }
                if (pb != null && onIt != null && cur.any { it.name == "Plinth" }) {
                    val ob = onIt.mesh.bounds; val u = if (fmt.isInch) " in" else " mm"
                    val l = pb.min.x - ob.min.x; val r = ob.max.x - pb.max.x; val f = pb.min.y - ob.min.y; val bk = ob.max.y - pb.max.y
                    val name = "item ${onIt.item ?: "-"}" + named(listOf(onIt))
                    if (listOf(l, r, f, bk).all { it > 1.0 }) lines += "Lay $name on the plinth " + (if (listOf(l, r, f, bk).let { it.max() - it.min() } <= 1.0) "overhanging it ${fmt.format(l)}$u all round"
                        else "overhanging it ${fmt.format(f)}$u at the front, ${fmt.format(bk)}$u at the back, ${if (kotlin.math.abs(l - r) <= 1.0) "${fmt.format(l)}$u at each side" else "${fmt.format(l)}$u at the left and ${fmt.format(r)}$u at the right"}") + "; mark the plinth's outline on its underside first."
                }
            }
            // Where loose parts sit on what is built, when nothing else in the step says (a toe-kick set back, a modesty panel set in).
            if (fmt != null && !st.subassembly && !base && !rowsTold) {
                val built = drawn.filter { it.id in done || it.id in placedHere }
                // Only where it reads as an instruction: a busy step of a sculpted model, with parts set every which way, would
                // list offsets the picture already shows better.
                val fitted = loose.filter { it.id !in placedHere }
                val said = if (built.isNotEmpty() && fitted.map { it.item }.distinct().size <= 2) placing(fitted, fmt, { p -> "item ${p.item ?: "-"}" + named(listOf(p)) }, built, gapTold + levelItems) else emptyList()
                if (said.size <= MAX_PLACING) lines += said
            }
            // How the step is held together, when the joining method is known; otherwise the general reminder.
            val how = if (fasten == null) emptyList() else splitziner.joinery.Fastening.lines(fasten, st.step, { p -> "item ${p.item ?: "-"}" + named(listOf(p)) })
            if (st.subassembly) {
                // Spindles between two rails: the gap, with a spacer block, rather than each one's place along the rail.
                val spind = cur.filter { it.name == "Spindle" }
                val sItem = spind.map { it.item }.distinct().singleOrNull()
                val sGap = if (fmt != null && sItem != null && spind.size >= 3) evenGap(spind, cur) else null
                if (fmt != null && sGap != null) {
                    val u = if (fmt.isInch) " in" else " mm"
                    val b = spind.map { it.mesh.bounds }
                    val ax = (0..1).maxByOrNull { k -> b.maxOf { it.center[k] } - b.minOf { it.center[k] } }!!
                    val rail = cur.filter { it.name != "Spindle" && spind.any { s -> kotlin.math.abs(it.mesh.bounds.max.z - s.mesh.bounds.min.z) < 1.0 } }
                    val ends = if (rail.isEmpty()) null else (b.minOf { it.min[ax] } - rail.minOf { it.mesh.bounds.min[ax] }) to (rail.maxOf { it.mesh.bounds.max[ax] } - b.maxOf { it.max[ax] })
                    val endText = when {
                        ends == null || kotlin.math.abs(ends.first - ends.second) > 1.5 -> ""
                        kotlin.math.abs(ends.first - sGap) <= 1.5 -> ", with the same gap at each end of the rail"
                        else -> ", the first and last ${fmt.format(ends.first)}$u from the ends of the rail"
                    }
                    lines += "Space the spindles (item $sItem) ${fmt.format(sGap)}$u apart, evenly$endText; a spacer block cut to ${fmt.format(sGap)}$u keeps every gap the same."
                }
                // A ladder's rungs, evenly pitched: the rise between them and where the lowest sits, not each one's height.
                val rungs = cur.filter { it.name == "Rung" }
                val rItem = rungs.map { it.item }.distinct().singleOrNull()
                val rGap = if (fmt != null && rItem != null && rungs.size >= 3) evenGap(rungs, cur) else null
                if (fmt != null && rGap != null) {
                    val u = if (fmt.isInch) " in" else " mm"
                    val sides = cur.filter { it.name == "Ladder side" }
                    val lowest = rungs.minOf { it.mesh.bounds.min.z } - (sides.minOfOrNull { it.mesh.bounds.min.z } ?: rungs.minOf { it.mesh.bounds.min.z })
                    val pitch = rGap + rungs[0].mesh.bounds.extents.z
                    lines += "Mark the rungs (item $rItem) on both sides at once, clamped together: the lowest ${fmt.format(lowest)}$u up from the bottom, then every ${fmt.format(pitch)}$u, so every rung is level and every step the same; a spacer block cut to ${fmt.format(rGap)}$u sets each gap."
                }
                val doorGroup = if (st.step in doorBench || st.step in fpanelBench || st.step in dfrontBench) runCatching { splitziner.check.FramePanels.find(cur) }.getOrDefault(emptyList()).firstOrNull() else null
                if (doorGroup != null) {
                    // Glued up round its panel: one stile down, the rails into it, the panel slid into the grooves, the other stile on.
                    val nm = { id: String -> cur.first { it.id == id }.let { p -> "item ${p.item ?: "-"}" + named(listOf(p)) } }
                    val rails = doorGroup.rails.map(nm).distinct()
                    lines.removeAll { it.startsWith("Lay down ") || it.startsWith("Join ") }
                    val panels = doorGroup.panels.map(nm).distinct()
                    // The rails meet the stiles; anything else (a muntin, a middle rail) sits between the rails.
                    val st0 = cur.first { it.id == doorGroup.stiles[0] }.mesh.bounds
                    fun meetsStile(id: String) = cur.first { it.id == id }.mesh.bounds.let { rb -> (0..2).all { a -> rb.min[a] <= st0.max[a] + 0.5 && st0.min[a] <= rb.max[a] + 0.5 } }
                    val ends = doorGroup.rails.filter { meetsStile(it) }; val mids = doorGroup.rails - ends.toSet()
                    val endNames = ends.map(nm).distinct()
                    val endText = if (ends.size == 2 && endNames.size == 1) "both ${endNames[0]} into it, one at each end" else "${endNames.joinToString(" and ")} into it, one at each end"
                    val midText = if (mids.isEmpty()) "" else ", with ${mids.map(nm).distinct().joinToString(" and ")} between them where the drawing shows"
                    val panelText = if (doorGroup.panels.size == 1) "slide ${panels[0]} into their grooves until it seats in the groove in ${nm(doorGroup.stiles[0])}"
                        else "slide ${if (panels.size == 1) "each ${panels[0]}" else panels.joinToString(" and ")} into the grooves as the pieces go together"
                    lines += "Lay one ${nm(doorGroup.stiles[0])} flat, its groove facing in. Fit $endText$midText, $panelText, then put on the other ${nm(doorGroup.stiles[1])} and close the joints."
                }
                // A screen panel: the frame first, face down, joined; then the sheet glued on its back.
                if (st.step in screenBench) {
                    lines.removeAll { it.startsWith("Lay down ") || it.startsWith("Join ") }
                    val nm = { ps: List<Part> -> ps.mapNotNull { it.item }.distinct().joinToString(" and ") { i -> "item $i" + named(cur.filter { it.item == i }) } }
                    val frameParts = cur.filter { it.name == "Stile" || it.name == "Rail" }; val sheet = cur.filter { it.name == "Panel" }
                    lines += "Lay ${nm(frameParts.filter { it.name == "Stile" })} and ${nm(frameParts.filter { it.name == "Rail" })} face down on a flat bench, the rails between the stiles${if (frameParts.count { it.name == "Rail" } > 2) " (the middle one centred on the height)" else ""}, and join the frame first; then glue ${nm(sheet)} on its back, edges flush."
                }
                if (fmt != null && doorGroup == null && st.step !in screenBench) lines += placing(cur, fmt, { p -> "item ${p.item ?: "-"}" + named(listOf(p)) }, skipItems = listOfNotNull(if (sGap != null) sItem else null, if (rGap != null) rItem else null).toSet())
                // A panel with strips screwed to its face has no corners to square.
                if (st.step in cpanelBench) { if (how.isEmpty()) lines += "Clamp them in place, then fasten." }
                else lines += if (how.isEmpty()) "Check it is flat and square (equal diagonals), then fasten." else "Check it is flat and square (equal diagonals)."
                lines += how
                // The grooves are cut before anything goes together.
                if (doorGroup != null) lines.filter { it.startsWith("Cut a groove") }.let { g -> lines.removeAll(g); lines.addAll(0, g) }
                // A glazed door is glued up empty; its glass goes in at the end.
                if (doorGroup != null && lines.any { it.startsWith("Glue the frame up empty") }) {
                    val i = lines.indexOfFirst { it.startsWith("Lay one ") }
                    if (i >= 0) lines[i] = lines[i].replace(", its groove facing in.", ".").replace(Regex(""", slide [^,]+ into [^,]+ until it seats in the groove in [^,]+,"""), ",").replace(Regex(""", slide [^,]+ into the grooves as the pieces go together,"""), ",")
                }
                val seq = seqOf(st)
                if (st.makeCount > 1 && seq.size > 1 && st.continues == null) lines.add(0, "Build all ${st.makeCount} side by side, a step at a time: this step on each, then the next.")
                if (st.makeCount > 1 && (seq.size <= 1 || st.step == seq.maxOf { it.step })) lines += "Make ${st.makeCount} of these, all the same."
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
                // Bought parts (casters) are in the hardware list below, not here.
                count.filter { (item, _) -> loose.none { it.item == item && it.name == "Caster" } }.map { (item, n) -> "Item ${item ?: "-"}${named(loose.filter { it.item == item })}: ${loose.first { it.item == item }.desc.lowercase()}" to n }
            val o = overrides[stepKey(st.partIds)]
            val auto = when {
                st.subassembly -> {
                    val its = count.keys.filterNotNull().sorted()  // a bench step has no units, so count holds all its parts
                    val names = if (its.size == 1) "item ${its[0]}" else "items ${its.dropLast(1).joinToString(", ")} and ${its.last()}"
                    val isDrawer = st.partIds.all { keyOf[it]?.startsWith("drawer") == true }
                    if (isDrawer) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} drawer boxes from" else "make the drawer box from"} $names"
                    else if (st.step in screenBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} screen panels from" else "make the screen panel from"} $names"
                    else if (st.step in faceBench) "Step ${st.step}: make the face frame from $names"
                    else if (st.step in slattedBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} slatted panels from" else "make the slatted panel from"} $names"
                    else if (st.step in plinthBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} plinths from" else "make the plinth from"} $names"
                    else if (st.step in ladderBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} ladders from" else "make the ladder from"} $names"
                    else if (st.step in doorBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} doors from" else "make the door from"} $names"
                    else if (st.step in dfrontBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} drawer fronts from" else "make the drawer front from"} $names"
                    else if (st.step in bedEnds) "Step ${st.step}: make the ${bedEnds.getValue(st.step)} from $names"
                    else if (st.step in fpanelBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} panelled frames from" else "make the panelled frame from"} $names"
                    else if (st.step in cpanelBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} cleated panels from" else "make the cleated panel from"} $names"
                    else if (st.step in cradleBench) "Step ${st.step}: make the cradle from $names"
                    else if (st.step in sboxBench) "Step ${st.step}: make the box from $names"
                    else if (st.step in moduleBench || st.step in upperBench) {
                        val seq = seqOf(st); val k = seq.indexOfFirst { it.step == st.step } + 1
                        "Step ${st.step}: build ${if (st.makeCount > 1) "${st.makeCount} ${unitWord(st.step, 2)}" else "the ${unitWord(st.step, 1)}"}" + (if (seq.size > 1) " ($k of ${seq.size}): $names" else " from $names")
                    }
                    else if (st.step in tenonRail) "Step ${st.step}: glue the tenons into ${if (st.makeCount > 1) "${st.makeCount} ${unitWord(st.step, 2)}" else "the ${unitWord(st.step, 1)}"} ($names)"
                    else if (st.step in trestleBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} trestle ends from" else "make the trestle end from"} $names"
                    else if (st.step in frameBench) "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} frames from" else "make the frame from"} $names"
                    else if (st.step in letterOf) {
                        val seq = seqOf(st); val k = seq.indexOfFirst { it.step == st.step } + 1
                        "Step ${st.step}: make ${if (st.makeCount > 1) "${st.makeCount} of " else ""}sub-assembly ${letterOf[st.step]}" + (if (seq.size > 1) " ($k of ${seq.size}): $names" else " from $names")
                    }
                    else "Step ${st.step}: ${if (st.makeCount > 1) "make ${st.makeCount} by joining" else "join"} $names"
                }
                else -> {
                    // What goes on, in plain words: "Step 6: the back panel", "Step 5: 4 shelves", "Step 8: hang the drawers".
                    fun plural(w: String, n: Int) = if (n == 1) w else pluralOf(w)
                    val bits = ArrayList<String>()
                    // Drawers of several sizes are still just drawers: "hang the 4 drawers".
                    val drawers = unitCount.values.filter { us -> us.all { u -> keyOf[u[0].id]?.startsWith("drawer") == true } }.sumOf { it.size }
                    if (drawers > 0) bits += if (drawers == 1) "hang the drawer" else "hang the $drawers drawers"
                    for ((bench, us) in unitCount) {
                        if (us.all { u -> keyOf[u[0].id]?.startsWith("drawer") == true }) continue
                        if (bench in doorBench) { bits += if (us.size == 1) "hang the door from step $bench" else "hang the ${us.size} doors from step $bench"; continue }
                        // A lettered sub-assembly is named by its letter alone: "sub-assembly A from step 1".
                        bits += if (us.size == 1) (if (bench in letterOf && (placedUnits[bench] ?: 0) == 0) "" else "${if ((placedUnits[bench] ?: 0) > 0) otherOf(bench) else "the"} ") + "${unitWord(bench, 1)} from step $bench"
                            else "the ${us.size} ${unitWord(bench, us.size)} from step $bench"
                    }
                    // Parts of one name are counted together, whatever their item numbers: "3 drawer fronts".
                    val byName = LinkedHashMap<String, Int>()
                    for ((item, n) in count) {
                        val ps = loose.filter { it.item == item }
                        // Every part named: counted by its own name ("the bottom and 3 shelves", one item under two names).
                        if (ps.all { it.name != it.id }) { for (p in ps) { val k = splitziner.parts.Distinct.shown(p, shownNames).lowercase().replace("french", "French"); byName[k] = (byName[k] ?: 0) + 1 }; continue }
                        val key = "\u0000" + (if (item != null) "item $item" else "part")
                        byName[key] = (byName[key] ?: 0) + n
                    }
                    for ((key, n) in byName) {
                        if (key.startsWith("\u0000")) { val w = key.drop(1); bits += if (n == 1) w else "$n× $w" }
                        else if (n == 1) {
                            // The second of two rails, a step after the first: "the other rail", not "the rail" again.
                            fun nm(p: Part) = splitziner.parts.Distinct.shown(p, shownNames).lowercase().replace("french", "French")
                            val total = byId.values.count { nm(it) == key }; val before = done.count { id -> byId[id]?.let { nm(it) } == key }
                            bits += if (before > 0 && total == 2) "the other $key" else if (before > 0) "another $key" else "the $key"
                        }
                        else bits += "${if (base) "the " else ""}$n ${plural(key, n)}"
                    }
                    val said = if (bits.size == 1) bits.firstOrNull() else bits.dropLast(1).joinToString(", ") + " and " + bits.lastOrNull()
                    // The first step says where the build starts: "Step 3: start with the 2 frames from step 1 and ...".
                    // Only made sub-assemblies to set out: "Step 3: set out the 3 frames".
                    val unitsOnly = base && loose.isEmpty() && unitCount.isNotEmpty() && unitCount.values.sumOf { it.size } > 1
                    val nUnits = unitCount.values.sumOf { it.size }
                    if (unitsOnly && unitCount.keys.all { it in screenBench }) "Step ${st.step}: hinge the $nUnits screen panels together"
                    else if (unitsOnly && unitCount.keys.all { it in bedEnds } && unitCount.keys.map { bedEnds[it] }.toSet() == setOf("headboard", "footboard")) "Step ${st.step}: set out the headboard and footboard"
                    else if (unitsOnly) "Step ${st.step}: set out the $nUnits ${if (unitCount.keys.all { it in trestleBench }) "trestle ends" else if (unitCount.keys.all { it in frameBench }) "frames" else if (unitCount.keys.all { it in moduleBench }) unitWord(unitCount.keys.first(), 2) else if (unitCount.keys.size == 1 && (unitCount.keys.first() in letterOf || unitCount.keys.first() in cpanelBench)) unitWord(unitCount.keys.first(), 2) else "sub-assemblies"}"
                    else if (bits.isEmpty()) (if (base) "Step ${st.step}: start here" else "Step ${st.step}")
                    // Many kinds of part: the first few, and how many more.
                    else if (bits.size > 3) "Step ${st.step}: ${if (firstMain) "start with " else ""}${bits.take(2).joinToString(", ")} and ${bits.size - 2} more"
                    else if (firstMain) "Step ${st.step}: start with $said"
                    else "Step ${st.step}: $said"
                }
            }
            val title0 = o?.title?.takeIf { it.isNotBlank() }?.let { "Step ${st.step}: $it" } ?: auto
            // The first step of each of a set of separate pieces says which piece it starts.
            val pieceFirst = st.pieces >= 1 && steps.firstOrNull { it.piece == st.piece }?.step == st.step
            val alikeText = if (st.alike > 1) "make ${st.alike} alike" else null
            val title = if (pieceFirst && st.pieces > 1) title0.replaceFirst("Step ${st.step}: ", "Step ${st.step}, piece ${st.piece} of ${st.pieces}${alikeText?.let { " ($it)" } ?: ""}: ")
                else if (pieceFirst && alikeText != null) title0.replaceFirst("Step ${st.step}: ", "Step ${st.step} ($alikeText): ") else title0
            for ((bench, us) in unitCount) placedUnits[bench] = (placedUnits[bench] ?: 0) + us.size
            // The list says how many of each part the whole step needs, all copies of the sub-assembly included.
            // And the hardware the step uses, so it can be laid out with the parts.
            val hw = LinkedHashMap<String, Int>()
            for (j in fasten?.byStep?.get(st.step).orEmpty()) {
                if (j.glueOnly || j.count <= 0) continue
                val key = when (j.kind) {
                    "brackets", "corner" -> "corner brackets"; "screenhinge" -> "${j.size} butt hinges"; "legplate" -> splitziner.joinery.Fastening.LEG_PLATES; "roundfoot" -> splitziner.joinery.Fastening.FOOT_BOLTS; "runners" -> splitziner.joinery.Fastening.HANDLE; "foldhinge" -> "pair of ${j.size} butt hinges"; "lid" -> j.size; "wallcleat" -> continue; "slides" -> "pair of ${j.size} drawer slides"; "hinges" -> if (j.size == "inset hinges") "inset face-frame hinges" else "concealed hinges"; "pins" -> "shelf pins"; "applied" -> "${j.size} screws"
                    "laminate", "front", "mitre" -> "${j.size} brads"; "pocketin" -> "${j.size} pocket screws"; "dowels" -> j.size; "thin", "edge", "housed", "panel", "peghole", "rodhole", "glass" -> continue
                    "up", "cabinets", "counterbored", "under" -> "${j.size} screws"; "rod" -> "closet rod sockets"; "caster" -> "casters"; "faceglue", "cratepack", "facebox" -> continue; "stop" -> "${j.size} brads"; "backscrew" -> "${j.size} screws"; "balltop", "pedtop" -> "${j.size} screws"; "bedrail" -> "pairs of bed rail fasteners"; "edgebolt" -> "worktop connector bolts"; "sliding" -> "double sliding-door track set"
                    else -> "${j.size} ${j.kind}"
                }
                hw[key] = (hw[key] ?: 0) + (if (j.kind == "slides" || j.kind == "sliding" || j.kind == "foldhinge") 1 else j.count)
                if (j.kind == "lid") hw[splitziner.joinery.LID_SUPPORT] = (hw[splitziner.joinery.LID_SUPPORT] ?: 0) + 1
                if (j.kind == "foldhinge") hw[splitziner.joinery.foldStop(j.size.endsWith(" in"))] = 1
                if (j.kind == "sliding") hw["flush pulls (recessed handles)"] = (hw["flush pulls (recessed handles)"] ?: 0) + j.count
                if (j.kind == "counterbored") { val plug = if (j.at.endsWith(" in")) "3/8 in wood plugs" else "10 mm wood plugs"; hw[plug] = (hw[plug] ?: 0) + j.count }
                // Each drawer and door gets a handle; its style is the maker's choice.
                if (j.kind == "hinges" || j.kind == "applied" || (j.kind == "slides" && j.into === j.through)) hw[splitziner.joinery.Fastening.HANDLE] = (hw[splitziner.joinery.Fastening.HANDLE] ?: 0) + 1
            }
            // All the copies' worth: of a sub-assembly made several times, and of identical pieces made alike.
            val mult = maxOf(1, st.makeCount) * maxOf(1, st.alike)
            val need = (if (mult > 1) list.map { (k, v) -> k to v * mult } else list) +
                hw.map { (k, v) -> k.replaceFirstChar { it.uppercase() } to v * mult }
            out += GuideStep(st.step, title, pic, o?.lines?.takeIf { it.isNotEmpty() } ?: lines.map { readable(it) }, need, o?.check?.takeIf { it.isNotBlank() })
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
    fun beforeAndAfter(bandItems: List<Int>, groovedItems: List<Int>, screws: Boolean, pocketHoles: Map<Int, Int> = emptyMap(), pinItems: List<Int> = emptyList(), thinItems: List<Int> = emptyList(), dowels: String? = null, glued: List<String> = emptyList(), pilots: String? = null): Pair<List<String>, List<String>> {
        fun list(i: List<Int>) = if (i.size == 1) "item ${i[0]}" else "items ${i.dropLast(1).joinToString(", ")} and ${i.last()}"
        val before = ArrayList<String>()
        before += "Cut every part to the cut list, and pencil its item number on a face that will not show."
        before += glued
        if (thinItems.isNotEmpty()) before += "Thin panels are held in a groove or rabbet, or against a glued strip (a cleat): the steps say where. If you choose grooves or rabbets, cut them in ${list(thinItems)} now, while the boards are loose, and add their depth to the thin panels."
        if (pinItems.isNotEmpty()) before += "Drill the shelf-pin holes in ${list(pinItems.sorted())} (${if (pinItems.size == 1) "its drawing gives" else "their drawings give"} the rows) before anything is joined, with a jig or a drilling guide so the rows match."
        if (groovedItems.isNotEmpty()) before += "Cut the grooves, rabbets and mortises that ${list(groovedItems.sorted())} ${if (groovedItems.size == 1) "needs" else "need"} (${if (groovedItems.size == 1) "its drawing gives" else "their drawings give"} the sizes) before anything is joined: it is far easier on a loose board."
        if (pocketHoles.isNotEmpty()) before += "Drill the pocket holes with the jig set to each board's thickness, on faces that will not show: " +
            pocketHoles.entries.joinToString("; ") { (i, n) -> "item $i, $n holes in all" } + ". Each goes in the end (or edge) that meets another part's face."
        if (dowels != null) {
            val dia = dowels.substringBefore(" x "); val len = dowels.substringAfter(" x ").substringBefore(" dowels")
            before += "Drill the dowel holes before anything is joined. Hold each pair of parts together and mark across the joint, then drill with a doweling jig: $dia${if (len.contains("mm")) " mm" else " in"} holes, square to the surface, a little over half the dowel's $len into the end or edge and the rest into the face, never more than two-thirds of the way through a board. A dry fit shows whether they line up."
        }
        if (bandItems.isNotEmpty()) before += "If the panels are plywood or particleboard, iron edge banding onto the edges that will show on ${list(bandItems.sorted())} and trim it flush. Banded edges are easier to do now than once it is built."
        if (pilots != null) before += pilots
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
            if (45.0 + splitziner.sheet.StrokeFont.width(v, 3.2) > w / 2 - 3) { y -= 5.5; p.text(25.0, y, v, 3.2, vAlign = VAlign.TOP) }
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
            val after = info.after + if (info.hardware.any { it.contains("anti-tip") }) listOf("Stand it where it will live and fix it to the wall with the anti-tip strap, screwed into a stud.")
                else if (info.hardware.any { it.contains("to hang it") } && steps.any { s -> s.text.any { it.contains("slopes lock together") } }) emptyList()
                else if (info.hardware.any { it.contains("to hang it") }) listOf("Hang it: find the studs and mark them, screw a straight batten to the wall level at the height of its bottom to rest it on, lift it onto the batten and drive two of the hanging screws through the hanging rail and the back into each stud it crosses; take the batten off after. On a masonry wall use plugs and screws made for it.")
                else emptyList()
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
                val need = 10 + steps.sumOf { wrapHanging("0. $it", 3.0, w - 56, 4.0).size * 4.5 + 1 }
                if (yy - need < 16) { pg = pages.new(); yy = h - 26 }
                // A long heading (a raised panel's) is set smaller, then broken after a comma, never run off the page.
                val room = w - 50
                val hs = (3.4 * room / StrokeFont.width(head, 3.4)).coerceIn(2.8, 3.4)
                val lines = if (StrokeFont.width(head, hs) <= room || !head.contains(", ")) listOf(head)
                    else head.split(", ").fold(listOf("")) { acc, part -> val last = acc.last(); val joined = if (last.isEmpty()) part else "$last, $part"
                        if (last.isEmpty() || StrokeFont.width(joined, hs) <= room) acc.dropLast(1) + joined else acc.dropLast(1) + "$last," + part }
                for (ln in lines) { pg.text(25.0, yy, ln, hs, vAlign = VAlign.TOP, bold = true); yy -= hs + 2.6 }
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
