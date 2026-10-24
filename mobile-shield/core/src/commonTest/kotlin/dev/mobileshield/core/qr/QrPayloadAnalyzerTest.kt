package dev.mobileshield.core.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrPayloadAnalyzerTest {
    private val analyzer = QrPayloadAnalyzer()

    @Test
    fun `upi collect qr is critical and carries the upi report`() {
        val r = analyzer.analyze("upi://collect?pa=scam@okaxis&am=1")
        assertEquals(QrContentType.UPI, r.type)
        assertEquals(QrRiskLevel.CRITICAL, r.level)
        assertNotNull(r.upi)
    }

    @Test
    fun `clean upi pay qr is low but still notes it sends money`() {
        val r = analyzer.analyze("upi://pay?pa=alice@okaxis&am=50&cu=INR")
        assertEquals(QrContentType.UPI, r.type)
        assertEquals(QrRiskLevel.LOW, r.level)
        assertTrue(r.signals.any { it.code == QrRiskCode.UPI_PAYMENT })
    }

    @Test
    fun `https plain url is low risk`() {
        val r = analyzer.analyze("https://www.example.com/page")
        assertEquals(QrContentType.URL, r.type)
        assertEquals(QrRiskLevel.LOW, r.level)
    }

    @Test
    fun `apk url is a strong signal`() {
        val r = analyzer.analyze("http://secure-bank.in/app/update.apk")
        assertEquals(QrContentType.URL, r.type)
        assertTrue(r.signals.any { it.code == QrRiskCode.APK_LINK })
    }

    @Test
    fun `shortener and raw ip are flagged`() {
        assertTrue(analyzer.analyze("https://bit.ly/abc").signals.any { it.code == QrRiskCode.URL_SHORTENER })
        assertTrue(analyzer.analyze("http://192.168.1.1/login").signals.any { it.code == QrRiskCode.RAW_IP_URL })
    }

    @Test
    fun `punycode host is flagged`() {
        val r = analyzer.analyze("https://xn--pay-6k9c.com/login")
        assertTrue(r.signals.any { it.code == QrRiskCode.PUNYCODE_HOST })
    }

    @Test
    fun `embedded credentials trick is flagged`() {
        val r = analyzer.analyze("https://sbi.com@evil.example.xyz/login")
        assertTrue(r.signals.any { it.code == QrRiskCode.EMBEDDED_CREDENTIALS })
    }

    @Test
    fun `brand on a suspicious domain is flagged`() {
        val r = analyzer.analyze("http://sbi-verify.xyz/kyc")
        assertTrue(r.signals.any { it.code == QrRiskCode.BRAND_IN_URL })
        assertTrue(r.signals.any { it.code == QrRiskCode.SUSPICIOUS_TLD })
    }

    @Test
    fun `open wifi qr is flagged`() {
        val r = analyzer.analyze("WIFI:T:nopass;S:FreePublicWifi;;")
        assertEquals(QrContentType.WIFI, r.type)
        assertTrue(r.signals.any { it.code == QrRiskCode.OPEN_WIFI })
    }

    @Test
    fun `sms to a shortcode is premium risk`() {
        val r = analyzer.analyze("SMSTO:56070:ACTIVATE")
        assertEquals(QrContentType.SMS, r.type)
        assertTrue(r.signals.any { it.code == QrRiskCode.PREMIUM_SHORTCODE })
    }

    @Test
    fun `tel and email and geo are classified`() {
        assertEquals(QrContentType.PHONE, analyzer.analyze("tel:+919876543210").type)
        assertEquals(QrContentType.EMAIL, analyzer.analyze("mailto:a@b.com").type)
        assertEquals(QrContentType.GEO, analyzer.analyze("geo:19.0,72.8").type)
    }

    @Test
    fun `bare domain in text is treated as a url`() {
        val r = analyzer.analyze("Claim now paytm-offer.xyz/win")
        assertEquals(QrContentType.URL, r.type)
    }

    @Test
    fun `plain text is low with no url`() {
        val r = analyzer.analyze("Table number 12")
        assertEquals(QrContentType.TEXT, r.type)
        assertEquals(QrRiskLevel.LOW, r.level)
    }
}
