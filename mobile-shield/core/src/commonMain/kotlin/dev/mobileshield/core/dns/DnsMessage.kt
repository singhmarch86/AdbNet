package dev.mobileshield.core.dns

/**
 * Just enough DNS wire-format handling for the on-device filter: read the
 * queried name out of a request, and synthesize the two responses the filter
 * needs — NXDOMAIN (to block) and a single A-record answer (to sinkhole).
 *
 * This is pure, allocation-light Kotlin with no platform dependency so it is
 * shared by the Android `VpnService` and the iOS NetworkExtension and can be
 * unit-tested exhaustively on crafted byte arrays. The actual UDP/IP transport
 * lives in each platform's shell.
 *
 * Only the question section is parsed, and DNS name compression is not expected
 * there (it never appears in a query's question). Malformed input yields null
 * rather than throwing, so a junk packet is simply forwarded untouched.
 */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val CLASS_IN = 1

    private const val HEADER_LEN = 12
    private const val MAX_NAME_LEN = 255

    data class Question(
        val name: String,
        val type: Int,
        val qclass: Int,
        /** Byte offset just past the question (header + QNAME + QTYPE + QCLASS). */
        val endOffset: Int,
    )

    /** Parses the first question, or null if the packet is not a usable query. */
    fun parseFirstQuestion(packet: ByteArray): Question? {
        if (packet.size < HEADER_LEN) return null
        val qdCount = u16(packet, 4)
        if (qdCount < 1) return null

        val labels = ArrayList<String>()
        var offset = HEADER_LEN
        var nameLen = 0
        while (true) {
            if (offset >= packet.size) return null
            val len = packet[offset].toInt() and 0xFF
            // Compression pointers (top two bits set) are invalid in a question.
            if (len and 0xC0 != 0) return null
            offset++
            if (len == 0) break
            if (offset + len > packet.size) return null
            nameLen += len + 1
            if (nameLen > MAX_NAME_LEN) return null
            labels.add(packet.decodeToString(offset, offset + len))
            offset += len
        }
        if (offset + 4 > packet.size) return null
        val type = u16(packet, offset)
        val qclass = u16(packet, offset + 2)
        return Question(labels.joinToString("."), type, qclass, offset + 4)
    }

    /**
     * Builds an NXDOMAIN response for [query] (the name does not exist), echoing
     * the query's id and question. Returns null if [query] has no valid
     * question.
     */
    fun buildNxDomainResponse(query: ByteArray): ByteArray? {
        val q = parseFirstQuestion(query) ?: return null
        val out = query.copyOf(q.endOffset)
        writeResponseHeaderFlags(out, rcode = 3)
        setCounts(out, answers = 0)
        return out
    }

    /**
     * Builds a response answering [query] with a single A record pointing at
     * [ipv4] (4 bytes) for [ttlSeconds]. Used to sinkhole a blocked name to a
     * dead address (e.g. 0.0.0.0) or to a local block page. Returns null if the
     * query is invalid or [ipv4] is not 4 bytes.
     */
    fun buildAResponse(query: ByteArray, ipv4: ByteArray, ttlSeconds: Int = 60): ByteArray? {
        if (ipv4.size != 4) return null
        val q = parseFirstQuestion(query) ?: return null

        val header = query.copyOf(q.endOffset)
        writeResponseHeaderFlags(header, rcode = 0)
        setCounts(header, answers = 1)

        // Answer: name pointer to the question (0xC00C), A/IN, TTL, RDLENGTH=4.
        val answer = ByteArray(16)
        answer[0] = 0xC0.toByte(); answer[1] = 0x0C
        putU16(answer, 2, TYPE_A)
        putU16(answer, 4, CLASS_IN)
        answer[6] = (ttlSeconds ushr 24).toByte()
        answer[7] = (ttlSeconds ushr 16).toByte()
        answer[8] = (ttlSeconds ushr 8).toByte()
        answer[9] = ttlSeconds.toByte()
        putU16(answer, 10, 4)
        ipv4.copyInto(answer, 12)

        return header + answer
    }

    // QR=1, RD copied from query, RA=1, plus the given rcode (0..15).
    private fun writeResponseHeaderFlags(buf: ByteArray, rcode: Int) {
        val rd = buf[2].toInt() and 0x01
        buf[2] = (0x80 or (rd shl 0)).toByte()      // QR=1, Opcode=0, AA=0, TC=0, RD
        buf[3] = (0x80 or (rcode and 0x0F)).toByte() // RA=1, Z=0, RCODE
    }

    private fun setCounts(buf: ByteArray, answers: Int) {
        putU16(buf, 6, answers) // ANCOUNT
        putU16(buf, 8, 0)       // NSCOUNT
        putU16(buf, 10, 0)      // ARCOUNT
    }

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun putU16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }
}
