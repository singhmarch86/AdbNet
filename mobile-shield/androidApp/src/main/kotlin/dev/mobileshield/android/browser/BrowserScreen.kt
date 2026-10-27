package dev.mobileshield.android.browser

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A minimal in-app "safe browser": a WebView whose images are filtered pixel-by
 * pixel by the on-device [NsfwClassifier] via [FilteredWebViewClient]. The DNS
 * family filter (if on) still applies to it too, so this adds content-level
 * filtering on top of domain-level filtering.
 */
@Composable
fun BrowserScreen() {
    val context = LocalContext.current
    val classifier = remember { TfliteNsfwClassifier(context) }
    var address by remember { mutableStateOf("https://www.google.com") }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = FilteredWebViewClient(classifier)
            loadUrl(address)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!classifier.isAvailable()) {
            Surface(color = Color(0xFFFFF3CD)) {
                Text(
                    "Image content filtering is OFF — no NSFW model installed. Add " +
                        "assets/nsfw.tflite to enable it. Domain filtering still applies when the " +
                        "content filter (Protect tab) is on.",
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                label = { Text("Address") },
                modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { webView.loadUrl(normalize(address)) }),
            )
            Button(onClick = { webView.loadUrl(normalize(address)) }) { Text("Go") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { if (webView.canGoBack()) webView.goBack() }) { Text("Back") }
            Button(onClick = { webView.reload() }) { Text("Reload") }
        }

        AndroidView(
            factory = { webView },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

private fun normalize(input: String): String {
    val s = input.trim()
    return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
}
