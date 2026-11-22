package splitziner.cli

import splitziner.export.CsvWriter
import splitziner.pipeline.AnalysisOptions
import splitziner.pipeline.Pipeline
import java.io.File
import kotlin.system.exitProcess

/** Runs the engine on a file and writes what it found; the way to check the engine without a phone. */
fun main(args: Array<String>) {
    if (args.isEmpty()) { System.err.println("usage: <file.stl> [--out <dir>] [--units mm|cm|in|m] [--display mm|in]"); exitProcess(1) }
    var out = File("build/out"); var units: String? = null; var display: String? = null; var file: String? = null; var full = false; var only: Set<String>? = null; var boards = false; var fix = false; var tools: Set<splitziner.check.Tool>? = null; var style = "decimal"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--out" -> out = File(args[++i])
            "--units" -> units = args[++i]
            "--display" -> display = args[++i]
            "--fraction" -> style = "fraction"
            "--full" -> full = true
            "--only" -> only = args[++i].split(',').toSet()
            "--boards" -> boards = true
            "--fix" -> fix = true
            "--tools" -> tools = splitziner.check.Tool.parse(args[++i].split(','))
            else -> file = args[i]
        }
        i++
    }
    try {
        // "template:shelves" builds a starting design and works on that.
        if (file?.startsWith("template:") == true) {
            val t = splitziner.edit.Templates.byKey(file!!.removePrefix("template:"))
            val tmp = File(out.apply { mkdirs() }, "${t.key}.stl")
            // TPL=key=value,... overrides template sizes; DRAWER=x,z adds a drawer to the opening round that point.
            var meshes = t.build(splitziner.edit.Templates.defaults(t, display == "in") + (System.getenv("TPL") ?: "").split(',').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=').toDouble() })
            System.getenv("DRAWER")?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { (x, z) ->
                val ps = meshes.mapIndexed { i, m -> splitziner.parts.Part("P$i", "P", m) }
                splitziner.edit.Editor.drawerAt(ps, x, z, 18.0)?.let { bs -> meshes = meshes + bs.map { (mn, sz) -> splitziner.edit.Editor.box(mn, sz) } }
            }
            tmp.outputStream().use { splitziner.io.StlWriter.writeParts(meshes, it) }
            file = tmp.path; if (units == null) units = "mm"
        }
        val f = File(file ?: error("no input file"))
        val a0 = Pipeline.analyze(f, AnalysisOptions(fileUnits = units, displayUnits = display, inchStyle = style))
        // --only P01,P05: keep just those parts, to look at one corner of a model.
        var a = if (only == null) a0 else Pipeline.analyze(f, AnalysisOptions(fileUnits = units, displayUnits = display, inchStyle = style, excludedPartIds = a0.parts.map { it.id }.filter { it !in only!! }.toSet()))
        out.mkdirs()
        File(out, "cutlist.csv").writeText(CsvWriter.cutList(a.cutList, a.format))
        val r = a.report
        println("file      ${f.name}: ${r.faces} triangles (${r.degenerateFaces} degenerate)")
        println("method    ${r.method}")
        println("bodies    ${r.bodies} -> ${r.parts} parts (${r.duplicatesRemoved} duplicates removed, ${r.watertightParts} watertight, ${r.tJunctionFixedParts} repaired)")
        println("units     file ${r.units.fileUnits}, design ${r.units.designUnits}, margin ${r.units.margin}, alternative ${r.units.alternative}")
        println("size      ${a.overallSize}")
        for (row in a.cutList.rows) println("item %2d  x%-3d %s".format(row.item, row.qty, row.description))
        for (w in r.warnings + a.cutList.warnings) println("warning   $w")
        println("timings   ${a.timingsMs}")
        for (p in a.parts) {
            val topo = p.mesh.topology
            val open = (0 until topo.edgeCount).count { topo.edgeUseCount[it] == 1 }; val many = (0 until topo.edgeCount).count { topo.edgeUseCount[it] > 2 }
            if (boards && p.kind != splitziner.parts.PartKind.ROD) {
                val t0 = System.nanoTime(); val (res, why) = splitziner.edit.Boards.decompose(p.mesh)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (res == null) println("boards    ${p.id} item ${p.item}: no ($why) ${ms}ms")
                else println("boards    ${p.id} item ${p.item}: ${res.note}: " + res.pieces.joinToString { q -> val e = splitziner.mesh.OrientedBounds.of(q).extents; listOf(e.x, e.y, e.z).sorted().joinToString(" x ") { a.format.format(it) } } + " ${ms}ms")
            }
            val bb = p.mesh.bounds; fun r(v: Double) = "%.2f".format(v / 25.4)
            println("box       ${p.id} item ${p.item} x ${r(bb.min.x)}..${r(bb.max.x)} y ${r(bb.min.y)}..${r(bb.max.y)} z ${r(bb.min.z)}..${r(bb.max.z)} vol ${"%.1f".format(Math.abs(p.mesh.volume) / 16387.064)} fill ${"%.2f".format(p.fill)}")
            println("part      ${p.id} item ${p.item} tris ${p.mesh.triangleCount} watertight ${p.watertight} open ${open} crowded ${many} copies ${p.copies} ${p.desc}")
        }
        if (fix) {
            // Work out the fixes, apply them, and write the corrected model so it can be read back like any other file.
            val t0 = System.nanoTime()
            val plan = splitziner.edit.AutoFix.plan(a.originalParts, a.format)
            println("fixtime   ${(System.nanoTime() - t0) / 1_000_000} ms, ${plan.edits.size} changes")
            for (l in plan.done) println("fixed     $l")
            for (l in plan.left) println("left      $l")
            val ms = plan.parts.filter { !it.excluded }.map { it.mesh }
            File(out, "fixed.stl").outputStream().use { splitziner.io.StlWriter.writeParts(ms, it) }
            println("wrote     ${File(out, "fixed.stl")} (${ms.sumOf { it.triangleCount }} triangles in ${ms.size} parts, millimetres)")
            // As the app does: the changes replayed on the file, so parts keep the ids that say where they came from.
            a = Pipeline.analyze(f, AnalysisOptions(fileUnits = units, displayUnits = display, inchStyle = style, edits = plan.edits))
        }
        if (full) {
            // The same steps the app takes, written as SVG so they can be looked at.
            fun time(label: String, f: () -> Unit) { val t = System.nanoTime(); runCatching(f).onFailure { println("FAILED    $label: $it"); it.printStackTrace() }; println("time      $label ${(System.nanoTime() - t) / 1_000_000} ms") }
            val info = splitziner.sheet.DrawingInfo(title = f.nameWithoutExtension, drawingNo = "TEST-1")
            var plan: splitziner.explode.PlanResult? = null
            time("plan") { plan = splitziner.explode.AssemblyPlan.plan(a.parts); println("plan      ${plan!!.steps.size} steps") }
            if (System.getenv("HOWTO") != null) for (row in a.cutList.rows) {
                val p = a.parts.first { it.id == row.memberIds[0] }
                println("howto     item ${row.item}: " + splitziner.check.HowTo.steps(row, p, a.format, tools, splitziner.drawing.PartDrawings.grooveNotes(p, a.parts, a.housed, a.format)).joinToString(" | "))
            }
            if (System.getenv("STRETCH") != null) for (k in 0..2) println("stretch   axis $k: ${splitziner.edit.Editor.stretchPlane(a.parts, k)?.let { "at ${a.format.format(it.first)}, can shrink ${a.format.format(it.second)}" } ?: "none"}")
            if (System.getenv("EXPL") != null) for (p in plan!!.parts) println("explode   ${p.id} item ${p.item} step ${p.step} ${p.explode}")
            if (System.getenv("STEPS") != null) for (st in plan!!.steps) println("step      ${st.step}${if (st.subassembly) " bench x${st.makeCount}" else ""}: ${st.partIds.size} parts, items ${st.items} ids ${st.partIds}")
            var plain = emptySet<Int>()
            time("drawing") {
                val tv = System.nanoTime()
                val vs = splitziner.drawing.DrawingBuilder.views(a.parts)
                println("views     ${(System.nanoTime() - tv) / 1_000_000} ms")
                plain = splitziner.drawing.PartDrawings.plainItems(a.parts, a.cutList.rows, a.format) - splitziner.drawing.PartDrawings.groovedItems(a.parts, a.housed)
                val noted = splitziner.drawing.PartDrawings.plainNote(plain)?.let { info.copy(notes = info.notes + it) } ?: info
                val r = splitziner.drawing.DrawingBuilder.build(a.parts, a.cutList.rows, vs, noted, splitziner.drawing.DrawingSettings(sheet = System.getenv("SHEET") ?: "auto", scale = System.getenv("SCALE")?.toDouble()), a.format, plan?.parts)
                r.sheets.forEachIndexed { i, d -> File(out, "sheet${i + 1}.svg").writeText(splitziner.export.SvgWriter.write(d)) }
                println("drawing   sheet ${r.sheet.letter} at ${r.scaleText} qa ${r.qa}")
            }
            time("profiles") {
                val pr = splitziner.export.ProfileDxf.write(a.parts, a.cutList.rows, a.format)
                File(out, "profiles.dxf").writeText(pr.dxf)
                println("profiles  ${pr.profiles} flat items, left out ${pr.leftOut}")
            }
            time("fullsize") {
                val fs = splitziner.drawing.FullSize.build(a.parts, a.cutList.rows, a.format)
                fs.pages.forEachIndexed { i, d -> File(out, "fullsize%02d.svg".format(i + 1)).writeText(splitziner.export.SvgWriter.write(d)) }
                println("fullsize  ${fs.pages.size} pages for items ${fs.items}, too big ${fs.tooBig}")
            }
            time("details") {
                val items = splitziner.drawing.PartDrawings.items(a.parts, a.cutList.rows, info, splitziner.drawing.DrawingSettings(), a.format, plain, a.housed)
                println("details   ${items.size} drawn, ${plain.size} plain items left to the list")
                for ((row, d) in items) { File(out, "item%02d.svg".format(row.item)).writeText(splitziner.export.SvgWriter.write(d.sheets[0])); if (d.qa.isNotEmpty()) println("detail    ${d.qa}") }
            }
            time("guide") {
                val pl = plan!!
                val method = splitziner.joinery.JoinMethod.defaultFor(tools)
                val fasten = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, method, a.format, tools, a.housed)
                println("hardware  ${method.label}: ${fasten.hardware}")
                if (System.getenv("JOINTS") != null) for ((st, js) in fasten.byStep.toSortedMap()) for (j in js) println("joint     step $st: ${j.through.id} (item ${j.through.item} ${j.through.desc}) into ${j.into.id} (item ${j.into.item} ${j.into.desc}): ${j.count} ${j.size} ${j.kind}")
                val steps = splitziner.guide.GuideSteps.build(pl.parts, pl.steps, emptyMap(), fasten)
                val drawn = pl.parts.filter { !it.excluded }
                val view = splitziner.explode.ExplodedView.pickView(drawn)
                val hero = splitziner.explode.ExplodedView.render(drawn.map { it.copy(explode = null) }, 170.0, 115.0, splitziner.explode.ExplodeOptions(balloons = false), view, "hero")
                val pages = splitziner.guide.GuideLayout.pages(splitziner.guide.GuideLayout.Info(f.nameWithoutExtension, "Build guide", a.overallSize, drawn.size, hardware = fasten.hardware).let { inf ->
                    val ba = splitziner.guide.GuideLayout.beforeAndAfter(splitziner.cutplan.EdgeBanding.estimate(a.parts).items, splitziner.drawing.PartDrawings.groovedItems(a.parts, a.housed).toList(), fasten.byStep.values.flatten().any { !it.glueOnly && it.kind in setOf("screws", "pocket screws") }, splitziner.joinery.Fastening.pocketHoles(fasten), splitziner.drawing.PartDrawings.pinItems(a.parts).toList(), splitziner.joinery.Fastening.grooveItems(fasten))
                    val cuts = splitziner.joinery.Fastening.cutLines(fasten) { p -> "item ${p.item ?: "-"}" + (if (p.name != p.id) " (${p.name.lowercase()})" else "") }
                    val making = a.cutList.rows.mapNotNull { r -> val p = a.parts.first { it.id == r.memberIds[0] }
                        val st = splitziner.check.HowTo.steps(r, p, a.format, tools, splitziner.drawing.PartDrawings.grooveNotes(p, a.parts, a.housed, a.format), cuts[r.item].orEmpty()); splitziner.check.HowTo.making(st)?.let { "Item ${r.item}" + (if (r.name.isNotBlank()) " (${r.name.lowercase()})" else "") + ": ${r.description}" to it } }
                    splitziner.guide.GuideLayout.Info(inf.title, inf.subtitle, inf.overallSize, inf.partCount, inf.tools, inf.a4, inf.hardware, ba.first, ba.second, making) }, steps, a.cutList.rows, hero)
                pages.forEachIndexed { i, d -> File(out, "guide%02d.svg".format(i + 1)).writeText(splitziner.export.SvgWriter.write(d)) }
                println("guide     ${steps.size} steps, ${pages.size} pages")
            }
            time("check") {
                val rep = splitziner.check.DesignCheck.run(plan?.parts ?: a.parts, a.cutList.rows, a.format, tools, housed = a.housed)
                for (w in rep.work) println("work      item ${w.item}: ${w.work.joinToString { it.name }} holes ${w.holeDiameters.map { a.format.format(it) }} cutouts ${w.cutouts}")
                for (f in rep.findings) println("finding   ${f.severity} | ${f.title} | ${f.detail} ${if (f.fixes.isEmpty()) "" else "| fix: " + f.fixes.joinToString { it.label }}")
                println("tools     ${rep.toolsNeeded}")
            }
            time("cutplan") {
                val cp = splitziner.cutplan.CutPlanner.plan(a.cutList.rows, splitziner.cutplan.StockSettings.defaultFor(a.format))
                splitziner.cutplan.CutPlanLayout.pages(cp, a.cutList.rows, f.nameWithoutExtension, a.format, extraBuy = listOfNotNull(splitziner.cutplan.EdgeBanding.line(a.parts, a.format))).forEachIndexed { i, d -> File(out, "cutplan%02d.svg".format(i + 1)).writeText(splitziner.export.SvgWriter.write(d)) }
                println("cutplan   ${splitziner.cutplan.CutPlanner.shoppingList(cp, a.format)} leftOut ${cp.leftOut.size}")
                println("banding   ${splitziner.cutplan.EdgeBanding.line(a.parts, a.format)}")
            }
        }
    } catch (e: Exception) {
        System.err.println(e.message ?: e.toString()); exitProcess(1)
    }
}
