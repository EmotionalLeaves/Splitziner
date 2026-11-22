package splitziner.explode

import org.locationtech.jts.geom.Geometry
import splitziner.math.Vec3
import splitziner.mesh.DisjointSet
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
    fun plan(all: List<Part>, maxSteps: Int = 10, order: List<List<String>>? = null): PlanResult {
        val parts = all.filter { !it.excluded }
        val n = parts.size
        if (n == 0) return PlanResult(all, emptyList())
        val lo = parts.map { it.mesh.bounds.min }; val hi = parts.map { it.mesh.bounds.max }
        val allLo = Vec3(lo.minOf { it.x }, lo.minOf { it.y }, lo.minOf { it.z }); val allHi = Vec3(hi.maxOf { it.x }, hi.maxOf { it.y }, hi.maxOf { it.z })
        val tol = maxOf(1e-3 * (allHi - allLo).length, 0.05)
        val cen = (allLo + allHi) * 0.5

        // Base: of the parts standing on the floor, the one with the largest footprint.
        var base = 0; var bestFoot = -1.0
        for (i in 0 until n) if (lo[i].z <= allLo.z + tol) {
            val foot = (hi[i].x - lo[i].x) * (hi[i].y - lo[i].y)
            if (foot > bestFoot) { bestFoot = foot; base = i }
        }

        // Shadow of every part along each axis, shrunk a little so parts that only touch do not count as overlapping.
        val shadows: List<List<Geometry>> = (0..2).map { k ->
            val view = ViewDef("axis$k", Vec3.axis(k), Vec3.axis((k + 1) % 3))
            parts.map { Silhouette.outline(it.mesh, view).buffer(-tol) }
        }

        fun touches(a: Int, b: Int) = (0..2).all { lo[a][it] <= hi[b][it] + tol && lo[b][it] <= hi[a][it] + tol }
        val adj = (0 until n).map { i -> (0 until n).filter { j -> j != i && touches(i, j) } }

        fun blockers(i: Int, d: Dir, among: Set<Int>): List<Int> {
            val out = ArrayList<Int>()
            for (j in among) {
                if (j == i) continue
                // Must overlap on both other axes to be in the way at all.
                if ((0..2).any { a -> a != d.k && (lo[j][a] >= hi[i][a] - tol || hi[j][a] <= lo[i][a] + tol) }) continue
                // Parts entirely behind the direction of travel are not in the way.
                val behind = if (d.sign > 0) hi[j][d.k] <= lo[i][d.k] + tol else lo[j][d.k] >= hi[i][d.k] - tol
                if (behind) continue
                val a = shadows[d.k][i]; val b = shadows[d.k][j]
                if (!a.isEmpty && !b.isEmpty && a.intersects(b) && a.intersection(b).area > tol * tol) out += j
            }
            return out
        }

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
        while (remaining.isNotEmpty()) {
            val present = remaining + base
            var free = LinkedHashMap<Int, Dir>()
            for (i in remaining.sorted()) for (d in dirs(i)) if (blockers(i, d, present).isEmpty()) { free[i] = d; break }
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

        // Sub-assemblies: in one layer, parts leaving the same way that touch each other.
        val raw = ArrayList<MutableList<Int>>()
        for (layer in layers.reversed()) {
            val idx = layer.keys.toList()
            val ds = DisjointSet(idx.size)
            for (a in idx.indices) for (b in a + 1 until idx.size) if (layer[idx[a]] == layer[idx[b]] && touches(idx[a], idx[b])) ds.union(a, b)
            val groups = idx.indices.groupBy { ds.find(it) }.values.map { g -> g.map { idx[it] }.toMutableList() }
            // Parts dropped in from above first, then from the lowest up.
            raw += groups.sortedWith(compareBy<MutableList<Int>>({ !(dirOf.getValue(it[0]).k == 2 && dirOf.getValue(it[0]).sign == 1) }, { g -> g.minOf { lo[it].z } }))
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
            while (pool.isNotEmpty()) {
                val pick = pool.firstOrNull { feasible(it) && grounded(it) } ?: pool.firstOrNull { feasible(it) && supported(it) }
                    ?: pool.firstOrNull { feasible(it) } ?: pool.first()
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
                    0.6 * maxOf(0, a.size + b.size - target) + 0.02 * (a.size + b.size)
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
