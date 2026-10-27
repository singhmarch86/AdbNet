package dev.mobileshield.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.mobileshield.android.data.Blocklists
import dev.mobileshield.android.filter.DnsResolvers
import dev.mobileshield.android.filter.FilterSettings
import dev.mobileshield.android.net.Ipv4UdpPacket
import dev.mobileshield.core.dns.DnsMessage
import dev.mobileshield.core.filter.DomainBlocklistMatcher
import dev.mobileshield.core.filter.FilterAction
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A local-only DNS filtering VPN. Only DNS queries to the app's virtual
 * resolver are routed through the tun interface; everything else is untouched,
 * so normal traffic is neither seen nor slowed. For each query we consult the
 * shared [DomainBlocklistMatcher]: blocked names get an NXDOMAIN answer, allowed
 * names are forwarded to an upstream resolver over a protected socket.
 *
 * NOTE: this is the device layer around the (unit-tested) shared logic. The
 * packet plumbing needs on-device verification; it cannot be exercised by JVM
 * unit tests. The filtering *decisions* are tested in :core.
 */
class ShieldVpnService : VpnService() {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var tunnel: ParcelFileDescriptor? = null
    private lateinit var matcher: DomainBlocklistMatcher
    private var upstreamServers: List<String> = DnsResolvers.DEFAULT.servers

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFilter()
            return START_NOT_STICKY
        }
        startFilter()
        return START_STICKY
    }

    private fun startFilter() {
        if (running.getAndSet(true)) return
        matcher = Blocklists.createMatcher()
        upstreamServers = FilterSettings.resolver(this).servers
        startForeground(NOTIFICATION_ID, buildNotification())

        val fd = Builder()
            .setSession("Mobile Shield")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_ADDRESS)
            .addRoute(VPN_ADDRESS, 32)
            .setBlocking(true)
            .establish()

        if (fd == null) {
            running.set(false)
            stopSelf()
            return
        }
        tunnel = fd
        worker = Thread({ runLoop(fd) }, "shield-dns").also { it.start() }
    }

    private fun stopFilter() {
        running.set(false)
        worker?.interrupt()
        worker = null
        try {
            tunnel?.close()
        } catch (_: Exception) {
        }
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET)
        while (running.get()) {
            val n = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) continue
            try {
                handlePacket(buffer, n, output)
            } catch (e: Exception) {
                Log.w(TAG, "DNS packet handling failed", e)
            }
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int, output: FileOutputStream) {
        val parsed = Ipv4UdpPacket.parse(buffer, length) ?: return
        if (parsed.dstPort != Ipv4UdpPacket.DNS_PORT) return

        val query = buffer.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLength)
        val question = DnsMessage.parseFirstQuestion(query)

        val responsePayload: ByteArray = if (question != null) {
            val decision = matcher.decide(question.name)
            when (decision.action) {
                FilterAction.BLOCK ->
                    DnsMessage.buildNxDomainResponse(query) ?: forward(query)
                FilterAction.REWRITE -> {
                    // SafeSearch: answer with a CNAME to the safe host, which the
                    // client then resolves (and which the filter allows).
                    val target = decision.rewriteTo
                    if (target != null) {
                        DnsMessage.buildCnameResponse(query, target) ?: forward(query)
                    } else {
                        forward(query)
                    }
                }
                FilterAction.ALLOW -> forward(query)
            }
        } else {
            forward(query)
        }

        val reply = Ipv4UdpPacket.buildReply(parsed, responsePayload)
        synchronized(output) { output.write(reply) }
    }

    /**
     * Forwards a DNS query to a family-filtering upstream resolver over a
     * protected socket. The upstream (Cloudflare for Families) maintains the
     * adult/malware categorization server-side, so every allowed name is
     * filtered against a real, constantly-updated list — not just our local
     * seed entries. Tries each resolver in turn; throws if all fail.
     */
    private fun forward(query: ByteArray): ByteArray {
        var last: Exception? = null
        for (server in upstreamServers) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket) // keep this socket off the VPN to avoid a loop
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    val upstream = InetAddress.getByName(server)
                    socket.send(DatagramPacket(query, query.size, upstream, Ipv4UdpPacket.DNS_PORT))
                    val resp = ByteArray(MAX_PACKET)
                    val dp = DatagramPacket(resp, resp.size)
                    socket.receive(dp)
                    return resp.copyOf(dp.length)
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("No upstream DNS server configured")
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Content filter", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mobile Shield is filtering")
            .setContentText("Blocking adult/malware domains via family-safe DNS and forcing SafeSearch.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopFilter()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShieldVpnService"
        const val ACTION_STOP = "dev.mobileshield.android.STOP"
        private const val VPN_ADDRESS = "10.111.0.2"
        // Upstream resolvers are chosen by the user (see FilterSettings /
        // DnsResolvers); the default is Cloudflare for Families.
        private const val UPSTREAM_TIMEOUT_MS = 5000
        private const val MAX_PACKET = 32767
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "shield_filter"
    }
}
