package splitziner.io

/**
 * Reads PLY (Stanford triangle format), as text or binary: what scanning and sculpting apps often export. Only the
 * vertex positions and the faces are used; faces with more than three corners are fanned into triangles. PLY
 * records no unit, so sizes are guessed as for an STL.
 */
object PlyReader {
    fun looksLikePly(head: ByteArray): Boolean = head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "ply\n" || (head.size >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "ply\r\n")

    private class Prop(val name: String, val type: String, val countType: String? = null)
    private class Element(val name: String, val count: Int, val props: MutableList<Prop> = ArrayList())

    fun read(input: java.io.InputStream, warnings: MutableList<String>): FloatArray {
        val bytes = input.readBytes()
        // The header: text lines up to "end_header".
        var at = 0
        val lines = ArrayList<String>()
        while (at < bytes.size) {
            var e = at; while (e < bytes.size && bytes[e] != '\n'.code.toByte()) e++
            val line = String(bytes, at, e - at, Charsets.ISO_8859_1).trim()
            at = e + 1
            lines += line
            if (line == "end_header") break
            if (lines.size > 10_000) throw StlException("Not a PLY file")
        }
        if (lines.lastOrNull() != "end_header") throw StlException("The PLY file's header is cut short")
        var format = "ascii"
        val elements = ArrayList<Element>()
        for (l in lines) {
            val w = l.split(Regex("\\s+"))
            when (w[0]) {
                "format" -> format = w.getOrElse(1) { "ascii" }
                "element" -> elements += Element(w.getOrElse(1) { "" }, w.getOrElse(2) { "0" }.toIntOrNull() ?: throw StlException("Not a PLY file"))
                "property" -> elements.lastOrNull()?.props?.add(if (w.getOrNull(1) == "list") Prop(w.getOrElse(4) { "" }, w.getOrElse(3) { "int" }, w.getOrElse(2) { "uchar" }) else Prop(w.getOrElse(2) { "" }, w.getOrElse(1) { "float" }))
            }
        }
        val verts = elements.firstOrNull { it.name == "vertex" } ?: throw StlException("The PLY file has no vertices")
        val xi = verts.props.indexOfFirst { it.name == "x" }; val yi = verts.props.indexOfFirst { it.name == "y" }; val zi = verts.props.indexOfFirst { it.name == "z" }
        if (xi < 0 || yi < 0 || zi < 0) throw StlException("The PLY file's vertices have no positions")
        val pos = DoubleArray(3 * verts.count)
        val soup = ArrayList<Float>()
        var skipped = 0
        fun faceOut(idx: IntArray) {
            if (idx.size < 3) return
            for (k in 1 until idx.size - 1) {
                val tri = intArrayOf(idx[0], idx[k], idx[k + 1])
                if (tri.any { it < 0 || it >= verts.count }) { skipped++; continue }
                for (i in tri) for (c in 0..2) soup += pos[3 * i + c].toFloat()
            }
        }
        if (format == "ascii") {
            val toks = String(bytes, at, bytes.size - at, Charsets.ISO_8859_1).split(Regex("\\s+")).filter { it.isNotEmpty() }
            var t = 0
            fun next(): Double = toks.getOrNull(t++)?.toDoubleOrNull() ?: throw StlException("The PLY file is cut short")
            for (el in elements) for (r in 0 until el.count) {
                if (el === verts) { for ((pi, p) in el.props.withIndex()) { if (p.countType != null) { val n = next().toInt(); repeat(n) { next() } } else { val v = next(); when (pi) { xi -> pos[3 * r] = v; yi -> pos[3 * r + 1] = v; zi -> pos[3 * r + 2] = v } } } }
                else for (p in el.props) {
                    if (p.countType != null) { val n = next().toInt(); val idx = IntArray(n) { next().toInt() }; if (el.name == "face" && (p.name == "vertex_indices" || p.name == "vertex_index")) faceOut(idx) }
                    else next()
                }
            }
        } else {
            val bb = java.nio.ByteBuffer.wrap(bytes).order(if (format == "binary_big_endian") java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN)
            var p0 = at
            fun read(type: String): Double {
                val size = when (type) { "char", "int8", "uchar", "uint8" -> 1; "short", "int16", "ushort", "uint16" -> 2; "double", "float64" -> 8; else -> 4 }
                if (p0 + size > bytes.size) throw StlException("The PLY file is cut short")
                val v = when (type) {
                    "char", "int8" -> bb.get(p0).toDouble(); "uchar", "uint8" -> (bb.get(p0).toInt() and 0xff).toDouble()
                    "short", "int16" -> bb.getShort(p0).toDouble(); "ushort", "uint16" -> (bb.getShort(p0).toInt() and 0xffff).toDouble()
                    "int", "int32" -> bb.getInt(p0).toDouble(); "uint", "uint32" -> (bb.getInt(p0).toLong() and 0xffffffffL).toDouble()
                    "float", "float32" -> bb.getFloat(p0).toDouble(); "double", "float64" -> bb.getDouble(p0)
                    else -> throw StlException("The PLY file uses a type the app does not know ($type)")
                }
                p0 += size; return v
            }
            for (el in elements) for (r in 0 until el.count) {
                for ((pi, p) in el.props.withIndex()) {
                    if (p.countType != null) {
                        val n = read(p.countType).toInt()
                        val idx = IntArray(n) { read(p.type).toInt() }
                        if (el.name == "face" && (p.name == "vertex_indices" || p.name == "vertex_index")) faceOut(idx)
                    } else {
                        val v = read(p.type)
                        if (el === verts) when (pi) { xi -> pos[3 * r] = v; yi -> pos[3 * r + 1] = v; zi -> pos[3 * r + 2] = v }
                    }
                }
            }
        }
        if (skipped > 0) warnings += "$skipped faces pointing at missing vertices were skipped"
        if (soup.isEmpty()) throw StlException(if (elements.none { it.name == "face" }) "The PLY file is a point cloud with no faces; mesh it first in the scanning app" else "The PLY file contains no triangles")
        return soup.toFloatArray()
    }
}
