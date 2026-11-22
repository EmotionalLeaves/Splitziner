package splitziner.parts

import splitziner.dims.LengthFormat
import splitziner.mesh.Mesh
import splitziner.mesh.OrientedBounds
import splitziner.section.Slicer
import kotlin.math.PI
import kotlin.math.abs

/** Stock-style description of one part. Sizes in millimetres. */
class PartDescription(val kind: PartKind, val t: Double, val w: Double, val l: Double, val fill: Double, val desc: String)

data class CutListRow(
    val item: Int,
    val qty: Int,
    val partNo: String,
    val description: String,
    val material: String,
    val materialSpec: String,
    /** "T x W x L" in display units. */
    val stock: String,
    val kind: PartKind,
    val t: Double, val w: Double, val l: Double,
    val memberIds: List<String>,
)

/** Total length to buy of one stock cross-section, before saw kerf and waste. */
data class StockSummaryRow(val kind: PartKind, val thickness: String, val width: String, val totalLength: String, val pieces: Int)

class CutListResult(val parts: List<Part>, val rows: List<CutListRow>, val stock: List<StockSummaryRow>, val warnings: List<String>)

object PartDescriber {
    /** A flat part at least this wide (mm) is called a panel rather than a board. */
    private const val PANEL_MIN_WIDTH = 250.0

    /** A plain rectangular block fills its box; anything under this has been shaped. */
    private const val SOLID_FILL = 0.96

    /** Under this a board has a notch or hole worth mentioning. */
    private const val NOTCH_FILL = 0.995

    fun describe(m: Mesh, fmt: LengthFormat): PartDescription {
        val ext = m.extents
        val obb = OrientedBounds.of(m)
        // Prefer the plain axis-aligned box unless the oriented one is clearly tighter.
        val use = if (ext.x * ext.y * ext.z <= obb.volume * 1.02) doubleArrayOf(ext.x, ext.y, ext.z)
        else doubleArrayOf(obb.extents.x, obb.extents.y, obb.extents.z)
        use.sort()
        var t = use[0]; var w = use[1]; val l = use[2]
        val vol = abs(m.volume)
        val fill = vol / maxOf(t * w * l, 1e-12)
        val d = { v: Double -> fmt.format(v) }

        if (!m.isWatertight) {
            // Small gaps barely change the measured volume, so the part is still described by it. A surface that is
            // mostly missing (many open edges, or a volume no box of this size could hold) is listed by its outer size only.
            val topo = m.topology
            val open = (0 until topo.edgeCount).count { topo.edgeUseCount[it] == 1 }
            if (open > 0.2 * topo.edgeCount || fill > 1.02 || fill < 0.01)
                return PartDescription(PartKind.SHAPED, t, w, l, 0.0, "SHAPED, ENVELOPE ${d(t)} X ${d(w)} X ${d(l)}")
        }

        var dia: Double? = null
        // A cylinder fills pi/4 (0.785) of its box.
        if (abs(t - w) < 0.05 * w && fill > 0.72 && fill < 0.84) dia = (t + w) / 2
        else if (l > 3 * w && fill < SOLID_FILL) {
            // Round bar with angled ends: look at the cross-section halfway along.
            val axis = obb.axes[listOf(obb.extents.x, obb.extents.y, obb.extents.z).let { e -> e.indexOf(e.max()) }]
            val loops = Slicer.sectionLoops(m, obb.center, axis)
            if (loops.size == 1) {
                val lp = loops[0]
                val w2 = lp.maxOf { it.x } - lp.minOf { it.x }; val h2 = lp.maxOf { it.y } - lp.minOf { it.y }
                val a2 = abs(Slicer.loopArea(lp))
                if (abs(w2 - h2) < 0.08 * maxOf(w2, h2) && abs(a2 / (PI / 4 * w2 * h2) - 1) < 0.06) dia = (w2 + h2) / 2
            }
        }
        if (dia != null) {
            t = dia; w = dia
            return PartDescription(PartKind.ROD, t, w, l, fill, "ROD ⌀${d(dia)} X ${d(l)} LG")
        }
        if (fill > SOLID_FILL) {
            val kind = if (w >= PANEL_MIN_WIDTH) PartKind.PANEL else PartKind.BOARD
            // What is missing from the plain board: round holes are named as holes, anything else as a notch.
            val holes = runCatching { splitziner.features.HoleFinder.find(m) }.getOrDefault(emptyList())
            val drilled = holes.sumOf { Math.PI * it.diameter * it.diameter / 4 * (it.depth ?: 0.0) }
            val missing = t * w * l - vol
            val notch = if (fill < NOTCH_FILL && drilled < 0.9 * missing) ", NOTCHED" else ""
            val holeText = when (holes.size) { 0 -> ""; 1 -> ", 1 HOLE"; else -> ", ${holes.size} HOLES" }
            return PartDescription(kind, t, w, l, fill, "$kind ${d(t)} X ${d(w)} X ${d(l)}$notch$holeText")
        }
        val desc = if (t < 0.3 * w) "SHAPED, ${d(t)} THK, CUT FROM ${d(w)} X ${d(l)}" else "SHAPED, ENVELOPE ${d(t)} X ${d(w)} X ${d(l)}"
        return PartDescription(PartKind.SHAPED, t, w, l, fill, desc)
    }
}

/** Per-item fields the user fills in; keyed by [CutList.itemKey]. */
data class ItemOverride(val material: String = "", val materialSpec: String = "", val partNo: String = "")

object CutList {
    private val KIND_ORDER = mapOf(PartKind.PANEL to 0, PartKind.BOARD to 1, PartKind.SHAPED to 2, PartKind.ROD to 3)

    private class Group(val d: PartDescription, val vol: Double) { val members = ArrayList<Part>() }

    /** Identifies "the same kind of piece" across re-runs, so user edits survive. */
    fun itemKey(kind: PartKind, t: Double, w: Double, l: Double): String =
        "$kind:${Math.round(t * 2)}:${Math.round(w * 2)}:${Math.round(l * 2)}"

    fun build(parts: List<Part>, fmt: LengthFormat, overrides: Map<String, ItemOverride> = emptyMap()): CutListResult {
        val groups = ArrayList<Group>()
        val warnings = ArrayList<String>()
        for (p in parts) {
            if (p.excluded) continue
            val d = PartDescriber.describe(p.mesh, fmt)
            if (!p.mesh.isWatertight) warnings += "${p.name} has gaps in its surface, so its shape is estimated"
            val vol = abs(p.mesh.volume)
            // Sizes are sorted, so identical parts match in any orientation and when mirrored.
            val g = groups.firstOrNull {
                it.d.kind == d.kind && maxOf(abs(it.d.t - d.t), abs(it.d.w - d.w), abs(it.d.l - d.l)) < 0.5 &&
                    abs(it.vol - vol) <= 0.01 * vol + 1e-6
            } ?: Group(d, vol).also { groups += it }
            g.members += p
        }
        val sorted = groups.sortedWith(compareBy<Group>({ KIND_ORDER[it.d.kind] ?: 9 }, { -it.vol }))
        val d = { v: Double -> fmt.format(v) }
        val rows = ArrayList<CutListRow>()
        val itemOf = HashMap<String, Triple<Int, Int, PartDescription>>()
        sorted.forEachIndexed { i, g ->
            val item = i + 1
            val o = overrides[itemKey(g.d.kind, g.d.t, g.d.w, g.d.l)] ?: ItemOverride()
            for (m in g.members) itemOf[m.id] = Triple(item, g.members.size, g.d)
            rows += CutListRow(
                item = item, qty = g.members.size, partNo = o.partNo, description = g.d.desc,
                material = o.material, materialSpec = o.materialSpec,
                stock = "${d(g.d.t)} x ${d(g.d.w)} x ${d(g.d.l)}", kind = g.d.kind,
                t = g.d.t, w = g.d.w, l = g.d.l, memberIds = g.members.map { it.id },
            )
        }
        val outParts = parts.map { p ->
            val x = itemOf[p.id] ?: return@map p
            p.copy(kind = x.third.kind, t = x.third.t, w = x.third.w, l = x.third.l, fill = x.third.fill, desc = x.third.desc, item = x.first, qty = x.second)
        }
        // Boards and rods are bought by length: total it per cross-section.
        val stock = rows.filter { it.kind == PartKind.BOARD || it.kind == PartKind.ROD }
            .groupBy { Triple(it.kind, d(it.t), d(it.w)) }
            .map { (k, rs) -> StockSummaryRow(k.first, k.second, k.third, d(rs.sumOf { it.l * it.qty }), rs.sumOf { it.qty }) }
        return CutListResult(outParts, rows, stock, warnings)
    }
}
