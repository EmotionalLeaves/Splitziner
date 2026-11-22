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
    /**
     * A thin frame cut from sheet is rebuilt from strips when its material covers less than this share of the
     * sheet piece it would be cut from. Below a third, two thirds of a bought sheet goes in the bin to make
     * what three or four strips make; above it (a panel with an opening) one piece is the sensible way.
     */
    const val SCRAP_SPLIT_SHARE = 0.35

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
        // Parts are named by their item numbers in the model as it was, which stay put while changes renumber the cut list.
        val firstItem = parts.associate { it.id to it.item }
        fun label(p: Part): String {
            val root = p.id.substringBefore('.')
            val item = firstItem[p.id] ?: firstItem[root]
            val split = p.id.contains('.') || (firstItem[p.id] != null && parts.any { it.id.startsWith("$root.") })
            val size = listOf(p.t, p.w, p.l).joinToString(" x ") { d(it) }
            return if (split) "a $size piece of item $item" else "item $item (${p.desc.lowercase()})"
        }
        val counts = LinkedHashMap<String, Int>()
        // Pairs of parts already tested; a change only makes the pairs with a changed part be tested again.
        val memo = DesignCheck.PairMemo()
        // Trims are told once per pair of items: how many cuts, and the largest share any one piece lost.
        class TrimGroup(val text: String) { var cuts = 0; var most = 0.0 }
        val trims = LinkedHashMap<String, TrimGroup>()
        fun itemOf(p: Part) = firstItem[p.id] ?: firstItem[p.id.substringBefore('.')]
        fun itemLabel(p: Part): String {
            val it = itemOf(p); val orig = base.firstOrNull { b -> b.id == p.id.substringBefore('.') }
            val desc = if (orig != null) CutList.build(listOf(orig), fmt).parts.firstOrNull()?.desc?.lowercase() else null
            return if (desc != null) "item $it ($desc)" else "item $it"
        }
        // Each part's volume before any trimming, for the limit on how much may be cut away in all.
        val startVolume = HashMap<String, Double>()
        fun push(e: Edit, text: String) {
            edits += e; counts[text] = (counts[text] ?: 0) + 1
            parts = CutList.build(Editor.apply(parts, listOf(edits.last())).parts, fmt).parts
        }

        fun seat() {
            val rel = DesignCheck.relations(parts, memo)
            val islandIdx = rel.islands
            val loose = islandIdx.flatten().toSet()
            val body = parts.filterIndexed { i, _ -> i !in loose }
            for (g in islandIdx) {
                val group = g.map { parts[it] }
                var best: Pair<Vec3, Double>? = null
                for (dir in DIRS) { val gap = DesignCheck.gap(group, body, dir) ?: continue; if (gap > 0.01 && (best == null || gap < best.second - 1e-6)) best = dir to gap }
                val b = best
                val who = group.mapNotNull { firstItem[it.id] ?: firstItem[it.id.substringBefore('.')] }.distinct().sorted().joinToString(", ")
                if (b == null || b.second > MAX_SEAT) { left += "Item${if (group.size == 1) "" else "s"} $who: not joined to the rest, and too far away to move safely."; continue }
                push(Edit.Move(group.map { it.id }, b.first * b.second), "Moved item${if (group.size == 1) "" else "s"} $who ${dirName(b.first)} by ${d(b.second)} $u to rest on the rest of the model.")
            }
        }
        // Solids that are really several boards are taken apart first, so later steps work on real pieces:
        // always for open boxes and channels, and for frames too thick to cut from any sheet.
        run {
            for ((_, ps) in parts.groupBy { it.item }) {
                val p = ps[0]
                val b = runCatching { Boards.decompose(p.mesh).first }.getOrNull() ?: continue
                // A thin frame is left as one sheet piece unless most of that sheet would be scrap.
                if (b.flat && p.t < 18.5 && b.materialShare >= SCRAP_SPLIT_SHARE) continue
                push(Edit.SplitBoards(ps.map { it.id }), "Made ${label(p)} from ${b.pieces.size} boards" + (if (ps.size > 1) " (each of the ${ps.size})" else "") + "; ${b.note.substringAfter("; ", "").ifBlank { "it was drawn as one solid" }}.")
            }
        }
        seat()

        // Overlapping flat pieces of one thickness lying in one plane are one piece of sheet drawn as several bodies.
        run {
            val rel = DesignCheck.relations(parts, memo)
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
            val groups = parts.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { parts[it] } to g.mapNotNull { prisms[it] } }
            for ((ps, pr) in groups) {
                // Joining strips into a frame cut from one sheet piece would undo the split above and waste the sheet.
                // Share of the joined piece's box that is material; overlaps are small, so volumes simply add.
                val box = splitziner.mesh.OrientedBounds.of(splitziner.mesh.MeshOps.concatenate(ps.map { it.mesh }))
                val boxVol = box.extents.x * box.extents.y * box.extents.z
                fun share(m: splitziner.mesh.Mesh): Double { val e = splitziner.mesh.OrientedBounds.of(m).extents; val v = e.x * e.y * e.z; return if (v > 0) abs(m.volume) / v else 1.0 }
                val joined = if (boxVol > 0) ps.sumOf { abs(it.mesh.volume) } / boxVol else 1.0
                // Only when joining makes much more scrap than the pieces have on their own: strips becoming a frame,
                // not a shaped piece taking in a block that overlaps it.
                if (pr.size == ps.size && joined < SCRAP_SPLIT_SHARE && joined < 0.6 * ps.minOf { share(it.mesh) }) continue
                push(Edit.Merge(ps.map { it.id }), "Joined ${ps.joinToString(" and ") { label(it) }} into one piece ${d(ps[0].t)} $u thick; they overlap in one plane and cut as one shape.")
            }
        }

        // Parts that run into a neighbour: cut the one that loses least, if that is only a little of it.
        for (p in parts) startVolume[p.id] = abs(p.mesh.volume)
        run {
            var guard = 0
            val tried = HashSet<Set<String>>()
            while (guard++ < 60) {
                val rel = DesignCheck.relations(parts, memo)
                val c = rel.clashes.sortedByDescending { it.depth }.firstOrNull { setOf(parts[it.a].id, parts[it.b].id) !in tried } ?: break
                val a = parts[c.a]; val b = parts[c.b]
                tried += setOf(a.id, b.id)
                val ab = runCatching { Editor.trimmed(a, b) }.getOrNull(); val ba = runCatching { Editor.trimmed(b, a) }.getOrNull()
                // The limit is on everything cut from a part, not on each cut: four small trims can add up to a different part.
                fun cutSoFar(p: Part, after: Double): Double { val v0 = startVolume[p.id] ?: return after; return 1 - (1 - after) * abs(p.mesh.volume) / v0 }
                val pick = listOfNotNull(ab?.let { Triple(a, b, it.second) }, ba?.let { Triple(b, a, it.second) })
                    .filter { cutSoFar(it.first, it.third) <= Editor.MAX_TRIM_SHARE }.minByOrNull { it.third }
                if (pick == null) {
                    // Neither passes through the other: one stops partway into it. Cutting it back to the other's face
                    // makes a butt joint, if that costs little of it.
                    val cb = listOfNotNull(runCatching { Editor.cutBack(a, b) }.getOrNull()?.let { Triple(a, b, it.second) }, runCatching { Editor.cutBack(b, a) }.getOrNull()?.let { Triple(b, a, it.second) })
                        // Only the smaller part is cut: it is the one that enters the other. Shortening the bigger
                        // one changes its size across its whole width, which is a design decision.
                        // A hair's overlap (under 3.5 mm) is a part drawn a touch long: either may be trimmed.
                        .filter { it.third > 0 && (c.depth < 3.5 || abs(it.first.mesh.volume) <= abs(it.second.mesh.volume)) && cutSoFar(it.first, it.third) <= Editor.MAX_TRIM_SHARE }.minByOrNull { it.third }
                    if (cb != null) {
                        push(Edit.CutBack(cb.first.id, cb.second.id), "Cut ${label(cb.first)} back so it stops at the face of ${label(cb.second)} (a butt joint): about ${Math.round(cb.third * 100).coerceAtLeast(1)}% of it removed.")
                        continue
                    }
                    left += "${label(a).replaceFirstChar { it.uppercase() }} and ${label(b)} overlap by about ${d(c.depth)} $u. " +
                        (if (ab == null && ba == null) "Making it fit means shortening the larger part or cutting away much of the smaller one, which is a design decision. Cut a recess (a housing) in one to take the other, move one, or use the cut-back the Check tab offers."
                        else "Making it fit means shortening the larger part or cutting away much of the smaller one, which is a design decision. The Check tab offers each cut and says how much it removes.")
                    continue
                }
                val key = "${itemOf(pick.first)}>${itemOf(pick.second)}"
                val g = trims.getOrPut(key) { TrimGroup("Trimmed ${itemLabel(pick.first)} where it runs into ${itemLabel(pick.second)}") }
                g.cuts++; g.most = maxOf(g.most, pick.third)
                edits += Edit.Trim(pick.first.id, pick.second.id)
                parts = CutList.build(Editor.apply(parts, listOf(edits.last())).parts, fmt).parts
            }
        }

        // Thicknesses nobody sells, moved to the nearest one that is sold.
        run {
            val flat = parts.filter { it.kind != PartKind.ROD && (it.kind == PartKind.PANEL || it.kind == PartKind.BOARD || it.t < 0.3 * it.w) }
            for ((_, ps) in flat.filter { DesignCheck.nearestStock(it.t, fmt).size != 1 }.groupBy { Math.round(it.t * 20) }.entries.sortedBy { it.key }) {
                val t = ps[0].t
                val to = DesignCheck.nearestStock(t, fmt).minByOrNull { abs(it - t) } ?: continue
                val ds = { v: Double -> if (fmt.isInch && fmt.inchStyle == "decimal") fmt.copy(decimals = 3).format(v).let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it } else fmt.format(v) }
                push(Edit.Thickness(ps.map { it.id }, to), "Changed ${ps.size} part${if (ps.size == 1) "" else "s"} from ${d(t)} to ${ds(to)} $u thick, a thickness shops stock (${ps.map { firstItem[it.id] ?: firstItem[it.id.substringBefore('.')] }.distinct().sortedBy { it ?: 0 }.joinToString(", ") { "item $it" }}).")
            }
        }
        // Changing thicknesses can open a hair of a gap under something; close it.
        seat()

        for (p in parts.filter { it.kind == PartKind.SHAPED && it.t >= 0.3 * it.w }.distinctBy { it.item }) {
            val fill = abs(p.mesh.volume) / maxOf(p.t * p.w * p.l, 1e-9)
            if (fill < 0.5) left += "${label(p).replaceFirstChar { it.uppercase() }} is a hollow shape drawn as one solid, and its walls are not square enough to split into boards automatically. It needs redrawing as separate boards in the 3D program."
        }
        // The same change made in several places is told once.
        for ((t, n) in counts) done += if (n > 1) "$t (${n} places)" else t
        // Trims after the others, in the order they were found.
        val trimLines = trims.values.map { g -> "${g.text}: ${if (g.cuts == 1) "1 cut" else "${g.cuts} cuts"}, at most ${Math.round(g.most * 100).coerceAtLeast(1)}% of a piece." }
        val thick = done.filter { it.startsWith("Changed ") }
        done.removeAll(thick.toSet()); done += trimLines; done += thick
        val leftOnce = left.distinct().map { t -> val n = left.count { it == t }; if (n > 1) "$t (${n} places)" else t }
        return FixPlan(edits, done, leftOnce, parts)
    }
}
