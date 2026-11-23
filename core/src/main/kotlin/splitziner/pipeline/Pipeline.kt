package splitziner.pipeline

import splitziner.dims.LengthFormat
import splitziner.io.StlReader
import splitziner.math.Aabb
import splitziner.parts.CutList
import splitziner.parts.CutListResult
import splitziner.parts.ItemOverride
import splitziner.parts.Part
import splitziner.split.BodySplitter
import splitziner.split.SplitReport

/** Choices the user can make that change how a file is read. */
data class AnalysisOptions(
    /** "mm", "cm", "in" or "m"; null lets the app guess. */
    val fileUnits: String? = null,
    /** "mm" or "in"; null uses the units the model appears to be designed in. */
    val displayUnits: String? = null,
    val inchStyle: String = "decimal",
    val itemOverrides: Map<String, ItemOverride> = emptyMap(),
    val excludedPartIds: Set<String> = emptySet(),
    val partNames: Map<String, String> = emptyMap(),
    /**
     * Which of the file's axes points up: "z" (the default), "y", "x", or with a minus for the opposite way
     * ("-z" is upside down). Many programs export with Y up; the app works with Z up.
     */
    val up: String = "z",
    /** The app chose [up] itself, judging the file to be saved another way up: the analysis says so in its notes. */
    val upGuessed: Boolean = false,
    /** Changes the user has made to the model, replayed on the file's parts every time it is read. */
    val edits: List<splitziner.edit.Edit> = emptyList(),
)

/** Everything the app knows about one STL after reading it. */
class Analysis(
    val sourceName: String,
    val report: SplitReport,
    val parts: List<Part>,
    val cutList: CutListResult,
    val format: LengthFormat,
    /** Bounds of all parts, in millimetres. */
    val bounds: Aabb,
    val timingsMs: Map<String, Long>,
    /** The parts as the file has them, before any of the user's changes. */
    val originalParts: List<Part> = parts,
    /** Changes that could not be replayed, in plain words. */
    val editNotes: List<String> = emptyList(),
    /** (part, the part grooved to take it) pairs the user accepted as housing joints. */
    val housed: List<Pair<String, String>> = emptyList(),
    /** Things made to order elsewhere (a clear cover from a plastics shop), one line each, for the shopping list. */
    val orders: List<String> = emptyList(),
) {
    /** Overall size as "W x D x H units". */
    val overallSize: String get() {
        val e = bounds.extents
        return "${format.format(e.x)} × ${format.format(e.y)} × ${format.format(e.z)} ${format.unitLabel}"
    }
}

fun interface Progress { fun report(fraction: Double, label: String) }

object Pipeline {
    /** Row-major 3x3 rotation taking the file's [up] axis to +Z, keeping handedness (no mirroring). */
    fun upRotation(up: String): DoubleArray {
        // "y>1": stood up with Y up, then turned a quarter about the vertical [n] times, so its front faces the viewer.
        val base = baseRotation(up.substringBefore('>'))
        val n = (up.substringAfter('>', "0").toIntOrNull() ?: 0).mod(4)
        if (n == 0) return base
        val c = intArrayOf(1, 0, -1, 0)[n].toDouble(); val sn = intArrayOf(0, 1, 0, -1)[n].toDouble()
        val t = doubleArrayOf(c, -sn, 0.0, sn, c, 0.0, 0.0, 0.0, 1.0)
        return DoubleArray(9) { k -> val i = k / 3; val j = k % 3; (0..2).sumOf { m -> t[3 * i + m] * base[3 * m + j] } }
    }

    /** The up axis on its own: the file's [up] axis taken to +Z, keeping handedness. */
    private fun baseRotation(up: String): DoubleArray = when (up) {
        "y" -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)      // file Y becomes Z, file Z becomes -Y (back)
        "-y" -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0)
        "x" -> doubleArrayOf(0.0, 0.0, -1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
        "-x" -> doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 0.0, -1.0, 0.0, 0.0)
        "-z" -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)
        else -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }

    /**
     * Which of the file's axes most likely points up, judged by what the model looks like stood each way: drawers
     * and doors that hang right, legs and a kick board on the floor, a top on top. Many programs save with Y up, so a
     * cabinet arrives lying on its back. Returns "z" unless another way is clearly better (by [MARGIN] points).
     */
    fun guessUp(parts: List<Part>): String {
        val live = parts.filter { !it.excluded }
        if (live.size < 3) return "z"
        fun turned(up: String): List<Part> { if (up == "z" || up == "z>0") return live; val r = upRotation(up)
            return live.map { p -> p.copy(name = p.id, mesh = splitziner.mesh.Mesh(DoubleArray(p.mesh.vertices.size) { i ->
                val v = 3 * (i / 3); val x = p.mesh.vertices[v]; val y = p.mesh.vertices[v + 1]; val z = p.mesh.vertices[v + 2]
                r[3 * (i % 3)] * x + r[3 * (i % 3) + 1] * y + r[3 * (i % 3) + 2] * z
            }, p.mesh.triangles)) } }
        fun score(ps: List<Part>): Int {
            // The strips of a frame round a panel are stiles and rails whichever way up it lies: they tell nothing.
            // Nor do pegs sunk in a board, standing up like posts when the board lies flat.
            val framed = runCatching { splitziner.check.FramePanels.find(ps).flatMap { it.ids } }.getOrDefault(emptyList()).toSet() +
                runCatching { splitziner.check.Pegs.ids(ps) }.getOrDefault(emptySet())
            val names = runCatching { splitziner.parts.Roles.names(ps).filterKeys { it !in framed }.values }.getOrDefault(emptyList())
            val drawers = runCatching { splitziner.check.Drawers.find(ps).size }.getOrDefault(0)
            val doors = runCatching { splitziner.check.Doors.find(ps).size }.getOrDefault(0)
            // Lying on its back, a whole panel rests on the floor; standing, only edges and feet do.
            val floor = ps.minOf { it.mesh.bounds.min.z }
            val all = ps.map { it.mesh.bounds }.reduce { x, y -> x.union(y) }
            var down = 0.0
            val pads = ArrayList<Double>()
            for (p in ps) { val m = p.mesh; val n = m.faceNormals; val a = m.faceAreas; val v = m.vertices; val t = m.triangles
                var mine = 0.0
                for (f in 0 until m.triangleCount) if (n[3 * f + 2] < -0.99 && v[3 * t[3 * f] + 2] < floor + 1.0) { down += a[f]; mine += a[f] }
                if (mine > 0) pads += mine }
            val frac = down / maxOf(1.0, all.extents.x * all.extents.y)
            // Standing on three or more small feet (legs' ends), the usual way furniture stands; lying down, boards' faces meet the floor.
            val onFeet = pads.size >= 3 && pads.all { it < 0.03 * all.extents.x * all.extents.y }
            // Roles that only make sense the right way up (a kick board, a stringer's treads) count most; ones that
            // come up either way a little; boards with no sensible role at all count against.
            // Counted by parts, not kinds: a bed's 24 slats and 4 legs outweigh a stray "foot" or two that a bed
            // lying on its side throws up.
            val kinds = names.toSet()
            // Stood this way, would it stand? Its balance point must fall inside what touches the floor: a chair on its
            // head rests on the tops of its back legs alone, a line, and falls over.
            val feet = ArrayList<splitziner.math.Vec2>()
            for (p in ps) if (p.mesh.bounds.min.z <= floor + 2.0) for (v in 0 until p.mesh.vertexCount) if (p.mesh.vertices[3 * v + 2] <= floor + 2.0) feet += splitziner.math.Vec2(p.mesh.vertices[3 * v], p.mesh.vertices[3 * v + 1])
            val hull = splitziner.mesh.OrientedBounds.convexHull(feet)
            var vm = 0.0; var cx = 0.0; var cy = 0.0
            for (p in ps) { val v = kotlin.math.abs(p.mesh.volume); val c = p.mesh.bounds.center; vm += v; cx += v * c.x; cy += v * c.y }
            // Made to hang: it meets the floor only along a strip at the back, under a full-width back board.
            // Along any of its four sides (it may not face the viewer yet).
            val hangs = feet.isNotEmpty() && (0..1).any { a -> val o = 1 - a
                val fa = feet.map { if (a == 0) it.x else it.y }
                all.extents[a] <= splitziner.parts.Roles.WALL_DEPTH && fa.max() - fa.min() <= maxOf(40.0, 0.25 * all.extents[a]) &&
                    (fa.max() >= all.max[a] - 1.0 || fa.min() <= all.min[a] + 1.0) &&
                    ps.any { p -> p.mesh.bounds.min.z <= floor + 2.0 && p.mesh.bounds.extents.let { e -> e[o] >= 0.8 * all.extents[o] && e[a] < minOf(e[o], e.z) } } }
            val stands = hangs || hull.size >= 3 && vm > 0 && hull.indices.all { k -> val p0 = hull[k]; val q = hull[(k + 1) % hull.size]
                (q.x - p0.x) * (cy / vm - p0.y) - (q.y - p0.y) * (cx / vm - p0.x) >= -1.0 } || hull.size >= 3 && hull.indices.all { k -> val p0 = hull[k]; val q = hull[(k + 1) % hull.size]
                (q.x - p0.x) * (cy / vm - p0.y) - (q.y - p0.y) * (cx / vm - p0.x) <= 1.0 }
            return (if (stands) 0 else -FALLS) + 3 * drawers + 2 * doors + 2 * minOf(STRONG_CAP, names.count { it in STRONG_ROLES && it != "Apron" } + minOf(4, names.count { it == "Apron" })) + minOf(6, names.count { it in WEAK_ROLES }) +
                (if ("Top" in kinds) 1 else 0) + (if ("Bottom" in kinds) 1 else 0) - minOf(3, names.count { it == "Panel" }) - (if (frac > 0.85) FLAT_ON_FLOOR else 0) + (if (onFeet) ON_FEET else 0)
        }
        // Each way up is judged as it is, plus whatever facing another way would add to the part that depends on facing:
        // a cabinet stood up but facing sideways still has its kick board, just not at the front, and must not lose to
        // lying on its back. (Big models: as it faces.)
        val turns = if (live.size > 150) listOf(0) else (0..3).toList()
        // Judged facing the way its front says (a face frame is only found at the front), plus what that turn adds.
        val scores = listOf("z", "y", "-y").associateWith { u -> val ps0 = turned(u)
            val f0 = frontScore(ps0)
            // Every way up gets the front it would have once turned to face the viewer, so one already facing is not
            // beaten by its upside-down twin for needing a turn.
            val fronts = turns.associateWith { n -> if (n == 0) f0 else frontScore(turned("$u>$n")) }
            val best = fronts.maxByOrNull { it.value }!!.key
            // Judged as it would stand once facing that way; the front itself is not added, or a table on its side,
            // its top boards reading as a face frame, would beat the table standing on its legs.
            maxOf(score(ps0), if (best == 0) score(ps0) else score(turned("$u>$best"))) }
        if (System.getenv("DBGUP") != null) { System.err.println("DBGUP $scores"); for (u in listOf("y", "-y")) for (n in turns) { val ps0 = turned("$u>$n"); System.err.println("DBGUP $u>$n score=${score(ps0)} front=${frontScore(ps0)} " + runCatching { splitziner.parts.Roles.names(ps0).values.groupingBy { it }.eachCount() }.getOrDefault(emptyMap())) } }
        // Y up is the common export; its upside-down twin has to beat it clearly as well.
        val other = if (scores.getValue("-y") >= scores.getValue("y") + MARGIN) "-y" else "y"
        return if (scores.getValue(other) >= scores.getValue("z") + MARGIN) other else "z"
    }

    /**
     * How many quarter turns about the vertical bring the model's front to face the viewer (the app's front is -Y):
     * drawers and doors that open towards it, a kick board and a face frame at the front, a back at the back.
     * 0 unless another turn is clearly better (by [MARGIN] points). [parts] stand the right way up already.
     */
    fun guessTurn(parts: List<Part>): Int {
        val live = parts.filter { !it.excluded }
        if (live.size < 3) return 0
        fun turned(n: Int): List<Part> { if (n == 0) return live; val r = upRotation("z>$n")
            return live.map { p -> p.copy(name = p.id, mesh = splitziner.mesh.Mesh(DoubleArray(p.mesh.vertices.size) { i ->
                val v = 3 * (i / 3); val x = p.mesh.vertices[v]; val y = p.mesh.vertices[v + 1]; val z = p.mesh.vertices[v + 2]
                r[3 * (i % 3)] * x + r[3 * (i % 3) + 1] * y + r[3 * (i % 3) + 2] * z
            }, p.mesh.triangles)) } }
        val scores = (0..3).associateWith { frontScore(turned(it)) }
        if (System.getenv("DBGTURN") != null) System.err.println("DBGTURN $scores")
        val best = scores.maxByOrNull { it.value }!!
        // Two ways equally good (the piece looks the same from front and back): leave it as it is. With nothing to go
        // by either way, a chest, table or bench faces the way its long side does: a quarter turn when drawn end-on.
        // Not a bed, which runs head to foot away from the viewer.
        if (scores.values.count { it == best.value } > 1) {
            val all = live.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }
            // A flat piece (a frame lying on its back) has no front to speak of.
            if (scores.getValue(0) != scores.getValue(1) || all.extents.y <= LONG_SIDE * all.extents.x || all.extents.z < 0.25 * maxOf(all.extents.x, all.extents.y)) return 0
            val names = runCatching { splitziner.parts.Roles.names(live) }.getOrDefault(emptyMap()).values
            return if (names.none { it in BED_ROLES }) 1 else 0
        }
        return if (best.key != 0 && best.value >= scores.getValue(0) + MARGIN) best.key else 0
    }

    private val BED_ROLES = setOf("Slat", "Side rail", "Headboard", "Footboard", "Guard rail", "Ladder side", "Back leg", "Ledge")

    /** A piece this much longer front to back than side to side, with nothing else to go by, is drawn end-on. */
    private const val LONG_SIDE = 1.2

    /** How much the piece looks as if it faces -Y: drawer fronts and doors at the front, a kick board and face frame there, a back behind. */
    private fun frontScore(ps: List<Part>): Int {
        if (ps.isEmpty()) return 0
        val byId = ps.associateBy { it.id }
        val names = runCatching { splitziner.parts.Roles.names(ps) }.getOrDefault(emptyMap())
        // Drawers and doors are found whichever way the piece faces: what counts is that their fronts are at the front.
        // The front of the carcass, not of a worktop or top overhanging it.
        val front = ps.filter { it.mesh.bounds.extents.let { e -> e.z > minOf(e.x, e.y) } }.minOfOrNull { it.mesh.bounds.min.y } ?: ps.minOf { it.mesh.bounds.min.y }
        fun atFront(id: String) = (byId[id]?.mesh?.bounds?.min?.y ?: Double.MAX_VALUE) <= front + FRONT_DEPTH
        // And nothing in front of it: a drawer box's end behind a back panel (the piece facing backwards) is not a face.
        fun open(id: String): Boolean { val b = byId[id]?.mesh?.bounds ?: return false
            return ps.none { o -> o.id != id && o.mesh.bounds.max.y <= b.min.y + 0.5 &&
                (minOf(o.mesh.bounds.max.x, b.max.x) - maxOf(o.mesh.bounds.min.x, b.min.x)) * (minOf(o.mesh.bounds.max.z, b.max.z) - maxOf(o.mesh.bounds.min.z, b.min.z)) > 0.5 * b.extents.x * b.extents.z &&
                o.mesh.bounds.max.x > b.min.x && o.mesh.bounds.min.x < b.max.x && o.mesh.bounds.max.z > b.min.z && o.mesh.bounds.min.z < b.max.z } }
        val faces = names.count { (id, n) -> (n == "Drawer front" || n == "Door" || n == "Door panel" || n == "Drawer front panel") && atFront(id) && open(id) }
        // A panelled frame's stiles say nothing about which way it faces; a door's do, through its panel above.
        val framed = runCatching { splitziner.check.FramePanels.find(ps).flatMap { it.ids } }.getOrDefault(emptyList()).toSet()
        val back = ps.filter { it.mesh.bounds.extents.let { e -> e.z > minOf(e.x, e.y) } }.maxOfOrNull { it.mesh.bounds.max.y } ?: ps.maxOf { it.mesh.bounds.max.y }
        fun atBack(id: String) = (byId[id]?.mesh?.bounds?.max?.y ?: Double.MIN_VALUE) >= back - FRONT_DEPTH
        // A wall piece's back board is its only clue, and a sure one.
        val board = if (names.any { (id, n) -> n == "Back board" && atBack(id) }) 3 else 0
        // Steps climb away from the viewer: the lowest tread at the front.
        val treads = names.filter { it.value == "Tread" }.keys.mapNotNull { byId[it] }
        val climbs = if (treads.map { Math.round(it.mesh.bounds.min.z) }.distinct().size >= 2) {
            val low = treads.minByOrNull { it.mesh.bounds.min.z }!!; val high = treads.maxByOrNull { it.mesh.bounds.min.z }!!
            if (low.mesh.bounds.center.y < high.mesh.bounds.center.y - 1.0) 3 else 0 } else 0
        // An easel's back leg stands behind its front legs.
        val backLeg = names.filter { it.value == "Back leg" }.keys.mapNotNull { byId[it] }; val frontLegs = names.filter { it.value == "Leg" }.keys.mapNotNull { byId[it] }
        fun footY(p: Part): Double { val v = p.mesh.vertices; var lo = Double.MAX_VALUE; var y = 0.0; for (i in 0 until p.mesh.vertexCount) if (v[3 * i + 2] < lo - 0.5) { lo = v[3 * i + 2]; y = v[3 * i + 1] }; return y }
        val easel = if (backLeg.isNotEmpty() && frontLegs.size >= 2 && backLeg.all { b -> footY(b) > frontLegs.maxOf { footY(it) } + 50.0 }) 4 else 0
        // Shelves or a hutch standing on a desk or counter top sit against its back edge, leaving the front clear.
        val upper = run {
            val tops = ps.filter { it.mesh.bounds.extents.let { e -> e.z < 0.2 * minOf(e.x, e.y) && e.y > 300.0 } }
            val top = tops.maxByOrNull { it.mesh.bounds.extents.x * it.mesh.bounds.extents.y } ?: return@run 0
            val tb = top.mesh.bounds
            val on = ps.filter { o -> o !== top && kotlin.math.abs(o.mesh.bounds.min.z - tb.max.z) < 1.0 && o.mesh.bounds.extents.z > o.mesh.bounds.extents.y }
            if (on.size < 2) return@run 0
            val ob = on.map { it.mesh.bounds }.reduce { x, y -> x.union(y) }
            if (kotlin.math.abs(ob.max.y - tb.max.y) < 2.0 && ob.min.y > tb.min.y + 0.3 * tb.extents.y) 4 else 0
        }
        // A seat's back (its back rails, slats or supports) is behind the middle of the seat.
        val seatBack = run {
            val backs = names.filter { it.value in setOf("Back rail", "Back slat", "Back support") }.keys.mapNotNull { byId[it] }
            if (backs.isEmpty()) return@run 0
            val cy = ps.map { it.mesh.bounds.center.y }.average()
            if (backs.map { it.mesh.bounds.center.y }.average() > cy + 0.15 * (ps.maxOf { it.mesh.bounds.max.y } - ps.minOf { it.mesh.bounds.min.y })) 4 else 0
        }
        return board + climbs + easel + upper + seatBack + 3 * minOf(8, faces) + 2 * minOf(6, names.count { (id, n) -> n in FRONT_ROLES && id !in framed && atFront(id) }) + minOf(3, names.count { (id, n) -> n in BACK_ROLES && atBack(id) }) + (if (names.any { (id, n) -> n == "Back panel" && atBack(id) }) 1 else 0)
    }

    /** A drawer front or door this close to the frontmost face of the piece (mm) faces the front: an inch, room for a face frame in front of an inset door, not for a cabinet side in front of a drawer box. */
    private const val FRONT_DEPTH = 25.0

    /** Roles found only at the front, and only at the back, of a piece standing the right way round. */
    private val FRONT_ROLES = setOf("Kick board", "Stile", "Sliding door", "Drawer rail", "Ledge")   // not face frame rails alone: any flat strips at the front look like them
    private val BACK_ROLES = setOf("Back panel", "Back", "Hanging rail", "Back board", "Back rail", "Back slat")

    // Spindles and seats are left out: a cabinet on its back is full of upright strips between level boards.
    private val STRONG_ROLES = setOf("Kick board", "Leg", "Centre leg", "Stringer", "Tread", "Foot", "Top rail", "Drawer front", "Sliding door", "Slat", "Stile", "Apron", "Back slat",
        "Plinth", "Lid", "Runner", "Kicker", "Drawer rail", "Hanging rail", "Corbel", "Rung", "Ladder side", "Back rail", "Guard rail", "Bracket", "Lip", "Back board")
    private val WEAK_ROLES = setOf("Post", "Stretcher", "Back panel", "Modesty panel", "Cleat", "Side rail", "End rail")

    /** Parts in telling roles counted at most (two points each): a loft bed's 12 slats, 4 legs, ladder and guard rails all count. */
    private const val STRONG_CAP = 24

    /** Points off a way up in which a whole face rests on the floor (on its back, or on its top). Small: a box on its bottom does too. */
    private const val FLAT_ON_FLOOR = 2

    // Aprons count four at most: a table has four, and a desk on its head turns every drawer side into one.

    /** Points for a way up in which it stands on three or more small feet. */
    private const val ON_FEET = 4

    /** Points off a way up in which the piece would fall over. */
    private const val FALLS = 8

    /**
     * How far (degrees) the model is turned about the vertical from the axes, judged from the upright faces of its
     * parts (their normals, by area, folded into one quarter turn). Null when it is square already (within
     * [SQUARE_TOL]) or has no clear direction (round, or parts at many angles).
     */
    fun squareUpAngle(parts: List<Part>): Double? {
        var sx = 0.0; var sy = 0.0; var total = 0.0
        for (p in parts) { val m = p.mesh; val n = m.faceNormals; val a = m.faceAreas
            for (f in 0 until m.triangleCount) { val nx = n[3 * f]; val ny = n[3 * f + 1]; val nz = n[3 * f + 2]
                if (kotlin.math.abs(nz) > 0.3 || a[f] <= 0.0) continue
                val ang = 4 * kotlin.math.atan2(ny, nx); sx += a[f] * kotlin.math.cos(ang); sy += a[f] * kotlin.math.sin(ang); total += a[f] } }
        if (total <= 0.0 || kotlin.math.hypot(sx, sy) < SQUARE_AGREE * total) return null
        val deg = Math.toDegrees(kotlin.math.atan2(sy, sx)) / 4
        if (kotlin.math.abs(deg) < SQUARE_TOL) return null
        // The outline must agree: a model whose walls face that way but whose plan is already square to the axes (an
        // oval table on diamond-set legs) is not turned. Turning it would only make its footprint bigger.
        fun footprint(a: Double): Double {
            val c = kotlin.math.cos(Math.toRadians(-a)); val sn = kotlin.math.sin(Math.toRadians(-a))
            var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (p in parts) { val v = p.mesh.vertices; var i = 0
                while (i < v.size) { val x = c * v[i] - sn * v[i + 1]; val y = sn * v[i] + c * v[i + 1]
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; i += 3 } }
            return (x1 - x0) * (y1 - y0)
        }
        return if (footprint(deg) > FOOTPRINT_GROW * footprint(0.0)) null else deg
    }

    /** Squaring up may not make the plan's bounding box more than this much bigger: the outline shows it is square already. */
    private const val FOOTPRINT_GROW = 1.03

    /** Below this (degrees) a model counts as square: drawing noise, not a turn. */
    private const val SQUARE_TOL = 0.3

    /** Share of the upright face area that must agree on one direction before the model is turned to it. */
    private const val SQUARE_AGREE = 0.6

    /** Points by which another up axis must beat the file's own Z before the app turns the model. */
    private const val MARGIN = 3

    /**
     * Above this many triangles in all, parts are simplified for the rest of the work. A phone draws
     * and checks this many comfortably; a CAD export of a cabinet is usually far below it.
     */
    const val WORK_BUDGET = 400_000

    /** No part is simplified below this; small parts keep their shape. */
    const val MIN_PART_TRIANGLES = 600

    fun analyze(bytes: ByteArray, name: String, options: AnalysisOptions = AnalysisOptions(), progress: Progress = Progress { _, _ -> }): Analysis =
        analyze({ java.io.ByteArrayInputStream(bytes) }, bytes.size.toLong(), name, options, progress)

    /** Reads the file from [open] (called once or twice) so it is never held in memory whole. */
    fun analyze(file: java.io.File, options: AnalysisOptions = AnalysisOptions(), progress: Progress = Progress { _, _ -> }, name: String = file.name): Analysis =
        analyze({ file.inputStream() }, file.length(), name, options, progress)

    fun analyze(open: () -> java.io.InputStream, size: Long, name: String, options: AnalysisOptions = AnalysisOptions(), progress: Progress = Progress { _, _ -> }): Analysis {
        val timings = LinkedHashMap<String, Long>()
        fun <T> timed(label: String, block: () -> T): T { val t0 = System.nanoTime(); val r = block(); timings[label] = (System.nanoTime() - t0) / 1_000_000; return r }

        progress.report(0.05, "Reading triangles")
        // The raw triangles are let go as soon as the parts are built.
        val split = run {
            val raw = timed("load") { StlReader.read(open, size, name) }
            progress.report(0.3, "Separating parts")
            timed("split") { BodySplitter.split(raw, options.fileUnits, options.displayUnits) }
        }
        // Turned so the chosen axis points up. A rotation by quarter turns: sizes and right angles are kept exactly.
        val turned0 = if (options.up == "z" || options.up == "z>0") split.parts else {
            val r = upRotation(options.up)
            split.parts.map { p -> p.copy(mesh = splitziner.mesh.Mesh(DoubleArray(p.mesh.vertices.size) { i ->
                val v = 3 * (i / 3); val x = p.mesh.vertices[v]; val y = p.mesh.vertices[v + 1]; val z = p.mesh.vertices[v + 2]
                r[3 * (i % 3)] * x + r[3 * (i % 3) + 1] * y + r[3 * (i % 3) + 2] * z
            }, p.mesh.triangles)) }
        }
        // Drawn at an angle on the floor plan (a model turned 30 degrees in SketchUp): squared up to the axes, so its
        // boards run along X and Y as the drawings, the roles and the build order expect.
        val yaw = squareUpAngle(turned0)
        val turned = if (yaw == null) turned0 else {
            val c = kotlin.math.cos(Math.toRadians(-yaw)); val sn = kotlin.math.sin(Math.toRadians(-yaw))
            turned0.map { p -> p.copy(mesh = splitziner.mesh.Mesh(DoubleArray(p.mesh.vertices.size) { i ->
                val v = 3 * (i / 3); val x = p.mesh.vertices[v]; val y = p.mesh.vertices[v + 1]
                when (i % 3) { 0 -> c * x - sn * y; 1 -> sn * x + c * y; else -> p.mesh.vertices[v + 2] }
            }, p.mesh.triangles)) }
        }
        // Very heavy models are worked with as a simplified copy, so drawings, the 3D view and the checks fit in a phone's memory.
        var maxErr = 0.0
        val heavy = turned.sumOf { it.mesh.triangleCount }
        var report = split.report
        val lighter = if (heavy <= WORK_BUDGET) turned else timed("simplify") {
            progress.report(0.7, "Simplifying a heavy model")
            val notes = ArrayList<String>()
            // Accuracy is judged against the whole model, as the drawings show it.
            val whole = turned.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }.extents.length
            val tol = splitziner.mesh.Simplify.tolerance(whole)
            val out = turned.map { p ->
                val share = maxOf(MIN_PART_TRIANGLES, (p.mesh.triangleCount.toLong() * WORK_BUDGET / heavy).toInt())
                if (p.mesh.triangleCount <= share) p else {
                    val s = splitziner.mesh.Simplify.run(p.mesh, share, tol)
                    // Merging vertices can leave a seam or two; whether the part itself is sealed is a fact about the file.
                    p.copy(mesh = s.mesh, watertight = p.watertight).also { maxErr = maxOf(maxErr, s.maxError) }
                }
            }
            val after = out.sumOf { it.mesh.triangleCount }
            notes += "This model has ${"%,d".format(heavy)} triangles. It is worked with as ${"%,d".format(after)}, which stays within ${if (maxErr < 0.01) "0.01" else "%.2f".format(maxErr)} mm of the original surface; sharp corners stay exact."
            report = report.copy(warnings = report.warnings + notes)
            out
        }
        progress.report(0.8, "Building the cut list")
        if (yaw != null) report = report.copy(warnings = report.warnings + "The model was drawn turned ${"%.1f".format(kotlin.math.abs(yaw))}\u00b0 on the floor plan, so it has been squared up: its boards now run straight across and back, as the drawings show them.")
        if (options.upGuessed && options.up.substringBefore('>') != "z") report = report.copy(warnings = report.warnings + "The model looked as if it was lying on its back (saved with ${options.up.substringBefore('>').uppercase()} up, as many programs do), so it has been stood up. If that is wrong, open the project menu (\u22ee, top right), then Which way is up.")
        if (options.upGuessed && options.up.contains('>')) report = report.copy(warnings = report.warnings + "The model's front looked as if it faced sideways (its drawers, doors or kick board were on a side), so it has been turned to face you. If that is wrong, open the project menu (\u22ee, top right), then Which way is up, and turn it.")
        val fmt = LengthFormat(units = split.report.units.designUnits, inchStyle = options.inchStyle)
        val named = lighter.map { p -> p.copy(name = options.partNames[p.id] ?: p.name, excluded = p.id in options.excludedPartIds) }
        val edited = if (options.edits.isEmpty()) null else timed("edits") { splitziner.edit.Editor.apply(named, options.edits) }
        // A clear cover on top (a plinth's vitrine) is made by a plastics shop: named, kept out of the wood lists and
        // drawings (and out of naming the rest), and said once with the size to order.
        val beforeCover = edited?.parts ?: named
        val coverIds = runCatching { splitziner.check.Covers.find(beforeCover) }.getOrDefault(emptyList()).toSet()
        val orders = ArrayList<String>()
        if (coverIds.isNotEmpty()) {
            val cs = beforeCover.filter { it.id in coverIds }
            val bx = cs.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }
            val th = cs.minOf { p -> p.mesh.bounds.extents.let { minOf(it.x, it.y, it.z) } }
            val u = if (fmt.isInch) " in" else " mm"
            report = report.copy(warnings = report.warnings + ("The clear cover on top is left out of the wood lists and drawings: have it made up by a plastics fabricator in ${fmt.format(th)}$u acrylic, " +
                "${fmt.format(bx.extents.x)} x ${fmt.format(bx.extents.y)} x ${fmt.format(bx.extents.z)}$u outside, glued, with polished edges. It rests on the top and lifts off."))
            orders += "Clear acrylic cover, ${fmt.format(bx.extents.x)} x ${fmt.format(bx.extents.y)} x ${fmt.format(bx.extents.z)}$u outside, ${fmt.format(th)}$u thick, open at the bottom: made up by a plastics fabricator"
        }
        // Parts with no name of their own get one from what they look like they are.
        val toCut = beforeCover.map { p -> if (p.id in coverIds) p.copy(name = if (p.id in options.partNames) p.name else "Acrylic cover", excluded = true) else p }.let { ps ->
            val roles = timed("roles") { runCatching { splitziner.parts.Roles.names(ps) }.getOrDefault(emptyMap()) }
            // A board split from a drawn part carries that part's id as its name ("P04" on "P04.2"): no name of its own either.
            fun unnamed(p: Part) = (p.name == p.id || p.name == p.id.substringBefore('.')) && p.id !in options.partNames && p.id.substringBefore('.') !in options.partNames
            // With no role either, it goes by its own id, which everything reads as "no name".
            ps.map { p -> if (unnamed(p)) roles[p.id]?.let { p.copy(name = it) } ?: p.copy(name = p.id) else p }
        }
        val cut = timed("cutlist") { CutList.build(toCut, fmt, options.itemOverrides) }
        val bounds = cut.parts.filter { !it.excluded }.map { it.mesh.bounds }.reduceOrNull { a, b -> a.union(b) }
            ?: Aabb.of(doubleArrayOf())
        progress.report(1.0, "Done")
        // A tenon sunk in another part sits in a mortise: that overlap is the joint, so it is housed as drawn.
        val live = cut.parts.filter { !it.excluded }
        val tenons = live.filter { it.name == "Tenon" }.mapNotNull { t -> val tb = t.mesh.bounds
            live.firstOrNull { o -> o !== t && o.mesh.bounds.let { ob -> (0..2).all { k -> tb.min[k] >= ob.min[k] - 0.5 && tb.max[k] <= ob.max[k] + 0.5 } } }?.let { t.id to it.id } }
        // A panel set in grooves in the frame round it overlaps each strip by the groove's depth: housed as drawn too.
        val grooved = runCatching { splitziner.check.FramePanels.find(live) }.getOrDefault(emptyList()).flatMap { it.sunk }
        // A rod with one end sunk into a board (a coat rack's peg) sits in a drilled hole: housed as drawn.
        val pegs = live.filter { it.kind == splitziner.parts.PartKind.ROD }.mapNotNull { r -> splitziner.check.Pegs.host(r, live)?.let { r.id to it.id } }
        val housed = ((edited?.housed ?: emptyList()) + tenons + grooved + pegs).distinct()
        return Analysis(name, report, cut.parts, cut, fmt, bounds, timings, named, edited?.notes ?: emptyList(), housed, orders)
    }
}
