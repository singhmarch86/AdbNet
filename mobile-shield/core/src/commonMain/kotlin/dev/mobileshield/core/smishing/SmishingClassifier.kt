package dev.mobileshield.core.smishing

enum class SmishingLevel { LOW, MEDIUM, HIGH, CRITICAL }

enum class SmishingCode {
    URL_SHORTENER,
    RAW_IP_URL,
    APK_LINK,
    GENERIC_LINK,
    URGENCY_THREAT,
    REWARD_LURE,
    CREDENTIAL_REQUEST,
    KYC_SCAM,
    CALLBACK_NUMBER,
    SENDER_LOOKS_PERSONAL,
}

data class SmishingSignal(
    val code: SmishingCode,
    val weight: Int,
    val message: String,
)

data class SmishingReport(
    val level: SmishingLevel,
    val score: Int,
    val signals: List<SmishingSignal>,
) {
    val headline: String
        get() = signals.firstOrNull()?.message
            ?: "No scam signals found in this message."
}

/**
 * Scores an SMS / notification message for the signs of smishing (SMS phishing),
 * entirely on-device and offline, in English and Hindi/Hinglish (both romanized
 * and Devanagari). This is a transparent heuristic over extractable features —
 * links, urgency/reward language, requests for OTP/PIN/card details, KYC bait —
 * not a trained model, so it is explainable and runs on any phone. A platform
 * may later combine these features with an on-device model.
 *
 * Deliberately conservative about the OTP false-positive trap: a legitimate
 * "Your OTP is 123456, do not share it" is NOT flagged, because only messages
 * that *ask you to share/enter* a secret trigger [SmishingCode.CREDENTIAL_REQUEST].
 */
class SmishingClassifier {

    fun classify(text: String, sender: String? = null): SmishingReport {
        val lower = text.lowercase()
        val signals = mutableListOf<SmishingSignal>()

        val urls = extractUrls(text)
        var linkSignalAdded = false
        for (url in urls) {
            val host = hostOf(url)
            when {
                url.contains(".apk", ignoreCase = true) -> {
                    signals += SmishingSignal(
                        SmishingCode.APK_LINK, 40,
                        "The message links to an app file (.apk). Installing apps from a text is a " +
                            "common way malware steals from your bank.",
                    )
                    linkSignalAdded = true
                }
                host != null && IP_HOST.matches(host) -> {
                    signals += SmishingSignal(
                        SmishingCode.RAW_IP_URL, 30,
                        "The link points to a raw IP address, not a real company website.",
                    )
                    linkSignalAdded = true
                }
                host != null && SHORTENERS.any { host == it || host.endsWith(".$it") } -> {
                    signals += SmishingSignal(
                        SmishingCode.URL_SHORTENER, 25,
                        "The message uses a shortened link that hides where it really goes.",
                    )
                    linkSignalAdded = true
                }
            }
        }
        if (urls.isNotEmpty() && !linkSignalAdded) {
            signals += SmishingSignal(
                SmishingCode.GENERIC_LINK, 10,
                "The message contains a link. Do not open links from unexpected messages.",
            )
        }

        if (containsAny(lower, URGENCY_THREAT)) {
            signals += SmishingSignal(
                SmishingCode.URGENCY_THREAT, 20,
                "It pressures you to act urgently or threatens to block/suspend something — a " +
                    "standard scam tactic.",
            )
        }
        if (containsAny(lower, REWARD_LURE)) {
            signals += SmishingSignal(
                SmishingCode.REWARD_LURE, 20,
                "It promises a prize, reward or refund to lure you in.",
            )
        }
        if (containsAny(lower, CREDENTIAL_REQUEST)) {
            signals += SmishingSignal(
                SmishingCode.CREDENTIAL_REQUEST, 35,
                "It asks for an OTP, PIN, CVV or password. No genuine bank ever asks you to share these.",
            )
        }
        if (containsAny(lower, KYC_BAIT)) {
            signals += SmishingSignal(
                SmishingCode.KYC_SCAM, 25,
                "It uses KYC / PAN / Aadhaar 'update' bait, a very common fraud hook in India.",
            )
        }
        if (CALLBACK.containsMatchIn(lower)) {
            signals += SmishingSignal(
                SmishingCode.CALLBACK_NUMBER, 10,
                "It tells you to call a number — scam 'helplines' harvest your details.",
            )
        }
        if (sender != null && looksLikePersonalNumber(sender) && (urls.isNotEmpty() || containsAny(lower, CREDENTIAL_REQUEST))) {
            signals += SmishingSignal(
                SmishingCode.SENDER_LOOKS_PERSONAL, 10,
                "It comes from a personal mobile number, not an official business sender ID.",
            )
        }

        signals.sortByDescending { it.weight }
        val score = signals.sumOf { it.weight }.coerceIn(0, 100)
        return SmishingReport(levelFor(score), score, signals.toList())
    }

    private fun levelFor(score: Int): SmishingLevel = when {
        score >= 60 -> SmishingLevel.CRITICAL
        score >= 35 -> SmishingLevel.HIGH
        score >= 15 -> SmishingLevel.MEDIUM
        else -> SmishingLevel.LOW
    }

    private fun containsAny(lowerText: String, needles: List<String>): Boolean =
        needles.any { lowerText.contains(it) }

    private fun extractUrls(text: String): List<String> {
        val out = linkedSetOf<String>()
        SCHEME_URL.findAll(text).forEach { out.add(it.value) }
        BARE_DOMAIN.findAll(text).forEach { out.add(it.value) }
        return out.toList()
    }

    private fun hostOf(url: String): String? {
        var s = url.lowercase()
        s = s.substringAfter("://", s)
        s = s.removePrefix("www.")
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        s = s.substringBefore(':')
        return s.ifEmpty { null }
    }

    private fun looksLikePersonalNumber(sender: String): Boolean {
        val digits = sender.filter { it.isDigit() }
        // Alphanumeric sender IDs (banks/brands) contain letters; personal
        // numbers are all digits, typically 10+.
        return sender.any { it.isLetter() }.not() && digits.length >= 10
    }

    companion object {
        private val SCHEME_URL = Regex("(https?://|www\\.)[^\\s]+", RegexOption.IGNORE_CASE)
        private val BARE_DOMAIN = Regex(
            "\\b[a-z0-9][a-z0-9.-]*\\.(com|net|in|org|co|xyz|top|info|link|click|ru|tk|apk)(/[^\\s]*)?",
            RegexOption.IGNORE_CASE,
        )
        private val IP_HOST = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
        private val CALLBACK = Regex(
            "(call|contact|helpline|customer care|toll free)\\D{0,15}\\+?\\d{6,}",
            RegexOption.IGNORE_CASE,
        )

        private val SHORTENERS = setOf(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "rebrand.ly", "cutt.ly",
            "is.gd", "ow.ly", "rb.gy", "shorturl.at", "bitly.com", "t.ly",
        )

        // Keyword groups include English, romanized Hindi/Hinglish, and Devanagari.
        private val URGENCY_THREAT = listOf(
            "blocked", "will be blocked", "suspend", "suspended", "expire", "expired",
            "deactivat", "urgent", "immediately", "within 24 hours", "account will",
            "reactivat", "penalty", "last warning", "frozen", "verify your account",
            // Hinglish
            "turant", "band ho", "block ho", "jaldi", "varna", "nahi to", "account band",
            // Devanagari
            "बंद", "तुरंत", "जल्दी", "खाता बंद", "सत्यापित",
        )
        private val REWARD_LURE = listOf(
            "you won", "you have won", "winner", "prize", "lottery", "lucky draw",
            "cashback", "gift", "reward", "claim your", "congratulations", "bonus",
            "free recharge", "refund",
            // Hinglish
            "jeeta", "jeet", "inaam", "muft", "tohfa", "badhai", "lucky",
            // Devanagari
            "जीता", "इनाम", "लॉटरी", "मुफ्त", "बधाई", "उपहार",
        )
        private val CREDENTIAL_REQUEST = listOf(
            "share otp", "send otp", "tell otp", "give otp", "enter otp",
            "share pin", "enter pin", "send pin", "upi pin", "mpin",
            "cvv", "card number", "share your password", "net banking password",
            // Hinglish
            "otp batao", "otp bhejo", "otp do", "pin batao", "otp share karo",
            // Devanagari
            "ओटीपी बताओ", "पिन बताओ",
        )
        private val KYC_BAIT = listOf(
            "kyc", "update kyc", "kyc update", "ekyc", "pan card update", "pan update",
            "aadhaar update", "aadhar update", "re-kyc",
            // Devanagari
            "केवाईसी",
        )
    }
}
