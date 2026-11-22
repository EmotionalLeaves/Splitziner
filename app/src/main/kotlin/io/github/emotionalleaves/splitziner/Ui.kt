package io.github.emotionalleaves.splitziner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabButton("Parts", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabButton("Cut list", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }
        if (tab == 0) {
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
