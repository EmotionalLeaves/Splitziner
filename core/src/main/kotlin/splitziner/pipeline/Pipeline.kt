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
) {
    /** Overall size as "W x D x H units". */
    val overallSize: String get() {
        val e = bounds.extents
        return "${format.format(e.x)} × ${format.format(e.y)} × ${format.format(e.z)} ${format.unitLabel}"
    }
}

fun interface Progress { fun report(fraction: Double, label: String) }

object Pipeline {
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
        // Very heavy models are worked with as a simplified copy, so drawings, the 3D view and the checks fit in a phone's memory.
        var maxErr = 0.0
        val heavy = split.parts.sumOf { it.mesh.triangleCount }
        var report = split.report
        val lighter = if (heavy <= WORK_BUDGET) split.parts else timed("simplify") {
            progress.report(0.7, "Simplifying a heavy model")
            val notes = ArrayList<String>()
            // Accuracy is judged against the whole model, as the drawings show it.
            val whole = split.parts.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }.extents.length
            val tol = splitziner.mesh.Simplify.tolerance(whole)
            val out = split.parts.map { p ->
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
        val fmt = LengthFormat(units = split.report.units.designUnits, inchStyle = options.inchStyle)
        val named = lighter.map { p -> p.copy(name = options.partNames[p.id] ?: p.name, excluded = p.id in options.excludedPartIds) }
        val edited = if (options.edits.isEmpty()) null else timed("edits") { splitziner.edit.Editor.apply(named, options.edits) }
        val cut = timed("cutlist") { CutList.build(edited?.parts ?: named, fmt, options.itemOverrides) }
        val bounds = cut.parts.filter { !it.excluded }.map { it.mesh.bounds }.reduceOrNull { a, b -> a.union(b) }
            ?: Aabb.of(doubleArrayOf())
        progress.report(1.0, "Done")
        return Analysis(name, report, cut.parts, cut, fmt, bounds, timings, named, edited?.notes ?: emptyList())
    }
}
