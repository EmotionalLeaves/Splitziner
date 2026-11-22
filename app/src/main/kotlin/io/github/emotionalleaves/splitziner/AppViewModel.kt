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

class OpenProject(val meta: ProjectMeta, val analysis: Analysis)

data class UiState(
    val projects: List<ProjectMeta> = emptyList(),
    val open: OpenProject? = null,
    /** Non-null while work is running: what is happening, and how far along (0..1). */
    val busyLabel: String? = null,
    val busyFraction: Float = 0f,
    val error: String? = null,
    val crashReport: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        _state.update { it.copy(projects = store.list(), crashReport = Diagnostics.lastCrash()) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissCrash() { Diagnostics.clearCrash(); _state.update { it.copy(crashReport = null) } }
    fun closeProject() = _state.update { it.copy(open = null, projects = store.list()) }

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
            _state.update { it.copy(open = OpenProject(saved, analysis), busyLabel = null, projects = store.list()) }
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
