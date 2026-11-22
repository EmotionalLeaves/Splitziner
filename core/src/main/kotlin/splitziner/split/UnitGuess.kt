package splitziner.split

import splitziner.mesh.Mesh
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/** The app's best guess at what units a unitless STL was saved in and designed in. */
data class UnitGuess(
    /** Multiply file coordinates by this to get millimetres. */
    val scale: Double,
    val fileUnits: String,
    val designUnits: String,
    /** 0..1: share of part sizes that land on a round grid in the design units. */
    val roundness: Double,
    /** Score gap to the best alternative; null when the user chose the units. */
    val margin: Double?,
    /** Largest extent in millimetres under this guess. */
    val sizeMm: Double,
    val alternative: String?,
) {
    /** A small gap between the best and second-best reading means the user should confirm. */
    val lowConfidence: Boolean get() = margin != null && margin < LOW_CONFIDENCE_MARGIN

    companion object {
        /** Below this score gap the units screen opens on its own. */
        const val LOW_CONFIDENCE_MARGIN = 0.15

        fun scaleOf(units: String): Double = when (units) { "mm" -> 1.0; "cm" -> 10.0; "in" -> 25.4; "m" -> 1000.0; else -> 1.0 }

        fun fixed(fileUnits: String, sizeFileUnits: Double, designUnits: String? = null): UnitGuess {
            val s = scaleOf(fileUnits)
            return UnitGuess(s, fileUnits, designUnits ?: if (fileUnits == "in") "in" else "mm", 0.0, null, sizeFileUnits * s, null)
        }
    }
}

object UnitGuesser {
    private class Grid(val step: Double, val weight: Double)

    // Coarser grids are stronger evidence that a designer picked the number on purpose.
    private val MM = listOf(Grid(1.0, 1.0), Grid(0.5, 0.8), Grid(0.1, 0.25))
    private val IN = listOf(Grid(1.0, 1.0), Grid(0.5, 0.95), Grid(0.25, 0.9), Grid(0.125, 0.85), Grid(0.0625, 0.75), Grid(0.1, 0.7), Grid(1.0 / 32, 0.4))
    private const val MM_TOL = 0.01      // mm
    private const val IN_TOL = 0.0045    // in, about 0.1 mm of slop

    private class Candidate(val total: Double, val scale: Double, val fileUnits: String, val designUnits: String, val roundness: Double)

    /** Lengths a designer would have typed in: each body's extents, or one body's extents and its step heights. */
    fun lengthsOf(bodies: List<Mesh>, sc0: Double): DoubleArray {
        val out = ArrayList<Double>()
        if (bodies.size > 1) {
            for (b in bodies) { val e = b.extents; out += e.x; out += e.y; out += e.z }
        } else if (bodies.size == 1) {
            val m = bodies[0]
            val e = m.extents; out += e.x; out += e.y; out += e.z
            val n = m.faceNormals; val c = m.faceCenters; val a = m.faceAreas
            for (k in 0..2) {
                val levels = HashMap<Long, Double>()
                for (t in 0 until m.triangleCount) {
                    if (abs(n[3 * t + k]) > 0.9999 && a[t] > 0) {
                        val key = (c[3 * t + k] / sc0 * 1e6).roundToLong()
                        levels[key] = (levels[key] ?: 0.0) + a[t]
                    }
                }
                val z = levels.filter { it.value > 1e-3 * m.area }.keys.sorted().map { it / 1e6 * sc0 }
                if (z.size > 1) {
                    for (v in z) out += v - z[0]
                    for (i in 1 until z.size) out += z[i] - z[i - 1]
                }
            }
        }
        return out.toDoubleArray()
    }

    private fun score(values: DoubleArray, factor: Double, grids: List<Grid>, tol: Double): Double {
        if (values.isEmpty()) return 0.0
        var s = 0.0
        for (raw in values) {
            val v = raw * factor
            for (g in grids) {
                if (abs(v / g.step - Math.rint(v / g.step)) * g.step < tol) { s += g.weight; break }
            }
        }
        return s / values.size
    }

    /** [sc0] is the largest extent of everything, in file units. */
    fun guess(lengths: DoubleArray, sc0: Double): UnitGuess {
        var lens = lengths.map { (it / sc0 * 1e7).roundToLong() }.distinct().map { it / 1e7 * sc0 }.filter { it > 1e-6 * sc0 }
        if (lens.size > 300) lens = lens.shuffled(Random(0)).take(300)
        val arr = lens.toDoubleArray()
        val cands = ArrayList<Candidate>()
        // (scale to mm, file unit, prior): millimetre files are the norm, then inches, metres, centimetres.
        for ((scale, unit, prior) in listOf(Triple(1.0, "mm", 0.3), Triple(25.4, "in", 0.1), Triple(1000.0, "m", 0.0), Triple(10.0, "cm", -0.1))) {
            val size = sc0 * scale
            // Anything from 5 mm to 5 m is a plausible object; metre files may describe rooms and buildings.
            if (size < 5 || size > (if (unit == "m") 50000.0 else 5000.0)) continue
            val mm = score(arr, scale, MM, MM_TOL)
            val inch = score(arr, scale / 25.4, IN, IN_TOL)
            cands += Candidate(mm + prior, scale, unit, "mm", mm)
            cands += Candidate(inch + prior, scale, unit, "in", inch)
        }
        if (cands.isEmpty()) return UnitGuess(1.0, "mm", "mm", 0.0, 0.0, sc0, null)
        val sorted = cands.sortedByDescending { it.total }
        val best = sorted[0]
        val other = sorted.firstOrNull { it.scale != best.scale }
        return UnitGuess(
            scale = best.scale, fileUnits = best.fileUnits, designUnits = best.designUnits,
            roundness = Math.round(best.roundness * 100) / 100.0,
            margin = Math.round((best.total - (other?.total ?: 0.0)) * 100) / 100.0,
            sizeMm = sc0 * best.scale,
            alternative = other?.let { "${it.fileUnits} file (${Math.round(sc0 * it.scale)} mm)" },
        )
    }
}
