package dev.mobileshield.android

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mobileshield.core.smishing.SmishingClassifier
import dev.mobileshield.core.smishing.SmishingLevel
import dev.mobileshield.core.smishing.SmishingReport

/**
 * "Check a message" card: paste any SMS and the shared [SmishingClassifier]
 * scores it on-device (no permission needed). Also offers the optional,
 * Play-restricted auto-scan of incoming SMS.
 */
@Composable
fun MessageCheckCard() {
    val classifier = remember { SmishingClassifier() }
    var text by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<SmishingReport?>(null) }

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not; the receiver simply becomes active when granted */ }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check a message", style = MaterialTheme.typography.titleLarge)
            Text("Paste a suspicious SMS to check it. Nothing leaves your phone.")
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Paste message text") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { report = classifier.classify(text.trim()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check")
            }
            report?.let { SmishingResult(it) }

            HorizontalDivider()

            Text("Auto-scan incoming SMS (optional)", fontWeight = FontWeight.Bold)
            Text(
                "Warns you automatically when a scam-looking SMS arrives. Needs SMS permission; " +
                    "Google Play restricts this, so it may be unavailable on Play Store builds."
            )
            OutlinedButton(
                onClick = { smsPermission.launch(Manifest.permission.RECEIVE_SMS) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enable SMS auto-scan")
            }
        }
    }
}

@Composable
private fun SmishingResult(report: SmishingReport) {
    val color = when (report.level) {
        SmishingLevel.CRITICAL, SmishingLevel.HIGH -> Color(0xFFB00020)
        SmishingLevel.MEDIUM -> Color(0xFFB36B00)
        SmishingLevel.LOW -> Color(0xFF1B5E20)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${report.level} risk  (score ${report.score})",
                color = color,
                fontWeight = FontWeight.Bold,
            )
            if (report.signals.isEmpty()) {
                Text("No scam signals found. Still be cautious with unexpected messages.")
            } else {
                report.signals.forEach { Text("• ${it.message}") }
            }
        }
    }
}
