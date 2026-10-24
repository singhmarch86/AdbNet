package dev.mobileshield.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.mobileshield.android.data.DeviceCheckup
import dev.mobileshield.core.apk.ApkRiskLevel
import dev.mobileshield.core.apk.ApkRiskReport
import dev.mobileshield.core.apk.ApkScanner
import dev.mobileshield.core.hygiene.AppFinding
import dev.mobileshield.core.hygiene.DeviceHygieneAnalyzer
import dev.mobileshield.core.hygiene.HygieneLevel
import dev.mobileshield.core.hygiene.HygieneReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Checkup" tab: runs the shared hygiene + APK analyzers over the real device
 * and shows what they flag. Data gathering is on an IO dispatcher; the scoring
 * itself is the pure `core` logic.
 */
@Composable
fun CheckupTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(false) }
    var hygiene by remember { mutableStateOf<HygieneReport?>(null) }
    var apks by remember { mutableStateOf<List<ApkRiskReport>?>(null) }

    val doScan = {
        scanning = true
        scope.launch {
            val apps = withContext(Dispatchers.IO) { DeviceCheckup.collectApps(context) }
            val downloads = withContext(Dispatchers.IO) { DeviceCheckup.collectDownloadedApks(context) }
            hygiene = DeviceHygieneAnalyzer().analyze(apps)
            apks = ApkScanner().scanAll(downloads).filter { it.isApk }
            scanning = false
        }
        Unit
    }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { doScan() } // proceed whether or not it was granted; scan is simply narrower without it

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Device checkup", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Scans your installed apps and downloaded APK files for the signs used in " +
                "remote-control and UPI scams. Nothing leaves your phone."
        )
        Button(
            enabled = !scanning,
            onClick = {
                if (Build.VERSION.SDK_INT <= 32 && !hasReadStorage(context)) {
                    storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                } else {
                    doScan()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (scanning) "Scanning…" else "Scan this device")
        }

        if (scanning) CircularProgressIndicator()

        hygiene?.let { HygieneReportView(it) }
        apks?.let { ApkReportsView(it) }

        MessageCheckCard()
    }
}

@Composable
private fun HygieneReportView(report: HygieneReport) {
    val color = hygieneColor(report.level)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Installed apps", style = MaterialTheme.typography.titleLarge)
            Text(
                "${report.level} risk  (score ${report.score})",
                color = color,
                fontWeight = FontWeight.Bold,
            )
            if (report.findings.isEmpty()) {
                Text("No risky apps found.")
            } else {
                report.findings.forEach { f: AppFinding -> Text("• ${f.message}") }
            }
        }
    }
}

@Composable
private fun ApkReportsView(reports: List<ApkRiskReport>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Downloaded APK files", style = MaterialTheme.typography.titleLarge)
            if (reports.isEmpty()) {
                Text("No APK files found in Downloads.")
                return@Column
            }
            reports.forEach { r ->
                val color = apkColor(r.level)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.fileName, fontWeight = FontWeight.Bold)
                    Text("${r.level} risk  (score ${r.score})", color = color)
                    r.findings.forEach { Text("• ${it.message}") }
                }
            }
        }
    }
}

private fun hygieneColor(level: HygieneLevel): Color = when (level) {
    HygieneLevel.CRITICAL, HygieneLevel.HIGH -> Color(0xFFB00020)
    HygieneLevel.MEDIUM -> Color(0xFFB36B00)
    HygieneLevel.LOW -> Color(0xFF1B5E20)
}

private fun apkColor(level: ApkRiskLevel): Color = when (level) {
    ApkRiskLevel.CRITICAL, ApkRiskLevel.HIGH -> Color(0xFFB00020)
    ApkRiskLevel.MEDIUM -> Color(0xFFB36B00)
    ApkRiskLevel.LOW -> Color(0xFF1B5E20)
}

private fun hasReadStorage(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
