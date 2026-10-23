package dev.mobileshield.core.util

/**
 * Multiplatform percent-decoding for URL query values, replacing the JVM-only
 * `java.net.URLDecoder`. Decodes `%XX` escapes as UTF-8 bytes and treats `+`
 * as a space (the `application/x-www-form-urlencoded` convention that
 * `URLDecoder` also applies). Invalid escapes are left verbatim rather than
 * throwing, so a malformed link still yields a readable string to warn about.
 */
internal fun percentDecode(s: String): String {
    if (!s.contains('%') && !s.contains('+')) return s

    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '%' && i + 2 < s.length -> {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes.add(v.toByte())
                    i += 3
                } else {
                    bytes.add(c.code.toByte())
                    i++
                }
            }
            c == '+' -> {
                bytes.add(' '.code.toByte())
                i++
            }
            else -> {
                for (b in c.toString().encodeToByteArray()) bytes.add(b)
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
