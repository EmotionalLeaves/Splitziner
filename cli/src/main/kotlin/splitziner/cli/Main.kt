package splitziner.cli

import splitziner.export.CsvWriter
import splitziner.pipeline.AnalysisOptions
import splitziner.pipeline.Pipeline
import java.io.File
import kotlin.system.exitProcess

/** Runs the engine on a file and writes what it found; the way to check the engine without a phone. */
fun main(args: Array<String>) {
    if (args.isEmpty()) { System.err.println("usage: <file.stl> [--out <dir>] [--units mm|cm|in|m] [--display mm|in]"); exitProcess(1) }
    var out = File("build/out"); var units: String? = null; var display: String? = null; var file: String? = null; var full = false; var fix = false; var tools: Set<splitziner.check.Tool>? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--out" -> out = File(args[++i])
            "--units" -> units = args[++i]
            "--display" -> display = args[++i]
            "--full" -> full = true
            "--fix" -> fix = true
            "--tools" -> tools = splitziner.check.Tool.parse(args[++i].split(','))
            else -> file = args[i]
        }
        i++
    }
    try {
        val f = File(file ?: error("no input file"))
        val a = Pipeline.analyze(f.readBytes(), f.name, AnalysisOptions(fileUnits = units, displayUnits = display))
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
            val all = splitziner.mesh.MeshOps.concatenate(plan.parts.filter { !it.excluded }.map { it.mesh })
            File(out, "fixed.stl").outputStream().use { splitziner.io.StlWriter.writeBinary(all, it, "Splitziner fixed model units=mm") }
            println("wrote     ${File(out, "fixed.stl")} (${all.triangleCount} triangles, millimetres)")
        }
        if (full) {
            // The same steps the app takes, written as SVG so they can be looked at.
            fun time(label: String, f: () -> Unit) { val t = System.nanoTime(); runCatching(f).onFailure { println("FAILED    $label: $it"); it.printStackTrace() }; println("time      $label ${(System.nanoTime() - t) / 1_000_000} ms") }
            val info = splitziner.sheet.DrawingInfo(title = f.nameWithoutExtension, drawingNo = "TEST-1")
            var plan: splitziner.explode.PlanResult? = null
            time("plan") { plan = splitziner.explode.AssemblyPlan.plan(a.parts); println("plan      ${plan!!.steps.size} steps") }
            time("drawing") {
                val vs = splitziner.drawing.DrawingBuilder.views(a.parts)
                val r = splitziner.drawing.DrawingBuilder.build(a.parts, a.cutList.rows, vs, info, splitziner.drawing.DrawingSettings(), a.format, plan?.parts)
                r.sheets.forEachIndexed { i, d -> File(out, "sheet${i + 1}.svg").writeText(splitziner.export.SvgWriter.write(d)) }
                println("drawing   sheet ${r.sheet.letter} at ${r.scaleText} qa ${r.qa}")
            }
            time("details") {
                val items = splitziner.drawing.PartDrawings.build(a.parts, a.cutList.rows, info, splitziner.drawing.DrawingSettings(), a.format)
                items.forEachIndexed { i, d -> File(out, "item%02d.svg".format(a.cutList.rows[i].item)).writeText(splitziner.export.SvgWriter.write(d.sheets[0])); if (d.qa.isNotEmpty()) println("detail    ${d.qa}") }
            }
            time("guide") {
                val pl = plan!!
                val steps = splitziner.guide.GuideSteps.build(pl.parts, pl.steps, emptyMap())
                val drawn = pl.parts.filter { !it.excluded }
                val view = splitziner.explode.ExplodedView.pickView(drawn)
                val hero = splitziner.explode.ExplodedView.render(drawn.map { it.copy(explode = null) }, 170.0, 115.0, splitziner.explode.ExplodeOptions(balloons = false), view, "hero")
                val pages = splitziner.guide.GuideLayout.pages(splitziner.guide.GuideLayout.Info(f.nameWithoutExtension, "Build guide", a.overallSize, drawn.size), steps, a.cutList.rows, hero)
                pages.forEachIndexed { i, d -> File(out, "guide%02d.svg".format(i + 1)).writeText(splitziner.export.SvgWriter.write(d)) }
                println("guide     ${steps.size} steps, ${pages.size} pages")
            }
            time("check") {
                val rep = splitziner.check.DesignCheck.run(plan?.parts ?: a.parts, a.cutList.rows, a.format, tools)
                for (w in rep.work) println("work      item ${w.item}: ${w.work.joinToString { it.name }} holes ${w.holeDiameters.map { a.format.format(it) }} cutouts ${w.cutouts}")
                for (f in rep.findings) println("finding   ${f.severity} | ${f.title} | ${f.detail} ${if (f.fixes.isEmpty()) "" else "| fix: " + f.fixes.joinToString { it.label }}")
                println("tools     ${rep.toolsNeeded}")
            }
            time("cutplan") {
                val cp = splitziner.cutplan.CutPlanner.plan(a.cutList.rows, splitziner.cutplan.StockSettings.defaultFor(a.format))
                splitziner.cutplan.CutPlanLayout.pages(cp, a.cutList.rows, f.nameWithoutExtension, a.format).forEachIndexed { i, d -> File(out, "cutplan%02d.svg".format(i + 1)).writeText(splitziner.export.SvgWriter.write(d)) }
                println("cutplan   ${splitziner.cutplan.CutPlanner.shoppingList(cp, a.format)} leftOut ${cp.leftOut.size}")
            }
        }
    } catch (e: Exception) {
        System.err.println(e.message ?: e.toString()); exitProcess(1)
    }
}
