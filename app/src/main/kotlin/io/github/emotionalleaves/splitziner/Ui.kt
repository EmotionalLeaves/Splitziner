package io.github.emotionalleaves.splitziner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Build
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
    SplitzinerTheme {
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
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { state.busyFraction }, modifier = Modifier.fillMaxWidth().height(6.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun HomeScreen(state: UiState, vm: AppViewModel) {
    var showNew by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // STL has no reliable MIME type across file apps, so the picker shows every file.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFrom(uri) }
    var confirmDelete by remember { mutableStateOf<ProjectMeta?>(null) }
    var renaming by remember { mutableStateOf<ProjectMeta?>(null) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        // Header: the app's name and what it does, with the rarely used things in a menu.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Splitziner", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("From 3D model to workshop drawings", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Check for updates") }, onClick = { menu = false; vm.checkForUpdate() })
                    DropdownMenuItem(text = { Text("Copy diagnostic report") }, onClick = { menu = false; copy(context, vm.diagnosticReport()) })
                    DropdownMenuItem(text = { Text("Version ${BuildConfig.VERSION_NAME}") }, onClick = { menu = false }, enabled = false)
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            state.update?.let { u ->
                Card(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { vm.installUpdate() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Version ${u.version} is available", style = MaterialTheme.typography.titleSmall)
                            Text(u.title.removePrefix("Splitziner ").ifBlank { "Tap Update to install it" }, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { vm.installUpdate() }, enabled = state.updateProgress == null) { Text("Update") }
                    }
                    state.updateProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = state.busyLabel == null, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import")
                }
                OutlinedButton(onClick = { showNew = true }, enabled = state.busyLabel == null, modifier = Modifier.weight(1f).height(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Default.Create, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New design", maxLines = 1, softWrap = false)
                }
            }
            Text("STL, OBJ or 3MF files, or a project shared from the app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (showNew) NewDesignDialog(vm, onDismiss = { showNew = false })
            BusyBar(state)
        }
        if (state.projects.isEmpty() && state.busyLabel == null) {
            // First run: what the app makes, in the order it makes it.
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How it works", style = MaterialTheme.typography.titleMedium)
                    for ((n, t) in listOf("Import a model from your 3D app, or start from a template such as a bookcase or a chest of drawers.",
                        "The app splits it into parts, checks each one can really be made, and offers fixes.",
                        "You get engineering drawings, a step-by-step build guide, a cut list, a cutting plan and a shopping list.").withIndex()) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                                Text("${n + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        } else if (state.projects.isNotEmpty()) {
            Text("Projects", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
            items(state.projects, key = { it.id }) { p -> ProjectCard(p, vm.thumbFile(p.id), state.thumbsVersion, enabled = state.busyLabel == null, onOpen = { vm.openProject(p) }, onDelete = { confirmDelete = p },
                onDuplicate = { vm.duplicateProject(p) }, onRename = { renaming = p }) }
        }
    }
    UpdateDialogs(state, vm)
    renaming?.let { p ->
        var name by remember(p) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = { TextButton(onClick = { vm.renameProject(p, name); renaming = null }, enabled = name.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            title = { Text("Rename project") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        )
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
    var showUp by remember { mutableStateOf(false) }
    if (showUp) AlertDialog(
        onDismissRequest = { showUp = false },
        confirmButton = { TextButton(onClick = { showUp = false }) { Text("Close") } },
        title = { Text("Which way is up?") },
        text = {
            Column {
                Text("STL files do not say which way is up. The app takes the file's Z axis as up; many programs (Blender, and Fusion and others with their default setting) save with Y up, so the model arrives lying on its back. Pick what makes it stand the right way. Floor, build order and drawings all follow this.", style = MaterialTheme.typography.bodySmall)
                for ((key, label) in listOf("z" to "Z up (as the file is)", "y" to "Y up (common from Blender, Fusion)", "x" to "X up", "-z" to "Upside down", "-y" to "Y down", "-x" to "X down")) {
                    Row(Modifier.fillMaxWidth().clickable { showUp = false; if (key != open.meta.up) vm.update { it.copy(up = key) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = open.meta.up == key, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (open.meta.edits.isNotEmpty()) Text("Your changes to parts were made the old way up; moves will now go in the new directions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
    )
    var editPart by remember { mutableStateOf<Part?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pull by remember { mutableFloatStateOf(0f) }
    var showHelp by remember { mutableStateOf(false) }
    var editRow by remember { mutableStateOf<CutListRow?>(null) }
    var shapePart by remember { mutableStateOf<Part?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showSize by remember { mutableStateOf(false) }
    var showChanges by remember { mutableStateOf(false) }
    var showShelf by remember { mutableStateOf(false) }
    if (showShelf) ShelfDialog(open, onDismiss = { showShelf = false }) { es -> showShelf = false; if (es.size == 1) vm.addEdit(es[0]) else vm.addEdits(es); selectedId = (es.firstOrNull() as? splitziner.edit.Edit.AddBox)?.id }
    if (showChanges) ChangesDialog(open, vm, onDismiss = { showChanges = false })
    if (showSize) OverallSizeDialog(open, onDismiss = { showSize = false }) { es -> showSize = false; vm.addEdits(es) }
    shapePart?.let { p -> ShapeDialog(p, open, onDismiss = { shapePart = null }) { e -> shapePart = null; vm.addEdit(e) } }
    if (showAdd) AddBoardDialog(open, onDismiss = { showAdd = false }) { e -> showAdd = false; vm.addEdit(e); selectedId = (e as? splitziner.edit.Edit.AddBox)?.id }
    var showCutPlan by remember { mutableStateOf(false) }
    var showShopping by remember { mutableStateOf(false) }
    var showPrices by remember { mutableStateOf(false) }
    var priceVersion by remember { mutableIntStateOf(0) }
    if (showPrices) PricesDialog(open, onDismiss = { showPrices = false; priceVersion++ })
    if (showCutPlan) CutPlanDialog(open, onDismiss = { showCutPlan = false })
    if (showShopping) ShoppingDialog(open, vm, onDismiss = { showShopping = false })
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var details by rememberSaveable { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        // App bar: back, the project and what it is, help and the project's own menu.
        Row(Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeProject) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).clickable { details = true }.padding(vertical = 4.dp)) {
                Text(open.meta.title.ifBlank { open.meta.name }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val notes = r.warnings.size + (if (r.duplicatesRemoved > 0) 1 else 0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.parts} part${if (r.parts == 1) "" else "s"}  \u00b7  ${a.overallSize}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (notes > 0) Text("  \u00b7  $notes note${if (notes == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
                }
            }
            Box {
                IconButton(onClick = { projectMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Project menu") }
                DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                    DropdownMenuItem(text = { Text("Project details") }, leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }, onClick = { projectMenu = false; details = true })
                    DropdownMenuItem(text = { Text("Help") }, leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) }, onClick = { projectMenu = false; showHelp = true })
                    DropdownMenuItem(text = { Text("Share project") }, leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }, onClick = {
                        projectMenu = false
                        val f = vm.exportProject()
                        if (f == null) Toast.makeText(context, "Could not save the project", Toast.LENGTH_LONG).show()
                        else shareFile(context, f, "application/zip", "Share project")
                    })
                    DropdownMenuItem(text = { Text("Share build pack") }, leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }, onClick = {
                        projectMenu = false
                        shareBuildPack(context, open, vm, scope)
                    })
                    DropdownMenuItem(text = { Text("Units") }, onClick = { projectMenu = false; showUnits = true })
                    DropdownMenuItem(text = { Text("Which way is up") }, onClick = { projectMenu = false; showUp = true })
                }
            }
        }
        if (details) AlertDialog(
            onDismissRequest = { details = false },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } },
            title = { Text("Project details") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val guessed = if (open.meta.fileUnits == null) " (guessed)" else ""
                    Text("${r.parts} part${if (r.parts == 1) "" else "s"}, ${a.overallSize} overall.", style = MaterialTheme.typography.bodyMedium)
                    Text("File ${open.meta.fileName}, read as ${unitName(r.units.fileUnits)}$guessed; shown in ${unitName(a.format.units)}.", style = MaterialTheme.typography.bodySmall)
                    if (r.duplicatesRemoved > 0) Text("The file stored ${r.bodies} bodies; ${r.duplicatesRemoved} were exact duplicates and were removed.", style = MaterialTheme.typography.bodySmall)
                    for (w in r.warnings) Text("Note: $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    Row {
                        TextButton(onClick = { details = false; showUnits = true }) { Text("Units") }
                        TextButton(onClick = { details = false; showUp = true }) { Text("Which way is up") }
                    }
                    TextButton(onClick = {
                        val f = vm.exportProject()
                        if (f == null) Toast.makeText(context, "Could not save the project", Toast.LENGTH_LONG).show()
                        else shareFile(context, f, "application/zip", "Share project")
                    }) { Text("Share project (to back up or open on another phone)") }
                }
            },
        )
        Box(Modifier.padding(horizontal = 16.dp)) { BusyBar(state) }
        // The check runs as soon as a project opens, so its tab can say at once whether anything needs attention.
        LaunchedEffect(open.analysis) { vm.ensureCheck() }
        val problems = state.check?.problems ?: 0
        // One row of tabs, all six always in view: compact labels without the usual side padding fit a narrow phone.
        androidx.compose.material3.PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            listOf("3D", "Parts", "Cut list", "Drawing", "Guide", "Check").forEachIndexed { i, label ->
                androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 380) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                            maxLines = 1, softWrap = false)
                        // A small red dot on Check when there are problems to fix.
                        if (i == 5 && problems > 0) Box(Modifier.padding(start = 3.dp).size(7.dp).background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
        if (tab == 3) {
            DrawingTab(open, state, vm, Modifier.weight(1f))
        } else if (tab == 4) {
            GuideTab(open, state, vm, Modifier.weight(1f))
        } else if (tab == 5) {
            CheckTab(open, state, vm, Modifier.weight(1f)) { id -> selectedId = id; tab = 0 }
        } else
        if (tab == 0) {
            val sel = a.parts.firstOrNull { it.id == selectedId }
            // Build steps played in 3D: parts appear step by step, the ones already built shown faint.
            var playing by remember { mutableStateOf(false) }
            var playPlan by remember(a) { mutableStateOf<splitziner.explode.PlanResult?>(null) }
            var playStep by remember(a) { mutableIntStateOf(1) }
            LaunchedEffect(playing, a) { if (playing && playPlan == null) playPlan = vm.planFor3D() }
            val pp = playPlan
            val stage = if (playing && pp != null) {
                val cur = pp.steps.firstOrNull { it.step == playStep }
                val now = cur?.partIds?.toSet().orEmpty()
                // A bench step is made away from the model; the rest is shown faint so it is clear where it will go.
                if (cur?.subassembly == true) pp.parts.associate { p -> p.id to if (p.id in now) 2 else 1 }
                else pp.parts.associate { p -> val s0 = p.step ?: Int.MAX_VALUE; p.id to when { p.id in now -> 2; s0 < playStep -> 1; else -> 0 } }
            } else null
            ModelView(a, selectedId, onSelect = { selectedId = it }, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), offsets = state.explodeOffsets, explode = if (playing) 0f else pull, stage = stage)
            if (playing) {
                val last = pp?.steps?.maxOfOrNull { it.step } ?: 1
                // Bench steps are built away from the model; in 3D the step shows where those parts end up.
                val st = pp?.steps?.firstOrNull { it.step == playStep }
                Box(Modifier.padding(vertical = 4.dp)) {
                    PagerBar(if (pp == null) "Working out the build order\u2026" else "Step $playStep of $last",
                        (if (st?.subassembly == true) "Made on the bench  \u00b7  " else "") + (st?.let { "items ${it.items.joinToString(", ")}" } ?: ""),
                        playStep > 1, playStep < last, { playStep = (playStep - 1).coerceAtLeast(1) }, { playStep = (playStep + 1).coerceAtMost(last) })
                }
            }
            if (a.parts.count { !it.excluded } > 1 && !playing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pull apart", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = pull, onValueChange = { pull = it; if (it > 0f) vm.ensureExplodeOffsets() }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                }
            }
            if (sel == null) {
                Text("Drag to turn, pinch to zoom, two fingers to move. Tap a part to select it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            } else Card(Modifier.fillMaxWidth().padding(bottom = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    sel.item?.let { ItemBadge(it) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("${sel.name}  \u00b7  \u00d7${sel.qty}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sel.desc, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // The clear space to the nearest part on each side, to read fits and gaps.
                        val clear by androidx.compose.runtime.produceState<List<splitziner.check.DesignCheck.Clearance>?>(null, sel.id, a) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { splitziner.check.DesignCheck.clearances(sel, a.parts) }.getOrDefault(emptyList()) }
                        }
                        clear?.filter { it.gap > 0.05 }?.takeIf { it.isNotEmpty() }?.let { cs ->
                            Text("Gap " + cs.joinToString(", ") { c -> "${c.direction} ${a.format.format(c.gap)}${c.item?.let { " to item $it" } ?: ""}" }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TextButton(onClick = { shapePart = sel }) { Text("Change") }
                    TextButton(onClick = { editPart = sel }) { Text("Name") }
                }
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                androidx.compose.material3.FilledTonalButton(onClick = { playing = !playing; playStep = 1 }, contentPadding = pad) { Text(if (playing) "Show all" else "Build steps") }
                androidx.compose.material3.FilledTonalButton(onClick = { showAdd = true }, enabled = state.busyLabel == null, contentPadding = pad) { Text("Add a board") }
                if (open.meta.edits.isNotEmpty()) androidx.compose.material3.FilledTonalButton(onClick = { vm.undoLastEdit() }, enabled = state.busyLabel == null, contentPadding = pad) { Text("Undo") }
                Box {
                    var more by remember { mutableStateOf(false) }
                    androidx.compose.material3.FilledTonalButton(onClick = { more = true }, enabled = state.busyLabel == null, contentPadding = pad) { Text("More\u2026") }
                    androidx.compose.material3.DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Add a shelf, divider, door or drawer") }, onClick = { more = false; showShelf = true })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Overall size") }, onClick = { more = false; showSize = true })
                        if (open.meta.edits.isNotEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text("Changes (${open.meta.edits.size})") }, onClick = { more = false; showChanges = true })
                    }
                }
            }
        } else if (tab == 1) {
            LazyColumn(Modifier.weight(1f)) {
                // In item order, the left-out parts last; sizes in ordinary type with their unit, like the cut list.
                val ordered = a.parts.sortedWith(compareBy({ it.excluded }, { it.item ?: Int.MAX_VALUE }, { it.id }))
                items(ordered, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { editPart = p }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val it0 = p.item; if (it0 != null && !p.excluded) ItemBadge(it0) else Box(Modifier.size(28.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val desc = p.desc.lowercase().replaceFirstChar { it.uppercase() }
                            Text(if (p.excluded) "Left out of lists and drawings" else desc.substringBefore(',') + " ${a.format.unitLabel}" + (if (desc.contains(',')) "," + desc.substringAfter(',') else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.excluded) Text("excluded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        else Text(p.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider()
                }
            }
            Text("Share parts sends each part as its own STL in a zip; Share model sends the whole model as one STL.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { shareParts(context, open) }, modifier = Modifier.weight(1f)) { Text("Share parts", maxLines = 1) }
                OutlinedButton(onClick = { shareModel(context, open) }, modifier = Modifier.weight(1f)) { Text("Share model", maxLines = 1) }
            }
        } else {
            // Roughly what it weighs, from the materials the user has set; typical densities, so only an estimate.
            val wt = remember(a.cutList.rows) { splitziner.material.Materials.weight(a.parts, a.cutList.rows) }
            val cost = remember(a, priceVersion) { runCatching { woodCost(context, splitziner.cutplan.CutPlanner.shoppingItems(splitziner.cutplan.CutPlanner.plan(a.cutList.rows, savedStock(context, a.format)), a.format)) }.getOrNull() }
            // What to buy, first: the stock the cutting plan uses, what it costs and roughly what the piece weighs.
            val buy = remember(a) { runCatching { splitziner.cutplan.CutPlanner.shoppingItems(splitziner.cutplan.CutPlanner.plan(a.cutList.rows, savedStock(context, a.format)), a.format) }.getOrDefault(emptyList()) }
            Card(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(12.dp)) {
                    Text("To buy", style = MaterialTheme.typography.titleSmall)
                    for (b in buy.take(4)) Text("\u2022 ${b.line.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (buy.size > 4) Text("and ${buy.size - 4} more: see the cutting plan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (cost != null && cost.first > 0) Text("About ${money(cost.first)} of wood" + if (cost.second > 0) " (${cost.second} kind${if (cost.second == 1) "" else "s"} of stock not priced)" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        if (wt.unknownItems.size == a.cutList.rows.size) "Set a material (tap an item) to get a weight estimate."
                        else "Weighs about ${splitziner.material.Materials.format(wt.kg, a.format.isInch)} (typical densities)" +
                            if (wt.unknownItems.isEmpty()) "." else if (wt.unknownItems.size > 6) ", not counting ${wt.unknownItems.size} items with no material, or one the app does not know."
                            else ", not counting item${if (wt.unknownItems.size == 1) "" else "s"} ${wt.unknownItems.joinToString(", ")}: no material, or one the app does not know.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
            val partById = remember(a) { a.parts.associateBy { it.id } }
            LazyColumn(Modifier.weight(1f)) {
                items(a.cutList.rows, key = { it.item }) { row ->
                    Row(Modifier.fillMaxWidth().clickable { editRow = row }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ItemBadge(row.item)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            // What the parts are called (Side, Shelf, Drawer side...), when they have names.
                            val names = row.name.ifBlank { row.memberIds.mapNotNull { partById[it] }.filter { it.name != it.id }.map { it.name }.distinct().sorted().let { if (it.isEmpty()) "" else it.take(3).joinToString(" / ") + if (it.size > 3) "…" else "" } }
                            Text(names.ifBlank { row.description.substringBefore(' ').lowercase().replaceFirstChar { it.uppercase() } }, style = MaterialTheme.typography.titleSmall)
                            // "Panel 18 x 600 x 1500, notched", in the units shown.
                            val desc = row.description.lowercase().replaceFirstChar { it.uppercase() }
                            Text(desc.substringBefore(',') + " ${a.format.unitLabel}" + (if (desc.contains(',')) "," + desc.substringAfter(',') else ""), style = MaterialTheme.typography.bodyMedium)
                            Text((if (row.material.isBlank()) "Tap to set material" else row.material) + if (row.grain) "  \u00b7  grain along length" else "", style = MaterialTheme.typography.bodySmall,
                                color = if (row.material.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("\u00d7${row.qty}", style = MaterialTheme.typography.titleMedium)
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
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { showCutPlan = true }) { Text("Cutting plan") }
                OutlinedButton(onClick = { showShopping = true }) { Text("Shopping list") }
                // Less used shares and settings sit behind one button, so the list keeps the screen.
                Box {
                    var more by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { more = true }) { Text("More\u2026") }
                    androidx.compose.material3.DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Prices") }, onClick = { more = false; showPrices = true })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Share cut list (CSV)") }, onClick = { more = false; shareCsv(context, open) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("CNC profiles (DXF)") }, onClick = { more = false; shareProfiles(context, open) })
                    }
                }
            }
        }
        }
    }

    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("OK") } },
        // For reporting a problem; kept here so it does not take a line of every screen.
        dismissButton = { TextButton(onClick = { copy(context, vm.diagnosticReport()) }) { Text("Copy diagnostic report") } },
        title = { Text("What to trust, and what to check") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((head, body) in HELP) { Text(head, fontWeight = FontWeight.Bold); Text(body, style = MaterialTheme.typography.bodyMedium) }
            }
        },
    )
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
        ItemDialog(row, remember(row, open.analysis) { vm.howTo(open.analysis, row, vm.toolsFor(open.meta)) }, onDismiss = { editRow = null }) { o, all -> editRow = null; if (all) vm.setItemAndRest(row, o) else vm.setItem(row, o) }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    if (selected) Button(onClick = onClick, modifier = modifier, contentPadding = pad) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = pad) { Text(label) }
}

@Composable
private fun UpdateDialogs(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val status = state.updateStatus
    if (status != null && state.updateProgress == null) {
        var auto by remember { mutableStateOf(Updater.autoCheck(context)) }
        AlertDialog(
            onDismissRequest = { vm.dismissUpdateStatus() },
            confirmButton = { TextButton(onClick = { vm.dismissUpdateStatus() }) { Text("OK") } },
            title = { Text("Updates") },
            text = {
                Column {
                    Text(status)
                    Row(Modifier.fillMaxWidth().clickable { auto = !auto; Updater.setAutoCheck(context, auto) }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = auto, onCheckedChange = { auto = it; Updater.setAutoCheck(context, it) })
                        Text("Look for updates once a day", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
    }
    val u = state.update
    if (u != null && state.updateProgress != null) AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Updating to ${u.version}") },
        text = {
            Column {
                Text(status ?: "Downloading…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { state.updateProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                val notes = splitziner.update.Versions.plainNotes(u.notes)
                if (notes.isNotBlank()) Text(notes.take(1200), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
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
private fun ItemDialog(row: CutListRow, howTo: List<String>, onDismiss: () -> Unit, onApply: (ItemOverride, Boolean) -> Unit) {
    var material by remember { mutableStateOf(row.material) }
    var spec by remember { mutableStateOf(row.materialSpec) }
    var partNo by remember { mutableStateOf(row.partNo) }
    var forAll by remember { mutableStateOf(false) }
    var grain by remember { mutableStateOf(row.grain) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(ItemOverride(material.trim(), spec.trim(), partNo.trim(), grain), forAll && material.isNotBlank()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Item ${row.item}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${row.description}  ×${row.qty}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = material, onValueChange = { material = it }, label = { Text("Material (e.g. birch plywood)") }, singleLine = true)
                // Common materials: tapping one fills the box, and the app then knows roughly what it weighs.
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (m in splitziner.material.Materials.COMMON.take(12)) androidx.compose.material3.SuggestionChip(onClick = { material = m.name }, label = { Text(m.name, style = MaterialTheme.typography.labelSmall) })
                }
                Row(Modifier.fillMaxWidth().clickable { forAll = !forAll }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = forAll, onCheckedChange = { forAll = it })
                    Text("Also use this material for every item that has none yet", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = spec, onValueChange = { spec = it }, label = { Text("Material spec or grade") }, singleLine = true)
                OutlinedTextField(value = partNo, onValueChange = { partNo = it }, label = { Text("Part number") }, singleLine = true)
                Row(Modifier.fillMaxWidth().clickable { grain = !grain }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = grain, onCheckedChange = { grain = it })
                    Text("Grain runs along its length (the cutting plan never turns it on the sheet)", style = MaterialTheme.typography.bodySmall)
                }
                if (howTo.isNotEmpty()) {
                    Text("How to make it", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    howTo.forEachIndexed { i, t -> Text("${i + 1}. $t", style = MaterialTheme.typography.bodySmall) }
                }
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
                Text("Assembly on sheet ${d.sheetLetter} at ${d.scaleText}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (d.qa.isNotEmpty()) TextButton(onClick = { showQa = true }) { Text("${d.qa.size} note${if (d.qa.size == 1) "" else "s"}") }
            } else Spacer(Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium).padding(6.dp)) {
            if (d != null && d.sheets.isNotEmpty()) SheetPreview(d.sheets[shown], Modifier.fillMaxSize())
            if (state.drawingBusy) {
                Column(Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                    Text("Working out which edges are visible\u2026", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (d != null && d.sheets.size > 1) PagerBar(d.labels.getOrElse(shown) { "Sheet ${shown + 1}" }, "${shown + 1} of ${d.sheets.size}  \u00b7  pinch to zoom, double-tap to fit",
            shown > 0, shown < d.sheets.size - 1, { sheetIndex = shown - 1 }, { sheetIndex = shown + 1 },
            jump = d.sheets.indices.map { d.labels.getOrElse(it) { "Sheet ${it + 1}" } }, onJump = { sheetIndex = it })
        else Text("Pinch to zoom, drag to move, double-tap to fit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Wraps rather than squeezing labels letter by letter on a narrow phone.
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val ready = d != null && !state.drawingBusy
            Button(onClick = { if (d != null) sharePdf(context, open, d) }, enabled = ready) { Text("Share PDF") }
            Box {
                var more by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { more = true }, enabled = ready) { Text("More\u2026") }
                androidx.compose.material3.DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Print") }, onClick = { more = false; if (d != null) printPdf(context, open, d.sheets, "drawing") })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("This sheet as an image") }, onClick = { more = false; if (d != null) shareImage(context, open, d, shown) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("DXF for CAD") }, onClick = { more = false; if (d != null) shareDxf(context, open, d) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Full-size templates") }, onClick = {
                        more = false
                        val fs = runCatching { splitziner.drawing.FullSize.build(open.analysis.parts, open.analysis.cutList.rows, open.analysis.format) }.getOrNull()
                        if (fs == null || fs.pages.isEmpty()) Toast.makeText(context, if (fs?.tooBig?.isNotEmpty() == true) "Item ${fs.tooBig.joinToString(", ")} is too big to print full size; mark it out from its drawing" else "No part is shaped enough to need a full-size template", Toast.LENGTH_LONG).show()
                        else sharePdf(context, open, fs.pages.map { io.github.emotionalleaves.splitziner.render.PreparedSheet.of(it) }, "full_size_templates")
                    })
                }
            }
        }
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
                for ((label, v) in listOf("Choose for me" to "auto", "Choose, but printable at home (A or B)" to "AB", "A (8.5 \u00d7 11 in)" to "A", "B (11 \u00d7 17 in)" to "B", "C (17 \u00d7 22 in)" to "C", "D (22 \u00d7 34 in)" to "D", "E (34 \u00d7 44 in)" to "E")) {
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


/**
 * Sends the pages to a printer through Android's own print screen, which offers the phone's printers, Wi-Fi
 * printers nearby and "Save as PDF". Each sheet prints at its own paper size where the printer allows.
 */
private fun printPdf(context: Context, open: OpenProject, sheets: List<io.github.emotionalleaves.splitziner.render.PreparedSheet>, kind: String) {
    try {
        val base = open.meta.drawingNo.ifBlank { open.meta.name }.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "splitziner" }
        val file = PdfExporter.write(context, sheets, "${base}_$kind.pdf")
        val pm = context.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        val name = "${open.meta.title.ifBlank { open.meta.name }} ${kind.replace('_', ' ')}"
        pm.print(name, object : android.print.PrintDocumentAdapter() {
            override fun onLayout(old: android.print.PrintAttributes?, new: android.print.PrintAttributes?, cancel: android.os.CancellationSignal?, cb: LayoutResultCallback, extras: android.os.Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(android.print.PrintDocumentInfo.Builder("${base}_$kind.pdf").setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(sheets.size).build(), true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>?, dest: android.os.ParcelFileDescriptor, cancel: android.os.CancellationSignal?, cb: WriteResultCallback) {
                try {
                    file.inputStream().use { input -> java.io.FileOutputStream(dest.fileDescriptor).use { input.copyTo(it) } }
                    cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }, null)
    } catch (e: Throwable) {
        Diagnostics.logError("print", e)
        Toast.makeText(context, "Could not print", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun GuideTab(open: OpenProject, state: UiState, vm: AppViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var showBuild by remember { mutableStateOf(false) }
    if (showJoin) {
        val current = vm.methodFor(open.meta)
        val owned = vm.toolsFor(open.meta)
        AlertDialog(
            onDismissRequest = { showJoin = false },
            confirmButton = { TextButton(onClick = { showJoin = false }) { Text("Close") } },
            title = { Text("How parts are joined") },
            text = {
                Column {
                    Text("The guide says, joint by joint, what to fix with and how many, and lists the hardware to buy. Fastener sizes and spacing are workshop rules of thumb.", style = MaterialTheme.typography.bodySmall)
                    for (m in splitziner.joinery.JoinMethod.entries) {
                        val missing = if (owned == null) emptyList() else m.needs.filter { it !in owned }
                        Row(Modifier.fillMaxWidth().clickable { showJoin = false; vm.setJoinMethod(m) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = m == current, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(m.label)
                                Text(if (missing.isEmpty()) "Needs: ${m.needs.joinToString(", ") { it.label.lowercase() }}" else "You have not ticked: ${missing.joinToString(", ") { it.label.lowercase() }}",
                                    style = MaterialTheme.typography.bodySmall, color = if (missing.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
        )
    }
    LaunchedEffect(open.analysis, state.guide == null) { vm.ensureGuide() }
    val g = state.guide
    val shown = if (g != null) page.coerceIn(0, maxOf(g.pages.size - 1, 0)) else 0
    Column(modifier.fillMaxWidth()) {
        Text("A draft order worked out from the shapes: read it through before you build.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium).padding(6.dp)) {
            if (g != null && g.pages.isNotEmpty()) SheetPreview(g.pages[shown], Modifier.fillMaxSize())
            if (state.guideBusy) {
                Column(Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                    Text("Working out the build order and drawing each step\u2026", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (g != null) PagerBar(g.labels.getOrNull(shown) ?: "Page ${shown + 1}", "Page ${shown + 1} of ${g.pages.size}  \u00b7  ${g.stepCount} steps", shown > 0, shown < g.pages.size - 1, { page = shown - 1 }, { page = shown + 1 },
            jump = g.pages.indices.map { "${it + 1}.  " + (g.labels.getOrNull(it) ?: "Page ${it + 1}") }, onJump = { page = it })
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { showBuild = true }, enabled = g != null && !state.guideBusy && g.work.isNotEmpty()) { Text("Build mode") }
            OutlinedButton(onClick = { if (g != null) sharePdf(context, open, g.pages, "guide") }, enabled = g != null && !state.guideBusy) { Text("Share PDF") }
            // The less used actions in one menu, so the page itself keeps the room.
            Box {
                var more by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { more = true }, enabled = !state.guideBusy) { Text("More\u2026") }
                androidx.compose.material3.DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Print") }, enabled = g != null, onClick = { more = false; if (g != null) printPdf(context, open, g.pages, "guide") })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Joining: screws, pocket screws, dowels\u2026") }, onClick = { more = false; showJoin = true })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Edit steps") }, enabled = g != null, onClick = { more = false; showEditor = true })
                }
            }
        }
    }
    if (showEditor && g != null) StepEditor(open, g.steps, vm, onDismiss = { showEditor = false })
    if (showBuild && g != null) BuildModeDialog(open, g.work, onDismiss = { showBuild = false })
}

/**
 * The guide a step at a screen, for the workshop: the picture large, what the step uses, what to do, then
 * "Done" moves on. Steps marked done are kept with the project, so the build can be picked up another day.
 */
@Composable
private fun BuildModeDialog(open: OpenProject, work: List<WorkStep>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("build", Context.MODE_PRIVATE) }
    val key = "done_${open.meta.id}"
    var done by remember { mutableStateOf(prefs.getStringSet(key, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()) }
    // Opens at the first step not yet done.
    var at by remember { mutableIntStateOf((work.indices.firstOrNull { it !in done } ?: 0)) }
    fun save() { prefs.edit().putStringSet(key, done.map { it.toString() }.toSet()).apply() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val st = work[at.coerceIn(0, work.size - 1)]
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Column(Modifier.weight(1f)) {
                        Text("Step ${at + 1} of ${work.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        // A step with no title of its own is named by its first instruction ("Fit item 1 (side) from the left").
                        Text(st.title.substringAfter(": ", st.title).replaceFirstChar { it.uppercase() }.let { if (it.startsWith("Step ")) (st.text.firstOrNull()?.trimEnd('.') ?: "Fit the parts") else it },
                            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (at in done) Text("Done", style = MaterialTheme.typography.labelLarge, color = successColors().second,
                        modifier = Modifier.background(successColors().first, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                LinearProgressIndicator(progress = { done.size.toFloat() / work.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Box(Modifier.fillMaxWidth().weight(0.9f).background(androidx.compose.ui.graphics.Color.White, MaterialTheme.shapes.medium).padding(4.dp)) {
                    io.github.emotionalleaves.splitziner.render.SheetPreview(st.picture, Modifier.fillMaxSize())
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                    if (st.parts.isNotEmpty()) {
                        item { Text("You need", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                        // "Item 1 (side) PANEL 18 X 600 X 1500" in ordinary type, as on the Parts tab.
                        items(st.parts) { Text("\u2022 " + it.replace(Regex("""^(.*?\)) ([A-Z]+ [0-9].*)$""")) { m -> m.groupValues[1] + " " + m.groupValues[2].lowercase() }, style = MaterialTheme.typography.bodyMedium) }
                    }
                    item { Text("What to do", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                    items(st.text.size) { i ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                            Text(st.text[i], style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    st.check?.let { c -> item {
                        Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Text("Check: $c", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
                        }
                    } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { at -= 1 }, enabled = at > 0) { Text("Back") }
                    if (at in done) OutlinedButton(onClick = { done = done - at; save() }) { Text("Not done") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        done = done + at; save()
                        if (at < work.size - 1) at += 1 else onDismiss()
                    }) { Text(if (at < work.size - 1) "Done, next" else "Finish") }
                }
            }
        }
    }
}

/** Lets the user fix the drafted build order and rewrite each step's wording. */
@Composable
private fun StepEditor(open: OpenProject, steps: List<splitziner.explode.BuildStep>, vm: AppViewModel, onDismiss: () -> Unit) {
    var order by remember { mutableStateOf(steps.map { it.partIds }) }
    var movePart by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var wording by remember { mutableStateOf<List<String>?>(null) }
    val parts = open.analysis.parts.associateBy { it.id }
    fun label(id: String) = parts[id]?.let { p -> if (p.name != p.id) "${p.name} (item ${p.item ?: "-"})" else "${p.id} (item ${p.item ?: "-"})" } ?: id
    fun clean(o: List<List<String>>) = o.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { vm.setStepOrder(order); onDismiss() }) { Text("Apply") } },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.setStepOrder(null); onDismiss() }) { Text("Automatic") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        title = { Text("Build steps") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Tap a part to move it to another step. Use the arrows to move a whole step. The first step is the base everything else is built on.", style = MaterialTheme.typography.bodySmall)
                order.forEachIndexed { i, ids ->
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Step ${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { order = order.toMutableList().also { val t = it[i]; it[i] = it[i - 1]; it[i - 1] = t } }, enabled = i > 0) { Text("\u2191") }
                                TextButton(onClick = { order = order.toMutableList().also { val t = it[i]; it[i] = it[i + 1]; it[i + 1] = t } }, enabled = i < order.size - 1) { Text("\u2193") }
                                TextButton(onClick = { wording = ids }) { Text("Wording") }
                            }
                            for (id in ids) Text(label(id), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clickable { movePart = i to id }.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        },
    )
    movePart?.let { (i, id) ->
        fun apply(target: Int?, own: Boolean = false) {
            val o = order.map { it.toMutableList() }.toMutableList()
            o[i].remove(id)
            when {
                own -> o.add(i + 1, mutableListOf(id))
                target != null && target >= o.size -> o.add(mutableListOf(id))
                target != null && target >= 0 -> o[target].add(id)
                else -> o[i].add(id)
            }
            order = clean(o); movePart = null
        }
        AlertDialog(
            onDismissRequest = { movePart = null },
            confirmButton = { TextButton(onClick = { movePart = null }) { Text("Cancel") } },
            title = { Text(label(id)) },
            text = {
                Column {
                    Text(parts[id]?.desc ?: "", style = MaterialTheme.typography.bodySmall)
                    if (i > 0) TextButton(onClick = { apply(i - 1) }) { Text("Move to step $i (earlier)") }
                    TextButton(onClick = { apply(i + 1) }) { Text("Move to step ${i + 2} (later)") }
                    if (order[i].size > 1) TextButton(onClick = { apply(null, own = true) }) { Text("Give it a step of its own, next") }
                }
            },
        )
    }
    wording?.let { ids ->
        val key = splitziner.guide.GuideSteps.stepKey(ids)
        val cur = open.meta.stepText[key]
        var title by remember(key) { mutableStateOf(cur?.title ?: "") }
        var lines by remember(key) { mutableStateOf(cur?.lines?.joinToString("\n") ?: "") }
        var check by remember(key) { mutableStateOf(cur?.check ?: "") }
        AlertDialog(
            onDismissRequest = { wording = null },
            confirmButton = {
                TextButton(onClick = {
                    val l = lines.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val t = splitziner.guide.StepText(title.trim().ifBlank { null }, l.ifEmpty { null }, check.trim().ifBlank { null })
                    vm.setStepText(key, if (t.title == null && t.lines == null && t.check == null) null else t)
                    wording = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { wording = null }) { Text("Cancel") } },
            title = { Text("Step wording") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Leave a box empty to keep the app's own wording. How parts are fastened is yours to say: the app never guesses glue, screws or clamping.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(title, { title = it }, label = { Text("Step name (e.g. Sides)") }, singleLine = true)
                    OutlinedTextField(lines, { lines = it }, label = { Text("Instructions, one per line") }, minLines = 3)
                    OutlinedTextField(check, { check = it }, label = { Text("Check (e.g. Diagonals are equal)") }, singleLine = true)
                }
            },
        )
    }
}


private val HELP = listOf(
    "Sizes and counts" to "Part sizes, the cut list and the drawing's dimensions are measured straight from your model. For flat-sided parts like boards and panels they are exact.",
    "Which way is up" to "STL files do not record which way is up. The app takes the file's Z axis as up. If your model arrives lying on its back or upside down (common from programs that save with Y up), open the project menu (\u22ee, top right), then Which way is up, and pick the axis that should point up.",
    "Changes" to "On the 3D tab, More > Changes lists every change made to the model, oldest first: yours and the ones Fix what can be fixed made. Take back any one of them and the rest are replayed without it; Undo takes back the last.",
    "Overall size" to "On the 3D tab, More > Overall size makes the whole piece wider, deeper or taller (or smaller). The app finds a place across the middle where only plain lengths of board are cut across; those parts get longer or shorter and everything beyond moves with them, so board thicknesses and joints stay as they were. Shrinking is limited to the clear stretch it found.",
    "Grooves (housings)" to "Where a smaller part runs part way into a bigger one, no deeper than half its thickness, the app can keep it as drawn: a groove is cut in the bigger part to take it. Fix what can be fixed does this by itself; the Check tab offers it as \"Keep it: groove item ...\". The grooved part then gets a detail sheet giving the groove's size, and the guide says to glue the part into it.",
    "Full-size templates" to "On the Drawing tab, More > Full-size templates makes a PDF of every strongly shaped flat part (a round top, a bracket, a curved leg) at full size, split across letter or A4 pages. Print at 100% (actual size), check the 1 in (25 mm) line on each page with a rule, trim at the dashed boxes, line up the crosses, tape the pages together and trace onto the wood. Rectangles with rounded corners or notches are left to their drawings.",
    "Prices" to "On the Cut list tab, More > Prices lets you type what each kind of sheet and board costs where you buy it. The Cut list tab then shows what the wood comes to, and the shopping list prices each line. Prices are remembered for other projects.",
    "CNC profiles" to "On the Cut list tab, More > CNC profiles shares a DXF with every flat item's true outline at full size in millimetres: outsides on layer CUT_OUTSIDE, openings on CUT_INSIDE, and a label saying how many to cut and how thick. Blocks and rods are left out. Set up tool offsets, tabs and depths in your CAM program.",
    "Weight" to "Once items have materials, the Cut list tab gives a weight, and the drawing's title block gets it when every item has one. It uses typical densities (pine 500 kg/m³, birch plywood 680, MDF 750, oak 750...); real boards vary by 10-20%. Pick a material from the chips when you tap an item, and tick the box to give every other item the same.",
    "Build pack" to "The project menu (\u22ee, top right) has Share build pack: one zip with the drawings and the build guide as PDFs, the cutting plan, the cut list as a spreadsheet file (CSV) and the shopping list. Send it to a print shop, to whoever is helping you build, or to yourself.",
    "Doors, drawers and shelf pins" to "On the 3D tab, More > Add a shelf, divider, door or drawer fills an opening. Doors get concealed hinges (2, 3 or 4 by height) and their drawings show where the cups go; drawers get a box sized for ball-bearing slides; adjustable shelves rest on 4 pins in rows of holes on the 32 mm system. The guide hangs all of them last, once the box is finished, and their hardware is on the shopping list.",
    "Backing up" to "Open the project menu (\u22ee, top right), then Share project. It makes one file with the model and everything you set and changed. Open that file with Import (or Open with) on any phone with the app to get the project back.",
    "Units" to "STL files don't record units. The app guesses from the model's size and how round its numbers are, and says when it guessed. If the overall size looks wrong, open the project menu (\u22ee, top right), then Units.",
    "Round things" to "STL stores curves as flat facets, so holes and rods measure slightly off. Take critical diameters from your original design.",
    "Holes" to "Each part's own drawing shows where its round holes are and how big: a size note such as 3X 8 DIA THRU, and each centre measured from the datum corner. The app finds straight round holes with a flat rim. It does not know about threads, countersinks or what a hole is for, and holes drilled at an angle are drawn but not dimensioned.",
    "Dimensions" to "Everything is measured from the lower-left-front corner. Which faces really matter for fit is a design decision the app can't make.",
    "Tolerances and materials" to "An STL contains neither. Tolerances say NOT SPECIFIED until you choose them under Title block. Materials are blank until you set them in the cut list.",
    "Build order" to "The guide's order is a draft. The app checks that each part can slide into place past the ones before it, and puts parts that stand on the floor first. It knows nothing about glue, clamps, screws or what is easy to hold. Use More > Edit steps on the Guide tab to correct it.",
    "Check" to "The Check tab looks at the model the way someone about to build it would. It flags parts that pass through each other, parts that touch nothing, hollow shapes drawn as one solid, thicknesses shops do not stock, shelves that run a long way between supports, and whether it would tip over. Problems (red) stop the build as drawn; Check items (amber) need your judgement. It works from shapes alone: it knows nothing about wood, glue, screws or loads, and its sag and balance findings are rules of thumb.",
    "Changing parts" to "On the 3D tab, tap a part, then Change. You can set its size, move it by an amount, slide it until it touches the next part, turn it a quarter turn, copy it, square it up, make it from boards, or delete it. Add a board puts a new board above the model to slide into place; More > Add a shelf, divider, door or drawer puts a shelf between the sides at the height you give (fixed, or adjustable on shelf pins), an upright divider between shelves, an inset door in an opening (with its hinges on the hardware list), or a drawer on slides in an opening. New design on the home screen starts a bookcase, cabinet (with doors set in or over the front), desk, nightstand, chest of drawers, table, bench or open box at the sizes you give (in stock thicknesses), or a single board. Undo takes the last change back.",
    "Frames and boxes drawn as one piece" to "3D programs often join a frame, an open box or a channel into one solid. Nobody can cut that from one board. The check finds these and offers Make from N boards, which splits the part into the plain boards it would be built from. Frames with a step in their thickness (a rebate) become two stacked layers, which make the same step. Rounded corners come out square.",
    "Fixing a model" to "On the Check tab, Fix what can be fixed proposes changes and shows them before anything happens: closing small gaps under loose parts, joining overlapping flat pieces that are really one piece of sheet, trimming parts that run a little into a neighbour, and moving odd thicknesses to ones you can buy. It will not cut away more than a quarter of a part or redesign a joint; those are listed for you to decide. Some findings also offer their own fix, such as Make .25 thick or Square these parts up. Your file is never altered: changes are kept as a list, Undo removes them, and Parts > Whole model (STL) shares the changed model in millimetres.",
    "Joining" to "On the Guide tab, More > Joining picks how parts are fixed: screws, pocket screws, glue and dowels, or glue and brads. It starts from what your tools allow. Each step then says which part is fixed to which, with what and how many, and the cover lists the hardware to buy. A screw goes through the thinner part and into the other about 1.5 times that thickness, never far enough to come out the far side, one every 10 in or so. Tops are fixed from underneath with brackets, and parts too thin for a screw are glued in a groove or against a cleat. These are rules of thumb, not a strength calculation.",
    "Tools" to "On the Check tab, Tools lets you tick what you own. The check then says which parts you cannot make, or can only make slowly, and what would do the job. The build guide's tool list follows the same choice. Your tools are remembered for the next project.",
    "Printing" to "Print, under More on the Guide and Drawing tabs and in the cutting plan, opens the phone's own print screen: choose a printer on your Wi-Fi, or Save as PDF. Drawings print each sheet at its own size where the printer allows; letter or A4 paper takes the guide and the cutting plan as they are.",
    "Projects" to "On the first screen, each project's menu (\u22ee) can rename it, make a copy to try a variation without losing the original, or delete it. Your original model file is never changed.",
    "Build mode" to "On the Guide tab, Build mode shows the guide one step to a screen, for the workshop: the picture large, the parts and hardware the step uses, and what to do, numbered. Done, next marks the step done and moves on; the app remembers which steps are done, so you can stop and pick up another day where you left off.",
    "Shopping list" to "On the Cut list tab, Shopping list opens a checklist of everything to buy: the sheets and lengths from the cutting plan, edge banding, and the screws, hinges and slides the guide uses. Tick each line as it goes in the trolley; the ticks stay with the project until you clear them. Share sends the list as text.",
    "Cutting plan" to "On the Cut list tab, Cutting plan lays the panels out on sheets and the boards along bought lengths, shows the layout page by page, and tells you how much to buy. Change the stock sizes under Stock and the plan is redrawn at once; Share PDF sends it, Print prints it (or saves it as a PDF), Share list sends just the shopping list. Tap an item and tick \"Grain runs along its length\" to keep that part from being turned on the sheet (marked G). You set the sheet size, the length boards come in and the width of your saw cut. Every sheet can be cut with straight cuts right across. It is a good layout, not a proven best one, and it does not know about defects or offcuts you already own.",
    "Joined parts" to "If two boards were merged into one solid in your 3D program, there is no seam to split on and they appear as one part.",
    "Not a checked drawing" to "The sheets follow a standard drawing format, but nobody has checked them. They are not proof that parts fit or that the design is strong enough.",
)


private fun baseName(open: OpenProject) = open.meta.drawingNo.ifBlank { open.meta.name }.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "splitziner" }

/** Full-size outlines of every flat item as DXF, for a CNC router or laser. */
private fun shareProfiles(context: Context, open: OpenProject) {
    try {
        val a = open.analysis
        val r = splitziner.export.ProfileDxf.write(a.parts, a.cutList.rows, a.format)
        if (r.profiles == 0) { Toast.makeText(context, "No flat parts to cut", Toast.LENGTH_LONG).show(); return }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val base = open.meta.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "splitziner" }
        val file = File(dir, "${base}_profiles.dxf")
        file.writeText(r.dxf)
        Toast.makeText(context, "${r.profiles} profiles, full size in millimetres" + if (r.leftOut.isEmpty()) "" else "; items ${r.leftOut.joinToString(", ")} are not flat and are left out", Toast.LENGTH_LONG).show()
        shareFile(context, file, "application/dxf", "Share cutting profiles")
    } catch (e: Throwable) {
        Diagnostics.logError("share profiles", e)
        Toast.makeText(context, "Could not make the profiles", Toast.LENGTH_LONG).show()
    }
}

private fun shareFile(context: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title))
}

private fun shareImage(context: Context, open: OpenProject, d: DrawingState, index: Int) {
    try {
        val (file, dpi) = io.github.emotionalleaves.splitziner.render.ImageExporter.writePng(context, d.sheets[index], "${baseName(open)}_${d.sheets[index].name.ifBlank { "sheet${index + 1}" }}.png")
        if (dpi < 200) Toast.makeText(context, "Large sheet: image made at $dpi dots per inch", Toast.LENGTH_LONG).show()
        shareFile(context, file, "image/png", "Share image")
    } catch (e: Throwable) {
        Diagnostics.logError("share image", e)
        Toast.makeText(context, "Could not make the image", Toast.LENGTH_LONG).show()
    }
}

private fun shareDxf(context: Context, open: OpenProject, d: DrawingState) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${baseName(open)}_dxf.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            d.lists.forEachIndexed { i, dl ->
                zip.putNextEntry(java.util.zip.ZipEntry("${baseName(open)}_${dl.name.ifBlank { "sheet${i + 1}" }}.dxf"))
                zip.write(splitziner.export.DxfWriter.write(dl).toByteArray(Charsets.US_ASCII))
                zip.closeEntry()
            }
        }
        Toast.makeText(context, "DXF files record the drawing; they are not cutting profiles", Toast.LENGTH_LONG).show()
        shareFile(context, file, "application/zip", "Share DXF files")
    } catch (e: Throwable) {
        Diagnostics.logError("share dxf", e)
        Toast.makeText(context, "Could not make the DXF files", Toast.LENGTH_LONG).show()
    }
}

private fun shareModel(context: Context, open: OpenProject) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${open.meta.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "splitziner" }}${if (open.meta.edits.isEmpty()) "" else "_fixed"}.stl")
        file.outputStream().use { splitziner.io.StlWriter.writeParts(open.analysis.parts.filter { !it.excluded }.map { it.mesh }, it) }
        Toast.makeText(context, "This STL is in millimetres", Toast.LENGTH_LONG).show()
        shareFile(context, file, "model/stl", "Share model")
    } catch (e: Throwable) {
        Diagnostics.logError("share model", e)
        Toast.makeText(context, "Could not write the model", Toast.LENGTH_LONG).show()
    }
}

private fun shareParts(context: Context, open: OpenProject) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${open.meta.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "splitziner" }}_parts.zip")
        file.outputStream().use { splitziner.export.PartsZip.write(open.analysis.parts, open.analysis.report, open.analysis.sourceName, it) }
        shareFile(context, file, "application/zip", "Share parts")
    } catch (e: Throwable) {
        Diagnostics.logError("share parts", e)
        Toast.makeText(context, "Could not pack the parts", Toast.LENGTH_LONG).show()
    }
}


/** Asks what stock the plan should cut from, in the project's units, then shares the plan as a PDF. */
/** The stock sizes last used in the cutting plan, or the usual ones for these units. */
private fun savedStock(context: Context, fmt: splitziner.dims.LengthFormat): splitziner.cutplan.StockSettings {
    val prefs = context.getSharedPreferences("cutplan", Context.MODE_PRIVATE)
    val def = splitziner.cutplan.StockSettings.defaultFor(fmt)
    val unit = if (fmt.isInch) 25.4 else 1.0; val tag = if (fmt.isInch) "in" else "mm"
    fun get(key: String, d: Double) = prefs.getString("${key}_$tag", null)?.let { num(it) }?.let { it * unit } ?: d
    val w = get("sheetW", def.sheetW); val l = get("sheetL", def.sheetL)
    return splitziner.cutplan.StockSettings(minOf(w, l), maxOf(w, l), get("boardL", def.boardL), get("kerf", def.kerf), prefs.getBoolean("rotate", true), lumber = fmt.isInch)
}

/** Prices the user has typed, per kind of stock ("sheet 3/4 in thick, 48 x 96"), remembered across projects. */
private fun prices(context: Context) = context.getSharedPreferences("prices", Context.MODE_PRIVATE)
private fun priceOf(context: Context, one: String): Double? = prices(context).getString(one, null)?.let { num(it) }

/** The wood's cost from the prices typed so far, and how many kinds of stock still have no price. */
private fun woodCost(context: Context, items: List<splitziner.cutplan.CutPlanner.Buy>): Pair<Double, Int> {
    var sum = 0.0; var missing = 0
    for (b in items) { val p = priceOf(context, b.one); if (p == null) missing++ else sum += p * b.count }
    return sum to missing
}

private fun money(v: Double) = java.text.NumberFormat.getCurrencyInstance().format(v)

/** Type what each kind of stock costs where you buy it; the cut list and shopping list then add it up. */
@Composable
private fun PricesDialog(open: OpenProject, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items = remember(open.analysis) { runCatching { splitziner.cutplan.CutPlanner.shoppingItems(splitziner.cutplan.CutPlanner.plan(open.analysis.cutList.rows, savedStock(context, open.analysis.format)), open.analysis.format) }.getOrDefault(emptyList()) }
    val vals = remember { androidx.compose.runtime.mutableStateListOf(*items.map { b -> prices(context).getString(b.one, "") ?: "" }.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            val e = prices(context).edit()
            items.forEachIndexed { i, b -> val v = vals[i].trim(); if (v.isEmpty()) e.remove(b.one) else if (num(v) != null) e.putString(b.one, v) }
            e.apply(); onDismiss()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Prices") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { Text("What one of each costs where you buy it. Prices are remembered for your other projects.", style = MaterialTheme.typography.bodySmall) }
                items(items.size) { i ->
                    OutlinedTextField(vals[i], { vals[i] = it }, label = { Text("1 ${items[i].one}  (need ${items[i].count})", maxLines = 2) }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                }
                if (items.isEmpty()) item { Text("Nothing here is cut from sheets or lengths.") }
            }
        },
    )
}

/** Everything to buy, as plain text to take to the store: stock, hardware, edge banding. Worked out off the main thread. */
private fun shareShopping(context: Context, open: OpenProject, vm: AppViewModel, scope: kotlinx.coroutines.CoroutineScope) {
    val stock = savedStock(context, open.analysis.format)
    scope.launch {
        val text = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { shoppingText(context, open, vm, stock) } }
            .onFailure { Diagnostics.logError("shopping list", it) }.getOrNull()
        if (text == null) { Toast.makeText(context, "Could not make the shopping list", Toast.LENGTH_LONG).show(); return@launch }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Shopping list").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share shopping list"))
    }
}

private fun shoppingText(context: Context, open: OpenProject, vm: AppViewModel, stock: splitziner.cutplan.StockSettings): String {
    run {
        val a = open.analysis; val fmt = a.format
        val plan = splitziner.cutplan.CutPlanner.plan(a.cutList.rows, stock)
        val sb = StringBuilder("Shopping list: ${open.meta.title.ifBlank { open.meta.name }}\n\nWood\n")
        for (b in splitziner.cutplan.CutPlanner.shoppingItems(plan, fmt)) {
            val p = priceOf(context, b.one)
            sb.append("• ").append(b.line).append(if (p != null) "  — ${money(p * b.count)}" else "").append('\n')
        }
        for (b in plan.leftOut) sb.append("• ").append(b).append('\n')
        runCatching { splitziner.cutplan.EdgeBanding.line(a.parts, fmt) }.getOrNull()?.let { sb.append("• ").append(it).append('\n') }
        val hardware = vm.hardwareFor(open)
        if (hardware.isNotEmpty()) { sb.append("\nHardware (${vm.methodFor(open.meta).label.lowercase()})\n"); for (h in hardware) sb.append("• ").append(h).append('\n') }
        val (cost, missing) = woodCost(context, splitziner.cutplan.CutPlanner.shoppingItems(plan, fmt))
        if (cost > 0) sb.append("\nWood comes to about ${money(cost)}" + (if (missing > 0) ", not counting $missing item${if (missing == 1) "" else "s"} with no price yet" else "") + ".\n")
        sb.append("\nSizes and counts are worked out from the model with workshop rules of thumb; check them against your plans before buying.")
        return sb.toString()
    }
}

private class ShopLine(val text: String, val cost: String?)
private class ShopLists(val wood: List<ShopLine>, val hardware: List<ShopLine>, val total: String?)

/**
 * The shopping list as a checklist to take to the store: wood, edge banding and hardware, each ticked off as it
 * goes in the trolley. Ticks are kept with the project; prices typed under Prices are added up.
 */
@Composable
private fun ShoppingDialog(open: OpenProject, vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val fmt = open.analysis.format
    val lists by androidx.compose.runtime.produceState<ShopLists?>(null, open.analysis) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val plan = splitziner.cutplan.CutPlanner.plan(open.analysis.cutList.rows, savedStock(context, fmt))
                val buy = splitziner.cutplan.CutPlanner.shoppingItems(plan, fmt)
                val wood = buy.map { b -> ShopLine(b.line, priceOf(context, b.one)?.let { money(it * b.count) }) } +
                    plan.leftOut.map { ShopLine(it, null) } +
                    listOfNotNull(runCatching { splitziner.cutplan.EdgeBanding.line(open.analysis.parts, fmt) }.getOrNull()).map { ShopLine(it, null) }
                val (cost, missing) = woodCost(context, buy)
                // Advice ("buy about 10% extra") is said under the list, not ticked off like a thing to buy.
                ShopLists(wood, vm.hardwareFor(open).filterNot { it.startsWith("Buy about") || it.startsWith("Joined with") }.map { ShopLine(it, null) },
                    if (cost > 0) "Wood about ${money(cost)}" + (if (missing > 0) ", $missing without a price" else "") else null)
            }.onFailure { Diagnostics.logError("shopping list", it) }.getOrNull()
        }
    }
    val prefs = remember { context.getSharedPreferences("shopping", Context.MODE_PRIVATE) }
    val key = "ticked_${open.meta.id}"
    var ticked by remember { mutableStateOf(prefs.getStringSet(key, emptySet())!!.toSet()) }
    fun toggle(t: String) { ticked = if (t in ticked) ticked - t else ticked + t; prefs.edit().putStringSet(key, ticked).apply() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                val all = lists?.let { it.wood + it.hardware }.orEmpty()
                val done = all.count { it.text in ticked }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Column(Modifier.weight(1f)) {
                        Text("Shopping list", style = MaterialTheme.typography.titleLarge)
                        Text(if (lists == null) "Working it out\u2026" else "$done of ${all.size} ticked" + (lists?.total?.let { "  \u00b7  $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (all.isNotEmpty()) LinearProgressIndicator(progress = { done.toFloat() / all.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    val l = lists
                    if (l == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else {
                        for ((head, lines) in listOf("Wood" to l.wood, "Hardware (${vm.methodFor(open.meta).label.lowercase()})" to l.hardware)) {
                            if (lines.isEmpty()) continue
                            item { Text(head, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                            items(lines) { line ->
                                val on = line.text in ticked
                                Row(Modifier.fillMaxWidth().clickable { toggle(line.text) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { toggle(line.text) })
                                    Text(line.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                                        color = if (on) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                        textDecoration = if (on) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                                    line.cost?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp)) }
                                }
                            }
                        }
                        if (l.hardware.any { it.text.contains("screws") || it.text.contains("brads") }) item { Text("Buy about 10% extra screws and brads; a few always go astray.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                        item { Text("Sizes and counts are worked out from the model with workshop rules of thumb; check them against your plans before buying. Stock sizes come from the cutting plan.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { shareShopping(context, open, vm, scope) }, enabled = lists != null) { Text("Share") }
                    if (ticked.isNotEmpty()) OutlinedButton(onClick = { ticked = emptySet(); prefs.edit().remove(key).apply() }) { Text("Clear ticks") }
                }
            }
        }
    }
}

@Composable
private fun CutPlanDialog(open: OpenProject, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fmt = open.analysis.format
    val unit = if (fmt.isInch) 25.4 else 1.0
    val prefs = remember { context.getSharedPreferences("cutplan", Context.MODE_PRIVATE) }
    val def = splitziner.cutplan.StockSettings.defaultFor(fmt)
    val tag = if (fmt.isInch) "in" else "mm"
    fun show(v: Double) = String.format(java.util.Locale.ROOT, "%.3f", v / unit).trimEnd('0').trimEnd('.')
    // The last sizes used are remembered, separately for inch and millimetre projects.
    var sheetW by remember { mutableStateOf(prefs.getString("sheetW_$tag", null) ?: show(def.sheetW)) }
    var sheetL by remember { mutableStateOf(prefs.getString("sheetL_$tag", null) ?: show(def.sheetL)) }
    var boardL by remember { mutableStateOf(prefs.getString("boardL_$tag", null) ?: show(def.boardL)) }
    var kerf by remember { mutableStateOf(prefs.getString("kerf_$tag", null) ?: show(def.kerf)) }
    var rotate by remember { mutableStateOf(prefs.getBoolean("rotate", true)) }
    var editing by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    val w = num(sheetW); val l = num(sheetL); val b = num(boardL); val k = num(kerf)
    val valid = w != null && l != null && b != null && k != null && w > 0 && l > 0 && b > 0 && k >= 0
    // The sheet's longer side is its length, whichever box it was typed in.
    val stock = if (valid) splitziner.cutplan.StockSettings(minOf(w!!, l!!) * unit, maxOf(w, l) * unit, b!! * unit, k!! * unit, rotate, lumber = fmt.isInch) else null
    class Made(val pages: List<io.github.emotionalleaves.splitziner.render.PreparedSheet>, val summary: String, val buy: List<String>)
    // Worked out again as the sizes change, away from the screen's thread; the plan is shown, not just shared.
    val made by androidx.compose.runtime.produceState<Made?>(null, stock, open.analysis) {
        val st = stock ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val rows = open.analysis.cutList.rows
                val plan = splitziner.cutplan.CutPlanner.plan(rows, st)
                val dls = splitziner.cutplan.CutPlanLayout.pages(plan, rows, open.meta.title.ifBlank { open.meta.name }, fmt, extraBuy = listOfNotNull(runCatching { splitziner.cutplan.EdgeBanding.line(open.analysis.parts, fmt) }.getOrNull()))
                val used = plan.sheets.map { it.used }.let { if (it.isEmpty()) null else it.average() }
                val summary = listOfNotNull(
                    plan.sheets.size.takeIf { it > 0 }?.let { "$it sheet${if (it == 1) "" else "s"}" },
                    plan.boards.size.takeIf { it > 0 }?.let { "$it length${if (it == 1) "" else "s"}" },
                    used?.let { "${Math.round(it * 100)}% of sheet used" },
                    plan.leftOut.size.takeIf { it > 0 }?.let { "$it part${if (it == 1) "" else "s"} too big for the stock" }).joinToString("  \u00b7  ")
                Made(dls.map { io.github.emotionalleaves.splitziner.render.PreparedSheet.of(it) }, summary, splitziner.cutplan.CutPlanner.shoppingList(plan, fmt))
            }.onFailure { Diagnostics.logError("cutting plan", it) }.getOrNull()
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Column(Modifier.weight(1f)) {
                        Text("Cutting plan", style = MaterialTheme.typography.titleLarge)
                        Text(made?.summary ?: if (valid) "Laying out\u2026" else "Check the stock sizes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                // What it is cut from, in one line; the boxes open only when asked for.
                androidx.compose.material3.OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { editing = !editing }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Stock", style = MaterialTheme.typography.labelLarge)
                                Text("Sheets ${sheetW.trim()} x ${sheetL.trim()}, boards ${boardL.trim()} long, saw cut ${kerf.trim()} ($tag)" + if (!rotate) ", grain kept" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Change") }
                        }
                        if (editing) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(sheetW, { sheetW = it }, label = { Text("Sheet width") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(sheetL, { sheetL = it }, label = { Text("Sheet length") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(boardL, { boardL = it }, label = { Text("Board length") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(kerf, { kerf = it }, label = { Text("Saw cut") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth().clickable { rotate = !rotate }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = rotate, onCheckedChange = { rotate = it })
                                Text("Panels may be turned to fit (untick to keep grain along the sheet)", style = MaterialTheme.typography.bodySmall)
                            }
                            if (!valid) Text("Enter each size as a number, for example ${show(def.sheetW)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { sheetW = show(def.sheetW); sheetL = show(def.sheetL); boardL = show(def.boardL); kerf = show(def.kerf); rotate = true }) { Text("Use common sizes") }
                        }
                    }
                }
                val pages = made?.pages.orEmpty()
                val shown = page.coerceIn(0, maxOf(0, pages.size - 1))
                Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium).padding(6.dp)) {
                    if (pages.isNotEmpty()) io.github.emotionalleaves.splitziner.render.SheetPreview(pages[shown], Modifier.fillMaxSize())
                    else if (valid) LinearProgressIndicator(Modifier.align(Alignment.Center).fillMaxWidth(0.6f))
                }
                if (pages.size > 1) PagerBar(if (shown == 0) "Summary and what to buy" else "Page ${shown + 1}", "${shown + 1} of ${pages.size}  \u00b7  pinch to zoom, double-tap to fit",
                    shown > 0, shown < pages.size - 1, { page = shown - 1 }, { page = shown + 1 })
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = pages.isNotEmpty(), onClick = {
                        prefs.edit().putString("sheetW_$tag", sheetW.trim()).putString("sheetL_$tag", sheetL.trim()).putString("boardL_$tag", boardL.trim()).putString("kerf_$tag", kerf.trim()).putBoolean("rotate", rotate).apply()
                        sharePdf(context, open, pages, "cutting_plan")
                    }) { Text("Share PDF") }
                    OutlinedButton(enabled = pages.isNotEmpty(), onClick = { printPdf(context, open, pages, "cutting_plan") }) { Text("Print") }
                    OutlinedButton(enabled = made != null, onClick = {
                        val text = (listOf("${open.meta.title.ifBlank { open.meta.name }}: to buy") + made!!.buy.map { "\u2022 $it" }).joinToString("\n")
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share shopping list"))
                    }) { Text("Share list") }
                }
            }
        }
    }
}


@Composable
private fun CheckTab(open: OpenProject, state: UiState, vm: AppViewModel, modifier: Modifier, onShow: (String) -> Unit) {
    var showTools by remember { mutableStateOf(false) }
    var showUndo by remember { mutableStateOf(false) }
    LaunchedEffect(open.analysis, state.check == null) { vm.ensureCheck() }
    state.fixPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { vm.dismissFixes() },
            confirmButton = { if (plan.edits.isNotEmpty()) TextButton(onClick = { vm.applyFixes() }) { Text("Apply ${plan.edits.size} change${if (plan.edits.size == 1) "" else "s"}") } else TextButton(onClick = { vm.dismissFixes() }) { Text("OK") } },
            dismissButton = { if (plan.edits.isNotEmpty()) TextButton(onClick = { vm.dismissFixes() }) { Text("Cancel") } },
            title = { Text(if (plan.edits.isEmpty()) "Nothing the app can fix by itself" else "Proposed fixes") },
            text = {
                LazyColumn {
                    if (plan.done.isNotEmpty()) item { Text("The app will:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall) }
                    // The fixes are worded as done ("Made item 13 from 4 boards"); before they are applied they read as to do.
                    items(plan.done) { Text("• ${toDo(it)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                    if (plan.left.isNotEmpty()) item { Text("Left for you to decide:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(plan.left) { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                    item { Text("Your original file is not altered. Undo brings it back.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
        )
    }
    if (showUndo) AlertDialog(
        onDismissRequest = { showUndo = false },
        confirmButton = { TextButton(onClick = { showUndo = false; vm.undoLastEdit() }) { Text("Undo last") } },
        dismissButton = { TextButton(onClick = { showUndo = false; vm.undoAllEdits() }) { Text("Undo all") } },
        title = { Text("Undo changes") },
        text = { Text("${open.meta.edits.size} change${if (open.meta.edits.size == 1) " has" else "s have"} been made to this model in the app. The original file is untouched.") },
    )
    val owned = vm.toolsFor(open.meta)
    if (showTools) ToolsDialog(owned ?: emptySet(), onDismiss = { showTools = false }) { showTools = false; vm.setTools(it) }
    val report = state.check
    Column(modifier.fillMaxWidth()) {
        // The verdict first, coloured by how serious it is.
        val (bg, fg) = when {
            report == null -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
            report.problems > 0 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            report.checks > 0 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            else -> successColors()
        }
        Card(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (report == null) Text("Checking the design…", style = MaterialTheme.typography.titleMedium)
                    else Text(
                        when {
                            report.problems > 0 -> "${report.problems} problem${if (report.problems == 1) "" else "s"}, ${report.checks} to check"
                            report.checks > 0 -> "No problems found, ${report.checks} to check"
                            else -> "No problems found"
                        }, style = MaterialTheme.typography.titleMedium)
                    Text(if (owned == null) "Tools: not chosen yet" else if (owned.isEmpty()) "Tools: none ticked" else "Tools: " + owned.joinToString(", ") { it.label.lowercase() },
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { showTools = true }) { Text("Tools") }
            }
        }
        if (report == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        val changes = open.meta.edits.size
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.planFixes() }, enabled = !state.fixBusy && state.busyLabel == null, modifier = Modifier.weight(1f)) { Text(if (state.fixBusy) "Working it out…" else "Fix what can be fixed") }
            if (changes > 0) OutlinedButton(onClick = { showUndo = true }) { Text("Undo ($changes)") }
        }
        for (n in open.analysis.editNotes) Text("Note: $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (report.problems == 0) Text("Nothing found that stops it being built as drawn. Next: read the Guide through, then share the Shopping list from the Cut list tab and take it to the store." +
            if (report.checks > 0) " The items below are worth a look first." else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        // Filters by how serious, so a long list can be worked through one kind at a time.
        var only by remember { mutableStateOf<splitziner.check.Severity?>(null) }
        val counts = report.findings.groupingBy { it.severity }.eachCount()
        if (counts.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.FilterChip(selected = only == null, onClick = { only = null }, label = { Text("All ${report.findings.size}") })
            for ((sev, word) in listOf(splitziner.check.Severity.PROBLEM to "Problems", splitziner.check.Severity.CHECK to "To check", splitziner.check.Severity.NOTE to "Notes")) {
                val n = counts[sev] ?: 0
                if (n > 0) androidx.compose.material3.FilterChip(selected = only == sev, onClick = { only = if (only == sev) null else sev }, label = { Text("$word $n") })
            }
        }
        val shownFindings = report.findings.filter { only == null || it.severity == only }
        LazyColumn(Modifier.weight(1f)) {
            items(shownFindings) { f ->
                val (word, color) = when (f.severity) {
                    splitziner.check.Severity.PROBLEM -> "Problem" to MaterialTheme.colorScheme.error
                    splitziner.check.Severity.CHECK -> "Check" to MaterialTheme.colorScheme.tertiary
                    else -> "Note" to MaterialTheme.colorScheme.outline
                }
                // A coloured edge says how serious each one is at a glance.
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(color))
                    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
                        Text(word.uppercase(), color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(f.title, style = MaterialTheme.typography.titleSmall)
                        // Long explanations open on request, so the list can be scanned.
                        var whole by remember(f) { mutableStateOf(false) }
                        val long = f.detail.length > 180
                        Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (long && !whole) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (long) TextButton(onClick = { whole = !whole }) { Text(if (whole) "Less" else "More") }
                            if (f.partIds.isNotEmpty()) TextButton(onClick = { onShow(f.partIds[0]) }) { Text("Show in 3D") }
                            for (fix in f.fixes) TextButton(onClick = {
                                vm.addEdits(when (fix.kind) {
                                    splitziner.check.FixKind.SET_THICKNESS -> listOf(splitziner.edit.Edit.Thickness(fix.partIds, fix.value))
                                    splitziner.check.FixKind.SQUARE_UP -> listOf(splitziner.edit.Edit.SquareUp(fix.partIds))
                                    splitziner.check.FixKind.SPLIT_BOARDS -> listOf(splitziner.edit.Edit.SplitBoards(fix.partIds))
                                    splitziner.check.FixKind.CUT_BACK -> listOf(splitziner.edit.Edit.CutBack(fix.partIds[0], fix.partIds[1]))
                                    // Pairs of (housed part, grooved part), one per place they overlap.
                                    splitziner.check.FixKind.HOUSE -> fix.partIds.chunked(2).filter { it.size == 2 }.map { splitziner.edit.Edit.House(it[0], it[1]) }
                                })
                            }) { Text(fix.label) }
                        }
                    }
                    }
                }
            }
            if (report.toolsNeeded.isNotEmpty()) item {
                Text(if (owned == null) "Work this build involves" else "Your tools on this build", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                for (t in report.toolsNeeded) Text("• ${t.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ToolsDialog(owned: Set<splitziner.check.Tool>, onDismiss: () -> Unit, onApply: (Set<splitziner.check.Tool>) -> Unit) {
    var picked by remember { mutableStateOf(owned) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(picked) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Tools you have") },
        text = {
            LazyColumn {
                item { Text("Tick what you can use for this project. The check and the build guide follow it.", style = MaterialTheme.typography.bodySmall) }
                items(splitziner.check.Tool.entries.toList()) { t ->
                    Row(Modifier.fillMaxWidth().clickable { picked = if (t in picked) picked - t else picked + t }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = t in picked, onCheckedChange = { on -> picked = if (on) picked + t else picked - t })
                        Text(t.label)
                    }
                }
            }
        },
    )
}


/** A number as typed: "12.5", "12,5", or a fraction such as "30-1/2", "30 1/2" or "3/4". */
private fun num(s: String): Double? {
    val t = s.trim().replace(',', '.')
    t.toDoubleOrNull()?.let { return it }
    val m = Regex("""^(\d+(?:\.\d+)?)?[\s-]*(\d+)/(\d+)$""").find(t) ?: return null
    val whole = m.groupValues[1].toDoubleOrNull() ?: 0.0
    val den = m.groupValues[3].toDouble(); if (den == 0.0) return null
    return whole + m.groupValues[2].toDouble() / den
}

/** A length in the project's units for a text field: no trailing zeros. */
/** A size to start a text box with, in the project's style (fractions where the project uses them). */
private fun sizeText(fmt: splitziner.dims.LengthFormat, mm: Double) = if (fmt.isInch && fmt.inchStyle == "fraction") fmt.format(mm) else field(mm, if (fmt.isInch) 25.4 else 1.0)

private fun field(mm: Double, unit: Double) = String.format(java.util.Locale.ROOT, "%.3f", mm / unit).trimEnd('0').trimEnd('.')

private fun newId(open: OpenProject, prefix: String): String {
    val used = open.analysis.originalParts.map { it.id }.toSet() + open.meta.edits.flatMap { e ->
        when (e) { is splitziner.edit.Edit.AddBox -> listOf(e.id); is splitziner.edit.Edit.Duplicate -> listOf(e.newId); else -> emptyList() }
    }
    var n = 1
    while ("$prefix$n" in used) n++
    return "$prefix$n"
}

/**
 * Changes to one part: its size, where it is, and whole-part actions. Sizes are typed in the project's units.
 * "Slide until it touches" moves the part the shortest way along one direction until it meets another part.
 */
@Composable
private fun ShapeDialog(p: Part, open: OpenProject, onDismiss: () -> Unit, onEdit: (splitziner.edit.Edit) -> Unit) {
    val fmt = open.analysis.format
    val unit = if (fmt.isInch) 25.4 else 1.0
    // Sizes start in the project's own style: 3/4 in a fraction project, .75 otherwise.
    fun start(mm: Double) = if (fmt.isInch && fmt.inchStyle == "fraction") fmt.format(mm) else field(mm, unit)
    var t by remember { mutableStateOf(start(p.t)) }
    var w by remember { mutableStateOf(start(p.w)) }
    var l by remember { mutableStateOf(start(p.l)) }
    var dx by remember { mutableStateOf("0") }; var dy by remember { mutableStateOf("0") }; var dz by remember { mutableStateOf("0") }
    var msg by remember { mutableStateOf<String?>(null) }
    val boards = remember(p) { runCatching { splitziner.edit.Boards.decompose(p.mesh).first }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(if (p.item != null) "Change item ${p.item}" + (if (p.name != p.id) " (${p.name})" else "") else "Change ${p.name}") },
        text = {
            LazyColumn {
                item {
                    Text(p.desc, style = MaterialTheme.typography.bodySmall)
                    Text("Size (${fmt.unitLabel}): thickness, width, length", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(t, { t = it }, label = { Text("T") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(w, { w = it }, label = { Text("W") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(l, { l = it }, label = { Text("L") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = {
                        val a = num(t); val b = num(w); val c = num(l)
                        if (a == null || b == null || c == null || a <= 0 || b <= 0 || c <= 0) msg = "Enter three sizes greater than zero"
                        else onEdit(splitziner.edit.Edit.Resize(p.id, a * unit, b * unit, c * unit))
                    }) { Text("Set size (about its centre)") }
                    Text("Move by (${fmt.unitLabel}): right, back, up", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(dx, { dx = it }, label = { Text("X") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(dy, { dy = it }, label = { Text("Y") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(dz, { dz = it }, label = { Text("Z") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    fun d(): splitziner.math.Vec3? { val a = num(dx); val b = num(dy); val c = num(dz); return if (a == null || b == null || c == null) null else splitziner.math.Vec3(a * unit, b * unit, c * unit) }
                    Row {
                        TextButton(onClick = { val v = d(); if (v == null) msg = "Enter three numbers (0 for no move)" else onEdit(splitziner.edit.Edit.Move(listOf(p.id), v)) }) { Text("Move") }
                        TextButton(onClick = {
                            val v = d()
                            if (v == null) msg = "Enter where the copy goes, as a move"
                            else onEdit(splitziner.edit.Edit.Duplicate(p.id, newId(open, "C"), if (v.length < 1e-9) splitziner.math.Vec3(0.0, 0.0, p.mesh.extents.z + 10.0) else v))
                        }) { Text("Copy, moved") }
                    }
                    Text("Slide until it touches", style = MaterialTheme.typography.labelMedium)
                    val others = open.analysis.parts.filter { it.id != p.id && !it.excluded }
                    androidx.compose.foundation.layout.FlowRow {
                        for ((label, dir) in listOf("Down" to splitziner.math.Vec3(0.0, 0.0, -1.0), "Up" to splitziner.math.Vec3(0.0, 0.0, 1.0), "Left" to splitziner.math.Vec3(-1.0, 0.0, 0.0),
                            "Right" to splitziner.math.Vec3(1.0, 0.0, 0.0), "Back" to splitziner.math.Vec3(0.0, 1.0, 0.0), "Front" to splitziner.math.Vec3(0.0, -1.0, 0.0))) {
                            TextButton(onClick = {
                                val gap = splitziner.check.DesignCheck.gap(listOf(p), others, dir)
                                if (gap == null || gap > 5000) msg = "Nothing is in that direction" else if (gap < 0.01) msg = "It already touches that way" else onEdit(splitziner.edit.Edit.Move(listOf(p.id), dir * gap))
                            }) { Text(label) }
                        }
                    }
                    Text("Turn a quarter turn", style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow {
                        TextButton(onClick = { onEdit(splitziner.edit.Edit.Rotate(listOf(p.id), 2, 1)) }) { Text("Flat, left-right") }
                        TextButton(onClick = { onEdit(splitziner.edit.Edit.Rotate(listOf(p.id), 0, 1)) }) { Text("Tip front-back") }
                        TextButton(onClick = { onEdit(splitziner.edit.Edit.Rotate(listOf(p.id), 1, 1)) }) { Text("Tip sideways") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    androidx.compose.foundation.layout.FlowRow {
                        TextButton(onClick = { onEdit(splitziner.edit.Edit.SquareUp(listOf(p.id))) }) { Text("Square up") }
                        if (boards != null) TextButton(onClick = { onEdit(splitziner.edit.Edit.SplitBoards(listOf(p.id))) }) { Text("Make from ${boards.pieces.size} boards") }
                        TextButton(onClick = { onEdit(splitziner.edit.Edit.Delete(listOf(p.id))) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                    msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("Every change can be undone; your file is never altered.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
    )
}

/** A new rectangular board. It starts on top of the model, in the middle, ready to be slid into place. */
@Composable
private fun AddBoardDialog(open: OpenProject, onDismiss: () -> Unit, onAdd: (splitziner.edit.Edit) -> Unit) {
    val fmt = open.analysis.format
    val unit = if (fmt.isInch) 25.4 else 1.0
    val b = open.analysis.bounds
    var sx by remember { mutableStateOf(sizeText(fmt, if (fmt.isInch) 24 * 25.4 else 600.0)) }
    var sy by remember { mutableStateOf(sizeText(fmt, if (fmt.isInch) 3.5 * 25.4 else 90.0)) }
    var sz by remember { mutableStateOf(sizeText(fmt, if (fmt.isInch) 0.75 * 25.4 else 18.0)) }
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val x = num(sx); val y = num(sy); val z = num(sz)
                if (x == null || y == null || z == null || x <= 0 || y <= 0 || z <= 0) { msg = "Enter three sizes greater than zero"; return@TextButton }
                val size = splitziner.math.Vec3(x * unit, y * unit, z * unit)
                val c = b.center
                val min = splitziner.math.Vec3(c.x - size.x / 2, c.y - size.y / 2, b.max.z + 20.0)
                val id = newId(open, "N")
                onAdd(splitziner.edit.Edit.AddBox(id, name.trim().ifBlank { id }, min, size))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add a board") },
        text = {
            Column {
                Text("Size in ${fmt.unitLabel}: left-right, front-back, up-down. It appears above the model; select it and use Change > Slide to put it in place.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(sx, { sx = it }, label = { Text("X") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(sy, { sy = it }, label = { Text("Y") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(sz, { sz = it }, label = { Text("Z") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}


/**
 * Changes the whole model's width, depth or height. Each changed size is made by stretching the model across a
 * plane through plain lengths of board: parts it crosses get longer or shorter, parts beyond it move.
 */
@Composable
private fun OverallSizeDialog(open: OpenProject, onDismiss: () -> Unit, onApply: (List<splitziner.edit.Edit>) -> Unit) {
    val a = open.analysis
    val fmt = a.format
    val unit = if (fmt.isInch) 25.4 else 1.0
    val e = a.bounds.extents
    val now = doubleArrayOf(e.x, e.y, e.z)
    val vals = remember { androidx.compose.runtime.mutableStateListOf(sizeText(fmt, e.x), sizeText(fmt, e.y), sizeText(fmt, e.z)) }
    var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            val edits = ArrayList<splitziner.edit.Edit>()
            for (k in 0..2) {
                val v = num(vals[k]) ?: run { msg = "Enter each size as a number"; return@TextButton }
                val delta = v * unit - now[k]
                if (kotlin.math.abs(delta) < 0.05) continue
                val plane = splitziner.edit.Editor.stretchPlane(a.parts, k) ?: run { msg = "The ${listOf("width", "depth", "height")[k]} cannot be changed automatically: every position across the middle cuts through a corner of some part."; return@TextButton }
                if (delta < 0 && -delta > plane.second) { msg = "The ${listOf("width", "depth", "height")[k]} can be made at most ${fmt.format(plane.second)} ${fmt.unitLabel} smaller this way; beyond that parts would pass through each other."; return@TextButton }
                edits += splitziner.edit.Edit.Stretch(k, plane.first, delta)
            }
            if (edits.isEmpty()) onDismiss() else onApply(edits)
        }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Overall size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Boards keep their thickness: the parts that run across the middle get longer or shorter and the rest move with them. Undo takes it back.", style = MaterialTheme.typography.bodySmall)
                for ((k, label) in listOf("Width (left to right)", "Depth (front to back)", "Height").withIndex())
                    OutlinedTextField(vals[k], { vals[k] = it; msg = null }, label = { Text("$label, ${fmt.unitLabel}") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Text))
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

/** Adds a shelf at a height, or an upright divider at a position across, between the parts either side. */
@Composable
private fun ShelfDialog(open: OpenProject, onDismiss: () -> Unit, onAdd: (List<splitziner.edit.Edit>) -> Unit) {
    val a = open.analysis; val fmt = a.format
    val unit = if (fmt.isInch) 25.4 else 1.0
    var kind by remember { mutableIntStateOf(0) }   // 0 shelf, 1 divider, 2 door, 3 drawer
    val divider = kind == 1
    var where by remember { mutableStateOf(sizeText(fmt, a.bounds.extents.z / 2)) }
    var t by remember { mutableStateOf(sizeText(fmt, if (fmt.isInch) 19.05 else 18.0)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pins by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            val v = num(where); val th = num(t)
            if (v == null || th == null || th <= 0) { msg = "Enter the position and thickness as numbers"; return@TextButton }
            if (kind == 3) {
                val boxes = splitziner.edit.Editor.drawerAt(a.parts, a.bounds.center.x, a.bounds.min.z + v * unit, th * unit, if (fmt.isInch) 12.7 else 12.0)
                if (boxes == null) { msg = "No room for a drawer there: there must be sides left and right and parts above and below, with nothing already in front, at least 150 mm (6 in) deep."; return@TextButton }
                // Each board gets its own id; the ids follow on from one another.
                val ids = ArrayList<String>(); val names = listOf("Drawer front", "Drawer box front", "Drawer box back", "Drawer side", "Drawer side", "Drawer bottom")
                var n = 1; val used = (open.analysis.originalParts.map { it.id } + open.meta.edits.filterIsInstance<splitziner.edit.Edit.AddBox>().map { it.id }).toSet()
                while (ids.size < boxes.size) { val id = "R$n"; if (id !in used) ids += id; n++ }
                onAdd(boxes.mapIndexed { i, (mn, sz) -> splitziner.edit.Edit.AddBox(ids[i], names[i], mn, sz) }); return@TextButton
            }
            val r = when (kind) {
                1 -> splitziner.edit.Editor.dividerAt(a.parts, a.bounds.min.x + v * unit, th * unit)
                2 -> splitziner.edit.Editor.doorAt(a.parts, a.bounds.center.x, a.bounds.min.z + v * unit, th * unit)
                else -> splitziner.edit.Editor.shelfAt(a.parts, a.bounds.min.z + v * unit, th * unit, pins = pins)
            }
            if (r == null) { msg = when (kind) {
                1 -> "No room for a divider there: there must be a shelf (or top and bottom) above and below it, and nothing already in the way."
                2 -> "No opening there: there must be sides left and right and parts above and below, with nothing already in front."
                else -> "No room for a shelf there: there must be an upright part on both sides at that height, and nothing already in the way." }; return@TextButton }
            onAdd(listOf(splitziner.edit.Edit.AddBox(newId(open, "S"), listOf("Shelf", "Divider", "Door")[kind], r.first, r.second)))
        }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add a " + listOf("shelf", "divider", "door", "drawer")[kind]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, label) in listOf("Shelf", "Divider", "Door", "Drawer").withIndex())
                        androidx.compose.material3.FilterChip(selected = kind == k, onClick = { kind = k; where = sizeText(fmt, if (k == 1) a.bounds.extents.x / 2 else a.bounds.extents.z / 2); msg = null }, label = { Text(label) })
                }
                Text(when (kind) {
                    1 -> "An upright board between the shelves above and below it, as deep as they allow."
                    2 -> "An inset door filling the opening at that height, 2 mm (about 1/16 in) clear all round, its face flush with the front. Hinges go on the hardware list."
                    3 -> "A drawer filling the opening at that height: an inset front like a door's, and a box behind it with 1/2 in (12.7 mm) each side for ball-bearing slides. The thickness is the front's; the box is 12 mm (1/2 in). Slides go on the hardware list."
                    else -> "A shelf between the sides either side of the middle, as deep as they allow, stopping at a back panel."
                }, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(where, { where = it; msg = null }, label = { Text(when (kind) { 1 -> "Centre, from the left end, ${fmt.unitLabel}"; 2, 3 -> "A height inside the opening, ${fmt.unitLabel}"; else -> "Height of its top from the floor, ${fmt.unitLabel}" }) }, singleLine = true)
                OutlinedTextField(t, { t = it; msg = null }, label = { Text("Thickness, ${fmt.unitLabel}") }, singleLine = true)
                if (kind == 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(pins, { pins = it })
                    Text("Adjustable, on shelf pins (1.5 mm short each end; pins go on the hardware list)", style = MaterialTheme.typography.bodySmall)
                }
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

/** Every change made to the model, in order, in plain words; any one can be taken back. */
@Composable
private fun ChangesDialog(open: OpenProject, vm: AppViewModel, onDismiss: () -> Unit) {
    val fmt = open.analysis.format
    val byId = (open.analysis.originalParts + open.analysis.parts).associateBy { it.id }
    fun name(id: String) = byId[id]?.let { p -> p.item?.let { "item $it" } ?: p.name } ?: id
    fun names(ids: List<String>) = ids.map { name(it) }.distinct().let { if (it.size <= 3) it.joinToString(", ") else "${it.take(3).joinToString(", ")} and ${it.size - 3} more" }
    fun len(v: Double) = "${fmt.format(kotlin.math.abs(v))} ${fmt.unitLabel}"
    fun describe(e: splitziner.edit.Edit): String = when (e) {
        is splitziner.edit.Edit.Move -> "Moved ${names(e.ids)}"
        is splitziner.edit.Edit.Thickness -> "Made ${names(e.ids)} ${len(e.t)} thick"
        is splitziner.edit.Edit.SquareUp -> "Squared up ${names(e.ids)}"
        is splitziner.edit.Edit.Merge -> "Joined ${names(e.ids)} into one piece"
        is splitziner.edit.Edit.Trim -> "Trimmed ${name(e.id)} around ${name(e.by)}"
        is splitziner.edit.Edit.Resize -> "Resized ${name(e.id)} to ${fmt.format(e.t)} × ${fmt.format(e.w)} × ${fmt.format(e.l)}"
        is splitziner.edit.Edit.AddBox -> "Added a board, ${e.name}"
        is splitziner.edit.Edit.Duplicate -> "Copied ${name(e.id)}"
        is splitziner.edit.Edit.Delete -> "Deleted ${names(e.ids)}"
        is splitziner.edit.Edit.CutBack -> "Cut ${name(e.id)} back to ${name(e.by)}"
        is splitziner.edit.Edit.SplitBoards -> "Made ${names(e.ids)} from boards"
        is splitziner.edit.Edit.House -> "Kept ${name(e.id)} in a groove in ${name(e.into)}"
        is splitziner.edit.Edit.Rotate -> "Turned ${names(e.ids)} a quarter turn"
        is splitziner.edit.Edit.Stretch -> "Made the whole ${listOf("width", "depth", "height")[e.axis]} ${len(e.by)} ${if (e.by >= 0) "bigger" else "smaller"}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { onDismiss(); vm.undoAllEdits() }) { Text("Take all back") } },
        title = { Text("Changes to the model") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { Text("Oldest first. Taking one back replays the rest without it; a later change that needed it is skipped and noted.", style = MaterialTheme.typography.bodySmall) }
                items(open.meta.edits.size) { i ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${describe(open.meta.edits[i])}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDismiss(); vm.removeEdit(i) }) { Text("Take back") }
                    }
                }
                if (open.analysis.editNotes.isNotEmpty()) item {
                    Text("Could not be replayed:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    for (n in open.analysis.editNotes) Text("• $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/** Pick a starting design and its main sizes; the parts can be changed one by one afterwards. */
@Composable
private fun NewDesignDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val inch = vm.inchLocale
    val unit = if (inch) "in" else "mm"
    fun show(mm: Double, count: Boolean) = if (count) "${mm.toInt()}" else if (inch) "%.2f".format(java.util.Locale.US, mm / 25.4).trimEnd('0').trimEnd('.') else "${Math.round(mm)}"
    var key by remember { mutableStateOf<String?>(splitziner.edit.Templates.ALL.first().key) }
    val template = key?.let { splitziner.edit.Templates.byKey(it) }
    val values = remember(key) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { template?.let { t -> val d = splitziner.edit.Templates.defaults(t, inch); t.fields.forEach { put(it.key, show(d.getValue(it.key), it.count)) } } } }
    var error by remember { mutableStateOf<String?>(null) }
    // A small picture of the design at the sizes typed so far, redrawn a moment after typing stops.
    val sizesKey = key + values.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }
    val preview by androidx.compose.runtime.produceState<io.github.emotionalleaves.splitziner.render.PreparedSheet?>(null, sizesKey) {
        val t = template ?: run { value = null; return@produceState }
        kotlinx.coroutines.delay(250)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val d = splitziner.edit.Templates.defaults(t, false)
                val mm = t.fields.associate { f -> f.key to (num(values[f.key] ?: "")?.let { v -> if (f.count) v else if (inch) v * 25.4 else v } ?: d.getValue(f.key)) }
                val parts = t.build(mm).mapIndexed { i, m -> splitziner.parts.Part("P$i", "P$i", m) }
                io.github.emotionalleaves.splitziner.render.PreparedSheet.of(splitziner.explode.ExplodedView.render(parts, 90.0, 60.0, splitziner.explode.ExplodeOptions(balloons = false)))
            }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            val t = template
            if (t == null) { onDismiss(); vm.newDesign(); return@TextButton }
            val mm = HashMap<String, Double>()
            for (f in t.fields) {
                val v = num(values[f.key] ?: "")
                if (v == null || v < 0 || (!f.count && v == 0.0 && !f.key.startsWith("back"))) { error = "Enter a number for ${f.label.lowercase()}."; return@TextButton }
                mm[f.key] = if (f.count) v else if (inch) v * 25.4 else v
            }
            onDismiss(); vm.newDesign(t.key, mm)
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New design") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in splitziner.edit.Templates.ALL) androidx.compose.material3.FilterChip(selected = key == t.key, onClick = { key = t.key; error = null }, label = { Text(t.name) })
                    androidx.compose.material3.FilterChip(selected = key == null, onClick = { key = null; error = null }, label = { Text("One board") })
                }
                if (template == null) Text("Starts from a single board to build on with Add a board.", style = MaterialTheme.typography.bodySmall)
                else {
                    preview?.let { SheetThumb(it, Modifier.fillMaxWidth().height(130.dp)) }
                    Text(template.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (f in template.fields) if (f.toggle) Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(values[f.key] == "1", { values[f.key] = if (it) "1" else "0"; error = null })
                        Text(f.label, style = MaterialTheme.typography.bodyMedium)
                    } else OutlinedTextField(value = values[f.key] ?: "", onValueChange = { values[f.key] = it; error = null },
                        label = { Text(if (f.count) f.label else "${f.label} ($unit)") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}


/** One project in the list: a monogram, its name, what it is, and when it was last used. */
@Composable
private fun ProjectCard(p: ProjectMeta, thumb: java.io.File, version: Int, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit = {}, onRename: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    // The model's picture, read off the main thread; a letter stands in until there is one.
    val pic by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, p.id, p.updatedAt, version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { if (thumb.exists()) android.graphics.BitmapFactory.decodeFile(thumb.path)?.let { b -> b.asImageBitmap() } else null }.getOrNull()
        }
    }
    ElevatedCard(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val shot = pic
            if (shot != null) androidx.compose.foundation.Image(shot, contentDescription = null, modifier = Modifier.size(64.dp, 48.dp).background(androidx.compose.ui.graphics.Color.White, MaterialTheme.shapes.small).padding(2.dp))
            else Box(Modifier.size(64.dp, 48.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Text(p.name.trim().take(1).uppercase().ifBlank { "?" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val what = listOfNotNull(if (p.partCount > 0) "${p.partCount} part${if (p.partCount == 1) "" else "s"}" else null, p.size.ifBlank { null }).joinToString("  \u00b7  ")
                Text(what.ifBlank { p.fileName }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val at = maxOf(p.createdAt, p.updatedAt)
                if (at > 0) Text(android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options for ${p.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Make a copy") }, leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}


/** An item number in a circle, the way the drawings' balloons show it. */
@Composable
private fun ItemBadge(item: Int) {
    Box(Modifier.size(28.dp).border(1.5.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
        Text("$item", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}


/** Container and content colours for "all good", in light and dark. */
@Composable
private fun successColors(): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
    if (isSystemInDarkTheme()) androidx.compose.ui.graphics.Color(0xFF1E4D2B) to androidx.compose.ui.graphics.Color(0xFFC8EFCF)
    else androidx.compose.ui.graphics.Color(0xFFDDF1E1) to androidx.compose.ui.graphics.Color(0xFF14421F)


/** Previous and next around what is shown: a title and a smaller line under it. */
@Composable
private fun PagerBar(title: String, sub: String, canBack: Boolean, canNext: Boolean, onBack: () -> Unit, onNext: () -> Unit, jump: List<String> = emptyList(), onJump: (Int) -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.FilledTonalButton(onClick = onBack, enabled = canBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) { Text("\u2190") }
        // With many pages, tapping the title lists them all, to go straight to one.
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().then(if (jump.size > 2) Modifier.clickable { menu = true } else Modifier).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title + if (jump.size > 2) " \u25be" else "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                jump.forEachIndexed { i, label -> DropdownMenuItem(text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { menu = false; onJump(i) }) }
            }
        }
        androidx.compose.material3.FilledTonalButton(onClick = onNext, enabled = canNext, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) { Text("\u2192") }
    }
}


/** A drawn picture shown whole, on white like paper, for small previews (no zooming). */
@Composable
private fun SheetThumb(sheet: io.github.emotionalleaves.splitziner.render.PreparedSheet, modifier: Modifier = Modifier) {
    Box(modifier.background(androidx.compose.ui.graphics.Color.White, MaterialTheme.shapes.small).padding(6.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val k = minOf(size.width / sheet.widthMm, size.height / sheet.heightMm)
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate((size.width - sheet.widthMm * k) / 2, (size.height - sheet.heightMm * k) / 2)
                sheet.draw(nc, k)
                nc.restore()
            }
        }
    }
}


/**
 * Everything needed to build it, in one zip to send or keep: the drawings and the build guide as PDFs, the
 * cutting plan, the cut list (CSV) and the shopping list. Drawings and guide are worked out first if they are not yet.
 */
private fun shareBuildPack(context: Context, open: OpenProject, vm: AppViewModel, scope: kotlinx.coroutines.CoroutineScope) {
    Toast.makeText(context, "Making the build pack\u2026", Toast.LENGTH_SHORT).show()
    scope.launch {
        val file = runCatching {
            vm.ensureDrawing(); vm.ensureGuide()
            // Wait for both to be ready (they work in the background).
            val st = vm.state.first { s -> s.open?.meta?.id != open.meta.id || (s.drawing != null && !s.drawingBusy && s.guide != null && !s.guideBusy) }
            val d = st.drawing; val g = st.guide
            if (d == null || g == null) error("The project was closed")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { makeBuildPack(context, open, vm, d, g) }
        }.onFailure { Diagnostics.logError("build pack", it) }.getOrNull()
        if (file == null) { Toast.makeText(context, "Could not make the build pack", Toast.LENGTH_LONG).show(); return@launch }
        shareFile(context, file, "application/zip", "Share build pack")
    }
}

/** The build pack's zip: drawings, guide and cutting plan as PDFs, the cut list and the shopping list. */
internal fun makeBuildPack(context: Context, open: OpenProject, vm: AppViewModel, d: DrawingState, g: GuideState): File {
                val base = baseName(open)
                val a = open.analysis; val fmt = a.format
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val stock = savedStock(context, fmt)
                val plan = splitziner.cutplan.CutPlanner.plan(a.cutList.rows, stock)
                val cutPages = splitziner.cutplan.CutPlanLayout.pages(plan, a.cutList.rows, open.meta.title.ifBlank { open.meta.name }, fmt,
                    extraBuy = listOfNotNull(runCatching { splitziner.cutplan.EdgeBanding.line(a.parts, fmt) }.getOrNull())).map { io.github.emotionalleaves.splitziner.render.PreparedSheet.of(it) }
                val files = listOf(
                    PdfExporter.write(context, d.sheets, "${base}_drawings.pdf"),
                    PdfExporter.write(context, g.pages, "${base}_build_guide.pdf"),
                    PdfExporter.write(context, cutPages, "${base}_cutting_plan.pdf"),
                    File(dir, "${base}_cut_list.csv").apply { writeText(CsvWriter.cutList(a.cutList, fmt)) },
                    File(dir, "${base}_shopping_list.txt").apply { writeText(shoppingText(context, open, vm, stock)) },
                )
                val zip = File(dir, "${base}_build_pack.zip")
                java.util.zip.ZipOutputStream(zip.outputStream()).use { z -> for (f in files) { z.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
                return zip
}

/** "Made item 13 from 4 boards" as a change still to make: "Make item 13 from 4 boards". */
internal fun toDo(line: String): String {
    val verbs = mapOf("Made" to "Make", "Moved" to "Move", "Joined" to "Join", "Kept" to "Keep", "Changed" to "Change", "Cut" to "Cut", "Removed" to "Remove", "Added" to "Add", "Turned" to "Turn")
    val first = line.substringBefore(' ')
    return verbs[first]?.let { it + line.removePrefix(first) } ?: line
}
