package splitziner.math

import kotlin.math.sqrt

/** 3D vector or point. Model lengths are millimetres once a file's unit scale is applied. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun get(i: Int): Double = when (i) { 0 -> x; 1 -> y; else -> z }
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Double get() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 { val l = length; return if (l > 0) this / l else this }
    fun with(i: Int, v: Double) = when (i) { 0 -> Vec3(v, y, z); 1 -> Vec3(x, v, z); else -> Vec3(x, y, v) }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun axis(k: Int, sign: Double = 1.0) = when (k) {
            0 -> Vec3(sign, 0.0, 0.0); 1 -> Vec3(0.0, sign, 0.0); else -> Vec3(0.0, 0.0, sign)
        }
    }
}

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    val length: Double get() = sqrt(x * x + y * y)
    fun normalized(): Vec2 { val l = length; return if (l > 0) Vec2(x / l, y / l) else this }
    fun perp() = Vec2(-y, x)
}

/** Axis-aligned bounding box. */
data class Aabb(val min: Vec3, val max: Vec3) {
    val extents: Vec3 get() = max - min
    val center: Vec3 get() = (min + max) * 0.5
    val diagonal: Double get() = extents.length

    fun union(o: Aabb) = Aabb(
        Vec3(minOf(min.x, o.min.x), minOf(min.y, o.min.y), minOf(min.z, o.min.z)),
        Vec3(maxOf(max.x, o.max.x), maxOf(max.y, o.max.y), maxOf(max.z, o.max.z)),
    )

    companion object {
        fun of(coords: DoubleArray): Aabb {
            if (coords.isEmpty()) return Aabb(Vec3.ZERO, Vec3.ZERO)
            var x0 = Double.POSITIVE_INFINITY; var y0 = x0; var z0 = x0
            var x1 = Double.NEGATIVE_INFINITY; var y1 = x1; var z1 = x1
            var i = 0
            while (i < coords.size) {
                val x = coords[i]; val y = coords[i + 1]; val z = coords[i + 2]
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
                if (z < z0) z0 = z; if (z > z1) z1 = z
                i += 3
            }
            return Aabb(Vec3(x0, y0, z0), Vec3(x1, y1, z1))
        }
    }
}
