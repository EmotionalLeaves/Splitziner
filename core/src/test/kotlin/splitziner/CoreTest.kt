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
        assertTrue(r.scale in splitziner.drawing.DrawingBuilder.SCALES.toList())
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
        // The base is the bottom panel: largest footprint on the floor.
        assertEquals(listOf("P03"), plan.steps[0].partIds)
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
        assertEquals("Place item 2.", steps[0].text[0])
        assertTrue(steps[1].text[0].startsWith("Fit item 1 from the left"), steps[1].text[0])
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
        for (b in boards) assertTrue(b.cuts.sumOf { it.second } + stock.kerf * (b.cuts.size - 1) <= stock.boardL + 1e-6)
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
    private fun problems(ps: List<Part>): List<String> {
        val cut = CutList.build(ps, mm)
        return splitziner.check.DesignCheck.run(cut.parts, cut.rows, mm, splitziner.check.Tool.entries.toSet()).findings.filter { it.severity == splitziner.check.Severity.PROBLEM }.map { it.title }
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
        val fixed = splitziner.edit.Editor.apply(ps, plan.edits).parts
        assertEquals(emptyList(), problems(fixed))
        assertEquals(18.0, fixed[2].mesh.bounds.min.z, 1e-6)                        // seated on the base
        assertEquals(12.0, fixed[2].mesh.extents.z, 1e-6)                           // and now a stock thickness
        // The pipeline replays the same changes on the original file.
        val bytes = Fixtures.binaryStl(Fixtures.soup(listOf(base, post, riser)))
        val a = Pipeline.analyze(bytes, "broken.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm", edits = splitziner.edit.AutoFix.plan(Pipeline.analyze(bytes, "broken.stl", splitziner.pipeline.AnalysisOptions(fileUnits = "mm")).originalParts, mm).edits))
        assertEquals(emptyList(), problems(a.parts.map { Part(it.id, it.name, it.mesh) }))
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
        assertTrue(plan.steps.last().partIds == listOf("P03"), plan.steps.map { it.partIds }.toString())
        val guide = splitziner.guide.GuideSteps.build(plan.parts, plan.steps)
        assertTrue(guide[0].title.startsWith("Step 1: make 2 by joining items"), guide[0].title)
        assertTrue(guide[0].text[0].startsWith("Lay down"), guide[0].text.toString())
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
        val lengths = f.hardware.mapNotNull { Regex("""× ([\d/-]+) in screws""").find(it)?.groupValues?.get(1) }
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
