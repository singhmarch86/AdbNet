package dev.mobileshield.core.upi

import dev.mobileshield.core.util.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpiLinkParseTest {
    @Test
    fun `parses standard pay link`() {
        val link = UpiLink.parseOrNull("upi://pay?pa=alice@okaxis&pn=Alice&am=100.50&cu=INR&tn=lunch")
        assertTrue(link != null)
        assertEquals("pay", link!!.action)
        assertEquals("alice@okaxis", link.payeeAddress)
        assertEquals("Alice", link.payeeName)
        assertEquals(Decimal.parseOrNull("100.50"), link.amount)
        assertEquals("okaxis", link.payeeHandle)
        assertEquals("INR", link.currency)
    }

    @Test
    fun `url-decodes values`() {
        val link = UpiLink.parseOrNull("upi://pay?pa=shop@okicici&pn=Corner%20Store&tn=Order%20%231")
        assertEquals("Corner Store", link!!.payeeName)
        assertEquals("Order #1", link.note)
    }

    @Test
    fun `returns null for non-upi input`() {
        assertNull(UpiLink.parseOrNull("https://example.com/pay"))
        assertNull(UpiLink.parseOrNull("just some text"))
    }

    @Test
    fun `invalid amount parses as null without throwing`() {
        val link = UpiLink.parseOrNull("upi://pay?pa=a@okaxis&am=not-a-number")
        assertEquals("not-a-number", link!!.amountRaw)
        assertNull(link.amount)
    }
}

class UpiLinkAnalyzerTest {
    private val analyzer = UpiLinkAnalyzer()

    @Test
    fun `clean small payment to known handle is low risk`() {
        val r = analyzer.analyze("upi://pay?pa=alice@okaxis&pn=Alice&am=120&cu=INR&tn=tea")
        assertEquals(RiskLevel.LOW, r.level)
        assertTrue(r.isUpiLink)
        assertTrue(r.flags.isEmpty())
    }

    @Test
    fun `collect request is critical`() {
        val r = analyzer.analyze("upi://collect?pa=scam@okaxis&pn=Prize&am=1")
        assertEquals(RiskLevel.CRITICAL, r.level)
        assertTrue(r.flags.any { it.code == RiskCode.COLLECT_REQUEST })
    }

    @Test
    fun `mode 02 is treated as collect`() {
        val r = analyzer.analyze("upi://pay?pa=scam@okaxis&mode=02&am=1")
        assertTrue(r.flags.any { it.code == RiskCode.COLLECT_REQUEST })
    }

    @Test
    fun `high amount is flagged`() {
        val r = analyzer.analyze("upi://pay?pa=alice@okaxis&am=25000&cu=INR")
        assertTrue(r.flags.any { it.code == RiskCode.HIGH_AMOUNT })
        assertTrue(r.score >= 15)
    }

    @Test
    fun `suspicious note keyword is flagged`() {
        val r = analyzer.analyze("upi://pay?pa=alice@okaxis&am=10&tn=KYC%20refund%20verify")
        assertTrue(r.flags.any { it.code == RiskCode.SUSPICIOUS_NOTE })
    }

    @Test
    fun `malformed vpa is flagged`() {
        val r = analyzer.analyze("upi://pay?pa=not-a-vpa&am=10")
        assertTrue(r.flags.any { it.code == RiskCode.MALFORMED_VPA })
    }

    @Test
    fun `unknown psp handle is flagged but known one is not`() {
        val unknown = analyzer.analyze("upi://pay?pa=alice@weirdbank&am=10")
        assertTrue(unknown.flags.any { it.code == RiskCode.UNKNOWN_PSP_HANDLE })
        val known = analyzer.analyze("upi://pay?pa=alice@okhdfcbank&am=10")
        assertFalse(known.flags.any { it.code == RiskCode.UNKNOWN_PSP_HANDLE })
    }

    @Test
    fun `missing payee is flagged`() {
        val r = analyzer.analyze("upi://pay?am=10&cu=INR")
        assertTrue(r.flags.any { it.code == RiskCode.MISSING_PAYEE })
    }

    @Test
    fun `non-inr currency is flagged`() {
        val r = analyzer.analyze("upi://pay?pa=alice@okaxis&am=10&cu=USD")
        assertTrue(r.flags.any { it.code == RiskCode.NON_INR_CURRENCY })
    }

    @Test
    fun `unsigned merchant is flagged`() {
        val r = analyzer.analyze("upi://pay?pa=shop@okaxis&am=10&mc=5411")
        assertTrue(r.flags.any { it.code == RiskCode.UNSIGNED_MERCHANT })
        val signed = analyzer.analyze("upi://pay?pa=shop@okaxis&am=10&mc=5411&sign=abc")
        assertFalse(signed.flags.any { it.code == RiskCode.UNSIGNED_MERCHANT })
    }

    @Test
    fun `duplicate payee params are flagged as tampering`() {
        val r = analyzer.analyze("upi://pay?pa=alice@okaxis&pa=evil@okaxis&am=10")
        assertTrue(r.flags.any { it.code == RiskCode.DUPLICATE_PARAMETERS })
    }

    @Test
    fun `non-upi input reports not a upi link`() {
        val r = analyzer.analyze("https://example.com")
        assertFalse(r.isUpiLink)
        assertEquals(RiskLevel.LOW, r.level)
        assertTrue(r.flags.any { it.code == RiskCode.NOT_A_UPI_LINK })
    }

    @Test
    fun `score is capped at 100`() {
        val r = analyzer.analyze(
            "upi://collect?pa=bad&pa=bad2&am=99999&cu=USD&mc=1&tn=lottery%20prize%20kyc"
        )
        assertTrue(r.score <= 100)
        assertEquals(RiskLevel.CRITICAL, r.level)
    }
}
