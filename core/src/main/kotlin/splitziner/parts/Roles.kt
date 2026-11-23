package splitziner.parts

/**
 * Plain names for parts that have none of their own ("Side", "Shelf", "Door", "Drawer bottom"), read from where
 * each part sits and which way it lies. A guess, and only a label: it changes nothing about how a part is made,
 * and a name from the file or from the maker always wins.
 */
object Roles {
    /** A board narrower than this (mm) is a strip (rail, upright), never a shelf, side or top. */
    private const val NARROW = 150.0

    /** Height of a seat's upper face off the floor (mm): a stool's or a chair's, 15 to 20-1/2 in. */
    private val SEAT_HEIGHT = 380.0..520.0

    /** A ladder's sides and rungs are slimmer than this (mm) across: a 2x3 at most. */
    private const val LADDER_BAR = 90.0

    /** Rise from one rung to the next (mm): a climbable step, 6 to 18 in. */
    private val RUNG_PITCH = 150.0..460.0

    /** Clear space between a ladder's sides (mm): room for a foot, short of a bench's width. */
    private val LADDER_GAP = 250.0..650.0

    fun names(parts: List<Part>): Map<String, String> {
        val live = parts.filter { !it.excluded }
        if (live.size < 2) return emptyMap()
        val out = HashMap<String, String>()
        runCatching { splitziner.check.Doors.find(live) }.getOrDefault(emptyList()).forEach { out[it.id] = "Door" }
        runCatching { splitziner.check.ShelfPins.find(live) }.getOrDefault(emptyList()).forEach { out[it] = "Adjustable shelf" }
        val byId = live.associateBy { it.id }
        for (dr in runCatching { splitziner.check.Drawers.find(live) }.getOrDefault(emptyList())) {
            val box = dr.boxIds.mapNotNull { byId[it] }
            for (id in dr.ids - dr.boxIds.toSet()) out[id] = "Drawer front"
            val bottom = box.minByOrNull { it.mesh.bounds.min.z + it.mesh.bounds.extents.z }
            val frontY = box.minOf { it.mesh.bounds.min.y }
            for (p in box) {
                val e = p.mesh.bounds.extents; val t = minOf(e.x, e.y, e.z)
                out[p.id] = when {
                    p == bottom && e.z <= t + 0.01 -> "Drawer bottom"
                    e.x <= t + 0.01 -> "Drawer side"
                    // With a separate front fixed on, the box's own front and back are alike: its ends.
                    e.y <= t + 0.01 && dr.boxIds.size != dr.ids.size -> "Drawer box end"
                    // A front glued on a box with two ends of its own: the box's front end is an end too.
                    e.y <= t + 0.01 && box.count { q -> q.mesh.bounds.extents.let { qe -> qe.y <= minOf(qe.x, qe.z) } } >= 3 && p.mesh.bounds.min.y > frontY + 0.5 -> "Drawer box end"
                    e.y <= t + 0.01 && p.mesh.bounds.min.y <= frontY + 0.5 -> "Drawer front"
                    e.y <= t + 0.01 -> "Drawer back"
                    else -> "Drawer part"
                }
            }
        }
        val all = live.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }
        val h = all.extents.z
        // A drawer that runs on wood rather than slides: the strips its sides ride on are runners, the ones just over
        // its sides (stopping it tipping as it comes out) kickers, and the strips across the front above and below
        // its front drawer rails.
        run {
            val boxSides = live.filter { out[it.id] == "Drawer side" }
            val fronts = live.filter { out[it.id] == "Drawer front" }
            fun overXY(a: Part, o: Part) = minOf(a.mesh.bounds.max.x, o.mesh.bounds.max.x) - maxOf(a.mesh.bounds.min.x, o.mesh.bounds.min.x) > 1.0 &&
                minOf(a.mesh.bounds.max.y, o.mesh.bounds.max.y) - maxOf(a.mesh.bounds.min.y, o.mesh.bounds.min.y) > 0.3 * a.mesh.bounds.extents.y
            for (pass in 0..1) for (p in live) {
                if (p.id in out || boxSides.isEmpty()) continue
                val b = p.mesh.bounds; val e = b.extents; val d = listOf(e.x, e.y, e.z).sorted()
                if (d[1] >= NARROW || e.z > d[1] + 0.01) continue
                // Runners and kickers first; the rails across the front only once runners show the drawer runs on wood.
                if (pass == 0) when {
                    e.y >= e.x && boxSides.any { sd -> kotlin.math.abs(sd.mesh.bounds.min.z - b.max.z) < 2.0 && overXY(sd, p) } -> out[p.id] = "Runner"
                    e.y >= e.x && boxSides.any { sd -> b.min.z - sd.mesh.bounds.max.z in -0.5..8.0 && overXY(sd, p) } -> out[p.id] = "Kicker"
                } else when {
                    e.x > e.y && live.any { out[it.id] == "Runner" } && fronts.any { f -> val fb = f.mesh.bounds; minOf(fb.max.x, b.max.x) - maxOf(fb.min.x, b.min.x) > 0.5 * fb.extents.x &&
                        b.min.y <= fb.max.y + 25.0 && (fb.min.z - b.max.z in -0.5..8.0 || b.min.z - fb.max.z in -0.5..8.0) } -> out[p.id] = "Drawer rail"
                }
            }
        }
        // A plinth: three or more boards on edge on the floor, joined in a ring, low, with the case standing on them.
        run {
            fun near(a: Part, o: Part) = (0..2).all { k -> o.mesh.bounds.min[k] <= a.mesh.bounds.max[k] + 1.0 && a.mesh.bounds.min[k] <= o.mesh.bounds.max[k] + 1.0 }
            val cand = live.filter { p -> p.id !in out && p.mesh.bounds.let { b -> val e = b.extents
                b.min.z <= all.min.z + 1.0 && e.z < 0.25 * h && minOf(e.x, e.y) < e.z && maxOf(e.x, e.y) > 3 * e.z &&
                    live.any { o -> o !== p && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 && near(p, o) && o.mesh.bounds.extents.z > e.z } } }
            if (cand.size >= 3 && cand.all { c -> cand.count { o -> o !== c && near(c, o) } >= if (cand.size >= 4) 2 else 1 }) for (c in cand) out[c.id] = "Plinth"
        }
        // A ladder: two slim bars standing side by side, a hand's span or more apart, with three or more level bars
        // (rungs) spanning between them up their height. Its sides are not legs, nor its rungs rails or cleats.
        run {
            fun near(a: Part, o: Part) = (0..2).all { k -> o.mesh.bounds.min[k] <= a.mesh.bounds.max[k] + 1.0 && a.mesh.bounds.min[k] <= o.mesh.bounds.max[k] + 1.0 }
            val bars = live.filter { p -> p.id !in out && p.mesh.bounds.extents.let { e -> maxOf(e.x, e.y) < LADDER_BAR && e.z > 6 * maxOf(e.x, e.y) } }
            for (u in bars.indices) for (v in u + 1 until bars.size) {
                val a = bars[u]; val c = bars[v]
                if (a.id in out || c.id in out) continue
                val ab = a.mesh.bounds; val cb = c.mesh.bounds
                // Lined up across one way, apart the other.
                val along = (0..1).firstOrNull { k -> kotlin.math.abs(ab.min[1 - k] - cb.min[1 - k]) < 1.0 && kotlin.math.abs(ab.max[1 - k] - cb.max[1 - k]) < 1.0 } ?: continue
                val gap = maxOf(ab.min[along], cb.min[along]) - minOf(ab.max[along], cb.max[along])
                if (gap !in LADDER_GAP) continue
                val rungs = live.filter { r -> r.id !in out && r !== a && r !== c && near(r, a) && near(r, c) && r.mesh.bounds.extents.let { e ->
                    (0..2).maxByOrNull { e[it] } == along && listOf(e.x, e.y, e.z).sorted()[1] < LADDER_BAR } }
                if (rungs.size < 3) continue
                val zs = rungs.map { it.mesh.bounds.center.z }.sorted()
                if (zs.max() - zs.min() < 0.4 * minOf(ab.extents.z, cb.extents.z)) continue
                // Rungs a step apart, evenly: a chair's back rails (one under the seat, two up the back) are not.
                val pitch = zs.zipWithNext { x, y -> y - x }
                if (pitch.any { it !in RUNG_PITCH } || pitch.max() > 1.5 * pitch.min()) continue
                out[a.id] = "Ladder side"; out[c.id] = "Ladder side"
                for (r in rungs) out[r.id] = "Rung"
            }
        }
        // Upright slats between two level rails, three or more alike (a crib's sides, a railing's balusters): spindles.
        run {
            fun over(a: Part, o: Part) = o.mesh.bounds.min.x < a.mesh.bounds.max.x - 0.5 && o.mesh.bounds.max.x > a.mesh.bounds.min.x + 0.5 &&
                o.mesh.bounds.min.y < a.mesh.bounds.max.y - 0.5 && o.mesh.bounds.max.y > a.mesh.bounds.min.y + 0.5
            fun level(o: Part) = o.mesh.bounds.extents.let { oe -> oe.z < maxOf(oe.x, oe.y) / 3 }
            val byRails = HashMap<Pair<String, String>, MutableList<Part>>()
            for (p in live) {
                if (p.id in out) continue
                val b = p.mesh.bounds; val e = b.extents; val d = listOf(e.x, e.y, e.z).sorted()
                if (e.z < d[2] - 0.01 || e.z < 3 * d[1] || d[1] >= NARROW || b.min.z <= all.min.z + 1.0) continue
                val below = live.firstOrNull { o -> o !== p && level(o) && kotlin.math.abs(o.mesh.bounds.max.z - b.min.z) < 1.0 && over(p, o) } ?: continue
                val above = live.firstOrNull { o -> o !== p && level(o) && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 && over(p, o) } ?: continue
                byRails.getOrPut(below.id to above.id) { ArrayList() } += p
            }
            for ((_, ps) in byRails) if (ps.size >= 3) {
                val e0 = ps[0].mesh.bounds.extents
                if (ps.all { q -> q.mesh.bounds.extents.let { kotlin.math.abs(it.z - e0.z) < 1.0 } }) for (q in ps) out[q.id] = "Spindle"
            }
        }
        // Legs first: square posts standing on the floor, at least twice as tall as they are thick (a bed's legs are
        // short). Rails are then known by the legs they join.
        val legs = HashSet<String>()
        // A face frame: narrow strips lying flat across the front of a case, their backs against its front edges.
        // Upright ones are stiles, level ones rails; neither is a leg, whatever it stands on.
        for (p in live) {
            if (p.id in out) continue
            val b = p.mesh.bounds; val e = b.extents
            // At the front: nothing overlapping it side to side and up and down stands further forward.
            val front = live.none { o -> o !== p && o.mesh.bounds.max.y <= b.min.y + 0.5 && o.mesh.bounds.min.x < b.max.x - 0.5 && o.mesh.bounds.max.x > b.min.x + 0.5 &&
                o.mesh.bounds.min.z < b.max.z - 0.5 && o.mesh.bounds.max.z > b.min.z + 0.5 }
            // Flat to the front (a square leg is not a stile).
            // And in the front half of the piece: a strip across the back of an open case is not a face frame.
            if (b.max.y > all.min.y + 0.5 * all.extents.y) continue
            if (!front || e.y > 0.7 * minOf(e.x, e.z) || maxOf(e.x, e.z) < 4 * minOf(e.x, e.z) || minOf(e.x, e.z) > 6 * e.y) continue
            val behind = live.count { o -> o !== p && kotlin.math.abs(o.mesh.bounds.min.y - b.max.y) < 1.0 &&
                o.mesh.bounds.min.x < b.max.x && o.mesh.bounds.max.x > b.min.x && o.mesh.bounds.min.z < b.max.z && o.mesh.bounds.max.z > b.min.z }
            if (behind == 0) continue
            out[p.id] = if (e.z > e.x) "Stile" else "Face frame rail"
        }
        // A rail between two stiles, flush with their faces, belongs to the frame even with an opening behind it
        // (the rail between a drawer and the doors below it).
        val stiles = live.filter { out[it.id] == "Stile" }
        if (stiles.size >= 2) for (p in live) {
            if (p.id in out) continue
            val b = p.mesh.bounds; val e = b.extents
            if (e.y > 0.7 * minOf(e.x, e.z) || e.x < 4 * e.z) continue
            val ends = stiles.filter { st -> val sb = st.mesh.bounds; kotlin.math.abs(sb.min.y - b.min.y) < 1.0 && kotlin.math.abs(sb.max.y - b.max.y) < 1.0 &&
                sb.min.z <= b.min.z + 0.5 && sb.max.z >= b.max.z - 0.5 && (kotlin.math.abs(sb.max.x - b.min.x) < 1.0 || kotlin.math.abs(sb.min.x - b.max.x) < 1.0) }
            if (ends.size == 2) out[p.id] = "Face frame rail"
        }
        val legBox = live.filter { o -> o.id !in out && o.mesh.bounds.extents.let { oe -> val od = listOf(oe.x, oe.y, oe.z).sorted()
            od[1] < 2.5 * od[0] && oe.z >= od[2] - 0.01 && oe.z > 1.5 * od[1] && o.mesh.bounds.min.z <= all.min.z + 1.0 } }.map { it.mesh.bounds }.reduceOrNull { x, y -> x.union(y) } ?: all
        // A board splayed out from the vertical (5 to 35 degrees), standing on the floor, long for its width: a leg
        // (a picnic table's or a sawhorse's), though its box, being tilted, is wide.
        for (p in live) {
            if (p.id in out || p.mesh.bounds.min.z > all.min.z + 1.0) continue
            val obb = runCatching { splitziner.mesh.OrientedBounds.of(p.mesh) }.getOrNull() ?: continue
            if (kotlin.math.abs(p.mesh.volume) < 0.9 * obb.volume) continue
            val oe = obb.extents.let { listOf(it.x, it.y, it.z) }; val od = oe.sorted()
            val longAx = obb.axes[oe.indices.maxByOrNull { oe[it] }!!]
            val deg = Math.toDegrees(kotlin.math.acos(kotlin.math.abs(longAx.z).coerceIn(0.0, 1.0)))
            if (deg in 5.0..35.0 && od[2] > 3 * od[1] && od[1] < NARROW && od[2] > 0.5 * h) { out[p.id] = "Leg"; legs += p.id }
        }
        for (p in live) {
            if (p.id in out) continue
            val e = p.mesh.bounds.extents; val dims = listOf(e.x, e.y, e.z).sorted()
            if (dims[1] < 2.5 * dims[0] && e.z >= dims[2] - 0.01 && e.z > 1.5 * dims[1] && p.mesh.bounds.min.z <= all.min.z + 1.0) {
                // One standing away from every outside edge (under a bed's centre rail) is a centre leg.
                // Measured against the other legs, not an overhanging top (a round table's legs stand well in from its edge).
                val b = p.mesh.bounds; val m = 2 * dims[1]
                val inside = b.min.x > legBox.min.x + m && b.max.x < legBox.max.x - m && b.min.y > legBox.min.y + m && b.max.y < legBox.max.y - m
                out[p.id] = if (inside) "Centre leg" else "Leg"; legs += p.id
            }
        }
        fun touches(a: Part, o: Part) = (0..2).all { k -> o.mesh.bounds.min[k] <= a.mesh.bounds.max[k] + 1.0 && a.mesh.bounds.min[k] <= o.mesh.bounds.max[k] + 1.0 }
        for (p in live) {
            if (p.id in out) continue
            val b = p.mesh.bounds; val e = b.extents
            val dims = listOf(e.x, e.y, e.z).sorted()
            val t = dims[0]
            // A flat triangle standing upright under a board, in the corner between it and an upright face it is
            // fixed to: a corbel (a bracket holding up an overhang).
            run {
                val fill = kotlin.math.abs(p.mesh.volume) / (e.x * e.y * e.z)
                val thinAx = (0..2).minByOrNull { e[it] }!!
                // Solid (an inch or more) and bracket-sized, not a thin shaped panel.
                if (thinAx != 2 && fill in 0.4..0.6 && e.z > 2 * t && t >= 25.0 && dims[2] < 700.0 && live.any { o -> o !== p && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 && o.mesh.bounds.extents.let { oe -> oe.z <= minOf(oe.x, oe.y) } && touches(p, o) }) {
                    out[p.id] = "Corbel"; return@run
                }
            }
            if (out[p.id] == "Corbel") continue
            // A strip fixed face-on along a bigger board, most of its length (a ledger under slats or a shelf): a cleat.
            if (dims[1] < NARROW) {
                val thinAx = (0..2).minByOrNull { e[it] }!!; val longAx = (0..2).maxByOrNull { e[it] }!!
                val backer = live.firstOrNull { o -> o !== p && touches(p, o) && o.mesh.bounds.extents.let { oe -> oe[longAx] >= e[longAx] - 1.0 && oe[3 - thinAx - longAx] > 1.5 * e[3 - thinAx - longAx] } &&
                    (kotlin.math.abs(o.mesh.bounds.max[thinAx] - b.min[thinAx]) < 1.0 || kotlin.math.abs(o.mesh.bounds.min[thinAx] - b.max[thinAx]) < 1.0) }
                if (backer != null && live.count { o -> o.id in legs && touches(p, o) } >= 2) { out[p.id] = "Cleat"; continue }
                // A strip lying along the face of a leg with a board resting on it (a desk top under a loft bed): a cleat.
                if (longAx != 2) {
                    val onLeg = live.any { o -> o.id in legs && touches(p, o) && (kotlin.math.abs(o.mesh.bounds.max[thinAx] - b.min[thinAx]) < 1.0 || kotlin.math.abs(o.mesh.bounds.min[thinAx] - b.max[thinAx]) < 1.0) }
                    val carries = live.any { o -> o !== p && o.mesh.bounds.extents.let { oe -> oe.z <= minOf(oe.x, oe.y) && oe.x > 2 * e.x && oe.y > 2 * e.y || oe.z <= minOf(oe.x, oe.y) && maxOf(oe.x, oe.y) > NARROW } &&
                        kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 && touches(p, o) }
                    if (onLeg && carries) { out[p.id] = "Cleat"; continue }
                }
            }
            // Square-ish in section and long: a leg standing up, a rail or apron lying down.
            if (dims[1] < 2.5 * t && (dims[2] > 4 * dims[1] || (dims[2] > 3 * dims[1] && live.count { o -> o.id in legs && touches(p, o) } >= 2))) {
                // Round in section and lying down: a rod (a wardrobe's hanging rod, a towel rail's bar).
                val fill = kotlin.math.abs(p.mesh.volume) / (e.x * e.y * e.z)
                if (e.z < dims[2] - 0.01 && fill in 0.70..0.82 && dims[1] < 1.1 * t) { out[p.id] = "Rod"; continue }
                out[p.id] = if (e.z >= dims[2] - 0.01 && b.min.z <= all.min.z + 1.0) "Leg" else if (e.z >= dims[2] - 0.01) "Post" else "Rail"
                continue
            }
            if (dims[1] < 2 * t) continue
            // An upright board off the floor joining two or more legs: a rail (a bed's side rail), unless it is an apron.
            val onEdge = (e.x <= t + 0.01 || e.y <= t + 0.01) && e.z < dims[2] - 0.01
            val joinsLegs = legs.isNotEmpty() && b.min.z > all.min.z + 1.0 && live.count { o -> o.id in legs && touches(p, o) } >= 2
            // A strip on edge across the back, just under the top and in front of a thin back: the hanging rail a
            // wall cabinet is screwed to the wall through.
            if (e.y <= t + 0.01 && e.x > 4 * e.z && dims[1] < NARROW && live.any { o -> o !== p && o.mesh.bounds.extents.let { oe -> oe.y <= 10.0 && oe.y < minOf(oe.x, oe.z) } &&
                    kotlin.math.abs(o.mesh.bounds.min.y - b.max.y) < 1.0 && o.mesh.bounds.min.z <= b.min.z + 0.5 && o.mesh.bounds.max.z >= b.max.z - 0.5 } &&
                live.any { o -> o !== p && o.mesh.bounds.extents.let { oe -> oe.z <= minOf(oe.x, oe.y) } && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 && touches(p, o) } &&
                b.max.z >= all.max.z - 3 * NARROW) { out[p.id] = "Hanging rail"; continue }
            // A shallow upright board high up, right under a flat part: a table's apron or a desk's rail.
            val upright = e.x <= t + 0.01 || e.y <= t + 0.01
            if (upright && e.z < 0.3 * h && b.min.z > all.min.z + 0.3 * h && live.any { o -> o !== p && o.mesh.bounds.extents.z <= minOf(o.mesh.bounds.extents.x, o.mesh.bounds.extents.y) && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 }) {
                out[p.id] = "Apron"; continue
            }
            if (onEdge && joinsLegs) {
                // Round the outside, named by the side it runs along (side rails the long way, end rails across);
                // one down the middle is the centre rail.
                val legT = live.filter { it.id in legs }.maxOf { o -> o.mesh.bounds.extents.let { minOf(it.x, it.y) } }
                val along = if (e.x >= e.y) 0 else 1; val across = 1 - along
                val outer = b.min[across] <= all.min[across] + legT + 1.0 || b.max[across] >= all.max[across] - legT - 1.0
                val longWay = if (all.extents.x >= all.extents.y) 0 else 1
                out[p.id] = if (!outer) "Centre rail" else if (along == longWay) "Side rail" else "End rail"
                continue
            }
            // Several narrow boards alike, lying flat side by side: slats (a bed's, a bench seat's).
            // Spread across their length, not end to end: a row of cabinets' top stretchers in line are not slats.
            val alike = live.filter { o -> o.mesh.bounds.extents.let { oe -> kotlin.math.abs(oe.x - e.x) < 0.5 && kotlin.math.abs(oe.y - e.y) < 0.5 && kotlin.math.abs(oe.z - e.z) < 0.5 } }
            val lenAx = if (e.x >= e.y) 0 else 1
            val inLine = alike.any { o -> o !== p && kotlin.math.abs(o.mesh.bounds.min[1 - lenAx] - b.min[1 - lenAx]) < 1.0 && kotlin.math.abs(o.mesh.bounds.min.z - b.min.z) < 1.0 }
            if (dims[1] < NARROW && e.z <= t + 0.01 && alike.size >= 4 && !inLine) {
                out[p.id] = "Slat"; continue
            }
            // Narrow boards are not shelves or sides: a strip lying down is a rail, one standing up an upright.
            if (dims[1] < NARROW) {
                val kick = e.y <= t + 0.01 && b.max.z <= all.min.z + 0.2 * h && b.min.z <= all.min.z + 1.0 && b.min.y <= all.min.y + 0.25 * all.extents.y
                // Lying flat across the top of a case between its sides (a base cabinet's front and back strips
                // under the worktop): stretchers.
                val sides = live.filter { o -> o !== p && touches(p, o) && o.mesh.bounds.extents.let { oe -> oe.x <= minOf(oe.y, oe.z) && oe.z > 0.3 * h } }
                // A divider ending under it does not count against it: two sides level with it are enough.
                val stretcher = e.z <= t + 0.01 && sides.count { o -> kotlin.math.abs(o.mesh.bounds.max.z - b.max.z) < 1.0 } >= 2
                out[p.id] = if (kick) "Kick board" else if (e.z >= dims[2] - 0.01) "Upright" else if (stretcher) "Stretcher" else "Rail"
                continue
            }
            out[p.id] = when {
                e.z <= t + 0.01 -> {
                    fun flat(o: Part) = o.mesh.bounds.extents.z <= minOf(o.mesh.bounds.extents.x, o.mesh.bounds.extents.y)
                    // How much of this part another flat part above (or below) it covers, seen from above.
                    fun overlapShare(o: Part): Double {
                        val ob = o.mesh.bounds
                        val ox = maxOf(0.0, minOf(ob.max.x, b.max.x) - maxOf(ob.min.x, b.min.x)); val oy = maxOf(0.0, minOf(ob.max.y, b.max.y) - maxOf(ob.min.y, b.min.y))
                        return ox * oy / (e.x * e.y)
                    }
                    val coveredAbove = live.any { o -> o !== p && flat(o) && o.mesh.bounds.min.z >= b.max.z - 0.5 && overlapShare(o) > 0.5 }
                    val coveredBelow = live.any { o -> o !== p && flat(o) && o.mesh.bounds.max.z <= b.min.z + 0.5 && overlapShare(o) > 0.3 }
                    // A bottom closes a case between its sides; a low board on a frame of legs is a shelf.
                    fun touches(o: Part) = (0..2).all { k -> o.mesh.bounds.min[k] <= b.max[k] + 1.0 && b.min[k] <= o.mesh.bounds.max[k] + 1.0 }
                    val betweenPanels = live.any { o -> o !== p && touches(o) && o.mesh.bounds.extents.let { oe -> oe.x <= minOf(oe.y, oe.z) && oe.z > 0.5 * h && oe.y >= NARROW } }
                    // Spanning the whole piece with parts below it reaching the floor (a desk's top under a hutch).
                    val spans = e.x >= 0.95 * all.extents.x && b.min.z > all.min.z + 0.3 * h &&
                        live.count { o -> o !== p && kotlin.math.abs(o.mesh.bounds.max.z - b.min.z) < 1.0 && o.mesh.bounds.min.z <= all.min.z + 1.0 && touches(o) } >= 2
                    when {
                        // Nothing over most of it and high up: a top (a desk's top, though a small shelf rides above it).
                        // At sitting height with a back rising well above it from its back edge: a seat (a chair's, a settle's).
                        !coveredAbove && b.max.z - all.min.z in SEAT_HEIGHT && all.max.z > b.max.z + 250.0 &&
                            live.any { o -> o !== p && o.mesh.bounds.max.z > b.max.z + 250.0 && o.mesh.bounds.min.z < b.max.z + 100.0 && o.mesh.bounds.min.y >= b.max.y - 60.0 } -> "Seat"
                        !coveredAbove && b.min.z > all.min.z + 0.5 * h -> "Top"
                        !coveredAbove && spans -> "Top"
                        !coveredBelow && b.min.z < all.min.z + 0.25 * h && betweenPanels -> "Bottom"
                        else -> "Shelf"
                    }
                }
                // A side has nothing beside it further out that overlaps it front to back and up and down (an
                // overhanging top only sits on it); a divider has panels on both sides.
                e.x <= t + 0.01 -> {
                    fun beyond(left: Boolean) = live.any { o -> o !== p && (if (left) o.mesh.bounds.max.x <= b.min.x + 0.5 else o.mesh.bounds.min.x >= b.max.x - 0.5) &&
                        o.mesh.bounds.min.y < b.max.y - 0.5 && o.mesh.bounds.max.y > b.min.y + 0.5 && o.mesh.bounds.min.z < b.max.z - 0.5 && o.mesh.bounds.max.z > b.min.z + 0.5 &&
                        o.mesh.bounds.extents.x <= minOf(o.mesh.bounds.extents.y, o.mesh.bounds.extents.z) }
                    if (b.min.x <= all.min.x + 1.0 || b.max.x >= all.max.x - 1.0 || !beyond(true) || !beyond(false)) "Side" else "Divider"
                }
                e.y <= t + 0.01 -> when {
                    // At the back but narrow against the whole piece (an L desk's return end): a side, not a back.
                    b.max.y >= all.max.y - 1.0 && t > 10.0 && e.x < 0.5 * all.extents.x && e.z > 0.5 * h -> "Side"
                    b.max.y >= all.max.y - 1.0 -> if (t <= 10.0) "Back panel" else "Back"
                    // Flush with the back edges of the sides it stands between, from near the floor most of the way up,
                    // though a top or brackets reach further back (an island's seating overhang): the back.
                    e.z > 0.5 * h && b.min.z <= all.min.z + 0.25 * h && live.filter { o -> o !== p && o.mesh.bounds.extents.let { oe -> oe.x <= minOf(oe.y, oe.z) && oe.z > 0.5 * h } &&
                        (0..2).all { k -> o.mesh.bounds.min[k] <= b.max[k] + 1.0 && b.min[k] <= o.mesh.bounds.max[k] + 1.0 } }.let { ss -> ss.size >= 2 && ss.all { o -> o.mesh.bounds.max.y <= b.max.y + 1.0 } } ->
                        if (t <= 10.0) "Back panel" else "Back"
                    b.max.z <= all.min.z + 0.2 * h && b.min.z <= all.min.z + 1.0 -> "Kick board"
                    b.min.y <= all.min.y + 1.0 -> "Front"
                    // Flush with the front of everything standing up, though a lid or top overhangs it: the front.
                    e.z > 0.3 * h && b.min.y <= live.filter { o -> o.mesh.bounds.extents.let { oe -> oe.z > minOf(oe.x, oe.y) } }.minOf { it.mesh.bounds.min.y } + 1.0 &&
                        b.min.z <= all.min.z + 0.25 * h -> "Front"
                    // Standing in the back half, hung from the underside of a top and clear of the floor:
                    // the panel that stiffens a desk and screens the legs, which furniture makers call a modesty panel.
                    (b.min.y + b.max.y) / 2 > all.min.y + 0.5 * all.extents.y &&
                        live.any { o -> o !== p && o.mesh.bounds.extents.z <= minOf(o.mesh.bounds.extents.x, o.mesh.bounds.extents.y) && kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 &&
                            b.min.z > all.min.z + 0.25 * (o.mesh.bounds.min.z - all.min.z) } -> "Modesty panel"
                    else -> "Panel"
                }
                else -> continue
            }
        }
        for (id in runCatching { splitziner.check.Doors.sliding(live) }.getOrDefault(emptyList())) out[id] = "Sliding door"
        // A tenon: a small block sunk into one part (a leg's mortise) and butting the end of another (an apron),
        // drawn as a part of its own. It is that apron's tenon.
        for (p in live) {
            val b = p.mesh.bounds; val e = b.extents
            if (maxOf(e.x, e.y, e.z) > 120.0 || minOf(e.x, e.y, e.z) > 16.0) continue
            fun inside(o: Part) = o.mesh.bounds.let { ob -> (0..2).all { k -> b.min[k] >= ob.min[k] - 0.5 && b.max[k] <= ob.max[k] + 0.5 } }
            val host = live.firstOrNull { o -> o !== p && inside(o) } ?: continue
            val rail = live.firstOrNull { o -> o !== p && o !== host && touches(p, o) && o.mesh.bounds.let { ob ->
                val long = (0..2).maxByOrNull { ob.extents[it] }!!
                kotlin.math.abs(ob.min[long] - b.max[long]) < 1.0 || kotlin.math.abs(ob.max[long] - b.min[long]) < 1.0 } } ?: continue
            if (rail.mesh.bounds.extents.let { maxOf(it.x, it.y, it.z) } < 2 * maxOf(e.x, e.y, e.z)) continue
            out[p.id] = "Tenon"
        }
        // Steps: a notched side carrying flat boards at two or more heights on its notches is a stringer, and the
        // boards are treads (a step stool, a short stair).
        for (p in live) {
            if (out[p.id] != "Side") continue
            val b = p.mesh.bounds
            if (kotlin.math.abs(p.mesh.volume) > 0.85 * b.extents.x * b.extents.y * b.extents.z) continue
            val on = live.filter { o -> o !== p && o.mesh.bounds.extents.let { oe -> oe.z <= minOf(oe.x, oe.y) } && touches(p, o) &&
                o.mesh.bounds.min.z >= b.min.z + 1.0 && o.mesh.bounds.min.z <= b.max.z + 1.0 }
            if (on.map { Math.round(it.mesh.bounds.min.z) }.distinct().size < 2) continue
            // Steps climb: each higher board stands further back (or forward) than the one below, by a good part
            // of the stringer's depth. A desk's notched end with a top and shelves over each other is not a stair.
            val byHeight = on.groupBy { Math.round(it.mesh.bounds.min.z) }.toSortedMap().values.map { g -> g.map { (it.mesh.bounds.min.y + it.mesh.bounds.max.y) / 2 }.average() }
            val steps = byHeight.zipWithNext { lo, hi -> hi - lo }
            val climbs = steps.all { it > 0.2 * b.extents.y } || steps.all { it < -0.2 * b.extents.y }
            if (!climbs) continue
            out[p.id] = "Stringer"; for (o in on) out[o.id] = "Tread"
        }
        // A seat's back: boards leaned back off upright above the seat, the long ones slats, the upright ones supports.
        for (p in live) {
            if (out[p.id] == "Leg" || out[p.id] == "Centre leg") continue
            val obb = runCatching { splitziner.mesh.OrientedBounds.of(p.mesh) }.getOrNull() ?: continue
            // Boards only: a rod's or a round top's box can turn any way about it.
            if (kotlin.math.abs(p.mesh.volume) < 0.9 * obb.volume) continue
            val axes = obb.axes
            val up = axes.maxByOrNull { kotlin.math.abs(it.z) }!!.let { if (it.z < 0) it * -1.0 else it }
            val deg = Math.toDegrees(kotlin.math.acos(up.z.coerceIn(-1.0, 1.0)))
            if (deg < 3.0 || deg > 40.0 || kotlin.math.abs(up.x) > 0.05 || p.mesh.bounds.min.z < all.min.z + 0.3 * h) continue
            val e = splitziner.mesh.OrientedBounds.of(p.mesh).extents.let { listOf(it.x, it.y, it.z) }
            val longAx = axes[e.indices.maxByOrNull { e[it] }!!]
            out[p.id] = if (kotlin.math.abs(longAx.z) > 0.7) "Back support" else "Back slat"
        }
        // A trestle: a post stands on a foot lying on the floor and carries a top rail under the top. Rails that run
        // from post to post are its stretchers.
        val posts = live.filter { out[it.id] == "Post" }
        for (p in live) {
            if (out[p.id] != "Rail") continue
            val b = p.mesh.bounds
            fun over(o: Part) = o.mesh.bounds.let { ob -> ob.min.x < b.max.x && ob.max.x > b.min.x && ob.min.y < b.max.y && ob.max.y > b.min.y }
            val postOn = posts.any { o -> kotlin.math.abs(o.mesh.bounds.min.z - b.max.z) < 1.0 && over(o) }
            val postUnder = posts.any { o -> kotlin.math.abs(o.mesh.bounds.max.z - b.min.z) < 1.0 && over(o) }
            val between = posts.count { o -> touches(p, o) && o.mesh.bounds.min.z < b.min.z && o.mesh.bounds.max.z > b.max.z }
            out[p.id] = when {
                postOn && b.min.z <= all.min.z + 1.0 -> "Foot"
                postUnder && !postOn -> "Top rail"
                between >= 2 -> "Stretcher"
                else -> "Rail"
            }
        }
        // A top over a closed box (front, back and both sides under it, no doors or drawers): the only way in is
        // through it, so it is a lid, hinged at the back.
        live.filter { out[it.id] == "Top" }.singleOrNull()?.let { top ->
            val tb = top.mesh.bounds
            val under = live.filter { o -> o !== top && kotlin.math.abs(o.mesh.bounds.max.z - tb.min.z) < 1.0 }.map { out[it.id] }
            val closed = under.count { it == "Side" } >= 2 && "Front" in under && (under.contains("Back") || under.contains("Back panel"))
            if (closed && live.none { out[it.id] in setOf("Door", "Drawer front", "Sliding door") } && live.none { it.mesh.bounds.min.z >= tb.max.z - 0.5 && it !== top }) out[top.id] = "Lid"
        }
        // Above a seat, the rails between the back legs carry the back: back rails.
        live.firstOrNull { out[it.id] == "Seat" }?.let { seat ->
            for (p in live) if (out[p.id] in setOf("Side rail", "End rail", "Rail", "Apron") && p.mesh.bounds.min.z >= seat.mesh.bounds.max.z - 0.5) out[p.id] = "Back rail"
        }
        // A bed's side or end rail carrying no slats, with one on the same side below it that does: a guard rail
        // round a top bunk.
        if (live.any { out[it.id] == "Slat" }) {
            val carriers = live.filter { out[it.id] == "Slat" || out[it.id] == "Cleat" }
            for (p in live) {
                val r = out[p.id]; if (r != "Side rail" && r != "End rail" && r != "Rail") continue
                if (carriers.any { touches(p, it) }) continue
                val b = p.mesh.bounds; val thin = (0..1).minByOrNull { b.extents[it] }!!
                // A plain rail counts when it stands on edge (a short guard rail stopping at the ladder).
                if (r == "Rail" && !(b.extents[thin] < b.extents.z && b.extents.z < b.extents[1 - thin])) continue
                val below = live.any { o -> o !== p && (out[o.id] == r || (r == "Rail" && out[o.id] in setOf("Side rail", "End rail"))) && o.mesh.bounds.max.z < b.min.z && kotlin.math.abs(o.mesh.bounds.min[thin] - b.min[thin]) < 1.0 &&
                    carriers.any { c -> touches(o, c) } }
                if (below) out[p.id] = "Guard rail"
            }
        }
        return out
    }
}
