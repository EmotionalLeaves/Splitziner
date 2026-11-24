package io.github.emotionalleaves.splitziner

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.emotionalleaves.splitziner.render.ImageExporter
import io.github.emotionalleaves.splitziner.render.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import splitziner.io.StlWriter
import splitziner.mesh.Mesh
import splitziner.mesh.MeshOps
import java.io.File

/**
 * The one test that runs on a real Android system: open a cabinet the way "Open with" does,
 * visit every tab, and make each export. It exists to catch crashes the engine's own tests cannot see.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun box(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double) = Mesh(
        doubleArrayOf(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1),
        intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7),
    )

    /** A small cabinet in millimetres: two sides, bottom, top, shelf, back and a kick board. */
    private fun cabinet() = MeshOps.concatenate(listOf(
        box(0.0, 18.0, 0.0, 600.0, 0.0, 1500.0), box(882.0, 900.0, 0.0, 600.0, 0.0, 1500.0),
        box(18.0, 882.0, 0.0, 594.0, 0.0, 18.0), box(18.0, 882.0, 0.0, 594.0, 1482.0, 1500.0),
        box(18.0, 882.0, 40.0, 594.0, 750.0, 768.0), box(18.0, 882.0, 594.0, 600.0, 0.0, 1500.0),
        box(18.0, 882.0, 0.0, 18.0, 18.0, 108.0),
    ))

    /** Saves what the screen shows, for looking at after the run (CI copies these off the emulator). */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(600)
        val inst = InstrumentationRegistry.getInstrumentation()
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        val dir = File(inst.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Waits for [cond]; on timeout fails with the app's own log, which says what it was doing. */
    private fun waitFor(vm: AppViewModel, ms: Long, cond: () -> Boolean) {
        try { rule.waitUntil(ms) { cond() } } catch (e: Throwable) {
            throw AssertionError("Timed out after $ms ms. State: busy=${vm.state.value.busyLabel} drawingBusy=${vm.state.value.drawingBusy} guideBusy=${vm.state.value.guideBusy} checkBusy=${vm.state.value.checkBusy} error=${vm.state.value.error}\n" + vm.diagnosticReport().takeLast(6000), e)
        }
    }

    @Test fun openDrawGuideAndExport() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "smoke_cabinet.stl")
        file.outputStream().use { StlWriter.writeBinary(cabinet(), it, "smoke test cabinet") }
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            waitFor(vm, 180_000) { vm.state.value.open != null || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val open = vm.state.value.open!!
            assertEquals(7, open.analysis.parts.size)
            println("SMOKE parts=${open.analysis.parts.size} items=${open.analysis.cutList.rows.size}")

            fun tap(label: String) {
                rule.waitUntil(30_000) { rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                // A button may sit outside a scrolled area; bring it into view first when it can be scrolled to.
                runCatching { rule.onAllNodesWithText(label).onFirst().performScrollTo() }
                rule.onAllNodesWithText(label).onFirst().performClick()
                rule.waitForIdle()
            }
            // If the app asks about units, accept what it guessed.
            if (rule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()) tap("Cancel")

            // The 3D view first: let it draw some frames on the graphics thread.
            tap("3D"); Thread.sleep(3000); shot("c01_3d")
            tap("Parts"); shot("c02_parts"); tap("Cut list"); shot("c03_cutlist")
            tap("Drawing")
            waitFor(vm, 240_000) { vm.state.value.drawing != null && !vm.state.value.drawingBusy }
            val drawing = vm.state.value.drawing!!
            shot("c04_drawing"); runCatching { tap("Turn to fill"); Thread.sleep(800); shot("c04b_drawing_turned"); tap("Turned") }
            // Assembly sheet and exploded sheet; the cabinet's parts are all plain boards, so no detail sheets.
            assertTrue("sheets: ${drawing.sheets.size}", drawing.sheets.size >= 2)
            val pdf = PdfExporter.write(ctx, drawing.sheets, "smoke_drawing.pdf")
            assertTrue("pdf ${pdf.length()}", pdf.length() > 5_000)
            val (png, dpi) = ImageExporter.writePng(ctx, drawing.sheets[0], "smoke_sheet.png")
            assertTrue("png ${png.length()} at $dpi", png.length() > 5_000)
            println("SMOKE drawing sheets=${drawing.sheets.size} pdf=${pdf.length()} png=${png.length()}@$dpi")

            tap("Guide")
            waitFor(vm, 240_000) { vm.state.value.guide != null && !vm.state.value.guideBusy }
            val guide = vm.state.value.guide!!
            shot("c05_guide"); runCatching { tap("Pages"); Thread.sleep(800); shot("c05a_guide_pages"); tap("Steps") }
            assertTrue(guide.stepCount >= 2)
            assertTrue(PdfExporter.write(ctx, guide.pages, "smoke_guide.pdf").length() > 5_000)
            println("SMOKE guide steps=${guide.stepCount} pages=${guide.pages.size}")
            // Build mode: a step a screen; Done moves on and is remembered.
            runCatching {
                tap("Build mode")
                rule.waitUntil(20_000) { rule.onAllNodesWithText("Step 1 of", substring = true).fetchSemanticsNodes().isNotEmpty() }
                tap("Done, next")
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Step 2 of", substring = true).fetchSemanticsNodes().isNotEmpty() }
                shot("c05b_build_mode")
                println("SMOKE build mode moves on")
                tap("Back"); tap("Not done")
                rule.onNodeWithContentDescription("Close").performClick(); rule.waitForIdle()
            }.onFailure { println("SMOKE build mode failed: ${it.message}") }
            // Help: its own screen, by section, searchable.
            runCatching {
                rule.onNodeWithContentDescription("Project menu").performClick(); rule.waitForIdle()
                tap("Help")
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Search help").fetchSemanticsNodes().isNotEmpty() }
                tap("Units")
                shot("c10_help")
                println("SMOKE help opens")
                rule.onNodeWithContentDescription("Close").performClick(); rule.waitForIdle()
            }.onFailure { println("SMOKE help failed: ${it.message}") }
            // The build pack: every output in one zip.
            val pack = makeBuildPack(ctx, vm.state.value.open!!, vm, vm.state.value.drawing!!, guide)
            val names = java.util.zip.ZipFile(pack).use { z -> z.entries().toList().map { it.name } }
            assertEquals(5, names.size)
            println("SMOKE build pack ${pack.length()} bytes: $names")

            // Dialogs people use, photographed for review: the cutting plan and the tools list.
            runCatching {
                tap("Cut list"); tap("Cutting plan")
                // The plan is laid out and shown in the app now, not only shared.
                rule.waitUntil(60_000) { rule.onAllNodesWithText("Share list").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithText("of sheet used", substring = true).fetchSemanticsNodes().isNotEmpty() }
                println("SMOKE cutting plan shown")
                shot("c06_cutplan")
                rule.onNodeWithContentDescription("Close").performClick(); rule.waitForIdle()
                // The shopping list is a checklist: tick the first line and see the count move.
                tap("Shopping list")
                rule.waitUntil(60_000) { rule.onAllNodesWithText("0 of ", substring = true).fetchSemanticsNodes().isNotEmpty() }
                rule.onAllNodes(androidx.compose.ui.test.isToggleable()).onFirst().performClick(); rule.waitForIdle()
                rule.waitUntil(10_000) { rule.onAllNodesWithText("1 of ", substring = true).fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithText("ticked", substring = true).fetchSemanticsNodes().isNotEmpty() }
                println("SMOKE shopping list ticks")
                shot("c09_shopping")
                tap("Clear ticks")
                rule.onNodeWithContentDescription("Close").performClick(); rule.waitForIdle()
                tap("More\u2026"); tap("Prices"); shot("c08_prices"); tap("Cancel")
                tap("Check"); waitFor(vm, 120_000) { vm.state.value.check != null }; tap("Tools"); shot("c07_tools"); tap("Cancel")
            }.onFailure { println("SMOKE dialog shots failed: ${it.message}") }

            // Editing: move a part up 100 mm and add a board; both must be there after the model is re-read.
            val first = open.analysis.parts.first()
            val z0 = first.mesh.bounds.min.z
            vm.addEdit(splitziner.edit.Edit.Move(listOf(first.id), splitziner.math.Vec3(0.0, 0.0, 100.0)))
            waitFor(vm, 120_000) { vm.state.value.busyLabel == null && vm.state.value.open!!.meta.edits.size == 1 }
            vm.addEdit(splitziner.edit.Edit.AddBox("N1", "Shelf", splitziner.math.Vec3(0.0, 0.0, 2000.0), splitziner.math.Vec3(500.0, 300.0, 18.0)))
            waitFor(vm, 120_000) { vm.state.value.busyLabel == null && vm.state.value.open!!.meta.edits.size == 2 }
            val edited = vm.state.value.open!!.analysis
            assertEquals(z0 + 100.0, edited.parts.first { it.id == first.id }.mesh.bounds.min.z, 1e-6)
            assertEquals(open.analysis.parts.size + 1, edited.parts.size)
            vm.undoLastEdit()
            waitFor(vm, 120_000) { vm.state.value.busyLabel == null && vm.state.value.open!!.meta.edits.size == 1 }
            println("SMOKE editing ok: ${edited.parts.size} parts after adding one")

            // Which way is up: the cabinet turned as if saved with Y up keeps its parts and lies on its back.
            val tall = open.analysis.bounds.extents.z   // as first read, before the test moved a part up
            vm.update { it.copy(up = "y") }
            waitFor(vm, 120_000) { vm.state.value.busyLabel == null && vm.state.value.open!!.meta.up == "y" }
            assertEquals(tall, vm.state.value.open!!.analysis.bounds.extents.y, 1.0)
            vm.update { it.copy(up = "z") }
            waitFor(vm, 120_000) { vm.state.value.busyLabel == null && vm.state.value.open!!.meta.up == "z" }
            println("SMOKE turning ok")

            // Back to 3D with the pull-apart offsets ready, then give it time to fail if it is going to.
            vm.ensureExplodeOffsets()
            waitFor(vm, 120_000) { vm.state.value.explodeOffsets != null }
            tap("3D"); Thread.sleep(3000)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNull(Diagnostics.lastCrash())
            assertNull(vm.state.value.error)
        }
    }

    /** A real desk drawn in a phone 3D app: messy surfaces, overlapping parts, a shelf that floats. Open it, check it, fix it, draw it. */
    @Test fun realDeskIsCheckedFixedAndDrawn() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val file = File(ctx.cacheDir, "smoke_desk.stl")
        inst.context.assets.open("desk.stl").use { input -> file.outputStream().use { input.copyTo(it) } }
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            fun settled() = vm.state.value.open != null && vm.state.value.busyLabel == null
            waitFor(vm, 240_000) { settled() || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            assertEquals(33, vm.state.value.open!!.analysis.parts.size)
            fun tap(label: String) {
                rule.waitUntil(30_000) { rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                // A button may sit outside a scrolled area; bring it into view first when it can be scrolled to.
                runCatching { rule.onAllNodesWithText(label).onFirst().performScrollTo() }
                rule.onAllNodesWithText(label).onFirst().performClick()
                rule.waitForIdle()
            }
            if (rule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()) tap("Cancel")

            tap("Check")
            waitFor(vm, 240_000) { vm.state.value.check != null }
            shot("d01_check")
            val before = vm.state.value.check!!.problems
            assertTrue("problems before: $before", before >= 5)

            vm.planFixes()
            waitFor(vm, 240_000) { vm.state.value.fixPlan != null || vm.state.value.error != null }
            val plan = vm.state.value.fixPlan!!
            shot("d02_fixes")
            assertTrue("changes: ${plan.edits.size}", plan.edits.size >= 5)
            // The proposal is on screen; accept it the way a person would.
            tap("Apply ${plan.edits.size} changes")
            waitFor(vm, 240_000) { settled() && vm.state.value.open!!.meta.edits.isNotEmpty() }
            vm.ensureCheck()
            waitFor(vm, 240_000) { vm.state.value.check != null }
            val after = vm.state.value.check!!.problems
            println("SMOKE desk problems $before -> $after after ${plan.edits.size} changes; left ${plan.left.size}")
            assertTrue("problems $before -> $after", after < before)

            tap("Drawing")
            waitFor(vm, 300_000) { vm.state.value.drawing != null && !vm.state.value.drawingBusy }
            val drawing = vm.state.value.drawing!!
            shot("d04_drawing")
            assertTrue(PdfExporter.write(ctx, drawing.sheets, "smoke_desk.pdf").length() > 20_000)
            tap("Guide")
            waitFor(vm, 300_000) { vm.state.value.guide != null && !vm.state.value.guideBusy }
            shot("d05_guide")
            tap("3D"); Thread.sleep(3000); shot("d03_3d")
            // Play the build in 3D a few steps in.
            runCatching {
                tap("Play the build"); rule.waitUntil(60_000) { rule.onAllNodesWithText("Step 1 of", substring = true).fetchSemanticsNodes().isNotEmpty() }
                repeat(6) { tap("\u2192") }; Thread.sleep(1500); shot("d06_build_steps"); tap("Show all")
            }.onFailure { println("SMOKE build steps failed: ${it.message}") }
            println("SMOKE desk sheets=${drawing.sheets.size} guide pages=${vm.state.value.guide!!.pages.size}")
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNull(Diagnostics.lastCrash())
            assertNull(vm.state.value.error)
        }
    }

    /** A complicated project: a run of four kitchen cabinets with doors, drawers and one worktop (69 parts, in mm). */
    @Test fun kitchenRunBuildsAsCabinets() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val file = File(ctx.cacheDir, "smoke_kitchen_run.stl")
        inst.context.assets.open("kitchen_run.stl").use { input -> file.outputStream().use { input.copyTo(it) } }
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            waitFor(vm, 240_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            assertEquals(69, vm.state.value.open!!.analysis.parts.size)
            fun tap(label: String) {
                rule.waitUntil(30_000) { rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                runCatching { rule.onAllNodesWithText(label).onFirst().performScrollTo() }
                rule.onAllNodesWithText(label).onFirst().performClick()
                rule.waitForIdle()
            }
            if (rule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()) tap("Cancel")
            val t0 = System.currentTimeMillis()
            tap("Guide")
            waitFor(vm, 300_000) { vm.state.value.guide != null && !vm.state.value.guideBusy }
            val guide = vm.state.value.guide!!
            shot("k01_guide")
            assertTrue("pages ${guide.pages.size}", guide.pages.size >= 6)
            assertTrue(PdfExporter.write(ctx, guide.pages, "smoke_kitchen_guide.pdf").length() > 20_000)
            println("SMOKE kitchen run guide steps=${guide.stepCount} pages=${guide.pages.size} in ${System.currentTimeMillis() - t0} ms")
            assertNull(Diagnostics.lastCrash())
            assertNull(vm.state.value.error)
        }
    }

    /** A stretcher at a common size: New design, Canvas stretcher, 16 x 20 in, Create. */
    @Test fun stretcherAtACommonSize() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            fun tap(label: String) {
                rule.waitUntil(30_000) { rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                runCatching { rule.onAllNodesWithText(label).onFirst().performScrollTo() }
                rule.onAllNodesWithText(label).onFirst().performClick()
                rule.waitForIdle()
            }
            rule.waitUntil(30_000) { rule.onAllNodesWithText("New design").fetchSemanticsNodes().isNotEmpty() }
            tap("New design"); tap("Canvas stretcher")
            // Let the design's picture draw before tapping below it.
            Thread.sleep(800); rule.waitForIdle()
            // Inches or centimetres, as the phone's locale has it.
            val size = if (vm.inchLocale) "16 x 20 in" else "40 x 50 cm"
            tap(size); shot("s01_stretcher_size")
            tap("Create")
            waitFor(vm, 120_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val e = vm.state.value.open!!.analysis.bounds.extents
            val want = if (vm.inchLocale) 16 * 25.4 to 20 * 25.4 else 400.0 to 500.0
            assertEquals(want.first, minOf(e.x, e.y), 1.0); assertEquals(want.second, maxOf(e.x, e.y), 1.0)
            assertNull(Diagnostics.lastCrash())
        }
    }

    /** A studio design the way a person would make one: New design, Display plinth, tick the clear cover, Create. */
    @Test fun plinthWithACoverFromTemplate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            fun tap(label: String) {
                rule.waitUntil(30_000) { rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                runCatching { rule.onAllNodesWithText(label).onFirst().performScrollTo() }
                rule.onAllNodesWithText(label).onFirst().performClick()
                rule.waitForIdle()
            }
            rule.waitUntil(30_000) { rule.onAllNodesWithText("New design").fetchSemanticsNodes().isNotEmpty() }
            tap("New design"); tap("Display plinth"); tap("A clear acrylic cover on top (a vitrine)"); shot("p01_plinth_cover")
            tap("Create")
            waitFor(vm, 120_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val a = vm.state.value.open!!.analysis
            assertEquals(5, a.parts.count { it.excluded })
            assertEquals(1, a.orders.size)
            tap("Guide")
            waitFor(vm, 120_000) { vm.state.value.guide != null && !vm.state.value.guideBusy }
            shot("p02_plinth_guide")
            println("SMOKE plinth parts=${a.parts.size} orders=${a.orders} guide steps=${vm.state.value.guide!!.stepCount}")
            assertNull(Diagnostics.lastCrash())
        }
    }

    /** A plinth sized for the open model, from the project menu: a new design, its top wider than the model. */
    @Test fun plinthForTheOpenModel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            fun tap(label: String) {
                rule.waitUntil(30_000) { rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                runCatching { rule.onAllNodesWithText(label).onFirst().performScrollTo() }
                rule.onAllNodesWithText(label).onFirst().performClick()
                rule.waitForIdle()
            }
            rule.waitUntil(30_000) { rule.onAllNodesWithText("New design").fetchSemanticsNodes().isNotEmpty() }
            tap("New design"); tap("Taboret"); tap("Create")
            waitFor(vm, 120_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val work = vm.state.value.open!!.analysis.parts.filter { !it.excluded }.map { it.mesh.bounds }.reduce { x, y -> x.union(y) }
            rule.onNodeWithContentDescription("Project menu").performClick(); rule.waitForIdle()
            tap("Make a plinth for this")
            waitFor(vm, 120_000) { (vm.state.value.open?.meta?.name == "Display plinth" && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val b = vm.state.value.open!!.analysis.parts.filter { !it.excluded }.map { it.mesh.bounds }.reduce { x, y -> x.union(y) }
            assertTrue(b.extents.x > work.extents.x && b.extents.y > work.extents.y)
            shot("w01_plinth_for_work")
            // Its sizes can be changed and the design built again in place.
            rule.onNodeWithContentDescription("Project menu").performClick(); rule.waitForIdle()
            tap("Design sizes")
            val unit = if (vm.inchLocale) "in" else "mm"
            rule.waitUntil(30_000) { rule.onAllNodesWithText("Height ($unit)").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("Height ($unit)").onFirst().performTextReplacement(if (vm.inchLocale) "30" else "760")
            shot("w02_design_sizes")
            tap("Build again")
            waitFor(vm, 120_000) { (vm.state.value.open?.meta?.templateValues?.get("h")?.let { kotlin.math.abs(it - 760.0) < 5 } == true && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val h = vm.state.value.open!!.analysis.parts.filter { !it.excluded }.map { it.mesh.bounds }.reduce { x, y -> x.union(y) }.extents.z
            assertEquals(if (vm.inchLocale) 762.0 else 760.0, h, 2.0)
            assertNull(Diagnostics.lastCrash())
        }
    }

    /** Starting a design from a template, the way a person would: New design, pick Bookcase, Create. */
    @Test fun newBookcaseFromTemplate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            fun tap(label: String) {
                rule.waitUntil(30_000) { rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                runCatching { rule.onAllNodesWithText(label).onFirst().performScrollTo() }
                rule.onAllNodesWithText(label).onFirst().performClick()
                rule.waitForIdle()
            }
            rule.waitUntil(30_000) { rule.onAllNodesWithText("New design").fetchSemanticsNodes().isNotEmpty() }
            shot("n00_home")
            tap("New design"); shot("n01a_studio_designs"); tap("Furniture"); tap("Bookcase"); Thread.sleep(2000); rule.waitForIdle(); shot("n01_new_design")
            tap("Create")
            waitFor(vm, 120_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val a = vm.state.value.open!!.analysis
            // Two sides, top, bottom, kick board, four shelves and a back.
            assertEquals(10, a.parts.size)
            tap("Guide")
            waitFor(vm, 120_000) { vm.state.value.guide != null && !vm.state.value.guideBusy }
            shot("n02_bookcase_guide")
            println("SMOKE bookcase parts=${a.parts.size} items=${a.cutList.rows.size} guide steps=${vm.state.value.guide!!.stepCount}")
            // Overall size: the dialog, then 6 in (152.4 mm) wider through the same edit it makes.
            tap("3D"); tap("More\u2026"); tap("Overall size"); shot("n03_overall_size"); tap("Cancel")
            val w0 = a.bounds.extents.x
            val plane = splitziner.edit.Editor.stretchPlane(a.parts, 0)!!
            vm.addEdits(listOf(splitziner.edit.Edit.Stretch(0, plane.first, 152.4)))
            waitFor(vm, 120_000) { vm.state.value.busyLabel == null && vm.state.value.open!!.meta.edits.isNotEmpty() }
            assertEquals(w0 + 152.4, vm.state.value.open!!.analysis.bounds.extents.x, 0.01)
            println("SMOKE overall size ok: ${vm.state.value.open!!.analysis.overallSize}")
            // Share project, then open the file again: the same model with the same changes.
            val before = vm.state.value.open!!
            val f = vm.exportProject()!!
            vm.closeProject(); rule.waitForIdle()
            vm.importFrom(Uri.fromFile(f))
            waitFor(vm, 120_000) { vm.state.value.open != null && vm.state.value.busyLabel == null && vm.state.value.open!!.meta.id != before.meta.id }
            val after = vm.state.value.open!!
            assertEquals(before.meta.edits.size, after.meta.edits.size)
            assertEquals(before.analysis.parts.size, after.analysis.parts.size)
            assertEquals(before.analysis.bounds.extents.x, after.analysis.bounds.extents.x, 0.01)
            println("SMOKE project round trip ok: ${after.meta.edits.size} changes kept")
            // Tap the middle of the 3D view to select a part, then open its Change dialog.
            runCatching {
                tap("3D"); Thread.sleep(1500); shot("n04a_before_tap")
                rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.52f)) }
                Thread.sleep(800); shot("n04b_after_tap")
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Change").fetchSemanticsNodes().isNotEmpty() }
                tap("Change"); shot("n04_change_part"); tap("Close")
            }.onFailure { println("SMOKE change dialog shot failed: ${it.message}") }
            assertNull(Diagnostics.lastCrash())
        }
    }

    /** A cabinet with a pair of doors over the front, and a bookcase with shelves on pins: hardware, last steps. */
    @Test fun doorsAndShelfPins() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            for ((key, values) in listOf("cabinet" to mapOf("doors" to 2.0, "overlay" to 1.0), "shelves" to mapOf("pins" to 1.0), "drawers" to emptyMap())) {
                val t = splitziner.edit.Templates.byKey(key)
                vm.newDesign(key, splitziner.edit.Templates.defaults(t, false) + values)
                waitFor(vm, 120_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null && vm.state.value.open!!.meta.name.isNotEmpty()) || vm.state.value.error != null }
                assertNull(vm.state.value.error)
                val a = vm.state.value.open!!.analysis
                val doors = splitziner.check.Doors.find(a.parts).size; val pins = splitziner.check.ShelfPins.find(a.parts).size; val drawers = splitziner.check.Drawers.find(a.parts).size
                val hw = vm.hardwareFor(vm.state.value.open!!)
                println("SMOKE $key doors=$doors pinned shelves=$pins drawers=$drawers hardware=$hw")
                rule.waitUntil(30_000) { rule.onAllNodesWithText("Guide").fetchSemanticsNodes().isNotEmpty() }
                rule.onAllNodesWithText("Guide").onFirst().performClick(); rule.waitForIdle()
                waitFor(vm, 120_000) { vm.state.value.guide != null && !vm.state.value.guideBusy }
                shot("t0${listOf("cabinet", "shelves", "drawers").indexOf(key) + 1}_${key}_guide")
                when (key) { "cabinet" -> assertEquals(2, doors); "shelves" -> assertEquals(4, pins); else -> assertEquals(4, drawers) }
                vm.closeProject(); rule.waitForIdle()
            }
            // A drawer added to a plain cabinet's upper opening, the way Add a drawer does it.
            val t = splitziner.edit.Templates.byKey("cabinet")
            vm.newDesign("cabinet", splitziner.edit.Templates.defaults(t, false))
            waitFor(vm, 120_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            val a0 = vm.state.value.open!!.analysis
            val boxes = splitziner.edit.Editor.drawerAt(a0.parts, a0.bounds.center.x, a0.bounds.min.z + 0.8 * a0.bounds.extents.z, 18.0)!!
            vm.addEdits(boxes.mapIndexed { i, (mn, sz) -> splitziner.edit.Edit.AddBox("R${i + 1}", "Drawer", mn, sz) })
            waitFor(vm, 120_000) { vm.state.value.busyLabel == null && vm.state.value.open!!.meta.edits.size == 6 }
            val drawers = splitziner.check.Drawers.find(vm.state.value.open!!.analysis.parts).size
            println("SMOKE added drawer: drawers=$drawers hardware=${vm.hardwareFor(vm.state.value.open!!)}")
            assertEquals(1, drawers)
            assertNull(Diagnostics.lastCrash())
        }
    }

    /** A 3MF file (a zip with XML inside, two objects) opens as two parts, not as a project file. */
    @Test fun threeMfFileOpens() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "smoke_boxes.3mf")
        fun box(id: Int, w: Double, d: Double, h: Double): String {
            val v = listOf(0.0, h).flatMap { z -> listOf(0.0, d).flatMap { y -> listOf(0.0, w).map { x -> "<vertex x=\"$x\" y=\"$y\" z=\"$z\"/>" } } }
            val f = listOf(listOf(0, 2, 1), listOf(1, 2, 3), listOf(4, 5, 6), listOf(5, 7, 6), listOf(0, 1, 4), listOf(1, 5, 4), listOf(2, 6, 3), listOf(3, 6, 7), listOf(0, 4, 2), listOf(2, 4, 6), listOf(1, 3, 5), listOf(3, 7, 5))
            return "<object id=\"$id\" type=\"model\"><mesh><vertices>${v.joinToString("")}</vertices><triangles>${f.joinToString("") { "<triangle v1=\"${it[0]}\" v2=\"${it[1]}\" v3=\"${it[2]}\"/>" }}</triangles></mesh></object>"
        }
        val xml = "<?xml version=\"1.0\"?><model unit=\"millimeter\"><resources>${box(1, 400.0, 18.0, 300.0)}${box(2, 400.0, 18.0, 300.0)}</resources><build><item objectid=\"1\"/><item objectid=\"2\" transform=\"1 0 0 0 1 0 0 0 1 500 0 0\"/></build></model>"
        java.util.zip.ZipOutputStream(file.outputStream()).use { z -> z.putNextEntry(java.util.zip.ZipEntry("3D/3dmodel.model")); z.write(xml.toByteArray()); z.closeEntry() }
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            waitFor(vm, 120_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val a = vm.state.value.open!!.analysis
            assertEquals(2, a.parts.size)
            assertEquals(300.0, a.bounds.extents.z, 0.5)
            println("SMOKE 3mf ok: ${a.parts.size} parts, ${a.overallSize}")
        }
    }

    /** An OBJ file (Y up, two objects) opens as two parts standing the right way up. */
    @Test fun objFileOpens() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "smoke_boxes.obj")
        val sb = StringBuilder()
        for ((k, ox) in listOf(0.0, 500.0).withIndex()) {
            sb.append("o Box$k\n")
            // 400 wide, 18 thick, 300 tall in Y (up in OBJ).
            for (z in listOf(0.0, 18.0)) for (y in listOf(0.0, 300.0)) for (x in listOf(0.0, 400.0)) sb.append("v ${x + ox} $y $z\n")
            val b = 8 * k
            fun f(vararg i: Int) = sb.append("f " + i.joinToString(" ") { "${it + b}" } + "\n")
            f(1, 3, 4, 2); f(5, 6, 8, 7); f(1, 2, 6, 5); f(3, 7, 8, 4); f(1, 5, 7, 3); f(2, 4, 8, 6)
        }
        file.writeText(sb.toString())
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            waitFor(vm, 120_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null) || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val a = vm.state.value.open!!.analysis
            assertEquals(2, a.parts.size)
            // Y up in the file, so the 300 mm side stands up.
            assertEquals(300.0, a.bounds.extents.z, 0.5)
            println("SMOKE obj ok: ${a.parts.size} parts, ${a.overallSize}")
        }
    }
}
