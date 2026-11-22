package splitziner.mesh

/**
 * Open-addressing map from Long keys to Int values, with no per-entry objects.
 * Meshes have millions of edges; a HashMap<Long, Int> would box every one of them.
 */
class LongIntMap(expected: Int = 16) {
    private var cap = tableSize(expected)
    private var keys = LongArray(cap)
    private var vals = IntArray(cap)
    private var used = BooleanArray(cap)
    var size = 0
        private set

    private fun tableSize(n: Int): Int { var c = 16; while (c < n * 2L) c = c shl 1; return c }

    private fun slot(key: Long): Int {
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        var i = h.toInt() and (cap - 1)
        while (used[i] && keys[i] != key) i = (i + 1) and (cap - 1)
        return i
    }

    fun get(key: Long, default: Int = -1): Int { val i = slot(key); return if (used[i]) vals[i] else default }

    fun put(key: Long, value: Int) {
        var i = slot(key)
        if (!used[i]) {
            if ((size + 1) * 2 > cap) { grow(); i = slot(key) }
            used[i] = true; keys[i] = key; size++
        }
        vals[i] = value
    }

    /** Adds [delta] to the value for [key] (starting from 0) and returns the new value. */
    fun add(key: Long, delta: Int): Int {
        var i = slot(key)
        if (!used[i]) {
            if ((size + 1) * 2 > cap) { grow(); i = slot(key) }
            used[i] = true; keys[i] = key; vals[i] = 0; size++
        }
        vals[i] += delta
        return vals[i]
    }

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        cap = cap shl 1
        keys = LongArray(cap); vals = IntArray(cap); used = BooleanArray(cap); size = 0
        for (j in ok.indices) if (ou[j]) { val i = slot(ok[j]); used[i] = true; keys[i] = ok[j]; vals[i] = ov[j]; size++ }
    }

    fun forEach(action: (Long, Int) -> Unit) {
        for (j in keys.indices) if (used[j]) action(keys[j], vals[j])
    }
}

/** Packs an undirected edge between two vertex ids into one Long, smaller id in the high half. */
fun edgeKey(a: Int, b: Int): Long =
    if (a < b) (a.toLong() shl 32) or (b.toLong() and 0xffffffffL) else (b.toLong() shl 32) or (a.toLong() and 0xffffffffL)

fun edgeKeyA(key: Long): Int = (key ushr 32).toInt()
fun edgeKeyB(key: Long): Int = key.toInt()

/**
 * Gives every distinct quantised 3D position an id, in first-seen order.
 * Used to merge vertices that sit at the same place.
 */
class PointIndexer(expected: Int = 16) {
    private var cap = run { var c = 16; while (c < expected * 2L) c = c shl 1; c }
    private var slots = IntArray(cap) { -1 }
    private var qx = LongArray(maxOf(expected, 16)); private var qy = LongArray(qx.size); private var qz = LongArray(qx.size)
    var size = 0
        private set

    private fun hash(x: Long, y: Long, z: Long): Int {
        var h = x * -0x61c8864680b583ebL + y * -0x3d4d51c2d82b14b1L + z * 0x165667b19e3779f9L
        h = h xor (h ushr 29)
        return (h xor (h ushr 32)).toInt()
    }

    fun idOf(x: Long, y: Long, z: Long): Int {
        var i = hash(x, y, z) and (cap - 1)
        while (true) {
            val s = slots[i]
            if (s < 0) break
            if (qx[s] == x && qy[s] == y && qz[s] == z) return s
            i = (i + 1) and (cap - 1)
        }
        if (size == qx.size) { val n = size * 2; qx = qx.copyOf(n); qy = qy.copyOf(n); qz = qz.copyOf(n) }
        val id = size++
        qx[id] = x; qy[id] = y; qz[id] = z
        slots[i] = id
        if (size * 2 > cap) rehash()
        return id
    }

    private fun rehash() {
        cap = cap shl 1
        slots = IntArray(cap) { -1 }
        for (id in 0 until size) {
            var i = hash(qx[id], qy[id], qz[id]) and (cap - 1)
            while (slots[i] >= 0) i = (i + 1) and (cap - 1)
            slots[i] = id
        }
    }
}

/** Union-find over 0 until n. */
class DisjointSet(n: Int) {
    private val parent = IntArray(n) { it }
    fun find(a: Int): Int {
        var r = a
        while (parent[r] != r) r = parent[r]
        var c = a
        while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
        return r
    }
    fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
}
