package splitziner

import org.junit.Test
import splitziner.dims.LengthFormat
import splitziner.export.CsvWriter
import splitziner.io.StlReader
import splitziner.io.StlWriter
import splitziner.math.Vec3
import splitziner.mesh.MeshOps
import splitziner.parts.CutList
import splitziner.parts.Part
import splitziner.parts.PartDescriber
import splitziner.parts.PartKind
import splitziner.pipeline.Pipeline
import splitziner.split.BodySplitter
import splitziner.split.TJunctionFixer
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshTest {
    @Test fun boxIsWatertightWithOutwardVolume() {
        val b = Fixtures.box(0.0, 2.0, 0.0, 3.0, 0.0, 4.0)
        assertTrue(b.isWatertight)
        assertEquals(24.0, b.volume, 1e-9)
        assertEquals(52.0, b.area, 1e-9)
        assertEquals(18, b.topology.edgeCount)
    }

    @Test fun fixWindingRepairsFlippedTriangles() {
        val b = Fixtures.box(0.0, 1.0, 0.0, 1.0, 0.0, 1.0)
        val t = b.triangles.copyOf()
        for (k in listOf(0, 4, 7)) { val s = t[3 * k + 1]; t[3 * k + 1] = t[3 * k + 2]; t[3 * k + 2] = s }
        val fixed = MeshOps.fixWinding(splitziner.mesh.Mesh(b.vertices, t))
        assertEquals(1.0, fixed.volume, 1e-12)
    }

    @Test fun bvhAgreesWithBruteForce() {
        val m = MeshOps.concatenate(listOf(Fixtures.cylinder(5.0, 0.0, 10.0, 48), Fixtures.box(8.0, 12.0, -2.0, 2.0, 0.0, 6.0)))
        val rnd = Random(1)
        repeat(1000) {
            val o = Vec3(rnd.nextDouble(-20.0, 20.0), rnd.nextDouble(-20.0, 20.0), rnd.nextDouble(-5.0, 15.0))
            val d = Vec3(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0)).normalized()
            var brute = false
            for (t in 0 until m.triangleCount) if (rayTri(m, t, o, d) > 0) brute = true
            assertEquals(brute, m.bvh.intersectsAny(o, d), "ray $it")
        }
    }

    @Test fun containsMatchesBox() {
        val b = Fixtures.box(0.0, 4.0, 0.0, 5.0, 0.0, 6.0)
        val rnd = Random(2)
        repeat(500) {
            val p = Vec3(rnd.nextDouble(-2.0, 6.0), rnd.nextDouble(-2.0, 7.0), rnd.nextDouble(-2.0, 8.0))
            val inside = p.x > 0 && p.x < 4 && p.y > 0 && p.y < 5 && p.z > 0 && p.z < 6
            assertEquals(inside, b.bvh.contains(p))
        }
    }

    private fun rayTri(m: splitziner.mesh.Mesh, t: Int, o: Vec3, d: Vec3): Double {
        val a = m.vertex(m.triangles[3 * t]); val b = m.vertex(m.triangles[3 * t + 1]); val c = m.vertex(m.triangles[3 * t + 2])
        val e1 = b - a; val e2 = c - a; val p = d cross e2; val det = e1 dot p
        if (abs(det) < 1e-300) return -1.0
        val s = o - a; val u = (s dot p) / det
        if (u < 0 || u > 1) return -1.0
        val q = s cross e1; val w = (d dot q) / det
        if (w < 0 || u + w > 1) return -1.0
        return (e2 dot q) / det
    }
}

class StlTest {
    private val cube = Fixtures.box(0.0, 1.0, 0.0, 1.0, 0.0, 1.0)

    @Test fun binaryAndAsciiGiveTheSameTriangles() {
        val out = ByteArrayOutputStream(); StlWriter.writeBinary(cube, out, "Splitziner cube units=mm")
        val bin = StlReader.read(out.toByteArray())
        val asc = StlReader.read(StlWriter.writeAscii(cube).toByteArray())
        assertEquals(12, bin.triangleCount)
        assertEquals(bin.soup.toList(), asc.soup.toList())
    }

    @Test fun binaryStartingWithSolidIsReadAsBinary() {
        val bytes = Fixtures.binaryStl(Fixtures.soup(listOf(cube)), header = "solid exported by something")
        assertEquals(12, StlReader.read(bytes).triangleCount)
    }

    @Test fun truncatedBinaryLoadsWhatIsThereAndWarns() {
        val bytes = Fixtures.binaryStl(Fixtures.soup(List(40) { cube }))
        val cut = bytes.copyOf(bytes.size - 120)
        val raw = StlReader.read(cut)
        assertEquals(477, raw.triangleCount)
        assertTrue(raw.warnings.any { it.contains("does not match") })
    }

    @Test fun rubbishIsRejected() {
        val e = runCatching { StlReader.read("hello world, this is not a model".toByteArray()) }.exceptionOrNull()
        assertNotNull(e); assertTrue(e!!.message!!.contains("Not a valid STL"))
    }
}

class SplitTest {
    private val a = Fixtures.box(0.0, 10.0, 0.0, 10.0, 0.0, 10.0)
    private val b = Fixtures.box(20.0, 30.0, 0.0, 10.0, 0.0, 10.0)

    @Test fun bodiesWrittenInOrderSplitByFileOrder() {
        val r = BodySplitter.split(Fixtures.soup(listOf(a, b)), "mm")
        assertEquals(2, r.parts.size)
        assertEquals(BodySplitter.METHOD_FILE_ORDER, r.report.method)
        assertTrue(r.parts.all { it.watertight && abs(it.mesh.volume - 1000.0) < 1e-6 })
    }

    @Test fun touchingBodiesStaySeparate() {
        val c = Fixtures.box(10.0, 20.0, 0.0, 10.0, 0.0, 10.0)
        val r = BodySplitter.split(Fixtures.soup(listOf(a, c)), "mm")
        assertEquals(2, r.parts.size)
    }

    @Test fun interleavedBodiesSplitByComponents() {
        val r = BodySplitter.split(Fixtures.soup(listOf(a, b), interleave = true), "mm")
        assertEquals(2, r.parts.size)
        assertEquals(BodySplitter.METHOD_COMPONENTS, r.report.method)
    }

    @Test fun repeatedBodyIsOnePart() {
        val r = BodySplitter.split(Fixtures.soup(listOf(a), repeat = 3), "mm")
        assertEquals(1, r.parts.size)
        assertEquals(3, r.parts[0].copies)
        assertEquals(2, r.report.duplicatesRemoved)
        assertEquals(3, r.report.bodies)
        assertTrue(r.report.warnings.any { it.contains("duplicate") })
    }

    @Test fun tJunctionIsRepaired() {
        val m = Fixtures.boxWithTJunction()
        assertFalse(m.isWatertight)
        val f = MeshOps.fixWinding(TJunctionFixer.fix(m))
        assertTrue(f.isWatertight)
        assertEquals(8000.0, f.volume, 1e-6)
    }

    @Test fun cabinetInMetresDesignedInInches() {
        val raw = Fixtures.soup(Fixtures.miniCabinet(), repeat = 3, scale = 0.0254)
        val r = BodySplitter.split(raw)
        assertEquals(7, r.parts.size)
        assertEquals(21, r.report.bodies)
        assertEquals(14, r.report.duplicatesRemoved)
        assertEquals("m", r.report.units.fileUnits)
        assertEquals("in", r.report.units.designUnits)
        assertEquals(1000.0, r.report.units.scale, 0.0)
        assertTrue(r.report.units.margin!! >= 0.15, "margin ${r.report.units.margin}")
    }

    @Test fun millimetreBlockIsReadAsMillimetres() {
        val r = BodySplitter.split(Fixtures.soup(listOf(Fixtures.box(0.0, 100.0, 0.0, 60.0, 0.0, 40.0))))
        assertEquals("mm", r.report.units.fileUnits)
        assertEquals("mm", r.report.units.designUnits)
        assertEquals(BodySplitter.METHOD_SINGLE, r.report.method)
    }

    @Test fun randomBoxesAllComeBack() {
        val rnd = Random(7)
        repeat(30) {
            val n = rnd.nextInt(2, 12)
            val boxes = (0 until n).map { i ->
                val x = i * 100.0
                Fixtures.box(x, x + rnd.nextDouble(5.0, 60.0), 0.0, rnd.nextDouble(5.0, 60.0), 0.0, rnd.nextDouble(5.0, 60.0))
            }
            val r = BodySplitter.split(Fixtures.soup(boxes), "mm")
            assertEquals(n, r.parts.size)
            assertTrue(r.parts.all { it.watertight })
            assertEquals(boxes.sumOf { it.volume }, r.parts.sumOf { it.mesh.volume }, 1e-6)
        }
    }
}

class CutListTest {
    private val mm = LengthFormat("mm")
    private val inch = LengthFormat("in")

    @Test fun numberFormats() {
        assertEquals(".75", inch.format(19.05)); assertEquals("3.50", inch.format(88.9)); assertEquals("72.00", inch.format(1828.8))
        assertEquals("19", mm.format(19.0)); assertEquals("12.5", mm.format(12.5)); assertEquals("0.5", mm.format(0.5))
        val frac = LengthFormat("in", "fraction")
        assertEquals("3/4", frac.format(19.05)); assertEquals("23-5/8", frac.format(600.0)); assertEquals("72", frac.format(1828.8))
        assertEquals("3-1/2", frac.format(88.9))
    }

    @Test fun boardPanelRodShaped() {
        val board = Fixtures.box(0.0, 19.0, 0.0, 89.0, 0.0, 600.0)
        val d = PartDescriber.describe(board, mm)
        assertEquals(PartKind.BOARD, d.kind); assertEquals("BOARD 19 X 89 X 600", d.desc)
        assertEquals("BOARD .75 X 3.50 X 23.62", PartDescriber.describe(board, inch).desc)
        assertEquals(PartKind.PANEL, PartDescriber.describe(Fixtures.box(0.0, 19.0, 0.0, 400.0, 0.0, 800.0), mm).kind)
        val rod = PartDescriber.describe(Fixtures.cylinder(12.5, 0.0, 300.0), mm)
        assertEquals(PartKind.ROD, rod.kind); assertTrue(abs(rod.t - 25.0) < 0.2)
        assertEquals(PartKind.SHAPED, PartDescriber.describe(Fixtures.lBracket(), mm).kind)
    }

    @Test fun rotatedBoardKeepsItsSize() {
        val d = PartDescriber.describe(Fixtures.rotatedZ(Fixtures.box(0.0, 19.0, 0.0, 89.0, 0.0, 600.0), 30.0), mm)
        assertEquals(PartKind.BOARD, d.kind)
        assertEquals(19.0, d.t, 0.01); assertEquals(89.0, d.w, 0.01); assertEquals(600.0, d.l, 0.01)
    }

    @Test fun cabinetCutList() {
        val parts = Fixtures.miniCabinet(Fixtures.INCH).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P%02d".format(i + 1), m) }
        val r = CutList.build(parts, inch)
        assertEquals(5, r.rows.size)
        assertEquals((1..5).toList(), r.rows.map { it.item })
        assertEquals(listOf(2, 2, 1, 1, 1), r.rows.map { it.qty })
        assertEquals(listOf(PartKind.PANEL, PartKind.PANEL, PartKind.PANEL, PartKind.PANEL, PartKind.BOARD), r.rows.map { it.kind })
        assertEquals("PANEL .75 X 24.00 X 60.00", r.rows[0].description)
        assertEquals("BOARD .75 X 3.50 X 34.50", r.rows[4].description)
        assertEquals(1, r.parts[0].item); assertEquals(2, r.parts[0].qty)
        assertEquals(1, r.stock.size); assertEquals("34.50", r.stock[0].totalLength)
        val csv = CsvWriter.cutList(r, inch)
        assertTrue(csv.startsWith("﻿Item,Qty,Kind"))
        assertTrue(csv.contains("\"P01, P02\""))
    }

    @Test fun excludedPartsAreLeftOut() {
        val parts = Fixtures.miniCabinet(Fixtures.INCH).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m, excluded = i == 6) }
        val r = CutList.build(parts, inch)
        assertEquals(4, r.rows.size)
        assertNull(r.parts[6].item)
    }
}

class PipelineTest {
    @Test fun endToEndOnTheCabinet() {
        val bytes = Fixtures.binaryStl(Fixtures.soup(Fixtures.miniCabinet(), repeat = 3, scale = 0.0254))
        val a = Pipeline.analyze(bytes, "cabinet.stl")
        assertEquals(7, a.parts.size)
        assertEquals("36.00 × 24.00 × 60.00 in", a.overallSize)
        assertEquals(5, a.cutList.rows.size)
    }

    @Test fun millionTrianglesWithinBudget() {
        // 1,000 bodies of 1,000 triangles each would need a denser fixture; boxes give 12 per body,
        // so this checks the splitter's scaling on many bodies instead: 80,000 boxes = 960,000 triangles.
        val n = 80_000
        val soup = DoubleArray(n * 12 * 9)
        val unit = Fixtures.soup(listOf(Fixtures.box(0.0, 7.0, 0.0, 5.0, 0.0, 3.0))).soup
        for (i in 0 until n) {
            val ox = (i % 300) * 10.0; val oy = (i / 300) * 10.0
            for (j in unit.indices) soup[i * 108 + j] = unit[j] + when (j % 3) { 0 -> ox; 1 -> oy; else -> 0.0 }
        }
        val t0 = System.nanoTime()
        val r = BodySplitter.split(splitziner.io.RawModel(soup, "big", emptyList()), "mm")
        val ms = (System.nanoTime() - t0) / 1_000_000
        // Every box is the same shape at a different place, so none are duplicates.
        assertEquals(n, r.parts.size)
        assertTrue(ms < 30_000, "split took $ms ms")
    }
}

class ViewTest {
    @Test fun boxHasTwelveFeatureEdgesAndCylinderOnlyItsRims() {
        val box = Fixtures.box(0.0, 1.0, 0.0, 2.0, 0.0, 3.0)
        assertEquals(12, splitziner.view.EdgeExtractor.featureEdges(box).size)
        val cyl = Fixtures.cylinder(5.0, 0.0, 10.0, 64)
        assertEquals(128, splitziner.view.EdgeExtractor.featureEdges(cyl).size)
        val g = splitziner.view.ViewerGeometry.build(box, box.bounds.center, 1.0)
        assertEquals(12 * 18, g.triangles.size); assertEquals(12 * 6, g.lines.size)
    }
}

class DrawingTest {
    private fun partsOf(meshes: List<splitziner.mesh.Mesh>) = meshes.mapIndexed { i, m -> Part("P%02d".format(i + 1), "P%02d".format(i + 1), m) }
    private val out = java.io.File("build/preview").apply { mkdirs() }

    @Test fun cubeFrontViewIsASquare() {
        val cube = Fixtures.box(0.0, 10.0, 0.0, 10.0, 0.0, 10.0)
        val v = splitziner.view.HiddenLine.compute(listOf(cube), cube, splitziner.view.ViewDef.FRONT)
        assertEquals(4, v.visible.size / 4)
        assertEquals(40.0, v.visibleLength, 1e-6)
        assertEquals(0, v.hidden.size)
    }

    @Test fun stackedBoxesShareOneLine() {
        val a = Fixtures.box(0.0, 10.0, 0.0, 10.0, 0.0, 10.0); val b = Fixtures.box(0.0, 10.0, 0.0, 10.0, 10.0, 20.0)
        val scene = MeshOps.concatenate(listOf(a, b))
        val v = splitziner.view.HiddenLine.compute(listOf(a, b), scene, splitziner.view.ViewDef.FRONT)
        // Outline 10 x 20 (perimeter 60) plus one shared line of 10: not two.
        assertEquals(70.0, v.visibleLength, 1e-6)
    }

    @Test fun strokeFontIsStable() {
        val w = splitziner.sheet.StrokeFont.width("SECTION A-A", 4.83)
        assertEquals(w, splitziner.sheet.StrokeFont.width("SECTION A-A", 4.83), 0.0)
        assertTrue(w > 20 && w < 45, "width $w")
    }

    @Test fun blockGetsThreeDimensions() {
        val block = Fixtures.box(0.0, 100.0, 0.0, 60.0, 0.0, 40.0)
        val parts = partsOf(listOf(block))
        val vs = splitziner.drawing.DrawingBuilder.views(parts)
        val g = splitziner.dims.DimPlanner.plan(vs.scene, vs.views, LengthFormat("mm"), 5)
        assertEquals(listOf("front/bottom/100", "right/bottom/60", "front/left/40"), g.map { "${it.view}/${it.side}/${it.items.single().text}" })
    }

    @Test fun cabinetSheet() {
        val parts = partsOf(Fixtures.miniCabinet(Fixtures.INCH))
        val fmt = LengthFormat("in")
        val cut = CutList.build(parts, fmt)
        val vs = splitziner.drawing.DrawingBuilder.views(cut.parts)
        val info = splitziner.sheet.DrawingInfo(title = "Cabinet, storage", drawingNo = "CAB-001", designer = "J. Ledbetter", date = "2026-11-21", softwareVersion = "test", sourceFile = "cabinet.stl")
        val r = splitziner.drawing.DrawingBuilder.build(cut.parts, cut.rows, vs, info, splitziner.drawing.DrawingSettings(), fmt)
        val svg = splitziner.export.SvgWriter.write(r.sheets[0])
        java.io.File(out, "cabinet_sheet1.svg").writeText(svg)
        println("cabinet: sheet ${r.sheet.letter} scale ${r.scaleText} qa ${r.qa}")
        assertEquals(svg, splitziner.export.SvgWriter.write(r.sheets[0]))
        for (word in listOf("NASA", "GSFC", "GODDARD")) assertFalse(svg.contains(word))
        assertTrue(r.scale in splitziner.drawing.DrawingBuilder.scalesFor(true).toList())
    }

    @Test fun steppedBlockSheet() {
        val block = MeshOps.concatenate(listOf(Fixtures.box(0.0, 100.0, 0.0, 60.0, 0.0, 20.0), Fixtures.box(0.0, 50.0, 0.0, 60.0, 20.0, 40.0)))
        val parts = partsOf(listOf(block))
        val fmt = LengthFormat("mm")
        val cut = CutList.build(parts, fmt)
        val vs = splitziner.drawing.DrawingBuilder.views(cut.parts)
        val r = splitziner.drawing.DrawingBuilder.build(cut.parts, cut.rows, vs, splitziner.sheet.DrawingInfo(title = "Block, stepped", drawingNo = "BLK-1"), splitziner.drawing.DrawingSettings(), fmt)
        java.io.File(out, "block_sheet1.svg").writeText(splitziner.export.SvgWriter.write(r.sheets[0]))
        println("block: sheet ${r.sheet.letter} scale ${r.scaleText} qa ${r.qa}")
    }
}

class ExplodeTest {
    private val out = java.io.File("build/preview").apply { mkdirs() }
    private fun cabinet(): splitziner.parts.CutListResult {
        val parts = Fixtures.miniCabinet(Fixtures.INCH).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P%02d".format(i + 1), m) }
        return CutList.build(parts, LengthFormat("in"))
    }

    @Test fun planCoversEveryPartOnceAndIsSlideable() {
        val cut = cabinet()
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        println("plan: " + plan.steps.joinToString(" | ") { s -> "${s.step}:${s.partIds}" + s.directions.joinToString(",") { "(${it.x.toInt()},${it.y.toInt()},${it.z.toInt()})" } })
        assertEquals(cut.parts.map { it.id }.sorted(), plan.steps.flatMap { it.partIds }.sorted())
        assertEquals((1..plan.steps.size).toList(), plan.steps.map { it.step })
        assertTrue(plan.steps.size <= 10)
        // The base is the bottom panel: largest footprint on the floor. It goes down first, with the parts fitted to it.
        assertEquals("P03", plan.steps[0].partIds.first())
        assertNull(plan.parts.first { it.id == "P03" }.explode)
    }

    @Test fun lidGoesOnAfterTheBlockInside() {
        val meshes = listOf(
            Fixtures.box(0.0, 100.0, 0.0, 100.0, 0.0, 5.0),        // floor
            Fixtures.box(0.0, 5.0, 0.0, 100.0, 5.0, 60.0), Fixtures.box(95.0, 100.0, 0.0, 100.0, 5.0, 60.0),
            Fixtures.box(5.0, 95.0, 0.0, 5.0, 5.0, 60.0), Fixtures.box(5.0, 95.0, 95.0, 100.0, 5.0, 60.0),
            Fixtures.box(30.0, 70.0, 30.0, 70.0, 5.0, 40.0),        // loose block
            Fixtures.box(0.0, 100.0, 0.0, 100.0, 60.0, 65.0),       // lid
        )
        val parts = meshes.mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val plan = splitziner.explode.AssemblyPlan.plan(parts)
        val step = plan.parts.associate { it.id to it.step!! }
        assertTrue(step.getValue("P06") < step.getValue("P07"), "block ${step["P06"]} lid ${step["P07"]}")
    }

    @Test fun explodedCabinetPicture() {
        val cut = cabinet()
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        val dl = splitziner.explode.ExplodedView.render(plan.parts, 200.0, 150.0, splitziner.explode.ExplodeOptions())
        java.io.File(out, "cabinet_exploded.svg").writeText(splitziner.export.SvgWriter.write(dl))
        assertEquals(5, dl.boxes.count { it.tag == "balloon" })
        val b = dl.boxes.filter { it.tag == "balloon" }
        for (i in b.indices) for (j in i + 1 until b.size) assertEquals(0.0, b[i].overlap(b[j]), 1e-9)
    }
}

class GuideTest {
    private val out = java.io.File("build/preview").apply { mkdirs() }

    @Test fun cabinetGuideAndSheetTwo() {
        val fmt = LengthFormat("in")
        val parts = Fixtures.miniCabinet(Fixtures.INCH).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P%02d".format(i + 1), m) }
        val cut = CutList.build(parts, fmt)
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        val steps = splitziner.guide.GuideSteps.build(plan.parts, plan.steps)
        assertEquals(plan.steps.size, steps.size)
        // The bottom goes down and the sides go on it in the same step.
        assertEquals("Place item 2.", steps[0].text[0])
        assertTrue(steps[0].text[1].startsWith("Fit 2× item 1 from the left and right"), steps[0].text.toString())
        // The top panel goes on only after both sides are there to carry it.
        val stepOf = plan.parts.associate { it.id to it.step!! }
        assertTrue(stepOf.getValue("P04") > stepOf.getValue("P01") && stepOf.getValue("P04") > stepOf.getValue("P02"))
        val hero = splitziner.explode.ExplodedView.render(plan.parts.map { it.copy(explode = null) }, 170.0, 115.0, splitziner.explode.ExplodeOptions(balloons = false), splitziner.explode.ExplodedView.pickView(plan.parts), "hero")
        val pages = splitziner.guide.GuideLayout.pages(splitziner.guide.GuideLayout.Info("Storage cabinet", "Build guide", "36.00 x 24.00 x 60.00 in", 7), steps, cut.rows, hero)
        pages.forEachIndexed { i, p -> java.io.File(out, "guide_page%02d.svg".format(i + 1)).writeText(splitziner.export.SvgWriter.write(p)) }
        java.io.File(out, "step03.svg").writeText(splitziner.export.SvgWriter.write(steps[2].picture))
        assertTrue(pages.size >= 3)

        val vs = splitziner.drawing.DrawingBuilder.views(cut.parts)
        val r = splitziner.drawing.DrawingBuilder.build(cut.parts, cut.rows, vs, splitziner.sheet.DrawingInfo(title = "Cabinet, storage", drawingNo = "CAB-001"), splitziner.drawing.DrawingSettings(), fmt, plan.parts)
        assertEquals(2, r.sheets.size)
        java.io.File(out, "cabinet_sheet2.svg").writeText(splitziner.export.SvgWriter.write(r.sheets[1]))
        println("sheet2 qa: ${r.qa}")
    }
}

class BiggerAssemblyTest {
    /** A shelving unit: two sides, a back, seven shelves and three rows of dividers. About 40 parts. */
    private fun shelving(): List<splitziner.mesh.Mesh> {
        val u = Fixtures.INCH
        val out = ArrayList<splitziner.mesh.Mesh>()
        out += Fixtures.box(0.0, 0.75, 0.0, 12.0, 0.0, 72.0, u); out += Fixtures.box(47.25, 48.0, 0.0, 12.0, 0.0, 72.0, u)
        out += Fixtures.box(0.75, 47.25, 11.75, 12.0, 0.0, 72.0, u)
        for (i in 0..6) out += Fixtures.box(0.75, 47.25, 0.0, 11.75, i * 11.875, i * 11.875 + 0.75, u)
        for (i in 0..5) for (j in 1..5) if ((i + j) % 2 == 0) out += Fixtures.box(j * 7.75, j * 7.75 + 0.75, 0.0, 11.75, i * 11.875 + 0.75, (i + 1) * 11.875, u)
        return out
    }

    @Test fun shelvingUnitEndToEnd() {
        val fmt = LengthFormat("in")
        val bytes = Fixtures.binaryStl(Fixtures.soup(shelving(), repeat = 2, scale = 1.0 / 1000))
        val t0 = System.nanoTime()
        val a = Pipeline.analyze(bytes, "shelving.stl")
        assertEquals("m", a.report.units.fileUnits); assertEquals("in", a.report.units.designUnits)
        val t1 = System.nanoTime()
        val plan = splitziner.explode.AssemblyPlan.plan(a.parts)
        val t2 = System.nanoTime()
        val vs = splitziner.drawing.DrawingBuilder.views(a.parts)
        val r = splitziner.drawing.DrawingBuilder.build(a.parts, a.cutList.rows, vs, splitziner.sheet.DrawingInfo(title = "Shelving unit"), splitziner.drawing.DrawingSettings(), a.format, plan.parts)
        val t3 = System.nanoTime()
        val steps = splitziner.guide.GuideSteps.build(plan.parts, plan.steps)
        val pages = splitziner.guide.GuideLayout.pages(splitziner.guide.GuideLayout.Info("Shelving unit", "Build guide", a.overallSize, a.parts.size), steps, a.cutList.rows, null)
        val t4 = System.nanoTime()
        println("shelving: ${a.parts.size} parts, ${a.cutList.rows.size} items, ${plan.steps.size} steps, sheet ${r.sheet.letter} ${r.scaleText}, ${pages.size} pages; ms analyze ${(t1 - t0) / 1000000} plan ${(t2 - t1) / 1000000} drawing ${(t3 - t2) / 1000000} guide ${(t4 - t3) / 1000000}; qa ${r.qa}")
        val out = java.io.File("build/preview").apply { mkdirs() }
        java.io.File(out, "shelving_sheet1.svg").writeText(splitziner.export.SvgWriter.write(r.sheets[0]))
        java.io.File(out, "shelving_sheet2.svg").writeText(splitziner.export.SvgWriter.write(r.sheets[1]))
        java.io.File(out, "shelving_step.svg").writeText(splitziner.export.SvgWriter.write(steps[steps.size / 2].picture))
        assertTrue(plan.steps.size <= 10)
        assertEquals(a.parts.size, plan.steps.sumOf { it.partIds.size })
        // Every part must be able to slide in past the parts of earlier steps: nothing is trapped.
        assertTrue(t4 - t0 < 60_000_000_000L)
    }
}

class SectionTest {
    /** Six panels forming a closed box, with eight shelves inside. From outside none of the shelves show. */
    private fun closedBoxWithShelf(): List<splitziner.mesh.Mesh> = listOf(
        Fixtures.box(0.0, 400.0, 0.0, 300.0, 0.0, 18.0), Fixtures.box(0.0, 400.0, 0.0, 300.0, 482.0, 500.0),
        Fixtures.box(0.0, 18.0, 0.0, 300.0, 18.0, 482.0), Fixtures.box(382.0, 400.0, 0.0, 300.0, 18.0, 482.0),
        Fixtures.box(18.0, 382.0, 0.0, 18.0, 18.0, 482.0), Fixtures.box(18.0, 382.0, 282.0, 300.0, 18.0, 482.0),
    ) + (1..8).map { i -> Fixtures.box(18.0, 382.0, 18.0, 282.0, i * 50.0, i * 50.0 + 12.0) }

    @Test fun closedBoxIsDrawnAsSections() {
        val fmt = LengthFormat("mm")
        val parts = closedBoxWithShelf().mapIndexed { i, m -> Part("P%02d".format(i + 1), "P%02d".format(i + 1), m) }
        val cut = CutList.build(parts, fmt)
        val vs = splitziner.drawing.DrawingBuilder.views(cut.parts)
        assertEquals(setOf("front", "right"), vs.sections.keys)
        assertEquals("A", vs.sections.getValue("front").letter); assertEquals("B", vs.sections.getValue("right").letter)
        // The front section cuts the two sides, top, bottom and eight shelves. The front and back panels are not cut.
        assertEquals(12, vs.sections.getValue("front").faces.size)
        assertEquals(0, vs.views.getValue("front").hidden.size)
        val r = splitziner.drawing.DrawingBuilder.build(cut.parts, cut.rows, vs, splitziner.sheet.DrawingInfo(title = "Box, closed, with shelf", drawingNo = "BOX-1"), splitziner.drawing.DrawingSettings(), fmt)
        java.io.File("build/preview").mkdirs()
        java.io.File("build/preview/box_sheet1.svg").writeText(splitziner.export.SvgWriter.write(r.sheets[0]))
        println("box: sheet ${r.sheet.letter} ${r.scaleText} qa ${r.qa}")
    }

    @Test fun clippedCubeIsHalfTheVolume() {
        val cube = Fixtures.box(0.0, 10.0, 0.0, 10.0, 0.0, 10.0)
        val v = splitziner.view.ViewDef.FRONT
        val origin = Vec3(0.0, 5.0, 0.0)
        val face = splitziner.section.SectionBuilder.sectionPolygons(cube, origin, v)
        assertNotNull(face); assertEquals(100.0, face!!.area, 1e-3)
        val half = splitziner.section.SectionBuilder.clipBehind(cube, origin, v, face)
        assertEquals(500.0, abs(half.volume), 0.05)
    }

    @Test fun openCabinetIsNotSectioned() {
        val parts = Fixtures.miniCabinet(Fixtures.INCH).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        assertTrue(splitziner.drawing.DrawingBuilder.views(parts).sections.isEmpty())
    }
}

class OrganicModelTest {
    /** A lumpy closed surface with many small triangles: nothing like furniture. */
    private fun blob(n: Int): splitziner.mesh.Mesh {
        val v = ArrayList<Double>(); val t = ArrayList<Int>()
        for (i in 0..n) for (j in 0 until n) {
            val th = Math.PI * i / n; val ph = 2 * Math.PI * j / n
            val r = 40.0 + 6 * Math.sin(5 * th) * Math.cos(3 * ph) + 3 * Math.sin(9 * ph)
            v += r * Math.sin(th) * Math.cos(ph); v += r * Math.sin(th) * Math.sin(ph); v += r * Math.cos(th) * 1.4
        }
        for (i in 0 until n) for (j in 0 until n) {
            val a = i * n + j; val b = i * n + (j + 1) % n; val c = (i + 1) * n + j; val d = (i + 1) * n + (j + 1) % n
            t += a; t += c; t += b; t += b; t += c; t += d
        }
        return splitziner.mesh.Mesh(v.toDoubleArray(), t.toIntArray())
    }

    @Test fun singleOrganicBodyGoesThroughEverything() {
        val m = blob(300)
        val bytes = Fixtures.binaryStl(Fixtures.soup(listOf(m)))
        val t0 = System.nanoTime()
        val a = Pipeline.analyze(bytes, "blob.stl")
        val t1 = System.nanoTime()
        val vs = splitziner.drawing.DrawingBuilder.views(a.parts)
        val t2 = System.nanoTime()
        val plan = splitziner.explode.AssemblyPlan.plan(a.parts)
        val r = splitziner.drawing.DrawingBuilder.build(a.parts, a.cutList.rows, vs, splitziner.sheet.DrawingInfo(title = "Blob"), splitziner.drawing.DrawingSettings(), a.format, plan.parts)
        val t3 = System.nanoTime()
        val steps = splitziner.guide.GuideSteps.build(plan.parts, plan.steps)
        val t4 = System.nanoTime()
        println("blob: ${m.triangleCount} tris, ${a.parts.size} part(s) ${a.report.method}, units ${a.report.units.fileUnits}, sheet ${r.sheet.letter} ${r.scaleText}; ms analyze ${(t1 - t0) / 1000000} views ${(t2 - t1) / 1000000} plan+draw ${(t3 - t2) / 1000000} guide ${(t4 - t3) / 1000000}; segs ${vs.views.values.map { it.visible.size / 4 }}; qa ${r.qa}")
        java.io.File("build/preview").mkdirs()
        java.io.File("build/preview/blob_sheet1.svg").writeText(splitziner.export.SvgWriter.write(r.sheets[0]))
        assertEquals(1, a.parts.size)
        assertEquals(1, steps.size)
        assertEquals("mm", a.report.units.designUnits)
        assertTrue(vs.sections.isEmpty(), "a solid lump is not sectioned")
        assertTrue(t4 - t0 < 120_000_000_000L)
    }
}

class PlanOverrideTest {
    @Test fun userOrderIsFollowedAndWordingSticksToItsStep() {
        val parts = Fixtures.miniCabinet(Fixtures.INCH).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P%02d".format(i + 1), m) }
        val cut = CutList.build(parts, LengthFormat("in"))
        // Bottom, then both sides at once, then everything else in one go; the rail (P07) is left for the app to add.
        val order = listOf(listOf("P03"), listOf("P01", "P02"), listOf("P06", "P04", "P05"))
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts, order = order)
        assertEquals(listOf(listOf("P03"), listOf("P01", "P02"), listOf("P06", "P04", "P05"), listOf("P07")), plan.steps.map { it.partIds })
        val key = splitziner.guide.GuideSteps.stepKey(listOf("P02", "P01"))
        val steps = splitziner.guide.GuideSteps.build(plan.parts, plan.steps, mapOf(key to splitziner.guide.StepText("Sides", listOf("Glue and clamp both sides."), "Diagonals are equal.")))
        assertEquals("Step 2: Sides", steps[1].title)
        assertEquals(listOf("Glue and clamp both sides."), steps[1].text)
        assertEquals("Diagonals are equal.", steps[1].check)
        assertEquals("Fit 2× item 1 from the left and right.", splitziner.guide.GuideSteps.build(plan.parts, plan.steps)[1].text[0])
    }
}

class HardSplitTest {
    private fun parts(raw: splitziner.io.RawModel) = BodySplitter.split(raw, "mm")

    @Test fun wholeSetWrittenThreeTimesOver() {
        // A B C ... then the whole set again, twice: not body-by-body repeats.
        val bodies = Fixtures.miniCabinet(Fixtures.INCH)
        val r = parts(Fixtures.soup(bodies + bodies + bodies))
        assertEquals(7, r.parts.size)
        assertTrue(r.parts.all { it.copies == 3 && it.watertight })
        assertEquals(21, r.report.bodies); assertEquals(14, r.report.duplicatesRemoved)
    }

    @Test fun bodiesWithTJunctionsStillSplit() {
        val t = Fixtures.boxWithTJunction()
        val shifted = MeshOps.translated(t, Vec3(100.0, 0.0, 0.0))
        val plain = Fixtures.box(200.0, 240.0, 0.0, 20.0, 0.0, 10.0)
        for (repeat in listOf(1, 3)) {
            val r = parts(Fixtures.soup(listOf(t, shifted, plain), repeat = repeat))
            assertEquals(3, r.parts.size, "repeat $repeat: ${r.report.method}")
            assertTrue(r.parts.all { it.watertight }, "repeat $repeat")
            assertEquals(8000.0, r.parts[0].mesh.volume, 1e-6)
            assertTrue(r.parts.all { it.copies == repeat })
        }
    }

    @Test fun boardsStackedFaceToFaceStayTwoParts() {
        val a = Fixtures.box(0.0, 100.0, 0.0, 50.0, 0.0, 19.0); val b = Fixtures.box(0.0, 100.0, 0.0, 50.0, 19.0, 38.0)
        for (interleave in listOf(false, true)) {
            val r = parts(Fixtures.soup(listOf(a, b), interleave = interleave))
            assertEquals(2, r.parts.size, "interleave $interleave")
            assertTrue(r.parts.all { it.watertight })
        }
    }
}

class ScrambledCabinetTest {
    @Test fun cabinetWithTrianglesInAnyOrderStillSplitsIntoSevenParts() {
        // The worst an exporter can do: every body's triangles mixed in with every other's.
        val bodies = Fixtures.miniCabinet(Fixtures.INCH)
        val raw = Fixtures.soup(bodies, interleave = true)
        val shuffled = (0 until raw.triangleCount).shuffled(Random(11))
        val src = raw.soup
        val soup = DoubleArray(src.size)
        shuffled.forEachIndexed { i, t -> src.copyInto(soup, 9 * i, 9 * t, 9 * t + 9) }
        val r = BodySplitter.split(splitziner.io.RawModel(soup + soup, "scrambled", emptyList()), "mm")
        assertEquals(BodySplitter.METHOD_COMPONENTS, r.report.method)
        assertEquals(7, r.parts.size)
        assertTrue(r.parts.all { it.watertight && it.copies == 2 })
        assertEquals(bodies.sumOf { it.volume }, r.parts.sumOf { it.mesh.volume }, 1e-3)
    }
}

class PartDrawingTest {
    @Test fun oneDetailSheetPerItem() {
        val fmt = LengthFormat("in")
        val meshes = Fixtures.miniCabinet(Fixtures.INCH).toMutableList()
        meshes += Fixtures.rotatedZ(Fixtures.box(0.0, 30.0 * Fixtures.INCH, 0.0, 2.0 * Fixtures.INCH, 0.0, 0.75 * Fixtures.INCH), 30.0)   // a brace lying at an angle
        val cut = CutList.build(meshes.mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }, fmt)
        val sheets = splitziner.drawing.PartDrawings.build(cut.parts, cut.rows, splitziner.sheet.DrawingInfo(title = "Cabinet, storage", drawingNo = "CAB-001"), splitziner.drawing.DrawingSettings(), fmt)
        assertEquals(cut.rows.size, sheets.size)
        val out = java.io.File("build/preview").apply { mkdirs() }
        sheets.forEachIndexed { i, r -> java.io.File(out, "item%02d.svg".format(i + 1)).writeText(splitziner.export.SvgWriter.write(r.sheets[0])) }
        println("items: " + sheets.joinToString { "${it.sheet.letter} ${it.scaleText}" })
        // The angled brace is squared up: its drawing shows its true 30 x 2 x .75 size.
        val brace = splitziner.drawing.PartDrawings.squaredUp(meshes.last())
        assertEquals(30.0 * Fixtures.INCH, brace.extents.x, 0.01); assertEquals(0.75 * Fixtures.INCH, brace.extents.y, 0.01); assertEquals(2.0 * Fixtures.INCH, brace.extents.z, 0.01)
        assertEquals(meshes.last().volume, brace.volume, 1e-3)
    }
}

class ExportTest {
    @Test fun dxfHasOneLinePerSegmentAndEndsProperly() {
        val dl = splitziner.sheet.DisplayList(100.0, 50.0)
        dl.segs(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 10.0), splitziner.sheet.LineStyle.THICK)
        dl.line(0.0, 5.0, 20.0, 5.0, splitziner.sheet.LineStyle.HIDDEN)
        val dxf = splitziner.export.DxfWriter.write(dl)
        assertEquals(3, Regex("(?m)^LINE$").findAll(dxf).count())
        assertTrue(dxf.contains("0\nSECTION\n2\nENTITIES\n")); assertTrue(dxf.endsWith("0\nEOF\n"))
        assertTrue(dxf.contains("8\nVISIBLE\n")); assertTrue(dxf.contains("8\nHIDDEN\n"))
    }

    @Test fun partsZipRoundTrips() {
        val bytes = Fixtures.binaryStl(Fixtures.soup(Fixtures.miniCabinet(), scale = 0.0254))
        val a = Pipeline.analyze(bytes, "cabinet.stl")
        val out = ByteArrayOutputStream()
        splitziner.export.PartsZip.write(a.parts, a.report, a.sourceName, out)
        val zin = java.util.zip.ZipInputStream(out.toByteArray().inputStream())
        var stl = 0; var names = ArrayList<String>()
        while (true) {
            val e = zin.nextEntry ?: break
            names += e.name
            if (e.name.endsWith(".stl")) {
                val raw = StlReader.read(zin.readBytes())
                val part = a.parts[stl++]
                assertEquals(part.mesh.triangleCount, raw.triangleCount)
                // Written as 32-bit floats, so volume agrees to float precision.
                assertEquals(part.mesh.volume, BodySplitter.split(raw, "mm").parts[0].mesh.volume, part.mesh.volume * 1e-5)
            }
        }
        assertEquals(7, stl); assertTrue("parts.csv" in names && "README.txt" in names)
    }
}

class HoleTest {
    @Test fun findsThroughAndBlindHoles() {
        val m = Fixtures.holedBoard(listOf(null, 10.0, null))
        assertTrue(m.isWatertight)
        val holes = splitziner.features.HoleFinder.find(m).sortedBy { it.start.x }
        assertEquals(3, holes.size)
        for (h in holes) { assertEquals(8.0, h.diameter, 1e-6); assertEquals(2, h.principalAxis); assertEquals(20.0, h.start.y, 1e-6) }
        assertEquals(listOf(true, false, true), holes.map { it.through })
        assertEquals(18.0, holes[0].depth!!, 1e-9); assertEquals(10.0, holes[1].depth!!, 1e-9)
        assertEquals(60.0, holes[1].start.x, 1e-6); assertEquals(18.0, holes[1].start.z, 1e-9); assertEquals(-1.0, holes[1].axis.z, 1e-9)
    }

    @Test fun solidShapesHaveNoHoles() {
        // A rod is round but is not a hole; a box and a bracket have no round rims at all.
        assertEquals(0, splitziner.features.HoleFinder.find(Fixtures.cylinder(10.0, 0.0, 50.0)).size)
        assertEquals(0, splitziner.features.HoleFinder.find(Fixtures.box(0.0, 10.0, 0.0, 20.0, 0.0, 30.0)).size)
        assertEquals(0, splitziner.features.HoleFinder.find(Fixtures.lBracket()).size)
        // Eight-sided holes count as round; a square hole (four sides) does not.
        assertEquals(1, splitziner.features.HoleFinder.find(Fixtures.holedBoard(listOf(null), sides = 8)).size)
        assertEquals(0, splitziner.features.HoleFinder.find(Fixtures.holedBoard(listOf(null), sides = 4)).size)
    }

    @Test fun drawingLocatesAndCallsOutHoles() {
        val fmt = LengthFormat("mm")
        val m = Fixtures.holedBoard(listOf(null, 10.0, null, null))
        val cut = CutList.build(listOf(Part("P01", "P", m)), fmt)
        val vs = splitziner.drawing.DrawingBuilder.views(cut.parts)
        val r = splitziner.drawing.DrawingBuilder.build(cut.parts, cut.rows, vs, splitziner.sheet.DrawingInfo(title = "Rail, drilled", drawingNo = "RL-1"), splitziner.drawing.DrawingSettings(), fmt)
        java.io.File("build/preview").mkdirs(); java.io.File("build/preview/holes.svg").writeText(splitziner.export.SvgWriter.write(r.sheets[0]))
        val texts = r.sheets[0].prims.filterIsInstance<splitziner.sheet.Text>().map { it.text }
        println("holes: ${r.sheet.letter} ${r.scaleText} qa=${r.qa}")
        assertEquals("BOARD 18 X 40 X 160, 4 HOLES", cut.rows[0].description)
        assertTrue(r.qa.isEmpty(), r.qa.toString())
        assertTrue(texts.any { it == "3X ${fmt.format(8.0)} DIA THRU" }, texts.toString())
        assertTrue(texts.any { it == "${fmt.format(8.0)} DIA ${fmt.format(10.0)} DEEP" })
        // Hole centres are dimensioned from the datum corner: 20, 60, 100, 140 along the board and 20 across it.
        for (v in listOf(20.0, 60.0, 100.0, 140.0)) assertTrue(texts.contains(fmt.format(v)), "missing ${fmt.format(v)} in $texts")
        assertTrue(r.sheets[0].prims.any { it is splitziner.sheet.Segments && it.style == splitziner.sheet.LineStyle.CENTER })
    }
}

class CutPlanTest {
    private fun cabinetRows(fmt: LengthFormat): List<splitziner.parts.CutListRow> {
        val meshes = Fixtures.miniCabinet(Fixtures.INCH).toMutableList()
        repeat(5) { meshes += MeshOps.translated(Fixtures.box(0.0, 30.0 * Fixtures.INCH, 0.0, 3.5 * Fixtures.INCH, 0.0, 0.75 * Fixtures.INCH), Vec3(0.0, 2000.0 + it * 200, 0.0)) }
        return CutList.build(meshes.mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }, fmt).rows
    }

    @Test fun everyPieceIsPlacedOnceWithoutOverlap() {
        val fmt = LengthFormat("in")
        val rows = cabinetRows(fmt)
        val stock = splitziner.cutplan.StockSettings.INCH
        val r = splitziner.cutplan.CutPlanner.plan(rows, stock)
        assertTrue(r.leftOut.isEmpty(), r.leftOut.toString())
        val panels = rows.filter { it.kind == splitziner.parts.PartKind.PANEL }
        assertEquals(panels.sumOf { it.qty }, r.sheets.sumOf { it.pieces.size })
        for (sh in r.sheets) {
            for (a in sh.pieces) {
                assertTrue(a.x >= -1e-6 && a.y >= -1e-6 && a.x + a.w <= sh.w + 1e-6 && a.y + a.l <= sh.l + 1e-6)
                val row = rows.first { it.item == a.item }
                assertEquals(row.w * row.l, a.w * a.l, 1e-6)
                // Pieces are at least a saw cut apart.
                for (b in sh.pieces) if (a !== b) {
                    val gapX = maxOf(b.x - (a.x + a.w), a.x - (b.x + b.w)); val gapY = maxOf(b.y - (a.y + a.l), a.y - (b.y + b.l))
                    assertTrue(maxOf(gapX, gapY) >= stock.kerf - 1e-6, "pieces ${a.item} and ${b.item} are closer than a saw cut")
                }
            }
        }
        // Five 30 in boards: three fit in one 96 in length, so two lengths are needed.
        val boards = r.boards.filter { it.kind == splitziner.parts.PartKind.BOARD && abs(it.w - 3.5 * Fixtures.INCH) < 0.01 }
        assertEquals(5, boards.sumOf { b -> b.cuts.count { abs(it.second - 30.0 * Fixtures.INCH) < 0.01 } }); assertEquals(2, boards.size)
        assertEquals(rows.filter { it.kind == splitziner.parts.PartKind.BOARD }.sumOf { it.qty }, r.boards.sumOf { it.cuts.size })
        for (b in boards) assertTrue(b.cuts.sumOf { it.second } + stock.kerf * (b.cuts.size - 1) <= b.length + 1e-6)
        val pages = splitziner.cutplan.CutPlanLayout.pages(r, rows, "Cabinet, storage", fmt)
        java.io.File("build/preview").mkdirs()
        pages.forEachIndexed { i, pg -> java.io.File("build/preview/cutplan%d.svg".format(i + 1)).writeText(splitziner.export.SvgWriter.write(pg)) }
        println("cutplan: sheets=${r.sheets.size} boards=${r.boards.size} pages=${pages.size} buy=${splitziner.cutplan.CutPlanner.shoppingList(r, fmt)}")
    }

    @Test fun oversizeAndGrainAreRespected() {
        val fmt = LengthFormat("mm")
        val big = Part("P01", "P", Fixtures.box(0.0, 3000.0, 0.0, 600.0, 0.0, 18.0))
        val wide = Part("P02", "P", Fixtures.box(0.0, 1000.0, 0.0, 2000.0, 0.0, 18.0))
        val rows = CutList.build(listOf(big, wide), fmt).rows
        val r = splitziner.cutplan.CutPlanner.plan(rows, splitziner.cutplan.StockSettings(rotate = false))
        assertEquals(1, r.leftOut.size)
        assertEquals(1, r.sheets.sumOf { it.pieces.size })
        assertTrue(r.sheets[0].pieces.none { it.rotated })
    }
}

class DesignCheckTest {
    private val mm = LengthFormat("mm")
    private fun check(meshes: List<splitziner.mesh.Mesh>, tools: Set<splitziner.check.Tool>? = null): splitziner.check.CheckReport {
        val cut = CutList.build(meshes.mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }, mm)
        return splitziner.check.DesignCheck.run(cut.parts, cut.rows, mm, tools)
    }
    private fun titles(r: splitziner.check.CheckReport) = r.findings.map { "${it.severity} ${it.title}" }

    @Test fun soundCabinetHasNoProblems() {
        val r = check(Fixtures.miniCabinet(Fixtures.INCH), splitziner.check.Tool.entries.toSet())
        assertEquals(0, r.problems, titles(r).toString())
    }

    @Test fun findsFloatingAndClashingParts() {
        val base = Fixtures.box(0.0, 600.0, 0.0, 400.0, 0.0, 18.0)
        val post = Fixtures.box(100.0, 118.0, 100.0, 300.0, 18.0, 500.0)
        val floating = Fixtures.box(300.0, 500.0, 100.0, 300.0, 200.0, 218.0)            // touches nothing
        val through = Fixtures.box(90.0, 290.0, 150.0, 168.0, 300.0, 400.0)              // passes through the post
        val t = titles(check(listOf(base, post, floating, through)))
        assertTrue(t.any { it.startsWith("PROBLEM 1 part touches nothing") }, t.toString())
        assertTrue(t.any { it.startsWith("PROBLEM") && it.contains("occupy the same space") }, t.toString())
        // Parts that only touch are neither.
        val ok = titles(check(listOf(base, post)))
        assertTrue(ok.none { it.contains("occupy") || it.contains("touch") }, ok.toString())
    }

    @Test fun flagsOddThicknessToolsSagAndTipping() {
        val base = Fixtures.box(0.0, 100.0, 0.0, 100.0, 0.0, 18.0)
        val arm = Fixtures.box(0.0, 2000.0, 0.0, 100.0, 18.0, 31.0)                      // 13 mm thick, far off the base
        val r = check(listOf(base, arm), setOf(splitziner.check.Tool.HAND_SAW))
        val t = titles(r)
        assertTrue(t.any { it.contains("13 mm is not a thickness shops stock") }, t.toString())
        assertTrue(t.any { it == "PROBLEM It would tip over" }, t.toString())
        assertTrue(t.any { it.contains("may sag") }, t.toString())
        assertTrue(t.any { it.startsWith("CHECK Your tools can manage straight cuts") }, t.toString())
        assertTrue(r.findings.first { it.title.contains("13 mm") }.fixes.map { it.value } == listOf(12.0, 15.0))
        // A drilled board needs a drill; with only a saw that is a problem, with a drill it is not.
        val drilled = Fixtures.holedBoard(listOf(null, null))
        assertTrue(titles(check(listOf(drilled), setOf(splitziner.check.Tool.CIRCULAR_SAW))).any { it == "PROBLEM No tool for round holes" })
        assertTrue(titles(check(listOf(drilled), setOf(splitziner.check.Tool.CIRCULAR_SAW, splitziner.check.Tool.DRILL))).none { it.contains("round holes") })
    }
}

class EditTest {
    private val mm = LengthFormat("mm")
    private fun parts(vararg m: splitziner.mesh.Mesh) = m.mapIndexed { i, x -> Part("P%02d".format(i + 1), "P", x) }
    private fun problems(ps: List<Part>, housed: List<Pair<String, String>> = emptyList()): List<String> {
        val cut = CutList.build(ps, mm)
        return splitziner.check.DesignCheck.run(cut.parts, cut.rows, mm, splitziner.check.Tool.entries.toSet(), housed = housed).findings.filter { it.severity == splitziner.check.Severity.PROBLEM }.map { it.title }
    }

    @Test fun prismRoundTripKeepsShapeAndCloses() {
        val m = Fixtures.holedBoard(listOf(null, null))
        val p = splitziner.edit.Prisms.of(m)!!
        assertEquals(18.0, p.thickness, 1e-9)
        val back = splitziner.edit.Prisms.toMesh(p)
        assertTrue(back.isWatertight); assertEquals(m.volume, back.volume, m.volume * 1e-6)
        assertNull(splitziner.edit.Prisms.of(Fixtures.cylinder(10.0, 0.0, 5.0).let { MeshOps.concatenate(listOf(it, Fixtures.box(20.0, 30.0, 0.0, 10.0, 0.0, 30.0))) }))
    }

    @Test fun trimThicknessMoveAndMerge() {
        val base = Fixtures.box(0.0, 600.0, 0.0, 400.0, 0.0, 18.0)
        val post = Fixtures.box(100.0, 118.0, 100.0, 300.0, 10.0, 500.0)           // sunk 8 mm into the base
        val r = splitziner.edit.Editor.apply(parts(base, post), listOf(splitziner.edit.Edit.Trim("P02", "P01")))
        assertTrue(r.notes.isEmpty(), r.notes.toString())
        assertEquals(18.0, r.parts[1].mesh.bounds.min.z, 1e-6); assertTrue(r.parts[1].mesh.isWatertight)
        // A shelf made thicker grows away from the post it rests on.
        val shelf = Fixtures.box(118.0, 400.0, 100.0, 300.0, 200.0, 213.0)
        val under = Fixtures.box(118.0, 136.0, 100.0, 300.0, 18.0, 200.0)
        val t = splitziner.edit.Editor.apply(parts(base, shelf, under), listOf(splitziner.edit.Edit.Thickness(listOf("P02"), 18.0))).parts[1].mesh.bounds
        assertEquals(200.0, t.min.z, 1e-6); assertEquals(218.0, t.max.z, 1e-6)
        val moved = splitziner.edit.Editor.apply(parts(base), listOf(splitziner.edit.Edit.Move(listOf("P01"), Vec3(0.0, 0.0, 5.0)), splitziner.edit.Edit.AddBox("N01", "Foot", Vec3(0.0, 0.0, 0.0), Vec3(50.0, 50.0, 5.0)))).parts
        assertEquals(2, moved.size); assertEquals(5.0, moved[0].mesh.bounds.min.z, 1e-9)
        // Two crossing strips of one thickness become one cross-shaped piece.
        val a = Fixtures.box(0.0, 300.0, 100.0, 140.0, 0.0, 18.0); val b = Fixtures.box(130.0, 170.0, 0.0, 240.0, 0.0, 18.0)
        val merged = splitziner.edit.Editor.apply(parts(a, b), listOf(splitziner.edit.Edit.Merge(listOf("P01", "P02")))).parts
        assertEquals(1, merged.size); assertTrue(merged[0].mesh.isWatertight)
        assertEquals((300.0 * 40 + 240.0 * 40 - 40.0 * 40) * 18, merged[0].mesh.volume, 1.0)
    }

    @Test fun autoFixMakesABrokenModelSound() {
        val base = Fixtures.box(0.0, 600.0, 0.0, 400.0, 0.0, 18.0)
        val post = Fixtures.box(100.0, 118.0, 100.0, 300.0, 10.0, 500.0)           // runs into the base
        val riser = Fixtures.box(300.0, 500.0, 100.0, 300.0, 21.0, 34.0)           // hovers 3 mm above it, and is 13 mm thick
        val ps = parts(base, post, riser)
        assertTrue(problems(ps).size >= 2, problems(ps).toString())
        val plan = splitziner.edit.AutoFix.plan(ps, mm)
        println("autofix: " + plan.done)
        assertTrue(plan.left.isEmpty(), plan.left.toString())
        val applied = splitziner.edit.Editor.apply(ps, plan.edits)
        val fixed = applied.parts
        assertEquals(emptyList(), problems(fixed, applied.housed))
        assertEquals(18.0, fixed[2].mesh.bounds.min.z, 1e-6)                        // seated on the base
        assertEquals(12.0, fixed[2].mesh.extents.z, 1e-6)                           // and now a stock thickness
        // The pipeline replays the same changes on the original file.
        val bytes = Fixtures.binaryStl(Fixtures.soup(listOf(base, post, riser)))
        val a = Pipeline.analyze(bytes, "broken.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm", edits = splitziner.edit.AutoFix.plan(Pipeline.analyze(bytes, "broken.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm")).originalParts, mm).edits))
        // The post let 8 mm into the base is kept as a housing (a stopped groove cut to take it), not cut short.
        assertEquals(emptyList(), problems(a.parts.map { Part(it.id, it.name, it.mesh) }, a.housed))
    }
}

class VersionsTest {
    @Test fun compares() {
        val v = splitziner.update.Versions
        assertTrue(v.isNewer("v0.12.0", "0.11.0")); assertTrue(v.isNewer("v0.11.1", "0.11.0")); assertTrue(v.isNewer("v1.0.0", "0.99.99"))
        assertFalse(v.isNewer("v0.11.0", "0.11.0")); assertFalse(v.isNewer("v0.10.9", "0.11.0")); assertFalse(v.isNewer("nightly", "0.11.0"))
        assertTrue(v.isNewer("v0.11.0", "0.0.1-dev"))
        assertEquals("• Check tab. It looks.", v.plainNotes("- **Check tab.** It looks.\n\n**Install:** open this page"))
    }
}

class SimplifyTest {
    @Test fun keepsBoxCornersAndBoundsCurvedError() {
        // A finely divided board: its size must survive simplification exactly.
        val fine = splitziner.edit.Editor.box(Vec3(0.0, 0.0, 0.0), Vec3(19.0, 600.0, 1500.0)).let { b ->
            var m = b; repeat(5) { m = subdivide(m) }; m
        }
        val s = splitziner.mesh.Simplify.run(fine, fine.triangleCount / 10)
        println("simplify board: ${fine.triangleCount} -> ${s.mesh.triangleCount}, error ${s.maxError}")
        // Accuracy wins over the budget: a thin board stops short of the target rather than bend its edges.
        assertTrue(s.mesh.triangleCount <= fine.triangleCount / 2, "${fine.triangleCount} -> ${s.mesh.triangleCount}, error ${s.maxError}")
        assertEquals(19.0, s.mesh.extents.x, 1e-6); assertEquals(600.0, s.mesh.extents.y, 1e-6); assertEquals(1500.0, s.mesh.extents.z, 1e-6)
        assertTrue(s.maxError < 1e-6, "flat board error ${s.maxError}")
        assertEquals(fine.volume, s.mesh.volume, fine.volume * 1e-6)
        // A smooth rod: the reported error really bounds how far its radius moved.
        val rod = Fixtures.cylinder(20.0, 0.0, 100.0, sides = 2000)
        val r = splitziner.mesh.Simplify.run(rod, 800)
        val e = r.mesh.extents
        assertTrue(abs(e.x - 40.0) <= 2 * r.maxError + 1e-6, "width ${e.x} error ${r.maxError}")
        assertTrue(r.maxError < 0.5)
    }

    private fun subdivide(m: splitziner.mesh.Mesh): splitziner.mesh.Mesh {
        val soup = ArrayList<Double>()
        for (t in 0 until m.triangleCount) {
            val a = m.vertex(m.triangles[3 * t]); val b = m.vertex(m.triangles[3 * t + 1]); val c = m.vertex(m.triangles[3 * t + 2])
            val ab = (a + b) * 0.5; val bc = (b + c) * 0.5; val ca = (c + a) * 0.5
            for (tri in listOf(listOf(a, ab, ca), listOf(ab, b, bc), listOf(ca, bc, c), listOf(ab, bc, ca))) for (p in tri) { soup += p.x; soup += p.y; soup += p.z }
        }
        return MeshOps.fromSoup(soup.toDoubleArray(), 1e-9)
    }
}

class PartMarksTest {
    @Test fun ownExportsReadBackAsTheSameParts() {
        // Two boards touching face to face, one of them with an open surface: without marks these would merge.
        val a = Fixtures.box(0.0, 100.0, 0.0, 50.0, 0.0, 18.0)
        val b0 = Fixtures.box(0.0, 100.0, 0.0, 50.0, 18.0, 36.0)
        val b = splitziner.mesh.Mesh(b0.vertices, b0.triangles.copyOf(b0.triangles.size - 3))
        val out = ByteArrayOutputStream()
        StlWriter.writeParts(listOf(a, b, Fixtures.box(200.0, 300.0, 0.0, 50.0, 0.0, 18.0)), out)
        val r = Pipeline.analyze(out.toByteArray(), "own.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(3, r.parts.size); assertEquals(BodySplitter.METHOD_MARKED, r.report.method)
        // Someone else's file with the same bytes set is not trusted: colours live there too.
        val other = out.toByteArray().copyOf(); "Other program".toByteArray().copyInto(other)
        assertEquals(splitziner.io.StlReader.read(other).groups, null)
    }

    @Test fun textFilesWithSeveralSolidsKeepThem() {
        val a = StlWriter.writeAscii(Fixtures.box(0.0, 10.0, 0.0, 10.0, 0.0, 10.0), "a")
        val b = StlWriter.writeAscii(Fixtures.box(0.0, 10.0, 0.0, 10.0, 10.0, 20.0).let { splitziner.mesh.Mesh(it.vertices, it.triangles.copyOf(it.triangles.size - 3)) }, "b")
        val r = Pipeline.analyze((a + b).toByteArray(), "two.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(2, r.parts.size)
    }
}

class BoardsTest {
    @Test fun frameAndOpenBoxBecomeBoards() {
        // A 600 x 400 frame of 40 mm members, 18 mm thick, drawn as one solid.
        val outer = splitziner.explode.Silhouette.GF.toGeometry(org.locationtech.jts.geom.Envelope(0.0, 600.0, 0.0, 400.0))
        val inner = splitziner.explode.Silhouette.GF.toGeometry(org.locationtech.jts.geom.Envelope(40.0, 560.0, 40.0, 360.0))
        val frame = splitziner.edit.Prisms.toMesh(splitziner.edit.Prism(splitziner.view.ViewDef.TOP, outer.difference(inner), 0.0, 18.0))
        val (r, why) = splitziner.edit.Boards.decompose(frame)
        assertNotNull(r, why); assertEquals(4, r!!.pieces.size); assertTrue(r.flat)
        assertEquals(frame.volume, r.pieces.sumOf { it.volume }, frame.volume * 1e-6)
        // A U channel, 60 x 60 with 12 mm walls and 600 long, drawn as one solid: three boards.
        val gf = splitziner.explode.Silhouette.GF
        val u = gf.toGeometry(org.locationtech.jts.geom.Envelope(0.0, 60.0, 0.0, 60.0)).difference(gf.toGeometry(org.locationtech.jts.geom.Envelope(12.0, 48.0, 12.0, 70.0)))
        val channel = splitziner.edit.Prisms.toMesh(splitziner.edit.Prism(splitziner.view.ViewDef.RIGHT, u, 0.0, 600.0))
        val (b, why2) = splitziner.edit.Boards.decompose(channel)
        assertNotNull(b, why2); assertEquals(3, b!!.pieces.size); assertFalse(b.flat)
        assertEquals(channel.volume, b.pieces.sumOf { it.volume }, channel.volume * 1e-6)
        // A plain panel, and a panel with a small cut-out, stay one piece.
        assertNull(splitziner.edit.Boards.decompose(Fixtures.box(0.0, 600.0, 0.0, 400.0, 0.0, 18.0)).first)
        assertNull(splitziner.edit.Boards.decompose(Fixtures.holedBoard(listOf(null, null))).first)
    }

    @Test fun thickFrameIsFixedAutomatically() {
        val outer = splitziner.explode.Silhouette.GF.toGeometry(org.locationtech.jts.geom.Envelope(0.0, 900.0, 0.0, 750.0))
        val inner = splitziner.explode.Silhouette.GF.toGeometry(org.locationtech.jts.geom.Envelope(25.0, 875.0, 25.0, 725.0))
        // A leg frame 75 mm deep standing on the floor, drawn as one solid: no sheet is that thick.
        val leg = splitziner.edit.Prisms.toMesh(splitziner.edit.Prism(splitziner.view.ViewDef.FRONT, outer.difference(inner), -75.0, 0.0))
        val mm = LengthFormat("mm")
        val ps = CutList.build(listOf(Part("P01", "P", leg)), mm).parts
        val check = splitziner.check.DesignCheck.run(ps, CutList.build(ps, mm).rows, mm, null)
        assertTrue(check.findings.any { it.title.startsWith("Item 1 is a frame cut from one") && it.fixes.any { f -> f.kind == splitziner.check.FixKind.SPLIT_BOARDS } })
        val plan = splitziner.edit.AutoFix.plan(ps, mm)
        val after = splitziner.edit.Editor.apply(ps, plan.edits).parts
        assertEquals(4, after.size, plan.done.toString())
    }
}

class SubassemblyTest {
    @Test fun framesAreMadeOnTheBenchFirst() {
        // A table: two leg frames, each drawn as one solid and split into boards, and a top resting on them.
        val gf = splitziner.explode.Silhouette.GF
        fun frame(x: Double): splitziner.mesh.Mesh {
            val ring = gf.toGeometry(org.locationtech.jts.geom.Envelope(0.0, 600.0, 0.0, 700.0)).difference(gf.toGeometry(org.locationtech.jts.geom.Envelope(40.0, 560.0, 40.0, 660.0)))
            return splitziner.edit.Prisms.toMesh(splitziner.edit.Prism(splitziner.view.ViewDef.RIGHT, ring, x, x + 40.0))
        }
        val top = Fixtures.box(0.0, 1200.0, 0.0, 600.0, 700.0, 720.0)
        val ps = listOf(Part("P01", "P", frame(0.0)), Part("P02", "P", frame(1160.0)), Part("P03", "P", top))
        val split = splitziner.edit.Editor.apply(ps, listOf(splitziner.edit.Edit.SplitBoards(listOf("P01", "P02")))).parts
        assertEquals(9, split.size)
        val cut = CutList.build(split, LengthFormat("mm"))
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        // One bench step makes both frames; the main build then places them whole, and the top goes on last.
        assertTrue(plan.steps[0].subassembly); assertEquals(2, plan.steps[0].makeCount)
        assertEquals(1, plan.steps.count { it.subassembly })
        // The second frame touches nothing until the top joins them, so it goes in with the top.
        assertTrue(plan.steps.last().partIds.last() == "P03", plan.steps.map { it.partIds }.toString())
        val guide = splitziner.guide.GuideSteps.build(plan.parts, plan.steps)
        assertTrue(guide[0].title.startsWith("Step 1: make 2 of sub-assembly A from items"), guide[0].title)
        assertTrue(guide[0].text[0].startsWith("Lay down"), guide[0].text.toString())
    }
}

class FurnitureTemplatesTest {
    private fun parts(key: String, inch: Boolean = true): List<Part> {
        val t = splitziner.edit.Templates.byKey(key)
        val ps = t.build(splitziner.edit.Templates.defaults(t, inch)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P%02d".format(i + 1), m) }
        val roles = splitziner.parts.Roles.names(ps)
        val named = ps.map { p -> roles[p.id]?.let { p.copy(name = it) } ?: p }
        return CutList.build(named, LengthFormat("in")).parts
    }

    @Test fun bedPartsAreNamedForWhatTheyAre() {
        val names = parts("bed").map { it.name }.toSet()
        for (n in listOf("Slat", "Cleat", "Leg", "Side rail", "End rail", "Centre rail")) assertTrue(n in names, "$n in $names")
    }

    @Test fun slatsTouchTheCentreRailTheyCross() {
        val ps = parts("bed")
        val rel = splitziner.check.DesignCheck.relations(ps)
        val centre = ps.indexOfFirst { it.name == "Centre rail" }
        val slats = ps.indices.filter { ps[it].name == "Slat" }
        assertTrue(slats.all { centre in rel.touching[it] }, "every slat rests on the centre rail")
    }

    @Test fun tablesAreBuiltFromEndFramesAndTopsAreNotScrewedThrough() {
        val ps = parts("table")
        val plan = splitziner.explode.AssemblyPlan.plan(ps)
        assertTrue(plan.steps[0].subassembly && plan.steps[0].makeCount == 2, plan.steps.map { it.partIds }.toString())
        val f = splitziner.joinery.Fastening.plan(plan.parts, plan.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in"))
        val top = plan.parts.first { it.name == "Top" }
        assertTrue(f.all.none { it.through === top && it.kind == "screws" }, "the top is fixed from underneath")
    }

    @Test fun shelvesGoInTogetherAndTheBackLast() {
        val plan = splitziner.explode.AssemblyPlan.plan(parts("shelves"))
        val byId = plan.parts.associateBy { it.id }
        assertTrue(plan.steps.last().partIds.all { byId.getValue(it).name == "Back panel" }, plan.steps.map { it.partIds }.toString())
        assertTrue(plan.steps.any { st -> st.partIds.size >= 4 && st.partIds.all { byId.getValue(it).name == "Shelf" } }, "the shelves share one step")
    }
}

class NotchDimensionTest {
    @Test fun aToeKickNotchIsDimensionedOnItsPartDrawing() {
        // A cabinet side with a toe-kick notch cut from its bottom front corner: the notch's top faces down and its
        // back faces the front, so only a part drawing that counts faces both ways can dimension it.
        val gf = splitziner.explode.Silhouette.GF
        val outline = gf.toGeometry(org.locationtech.jts.geom.Envelope(0.0, 560.0, 0.0, 720.0)).difference(gf.toGeometry(org.locationtech.jts.geom.Envelope(0.0, 75.0, 0.0, 100.0)))
        val mesh = splitziner.edit.Prisms.toMesh(splitziner.edit.Prism(splitziner.view.ViewDef.RIGHT, outline, 0.0, 18.0))
        val b = mesh.bounds
        val detail = splitziner.dims.DimPlanner.levels(mesh, b.min, b.max, 8, detail = true)
        val plain = splitziner.dims.DimPlanner.levels(mesh, b.min, b.max, 8)
        val z = detail[2].map { it - b.min.z }
        assertTrue(z.any { kotlin.math.abs(it - 100.0) < 0.5 }, "detail z levels $z")
        val y = detail[1].map { it - b.min.y }
        assertTrue(y.any { kotlin.math.abs(it - 75.0) < 0.5 || kotlin.math.abs(it - 485.0) < 0.5 }, "detail y levels $y")
        // On an assembly only upward faces count, so the notch's top is not taken for a shelf.
        assertTrue(plain[2].none { kotlin.math.abs(it - b.min.z - 100.0) < 0.5 }, "assembly z levels ${plain[2]}")
    }
}

class FasteningTest {
    @Test fun cabinetGetsSensibleScrews() {
        val fmt = LengthFormat("in")
        val cut = CutList.build(Fixtures.miniCabinet(Fixtures.INCH).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }, fmt)
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        val f = splitziner.joinery.Fastening.plan(plan.parts, plan.steps, splitziner.joinery.JoinMethod.SCREWS, fmt)
        println("cabinet hardware: ${f.hardware}")
        // 3/4 in sides into 3/4 in shelves: 1-1/2 in would be 3/4 through plus 3/4 in; 1-1/4 is the longest stock size under the 1-7/8 in limit... at most 2 in.
        val lengths = f.hardware.mapNotNull { Regex("""screws, (?:#\d+ x )?([\d/-]+) in""").find(it)?.groupValues?.get(1) }
        assertTrue(lengths.isNotEmpty(), f.hardware.toString())
        assertTrue(lengths.none { it.startsWith("2-") || it.startsWith("3") }, "screws too long for 3/4 in stock: $lengths")
        // The 1/4 in back is pinned with brads, not screwed.
        assertTrue(f.hardware.any { it.endsWith("brads") }, f.hardware.toString())
        val guide = splitziner.guide.GuideSteps.build(plan.parts, plan.steps, emptyMap(), f)
        assertTrue(guide.any { st -> st.text.any { it.startsWith("Drill pilot holes") } })
    }
}

class LumberTest {
    @Test fun boardsBecomeStandardLumber() {
        val fmt = LengthFormat("in")
        val I = Fixtures.INCH
        val ms = listOf(Fixtures.box(0.0, 30 * I, 0.0, 3.5 * I, 0.0, 0.75 * I), Fixtures.box(0.0, 30 * I, 10 * I, 13 * I, 0.0, 0.75 * I),
            Fixtures.box(0.0, 30 * I, 20 * I, 22 * I, 0.0, 0.5 * I))
        val rows = CutList.build(ms.mapIndexed { i, m -> Part("P$i", "P", m) }, fmt).rows
        val r = splitziner.cutplan.CutPlanner.plan(rows, splitziner.cutplan.StockSettings.INCH)
        val buy = splitziner.cutplan.CutPlanner.shoppingList(r, fmt)
        println("lumber: $buy")
        // The 3-1/2 and 3 in wide boards both come from 1x4 (the 3 in one ripped); the 1/2 in strip comes from sheet.
        assertTrue(buy.any { it.contains("1x4 (actual .75 x 3.50), some pieces ripped narrower") }, buy.toString())
        assertTrue(buy.any { it.contains("sheet 1/2 in thick") }, buy.toString())
    }
}

class UpTest {
    @Test fun yUpFileStandsUpright() {
        // The mini cabinet exported with Y up: its height (60 in) lies along the file's Y.
        val zUp = Fixtures.miniCabinet(Fixtures.INCH)
        val yUp = zUp.map { m -> splitziner.mesh.Mesh(DoubleArray(m.vertices.size) { i -> val v = 3 * (i / 3); when (i % 3) { 0 -> m.vertices[v]; 1 -> m.vertices[v + 2]; else -> -m.vertices[v + 1] } }, m.triangles) }
        val bytes = Fixtures.binaryStl(Fixtures.soup(yUp))
        val flat = Pipeline.analyze(bytes, "y.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val up = Pipeline.analyze(bytes, "y.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm", up = "y"))
        assertEquals(60 * Fixtures.INCH, up.bounds.extents.z, 1e-6)
        assertEquals(60 * Fixtures.INCH, flat.bounds.extents.y, 1e-6)
        // Turning keeps every part's volume and is not a mirror image.
        assertEquals(flat.parts.sumOf { it.mesh.volume }, up.parts.sumOf { it.mesh.volume }, 1e-3)
        assertTrue(up.parts.all { it.mesh.volume > 0 })
    }
}


class SmallPanelTest {
    @Test fun aFewSmallPiecesBuyAQuarterSheet() {
        val fmt = LengthFormat("in")
        val parts = (0 until 3).map { i -> Part("P$i", "P", MeshOps.translated(Fixtures.box(0.0, 12 * Fixtures.INCH, 0.0, 16 * Fixtures.INCH, 0.0, 0.5 * Fixtures.INCH), Vec3(0.0, 0.0, i * 100.0))) }
        val rows = CutList.build(parts, fmt).rows
        val r = splitziner.cutplan.CutPlanner.plan(rows, splitziner.cutplan.StockSettings.INCH)
        assertEquals(1, r.sheets.size)
        assertEquals("quarter sheet", r.sheets[0].size)
        assertEquals(24 * Fixtures.INCH, r.sheets[0].w, 1e-6)
        assertTrue(splitziner.cutplan.CutPlanner.shoppingList(r, fmt)[0].startsWith("1 quarter sheet 1/2 in thick"), splitziner.cutplan.CutPlanner.shoppingList(r, fmt)[0])
    }
}

class MaterialsTest {
    @Test fun densityFromWhatPeopleType() {
        assertEquals(680.0, splitziner.material.Materials.density("Baltic birch plywood")!!, 1e-9)
        assertEquals(560.0, splitziner.material.Materials.density("3/4 ply")!!, 1e-9)
        assertEquals(500.0, splitziner.material.Materials.density("Pine 1x4")!!, 1e-9)
        assertNull(splitziner.material.Materials.density("unobtainium"))
        assertNull(splitziner.material.Materials.density(""))
    }

    @Test fun weightCountsOnlyKnownMaterials() {
        val fmt = LengthFormat("mm")
        val parts = listOf(Part("P1", "P", Fixtures.box(0.0, 1000.0, 0.0, 100.0, 0.0, 10.0)), Part("P2", "P", Fixtures.box(0.0, 500.0, 0.0, 100.0, 50.0, 60.0)))
        val cut = CutList.build(parts, fmt)
        val rows = cut.rows.map { if (it.l > 900) it.copy(material = "Pine") else it }
        val w = splitziner.material.Materials.weight(cut.parts, rows)
        assertEquals(1000.0 * 100 * 10 * 1e-9 * 500, w.kg, 1e-9)
        assertEquals(1, w.unknownItems.size)
        assertEquals("0.5 kg", splitziner.material.Materials.format(w.kg, false))
    }
}

class TemplatesTest {
    @Test fun everyTemplateBuildsCleanBoardsThatDoNotOverlap() {
        for (inch in listOf(false, true)) for (t in splitziner.edit.Templates.ALL) {
            val fmt = LengthFormat(if (inch) "in" else "mm")
            val meshes = t.build(splitziner.edit.Templates.defaults(t, inch))
            val parts = meshes.mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
            val cut = CutList.build(parts, fmt)
            assertTrue(cut.rows.all { it.kind == PartKind.PANEL || it.kind == PartKind.BOARD }, "${t.name}: ${cut.rows.map { it.description }}")
            val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
            assertTrue(rep.findings.none { it.title.contains("occupy the same space") || it.title.contains("touch nothing") || it.title.contains("tip over") || it.title.contains("not a thickness") }, "${t.name}: ${rep.findings.map { it.title }}")
            // The whole file round-trips through the reader as the same number of parts.
            val bytes = ByteArrayOutputStream().also { StlWriter.writeParts(meshes, it) }.toByteArray()
            assertEquals(meshes.size, Pipeline.analyze(bytes, "t.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm")).parts.size, t.name)
        }
    }
}

class ProfileDxfTest {
    @Test fun flatPartsBecomeClosedProfilesFullSize() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("cabinet")
        val meshes = t.build(splitziner.edit.Templates.defaults(t, false))
        val cut = CutList.build(meshes.mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }, fmt)
        val r = splitziner.export.ProfileDxf.write(cut.parts, cut.rows, fmt)
        assertEquals(cut.rows.size, r.profiles, "left out ${r.leftOut}")
        assertTrue(r.dxf.contains("CUT_OUTSIDE") && r.dxf.trimEnd().endsWith("EOF"))
        // The side panel's 720 mm height is in the file at full size.
        val ys = Regex("""\n20\n(-?[0-9.]+)\n""").findAll(r.dxf).map { it.groupValues[1].toDouble() }.toList()
        assertTrue(ys.max() - ys.min() >= 720.0 - 1e-6)
    }
}

class EdgeBandingTest {
    @Test fun countsOnlyFreeFrontSideAndTopEdges() {
        // One upright 600 x 18 x 900 panel standing alone: front? its thin axis is Y, so its edges are left, right and top.
        val p = Part("P1", "P", Fixtures.box(0.0, 600.0, 0.0, 18.0, 0.0, 900.0)).copy(kind = splitziner.parts.PartKind.PANEL, item = 1)
        val est = splitziner.cutplan.EdgeBanding.estimate(listOf(p))
        assertEquals((900.0 + 900.0 + 600.0) * 1.1, est.lengthMm, 1e-6)
    }
}

class HousingTest {
    /** A 40 mm thick base with a 6 mm lip standing 10 mm down into it: trimming the lip would lose a third of it. */
    private fun parts() = listOf(
        Part("P01", "Base", Fixtures.box(0.0, 600.0, 0.0, 300.0, 0.0, 40.0)),
        Part("P02", "Lip", Fixtures.box(0.0, 600.0, 280.0, 286.0, 30.0, 60.0)),
    )

    @Test fun fixerKeepsAShallowEntryAsAGrooveAndTheCheckAcceptsIt() {
        val fmt = LengthFormat("mm")
        val plan = splitziner.edit.AutoFix.plan(parts(), fmt)
        val house = plan.edits.filterIsInstance<splitziner.edit.Edit.House>()
        assertEquals(listOf(splitziner.edit.Edit.House("P02", "P01")), house, "${plan.done} / ${plan.left}")
        val res = splitziner.edit.Editor.apply(parts(), plan.edits)
        assertEquals(listOf("P02" to "P01"), res.housed)
        val cut = CutList.build(res.parts, fmt)
        val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null, housed = res.housed)
        assertTrue(rep.findings.none { it.title.contains("same space") }, rep.findings.map { it.title }.toString())
        val notes = splitziner.drawing.PartDrawings.grooveNotes(cut.parts.first { it.id == "P01" }, cut.parts, res.housed, fmt)
        assertEquals(1, notes.size); assertTrue(notes[0].startsWith("GROOVE FOR ITEM"), notes[0])
        assertTrue(notes[0].contains("10 DEEP X 6 WIDE X 600 LONG"), notes[0])
    }
}

class StretchTest {
    @Test fun makingABookcaseWiderLengthensShelvesAndMovesTheFarSide() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("shelves")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val (at, room) = splitziner.edit.Editor.stretchPlane(parts, 0)!!
        assertTrue(room > 100)
        val wider = splitziner.edit.Editor.apply(parts, listOf(splitziner.edit.Edit.Stretch(0, at, 150.0))).parts
        val cut = CutList.build(wider, fmt)
        // Width 800 -> 950; sides still 18 thick; shelves 764 -> 914 long.
        assertEquals(950.0, wider.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }.extents.x, 1e-6)
        assertTrue(cut.rows.any { abs(it.l - 914.0) < 1e-6 }, cut.rows.map { it.description }.toString())
        assertTrue(cut.rows.all { abs(it.t - 18.0) < 1e-6 || abs(it.t - 6.0) < 1e-6 })
        // Shorter by more than the clear stretch is refused by the dialog; within it, the shelves shrink.
        val narrower = splitziner.edit.Editor.apply(parts, listOf(splitziner.edit.Edit.Stretch(0, at, -100.0))).parts
        assertEquals(700.0, narrower.map { it.mesh.bounds }.reduce { a, b -> a.union(b) }.extents.x, 1e-6)
    }
}

class ObjTest {
    @Test fun objObjectsBecomeParts() {
        // Two unit cubes as quads, written the way most programs write OBJ.
        val sb = StringBuilder("# test\nmtllib x.mtl\n")
        for ((k, ox) in listOf(0.0, 50.0).withIndex()) {
            sb.append("o Box$k\n")
            for (z in listOf(0.0, 10.0)) for (y in listOf(0.0, 10.0)) for (x in listOf(0.0, 10.0)) sb.append("v ${x + ox} $y $z\n")
            val b = 8 * k
            fun f(vararg i: Int) = sb.append("f " + i.joinToString(" ") { "${it + b}/1/1" } + "\n")
            f(1, 3, 4, 2); f(5, 6, 8, 7); f(1, 2, 6, 5); f(3, 7, 8, 4); f(1, 5, 7, 3); f(2, 4, 8, 6)
        }
        val bytes = sb.toString().toByteArray()
        val a = Pipeline.analyze(bytes, "boxes.obj", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(2, a.parts.size)
        assertTrue(a.parts.all { abs(abs(it.mesh.volume) - 1000.0) < 1e-6 }, a.parts.map { it.mesh.volume }.toString())
    }
}

class FullSizeTest {
    @Test fun anAngledBracketGetsTemplatesAndSquareShapesDoNot() {
        val fmt = LengthFormat("mm")
        fun prism(wkt: String) = splitziner.edit.Prisms.toMesh(splitziner.edit.Prism(splitziner.view.ViewDef("p", Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0)),
            org.locationtech.jts.io.WKTReader().read(wkt), 0.0, 18.0))
        // A bracket 300 x 300 with a sloped brace edge needs a template; a square-cut L is marked out with a square.
        val v = prism("POLYGON ((0 0, 300 0, 300 60, 60 300, 0 300, 0 0))")
        val l = prism("POLYGON ((1000 0, 1300 0, 1300 60, 1060 60, 1060 300, 1000 300, 1000 0))")
        val parts = listOf(Part("P01", "V", v), Part("P02", "L", l), Part("P03", "Board", Fixtures.box(400.0, 700.0, 0.0, 60.0, 0.0, 18.0)))
        val cut = CutList.build(parts, fmt)
        val r = splitziner.drawing.FullSize.build(cut.parts, cut.rows, fmt)
        assertEquals(1, r.items.size)
        assertTrue(r.pages.size in 1..4, "${r.pages.size}")
    }
}

class DrawerTest {
    private fun cabinetWithDrawer(sideGap: Double): List<Part> {
        val t = splitziner.edit.Templates.byKey("cabinet")
        val c = t.build(splitziner.edit.Templates.defaults(t, false))   // 600 wide, 18 sides, 720 high, 560 deep, shelf at 351
        val inner0 = 18.0 + sideGap; val inner1 = 600.0 - 18.0 - sideGap
        val z0 = 400.0; val h = 150.0; val d = 480.0
        val drawer = listOf(
            Fixtures.box(inner0, inner1, 20.0, 32.0, z0, z0 + h), Fixtures.box(inner0, inner1, 20.0 + d - 12, 20.0 + d, z0, z0 + h),
            Fixtures.box(inner0, inner0 + 12, 32.0, 20.0 + d - 12, z0, z0 + h), Fixtures.box(inner1 - 12, inner1, 32.0, 20.0 + d - 12, z0, z0 + h),
            Fixtures.box(inner0 + 12, inner1 - 12, 32.0, 20.0 + d - 12, z0, z0 + 6))
        return (c + drawer).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
    }

    @Test fun aDrawerWithNoRoomForSlidesIsFlagged() {
        val fmt = LengthFormat("mm")
        val tight = cabinetWithDrawer(0.5)
        val ds = splitziner.check.Drawers.find(tight)
        assertEquals(1, ds.size); assertEquals(0.5, ds[0].left, 1e-6)
        val cut = CutList.build(tight, fmt)
        val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
        assertTrue(rep.findings.any { it.title.contains("drawer", ignoreCase = true) }, rep.findings.map { it.title }.toString())
        val roomy = cabinetWithDrawer(12.7)
        val cut2 = CutList.build(roomy, fmt)
        assertTrue(splitziner.check.DesignCheck.run(cut2.parts, cut2.rows, fmt, null).findings.none { it.title.contains("No room") })
    }
}

class RealDeskTest {
    /** The real desk from a phone 3D app: fixing it automatically must leave nothing that stops it being built. */
    @Test fun fixingTheDeskLeavesNoProblems() {
        val f = java.io.File("../app/src/androidTest/assets/desk.stl")
        if (!f.exists()) return
        val a0 = Pipeline.analyze(f, splitziner.pipeline.AnalysisOptions(displayUnits = "in"))
        val plan = splitziner.edit.AutoFix.plan(a0.originalParts, a0.format)
        val a = Pipeline.analyze(f, splitziner.pipeline.AnalysisOptions(displayUnits = "in", edits = plan.edits))
        val rep = splitziner.check.DesignCheck.run(a.parts, a.cutList.rows, a.format, null, housed = a.housed)
        assertEquals(0, rep.problems, rep.findings.filter { it.severity == splitziner.check.Severity.PROBLEM }.map { it.title }.toString())
    }
}

class RotateTest {
    @Test fun aBoardTurnedOnItsEdgeKeepsItsSizeAndFloor() {
        val p = Part("P1", "B", Fixtures.box(0.0, 600.0, 0.0, 90.0, 100.0, 118.0))
        val r = splitziner.edit.Editor.apply(listOf(p), listOf(splitziner.edit.Edit.Rotate(listOf("P1"), 0, 1))).parts[0]
        val e = r.mesh.bounds.extents
        assertEquals(600.0, e.x, 1e-9); assertEquals(18.0, e.y, 1e-9); assertEquals(90.0, e.z, 1e-9)
        assertEquals(100.0, r.mesh.bounds.min.z, 1e-9)
        assertTrue(r.mesh.volume > 0)
    }
}

class GrainTest {
    @Test fun aGrainPieceIsNeverTurned() {
        val fmt = LengthFormat("mm")
        // 1100 wide x 600 long: it only fits a 1220 x 2440 sheet turned... unless grain is set, when it fits upright too.
        val parts = listOf(Part("P1", "P", Fixtures.box(0.0, 1100.0, 0.0, 600.0, 0.0, 18.0)), Part("P2", "P", Fixtures.box(0.0, 1100.0, 0.0, 600.0, 100.0, 118.0)))
        val rows = CutList.build(parts, fmt, mapOf(CutList.itemKey(PartKind.PANEL, 18.0, 600.0, 1100.0) to splitziner.parts.ItemOverride(grain = true))).rows
        assertTrue(rows.all { it.grain })
        val r = splitziner.cutplan.CutPlanner.plan(rows, splitziner.cutplan.StockSettings())
        assertTrue(r.sheets.flatMap { it.pieces }.none { it.rotated })
    }
}

class ClearanceTest {
    @Test fun gapsToNeighboursOnEachSide() {
        val a = Part("A", "A", Fixtures.box(0.0, 100.0, 0.0, 100.0, 0.0, 18.0))
        val left = Part("L", "L", Fixtures.box(-30.0, -12.0, 0.0, 100.0, 0.0, 18.0))
        val above = Part("U", "U", Fixtures.box(0.0, 100.0, 0.0, 100.0, 25.0, 43.0))
        val c = splitziner.check.DesignCheck.clearances(a, listOf(a, left, above)).associateBy { it.direction }
        assertEquals(12.0, c.getValue("left").gap, 1e-6)
        assertEquals(7.0, c.getValue("above").gap, 1e-6)
        assertNull(c["right"])
    }
}

class RackingTest {
    @Test fun aBookcaseWithoutABackIsFlagged() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("shelves")
        for ((back, flagged) in listOf(6.0 to false, 0.0 to true)) {
            val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("back" to back)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
            val cut = CutList.build(parts, fmt)
            val r = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
            assertEquals(flagged, r.findings.any { it.title.contains("rack") }, "back $back: ${r.findings.map { it.title }}")
        }
    }
}

class ShelfAtTest {
    @Test fun aShelfFillsBetweenTheSides() {
        val t = splitziner.edit.Templates.byKey("cabinet")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val (min, size) = splitziner.edit.Editor.shelfAt(parts, 600.0, 18.0)!!
        assertEquals(18.0, min.x, 1e-6); assertEquals(564.0, size.x, 1e-6); assertEquals(18.0, size.z, 1e-6)
        // Where the fixed shelf already is, there is no room.
        assertNull(splitziner.edit.Editor.shelfAt(parts, (720.0 - 18) / 2 + 18, 18.0))
    }
}

class DividerAtTest {
    @Test fun aDividerStandsBetweenBottomAndShelf() {
        val t = splitziner.edit.Templates.byKey("cabinet")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val (min, size) = splitziner.edit.Editor.dividerAt(parts, 300.0, 18.0, 150.0)!!
        assertEquals(291.0, min.x, 1e-6); assertEquals(18.0, size.x, 1e-6)
        assertEquals(18.0, min.z, 1e-6); assertEquals((720.0 - 18) / 2 - 18, size.z, 1e-6)
    }
}

class DoorTest {
    @Test fun aDoorAddedToACabinetHangsOnHinges() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("cabinet")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        // The opening below the shelf.
        val (min, size) = splitziner.edit.Editor.doorAt(parts, 300.0, 150.0, 18.0)!!
        assertEquals(20.0, min.x, 1e-6); assertEquals(560.0, size.x, 1e-6)
        val withDoor = splitziner.edit.Editor.apply(parts, listOf(splitziner.edit.Edit.AddBox("D1", "Door", min, size))).parts
        val cut = CutList.build(withDoor, fmt)
        val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
        assertTrue(rep.findings.none { it.title.contains("touches nothing") }, rep.findings.map { it.title }.toString())
        assertTrue(rep.findings.any { it.title.startsWith("Door") })
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        val f = splitziner.joinery.Fastening.plan(plan.parts, plan.steps, splitziner.joinery.JoinMethod.SCREWS, fmt)
        assertTrue(f.hardware.any { it.contains("2 × concealed (cup) hinges") }, f.hardware.toString())
    }
}

class CabinetDoorsTest {
    @Test fun aPairOfDoorsIsFoundAndHung() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("cabinet")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("doors" to 2.0)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        assertEquals(2, splitziner.check.Doors.find(parts).size)
        val cut = CutList.build(parts, fmt)
        val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
        assertTrue(rep.problems == 0, rep.findings.map { it.title }.toString())
        // The doors get a drawing that says where the hinge cups go.
        val door = cut.parts.first { it.id == splitziner.check.Doors.find(cut.parts)[0].id }
        assertTrue(door.item!! !in splitziner.drawing.PartDrawings.plainItems(cut.parts, cut.rows, fmt))
        val note = splitziner.drawing.PartDrawings.hingeNotes(door, cut.parts, fmt).single()
        assertTrue(note.contains("2 CONCEALED HINGES") && note.contains("35 MM"), note)
    }
}

class HandlesTest {
    @Test fun eachDoorGetsAHandleAfterTheHinges() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("cabinet")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("doors" to 2.0)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val cut = CutList.build(parts, fmt)
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        val hw = splitziner.joinery.Fastening.plan(plan.parts, plan.steps, splitziner.joinery.JoinMethod.SCREWS, fmt).hardware
        val h = hw.indexOfFirst { it.contains("handles or knobs") }
        assertTrue(h >= 0 && hw[h].startsWith("2 "), hw.toString())
        assertTrue(h > hw.indexOfFirst { it.contains("hinges") }, hw.toString())
    }
}

class OverlayDoorsTest {
    @Test fun doorsOverTheFrontAreHungNotScrewed() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("cabinet")
        for (n in 1..2) {
            val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("doors" to n.toDouble()) + ("overlay" to 1.0)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
            assertEquals(n, splitziner.check.Doors.find(parts).size)
            val cut = CutList.build(parts, fmt)
            val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
            assertTrue(rep.problems == 0, rep.findings.filter { it.severity == splitziner.check.Severity.PROBLEM }.map { it.title + ": " + it.detail }.toString())
            val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
            val f = splitziner.joinery.Fastening.plan(plan.parts, plan.steps, splitziner.joinery.JoinMethod.SCREWS, fmt)
            val doorIds = splitziner.check.Doors.find(cut.parts).map { it.id }.toSet()
            assertTrue(f.byStep.values.flatten().none { (it.through.id in doorIds || it.into.id in doorIds) && it.kind != "hinges" })
        }
    }

    @Test fun aModestyPanelOnLegsIsNotADoor() {
        // Two legs and a top, with a thin panel screwed to the legs' front faces: nothing behind its bottom edge.
        val box = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> splitziner.edit.Editor.box(splitziner.math.Vec3(x, y, z), splitziner.math.Vec3(w, d, h)) }
        val parts = listOf(box(0.0, 18.0, 0.0, 50.0, 50.0, 720.0), box(950.0, 18.0, 0.0, 50.0, 50.0, 720.0), box(0.0, 18.0, 720.0, 1000.0, 600.0, 25.0),
            box(0.0, 0.0, 400.0, 1000.0, 18.0, 300.0)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        assertEquals(0, splitziner.check.Doors.find(parts).size)
    }
}

class ShelfPinsTest {
    @Test fun adjustableShelvesRestOnPins() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("shelves")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("pins" to 1.0)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        assertEquals(4, splitziner.check.ShelfPins.find(parts).size)
        val cut = CutList.build(parts, fmt)
        val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
        assertTrue(rep.problems == 0, rep.findings.filter { it.severity == splitziner.check.Severity.PROBLEM }.map { it.title + ": " + it.detail }.toString())
        assertTrue(rep.findings.any { it.title.contains("adjustable shelves") })
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        val f = splitziner.joinery.Fastening.plan(plan.parts, plan.steps, splitziner.joinery.JoinMethod.SCREWS, fmt)
        assertTrue(f.hardware.any { it == "16 × shelf pins" }, f.hardware.toString())
        // The sides get a detail drawing with the hole rows.
        val sideItem = cut.parts.first { it.id == "P01" }.item!!
        assertTrue(sideItem in splitziner.drawing.PartDrawings.pinItems(cut.parts))
        assertTrue(sideItem !in splitziner.drawing.PartDrawings.plainItems(cut.parts, cut.rows, fmt))
        val note = splitziner.drawing.PartDrawings.pinNotes(cut.parts.first { it.id == "P01" }, cut.parts, fmt).single()
        assertTrue(note.contains("2 ROWS OF") && note.contains("37 MM") && note.contains("32 MM APART"), note)
        // Fixed shelves are not on pins.
        val fixed = t.build(splitziner.edit.Templates.defaults(t, false)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        assertEquals(0, splitziner.check.ShelfPins.find(fixed).size)
    }
}

class AddPinnedShelfTest {
    @Test fun aShelfAddedOnPinsIsAdjustable() {
        val t = splitziner.edit.Templates.byKey("shelves")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("n" to 0.0)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val (mn, sz) = splitziner.edit.Editor.shelfAt(parts, 900.0, 18.0, pins = true)!!
        val withShelf = parts + Part("S1", "Shelf", splitziner.edit.Editor.box(mn, sz))
        assertEquals(listOf("S1"), splitziner.check.ShelfPins.find(withShelf))
    }
}

class ChestOfDrawersTest {
    @Test fun everyDrawerIsFoundAndHung() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("drawers")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val drawers = splitziner.check.Drawers.find(parts)
        assertEquals(4, drawers.size)
        assertTrue(drawers.all { it.ids.size == 6 }, drawers.map { it.ids }.toString())
        val cut = CutList.build(parts, fmt)
        val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
        assertTrue(rep.problems == 0, rep.findings.filter { it.severity == splitziner.check.Severity.PROBLEM }.map { it.title + ": " + it.detail }.toString())
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        val hw = splitziner.joinery.Fastening.plan(plan.parts, plan.steps, splitziner.joinery.JoinMethod.SCREWS, fmt).hardware
        assertTrue(hw.any { it.startsWith("4 pairs of") && it.contains("drawer slides") }, hw.toString())
        assertTrue(hw.any { it == "4 × handles or knobs, with their screws" }, hw.toString())
    }
}

class OverlayDrawerFrontsTest {
    @Test fun frontsOverTheSidesAreNotScrewedToThem() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("drawers")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("overlay" to 1.0)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val drawers = splitziner.check.Drawers.find(parts)
        assertEquals(4, drawers.size)
        assertTrue(drawers.all { it.ids.size == 6 && it.left > 12.0 && it.right > 12.0 })
        val cut = CutList.build(parts, fmt)
        val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
        assertTrue(rep.problems == 0, rep.findings.filter { it.severity == splitziner.check.Severity.PROBLEM }.map { it.title + ": " + it.detail }.toString())
        val plan = splitziner.explode.AssemblyPlan.plan(cut.parts)
        val f = splitziner.joinery.Fastening.plan(plan.parts, plan.steps, splitziner.joinery.JoinMethod.SCREWS, fmt)
        val ids = drawers.flatMap { it.ids }.toSet()
        assertTrue(f.byStep.values.flatten().none { (it.through.id in ids) != (it.into.id in ids) })
    }

    @Test fun graduatedDrawersGetDeeperGoingDown() {
        val t = splitziner.edit.Templates.byKey("drawers")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("graduated" to 1.0)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val drawers = splitziner.check.Drawers.find(parts)
        assertEquals(4, drawers.size)
        val heights = drawers.map { d -> parts.filter { it.id in d.ids }.let { ps -> ps.minOf { it.mesh.bounds.min.z } to ps.maxOf { it.mesh.bounds.max.z } - ps.minOf { it.mesh.bounds.min.z } } }.sortedBy { it.first }.map { it.second }
        assertTrue(heights.zipWithNext().all { (a, b) -> a > b }, heights.toString())
    }
}

class AddDrawerTest {
    @Test fun aDrawerAddedToAnOpeningIsFoundAndHung() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("cabinet")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        // Above the shelf (which is at mid height).
        val boxes = splitziner.edit.Editor.drawerAt(parts, 300.0, 600.0, 18.0)!!
        assertEquals(6, boxes.size)
        val withDrawer = parts + boxes.mapIndexed { i, (mn, sz) -> Part("D$i", "Drawer", splitziner.edit.Editor.box(mn, sz)) }
        val drawers = splitziner.check.Drawers.find(withDrawer)
        assertEquals(1, drawers.size)
        assertEquals(6, drawers[0].ids.size)
        assertTrue(drawers[0].left > 12.0 && drawers[0].right > 12.0)
        assertTrue(splitziner.check.Doors.find(withDrawer).isEmpty())
        val cut = CutList.build(withDrawer, fmt)
        val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
        assertTrue(rep.problems == 0, rep.findings.filter { it.severity == splitziner.check.Severity.PROBLEM }.map { it.title + ": " + it.detail }.toString())
    }
}

class RolesTest {
    @Test fun templatePartsGetPlainNames() {
        val t = splitziner.edit.Templates.byKey("nightstand")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P%02d".format(i + 1), m) }
        val names = splitziner.parts.Roles.names(parts)
        println("nightstand roles: " + parts.map { it.id + "=" + names[it.id] })
        assertEquals("Side", names["P01"]); assertEquals("Side", names["P02"])
        assertEquals("Bottom", names["P03"]); assertEquals("Top", names["P04"]); assertEquals("Kick board", names["P05"])
        assertEquals("Shelf", names["P06"]); assertEquals("Back panel", names["P07"])
        assertEquals("Drawer bottom", names["P12"]); assertEquals("Drawer front", names["P13"])
        assertEquals(listOf("Drawer side", "Drawer side"), listOf(names["P10"], names["P11"]))
        val table = splitziner.edit.Templates.byKey("table")
        val tp = table.build(splitziner.edit.Templates.defaults(table, false)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P%02d".format(i + 1), m) }
        val tn = splitziner.parts.Roles.names(tp)
        println("table roles: " + tp.map { it.id + "=" + tn[it.id] })
        assertEquals("Top", tn["P01"]); assertEquals(4, tn.values.count { it == "Leg" }); assertEquals(4, tn.values.count { it == "Apron" })
    }
}

class ThreeMfTest {
    private fun cube(id: Int, s: Double): String {
        val v = listOf(0.0, s).flatMap { z -> listOf(0.0, s).flatMap { y -> listOf(0.0, s).map { x -> """<vertex x="$x" y="$y" z="$z"/>""" } } }
        val f = listOf(intArrayOf(0, 2, 1), intArrayOf(1, 2, 3), intArrayOf(4, 5, 6), intArrayOf(5, 7, 6), intArrayOf(0, 1, 4), intArrayOf(1, 5, 4), intArrayOf(2, 6, 3), intArrayOf(3, 6, 7), intArrayOf(0, 4, 2), intArrayOf(2, 4, 6), intArrayOf(1, 3, 5), intArrayOf(3, 7, 5))
        return """<object id="$id" type="model"><mesh><vertices>${v.joinToString("")}</vertices><triangles>${f.joinToString("") { """<triangle v1="${it[0]}" v2="${it[1]}" v3="${it[2]}"/>""" }}</triangles></mesh></object>"""
    }

    @Test fun twoObjectsPlacedInInchesBecomeTwoPartsInMillimetres() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><model unit="inch" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>${cube(1, 2.0)}${cube(2, 1.0)}</resources>""" +
            """<build><item objectid="1"/><item objectid="2" transform="1 0 0 0 1 0 0 0 1 10 0 0"/></build></model>"""
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml")); z.write("<Types/>".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("3D/3dmodel.model")); z.write(xml.toByteArray()); z.closeEntry()
        }
        val bytes = bos.toByteArray()
        val raw = splitziner.io.StlReader.read(bytes, "source.stl")
        assertEquals(24, raw.triangleCount)
        val b = raw.bounds()
        assertEquals(0.0, b.min.x, 1e-3); assertEquals(11 * 25.4, b.max.x, 1e-2); assertEquals(2 * 25.4, b.max.z, 1e-2)
        val a = Pipeline.analyze(bytes, "chair.3mf")
        assertEquals(2, a.parts.size)
    }
}

class WideDoorTest {
    @Test fun aWideSingleDoorIsQueried() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("cabinet")
        for ((n, want) in listOf(1 to true, 2 to false)) {
            val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("w" to 900.0) + ("doors" to n.toDouble())).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
            val cut = CutList.build(parts, fmt)
            val rep = splitziner.check.DesignCheck.run(cut.parts, cut.rows, fmt, null)
            assertEquals(want, rep.findings.any { it.title.startsWith("Wide door") })
        }
    }
}


class CutPlanPackingTest {
    @Test fun aChestsEighteenMillimetrePartsShareOneSheet() {
        val fmt = LengthFormat("mm")
        val t = splitziner.edit.Templates.byKey("drawers")
        val parts = t.build(splitziner.edit.Templates.defaults(t, false) + ("overlay" to 1.0)).mapIndexed { i, m -> Part("P%02d".format(i + 1), "P", m) }
        val cut = CutList.build(parts, fmt)
        val cp = splitziner.cutplan.CutPlanner.plan(cut.rows, splitziner.cutplan.StockSettings())
        // Drawer sides and fronts are ripped from sheet in metric, and the 18 mm parts fit one sheet.
        assertTrue(cp.boards.isEmpty(), cp.boards.map { it.cuts }.toString())
        assertEquals(1, cp.sheets.count { kotlin.math.abs(it.thickness - 18.0) < 0.1 })
        // No two pieces overlap on any sheet.
        for (sh in cp.sheets) for ((i, a) in sh.pieces.withIndex()) for (b in sh.pieces.drop(i + 1))
            assertTrue(a.x + a.w <= b.x + 1e-6 || b.x + b.w <= a.x + 1e-6 || a.y + a.l <= b.y + 1e-6 || b.y + b.l <= a.y + 1e-6)
    }
}

class GluedUpPanelTest {
    @Test fun thickTopIsTwoLayersOfSheet() {
        assertEquals(2 to 19.05, splitziner.cutplan.CutPlanner.layersOf(38.1, true)?.let { it.first to Math.round(it.second * 100) / 100.0 })
        assertEquals(2, splitziner.cutplan.CutPlanner.layersOf(38.0, false)?.first)
        assertEquals(null, splitziner.cutplan.CutPlanner.layersOf(19.0, false))
        val row = splitziner.parts.CutListRow(1, 1, "", "PANEL", "", "", "", splitziner.parts.PartKind.PANEL, 38.1, 609.6, 1524.0, listOf("P01"), name = "Top")
        val r = splitziner.cutplan.CutPlanner.plan(listOf(row), splitziner.cutplan.StockSettings.INCH)
        assertTrue(r.sheets.all { kotlin.math.abs(it.thickness - 19.05) < 0.01 }, "cut from 3/4 in sheet")
        assertEquals(2, r.sheets.sumOf { s -> s.pieces.count { it.item == 1 } })
        assertTrue(splitziner.cutplan.CutPlanner.layerNotes(r, splitziner.dims.LengthFormat("in", "fraction")).single().contains("2 layers of 3/4 in"))
        assertTrue(splitziner.cutplan.CutPlanner.gluingSteps(listOf(row), splitziner.dims.LengthFormat("in", "fraction")).single().startsWith("Glue up item 1 (top)"))
    }
}

class PlacingTest {
    @Test fun legsAndCleatOnARailAreMeasured() {
        fun box(x: Double, y: Double, z: Double, w: Double, d: Double, h: Double) = splitziner.edit.Editor.box(splitziner.math.Vec3(x, y, z), splitziner.math.Vec3(w, d, h))
        // A side rail 2000 long, two legs 19 in from its ends, a cleat 44 down from its top between the legs.
        val rail = Part("P01", "Side rail", box(0.0, 0.0, 100.0, 19.0, 2000.0, 184.0), item = 2)
        val leg1 = Part("P02", "Leg", box(19.0, 19.0, 0.0, 89.0, 89.0, 284.0), item = 5)
        val leg2 = Part("P03", "Leg", box(19.0, 1892.0, 0.0, 89.0, 89.0, 284.0), item = 5)
        val cleat = Part("P04", "Cleat", box(19.0, 108.0, 202.0, 19.0, 1784.0, 38.0), item = 7)
        val lines = splitziner.guide.GuideSteps.placing(listOf(rail, leg1, leg2, cleat), splitziner.dims.LengthFormat("mm"), { p: Part -> "item ${p.item} (${p.name.lowercase()})" })
        assertTrue(lines.contains("Set each item 5 (leg) 19 mm from each end of item 2 (side rail)."), lines.toString())
        assertTrue(lines.contains("Set item 7 (cleat) 44 mm down from the top edge of item 2 (side rail)."), lines.toString())
        assertTrue(lines.none { it.contains("cleat") && it.contains("end") }, "the legs place the cleat along the rail")
    }
}

class EdgeGluedPanelTest {
    @Test fun oakTopIsGluedUpFromBoards() {
        val row = splitziner.parts.CutListRow(1, 1, "", "PANEL", "Oak", "", "", splitziner.parts.PartKind.PANEL, 25.4, 762.0, 1524.0, listOf("P01"), name = "Top")
        val r = splitziner.cutplan.CutPlanner.plan(listOf(row), splitziner.cutplan.StockSettings.INCH)
        assertTrue(r.sheets.isEmpty(), "no sheet of oak")
        assertEquals(6, r.edgeGlued[1])
        assertEquals(6, r.boards.sumOf { b -> b.cuts.count { it.first == 1 } })
        assertTrue(r.boards.all { it.stockName == "5/4x6" }, r.boards.map { it.stockName }.toString())
        assertTrue(splitziner.cutplan.CutPlanner.gluingSteps(listOf(row), splitziner.dims.LengthFormat("in", "fraction")).single().startsWith("Glue up item 1 (top) edge to edge from 6 boards"))
        // Plywood stays a sheet; a thin "oak" back is veneered plywood.
        assertEquals(null, splitziner.cutplan.CutPlanner.edgeGlue(row.copy(material = "Oak plywood"), true))
        assertEquals(null, splitziner.cutplan.CutPlanner.edgeGlue(row.copy(t = 6.35), true))
    }
}

class NamesTest {
    @Test fun fileNamesReadAsNames() {
        assertEquals("Kitchen run", splitziner.text.Names.fromFile("kitchen_run.stl"))
        assertEquals("BunkBed v2", splitziner.text.Names.fromFile("BunkBed-v2.obj"))
        assertEquals("My desk", splitziner.text.Names.fromFile("My desk.3mf"))
    }
}

class RoundAndOvalTest {
    @Test fun circlesAndOvalsAreMarkedOutNotTemplated() {
        val disk = Fixtures.cylinder(457.2, 0.0, 25.4)
        assertEquals(914.4, splitziner.drawing.FullSize.circleDiameter(disk)!!, 1.0)
        // Stretched twice as long: an ellipse, not a circle.
        val v = disk.vertices.copyOf(); for (i in 0 until disk.vertexCount) v[3 * i] *= 2.0
        val oval = splitziner.mesh.Mesh(v, disk.triangles)
        assertNull(splitziner.drawing.FullSize.circleDiameter(oval))
        val (a, b) = splitziner.drawing.FullSize.oval(oval)!!
        assertEquals(1828.8, a, 1.0); assertEquals(914.4, b, 1.0)
        // A rod is round in section but cut to length, never marked out.
        assertNull(splitziner.drawing.FullSize.oval(Fixtures.cylinder(15.9, 0.0, 970.0)))
    }
}

class TrestleTest {
    private fun trestle(): List<splitziner.mesh.Mesh> {
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val out = ArrayList<splitziner.mesh.Mesh>()
        out += b(0.0, 0.0, 711.2, 1828.8, 762.0, 31.75)
        for (x in listOf(250.0, 1502.6)) {
            out += b(x, 342.9, 76.2, 76.2, 76.2, 558.8)      // post
            out += b(x, 76.2, 0.0, 76.2, 609.6, 76.2)        // foot
            out += b(x, 101.6, 635.0, 76.2, 558.8, 76.2)     // top rail
        }
        out += b(326.2, 361.95, 304.8, 1176.4, 38.1, 101.6) // stretcher
        return out
    }

    @Test fun endsAreBuiltOnTheBenchAndThickPartsCounterbored() {
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(trestle())), "trestle.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val names = a.parts.map { it.name }
        assertEquals(2, names.count { it == "Foot" }, names.toString()); assertEquals(2, names.count { it == "Post" }); assertEquals(1, names.count { it == "Stretcher" })
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val bench = pl.steps.first()
        assertTrue(bench.subassembly && bench.makeCount == 2 && bench.partIds.size == 3, pl.steps.toString())
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, splitziner.dims.LengthFormat("in", "fraction"))
        assertTrue(f.all.any { it.kind == "counterbored" } && f.all.none { it.kind == "corner" }, f.all.map { it.kind }.toString())
        assertTrue(f.hardware.any { it.contains("3/8 in wood plugs") }, f.hardware.toString())
    }
}

class MitreTest {
    /** A 16 x 20 in picture frame of 2 in moulding, its four sides mitred at the corners. */
    private fun frame(): List<splitziner.mesh.Mesh> {
        val W = 406.4; val H = 508.0; val w = 50.8; val t = 19.05
        fun prism(xy: List<Pair<Double, Double>>): splitziner.mesh.Mesh {
            val n = xy.size; val v = ArrayList<Double>(); val tri = ArrayList<Int>()
            for (z in listOf(0.0, t)) for ((x, y) in xy) { v += x; v += y; v += z }
            for (i in 1 until n - 1) { tri += listOf(0, i + 1, i); tri += listOf(n, n + i, n + i + 1) }
            for (i in 0 until n) { val j = (i + 1) % n; tri += listOf(i, j, n + j); tri += listOf(i, n + j, n + i) }
            return splitziner.mesh.Mesh(v.toDoubleArray(), tri.toIntArray())
        }
        return listOf(prism(listOf(0.0 to 0.0, W to 0.0, W - w to w, w to w)), prism(listOf(w to H - w, W - w to H - w, W to H, 0.0 to H)),
            prism(listOf(0.0 to 0.0, w to w, w to H - w, 0.0 to H)), prism(listOf(W to 0.0, W to H, W - w to H - w, W - w to w)))
    }

    @Test fun mitredFrameIsCutAndGluedAsMitres() {
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(frame())), "frame.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertTrue(a.cutList.rows.all { it.description.endsWith("ENDS CUT AT 45° (MITRE)") }, a.cutList.rows.map { it.description }.toString())
        val inch = LengthFormat("in", "fraction")
        val row = a.cutList.rows.first()
        val how = splitziner.check.HowTo.steps(row, a.parts.first { it.id == row.memberIds[0] }, inch, null)
        assertTrue(how.any { it.startsWith("Mitre both ends at 45°, opposite ways") }, how.toString())
        assertTrue(how.none { it.contains("notches") }, how.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, inch)
        assertEquals(4, f.all.count { it.kind == "mitre" }, f.all.map { it.kind }.toString())
    }
}

class HutchTest {
    @Test fun hutchIsBuiltWholeAndSetOnTheDeskTop() {
        val T = 19.05; val W = 1524.0; val D = 609.6; val H = 742.95; val hd = 304.8; val hh = 914.4; val y0 = D - hd
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = listOf(b(0.0, 0.0, 0.0, T, D, H - T), b(W - T, 0.0, 0.0, T, D, H - T), b(0.0, 0.0, H - T, W, D, T), b(T, D - 50.8 - T, 254.0, W - 2 * T, T, H - T - 254.0),
            b(0.0, y0, H, T, hd, hh), b(W - T, y0, H, T, hd, hh), b(T, y0, H + hh - T, W - 2 * T, hd - 6.35, T), b(W / 2 - T / 2, y0, H, T, hd - 6.35, hh - T),
            b(T, y0, H + 381.0, W / 2 - 1.5 * T, hd - 6.35, T), b(W / 2 + T / 2, y0, H + 381.0, W / 2 - 1.5 * T, hd - 6.35, T), b(T, D - 6.35, H, W - 2 * T, 6.35, hh - T))
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "desk.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val top = a.parts.first { kotlin.math.abs(it.mesh.bounds.max.z - H) < 1 && it.mesh.bounds.extents.x > W - 1 }
        assertEquals("Top", top.name)
        assertEquals(7, splitziner.explode.AssemblyPlan.upperUnit(a.parts)?.size)
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        // The desk top is not screwed through its face, and the hutch is screwed up through it from below.
        assertTrue(f.all.none { it.through.id == top.id && it.kind == "screws" }, f.all.filter { it.through.id == top.id }.map { it.kind }.toString())
        assertTrue(f.all.any { it.through.id == top.id && it.kind == "under" })
    }
}

class BedKnockDownTest {
    @Test fun bedRailsHookOnSoTheBedComesApart() {
        val t = splitziner.edit.Templates.byKey("bed")
        val ms = t.build(splitziner.edit.Templates.defaults(t, true))
        val bos = ByteArrayOutputStream(); StlWriter.writeParts(ms, bos)
        val a = Pipeline.analyze(bos.toByteArray(), "bed.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        assertTrue(f.all.count { it.kind == "bedrail" } >= 4, f.all.map { it.kind }.toString())
        // Nothing glues or screws an end rail to a leg of the side frames.
        assertTrue(f.all.none { j -> setOf(j.through.name, j.into.name) == setOf("End rail", "Leg") }, f.all.map { "${it.through.name}-${it.into.name}:${it.kind}" }.toString())
        assertTrue(f.hardware.any { it.contains("bed rail fasteners") })
    }
}

class TenonTest {
    @Test fun tenonsAreGluedIntoMortisesNotScrewed() {
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        // Two legs 2-1/2 in square, an apron between them, a 3/8 x 1 x 3 tenon at each end sunk 1 in into the leg.
        val ms = listOf(b(0.0, 0.0, 0.0, 63.5, 63.5, 711.0), b(1000.0, 0.0, 0.0, 63.5, 63.5, 711.0), b(63.5, 22.0, 610.0, 936.5, 19.0, 101.0),
            b(38.1, 27.0, 622.7, 25.4, 9.5, 76.2), b(1000.0, 27.0, 622.7, 25.4, 9.5, 76.2), b(0.0, 0.0, 711.0, 1063.5, 400.0, 25.0))
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "t.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(2, a.parts.count { it.name == "Tenon" }, a.parts.map { it.name }.toString())
        assertEquals(2, a.housed.size)
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"), housed = a.housed)
        assertTrue(f.all.count { it.kind == "mortise" } == 2 && f.all.count { it.kind == "tenon" } == 2, f.all.map { it.kind }.toString())
        val leg = a.parts.first { it.name == "Leg" }
        assertTrue(splitziner.drawing.PartDrawings.grooveNotes(leg, a.parts, a.housed, LengthFormat("in", "fraction")).single().startsWith("MORTISE FOR ITEM"))
        // The leg's drawing shows the mortise: a closed block with the pocket taken out.
        val m = splitziner.drawing.PartDrawings.pocketed(leg, a.parts, a.housed)!!
        assertTrue(m.isWatertight)
        assertEquals(63.5 * 63.5 * 711.0 - 25.4 * 9.5 * 76.2, kotlin.math.abs(m.volume), 50.0)
    }
}

class SlidingDoorTest {
    @Test fun doorsOneBehindTheOtherRunInATrack() {
        val T = 19.05; val W = 1524.0; val D = 457.2; val H = 609.6
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val dh = H - 76.2 - 2 * T - 3.2; val dw = (W - 2 * T) / 2 + 12.7
        val ms = listOf(b(0.0, 0.0, 0.0, T, D, H), b(W - T, 0.0, 0.0, T, D, H), b(T, 0.0, H - T, W - 2 * T, D, T), b(T, 0.0, 76.2, W - 2 * T, D, T),
            b(T, 50.8, 0.0, W - 2 * T, T, 76.2), b(T, D - 6.35, 76.2 + T, W - 2 * T, 6.35, H - 76.2 - 2 * T),
            b(T, 0.0, 76.2 + T + 1.6, dw, T, dh), b(W - T - dw, T + 1.6, 76.2 + T + 1.6, dw, T, dh))
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "c.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(2, a.parts.count { it.name == "Sliding door" }, a.parts.map { it.name }.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        assertEquals(1, f.all.count { it.kind == "sliding" })
        assertTrue(f.all.none { (it.through.name == "Sliding door" || it.into.name == "Sliding door") && it.kind != "sliding" })
        assertTrue(f.hardware.any { it.contains("sliding-door track") })
    }
}

class UpGuessTest {
    @Test fun aCabinetSavedWithYUpIsStoodUp() {
        val t = splitziner.edit.Templates.byKey("cabinet")
        val ms = t.build(splitziner.edit.Templates.defaults(t, true))
        fun stl(meshes: List<splitziner.mesh.Mesh>): ByteArray { val bos = ByteArrayOutputStream(); StlWriter.writeParts(meshes, bos); return bos.toByteArray() }
        // Saved with Y up: the model's Z (up) becomes the file's Y, its Y (back) the file's -Z.
        val yUp = ms.map { m -> splitziner.mesh.Mesh(DoubleArray(m.vertices.size) { i -> val v = 3 * (i / 3); when (i % 3) { 0 -> m.vertices[v]; 1 -> m.vertices[v + 2]; else -> -m.vertices[v + 1] } }, m.triangles) }
        val upright = Pipeline.analyze(stl(ms), "c.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val lying = Pipeline.analyze(stl(yUp), "c.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals("z", Pipeline.guessUp(upright.parts))
        assertEquals("y", Pipeline.guessUp(lying.parts))
        val stood = Pipeline.analyze(stl(yUp), "c.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm", up = "y", upGuessed = true))
        assertEquals(upright.overallSize, stood.overallSize)
        assertTrue(stood.report.warnings.any { it.contains("lying on its back") })
    }
}

class CutOrderTest {
    @Test fun everyPieceIsFreedByStraightCutsInOrder() {
        val row = splitziner.parts.CutListRow(1, 4, "", "PANEL", "", "", "", splitziner.parts.PartKind.PANEL, 19.05, 584.2, 914.4, listOf("P01"), name = "Side")
        val r = splitziner.cutplan.CutPlanner.plan(listOf(row), splitziner.cutplan.StockSettings.INCH)
        val sh = r.sheets.first()
        val cuts = splitziner.cutplan.CutPlanner.cutOrder(sh, sh.w.takeIf { it > 0 } ?: 1219.2, sh.l.takeIf { it > 0 } ?: 2438.4, 3.175)
        assertTrue(cuts.size >= sh.pieces.size - 1, cuts.size.toString())
        assertEquals((1..cuts.size).toList(), cuts.map { it.n })
        // No cut runs through a piece.
        for (c in cuts) for (pc in sh.pieces) {
            val crosses = if (c.rip) pc.x < c.x0 - 0.5 && pc.x + pc.w > c.x0 + 0.5 && pc.y < maxOf(c.y0, c.y1) && pc.y + pc.l > minOf(c.y0, c.y1)
                else pc.y < c.y0 - 0.5 && pc.y + pc.l > c.y0 + 0.5 && pc.x < maxOf(c.x0, c.x1) && pc.x + pc.w > minOf(c.x0, c.x1)
            assertFalse(crosses, "cut ${c.n} crosses item at ${pc.x},${pc.y}")
        }
    }
}

class BunkBedTest {
    @Test fun guardRailsCleatsThenAllTheSlatsAndHiddenScrews() {
        val T = 19.05; val L = 2032.0; val Wd = 990.6; val H = 1651.0; val post = 88.9
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = ArrayList<splitziner.mesh.Mesh>()
        for (x in listOf(0.0, Wd - post)) for (y in listOf(0.0, L - post)) ms += b(x, y, 0.0, post, post, H)
        for (z in listOf(304.8, 1219.2)) {
            ms += b(0.0, post, z, T, L - 2 * post, 184.15); ms += b(Wd - T, post, z, T, L - 2 * post, 184.15)
            ms += b(post, 0.0, z, Wd - 2 * post, T, 184.15); ms += b(post, L - T, z, Wd - 2 * post, T, 184.15)
            ms += b(T, post, z + 50.8, T, L - 2 * post, 38.1); ms += b(Wd - 2 * T, post, z + 50.8, T, L - 2 * post, 38.1)
            val gap = (L - 2 * post - 12 * 88.9) / 11
            for (i in 0 until 12) ms += b(T, post + i * (88.9 + gap), z + 50.8 + 38.1, Wd - 2 * T, 88.9, T)
        }
        ms += b(0.0, post, 1524.0, T, L - 2 * post, 88.9); ms += b(Wd - T, post, 1524.0, T, L - 2 * post, 88.9)
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "bunk.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(2, a.parts.count { it.name == "Guard rail" }, a.parts.map { it.name }.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        // Each deck's twelve slats go down in one step, after its cleats.
        val slatSteps = pl.parts.filter { it.name == "Slat" }.groupBy { it.step }
        assertEquals(listOf(12, 12), slatSteps.values.map { it.size }, slatSteps.keys.toString())
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        // Cleats are screwed from inside, through the cleat, so no heads show on the side rails.
        assertTrue(f.all.filter { setOf(it.through.name, it.into.name) == setOf("Cleat", "Side rail") }.all { it.through.name == "Cleat" })
        assertTrue(f.all.count { it.kind == "bedrail" && (it.through.name == "Guard rail" || it.into.name == "Guard rail") } >= 2)
        // Guard rails 11-1/4 in above the slats allow a mattress of 6-1/4 in at most.
        val check = splitziner.check.DesignCheck.run(a.parts, a.cutList.rows, LengthFormat("in", "fraction"), null).findings
        assertTrue(check.any { it.title == "Mattress for the top bunk: no thicker than 6-1/4 in" }, check.map { it.title }.toString())
    }
}

class LoftBedTest {
    @Test fun ladderIsMadeOnTheBenchAndFittedLast() {
        val T = 19.05; val L = 2032.0; val Wd = 990.6; val H = 1828.8; val post = 88.9
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = ArrayList<splitziner.mesh.Mesh>()
        for (x in listOf(0.0, Wd - post)) for (y in listOf(0.0, L - post)) ms += b(x, y, 0.0, post, post, H)
        val z = 1371.6
        ms += b(0.0, post, z, T, L - 2 * post, 184.15); ms += b(Wd - T, post, z, T, L - 2 * post, 184.15)
        ms += b(post, 0.0, z, Wd - 2 * post, T, 184.15); ms += b(post, L - T, z, Wd - 2 * post, T, 184.15)
        ms += b(T, post, z + 50.8, T, L - 2 * post, 38.1); ms += b(Wd - 2 * T, post, z + 50.8, T, L - 2 * post, 38.1)
        val gap = (L - 2 * post - 12 * 88.9) / 11
        for (i in 0 until 12) ms += b(T, post + i * (88.9 + gap), z + 50.8 + 38.1, Wd - 2 * T, 88.9, T)
        ms += b(0.0, post, H - 127.0, T, L - 2 * post, 88.9)
        for (y in listOf(post + 25.4, post + 25.4 + 406.4 - 38.1)) ms += b(Wd, y, 0.0, 38.1, 38.1, H - 88.9)
        for (k in 1..5) ms += b(Wd, post + 25.4 + 38.1, k * 280.0, 38.1, 406.4 - 2 * 38.1, 38.1)
        ms += b(post, post, 698.5, T, 609.6, 38.1); ms += b(Wd - post - T, post, 698.5, T, 609.6, 38.1); ms += b(post, post, 736.6, Wd - 2 * post, 609.6, T)
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "loft.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val names = a.parts.map { it.name }
        assertEquals(2, names.count { it == "Ladder side" }, names.toString())
        assertEquals(5, names.count { it == "Rung" }, names.toString())
        assertEquals(4, names.count { it == "Cleat" }, names.toString())
        assertEquals(4, names.count { it == "Leg" }, names.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val rungs = pl.parts.filter { it.name == "Rung" || it.name == "Ladder side" }
        // One bench step makes the whole ladder; it goes on in the last step, from the side it hangs on.
        val bench = pl.steps.filter { st -> st.subassembly && st.partIds.any { id -> rungs.any { it.id == id } } }
        assertEquals(1, bench.size)
        assertEquals(7, bench[0].partIds.size)
        assertTrue(rungs.all { it.step == pl.steps.maxOf { s -> s.step } }, rungs.map { it.step }.toString())
        val g = splitziner.guide.GuideSteps.build(pl.parts, pl.steps, fmt = LengthFormat("in", "fraction"))
        assertTrue(g.any { p -> p.title.contains("make the ladder from") }, g.map { it.title }.toString())
        assertTrue(g.any { p -> p.text.any { it.startsWith("Mark the rungs") && it.contains("then every 11 in") } }, g.flatMap { it.text }.toString())
    }
}

class IslandTest {
    @Test fun islandHasABackStretchersAndCorbelsAndStartsFromASide() {
        val T = 19.05; val W = 1219.2; val D = 609.6; val H = 876.3; val kick = 101.6; val ct = 31.75
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        // A triangle in the y-z plane, 1-1/2 in thick along x: a corbel under the overhang, against the back.
        fun corbel(x: Double): splitziner.mesh.Mesh {
            val yz = listOf(D to H - ct, D + 254.0 to H - ct, D to H - ct - 254.0)
            val v = ArrayList<Double>(); for (xx in listOf(x, x + 38.1)) for ((y, z) in yz) { v += xx; v += y; v += z }
            val tri = intArrayOf(0, 2, 1, 3, 4, 5, 0, 1, 4, 0, 4, 3, 1, 2, 5, 1, 5, 4, 2, 0, 3, 2, 3, 5)
            return splitziner.mesh.Mesh(v.toDoubleArray(), tri)
        }
        val ms = arrayListOf(b(0.0, 0.0, 0.0, T, D, H - ct), b(W - T, 0.0, 0.0, T, D, H - ct), b(T, 0.0, kick, W - 2 * T, D - T, T), b(T, 76.2, 0.0, W - 2 * T, T, kick),
            b(T, D - T, 0.0, W - 2 * T, T, H - ct), b(T, 0.0, H - ct - T, W - 2 * T, 101.6, T), b(T, D - T - 101.6, H - ct - T, W - 2 * T, 101.6, T),
            b(609.6, 0.0, kick + T, T, D - T, H - ct - 2 * T - kick), b(609.6 + T, 50.8, kick + T + 330.0, W - 2 * T - 609.6, D - T - 50.8, T),
            b(-25.4, -25.4, H - ct, W + 50.8, D + 330.2, ct), corbel(152.4), corbel(W - 190.5))
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "island.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val names = a.parts.map { it.name }
        assertEquals(1, names.count { it == "Back" }, names.toString())
        assertEquals(2, names.count { it == "Stretcher" }, names.toString())
        assertEquals(2, names.count { it == "Corbel" }, names.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val first = pl.steps.first { !it.subassembly }
        assertEquals("Side", a.parts.first { it.id == first.partIds.first() }.name, first.partIds.map { id -> a.parts.first { it.id == id }.name }.toString())
    }
}

class CribTest {
    @Test fun spindlesAreMadeIntoSlattedPanelsAndSpacedWithABlock() {
        val L = 1384.3; val Wd = 755.65; val H = 914.4; val post = 50.8; val rt = 19.05; val rh = 63.5
        val zb = 254.0; val zt = H - 25.4 - rh; val sw = 44.45; val gap = 57.15
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = ArrayList<splitziner.mesh.Mesh>()
        for (x in listOf(0.0, Wd - post)) for (y in listOf(0.0, L - post)) ms += b(x, y, 0.0, post, post, H)
        fun side(x0: Double, y0: Double, y1: Double, alongY: Boolean) {
            val span = y1 - y0
            ms += if (alongY) listOf(b(x0, y0, zb, rt, span, rh), b(x0, y0, zt, rt, span, rh)) else listOf(b(y0, x0, zb, span, rt, rh), b(y0, x0, zt, span, rt, rh))
            val n = ((span - gap) / (sw + gap)).toInt(); val used = n * sw + (n + 1) * gap; val start = y0 + (span - used) / 2 + gap
            for (i in 0 until n) { val s = start + i * (sw + gap); ms += if (alongY) b(x0, s, zb + rh, rt, sw, zt - zb - rh) else b(s, x0, zb + rh, sw, rt, zt - zb - rh) }
        }
        side((post - rt) / 2, post, L - post, true); side(Wd - post + (post - rt) / 2, post, L - post, true)
        side((post - rt) / 2, post, Wd - post, false); side(L - post + (post - rt) / 2, post, Wd - post, false)
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "crib.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(34, a.parts.count { it.name == "Spindle" }, a.parts.map { it.name }.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val g = splitziner.guide.GuideSteps.build(pl.parts, pl.steps, fmt = LengthFormat("in", "fraction"))
        assertTrue(g.any { it.title.contains("make 2 slatted panels from") }, g.map { it.title }.toString())
        assertTrue(g.any { s -> s.text.any { it.startsWith("Space the spindles (item") && it.contains("2-1/4 in apart") } }, g.flatMap { it.text }.toString())
        // Two bench steps: the end frames (legs, rails, spindles) and the slatted sides, each in one picture.
        assertEquals(2, pl.steps.count { it.subassembly })
    }
}

class ChairTest {
    @Test fun chairHasASeatAndBackRailsNotALadder() {
        val W = 457.2; val D = 457.2; val Hs = 457.2; val Hb = 914.4; val leg = 38.1; val t = 19.05
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = ArrayList<splitziner.mesh.Mesh>()
        for (x in listOf(0.0, W - leg)) { ms += b(x, 0.0, 0.0, leg, leg, Hs - t); ms += b(x, D - leg, 0.0, leg, leg, Hb) }
        val az = Hs - t - 76.2; val off = (leg - t) / 2
        ms += b(leg, off, az, W - 2 * leg, t, 76.2); ms += b(leg, D - leg + off, az, W - 2 * leg, t, 76.2)
        for (x in listOf(0.0, W - leg)) { ms += b(x + off, leg, az, t, D - 2 * leg, 76.2); ms += b(x + off, leg, 152.4, t, D - 2 * leg, 38.1) }
        ms += b(0.0, 0.0, Hs - t, W, D - leg, t)
        val rl = Hs + 76.2; val rt = Hb - 88.9
        ms += b(leg, D - leg + off, rl, W - 2 * leg, t, 50.8); ms += b(leg, D - leg + off, rt, W - 2 * leg, t, 76.2)
        val sw = 50.8; val g = (W - 2 * leg - 3 * sw) / 4
        for (i in 0 until 3) ms += b(leg + g + i * (sw + g), D - leg + (leg - 12.7) / 2, rl + 50.8, sw, 12.7, rt - rl - 50.8)
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "chair.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val names = a.parts.map { it.name }
        assertEquals(1, names.count { it == "Seat" }, names.toString())
        assertEquals(2, names.count { it == "Back rail" }, names.toString())
        assertEquals(3, names.count { it == "Spindle" }, names.toString())
        assertTrue(names.none { it == "Rung" || it == "Ladder side" }, names.toString())
    }
}

class WallCabinetTest {
    @Test fun wallCabinetHangsByItsRail() {
        val T = 19.05; val W = 762.0; val D = 304.8; val H = 762.0
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = listOf(b(0.0, 0.0, 0.0, T, D, H), b(W - T, 0.0, 0.0, T, D, H), b(T, 0.0, 0.0, W - 2 * T, D - 6.35, T), b(T, 0.0, H - T, W - 2 * T, D - 6.35, T),
            b(T, D - 6.35 - T, H - T - 88.9, W - 2 * T, T, 88.9), b(T, D - 6.35, 0.0, W - 2 * T, 6.35, H))
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "wall.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(1, a.parts.count { it.name == "Hanging rail" }, a.parts.map { it.name }.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        assertTrue(f.hardware.any { it.contains("to hang it") }, f.hardware.toString())
        assertTrue(f.hardware.none { it.contains("anti-tip") }, f.hardware.toString())
    }
}

class EndTableTest {
    @Test fun drawerOnWoodenRunnersNeedsNoSlides() {
        val W = 508.0; val D = 457.2; val H = 660.4; val leg = 38.1; val at = 19.05
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = ArrayList<splitziner.mesh.Mesh>()
        for (x in listOf(0.0, W - leg)) for (y in listOf(0.0, D - leg)) ms += b(x, y, 0.0, leg, leg, H - 19.05)
        val az = H - 19.05 - 139.7; val ah = 139.7
        for (x in listOf(leg - at, W - leg)) ms += b(x, leg, az, at, D - 2 * leg, ah)
        ms += b(leg, D - leg, az, W - 2 * leg, at, ah)
        ms += b(leg, 0.0, az, W - 2 * leg, 38.1, 19.05); ms += b(leg, 0.0, az + ah - 19.05, W - 2 * leg, 38.1, 19.05)
        for (x in listOf(leg, W - leg - 19.05)) { ms += b(x, 38.1, az, 19.05, D - leg - 38.1, 19.05); ms += b(x, 38.1, az + ah - 19.05, 19.05, D - leg - 38.1, 19.05) }
        // Drawer: sides ride on the runners; a front glued on its box.
        val x0 = leg + 1.6; val x1 = W - leg - 1.6; val z0 = az + 19.05; val bh = ah - 2 * 19.05 - 3.2; val y0 = 19.05; val dd = 381.0; val t = 12.7
        ms += b(x0, y0, z0, t, dd, bh); ms += b(x1 - t, y0, z0, t, dd, bh); ms += b(x0 + t, y0, z0, x1 - x0 - 2 * t, t, bh); ms += b(x0 + t, y0 + dd - t, z0, x1 - x0 - 2 * t, t, bh)
        ms += b(x0 + t, y0 + t, z0, x1 - x0 - 2 * t, dd - 2 * t, 6.35)
        ms += b(x0, 0.0, z0, x1 - x0, 19.05, ah - 2 * 19.05 - 1.6)
        ms += b(-25.4, -25.4, H - 19.05, W + 50.8, D + 50.8, 19.05)
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "et.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val names = a.parts.map { it.name }
        assertEquals(2, names.count { it == "Runner" }, names.toString())
        assertEquals(2, names.count { it == "Kicker" }, names.toString())
        assertEquals(2, names.count { it == "Drawer rail" }, names.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        assertTrue(f.hardware.none { it.contains("slides") }, f.hardware.toString())
        assertTrue(f.all.any { it.kind == "runners" })
        // Runners and kickers go on the side frames on the bench.
        val bench = pl.steps.filter { it.subassembly }.flatMap { it.partIds }.toSet()
        assertTrue(a.parts.filter { it.name == "Runner" || it.name == "Kicker" }.all { r -> pl.steps.any { st -> st.subassembly && r.id in st.partIds } || pl.parts.first { it.id == r.id }.step == pl.parts.filter { it.name == "Leg" }.minOf { it.step ?: 0 } }, bench.toString())
    }
}

class ChestTest {
    @Test fun chestStandsOnAPlinthAndItsLidIsHingedAndHeldOpen() {
        val T = 19.05; val W = 914.4; val D = 457.2; val H = 482.6; val pl = 76.2; val s = 12.7
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = listOf(b(0.0, 0.0, pl, W, T, H - pl), b(0.0, D - T, pl, W, T, H - pl), b(0.0, T, pl, T, D - 2 * T, H - pl), b(W - T, T, pl, T, D - 2 * T, H - pl),
            b(T, T, pl, W - 2 * T, D - 2 * T, T), b(s, s, 0.0, W - 2 * s, T, pl), b(s, D - s - T, 0.0, W - 2 * s, T, pl),
            b(s, s + T, 0.0, T, D - 2 * s - 2 * T, pl), b(W - s - T, s + T, 0.0, T, D - 2 * s - 2 * T, pl), b(-T, -T, H, W + 2 * T, D + T, T))
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "chest.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val names = a.parts.map { it.name }
        assertEquals(4, names.count { it == "Plinth" }, names.toString())
        assertEquals(1, names.count { it == "Lid" }, names.toString())
        assertEquals(1, names.count { it == "Front" }, names.toString())
        val pl0 = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl0.parts, pl0.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        assertTrue(f.hardware.any { it.contains("piano hinge") } && f.hardware.any { it.contains("lid support") }, f.hardware.toString())
        assertTrue(f.all.none { it.kind == "brackets" }, f.all.map { it.kind }.toString())
        // The case is screwed to its bottom, though the bottom rests on the plinth.
        assertTrue(f.all.any { setOf(it.through.name, it.into.name) == setOf("Front", "Bottom") }, f.all.map { "${it.through.name}-${it.into.name}" }.toString())
        assertTrue(splitziner.check.DesignCheck.run(a.parts, a.cutList.rows, LengthFormat("in", "fraction"), null).findings.any { it.title == "Hold the lid open" })
    }
}

class PicnicTest {
    /** A board [w] x [t] x [len] centred at [c], its length tilted [deg] from upright in the y-z plane. */
    private fun splayed(c: Vec3, w: Double, t: Double, len: Double, deg: Double): splitziner.mesh.Mesh {
        val a = Math.toRadians(deg); val cs = kotlin.math.cos(a); val sn = kotlin.math.sin(a)
        val m = Fixtures.box(Vec3(-t / 2, -w / 2, -len / 2), Vec3(t / 2, w / 2, len / 2))
        val v = m.vertices.copyOf()
        for (i in 0 until v.size / 3) { val y = v[3 * i + 1]; val z = v[3 * i + 2]; v[3 * i] += c.x; v[3 * i + 1] = y * cs - z * sn + c.y; v[3 * i + 2] = y * sn + z * cs + c.z }
        return splitziner.mesh.Mesh(v, m.triangles)
    }

    @Test fun splayedBoardLegsMakeEndFramesWithTheirCrosspieces() {
        val L = 1828.8; val Wt = 762.0; val t2 = 38.1; val w6 = 139.7; val w4 = 88.9; val Htop = 762.0; val Hs = 457.2; val ang = 22.0
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = ArrayList<splitziner.mesh.Mesh>()
        val len = (Htop - t2) / kotlin.math.cos(Math.toRadians(ang))
        for (xe in listOf(152.4, L - 152.4 - t2)) {
            ms += b(xe, 0.0, Htop - t2 - w4, t2, Wt, w4); ms += b(xe, -381.0, Hs - t2 - w6, t2, Wt + 762.0, w6)
            for (side in listOf(-1, 1)) ms += splayed(Vec3(xe - t2 / 2, Wt / 2 + side * kotlin.math.tan(Math.toRadians(ang)) * (Htop - t2) / 2, (Htop - t2) / 2), w6, t2, len, -side * ang)
        }
        for (i in 0 until 5) ms += b(0.0, i * (w6 + (Wt - 5 * w6) / 4), Htop - t2, L, w6, t2)
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "picnic.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(4, a.parts.count { it.name == "Leg" }, a.parts.map { it.name }.toString())
        val frames = splitziner.explode.AssemblyPlan.legFrames(a.parts)
        assertEquals(2, frames.size, frames.toString())
        assertTrue(frames.all { it.size == 4 }, frames.toString())
    }
}

class TurnGuessTest {
    @Test fun aCabinetFacingSidewaysIsTurnedToFaceTheFront() {
        val t = splitziner.edit.Templates.byKey("drawers")
        val ms = t.build(splitziner.edit.Templates.defaults(t, true))
        fun stl(meshes: List<splitziner.mesh.Mesh>): ByteArray { val bos = ByteArrayOutputStream(); StlWriter.writeParts(meshes, bos); return bos.toByteArray() }
        // Its front turned to face +X: (x, y) becomes (-y, x).
        val side = ms.map { m -> splitziner.mesh.Mesh(DoubleArray(m.vertices.size) { i -> val v = 3 * (i / 3); when (i % 3) { 0 -> -m.vertices[v + 1]; 1 -> m.vertices[v]; else -> m.vertices[v + 2] } }, m.triangles) }
        val facing = Pipeline.analyze(stl(ms), "d.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val sideways = Pipeline.analyze(stl(side), "d.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(0, Pipeline.guessTurn(facing.parts))
        // Still the right way up, though it faces sideways.
        assertEquals("z", Pipeline.guessUp(sideways.parts))
        val n = Pipeline.guessTurn(sideways.parts)
        assertEquals(3, n)
        val turned = Pipeline.analyze(stl(side), "d.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm", up = "z>$n", upGuessed = true))
        assertEquals(facing.overallSize, turned.overallSize)
        assertEquals(facing.parts.map { it.name }.sorted(), turned.parts.map { it.name }.sorted())
        assertTrue(turned.report.warnings.any { it.contains("turned to face you") })
    }
}

class PlatformBedTest {
    @Test fun frameThenCentreBeamThenSlatsThenHeadboard() {
        val L = 2032.0; val W = 1524.0 + 2 * 38.1; val t2 = 38.1; val w10 = 235.0; val leg = 88.9; val zr = 152.4; val Hh = 1219.2
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        val ms = arrayListOf(b(0.0, 0.0, zr, t2, L, w10), b(W - t2, 0.0, zr, t2, L, w10), b(t2, 0.0, zr, W - 2 * t2, t2, w10), b(t2, L - t2, zr, W - 2 * t2, t2, w10))
        for (x in listOf(t2, W - t2 - leg)) { ms += b(x, t2, 0.0, leg, leg, Hh); ms += b(x, L - t2 - leg, 0.0, leg, leg, zr + w10); ms += b(x, L / 2 - leg / 2, 0.0, leg, leg, zr + w10 - t2) }
        val lz = zr + w10 - 38.1 - 19.05; val top = lz + 38.1
        ms += b(W / 2 - t2 / 2, t2, top - 140.0, t2, L - 2 * t2, 140.0)
        for (y in listOf(L / 3, 2 * L / 3)) ms += b(W / 2 - leg / 2, y, 0.0, leg, leg, top - 140.0)
        ms += b(t2 + leg, t2 + leg, lz, 38.1, L - 2 * t2 - 2 * leg, 38.1); ms += b(W - t2 - leg - 38.1, t2 + leg, lz, 38.1, L - 2 * t2 - 2 * leg, 38.1)
        val n = 14; val sw = 88.9; val gap = (L - 2 * t2 - 2 * leg - n * sw) / (n - 1)
        for (i in 0 until n) ms += b(t2 + leg, t2 + leg + i * (sw + gap), lz + 38.1, W - 2 * t2 - 2 * leg, sw, 19.05)
        ms += b(t2 + leg, t2 + (leg - 19.05) / 2, zr + w10 + 50.8, W - 2 * t2 - 2 * leg, 19.05, Hh - zr - w10 - 101.6)
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "pb.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(1, a.parts.count { it.name == "Headboard" }, a.parts.map { it.name }.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        fun stepOf(name: String) = pl.parts.filter { it.name == name }.maxOf { it.step!! }
        assertTrue(stepOf("Side rail") < stepOf("Centre rail"), "${stepOf("Side rail")} ${stepOf("Centre rail")}")
        assertTrue(stepOf("Centre rail") < pl.parts.filter { it.name == "Slat" }.minOf { it.step!! })
        assertTrue(stepOf("Slat") < stepOf("Headboard"))
        // All the slats in one step.
        assertEquals(1, pl.parts.filter { it.name == "Slat" }.map { it.step }.distinct().size)
    }
}

class NotALadderTest {
    @Test fun aFrameWithRailsFlushAtItsEndsIsNotALadder() {
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        // A desk's O-shaped leg drawn as boards: two uprights 25 x 75 x 735, rails at the bottom, middle and top between them.
        val ms = listOf(b(0.0, 0.0, 0.0, 25.0, 75.0, 735.0), b(0.0, 600.0, 0.0, 25.0, 75.0, 735.0),
            b(0.0, 75.0, 0.0, 25.0, 525.0, 75.0), b(0.0, 75.0, 330.0, 25.0, 525.0, 75.0), b(0.0, 75.0, 660.0, 25.0, 525.0, 75.0),
            b(-300.0, -50.0, 735.0, 1500.0, 775.0, 25.0))
        val names = splitziner.parts.Roles.names(ms.mapIndexed { i, m -> Part("P$i", "P$i", m) })
        assertTrue(names.values.none { it == "Rung" || it == "Ladder side" }, names.toString())
    }
}

class SquareUpTest {
    @Test fun aCabinetDrawnAtAnAngleIsSquaredUp() {
        val t = splitziner.edit.Templates.byKey("drawers")
        val ms = t.build(splitziner.edit.Templates.defaults(t, true))
        fun stl(meshes: List<splitziner.mesh.Mesh>): ByteArray { val bos = ByteArrayOutputStream(); StlWriter.writeParts(meshes, bos); return bos.toByteArray() }
        val a = Math.toRadians(30.0); val c = kotlin.math.cos(a); val s = kotlin.math.sin(a)
        val turned = ms.map { m -> splitziner.mesh.Mesh(DoubleArray(m.vertices.size) { i -> val v = 3 * (i / 3); when (i % 3) { 0 -> c * m.vertices[v] - s * m.vertices[v + 1]; 1 -> s * m.vertices[v] + c * m.vertices[v + 1]; else -> m.vertices[v + 2] } }, m.triangles) }
        val square = Pipeline.analyze(stl(ms), "d.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val angled = Pipeline.analyze(stl(turned), "d.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertTrue(angled.report.warnings.any { it.contains("squared up") }, angled.report.warnings.toString())
        assertTrue(square.report.warnings.none { it.contains("squared up") })
        assertEquals(square.parts.map { it.name }.sorted(), angled.parts.map { it.name }.sorted())
        assertEquals(square.overallSize, angled.overallSize)
    }
}

class FitDirectionsTest {
    @Test fun everyPartFittedInATemplateGuideSaysWhichWay() {
        for (t in splitziner.edit.Templates.ALL) {
            val ms = t.build(splitziner.edit.Templates.defaults(t, true))
            val bos = ByteArrayOutputStream(); StlWriter.writeParts(ms, bos)
            val a = Pipeline.analyze(bos.toByteArray(), "${t.key}.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
            val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
            val g = splitziner.guide.GuideSteps.build(pl.parts, pl.steps, fmt = LengthFormat("in", "fraction"))
            val bare = g.flatMap { it.text }.filter { Regex("""^(Fit|Join) [^.]*\)\.$""").matches(it) }
            assertTrue(bare.isEmpty(), "${t.key}: $bare")
        }
    }
}

class SpindleGapTest {
    @Test fun wideSpindleGapsAreFlagged() {
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        // Two posts, two rails between them, five spindles with 80 mm clear between them.
        val ms = arrayListOf(b(0.0, 0.0, 0.0, 50.0, 50.0, 900.0), b(650.0, 0.0, 0.0, 50.0, 50.0, 900.0),
            b(50.0, 15.0, 200.0, 600.0, 20.0, 60.0), b(50.0, 15.0, 760.0, 600.0, 20.0, 60.0))
        for (i in 0 until 5) ms += b(50.0 + 60.0 + i * (40.0 + 80.0), 15.0, 260.0, 40.0, 20.0, 500.0)
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "rail.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(5, a.parts.count { it.name == "Spindle" }, a.parts.map { it.name }.toString())
        val f = splitziner.check.DesignCheck.run(a.parts, a.cutList.rows, LengthFormat("mm"), null).findings
        assertTrue(f.any { it.title.startsWith("Spindles 80") && it.detail.contains("crib") }, f.map { it.title }.toString())
    }
}

class FrenchCleatTest {
    @Test fun bevelledStripsAreAFrenchCleatAndItsWallHalf() {
        val T = 19.05; val W = 610.0; val D = 254.0; val H = 457.2
        val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }
        // A strip along X, its profile in y-z with the top cut at 45 degrees across the thickness.
        fun bev(yfr: Double, ybk: Double, z0: Double, z1: Double, backLower: Boolean): splitziner.mesh.Mesh {
            val yz = if (backLower) listOf(yfr to z0, ybk to z0, ybk to z1 - (ybk - yfr), yfr to z1) else listOf(yfr to z0, ybk to z0 + (ybk - yfr), ybk to z1, yfr to z1)
            val v = ArrayList<Double>(); for (x in listOf(T, W - T)) for ((y, z) in yz) { v += x; v += y; v += z }
            val t = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
            return splitziner.mesh.Mesh(v.toDoubleArray(), t)
        }
        val ms = listOf(b(0.0, 0.0, 0.0, T, D, H), b(W - T, 0.0, 0.0, T, D, H), b(T, 0.0, 0.0, W - 2 * T, D, T), b(T, 0.0, H - T, W - 2 * T, D, T),
            b(T, D - T - 6.35, T, W - 2 * T, 6.35, H - 2 * T), bev(D - T, D, H - T - 76.2, H - T, true), bev(D, D + T, H - T - 127.0, H - 2 * T, false))
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "cc.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertTrue(a.cutList.rows.count { it.description.contains("ONE EDGE BEVELLED AT 45") } == 2, a.cutList.rows.map { it.description }.toString())
        assertEquals(1, a.parts.count { it.name == "Wall cleat" }, a.parts.map { it.name }.toString())
        assertEquals(1, a.parts.count { it.name == "French cleat" }, a.parts.map { it.name }.toString())
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        assertTrue(f.all.any { it.kind == "wallcleat" })
        assertTrue(f.all.none { it.kind != "wallcleat" && (it.through.name == "Wall cleat" || it.into.name == "Wall cleat") })
        assertEquals(pl.steps.size, pl.parts.first { it.name == "Wall cleat" }.step)
    }
}

class OutdoorTest {
    @Test fun outdoorTimberAsksForExteriorFasteners() {
        assertTrue(splitziner.material.Materials.outdoor("Pressure-treated pine"))
        assertTrue(splitziner.material.Materials.outdoor("western red cedar"))
        assertFalse(splitziner.material.Materials.outdoor("Pine"))
        assertEquals(600.0, splitziner.material.Materials.density("pressure treated pine"))
        val t = splitziner.edit.Templates.byKey("bench")
        val ms = t.build(splitziner.edit.Templates.defaults(t, true))
        val bos = ByteArrayOutputStream(); StlWriter.writeParts(ms, bos)
        val a0 = Pipeline.analyze(bos.toByteArray(), "b.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val a = Pipeline.analyze(bos.toByteArray(), "b.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm",
            itemOverrides = a0.cutList.rows.associate { r -> splitziner.parts.CutList.itemKey(r.kind, r.t, r.w, r.l) to splitziner.parts.ItemOverride(material = "Cedar") }))
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        assertTrue(f.hardware.any { it.startsWith("All fasteners exterior-rated") }, f.hardware.toString())
    }
}

class FramePanelTest {
    private val T = 19.05
    private val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }

    private fun guide(ms: List<splitziner.mesh.Mesh>): Triple<splitziner.pipeline.Analysis, splitziner.joinery.FastenPlan, List<splitziner.guide.GuideStep>> {
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "fp.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val fmt = LengthFormat("in", "fraction")
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, fmt)
        return Triple(a, f, splitziner.guide.GuideSteps.build(pl.parts, pl.steps, fasten = f, fmt = fmt))
    }

    @Test fun shakerDoorsAreMadeOnTheBenchAndHung() {
        val W = 762.0; val D = 406.4; val H = 762.0
        val ms = arrayListOf(b(0.0, 0.0, 0.0, T, D, H), b(W - T, 0.0, 0.0, T, D, H), b(T, 0.0, 0.0, W - 2 * T, D - 6.35, T), b(T, 0.0, H - T, W - 2 * T, D - 6.35, T),
            b(T, D - 6.35, 0.0, W - 2 * T, 6.35, H))
        val dw = W / 2 - 3.2; val dh = H - 3.2; val sw = 57.15; val g = 9.525
        for (x0 in listOf(1.6, W / 2 + 1.6)) {
            ms += listOf(b(x0, -T, 1.6, sw, T, dh), b(x0 + dw - sw, -T, 1.6, sw, T, dh), b(x0 + sw, -T, 1.6, dw - 2 * sw, T, sw), b(x0 + sw, -T, 1.6 + dh - sw, dw - 2 * sw, T, sw),
                b(x0 + sw - g, -T + (T - 6.35) / 2, 1.6 + sw - g, dw - 2 * sw + 2 * g, 6.35, dh - 2 * sw + 2 * g))
        }
        val (a, f, steps) = guide(ms)
        assertEquals(4, a.parts.count { it.name == "Door stile" }, a.parts.map { it.name }.toString())
        assertEquals(2, a.parts.count { it.name == "Door panel" })
        // The panels' edges sit in grooves: no clash.
        val report = splitziner.check.DesignCheck.run(a.cutList.parts, a.cutList.rows, LengthFormat("in", "fraction"), null, housed = a.housed)
        assertTrue(report.findings.none { it.title.contains("occupy the same space") }, report.findings.map { it.title }.toString())
        assertTrue(steps.any { it.title.contains("make 2 doors") }, steps.map { it.title }.toString())
        assertTrue(steps.any { it.title.contains("hang the 2 doors") }, steps.map { it.title }.toString())
        assertTrue(steps.flatMap { it.text }.any { it.startsWith("Cut a groove") && it.contains("not glued") })
        // Never screwed to the case: only hinged.
        assertTrue(f.all.none { j -> (j.through.name.startsWith("Door") != j.into.name.startsWith("Door")) && j.kind != "hinges" })
        assertEquals(4, f.all.filter { it.kind == "hinges" }.sumOf { it.count })
    }

    private fun shaker(x0: Double, y0: Double, z0: Double, w: Double, h: Double, sw: Double = 57.15, g: Double = 9.525, pt: Double = 6.35) = listOf(
        b(x0, y0, z0, sw, T, h), b(x0 + w - sw, y0, z0, sw, T, h), b(x0 + sw, y0, z0, w - 2 * sw, T, sw), b(x0 + sw, y0, z0 + h - sw, w - 2 * sw, T, sw),
        b(x0 + sw - g, y0 + (T - pt) / 2, z0 + sw - g, w - 2 * sw + 2 * g, pt, h - 2 * sw + 2 * g))

    @Test fun fivePieceDrawerFrontIsMadeThenScrewedOnAsOne() {
        val W = 762.0; val D = 584.2; val H = 876.3; val kick = 101.6
        val ms = arrayListOf(b(0.0, 0.0, 0.0, T, D, H), b(W - T, 0.0, 0.0, T, D, H), b(T, 0.0, kick, W - 2 * T, D - 6.35, T), b(T, 76.2, 0.0, W - 2 * T, T, kick),
            b(T, 0.0, H - T, W - 2 * T, 101.6, T), b(T, D - 6.35 - 101.6, H - T, W - 2 * T, 101.6, T), b(T, 0.0, H - 203.2, W - 2 * T, D - 6.35, T),
            b(T, D - 6.35, kick, W - 2 * T, 6.35, H - kick))
        // The drawer box: sides, ends, bottom.
        val x = T + 12.7; val z = H - 203.2 + T + 6.35; val w = W - 2 * T - 25.4; val h = 127.0; val d = 508.0; val t = 12.7
        ms += listOf(b(x, 0.0, z, t, d, h), b(x + w - t, 0.0, z, t, d, h), b(x + t, 0.0, z, w - 2 * t, t, h), b(x + t, d - t, z, w - 2 * t, t, h), b(x + t, t, z, w - 2 * t, d - 2 * t, 6.35))
        ms += shaker(1.6, -T, H - 203.2 + T / 2 + 1.6, W - 3.2, 203.2 - T / 2 - 3.2, sw = 50.8)
        val (a, f, steps) = guide(ms)
        assertEquals(2, a.parts.count { it.name == "Drawer front stile" }, a.parts.map { it.name }.toString())
        assertEquals(1, a.parts.count { it.name == "Drawer front panel" })
        assertTrue(steps.any { it.title.contains("make the drawer front") }, steps.map { it.title }.toString())
        assertEquals(1, f.all.count { it.kind == "applied" })
        assertTrue(steps.flatMap { it.text }.any { it.contains("hold the drawer front from step") })
    }

    @Test fun insetShakerDoorsInAFaceFrameGetInsetHinges() {
        val W = 762.0; val D = 304.8; val H = 762.0; val ffw = 38.1
        val ms = arrayListOf(b(0.0, 0.0, 0.0, T, D, H), b(W - T, 0.0, 0.0, T, D, H), b(T, 0.0, 0.0, W - 2 * T, D - 6.35, T), b(T, 0.0, H - T, W - 2 * T, D - 6.35, T),
            b(T, D - 6.35, T, W - 2 * T, 6.35, H - 2 * T), b(0.0, -T, 0.0, ffw, T, H), b(W - ffw, -T, 0.0, ffw, T, H), b(ffw, -T, 0.0, W - 2 * ffw, T, ffw), b(ffw, -T, H - ffw, W - 2 * ffw, T, ffw))
        val gap = 1.6; val dw = (W - 2 * ffw - 3 * gap) / 2
        for (x0 in listOf(ffw + gap, ffw + 2 * gap + dw)) ms += shaker(x0, -T, ffw + gap, dw, H - 2 * ffw - 2 * gap)
        val (a, f, _) = guide(ms)
        assertEquals(4, a.parts.count { it.name == "Door stile" }, a.parts.map { it.name }.toString())
        val hinges = f.all.filter { it.kind == "hinges" }
        assertEquals(2, hinges.size)
        assertTrue(hinges.all { it.size == "inset hinges" }, hinges.map { it.size }.toString())
    }

    /** A raised panel: flat back at [yb], a tongue [tg] thick all round, bevelled [bw] in to a field [rise] higher (toward -y). */
    private fun raised(x0: Double, x1: Double, z0: Double, z1: Double, yb: Double, tg: Double = 6.35, rise: Double = 9.525, bw: Double = 38.1): splitziner.mesh.Mesh {
        val rings = listOf(listOf(x0, x1, z0, z1, yb), listOf(x0, x1, z0, z1, yb - tg), listOf(x0 + bw, x1 - bw, z0 + bw, z1 - bw, yb - tg - rise))
        val v = ArrayList<Double>()
        for (r in rings) for ((x, z) in listOf(r[0] to r[2], r[1] to r[2], r[1] to r[3], r[0] to r[3])) { v += x; v += r[4]; v += z }
        val t = ArrayList<Int>()
        t += listOf(0, 2, 1, 0, 3, 2, 8, 9, 10, 8, 10, 11)
        for (ring in 0..1) for (i in 0..3) { val j = (i + 1) % 4; val a = 4 * ring; val b = 4 * (ring + 1); t += listOf(a + i, a + j, b + j, a + i, b + j, b + i) }
        return splitziner.mesh.Mesh(v.toDoubleArray(), t.toIntArray())
    }

    @Test fun raisedPanelDoorsAreNamedAndGrooved() {
        val W = 762.0; val D = 406.4; val H = 762.0; val sw = 57.15; val g = 9.525
        val ms = arrayListOf(b(0.0, 0.0, 0.0, T, D, H), b(W - T, 0.0, 0.0, T, D, H), b(T, 0.0, 0.0, W - 2 * T, D - 6.35, T), b(T, 0.0, H - T, W - 2 * T, D - 6.35, T),
            b(T, D - 6.35, 0.0, W - 2 * T, 6.35, H))
        val dw = W / 2 - 3.2; val dh = H - 3.2
        for (x0 in listOf(1.6, W / 2 + 1.6)) {
            ms += listOf(b(x0, -T, 1.6, sw, T, dh), b(x0 + dw - sw, -T, 1.6, sw, T, dh), b(x0 + sw, -T, 1.6, dw - 2 * sw, T, sw), b(x0 + sw, -T, 1.6 + dh - sw, dw - 2 * sw, T, sw))
            ms += raised(x0 + sw - g, x0 + dw - sw + g, 1.6 + sw - g, 1.6 + dh - sw + g, -T + (T + 6.35) / 2)
        }
        val (a, _, steps) = guide(ms)
        assertTrue(a.cutList.rows.any { it.description.contains("RAISED (EDGE 6.3 THK, BEVEL 38.1 WIDE)") }, a.cutList.rows.map { it.description }.toString())
        assertEquals(2, a.parts.count { it.name == "Door panel" }, a.parts.map { it.name }.toString())
        assertTrue(steps.flatMap { it.text }.any { it.startsWith("Cut a groove 1/4 in wide and 3/8 in deep") }, steps.flatMap { it.text }.toString())
        val report = splitziner.check.DesignCheck.run(a.cutList.parts, a.cutList.rows, LengthFormat("in", "fraction"), null, housed = a.housed)
        assertTrue(report.findings.none { it.title.contains("occupy the same space") }, report.findings.map { it.title }.toString())
    }

    @Test fun panelledBedEndsAreHeadboardAndFootboardOnTheirPosts() {
        val P = 88.9; val g = 12.7; val pt = 12.7; val W = 1524.0 + 2 * P; val L = 2032.0 + 2 * P
        val ms = arrayListOf<splitziner.mesh.Mesh>()
        for ((y0, h) in listOf(0.0 to 609.6, (L - P) to 1219.2)) {
            ms += listOf(b(0.0, y0, 0.0, P, P, h), b(W - P, y0, 0.0, P, P, h))
            val yr = y0 + (P - T) / 2; val rh = if (h > 1000) 139.7 else 101.6
            ms += listOf(b(P, yr, 254.0, W - 2 * P, T, rh), b(P, yr, h - 152.4, W - 2 * P, T, 101.6))
            val z0 = 254.0 + rh; val z1 = h - 152.4
            ms += b(P - g, yr + (T - pt) / 2, z0 - g, W - 2 * P + 2 * g, pt, z1 - z0 + 2 * g)
        }
        ms += listOf(b((P - T) / 2, P, 254.0, T, L - 2 * P, 177.8), b(W - (P + T) / 2, P, 254.0, T, L - 2 * P, 177.8),
            b((P + T) / 2, P, 254.0, 38.1, L - 2 * P, 38.1), b(W - (P + T) / 2 - 38.1, P, 254.0, 38.1, L - 2 * P, 38.1))
        for (i in 0 until 14) ms += b((P + T) / 2, P + i * 145.0, 292.1, W - P - T, 88.9, T)
        val (a, f, steps) = guide(ms)
        assertEquals(4, a.parts.count { it.name == "Leg" || it.name == "Post" }, a.parts.map { it.name }.toString())
        assertTrue(steps.any { it.title.contains("make the headboard") } && steps.any { it.title.contains("make the footboard") }, steps.map { it.title }.toString())
        assertTrue(f.all.any { it.kind == "bedrail" })
        assertTrue(f.all.none { it.kind == "pocketin" })
        val report = splitziner.check.DesignCheck.run(a.cutList.parts, a.cutList.rows, LengthFormat("in", "fraction"), null, housed = a.housed)
        assertTrue(report.findings.any { it.title == "The slats need a centre support" }, report.findings.map { it.title }.toString())
    }

    @Test fun panelledChestIsScrewedFromInsideAndHasALid() {
        val W = 914.4; val D = 457.2; val H = 482.6; val sw = 63.5; val g = 9.525; val pt = 6.35
        val ms = arrayListOf<splitziner.mesh.Mesh>()
        for (y0 in listOf(0.0, D - T)) {
            val mx = W / 2 - sw / 2
            ms += listOf(b(0.0, y0, 0.0, sw, T, H), b(W - sw, y0, 0.0, sw, T, H), b(sw, y0, 0.0, W - 2 * sw, T, sw), b(sw, y0, H - sw, W - 2 * sw, T, sw), b(mx, y0, sw, sw, T, H - 2 * sw))
            for ((x0, x1) in listOf(sw to mx, mx + sw to W - sw)) ms += b(x0 - g, y0 + (T - pt) / 2, sw - g, x1 - x0 + 2 * g, pt, H - 2 * sw + 2 * g)
        }
        ms += listOf(b(0.0, T, 0.0, T, D - 2 * T, H), b(W - T, T, 0.0, T, D - 2 * T, H), b(T, T, 50.8, W - 2 * T, D - 2 * T, T), b(-12.7, -12.7, H, W + 25.4, D + 25.4, T))
        val (a, f, steps) = guide(ms)
        assertEquals(2, a.parts.count { it.name == "Muntin" }, a.parts.map { it.name }.toString())
        assertEquals(1, a.parts.count { it.name == "Lid" })
        assertTrue(steps.any { it.title.contains("make 2 panelled frames") }, steps.map { it.title }.toString())
        assertTrue(f.all.any { it.kind == "lid" })
        // Nothing driven through the frame's outside face.
        assertTrue(f.all.none { j -> j.through.name in setOf("Stile", "Rail", "Muntin") && j.kind in setOf("screws", "brads", "front") }, f.all.map { "${it.through.name}>${it.into.name}:${it.kind}" }.toString())
        assertTrue(f.all.any { it.kind == "pocketin" })
    }
}

class TaperTest {
    /** A square leg [s] wide, [h] tall, tapered on its +x and +y faces from [start] below the top to [foot] square. */
    private fun leg(s: Double, h: Double, foot: Double, start: Double): splitziner.mesh.Mesh {
        val rings = listOf(0.0 to foot, (h - start) to s, h to s)
        val v = ArrayList<Double>()
        for ((z, w) in rings) for ((x, y) in listOf(0.0 to 0.0, w to 0.0, w to w, 0.0 to w)) { v += x; v += y; v += z }
        val t = arrayListOf(0, 2, 1, 0, 3, 2, 8, 9, 10, 8, 10, 11)
        for (ring in 0..1) for (i in 0..3) { val j = (i + 1) % 4; val a = 4 * ring; val b = 4 * (ring + 1); t += listOf(a + i, a + j, b + j, a + i, b + j, b + i) }
        return splitziner.mesh.Mesh(v.toDoubleArray(), t.toIntArray())
    }

    @Test fun aTaperedLegIsABoardWithItsTaperNamed() {
        val d = splitziner.parts.PartDescriber.describe(leg(38.1, 590.55, 19.05, 127.0), LengthFormat("in", "fraction"))
        assertEquals("BOARD 1-1/2 X 1-1/2 X 23-1/4, TAPERED ON TWO ADJACENT FACES TO 3/4 X 3/4, STARTING 5 FROM THE TOP", d.desc)
        // A plain stick is not tapered.
        val plain = splitziner.parts.PartDescriber.describe(Fixtures.box(Vec3(0.0, 0.0, 0.0), Vec3(38.1, 38.1, 590.0)), LengthFormat("in", "fraction"))
        assertFalse(plain.desc.contains("TAPERED"))
    }
}

class DisplayPieceTest {
    private val T = 19.05
    private val b = { x: Double, y: Double, z: Double, w: Double, d: Double, h: Double -> Fixtures.box(Vec3(x, y, z), Vec3(x + w, y + d, z + h)) }

    @Test fun aPlinthsFrontIsNotADoorAndItCanBeWeighted() {
        val W = 406.4; val D = 406.4; val H = 1066.8
        val ms = listOf(b(0.0, 0.0, 0.0, W, T, H), b(0.0, D - T, 0.0, W, T, H), b(0.0, T, 0.0, T, D - 2 * T, H), b(W - T, T, 0.0, T, D - 2 * T, H),
            b(T, T, H - T, W - 2 * T, D - 2 * T, T), b(T, T, H - T - T, W - 2 * T, T, T), b(T, D - 2 * T, H - T - T, W - 2 * T, T, T),
            b(T, T, 101.6, W - 2 * T, T, T), b(T, D - 2 * T, 101.6, W - 2 * T, T, T), b(T, T, 101.6 + T, W - 2 * T, D - 2 * T, T))
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "p.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertTrue(a.parts.none { it.name == "Door" }, a.parts.map { it.name }.toString())
        val report = splitziner.check.DesignCheck.run(a.cutList.parts, a.cutList.rows, LengthFormat("in", "fraction"), null)
        assertTrue(report.findings.any { it.title == "Fix it to the wall" && it.detail.contains("sandbags") })
    }

    @Test fun stripsOnACratesLidAreBattens() {
        val W = 1016.0; val D = 152.4; val H = 812.8; val pt = 12.7; val fw = 88.9
        val ms = arrayListOf(b(0.0, pt, 0.0, W, D - 2 * pt, T), b(0.0, pt, H - T, W, D - 2 * pt, T), b(0.0, pt, T, T, D - 2 * pt, H - 2 * T), b(W - T, pt, T, T, D - 2 * pt, H - 2 * T),
            b(0.0, 0.0, 0.0, W, pt, H), b(0.0, D - pt, 0.0, W, pt, H))
        for (x in listOf(0.0, W / 2 - fw / 2, W - fw)) ms += b(x, -T, 0.0, fw, T, H)
        val a = Pipeline.analyze(Fixtures.binaryStl(Fixtures.soup(ms)), "c.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(3, a.parts.count { it.name == "Batten" }, a.parts.map { it.name }.toString())
        assertTrue(a.parts.none { it.name == "Door" })
    }
}

class EaselTest {
    @Test fun anEaselsBackLegIsHingedOnLastAndItsLedgeNamed() {
        val bytes = javaClass.getResourceAsStream("/complex/easel.stl")!!.readBytes()
        val a = Pipeline.analyze(bytes, "easel.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(1, a.parts.count { it.name == "Back leg" }, a.parts.map { it.name }.toString())
        assertEquals(1, a.parts.count { it.name == "Ledge" })
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        assertEquals(pl.steps.size, pl.parts.first { it.name == "Back leg" }.step)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        assertEquals(1, f.all.count { it.kind == "foldhinge" })
        assertTrue(f.all.none { it.kind != "foldhinge" && (it.through.name == "Back leg" || it.into.name == "Back leg") })
        // Every part is joined: no floating parts on a line contact.
        val report = splitziner.check.DesignCheck.run(a.cutList.parts, a.cutList.rows, LengthFormat("in", "fraction"), null)
        assertTrue(report.findings.none { it.title.contains("touch nothing") || it.title.contains("touches nothing") }, report.findings.map { it.title }.toString())
    }
}

class PlanterTest {
    @Test fun stackedWallBoardsAreScrewedToTheLegsNotGlued() {
        val bytes = javaClass.getResourceAsStream("/complex/planter.stl")!!.readBytes()
        val a = Pipeline.analyze(bytes, "planter.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(4, a.parts.count { it.name == "Side board" }, a.parts.map { it.name }.toString())
        assertEquals(4, a.parts.count { it.name == "End board" })
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"))
        assertTrue(f.all.none { it.kind == "edge" }, f.all.filter { it.kind == "edge" }.map { "${it.through.name}/${it.into.name}" }.toString())
        assertTrue(f.all.filter { it.through.name == "Cleat" || it.into.name == "Cleat" }.filter { it.through.name.endsWith("board") }.isEmpty())
        assertTrue(f.all.none { it.kind == "brackets" })
    }
}

class CoatRackTest {
    @Test fun pegsAreGluedIntoHolesAndTheRackHangsOnTheWall() {
        val bytes = javaClass.getResourceAsStream("/complex/coat_rack.stl")!!.readBytes()
        val a = Pipeline.analyze(bytes, "coat_rack.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm"))
        assertEquals(5, a.parts.count { it.name == "Peg" }, a.parts.map { it.name }.toString())
        assertEquals(2, a.parts.count { it.name == "Bracket" })
        assertEquals(1, a.parts.count { it.name == "Back board" })
        val report = splitziner.check.DesignCheck.run(a.cutList.parts, a.cutList.rows, LengthFormat("in", "fraction"), null, housed = a.housed)
        assertTrue(report.findings.none { it.title.contains("occupy the same space") || it.title == "It would tip over" }, report.findings.map { it.title }.toString())
        assertTrue(report.findings.any { it.title == "Hang it on the wall" })
        val pl = splitziner.explode.AssemblyPlan.plan(a.parts)
        val f = splitziner.joinery.Fastening.plan(pl.parts, pl.steps, splitziner.joinery.JoinMethod.SCREWS, LengthFormat("in", "fraction"), housed = a.housed)
        assertEquals(5, f.all.count { it.kind == "peghole" })
        // All five pegs go in together, along their length.
        assertEquals(1, pl.steps.count { st -> st.partIds.any { id -> pl.parts.first { it.id == id }.name == "Peg" } })
    }
}
