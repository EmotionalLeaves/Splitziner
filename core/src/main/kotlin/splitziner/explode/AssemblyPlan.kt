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
    /** A big sub-assembly (a cabinet) is built in several bench steps: this is the first of them, on later ones. */
    val continues: Int? = null,
    /** The first step of the main build when it also fits parts: the ids just put down, before the rest are fitted. */
    val placed: List<String> = emptyList(),
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
        (legFrames(live).ifEmpty { trestleEnds(live) }).forEachIndexed { k, ids -> for (id in ids) out[id] = "frame${k + 1}" }
        // A frame-and-panel door is glued up flat on the bench, then hung whole.
        // Any other frame round a panel (a panelled side) is made on the bench too, then fitted like a board.
        val doorGroups = runCatching { splitziner.check.FramePanels.doors(live) }.getOrDefault(emptyList())
        doorGroups.forEachIndexed { k, g -> for (id in g.ids) out[id] = "door${k + 1}" }
        (runCatching { splitziner.check.FramePanels.find(live) }.getOrDefault(emptyList()).filter { g -> doorGroups.none { it.panel == g.panel } })
            .forEachIndexed { k, g -> for (id in g.ids) out[id] = "fpanel${k + 1}" }
        // A drawer's runners and kickers are screwed to the inside of the side aprons: on the bench, with their frame.
        for (r in live.filter { (it.name == "Runner" || it.name == "Kicker") && '.' !in it.id }) {
            val rb = r.mesh.bounds
            val apron = live.firstOrNull { o -> o !== r && out[o.id]?.startsWith("frame") == true && (kotlin.math.abs(o.mesh.bounds.max.x - rb.min.x) < 1.0 || kotlin.math.abs(o.mesh.bounds.min.x - rb.max.x) < 1.0) &&
                o.mesh.bounds.min.z <= rb.max.z && o.mesh.bounds.max.z >= rb.min.z && o.mesh.bounds.min.y < rb.max.y && o.mesh.bounds.max.y > rb.min.y } ?: continue
            out[r.id] = out.getValue(apron.id)
        }
        // Spindles go with the two rails they stand between: into a leg frame when the rails are in one, else the
        // rails and spindles are a slatted panel of their own, made flat on the bench.
        run {
            val sp = live.filter { it.name == "Spindle" && '.' !in it.id }
            fun over(a: Part, o: Part) = o.mesh.bounds.min.x < a.mesh.bounds.max.x - 0.5 && o.mesh.bounds.max.x > a.mesh.bounds.min.x + 0.5 &&
                o.mesh.bounds.min.y < a.mesh.bounds.max.y - 0.5 && o.mesh.bounds.max.y > a.mesh.bounds.min.y + 0.5
            val byRails = sp.groupBy { p -> val b = p.mesh.bounds
                live.firstOrNull { o -> o.name != "Spindle" && kotlin.math.abs(o.mesh.bounds.max.z - b.min.z) < 1.0 && over(p, o) } to
                    live.firstOrNull { o -> o.name != "Spindle" && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 && over(p, o) } }
            var k = 0
            for ((rails, ps) in byRails) {
                val (lo, hi) = rails; if (lo == null || hi == null) continue
                val kl = out[lo.id]; val kh = out[hi.id]
                if (kl != null && kl == kh && kl.startsWith("frame")) { for (q in ps) out[q.id] = kl; continue }
                if (kl != lo.id.substringBefore('.') || kh != hi.id.substringBefore('.')) continue
                k++; for (q in ps + lo + hi) out[q.id] = "slatted$k"
            }
        }
        // Identical cabinets in a row (a kitchen run): each is built whole, then they are set side by side and joined.
        val mods1 = modules(live, out.filterValues { it.startsWith("drawer") }.keys)
        mods1.forEachIndexed { k, ids -> for (id in ids) if (out[id] == id.substringBefore('.')) out[id] = "module${k + 1}" }
        // A second run at right angles (an L-shaped kitchen) repeats its own way: found among the parts left over.
        if (mods1.isNotEmpty()) {
            val taken = mods1.flatten().toSet()
            val rest = live.filter { it.id !in taken }
            modules(rest, out.filterValues { it.startsWith("drawer") }.keys).forEachIndexed { k, ids -> for (id in ids) if (out[id] == id.substringBefore('.')) out[id] = "module${mods1.size + k + 1}" }
        }
        // What other units (leg frames) took leaves too little to be a cabinet: those parts are their own again.
        run { val sizes = out.values.groupingBy { it }.eachCount()
            for ((id, k) in out.toMap()) if (k.startsWith("module") && (sizes[k] ?: 0) < MIN_SUB_PARTS) out[id] = id.substringBefore('.') }
        // A tenon goes with the rail whose end it is on: glued in (or cut on it) before the rail is fitted.
        for (t in live.filter { it.name == "Tenon" }) {
            val tb = t.mesh.bounds
            val rail = live.firstOrNull { o -> o !== t && o.name != "Tenon" && o.mesh.bounds.let { ob -> val long = (0..2).maxByOrNull { ob.extents[it] }!!
                (kotlin.math.abs(ob.min[long] - tb.max[long]) < 1.0 || kotlin.math.abs(ob.max[long] - tb.min[long]) < 1.0) &&
                (0..2).filter { it != long }.all { k -> tb.min[k] >= ob.min[k] - 0.5 && tb.max[k] <= ob.max[k] + 0.5 } } } ?: continue
            out[t.id] = out[rail.id] ?: rail.id
        }
        // A case standing on another's top (a desk's hutch, a dresser's mirror stand) is built whole, then set on.
        upperUnit(live)?.let { ids -> for (id in ids) if (out[id] == id.substringBefore('.')) out[id] = "upper" }
        // A plinth is screwed together as a frame on the bench, then the case is built on it (or set on it).
        val plinth = live.filter { it.name == "Plinth" }
        if (plinth.size >= 3) for (p in plinth) out[p.id] = "plinth"
        // A ladder is glued and screwed up flat on the bench (sides and rungs), then fixed to the bed whole.
        val ladder = live.filter { it.name == "Ladder side" || it.name == "Rung" }
        if (ladder.count { it.name == "Ladder side" } >= 2 && ladder.count { it.name == "Rung" } >= 3) for (p in ladder) out[p.id] = "ladder"
        // A face frame is glued up flat on the bench, then fixed to the front of the case whole.
        val ff = live.filter { it.name == "Stile" || it.name == "Face frame rail" }
        if (ff.size >= 3 && ff.any { it.name == "Stile" } && ff.any { it.name == "Face frame rail" }) for (p in ff) out[p.id] = "faceframe"
        return out
    }

    /**
     * A table or bench on legs is built as end frames: two legs and the short rails between them, glued up flat on
     * the bench, then stood up and joined by the long rails. Building leg by leg instead leaves a single leg
     * standing alone in step 1. The frames are the rails of one item running one way that pair every leg exactly
     * once; of those, the shortest, as a woodworker would make the ends first.
     */
    /**
     * Repeated identical modules: the same parts again and again, each set shifted by one step along one axis (four
     * base cabinets in a row under one worktop). Returns each module's part ids, or nothing when there is no clear
     * repeat of at least two modules of four or more parts. Hung parts (doors, drawers and their fronts) and
     * parts that run across the modules (the worktop) stay out of them.
     */
    fun modules(live: List<Part>, drawerIds: Set<String> = emptySet()): List<List<String>> {
        val hung = runCatching { (splitziner.check.Doors.find(live).map { it.id } + splitziner.check.Doors.sliding(live)) }.getOrDefault(emptyList()).toSet() + drawerIds +
            live.filter { it.name == "Drawer front" || it.name == "Door" }.map { it.id }
        val ps = live.filter { it.id !in hung && '.' !in it.id }
        if (ps.size < 8) return emptyList()
        fun key(p: Part) = p.mesh.bounds.extents.let { e -> Triple(Math.round(e.x), Math.round(e.y), Math.round(e.z)) }
        fun corner(p: Part) = p.mesh.bounds.min
        // The most common shift between like parts, along one axis.
        val counts = HashMap<Triple<Long, Long, Long>, Int>()
        for ((_, g) in ps.groupBy { key(it) }) for (a in g) for (b in g) {
            if (a === b) continue
            val d = corner(b) - corner(a)
            val axes = listOf(d.x, d.y, d.z).count { kotlin.math.abs(it) > 1.0 }
            if (axes != 1 || listOf(d.x, d.y, d.z).any { it < -1.0 }) continue
            val k = Triple(Math.round(d.x), Math.round(d.y), Math.round(d.z)); counts[k] = (counts[k] ?: 0) + 1
        }
        val best = counts.entries.maxWithOrNull(compareBy<Map.Entry<Triple<Long, Long, Long>, Int>> { it.value }.thenBy { -(kotlin.math.abs(it.key.first) + kotlin.math.abs(it.key.second) + kotlin.math.abs(it.key.third)) }) ?: return emptyList()
        if (best.value < 4) return emptyList()
        val d = Vec3(best.key.first.toDouble(), best.key.second.toDouble(), best.key.third.toDouble())
        fun at(p: Part, k: Int): Part? { val c = corner(p) + d * k.toDouble(); return ps.firstOrNull { o -> key(o) == key(p) && (o.mesh.bounds.min - c).length < 1.5 } }
        // The first module: parts with no copy one step back, but a copy one step on.
        // The first module is the stretch one step long at the start of the row.
        val ax = if (kotlin.math.abs(d.x) > 1) 0 else if (kotlin.math.abs(d.y) > 1) 1 else 2
        // Modules stand side by side on the floor: a pattern repeating upwards (shelves and dividers) is not one.
        if (ax == 2) return emptyList()
        // Where the row starts: its first repeating part, not whatever else stands further along that way (the corner
        // cabinet of an L-shaped kitchen, or the other run).
        val start = ps.filter { at(it, 1) != null }.minOfOrNull { corner(it)[ax] } ?: return emptyList()
        fun chain(p: Part): Int { var k = 1; while (at(p, k) != null) k++; return k }
        val cand = ps.filter { corner(it)[ax] < start + d[ax] - 1.0 && at(it, 1) != null }
        if (cand.size < 4) return emptyList()
        // As many modules as most of its parts repeat (a part another feature took in one module is left out).
        val n = cand.map { chain(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        val first = cand.filter { chain(it) >= n }
        if (n < 2 || first.size < 4) return emptyList()
        val mods = (0 until n).map { k -> first.map { at(it, k)!!.id } }
        if (mods.flatten().toSet().size != n * first.size) return emptyList()
        // Each module must hang together on its own.
        fun touch(a: Part, b: Part): Boolean { val x = a.mesh.bounds; val y = b.mesh.bounds; return (0..2).all { x.min[it] <= y.max[it] + 0.5 && y.min[it] <= x.max[it] + 0.5 } }
        val m0 = first; val seen = HashSet<Part>(); val todo = ArrayDeque(listOf(m0[0]))
        while (todo.isNotEmpty()) { val q = todo.removeFirst(); if (!seen.add(q)) continue; m0.filter { it !in seen && touch(it, q) }.forEach { todo += it } }
        if (seen.size != m0.size) return emptyList()
        val floor = live.minOf { it.mesh.bounds.min.z }
        if (m0.none { it.mesh.bounds.min.z <= floor + 1.0 }) return emptyList()
        // Each a box of its own: neighbours meet only side to side, two panels back to back. Bays that share an
        // upright (a library's shelves running into the next bay's side) are one piece of furniture, not modules.
        val byId = ps.associateBy { it.id }
        fun thinAxis(p: Part) = p.mesh.bounds.extents.let { e -> if (e.x <= e.y && e.x <= e.z) 0 else if (e.y <= e.z) 1 else 2 }
        for (k in 0 until n - 1) for (a in mods[k]) for (b in mods[k + 1]) {
            val pa = byId.getValue(a); val pb = byId.getValue(b)
            if (touch(pa, pb) && !(thinAxis(pa) == ax && thinAxis(pb) == ax)) return emptyList()
        }
        return mods
    }

    /**
     * A trestle's ends: each post with the foot it stands on and the top rail it carries, made flat on the bench as
     * one frame, then the ends are stood up and joined by the stretcher. Only when every post has both.
     */
    fun trestleEnds(live: List<Part>): List<List<String>> {
        val posts = live.filter { it.name == "Post" && '.' !in it.id }
        if (posts.size < 2) return emptyList()
        fun touch(a: Part, b: Part): Boolean { val x = a.mesh.bounds; val y = b.mesh.bounds; return (0..2).all { x.min[it] <= y.max[it] + 0.5 && y.min[it] <= x.max[it] + 0.5 } }
        val ends = posts.map { p ->
            val foot = live.filter { it.name == "Foot" && touch(it, p) }
            val top = live.filter { it.name == "Top rail" && touch(it, p) }
            if (foot.size != 1 || top.size != 1) return emptyList()
            listOf(foot[0].id, p.id, top[0].id)
        }
        return if (ends.flatten().toSet().size == ends.size * 3) ends else emptyList()
    }

    /**
     * The parts of a case that stands on the top of the piece below it (a hutch on a desk): all of them above that
     * top, touching each other, four or more, with at least one standing on it. Null when there is none.
     */
    fun upperUnit(live: List<Part>): List<String>? {
        if (live.size < 8) return null
        fun flat(p: Part) = p.mesh.bounds.extents.let { it.z <= minOf(it.x, it.y) }
        fun touch(a: Part, b: Part): Boolean { val x = a.mesh.bounds; val y = b.mesh.bounds; return (0..2).all { x.min[it] <= y.max[it] + 0.5 && y.min[it] <= x.max[it] + 0.5 } }
        val all = live.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }
        // The lower top: a big flat part spanning most of the width, with parts standing on it.
        for (top in live.filter { flat(it) && it.mesh.bounds.extents.x >= 0.8 * all.extents.x && it.mesh.bounds.max.z < all.max.z - 100.0 }.sortedByDescending { it.mesh.bounds.extents.x * it.mesh.bounds.extents.y }) {
            val tz = top.mesh.bounds.max.z
            val above = live.filter { it !== top && it.mesh.bounds.min.z >= tz - 0.5 }
            val below = live.filter { it !== top && it.mesh.bounds.max.z <= top.mesh.bounds.min.z + 0.5 }
            // Everything is either above or below the top: nothing passes through it.
            if (above.size < 4 || below.size < 2 || above.size + below.size + 1 != live.size) continue
            if (above.none { touch(it, top) }) continue
            // One connected group.
            val seen = HashSet<String>(); val q = ArrayDeque(listOf(above[0]))
            while (q.isNotEmpty()) { val p = q.removeFirst(); if (!seen.add(p.id)) continue; above.filter { it.id !in seen && touch(it, p) }.forEach { q += it } }
            if (seen.size == above.size) return above.map { it.id }
        }
        return null
    }

    fun legFrames(live: List<Part>): List<List<String>> {
        val legs = live.filter { it.name.endsWith("eg") && '.' !in it.id }
        if (legs.size < 4 || legs.size % 2 != 0) return emptyList()
        fun touch(a: Part, b: Part): Boolean { val x = a.mesh.bounds; val y = b.mesh.bounds; return (0..2).all { x.min[it] <= y.max[it] + 0.5 && y.min[it] <= x.max[it] + 0.5 } }
        fun longAxis(p: Part): Int { val e = p.mesh.bounds.extents; return if (e.x >= e.y && e.x >= e.z) 0 else if (e.y >= e.z) 1 else 2 }
        // Rails on edge only: a board lying flat across two legs (a slat) is not part of a frame.
        fun flat(p: Part) = p.mesh.bounds.extents.let { it.z <= minOf(it.x, it.y) }
        // A crosspiece named a cleat that joins two splayed legs (a picnic table's top cleat and seat support) is a rail here.
        val rails = live.filter { (it.name == "Apron" || it.name.endsWith("ail") || (it.name == "Cleat" && legs.count { l -> touch(it, l) } == 2)) && !flat(it) }.map { r -> r to legs.filter { touch(r, it) } }.filter { it.second.size == 2 && longAxis(it.first) != 2 }
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
            (pair.toList() + frameRails.map { it.id } + cleats.map { it.id }).distinct()
        }
    }

    /**
     * A first step that only puts one part down ("start with the side") is a page with nothing to do: the next parts go
     * on in it. Several units set out together (frames, cabinets) keep their own step, which says how far apart.
     */
    private fun loneBase(steps: List<BuildStep>) = steps.size >= 3 && steps[0].partIds.size == 1 && !steps[1].subassembly && steps[1].partIds.none { it.startsWith("drawer") }

    private fun foldBase(steps: List<BuildStep>): List<BuildStep> =
        listOf(steps[1].copy(step = 1, partIds = steps[0].partIds + steps[1].partIds, items = (steps[0].items + steps[1].items).distinct().sorted(), placed = steps[0].partIds)) +
            steps.drop(2).map { it.copy(step = it.step - 1) }

    fun plan(all: List<Part>, maxSteps: Int = 10, order: List<List<String>>? = null): PlanResult {
        val live = all.filter { !it.excluded }
        val keyOf = unitKeys(live)
        val units = live.groupBy { keyOf.getValue(it.id) }
        // Two boards (an L, a pair of strips) are simply fitted one after the other; a bench step for them is a page of nothing.
        val subs = units.filter { it.value.size >= MIN_SUB_PARTS }
        if (order != null) return planCore(all, maxSteps, order)
        if (subs.isEmpty()) {
            val r = planCore(all, maxSteps, order)
            val moved = HashMap<String, Int>()
            val (st0, ren) = mergeFloating(r.steps, live, moved = moved)
            val lone = loneBase(st0)
            if (!lone && st0.size == r.steps.size && ren.all { (k, v) -> k == v } && moved.isEmpty()) return r
            val st = if (lone) foldBase(st0) else st0
            return PlanResult(shareGroups(r.parts.map { p -> p.copy(step = (moved[p.id] ?: p.step?.let { ren[it] ?: it })?.let { if (lone && it >= 2) it - 1 else it }) }), st)
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
                    // A rail sitting on its legs (a bed's centre rail on its short legs) is lowered onto them, never slid
                    // in end-on along its own length.
                    val touching = u.filter { o -> o !== p && (0..2).all { k -> o.mesh.bounds.min[k] <= b.max[k] + 0.5 && b.min[k] <= o.mesh.bounds.max[k] + 0.5 } }
                    val sitsOnThem = touching.isNotEmpty() && touching.all { o -> o.mesh.bounds.max.z <= b.min.z + 0.5 }
                    benchExplode[p.id] = if (e != null && (p.name == "Apron" || p.name.endsWith("ail")) && kotlin.math.abs(e.z) < 0.1 * e.length && b.extents.z < maxOf(b.extents.x, b.extents.y) &&
                        (sitsOnThem || kotlin.math.abs(if (b.extents.x >= b.extents.y) e.x else e.y) < 0.5 * e.length) && b.max.z >= unitTop - 0.5 && !overlapsAbove) Vec3(0.0, 0.0, e.length) else e
                }
                // Spindles stand up on their bottom rail and the top rail comes down on them: drawn lifted straight up,
                // the top rail above them, so the panel reads as it goes together.
                val sp = u.filter { it.name == "Spindle" }
                if (sp.size >= 3) {
                    val lift = 0.6 * sp.maxOf { it.mesh.bounds.extents.z }
                    val spTop = sp.maxOf { it.mesh.bounds.max.z }
                    for (q in sp) benchExplode[q.id] = Vec3(0.0, 0.0, lift)
                    for (q in u) if (q.name != "Spindle" && q.mesh.bounds.min.z >= spTop - 0.5 && q.mesh.bounds.extents.z < maxOf(q.mesh.bounds.extents.x, q.mesh.bounds.extents.y)) benchExplode[q.id] = Vec3(0.0, 0.0, 2 * lift)
                }
                ids += u.map { it.id }
            }
            val first = kind.first()
            // A ladder lies flat in one plane, its rungs all alike: one picture shows it whatever its length.
            // So does any unit lying in one plane (a crib's slatted end, a face frame): its parts are all in view.
            val flatUnit = (0..2).any { k -> first.maxOf { it.mesh.bounds.max[k] } - first.minOf { it.mesh.bounds.min[k] } <= 1.05 * first.maxOf { p -> p.mesh.bounds.extents.let { e -> minOf(e.x, e.y, e.z) } } + 0.5 }
            if (first.size > BIG_SUB && !flatUnit && !first.all { it.name == "Ladder side" || it.name == "Rung" }) {
                // A whole cabinet is too much for one picture: it is built in a few bench steps, as on its own.
                // Its back panel goes on last, once the case is together, as in the main build.
                val backs = first.filter { it.name == "Back panel" }.takeIf { first.size - it.size >= 3 }.orEmpty()
                val inner = planCore(first - backs.toSet(), maxSteps = minOf(5, maxOf(2, ceil(first.size / 3.0).toInt())))
                val ex = inner.parts.associate { it.id to it.explode } + backs.associate { it.id to Vec3(0.0, 1.0, 0.0) * maxOf(it.mesh.bounds.extents.z, 50.0) * 0.5 }
                val start = bench.size + 1
                // The piece laid down first goes in with the first parts joined to it, not on a page of its own.
                val isteps = (if (inner.steps.size >= 2 && inner.steps[0].partIds.size == 1)
                    listOf(inner.steps[1].copy(partIds = inner.steps[0].partIds + inner.steps[1].partIds)) + inner.steps.drop(2) else inner.steps) +
                    (if (backs.isEmpty()) emptyList() else listOf(BuildStep(0, backs.map { it.id }, listOf(Vec3(0.0, 1.0, 0.0)), backs.mapNotNull { it.item }.distinct().sorted())))
                for ((k, ist) in isteps.withIndex()) {
                    val ids = ist.partIds.filter { id -> first.any { it.id == id } }
                    if (ids.isEmpty()) continue
                    bench += BuildStep(bench.size + 1, ids, emptyList(), ids.mapNotNull { id -> first.first { it.id == id }.item }.distinct().sorted(),
                        subassembly = true, explodeOf = ids.associateWith { if (k == 0 && ex[it] == null) null else ex[it] }, makeCount = kind.size, continues = if (bench.size + 1 == start) null else start)
                }
                continue
            }
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
        val hungMain = runCatching { splitziner.check.Doors.find(live).map { it.id } + splitziner.check.Doors.sliding(live) + splitziner.check.ShelfPins.find(live) }.getOrDefault(emptyList()).toSet() +
            unitParts.map { it.id }.filter { it.startsWith("drawer") || it.startsWith("door") || it == "ladder" } + appliedFronts
        val main = planCore(unitParts, maxOf(MIN_MAIN_STEPS, maxSteps - bench.size, minOf(MAX_MAIN_STEPS, ceil(unitParts.size / 3.0).toInt())), hungGiven = hungMain, hungFirst = appliedFronts,
            members = subs)
        val moved = HashMap<String, Int>()
        val (mainSteps0, renumber) = mergeFloating(main.steps, unitParts, subs, moved)
        val unitOf0 = main.parts.associate { it.id to it.copy(step = moved[it.id] ?: it.step?.let { s0 -> renumber[s0] ?: s0 }) }
        val lone = loneBase(mainSteps0)
        val mainSteps = if (!lone) mainSteps0 else foldBase(mainSteps0)
        val unitOf = if (!lone) unitOf0 else unitOf0.mapValues { (_, p) -> p.copy(step = p.step?.let { if (it >= 2) it - 1 else it }) }
        val steps = ArrayList<BuildStep>(bench)
        for (st in mainSteps) {
            val ids = st.partIds.flatMap { id -> subs[id]?.map { it.id } ?: listOf(id) }
            steps += st.copy(step = steps.size + 1, partIds = ids, items = ids.mapNotNull { id -> live.first { it.id == id }.item }.distinct().sorted())
        }
        val firstMain = bench.size + 1
        // Cabinets in a row are drawn pulled apart along the row, evenly out from the middle, so each reads whole;
        // whatever the peel order gave them would scatter them front and back.
        val rowPull = HashMap<String, Vec3>()
        val mods = unitParts.filter { it.id.startsWith("module") }
        if (mods.size >= 2) {
            val ax = (0..1).maxByOrNull { k -> mods.maxOf { it.mesh.bounds.center[k] } - mods.minOf { it.mesh.bounds.center[k] } }!!
            val sorted = mods.sortedBy { it.mesh.bounds.center[ax] }
            val gap = 0.3 * sorted.minOf { it.mesh.bounds.extents[ax] }
            sorted.forEachIndexed { n, m -> val off = (n - (sorted.size - 1) / 2.0) * gap; if (kotlin.math.abs(off) > 1e-6) rowPull[m.id] = Vec3.axis(ax, off) }
        }
        val out = all.map { p ->
            if (p.excluded) p else {
                val key = keyOf[p.id] ?: p.id.substringBefore('.')
                val u = unitOf[p.id] ?: unitOf[key]
                val ex = if (key.startsWith("module") && key in rowPull) rowPull[key] else u?.explode
                p.copy(explode = ex, group = u?.group, step = (u?.step ?: 1) + firstMain - 1)
            }
        }
        return PlanResult(shareGroups(out), steps)
    }

    /**
     * A step that stands something on the floor touching nothing already built (a desk's second side, a table's
     * second end frame) cannot be fastened yet: it goes in with the step after it, which joins it to the rest.
     * Returns the steps and, per old step number, the new one.
     */
    private fun mergeFloating(steps: List<BuildStep>, units: List<Part>, members: Map<String, List<Part>> = emptyMap(), moved: MutableMap<String, Int> = HashMap()): Pair<List<BuildStep>, Map<Int, Int>> {
        val (q0, rq) = railsBeforeSlats(steps, units, members)
        val (s00, rs0) = supportsFirst(q0, units, members)
        val rs = rq.mapValues { (_, v) -> rs0[v] ?: v }; val s0 = s00
        // Parts moved on their own (out of their step) follow each later renumbering.
        fun follow(pre: Map<String, Int>, ren: Map<Int, Int>) { for ((k, v) in pre) if (moved[k] == v) moved[k] = ren[v] ?: v }
        val (sp, rsp) = backApart(s0, units, moved)
        val rs1 = rs.mapValues { (_, v) -> rsp[v] ?: v }
        val pre0 = moved.toMap()
        val (a00, r00) = backLast(sp, units)
        follow(pre0, r00)
        val r0 = rs1.mapValues { (_, v) -> r00[v] ?: v }; val a0 = a00
        val pre1 = moved.toMap()
        val (a, ra1) = mergeFloating0(a0, units, moved)
        follow(pre1, ra1)
        val ra = r0.mapValues { (_, v) -> ra1[v] ?: v }
        val (b, rb) = mergeAlike(a, units)
        for (k in moved.keys.toList()) moved[k] = rb[moved[k]!!] ?: moved[k]!!
        return slatsTogether(b, units, moved) to ra.mapValues { (_, v) -> rb[v] ?: v }
    }

    /**
     * A step that fits a few slats with the cleats they rest on, followed by a step of the rest of those slats: the
     * few move to the next step, so the cleats go on first and the slats are laid all together, spaced with one block.
     */
    private fun slatsTogether(steps: List<BuildStep>, units: List<Part>, moved: MutableMap<String, Int>): List<BuildStep> {
        val byId = units.associateBy { it.id }
        val out = steps.toMutableList()
        for (n in 0 until out.size - 1) {
            val a = out[n]; val b = out[n + 1]
            if (a.subassembly || b.subassembly || b.partIds.isEmpty()) continue
            val item = byId[b.partIds[0]]?.item ?: continue
            if (!b.partIds.all { byId[it]?.name == "Slat" && byId[it]?.item == item }) continue
            val few = a.partIds.filter { byId[it]?.name == "Slat" && byId[it]?.item == item }
            if (few.isEmpty() || few.size == a.partIds.size) continue
            out[n] = a.copy(partIds = a.partIds - few.toSet())
            out[n + 1] = b.copy(partIds = few + b.partIds)
            for (id in few) moved[id] = b.step
        }
        return out
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
        // Only the rails round those slats: a bunk bed's upper rails wait for the lower deck to be finished.
        fun zr(st: BuildStep): Pair<Double, Double> { val bs = st.partIds.flatMap { id -> members[id]?.map { it.mesh.bounds } ?: listOfNotNull(byId[id]?.mesh?.bounds) }
            return (bs.minOfOrNull { it.min.z } ?: 0.0) to (bs.maxOfOrNull { it.max.z } ?: 0.0) }
        val slatZ = zr(steps[firstSlat])
        val rails = steps.indices.filter { it > firstSlat && railOnly[it] && zr(steps[it]).let { (a, b) -> a <= slatZ.second + 1.0 && b >= slatZ.first - 1.0 } }
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
    /**
     * A back panel that the plan fits along with other parts (a side and a shelf) is taken out into a step of its
     * own just after, so it can go on last, once the case is together: it squares the case and closes it.
     */
    private fun backApart(steps: List<BuildStep>, units: List<Part>, moved: MutableMap<String, Int>): Pair<List<BuildStep>, Map<Int, Int>> {
        val byId = units.associateBy { it.id }
        fun isBack(id: String) = byId[id]?.name == "Back panel"
        val holding = steps.indices.filter { n -> !steps[n].subassembly && steps[n].partIds.any { isBack(it) } }
        // Nothing to gather: no back, or one back already in a step of its own.
        if (holding.isEmpty() || (holding.size == 1 && steps[holding[0]].partIds.all { isBack(it) })) return steps to steps.associate { it.step to it.step }
        val backs = holding.flatMap { n -> steps[n].partIds.filter { isBack(it) } }
        val lastAt = holding.last()
        val out = ArrayList<BuildStep>(); val ren = HashMap<Int, Int>()
        // Backs go on together from behind (all of a bookcase's bays), once the case is together.
        val back = Vec3(0.0, 1.0, 0.0)
        for ((n, st) in steps.withIndex()) {
            val rest = if (st.subassembly) st.partIds else st.partIds.filter { !isBack(it) }
            if (rest.isNotEmpty()) {
                out += if (rest.size == st.partIds.size) st.copy(step = out.size + 1)
                    else st.copy(step = out.size + 1, partIds = rest, items = rest.mapNotNull { byId[it]?.item }.distinct().sorted(), directions = st.directions.filter { (it dot back) < 0.5 }.ifEmpty { st.directions })
                ren[st.step] = out.size
            }
            if (n == lastAt) {
                out += st.copy(step = out.size + 1, partIds = backs, items = backs.mapNotNull { byId[it]?.item }.distinct().sorted(), directions = listOf(back), subassembly = false, explodeOf = emptyMap(), continues = null)
                if (rest.isEmpty()) ren[st.step] = out.size
                for (id in backs) moved[id] = out.size
            }
        }
        for (n in holding) if (steps[n].step !in ren) ren[steps[n].step] = moved[backs[0]]!!
        return out to ren
    }

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
            val hung = st.partIds.any { id -> id.startsWith("drawer") || id.startsWith("door") || byId[id]?.name in setOf("Door", "Drawer front", "Adjustable shelf") }
            // A later part that goes in the same way as the back, through where the back would be, must go first.
            fun crosses(id: String, d: Vec3): Boolean {
                val x = byId[id]?.mesh?.bounds ?: return true
                val ax = (0..2).maxByOrNull { kotlin.math.abs(d[it]) }!!
                return back.partIds.any { b -> val y = byId[b]?.mesh?.bounds ?: return@any true; (0..2).filter { it != ax }.all { x.min[it] < y.max[it] - 0.5 && y.min[it] < x.max[it] - 0.5 } }
            }
            val sameWay = st.directions.any { d -> back.directions.any { (it dot d) > 0.5 } }
            if (st.subassembly || hung || (sameWay && st.partIds.any { id -> back.directions.any { crosses(id, it) } })) break
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
        // Parts named alike (drawer fronts of graduated drawers, items of their own) count as alike too.
        val nameOf = units.associate { it.id to it.name.takeIf { n -> n != it.id && n.isNotBlank() } }
        val out = ArrayList<BuildStep>(); val renumber = HashMap<Int, Int>()
        for ((n, st) in steps.withIndex()) {
            val last = out.lastOrNull()
            val items = st.partIds.map { itemOf[it] }.distinct()
            val names = (st.partIds + (last?.partIds ?: emptyList())).map { nameOf[it] }.distinct()
            val sameItem = items.size == 1 && items[0] != null && last != null && last.partIds.all { itemOf[it] == items[0] }
            val sameName = names.size == 1 && names[0] != null
            // A pair fitted from opposite sides (a table's front and back aprons) is one step: "from the front and back".
            val opposite = last != null && last.directions.size == 1 && st.directions.size == 1 &&
                last.directions[0].x == -st.directions[0].x && last.directions[0].y == -st.directions[0].y && last.directions[0].z == -st.directions[0].z
            val alike = n > 1 && last != null && !st.subassembly && !last.subassembly && (sameItem || sameName) &&
                (last.directions.toSet() == st.directions.toSet() || (opposite && sameItem)) &&
                // Drawers and their fronts go in all together, a chest's worth: hanging them is the same job each time.
                last.partIds.size + st.partIds.size <= (if ((last.partIds + st.partIds).all { it.startsWith("drawer") || nameOf[it] == "Drawer front" || nameOf[it] == "Door" }) MAX_DRAWERS
                    // A bed's or bench's slats go down in one go, spaced with one block: laying them is one job.
                    else if ((last.partIds + st.partIds).all { nameOf[it] == "Slat" }) MAX_SLATS else MAX_GROUP)
            if (alike) { out[out.size - 1] = last!!.copy(partIds = last.partIds + st.partIds, directions = (last.directions + st.directions).distinct()); renumber[st.step] = out.size; continue }
            out += st.copy(step = out.size + 1); renumber[st.step] = out.size
        }
        return out to renumber
    }

    private fun mergeFloating0(steps0: List<BuildStep>, units: List<Part>, moved: MutableMap<String, Int>): Pair<List<BuildStep>, Map<Int, Int>> {
        val boundsOf = units.associate { it.id to it.mesh.bounds }
        val floor = units.minOfOrNull { it.mesh.bounds.min.z } ?: 0.0
        // A build that starts from one leg frame sets out its twins standing on the floor with it, whenever the order
        // found would only bring them in later (after an apron fixed to the first frame alone).
        var steps = steps0
        if (steps.isNotEmpty() && !steps[0].subassembly && steps[0].partIds.all { it.startsWith("frame") }) {
            val twins = steps.drop(1).flatMap { it.partIds }.filter { id -> id.startsWith("frame") && (boundsOf[id]?.min?.z ?: Double.MAX_VALUE) <= floor + 1.0 }
            if (twins.isNotEmpty()) {
                for (id in twins) moved[id] = 1
                steps = listOf(steps[0].copy(partIds = steps[0].partIds + twins)) + steps.drop(1).map { st -> st.copy(partIds = st.partIds - twins.toSet()) }.filter { it.partIds.isNotEmpty() }
            }
        }
        fun touch(a: String, b: String): Boolean { val x = boundsOf[a] ?: return false; val y = boundsOf[b] ?: return false; return (0..2).all { x.min[it] <= y.max[it] + 0.5 && y.min[it] <= x.max[it] + 0.5 } }
        val out = ArrayList<BuildStep>(); val renumber = HashMap<Int, Int>()
        val built = HashSet<String>(); var carry: BuildStep? = null
        val pending = ArrayList<String>()
        for ((n, st0) in steps.withIndex()) {
            var st = st0
            fun onFloor(id: String) = (boundsOf[id]?.min?.z ?: Double.MAX_VALUE) <= floor + 1.0
            // Standing beside the base, before anything ties them together (a bed's other side frame and its centre
            // frame), they are set out with it: placed, not fitted from a direction they never travel. The rest of
            // their step (the slats that tie them) stays a step of its own.
            val base = out.lastOrNull()
            // Flat pieces laid out apart (a picture frame's long sides) and the flat pieces that join them (its short
            // sides) are one glue-up on the bench: laid out together and closed in one go.
            fun flat(id: String) = boundsOf[id]?.extents?.let { it.z <= minOf(it.x, it.y) } == true
            if (base != null && !base.subassembly && out.count { !it.subassembly } == 1 && base.partIds.all { flat(it) } && st.partIds.all { flat(it) } &&
                !st.subassembly && boundsOf.values.maxOf { it.max.z } - floor < 100.0 && boundsOf.values.all { it.min.z <= floor + 1.0 }) {
                out[out.size - 1] = base.copy(partIds = base.partIds + st.partIds, directions = (base.directions + st.directions).distinct(), items = (base.items + st.items).distinct().sorted())
                built += st.partIds; renumber[st.step] = out.size; continue
            }
            // Repeated cabinets are all set out in a row at the start, then joined to each other.
            if (base != null && !base.subassembly && out.count { !it.subassembly } == 1 && base.partIds.any { it.startsWith("module") }) {
                val mods = st.partIds.filter { it.startsWith("module") }
                if (mods.isNotEmpty()) {
                    out[out.size - 1] = base.copy(partIds = base.partIds + mods)
                    built += mods
                    if (mods.size == st.partIds.size) { renumber[st.step] = out.size; continue }
                    for (id in mods) moved[id] = out.size
                    st = st.copy(partIds = st.partIds - mods.toSet())
                }
            }
            if (carry == null && base != null && !base.subassembly && out.count { !it.subassembly } == 1 && base.partIds.all { onFloor(it) }) {
                // Only pieces the next parts tie to the base straight away (a bed's slats, a table's aprons): one that
                // stands alone until the very end (a desk's far end panel) waits for its own step.
                val soon = st.partIds + (steps.getOrNull(n + 1)?.partIds ?: emptyList())
                val standing = st.partIds.filter { id -> onFloor(id) && built.none { touch(id, it) } &&
                    soon.any { t -> t != id && touch(t, id) && base.partIds.any { bp -> touch(t, bp) } } }
                if (standing.isNotEmpty()) {
                    out[out.size - 1] = base.copy(partIds = base.partIds + standing)
                    built += standing
                    if (standing.size == st.partIds.size) { renumber[st.step] = out.size; continue }
                    for (id in standing) moved[id] = out.size
                    st = st.copy(partIds = st.partIds - standing.toSet())
                }
            }
            // A piece standing on the floor that nothing holds yet (a desk's far end panel, fitted with a back panel)
            // waits for the step that joins it, which the rest of its step goes on without it.
            if (carry == null && base != null && n < steps.size - 1 && st.partIds.size > 1) {
                val loners = st.partIds.filter { id -> onFloor(id) && built.none { touch(id, it) } && st.partIds.none { o -> o != id && touch(o, id) } }
                if (loners.isNotEmpty() && loners.size < st.partIds.size) { pending += loners; st = st.copy(partIds = st.partIds - loners.toSet()) }
            }
            val alone = carry == null && built.isNotEmpty() && n < steps.size - 1 &&
                st.partIds.all { id -> onFloor(id) } &&
                st.partIds.none { id -> built.any { touch(id, it) } }
            built += st.partIds
            if (alone) { carry = st; continue }
            // What waits goes in with the first step that touches it, the one that ties it in.
            val c = carry
            val ties = c != null && st.partIds.any { id -> c.partIds.any { touch(id, it) } }
            val merged = if (c != null && ties) st.copy(partIds = c.partIds + st.partIds, directions = (c.directions + st.directions).distinct(), items = (c.items + st.items).distinct().sorted()) else st
            if (c != null && ties) { renumber[c.step] = out.size + 1; carry = null }
            val takes = pending.isNotEmpty() && st.partIds.any { id -> pending.any { touch(id, it) } }
            val withPending = if (takes) merged.copy(partIds = pending.toList() + merged.partIds) else merged
            out += withPending.copy(step = out.size + 1); renumber[st.step] = out.size
            if (takes) { for (id in pending) moved[id] = out.size; pending.clear() }
        }
        // Nothing ever tied it: it goes in last, as it was.
        carry?.let { out += it.copy(step = out.size + 1); renumber[it.step] = out.size }
        if (pending.isNotEmpty()) { out += steps.first().copy(step = out.size + 1, partIds = pending.toList(), subassembly = false, directions = emptyList()); for (id in pending) moved[id] = out.size }
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
        // Across steps too: a bookcase's shelves fitted over four steps are still drawn pulled out together, side by
        // side, rather than each clearing the last and spilling down the page.
        // Drawer boxes alike (a chest's six) are pulled out together too, each with its whole box.
        val kindOf = parts.filter { !it.excluded }.groupBy { unitOf[it.id] ?: it.id }.mapValues { (_, ms) -> ms.mapNotNull { it.item }.sorted().joinToString(",") }
        val key = { p: Part ->
            val u = unitOf[p.id] ?: p.id
            val dir = p.explode?.let { e -> val d = e.normalized(); "${Math.round(d.x * 4)},${Math.round(d.y * 4)},${Math.round(d.z * 4)}" }
            when {
                dir == null || p.excluded || p.item == null -> null
                single(p) -> "${p.item}/$dir"
                u.startsWith("drawer") -> "box:${kindOf[u]}/$dir"
                else -> null
            }
        }
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

    /** A sub-assembly of more parts than this is built in several bench steps. */
    private const val BIG_SUB = 6

    /** Slats laid in one step, at most: a big bed's worth. */
    private const val MAX_SLATS = 30

    /** Drawers, their fronts or doors hung in one step, at most: a kitchen run's worth (each is hung the same way). */
    private const val MAX_DRAWERS = 24

    /** [hungGiven], when not null, names the parts hung last (doors, drawers made whole, shelves on pins) instead of finding them. */
    private fun planCore(all: List<Part>, maxSteps: Int = 10, order: List<List<String>>? = null, hungGiven: Set<String>? = null, hungFirst: Set<String> = emptySet(),
                         members: Map<String, List<Part>> = emptyMap()): PlanResult {
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
        // A face frame is never where the build starts: it goes on the finished case.
        val onFloor = (0 until n).filter { lo[it].z <= allLo.z + tol && parts[it].id != "faceframe" }.ifEmpty { (0 until n).filter { lo[it].z <= allLo.z + tol } }
        fun foot(i: Int) = (hi[i].x - lo[i].x) * (hi[i].y - lo[i].y)
        fun lyingFlat(i: Int) = (hi[i].z - lo[i].z) <= minOf(hi[i].x - lo[i].x, hi[i].y - lo[i].y)
        val flatBase = onFloor.filter { lyingFlat(it) && foot(it) >= 0.25 * modelFoot }.maxByOrNull { foot(it) }
        // Equal candidates (a pedestal desk's three uprights) go by how much they touch: the build starts where
        // the most is joined, not from an end panel that waits alone until the top goes on.
        fun contacts(i: Int) = (0 until n).count { j -> j != i && (0..2).all { lo[i][it] <= hi[j][it] + tol && lo[j][it] <= hi[i][it] + tol } }
        // A case is started from a side, as a cabinetmaker does, not from its back however big: the back goes on a case
        // already standing (and a solid back is screwed through into the sides' edges).
        val starters = onFloor.filter { parts[it].name != "Back" && parts[it].name != "Back panel" && parts[it].name != "Kick board" }.ifEmpty { onFloor }
        val base = flatBase ?: starters.maxWithOrNull(compareBy<Int> { Math.round(foot(it) * (hi[it].z - lo[it].z) / 1e4) }.thenBy { contacts(it) }) ?: 0

        // Shadow of every part along each axis, shrunk a little so parts that only touch do not count as overlapping.
        // Worked out only for parts some test needs; with hundreds of parts most pairs never get that far.
        val shadowCache = Array(3) { arrayOfNulls<Geometry>(n) }
        fun shadow(k: Int, i: Int): Geometry = shadowCache[k][i] ?: run {
            val view = ViewDef("axis$k", Vec3.axis(k), Vec3.axis((k + 1) % 3))
            Silhouette.outline(parts[i].mesh, view).buffer(-tol).also { shadowCache[k][i] = it }
        }

        val memberShadows = HashMap<String, Geometry>()
        fun memberShadow(k: Int, m: Part): Geometry = memberShadows.getOrPut("$k/${m.id}") {
            Silhouette.outline(m.mesh, ViewDef("axis$k", Vec3.axis(k), Vec3.axis((k + 1) % 3))).buffer(-tol)
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
        fun aheadShadow(j: Int, i: Int, d: Dir): Geometry {
            val m = parts[j].mesh; val v = m.vertices; val t = m.triangles
            val edge = if (d.sign > 0) hi[i][d.k] - tol else lo[i][d.k] + tol
            // Each triangle clipped to the half of space ahead of i's leading face (a big face of a leaning board
            // reaches both sides of it), the pieces fanned back into triangles.
            val nv = ArrayList<Double>(); val nt = ArrayList<Int>()
            fun ahead(c: Double) = if (d.sign > 0) c >= edge else c <= edge
            for (f in 0 until m.triangleCount) {
                val pts = (0..2).map { q -> val vi = t[3 * f + q]; Vec3(v[3 * vi], v[3 * vi + 1], v[3 * vi + 2]) }
                val poly = ArrayList<Vec3>()
                for (q in 0..2) {
                    val p0 = pts[q]; val p1 = pts[(q + 1) % 3]
                    val a0 = ahead(p0[d.k]); val a1 = ahead(p1[d.k])
                    if (a0) poly += p0
                    if (a0 != a1) { val s0 = (edge - p0[d.k]) / (p1[d.k] - p0[d.k]); poly += p0 + (p1 - p0) * s0 }
                }
                if (poly.size < 3) continue
                val base = nv.size / 3
                for (p in poly) { nv += p.x; nv += p.y; nv += p.z }
                for (q in 1 until poly.size - 1) { nt += base; nt += base + q; nt += base + q + 1 }
            }
            if (nt.isEmpty()) return Silhouette.GF.createPolygon()
            val view = ViewDef("axis${d.k}", Vec3.axis(d.k), Vec3.axis((d.k + 1) % 3))
            return runCatching { Silhouette.outline(splitziner.mesh.Mesh(nv.toDoubleArray(), nt.toIntArray()), view).buffer(-tol) }.getOrElse { shadow(d.k, j) }
        }
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
                val a = shadow(d.k, i)
                // A made sub-assembly is judged board by board: a slat resting on a frame's cleat is not blocked by
                // that cleat, only by a board of the frame that lies across its path.
                val ms = members[parts[j].id]
                if (ms != null) {
                    if (ms.any { m -> val mb = m.mesh.bounds
                            val mBehind = if (d.sign > 0) mb.max[d.k] <= lo[i][d.k] + tol else mb.min[d.k] >= hi[i][d.k] - tol
                            val apart = (0..2).any { ax -> ax != d.k && (mb.min[ax] >= hi[i][ax] - tol || mb.max[ax] <= lo[i][ax] + tol) }
                            !mBehind && !apart && memberShadow(d.k, m).let { b -> !a.isEmpty && !b.isEmpty && a.intersects(b) && a.intersection(b).area > tol * tol } }) out += j
                    continue
                }
                // A notched part (a stringer) is in the way only where it has material ahead of i: its triangles
                // past i's leading face. A plain box is the same either way.
                val vol = kotlin.math.abs(parts[j].mesh.volume); val boxV = (0..2).fold(1.0) { acc, ax -> acc * (hi[j][ax] - lo[j][ax]) }
                val b = if (vol < 0.85 * boxV) aheadShadow(j, i, d) else shadow(d.k, j)
                if (!a.isEmpty && !b.isEmpty && a.intersects(b) && a.intersection(b).area > tol * tol) out += j
            }
            out.toIntArray()
        }
        fun blockers(i: Int, d: Dir, among: Set<Int>): List<Int> = allBlockers(i, d).filter { it in among }

        val floorZ = lo.minOf { it.z }
        fun dirs(i: Int): List<Dir> {
            val e = hi[i] - lo[i]
            val k0 = if (e.x <= e.y && e.x <= e.z) 0 else if (e.y <= e.z) 1 else 2
            val s0 = if ((lo[i][k0] + hi[i][k0]) / 2 >= cen[k0]) 1 else -1
            val out = ArrayList<Dir>()
            for (d in listOf(Dir(k0, s0), Dir(k0, -s0), Dir(2, 1), Dir(1, -1), Dir(0, -1), Dir(0, 1), Dir(1, 1))) {
                if (d !in out && !(d.k == 2 && d.sign == -1)) out += d
            }
            // Nothing standing on the floor goes in from below it.
            return if (lo[i].z <= floorZ + 1.0) out else out + Dir(2, -1)
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
        val hungIds = hungGiven ?: runCatching { splitziner.check.Doors.find(parts).map { it.id } + splitziner.check.Doors.sliding(parts) + drawerSets.flatten() + splitziner.check.ShelfPins.find(parts) }.getOrDefault(emptyList()).toSet()
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
            // A ladder is fitted last but comes off sideways, away from the side it is fixed to, not out of the front.
            val outFront = (if (firstOff.isNotEmpty()) firstOff else hungGroups).filter { g -> g.none { parts[it].id == "ladder" } }.filter { g -> g.all { i -> i in remaining && (hungGiven != null || blockers(i, outDir, present).all { it in g }) } }
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
                // A board laid on top of what is built (a desk top on its ends, with a hutch to come on it) goes on
                // from above, though in the finished piece something above stops it lifting off.
                // A tread goes down onto its stringers' notches.
                // Nothing is fitted from below when the way from above is just as clear: the part is let down, not held up.
                if (d0.k == 2 && d0.sign == -1 && blockers(i, Dir(2, 1), placed).isEmpty()) return Dir(2, 1)
                if (d0.k != 2 && (parts[i].name == "Tread" || parts[i].name == "Slat") && blockers(i, Dir(2, 1), placed).isEmpty()) return Dir(2, 1)
                if (d0.k != 2 && (lyingFlat(i) || parts[i].id == "upper") && placed.any { j -> touches(i, j) && hi[j].z <= lo[i].z + tol } &&
                    placed.none { j -> touches(i, j) && lo[j].z < hi[i].z - tol && hi[j].z > lo[i].z + tol } && blockers(i, Dir(2, 1), placed).isEmpty()) return Dir(2, 1)
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
            // A face frame goes on the finished case, before the doors and drawers that hang in it.
            // A bed (or a slatted bench) goes up as a builder does: the frame round the outside on its legs, then what
            // runs inside it (a centre beam on its legs, the ledgers), then the slats, then the headboard and guard rails.
            // One deck only: a bunk bed is built deck by deck from the bottom, and a bench's seat goes on before its back.
            val slatZ = parts.indices.filter { parts[it].name == "Slat" }.map { lo[it].z }
            val slatted = slatZ.isNotEmpty() && slatZ.max() - slatZ.min() < 50.0 && parts.none { it.name == "Back slat" || it.name == "Back support" }
            fun bedRank(i: Int) = if (!slatted) 0 else when (parts[i].name) {
                "Centre rail", "Centre leg", "Cleat" -> 1; "Slat" -> 2; "Headboard", "Footboard", "Guard rail" -> 3; else -> 0 }
            fun rank(g: List<Int>) = when { g.any { parts[it].name == "Wall cleat" } -> 40; g.any { parts[it].id in hungFirst } -> 30; g.any { parts[it].id in hungIds } -> 20; g.any { parts[it].id == "faceframe" } -> 10; else -> g.maxOf { bedRank(it) } }
            while (pool.isNotEmpty()) {
                val low = pool.minOf { rank(it) }
                // Bottom up where there is a choice: a bunk bed's lower deck before the upper one closes it in.
                val now = pool.filter { rank(it) == low }.sortedWith(compareBy<List<Int>> { g -> g.minOf { lo[it].z } }.thenBy { g -> g.maxOf { hi[it].z } })
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
                // Doors and drawers are hung on their own, never in a step with the box they hang in.
                val hungMix = (a.any { parts[it].id in hungIds } || b.any { parts[it].id in hungIds }) && !(a.all { parts[it].id in hungIds } && b.all { parts[it].id in hungIds })
                return (if (hungMix) 50.0 else 0.0) + (if (touch) 0.0 else 2.0) + (if (sameDirs) 0.0 else 1.0) + (if (shareItem) 0.0 else 0.5) +
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
