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
