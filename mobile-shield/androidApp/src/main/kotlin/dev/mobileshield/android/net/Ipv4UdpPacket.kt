package dev.mobileshield.android.net

/**
 * Minimal IPv4 + UDP handling for the DNS filtering VPN. The tun interface
 * hands us whole IP packets; we only care about UDP datagrams destined for a
 * DNS server (port 53), from which we extract the DNS payload and, when
 * replying, build a response packet back to the client.
 *
 * UDP checksums are set to 0, which IPv4 explicitly permits ("no checksum"),
 * so we only have to compute the IPv4 header checksum — one less place to get
 * wrong. IPv4 options are not emitted (our replies use a fixed 20-byte header).
 */
object Ipv4UdpPacket {
    private const val PROTO_UDP = 17
    const val DNS_PORT = 53

    class Parsed(
        val ihl: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    /** Parses [length] bytes of [packet] as IPv4/UDP, or null if it is not that. */
    fun parse(packet: ByteArray, length: Int): Parsed? {
        if (length < 28) return null                       // 20 IP + 8 UDP minimum
        val version = (packet[0].toInt() and 0xF0) ushr 4
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl + 8 > length) return null
        if ((packet[9].toInt() and 0xFF) != PROTO_UDP) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        val udpLen = u16(packet, ihl + 4)
        val payloadOffset = ihl + 8
        val payloadLength = (udpLen - 8).coerceAtMost(length - payloadOffset)
        if (payloadLength < 0) return null

        return Parsed(ihl, srcIp, dstIp, srcPort, dstPort, payloadOffset, payloadLength)
    }

    /**
     * Builds an IPv4/UDP reply carrying [dnsPayload], swapping source and
     * destination of [request] so it goes back to the querying client.
     */
    fun buildReply(request: Parsed, dnsPayload: ByteArray): ByteArray {
        val totalLen = 20 + 8 + dnsPayload.size
        val out = ByteArray(totalLen)

        // IPv4 header (20 bytes, no options).
        out[0] = 0x45                                       // version 4, IHL 5
        out[1] = 0                                          // DSCP/ECN
        putU16(out, 2, totalLen)
        putU16(out, 4, 0)                                   // identification
        putU16(out, 6, 0x4000)                              // flags: Don't Fragment
        out[8] = 64                                         // TTL
        out[9] = PROTO_UDP.toByte()
        putU16(out, 10, 0)                                  // checksum placeholder
        request.dstIp.copyInto(out, 12)                     // src = original dst (the DNS server)
        request.srcIp.copyInto(out, 16)                     // dst = original client
        putU16(out, 10, checksum(out, 0, 20))

        // UDP header (8 bytes).
        putU16(out, 20, request.dstPort)                    // src port = 53
        putU16(out, 22, request.srcPort)                    // dst port = client's port
        putU16(out, 24, 8 + dnsPayload.size)                // UDP length
        putU16(out, 26, 0)                                  // UDP checksum omitted (valid in IPv4)

        dnsPayload.copyInto(out, 28)
        return out
    }

    /** Standard one's-complement 16-bit header checksum over [len] bytes. */
    private fun checksum(b: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + len
        while (i + 1 < end) {
            sum += u16(b, i)
            i += 2
        }
        if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun putU16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }
}
