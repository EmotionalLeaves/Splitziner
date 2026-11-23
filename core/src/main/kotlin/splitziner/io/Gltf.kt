package splitziner.io

/**
 * Reads glTF 2.0, as a binary .glb or a .gltf with its buffers embedded (base64), the format many phone and web
 * modelling apps export. Each mesh primitive placed in the scene becomes its own part, moved by its node's
 * transform. glTF is in metres with Y up: sizes come out in millimetres with Z up, like an STL.
 */
object GltfReader {
    /** A .glb starts with the four bytes "glTF". */
    fun looksLikeGlb(head: ByteArray): Boolean = head.size >= 4 && head[0] == 'g'.code.toByte() && head[1] == 'l'.code.toByte() && head[2] == 'T'.code.toByte() && head[3] == 'F'.code.toByte()

    /** A .gltf is JSON naming its asset version. */
    fun looksLikeGltf(head: ByteArray): Boolean = String(head, Charsets.ISO_8859_1).let { it.trimStart().startsWith("{") && it.contains("\"asset\"") }

    class Result(val soup: FloatArray, val groups: IntArray?)

    fun read(input: java.io.InputStream, warnings: MutableList<String>): Result {
        val bytes = input.readBytes()
        val json: Any?; var bin: ByteArray? = null
        if (looksLikeGlb(bytes)) {
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 20) throw StlException("The glTF file is cut short")
            var at = 12; var js: String? = null
            while (at + 8 <= bytes.size) {
                val len = bb.getInt(at); val type = bb.getInt(at + 4)
                if (len < 0 || at + 8 + len > bytes.size) break
                if (type == 0x4E4F534A) js = String(bytes, at + 8, len, Charsets.UTF_8)
                else if (type == 0x004E4942) bin = bytes.copyOfRange(at + 8, at + 8 + len)
                at += 8 + len
            }
            json = Json.parse(js ?: throw StlException("The glTF file has no scene description"))
        } else json = Json.parse(String(bytes, Charsets.UTF_8))
        val root = json as? Map<*, *> ?: throw StlException("Not a glTF file")
        if (root.containsKey("extensionsRequired") && (root["extensionsRequired"] as? List<*>)?.any { it.toString().contains("draco") || it.toString().contains("meshopt") } == true)
            throw StlException("This glTF file is compressed (Draco or meshopt), which the app cannot read. Export it again without compression, or as STL or OBJ")
        fun list(k: String) = (root[k] as? List<*>).orEmpty().map { it as? Map<*, *> ?: emptyMap<Any, Any>() }
        val buffers = list("buffers").mapIndexed { i, b ->
            val uri = b["uri"] as? String
            when {
                uri == null -> if (i == 0) bin else null
                uri.startsWith("data:") -> java.util.Base64.getDecoder().decode(uri.substringAfter(","))
                else -> throw StlException("This glTF file keeps its geometry in a separate file ($uri). Export it as a single .glb instead")
            }
        }
        val views = list("bufferViews"); val accessors = list("accessors"); val meshes = list("meshes"); val nodes = list("nodes")
        fun num(m: Map<*, *>, k: String, d: Double = 0.0) = (m[k] as? Number)?.toDouble() ?: d
        fun floats(ai: Int, comps: Int): DoubleArray {
            val a = accessors.getOrNull(ai) ?: throw StlException("The glTF file refers to missing data")
            val count = num(a, "count").toInt()
            val out = DoubleArray(count * comps)
            val vi = (a["bufferView"] as? Number)?.toInt() ?: return out
            val v = views[vi]; val buf = buffers.getOrNull(num(v, "buffer").toInt()) ?: throw StlException("The glTF file refers to missing data")
            val ct = num(a, "componentType").toInt()
            val size = when (ct) { 5126, 5125 -> 4; 5123, 5122 -> 2; else -> 1 }
            val stride = num(v, "byteStride").toInt().takeIf { it > 0 } ?: (size * comps)
            val base = num(v, "byteOffset").toInt() + num(a, "byteOffset").toInt()
            val bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val normalized = a["normalized"] == true
            for (i in 0 until count) for (c in 0 until comps) {
                val p = base + i * stride + c * size
                if (p + size > buf.size) throw StlException("The glTF file is cut short")
                out[i * comps + c] = when (ct) {
                    5126 -> bb.getFloat(p).toDouble()
                    5125 -> (bb.getInt(p).toLong() and 0xffffffffL).toDouble()
                    5123 -> (bb.getShort(p).toInt() and 0xffff).toDouble().let { if (normalized) it / 65535 else it }
                    5122 -> bb.getShort(p).toDouble().let { if (normalized) maxOf(it / 32767, -1.0) else it }
                    5121 -> (bb.get(p).toInt() and 0xff).toDouble().let { if (normalized) it / 255 else it }
                    else -> bb.get(p).toDouble().let { if (normalized) maxOf(it / 127, -1.0) else it }
                }
            }
            return out
        }
        val soup = FloatBuf(); var groups = IntArray(1 shl 14); var nGroups = 0
        var group = 0
        fun emit(ni: Int, parent: DoubleArray, depth: Int) {
            if (depth > 32) { warnings += "Nodes nested too deeply were skipped"; return }
            val n = nodes.getOrNull(ni) ?: return
            val m = mul(parent, local(n))
            (n["mesh"] as? Number)?.toInt()?.let { mi ->
                for (prim in (meshes.getOrNull(mi)?.get("primitives") as? List<*>).orEmpty().map { it as? Map<*, *> ?: emptyMap<Any, Any>() }) {
                    val mode = num(prim, "mode", 4.0).toInt()
                    if (mode != 4) { warnings += "Lines, points and strips in the glTF file were skipped"; continue }
                    val attrs = prim["attributes"] as? Map<*, *> ?: continue
                    val pos = floats((attrs["POSITION"] as? Number)?.toInt() ?: continue, 3)
                    val nv = pos.size / 3
                    val idx = (prim["indices"] as? Number)?.toInt()?.let { floats(it, 1).let { d -> IntArray(d.size) { i -> d[i].toInt() } } } ?: IntArray(nv) { it }
                    for (t in 0 until idx.size / 3) {
                        val tri = (0..2).map { idx[3 * t + it] }
                        if (tri.any { it < 0 || it >= nv }) continue
                        for (i in tri) {
                            val x = pos[3 * i]; val y = pos[3 * i + 1]; val z = pos[3 * i + 2]
                            val wx = m[0] * x + m[4] * y + m[8] * z + m[12]; val wy = m[1] * x + m[5] * y + m[9] * z + m[13]; val wz = m[2] * x + m[6] * y + m[10] * z + m[14]
                            // Metres to millimetres; Y up to Z up (a quarter turn about X, keeping it right-handed).
                            soup += (wx * 1000).toFloat(); soup += (-wz * 1000).toFloat(); soup += (wy * 1000).toFloat()
                        }
                        if (nGroups == groups.size) groups = groups.copyOf(groups.size * 2)
                        groups[nGroups++] = group
                    }
                    group++
                }
            }
            for (c in (n["children"] as? List<*>).orEmpty()) (c as? Number)?.toInt()?.let { emit(it, m, depth + 1) }
        }
        val scenes = list("scenes")
        val si = (root["scene"] as? Number)?.toInt() ?: 0
        val top = scenes.getOrNull(si)?.get("nodes") as? List<*>
            ?: run { val child = nodes.flatMap { (it["children"] as? List<*>).orEmpty() }.mapNotNull { (it as? Number)?.toInt() }.toSet(); nodes.indices.filter { it !in child } }
        for (t in top) (t as? Number)?.toInt()?.let { emit(it, IDENTITY, 0) }
        if (soup.isEmpty()) throw StlException("The glTF file contains no triangles")
        warnings += "Sizes converted to millimetres from glTF's metres"
        return Result(soup.toFloatArray(), if (group > 1) groups.copyOf(nGroups) else null)
    }

    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    /** A node's own transform, column-major 4x4: its matrix, or translation x rotation x scale. */
    private fun local(n: Map<*, *>): DoubleArray {
        (n["matrix"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 16 }?.let { return it.toDoubleArray() }
        val t = (n["translation"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 3 } ?: listOf(0.0, 0.0, 0.0)
        val q = (n["rotation"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 } ?: listOf(0.0, 0.0, 0.0, 1.0)
        val s = (n["scale"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 3 } ?: listOf(1.0, 1.0, 1.0)
        val (x, y, z, w) = q
        val r = doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0.0,
            2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0.0,
            2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0.0,
            0.0, 0.0, 0.0, 1.0)
        for (c in 0..2) for (k in 0..2) r[4 * c + k] *= s[c]
        r[12] = t[0]; r[13] = t[1]; r[14] = t[2]
        return r
    }

    /** a then b, column-major: a x b. */
    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (c in 0..3) for (row in 0..3) { var v = 0.0; for (k in 0..3) v += a[4 * k + row] * b[4 * c + k]; r[4 * c + row] = v }
        return r
    }
}

/** Just enough JSON for glTF: objects, arrays, strings, numbers, true, false, null. */
internal object Json {
    fun parse(s: String): Any? { val p = P(s); val v = p.value(); return v }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws(); if (i >= s.length) throw StlException("The glTF file's description is cut short")
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); expect(':'); m[k] = value(); ws(); if (s[i] == ',') { i++; continue }; expect('}'); break }
                    m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i] == ',') { i++; continue }; expect(']'); break }
                    l }
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; if (st == i) throw StlException("Not a glTF file (unexpected '$c')"); s.substring(st, i).toDouble() }
            }
        }
        fun expect(c: Char) { ws(); if (i >= s.length || s[i] != c) throw StlException("Not a glTF file"); i++ }
        fun str(): String {
            expect('"'); val b = StringBuilder()
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length) { i++; when (s[i]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); 'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> b.append(s[i]) } }
                else b.append(s[i])
                i++
            }
            i++
            return b.toString()
        }
    }
}
