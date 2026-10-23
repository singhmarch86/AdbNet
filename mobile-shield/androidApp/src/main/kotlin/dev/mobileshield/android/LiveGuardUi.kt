package dev.mobileshield.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mobileshield.android.guard.ForegroundAppDetector
import dev.mobileshield.android.guard.GuardWarningOverlay
import dev.mobileshield.android.guard.PaymentGuardService

/**
 * "Payment protection" card: turns the live guard on/off and walks the user
 * through the two grants it needs (usage access + draw-over-other-apps), plus
 * an optional link to the advanced accessibility detector.
 */
@Composable
fun PaymentGuardCard() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Payment protection (beta)", style = MaterialTheme.typography.titleLarge)
            Text(
                if (running)
                    "On. If you open a payment app while on a call and sharing your screen, " +
                        "you'll get a warning."
                else
                    "Warns you at the moment of payment if signs of a remote-access scam are " +
                        "present — a live call plus screen sharing while a payment app is open. " +
                        "Runs on-device."
            )
            Button(
                onClick = {
                    when {
                        !ForegroundAppDetector.hasUsageAccess(context) ->
                            open(context, Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        !GuardWarningOverlay.canDraw(context) ->
                            open(
                                context,
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        running -> {
                            PaymentGuardService.stop(context); running = false
                        }
                        else -> {
                            PaymentGuardService.start(context); running = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        !ForegroundAppDetector.hasUsageAccess(context) -> "Grant usage access"
                        !GuardWarningOverlay.canDraw(context) -> "Allow display over other apps"
                        running -> "Turn off payment protection"
                        else -> "Turn on payment protection"
                    }
                )
            }
            OutlinedButton(
                onClick = { open(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Advanced detection (accessibility) — optional")
            }
        }
    }
}

private fun open(context: Context, action: String, data: Uri? = null) {
    val intent = Intent(action).apply {
        if (data != null) this.data = data
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
