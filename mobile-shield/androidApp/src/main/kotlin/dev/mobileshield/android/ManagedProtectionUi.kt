package dev.mobileshield.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mobileshield.android.policy.PolicyEnforcer
import dev.mobileshield.core.policy.EnforcementPolicyEngine
import dev.mobileshield.core.policy.PolicyContext
import dev.mobileshield.core.policy.ProtectionProfile

/**
 * "Managed protection" card: shows what the current protection profile would
 * lock down, whether it can actually be enforced (device owner) or only warned,
 * and lets a device owner apply it. Uses the shared EnforcementPolicyEngine for
 * the decision and PolicyEnforcer to execute.
 */
@Composable
fun ManagedProtectionCard(profile: ProtectionProfile = ProtectionProfile.CHILD) {
    val context = LocalContext.current
    val enforcer = remember { PolicyEnforcer(context) }
    val isOwner = remember { enforcer.isDeviceOwner() }
    val plan = remember(isOwner, profile) {
        EnforcementPolicyEngine().plan(PolicyContext(isDeviceOwner = isOwner, profile = profile))
    }
    var log by remember { mutableStateOf<List<String>?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Managed protection", style = MaterialTheme.typography.titleLarge)
            Text(
                if (isOwner)
                    "This device is managed by Mobile Shield (device owner). The protections below " +
                        "are enforced and cannot be turned off from the device."
                else
                    "Full enforcement needs Mobile Shield set as device owner (a managed or child-device " +
                        "setup). Without it, the items below are warnings only.",
                fontWeight = FontWeight.Medium,
            )

            if (plan.decisions.isEmpty()) {
                Text("This profile does not lock anything down; protection is advisory.")
            } else {
                plan.decisions.forEach { d ->
                    Text((if (d.enabled) "✓ " else "• ") + d.rationale)
                }
            }

            if (isOwner && plan.decisions.any { it.enabled }) {
                Button(onClick = { log = enforcer.apply(plan) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply protections")
                }
            }
            log?.forEach { Text(it) }
        }
    }
}
