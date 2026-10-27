package dev.mobileshield.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.mobileshield.core.upi.RiskLevel
import dev.mobileshield.core.upi.UpiLinkAnalyzer
import dev.mobileshield.core.upi.UpiRiskReport

/**
 * Single-screen UI: a toggle for the DNS content filter, and a UPI link
 * checker that runs the shared [UpiLinkAnalyzer]. A UPI link shared into the
 * app (ACTION_SEND text/plain) pre-fills the checker.
 */
class MainActivity : ComponentActivity() {

    private val analyzer = UpiLinkAnalyzer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = sharedText(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(analyzer = analyzer, initialLink = shared)
                }
            }
        }
    }

    private fun sharedText(intent: Intent?): String = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        Intent.ACTION_VIEW -> intent.dataString.orEmpty()
        else -> ""
    }
}

@Composable
private fun App(analyzer: UpiLinkAnalyzer, initialLink: String) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Protect") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Checkup") })
        }
        when (tab) {
            0 -> ProtectTab(analyzer = analyzer, initialLink = initialLink)
            else -> CheckupTab()
        }
    }
}

@Composable
private fun ProtectTab(analyzer: UpiLinkAnalyzer, initialLink: String) {
    val context = LocalContext.current
    var filtering by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(initialLink) }
    var report by remember { mutableStateOf<UpiRiskReport?>(null) }
    var showScanner by remember { mutableStateOf(false) }

    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startFilter(context)
            filtering = true
        }
    }

    if (showScanner) {
        QrScannerScreen(onClose = { showScanner = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Mobile Shield", style = MaterialTheme.typography.headlineMedium)

        // --- Web content filter -------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Content filter", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (filtering)
                        "Filtering is ON. Adult content and malware are blocked via a family-safe DNS " +
                            "resolver, and SafeSearch is enforced."
                    else
                        "Blocks adult content and malware using a family-safe DNS resolver " +
                            "(Cloudflare for Families), forces SafeSearch, and closes the DNS-over-HTTPS " +
                            "bypass. Only DNS lookups are checked — your page content and browsing never " +
                            "leave the phone."
                )
                Button(
                    onClick = {
                        if (filtering) {
                            stopFilter(context)
                            filtering = false
                        } else {
                            val prepare = VpnService.prepare(context)
                            if (prepare != null) {
                                vpnConsent.launch(prepare)
                            } else {
                                startFilter(context)
                                filtering = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (filtering) "Turn off filter" else "Turn on filter")
                }
            }
        }

        // --- UPI link checker ---------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Check a UPI link", style = MaterialTheme.typography.titleLarge)
                Text("Paste a upi:// link or QR text to check it before you pay.")
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("upi://pay?...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { report = analyzer.analyze(link.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Check")
                }
                OutlinedButton(
                    onClick = { showScanner = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Scan a QR code")
                }
                report?.let { RiskResult(it) }
            }
        }

        // --- Live payment protection --------------------------------------
        PaymentGuardCard()
    }
}

@Composable
private fun RiskResult(report: UpiRiskReport) {
    val color = when (report.level) {
        RiskLevel.CRITICAL, RiskLevel.HIGH -> Color(0xFFB00020)
        RiskLevel.MEDIUM -> Color(0xFFB36B00)
        RiskLevel.LOW -> Color(0xFF1B5E20)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!report.isUpiLink) {
                Text("Not a UPI link.", fontWeight = FontWeight.Bold)
                return@Column
            }
            Text(
                "${report.level} risk  (score ${report.score})",
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            if (report.flags.isEmpty()) {
                Text("No risk signals found. Still confirm the payee before paying.")
            } else {
                report.flags.forEach { flag -> Text("• ${flag.message}") }
            }
        }
    }
}

private fun startFilter(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, ShieldVpnService::class.java),
    )
}

private fun stopFilter(context: Context) {
    context.startService(
        Intent(context, ShieldVpnService::class.java).apply { action = ShieldVpnService.ACTION_STOP }
    )
}
