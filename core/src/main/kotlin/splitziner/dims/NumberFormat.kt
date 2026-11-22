package splitziner.dims

import java.util.Locale
import kotlin.math.abs

/** How lengths are written on drawings and lists. */
data class LengthFormat(
    /** "mm" or "in". */
    val units: String = "mm",
    /** For inches: "decimal" or "fraction". */
    val inchStyle: String = "decimal",
    /** Null means 2 places for inches and 1 for millimetres. */
    val decimals: Int? = null,
    /** Fractions are rounded to the nearest 1/denominator. */
    val fractionDenominator: Int = 16,
) {
    val isInch: Boolean get() = units == "in"

    /** Formats a length given in millimetres. */
    fun format(valueMm: Double): String {
        if (!isInch) {
            // Millimetres: no trailing zeros, leading zero kept (0.5).
            val s = String.format(Locale.ROOT, "%.${decimals ?: 1}f", valueMm)
            val trimmed = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
            return if (trimmed == "-0") "0" else trimmed
        }
        val v = valueMm / 25.4
        if (inchStyle == "fraction") return fraction(v, fractionDenominator)
        // Decimal inches: fixed places, no leading zero below one (.75).
        val s = String.format(Locale.ROOT, "%.${decimals ?: 2}f", v)
        return if (abs(v) < 1 && s.removePrefix("-").startsWith("0.")) s.replaceFirst("0.", ".") else s
    }

    val unitLabel: String get() = if (isInch) "in" else "mm"

    companion object {
        fun fraction(v: Double, denominator: Int): String {
            val sign = if (v < 0) "-" else ""
            val total = Math.round(abs(v) * denominator)
            val whole = total / denominator
            var num = total % denominator
            var den = denominator.toLong()
            while (num != 0L && num % 2 == 0L && den % 2 == 0L) { num /= 2; den /= 2 }
            return when {
                num == 0L -> "$sign$whole"
                whole == 0L -> "$sign$num/$den"
                else -> "$sign$whole-$num/$den"
            }
        }
    }
}
