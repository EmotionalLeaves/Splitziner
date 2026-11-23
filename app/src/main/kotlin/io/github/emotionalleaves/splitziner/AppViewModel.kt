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
class GuideState(val pages: List<PreparedSheet>, val stepCount: Int, val steps: List<splitziner.explode.BuildStep>, val work: List<WorkStep> = emptyList(), val labels: List<String> = emptyList())

/** One step as the build mode shows it, a screen at a time: its picture, what it uses, what to do. */
class WorkStep(val title: String, val picture: PreparedSheet, val parts: List<String>, val text: List<String>, val check: String?)

class DrawingState(
    val sheets: List<PreparedSheet>, val labels: List<String>, val sheetLetter: String, val scaleText: String, val qa: List<String>,
    /** The sheets as the engine made them, for exports that need more than a picture (DXF). */
    val lists: List<splitziner.sheet.DisplayList> = emptyList(),
)

data class UiState(
    val projects: List<ProjectMeta> = emptyList(),
    /** Bumped when a project's picture is saved, so the list reloads it. */
    val thumbsVersion: Int = 0,
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

    /** The hardware list the guide would give (fasteners and glue), worked out here so the shopping list has it. */
    fun hardwareFor(open: OpenProject): List<String> = runCatching {
        val a = open.analysis
        val pl = planOf(a, open.meta.stepOrder)
        splitziner.joinery.Fastening.plan(pl.parts, pl.steps, methodFor(open.meta), a.format, toolsFor(open.meta), a.housed).hardware
    }.getOrDefault(emptyList())

    /** The build plan for playing the steps in 3D: worked out off the main thread, cached with the guide's. */
    suspend fun planFor3D(): splitziner.explode.PlanResult? {
        val open = _state.value.open ?: return null
        return withContext(Dispatchers.Default) { runCatching { planOf(open.analysis, open.meta.stepOrder) }.getOrNull() }
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
    fun addEdits(edits: List<splitziner.edit.Edit>) = update { it.copy(edits = it.edits + edits) }
    fun undoLastEdit() = update { it.copy(edits = it.edits.dropLast(1)) }
    fun undoAllEdits() = update { it.copy(edits = emptyList()) }
    /** Takes back one change from anywhere in the list; later changes are replayed without it. */
    fun removeEdit(index: Int) = update { m -> m.copy(edits = m.edits.filterIndexed { i, _ -> i != index }) }

    /** The joining method: the one chosen for this project, else what the tools allow. */
    fun methodFor(meta: ProjectMeta) = splitziner.joinery.JoinMethod.parse(meta.joinMethod) ?: splitziner.joinery.JoinMethod.defaultFor(toolsFor(meta))

    fun setJoinMethod(m: splitziner.joinery.JoinMethod) {
        val open = _state.value.open ?: return
        val meta = open.meta.copy(joinMethod = m.name)
        store.save(meta)
        guideJob?.cancel()
        _state.update { it.copy(open = OpenProject(meta, open.analysis), guide = null, guideBusy = false) }
        ensureGuide()
    }

    fun ensureCheck() {
        val open = _state.value.open ?: return
        if (_state.value.check != null || _state.value.checkBusy) return
        _state.update { it.copy(checkBusy = true) }
        checkJob = viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.Default) {
                    val t0 = System.nanoTime()
                    val a = open.analysis
                    splitziner.check.DesignCheck.run(a.parts, a.cutList.rows, a.format, toolsFor(open.meta), housed = a.housed).also {
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
                    val method = methodFor(open.meta)
                    val fasten = runCatching { splitziner.joinery.Fastening.plan(pl.parts, pl.steps, method, a.format, toolsFor(open.meta), a.housed) }.onFailure { Diagnostics.logError("fastening", it) }.getOrNull()
                    val steps = splitziner.guide.GuideSteps.build(pl.parts, pl.steps, open.meta.stepText, fasten, a.format)
                    val drawn = pl.parts.filter { !it.excluded }
                    val view = splitziner.explode.ExplodedView.pickView(drawn)
                    val hero = splitziner.explode.ExplodedView.render(drawn.map { it.copy(explode = null) }, 170.0, 115.0, splitziner.explode.ExplodeOptions(balloons = false), view, "hero")
                    // The cover lists the tools the parts call for, named from the maker's own tools once they have chosen them.
                    val rep = runCatching { _state.value.check ?: splitziner.check.DesignCheck.run(a.parts, a.cutList.rows, a.format, toolsFor(open.meta), housed = a.housed) }.getOrNull()
                    val tools = rep?.toolsNeeded ?: emptyList()
                    val ba = splitziner.guide.GuideLayout.beforeAndAfter(
                        runCatching { splitziner.cutplan.EdgeBanding.estimate(a.parts).items }.getOrDefault(emptyList()),
                        splitziner.drawing.PartDrawings.groovedItems(a.parts, a.housed).toList(),
                        fasten?.byStep?.values?.flatten()?.any { !it.glueOnly && it.kind in setOf("screws", "pocket screws", "under", "up", "counterbored", "cabinets") } ?: true,
                        fasten?.let { splitziner.joinery.Fastening.pocketHoles(it) } ?: emptyMap(),
                        runCatching { splitziner.drawing.PartDrawings.pinItems(a.parts).toList() }.getOrDefault(emptyList()),
                        fasten?.let { splitziner.joinery.Fastening.grooveItems(it) } ?: emptyList(),
                        fasten?.let { splitziner.joinery.Fastening.dowelSize(it) },
                        splitziner.cutplan.CutPlanner.gluingSteps(a.cutList.rows, a.format),
                        fasten?.let { splitziner.joinery.Fastening.pilotNote(it) })
                    val info = splitziner.guide.GuideLayout.Info(open.meta.title.ifBlank { open.meta.name }, "Build guide", a.overallSize, drawn.size, tools.map { it.replaceFirstChar { c -> c.uppercase() } },
                        hardware = (fasten?.hardware ?: emptyList()).let { if (it.isEmpty()) it else listOf("Joined with: ${method.label.lowercase()}") + it } + a.orders.map { "To order: $it" },
                        before = ba.first, after = ba.second + splitziner.check.DesignCheck.finishNotes(rep), making = makingSteps(a, toolsFor(open.meta), fasten))
                    val pages = splitziner.guide.GuideLayout.pages(info, steps, a.cutList.rows, hero)
                    Diagnostics.log("guide: ${steps.size} steps, ${pages.size} pages, ${(System.nanoTime() - t0) / 1_000_000} ms")
                    val work = steps.map { st -> WorkStep(st.title, PreparedSheet.of(st.picture), st.parts.map { (what, n) -> "$n\u00d7 $what" }, st.text, st.check) }
                    GuideState(pages.map { PreparedSheet.of(it) }, steps.size, pl.steps, work, runCatching { splitziner.guide.GuideLayout.labels(pages) }.getOrDefault(emptyList()))
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
                    // Plain square boards need no detail drawing; a general note on the assembly says which they are.
                    val details = a.parts.count { !it.excluded } > 1
                    val plain = if (details) runCatching { splitziner.drawing.PartDrawings.plainItems(a.parts, a.cutList.rows, a.format) - splitziner.drawing.PartDrawings.groovedItems(a.parts, a.housed) }.getOrDefault(emptySet()) else emptySet()
                    // The title block's weight, when the user has not written one and every item has a known material.
                    val info0 = open.meta.drawingInfo(BuildConfig.VERSION_NAME)
                    val wt = splitziner.material.Materials.weight(a.parts, a.cutList.rows)
                    val baseInfo = if (info0.weight.isBlank() && wt.unknownItems.isEmpty() && wt.kg > 0) info0.copy(weight = "ABOUT " + splitziner.material.Materials.format(wt.kg, a.format.isInch).uppercase()) else info0
                    val detailed = if (details) a.cutList.rows.map { it.item }.toSet() - plain - splitziner.drawing.PartDrawings.boughtItems(a.parts) else emptySet()
                    val info = baseInfo.copy(notes = baseInfo.notes + listOfNotNull(splitziner.drawing.PartDrawings.leanNote(a.parts), splitziner.drawing.PartDrawings.plainNote(plain), splitziner.drawing.PartDrawings.detailNote(detailed, baseInfo.drawingNo)))
                    val r = DrawingBuilder.build(a.parts, a.cutList.rows, vs, info, open.meta.drawingSettings(), a.format, planned)
                    Diagnostics.log("drawing: sheet ${r.sheet.letter} at ${r.scaleText}, views ${(t1 - t0) / 1_000_000} ms, layout ${(System.nanoTime() - t1) / 1_000_000} ms, qa ${r.qa}")
                    val sheets = ArrayList(r.sheets); val qa = ArrayList(r.qa)
                    val labels = ArrayList(r.sheets.mapIndexed { i, d -> "Sheet ${i + 1}: " + when { i == 0 -> "views"; d.name.startsWith("list") -> "list of material"; else -> "exploded view" } })
                    // One detail drawing per item, when there is more than one part to detail.
                    if (details) runCatching {
                        val t2 = System.nanoTime()
                        val items = splitziner.drawing.PartDrawings.items(a.parts, a.cutList.rows, baseInfo, open.meta.drawingSettings(), a.format, plain, a.housed)
                        for ((row, d) in items) { sheets += d.sheets; labels += d.sheets.map { "Item ${row.item}: " + (if (row.name.isNotBlank()) "${row.name}, " else "") + row.description.lowercase() }; qa += d.qa.filter { q -> !q.contains("Hidden lines") } }
                        Diagnostics.log("part drawings: ${items.size} in ${(System.nanoTime() - t2) / 1_000_000} ms, ${plain.size} plain items left to the list")
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

    /** How to make one item, for the cut list and the guide. */
    fun howTo(a: splitziner.pipeline.Analysis, row: CutListRow, tools: Set<splitziner.check.Tool>?, extra: List<String> = emptyList()): List<String> {
        val p = a.parts.firstOrNull { it.id == row.memberIds.firstOrNull() } ?: return emptyList()
        return runCatching { splitziner.check.HowTo.steps(row, p, a.format, tools, splitziner.drawing.PartDrawings.grooveNotes(p, a.parts, a.housed, a.format), extra) }.getOrDefault(emptyList())
    }

    /** Items that need more than cutting to size, with their steps. */
    fun makingSteps(a: splitziner.pipeline.Analysis, tools: Set<splitziner.check.Tool>?, fasten: splitziner.joinery.FastenPlan? = null): List<Pair<String, List<String>>> {
        val cuts = fasten?.let { f -> runCatching { splitziner.joinery.Fastening.cutLines(f) { p -> "item ${p.item ?: "-"}" + (if (p.name != p.id) " (${p.name.lowercase()})" else "") } }.getOrNull() } ?: emptyMap()
        return a.cutList.rows.mapNotNull { r -> splitziner.check.HowTo.making(howTo(a, r, tools, cuts[r.item].orEmpty()))?.let { "Item ${r.item}" + (if (r.name.isNotBlank()) " (${r.name.lowercase()})" else "") + ": ${r.description}" to it } }
    }

    /**
     * Draws the model small, as the drawings show it, for its card in the project list. In the background and
     * only for models that draw quickly; the list shows a letter until the picture exists.
     */
    private fun saveThumb(meta: ProjectMeta, analysis: splitziner.pipeline.Analysis) {
        val parts = analysis.parts.filter { !it.excluded }.map { it.copy(explode = null) }
        if (parts.isEmpty() || parts.size > 300) return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val view = splitziner.explode.ExplodedView.pickView(parts)
                val sheet = io.github.emotionalleaves.splitziner.render.PreparedSheet.of(splitziner.explode.ExplodedView.render(parts, 80.0, 60.0, splitziner.explode.ExplodeOptions(balloons = false), view, "thumb"))
                val w = 240; val h = 180
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp); c.drawColor(android.graphics.Color.WHITE)
                val k = minOf(w / sheet.widthMm, h / sheet.heightMm)
                c.translate((w - sheet.widthMm * k) / 2, (h - sheet.heightMm * k) / 2)
                sheet.draw(c, k)
                store.thumb(meta.id).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                _state.update { it.copy(projects = store.list(), thumbsVersion = it.thumbsVersion + 1) }
            }.onFailure { Diagnostics.logError("thumbnail", it) }
        }
    }

    fun thumbFile(id: String): java.io.File = store.thumb(id)

    /** Writes the open project to a single file in the app's cache, for sharing. */
    fun exportProject(): java.io.File? {
        val open = _state.value.open ?: return null
        return runCatching {
            val dir = java.io.File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            val f = java.io.File(dir, open.meta.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "project" } + ".splitziner.zip")
            f.outputStream().use { store.export(open.meta, it) }
            f
        }.onFailure { Diagnostics.logError("export project", it) }.getOrNull()
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
                    var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') } ?: "model.stl"
                    runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
                        }
                    }
                    // Woodworkers in inch countries read 3-1/2, not 3.50: new projects there start in fractions.
                    // A project file from Share project (a zip with the model and its settings) comes back whole.
                    val zipped = resolver.openInputStream(uri)?.use { s -> val b = ByteArray(2); s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() } == true
                    // A 3MF model is a zip too: it has a .model file inside, and no project settings.
                    val threeMf = zipped && (name.lowercase().endsWith(".3mf") || resolver.openInputStream(uri)?.use { s ->
                        java.util.zip.ZipInputStream(s).use { z -> generateSequence { z.nextEntry }.take(200).any { it.name.lowercase().endsWith(".model") } }
                    } == true)
                    if (zipped && !threeMf) {
                        val m = resolver.openInputStream(uri)?.use { store.importProject(it) } ?: error("The file could not be opened.")
                        Diagnostics.log("imported project ${m.name}")
                        return@withContext m
                    }
                    val meta = (resolver.openInputStream(uri)?.use { store.create(name, it) } ?: error("The file could not be opened."))
                        .let { m -> if (inchLocale) m.copy(inchStyle = "fraction").also { store.save(it) } else m }
                        // OBJ files are Y-up by convention (Blender and most others write them so).
                        .let { m ->
                            val obj = name.lowercase().endsWith(".obj") || store.source(m.id).inputStream().use { s -> val b = ByteArray(1024); val n = s.read(b); splitziner.io.ObjReader.looksLikeObj(b.copyOf(maxOf(0, n))) }
                            if (obj) m.copy(up = "y").also { store.save(it) } else m
                        }
                    val file = store.source(meta.id)
                    Diagnostics.log("import $name, ${file.length()} bytes")
                    val head = file.inputStream().use { s -> ByteArray(1024).let { b -> b.copyOf(maxOf(0, s.read(b))) } }
                    if (!name.lowercase().endsWith(".stl") && !name.lowercase().endsWith(".obj") && !name.lowercase().endsWith(".3mf") && !name.lowercase().endsWith(".glb") && !name.lowercase().endsWith(".gltf") && !StlReader.looksLikeStl(head, file.length())) {
                        store.delete(meta.id)
                        error("That file is not an STL, OBJ, 3MF or glTF model.")
                    }
                    meta
                }
                analyze(meta, deleteOnFailure = true)
            } catch (e: Throwable) {
                fail("import", e)
            }
        }
    }

    /**
     * A new design with nothing but a base board to build on. STL has no empty file, so the project's
     * source is that one board; everything else is added in the app and kept as changes.
     */
    /** Countries that measure in inches, for a new design's display units. */
    val inchLocale: Boolean get() = java.util.Locale.getDefault().country in setOf("US", "LR", "MM")

    /** Starts a project from a template ([key] in [splitziner.edit.Templates]), or from one board when [key] is null. Sizes in mm. */
    fun newDesign(key: String? = null, values: Map<String, Double> = emptyMap(), material: String? = null) {
        if (_state.value.busyLabel != null) return
        viewModelScope.launch {
            try {
                val inch = inchLocale
                val meta = withContext(Dispatchers.IO) {
                    val t = key?.let { splitziner.edit.Templates.byKey(it) }
                    val meshes = if (t != null) t.build(splitziner.edit.Templates.defaults(t, inch) + values)
                        else listOf(if (inch) splitziner.edit.Editor.box(splitziner.math.Vec3(0.0, 0.0, 0.0), splitziner.math.Vec3(24 * 25.4, 16 * 25.4, 0.75 * 25.4))
                        else splitziner.edit.Editor.box(splitziner.math.Vec3(0.0, 0.0, 0.0), splitziner.math.Vec3(600.0, 400.0, 18.0)))
                    val name = t?.name ?: "New design"
                    val bytes = java.io.ByteArrayOutputStream().also { splitziner.io.StlWriter.writeParts(meshes, it) }.toByteArray()
                    val m = store.create("$name.stl", bytes.inputStream())
                    m.copy(name = name, fileUnits = "mm", displayUnits = if (inch) "in" else "mm", inchStyle = if (inch) "fraction" else m.inchStyle).also { store.save(it) }
                }
                analyze(meta, deleteOnFailure = true)
                // The material chosen with the template, given to every item; each can still be changed on the Cut list tab.
                if (material != null) _state.value.open?.takeIf { it.meta.id == meta.id }?.let { o ->
                    update { m -> m.copy(items = m.items + o.analysis.cutList.rows.associate { r -> CutList.itemKey(r.kind, r.t, r.w, r.l) to ItemOverride(material = material) }) }
                }
            } catch (e: Throwable) { fail("new design", e) }
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

    /** Sets [row] as given and gives every item that has no material yet the same material. */
    fun setItemAndRest(row: CutListRow, value: ItemOverride) {
        val rows = _state.value.open?.analysis?.cutList?.rows ?: return
        update { m ->
            val more = rows.filter { it.item != row.item && it.material.isBlank() }.associate { r ->
                val k = CutList.itemKey(r.kind, r.t, r.w, r.l)
                k to (m.items[k] ?: ItemOverride()).copy(material = value.material)
            }
            m.copy(items = m.items + more + (CutList.itemKey(row.kind, row.t, row.w, row.l) to value))
        }
    }

    fun duplicateProject(meta: ProjectMeta) {
        runCatching { store.duplicate(meta, "${meta.name} (copy)") }.onFailure { Diagnostics.logError("duplicate", it); _state.update { s -> s.copy(error = "Could not copy the project") } }
        _state.update { it.copy(projects = store.list()) }
    }

    fun renameProject(meta: ProjectMeta, name: String) {
        val n = name.trim().ifBlank { return }
        store.save(meta.copy(name = n))
        _state.update { it.copy(projects = store.list(), open = it.open?.let { o -> if (o.meta.id == meta.id) OpenProject(o.meta.copy(name = n), o.analysis) else o }) }
    }

    fun deleteProject(meta: ProjectMeta) {
        store.delete(meta.id)
        // Its build progress and shopping ticks go with it.
        val app = getApplication<Application>()
        app.getSharedPreferences("build", android.content.Context.MODE_PRIVATE).edit().remove("done_${meta.id}").remove("total_${meta.id}").apply()
        app.getSharedPreferences("shopping", android.content.Context.MODE_PRIVATE).edit().remove("ticked_${meta.id}").apply()
        _state.update { it.copy(projects = store.list(), open = if (it.open?.meta?.id == meta.id) null else it.open) }
    }

    private suspend fun analyze(meta0: ProjectMeta, deleteOnFailure: Boolean) {
        _state.update { it.copy(busyLabel = "Reading triangles", busyFraction = 0.05f, error = null) }
        try {
            var meta = meta0
            var analysis = withContext(Dispatchers.Default) {
                Pipeline.analyze(store.source(meta.id), meta.options(), { f, label ->
                    _state.update { it.copy(busyLabel = label, busyFraction = f.toFloat()) }
                }, meta.fileName)
            }
            // A newly imported model that looks as if it is lying on its back (saved with Y up) is stood up,
            // and its notes say so; the user can still pick another way under Which way is up.
            if (deleteOnFailure && meta.up == "z" && !meta.upGuessed) {
                val up = withContext(Dispatchers.Default) { runCatching { Pipeline.guessUp(analysis.parts) }.getOrDefault("z") }
                if (up != "z") {
                    meta = meta.copy(up = up, upGuessed = true).also { store.save(it) }
                    _state.update { it.copy(busyLabel = "Standing the model up", busyFraction = 0.5f) }
                    analysis = withContext(Dispatchers.Default) { Pipeline.analyze(store.source(meta.id), meta.options(), { _, _ -> }, meta.fileName) }
                    Diagnostics.log("import: turned to $up up")
                }
                // Standing, but facing sideways (its drawers and doors on a side): turned to face the front, noted the same way.
                val turn = withContext(Dispatchers.Default) { runCatching { Pipeline.guessTurn(analysis.parts) }.getOrDefault(0) }
                if (turn != 0) {
                    meta = meta.copy(up = "${meta.up}>$turn", upGuessed = true).also { store.save(it) }
                    _state.update { it.copy(busyLabel = "Turning it to face you", busyFraction = 0.6f) }
                    analysis = withContext(Dispatchers.Default) { Pipeline.analyze(store.source(meta.id), meta.options(), { _, _ -> }, meta.fileName) }
                    Diagnostics.log("import: turned $turn quarter(s) to face the front")
                }
            }
            val r = analysis.report
            Diagnostics.log("analysis: ${r.faces} tris, ${r.method}, ${r.bodies} bodies, ${r.parts} parts, units ${r.units.fileUnits}/${r.units.designUnits} margin ${r.units.margin}, ${analysis.timingsMs}")
            val saved = meta.copy(partCount = analysis.parts.size, size = analysis.overallSize, updatedAt = System.currentTimeMillis())
            store.save(saved)
            drawingJob?.cancel(); guideJob?.cancel(); checkJob?.cancel()
            _state.update { it.copy(open = OpenProject(saved, analysis), busyLabel = null, projects = store.list(), drawing = null, drawingBusy = false, guide = null, guideBusy = false, check = null, checkBusy = false, explodeOffsets = null) }
            saveThumb(saved, analysis)
        } catch (e: Throwable) {
            if (deleteOnFailure) store.delete(meta0.id)
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
