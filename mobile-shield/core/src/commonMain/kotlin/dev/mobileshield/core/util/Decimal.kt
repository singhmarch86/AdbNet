package dev.mobileshield.core.util

/**
 * A small, exact, multiplatform decimal for comparing UPI amounts without the
 * rounding error of binary floating point. `BigDecimal` is JVM-only, so this
 * stands in for the single thing the analyzer needs: parse a money string and
 * compare it. It stores a canonical form (no leading/trailing zeros) so equal
 * values compare and hash equal regardless of input formatting
 * (`"100.50" == "100.5"`).
 *
 * It intentionally does no arithmetic — comparison and display only.
 */
class Decimal private constructor(
    private val negative: Boolean,
    private val intDigits: String,  // canonical: no leading zeros, "0" when zero
    private val fracDigits: String, // canonical: no trailing zeros, "" when none
) : Comparable<Decimal> {

    override fun compareTo(other: Decimal): Int {
        if (negative != other.negative) return if (negative) -1 else 1
        val magnitude = compareMagnitude(other)
        return if (negative) -magnitude else magnitude
    }

    private fun compareMagnitude(o: Decimal): Int {
        if (intDigits.length != o.intDigits.length) {
            return intDigits.length.compareTo(o.intDigits.length)
        }
        val intCmp = intDigits.compareTo(o.intDigits)
        if (intCmp != 0) return intCmp
        val width = maxOf(fracDigits.length, o.fracDigits.length)
        return fracDigits.padEnd(width, '0').compareTo(o.fracDigits.padEnd(width, '0'))
    }

    override fun equals(other: Any?): Boolean = other is Decimal && compareTo(other) == 0
    override fun hashCode(): Int = toString().hashCode()

    override fun toString(): String {
        val sign = if (negative) "-" else ""
        return if (fracDigits.isEmpty()) "$sign$intDigits" else "$sign$intDigits.$fracDigits"
    }

    companion object {
        private val PATTERN = Regex("^([+-]?)(\\d*)(?:\\.(\\d*))?$")

        /**
         * Parses a plain decimal string such as "100", "100.50", "-0.5".
         * Returns null for anything that is not a plain decimal (empty, ".",
         * exponent notation, stray characters).
         */
        fun parseOrNull(raw: String): Decimal? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val m = PATTERN.matchEntire(s) ?: return null
            val rawInt = m.groupValues[2]
            val rawFrac = m.groupValues[3]
            if (rawInt.isEmpty() && rawFrac.isEmpty()) return null // "", ".", "+", "-"

            val intDigits = rawInt.trimStart('0').ifEmpty { "0" }
            val fracDigits = rawFrac.trimEnd('0')
            val isZero = intDigits == "0" && fracDigits.isEmpty()
            val negative = m.groupValues[1] == "-" && !isZero
            return Decimal(negative, intDigits, fracDigits)
        }
    }
}
