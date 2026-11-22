package io.github.emotionalleaves.splitziner

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
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

    @Test fun openDrawGuideAndExport() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "smoke_cabinet.stl")
        file.outputStream().use { StlWriter.writeBinary(cabinet(), it, "smoke test cabinet") }
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            rule.waitUntil(180_000) { vm.state.value.open != null || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val open = vm.state.value.open!!
            assertEquals(7, open.analysis.parts.size)
            println("SMOKE parts=${open.analysis.parts.size} items=${open.analysis.cutList.rows.size}")

            fun tap(label: String) {
                rule.waitUntil(30_000) { rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                rule.onAllNodesWithText(label).onFirst().performClick()
                rule.waitForIdle()
            }
            // If the app asks about units, accept what it guessed.
            if (rule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()) tap("Cancel")

            // The 3D view first: let it draw some frames on the graphics thread.
            tap("3D"); Thread.sleep(3000)
            tap("Parts"); tap("Cut list")
            tap("Drawing")
            rule.waitUntil(240_000) { vm.state.value.drawing != null && !vm.state.value.drawingBusy }
            val drawing = vm.state.value.drawing!!
            // Assembly sheet, exploded sheet, and one detail sheet per item.
            assertTrue("sheets: ${drawing.sheets.size}", drawing.sheets.size >= 2 + open.analysis.cutList.rows.size)
            val pdf = PdfExporter.write(ctx, drawing.sheets, "smoke_drawing.pdf")
            assertTrue("pdf ${pdf.length()}", pdf.length() > 5_000)
            val (png, dpi) = ImageExporter.writePng(ctx, drawing.sheets[0], "smoke_sheet.png")
            assertTrue("png ${png.length()} at $dpi", png.length() > 5_000)
            println("SMOKE drawing sheets=${drawing.sheets.size} pdf=${pdf.length()} png=${png.length()}@$dpi")

            tap("Guide")
            rule.waitUntil(240_000) { vm.state.value.guide != null && !vm.state.value.guideBusy }
            val guide = vm.state.value.guide!!
            assertTrue(guide.stepCount >= 2)
            assertTrue(PdfExporter.write(ctx, guide.pages, "smoke_guide.pdf").length() > 5_000)
            println("SMOKE guide steps=${guide.stepCount} pages=${guide.pages.size}")

            // Back to 3D with the pull-apart offsets ready, then give it time to fail if it is going to.
            vm.ensureExplodeOffsets()
            rule.waitUntil(120_000) { vm.state.value.explodeOffsets != null }
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
            rule.waitUntil(240_000) { settled() || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            assertEquals(33, vm.state.value.open!!.analysis.parts.size)
            fun tap(label: String) {
                rule.waitUntil(30_000) { rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                rule.onAllNodesWithText(label).onFirst().performClick()
                rule.waitForIdle()
            }
            if (rule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()) tap("Cancel")

            tap("Check")
            rule.waitUntil(240_000) { vm.state.value.check != null }
            val before = vm.state.value.check!!.problems
            assertTrue("problems before: $before", before >= 5)

            vm.planFixes()
            rule.waitUntil(240_000) { vm.state.value.fixPlan != null || vm.state.value.error != null }
            val plan = vm.state.value.fixPlan!!
            assertTrue("changes: ${plan.edits.size}", plan.edits.size >= 5)
            // The proposal is on screen; accept it the way a person would.
            tap("Apply ${plan.edits.size} changes")
            rule.waitUntil(240_000) { settled() && vm.state.value.open!!.meta.edits.isNotEmpty() }
            vm.ensureCheck()
            rule.waitUntil(240_000) { vm.state.value.check != null }
            val after = vm.state.value.check!!.problems
            println("SMOKE desk problems $before -> $after after ${plan.edits.size} changes; left ${plan.left.size}")
            assertTrue("problems $before -> $after", after < before)

            tap("Drawing")
            rule.waitUntil(300_000) { vm.state.value.drawing != null && !vm.state.value.drawingBusy }
            val drawing = vm.state.value.drawing!!
            assertTrue(PdfExporter.write(ctx, drawing.sheets, "smoke_desk.pdf").length() > 20_000)
            tap("Guide")
            rule.waitUntil(300_000) { vm.state.value.guide != null && !vm.state.value.guideBusy }
            tap("3D"); Thread.sleep(3000)
            println("SMOKE desk sheets=${drawing.sheets.size} guide pages=${vm.state.value.guide!!.pages.size}")
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNull(Diagnostics.lastCrash())
            assertNull(vm.state.value.error)
        }
    }
}
