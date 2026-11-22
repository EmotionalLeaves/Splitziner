package io.github.emotionalleaves.splitziner

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitziner.io.StlReader
import splitziner.parts.CutList
import splitziner.parts.CutListRow
import splitziner.parts.ItemOverride
import splitziner.pipeline.Analysis
import splitziner.pipeline.Pipeline
import io.github.emotionalleaves.splitziner.render.PreparedSheet
import splitziner.drawing.DrawingBuilder
import splitziner.drawing.ViewSet
import kotlinx.coroutines.Job

class OpenProject(val meta: ProjectMeta, val analysis: Analysis)

/** The drawing for the open project: its sheets ready to show, and what the engine wants the user to know. */
/** The build guide for the open project, laid out on pages. */
class GuideState(val pages: List<PreparedSheet>, val stepCount: Int, val steps: List<splitziner.explode.BuildStep>)

class DrawingState(val sheets: List<PreparedSheet>, val sheetLetter: String, val scaleText: String, val qa: List<String>)

data class UiState(
    val projects: List<ProjectMeta> = emptyList(),
    val open: OpenProject? = null,
    /** Non-null while work is running: what is happening, and how far along (0..1). */
    val busyLabel: String? = null,
    val busyFraction: Float = 0f,
    val error: String? = null,
    val crashReport: String? = null,
    val drawing: DrawingState? = null,
    val drawingBusy: Boolean = false,
    val guide: GuideState? = null,
    val guideBusy: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        _state.update { it.copy(projects = store.list(), crashReport = Diagnostics.lastCrash()) }
    }

    // Hidden-line views are the slow part of a drawing, so they are kept for as long as the parts do not change.
    private var viewSet: ViewSet? = null
    private var viewSetFor: Analysis? = null
    private var viewSetSections: String? = null
    private var drawingJob: Job? = null
    private var guideJob: Job? = null
    private var plan: splitziner.explode.PlanResult? = null
    private var planFor: Analysis? = null

    /** The drafted assembly order for an analysis, worked out once and shared by the drawing and the guide. */
    private var planOrder: List<List<String>>? = null

    @Synchronized private fun planOf(a: Analysis, order: List<List<String>>?): splitziner.explode.PlanResult {
        if (planFor !== a || plan == null || planOrder != order) { plan = splitziner.explode.AssemblyPlan.plan(a.parts, order = order); planFor = a; planOrder = order }
        return plan!!
    }

    /** Sets the build order (part ids per step, base first), or null to go back to the automatic one. */
    fun setStepOrder(order: List<List<String>>?) = updateDrawing { it.copy(stepOrder = order?.filter { g -> g.isNotEmpty() }) }

    fun setStepText(key: String, text: splitziner.guide.StepText?) {
        val open = _state.value.open ?: return
        val meta = open.meta.copy(stepText = if (text == null) open.meta.stepText - key else open.meta.stepText + (key to text))
        store.save(meta)
        guideJob?.cancel()
        _state.update { it.copy(open = OpenProject(meta, open.analysis), guide = null, guideBusy = false) }
        ensureGuide()
    }

    /** Builds the step-by-step guide for the open project. Safe to call whenever the Guide tab is shown. */
    fun ensureGuide(force: Boolean = false) {
        val open = _state.value.open ?: return
        if (!force && (_state.value.guide != null || _state.value.guideBusy)) return
        guideJob?.cancel()
        _state.update { it.copy(guideBusy = true) }
        guideJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val a = open.analysis
                    val t0 = System.nanoTime()
                    val pl = planOf(a, open.meta.stepOrder)
                    val steps = splitziner.guide.GuideSteps.build(pl.parts, pl.steps, open.meta.stepText)
                    val drawn = pl.parts.filter { !it.excluded }
                    val view = splitziner.explode.ExplodedView.pickView(drawn)
                    val hero = splitziner.explode.ExplodedView.render(drawn.map { it.copy(explode = null) }, 170.0, 115.0, splitziner.explode.ExplodeOptions(balloons = false), view, "hero")
                    val info = splitziner.guide.GuideLayout.Info(open.meta.title.ifBlank { open.meta.name }, "Build guide", a.overallSize, drawn.size)
                    val pages = splitziner.guide.GuideLayout.pages(info, steps, a.cutList.rows, hero)
                    Diagnostics.log("guide: ${steps.size} steps, ${pages.size} pages, ${(System.nanoTime() - t0) / 1_000_000} ms")
                    GuideState(pages.map { PreparedSheet.of(it) }, steps.size, pl.steps)
                }
                _state.update { it.copy(guide = result, guideBusy = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diagnostics.logError("guide", e)
                _state.update { it.copy(guideBusy = false, error = if (e is OutOfMemoryError) "This model is too large to make a guide for on this phone." else "The guide could not be made: ${e.message}") }
            }
        }
    }

    /** Builds (or rebuilds) the drawing for the open project. Safe to call whenever the Drawing tab is shown. */
    fun ensureDrawing(force: Boolean = false) {
        val open = _state.value.open ?: return
        if (!force && (_state.value.drawing != null || _state.value.drawingBusy)) return
        drawingJob?.cancel()
        _state.update { it.copy(drawingBusy = true) }
        drawingJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val a = open.analysis
                    val t0 = System.nanoTime()
                    val sec = open.meta.sections
                    val vs = if (viewSetFor === a && viewSetSections == sec && viewSet != null) viewSet!!
                    else DrawingBuilder.views(a.parts, sec).also { viewSet = it; viewSetFor = a; viewSetSections = sec }
                    val t1 = System.nanoTime()
                    // The exploded sheet needs the assembly plan; if planning fails the drawing still gets its first sheet.
                    val planned = runCatching { planOf(a, open.meta.stepOrder).parts }.onFailure { Diagnostics.logError("plan", it) }.getOrNull()
                    val r = DrawingBuilder.build(a.parts, a.cutList.rows, vs, open.meta.drawingInfo(BuildConfig.VERSION_NAME), open.meta.drawingSettings(), a.format, planned)
                    Diagnostics.log("drawing: sheet ${r.sheet.letter} at ${r.scaleText}, views ${(t1 - t0) / 1_000_000} ms, layout ${(System.nanoTime() - t1) / 1_000_000} ms, qa ${r.qa}")
                    DrawingState(r.sheets.map { PreparedSheet.of(it) }, r.sheet.letter, r.scaleText, r.qa)
                }
                _state.update { it.copy(drawing = result, drawingBusy = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diagnostics.logError("drawing", e)
                _state.update { it.copy(drawingBusy = false, error = if (e is OutOfMemoryError) "This model is too large to draw on this phone." else "The drawing could not be made: ${e.message}") }
            }
        }
    }

    /** Changes title block or sheet choices without re-reading the file, then redraws. */
    fun updateDrawing(change: (ProjectMeta) -> ProjectMeta) {
        val open = _state.value.open ?: return
        val meta = change(open.meta)
        store.save(meta)
        // The guide's cover carries the title, so it is rebuilt the next time it is opened.
        guideJob?.cancel()
        _state.update { it.copy(open = OpenProject(meta, open.analysis), guide = null, guideBusy = false) }
        ensureDrawing(force = true)
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissCrash() { Diagnostics.clearCrash(); _state.update { it.copy(crashReport = null) } }
    fun closeProject() {
        drawingJob?.cancel(); guideJob?.cancel(); viewSet = null; viewSetFor = null; plan = null; planFor = null
        _state.update { it.copy(open = null, projects = store.list(), drawing = null, drawingBusy = false, guide = null, guideBusy = false) }
    }

    fun importFrom(uri: Uri) {
        if (_state.value.busyLabel != null) return
        viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Copying the file", busyFraction = 0f, error = null) }
            try {
                val meta = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    var name = "model.stl"
                    runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
                        }
                    }
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("The file could not be opened.")
                    Diagnostics.log("import $name, ${bytes.size} bytes")
                    if (!name.lowercase().endsWith(".stl") && !StlReader.looksLikeStl(bytes.copyOf(minOf(bytes.size, 1024)), bytes.size.toLong())) {
                        error("That file is not an STL.")
                    }
                    store.create(name, bytes)
                }
                analyze(meta, deleteOnFailure = true)
            } catch (e: Throwable) {
                fail("import", e)
            }
        }
    }

    fun openProject(meta: ProjectMeta) {
        if (_state.value.busyLabel != null) return
        viewModelScope.launch { analyze(meta, deleteOnFailure = false) }
    }

    /** Applies a change to the open project's settings and re-reads the file with them. */
    fun update(change: (ProjectMeta) -> ProjectMeta) {
        val open = _state.value.open ?: return
        if (_state.value.busyLabel != null) return
        val meta = change(open.meta)
        store.save(meta)
        viewModelScope.launch { analyze(meta, deleteOnFailure = false) }
    }

    fun setItem(row: CutListRow, value: ItemOverride) =
        update { it.copy(items = it.items + (CutList.itemKey(row.kind, row.t, row.w, row.l) to value)) }

    fun deleteProject(meta: ProjectMeta) {
        store.delete(meta.id)
        _state.update { it.copy(projects = store.list(), open = if (it.open?.meta?.id == meta.id) null else it.open) }
    }

    private suspend fun analyze(meta: ProjectMeta, deleteOnFailure: Boolean) {
        _state.update { it.copy(busyLabel = "Reading triangles", busyFraction = 0.05f, error = null) }
        try {
            val analysis = withContext(Dispatchers.Default) {
                val bytes = store.source(meta.id).readBytes()
                Pipeline.analyze(bytes, meta.fileName, meta.options()) { f, label ->
                    _state.update { it.copy(busyLabel = label, busyFraction = f.toFloat()) }
                }
            }
            val r = analysis.report
            Diagnostics.log("analysis: ${r.faces} tris, ${r.method}, ${r.bodies} bodies, ${r.parts} parts, units ${r.units.fileUnits}/${r.units.designUnits} margin ${r.units.margin}, ${analysis.timingsMs}")
            val saved = meta.copy(partCount = analysis.parts.size)
            store.save(saved)
            drawingJob?.cancel(); guideJob?.cancel()
            _state.update { it.copy(open = OpenProject(saved, analysis), busyLabel = null, projects = store.list(), drawing = null, drawingBusy = false, guide = null, guideBusy = false) }
        } catch (e: Throwable) {
            if (deleteOnFailure) store.delete(meta.id)
            fail("analysis", e)
        }
    }

    private fun fail(where: String, e: Throwable) {
        Diagnostics.logError(where, e)
        val message = when (e) {
            is OutOfMemoryError -> "This file is too large for this phone's memory."
            else -> e.message ?: "Something went wrong."
        }
        _state.update { it.copy(busyLabel = null, error = message, projects = store.list()) }
    }

    /** Text for the "Copy diagnostic report" button. */
    fun diagnosticReport(): String {
        val a = _state.value.open?.analysis ?: return Diagnostics.report("")
        val r = a.report
        return Diagnostics.report(
            "File: ${a.sourceName}\nTriangles: ${r.faces} (${r.degenerateFaces} degenerate)\nMethod: ${r.method}\n" +
                "Bodies: ${r.bodies}, parts: ${r.parts}, duplicates removed: ${r.duplicatesRemoved}, watertight: ${r.watertightParts}, repaired: ${r.tJunctionFixedParts}\n" +
                "Units: file ${r.units.fileUnits}, design ${r.units.designUnits}, margin ${r.units.margin}, alternative ${r.units.alternative}\n" +
                "Size: ${a.overallSize}\nWarnings: ${r.warnings + a.cutList.warnings}\nTimings ms: ${a.timingsMs}"
        )
    }
}
