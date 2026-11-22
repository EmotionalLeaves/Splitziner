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
    class Field(val key: String, val label: String, val mm: Double, val inch: Double = mm / 25.4, val count: Boolean = false)

    class Template(val key: String, val name: String, val blurb: String, val fields: List<Field>, val build: (Map<String, Double>) -> List<Mesh>)

    private fun box(x: Double, y: Double, z: Double, w: Double, d: Double, h: Double) = Editor.box(Vec3(x, y, z), Vec3(w, d, h))

    val ALL: List<Template> = listOf(
        Template("shelves", "Bookcase", "Two sides, a top and bottom between them, evenly spaced shelves, a back panel and a kick board.", listOf(
            Field("w", "Width", 800.0, 32.0), Field("h", "Height", 1800.0, 72.0), Field("d", "Depth", 300.0, 12.0),
            Field("t", "Board thickness", 18.0, 0.75), Field("n", "Shelves", 4.0, 4.0, count = true), Field("back", "Back thickness (0 for none)", 6.0, 0.25),
        )) { v ->
            val w = v.getValue("w"); val h = v.getValue("h"); val d = v.getValue("d"); val t = v.getValue("t"); val back = v.getValue("back")
            val n = v.getValue("n").toInt().coerceIn(0, 20)
            val inner = w - 2 * t; val dIn = d - back
            val kick = 3 * t + 40.0
            val out = arrayListOf(box(0.0, 0.0, 0.0, t, d, h), box(w - t, 0.0, 0.0, t, d, h),
                box(t, 0.0, kick, inner, dIn, t), box(t, 0.0, h - t, inner, dIn, t), box(t, 0.0, 0.0, inner, t, kick))
            // Shelves evenly spaced in the clear height between bottom and top.
            val clear0 = kick + t; val clear1 = h - t
            val gap = (clear1 - clear0 - n * t) / (n + 1)
            for (i in 1..n) out += box(t, 0.0, clear0 + i * gap + (i - 1) * t, inner, dIn, t)
            if (back > 0) out += box(t, d - back, kick, inner, back, h - kick)
            out
        },
        Template("cabinet", "Cabinet", "A box with sides, top, bottom, one shelf and a back: the carcass of a cupboard.", listOf(
            Field("w", "Width", 600.0, 24.0), Field("h", "Height", 720.0, 30.0), Field("d", "Depth", 560.0, 24.0), Field("t", "Board thickness", 18.0, 0.75), Field("back", "Back thickness (0 for none)", 6.0, 0.25),
        )) { v ->
            val w = v.getValue("w"); val h = v.getValue("h"); val d = v.getValue("d"); val t = v.getValue("t"); val back = v.getValue("back")
            val inner = w - 2 * t; val dIn = d - back
            val out = arrayListOf(box(0.0, 0.0, 0.0, t, d, h), box(w - t, 0.0, 0.0, t, d, h),
                box(t, 0.0, 0.0, inner, dIn, t), box(t, 0.0, h - t, inner, dIn, t), box(t, 0.0, (h - t) / 2, inner, dIn - 20.0, t))
            if (back > 0) out += box(t, d - back, t, inner, back, h - 2 * t)
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
        Template("box", "Open box", "A bottom with four sides around it: a crate, planter or drawer box.", listOf(
            Field("w", "Width", 400.0, 16.0), Field("d", "Depth", 300.0, 12.0), Field("h", "Height", 200.0, 8.0), Field("t", "Side thickness", 12.0, 0.5), Field("bt", "Bottom thickness", 6.0, 0.25),
        )) { v ->
            val w = v.getValue("w"); val d = v.getValue("d"); val h = v.getValue("h"); val t = v.getValue("t"); val bt = v.getValue("bt")
            listOf(box(0.0, 0.0, 0.0, w, t, h), box(0.0, d - t, 0.0, w, t, h), box(0.0, t, 0.0, t, d - 2 * t, h), box(w - t, t, 0.0, t, d - 2 * t, h), box(t, t, 0.0, w - 2 * t, d - 2 * t, bt))
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
