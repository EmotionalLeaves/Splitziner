package splitziner.cli

import splitziner.export.CsvWriter
import splitziner.pipeline.AnalysisOptions
import splitziner.pipeline.Pipeline
import java.io.File
import kotlin.system.exitProcess

/** Runs the engine on a file and writes what it found; the way to check the engine without a phone. */
fun main(args: Array<String>) {
    if (args.isEmpty()) { System.err.println("usage: <file.stl> [--out <dir>] [--units mm|cm|in|m] [--display mm|in]"); exitProcess(1) }
    var out = File("build/out"); var units: String? = null; var display: String? = null; var file: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--out" -> out = File(args[++i])
            "--units" -> units = args[++i]
            "--display" -> display = args[++i]
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
    } catch (e: Exception) {
        System.err.println(e.message ?: e.toString()); exitProcess(1)
    }
}
