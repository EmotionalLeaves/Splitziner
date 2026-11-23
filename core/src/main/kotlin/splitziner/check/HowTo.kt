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
        // Glass is cut by a glass shop; nothing to make here.
        if (part.name == "Glass") return listOf("Have it cut by a glass shop, ${if (fmt.isInch) "1/16 in" else "2 mm"} under the drawn size each way, with smoothed edges; see the cutting plan's list.")
        if (part.name == "Caster") return listOf("Bought, not made: plate-mounted casters this tall overall, rated for the piece and what goes in it; two with brakes keep it still while you work.")
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
                // Sloped sides cut in nested pairs: the blank is the pair's.
                val pairBlank = row.pairW != null && row.qty > 1 && row.description.contains("SLOPED")
                out += "Cut ${if (pairBlank) "${if (row.qty / 2 == 1) "a blank" else "${row.qty / 2} blanks"} ${d(row.pairW!!)}" else "a blank ${d(row.w)}"} x ${d(row.l)} $u from ${d(row.t)} $u stock with a $tool" +
                    (if (big && tool.contains("circular")) ", clamping a straight board as a fence" else "") + (if (row.grain) ", with the grain along the ${d(row.l)} $u length" else "") + "."
            }
        }
        val bevel = Regex("""ONE EDGE BEVELLED AT (\S+)""").find(row.description)?.groupValues?.get(1)
        if (Work.ANGLE_CUT in w.work && bevel != null) {
            out += "Rip one long edge at $bevel with the saw blade tilted (on a table saw, or with a circular saw run against a straight board clamped as a fence), so the slope runs right across the thickness and the wide face stays ${d(row.w)} $u. Make a pair (a French cleat) by ripping one board down the middle at $bevel: one half goes on the wall, the other on the back of the piece, the slopes hooking together."
        } else if (Work.ANGLE_CUT in w.work) {
            val v = Regex("""ENDS CUT AT (.*)$""").find(row.description)?.groupValues?.get(1) ?: ""
            val deg = v.substringBefore(" (").lowercase()
            val saw = pick(Work.ANGLE_CUT)
            out += when {
                v.endsWith("(MITRE)") -> "Mitre both ends at $deg, opposite ways, with a $saw: cut one end, measure ${d(row.l)} $u along the long edge to the point and cut the other. Cut a test pair from scrap first and check that two make a square corner."
                v.endsWith("(BEVEL)") -> "Bevel both ends at $deg across the thickness, opposite ways, with a $saw with the blade tilted: cut one end, measure ${d(row.l)} $u along the outside face to the point and cut the other. Cut a test pair from scrap first and check that two make a square corner."
                v.contains("COMPOUND") -> "Cut both ends at ${v.substringBefore(" (").lowercase()} off square (a compound cut: set both the mitre and the bevel), parallel to each other, with a $saw; measure ${d(row.l)} $u along the long edge."
                else -> "Cut both ends at $deg off square, parallel to each other, with a $saw; measure ${d(row.l)} $u along the long edge."
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
        val taper = Regex("""TAPERED ON (.+?) TO ([^,]+), STARTING ([^ ]+) FROM THE (TOP|THICK END)""").find(row.description)
        if (taper != null) out += "Cut any joinery while it is still square. Then mark the taper on ${taper.groupValues[1].lowercase()}: full size for ${taper.groupValues[3]} $u from the ${taper.groupValues[4].lowercase()}, " +
            "down to ${taper.groupValues[2].lowercase()} $u at the other end. Saw it with a taper jig on the table saw (or on the band saw just outside the line), one face at a time, then plane to the line."
        val slope = Regex("""ONE EDGE SLOPED FROM ([^ ]+) TO ([^ ,]+)""").find(row.description)
        val pw = row.pairW
        if (slope != null && pw != null && row.qty > 1) out += "Two nest head to tail in each blank: mark ${slope.groupValues[1]} $u along one end and ${slope.groupValues[2]} $u along the other, both from the same long edge, join the marks with a straight edge and saw along the line (a circular saw against a clamped guide, or a jigsaw just outside it and plane to the line). Each half is one${if (row.qty % 2 == 1) "; cut the odd one from a blank ${d(row.w)} x ${d(row.l)} $u the same way" else ""}."
        else if (slope != null) out += "Mark the slope, measuring both from the same long edge: ${slope.groupValues[1]} $u at one end (the full width) and ${slope.groupValues[2]} $u at the other. Join the marks with a straight edge and saw along the line (a circular saw against a clamped guide, or a jigsaw just outside it and plane to the line)."
        Regex("""(ONE CORNER|\d+ CORNERS) CUT OFF ([^ ,]+)(?: X ([^ ,]+))?""").find(row.description)?.let { cm ->
            val legs = if (cm.groupValues[3].isEmpty()) "${cm.groupValues[2]} $u along both edges" else "${cm.groupValues[2]} $u along one edge and ${cm.groupValues[3]} $u along the other"
            out += "Mark ${if (cm.groupValues[1] == "ONE CORNER") "the corner to cut off" else "each corner to cut off (see the drawing for which)"}: $legs from the corner. Join the marks and saw the line, then sand the edge smooth."
        }
        if (row.description.contains("A RIGHT TRIANGLE")) out += "Draw a line corner to corner across the blank and saw along it: each blank makes two triangles${if (row.qty > 1) ", so cut ${(row.qty + 1) / 2} blank${if ((row.qty + 1) / 2 == 1) "" else "s"} for ${row.qty}" else ""}."
        val raised = Regex("""RAISED \(EDGE ([^ ]+) THK, BEVEL ([^ ]+) WIDE\)""").find(row.description)
        if (raised != null) out += "Raise the panel: cut the bevel ${raised.groupValues[2]} $u wide on all four edges of the front face, leaving the edge ${raised.groupValues[1]} $u thick to slide into the grooves. " +
            "Use a panel-raising bit in a router table, or the table saw with the blade tilted and the panel stood on edge against a tall fence. Cut the end-grain edges first, so the long-grain cuts clean up any tear-out, and test the fit in an offcut of the groove."
        else if (Work.EDGE_SHAPE in w.work) out += "Round or bevel the edges with a ${pick(Work.EDGE_SHAPE)}; test the setting on an offcut first."
        if (Work.THICKNESS in w.work) out += "Plane it down to ${d(row.t)} $u with a ${pick(Work.THICKNESS)}, taking light passes off both faces."
        val mortiseSizes = HashSet<String>()
        for (g in grooves) {
            // "GROOVE FOR ITEM 3: 1/4 DEEP X 3/4 WIDE X 11-3/4 LONG (3 PLACES), 3, 15-3/4 AND 29-1/2 FROM THE BOTTOM END ..."
            // or "RABBET FOR ITEM 2: 1/4 DEEP X 1/4 WIDE X 45 LONG, ALONG THE BACK EDGE (AS ASSEMBLED)."
            val m = Regex("""(GROOVE|RABBET|MORTISE) FOR ITEM (\S+): (.+?) DEEP X (.+?) WIDE X (.+?) LONG(?: \((\d+) PLACES\))?(?:, (.*?))?\.?$""").find(g) ?: continue
            val places = m.groupValues[6].takeIf { it.isNotEmpty() }?.let { " in $it places" } ?: ""
            // Positions carry their unit as the sizes do: "at 3 in, 15-3/4 in and 29-1/2 in from the bottom end".
            val where = m.groupValues[7].takeIf { it.isNotEmpty() }?.lowercase()?.replace(Regex("""(\d)(,| and| from)"""), "$1 $u$2")?.let { w -> if (w.startsWith("along")) w else "at $w" } ?: "where its drawing shows"
            // A second mortise of the same size (a leg's other face) is told briefly: the method was just given.
            val size = m.groupValues.subList(2, 6).joinToString("|")
            if (m.groupValues[1] == "MORTISE" && !mortiseSizes.add(size)) { out += "Cut the other mortise for item ${m.groupValues[2]}$places the same size, $where."; continue }
            out += if (m.groupValues[1] == "MORTISE") "Cut a mortise for item ${m.groupValues[2]}$places: ${m.groupValues[3].lowercase()} $u deep, ${m.groupValues[4].lowercase()} $u wide, ${m.groupValues[5].lowercase()} $u long, $where. Drill out most of it with a ${m.groupValues[4].lowercase()} $u bit, a row of holes to depth, then pare the sides and square the ends with a chisel (or rout it with a fence). Test the tenon's fit: snug by hand, not hammered."
                else "Cut a ${m.groupValues[1].lowercase()} for item ${m.groupValues[2]}$places: ${m.groupValues[3].lowercase()} $u deep, ${m.groupValues[4].lowercase()} $u wide, ${m.groupValues[5].lowercase()} $u long, $where, with a ${grooveTool()}."
        }
        out += extra
        if (row.qty > 1 && !(row.pairW != null && row.description.contains("SLOPED"))) out += "Make ${row.qty} the same; cutting them together, or setting a stop, keeps them identical."
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
