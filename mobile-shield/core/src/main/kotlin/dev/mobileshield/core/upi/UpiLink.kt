package dev.mobileshield.core.upi

import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A parsed UPI deep link, e.g.
 * `upi://pay?pa=alice@okbank&pn=Alice&am=100.00&cu=INR&tn=note`.
 *
 * Only parsing lives here — no judgement. [UpiLinkAnalyzer] interprets the
 * fields and assigns risk. Field names follow the UPI deep-linking spec:
 *  - pa  payee address (the VPA, e.g. `name@bank`)
 *  - pn  payee name
 *  - am  amount
 *  - mam minimum amount
 *  - cu  currency (should be INR)
 *  - tn  transaction note
 *  - tr  transaction reference id
 *  - tid transaction id
 *  - mc  merchant category code
 *  - mode / purpose / orgid
 *  - sign signed, verified-merchant intent
 */
data class UpiLink(
    val action: String,
    val params: Map<String, String>,
) {
    val payeeAddress: String? get() = params["pa"]
    val payeeName: String? get() = params["pn"]
    val currency: String? get() = params["cu"]
    val note: String? get() = params["tn"]
    val merchantCode: String? get() = params["mc"]
    val mode: String? get() = params["mode"]
    val signature: String? get() = params["sign"]

    /** The raw amount string as it appeared, or null if absent. */
    val amountRaw: String? get() = params["am"]

    /** Parsed amount, or null if absent or not a valid number. */
    val amount: BigDecimal?
        get() = amountRaw?.let { runCatching { BigDecimal(it.trim()) }.getOrNull() }

    /** The handle part of the VPA (`okbank` in `alice@okbank`), lower-cased. */
    val payeeHandle: String?
        get() = payeeAddress?.substringAfter('@', "")?.lowercase()?.takeIf { it.isNotEmpty() }

    companion object {
        /**
         * Parses a `upi://` link. Returns null when [raw] is not a UPI URI.
         * Lenient about the exact action (`pay`, `collect`, ...), which the
         * analyzer inspects separately.
         */
        fun parseOrNull(raw: String): UpiLink? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("upi://", ignoreCase = true)) return null

            val rest = trimmed.substring("upi://".length)
            val action = rest.substringBefore('?', rest).substringBefore('/').lowercase()
            val query = rest.substringAfter('?', "")

            val params = LinkedHashMap<String, String>()
            if (query.isNotEmpty()) {
                for (pair in query.split('&')) {
                    if (pair.isEmpty()) continue
                    val key = pair.substringBefore('=')
                    val value = if (pair.contains('=')) pair.substringAfter('=') else ""
                    if (key.isEmpty()) continue
                    // First occurrence wins; duplicate keys are a known
                    // tampering trick to smuggle a second payee/amount.
                    params.putIfAbsent(decode(key), decode(value))
                }
            }
            return UpiLink(action, params)
        }

        private fun decode(s: String): String =
            runCatching { URLDecoder.decode(s, StandardCharsets.UTF_8.name()) }.getOrDefault(s)
    }
}
