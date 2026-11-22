package splitziner.io

/**
 * Reads 3MF, the zipped XML format many modelling and slicing apps export. Each object placed in the build
 * (and each mesh inside a component object) becomes its own part, moved by its transform, and sizes are
 * converted to millimetres from the file's own unit.
 */
object ThreeMfReader {
    /** A zip file (3MF is one): it starts "PK", then 3 and 4. */
    fun looksLike3mf(head: ByteArray): Boolean = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    class Result(val soup: FloatArray, val groups: IntArray?)

    private class Obj(val verts: DoubleArray?, val tris: IntArray?, val comps: List<Pair<String, DoubleArray?>>)

    /** Millimetres per file unit; 3MF's default is the millimetre. */
    private fun scaleOf(unit: String?) = when (unit?.lowercase()) {
        "micron" -> 0.001; "centimeter" -> 10.0; "inch" -> 25.4; "foot" -> 304.8; "meter" -> 1000.0; else -> 1.0
    }

    fun read(input: java.io.InputStream, warnings: MutableList<String>): Result {
        var model: String? = null
        java.util.zip.ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.lowercase()
                // The main model is 3D/3dmodel.model; take the first .model file if it is named otherwise.
                if (n.endsWith(".model") && (model == null || n == "3d/3dmodel.model")) model = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        val text = model ?: throw StlException("This zip file has no 3D model in it (not a 3MF file)")
        return parse(text, warnings)
    }

    private val TAG = Regex("""<(/?)(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)((?:[^>"']|"[^"]*"|'[^']*')*?)(/?)>""")
    private val ATTR = Regex("""(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)\s*=\s*["']([^"']*)["']""")

    fun parse(text: String, warnings: MutableList<String>): Result {
        val objs = HashMap<String, Obj>()
        val build = ArrayList<Pair<String, DoubleArray?>>()
        var scale = 1.0
        var curId: String? = null
        val vx = DoubleArrayBuilder(); val tri = IntArrayBuilder(); val comps = ArrayList<Pair<String, DoubleArray?>>()
        var inBuild = false
        for (m in TAG.findAll(text)) {
            val close = m.groupValues[1] == "/"; val tag = m.groupValues[2]; val selfClose = m.groupValues[4] == "/"
            fun attrs(): Map<String, String> = ATTR.findAll(m.groupValues[3]).associate { it.groupValues[1] to it.groupValues[2] }
            when (tag) {
                "model" -> if (!close) scale = scaleOf(attrs()["unit"])
                "object" -> if (!close) {
                    curId = attrs()["id"]; vx.clear(); tri.clear(); comps.clear()
                    if (selfClose) curId = null
                } else {
                    curId?.let { id -> objs[id] = Obj(if (vx.size > 0) vx.toArray() else null, if (tri.size > 0) tri.toArray() else null, comps.toList()) }
                    curId = null
                }
                "vertex" -> if (curId != null && !close) { val a = attrs(); vx.add(a["x"]?.toDoubleOrNull() ?: 0.0); vx.add(a["y"]?.toDoubleOrNull() ?: 0.0); vx.add(a["z"]?.toDoubleOrNull() ?: 0.0) }
                "triangle" -> if (curId != null && !close) { val a = attrs(); val v1 = a["v1"]?.toIntOrNull(); val v2 = a["v2"]?.toIntOrNull(); val v3 = a["v3"]?.toIntOrNull(); if (v1 != null && v2 != null && v3 != null) { tri.add(v1); tri.add(v2); tri.add(v3) } }
                "component" -> if (curId != null && !close) { val a = attrs(); a["objectid"]?.let { comps += it to matrix(a["transform"]) } }
                "build" -> inBuild = !close && !selfClose
                "item" -> if (inBuild && !close) { val a = attrs(); a["objectid"]?.let { build += it to matrix(a["transform"]) } }
            }
        }
        if (objs.isEmpty()) throw StlException("The 3MF file has no objects")
        // Without a build list, every object that is not part of another is placed as it is.
        val items = build.ifEmpty { val used = objs.values.flatMap { o -> o.comps.map { it.first } }.toSet(); objs.keys.filter { it !in used }.sorted().map { it to null } }
        val soup = FloatArrayBuilder(); val groups = IntArrayBuilder()
        var group = 0
        fun emit(id: String, t: DoubleArray?, depth: Int) {
            val o = objs[id] ?: return
            if (depth > 16) { warnings += "Components nested too deeply were skipped"; return }
            if (o.verts != null && o.tris != null) {
                val v = o.verts
                for (k in 0 until o.tris.size / 3) {
                    for (c in 0..2) {
                        val i = o.tris[3 * k + c]
                        if (i < 0 || 3 * i + 2 >= v.size) continue
                        val x = v[3 * i]; val y = v[3 * i + 1]; val z = v[3 * i + 2]
                        val p = if (t == null) doubleArrayOf(x, y, z) else doubleArrayOf(x * t[0] + y * t[3] + z * t[6] + t[9], x * t[1] + y * t[4] + z * t[7] + t[10], x * t[2] + y * t[5] + z * t[8] + t[11])
                        for (q in p) soup.add((q * scale).toFloat())
                    }
                    groups.add(group)
                }
                group++
            }
            for ((cid, ct) in o.comps) emit(cid, compose(ct, t), depth + 1)
        }
        for ((id, t) in items) emit(id, t, 0)
        if (scale != 1.0) warnings += "Sizes converted to millimetres from the file's unit"
        val arr = soup.toArray()
        // A triangle with a bad vertex index was dropped above; keep groups in step with what was written.
        val n = arr.size / 9
        val g = groups.toArray()
        return Result(arr.copyOf(n * 9), if (group > 1 && g.size == n) g else null)
    }

    /** "m00 m01 m02 m10 m11 m12 m20 m21 m22 m30 m31 m32": a point (x y z 1) times this 4x3 matrix. */
    private fun matrix(s: String?): DoubleArray? = s?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 12 }?.toDoubleArray()

    /** First [inner], then [outer]. */
    private fun compose(inner: DoubleArray?, outer: DoubleArray?): DoubleArray? {
        if (inner == null) return outer
        if (outer == null) return inner
        val r = DoubleArray(12)
        for (row in 0..3) for (col in 0..2) {
            var v = 0.0
            for (k in 0..2) v += (if (row < 3) inner[row * 3 + k] else inner[9 + k]) * outer[k * 3 + col]
            if (row == 3) v += outer[9 + col]
            r[row * 3 + col] = v
        }
        return r
    }

    private class DoubleArrayBuilder { var a = DoubleArray(1024); var size = 0
        fun add(v: Double) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun clear() { size = 0 }; fun toArray() = a.copyOf(size) }
    private class IntArrayBuilder { var a = IntArray(1024); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun clear() { size = 0 }; fun toArray() = a.copyOf(size) }
    private class FloatArrayBuilder { var a = FloatArray(1024); var size = 0
        fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size) }
}
