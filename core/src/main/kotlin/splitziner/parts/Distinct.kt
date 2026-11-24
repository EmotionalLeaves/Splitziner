package splitziner.parts

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Names a maker can tell apart. Two different parts that both came out as "Side" (a desk's side and its hutch's side)
 * or "Apron" (the long and the short aprons of a table) leave every instruction that names one ambiguous. Each gets a
 * word in front that a maker would use: "Hutch side", "Front leg" and "Back leg", "Upper rail" and "Lower rail",
 * "Long apron" and "Short apron".
 *
 * Display only: the parts keep their role names, which the rest of the app reasons with. Parts of separate pieces
 * are left alone, as each piece is built on its own and its guide names only its own parts.
 */
object Distinct {
    /** Sizes this close (mm) are the same size. */
    private const val SAME = 1.0

    /** Part id to the name to show, for the parts whose name needs a word to tell it apart. */
    fun names(parts: List<Part>, userNamed: Set<String> = emptySet()): Map<String, String> {
        val live = parts.filter { !it.excluded && it.name != it.id && it.name.isNotBlank() }
        if (live.size < 2) return emptyMap()
        val out = HashMap<String, String>()
        val upper = runCatching { splitziner.explode.AssemblyPlan.upperUnit(live)?.toSet() }.getOrNull().orEmpty()
        val pieceOf = HashMap<String, Int>()
        runCatching { Pieces.find(live) }.getOrNull()?.forEachIndexed { i, ps -> ps.forEach { pieceOf[it.id] = i } }
        fun size(p: Part) = p.mesh.bounds.extents.let { e -> listOf(e.x, e.y, e.z).sorted() }
        fun sig(p: Part) = p.item?.toString() ?: size(p).joinToString(",") { (it / SAME).roundToLong().toString() }
        for ((name, ps) in live.groupBy { it.name }) {
            val kinds = ps.groupBy { sig(it) }.values.toList()
            if (kinds.size < 2) continue
            if (ps.map { pieceOf[it.id] }.distinct().size > 1) continue
            val word = name.lowercase().replace("french", "French")
            fun put(g: List<Part>, q: String) { for (p in g) if (p.id !in userNamed && p.id.substringBefore('.') !in userNamed) out[p.id] = "$q $word" }
            // In a hutch on a desk: the hutch's ones.
            val up = kinds.filter { g -> g.all { it.id in upper } }
            if (up.isNotEmpty() && up.size < kinds.size && kinds.filter { it !in up }.none { g -> g.any { it.id in upper } }) {
                if (up.size == 1) { put(up[0], "Hutch"); continue }
            }
            if (kinds.size != 2) continue
            val (a, b) = kinds
            fun lo(g: List<Part>, k: Int) = g.minOf { it.mesh.bounds.center[k] }
            fun hi(g: List<Part>, k: Int) = g.maxOf { it.mesh.bounds.center[k] }
            // Wholly in front of the other (y grows to the back), or wholly above it.
            val firstWord = name.substringBefore(' ').lowercase()
            if (firstWord !in setOf("front", "back") && (hi(a, 1) < lo(b, 1) - SAME || hi(b, 1) < lo(a, 1) - SAME)) {
                val aFront = hi(a, 1) < lo(b, 1); put(if (aFront) a else b, "Front"); put(if (aFront) b else a, "Back"); continue
            }
            if (firstWord !in setOf("upper", "lower", "top", "bottom") && (hi(a, 2) < lo(b, 2) - SAME || hi(b, 2) < lo(a, 2) - SAME)) {
                val aLow = hi(a, 2) < lo(b, 2); put(if (aLow) a else b, "Lower"); put(if (aLow) b else a, "Upper"); continue
            }
            // Otherwise by size: whichever of length, width or thickness differs most.
            val sa = size(a[0]); val sb = size(b[0])
            val k = (0..2).maxByOrNull { abs(sa[it] - sb[it]) / maxOf(sa[it], sb[it], 1.0) }!!
            if (abs(sa[k] - sb[k]) < SAME) continue
            val (big, small) = when (k) { 2 -> "Long" to "Short"; 1 -> "Wide" to "Narrow"; else -> "Thick" to "Thin" }
            val aBig = sa[k] > sb[k]; put(if (aBig) a else b, big); put(if (aBig) b else a, small)
        }
        return out
    }

    /** The name to show for [p]: its own, or the one [names] gave it. */
    fun shown(p: Part, names: Map<String, String>): String = names[p.id] ?: p.name
}
