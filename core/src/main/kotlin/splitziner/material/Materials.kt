package splitziner.material

import splitziner.parts.CutListRow
import splitziner.parts.Part
import kotlin.math.abs

/**
 * Common shop materials with a typical density, for weight estimates only. Real boards vary by
 * species, moisture and grade by 10-20%; every figure shown from these says it is an estimate.
 */
object Materials {
    /** [kgM3] is a typical density; [words] are what a user might type for it. */
    class Material(val name: String, val kgM3: Double, val words: List<String>)

    val COMMON = listOf(
        Material("Birch plywood", 680.0, listOf("birch ply", "baltic")),
        Material("Plywood", 560.0, listOf("plywood", "ply")),
        Material("MDF", 750.0, listOf("mdf", "medium density")),
        Material("Particleboard", 650.0, listOf("particle", "chipboard", "melamine")),
        Material("OSB", 620.0, listOf("osb", "oriented strand")),
        Material("Pine", 500.0, listOf("pine", "spruce", "fir", "whitewood", "spf")),
        Material("Poplar", 450.0, listOf("poplar")),
        Material("Oak", 750.0, listOf("oak")),
        Material("Maple", 700.0, listOf("maple")),
        Material("Walnut", 640.0, listOf("walnut")),
        Material("Cherry", 580.0, listOf("cherry")),
        Material("Beech", 720.0, listOf("beech")),
        Material("Steel", 7850.0, listOf("steel")),
        Material("Aluminium", 2700.0, listOf("aluminium", "aluminum")),
        Material("PLA (3D print)", 1240.0, listOf("pla")),
        Material("PETG (3D print)", 1270.0, listOf("petg")),
        Material("Acrylic", 1180.0, listOf("acrylic", "perspex", "plexi")),
    )

    /** The typical density of what the user typed, or null if it is not one of the common materials. */
    fun density(text: String): Double? {
        val t = text.lowercase()
        if (t.isBlank()) return null
        // Longest matching word wins, so "birch plywood" is birch plywood rather than plywood.
        return COMMON.flatMap { m -> m.words.map { w -> w to m } }.filter { (w, _) -> Regex("""\b${Regex.escape(w)}""").containsMatchIn(t) }
            .maxByOrNull { it.first.length }?.second?.kgM3
    }

    /** Estimated weight in kg of the parts whose item has a known material, and the items left out for want of one. */
    class Weight(val kg: Double, val unknownItems: List<Int>)

    fun weight(parts: List<Part>, rows: List<CutListRow>): Weight {
        var kg = 0.0
        val unknown = ArrayList<Int>()
        val byItem = parts.filter { !it.excluded && it.item != null }.groupBy { it.item!! }
        for (row in rows) {
            val rho = density(row.material)
            if (rho == null) { unknown += row.item; continue }
            for (p in byItem[row.item].orEmpty()) kg += abs(p.mesh.volume) * 1e-9 * rho
        }
        return Weight(kg, unknown)
    }

    /** "38 kg" or "84 lb", to two significant figures: the estimate is not better than that. */
    fun format(kg: Double, inch: Boolean): String {
        val v = if (inch) kg * 2.20462 else kg
        val r = if (v >= 100) Math.round(v / 10.0) * 10.0 else if (v >= 10) Math.round(v).toDouble() else Math.round(v * 10) / 10.0
        val s = if (r == Math.rint(r)) "${r.toLong()}" else "$r"
        return "$s ${if (inch) "lb" else "kg"}"
    }
}
