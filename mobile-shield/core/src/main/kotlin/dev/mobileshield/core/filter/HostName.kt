package dev.mobileshield.core.filter

import java.net.IDN

/**
 * A normalized DNS host name used for blocklist matching.
 *
 * Normalization makes lookups robust against the trivial evasions and encoding
 * differences that show up in real DNS queries:
 *  - case is folded to lower case (DNS is case-insensitive),
 *  - a single trailing dot (the fully-qualified root label) is removed,
 *  - an optional `:port` suffix is stripped,
 *  - Unicode/IDN labels are converted to their ASCII (punycode) form so that
 *    `bücher.example` and `xn--bcher-kva.example` compare equal.
 *
 * The result is the list of DNS labels, most-specific last, e.g.
 * `www.ads.example.com` -> ["www", "ads", "example", "com"].
 */
class HostName private constructor(
    /** The normalized, ASCII, dot-joined host, e.g. "ads.example.com". */
    val ascii: String,
    /** DNS labels in order, e.g. ["ads", "example", "com"]. */
    val labels: List<String>,
) {
    /**
     * True when this host is equal to [suffix] or is a sub-domain of it on a
     * label boundary. `a.example.com` is under `example.com`, but
     * `notexample.com` is not under `example.com`.
     */
    fun isUnder(suffix: HostName): Boolean {
        if (suffix.labels.isEmpty() || labels.size < suffix.labels.size) return false
        val offset = labels.size - suffix.labels.size
        for (i in suffix.labels.indices) {
            if (labels[offset + i] != suffix.labels[i]) return false
        }
        return true
    }

    override fun equals(other: Any?): Boolean = other is HostName && other.ascii == ascii
    override fun hashCode(): Int = ascii.hashCode()
    override fun toString(): String = ascii

    companion object {
        /**
         * Parses and normalizes [raw]. Returns null when the input is not a
         * usable host name (empty, or with empty labels such as "a..b").
         */
        fun parseOrNull(raw: String): HostName? {
            var s = raw.trim().lowercase()
            if (s.isEmpty()) return null

            // Strip a scheme-less leading "//" if present, then any path.
            s = s.removePrefix("//")
            s = s.substringBefore('/')

            // Strip an optional :port suffix (but leave IPv6 bracket forms alone;
            // we only handle textual host names here, not raw IP literals).
            if (!s.contains('[') && s.count { it == ':' } == 1) {
                s = s.substringBefore(':')
            }

            // Remove a single trailing root dot.
            if (s.endsWith('.')) s = s.dropLast(1)
            if (s.isEmpty()) return null

            val ascii = try {
                IDN.toASCII(s, IDN.ALLOW_UNASSIGNED)
            } catch (_: IllegalArgumentException) {
                return null
            }

            val labels = ascii.split('.')
            if (labels.any { it.isEmpty() }) return null

            return HostName(ascii, labels)
        }
    }
}
