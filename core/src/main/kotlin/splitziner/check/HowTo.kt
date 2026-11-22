package splitziner.check

import splitziner.dims.LengthFormat
import splitziner.parts.CutListRow
import splitziner.parts.Part

/**
 * How to make one item, step by step, from the work its shape needs and the tools the maker has.
 * Workshop practice written down; it says which tool for each step and what to watch for.
 */
object HowTo {
    /** [extra] are further cuts to make in this item (rabbets and grooves for thin parts), told before the copies. */
    fun steps(row: CutListRow, part: Part, fmt: LengthFormat, tools: Set<Tool>?, grooves: List<String> = emptyList(), extra: List<String> = emptyList()): List<String> {
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
        val circle = runCatching { splitziner.drawing.FullSize.circleDiameter(part.mesh) }.getOrNull()
        val oval = if (circle == null) runCatching { splitziner.drawing.FullSize.oval(part.mesh) }.getOrNull() else null
        if (circle != null) out += "It is a circle ${d(circle)} $u across: find the centre of the blank, swing ${if (Regex("""^(8|11(?!\d)|18(?!\d))""").containsMatchIn(d(circle / 2))) "an" else "a"} ${d(circle / 2)} $u radius with a trammel (or a pencil on a string pinned at the centre), cut just outside the line with a ${pick(Work.CURVED_CUT)}, then true the edge with a router on a circle jig pivoting on the same centre, or sand to the line."
        else if (oval != null) {
            val f = Math.sqrt(oval.first * oval.first - oval.second * oval.second) / 2
            out += "It is an oval (an ellipse) ${d(oval.first)} x ${d(oval.second)} $u: draw both centre lines on the blank, drive two nails on the long one ${d(f)} $u each side of the centre, and tie a string that does not stretch between them, ${d(oval.first)} $u long. Hold a pencil against the string, keep it taut and run it round; cut just outside the line with a ${pick(Work.CURVED_CUT)} and sand to the line."
        }
        else if (Work.CURVED_CUT in w.work) out += "Mark the curves (print the full-size template if it has one) and cut them with a ${pick(Work.CURVED_CUT)}, just outside the line; sand to the line."
        if (Work.CUTOUT in w.work) out += "For the ${if (w.cutouts == 1) "opening" else "${w.cutouts} openings"}: drill a hole inside each one big enough for the blade, then cut out with a ${pick(Work.CUTOUT)}."
        if (Work.HOLES in w.work) {
            val sizes = w.holeDiameters.distinct().sorted().joinToString(", ") { d(it) }
            out += "Drill the holes${if (sizes.isNotEmpty()) " ($sizes $u)" else ""} with a ${pick(Work.HOLES)}, with a scrap board behind to stop the back splintering."
        }
        if (Work.EDGE_SHAPE in w.work) out += "Round or bevel the edges with a ${pick(Work.EDGE_SHAPE)}; test the setting on an offcut first."
        if (Work.THICKNESS in w.work) out += "Plane it down to ${d(row.t)} $u with a ${pick(Work.THICKNESS)}, taking light passes off both faces."
        for (g in grooves) {
            // "GROOVE FOR ITEM 3: 1/4 DEEP X 3/4 WIDE X 11-3/4 LONG (3 PLACES), 3, 15-3/4 AND 29-1/2 FROM THE BOTTOM END ..."
            // or "RABBET FOR ITEM 2: 1/4 DEEP X 1/4 WIDE X 45 LONG, ALONG THE BACK EDGE (AS ASSEMBLED)."
            val m = Regex("""(GROOVE|RABBET) FOR ITEM (\S+): (.+?) DEEP X (.+?) WIDE X (.+?) LONG(?: \((\d+) PLACES\))?(?:, (.*?))?\.?$""").find(g) ?: continue
            val places = m.groupValues[6].takeIf { it.isNotEmpty() }?.let { " in $it places" } ?: ""
            val where = m.groupValues[7].takeIf { it.isNotEmpty() }?.lowercase()?.let { w -> if (w.startsWith("along")) w else "at $w" } ?: "where its drawing shows"
            out += "Cut a ${m.groupValues[1].lowercase()} for item ${m.groupValues[2]}$places: ${m.groupValues[3].lowercase()} deep, ${m.groupValues[4].lowercase()} wide, ${m.groupValues[5].lowercase()} long, $where, with a ${grooveTool()}."
        }
        out += extra
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
