package dev.mobileshield.core.dns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsMessageTest {

    /** Builds a minimal DNS query packet for [name] with the given [type]. */
    private fun query(name: String, type: Int = DnsMessage.TYPE_A, id: Int = 0x1234): ByteArray {
        val labels = name.split('.')
        val q = ArrayList<Byte>()
        // Header: id, flags(RD=1), qd=1, an=ns=ar=0
        q.add((id ushr 8).toByte()); q.add(id.toByte())
        q.add(0x01); q.add(0x00)
        q.add(0x00); q.add(0x01)
        q.add(0x00); q.add(0x00)
        q.add(0x00); q.add(0x00)
        q.add(0x00); q.add(0x00)
        for (label in labels) {
            q.add(label.length.toByte())
            for (c in label) q.add(c.code.toByte())
        }
        q.add(0x00) // root
        q.add((type ushr 8).toByte()); q.add(type.toByte())
        q.add(0x00); q.add(0x01) // class IN
        return q.toByteArray()
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    @Test
    fun `parses the question name type and end offset`() {
        val q = query("ads.example.com")
        val parsed = DnsMessage.parseFirstQuestion(q)
        assertTrue(parsed != null)
        assertEquals("ads.example.com", parsed!!.name)
        assertEquals(DnsMessage.TYPE_A, parsed.type)
        assertEquals(DnsMessage.CLASS_IN, parsed.qclass)
        assertEquals(q.size, parsed.endOffset)
    }

    @Test
    fun `parses AAAA type`() {
        val parsed = DnsMessage.parseFirstQuestion(query("example.com", DnsMessage.TYPE_AAAA))
        assertEquals(DnsMessage.TYPE_AAAA, parsed!!.type)
    }

    @Test
    fun `rejects malformed packets`() {
        assertNull(DnsMessage.parseFirstQuestion(ByteArray(4)))              // too short
        assertNull(DnsMessage.parseFirstQuestion(ByteArray(12)))            // qdcount=0
        // Label length runs past the end of the buffer.
        val bad = query("a.b").copyOf(14)
        assertNull(DnsMessage.parseFirstQuestion(bad))
    }

    @Test
    fun `nxdomain response preserves id and question, sets QR and rcode 3`() {
        val q = query("bad.example.com", id = 0xABCD)
        val resp = DnsMessage.buildNxDomainResponse(q)!!
        assertEquals(0xABCD, u16(resp, 0))                       // id echoed
        assertTrue(resp[2].toInt() and 0x80 != 0)                // QR=1
        assertEquals(3, resp[3].toInt() and 0x0F)                // RCODE=3 (NXDOMAIN)
        assertEquals(0, u16(resp, 6))                            // ANCOUNT=0
        // Question is echoed unchanged.
        assertEquals("bad.example.com", DnsMessage.parseFirstQuestion(resp)!!.name)
    }

    @Test
    fun `a response carries the sinkhole address`() {
        val q = query("ads.example.com")
        val resp = DnsMessage.buildAResponse(q, byteArrayOf(0, 0, 0, 0), ttlSeconds = 30)!!
        assertTrue(resp[2].toInt() and 0x80 != 0)                // QR=1
        assertEquals(0, resp[3].toInt() and 0x0F)                // RCODE=0
        assertEquals(1, u16(resp, 6))                            // ANCOUNT=1
        // Last 4 bytes are the A record RDATA = 0.0.0.0
        val rdata = resp.copyOfRange(resp.size - 4, resp.size)
        assertTrue(rdata.all { it.toInt() == 0 })
    }

    @Test
    fun `a response rejects non-ipv4`() {
        assertNull(DnsMessage.buildAResponse(query("x.com"), byteArrayOf(1, 2, 3)))
    }

    /** Decodes a length-prefixed DNS name (no compression) starting at [offset]. */
    private fun decodeName(b: ByteArray, offset: Int): String {
        val labels = ArrayList<String>()
        var i = offset
        while (true) {
            val len = b[i].toInt() and 0xFF
            i++
            if (len == 0) break
            labels.add(b.decodeToString(i, i + len))
            i += len
        }
        return labels.joinToString(".")
    }

    @Test
    fun `encodeName produces length-prefixed labels terminated by root`() {
        val encoded = DnsMessage.encodeName("forcesafesearch.google.com")!!
        assertEquals(0, encoded.last().toInt())
        assertEquals("forcesafesearch.google.com", decodeName(encoded, 0))
    }

    @Test
    fun `encodeName rejects empty and oversized labels`() {
        assertNull(DnsMessage.encodeName("a..b"))
        assertNull(DnsMessage.encodeName("x".repeat(64) + ".com"))
    }

    @Test
    fun `cname response carries the canonical name`() {
        val q = query("google.com")
        val resp = DnsMessage.buildCnameResponse(q, "forcesafesearch.google.com", ttlSeconds = 300)!!
        assertTrue(resp[2].toInt() and 0x80 != 0)                // QR=1
        assertEquals(0, resp[3].toInt() and 0x0F)                // RCODE=0
        assertEquals(1, u16(resp, 6))                            // ANCOUNT=1

        // Answer begins right after the echoed question.
        val ansStart = q.size
        assertEquals(0xC00C, u16(resp, ansStart))                // name pointer to question
        assertEquals(DnsMessage.TYPE_CNAME, u16(resp, ansStart + 2))
        assertEquals(DnsMessage.CLASS_IN, u16(resp, ansStart + 4))
        val rdLength = u16(resp, ansStart + 10)
        val rdataStart = ansStart + 12
        assertEquals("forcesafesearch.google.com", decodeName(resp, rdataStart))
        // RDLENGTH matches the encoded name length.
        assertEquals(rdLength, resp.size - rdataStart)
    }

    @Test
    fun `cname response rejects invalid inputs`() {
        assertNull(DnsMessage.buildCnameResponse(ByteArray(4), "safe.example.com")) // bad query
        assertNull(DnsMessage.buildCnameResponse(query("google.com"), "a..b"))       // bad name
    }
}
