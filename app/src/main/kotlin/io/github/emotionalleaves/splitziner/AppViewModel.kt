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

class DrawingState(
    val sheets: List<PreparedSheet>, val labels: List<String>, val sheetLetter: String, val scaleText: String, val qa: List<String>,
    /** The sheets as the engine made them, for exports that need more than a picture (DXF). */
    val lists: List<splitziner.sheet.DisplayList> = emptyList(),
)

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
    /** A newer release found on GitHub, and the state of fetching it. */
    val update: Release? = null,
    val updateStatus: String? = null,
    val updateProgress: Float? = null,
    /** Fixes worked out for the open project, waiting for the user to accept or dismiss them. */
    val fixPlan: splitziner.edit.FixPlan? = null,
    val fixBusy: Boolean = false,
    /** The design check for the open project; null until it has been worked out. */
    val check: splitziner.check.CheckReport? = null,
    val checkBusy: Boolean = false,
    /** Per part id, how far it travels when the 3D view is fully pulled apart (model mm). */
    val explodeOffsets: Map<String, splitziner.math.Vec3>? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        _state.update { it.copy(projects = store.list(), crashReport = Diagnostics.lastCrash()) }
        if (Updater.dueForAutoCheck(app)) checkForUpdate(quiet = true)
    }

    private var updateJob: Job? = null

    /** Looks for a newer release. [quiet] checks say nothing unless there is one. */
    fun checkForUpdate(quiet: Boolean = false) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            if (!quiet) _state.update { it.copy(updateStatus = "Looking for a new version…") }
            try {
                val r = withContext(Dispatchers.IO) { Updater.latest(getApplication()) }
                Diagnostics.log("update check: latest ${r?.version}, running ${BuildConfig.VERSION_NAME}")
                _state.update {
                    when {
                        r != null && Updater.isNewer(r) -> it.copy(update = r, updateStatus = null)
                        quiet -> it.copy(updateStatus = null)
                        else -> it.copy(update = null, updateStatus = "You have the latest version (${BuildConfig.VERSION_NAME}).")
                    }
                }
            } catch (e: Throwable) {
                Diagnostics.logError("update check", e)
                _state.update { it.copy(updateStatus = if (quiet) null else "Could not reach GitHub. Check your connection and try again.") }
            }
        }
    }

    fun dismissUpdateStatus() = _state.update { it.copy(updateStatus = null) }
    fun dismissUpdate() = _state.update { it.copy(update = null, updateProgress = null, updateStatus = null) }

    /** Downloads the release and hands it to Android's installer, which asks the user to confirm. */
    fun installUpdate() {
        val r = _state.value.update ?: return
        val app = getApplication<Application>()
        if (!Updater.canInstall(app)) {
            _state.update { it.copy(updateStatus = "Allow Splitziner to install apps, then come back and tap Update again.") }
            Updater.openInstallPermission(app)
            return
        }
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            _state.update { it.copy(updateProgress = 0f, updateStatus = "Downloading version ${r.version}…") }
            try {
                val apk = withContext(Dispatchers.IO) { Updater.download(app, r) { f -> _state.update { s -> s.copy(updateProgress = f) } } }
                _state.update { it.copy(updateProgress = null, updateStatus = null) }
                Updater.install(app, apk)
            } catch (e: Throwable) {
                Diagnostics.logError("update download", e)
                _state.update { it.copy(updateProgress = null, updateStatus = "The update could not be downloaded: ${e.message}") }
            }
        }
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
        _state.update { it.copy(open = OpenProject(meta, open.analysis), guide = null, guideBusy = false, check = null, checkBusy = false) }
        ensureGuide()
    }

    private var explodeJob: Job? = null

    /** Works out how far each part moves when the 3D view is pulled apart. Called the first time the slider is touched. */
    fun ensureExplodeOffsets() {
        val open = _state.value.open ?: return
        if (_state.value.explodeOffsets != null || explodeJob?.isActive == true) return
        explodeJob = viewModelScope.launch {
            try {
                val offsets = withContext(Dispatchers.Default) {
                    val pl = planOf(open.analysis, open.meta.stepOrder)
                    splitziner.explode.ExplodedView.offsetsById(pl.parts.filter { !it.excluded })
                }
                _state.update { it.copy(explodeOffsets = offsets) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diagnostics.logError("explode offsets", e)
                _state.update { it.copy(explodeOffsets = emptyMap()) }
            }
        }
    }

    /** Builds the step-by-step guide for the open project. Safe to call whenever the Guide tab is shown. */
    private var checkJob: Job? = null
    private val toolPrefs by lazy { getApplication<Application>().getSharedPreferences("tools", android.content.Context.MODE_PRIVATE) }

    /** The tools to check against: this project's, else the ones last chosen for any project, else not yet known. */
    fun toolsFor(meta: ProjectMeta): Set<splitziner.check.Tool>? =
        (meta.tools ?: toolPrefs.getStringSet("owned", null)?.toList())?.let { splitziner.check.Tool.parse(it) }

    fun setTools(tools: Set<splitziner.check.Tool>) {
        val open = _state.value.open ?: return
        val names = tools.map { it.name }
        toolPrefs.edit().putStringSet("owned", names.toSet()).apply()
        val meta = open.meta.copy(tools = names)
        store.save(meta)
        // The check and the guide's tool list both depend on the tools.
        checkJob?.cancel(); guideJob?.cancel()
        _state.update { it.copy(open = OpenProject(meta, open.analysis), check = null, checkBusy = false, guide = null, guideBusy = false) }
        ensureCheck()
    }

    /** Works out what the app can put right by itself. Nothing changes until [applyFixes]. */
    fun planFixes() {
        val open = _state.value.open ?: return
        if (_state.value.fixBusy) return
        _state.update { it.copy(fixBusy = true, fixPlan = null) }
        viewModelScope.launch {
            try {
                val plan = withContext(Dispatchers.Default) {
                    val t0 = System.nanoTime()
                    splitziner.edit.AutoFix.plan(open.analysis.originalParts, open.analysis.format, open.meta.edits).also {
                        Diagnostics.log("fix plan: ${it.edits.size} changes, ${it.left.size} left, ${(System.nanoTime() - t0) / 1_000_000} ms")
                    }
                }
                _state.update { it.copy(fixPlan = plan, fixBusy = false) }
            } catch (e: Throwable) {
                Diagnostics.logError("fix plan", e)
                _state.update { it.copy(fixBusy = false, error = "The fixes could not be worked out: ${e.message}") }
            }
        }
    }

    fun dismissFixes() = _state.update { it.copy(fixPlan = null) }

    fun applyFixes() {
        val plan = _state.value.fixPlan ?: return
        _state.update { it.copy(fixPlan = null) }
        if (plan.edits.isNotEmpty()) update { it.copy(edits = it.edits + plan.edits) }
    }

    fun addEdit(edit: splitziner.edit.Edit) = update { it.copy(edits = it.edits + edit) }
    fun undoLastEdit() = update { it.copy(edits = it.edits.dropLast(1)) }
    fun undoAllEdits() = update { it.copy(edits = emptyList()) }

    fun ensureCheck() {
        val open = _state.value.open ?: return
        if (_state.value.check != null || _state.value.checkBusy) return
        _state.update { it.copy(checkBusy = true) }
        checkJob = viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.Default) {
                    val t0 = System.nanoTime()
                    val a = open.analysis
                    splitziner.check.DesignCheck.run(a.parts, a.cutList.rows, a.format, toolsFor(open.meta)).also {
                        Diagnostics.log("check: ${it.problems} problems, ${it.checks} to check, ${(System.nanoTime() - t0) / 1_000_000} ms")
                    }
                }
                _state.update { it.copy(check = report, checkBusy = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diagnostics.logError("check", e)
                _state.update { it.copy(checkBusy = false, error = "The design check could not be finished: ${e.message}") }
            }
        }
    }

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
                    // The cover lists the tools the parts call for, named from the maker's own tools once they have chosen them.
                    val tools = runCatching { (_state.value.check ?: splitziner.check.DesignCheck.run(a.parts, a.cutList.rows, a.format, toolsFor(open.meta))).toolsNeeded }.getOrDefault(emptyList())
                    val info = splitziner.guide.GuideLayout.Info(open.meta.title.ifBlank { open.meta.name }, "Build guide", a.overallSize, drawn.size, tools.map { it.replaceFirstChar { c -> c.uppercase() } })
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
                    val sheets = ArrayList(r.sheets); val qa = ArrayList(r.qa)
                    val labels = ArrayList(r.sheets.indices.map { if (it == 0) "Sheet 1: views" else "Sheet ${it + 1}: exploded view" })
                    // One detail drawing per item, when there is more than one part to detail.
                    if (a.parts.count { !it.excluded } > 1) runCatching {
                        val t2 = System.nanoTime()
                        val items = splitziner.drawing.PartDrawings.build(a.parts, a.cutList.rows, open.meta.drawingInfo(BuildConfig.VERSION_NAME), open.meta.drawingSettings(), a.format)
                        items.forEachIndexed { i, d -> sheets += d.sheets; labels += d.sheets.map { "Item ${a.cutList.rows[i].item}: ${a.cutList.rows[i].description}" }; qa += d.qa.filter { q -> !q.contains("Hidden lines") } }
                        Diagnostics.log("part drawings: ${items.size} in ${(System.nanoTime() - t2) / 1_000_000} ms")
                    }.onFailure { Diagnostics.logError("part drawings", it) }
                    DrawingState(sheets.map { PreparedSheet.of(it) }, labels, r.sheet.letter, r.scaleText, qa, sheets)
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
        explodeJob?.cancel()
        _state.update { it.copy(open = OpenProject(meta, open.analysis), guide = null, guideBusy = false, check = null, checkBusy = false, explodeOffsets = null) }
        ensureDrawing(force = true)
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissCrash() { Diagnostics.clearCrash(); _state.update { it.copy(crashReport = null) } }
    fun closeProject() {
        drawingJob?.cancel(); guideJob?.cancel(); checkJob?.cancel(); viewSet = null; viewSetFor = null; plan = null; planFor = null
        _state.update { it.copy(open = null, projects = store.list(), drawing = null, drawingBusy = false, guide = null, guideBusy = false, check = null, checkBusy = false, explodeOffsets = null) }
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
            drawingJob?.cancel(); guideJob?.cancel(); checkJob?.cancel()
            _state.update { it.copy(open = OpenProject(saved, analysis), busyLabel = null, projects = store.list(), drawing = null, drawingBusy = false, guide = null, guideBusy = false, check = null, checkBusy = false, explodeOffsets = null) }
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
