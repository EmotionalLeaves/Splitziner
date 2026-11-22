package splitziner.mesh

import splitziner.math.Vec3

/** Result of a nearest-hit ray query. */
class Hit(val triangle: Int, val t: Double)

/**
 * Bounding-volume hierarchy over a mesh's triangles: median split on the longest axis,
 * at most [LEAF] triangles per leaf. Queries are iterative and reuse a per-thread stack.
 */
class Bvh(private val mesh: Mesh) {
    private val order = IntArray(mesh.triangleCount) { it }
    private var nodeCount = 0
    private var lo = DoubleArray(0)      // 3 per node
    private var hi = DoubleArray(0)
    private var left = IntArray(0)       // child index, or -1 for a leaf
    private var right = IntArray(0)
    private var start = IntArray(0)      // leaf: range in [order]
    private var count = IntArray(0)

    init {
        val cap = maxOf(1, 2 * (mesh.triangleCount / (LEAF / 2) + 1))
        lo = DoubleArray(cap * 3); hi = DoubleArray(cap * 3)
        left = IntArray(cap); right = IntArray(cap); start = IntArray(cap); count = IntArray(cap)
        if (mesh.triangleCount > 0) build(0, mesh.triangleCount)
    }

    private fun ensure(n: Int) {
        if (n < left.size) return
        val c = left.size * 2
        lo = lo.copyOf(c * 3); hi = hi.copyOf(c * 3)
        left = left.copyOf(c); right = right.copyOf(c); start = start.copyOf(c); count = count.copyOf(c)
    }

    private fun build(from: Int, to: Int): Int {
        val node = nodeCount++
        ensure(nodeCount)
        var x0 = Double.POSITIVE_INFINITY; var y0 = x0; var z0 = x0
        var x1 = Double.NEGATIVE_INFINITY; var y1 = x1; var z1 = x1
        val v = mesh.vertices; val tr = mesh.triangles
        for (i in from until to) {
            val t = order[i]
            for (k in 0..2) {
                val p = 3 * tr[3 * t + k]
                if (v[p] < x0) x0 = v[p]; if (v[p] > x1) x1 = v[p]
                if (v[p + 1] < y0) y0 = v[p + 1]; if (v[p + 1] > y1) y1 = v[p + 1]
                if (v[p + 2] < z0) z0 = v[p + 2]; if (v[p + 2] > z1) z1 = v[p + 2]
            }
        }
        lo[3 * node] = x0; lo[3 * node + 1] = y0; lo[3 * node + 2] = z0
        hi[3 * node] = x1; hi[3 * node + 1] = y1; hi[3 * node + 2] = z1
        if (to - from <= LEAF) { left[node] = -1; start[node] = from; count[node] = to - from; return node }
        val ex = x1 - x0; val ey = y1 - y0; val ez = z1 - z0
        val axis = if (ex >= ey && ex >= ez) 0 else if (ey >= ez) 1 else 2
        val mid = (from + to) / 2
        select(from, to, mid, axis)
        val l = build(from, mid)
        val r = build(mid, to)
        left[node] = l; right[node] = r
        return node
    }

    private fun key(t: Int, axis: Int): Double = mesh.faceCenters[3 * t + axis]

    /** Quickselect: afterwards order[k] is the k-th triangle by centre on [axis], smaller ones before it. */
    private fun select(from: Int, to: Int, k: Int, axis: Int) {
        var a = from; var b = to - 1
        while (a < b) {
            val pivot = key(order[(a + b) ushr 1], axis)
            var i = a; var j = b
            while (i <= j) {
                while (key(order[i], axis) < pivot) i++
                while (key(order[j], axis) > pivot) j--
                if (i <= j) { val tmp = order[i]; order[i] = order[j]; order[j] = tmp; i++; j-- }
            }
            if (k <= j) b = j else if (k >= i) a = i else return
        }
    }

    private fun boxHit(n: Int, ox: Double, oy: Double, oz: Double, ix: Double, iy: Double, iz: Double, tMax: Double): Boolean {
        var t0 = 0.0; var t1 = tMax
        var a = (lo[3 * n] - ox) * ix; var b = (hi[3 * n] - ox) * ix
        if (a > b) { val s = a; a = b; b = s }
        if (a > t0) t0 = a; if (b < t1) t1 = b
        if (t0 > t1) return false
        a = (lo[3 * n + 1] - oy) * iy; b = (hi[3 * n + 1] - oy) * iy
        if (a > b) { val s = a; a = b; b = s }
        if (a > t0) t0 = a; if (b < t1) t1 = b
        if (t0 > t1) return false
        a = (lo[3 * n + 2] - oz) * iz; b = (hi[3 * n + 2] - oz) * iz
        if (a > b) { val s = a; a = b; b = s }
        if (a > t0) t0 = a; if (b < t1) t1 = b
        return t0 <= t1
    }

    /** Möller–Trumbore; returns the ray parameter, or a negative number when there is no hit. */
    private fun triHit(t: Int, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Double {
        val v = mesh.vertices; val tr = mesh.triangles
        val a = 3 * tr[3 * t]; val b = 3 * tr[3 * t + 1]; val c = 3 * tr[3 * t + 2]
        val e1x = v[b] - v[a]; val e1y = v[b + 1] - v[a + 1]; val e1z = v[b + 2] - v[a + 2]
        val e2x = v[c] - v[a]; val e2y = v[c + 1] - v[a + 1]; val e2z = v[c + 2] - v[a + 2]
        val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (det > -1e-300 && det < 1e-300) return -1.0
        val inv = 1.0 / det
        val sx = ox - v[a]; val sy = oy - v[a + 1]; val sz = oz - v[a + 2]
        val u = (sx * px + sy * py + sz * pz) * inv
        if (u < -EDGE_EPS || u > 1 + EDGE_EPS) return -1.0
        val qx = sy * e1z - sz * e1y; val qy = sz * e1x - sx * e1z; val qz = sx * e1y - sy * e1x
        val w = (dx * qx + dy * qy + dz * qz) * inv
        if (w < -EDGE_EPS || u + w > 1 + EDGE_EPS) return -1.0
        return (e2x * qx + e2y * qy + e2z * qz) * inv
    }

    private inline fun inv(d: Double) = if (d == 0.0) Double.MAX_VALUE else 1.0 / d

    /** True if the ray from [o] along [d] hits any triangle at a parameter greater than [tMin]. */
    fun intersectsAny(o: Vec3, d: Vec3, tMin: Double = 0.0): Boolean {
        if (nodeCount == 0) return false
        val stack = STACK.get()
        val ix = inv(d.x); val iy = inv(d.y); val iz = inv(d.z)
        var sp = 0; stack[sp++] = 0
        while (sp > 0) {
            val n = stack[--sp]
            if (!boxHit(n, o.x, o.y, o.z, ix, iy, iz, Double.MAX_VALUE)) continue
            if (left[n] < 0) {
                for (i in start[n] until start[n] + count[n]) if (triHit(order[i], o.x, o.y, o.z, d.x, d.y, d.z) > tMin) return true
            } else { stack[sp++] = left[n]; stack[sp++] = right[n] }
        }
        return false
    }

    /** Nearest hit along the ray, or null. */
    fun firstHit(o: Vec3, d: Vec3, tMin: Double = 0.0): Hit? {
        if (nodeCount == 0) return null
        val stack = STACK.get()
        val ix = inv(d.x); val iy = inv(d.y); val iz = inv(d.z)
        var best = Double.MAX_VALUE; var bestT = -1
        var sp = 0; stack[sp++] = 0
        while (sp > 0) {
            val n = stack[--sp]
            if (!boxHit(n, o.x, o.y, o.z, ix, iy, iz, best)) continue
            if (left[n] < 0) {
                for (i in start[n] until start[n] + count[n]) {
                    val t = triHit(order[i], o.x, o.y, o.z, d.x, d.y, d.z)
                    if (t > tMin && t < best) { best = t; bestT = order[i] }
                }
            } else { stack[sp++] = left[n]; stack[sp++] = right[n] }
        }
        return if (bestT >= 0) Hit(bestT, best) else null
    }

    /** Counts crossings along a ray; an odd count means the point is inside. Valid for watertight meshes. */
    fun contains(p: Vec3): Boolean {
        if (nodeCount == 0) return false
        val d = PARITY_DIR
        val stack = STACK.get()
        val ix = inv(d.x); val iy = inv(d.y); val iz = inv(d.z)
        var hits = 0
        var sp = 0; stack[sp++] = 0
        while (sp > 0) {
            val n = stack[--sp]
            if (!boxHit(n, p.x, p.y, p.z, ix, iy, iz, Double.MAX_VALUE)) continue
            if (left[n] < 0) {
                for (i in start[n] until start[n] + count[n]) if (triHitStrict(order[i], p, d) > 0) hits++
            } else { stack[sp++] = left[n]; stack[sp++] = right[n] }
        }
        return hits % 2 == 1
    }

    /** Like [triHit] but with exact edge rules, so a ray through a shared edge is not counted twice. */
    private fun triHitStrict(t: Int, o: Vec3, d: Vec3): Double {
        val a = mesh.vertex(mesh.triangles[3 * t]); val b = mesh.vertex(mesh.triangles[3 * t + 1]); val c = mesh.vertex(mesh.triangles[3 * t + 2])
        val e1 = b - a; val e2 = c - a
        val p = d cross e2
        val det = e1 dot p
        if (det == 0.0) return -1.0
        val inv = 1.0 / det
        val s = o - a
        val u = (s dot p) * inv
        if (u < 0 || u >= 1) return -1.0
        val q = s cross e1
        val w = (d dot q) * inv
        if (w < 0 || u + w >= 1) return -1.0
        return (e2 dot q) * inv
    }

    companion object {
        const val LEAF = 8

        /** Slack on barycentric tests so rays grazing a shared edge still count as blocked. */
        private const val EDGE_EPS = 1e-9

        /** A skewed direction, so parity rays rarely run along an edge or through a vertex. */
        private val PARITY_DIR = Vec3(0.577 + 0.0131, 0.577 - 0.0073, 0.577 + 0.0029).normalized()

        private val STACK = ThreadLocal.withInitial { IntArray(256) }
    }
}
