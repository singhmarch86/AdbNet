package dev.mobileshield.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecimalTest {
    @Test
    fun `equal values compare and hash equal regardless of formatting`() {
        val a = Decimal.parseOrNull("100.50")!!
        val b = Decimal.parseOrNull("100.5")!!
        val c = Decimal.parseOrNull("0100.500")!!
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(0, a.compareTo(b))
    }

    @Test
    fun `orders by magnitude and sign`() {
        assertTrue(Decimal.parseOrNull("25000")!! > Decimal.parseOrNull("5000")!!)
        assertTrue(Decimal.parseOrNull("5000")!! > Decimal.parseOrNull("4999.99")!!)
        assertTrue(Decimal.parseOrNull("0.1")!! > Decimal.parseOrNull("0.09")!!)
        assertTrue(Decimal.parseOrNull("-1")!! < Decimal.parseOrNull("0")!!)
        assertEquals(Decimal.parseOrNull("-0")!!, Decimal.parseOrNull("0")!!)
    }

    @Test
    fun `canonical toString`() {
        assertEquals("100.5", Decimal.parseOrNull("100.50")!!.toString())
        assertEquals("0", Decimal.parseOrNull("0.0")!!.toString())
        assertEquals("42", Decimal.parseOrNull("042")!!.toString())
    }

    @Test
    fun `rejects non-decimals`() {
        assertNull(Decimal.parseOrNull(""))
        assertNull(Decimal.parseOrNull("."))
        assertNull(Decimal.parseOrNull("1e3"))
        assertNull(Decimal.parseOrNull("12abc"))
        assertNull(Decimal.parseOrNull("1,000"))
    }
}

class IdnaTest {
    @Test
    fun `ascii labels pass through unchanged`() {
        assertEquals("www.example.com", Idna.toAscii("www.example.com"))
    }

    @Test
    fun `non-ascii labels are punycoded`() {
        // Canonical RFC 3492 / IDN example.
        assertEquals("xn--bcher-kva.example", Idna.toAscii("bücher.example"))
    }
}

class PercentCodecTest {
    @Test
    fun `decodes escapes and plus`() {
        assertEquals("Corner Store", percentDecode("Corner%20Store"))
        assertEquals("a b", percentDecode("a+b"))
        assertEquals("#1", percentDecode("%231"))
    }

    @Test
    fun `leaves invalid escapes verbatim`() {
        assertEquals("100%", percentDecode("100%"))
        assertEquals("%zz", percentDecode("%zz"))
    }

    @Test
    fun `passes through plain text`() {
        assertEquals("alice@okaxis", percentDecode("alice@okaxis"))
    }
}
