package splitziner

import splitziner.io.RawModel
import splitziner.math.Vec3
import splitziner.mesh.Mesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Meshes built in code, so tests need no binary files. */
object Fixtures {
    const val INCH = 25.4

    /** Axis-aligned box: 8 vertices, 12 outward-wound triangles. */
    fun box(lo: Vec3, hi: Vec3): Mesh {
        val v = doubleArrayOf(
            lo.x, lo.y, lo.z, hi.x, lo.y, lo.z, hi.x, hi.y, lo.z, lo.x, hi.y, lo.z,
            lo.x, lo.y, hi.z, hi.x, lo.y, hi.z, hi.x, hi.y, hi.z, lo.x, hi.y, hi.z,
        )
        val t = intArrayOf(
            0, 2, 1, 0, 3, 2,   // bottom
            4, 5, 6, 4, 6, 7,   // top
            0, 1, 5, 0, 5, 4,   // front
            1, 2, 6, 1, 6, 5,   // right
            2, 3, 7, 2, 7, 6,   // back
            3, 0, 4, 3, 4, 7,   // left
        )
        return Mesh(v, t)
    }

    fun box(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double, unit: Double = 1.0) =
        box(Vec3(x0 * unit, y0 * unit, z0 * unit), Vec3(x1 * unit, y1 * unit, z1 * unit))

    /** Closed cylinder along Z from z0 to z1. */
    fun cylinder(radius: Double, z0: Double, z1: Double, sides: Int = 64): Mesh {
        val v = ArrayList<Double>(); val t = ArrayList<Int>()
        for (i in 0 until sides) {
            val a = 2 * PI * i / sides
            v += radius * cos(a); v += radius * sin(a); v += z0
            v += radius * cos(a); v += radius * sin(a); v += z1
        }
        val cb = 2 * sides; val ct = 2 * sides + 1
        v += 0.0; v += 0.0; v += z0; v += 0.0; v += 0.0; v += z1
        for (i in 0 until sides) {
            val j = (i + 1) % sides
            val b0 = 2 * i; val t0 = 2 * i + 1; val b1 = 2 * j; val t1 = 2 * j + 1
            t += b0; t += b1; t += t1; t += b0; t += t1; t += t0
            t += cb; t += b1; t += b0
            t += ct; t += t0; t += t1
        }
        return Mesh(v.toDoubleArray(), t.toIntArray())
    }

    fun rotatedZ(m: Mesh, degrees: Double): Mesh {
        val a = Math.toRadians(degrees); val c = cos(a); val s = sin(a)
        val v = m.vertices.copyOf()
        for (i in 0 until m.vertexCount) { val x = v[3 * i]; val y = v[3 * i + 1]; v[3 * i] = c * x - s * y; v[3 * i + 1] = s * x + c * y }
        return Mesh(v, m.triangles)
    }

    /** An L-shaped prism: a 2x2x1 block with one 1x1x1 corner removed, built from three boxes' outer skin. */
    fun lBracket(u: Double = 20.0): Mesh {
        // Extrude the L outline (6 corners) along Z.
        val o = listOf(0.0 to 0.0, 2.0 to 0.0, 2.0 to 1.0, 1.0 to 1.0, 1.0 to 2.0, 0.0 to 2.0)
        val v = ArrayList<Double>(); val t = ArrayList<Int>()
        for ((x, y) in o) { v += x * u; v += y * u; v += 0.0 }
        for ((x, y) in o) { v += x * u; v += y * u; v += u }
        for (i in 0 until 6) { val j = (i + 1) % 6; t += i; t += j; t += j + 6; t += i; t += j + 6; t += i + 6 }
        // Caps: the L splits into triangles 0-1-2, 0-2-3, 0-3-4, 0-4-5.
        for ((a, b, c) in listOf(Triple(0, 1, 2), Triple(0, 2, 3), Triple(0, 3, 4), Triple(0, 4, 5))) {
            t += a; t += c; t += b
            t += a + 6; t += b + 6; t += c + 6
        }
        return Mesh(v.toDoubleArray(), t.toIntArray())
    }

    /** A box whose top face is cut into two rectangles, leaving a T-junction on two of the side faces' top edges. */
    fun boxWithTJunction(): Mesh {
        val b = box(0.0, 40.0, 0.0, 20.0, 0.0, 10.0)
        val v = b.vertices.toMutableList()
        // Extra vertices at the middle of the top's front and back edges.
        val f = v.size / 3; v += 20.0; v += 0.0; v += 10.0
        val k = v.size / 3; v += 20.0; v += 20.0; v += 10.0
        val t = b.triangles.toMutableList()
        // Replace the two top triangles (indices 2 and 3) with four that use the new vertices.
        repeat(6) { t.removeAt(6) }
        t.addAll(6, listOf(4, f, k, 4, k, 7, f, 5, 6, f, 6, k))
        return Mesh(v.toDoubleArray(), t.toIntArray())
    }

    /** Seven-part cabinet designed in inches: 36 x 24 x 60, open at the front. Coordinates in [unit] per inch. */
    fun miniCabinet(unit: Double = 1.0): List<Mesh> = listOf(
        box(0.0, 0.75, 0.0, 24.0, 0.0, 60.0, unit),          // left side
        box(35.25, 36.0, 0.0, 24.0, 0.0, 60.0, unit),        // right side
        box(0.75, 35.25, 0.0, 23.75, 0.0, 0.75, unit),       // bottom
        box(0.75, 35.25, 0.0, 23.75, 59.25, 60.0, unit),     // top
        box(0.75, 35.25, 1.75, 23.75, 30.0, 30.75, unit),    // shelf
        box(0.75, 35.25, 23.75, 24.0, 0.0, 60.0, unit),      // back
        box(0.75, 35.25, 0.0, 0.75, 55.75, 59.25, unit),     // front rail
    )

    /** Writes bodies as unindexed triangles, optionally repeated, interleaved or scaled. */
    fun soup(bodies: List<Mesh>, repeat: Int = 1, interleave: Boolean = false, scale: Double = 1.0): RawModel {
        val tris = ArrayList<DoubleArray>()
        val perBody = bodies.map { m ->
            (0 until m.triangleCount).map { t ->
                DoubleArray(9) { i -> m.vertices[3 * m.triangles[3 * t + i / 3] + i % 3] * scale }
            }
        }
        if (interleave) {
            val longest = perBody.maxOf { it.size }
            for (i in 0 until longest) for (b in perBody) if (i < b.size) tris += b[i]
        } else {
            for (b in perBody) repeat(repeat) { tris.addAll(b) }
        }
        val out = DoubleArray(tris.size * 9)
        tris.forEachIndexed { i, a -> a.copyInto(out, 9 * i) }
        return RawModel(out, "fixture.stl", emptyList())
    }

    fun binaryStl(raw: RawModel, header: String = "fixture"): ByteArray {
        val n = raw.triangleCount
        val buf = java.nio.ByteBuffer.allocate(84 + 50 * n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(header.toByteArray(Charsets.US_ASCII).copyOf(80)); buf.putInt(n)
        for (t in 0 until n) { repeat(3) { buf.putFloat(0f) }; for (k in 0 until 9) buf.putFloat(raw.soup[9 * t + k].toFloat()); buf.putShort(0) }
        return buf.array()
    }
}
