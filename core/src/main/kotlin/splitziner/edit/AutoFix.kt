package splitziner.edit

import splitziner.check.DesignCheck
import splitziner.dims.LengthFormat
import splitziner.math.Vec3
import splitziner.parts.CutList
import splitziner.parts.Part
import splitziner.parts.PartKind
import kotlin.math.abs

/** The changes worked out, what each one does in plain words, and what was left alone and why. */
class FixPlan(val edits: List<Edit>, val done: List<String>, val left: List<String>, val parts: List<Part>)

/**
 * Works out the changes that make a model buildable without changing what it is: closing small gaps,
 * joining pieces that were drawn as overlapping bodies of one sheet, trimming parts that run a little
 * into their neighbours, and moving odd thicknesses to ones that can be bought. Anything that would
 * change the design (cutting away a large share of a part, rebuilding a hollow solid) is left for the
 * maker and reported.
 */
object AutoFix {
    /** A loose group further than this (mm) from the rest is not moved: it may be meant to be separate. */
    private const val MAX_SEAT = 30.0

    private val DIRS = listOf(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0))
    private fun dirName(d: Vec3) = when { d.z < 0 -> "down"; d.z > 0 -> "up"; d.x > 0 -> "right"; d.x < 0 -> "left"; d.y > 0 -> "back"; else -> "forward" }

    fun plan(original: List<Part>, fmt: LengthFormat, already: List<Edit> = emptyList()): FixPlan {
        val edits = ArrayList<Edit>(); val done = ArrayList<String>(); val left = ArrayList<String>()
        val base = original.filter { !it.excluded }
        var parts = CutList.build(Editor.apply(base, already).parts, fmt).parts
        val d = { v: Double -> fmt.format(v) }
        val u = fmt.unitLabel
        fun label(p: Part) = "item ${p.item} (${p.desc.lowercase()})"
        fun push(e: Edit, text: String) {
            edits += e; done += text
            parts = CutList.build(Editor.apply(base, already + edits).parts, fmt).parts
        }

        fun seat() {
            val rel = DesignCheck.relations(parts)
            val islandIdx = rel.islands
            val loose = islandIdx.flatten().toSet()
            val body = parts.filterIndexed { i, _ -> i !in loose }
            for (g in islandIdx) {
                val group = g.map { parts[it] }
                var best: Pair<Vec3, Double>? = null
                for (dir in DIRS) { val gap = DesignCheck.gap(group, body, dir) ?: continue; if (gap > 0.01 && (best == null || gap < best.second - 1e-6)) best = dir to gap }
                val b = best
                val who = group.mapNotNull { it.item }.distinct().sorted().joinToString(", ")
                if (b == null || b.second > MAX_SEAT) { left += "Item${if (group.size == 1) "" else "s"} $who: not joined to the rest, and too far away to move safely."; continue }
                push(Edit.Move(group.map { it.id }, b.first * b.second), "Moved item${if (group.size == 1) "" else "s"} $who ${dirName(b.first)} by ${d(b.second)} $u to rest on the rest of the model.")
            }
        }
        seat()

        // Overlapping flat pieces of one thickness lying in one plane are one piece of sheet drawn as several bodies.
        run {
            val rel = DesignCheck.relations(parts)
            val prisms = parts.map { runCatching { Prisms.of(it.mesh) }.getOrNull() }
            val parent = IntArray(parts.size) { it }
            fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
            for (c in rel.clashes) {
                val a = prisms[c.a] ?: continue; val b = prisms[c.b] ?: continue
                if ((a.view.n dot b.view.n) < 0.999) continue
                if (abs(a.lo - b.lo) > 0.6 || abs(a.hi - b.hi) > 0.6) continue
                parent[find(c.a)] = find(c.b)
            }
            // Looked up before any change is made: each change renumbers the list.
            val groups = parts.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { parts[it] } }
            for (ps in groups) {
                push(Edit.Merge(ps.map { it.id }), "Joined ${ps.size} overlapping flat pieces (items ${ps.mapNotNull { it.item }.distinct().sorted().joinToString(", ")}) into one piece ${d(ps[0].t)} $u thick; they lie in one plane and cut as one shape.")
            }
        }

        // Parts that run into a neighbour: cut the one that loses least, if that is only a little of it.
        run {
            var guard = 0
            val tried = HashSet<Set<String>>()
            while (guard++ < 60) {
                val rel = DesignCheck.relations(parts)
                val c = rel.clashes.sortedByDescending { it.depth }.firstOrNull { setOf(parts[it.a].id, parts[it.b].id) !in tried } ?: break
                val a = parts[c.a]; val b = parts[c.b]
                tried += setOf(a.id, b.id)
                val ab = runCatching { Editor.trimmed(a, b) }.getOrNull(); val ba = runCatching { Editor.trimmed(b, a) }.getOrNull()
                val pick = listOfNotNull(ab?.let { Triple(a, b, it.second) }, ba?.let { Triple(b, a, it.second) }).filter { it.third <= Editor.MAX_TRIM_SHARE }.minByOrNull { it.third }
                if (pick == null) {
                    left += "${label(a).replaceFirstChar { it.uppercase() }} and ${label(b)} overlap by about ${d(c.depth)} $u. " +
                        (if (ab == null && ba == null) "Neither passes right through the other, so one needs a recess cut into it: that is a joint to design, not a trim."
                        else "Cutting either back would remove more than a quarter of it, which changes the design.")
                    continue
                }
                push(Edit.Trim(pick.first.id, pick.second.id), "Trimmed ${label(pick.first)} where it ran into ${label(pick.second)}: about ${Math.round(pick.third * 100).coerceAtLeast(1)}% of it removed.")
            }
        }

        // Thicknesses nobody sells, moved to the nearest one that is sold.
        run {
            val flat = parts.filter { it.kind != PartKind.ROD && (it.kind == PartKind.PANEL || it.kind == PartKind.BOARD || it.t < 0.3 * it.w) }
            for ((_, ps) in flat.filter { DesignCheck.nearestStock(it.t, fmt).size != 1 }.groupBy { Math.round(it.t * 20) }.entries.sortedBy { it.key }) {
                val t = ps[0].t
                val to = DesignCheck.nearestStock(t, fmt).minByOrNull { abs(it - t) } ?: continue
                val ds = { v: Double -> if (fmt.isInch && fmt.inchStyle == "decimal") fmt.copy(decimals = 3).format(v).let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it } else fmt.format(v) }
                push(Edit.Thickness(ps.map { it.id }, to), "Changed ${ps.size} part${if (ps.size == 1) "" else "s"} from ${d(t)} to ${ds(to)} $u thick (items ${ps.mapNotNull { it.item }.distinct().sorted().joinToString(", ")}), a thickness shops stock.")
            }
        }
        // Changing thicknesses can open a hair of a gap under something; close it.
        seat()

        for (p in parts.filter { it.kind == PartKind.SHAPED && it.t >= 0.3 * it.w }.distinctBy { it.item }) {
            val fill = abs(p.mesh.volume) / maxOf(p.t * p.w * p.l, 1e-9)
            if (fill < 0.5) left += "${label(p).replaceFirstChar { it.uppercase() }} is a hollow shape drawn as one solid. It needs redrawing as separate boards in the 3D program; the app cannot tell where its joints should be."
        }
        return FixPlan(edits, done, left, parts)
    }
}
