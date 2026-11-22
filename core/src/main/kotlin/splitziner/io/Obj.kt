package splitziner.io

/**
 * Wavefront OBJ, the other common mesh format: "v x y z" vertices and "f a b c ..." faces, faces with more than
 * three corners split into triangles. Each "o" (object) or "g" (group) becomes a part, as named solids do in STL.
 */
object ObjReader {
    /** Whether the start of a file reads like OBJ: its first meaningful lines are vertices, objects, groups or a material library. */
    fun looksLikeObj(head: ByteArray): Boolean {
        val text = String(head, Charsets.ISO_8859_1)
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(5).toList()
        return lines.isNotEmpty() && lines.all { l -> listOf("v ", "vn ", "vt ", "o ", "g ", "mtllib ", "usemtl ", "s ", "f ").any { l.startsWith(it) } } && lines.any { it.startsWith("v ") }
    }

    class Result(val soup: FloatArray, val groups: IntArray?)

    fun read(input: java.io.InputStream, warnings: MutableList<String>): Result {
        val vx = FloatArrayList(); val tri = FloatArrayList(); val grp = IntArrayList()
        val groupIds = HashMap<String, Int>(); var group = 0; var named = false
        input.bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (raw in seq) {
                val line = raw.trim()
                if (line.startsWith("v ")) {
                    val p = line.split(Regex("\\s+"))
                    if (p.size >= 4) { vx.add(p[1].toFloat()); vx.add(p[2].toFloat()); vx.add(p[3].toFloat()) }
                } else if (line.startsWith("o ") || line.startsWith("g ")) {
                    val name = line.substring(2).trim()
                    group = groupIds.getOrPut(name) { groupIds.size }; named = true
                } else if (line.startsWith("f ")) {
                    val n = vx.size / 3
                    val idx = line.substring(2).trim().split(Regex("\\s+")).mapNotNull { t ->
                        val i = t.substringBefore('/').toIntOrNull() ?: return@mapNotNull null
                        val k = if (i < 0) n + i else i - 1
                        if (k in 0 until n) k else null
                    }
                    for (j in 1 until idx.size - 1) {
                        for (c in intArrayOf(idx[0], idx[j], idx[j + 1])) { tri.add(vx[3 * c]); tri.add(vx[3 * c + 1]); tri.add(vx[3 * c + 2]) }
                        grp.add(group)
                    }
                }
            }
        }
        if (tri.size == 0) warnings += "The OBJ file has no faces"
        return Result(tri.toArray(), if (named && groupIds.size > 1) grp.toArray() else null)
    }

    private class FloatArrayList { var a = FloatArray(1024); var size = 0
        fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
        fun toArray() = a.copyOf(size) }
    private class IntArrayList { var a = IntArray(1024); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size) }
}
