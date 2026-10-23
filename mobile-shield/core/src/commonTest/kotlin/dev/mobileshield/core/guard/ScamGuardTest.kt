package dev.mobileshield.core.guard

import dev.mobileshield.core.hygiene.DeviceHygieneAnalyzer
import dev.mobileshield.core.hygiene.InstallSource
import dev.mobileshield.core.hygiene.InstalledApp
import dev.mobileshield.core.upi.UpiLinkAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScamGuardTest {
    private val guard = ScamGuard()
    private val upi = UpiLinkAnalyzer()
    private val hygiene = DeviceHygieneAnalyzer()

    private fun collectRequest() = upi.analyze("upi://collect?pa=scam@okaxis&am=1")
    private fun cleanPayment() = upi.analyze("upi://pay?pa=alice@okaxis&am=100&cu=INR")
    private fun deviceWithRemoteAccess() =
        hygiene.analyze(listOf(InstalledApp("com.anydesk.anydeskandroid", "AnyDesk", InstallSource.PLAY_STORE)))

    @Test
    fun `no signals is allow`() {
        val d = guard.evaluate(PaymentContext())
        assertEquals(GuardAction.ALLOW, d.action)
        assertEquals(0, d.score)
        assertTrue(d.signals.isEmpty())
    }

    @Test
    fun `clean payment stays allow`() {
        val d = guard.evaluate(PaymentContext(upi = cleanPayment()))
        assertEquals(GuardAction.ALLOW, d.action)
    }

    @Test
    fun `call plus screen share warns on its own`() {
        val d = guard.evaluate(PaymentContext(callActive = true, screenSharingActive = true))
        assertEquals(GuardAction.WARN, d.action)
        assertTrue(d.headline.contains("call", ignoreCase = true))
    }

    @Test
    fun `call plus screen share plus remote-access device is block`() {
        val d = guard.evaluate(
            PaymentContext(
                callActive = true,
                screenSharingActive = true,
                hygiene = deviceWithRemoteAccess(),
            )
        )
        assertEquals(GuardAction.BLOCK_RECOMMENDED, d.action)
        assertTrue(d.score >= 70)
    }

    @Test
    fun `collect request alone warns`() {
        val d = guard.evaluate(PaymentContext(upi = collectRequest()))
        assertEquals(GuardAction.WARN, d.action)
    }

    @Test
    fun `collect request with active screen share is block`() {
        val d = guard.evaluate(PaymentContext(upi = collectRequest(), screenSharingActive = true))
        assertEquals(GuardAction.BLOCK_RECOMMENDED, d.action)
    }

    @Test
    fun `first-seen payee alone is minor`() {
        val d = guard.evaluate(PaymentContext(payeeFirstSeen = true))
        assertEquals(GuardAction.ALLOW, d.action)
        assertTrue(d.signals.isNotEmpty())
    }

    @Test
    fun `signals are sorted by weight and score is capped`() {
        val d = guard.evaluate(
            PaymentContext(
                upi = collectRequest(),
                hygiene = deviceWithRemoteAccess(),
                callActive = true,
                screenSharingActive = true,
                payeeFirstSeen = true,
                initiatedFromLink = true,
            )
        )
        assertEquals(GuardAction.BLOCK_RECOMMENDED, d.action)
        assertEquals(100, d.score)
        val weights = d.signals.map { it.weight }
        assertEquals(weights.sortedDescending(), weights)
    }
}
