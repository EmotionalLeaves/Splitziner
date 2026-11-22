package io.github.emotionalleaves.splitziner

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
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
            shot("c04_drawing")
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
            shot("c05_guide")
            assertTrue(guide.stepCount >= 2)
            assertTrue(PdfExporter.write(ctx, guide.pages, "smoke_guide.pdf").length() > 5_000)
            println("SMOKE guide steps=${guide.stepCount} pages=${guide.pages.size}")

            // Dialogs people use, photographed for review: the cutting plan and the tools list.
            runCatching {
                tap("Cut list"); tap("Cutting plan"); shot("c06_cutplan"); tap("Cancel")
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
                tap("Build steps"); rule.waitUntil(60_000) { rule.onAllNodesWithText("Step 1 of", substring = true).fetchSemanticsNodes().isNotEmpty() }
                repeat(6) { tap("\u2192") }; Thread.sleep(1500); shot("d06_build_steps"); tap("Show all")
            }.onFailure { println("SMOKE build steps failed: ${it.message}") }
            println("SMOKE desk sheets=${drawing.sheets.size} guide pages=${vm.state.value.guide!!.pages.size}")
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNull(Diagnostics.lastCrash())
            assertNull(vm.state.value.error)
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
            tap("New design"); tap("Bookcase"); shot("n01_new_design")
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
                Thread.sleep(1500)
                rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.45f, height * 0.42f)) }
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
            for ((key, values) in listOf("cabinet" to mapOf("doors" to 2.0, "overlay" to 1.0), "shelves" to mapOf("pins" to 1.0))) {
                val t = splitziner.edit.Templates.byKey(key)
                vm.newDesign(key, splitziner.edit.Templates.defaults(t, false) + values)
                waitFor(vm, 120_000) { (vm.state.value.open != null && vm.state.value.busyLabel == null && vm.state.value.open!!.meta.name.isNotEmpty()) || vm.state.value.error != null }
                assertNull(vm.state.value.error)
                val a = vm.state.value.open!!.analysis
                val doors = splitziner.check.Doors.find(a.parts).size; val pins = splitziner.check.ShelfPins.find(a.parts).size
                val hw = vm.hardwareFor(vm.state.value.open!!)
                println("SMOKE $key doors=$doors pinned shelves=$pins hardware=$hw")
                rule.waitUntil(30_000) { rule.onAllNodesWithText("Guide").fetchSemanticsNodes().isNotEmpty() }
                rule.onAllNodesWithText("Guide").onFirst().performClick(); rule.waitForIdle()
                waitFor(vm, 120_000) { vm.state.value.guide != null && !vm.state.value.guideBusy }
                shot("d0${if (key == "cabinet") 1 else 2}_${key}_guide")
                if (key == "cabinet") assertEquals(2, doors) else assertEquals(4, pins)
                vm.closeProject(); rule.waitForIdle()
            }
            assertNull(Diagnostics.lastCrash())
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
