package splitziner.io

import splitziner.mesh.Mesh
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StlException(message: String) : Exception(message)

/**
 * Triangles exactly as the file stores them: 9 doubles each, in file order.
 * File order is kept on purpose; the part splitter depends on it.
 */
class RawModel(val soup: DoubleArray, val sourceName: String, val warnings: List<String>) {
    val triangleCount: Int get() = soup.size / 9
}

object StlReader {
    /** Larger files are refused: a phone cannot hold the working data for them. */
    const val MAX_TRIANGLES = 5_000_000

    fun looksLikeStl(head: ByteArray, size: Long): Boolean = isBinary(head, size) || isAscii(head)

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

    fun read(bytes: ByteArray, name: String = "model.stl"): RawModel {
        val warnings = ArrayList<String>()
        val size = bytes.size.toLong()
        val n = declaredCount(bytes)
        val soup = when {
            // Binary is tested first: many binary files begin with the word "solid".
            isBinary(bytes, size) -> readBinary(bytes, n)
            isAscii(bytes) -> readAscii(bytes, warnings)
            n > 0 && size >= 84 && Math.abs(84 + 50 * n - size) <= size / 100 -> {
                warnings += "File length does not match its triangle count"
                readBinary(bytes, minOf(n, (size - 84) / 50))
            }
            else -> throw StlException("Not a valid STL file")
        }
        val clean = dropNonFinite(soup, warnings)
        if (clean.isEmpty()) throw StlException("The file contains no triangles")
        return RawModel(clean, name, warnings)
    }

    private fun readBinary(bytes: ByteArray, n: Long): DoubleArray {
        if (n > MAX_TRIANGLES) throw StlException("This file is too large for this version (limit 5 million triangles)")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val soup = DoubleArray(n.toInt() * 9)
        var p = 84
        for (t in 0 until n.toInt()) {
            p += 12 // the stored normal is never trusted
            for (k in 0 until 9) { soup[9 * t + k] = buf.getFloat(p).toDouble(); p += 4 }
            p += 2
        }
        return soup
    }

    private fun readAscii(bytes: ByteArray, warnings: MutableList<String>): DoubleArray {
        var out = DoubleArray(9 * 1024)
        var count = 0
        var i = 0
        val n = bytes.size
        fun isSpace(b: Byte) = b <= 32
        fun nextToken(): String? {
            while (i < n && isSpace(bytes[i])) i++
            if (i >= n) return null
            val s = i
            while (i < n && !isSpace(bytes[i])) i++
            return String(bytes, s, i - s, Charsets.ISO_8859_1)
        }
        while (true) {
            val tok = nextToken() ?: break
            if (!tok.equals("vertex", ignoreCase = true)) continue
            for (k in 0..2) {
                val v = nextToken()?.toDoubleOrNull() ?: Double.NaN
                if (count == out.size) out = out.copyOf(out.size * 2)
                out[count++] = v
            }
            if (count / 9 > MAX_TRIANGLES) throw StlException("This file is too large for this version (limit 5 million triangles)")
        }
        if (count % 9 != 0) { warnings += "The file ends with an incomplete triangle; it was dropped"; count -= count % 9 }
        return out.copyOf(count)
    }

    private fun dropNonFinite(soup: DoubleArray, warnings: MutableList<String>): DoubleArray {
        val nT = soup.size / 9
        var bad = 0
        for (t in 0 until nT) for (k in 0 until 9) if (!soup[9 * t + k].isFinite()) { bad++; break }
        if (bad == 0) return soup
        val out = DoubleArray((nT - bad) * 9)
        var o = 0
        for (t in 0 until nT) {
            var ok = true
            for (k in 0 until 9) if (!soup[9 * t + k].isFinite()) ok = false
            if (ok) { soup.copyInto(out, o, 9 * t, 9 * t + 9); o += 9 }
        }
        warnings += "$bad triangles with invalid coordinates were dropped"
        return out
    }
}

object StlWriter {
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
