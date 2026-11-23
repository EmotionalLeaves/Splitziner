package splitziner.drawing

import splitziner.dims.LengthFormat
import splitziner.math.Vec3
import splitziner.mesh.Mesh
import splitziner.mesh.OrientedBounds
import splitziner.parts.CutListRow
import splitziner.parts.Part
import splitziner.sheet.DisplayList
import splitziner.sheet.DrawingInfo
import kotlin.math.abs

/**
 * Detail drawings: one sheet per item of the cut list, showing that part on its own with its
 * dimensions. Each is a drawing in its own right, numbered after the assembly drawing.
 */
object PartDrawings {
    /**
     * Lays a part down the way a drafter would draw it: its length across the sheet, its broad face
     * toward the viewer, its thickness seen from above and from the side. A part that sits at an
     * angle in the assembly is squared up first.
     */
    /** The mapping [squaredUp] applies to [mesh]: a point of the model to the laid-down drawing, and a direction. */
    fun squaredUpMap(mesh: Mesh): Pair<(Vec3) -> Vec3, (Vec3) -> Vec3> {
        val box = OrientedBounds.of(mesh)
        val ext = listOf(box.extents.x, box.extents.y, box.extents.z)
        val order = ext.indices.sortedByDescending { ext[it] }
        val aL = box.axes[order[0]]; val aW = box.axes[order[1]]; val aT = aW cross aL
        val c = box.center
        var x0 = Double.MAX_VALUE; var y0 = x0; var z0 = x0
        for (i in 0 until mesh.vertexCount) { val q = mesh.vertex(i) - c; x0 = minOf(x0, q dot aL); y0 = minOf(y0, q dot aT); z0 = minOf(z0, q dot aW) }
        return Pair({ v: Vec3 -> val q = v - c; Vec3((q dot aL) - x0, (q dot aT) - y0, (q dot aW) - z0) }, { d: Vec3 -> Vec3(d dot aL, d dot aT, d dot aW) })
    }

    /** The holes drilled in [p] for pegs glued into it, in the coordinates of its laid-down drawing. */
    fun pegHoles(p: Part, parts: List<Part>, housed: List<Pair<String, String>>): List<splitziner.features.Hole> {
        val byId = parts.associateBy { it.id }
        val pegs = housed.filter { it.second == p.id }.mapNotNull { byId[it.first] }.filter { it.kind == splitziner.parts.PartKind.ROD || it.name == "Peg" }
        if (pegs.isEmpty()) return emptyList()
        val (pt, dir) = squaredUpMap(p.mesh)
        val hb = p.mesh.bounds
        return pegs.map { q ->
            val b = q.mesh.bounds; val ax = (0..2).maxByOrNull { b.extents[it] }!!
            val dia = (0..2).filter { it != ax }.maxOf { b.extents[it] }
            // The hole opens on the face the peg stands out of and runs into the board.
            val outward = (b.min[ax] + b.max[ax]) / 2 >= (hb.min[ax] + hb.max[ax]) / 2
            val face = if (outward) hb.max[ax] else hb.min[ax]
            val depth = minOf(b.max[ax], hb.max[ax]) - maxOf(b.min[ax], hb.min[ax])
            val c = b.center.let { m -> Vec3(if (ax == 0) face else m.x, if (ax == 1) face else m.y, if (ax == 2) face else m.z) }
            val inward = Vec3(if (ax == 0) (if (outward) -1.0 else 1.0) else 0.0, if (ax == 1) (if (outward) -1.0 else 1.0) else 0.0, if (ax == 2) (if (outward) -1.0 else 1.0) else 0.0)
            splitziner.features.Hole(pt(c), dir(inward), dia, depth, false)
        }
    }

    fun squaredUp(mesh: Mesh): Mesh {
        val box = OrientedBounds.of(mesh)
        val ext = listOf(box.extents.x, box.extents.y, box.extents.z)
        val order = ext.indices.sortedByDescending { ext[it] }          // length, width, thickness
        val aL = box.axes[order[0]]; val aW = box.axes[order[1]]
        // Front view looks along Y, so: length -> X, thickness -> Y, width -> Z. Y is derived to keep the part from being mirrored.
        val aT = aW cross aL
        val c = box.center
        val v = DoubleArray(mesh.vertices.size)
        for (i in 0 until mesh.vertexCount) {
            val p = mesh.vertex(i) - c
            v[3 * i] = p dot aL; v[3 * i + 1] = p dot aT; v[3 * i + 2] = p dot aW
        }
        // Start the part at the origin so its dimensions read from zero.
        var x0 = Double.MAX_VALUE; var y0 = x0; var z0 = x0
        for (i in 0 until mesh.vertexCount) { x0 = minOf(x0, v[3 * i]); y0 = minOf(y0, v[3 * i + 1]); z0 = minOf(z0, v[3 * i + 2]) }
        for (i in 0 until mesh.vertexCount) { v[3 * i] -= x0; v[3 * i + 1] -= y0; v[3 * i + 2] -= z0 }
        return Mesh(v, mesh.triangles)
    }

    /**
     * Items that are plain square boards or panels: no notch, hole or shaping. The list of material gives
     * everything needed to cut them, so a sheet each would only repeat three numbers. Shops do not draw these.
     */
    fun plainItems(parts: List<Part>, rows: List<CutListRow>, fmt: LengthFormat): Set<Int> {
        val byId = parts.associateBy { it.id }
        return rows.filter { row ->
            if (row.kind != splitziner.parts.PartKind.BOARD && row.kind != splitziner.parts.PartKind.PANEL) return@filter false
            val p = row.memberIds.firstNotNullOfOrNull { byId[it] } ?: return@filter false
            val d = runCatching { splitziner.parts.PartDescriber.describe(p.mesh, fmt) }.getOrNull() ?: return@filter false
            p.mesh.isWatertight && !d.desc.contains("NOTCHED") && !d.desc.contains("HOLE") && !d.desc.contains("ENDS CUT") && !d.desc.contains("EDGE BEVELLED") && !d.desc.contains("RAISED") && !d.desc.contains("TAPERED") && !d.desc.contains("SLOPED")
        }.map { it.item }.toSet() - pinItems(parts) - doorItems(parts)
    }

    /** Items bought, not made (casters): no detail drawing. */
    fun boughtItems(parts: List<Part>): Set<Int> = parts.filter { !it.excluded && it.name == "Caster" }.mapNotNull { it.item }.toSet()

    /** Items that are doors: these get a detail sheet giving where the hinge cups go. */
    fun doorItems(parts: List<Part>): Set<Int> {
        val byId = parts.associateBy { it.id }
        return runCatching { splitziner.check.Doors.find(parts).mapNotNull { byId[it.id]?.item }.toSet() }.getOrDefault(emptySet())
    }

    /**
     * Where the hinge cups go in door [p]. The positions are the usual workshop practice (cups about 100 mm in from
     * each end, any more evenly between); the cup's distance from the edge and its depth belong to the hinge
     * bought, so they are given as typical values to check against its template.
     */
    /** Typical cup edge distance and depth (mm), drawn to show where the cups go; the hinges' template rules. */
    private const val CUP_IN = 22.0
    private const val CUP_DEEP = 13.0
    private const val CUP_DIA = 35.0

    /** How far a cup's centre is from each end of the door (mm), the usual workshop practice. */
    private fun cupEnd(height: Double) = minOf(100.0, height / 5)

    /**
     * The hinge cups of door [p] in the coordinates of its laid-down drawing [laid]: along one long edge (the
     * hinge edge, shown on the drawing's lower or left edge), drilled into the back face.
     */
    fun hingeCups(p: Part, parts: List<Part>, laid: Mesh): List<splitziner.features.Hole> {
        val door = runCatching { splitziner.check.Doors.find(parts) }.getOrDefault(emptyList()).firstOrNull { it.id == p.id } ?: return emptyList()
        val n = splitziner.check.Doors.hinges(door.height)
        val b = laid.bounds
        val e = cupEnd(door.height)
        // The door's height is the laid X when it is the longer side, else Z.
        val alongX = kotlin.math.abs(b.extents.x - door.height) <= kotlin.math.abs(b.extents.z - door.height)
        val len = if (alongX) b.extents.x else b.extents.z
        val at = (0 until n).map { k -> e + k * (len - 2 * e) / (n - 1) }
        val back = b.max.y
        return at.map { a ->
            val c = if (alongX) splitziner.math.Vec3(b.min.x + a, back, b.min.z + CUP_IN) else splitziner.math.Vec3(b.min.x + CUP_IN, back, b.min.z + a)
            splitziner.features.Hole(c, splitziner.math.Vec3(0.0, -1.0, 0.0), CUP_DIA, CUP_DEEP, false)
        }
    }

    fun hingeNotes(p: Part, parts: List<Part>, fmt: LengthFormat): List<String> {
        val door = runCatching { splitziner.check.Doors.find(parts) }.getOrDefault(emptyList()).firstOrNull { it.id == p.id } ?: return emptyList()
        val n = splitziner.check.Doors.hinges(door.height)
        val end = cupEnd(door.height)
        val d = { v: Double -> fmt.format(v) }
        val between = if (n > 2) ", ${n - 2} MORE EVENLY BETWEEN" else ""
        // A frame-and-panel door takes its cups in one stile, the one on its hinge side.
        val framed = runCatching { splitziner.check.FramePanels.find(parts) }.getOrDefault(emptyList())
        if (framed.any { p.id in it.stiles }) {
            val nDoors = framed.count { g -> parts.firstOrNull { it.id == g.stiles[0] }?.item == p.item }
            val nStiles = parts.count { !it.excluded && it.item == p.item }
            return listOf("HINGE CUPS FOR $n CONCEALED HINGES: 35 MM DIAMETER IN THE BACK FACE ALONG THE OUTER EDGE, CENTRES ${d(end)} FROM EACH END$between, " +
                "IN THE HINGE-SIDE STILE OF EACH DOOR ONLY" + (if (nStiles > nDoors) " ($nDoors OF THE $nStiles)" else "") + ". " +
                "THE CUPS ARE DRAWN AT THE USUAL 22 MM IN FROM THE EDGE AND 13 MM DEEP: CHECK BOTH AGAINST THE HINGES' TEMPLATE BEFORE DRILLING. DO NOT DRILL THROUGH." +
                (if (nDoors > 1) " A PAIR OF DOORS IS MADE ONE OF EACH HAND." else ""))
        }
        return listOf("HINGE CUPS FOR $n CONCEALED HINGES: 35 MM DIAMETER IN THE BACK FACE ALONG THE HINGE EDGE, CENTRES ${d(end)} FROM EACH END$between. " +
            "THE CUPS ARE DRAWN AT THE USUAL 22 MM IN FROM THE EDGE AND 13 MM DEEP: CHECK BOTH AGAINST THE HINGES' TEMPLATE BEFORE DRILLING. DO NOT DRILL THROUGH. A PAIR OF DOORS IS MADE ONE OF EACH HAND.")
    }

    /** Items drilled for shelf pins: these always get a detail sheet, which gives the holes. */
    fun pinItems(parts: List<Part>): Set<Int> {
        val byId = parts.associateBy { it.id }
        return runCatching { splitziner.check.ShelfPins.uprights(parts).keys.mapNotNull { byId[it]?.item }.toSet() }.getOrDefault(emptySet())
    }

    /**
     * Notes for the shelf-pin holes in upright [p]: two rows in the face toward the shelves, in the common 32 mm
     * system, running from three holes below the lowest shelf to three above the highest so each can move.
     */
    fun pinNotes(p: Part, parts: List<Part>, fmt: LengthFormat): List<String> {
        val ups = runCatching { splitziner.check.ShelfPins.uprights(parts) }.getOrDefault(emptyMap())
        val byId = parts.associateBy { it.id }
        val shelves = ups[p.id]?.mapNotNull { byId[it] } ?: return emptyList()
        if (shelves.isEmpty()) return emptyList()
        val u = p.mesh.bounds; val d = { v: Double -> fmt.format(v) }
        val pitch = splitziner.check.ShelfPins.PITCH
        val lo = maxOf(u.min.z + pitch, shelves.minOf { it.mesh.bounds.min.z } - 3 * pitch)
        val hi = minOf(u.max.z - pitch, shelves.maxOf { it.mesh.bounds.min.z } + 3 * pitch)
        val n = ((hi - lo) / pitch).toInt() + 1
        // A divider with shelves both sides is drilled in both faces (the rows offset, or the holes meet).
        val both = shelves.any { it.mesh.bounds.center.x > u.center.x } && shelves.any { it.mesh.bounds.center.x < u.center.x }
        val where = if (both) "4 ROWS OF $n, 2 IN EACH FACE (OFFSET ONE FACE'S ROWS SO THE HOLES DO NOT MEET)" else "2 ROWS OF $n IN THE FACE TOWARD THE SHELVES"
        return listOf("SHELF PIN HOLES FOR ITEM ${shelves.mapNotNull { it.item }.distinct().joinToString(", ")}: $where, " +
            "${splitziner.check.ShelfPins.SETBACK.toInt()} MM FROM THE FRONT AND BACK EDGES, ${pitch.toInt()} MM APART, THE FIRST ${d(lo - u.min.z)} FROM THE BOTTOM END AS ASSEMBLED. " +
            "DIAMETER AND DEPTH TO SUIT THE PINS (5 MM DIAMETER IS COMMON). NOT THROUGH.")
    }

    /** Item numbers in ranges: "3-5, 9". */
    private fun runs(items: Set<Int>): List<String> {
        val sorted = items.sorted(); val runs = ArrayList<String>()
        var a = sorted[0]; var b = a
        for (i in sorted.drop(1)) { if (i == b + 1) b = i else { runs += if (a == b) "$a" else "$a-$b"; a = i; b = i } }
        runs += if (a == b) "$a" else "$a-$b"
        return runs
    }

    /**
     * A general note for the assembly drawing giving the angle of every item set off upright (a bench's leaned-back
     * slats and supports, a stool's splayed legs), which the views show but do not dimension. Null when none is.
     */
    fun leanNote(parts: List<Part>): String? {
        val byAngle = java.util.TreeMap<String, java.util.TreeSet<Int>>()
        for (p in parts) {
            if (p.excluded || p.item == null) continue
            val obb = runCatching { splitziner.mesh.OrientedBounds.of(p.mesh) }.getOrNull() ?: continue
            // Boards only: a rod's or a round top's box can turn any way about it.
            if (kotlin.math.abs(p.mesh.volume) < 0.9 * obb.volume) continue
            val up = obb.axes.maxByOrNull { kotlin.math.abs(it.z) }!!.let { if (it.z < 0) it * -1.0 else it }
            val deg = Math.toDegrees(kotlin.math.acos(up.z.coerceIn(-1.0, 1.0)))
            if (deg < 1.0 || deg > 45.0) continue
            val way = when {
                kotlin.math.abs(up.x) > 0.05 && kotlin.math.abs(up.y) > 0.05 -> "SPLAYED"
                up.y > 0.05 -> "LEANING BACK"; up.y < -0.05 -> "LEANING FORWARD"
                up.x > 0.05 -> "LEANING RIGHT"; else -> "LEANING LEFT"
            }
            val d = Math.round(deg * 2) / 2.0
            byAngle.getOrPut("${if (d == Math.floor(d)) "${d.toInt()}" else "$d"}° OFF UPRIGHT, $way") { java.util.TreeSet() } += p.item
        }
        if (byAngle.isEmpty()) return null
        return byAngle.entries.joinToString(" ") { (what, items) ->
            val rs = runs(items)
            (if (items.size == 1) "ITEM ${items.first()} IS SET " else "ITEMS ${if (rs.size == 2) rs.joinToString(" AND ") else rs.joinToString(", ")} ARE SET ") + "$what."
        } + " SEE THE SIDE VIEW."
    }

    /** A general note for the assembly drawing naming the items that have no detail drawing, in ranges ("3-5, 9"). */
    fun plainNote(items: Set<Int>): String? {
        if (items.isEmpty()) return null
        val runs = runs(items)
        val which = if (items.size == 1) "ITEM ${runs[0]} IS A PLAIN SQUARE BOARD" else "ITEMS ${runs.joinToString(", ")} ARE PLAIN SQUARE BOARDS"
        return "$which: CUT TO THE SIZES IN THE LIST OF MATERIAL. NO DETAIL DRAWING."
    }

    /**
     * The other half of [plainNote]: which items have a detail drawing and what it is numbered, so a drawing set
     * of many sheets can be found through. Detail drawings take the assembly's number and the item's ("DESK-1-13").
     */
    /** The end cuts of a stick sawn off square (a splayed leg), as the saw is set: from the cut list's description. */
    /** A round part is marked with a trammel from its centre, so the drawing gives the radius to swing. */
    private fun circleNotes(p: Part, fmt: LengthFormat): List<String> {
        val d = runCatching { FullSize.circleDiameter(p.mesh) }.getOrNull()
        if (d != null) return listOf("CIRCLE ${fmt.format(d)} DIA. MARK FROM THE CENTRE WITH A TRAMMEL SET TO ${fmt.format(d / 2)} RADIUS; NO TEMPLATE NEEDED.")
        // An ellipse is drawn with a string round two nails at its foci (the gardener's method).
        val (a, b) = runCatching { FullSize.oval(p.mesh) }.getOrNull() ?: return emptyList()
        val f = Math.sqrt(a * a - b * b) / 2
        return listOf("ELLIPSE ${fmt.format(a)} X ${fmt.format(b)}. MARK WITH NAILS ON THE LONG CENTRE LINE ${fmt.format(f)} EACH SIDE OF THE CENTRE AND A STRING ${fmt.format(a)} LONG TIED BETWEEN THEM, PENCIL HELD AGAINST THE STRING; NO TEMPLATE NEEDED.")
    }

    private fun bevelNotes(row: CutListRow, fmt: LengthFormat): List<String> {
        Regex("""TAPERED ON (.+?) TO ([^,]+), STARTING ([^ ]+) FROM THE (TOP|THICK END)""").find(row.description)?.let { m ->
            return listOf("TAPERED ON ${m.groupValues[1]} (SEE THE SIDE VIEWS): FULL SIZE FOR ${m.groupValues[3]} FROM THE ${m.groupValues[4]}, THEN STRAIGHT DOWN TO ${m.groupValues[2]} AT THE OTHER END. " +
                "ON A LEG THE TAPERS GO ON THE INSIDE FACES, SO IT LOOKS STRAIGHT FROM OUTSIDE. CUT THE JOINERY (MORTISES, POCKET HOLES) WHILE THE LEG IS STILL SQUARE.")
        }
        Regex("""RAISED \(EDGE ([^ ]+) THK, BEVEL ([^ ]+) WIDE\)""").find(row.description)?.let { m ->
            return listOf("RAISED PANEL: FLAT BACK; FRONT BEVELLED ${m.groupValues[2]} WIDE ALL ROUND DOWN TO AN EDGE ${m.groupValues[1]} THICK, WHICH FITS THE FRAME'S GROOVES (SEE THE EDGE VIEWS). " +
                "CUT THE END-GRAIN EDGES FIRST. LEAVE ABOUT ${if (fmt.isInch) "1/16 IN" else "2 MM"} SPARE IN EACH GROOVE ACROSS THE GRAIN SO A SOLID-WOOD PANEL CAN SWELL.")
        }
        val deg = Regex("""ONE EDGE BEVELLED AT (\S+)""").find(row.description)?.groupValues?.get(1) ?: return emptyList()
        return listOf("ONE LONG EDGE BEVELLED AT $deg OFF SQUARE, RIGHT ACROSS THE THICKNESS (SEE THE END VIEW). RIP IT WITH THE BLADE TILTED; THE WIDTH IS MEASURED ON THE WIDE FACE.")
    }

    private fun angleNotes(row: CutListRow): List<String> {
        val m = Regex("""ENDS CUT AT (.*)$""").find(row.description) ?: return emptyList()
        val v = m.groupValues[1]; val deg = v.substringBefore(" (")
        return listOf(when {
            v.endsWith("(MITRE)") -> "BOTH ENDS MITRED AT $deg, OPPOSITE WAYS. SET THE MITRE ONLY. LENGTH IS MEASURED ALONG THE LONG EDGE, POINT TO POINT."
            v.endsWith("(BEVEL)") -> "BOTH ENDS BEVELLED AT $deg ACROSS THE THICKNESS, OPPOSITE WAYS (A MITRED CORNER). TILT THE BLADE ONLY. LENGTH IS MEASURED ON THE OUTSIDE FACE, POINT TO POINT."
            v.contains("COMPOUND") -> "BOTH ENDS CUT AT $v OFF SQUARE, PARALLEL TO EACH OTHER. SET BOTH THE MITRE AND THE BEVEL. LENGTH IS MEASURED ALONG THE LONG EDGE."
            else -> "BOTH ENDS CUT AT $v OFF SQUARE, PARALLEL TO EACH OTHER. SET THE MITRE ONLY. LENGTH IS MEASURED ALONG THE LONG EDGE."
        })
    }

    fun detailNote(items: Set<Int>, drawingNo: String): String? {
        if (items.isEmpty()) return null
        val base = drawingNo.ifBlank { "DWG" }
        if (items.size <= 6) return "DETAIL DRAWING${if (items.size == 1) "" else "S"}: " + items.sorted().joinToString(", ") { "ITEM $it ON $base-$it" } + "."
        return "DETAIL DRAWINGS FOR ITEMS ${runs(items).joinToString(", ")}: NUMBERED $base- AND THE ITEM NUMBER (ITEM ${items.min()} ON $base-${items.min()})."
    }

    fun build(parts: List<Part>, rows: List<CutListRow>, info: DrawingInfo, settings: DrawingSettings, fmt: LengthFormat): List<DrawingResult> =
        items(parts, rows, info, settings, fmt, emptySet()).map { it.second }

    /** Items with a groove cut for a housed part: these always get a detail sheet, which gives the groove. */
    fun groovedItems(parts: List<Part>, housed: List<Pair<String, String>>): Set<Int> {
        val byId = parts.associateBy { it.id }
        return housed.mapNotNull { byId[it.second]?.item }.toSet()
    }

    /** Notes for the grooves in [p]: one per part housed in it, sized from where the two overlap in the model. */
    /**
     * The part with the grooves that housed parts sit in cut away, for drawing. Only grooves running right through
     * the part one way (dados and rabbets cut end to end) are cut, along the direction most of them run; others are
     * left to the notes. Null when there is nothing to cut.
     */
    /** Glass sits in a rabbet: its seat runs out to the strip's back face, the nearer one, not a groove inside it. */
    private fun glassSeat(q: Part, a: splitziner.math.Aabb, b: splitziner.math.Aabb): splitziner.math.Aabb {
        if (q.name != "Glass") return b
        val k = (0..2).minByOrNull { b.extents[it] }!!
        val toMax = a.max[k] - b.max[k]; val toMin = b.min[k] - a.min[k]
        fun with(v: Vec3, x: Double) = Vec3(if (k == 0) x else v.x, if (k == 1) x else v.y, if (k == 2) x else v.z)
        return if (toMax <= toMin) splitziner.math.Aabb(b.min, with(b.max, a.max[k])) else splitziner.math.Aabb(with(b.min, a.min[k]), b.max)
    }

    fun carveGrooves(p: Part, parts: List<Part>, housed: List<Pair<String, String>>): splitziner.mesh.Mesh? {
        val byId = parts.associateBy { it.id }
        val a = p.mesh.bounds
        val boxes = housed.filter { it.second == p.id }.mapNotNull { (pid, _) ->
            val b = byId[pid]?.let { glassSeat(it, a, it.mesh.bounds) } ?: return@mapNotNull null
            val lo = splitziner.math.Vec3(maxOf(a.min.x, b.min.x), maxOf(a.min.y, b.min.y), maxOf(a.min.z, b.min.z))
            val hi = splitziner.math.Vec3(minOf(a.max.x, b.max.x), minOf(a.max.y, b.max.y), minOf(a.max.z, b.max.z))
            if ((0..2).any { hi[it] <= lo[it] }) null else lo to hi
        }
        if (boxes.isEmpty()) return null
        // The direction each runs right through the part.
        // (A dado that stops at a rabbet along the back edge runs into it: through, for the drawing, within 1/2 in.)
        fun through(bx: Pair<splitziner.math.Vec3, splitziner.math.Vec3>) = (0..2).firstOrNull { k -> bx.first[k] <= a.min[k] + 13.0 && bx.second[k] >= a.max[k] - 13.0 && a.extents[k] > 3 * (bx.second - bx.first).let { minOf(it.x, it.y, it.z) } }
        val axis = boxes.mapNotNull { through(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val cut = boxes.filter { through(it) == axis }
        val dir = splitziner.math.Vec3.axis(axis)
        val prism = splitziner.edit.Prisms.along(p.mesh, dir, splitziner.math.Vec3.axis((axis + 1) % 3))
        // Only a plain prism along that way (a board's section), so cutting its outline is exact.
        if (kotlin.math.abs(prism.outline.area * prism.thickness - kotlin.math.abs(p.mesh.volume)) > 0.02 * kotlin.math.abs(p.mesh.volume)) return null
        val gf = org.locationtech.jts.geom.GeometryFactory()
        var outline = prism.outline
        for ((lo, hi) in cut) {
            val corners = listOf(lo, hi, splitziner.math.Vec3(lo.x, hi.y, lo.z), splitziner.math.Vec3(hi.x, lo.y, hi.z), splitziner.math.Vec3(lo.x, lo.y, hi.z), splitziner.math.Vec3(hi.x, hi.y, lo.z), splitziner.math.Vec3(hi.x, lo.y, lo.z), splitziner.math.Vec3(lo.x, hi.y, hi.z))
            val px = corners.map { it dot prism.view.x }; val py = corners.map { it dot prism.view.up }
            val env = org.locationtech.jts.geom.Envelope(px.min(), px.max(), py.min(), py.max())
            outline = outline.difference(gf.toGeometry(env))
        }
        return splitziner.edit.Prisms.toMesh(prism.with(outline = outline))
    }

    /**
     * A plain block with blind pockets cut into its faces (a leg's mortises), as a mesh, so its drawing shows
     * them: hidden lines where they are out of sight. Null unless the part is a plain box and each pocket opens
     * on one face only, well inside its edges, one per face.
     */
    fun pocketed(p: Part, parts: List<Part>, housed: List<Pair<String, String>>): splitziner.mesh.Mesh? {
        val a = p.mesh.bounds
        if (kotlin.math.abs(kotlin.math.abs(p.mesh.volume) - a.extents.x * a.extents.y * a.extents.z) > 0.01 * a.extents.x * a.extents.y * a.extents.z) return null
        val byId = parts.associateBy { it.id }
        class Pk(val k: Int, val sign: Int, val lo: splitziner.math.Vec3, val hi: splitziner.math.Vec3)
        val pockets = housed.filter { it.second == p.id }.mapNotNull { (pid, _) ->
            val b = byId[pid]?.let { glassSeat(it, a, it.mesh.bounds) } ?: return@mapNotNull null
            val lo = splitziner.math.Vec3(maxOf(a.min.x, b.min.x), maxOf(a.min.y, b.min.y), maxOf(a.min.z, b.min.z))
            val hi = splitziner.math.Vec3(minOf(a.max.x, b.max.x), minOf(a.max.y, b.max.y), minOf(a.max.z, b.max.z))
            if ((0..2).any { hi[it] <= lo[it] + 0.1 }) return@mapNotNull null
            // Opening on exactly one face; inside the other faces by a margin.
            val opens = (0..2).flatMap { k -> listOfNotNull(if (kotlin.math.abs(hi[k] - a.max[k]) < 0.5) k to 1 else null, if (kotlin.math.abs(lo[k] - a.min[k]) < 0.5) k to -1 else null) }
            if (opens.size != 1) return@mapNotNull null
            Pk(opens[0].first, opens[0].second, lo, hi)
        }
        if (pockets.isEmpty() || pockets.size != housed.count { it.second == p.id }) return null
        if (pockets.groupBy { it.k to it.sign }.any { it.value.size > 1 }) return null
        val verts = ArrayList<Double>(); val tris = ArrayList<Int>(); val index = HashMap<Triple<Long, Long, Long>, Int>()
        fun v(x: splitziner.math.Vec3): Int = index.getOrPut(Triple(Math.round(x.x * 1000), Math.round(x.y * 1000), Math.round(x.z * 1000))) { verts += x.x; verts += x.y; verts += x.z; verts.size / 3 - 1 }
        fun tri(p0: splitziner.math.Vec3, p1: splitziner.math.Vec3, p2: splitziner.math.Vec3, n: splitziner.math.Vec3) {
            val c = (p1 - p0) cross (p2 - p0)
            if (c.length < 1e-9) return
            if ((c dot n) >= 0) { tris += v(p0); tris += v(p1); tris += v(p2) } else { tris += v(p0); tris += v(p2); tris += v(p1) }
        }
        fun quad(q: List<splitziner.math.Vec3>, n: splitziner.math.Vec3) { tri(q[0], q[1], q[2], n); tri(q[0], q[2], q[3], n) }
        fun pt(k: Int, c: Double, u: Double, w: Double): splitziner.math.Vec3 { val arr = DoubleArray(3); arr[k] = c; arr[(k + 1) % 3] = u; arr[(k + 2) % 3] = w; return splitziner.math.Vec3(arr[0], arr[1], arr[2]) }
        for (k in 0..2) for (sign in intArrayOf(-1, 1)) {
            val c = if (sign > 0) a.max[k] else a.min[k]; val n = splitziner.math.Vec3.axis(k, sign.toDouble())
            val ku = (k + 1) % 3; val kw = (k + 2) % 3
            val outer = listOf(pt(k, c, a.min[ku], a.min[kw]), pt(k, c, a.max[ku], a.min[kw]), pt(k, c, a.max[ku], a.max[kw]), pt(k, c, a.min[ku], a.max[kw]))
            val pk = pockets.firstOrNull { it.k == k && it.sign == sign }
            if (pk == null) { quad(outer, n); continue }
            val inner = listOf(pt(k, c, pk.lo[ku], pk.lo[kw]), pt(k, c, pk.hi[ku], pk.lo[kw]), pt(k, c, pk.hi[ku], pk.hi[kw]), pt(k, c, pk.lo[ku], pk.hi[kw]))
            // The face round the opening, as four trapezoids corner to corner (no T-junctions).
            for (i in 0..3) quad(listOf(outer[i], outer[(i + 1) % 4], inner[(i + 1) % 4], inner[i]), n)
            // The pocket: its floor faces out of the opening, its walls face into the hole.
            val f = if (sign > 0) pk.lo[k] else pk.hi[k]
            val floor = inner.map { q -> pt(k, f, q[ku], q[kw]) }
            quad(floor, n)
            val centre = (pk.lo + pk.hi) * 0.5
            for (i in 0..3) {
                val w = listOf(inner[i], inner[(i + 1) % 4], floor[(i + 1) % 4], floor[i])
                val mid = (w[0] + w[1] + w[2] + w[3]) * 0.25
                quad(w, pt(k, 0.0, centre[ku] - mid[ku], centre[kw] - mid[kw]))
            }
        }
        return splitziner.mesh.Mesh(verts.toDoubleArray(), tris.toIntArray())
    }

    fun grooveNotes(p: Part, parts: List<Part>, housed: List<Pair<String, String>>, fmt: LengthFormat): List<String> {
        val byId = parts.associateBy { it.id }
        val d = { v: Double -> fmt.format(v) }
        class G(val text: String, val at: Double?, val from: String, val extra: String = "")
        val gs = housed.filter { it.second == p.id }.mapNotNull { (pid, _) ->
            val q = byId[pid] ?: return@mapNotNull null
            // A raised panel sits in the groove by its thin edge, not its whole thickness.
            val a = p.mesh.bounds; val b = q.mesh.bounds.let { qb -> val qk = (0..2).minByOrNull { qb.extents[it] }!!
                splitziner.check.FramePanels.edgeRange(q, qk)?.takeIf { (lo, hi) -> hi - lo < qb.extents[qk] - 0.5 }?.let { (lo, hi) ->
                    fun with(v: Vec3, x: Double) = Vec3(if (qk == 0) x else v.x, if (qk == 1) x else v.y, if (qk == 2) x else v.z)
                    splitziner.math.Aabb(with(qb.min, lo), with(qb.max, hi)) } ?: qb }.let { glassSeat(q, a, it) }
            val e = doubleArrayOf(minOf(a.max.x, b.max.x) - maxOf(a.min.x, b.min.x), minOf(a.max.y, b.max.y) - maxOf(a.min.y, b.min.y), minOf(a.max.z, b.max.z) - maxOf(a.min.z, b.min.z))
            if (e.any { it <= 0 }) return@mapNotNull null
            // The overlap is the groove. Its depth runs along the direction the housed part sticks out of this one;
            // of the other two sizes the smaller is the groove's width, the larger its length.
            val qe = doubleArrayOf(b.extents.x, b.extents.y, b.extents.z)
            val out = (0..2).filter { e[it] < qe[it] - 0.01 }
            val k = out.minByOrNull { e[it] } ?: (0..2).minByOrNull { e[it] }!!
            val rest = (0..2).filter { it != k }.sortedBy { e[it] }
            // Where it is: from the part's lower end (or edge) to the near side of the groove, across its width.
            val w = rest[0]; val lo = maxOf(a.min[w], b.min[w]) - a.min[w]
            val atEdge = lo < 0.5 || a.max[w] - (lo + a.min[w] + e[w]) < 0.5
            val atMin = lo < 0.5
            val edgeName = when (w) { 2 -> if (atMin) "BOTTOM END" else "TOP END"; 1 -> if (atMin) "FRONT EDGE" else "BACK EDGE"; else -> if (atMin) "LEFT EDGE" else "RIGHT EDGE" }
            val from = if (w == 2) "THE BOTTOM END" else if (a.extents[w] >= maxOf(a.extents.x, a.extents.y, a.extents.z) - 0.5) "ONE END" else "THE FRONT EDGE"
            // A peg sits in a round hole drilled into the face.
            if (q.kind == splitziner.parts.PartKind.ROD || q.name == "Peg") {
                val ax = (0..2).maxByOrNull { b.extents[it] }!!
                val dia = (0..2).filter { it != ax }.maxOf { b.extents[it] }
                // Measured along the board's length (u) and across it (v).
                val (u, v) = (0..2).filter { it != ax }.sortedByDescending { a.extents[it] }
                val cu = (b.min[u] + b.max[u]) / 2 - a.min[u]; val cv = (b.min[v] + b.max[v]) / 2 - a.min[v]
                fun nm(k: Int) = if (k == 2) "THE BOTTOM EDGE" else if (k == 1) "THE FRONT EDGE" else "THE LEFT END"
                return@mapNotNull G("HOLE FOR ITEM ${q.item}: ${d(dia)} DIA X ${d(e[ax])} DEEP", cu, nm(u), ", ${d(cv)} FROM ${nm(v)}")
            }
            // A tenon sits in a mortise: a slot closed at both ends, opening on the face the tenon is flush with.
            if (q.name == "Tenon") {
                val mk = (0..2).firstOrNull { ax -> kotlin.math.abs(b.max[ax] - a.max[ax]) < 0.5 || kotlin.math.abs(b.min[ax] - a.min[ax]) < 0.5 } ?: k
                val (mw, ml) = (0..2).filter { it != mk }.sortedBy { e[it] }
                // Where: across the face from its edge, and along it from the end.
                val acrossFrom = if (mw == 2) "THE BOTTOM END" else if (mw == 1) "THE FRONT EDGE" else "THE LEFT EDGE"
                val alongTop = a.max[ml] - b.max[ml]
                val alongName = if (ml == 2) "THE TOP END" else if (ml == 1) "THE BACK EDGE" else "THE RIGHT EDGE"
                return@mapNotNull G("MORTISE FOR ITEM ${q.item}: ${d(e[mk])} DEEP X ${d(e[mw])} WIDE X ${d(e[ml])} LONG", b.min[mw] - a.min[mw], acrossFrom, ", ITS END ${d(alongTop)} FROM $alongName")
            }
            G("${if (atEdge) "RABBET" else "GROOVE"} FOR ITEM ${q.item}: ${d(e[k])} DEEP X ${d(e[w])} WIDE X ${d(e[rest[1]])} LONG", if (atEdge) null else lo, if (atEdge) edgeName else from)
        }
        // Alike only when cut the same and measured from the same edge (a leg's two mortises face different ways).
        return gs.groupBy { Triple(it.text, it.from, it.extra) }.map { (key, g) -> val t = key.first
            val ats = g.mapNotNull { it.at }.distinct().sorted()
            val where = if (t.startsWith("HOLE")) "CENTRES ${ats.dropLast(1).joinToString(", ") { d(it) }}${if (ats.size > 1) " AND " else ""}${d(ats.last())} FROM ${g[0].from} (AS ASSEMBLED)"
                else if (ats.isEmpty()) "ALONG THE ${g[0].from} (AS ASSEMBLED)"
                else "${ats.dropLast(1).joinToString(", ") { d(it) }}${if (ats.size > 1) " AND " else ""}${d(ats.last())} FROM ${g[0].from} (AS ASSEMBLED) TO THE NEAR SIDE OF ${if (ats.size > 1) "EACH" else "THE"} ${if (t.startsWith("MORTISE")) "MORTISE" else "GROOVE"}"
            "$t${if (g.size > 1) " (${g.size} PLACES)" else ""}, $where${g[0].extra}."
        }
    }

    /** Detail drawings for every item except those in [skip], each with the row it details. */
    fun items(parts: List<Part>, rows: List<CutListRow>, info: DrawingInfo, settings: DrawingSettings, fmt: LengthFormat, skip: Set<Int>, housed: List<Pair<String, String>> = emptyList()): List<Pair<CutListRow, DrawingResult>> {
        val byId = parts.associateBy { it.id }
        val out = ArrayList<Pair<CutListRow, DrawingResult>>()
        for (row in rows) {
            if (row.item in skip || row.item in boughtItems(parts)) continue
            val p = row.memberIds.firstNotNullOfOrNull { byId[it] } ?: continue
            // Grooves for housed parts are cut into the drawn part, so the views show them and the dimensions place them.
            val carved = runCatching { carveGrooves(p, parts, housed) }.getOrNull() ?: runCatching { pocketed(p, parts, housed) }.getOrNull() ?: p.mesh
            val laid = p.copy(mesh = squaredUp(carved))
            val vs = DrawingBuilder.views(listOf(laid), settings.sections)
            val base = info.drawingNo.ifBlank { "DWG" }
            val itemInfo = info.copy(
                title = "${info.title.ifBlank { "PART" }} — ITEM ${row.item}",
                drawingNo = "$base-${row.item}",
                notes = listOf("ITEM ${row.item} OF ${info.drawingNo.ifBlank { "THE ASSEMBLY DRAWING" }}. QUANTITY REQUIRED: ${row.qty}.") + outlineNotes(laid.mesh, fmt) + circleNotes(p, fmt) + angleNotes(row) + bevelNotes(row, fmt) + pinNotes(p, parts, fmt) + hingeNotes(p, parts, fmt) + grooveNotes(row.memberIds.firstNotNullOfOrNull { id -> byId[id]?.takeIf { g -> housed.any { it.second == g.id } } } ?: p, parts, housed, fmt) + info.notes,
            )
            // A detail drawing rarely needs more than a small sheet; a pinned sheet size applies to the assembly only.
            val r = DrawingBuilder.build(listOf(laid), listOf(row), vs, itemInfo, settings.copy(sheet = if (settings.sheet == "AB") "AB" else "auto"), fmt, extraHoles = hingeCups(p, parts, laid.mesh) + runCatching { pegHoles(p, parts, housed) }.getOrDefault(emptyList()), tags = runCatching { openingTags(laid.mesh) }.getOrDefault(emptyList()))
            r.sheets.forEach { it.qa.clear() }
            out += row to DrawingResult(r.sheets.map { rename(it, "item%02d".format(row.item)) }, r.sheet, r.scale, r.scaleText, r.qa.map { "Item ${row.item}: $it" })
        }
        return out
    }

    /**
     * What a maker needs to cut a flat part's outline that the views do not dimension: the corner radius, and where
     * each opening is and how big. Read from a slice through the middle of the part's thickness, in the front view's
     * terms (from the lower left corner of the part as drawn). Openings are given by the box around them.
     */
    /** Where each lettered opening of a flat part is, so the drawing can mark it with its letter ("A", "B"...). */
    fun openingTags(laid: Mesh): List<Triple<String, Vec3, Double>> {
        val ext = laid.extents
        if (ext.y > 0.35 * minOf(ext.x, ext.z)) return emptyList()
        val loops = runCatching { splitziner.section.Slicer.sectionLoops(laid, Vec3(0.0, ext.y / 2, 0.0), Vec3(0.0, 1.0, 0.0)) }.getOrNull() ?: return emptyList()
        if (loops.size < 3) return emptyList()
        fun area(l: List<splitziner.math.Vec2>) = abs(splitziner.section.Slicer.loopArea(l))
        val outer = loops.maxByOrNull { area(it) }!!
        val ou0 = outer.minOf { it.x }; val ou1 = outer.maxOf { it.x }; val ov0 = outer.minOf { it.y }; val ov1 = outer.maxOf { it.y }
        val holes = loops.filter { it !== outer && it.all { p -> p.x > ou0 && p.x < ou1 && p.y > ov0 && p.y < ov1 } && area(it) > 1e-4 * area(outer) }
            .sortedWith(compareBy({ it.minOf { p -> p.y } }, { it.minOf { p -> p.x } }))
        if (holes.size < 2) return emptyList()
        return holes.mapIndexed { i, h ->
            val u0 = h.minOf { it.x }; val u1 = h.maxOf { it.x }; val v0 = h.minOf { it.y }; val v1 = h.maxOf { it.y }
            Triple("${'A' + i}", Vec3((v0 + v1) / 2, ext.y / 2, (u0 + u1) / 2), minOf(v1 - v0, u1 - u0))
        }
    }

    fun outlineNotes(laid: Mesh, fmt: LengthFormat): List<String> {
        val ext = laid.extents
        if (ext.y > 0.35 * minOf(ext.x, ext.z)) return emptyList()       // not a flat part
        // The slice's own frame: u along Z (the part's width, up the sheet), v along X (its length, across).
        val loops = runCatching { splitziner.section.Slicer.sectionLoops(laid, Vec3(0.0, ext.y / 2, 0.0), Vec3(0.0, 1.0, 0.0)) }.getOrNull() ?: return emptyList()
        if (loops.isEmpty()) return emptyList()
        fun area(l: List<splitziner.math.Vec2>) = abs(splitziner.section.Slicer.loopArea(l))
        val outer = loops.maxByOrNull { area(it) }!!
        val d = { v: Double -> fmt.format(v) }
        val out = ArrayList<String>()
        // Rounded corners: a corner of radius r leaves r(sqrt 2 - 1) between the box corner and the outline.
        val ou0 = outer.minOf { it.x }; val ou1 = outer.maxOf { it.x }; val ov0 = outer.minOf { it.y }; val ov1 = outer.maxOf { it.y }
        val radii = listOf(ou0 to ov0, ou0 to ov1, ou1 to ov0, ou1 to ov1).map { (cu, cv) -> outer.minOf { Math.hypot(it.x - cu, it.y - cv) } / (Math.sqrt(2.0) - 1) }
        val r = radii.average()
        // A radius near half the width is a round or round-ended outline, not a rounded corner.
        if (r > 0.01 * minOf(ou1 - ou0, ov1 - ov0) && r < 0.4 * minOf(ou1 - ou0, ov1 - ov0) && radii.all { abs(it - r) < 0.15 * r }) out += "ALL OUTSIDE CORNERS R${d(r)}."
        // Openings: loops inside the outline.
        val holes = loops.filter { it !== outer && it.all { p -> p.x > ou0 && p.x < ou1 && p.y > ov0 && p.y < ov1 } && area(it) > 1e-4 * area(outer) }
            .sortedWith(compareBy({ it.minOf { p -> p.y } }, { it.minOf { p -> p.x } }))
        for ((i, h) in holes.withIndex()) {
            val u0 = h.minOf { it.x }; val u1 = h.maxOf { it.x }; val v0 = h.minOf { it.y }; val v1 = h.maxOf { it.y }
            val w = v1 - v0; val hgt = u1 - u0
            val shape = when {
                area(h) > 0.97 * w * hgt -> "SQUARE-CORNERED"
                abs(w - hgt) < 0.05 * maxOf(w, hgt) && area(h) > 0.75 * w * hgt -> "ROUND"
                else -> "ROUND-ENDED"
            }
            val name = if (holes.size == 1) "OPENING" else "OPENING ${'A' + i}"
            out += if (shape == "ROUND") "$name: ${d(w)} DIA THRU, CENTRE ${d((v0 + v1) / 2 - ov0)} FROM LEFT EDGE, ${d((u0 + u1) / 2 - ou0)} FROM BOTTOM EDGE."
            else "$name: $shape, ${d(w)} X ${d(hgt)} THRU, ITS BOX STARTING ${d(v0 - ov0)} FROM LEFT EDGE AND ${d(u0 - ou0)} FROM BOTTOM EDGE."
        }
        return out
    }

    private fun rename(d: DisplayList, name: String): DisplayList {
        val n = DisplayList(d.widthMm, d.heightMm, name)
        n.prims += d.prims; n.boxes += d.boxes; n.upperCase = d.upperCase
        return n
    }
}
