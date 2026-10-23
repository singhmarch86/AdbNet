package dev.mobileshield.core.util

/**
 * Minimal, multiplatform IDNA `toASCII` for host names: each label containing
 * non-ASCII characters is Punycode-encoded and prefixed with `xn--`, matching
 * what `java.net.IDN.toASCII` produces for the normalized (already lower-cased)
 * host names this project feeds it.
 *
 * This is deliberately a small, self-contained implementation so the shared
 * `core` module depends only on the Kotlin standard library and can compile for
 * both the JVM (Android) and Kotlin/Native (iOS). It is not a full IDNA2008
 * conformance implementation; it handles the encoding step, and callers remain
 * responsible for validating label structure (e.g. rejecting empty labels).
 */
object Idna {
    /** Returns the ASCII form of [domain], Punycode-encoding non-ASCII labels. */
    fun toAscii(domain: String): String =
        domain.split('.').joinToString(".") { label ->
            if (label.isEmpty() || label.all { it.code < 0x80 }) label
            else "xn--" + Punycode.encode(label)
        }
}

/** RFC 3492 Punycode encoder, operating on Unicode code points. */
internal object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128
    private const val DELIMITER = '-'

    fun encode(input: String): String {
        val codePoints = input.toCodePoints()
        val output = StringBuilder()

        var basicCount = 0
        for (cp in codePoints) {
            if (cp < INITIAL_N) {
                output.append(cp.toChar())
                basicCount++
            }
        }

        var handled = basicCount
        if (basicCount > 0) output.append(DELIMITER)

        var n = INITIAL_N
        var delta = 0
        var bias = INITIAL_BIAS
        val total = codePoints.size

        while (handled < total) {
            var m = Int.MAX_VALUE
            for (cp in codePoints) if (cp in n until m) m = cp

            delta += (m - n) * (handled + 1)
            n = m

            for (cp in codePoints) {
                if (cp < n) delta++
                if (cp == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = when {
                            k <= bias -> TMIN
                            k >= bias + TMAX -> TMAX
                            else -> k - bias
                        }
                        if (q < t) break
                        output.append(digitToBasic(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    output.append(digitToBasic(q))
                    bias = adapt(delta, handled + 1, handled == basicCount)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return output.toString()
    }

    private fun adapt(delta: Int, numPoints: Int, firstTime: Boolean): Int {
        var d = if (firstTime) delta / DAMP else delta / 2
        d += d / numPoints
        var k = 0
        while (d > ((BASE - TMIN) * TMAX) / 2) {
            d /= (BASE - TMIN)
            k += BASE
        }
        return k + ((BASE - TMIN + 1) * d) / (d + SKEW)
    }

    /** 0..25 -> 'a'..'z', 26..35 -> '0'..'9'. */
    private fun digitToBasic(d: Int): Char =
        if (d < 26) ('a' + d) else ('0' + (d - 26))

    /** Decodes a UTF-16 string into Unicode code points, combining surrogates. */
    private fun String.toCodePoints(): List<Int> {
        val result = ArrayList<Int>(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
                val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
                result.add(cp)
                i += 2
            } else {
                result.add(c.code)
                i++
            }
        }
        return result
    }
}
