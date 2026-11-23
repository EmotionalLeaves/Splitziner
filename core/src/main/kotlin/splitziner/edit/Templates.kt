package splitziner.edit

import splitziner.math.Vec3
import splitziner.mesh.Mesh

/**
 * Starting designs built from plain boards, so a new project is a real piece of furniture to change rather
 * than one board. All sizes are millimetres; X is width, Y depth (front at Y = 0), Z height. Joinery is plain
 * butt joints, which is what the guide's joining advice works from.
 */
object Templates {
    /** A size the user can set, with its default in millimetres. */
    /** [mm] is the metric default; [inch] the default in inches, chosen as stock sizes (3/4 in board, 1/4 in back). */
    class Field(val key: String, val label: String, val mm: Double, val inch: Double = mm / 25.4, val count: Boolean = false, val toggle: Boolean = false)

    class Template(val key: String, val name: String, val blurb: String, val fields: List<Field>, val build: (Map<String, Double>) -> List<Mesh>)

    /** Sizes that cannot make the piece: a part would come out with no size at all, or less. */
    class SizesDoNotFit : IllegalArgumentException("Those sizes do not fit together: some part would have no room. Make the piece bigger or its parts smaller.")

    private fun box(x: Double, y: Double, z: Double, w: Double, d: Double, h: Double): Mesh {
        if (w < 1.0 || d < 1.0 || h < 1.0) throw SizesDoNotFit()
        return Editor.box(Vec3(x, y, z), Vec3(w, d, h))
    }

    /**
     * A board [w] wide (across, near X) and [t] thick whose centre line runs from (x0, y0, z0) to (x1, y1, z1), its two
     * ends cut level (horizontal), as a leaning leg is.
     */
    private fun leaner(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, w: Double, t: Double): Mesh {
        if (w < 1.0 || t < 1.0 || z1 - z0 < 1.0) throw SizesDoNotFit()
        val ax = Vec3(x1 - x0, y1 - y0, z1 - z0).normalized()
        val sx = Vec3(1.0, 0.0, 0.0).let { v -> (v - ax * (v dot ax)).normalized() }
        val n = ax cross sx
        val offs = listOf(sx * (-w / 2) - n * (t / 2), sx * (w / 2) - n * (t / 2), sx * (w / 2) + n * (t / 2), sx * (-w / 2) + n * (t / 2))
        fun at(o: Vec3, z: Double): Vec3 { val p = Vec3(x0, y0, z0) + o; val lam = (z - p.z) / ax.z; return p + ax * lam }
        val pts = offs.map { at(it, z0) } + offs.map { at(it, z1) }
        val v = DoubleArray(24) { pts[it / 3].let { p -> if (it % 3 == 0) p.x else if (it % 3 == 1) p.y else p.z } }
        return splitziner.mesh.MeshOps.fixWinding(Mesh(v, intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)))
    }

    /** A four-cornered outline in plan (x, y pairs, in order round it) made [z1] - [z0] thick: a mitred bar lying flat. */
    private fun quadZ(q: List<Pair<Double, Double>>, z0: Double, z1: Double): Mesh {
        if (z1 - z0 < 1.0) throw SizesDoNotFit()
        val pts = q.map { Vec3(it.first, it.second, z0) } + q.map { Vec3(it.first, it.second, z1) }
        val v = DoubleArray(24) { pts[it / 3].let { p -> if (it % 3 == 0) p.x else if (it % 3 == 1) p.y else p.z } }
        return splitziner.mesh.MeshOps.fixWinding(Mesh(v, intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)))
    }

    /** An upright round part (a caster, drawn as its overall size) on the floor at ([cx], [cy]). */
    private fun cylZ(cx: Double, cy: Double, z0: Double, r: Double, h: Double, n: Int = 24): Mesh {
        if (r < 1.0 || h < 1.0) throw SizesDoNotFit()
        val v = DoubleArray(3 * (2 * n + 2)); val tri = ArrayList<Int>()
        for (i in 0 until n) { val a = 2 * Math.PI * i / n
            for ((k, z) in listOf(z0, z0 + h).withIndex()) { val j = 3 * (2 * i + k); v[j] = cx + r * kotlin.math.cos(a); v[j + 1] = cy + r * kotlin.math.sin(a); v[j + 2] = z } }
        val c0 = 2 * n; val c1 = 2 * n + 1
        v[3 * c0] = cx; v[3 * c0 + 1] = cy; v[3 * c0 + 2] = z0; v[3 * c1] = cx; v[3 * c1 + 1] = cy; v[3 * c1 + 2] = z0 + h
        for (i in 0 until n) { val a0 = 2 * i; val a1 = 2 * i + 1; val b0 = 2 * ((i + 1) % n); val b1 = b0 + 1
            tri += listOf(c0, b0, a0, c1, a1, b1, a0, b0, b1, a0, b1, a1) }
        return splitziner.mesh.MeshOps.fixWinding(Mesh(v, tri.toIntArray()))
    }

    /** Above this (mm, 30 in) a stretcher side gets a brace across the middle, as stretcher makers advise. */
    private const val BRACE_OVER = 762.0

    /** Designs for making and showing art, listed first and in this order: the app's own user works in a studio. */
    private val STUDIO = listOf("plinth", "easel", "stretcher", "frame", "rack", "taboret", "crate", "ledge")

    /** The designs in labelled groups, in the order the new-design picker shows them. */
    fun groups(): List<Pair<String, List<Template>>> = listOf(
        "Art and studio" to STUDIO.mapNotNull { k -> ALL.firstOrNull { it.key == k } },
        "Furniture" to ALL.filter { it.key !in STUDIO })

    /** Null when the sizes make the piece, else what is wrong, in words for the person who typed them. */
    fun problem(t: Template, values: Map<String, Double>): String? =
        try { t.build(values); null } catch (e: SizesDoNotFit) { e.message } catch (e: Exception) { "Those sizes do not make this piece." }

    val ALL: List<Template> = listOf(
        Template("shelves", "Bookcase", "Two sides, a top and bottom between them, evenly spaced shelves (fixed or on pins), a back panel and a kick board.", listOf(
            Field("w", "Width", 800.0, 32.0), Field("h", "Height", 1800.0, 72.0), Field("d", "Depth", 300.0, 12.0),
            Field("t", "Board thickness", 18.0, 0.75), Field("n", "Shelves", 4.0, 4.0, count = true), Field("back", "Back thickness (0 for none)", 6.0, 0.25),
            Field("pins", "Adjustable shelves, on pins", 0.0, 0.0, count = true, toggle = true),
        )) { v ->
            val w = v.getValue("w"); val h = v.getValue("h"); val d = v.getValue("d"); val t = v.getValue("t"); val back = v.getValue("back")
            val n = v.getValue("n").toInt().coerceIn(0, 20)
            // Adjustable shelves are 1.5 mm short at each end so they drop in onto their pins.
            val pg = if ((v["pins"] ?: 0.0) >= 0.5) 1.5 else 0.0
            val inner = w - 2 * t; val dIn = d - back
            val kick = 3 * t + 40.0
            val out = arrayListOf(box(0.0, 0.0, 0.0, t, d, h), box(w - t, 0.0, 0.0, t, d, h),
                box(t, 0.0, kick, inner, dIn, t), box(t, 0.0, h - t, inner, dIn, t), box(t, 0.0, 0.0, inner, t, kick))
            // Shelves evenly spaced in the clear height between bottom and top.
            val clear0 = kick + t; val clear1 = h - t
            val gap = (clear1 - clear0 - n * t) / (n + 1)
            for (i in 1..n) out += box(t + pg, 0.0, clear0 + i * gap + (i - 1) * t, inner - 2 * pg, dIn, t)
            if (back > 0) out += box(t, d - back, kick, inner, back, h - kick)
            out
        },
        Template("cabinet", "Cabinet", "A box with sides, top, bottom, one shelf, a back and doors (0, 1 or 2), set in the opening or over the front: a cupboard.", listOf(
            Field("w", "Width", 600.0, 24.0), Field("h", "Height", 720.0, 30.0), Field("d", "Depth", 560.0, 24.0), Field("t", "Board thickness", 18.0, 0.75), Field("back", "Back thickness (0 for none)", 6.0, 0.25),
            Field("doors", "Doors (0, 1 or 2)", 0.0, 0.0, count = true), Field("overlay", "Doors over the front, not set in", 0.0, 0.0, count = true, toggle = true),
            Field("glass", "Glass doors (a frame round a pane, for a display cabinet)", 0.0, 0.0, count = true, toggle = true),
        )) { v ->
            val glazed = (v["glass"] ?: 0.0) >= 0.5
            // A door: a plain slab, or a frame of stiles and rails 2 in (50 mm) wide round a 4 mm pane set in a rabbet at its back.
            fun door(x: Double, y: Double, z: Double, dw: Double, dh: Double, t: Double): List<splitziner.mesh.Mesh> {
                if (!glazed || dw < 160.0 || dh < 160.0) return listOf(box(x, y, z, dw, t, dh))
                val sw = 50.0; val lap = 9.5; val gl = 4.0
                return listOf(box(x, y, z, sw, t, dh), box(x + dw - sw, y, z, sw, t, dh), box(x + sw, y, z, dw - 2 * sw, t, sw), box(x + sw, y, z + dh - sw, dw - 2 * sw, t, sw),
                    box(x + sw - lap, y + t - 6.0 - gl, z + sw - lap, dw - 2 * sw + 2 * lap, gl, dh - 2 * sw + 2 * lap))
            }
            val w = v.getValue("w"); val h = v.getValue("h"); val d = v.getValue("d"); val t = v.getValue("t"); val back = v.getValue("back")
            val doors = (v["doors"] ?: 0.0).toInt().coerceIn(0, 2)
            val inner = w - 2 * t; val dIn = d - back
            // With doors the shelf is set back behind them.
            val over = doors > 0 && (v["overlay"] ?: 0.0) >= 0.5
            val shelfY = if (doors > 0 && !over) t + 2.0 else 0.0
            val out = arrayListOf<splitziner.mesh.Mesh>(box(0.0, 0.0, 0.0, t, d, h), box(w - t, 0.0, 0.0, t, d, h),
                box(t, 0.0, 0.0, inner, dIn, t), box(t, 0.0, h - t, inner, dIn, t), box(t, shelfY, (h - t) / 2, inner, dIn - 20.0 - shelfY, t))
            if (back > 0) out += box(t, d - back, t, inner, back, h - 2 * t)
            val g = 2.0
            if (over) {
                // Doors over the front (full overlay): the box moves back by the door thickness, and the doors cover
                // the sides' front edges, 2 mm in from the outside and 2 mm apart as a pair.
                val moved = out.map { splitziner.mesh.MeshOps.translated(it, Vec3(0.0, t, 0.0)) }
                out.clear(); out += moved
                val dh = h - 2 * g
                if (doors == 1) out += door(g, 0.0, g, w - 2 * g, dh, t)
                else { val dw = (w - 3 * g) / 2; out += door(g, 0.0, g, dw, dh, t); out += door(2 * g + dw, 0.0, g, dw, dh, t) }
                return@Template out
            }
            // Inset doors, 2 mm clear all round and between a pair.
            val dh = h - 2 * t - 2 * g
            if (doors == 1) out += door(t + g, 0.0, t + g, inner - 2 * g, dh, t)
            if (doors == 2) { val dw = (inner - 3 * g) / 2; out += door(t + g, 0.0, t + g, dw, dh, t); out += door(t + 2 * g + dw, 0.0, t + g, dw, dh, t) }
            out
        },
        Template("table", "Table", "A top on four square legs joined by aprons set in from the edge.", listOf(
            Field("w", "Length", 1400.0, 60.0), Field("d", "Width", 800.0, 32.0), Field("h", "Height", 750.0, 30.0), Field("t", "Top thickness", 25.0, 1.0),
            Field("leg", "Leg size", 70.0, 3.5), Field("apron", "Apron depth", 90.0, 3.5), Field("at", "Apron thickness", 19.0, 0.75), Field("over", "Top overhang", 30.0, 1.0),
        )) { v -> table(v) },
        Template("bench", "Bench", "A low table: top, four legs and aprons.", listOf(
            Field("w", "Length", 1200.0, 48.0), Field("d", "Width", 350.0, 14.0), Field("h", "Height", 450.0, 18.0), Field("t", "Top thickness", 30.0, 1.5),
            Field("leg", "Leg size", 60.0, 3.5), Field("apron", "Apron depth", 80.0, 3.5), Field("at", "Apron thickness", 19.0, 0.75),
        )) { v -> table(v) },
        Template("workbench", "Workbench", "A sturdy table for the workshop: a thick top on four legs with aprons, and stretchers low down carrying a shelf.", listOf(
            Field("w", "Length", 1500.0, 60.0), Field("d", "Width", 600.0, 24.0), Field("h", "Height", 900.0, 36.0), Field("t", "Top thickness", 38.0, 1.5),
            Field("leg", "Leg size", 70.0, 3.5), Field("apron", "Apron depth", 90.0, 3.5), Field("at", "Apron thickness", 19.0, 0.75),
            Field("low", "Shelf stretchers, height off the floor", 150.0, 6.0), Field("sd", "Stretcher depth", 64.0, 2.5), Field("st", "Shelf thickness", 18.0, 0.75),
        )) { v ->
            val out = ArrayList(table(v + ("over" to 0.0)))
            val w = v.getValue("w"); val d = v.getValue("d"); val leg = v.getValue("leg"); val at = v.getValue("at")
            val low = v.getValue("low"); val sd = v.getValue("sd"); val st = v.getValue("st")
            val inset = (leg - at) / 2
            // Stretchers between the legs low down, on the same lines as the aprons, and a shelf resting on the long ones.
            out += box(leg, inset, low, w - 2 * leg, at, sd)
            out += box(leg, d - leg + inset, low, w - 2 * leg, at, sd)
            out += box(inset, leg, low, at, d - 2 * leg, sd)
            out += box(w - leg + inset, leg, low, at, d - 2 * leg, sd)
            out += box(leg, inset, low + sd, w - 2 * leg, d - leg + inset + at - inset, st)
            out
        },
        Template("desk", "Desk", "A top on two panel ends, braced by a modesty panel set in from the back.", listOf(
            Field("w", "Width", 1400.0, 60.0), Field("d", "Depth", 700.0, 28.0), Field("h", "Height", 750.0, 30.0),
            Field("t", "Top thickness", 25.0, 1.0), Field("st", "Panel thickness", 18.0, 0.75), Field("m", "Modesty panel height", 300.0, 12.0),
        )) { v ->
            val w = v.getValue("w"); val d = v.getValue("d"); val h = v.getValue("h"); val t = v.getValue("t"); val st = v.getValue("st"); val m = v.getValue("m")
            val lh = h - t; val inner = w - 2 * st
            listOf(
                box(0.0, 0.0, lh, w, d, t),                                   // top, flush with the ends
                box(0.0, 0.0, 0.0, st, d, lh), box(w - st, 0.0, 0.0, st, d, lh), // ends
                box(st, d - 3 * st, lh - m, inner, st, m),                    // modesty panel, set in from the back
            )
        },
        Template("nightstand", "Nightstand", "A small cabinet with a drawer on slides at the top and an open shelf below. The drawer box leaves 1/2 in (12.7 mm) each side for ball-bearing slides.", listOf(
            Field("w", "Width", 450.0, 18.0), Field("h", "Height", 600.0, 24.0), Field("d", "Depth", 400.0, 16.0),
            Field("t", "Board thickness", 18.0, 0.75), Field("dh", "Drawer opening height", 150.0, 6.0), Field("dt", "Drawer box thickness", 12.0, 0.5),
        )) { v ->
            val w = v.getValue("w"); val h = v.getValue("h"); val d = v.getValue("d"); val t = v.getValue("t"); val dh = v.getValue("dh"); val dt = v.getValue("dt")
            val inner = w - 2 * t; val back = 6.0; val dIn = d - back
            val out = arrayListOf(box(0.0, 0.0, 0.0, t, d, h), box(w - t, 0.0, 0.0, t, d, h),
                box(t, 0.0, 50.0, inner, dIn, t), box(t, 0.0, h - t, inner, dIn, t), box(t, 0.0, 0.0, inner, t, 50.0),
                box(t, 0.0, h - t - dh - t, inner, dIn, t),                 // divider under the drawer
                box(t, d - back, 50.0, inner, back, h - 50.0))
            // Drawer box: 12.7 mm each side for slides, 6 mm clear above and below, front set back by the false front.
            val slide = 12.7; val bx0 = t + slide; val bx1 = w - t - slide
            val z0 = h - t - dh + 6.0; val bh = dh - 12.0; val y0 = 0.0; val y1 = dIn - 10.0
            out += box(bx0, y0, z0, bx1 - bx0, dt, bh); out += box(bx0, y1 - dt, z0, bx1 - bx0, dt, bh)
            out += box(bx0, y0 + dt, z0, dt, y1 - y0 - 2 * dt, bh); out += box(bx1 - dt, y0 + dt, z0, dt, y1 - y0 - 2 * dt, bh)
            out += box(bx0 + dt, y0 + dt, z0, bx1 - bx0 - 2 * dt, y1 - y0 - 2 * dt, 6.0)
            // False front covering the opening, 2 mm clear all round, in front of the carcass.
            out += box(t + 2.0, -t, h - t - dh + 2.0, inner - 4.0, t, dh - 4.0)
            out
        },
        Template("drawers", "Chest of drawers", "A carcass with a kick board and a stack of drawers on slides, each with a front set in front of the opening. The drawer boxes leave 1/2 in (12.7 mm) each side for ball-bearing slides.", listOf(
            Field("w", "Width", 800.0, 32.0), Field("h", "Height", 900.0, 36.0), Field("d", "Depth", 450.0, 18.0),
            Field("t", "Board thickness", 18.0, 0.75), Field("n", "Drawers", 4.0, 4.0, count = true), Field("dt", "Drawer box thickness", 12.0, 0.5),
            Field("overlay", "Fronts over the sides, not between them", 0.0, 0.0, count = true, toggle = true),
            Field("graduated", "Graduated: deeper drawers lower down", 0.0, 0.0, count = true, toggle = true),
        )) { v ->
            val w = v.getValue("w"); val h = v.getValue("h"); val d = v.getValue("d"); val t = v.getValue("t"); val dt = v.getValue("dt")
            val n = v.getValue("n").toInt().coerceIn(1, 8)
            val inner = w - 2 * t; val back = 6.0; val dIn = d - back; val kick = 50.0
            val out = arrayListOf(box(0.0, 0.0, 0.0, t, d, h), box(w - t, 0.0, 0.0, t, d, h),
                box(t, 0.0, kick, inner, dIn, t), box(t, 0.0, h - t, inner, dIn, t), box(t, 0.0, 0.0, inner, t, kick),
                box(t, d - back, kick, inner, back, h - kick))
            // The space between bottom and top shared equally; each drawer box 6 mm clear of its share above and
            // below, 12.7 mm each side for slides, and 10 mm short of the back.
            val z0 = kick + t
            // Equal shares, or graduated: each drawer a fifth deeper than the one above it, the classic proportion.
            val weights = (0 until n).map { k -> if ((v["graduated"] ?: 0.0) >= 0.5) Math.pow(1.2, (n - 1 - k).toDouble()) else 1.0 }
            val shares = weights.map { it / weights.sum() * (h - t - z0) }
            val starts = shares.runningFold(z0) { a, b -> a + b }
            val slide = 12.7; val bx0 = t + slide; val bx1 = w - t - slide; val y1 = dIn - 10.0
            for (k in 0 until n) {
                val oh = shares[k]; val zs = starts[k]
                val zb = zs + 6.0; val bh = oh - 12.0
                out += box(bx0, 0.0, zb, bx1 - bx0, dt, bh); out += box(bx0, y1 - dt, zb, bx1 - bx0, dt, bh)
                out += box(bx0, dt, zb, dt, y1 - 2 * dt, bh); out += box(bx1 - dt, dt, zb, dt, y1 - 2 * dt, bh)
                out += box(bx0 + dt, dt, zb, bx1 - bx0 - 2 * dt, y1 - 2 * dt, 6.0)
                // Its front, in front of the carcass, 2 mm in from the sides (or over them, 2 mm in from the outside)
                // and 2 mm from the fronts above and below.
                if ((v["overlay"] ?: 0.0) >= 0.5) out += box(2.0, -t, zs + 2.0, w - 4.0, t, oh - 4.0)
                else out += box(t + 2.0, -t, zs + 2.0, inner - 4.0, t, oh - 4.0)
            }
            out
        },
        Template("bed", "Bed frame", "A platform bed: side and end rails round the mattress, legs in the corners, slats on cleats, and a centre rail on two more legs so the slats never span more than half the width.", listOf(
            Field("mw", "Mattress width", 1525.0, 60.0), Field("ml", "Mattress length", 2030.0, 80.0), Field("h", "Height to the top of the rails", 350.0, 14.0),
            Field("rd", "Rail depth", 184.0, 7.25), Field("rt", "Rail thickness", 19.0, 0.75), Field("leg", "Leg size", 89.0, 3.5),
            Field("sw", "Slat width", 89.0, 3.5), Field("st", "Slat thickness", 19.0, 0.75), Field("gap", "Gap between slats", 75.0, 3.0),
        )) { v ->
            val mw = v.getValue("mw"); val ml = v.getValue("ml"); val h = v.getValue("h"); val rd = v.getValue("rd"); val rt = v.getValue("rt")
            val leg = v.getValue("leg"); val sw = v.getValue("sw"); val st = v.getValue("st"); val gap = v.getValue("gap")
            val out = ArrayList<Mesh>()
            val w = mw + 2 * rt; val l = ml + 2 * rt
            // The mattress sits a little down inside the rails, on slats whose tops are this far below the rails' tops.
            val sink = minOf(25.0, rd / 4)
            val slatTop = h - sink; val cleat = 2 * rt
            out += box(0.0, 0.0, h - rd, rt, l, rd); out += box(w - rt, 0.0, h - rd, rt, l, rd)        // side rails, full length
            out += box(rt, 0.0, h - rd, mw, rt, rd); out += box(rt, l - rt, h - rd, mw, rt, rd)       // end rails between them
            val legTop = slatTop - st
            for ((x, y) in listOf(rt to rt, w - rt - leg to rt, rt to l - rt - leg, w - rt - leg to l - rt - leg)) out += box(x, y, 0.0, leg, leg, legTop)
            // Cleats along the side rails between the legs carry the slats' ends.
            out += box(rt, rt + leg, legTop - cleat, rt, ml - 2 * leg, cleat); out += box(w - 2 * rt, rt + leg, legTop - cleat, rt, ml - 2 * leg, cleat)
            // A centre rail on edge, end rail to end rail, on two legs of its own.
            val cr = 2 * rt; val crDepth = minOf(rd, 140.0)
            val cx = w / 2 - cr / 2
            out += box(cx, rt, legTop - crDepth, cr, ml, crDepth)
            for (y in listOf(rt + ml / 3 - leg / 2, rt + 2 * ml / 3 - leg / 2)) out += box(w / 2 - leg / 2, y, 0.0, leg, leg, legTop - crDepth)
            // Slats across, evenly spread between the legs.
            val run = ml - 2 * leg
            val n = maxOf(2, Math.floor((run + gap) / (sw + gap)).toInt())
            val pitch = if (n > 1) (run - sw) / (n - 1) else 0.0
            for (i in 0 until n) out += box(rt, rt + leg + i * pitch, legTop, mw, sw, st)
            out
        },
        Template("box", "Open box", "A bottom with four sides around it: a crate, planter or drawer box.", listOf(
            Field("w", "Width", 400.0, 16.0), Field("d", "Depth", 300.0, 12.0), Field("h", "Height", 200.0, 8.0), Field("t", "Side thickness", 12.0, 0.5), Field("bt", "Bottom thickness", 6.0, 0.25),
        )) { v ->
            val w = v.getValue("w"); val d = v.getValue("d"); val h = v.getValue("h"); val t = v.getValue("t"); val bt = v.getValue("bt")
            listOf(box(0.0, 0.0, 0.0, w, t, h), box(0.0, d - t, 0.0, w, t, h), box(0.0, t, 0.0, t, d - 2 * t, h), box(w - t, t, 0.0, t, d - 2 * t, h), box(t, t, 0.0, w - 2 * t, d - 2 * t, bt))
        },
        // For showing work: a gallery plinth, a picture ledge, a crate to ship a painting in.
        Template("plinth", "Display plinth", "A gallery pedestal: four sides butted round a top set in flush, cleats under the top, and a low shelf on cleats to hold ballast so it cannot tip. Open at the bottom.", listOf(
            Field("w", "Width", 400.0, 16.0), Field("d", "Depth", 400.0, 16.0), Field("h", "Height", 1050.0, 42.0), Field("t", "Board thickness", 18.0, 0.75),
            Field("cleat", "Cleat size", 19.0, 0.75), Field("shelf", "Ballast shelf height", 100.0, 4.0),
        )) { v ->
            val w = v.getValue("w"); val d = v.getValue("d"); val h = v.getValue("h"); val t = v.getValue("t"); val c = v.getValue("cleat"); val sh = v.getValue("shelf")
            listOf(box(0.0, 0.0, 0.0, w, t, h), box(0.0, d - t, 0.0, w, t, h), box(0.0, t, 0.0, t, d - 2 * t, h), box(w - t, t, 0.0, t, d - 2 * t, h),
                box(t, t, h - t, w - 2 * t, d - 2 * t, t),
                box(t, t, h - t - c, w - 2 * t, c, c), box(t, d - t - c, h - t - c, w - 2 * t, c, c),
                box(t, t, sh, w - 2 * t, c, c), box(t, d - t - c, sh, w - 2 * t, c, c), box(t, t, sh + c, w - 2 * t, d - 2 * t, t))
        },
        Template("easel", "Easel", "A studio A-frame easel: two front legs leaning in and back, joined by a block at the top, a ledge for the canvas and a brace, and a back leg hinged to the block so it folds.", listOf(
            Field("h", "Height", 1830.0, 72.0), Field("spread", "Front legs apart at the floor", 660.0, 26.0), Field("lean", "Lean back of the front legs", 380.0, 15.0),
            Field("reach", "Back leg's foot behind the front legs", 760.0, 30.0), Field("ledge", "Ledge height", 760.0, 30.0), Field("lw", "Leg width", 45.0, 1.75), Field("t", "Thickness", 19.0, 0.75),
        )) { v ->
            val h = v.getValue("h"); val sp = v.getValue("spread"); val lean = v.getValue("lean"); val reach = v.getValue("reach"); val zl = v.getValue("ledge"); val w = v.getValue("lw"); val t = v.getValue("t")
            val block = 50.0; val hl = h - block; val xt = w * 1.6
            if (zl + 90.0 > hl * 0.8 || sp / 2 < xt + w) throw SizesDoNotFit()
            val left = leaner(-sp / 2, 0.0, 0.0, -xt, lean, hl, w, t); val right = leaner(sp / 2, 0.0, 0.0, xt, lean, hl, w, t)
            // The legs' tops, as cut: the block sits on them, across.
            val topY = right.bounds.let { b -> doubleArrayOf(b.min.y, b.max.y) }.let { _ -> val vs = right.vertices; (0 until right.vertexCount).filter { vs[3 * it + 2] > hl - 0.01 }.map { vs[3 * it + 1] } }
            val yf = topY.min(); val yb = topY.max()
            val out = arrayListOf(left, right, box(-xt - w / 2, yf, hl, 2 * xt + w, yb - yf, block))
            // The back leg, its top against the block's back, hinged there.
            val back = leaner(0.0, yb + reach, 0.0, 0.0, yb + t / 2 + 2.0, h - 12.0, w, t)
            val bTop = back.vertices.let { vs -> (0 until back.vertexCount).filter { vs[3 * it + 2] > h - 12.01 }.minOf { vs[3 * it + 1] } }
            out += splitziner.mesh.MeshOps.translated(back, Vec3(0.0, yb - bTop, 0.0))
            // Ledge and brace across the front faces of the legs, touching them along their lower edges.
            fun frontY(z: Double): Double { val vs = right.vertices; val lo = (0 until right.vertexCount).filter { vs[3 * it + 2] < 0.01 }.minOf { vs[3 * it + 1] }
                val hi = (0 until right.vertexCount).filter { vs[3 * it + 2] > hl - 0.01 }.minOf { vs[3 * it + 1] }; return lo + (hi - lo) * z / hl }
            fun halfX(z: Double) = sp / 2 - (sp / 2 - xt) * z / hl + w / 2
            out += box(-halfX(zl) - 25.0, frontY(zl) + 0.3 - t, zl, 2 * halfX(zl) + 50.0, t, 90.0)
            val zb = zl + (hl - zl) * 0.6
            out += box(-halfX(zb + 63.0), frontY(zb) + 0.3 - t, zb, 2 * halfX(zb + 63.0), t, 63.0)
            out
        },
        Template("ledge", "Picture ledge", "A shelf for leaning framed work against a wall: a back board screwed to the wall, a shelf on its face, and a lip along the front so frames cannot slide off.", listOf(
            Field("w", "Length", 1200.0, 48.0), Field("d", "Shelf depth", 90.0, 3.5), Field("back", "Back board height", 100.0, 4.0), Field("lip", "Lip height", 45.0, 1.75),
            Field("t", "Board thickness", 18.0, 0.75),
        )) { v ->
            val w = v.getValue("w"); val d = v.getValue("d"); val bh = v.getValue("back"); val lip = v.getValue("lip"); val t = v.getValue("t")
            val z = minOf(25.0, bh - t - 1.0)
            listOf(box(0.0, 0.0, 0.0, w, t, bh), box(0.0, -d, z, w, d, t), box(0.0, -d - t, z, w, t, lip))
        },
        Template("crate", "Art crate", "A crate to ship a framed painting: a frame of boards on edge round it, plywood screwed on both faces (one is the lid), and battens across the lid to stiffen it and lift it.", listOf(
            Field("w", "Inside width", 1000.0, 40.0), Field("h", "Inside height", 800.0, 32.0), Field("d", "Inside depth", 120.0, 5.0), Field("t", "Frame thickness", 18.0, 0.75),
            Field("ply", "Plywood thickness", 12.0, 0.5), Field("bat", "Batten width", 90.0, 3.5),
        )) { v ->
            val iw = v.getValue("w"); val ih = v.getValue("h"); val id = v.getValue("d"); val t = v.getValue("t"); val p = v.getValue("ply"); val bw = v.getValue("bat")
            val W = iw + 2 * t; val H = ih + 2 * t
            listOf(box(0.0, p, 0.0, W, id, t), box(0.0, p, H - t, W, id, t), box(0.0, p, t, t, id, H - 2 * t), box(W - t, p, t, t, id, H - 2 * t),
                box(0.0, 0.0, 0.0, W, p, H), box(0.0, p + id, 0.0, W, p, H),
                box(0.0, -t, 0.0, bw, t, H), box(W / 2 - bw / 2, -t, 0.0, bw, t, H), box(W - bw, -t, 0.0, bw, t, H))
        },
        Template("taboret", "Taboret", "A painter's rolling cart: a carcass on four casters, drawers on slides over an open shelf, and a top with a gallery rail round the back and sides so brushes and tubes stay put.", listOf(
            Field("w", "Width", 508.0, 20.0), Field("d", "Depth", 457.0, 18.0), Field("h", "Height, casters included", 813.0, 32.0),
            Field("n", "Drawers", 3.0, 3.0, count = true), Field("shelf", "Open shelf height", 200.0, 8.0), Field("caster", "Caster height", 76.0, 3.0),
            Field("gallery", "Gallery rail height", 50.0, 2.0), Field("t", "Board thickness", 18.0, 0.75), Field("dt", "Drawer box thickness", 12.0, 0.5),
        )) { v ->
            val w = v.getValue("w"); val d = v.getValue("d"); val h = v.getValue("h"); val t = v.getValue("t"); val dt = v.getValue("dt")
            val c = v.getValue("caster"); val sh = v.getValue("shelf"); val g = v.getValue("gallery")
            val n = v.getValue("n").toInt().coerceIn(1, 6)
            val back = 6.0; val inner = w - 2 * t; val dIn = d - back; val top = h - g - t
            val out = arrayListOf(box(0.0, 0.0, c, t, d, top - c), box(w - t, 0.0, c, t, d, top - c),
                box(t, 0.0, c, inner, dIn, t), box(t, 0.0, c + t + sh, inner, dIn, t), box(t, d - back, c, inner, back, top - c),
                box(0.0, 0.0, top, w, d, t),
                box(0.0, d - t, top + t, w, t, g), box(0.0, 0.0, top + t, t, d - t, g), box(w - t, 0.0, top + t, t, d - t, g))
            // Casters drawn as their overall size, a little in from each corner of the bottom.
            val r = minOf(35.0, 0.12 * minOf(w, d)); val inset = r + 15.0
            for ((x, y) in listOf(inset to inset, w - inset to inset, inset to d - inset, w - inset to d - inset)) out += cylZ(x, y, 0.0, r, c)
            // Drawers share the space between the shelf and the top, as the chest of drawers does.
            val z0 = c + 2 * t + sh; val share = (top - z0) / n
            if (share < 60.0) throw SizesDoNotFit()
            val slide = 12.7; val bx0 = t + slide; val bx1 = w - t - slide; val y1 = dIn - 10.0
            for (k in 0 until n) {
                val zs = z0 + k * share; val zb = zs + 6.0; val bh = share - 12.0
                out += box(bx0, 0.0, zb, bx1 - bx0, dt, bh); out += box(bx0, y1 - dt, zb, bx1 - bx0, dt, bh)
                out += box(bx0, dt, zb, dt, y1 - 2 * dt, bh); out += box(bx1 - dt, dt, zb, dt, y1 - 2 * dt, bh)
                out += box(bx0 + dt, dt, zb, bx1 - bx0 - 2 * dt, y1 - 2 * dt, 6.0)
                out += box(t + 2.0, -t, zs + 2.0, inner - 4.0, t, share - 4.0)
            }
            out
        },
        Template("frame", "Picture frame", "A frame for a picture or print, drawn lying face down as it is made: four face bars mitred at the corners, with four narrower mitred strips glued behind them so the opening steps out into a rabbet the art, glass and backing drop into. No router needed.", listOf(
            Field("aw", "Art width", 279.4, 11.0), Field("ah", "Art height", 355.6, 14.0),
            Field("bar", "Face bar width", 50.0, 2.0), Field("t", "Face bar thickness", 19.0, 0.75),
            Field("lip", "Lip over the art", 10.0, 0.375), Field("st", "Back strip thickness", 12.0, 0.5),
        )) { v ->
            val b = v.getValue("bar"); val t = v.getValue("t"); val r = v.getValue("lip"); val st = v.getValue("st")
            if (r >= b - 6.0) throw SizesDoNotFit()
            // A little play round the art (1/8 in in all) so it drops in.
            val iw = v.getValue("aw") + 3.0; val ih = v.getValue("ah") + 3.0
            val ow = iw - 2 * r + 2 * b; val oh = ih - 2 * r + 2 * b
            val sb = b - r
            fun ring(w0: Double, z0: Double, z1: Double, bw: Double) = listOf(
                quadZ(listOf(0.0 to 0.0, ow to 0.0, ow - bw to bw, bw to bw), z0, z1),
                quadZ(listOf(bw to oh - bw, ow - bw to oh - bw, ow to oh, 0.0 to oh), z0, z1),
                quadZ(listOf(0.0 to 0.0, bw to bw, bw to oh - bw, 0.0 to oh), z0, z1),
                quadZ(listOf(ow to 0.0, ow to oh, ow - bw to oh - bw, ow - bw to bw), z0, z1))
            // Face down on the bench: the face bars at the bottom, the strips on their backs.
            ring(ow, 0.0, t, b) + ring(ow, t, t + st, sb)
        },
        Template("stretcher", "Canvas stretcher", "A frame to stretch a canvas on, drawn lying face down on the bench as it is made: four bars mitred at the corners, with a brace across the middle once a side passes 30 in (a cross when both do). Fixed corners: a strainer, with no expansion keys.", listOf(
            Field("w", "Outside width", 610.0, 24.0), Field("h", "Outside height", 762.0, 30.0),
            Field("bar", "Bar width", 50.0, 2.0), Field("t", "Bar depth", 19.0, 0.75),
        )) { v ->
            val w = v.getValue("w"); val h = v.getValue("h"); val b = v.getValue("bar"); val t = v.getValue("t")
            if (w < 3 * b || h < 3 * b) throw SizesDoNotFit()
            val out = arrayListOf(
                quadZ(listOf(0.0 to 0.0, w to 0.0, w - b to b, b to b), 0.0, t),
                quadZ(listOf(b to h - b, w - b to h - b, w to h, 0.0 to h), 0.0, t),
                quadZ(listOf(0.0 to 0.0, b to b, b to h - b, 0.0 to h), 0.0, t),
                quadZ(listOf(w to 0.0, w to h, w - b to h - b, w - b to b), 0.0, t))
            val across = h > BRACE_OVER; val upright = w > BRACE_OVER
            // Across the middle, side to side; the other one, when both, in two lengths either side of it.
            if (across) out += box(b, h / 2 - b / 2, 0.0, w - 2 * b, b, t)
            if (upright && across) { out += box(w / 2 - b / 2, b, 0.0, b, h / 2 - b / 2 - b, t); out += box(w / 2 - b / 2, h / 2 + b / 2, 0.0, b, h / 2 - b / 2 - b, t) }
            else if (upright) out += box(w / 2 - b / 2, b, 0.0, b, h - 2 * b, t)
            out
        },
        Template("rack", "Canvas rack", "A floor rack to store paintings on edge: a box open at the front and top, split into slots by plywood dividers, with a rail across the front top to tie the dividers.", listOf(
            Field("w", "Width", 1200.0, 48.0), Field("d", "Depth", 500.0, 20.0), Field("h", "Height", 900.0, 36.0),
            Field("slots", "Slots", 6.0, 6.0, count = true), Field("t", "Board thickness", 18.0, 0.75), Field("ply", "Divider thickness", 12.0, 0.5), Field("rail", "Front rail height", 75.0, 3.0),
        )) { v ->
            val w = v.getValue("w"); val d = v.getValue("d"); val h = v.getValue("h"); val t = v.getValue("t"); val p = v.getValue("ply"); val r = v.getValue("rail")
            val n = maxOf(2, Math.round(v.getValue("slots")).toInt())
            val out = arrayListOf(box(0.0, 0.0, 0.0, t, d, h), box(w - t, 0.0, 0.0, t, d, h), box(t, d - t, 0.0, w - 2 * t, t, h),
                box(t, 0.0, 0.0, w - 2 * t, d - t, t), box(t, 0.0, h - r, w - 2 * t, t, r))
            // Slots of equal width between the ends.
            val slot = (w - 2 * t - (n - 1) * p) / n
            for (k in 1 until n) out += box(t + k * slot + (k - 1) * p, 0.0, t, p, d - t, h - r - t)
            out
        },
    )

    private fun table(v: Map<String, Double>): List<Mesh> {
        val apronThick = v.getValue("at")
        val w = v.getValue("w"); val d = v.getValue("d"); val h = v.getValue("h"); val t = v.getValue("t"); val leg = v.getValue("leg"); val ap = v.getValue("apron")
        // Legs set in from the top's edge by a little overhang, aprons centred on the legs' faces.
        val over = v["over"] ?: minOf(30.0, 0.05 * minOf(w, d)); val lh = h - t
        val out = ArrayList<Mesh>()
        out += box(0.0, 0.0, lh, w, d, t)
        for ((x, y) in listOf(over to over, w - over - leg to over, over to d - over - leg, w - over - leg to d - over - leg)) out += box(x, y, 0.0, leg, leg, lh)
        val inset = (leg - apronThick) / 2
        out += box(over + leg, over + inset, lh - ap, w - 2 * over - 2 * leg, apronThick, ap)
        out += box(over + leg, d - over - leg + inset, lh - ap, w - 2 * over - 2 * leg, apronThick, ap)
        out += box(over + inset, over + leg, lh - ap, apronThick, d - 2 * over - 2 * leg, ap)
        out += box(w - over - leg + inset, over + leg, lh - ap, apronThick, d - 2 * over - 2 * leg, ap)
        return out
    }

    fun byKey(key: String) = ALL.first { it.key == key }

    /** Default sizes in millimetres for a metric or an inch user. */
    fun defaults(t: Template, inch: Boolean): Map<String, Double> = t.fields.associate { f -> f.key to if (f.count) f.mm else if (inch) f.inch * 25.4 else f.mm }
}
