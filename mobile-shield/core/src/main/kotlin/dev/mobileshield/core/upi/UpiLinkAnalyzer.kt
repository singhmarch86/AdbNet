package dev.mobileshield.core.upi

import java.math.BigDecimal

enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

enum class RiskCode {
    NOT_A_UPI_LINK,
    COLLECT_REQUEST,
    MALFORMED_VPA,
    UNKNOWN_PSP_HANDLE,
    HIGH_AMOUNT,
    SUSPICIOUS_NOTE,
    PAYEE_NAME_MISMATCH,
    NON_INR_CURRENCY,
    UNSIGNED_MERCHANT,
    DUPLICATE_PARAMETERS,
    MISSING_PAYEE,
}

data class RiskFlag(
    val code: RiskCode,
    /** Points this flag contributes toward the 0..100 score. */
    val weight: Int,
    /** Plain-language explanation safe to show the user. */
    val message: String,
)

data class UpiRiskReport(
    val level: RiskLevel,
    val score: Int,
    val flags: List<RiskFlag>,
    val link: UpiLink?,
) {
    val isUpiLink: Boolean get() = link != null
}

/**
 * Scores a UPI link for the common pre-payment scam patterns a third-party app
 * *can* legitimately catch: collect (pull-money) requests, malformed or
 * unknown payee handles, suspiciously high amounts, scam-bait notes, unsigned
 * merchant intents, and tampered duplicate parameters.
 *
 * It cannot, and does not claim to, see inside a UPI app or stop a payment —
 * that is the bank's and NPCI's domain. It only inspects the link before the
 * user acts on it and explains the risk, so the decision stays with the user.
 *
 * Pure function of its input: no I/O, no device state. The thresholds and
 * known-handle set are constructor parameters so they can be tuned and tested.
 */
class UpiLinkAnalyzer(
    private val highAmountThreshold: BigDecimal = BigDecimal("5000"),
    private val knownPspHandles: Set<String> = DEFAULT_KNOWN_PSP_HANDLES,
    private val suspiciousNoteKeywords: Set<String> = DEFAULT_SUSPICIOUS_NOTE_KEYWORDS,
) {
    fun analyze(raw: String): UpiRiskReport {
        val link = UpiLink.parseOrNull(raw)
            ?: return UpiRiskReport(
                level = RiskLevel.LOW,
                score = 0,
                flags = listOf(
                    RiskFlag(RiskCode.NOT_A_UPI_LINK, 0, "This is not a UPI link.")
                ),
                link = null,
            )

        val flags = mutableListOf<RiskFlag>()

        // A collect request pulls money *from* you — the single biggest UPI
        // scam vector. Flagged as critical on its own.
        if (link.action == "collect" || link.mode == "02") {
            flags += RiskFlag(
                RiskCode.COLLECT_REQUEST,
                60,
                "This is a request to pull money FROM your account, not a payment to you. " +
                    "Approving it debits you. Never approve a collect request to receive money.",
            )
        }

        val vpa = link.payeeAddress
        if (vpa.isNullOrBlank()) {
            flags += RiskFlag(
                RiskCode.MISSING_PAYEE,
                40,
                "The link has no payee ID. A genuine payment link always names who is paid.",
            )
        } else {
            if (!VPA_PATTERN.matches(vpa)) {
                flags += RiskFlag(
                    RiskCode.MALFORMED_VPA,
                    35,
                    "The payee ID \"$vpa\" is not a well-formed UPI address.",
                )
            }
            val handle = link.payeeHandle
            if (handle != null && knownPspHandles.none { handle == it || handle.endsWith(".$it") }) {
                flags += RiskFlag(
                    RiskCode.UNKNOWN_PSP_HANDLE,
                    20,
                    "The bank handle \"@$handle\" is not a widely recognised UPI provider. " +
                        "Double-check it before paying.",
                )
            }
        }

        link.amount?.let { amount ->
            if (amount > highAmountThreshold) {
                flags += RiskFlag(
                    RiskCode.HIGH_AMOUNT,
                    25,
                    "The amount ₹$amount is high. Confirm you intend to send this much.",
                )
            }
        }

        link.note?.let { note ->
            val lower = note.lowercase()
            val hit = suspiciousNoteKeywords.firstOrNull { lower.contains(it) }
            if (hit != null) {
                flags += RiskFlag(
                    RiskCode.SUSPICIOUS_NOTE,
                    25,
                    "The note mentions \"$hit\". Scammers use words like this to create urgency. " +
                        "No bank or refund needs you to PAY to receive money.",
                )
            }
        }

        link.currency?.let { cu ->
            if (!cu.equals("INR", ignoreCase = true)) {
                flags += RiskFlag(
                    RiskCode.NON_INR_CURRENCY,
                    15,
                    "The currency is \"$cu\", not INR. This is unusual for a UPI payment.",
                )
            }
        }

        // A merchant category code with no signed intent cannot be verified as a
        // real merchant transaction.
        if (!link.merchantCode.isNullOrBlank() && link.signature.isNullOrBlank()) {
            flags += RiskFlag(
                RiskCode.UNSIGNED_MERCHANT,
                20,
                "This claims to be a merchant payment but carries no verified signature.",
            )
        }

        // Duplicate pa/am keys in the raw query are a tampering signal.
        if (hasDuplicateSensitiveParams(raw)) {
            flags += RiskFlag(
                RiskCode.DUPLICATE_PARAMETERS,
                30,
                "The link lists the payee or amount more than once, which can hide the real " +
                    "destination. Treat it as tampered.",
            )
        }

        val score = flags.sumOf { it.weight }.coerceIn(0, 100)
        return UpiRiskReport(levelFor(score), score, flags.toList(), link)
    }

    private fun levelFor(score: Int): RiskLevel = when {
        score >= 60 -> RiskLevel.CRITICAL
        score >= 35 -> RiskLevel.HIGH
        score >= 15 -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }

    private fun hasDuplicateSensitiveParams(raw: String): Boolean {
        val query = raw.substringAfter('?', "")
        if (query.isEmpty()) return false
        val counts = HashMap<String, Int>()
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=').lowercase()
            if (key == "pa" || key == "am") counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.values.any { it > 1 }
    }

    companion object {
        // A VPA is `identifier@handle`: the local part allows letters, digits,
        // and a few punctuation marks; the handle is alphanumeric with dots.
        private val VPA_PATTERN = Regex("^[a-zA-Z0-9._-]{2,256}@[a-zA-Z][a-zA-Z0-9.]{1,64}$")

        /** A non-exhaustive set of common Indian PSP/bank UPI handles. */
        val DEFAULT_KNOWN_PSP_HANDLES: Set<String> = setOf(
            "okaxis", "okhdfcbank", "okicici", "oksbi",
            "ybl", "ibl", "axl", // PhonePe
            "paytm", "ptyes", "ptaxis", "ptsbi", "pthdfc",
            "apl", "yapl", // Amazon Pay
            "upi", // generic BHIM
            "fbl", "sbi", "hdfcbank", "icici", "axisbank", "kotak", "barodampay",
        )

        val DEFAULT_SUSPICIOUS_NOTE_KEYWORDS: Set<String> = setOf(
            "kyc", "refund", "cashback", "lottery", "prize", "winner", "reward",
            "verify", "verification", "blocked", "suspend", "penalty", "fine",
            "electricity bill", "offer", "lucky", "claim", "otp",
        )
    }
}
