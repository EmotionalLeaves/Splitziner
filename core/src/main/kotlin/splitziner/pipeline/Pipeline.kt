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
    fun analyze(bytes: ByteArray, name: String, options: AnalysisOptions = AnalysisOptions(), progress: Progress = Progress { _, _ -> }): Analysis {
        val timings = LinkedHashMap<String, Long>()
        fun <T> timed(label: String, block: () -> T): T { val t0 = System.nanoTime(); val r = block(); timings[label] = (System.nanoTime() - t0) / 1_000_000; return r }

        progress.report(0.05, "Reading triangles")
        val raw = timed("load") { StlReader.read(bytes, name) }
        progress.report(0.3, "Separating parts")
        val split = timed("split") { BodySplitter.split(raw, options.fileUnits, options.displayUnits) }
        progress.report(0.8, "Building the cut list")
        val fmt = LengthFormat(units = split.report.units.designUnits, inchStyle = options.inchStyle)
        val named = split.parts.map { p -> p.copy(name = options.partNames[p.id] ?: p.name, excluded = p.id in options.excludedPartIds) }
        val edited = if (options.edits.isEmpty()) null else timed("edits") { splitziner.edit.Editor.apply(named, options.edits) }
        val cut = timed("cutlist") { CutList.build(edited?.parts ?: named, fmt, options.itemOverrides) }
        val bounds = cut.parts.filter { !it.excluded }.map { it.mesh.bounds }.reduceOrNull { a, b -> a.union(b) }
            ?: Aabb.of(doubleArrayOf())
        progress.report(1.0, "Done")
        return Analysis(name, split.report, cut.parts, cut, fmt, bounds, timings, named, edited?.notes ?: emptyList())
    }
}
