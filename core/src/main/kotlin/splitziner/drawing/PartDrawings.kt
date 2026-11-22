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
            p.mesh.isWatertight && !d.desc.contains("NOTCHED") && !d.desc.contains("HOLE")
        }.map { it.item }.toSet() - pinItems(parts) - doorItems(parts)
    }

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
    fun grooveNotes(p: Part, parts: List<Part>, housed: List<Pair<String, String>>, fmt: LengthFormat): List<String> {
        val byId = parts.associateBy { it.id }
        val d = { v: Double -> fmt.format(v) }
        return housed.filter { it.second == p.id }.mapNotNull { (pid, _) ->
            val q = byId[pid] ?: return@mapNotNull null
            val a = p.mesh.bounds; val b = q.mesh.bounds
            val e = doubleArrayOf(minOf(a.max.x, b.max.x) - maxOf(a.min.x, b.min.x), minOf(a.max.y, b.max.y) - maxOf(a.min.y, b.min.y), minOf(a.max.z, b.max.z) - maxOf(a.min.z, b.min.z))
            if (e.any { it <= 0 }) return@mapNotNull null
            // The overlap is the groove. Its depth runs along the direction the housed part sticks out of this one;
            // of the other two sizes the smaller is the groove's width, the larger its length.
            val qe = doubleArrayOf(b.extents.x, b.extents.y, b.extents.z)
            val out = (0..2).filter { e[it] < qe[it] - 0.01 }
            val k = out.minByOrNull { e[it] } ?: (0..2).minByOrNull { e[it] }!!
            val rest = (0..2).filter { it != k }.map { e[it] }.sorted()
            "GROOVE FOR ITEM ${q.item}: ${d(e[k])} DEEP X ${d(rest[0])} WIDE X ${d(rest[1])} LONG, WHERE ITEM ${q.item} MEETS IT ON THE ASSEMBLY DRAWING"
        }.groupingBy { it }.eachCount().map { (t, n) -> if (n > 1) "$t ($n PLACES)." else "$t." }
    }

    /** Detail drawings for every item except those in [skip], each with the row it details. */
    fun items(parts: List<Part>, rows: List<CutListRow>, info: DrawingInfo, settings: DrawingSettings, fmt: LengthFormat, skip: Set<Int>, housed: List<Pair<String, String>> = emptyList()): List<Pair<CutListRow, DrawingResult>> {
        val byId = parts.associateBy { it.id }
        val out = ArrayList<Pair<CutListRow, DrawingResult>>()
        for (row in rows) {
            if (row.item in skip) continue
            val p = row.memberIds.firstNotNullOfOrNull { byId[it] } ?: continue
            val laid = p.copy(mesh = squaredUp(p.mesh))
            val vs = DrawingBuilder.views(listOf(laid), settings.sections)
            val base = info.drawingNo.ifBlank { "DWG" }
            val itemInfo = info.copy(
                title = "${info.title.ifBlank { "PART" }} — ITEM ${row.item}",
                drawingNo = "$base-${row.item}",
                notes = listOf("ITEM ${row.item} OF ${info.drawingNo.ifBlank { "THE ASSEMBLY DRAWING" }}. QUANTITY REQUIRED: ${row.qty}.") + outlineNotes(laid.mesh, fmt) + pinNotes(p, parts, fmt) + hingeNotes(p, parts, fmt) + grooveNotes(row.memberIds.firstNotNullOfOrNull { id -> byId[id]?.takeIf { g -> housed.any { it.second == g.id } } } ?: p, parts, housed, fmt) + info.notes,
            )
            // A detail drawing rarely needs more than a small sheet; a pinned sheet size applies to the assembly only.
            val r = DrawingBuilder.build(listOf(laid), listOf(row), vs, itemInfo, settings.copy(sheet = if (settings.sheet == "AB") "AB" else "auto"), fmt, extraHoles = hingeCups(p, parts, laid.mesh), tags = runCatching { openingTags(laid.mesh) }.getOrDefault(emptyList()))
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
        if (r > 0.01 * minOf(ou1 - ou0, ov1 - ov0) && radii.all { abs(it - r) < 0.15 * r }) out += "ALL OUTSIDE CORNERS R${d(r)}."
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
