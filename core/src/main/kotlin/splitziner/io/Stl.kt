package splitziner.io

import splitziner.mesh.Mesh
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StlException(message: String) : Exception(message)

/**
 * Triangles exactly as the file stores them: 9 coordinates each, in file order.
 * File order is kept on purpose; the part splitter depends on it. Files store 32-bit numbers, so
 * triangles read from a file are kept at that precision: half the memory, nothing lost.
 */
class RawModel private constructor(private var f: FloatArray?, private var d: DoubleArray?, val sourceName: String, val warnings: List<String>) {
    /**
     * Which part each triangle belongs to, when the file says so: Splitziner's own exports mark it in each
     * triangle's spare two bytes, and text files can hold several named solids. Null when the file does not say.
     */
    var groups: IntArray? = null

    /** Lets the triangles go once the splitter has indexed them; on a large file this frees most of its memory. */
    fun release() { f = null; d = null }

    constructor(soup: FloatArray, sourceName: String, warnings: List<String>) : this(soup, null, sourceName, warnings)
    constructor(soup: DoubleArray, sourceName: String, warnings: List<String>) : this(null, soup, sourceName, warnings)

    val size: Int get() = f?.size ?: d?.size ?: 0
    val triangleCount: Int get() = size / 9
    fun coord(i: Int): Double { val a = f; return if (a != null) a[i].toDouble() else d!![i] }

    /** A full-precision copy, for tests and small files. */
    val soup: DoubleArray get() = d ?: f!!.let { a -> DoubleArray(a.size) { a[it].toDouble() } }

    fun area(t: Int): Double {
        val i = 9 * t
        val ux = coord(i + 3) - coord(i); val uy = coord(i + 4) - coord(i + 1); val uz = coord(i + 5) - coord(i + 2)
        val vx = coord(i + 6) - coord(i); val vy = coord(i + 7) - coord(i + 1); val vz = coord(i + 8) - coord(i + 2)
        val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
        return Math.sqrt(cx * cx + cy * cy + cz * cz) / 2
    }

    fun bounds(): splitziner.math.Aabb {
        if (size == 0) return splitziner.math.Aabb(splitziner.math.Vec3.ZERO, splitziner.math.Vec3.ZERO)
        val lo = DoubleArray(3) { Double.POSITIVE_INFINITY }; val hi = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        for (i in 0 until size) { val v = coord(i); val k = i % 3; if (v < lo[k]) lo[k] = v; if (v > hi[k]) hi[k] = v }
        return splitziner.math.Aabb(splitziner.math.Vec3(lo[0], lo[1], lo[2]), splitziner.math.Vec3(hi[0], hi[1], hi[2]))
    }
}

/** Splitziner's own STL files begin their header with this, so their part marks can be trusted when read back. */
const val SPLITZINER_HEADER = "Splitziner"

object StlReader {
    /**
     * Larger files are refused. Twenty million triangles is a 1 GB file; at 36 bytes a triangle
     * while reading, the limit that matters on a phone is its memory, which [read] checks as it goes.
     */
    const val MAX_TRIANGLES = 20_000_000

    fun looksLikeStl(head: ByteArray, size: Long): Boolean = isBinary(head, size) || isAscii(head) || ObjReader.looksLikeObj(head) || ThreeMfReader.looksLike3mf(head)

    private fun declaredCount(head: ByteArray): Long =
        if (head.size < 84) -1 else ByteBuffer.wrap(head, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    private fun isBinary(head: ByteArray, size: Long): Boolean {
        val n = declaredCount(head)
        return n >= 0 && size == 84 + 50 * n
    }

    private fun isAscii(head: ByteArray): Boolean {
        val text = String(head, 0, minOf(head.size, 1000), Charsets.ISO_8859_1).trimStart().lowercase()
        return text.startsWith("solid") && text.contains("facet")
    }

    fun read(bytes: ByteArray, name: String = "model.stl"): RawModel = read({ java.io.ByteArrayInputStream(bytes) }, bytes.size.toLong(), name)

    /** Reads from a stream so the file itself is never held in memory. [open] is called once or twice. */
    fun read(open: () -> java.io.InputStream, size: Long, name: String = "model.stl"): RawModel {
        val warnings = ArrayList<String>()
        val head = open().use { s -> val b = ByteArray(1024); var n = 0; while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }; b.copyOf(n) }
        // 3MF files (zipped XML) by name or by their zip signature.
        if (name.lowercase().endsWith(".3mf") || ThreeMfReader.looksLike3mf(head)) {
            val r = open().use { ThreeMfReader.read(it, warnings) }
            if (r.soup.isEmpty()) throw StlException("The file contains no triangles")
            return RawModel(r.soup, name, warnings).also { it.groups = r.groups }
        }
        // OBJ files are read too, by name or by their first lines.
        if (name.lowercase().endsWith(".obj") || (!isBinary(head, size) && !isAscii(head) && ObjReader.looksLikeObj(head))) {
            val r = open().use { ObjReader.read(it, warnings) }
            if (r.soup.isEmpty()) throw StlException("The file contains no triangles")
            return RawModel(r.soup, name, warnings).also { it.groups = r.groups }
        }
        val n = declaredCount(head)
        val soup = when {
            // Binary is tested first: many binary files begin with the word "solid".
            isBinary(head, size) -> open().use { readBinary(it, n, warnings) }
            isAscii(head) -> open().use { readAscii(it, warnings) }
            n > 0 && size >= 84 && Math.abs(84 + 50 * n - size) <= size / 100 -> {
                warnings += "File length does not match its triangle count"
                open().use { readBinary(it, minOf(n, (size - 84) / 50), warnings) }
            }
            else -> throw StlException("Not a valid STL file")
        }
        if (soup.isEmpty()) throw StlException("The file contains no triangles")
        val raw = RawModel(soup, name, warnings)
        // Part marks are trusted from Splitziner's own files (other programs use those bytes for colour) and from text files with several solids.
        val ours = String(head, 0, minOf(80, head.size), Charsets.ISO_8859_1).startsWith(SPLITZINER_HEADER)
        val a = lastAttr; val so = lastSolid
        lastAttr = null; lastSolid = null
        if (ours && a != null && a.size == raw.triangleCount && a.any { it != a[0] }) raw.groups = a
        else if (so != null && so.size == raw.triangleCount) raw.groups = so
        return raw
    }

    private fun tooBig(n: Long) {
        if (n > MAX_TRIANGLES) throw StlException("This file has more triangles than the app can work with (limit ${MAX_TRIANGLES / 1_000_000} million)")
        // Reading needs 36 bytes a triangle, and the work after it about four times that again.
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (n * 36 * 5 > free) throw StlException("This file has ${n / 1000}k triangles, more than this phone's memory can work with (about ${free / 180 / 1000}k). Simplifying it in your 3D program first will help.")
    }

    private fun readBinary(input: java.io.InputStream, n: Long, warnings: MutableList<String>): FloatArray {
        tooBig(n)
        val s = java.io.BufferedInputStream(input, 1 shl 16)
        var skip = 84L; while (skip > 0) { val k = s.skip(skip); if (k <= 0) break; skip -= k }
        val soup = FloatArray(n.toInt() * 9)
        val attr = IntArray(n.toInt())
        val rec = ByteArray(50 * 1024)
        val bb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        var t = 0; var o = 0; var bad = 0
        while (t < n) {
            val want = minOf(1024L, n - t).toInt() * 50
            var got = 0
            while (got < want) { val r = s.read(rec, got, want - got); if (r < 0) break; got += r }
            val whole = got / 50
            for (j in 0 until whole) {
                var p = j * 50 + 12          // the stored normal is never trusted
                var ok = true
                for (k in 0 until 9) { val v = bb.getFloat(p); p += 4; if (!v.isFinite()) ok = false; soup[o + k] = v }
                if (ok) { attr[o / 9] = bb.getShort(p).toInt() and 0xffff; o += 9 } else bad++
            }
            t += whole
            if (got < want) { warnings += "The file ends early; ${n - t} triangles are missing"; break }
        }
        if (bad > 0) warnings += "$bad triangles with invalid coordinates were dropped"
        lastAttr = attr.copyOf(o / 9)
        return if (o == soup.size) soup else soup.copyOf(o)
    }

    // Per-triangle part marks found by the last read; picked up by [read]. Reading is one file at a time per thread.
    private var lastAttr: IntArray? by ThreadLocalVar()
    private var lastSolid: IntArray? by ThreadLocalVar()

    private class ThreadLocalVar<T> {
        private val v = ThreadLocal<T?>()
        operator fun getValue(r: Any?, p: kotlin.reflect.KProperty<*>): T? = v.get()
        operator fun setValue(r: Any?, p: kotlin.reflect.KProperty<*>, value: T?) = v.set(value)
    }

    private fun readAscii(input: java.io.InputStream, warnings: MutableList<String>): FloatArray {
        val s = java.io.BufferedInputStream(input, 1 shl 16)
        var out = FloatArray(9 * 1024)
        var count = 0; var bad = 0
        val tok = StringBuilder()
        fun next(): String? {
            tok.setLength(0)
            var c = s.read()
            while (c in 0..32) c = s.read()
            if (c < 0) return null
            while (c > 32) { tok.append(c.toChar()); c = s.read() }
            return tok.toString()
        }
        val tri = FloatArray(9); var k = 0
        var solid = -1
        var solids = IntArray(1024)
        while (true) {
            val t = next() ?: break
            if (t.equals("solid", ignoreCase = true)) { solid++; continue }
            if (!t.equals("vertex", ignoreCase = true)) continue
            for (j in 0..2) tri[k++] = next()?.toFloatOrNull() ?: Float.NaN
            if (k == 9) {
                k = 0
                if (tri.any { !it.isFinite() }) { bad++; continue }
                if (count + 9 > out.size) { tooBig(count / 9L * 2); out = out.copyOf(out.size * 2) }
                if (count / 9 >= solids.size) solids = solids.copyOf(solids.size * 2)
                solids[count / 9] = maxOf(solid, 0)
                tri.copyInto(out, count); count += 9
            }
        }
        if (k != 0) warnings += "The file ends with an incomplete triangle; it was dropped"
        if (bad > 0) warnings += "$bad triangles with invalid coordinates were dropped"
        lastSolid = if (solid >= 1) solids.copyOf(count / 9) else null
        return out.copyOf(count)
    }
}

object StlWriter {
    /** Several parts in one binary STL, each triangle marked with its part's number (1 up), so the parts read back as they were. */
    fun writeParts(meshes: List<Mesh>, out: OutputStream, header: String = "$SPLITZINER_HEADER model, millimetres, part number in each facet attribute") {
        require(header.startsWith(SPLITZINER_HEADER))
        val total = meshes.sumOf { it.triangleCount }
        val bos = java.io.BufferedOutputStream(out, 1 shl 16)
        val head = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
        val h = header.toByteArray(Charsets.US_ASCII); head.put(h, 0, minOf(h.size, 80)); head.position(80); head.putInt(total)
        bos.write(head.array())
        val rec = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        for ((pi, mesh) in meshes.withIndex()) {
            val n = mesh.faceNormals
            for (t in 0 until mesh.triangleCount) {
                rec.clear()
                for (k in 0..2) rec.putFloat(n[3 * t + k].toFloat())
                for (c in 0..2) { val v = 3 * mesh.triangles[3 * t + c]; for (k in 0..2) rec.putFloat(mesh.vertices[v + k].toFloat()) }
                rec.putShort(((pi + 1) and 0xffff).toShort())
                bos.write(rec.array())
            }
        }
        bos.flush()
    }

    /** Binary STL with recomputed normals. [header] must not start with "solid" or readers take it for ASCII. */
    fun writeBinary(mesh: Mesh, out: OutputStream, header: String) {
        require(!header.trimStart().lowercase().startsWith("solid")) { "binary STL header must not start with 'solid'" }
        val buf = ByteBuffer.allocate(84 + 50 * mesh.triangleCount).order(ByteOrder.LITTLE_ENDIAN)
        val h = header.toByteArray(Charsets.US_ASCII)
        buf.put(h, 0, minOf(h.size, 80)); buf.position(80)
        buf.putInt(mesh.triangleCount)
        val n = mesh.faceNormals
        for (t in 0 until mesh.triangleCount) {
            for (k in 0..2) buf.putFloat(n[3 * t + k].toFloat())
            for (c in 0..2) { val v = 3 * mesh.triangles[3 * t + c]; for (k in 0..2) buf.putFloat(mesh.vertices[v + k].toFloat()) }
            buf.putShort(0)
        }
        out.write(buf.array())
    }

    fun writeAscii(mesh: Mesh, name: String = "part"): String {
        val sb = StringBuilder("solid $name\n")
        val n = mesh.faceNormals
        for (t in 0 until mesh.triangleCount) {
            sb.append(" facet normal ${n[3 * t]} ${n[3 * t + 1]} ${n[3 * t + 2]}\n  outer loop\n")
            for (c in 0..2) { val v = 3 * mesh.triangles[3 * t + c]; sb.append("   vertex ${mesh.vertices[v]} ${mesh.vertices[v + 1]} ${mesh.vertices[v + 2]}\n") }
            sb.append("  endloop\n endfacet\n")
        }
        return sb.append("endsolid $name\n").toString()
    }
}
