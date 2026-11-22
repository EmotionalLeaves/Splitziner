package splitziner.check

import splitziner.dims.LengthFormat
import splitziner.parts.CutListRow
import splitziner.parts.Part

/**
 * How to make one item, step by step, from the work its shape needs and the tools the maker has.
 * Workshop practice written down; it says which tool for each step and what to watch for.
 */
object HowTo {
    fun steps(row: CutListRow, part: Part, fmt: LengthFormat, tools: Set<Tool>?, grooves: List<String> = emptyList()): List<String> {
        val w = runCatching { DesignCheck.workFor(part, fmt) }.getOrNull() ?: return emptyList()
        val d = { v: Double -> fmt.format(v) }
        val u = if (fmt.isInch) "in" else "mm"
        fun pick(work: Work): String {
            if (tools == null) return work.with.first().label.lowercase()
            work.with.firstOrNull { it in tools }?.let { return it.label.lowercase() }
            work.slowly.firstOrNull { it in tools }?.let { return "${it.label.lowercase()} (slowly; a ${work.with.first().label.lowercase()} does it better)" }
            return "${work.with.first().label.lowercase()} (not in your tools)"
        }
        fun grooveTool(): String = when {
            tools == null || Tool.ROUTER in tools -> "router"
            Tool.TABLE_SAW in tools -> "table saw (several passes)"
            Tool.CNC in tools -> "CNC router"
            else -> "router (not in your tools; a chisel and saw do it slowly)"
        }
        val out = ArrayList<String>()
        when {
            Work.CARVE in w.work -> {
                out += "This is a solid shape, not a board: glue up layers to ${d(row.t)} x ${d(row.w)} x ${d(row.l)} $u, then shape it with a ${pick(Work.CARVE)}, or have it machined or printed."
                return out
            }
            row.kind == splitziner.parts.PartKind.ROD -> out += "Cut a length of ${d(row.t)} $u dowel or rod to ${d(row.l)} $u with a ${pick(Work.STRAIGHT_CUT)}."
            else -> {
                val big = Work.SHEET_CUT in w.work
                val tool = pick(if (big) Work.SHEET_CUT else Work.STRAIGHT_CUT)
                out += "Cut a blank ${d(row.w)} x ${d(row.l)} $u from ${d(row.t)} $u stock with a $tool" +
                    (if (big && tool.contains("circular")) ", clamping a straight board as a fence" else "") + (if (row.grain) ", with the grain along the ${d(row.l)} $u length" else "") + "."
            }
        }
        if (Work.NOTCH in w.work) out += "Mark the notches from the drawing and cut them with a ${pick(Work.NOTCH)}, staying just inside the line; pare or sand to it."
        if (Work.CURVED_CUT in w.work) out += "Mark the curves (print the full-size template if it has one) and cut them with a ${pick(Work.CURVED_CUT)}, just outside the line; sand to the line."
        if (Work.CUTOUT in w.work) out += "For the ${if (w.cutouts == 1) "opening" else "${w.cutouts} openings"}: drill a hole inside each one big enough for the blade, then cut out with a ${pick(Work.CUTOUT)}."
        if (Work.HOLES in w.work) {
            val sizes = w.holeDiameters.distinct().sorted().joinToString(", ") { d(it) }
            out += "Drill the holes${if (sizes.isNotEmpty()) " ($sizes $u)" else ""} with a ${pick(Work.HOLES)}, with a scrap board behind to stop the back splintering."
        }
        if (Work.EDGE_SHAPE in w.work) out += "Round or bevel the edges with a ${pick(Work.EDGE_SHAPE)}; test the setting on an offcut first."
        if (Work.THICKNESS in w.work) out += "Plane it down to ${d(row.t)} $u with a ${pick(Work.THICKNESS)}, taking light passes off both faces."
        for (g in grooves) {
            // "GROOVE FOR ITEM 32: 1/2 DEEP X 7/8 WIDE X 33-3/4 LONG, WHERE ... (2 PLACES)."
            val m = Regex("""GROOVE FOR ITEM (\S+): (.+?) DEEP X (.+?) WIDE X (.+?) LONG.*?(\((\d+) PLACES\))?\.?$""").find(g) ?: continue
            val places = m.groupValues[6].takeIf { it.isNotEmpty() }?.let { " in $it places" } ?: ""
            out += "Cut a groove for item ${m.groupValues[1]}$places: ${m.groupValues[2].lowercase()} deep, ${m.groupValues[3].lowercase()} wide, ${m.groupValues[4].lowercase()} long, where its drawing shows, with a ${grooveTool()}."
        }
        if (row.qty > 1) out += "Make ${row.qty} the same; cutting them together, or setting a stop, keeps them identical."
        out += "Label it ${row.item} on a face that will not show."
        return out
    }

    /**
     * The steps worth a place on the guide's "Making the parts" page, without the closing label line; null for a
     * plain board that is only cut to size (copies included), which the cut list already covers.
     */
    fun making(steps: List<String>): List<String>? {
        val work = steps.filter { !it.startsWith("Make ") && !it.startsWith("Label it ") }
        return if (work.size <= 1) null else steps.filter { !it.startsWith("Label it ") }
    }
}
