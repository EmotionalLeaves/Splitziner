package io.github.emotionalleaves.splitziner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.emotionalleaves.splitziner.gl.ModelView
import io.github.emotionalleaves.splitziner.render.PdfExporter
import io.github.emotionalleaves.splitziner.render.SheetPreview
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import splitziner.export.CsvWriter
import splitziner.parts.CutListRow
import splitziner.parts.ItemOverride
import splitziner.parts.Part
import java.io.File

@Composable
fun SplitzinerApp(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val open = state.open
                if (open == null) HomeScreen(state, vm) else ProjectScreen(open, state, vm)
            }
        }
        state.error?.let { msg ->
            AlertDialog(
                onDismissRequest = vm::dismissError,
                confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
                title = { Text("That didn't work") },
                text = { Text(msg) },
            )
        }
        state.crashReport?.let { report ->
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = vm::dismissCrash,
                confirmButton = { TextButton(onClick = { copy(context, Diagnostics.report("")); vm.dismissCrash() }) { Text("Copy report") } },
                dismissButton = { TextButton(onClick = vm::dismissCrash) { Text("Dismiss") } },
                title = { Text("The app closed unexpectedly last time") },
                text = { Text("Copy the report and paste it to whoever is helping you fix the app.\n\n" + report.lineSequence().take(3).joinToString("\n")) },
            )
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Splitziner report", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun BusyBar(state: UiState) {
    val label = state.busyLabel ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { state.busyFraction }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HomeScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    // STL has no reliable MIME type across file apps, so the picker shows every file.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFrom(uri) }
    var confirmDelete by remember { mutableStateOf<ProjectMeta?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Splitziner", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Turn an STL into parts, a cut list and drawings.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = state.busyLabel == null, modifier = Modifier.fillMaxWidth()) { Text("Import STL") }
        BusyBar(state)
        Spacer(Modifier.height(8.dp))
        if (state.projects.isEmpty() && state.busyLabel == null) {
            Text("Import an STL file to get started.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable(enabled = state.busyLabel == null) { vm.openProject(p) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (p.partCount > 0) "${p.partCount} parts" else p.fileName, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { confirmDelete = p }) { Text("Delete") }
                    }
                }
            }
        }
        TextButton(onClick = { copy(context, vm.diagnosticReport()) }) { Text("Copy diagnostic report") }
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            title = { Text("Delete ${p.name}?") },
            text = { Text("This removes the project from the app. Your original file is not touched.") },
        )
    }
}

@Composable
private fun ProjectScreen(open: OpenProject, state: UiState, vm: AppViewModel) {
    BackHandler { vm.closeProject() }
    val a = open.analysis
    val r = a.report
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showUnits by remember { mutableStateOf(r.units.lowConfidence && open.meta.fileUnits == null) }
    var editPart by remember { mutableStateOf<Part?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editRow by remember { mutableStateOf<CutListRow?>(null) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::closeProject) { Text("← Back") }
            Text(open.meta.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        BusyBar(state)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Found ${r.parts} part${if (r.parts == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (r.duplicatesRemoved > 0) {
                    Text("The file stored ${r.bodies} bodies; ${r.duplicatesRemoved} were exact duplicates and were removed.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Overall size ${a.overallSize}", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val guessed = if (open.meta.fileUnits == null) " (guessed)" else ""
                    Text("File saved in ${unitName(r.units.fileUnits)}$guessed, shown in ${unitName(a.format.units)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showUnits = true }) { Text("Change") }
                }
                for (w in r.warnings) Text("Note: $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("3D", "Parts", "Cut list", "Drawing", "Guide").forEachIndexed { i, label -> TabButton(label, tab == i, Modifier) { tab = i } }
        }
        if (tab == 3) {
            DrawingTab(open, state, vm, Modifier.weight(1f))
        } else if (tab == 4) {
            GuideTab(open, state, vm, Modifier.weight(1f))
        } else
        if (tab == 0) {
            val sel = a.parts.firstOrNull { it.id == selectedId }
            ModelView(a, selectedId, onSelect = { selectedId = it }, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
            if (sel == null) {
                Text("Drag to turn, pinch to zoom, two fingers to move. Tap a part to select it.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${sel.name}  \u00b7  item ${sel.item ?: "-"}  \u00d7${sel.qty}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sel.desc, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { editPart = sel }) { Text("Edit") }
                }
            }
        } else if (tab == 1) {
            LazyColumn(Modifier.weight(1f)) {
                items(a.parts, key = { it.id }) { p ->
                    Column(Modifier.fillMaxWidth().clickable { editPart = p }.padding(vertical = 8.dp)) {
                        Row {
                            Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (p.excluded) "excluded" else "item ${p.item ?: "-"}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (p.excluded) "Left out of lists and drawings" else p.desc, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(a.cutList.rows, key = { it.item }) { row ->
                    Row(Modifier.fillMaxWidth().clickable { editRow = row }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${row.item}", fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.description, style = MaterialTheme.typography.bodyMedium)
                            Text(if (row.material.isBlank()) "Tap to set material" else row.material, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("×${row.qty}", style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider()
                }
                if (a.cutList.stock.isNotEmpty()) {
                    item {
                        Text("Stock to buy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                        Text("Total length before saw kerf and waste", style = MaterialTheme.typography.bodySmall)
                    }
                    items(a.cutList.stock) { s ->
                        val shape = if (s.kind.name == "ROD") "⌀${s.thickness}" else "${s.thickness} × ${s.width}"
                        Text("${s.kind} $shape: ${s.totalLength} ${a.format.unitLabel} in ${s.pieces} piece${if (s.pieces == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                item {
                    for (w in a.cutList.warnings) Text("Note: $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = { shareCsv(context, open) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Share cut list (CSV)") }
        }
        TextButton(onClick = { copy(context, vm.diagnosticReport()) }) { Text("Copy diagnostic report") }
    }

    if (showUnits) UnitsDialog(open, onDismiss = { showUnits = false }) { file, display, style ->
        showUnits = false
        vm.update { it.copy(fileUnits = file, displayUnits = display, inchStyle = style) }
    }
    editPart?.let { p ->
        PartDialog(p, onDismiss = { editPart = null }) { name, excluded ->
            editPart = null
            vm.update { m ->
                m.copy(
                    partNames = if (name.isBlank() || name == p.id) m.partNames - p.id else m.partNames + (p.id to name),
                    excluded = if (excluded) m.excluded + p.id else m.excluded - p.id,
                )
            }
        }
    }
    editRow?.let { row ->
        ItemDialog(row, onDismiss = { editRow = null }) { o -> editRow = null; vm.setItem(row, o) }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

private fun unitName(u: String) = when (u) { "mm" -> "millimetres"; "cm" -> "centimetres"; "in" -> "inches"; "m" -> "metres"; else -> u }

@Composable
private fun <T> Choice(label: String, value: T, current: T, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = value == current, onClick = { onPick(value) }).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = value == current, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun UnitsDialog(open: OpenProject, onDismiss: () -> Unit, onApply: (String?, String?, String) -> Unit) {
    var file by remember { mutableStateOf(open.meta.fileUnits) }
    var display by remember { mutableStateOf(open.meta.displayUnits) }
    var style by remember { mutableStateOf(open.meta.inchStyle) }
    val g = open.analysis.report.units
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(file, display, style) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Units") },
        text = {
            LazyColumn {
                item {
                    Text("STL files don't say what units they use. The app's guess: saved in ${unitName(g.fileUnits)}, designed in ${unitName(g.designUnits)}." +
                        (g.alternative?.let { " Other possibility: $it." } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Text("The file's numbers are in", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    Choice("Let the app guess", null, file) { file = it }
                    Choice("Millimetres", "mm", file) { file = it }
                    Choice("Centimetres", "cm", file) { file = it }
                    Choice("Inches", "in", file) { file = it }
                    Choice("Metres", "m", file) { file = it }
                    Text("Show sizes in", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    Choice("Whatever it was designed in", null to "decimal", display to style) { display = null; style = "decimal" }
                    Choice("Millimetres", "mm" to "decimal", display to style) { display = "mm"; style = "decimal" }
                    Choice("Inches, decimal (23.62)", "in" to "decimal", display to style) { display = "in"; style = "decimal" }
                    Choice("Inches, fractions (23-5/8)", "in" to "fraction", display to style) { display = "in"; style = "fraction" }
                }
            }
        },
    )
}

@Composable
private fun PartDialog(p: Part, onDismiss: () -> Unit, onApply: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var excluded by remember { mutableStateOf(p.excluded) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(name.trim(), excluded) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(p.id) },
        text = {
            Column {
                Text(p.desc, style = MaterialTheme.typography.bodySmall)
                if (p.copies > 1) Text("Stored ${p.copies} times in the file; counted once.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().clickable { excluded = !excluded }.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = excluded, onClick = null)
                    Text("Leave this part out", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
    )
}

@Composable
private fun ItemDialog(row: CutListRow, onDismiss: () -> Unit, onApply: (ItemOverride) -> Unit) {
    var material by remember { mutableStateOf(row.material) }
    var spec by remember { mutableStateOf(row.materialSpec) }
    var partNo by remember { mutableStateOf(row.partNo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(ItemOverride(material.trim(), spec.trim(), partNo.trim())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Item ${row.item}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${row.description}  ×${row.qty}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = material, onValueChange = { material = it }, label = { Text("Material (e.g. birch plywood)") }, singleLine = true)
                OutlinedTextField(value = spec, onValueChange = { spec = it }, label = { Text("Material spec or grade") }, singleLine = true)
                OutlinedTextField(value = partNo, onValueChange = { partNo = it }, label = { Text("Part number") }, singleLine = true)
            }
        },
    )
}

private fun shareCsv(context: Context, open: OpenProject) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val base = open.meta.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "splitziner" }
        val file = File(dir, "${base}_cutlist.csv")
        file.writeText(CsvWriter.cutList(open.analysis.cutList, open.analysis.format))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share cut list"))
    } catch (e: Exception) {
        Diagnostics.logError("share csv", e)
        Toast.makeText(context, "Could not share the file", Toast.LENGTH_LONG).show()
    }
}


@Composable
private fun DrawingTab(open: OpenProject, state: UiState, vm: AppViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    var showQa by remember { mutableStateOf(false) }
    var sheetIndex by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(open.analysis) { vm.ensureDrawing() }
    val d = state.drawing
    val shown = if (d != null) sheetIndex.coerceIn(0, maxOf(d.sheets.size - 1, 0)) else 0
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showInfo = true }) { Text("Title block") }
            if (d != null) {
                Text("Sheet ${d.sheetLetter}, ${d.scaleText}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (d.qa.isNotEmpty()) TextButton(onClick = { showQa = true }) { Text("${d.qa.size} note${if (d.qa.size == 1) "" else "s"}") }
            } else Spacer(Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)) {
            if (d != null && d.sheets.isNotEmpty()) SheetPreview(d.sheets[shown], Modifier.fillMaxSize())
            if (state.drawingBusy) {
                Column(Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                    Text("Working out which edges are visible\u2026", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (d != null && d.sheets.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.sheets.indices.forEach { i -> TabButton(if (i == 0) "Sheet 1: views" else "Sheet ${i + 1}: exploded", shown == i, Modifier.weight(1f)) { sheetIndex = i } }
            }
        }
        Text("Pinch to zoom, drag to move, double-tap to fit.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { if (d != null) sharePdf(context, open, d) }, enabled = d != null && !state.drawingBusy, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("Share drawing (PDF)") }
    }
    if (showInfo) TitleBlockDialog(open.meta, onDismiss = { showInfo = false }) { m -> showInfo = false; vm.updateDrawing { m } }
    if (showQa && d != null) {
        AlertDialog(
            onDismissRequest = { showQa = false },
            confirmButton = { TextButton(onClick = { showQa = false }) { Text("OK") } },
            title = { Text("About this drawing") },
            text = { Column { for (q in d.qa) Text("\u2022 $q", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) } },
        )
    }
}

private val TOLERANCE_PRESETS: List<Pair<String, List<String>?>> = listOf(
    "Not specified" to null,
    "Woodworking (fractions \u00b11/16)" to listOf("FRACTIONS \u00b11/16", "ANGLES \u00b11\u00b0"),
    "Machined, inch (.XX \u00b1.01)" to listOf(".XX \u00b1.01  .XXX \u00b1.005", "ANGLES \u00b10.5\u00b0"),
    "Machined, mm (X.X \u00b10.2)" to listOf("X.X \u00b10.2  X.XX \u00b10.1", "ANGLES \u00b10.5\u00b0"),
    "Standard sample (.XX \u00b1.02)" to listOf(".XX \u00b1.02  .XXX \u00b1.005", "ANGLES \u00b11\u00b0", "FRACTIONS \u00b11/16"),
)

@Composable
private fun TitleBlockDialog(meta: ProjectMeta, onDismiss: () -> Unit, onApply: (ProjectMeta) -> Unit) {
    var m by remember { mutableStateOf(meta) }
    var notes by remember { mutableStateOf(meta.notes.joinToString("\n")) }
    var revError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // I, O, Q and X are never used as revision letters: they are too easily misread.
                if (!splitziner.sheet.DrawingInfo.revisionAllowed(m.rev)) revError = true
                else onApply(m.copy(notes = notes.lines().map { it.trim() }.filter { it.isNotEmpty() }))
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Title block") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(m.title, { m = m.copy(title = it) }, label = { Text("Title") }, singleLine = true, supportingText = { Text("Noun first, then details: CABINET, STORAGE, BIN") })
                OutlinedTextField(m.drawingNo, { m = m.copy(drawingNo = it) }, label = { Text("Drawing number") }, singleLine = true)
                OutlinedTextField(m.rev, { m = m.copy(rev = it); revError = false }, label = { Text("Revision") }, singleLine = true, isError = revError,
                    supportingText = { if (revError) Text("The letters I, O, Q and X are not used for revisions.") })
                OutlinedTextField(m.organisation, { m = m.copy(organisation = it) }, label = { Text("Organisation (optional)") }, singleLine = true)
                OutlinedTextField(m.designer, { m = m.copy(designer = it) }, label = { Text("Designer") }, singleLine = true)
                OutlinedTextField(m.drawn, { m = m.copy(drawn = it) }, label = { Text("Drawn by") }, singleLine = true)
                OutlinedTextField(m.checked, { m = m.copy(checked = it) }, label = { Text("Checked by") }, singleLine = true)
                OutlinedTextField(m.approved, { m = m.copy(approved = it) }, label = { Text("Approved by") }, singleLine = true)
                OutlinedTextField(m.date, { m = m.copy(date = it) }, label = { Text("Date") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Extra notes, one per line") }, minLines = 2)
                Text("Tolerances", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text("The app never picks tolerances for you.", style = MaterialTheme.typography.bodySmall)
                for ((label, lines) in TOLERANCE_PRESETS) Choice(label, lines, m.tolerance) { m = m.copy(tolerance = it) }
                Text("Sheet size", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                for ((label, v) in listOf("Choose for me" to "auto", "A (8.5 \u00d7 11 in)" to "A", "B (11 \u00d7 17 in)" to "B", "C (17 \u00d7 22 in)" to "C", "D (22 \u00d7 34 in)" to "D", "E (34 \u00d7 44 in)" to "E")) {
                    Choice(label, v, m.sheet) { m = m.copy(sheet = it) }
                }
                Text("Section views (cut open to show the inside)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                for ((label, v) in listOf("When the inside is hidden" to "auto", "Never" to "none")) Choice(label, v, m.sections) { m = m.copy(sections = it) }
                Text("Hidden edges (dashed lines)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                for ((label, v) in listOf("Choose for me" to "auto", "Always show" to "on", "Never show" to "off")) Choice(label, v, m.hidden) { m = m.copy(hidden = it) }
            }
        },
    )
}

private fun sharePdf(context: Context, open: OpenProject, d: DrawingState) = sharePdf(context, open, d.sheets, "drawing")

private fun sharePdf(context: Context, open: OpenProject, sheets: List<io.github.emotionalleaves.splitziner.render.PreparedSheet>, kind: String) {
    try {
        val base = open.meta.drawingNo.ifBlank { open.meta.name }.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "splitziner" }
        val file = PdfExporter.write(context, sheets, "${base}_$kind.pdf")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share $kind"))
    } catch (e: Throwable) {
        Diagnostics.logError("share pdf", e)
        Toast.makeText(context, "Could not make the PDF", Toast.LENGTH_LONG).show()
    }
}


@Composable
private fun GuideTab(open: OpenProject, state: UiState, vm: AppViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(open.analysis, state.guide == null) { vm.ensureGuide() }
    val g = state.guide
    val shown = if (g != null) page.coerceIn(0, maxOf(g.pages.size - 1, 0)) else 0
    Column(modifier.fillMaxWidth()) {
        Text("The build order is a draft worked out from the shapes alone. It does not know about glue, clamps or screws: read it through before you build.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)) {
            if (g != null && g.pages.isNotEmpty()) SheetPreview(g.pages[shown], Modifier.fillMaxSize())
            if (state.guideBusy) {
                Column(Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                    Text("Working out the build order and drawing each step\u2026", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (g != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { page = shown - 1 }, enabled = shown > 0) { Text("\u2190 Back") }
                Text("Page ${shown + 1} of ${g.pages.size}  \u00b7  ${g.stepCount} steps", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                OutlinedButton(onClick = { page = shown + 1 }, enabled = shown < g.pages.size - 1) { Text("Next \u2192") }
            }
        }
        Button(onClick = { if (g != null) sharePdf(context, open, g.pages, "guide") }, enabled = g != null && !state.guideBusy, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("Share build guide (PDF)") }
    }
}
