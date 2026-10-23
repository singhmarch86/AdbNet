package dev.mobileshield.core.guard

import dev.mobileshield.core.hygiene.HygieneReport
import dev.mobileshield.core.upi.RiskLevel
import dev.mobileshield.core.upi.UpiRiskReport

/**
 * A live snapshot of everything known at the instant a payment is about to
 * happen. The platform fills in what it can; every field is optional so the
 * engine degrades gracefully when a signal is unavailable.
 */
data class PaymentContext(
    /** Result of analyzing the UPI link/QR being paid, if one was scanned. */
    val upi: UpiRiskReport? = null,
    /** Latest device-hygiene result, if a checkup has run. */
    val hygiene: HygieneReport? = null,
    /** A phone call is in progress right now. */
    val callActive: Boolean = false,
    /** A screen-sharing / remote-access session is active right now. */
    val screenSharingActive: Boolean = false,
    /** This payee has never been paid from this device before. */
    val payeeFirstSeen: Boolean = false,
    /** The payment was started from a link/message, not typed by the user. */
    val initiatedFromLink: Boolean = false,
)

/** What the app should do at the payment moment. */
enum class GuardAction { ALLOW, WARN, BLOCK_RECOMMENDED }

data class GuardSignal(
    val weight: Int,
    val message: String,
)

data class GuardDecision(
    val action: GuardAction,
    val score: Int,
    /** The single most important thing to tell the user, in one line. */
    val headline: String,
    /** All contributing reasons, most important first. */
    val signals: List<GuardSignal>,
)

/**
 * The Scam-Moment Risk Engine: fuses the independent signals mobile-shield
 * already computes into one explainable verdict at the instant of payment.
 *
 * Its edge over platform features is that it runs **pre-tap, on-device, on any
 * phone**, and explains itself. The dangerous combinations it is tuned for are
 * the real-world remote-access fraud patterns: a live call + an active
 * screen-share while paying, a collect (pull-money) request, or a device
 * already carrying remote-control capability.
 *
 * Pure function of its input — unit-tested, reused unchanged on Android/iOS.
 */
class ScamGuard(
    private val warnThreshold: Int = 35,
    private val blockThreshold: Int = 70,
) {
    fun evaluate(context: PaymentContext): GuardDecision {
        val signals = mutableListOf<GuardSignal>()

        // The classic remote-access scam: on a call AND screen is being shared
        // while a payment is in progress.
        if (context.callActive && context.screenSharingActive) {
            signals += GuardSignal(
                55,
                "You are on a call while your screen is being shared and a payment is starting. " +
                    "This is the most common remote-access scam — hang up and stop sharing before paying.",
            )
        } else if (context.screenSharingActive) {
            signals += GuardSignal(
                35,
                "Your screen is being shared right now. Never pay while someone can see your screen.",
            )
        }

        // Device already carries remote-control capability (hygiene checkup).
        if (context.hygiene?.hasRemoteControlRisk == true) {
            signals += GuardSignal(
                30,
                "A screen-sharing/remote-access app or an accessibility service is active on this " +
                    "device, which can watch or control your payment.",
            )
        }

        // Risk carried by the UPI link/QR itself.
        context.upi?.let { upi ->
            if (upi.isUpiLink) {
                val weight = when (upi.level) {
                    RiskLevel.CRITICAL -> 45
                    RiskLevel.HIGH -> 25
                    RiskLevel.MEDIUM -> 12
                    RiskLevel.LOW -> 0
                }
                if (weight > 0) {
                    val lead = upi.flags.firstOrNull()?.message
                        ?: "This payment request looks risky."
                    signals += GuardSignal(weight, lead)
                }
            }
        }

        if (context.payeeFirstSeen) {
            val highValueContext = context.upi?.let { it.level >= RiskLevel.MEDIUM } == true
            if (highValueContext) {
                signals += GuardSignal(
                    15,
                    "You have never paid this payee before, and this request already looks risky. " +
                        "Confirm who they are first.",
                )
            } else {
                signals += GuardSignal(
                    5,
                    "First time paying this payee — double-check the name before you confirm.",
                )
            }
        }

        if (context.initiatedFromLink) {
            signals += GuardSignal(
                10,
                "This payment was opened from a link or message rather than started by you.",
            )
        }

        signals.sortByDescending { it.weight }
        val score = signals.sumOf { it.weight }.coerceIn(0, 100)
        val action = when {
            score >= blockThreshold -> GuardAction.BLOCK_RECOMMENDED
            score >= warnThreshold -> GuardAction.WARN
            else -> GuardAction.ALLOW
        }
        val headline = when (action) {
            GuardAction.BLOCK_RECOMMENDED ->
                signals.firstOrNull()?.message ?: "High scam risk — do not complete this payment."
            GuardAction.WARN ->
                signals.firstOrNull()?.message ?: "Be careful before completing this payment."
            GuardAction.ALLOW -> "No elevated risk detected for this payment."
        }

        return GuardDecision(action, score, headline, signals.toList())
    }
}
