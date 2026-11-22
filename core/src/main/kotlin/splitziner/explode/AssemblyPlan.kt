package splitziner.explode

import org.locationtech.jts.geom.Geometry
import splitziner.math.Vec3
import splitziner.mesh.DisjointSet
import splitziner.mesh.MeshOps
import splitziner.parts.Part
import splitziner.view.ViewDef
import kotlin.math.ceil

/** One step of the build: the parts that go on, and how the instructions describe it. */
data class BuildStep(
    val step: Int,
    val partIds: List<String>,
    /** Unit directions the step's parts leave the assembly by; they are inserted the opposite way. */
    val directions: List<Vec3>,
    val items: List<Int>,
    val title: String = "",
    val text: List<String> = emptyList(),
    val check: String? = null,
    /** A step made on the bench before the main build: joining the boards of one sub-assembly (a frame, a box). */
    val subassembly: Boolean = false,
    /** How far each part is pulled out in this step's picture, where it differs from the part's own. */
    val explodeOf: Map<String, Vec3?> = emptyMap(),
    /** For a sub-assembly step: how many identical ones to make. */
    val makeCount: Int = 1,
)

class PlanResult(val parts: List<Part>, val steps: List<BuildStep>)

/**
 * Drafts a build order by taking the finished object apart.
 *
 * Repeatedly, every part that can slide straight out along a principal axis without hitting the
 * parts still present is removed (touching is allowed). Reversed, the removal layers are a build
 * order. The base (the largest part resting on the floor) never moves, and pulling a part straight
 * down is tried last, since building from underneath fights gravity.
 *
 * The result only proves each part can slide into place past earlier ones. It knows nothing of
 * glue, clamps or fasteners, so it is a draft for a person to confirm.
 */
object AssemblyPlan {
    private class Dir(val k: Int, val sign: Int) {
        override fun equals(other: Any?) = other is Dir && other.k == k && other.sign == sign
        override fun hashCode() = k * 3 + sign
        fun vec() = Vec3.axis(k, sign.toDouble())
    }

    /**
     * [order], when given, is the user's own build order: part ids per step, base step first. The
     * automatic order is then skipped; each part is still given the first direction it can slide in
     * from past the parts of earlier steps. Parts the list does not mention are added as a last step.
     */
    private fun byAxisX(lo: List<Vec3>, n: Int) = (0 until n).sortedBy { lo[it].x }

    /**
     * Parts that came from one part of the drawing (split into boards: ids "P02", "P02.2", ...) are a
     * sub-assembly. Each kind is made first, on the bench, as its own step; the main build then places
     * each sub-assembly whole. Without sub-assemblies, or with the user's own order, this is [planCore].
     */
    /**
     * Which unit (sub-assembly) each part belongs to: the boards that came from one drawn part share the id before
     * the dot ("P02", "P02.2"), and the parts of a drawer box are one unit too, made on the bench and hung whole.
     */
    fun unitKeys(parts: List<Part>): Map<String, String> {
        val live = parts.filter { !it.excluded }
        val out = HashMap<String, String>()
        for (p in live) out[p.id] = p.id.substringBefore('.')
        // An applied front is not part of its drawer's unit: it goes on after the drawer hangs, so its gaps can be set.
        runCatching { splitziner.check.Drawers.find(live) }.getOrDefault(emptyList()).forEachIndexed { k, d -> for (id in d.boxIds) out[id] = "drawer${k + 1}" }
        legFrames(live).forEachIndexed { k, ids -> for (id in ids) out[id] = "frame${k + 1}" }
        return out
    }

    /**
     * A table or bench on legs is built as end frames: two legs and the short rails between them, glued up flat on
     * the bench, then stood up and joined by the long rails. Building leg by leg instead leaves a single leg
     * standing alone in step 1. The frames are the rails of one item running one way that pair every leg exactly
     * once; of those, the shortest, as a woodworker would make the ends first.
     */
    fun legFrames(live: List<Part>): List<List<String>> {
        val legs = live.filter { it.name == "Leg" && '.' !in it.id }
        if (legs.size < 4 || legs.size % 2 != 0) return emptyList()
        fun touch(a: Part, b: Part): Boolean { val x = a.mesh.bounds; val y = b.mesh.bounds; return (0..2).all { x.min[it] <= y.max[it] + 0.5 && y.min[it] <= x.max[it] + 0.5 } }
        fun longAxis(p: Part): Int { val e = p.mesh.bounds.extents; return if (e.x >= e.y && e.x >= e.z) 0 else if (e.y >= e.z) 1 else 2 }
        // Rails on edge only: a board lying flat across two legs (a slat) is not part of a frame.
        fun flat(p: Part) = p.mesh.bounds.extents.let { it.z <= minOf(it.x, it.y) }
        val rails = live.filter { (it.name == "Apron" || it.name.endsWith("ail")) && !flat(it) }.map { r -> r to legs.filter { touch(r, it) } }.filter { it.second.size == 2 && longAxis(it.first) != 2 }
        val legIds = legs.map { it.id }.toSet()
        // Rails one way whose leg pairs cover every leg once: an apron and a stretcher between the same two legs
        // are one pair, so a frame can have both.
        val pick = rails.groupBy { longAxis(it.first) }.values.mapNotNull { g ->
            val pairs = g.map { it.second.map { l -> l.id }.toSet() }.distinct()
            val ls = pairs.flatten()
            if (ls.size == legIds.size && ls.toSet() == legIds && pairs.size >= 2) pairs to g else null
        }.minByOrNull { (_, g) -> g[0].first.mesh.bounds.extents.let { maxOf(it.x, it.y) } } ?: return emptyList()
        return pick.first.map { pair ->
            val frameRails = rails.filter { it.second.map { l -> l.id }.toSet() == pair }.map { it.first }
            // A cleat screwed along one of the frame's rails is made with it on the bench.
            val cleats = live.filter { c -> c.name == "Cleat" && frameRails.any { r -> touch(c, r) } }
            pair.toList() + frameRails.map { it.id } + cleats.map { it.id }
        }
    }

    fun plan(all: List<Part>, maxSteps: Int = 10, order: List<List<String>>? = null): PlanResult {
        val live = all.filter { !it.excluded }
        val keyOf = unitKeys(live)
        val units = live.groupBy { keyOf.getValue(it.id) }
        // Two boards (an L, a pair of strips) are simply fitted one after the other; a bench step for them is a page of nothing.
        val subs = units.filter { it.value.size >= MIN_SUB_PARTS }
        if (order != null) return planCore(all, maxSteps, order)
        if (subs.isEmpty()) {
            val r = planCore(all, maxSteps, order)
            val (st, ren) = mergeFloating(r.steps, live)
            if (st.size == r.steps.size && ren.all { (k, v) -> k == v }) return r
            return PlanResult(shareGroups(r.parts.map { p -> p.copy(step = p.step?.let { ren[it] ?: it }) }), st)
        }
        // Identical sub-assemblies (two leg frames) are made in one step: same items in the same numbers.
        val kinds = subs.values.groupBy { u -> u.mapNotNull { it.item }.sorted() }.values.toList()
        val bench = ArrayList<BuildStep>()
        val benchExplode = HashMap<String, Vec3?>()
        for (kind in kinds) {
            val ids = ArrayList<String>()
            for (u in kind) {
                val inner = planCore(u, maxSteps = 2)
                val unitTop = u.maxOf { it.mesh.bounds.max.z }
                for (p in inner.parts) {
                    val e = p.explode
                    // Pulled straight at the viewer, a rail is drawn across the leg it joins. One at the top of the
                    // frame is lifted out upward instead, which nothing in the frame blocks and which reads clearly.
                    val b = p.mesh.bounds
                    val overlapsAbove = u.any { o -> o !== p && o.mesh.bounds.min.z >= b.max.z - 0.5 &&
                        minOf(o.mesh.bounds.max.x, b.max.x) > maxOf(o.mesh.bounds.min.x, b.min.x) && minOf(o.mesh.bounds.max.y, b.max.y) > maxOf(o.mesh.bounds.min.y, b.min.y) }
                    benchExplode[p.id] = if (e != null && (p.name == "Apron" || p.name.endsWith("ail")) && kotlin.math.abs(e.z) < 0.1 * e.length && b.extents.z < maxOf(b.extents.x, b.extents.y) &&
                        kotlin.math.abs(if (b.extents.x >= b.extents.y) e.x else e.y) < 0.5 * e.length && b.max.z >= unitTop - 0.5 && !overlapsAbove) Vec3(0.0, 0.0, e.length) else e
                }
                ids += u.map { it.id }
            }
            val first = kind.first()
            bench += BuildStep(bench.size + 1, first.map { it.id }, emptyList(), first.mapNotNull { it.item }.distinct().sorted(),
                subassembly = true, explodeOf = first.associate { it.id to benchExplode[it.id] }, makeCount = kind.size)
        }
        // The main build, with each sub-assembly as one rigid unit.
        val unitParts = units.flatMap { (root, ms) ->
            if (root !in subs) ms else listOf(Part(root, root, MeshOps.concatenate(ms.map { it.mesh }), item = ms[0].item))
        }
        // The main build keeps a readable number of steps of its own, however many sub-assemblies there are.
        // About three things fitted per step reads well; a step that places a whole pedestal and its drawers does not.
        // Drawers are made whole and hung last, with any doors and shelves on pins.
        val appliedFronts = runCatching { splitziner.check.Drawers.find(live).flatMap { d -> d.ids - d.boxIds.toSet() } }.getOrDefault(emptyList()).toSet()
        val hungMain = runCatching { splitziner.check.Doors.find(live).map { it.id } + splitziner.check.ShelfPins.find(live) }.getOrDefault(emptyList()).toSet() +
            unitParts.map { it.id }.filter { it.startsWith("drawer") } + appliedFronts
        val main = planCore(unitParts, maxOf(MIN_MAIN_STEPS, maxSteps - bench.size, minOf(MAX_MAIN_STEPS, ceil(unitParts.size / 3.0).toInt())), hungGiven = hungMain, hungFirst = appliedFronts)
        val (mainSteps, renumber) = mergeFloating(main.steps, unitParts, subs)
        val unitOf = main.parts.associate { it.id to it.copy(step = it.step?.let { s0 -> renumber[s0] ?: s0 }) }
        val steps = ArrayList<BuildStep>(bench)
        for (st in mainSteps) {
            val ids = st.partIds.flatMap { id -> subs[id]?.map { it.id } ?: listOf(id) }
            steps += st.copy(step = steps.size + 1, partIds = ids, items = ids.mapNotNull { id -> live.first { it.id == id }.item }.distinct().sorted())
        }
        val firstMain = bench.size + 1
        val out = all.map { p ->
            if (p.excluded) p else {
                val u = unitOf[p.id] ?: unitOf[keyOf[p.id] ?: p.id.substringBefore('.')]
                p.copy(explode = u?.explode, group = u?.group, step = (u?.step ?: 1) + firstMain - 1)
            }
        }
        return PlanResult(shareGroups(out), steps)
    }

    /**
     * A step that stands something on the floor touching nothing already built (a desk's second side, a table's
     * second end frame) cannot be fastened yet: it goes in with the step after it, which joins it to the rest.
     * Returns the steps and, per old step number, the new one.
     */
    private fun mergeFloating(steps: List<BuildStep>, units: List<Part>, members: Map<String, List<Part>> = emptyMap()): Pair<List<BuildStep>, Map<Int, Int>> {
        val (q0, rq) = railsBeforeSlats(steps, units, members)
        val (s00, rs0) = supportsFirst(q0, units, members)
        val rs = rq.mapValues { (_, v) -> rs0[v] ?: v }; val s0 = s00
        val (a00, r00) = backLast(s0, units)
        val r0 = rs.mapValues { (_, v) -> r00[v] ?: v }; val a0 = a00
        val (a, ra1) = mergeFloating0(a0, units)
        val ra = r0.mapValues { (_, v) -> ra1[v] ?: v }
        val (b, rb) = mergeAlike(a, units)
        return b to ra.mapValues { (_, v) -> rb[v] ?: v }
    }

    /**
     * The frame is finished before it is filled: steps that only lay slats wait until the rails that tie the frame
     * together (a bed's end rails) are on, as long as nothing the slats rest on comes with those rails.
     */
    private fun railsBeforeSlats(steps: List<BuildStep>, units: List<Part>, members: Map<String, List<Part>>): Pair<List<BuildStep>, Map<Int, Int>> {
        val byId = units.associateBy { it.id }
        fun names(id: String) = members[id]?.map { it.name } ?: listOfNotNull(byId[id]?.name)
        val slatOnly = steps.map { st -> !st.subassembly && st.partIds.isNotEmpty() && st.partIds.all { names(it).all { n -> n == "Slat" } } }
        val railOnly = steps.map { st -> !st.subassembly && st.partIds.isNotEmpty() && st.partIds.all { names(it).all { n -> n.endsWith("ail") || n == "Apron" } } }
        val firstSlat = slatOnly.indexOfFirst { it }
        if (firstSlat < 0) return steps to steps.associate { it.step to it.step }
        val rails = steps.indices.filter { it > firstSlat && railOnly[it] }
        if (rails.isEmpty()) return steps to steps.associate { it.step to it.step }
        val list = steps.toMutableList()
        val moving = rails.map { steps[it] }
        list.removeAll(moving.toSet()); list.addAll(firstSlat, moving)
        val renumber = HashMap<Int, Int>()
        return list.mapIndexed { n, st -> renumber[st.step] = n + 1; st.copy(step = n + 1) } to renumber
    }

    /**
     * Nothing is set on a support that is not there yet: a step whose parts rest on parts fitted later (slats on
     * cleats the planner slid in from the side afterwards) has the step with those supports moved before it.
     */
    private fun supportsFirst(steps: List<BuildStep>, units: List<Part>, members: Map<String, List<Part>> = emptyMap()): Pair<List<BuildStep>, Map<Int, Int>> {
        val byId = units.associateBy { it.id }
        // A sub-assembly is judged by its own boards, not its overall box (a frame's cleat, not its rail's top).
        fun boxes(id: String) = members[id]?.map { it.mesh.bounds } ?: listOfNotNull(byId[id]?.mesh?.bounds)
        fun restsOn(top: String, under: String): Boolean = boxes(top).any { a -> boxes(under).any { b ->
            kotlin.math.abs(b.max.z - a.min.z) < 1.0 && minOf(a.max.x, b.max.x) > maxOf(a.min.x, b.min.x) + 1.0 && minOf(a.max.y, b.max.y) > maxOf(a.min.y, b.min.y) + 1.0 } }
        val list = steps.toMutableList()
        var guard = 0
        loop@ while (guard++ < 20) {
            for (i in list.indices) {
                if (list[i].subassembly) continue
                val j = (i + 1 until list.size).firstOrNull { j -> !list[j].subassembly && list[j].partIds.any { u -> list[i].partIds.any { t -> restsOn(t, u) } } } ?: continue
                // Only when the supports themselves rest on nothing fitted in between.
                if (list[j].partIds.any { u -> (i until j).any { k -> list[k].partIds.any { t -> restsOn(u, t) } } }) continue
                val moved = list.removeAt(j); list.add(i, moved)
                continue@loop
            }
            break
        }
        if (list.map { it.step } == steps.map { it.step }) return list to steps.associate { it.step to it.step }
        val renumber = HashMap<Int, Int>()
        return list.mapIndexed { n, st -> renumber[st.step] = n + 1; st.copy(step = n + 1) } to renumber
    }

    /**
     * A thin back panel goes on after the shelves and dividers it covers, as a cabinetmaker does: the case is
     * squared up with the back, and the shelves are fixed while it is still open. It moves later only past
     * steps that do not come in from behind, so nothing it would block is fitted after it.
     */
    private fun backLast(steps: List<BuildStep>, units: List<Part>): Pair<List<BuildStep>, Map<Int, Int>> {
        val byId = units.associateBy { it.id }
        fun touch(a: String, b: String): Boolean { val x = byId[a]?.mesh?.bounds ?: return false; val y = byId[b]?.mesh?.bounds ?: return false; return (0..2).all { x.min[it] <= y.max[it] + NEAR_BACK && y.min[it] <= x.max[it] + NEAR_BACK } }
        val list = steps.toMutableList()
        val i = list.indexOfFirst { st -> !st.subassembly && st.partIds.isNotEmpty() && st.partIds.all { byId[it]?.name == "Back panel" } }
        if (i < 0 || i == list.size - 1) return list to steps.associate { it.step to it.step }
        val back = list[i]
        var j = i
        for (k in i + 1 until list.size) {
            val st = list[k]
            // Doors, drawers and shelves on pins are hung in a squared case, so the back stays before them.
            val hung = st.partIds.any { id -> id.startsWith("drawer") || byId[id]?.name in setOf("Door", "Drawer front", "Adjustable shelf") }
            if (st.subassembly || hung || st.directions.any { d -> back.directions.any { (it dot d) > 0.5 } }) break
            if (st.partIds.any { id -> back.partIds.any { touch(id, it) } }) j = k
        }
        if (j == i) return list to steps.associate { it.step to it.step }
        list.removeAt(i); list.add(j, back)
        val renumber = HashMap<Int, Int>()
        val out = list.mapIndexed { n, st -> renumber[st.step] = n + 1; st.copy(step = n + 1) }
        return out to renumber
    }

    /**
     * Steps that each fit one more of the same item the same way (four shelves, one after another) are one step:
     * "fit the four shelves" reads as the job it is, and the guide is not four pages of the same picture.
     */
    private fun mergeAlike(steps: List<BuildStep>, units: List<Part>): Pair<List<BuildStep>, Map<Int, Int>> {
        val itemOf = units.associate { it.id to it.item }
        val out = ArrayList<BuildStep>(); val renumber = HashMap<Int, Int>()
        for ((n, st) in steps.withIndex()) {
            val last = out.lastOrNull()
            val items = st.partIds.map { itemOf[it] }.distinct()
            val alike = n > 1 && last != null && !st.subassembly && !last.subassembly && items.size == 1 && items[0] != null &&
                last.partIds.all { itemOf[it] == items[0] } && last.directions.toSet() == st.directions.toSet() &&
                last.partIds.size + st.partIds.size <= MAX_GROUP
            if (alike) { out[out.size - 1] = last!!.copy(partIds = last.partIds + st.partIds); renumber[st.step] = out.size; continue }
            out += st.copy(step = out.size + 1); renumber[st.step] = out.size
        }
        return out to renumber
    }

    private fun mergeFloating0(steps: List<BuildStep>, units: List<Part>): Pair<List<BuildStep>, Map<Int, Int>> {
        val boundsOf = units.associate { it.id to it.mesh.bounds }
        val floor = units.minOfOrNull { it.mesh.bounds.min.z } ?: 0.0
        fun touch(a: String, b: String): Boolean { val x = boundsOf[a] ?: return false; val y = boundsOf[b] ?: return false; return (0..2).all { x.min[it] <= y.max[it] + 0.5 && y.min[it] <= x.max[it] + 0.5 } }
        val out = ArrayList<BuildStep>(); val renumber = HashMap<Int, Int>()
        val built = HashSet<String>(); var carry: BuildStep? = null
        for ((n, st) in steps.withIndex()) {
            val merged = carry?.let { c -> st.copy(partIds = c.partIds + st.partIds, directions = (c.directions + st.directions).distinct(), items = (c.items + st.items).distinct().sorted()) } ?: st
            val alone = carry == null && built.isNotEmpty() && n < steps.size - 1 &&
                st.partIds.all { id -> (boundsOf[id]?.min?.z ?: Double.MAX_VALUE) <= floor + 1.0 } &&
                st.partIds.none { id -> built.any { touch(id, it) } }
            built += st.partIds
            if (alone) { carry = st; continue }
            carry?.let { renumber[it.step] = out.size + 1 }
            carry = null
            out += merged.copy(step = out.size + 1); renumber[st.step] = out.size
        }
        return out to renumber
    }

    /**
     * Parts of one item fitted in one step the same way (four shelves) move as one group in its picture: they are
     * pulled out the same distance, side by side, instead of each clearing the last and drifting apart.
     */
    private fun shareGroups(parts: List<Part>): List<Part> {
        // Parts of a sub-assembly (a drawer box, a frame) keep moving with their unit.
        val unitOf = unitKeys(parts)
        val unitSize = unitOf.values.groupingBy { it }.eachCount()
        fun single(p: Part) = !p.excluded && p.item != null && (unitSize[unitOf[p.id] ?: p.id] ?: 1) == 1
        val key = { p: Part -> if (!single(p)) null else p.explode?.let { e -> val d = e.normalized(); "${p.step}/${p.item}/${Math.round(d.x * 4)},${Math.round(d.y * 4)},${Math.round(d.z * 4)}" } }
        val counts = parts.mapNotNull { key(it) }.groupingBy { it }.eachCount()
        return parts.map { p ->
            val k = key(p)
            if (k == null || (counts[k] ?: 0) < 2) p
            else {
                val same = parts.filter { key(it) == k }
                val far = same.maxOf { it.explode!!.length }
                p.copy(group = "like/$k", explode = p.explode!!.normalized() * far)
            }
        }
    }

    /** A shelf set this close in front of a back panel (mm) counts as one the back covers. */
    private const val NEAR_BACK = 25.0

    /** Boards from one drawn part are built on the bench only when there are at least this many of them. */
    private const val MIN_SUB_PARTS = 3

    /** Fewest steps the main build is squeezed into when sub-assemblies take their own steps. */
    private const val MIN_MAIN_STEPS = 8

    /** And at most this many, however many units there are; past it the guide is longer than the build. */
    private const val MAX_MAIN_STEPS = 14

    /** Most things one step of the main build fits from one direction. */
    private const val MAX_GROUP = 5

    /** [hungGiven], when not null, names the parts hung last (doors, drawers made whole, shelves on pins) instead of finding them. */
    private fun planCore(all: List<Part>, maxSteps: Int = 10, order: List<List<String>>? = null, hungGiven: Set<String>? = null, hungFirst: Set<String> = emptySet()): PlanResult {
        val parts = all.filter { !it.excluded }
        val n = parts.size
        if (n == 0) return PlanResult(all, emptyList())
        val lo = parts.map { it.mesh.bounds.min }; val hi = parts.map { it.mesh.bounds.max }
        val allLo = Vec3(lo.minOf { it.x }, lo.minOf { it.y }, lo.minOf { it.z }); val allHi = Vec3(hi.maxOf { it.x }, hi.maxOf { it.y }, hi.maxOf { it.z })
        val tol = maxOf(1e-3 * (allHi - allLo).length, 0.05)
        val cen = (allLo + allHi) * 0.5

        // Base: of the parts standing on the floor, the biggest. A bookcase starts from a side, not from the kick
        // board that happens to have the wider footprint; a table from a leg, a cabinet from its bottom.
        // A part lying flat on the floor under a good share of the whole footprint (a cabinet's bottom) wins outright.
        val modelFoot = (allHi.x - allLo.x) * (allHi.y - allLo.y)
        val onFloor = (0 until n).filter { lo[it].z <= allLo.z + tol }
        fun foot(i: Int) = (hi[i].x - lo[i].x) * (hi[i].y - lo[i].y)
        fun lyingFlat(i: Int) = (hi[i].z - lo[i].z) <= minOf(hi[i].x - lo[i].x, hi[i].y - lo[i].y)
        val flatBase = onFloor.filter { lyingFlat(it) && foot(it) >= 0.25 * modelFoot }.maxByOrNull { foot(it) }
        val base = flatBase ?: onFloor.maxByOrNull { foot(it) * (hi[it].z - lo[it].z) } ?: 0

        // Shadow of every part along each axis, shrunk a little so parts that only touch do not count as overlapping.
        // Worked out only for parts some test needs; with hundreds of parts most pairs never get that far.
        val shadowCache = Array(3) { arrayOfNulls<Geometry>(n) }
        fun shadow(k: Int, i: Int): Geometry = shadowCache[k][i] ?: run {
            val view = ViewDef("axis$k", Vec3.axis(k), Vec3.axis((k + 1) % 3))
            Silhouette.outline(parts[i].mesh, view).buffer(-tol).also { shadowCache[k][i] = it }
        }

        fun touches(a: Int, b: Int) = (0..2).all { lo[a][it] <= hi[b][it] + tol && lo[b][it] <= hi[a][it] + tol }
        val adj = (0 until n).map { i -> ArrayList<Int>() }
        run {
            // Sweep along X: only parts whose X ranges meet can touch.
            val order = byAxisX(lo, n)
            for ((p, i) in order.withIndex()) for (q in p + 1 until order.size) {
                val j = order[q]
                if (lo[j].x > hi[i].x + tol) break
                if (touches(i, j)) { adj[i] += j; adj[j] += i }
            }
        }

        // Which parts are in the way of part i sliding along d never changes, so each answer is worked out once.
        // Boxes are sorted along each axis so only parts that could possibly be in the way are tested.
        val byAxis = (0..2).map { a -> (0 until n).sortedBy { lo[it][a] }.toIntArray() }
        val blockCache = HashMap<Long, IntArray>()
        fun allBlockers(i: Int, d: Dir): IntArray = blockCache.getOrPut(i.toLong() * 8 + d.k * 2 + (if (d.sign > 0) 1 else 0)) {
            val out = ArrayList<Int>()
            // Candidates must overlap on the first of the other axes; scan only parts starting before i ends there.
            val a0 = if (d.k == 0) 1 else 0
            for (j in byAxis[a0]) {
                if (lo[j][a0] >= hi[i][a0] - tol) break
                if (j == i) continue
                // Must overlap on both other axes to be in the way at all.
                if ((0..2).any { a -> a != d.k && (lo[j][a] >= hi[i][a] - tol || hi[j][a] <= lo[i][a] + tol) }) continue
                // Parts entirely behind the direction of travel are not in the way.
                val behind = if (d.sign > 0) hi[j][d.k] <= lo[i][d.k] + tol else lo[j][d.k] >= hi[i][d.k] - tol
                if (behind) continue
                val a = shadow(d.k, i); val b = shadow(d.k, j)
                if (!a.isEmpty && !b.isEmpty && a.intersects(b) && a.intersection(b).area > tol * tol) out += j
            }
            out.toIntArray()
        }
        fun blockers(i: Int, d: Dir, among: Set<Int>): List<Int> = allBlockers(i, d).filter { it in among }

        fun dirs(i: Int): List<Dir> {
            val e = hi[i] - lo[i]
            val k0 = if (e.x <= e.y && e.x <= e.z) 0 else if (e.y <= e.z) 1 else 2
            val s0 = if ((lo[i][k0] + hi[i][k0]) / 2 >= cen[k0]) 1 else -1
            val out = ArrayList<Dir>()
            for (d in listOf(Dir(k0, s0), Dir(k0, -s0), Dir(2, 1), Dir(1, -1), Dir(0, -1), Dir(0, 1), Dir(1, 1))) {
                if (d !in out && !(d.k == 2 && d.sign == -1)) out += d
            }
            return out + Dir(2, -1)
        }

        fun reachable(set: Set<Int>): Set<Int> {
            val seen = HashSet<Int>(); seen += base
            val todo = ArrayDeque<Int>(); todo += base
            while (todo.isNotEmpty()) for (j in adj[todo.removeFirst()]) if (j in set && seen.add(j)) todo += j
            return seen
        }

        val remaining = (0 until n).filter { it != base }.toMutableSet()
        val layers = ArrayList<LinkedHashMap<Int, Dir>>()
        val dirOf = HashMap<Int, Dir>()
        // Doors, drawers and adjustable shelves go in once the box is finished: taken off first, so they go on last.
        val drawerSets = if (hungGiven != null) emptyList() else runCatching { splitziner.check.Drawers.find(parts).map { it.ids } }.getOrDefault(emptyList())
        val hungIds = hungGiven ?: runCatching { splitziner.check.Doors.find(parts).map { it.id } + drawerSets.flatten() + splitziner.check.ShelfPins.find(parts) }.getOrDefault(emptyList()).toSet()
        val hung = (0 until n).filter { parts[it].id in hungIds && it != base }.toMutableSet()
        // Each drawer comes out whole; a door or a shelf on its own.
        val idIndex = (0 until n).associateBy { parts[it].id }
        val hungGroups = (drawerSets.map { ids -> ids.mapNotNull { idIndex[it] }.toSet() } + hung.filter { i -> drawerSets.none { parts[i].id in it } }.map { setOf(it) })
            .filter { it.isNotEmpty() && base !in it }.toMutableList()
        val outDir = Dir(1, -1)
        while (remaining.isNotEmpty()) {
            val present = remaining + base
            var free = LinkedHashMap<Int, Dir>()
            for (i in remaining.sorted()) for (d in dirs(i)) if (blockers(i, d, present).isEmpty()) { free[i] = d; break }
            // Doors, drawers and shelves on pins go in from the front, the way they really do: each group that can
            // leave straight out of the front (its own parts aside) is taken first, all its parts together.
            // A drawer made whole is one mesh here, and its front may overlap the carcass edges in a shadow test though
            // it leaves straight forward on its slides: such units are trusted to come out of the front.
            // Applied drawer fronts come off before their drawers (so go on after them).
            val firstOff = hungGroups.filter { g -> g.all { parts[it].id in hungFirst } }
            val outFront = (if (firstOff.isNotEmpty()) firstOff else hungGroups).filter { g -> g.all { i -> i in remaining && (hungGiven != null || blockers(i, outDir, present).all { it in g }) } }
            if (outFront.isNotEmpty()) {
                val take = LinkedHashMap<Int, Dir>()
                for (g in outFront) for (i in g) take[i] = outDir
                layers += take; remaining -= take.keys; dirOf += take; hung -= take.keys; hungGroups -= outFront.toSet()
                continue
            }
            val hungFree = free.filterKeys { it in hung }
            if (hungFree.isNotEmpty()) {
                val take = LinkedHashMap(hungFree)
                layers += take; remaining -= take.keys; dirOf += take; hung -= take.keys
                continue
            }
            if (free.isEmpty()) {
                // Interlocked: let go of whichever part is held by the fewest others.
                var best: Triple<Int, Int, Dir>? = null
                for (i in remaining.sorted()) for (d in dirs(i)) {
                    val c = blockers(i, d, present).size
                    if (best == null || c < best.first) best = Triple(c, i, d)
                }
                free = linkedMapOf(best!!.second to best.third)
            }
            // Distance from the base through touching parts: outermost parts are taken first.
            val dist = HashMap<Int, Int>(); dist[base] = 0
            val todo = ArrayDeque<Int>(); todo += base
            while (todo.isNotEmpty()) { val u = todo.removeFirst(); for (j in adj[u]) if (j in present && j !in dist) { dist[j] = dist.getValue(u) + 1; todo += j } }
            val take = LinkedHashMap<Int, Dir>()
            for (i in free.keys.sortedByDescending { dist[it] ?: Int.MAX_VALUE }) {
                // Only take a part if what is left still hangs together from the base, so that in
                // build order every part lands on something already there.
                val rest = present - take.keys - i
                if (dist[i] == null || reachable(rest).size == rest.size) take[i] = free.getValue(i)
            }
            if (take.isEmpty()) { val i = free.keys.maxByOrNull { dist[it] ?: Int.MAX_VALUE }!!; take[i] = free.getValue(i) }
            layers += take; remaining -= take.keys; dirOf += take
        }

        // Identical doors or adjustable shelves going in together are fitted in one step.
        fun sameHung(a: Int, b: Int): Boolean {
            if (parts[a].id !in hungIds || parts[b].id !in hungIds) return false
            val ea = (hi[a] - lo[a]).let { listOf(it.x, it.y, it.z).sorted() }; val eb = (hi[b] - lo[b]).let { listOf(it.x, it.y, it.z).sorted() }
            return (0..2).all { kotlin.math.abs(ea[it] - eb[it]) < 0.5 }
        }
        // Sub-assemblies: in one layer, parts leaving the same way that touch each other.
        val raw = ArrayList<MutableList<Int>>()
        for (layer in layers.reversed()) {
            val idx = layer.keys.toList()
            val ds = DisjointSet(idx.size)
            for (a in idx.indices) for (b in a + 1 until idx.size) if (layer[idx[a]] == layer[idx[b]] && (touches(idx[a], idx[b]) || sameHung(idx[a], idx[b]))) ds.union(a, b)
            val groups = idx.indices.groupBy { ds.find(it) }.values.map { g -> g.map { idx[it] }.toMutableList() }
            // A layer's parts can each leave on their own, so a big group may be fitted in several steps: lowest first,
            // a few at a time, so one step never places a whole pedestal with its drawers.
            val split = groups.flatMap { g -> if (g.size <= MAX_GROUP) listOf(g) else g.sortedBy { lo[it].z }.chunked(MAX_GROUP).map { it.toMutableList() } }
            raw += split.sortedWith(compareBy<MutableList<Int>>({ !(dirOf.getValue(it[0]).k == 2 && dirOf.getValue(it[0]).sign == 1) }, { g -> g.minOf { lo[it].z } }))
        }

        var userBase: List<Int>? = null
        if (order != null) {
            val index = parts.indices.associateBy { parts[it].id }
            val seen = HashSet<Int>()
            val groups = order.map { g -> g.mapNotNull { index[it] }.filter { seen.add(it) } }.filter { it.isNotEmpty() }.map { it.toMutableList() }.toMutableList()
            val missing = parts.indices.filter { it !in seen }
            if (missing.isNotEmpty()) groups += missing.toMutableList()
            if (groups.isNotEmpty()) {
                userBase = groups.removeAt(0)
                raw.clear(); raw += groups
                val placed = HashSet<Int>(userBase)
                for (g in raw) {
                    for (i in g) {
                        val d0 = dirOf[i]
                        dirOf[i] = (if (d0 != null && blockers(i, d0, placed).isEmpty()) d0 else dirs(i).firstOrNull { blockers(i, it, placed).isEmpty() }) ?: d0 ?: dirs(i).first()
                    }
                    placed += g
                }
            }
        }

        // Re-order for building. Peeling proves the parts can come apart, but read backwards it can
        // schedule a part before the neighbours that hold it up (a top panel before the sides).
        // So the order is rebuilt forwards: always take the earliest group that can slide in past
        // what is already built, preferring parts that stand on the floor, then parts that touch
        // something already there.
        if (userBase == null) run {
            val placed = HashSet<Int>(); placed += base
            val pool = ArrayList(raw); raw.clear()
            fun freeDir(i: Int): Dir? {
                val d0 = dirOf.getValue(i)
                return if (blockers(i, d0, placed).isEmpty()) d0 else dirs(i).firstOrNull { blockers(i, it, placed).isEmpty() }
            }
            fun feasible(g: List<Int>) = g.all { freeDir(it) != null }
            // Parts that stand on the floor go first; then parts that touch what is built; then anything that fits.
            fun grounded(g: List<Int>) = g.any { i -> lo[i].z <= allLo.z + tol } && g.any { i -> placed.any { touches(i, it) } }
            fun supported(g: List<Int>) = g.any { i -> placed.any { touches(i, it) } }
            fun onFloor(g: List<Int>) = g.any { i -> lo[i].z <= allLo.z + tol }
            // Everything a part rests on (touching it from below) must already be in place, or come with it.
            fun below(i: Int) = adj[i].filter { j -> hi[j].z <= lo[i].z + tol }
            fun restsOnBuilt(g: List<Int>) = g.all { i -> lo[i].z <= allLo.z + tol || below(i).all { it in placed || it in g } }
            // Hung parts wait for the box to be finished, and applied drawer fronts for their drawers.
            fun rank(g: List<Int>) = when { g.any { parts[it].id in hungFirst } -> 2; g.any { parts[it].id in hungIds } -> 1; else -> 0 }
            while (pool.isNotEmpty()) {
                val low = pool.minOf { rank(it) }
                val now = pool.filter { rank(it) == low }
                // Standing parts first (a second leg frame before the top that spans both), then parts whose supports are all built.
                val pick = now.firstOrNull { feasible(it) && grounded(it) } ?: now.firstOrNull { feasible(it) && onFloor(it) }
                    ?: now.firstOrNull { feasible(it) && supported(it) && restsOnBuilt(it) } ?: now.firstOrNull { feasible(it) && supported(it) }
                    ?: now.firstOrNull { feasible(it) } ?: now.first()
                for (i in pick) freeDir(i)?.let { dirOf[i] = it }
                raw += pick; pool.remove(pick); placed += pick
            }
        }

        // Merge neighbouring groups until the guide has a sensible number of steps.
        val target = maxOf(2, ceil(n.toDouble() / maxOf(maxSteps - 1, 1)).toInt())
        while (userBase == null && raw.size > maxOf(1, maxSteps - 1)) {
            fun cost(k: Int): Double {
                val a = raw[k]; val b = raw[k + 1]
                val touch = a.any { x -> b.any { y -> touches(x, y) } }
                val sameDirs = a.map { dirOf.getValue(it) }.toSet() == b.map { dirOf.getValue(it) }.toSet()
                val shareItem = a.map { parts[it].item ?: -it - 1 }.toSet().intersect(b.map { parts[it].item ?: -it - 1 }.toSet()).isNotEmpty()
                return (if (touch) 0.0 else 2.0) + (if (sameDirs) 0.0 else 1.0) + (if (shareItem) 0.0 else 0.5) +
                    0.6 * maxOf(0, a.size + b.size - target) + 0.02 * (a.size + b.size) + (if (a.size + b.size > MAX_GROUP) 4.0 else 0.0)
            }
            val k = (0 until raw.size - 1).minByOrNull { cost(it) }!!
            raw[k].addAll(raw[k + 1]); raw.removeAt(k + 1)
        }

        val explode = HashMap<String, Vec3?>(); val group = HashMap<String, String>(); val stepOf = HashMap<String, Int>()
        val steps = ArrayList<BuildStep>()
        val first = userBase ?: listOf(base)
        for (i in first) { explode[parts[i].id] = null; group[parts[i].id] = "S1"; stepOf[parts[i].id] = 1 }
        steps += BuildStep(1, first.map { parts[it].id }, emptyList(), first.mapNotNull { parts[it].item }.distinct().sorted())
        for (members in raw) {
            val st = steps.size + 1
            for (i in members) {
                val d = dirOf.getValue(i); val v = d.vec()
                val extent = hi[i][d.k] - lo[i][d.k]
                explode[parts[i].id] = v * maxOf(0.05 * extent, tol)
                group[parts[i].id] = "S$st${"XYZ"[d.k]}${if (d.sign > 0) "+1" else "-1"}"
                stepOf[parts[i].id] = st
            }
            steps += BuildStep(st, members.map { parts[it].id }, members.map { dirOf.getValue(it).vec() }.distinct(), members.mapNotNull { parts[it].item }.distinct().sorted())
        }
        val out = all.map { p -> if (p.excluded) p else p.copy(explode = explode[p.id], group = group[p.id], step = stepOf[p.id]) }
        return PlanResult(out, steps)
    }
}
