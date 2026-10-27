package dev.mobileshield.android.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A WebViewClient that runs every loaded image through an on-device
 * [NsfwClassifier] and replaces explicit ones with a solid placeholder — real,
 * content-based (pixel-level) filtering, which DNS filtering cannot do.
 *
 * It only intercepts image resource requests: it re-fetches each image, decodes
 * and classifies it, and returns either the original bytes or a blocked
 * placeholder. `shouldInterceptRequest` runs off the UI thread, so the network
 * fetch + inference here are fine.
 *
 * Known limits (honest): only `<img>`-style resources are covered — CSS
 * background images, `<canvas>`, and video frames are not; the re-fetch does
 * not carry the page's cookies, so some authenticated images may not load; and
 * accuracy is only as good as the installed model. When no model is installed
 * ([NsfwClassifier.isAvailable] is false) nothing is intercepted.
 */
class FilteredWebViewClient(
    private val classifier: NsfwClassifier,
    private val threshold: Float = 0.7f,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!classifier.isAvailable()) return null
        val url = request.url?.toString() ?: return null
        if (!looksLikeImage(url, request)) return null

        return try {
            val bytes = fetch(url) ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return imageResponse(bytes, url)
            if (classifier.nsfwScore(bitmap) >= threshold) {
                blockedResponse(bitmap.width, bitmap.height)
            } else {
                imageResponse(bytes, url)
            }
        } catch (_: Exception) {
            null // fall back to the WebView's own loading
        }
    }

    private fun looksLikeImage(url: String, request: WebResourceRequest): Boolean {
        val path = url.substringBefore('?').lowercase()
        if (IMAGE_EXTENSIONS.any { path.endsWith(it) }) return true
        val accept = request.requestHeaders["Accept"]?.lowercase().orEmpty()
        return accept.contains("image/")
    }

    private fun fetch(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MobileShield-SafeBrowser")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun imageResponse(bytes: ByteArray, url: String): WebResourceResponse {
        val mime = when {
            url.contains(".png", true) -> "image/png"
            url.contains(".gif", true) -> "image/gif"
            url.contains(".webp", true) -> "image/webp"
            else -> "image/jpeg"
        }
        return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
    }

    private fun blockedResponse(width: Int, height: Int): WebResourceResponse {
        val w = width.coerceIn(1, 1024)
        val h = height.coerceIn(1, 1024)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(230, 230, 230))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return WebResourceResponse("image/png", null, ByteArrayInputStream(out.toByteArray()))
    }

    companion object {
        private val IMAGE_EXTENSIONS =
            listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
    }
}
