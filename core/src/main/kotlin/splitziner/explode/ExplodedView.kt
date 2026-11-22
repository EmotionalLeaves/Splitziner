package splitziner.explode

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import splitziner.math.Vec2
import splitziner.math.Vec3
import splitziner.mesh.Mesh
import splitziner.mesh.MeshOps
import splitziner.parts.Part
import splitziner.sheet.Box
import splitziner.sheet.DisplayList
import splitziner.sheet.HAlign
import splitziner.sheet.LineStyle
import splitziner.sheet.StrokeFont
import splitziner.sheet.VAlign
import splitziner.view.HiddenLine
import splitziner.view.ViewDef
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What to add to an exploded picture. */
class ExplodeOptions(
    val balloons: Boolean = true,
    /** Bold insertion arrows, one per group of parts that move together. */
    val arrows: Boolean = false,
    /** "×4" tags beside groups of identical parts. */
    val qtyTags: Boolean = false,
    /** Ids of the parts being added in this step; others are drawn lighter. Empty = no emphasis. */
    val highlight: Set<String> = emptySet(),
    /** Ids of parts already installed, drawn in grey. */
    val ghost: Set<String> = emptySet(),
    /** Lettering height for balloons, in mm. */
    val textHeight: Double = 3.0,
    val title: String? = null,
)

/** Exploded line work in view coordinates, ready to be drawn at any scale. */
class ExplodedGeometry(
    val parts: List<Part>,
    val view: ViewDef,
    val offsets: List<Vec3>,
    /** Visible segments per part: x0,y0,x1,y1. */
    val segments: List<DoubleArray>,
    val silhouettes: List<Geometry>,
    val traces: List<DoubleArray>,
    val moved: List<Mesh>,
    val scene: Mesh,
    /** minX, minY, maxX, maxY of everything. */
    val bounds: DoubleArray,
)

/**
 * Engineering "pull-apart" views.
 *
 * Each moving part travels along the direction it is installed from, just far enough to clear
 * everything else in the view; parts in one group travel the same distance; the base stays put.
 * Thin chain lines trace each part back to its seat. The view is chosen so those paths run across
 * the picture, never straight at the viewer.
 */
object ExplodedView {
    /** Clearance between exploded parts, as a fraction of the moving part's size. */
    private const val GAP = 0.25

    private fun keyOf(p: Part): String = p.group ?: p.item?.toString() ?: p.id

    /** Extent of the assembled parts as seen in [view]: minX, minY, maxX, maxY. */
    fun assembledBounds(parts: List<Part>, view: ViewDef): DoubleArray {
        val bb = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (p in parts) {
            val b = p.mesh.bounds
            for (c in 0 until 8) {
                val q = view.project(Vec3(if (c and 1 == 0) b.min.x else b.max.x, if (c and 2 == 0) b.min.y else b.max.y, if (c and 4 == 0) b.min.z else b.max.z))
                if (q.x < bb[0]) bb[0] = q.x; if (q.x > bb[2]) bb[2] = q.x; if (q.y < bb[1]) bb[1] = q.y; if (q.y > bb[3]) bb[3] = q.y
            }
        }
        return bb
    }

    /** Picks the isometric corner to look from. */
    fun pickView(parts: List<Part>): ViewDef {
        val dirs = LinkedHashMap<String, Vec3>()
        for (p in parts) { val e = p.explode ?: continue; if (e.length > 1e-9) dirs.getOrPut(keyOf(p)) { e.normalized() } }
        if (dirs.isEmpty()) return ViewDef.ISO
        var best: ViewDef? = null; var bestScore = Double.NEGATIVE_INFINITY
        for (sx in intArrayOf(1, -1)) for (sy in intArrayOf(1, -1)) for (sz in intArrayOf(1, -1)) {
            val n = Vec3(sx.toDouble(), -sy.toDouble(), sz.toDouble()).normalized()
            // World Z stays up on screen.
            val up = (Vec3(0.0, 0.0, 1.0) - n * n.z).normalized()
            val x = up cross n
            val across = dirs.values.sumOf { (it cross n).length } / dirs.size
            val side = dirs.values.sumOf { Math.signum(it dot n) * 0.25 }
            // Seeing the top and the front is what people expect of a pictorial.
            val score = across + side + (if (sz > 0) 0.3 else 0.0) + (if (sy > 0) 0.4 else 0.0)
            if (score > bestScore + 1e-9) { bestScore = score; best = ViewDef("exploded", n, x) }
        }
        return best!!
    }

    /** How far each part moves, in model mm. Parts without an explode vector stay where they are. */
    fun planOffsets(parts: List<Part>, view: ViewDef, outlines: List<Geometry>, minTravel: Double = 0.0): List<Vec3> {
        val offs = MutableList(parts.size) { Vec3.ZERO }
        // Outlines already in place, in a spatial index: with hundreds of parts, testing each new position
        // against every one of them was most of the work.
        val placed = ArrayList<Geometry>(); val boxes = ArrayList<org.locationtech.jts.geom.Envelope>()
        fun place(g: Geometry) { if (!g.isEmpty) { placed += g; boxes += g.envelopeInternal } }
        // A box test first: most outlines are nowhere near, and the exact test is costly.
        fun hits(g: Geometry): Boolean { val e = g.envelopeInternal; for (i in placed.indices) if (boxes[i].intersects(e) && g.intersects(placed[i])) return true; return false }
        for ((i, p) in parts.withIndex()) if (p.explode == null || p.explode.length < 1e-9) place(outlines[i])
        val groups = LinkedHashMap<String, MutableList<Int>>()
        for ((i, p) in parts.withIndex()) if (p.explode != null && p.explode.length >= 1e-9) groups.getOrPut(keyOf(p)) { ArrayList() } += i
        // Groups are placed in build order, so each new part clears everything installed before it.
        for (idx in groups.values.sortedBy { g -> g.minOf { parts[it].step ?: Int.MAX_VALUE } }) {
            var need = 0.0
            for (i in idx) {
                val v = parts[i].explode!!; val l0 = v.length; val d = v / l0
                val b = parts[i].mesh.bounds
                val size = (abs(d.x) * b.extents.x + abs(d.y) * b.extents.y + abs(d.z) * b.extents.z).let { if (it > 0) it else 1.0 }
                var l = l0
                val step = maxOf(0.08 * size, 0.5)
                val clearance = GAP * maxOf(size, 5.0) * 0.5
                val grown = outlines[i].buffer(clearance)
                val d2 = Vec2(d dot view.x, d dot view.up)
                // Travel grows until the part's outline, with clearance, misses everything already placed.
                while (l < l0 + 12 * maxOf(size, 10.0)) {
                    val shifted = Silhouette.translated(grown, d2.x * l, d2.y * l)
                    if (!hits(shifted)) break
                    l += step
                }
                need = maxOf(need, l + GAP * size * 0.5)
            }
            // A thin panel clears its neighbours after moving a hair; a readable picture needs a visible gap.
            need = maxOf(need, minTravel)
            for (i in idx) {
                val d = parts[i].explode!!.normalized()
                offs[i] = d * need
                place(Silhouette.translated(outlines[i], (offs[i] dot view.x), (offs[i] dot view.up)))
            }
        }
        return offs
    }

    /** Per part id, how far it moves when the model is pulled apart for an interactive 3D view. */
    fun offsetsById(parts: List<Part>, minTravelShare: Double = 0.15): Map<String, Vec3> {
        val view = pickView(parts)
        val outlines = parts.map { Silhouette.outline(it.mesh, view) }
        val whole = parts.map { it.mesh.bounds }.reduceOrNull { a, b -> a.union(b) }
        val o = planOffsets(parts, view, outlines, minTravelShare * (whole?.diagonal ?: 0.0))
        return parts.indices.associate { parts[it].id to o[it] }
    }

    /** [fitBounds] (minX, minY, maxX, maxY in view coordinates) is included in the picture's extent, so a series of pictures can share one framing. */
    fun prepare(parts: List<Part>, view: ViewDef? = null, fitBounds: DoubleArray? = null, minTravelShare: Double = 0.12): ExplodedGeometry {
        val v = view ?: pickView(parts)
        // Each part's outline is separate work; on a phone's several cores they are traced side by side.
        val outlines0: List<Geometry> = Silhouette.outlines(parts.map { it.mesh }, v)
        val whole = parts.map { it.mesh.bounds }.reduceOrNull { a, b -> a.union(b) }
        val offs = planOffsets(parts, v, outlines0, minTravelShare * (whole?.diagonal ?: 0.0))
        val moved = parts.mapIndexed { i, p -> if (offs[i].length < 1e-9) p.mesh else MeshOps.translated(p.mesh, offs[i]) }
        val scene = MeshOps.concatenate(moved)
        val scale = scene.scale
        // Each part against the shared scene, side by side on the phone's cores; the search tree is built once first.
        scene.bvh; moved.forEach { it.topology; it.faceNormals; if (it.isWatertight) it.bvh }
        val segs: List<DoubleArray> = if (moved.size < 16) moved.map { m -> HiddenLine.project(HiddenLine.split(m, scene, v, scale, 120.0).visible, v) }
            else moved.parallelStream().map { m -> HiddenLine.project(HiddenLine.split(m, scene, v, scale, 120.0).visible, v) }.collect(java.util.stream.Collectors.toList())
        val sil = parts.indices.map { i -> Silhouette.translated(outlines0[i], offs[i] dot v.x, offs[i] dot v.up) }

        // Each moved part's box, so a trace point is tested for being inside only the parts it could be inside.
        val movedBounds = moved.map { it.bounds }
        // Trace lines: from just past each exploded part back into its seat, drawn only where nothing hides them.
        val lift = v.n * (1e-3 * scale)
        // Fewer samples per trace on big assemblies: each is a short line, and 600 parts x 160 rays took seconds.
        val samples = if (parts.size > 100) 48 else 160
        fun traceOf(i: Int): List<DoubleArray> {
            val p = parts[i]; val o = offs[i]
            if (o.length < 1e-9) return emptyList()
            val out = ArrayList<DoubleArray>()
            val d = o.normalized()
            val b = p.mesh.bounds
            val ext = abs(d.x) * b.extents.x + abs(d.y) * b.extents.y + abs(d.z) * b.extents.z
            val a = b.center
            val from = a + o + d * (ext * 0.15); val to = a - d * (ext * 0.15)
            var run = ArrayList<Double>()
            for (k in 0 until samples) {
                val q = from + (to - from) * (k / (samples - 1.0))
                var ok = !scene.bvh.intersectsAny(q + lift, v.n)
                if (ok) for (mi in moved.indices) { val bb = movedBounds[mi]; if (q.x < bb.min.x || q.x > bb.max.x || q.y < bb.min.y || q.y > bb.max.y || q.z < bb.min.z || q.z > bb.max.z) continue
                    val m = moved[mi]; if (m.isWatertight && m.bvh.contains(q)) { ok = false; break } }
                if (ok) { val s = v.project(q); run.add(s.x); run.add(s.y) }
                else { if (run.size >= 4) out += run.toDoubleArray(); run = ArrayList() }
            }
            if (run.size >= 4) out += run.toDoubleArray()
            return out
        }
        val traces: List<DoubleArray> = (if (parts.size < 16) parts.indices.map { traceOf(it) }
            else parts.indices.toList().parallelStream().map { traceOf(it) }.collect(java.util.stream.Collectors.toList())).flatten()

        val bb = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (a in segs + traces) { var i = 0; while (i < a.size) { if (a[i] < bb[0]) bb[0] = a[i]; if (a[i] > bb[2]) bb[2] = a[i]; if (a[i + 1] < bb[1]) bb[1] = a[i + 1]; if (a[i + 1] > bb[3]) bb[3] = a[i + 1]; i += 2 } }
        if (fitBounds != null) { bb[0] = minOf(bb[0], fitBounds[0]); bb[1] = minOf(bb[1], fitBounds[1]); bb[2] = maxOf(bb[2], fitBounds[2]); bb[3] = maxOf(bb[3], fitBounds[3]) }
        if (bb[0] > bb[2]) { bb[0] = 0.0; bb[1] = 0.0; bb[2] = 1.0; bb[3] = 1.0 }
        return ExplodedGeometry(parts, v, offs, segs, sil, traces, moved, scene, bb)
    }

    /** A standalone picture of the given size in mm. */
    fun render(parts: List<Part>, widthMm: Double, heightMm: Double, opt: ExplodeOptions, view: ViewDef? = null, name: String = "exploded", fitBounds: DoubleArray? = null): DisplayList {
        val dl = DisplayList(widthMm, heightMm, name)
        val geo = prepare(parts, view, fitBounds, if (opt.arrows) 0.2 else 0.12)
        val top = heightMm - 2 - (if (opt.title != null) 8 else 0)
        drawInto(dl, geo, 2.0, 2.0, widthMm - 2, top, opt)
        if (opt.title != null) dl.text(widthMm / 2, heightMm - 6, opt.title, 4.5, HAlign.CENTER, VAlign.CENTER, bold = true)
        return dl
    }

    /** Draws [geo] scaled to fit the rectangle, leaving room around it for balloons. */
    /** Returns the item numbers that got a balloon. */
    fun drawInto(dl: DisplayList, geo: ExplodedGeometry, rx0: Double, ry0: Double, rx1: Double, ry1: Double, opt: ExplodeOptions): Set<Int> {
        val pad = if (opt.balloons) 2.7 * opt.textHeight + 7 else 4.0
        val ew = maxOf(geo.bounds[2] - geo.bounds[0], 1e-9); val eh = maxOf(geo.bounds[3] - geo.bounds[1], 1e-9)
        val s = minOf((rx1 - rx0 - 2 * pad) / ew, (ry1 - ry0 - 2 * pad) / eh)
        val ox = (rx0 + rx1) / 2 - (geo.bounds[0] + ew / 2) * s; val oy = (ry0 + ry1) / 2 - (geo.bounds[1] + eh / 2) * s
        fun px(x: Double) = ox + x * s
        fun py(y: Double) = oy + y * s
        fun paper(a: DoubleArray) = DoubleArray(a.size) { if (it % 2 == 0) px(a[it]) else py(a[it]) }

        for (t in geo.traces) dl.polyline(paper(t), LineStyle.TRACE)

        val dense = geo.parts.size > 15
        val partBoxes = ArrayList<Box?>()
        for ((i, p) in geo.parts.withIndex()) {
            val seg = geo.segments[i]
            if (seg.isEmpty()) { partBoxes += null; continue }
            val outline = ArrayList<Double>(); val inner = ArrayList<Double>()
            val boundary = geo.silhouettes[i].boundary
            val near = 0.35 / s
            var k = 0
            while (k < seg.size) {
                val mid = Silhouette.GF.createPoint(Coordinate((seg[k] + seg[k + 2]) / 2, (seg[k + 1] + seg[k + 3]) / 2))
                val target = if (!boundary.isEmpty && boundary.distance(mid) < near) outline else inner
                for (j in 0..3) target += seg[k + j]
                k += 4
            }
            val ghost = p.id in opt.ghost
            val emphasised = opt.highlight.isEmpty() || p.id in opt.highlight
            // Outlines are heavier than inside edges; busy pictures read better one step lighter.
            val (so, si) = when {
                ghost -> LineStyle.GHOST to LineStyle.GHOST
                dense -> LineStyle.THICK to LineStyle.THIN
                emphasised -> LineStyle.OUTLINE to LineStyle.THICK
                else -> LineStyle.THICK to LineStyle.THIN
            }
            dl.segs(paper(inner.toDoubleArray()), si); dl.segs(paper(outline.toDoubleArray()), so)
            val pp = paper(seg)
            var x0 = Double.POSITIVE_INFINITY; var y0 = x0; var x1 = Double.NEGATIVE_INFINITY; var y1 = x1
            var j = 0
            while (j < pp.size) { if (pp[j] < x0) x0 = pp[j]; if (pp[j] > x1) x1 = pp[j]; if (pp[j + 1] < y0) y0 = pp[j + 1]; if (pp[j + 1] > y1) y1 = pp[j + 1]; j += 2 }
            partBoxes += dl.register(x0, y0, x1, y1, "view")
        }

        // Insertion arrows and quantity tags, one per group of parts that move together.
        val seenArrow = HashSet<String>(); val seenQty = HashSet<String>()
        for ((i, p) in geo.parts.withIndex()) {
            val o = geo.offsets[i]
            val box = partBoxes[i] ?: continue
            if (o.length < 1e-9 || (opt.highlight.isNotEmpty() && p.id !in opt.highlight)) continue
            val key = keyOf(p)
            if (opt.arrows && seenArrow.add(key)) arrow(dl, geo, box, Vec2(-(o dot geo.view.x), -(o dot geo.view.up)), s, ox, oy)
            val same = geo.parts.indices.filter { keyOf(geo.parts[it]) == key && geo.parts[it].item == p.item && partBoxes[it] != null }
            if (opt.qtyTags && same.size > 1 && seenQty.add("$key/${p.item}")) {
                val gx0 = same.minOf { partBoxes[it]!!.x0 }; val gx1 = same.maxOf { partBoxes[it]!!.x1 }
                val gy0 = same.minOf { partBoxes[it]!!.y0 }; val gy1 = same.maxOf { partBoxes[it]!!.y1 }
                val txt = "×${same.size}"; val h = 4.5; val tw = StrokeFont.width(txt, h)
                val spots = listOf(
                    doubleArrayOf(gx1 + 3, (gy0 + gy1) / 2), doubleArrayOf(gx0 - 3 - tw, (gy0 + gy1) / 2),
                    doubleArrayOf((gx0 + gx1) / 2 - tw / 2, gy0 - 8), doubleArrayOf((gx0 + gx1) / 2 - tw / 2, gy1 + 4 + h / 2),
                )
                val spot = spots.firstOrNull { dl.overlapArea(Box(it[0] - 1, it[1] - 3, it[0] + tw + 1, it[1] + 3, "")) == 0.0 } ?: spots[0]
                dl.text(spot[0], spot[1], txt, h, vAlign = VAlign.CENTER, bold = true, tag = "qty")
            }
        }

        return if (opt.balloons) balloons(dl, geo, opt, ::px, ::py) else emptySet()
    }

    private fun arrow(dl: DisplayList, geo: ExplodedGeometry, box: Box, d2: Vec2, s: Double, ox: Double, oy: Double) {
        val travel = d2.length * s
        if (travel < 6) return
        val u = d2.normalized(); val perp = u.perp()
        val w = 1.8; val ln = minOf(16.0, 0.6 * travel)
        val c = Vec2((box.x0 + box.x1) / 2, (box.y0 + box.y1) / 2)
        val ext = Vec2(box.x1 - box.x0, box.y1 - box.y0)
        // Outlines of every part, on paper, slightly grown: the arrow must sit beside the parts, not on them.
        val paperOutlines = geo.silhouettes.map { g ->
            org.locationtech.jts.geom.util.AffineTransformation().scale(s, s).translate(ox, oy).transform(g).buffer(1.2)
        }
        var best: DoubleArray? = null; var bestCost = Double.MAX_VALUE; var bestBox: Box? = null
        search@ for (off in doubleArrayOf(5.0, 9.0, 14.0, 20.0, 28.0)) for (sgn in doubleArrayOf(1.0, -1.0)) for (along in doubleArrayOf(0.0, 0.5, -0.5)) {
            val base = c + perp * (sgn * (0.5 * abs(perp dot ext) + off)) + u * (along * ext.length * 0.5)
            val tail = base - u * (ln * 0.3); val tip = base + u * (ln * 0.7)
            val neck = tip - u * 4.5
            val pts = listOf(tail + perp * (w / 2), neck + perp * (w / 2), neck + perp * (w * 1.7), tip, neck - perp * (w * 1.7), neck - perp * (w / 2), tail - perp * (w / 2))
            val poly = Silhouette.GF.createPolygon((pts + pts[0]).map { Coordinate(it.x, it.y) }.toTypedArray())
            val bb = Box(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y }, "arrow")
            val cost = paperOutlines.sumOf { if (it.intersects(poly)) it.intersection(poly).area else 0.0 } + dl.overlapArea(bb, setOf("view"))
            if (cost < bestCost) { bestCost = cost; best = pts.flatMap { listOf(it.x, it.y) }.toDoubleArray(); bestBox = bb }
            if (cost == 0.0) break@search
        }
        if (best != null) { dl.fill(best); dl.boxes += bestBox!! }
    }

    /** One balloon per item: a dot on a visible face of the part, a leader, and the item number in a circle outside the picture. */
    private fun balloons(dl: DisplayList, geo: ExplodedGeometry, opt: ExplodeOptions, px: (Double) -> Double, py: (Double) -> Double): Set<Int> {
        val th = opt.textHeight; val r = 1.35 * th
        val showQty = !opt.qtyTags
        val lift = geo.view.n * (1e-3 * geo.scene.scale)
        class Target(val item: Int, val x: Double, val y: Double, val qty: Int)
        val targets = ArrayList<Target>()
        val seen = HashSet<Int>()
        for ((i, p) in geo.parts.withIndex()) {
            val item = p.item ?: continue
            if (opt.highlight.isNotEmpty() && p.id !in opt.highlight) continue
            if (item in seen) continue
            val m = geo.moved[i]
            val c2 = geo.view.project(m.bounds.center)
            // Spread sample points over the surface by area and keep the visible one nearest the part's middle.
            val rnd = Random(2)
            val areas = m.faceAreas; val total = m.area
            if (total <= 0) continue
            var best: Vec2? = null; var bestD = Double.MAX_VALUE
            repeat(400) {
                var pick = rnd.nextDouble() * total; var t = 0
                while (t < m.triangleCount - 1 && pick > areas[t]) { pick -= areas[t]; t++ }
                var a = rnd.nextDouble(); var b = rnd.nextDouble()
                if (a + b > 1) { a = 1 - a; b = 1 - b }
                val v0 = m.vertex(m.triangles[3 * t]); val v1 = m.vertex(m.triangles[3 * t + 1]); val v2 = m.vertex(m.triangles[3 * t + 2])
                val q = v0 + (v1 - v0) * a + (v2 - v0) * b
                if (!geo.scene.bvh.intersectsAny(q + lift, geo.view.n)) {
                    val s2 = geo.view.project(q); val d = (s2 - c2).length
                    if (d < bestD) { bestD = d; best = s2 }
                }
            }
            val hit = best ?: continue
            seen += item
            targets += Target(item, px(hit.x), py(hit.y), if (showQty) p.qty else 1)
        }
        if (targets.isEmpty()) return emptySet()
        val views = dl.boxes.filter { it.tag == "view" }
        if (views.isEmpty()) return emptySet()
        val gx0 = views.minOf { it.x0 }; val gy0 = views.minOf { it.y0 }; val gx1 = views.maxOf { it.x1 }; val gy1 = views.maxOf { it.y1 }
        val cx = (gx0 + gx1) / 2; val cy = (gy0 + gy1) / 2
        val rx = (gx1 - gx0) / 2 + r + 6; val ry = (gy1 - gy0) / 2 + r + 6
        for (t in targets.sortedBy { atan2(it.y - cy, it.x - cx) }) {
            val a0 = atan2(t.y - cy, t.x - cx)
            var bestC: Vec2? = null; var bestCost = Double.MAX_VALUE; var bestTie = Double.MAX_VALUE
            search@ for (grow in doubleArrayOf(0.0, 5.0, 10.0, 16.0, 24.0)) for (da in doubleArrayOf(0.0, 0.12, -0.12, 0.25, -0.25, 0.4, -0.4, 0.6, -0.6)) {
                val c = Vec2(cx + (rx + grow) * cos(a0 + da), cy + (ry + grow) * sin(a0 + da))
                val extra = if (t.qty > 1) 8.0 else 0.0
                val bb = Box(c.x - r - 1, c.y - r - 1, c.x + r + 1 + extra, c.y + r + 1, "balloon")
                val outside = if (bb.x0 < 0 || bb.y0 < 0 || bb.x1 > dl.widthMm || bb.y1 > dl.heightMm) 50.0 else 0.0
                val cost = dl.overlapArea(bb, setOf("view")) + views.sumOf { it.overlap(bb) } * 0.2 + outside +
                    (if (dl.segmentFree(c.x, c.y, t.x, t.y, setOf("view"))) 0.0 else 25.0)
                val tie = grow + abs(da) * 10
                if (cost < bestCost || (cost == bestCost && tie < bestTie)) { bestCost = cost; bestTie = tie; bestC = c }
                if (cost == 0.0) break@search
            }
            val c = bestC!!
            val dx = t.x - c.x; val dy = t.y - c.y; val len = sqrt(dx * dx + dy * dy).let { if (it > 0) it else 1.0 }
            dl.line(c.x + dx / len * r, c.y + dy / len * r, t.x, t.y)
            dl.dot(t.x, t.y, 0.7)
            dl.circle(c.x, c.y, r)
            dl.text(c.x, c.y, "${t.item}", th, HAlign.CENTER, VAlign.CENTER)
            if (t.qty > 1) dl.text(c.x + r + 1, c.y - r, "${t.qty}X", th * 0.85)
            dl.register(c.x - r - 0.5, c.y - r - 0.5, c.x + r + 0.5 + (if (t.qty > 1) 8.0 else 0.0), c.y + r + 0.5, "balloon")
        }
        return targets.map { it.item }.toSet()
    }

    /**
     * For a big assembly an all-parts explosion is unreadable. Keeps the explosion only on the last
     * build steps (the outer layer), up to [maxGroups] groups, so the core is shown assembled.
     */
    fun peel(parts: List<Part>, maxGroups: Int = 6): List<Part> {
        val groups = LinkedHashMap<String, Int>()
        for (p in parts) if (p.explode != null) groups.getOrPut(keyOf(p)) { p.step ?: 0 }
        if (groups.size <= maxGroups) return parts
        val keep = HashSet<Int>(); var count = 0
        for (st in groups.values.toSet().sortedDescending()) {
            val k = groups.values.count { it == st }
            if (count + k > maxGroups && keep.isNotEmpty()) break
            keep += st; count += k
        }
        return parts.map { if (it.explode != null && it.step !in keep) it.copy(explode = null) else it }
    }
}
