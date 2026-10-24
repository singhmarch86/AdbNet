package dev.mobileshield.core.qr

import dev.mobileshield.core.upi.RiskLevel
import dev.mobileshield.core.upi.UpiLinkAnalyzer
import dev.mobileshield.core.upi.UpiRiskReport

enum class QrContentType { UPI, URL, WIFI, PHONE, SMS, EMAIL, GEO, CONTACT, TEXT }

enum class QrRiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

enum class QrRiskCode {
    UPI_PAYMENT,
    INSECURE_HTTP,
    URL_SHORTENER,
    RAW_IP_URL,
    APK_LINK,
    PUNYCODE_HOST,
    EMBEDDED_CREDENTIALS,
    BRAND_IN_URL,
    SUSPICIOUS_TLD,
    OPEN_WIFI,
    PREMIUM_SHORTCODE,
    ACTION_NOTE,
}

data class QrSignal(
    val code: QrRiskCode,
    val weight: Int,
    val message: String,
)

data class QrRiskReport(
    val type: QrContentType,
    val level: QrRiskLevel,
    val score: Int,
    val signals: List<QrSignal>,
    /** Present when the QR is a UPI payment; the full UPI analysis. */
    val upi: UpiRiskReport? = null,
    /** The raw decoded payload, for display. */
    val value: String,
) {
    val headline: String
        get() = signals.firstOrNull()?.message ?: "No risk signals found in this QR code."
}

/**
 * Analyzes the text decoded from a QR code for scam risk. UPI payloads are
 * delegated to [UpiLinkAnalyzer] so QR and link analysis agree; URLs are scored
 * for the usual phishing signs (shorteners, raw-IP, .apk, punycode/homograph
 * hosts, embedded credentials, brand-in-a-bad-domain); Wi-Fi, tel and SMS
 * payloads get their own targeted checks.
 *
 * Pure/offline/testable; reused on Android and iOS. The camera decode is the
 * platform's job — this judges the decoded string.
 */
class QrPayloadAnalyzer(
    private val upiAnalyzer: UpiLinkAnalyzer = UpiLinkAnalyzer(),
) {
    fun analyze(payload: String): QrRiskReport {
        val raw = payload.trim()
        val lower = raw.lowercase()

        return when {
            lower.startsWith("upi://") -> analyzeUpi(raw)
            lower.startsWith("http://") || lower.startsWith("https://") -> analyzeUrl(raw, raw)
            lower.startsWith("wifi:") -> analyzeWifi(raw)
            lower.startsWith("tel:") -> analyzePhone(raw)
            lower.startsWith("smsto:") || lower.startsWith("sms:") -> analyzeSms(raw)
            lower.startsWith("mailto:") -> plain(QrContentType.EMAIL, raw, "This QR composes an email.")
            lower.startsWith("geo:") -> plain(QrContentType.GEO, raw, "This QR opens a map location.")
            lower.startsWith("begin:vcard") || lower.startsWith("mecard:") ->
                plain(QrContentType.CONTACT, raw, "This QR adds a contact. Verify the details before saving.")
            else -> {
                val url = extractUrl(raw)
                if (url != null) analyzeUrl(url, raw)
                else plain(QrContentType.TEXT, raw, "Plain text — no link or payment.")
            }
        }
    }

    private fun analyzeUpi(raw: String): QrRiskReport {
        val report = upiAnalyzer.analyze(raw)
        val signals = mutableListOf(
            QrSignal(
                QrRiskCode.UPI_PAYMENT, 0,
                "This QR starts a UPI payment. Scanning a payment QR sends money FROM you — it never " +
                    "receives money.",
            )
        )
        report.flags.forEach { flag ->
            signals += QrSignal(QrRiskCode.UPI_PAYMENT, flag.weight, flag.message)
        }
        signals.sortByDescending { it.weight }
        return QrRiskReport(
            type = QrContentType.UPI,
            level = mapUpiLevel(report.level),
            score = report.score,
            signals = signals,
            upi = report,
            value = raw,
        )
    }

    private fun analyzeUrl(url: String, raw: String): QrRiskReport {
        val lower = url.lowercase()
        val authority = authorityOf(url)
        val host = authority.substringAfterLast('@').substringBefore(':')
        val signals = mutableListOf<QrSignal>()

        val insecure = lower.startsWith("http://")
        if (insecure) {
            signals += QrSignal(QrRiskCode.INSECURE_HTTP, 10, "The link is not secure (http, not https).")
        }
        if (url.contains(".apk", ignoreCase = true)) {
            signals += QrSignal(QrRiskCode.APK_LINK, 40, "The link downloads an app file (.apk) — a common malware trick.")
        }
        if (IP_HOST.matches(host)) {
            signals += QrSignal(QrRiskCode.RAW_IP_URL, 30, "The link points to a raw IP address, not a real website.")
        }
        val shortener = SHORTENERS.any { host == it || host.endsWith(".$it") }
        if (shortener) {
            signals += QrSignal(QrRiskCode.URL_SHORTENER, 25, "The link is shortened and hides its real destination.")
        }
        if (host.contains("xn--")) {
            signals += QrSignal(QrRiskCode.PUNYCODE_HOST, 25, "The link uses a punycode host that can imitate a real brand's name.")
        }
        if (authority.contains('@')) {
            signals += QrSignal(
                QrRiskCode.EMBEDDED_CREDENTIALS, 25,
                "The link hides its real site behind an '@' — what looks like the site is not where it goes.",
            )
        }
        val suspiciousTld = SUSPICIOUS_TLDS.any { host.endsWith(".$it") }
        if (suspiciousTld) {
            signals += QrSignal(QrRiskCode.SUSPICIOUS_TLD, 10, "The link uses an uncommon domain ending often used for scams.")
        }
        val brand = BRAND_KEYWORDS.firstOrNull { host.contains(it) }
        if (brand != null && (suspiciousTld || shortener || insecure)) {
            signals += QrSignal(
                QrRiskCode.BRAND_IN_URL, 25,
                "The link name mentions '$brand' but is not an official, secure domain for it.",
            )
        }

        signals.sortByDescending { it.weight }
        val score = signals.sumOf { it.weight }.coerceIn(0, 100)
        return QrRiskReport(QrContentType.URL, levelFor(score), score, signals, value = raw)
    }

    private fun analyzeWifi(raw: String): QrRiskReport {
        val open = raw.contains("T:nopass", ignoreCase = true) ||
            !raw.contains("P:", ignoreCase = true) // no password field
        val signals = if (open) {
            listOf(QrSignal(QrRiskCode.OPEN_WIFI, 15, "This QR connects to an open Wi-Fi network, where your traffic can be intercepted."))
        } else {
            listOf(QrSignal(QrRiskCode.ACTION_NOTE, 0, "This QR connects to a Wi-Fi network. Only do so if you trust the owner."))
        }
        val score = signals.sumOf { it.weight }
        return QrRiskReport(QrContentType.WIFI, levelFor(score), score, signals, value = raw)
    }

    private fun analyzePhone(raw: String): QrRiskReport {
        val number = raw.substringAfter("tel:").filter { it.isDigit() || it == '+' }
        val signals = if (isShortcode(number)) {
            listOf(QrSignal(QrRiskCode.PREMIUM_SHORTCODE, 20, "This QR dials a short code, which can trigger premium charges."))
        } else {
            listOf(QrSignal(QrRiskCode.ACTION_NOTE, 0, "This QR dials a phone number."))
        }
        return QrRiskReport(QrContentType.PHONE, levelFor(signals.sumOf { it.weight }), signals.sumOf { it.weight }, signals, value = raw)
    }

    private fun analyzeSms(raw: String): QrRiskReport {
        val afterScheme = raw.substringAfter(':')
        val number = afterScheme.substringBefore(':').substringBefore('?').filter { it.isDigit() || it == '+' }
        val signals = if (isShortcode(number)) {
            listOf(QrSignal(QrRiskCode.PREMIUM_SHORTCODE, 30, "This QR pre-addresses a text to a short code, which can trigger premium charges."))
        } else {
            listOf(QrSignal(QrRiskCode.ACTION_NOTE, 5, "This QR pre-writes a text message. Check the number and text before sending."))
        }
        return QrRiskReport(QrContentType.SMS, levelFor(signals.sumOf { it.weight }), signals.sumOf { it.weight }, signals, value = raw)
    }

    private fun plain(type: QrContentType, raw: String, note: String): QrRiskReport =
        QrRiskReport(type, QrRiskLevel.LOW, 0, listOf(QrSignal(QrRiskCode.ACTION_NOTE, 0, note)), value = raw)

    private fun isShortcode(number: String): Boolean {
        val digits = number.removePrefix("+").filter { it.isDigit() }
        return digits.isNotEmpty() && digits.length <= 6
    }

    private fun authorityOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?')

    private fun extractUrl(text: String): String? =
        BARE_DOMAIN.find(text)?.value

    private fun mapUpiLevel(level: RiskLevel): QrRiskLevel = when (level) {
        RiskLevel.CRITICAL -> QrRiskLevel.CRITICAL
        RiskLevel.HIGH -> QrRiskLevel.HIGH
        RiskLevel.MEDIUM -> QrRiskLevel.MEDIUM
        RiskLevel.LOW -> QrRiskLevel.LOW
    }

    private fun levelFor(score: Int): QrRiskLevel = when {
        score >= 60 -> QrRiskLevel.CRITICAL
        score >= 35 -> QrRiskLevel.HIGH
        score >= 15 -> QrRiskLevel.MEDIUM
        else -> QrRiskLevel.LOW
    }

    companion object {
        private val IP_HOST = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
        private val BARE_DOMAIN = Regex(
            "\\b[a-z0-9][a-z0-9.-]*\\.(com|net|in|org|co|xyz|top|info|link|click|ru|tk|apk)(/[^\\s]*)?",
            RegexOption.IGNORE_CASE,
        )
        private val SHORTENERS = setOf(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "rebrand.ly", "cutt.ly",
            "is.gd", "ow.ly", "rb.gy", "shorturl.at", "bitly.com", "t.ly",
        )
        private val SUSPICIOUS_TLDS = setOf("xyz", "top", "tk", "click", "link", "ru", "info", "cf", "gq", "ml")
        private val BRAND_KEYWORDS = setOf(
            "sbi", "hdfc", "icici", "axis", "paytm", "phonepe", "gpay", "bank",
            "upi", "npci", "amazon", "flipkart", "kotak",
        )
    }
}
