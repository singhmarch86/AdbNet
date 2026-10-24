package dev.mobileshield.android.smishing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.mobileshield.core.smishing.SmishingClassifier
import dev.mobileshield.core.smishing.SmishingLevel

/**
 * OPTIONAL auto-scan of incoming SMS. When the user grants RECEIVE_SMS, each
 * incoming message is scored on-device by the shared [SmishingClassifier] and,
 * if it looks like a scam, the user gets a warning notification. The message
 * text never leaves the phone.
 *
 * NOTE: SMS permissions are heavily restricted on Google Play (generally only
 * the default SMS app may hold them), so this path is mainly for sideload /
 * enterprise builds. The in-app "check a message" card needs no permission and
 * is the Play-safe path.
 */
class SmsSmishingReceiver : BroadcastReceiver() {

    private val classifier = SmishingClassifier()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().displayOriginatingAddress
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        if (body.isBlank()) return

        val report = classifier.classify(body, sender)
        if (report.level != SmishingLevel.LOW) {
            SmishingNotifier.warn(context, sender, report)
        }
    }
}
