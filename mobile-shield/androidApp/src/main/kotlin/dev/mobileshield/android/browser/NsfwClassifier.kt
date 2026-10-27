package dev.mobileshield.android.browser

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Classifies an image as explicit (NSFW) on-device. */
interface NsfwClassifier {
    /** True when a model is loaded and classification can run. */
    fun isAvailable(): Boolean

    /** Returns an NSFW probability in 0..1 (0 when unavailable). */
    fun nsfwScore(bitmap: Bitmap): Float
}

/**
 * TensorFlow Lite NSFW classifier. Loads `assets/nsfw.tflite` and expects the
 * common GantMan `nsfw_model` output layout — 5 softmax classes in the order
 * [drawings, hentai, neutral, porn, sexy], 224x224x3 float input normalized to
 * 0..1. The NSFW score is hentai + porn + sexy.
 *
 * The model is NOT bundled (sourcing/licensing it is a deployment decision).
 * When the asset is absent, [isAvailable] is false and the browser degrades to
 * no image filtering (the DNS family filter still covers whole domains). Drop a
 * compatible `nsfw.tflite` into `androidApp/src/main/assets/` to enable it; see
 * that folder's README.
 */
class TfliteNsfwClassifier(context: Context) : NsfwClassifier {

    private val interpreter: Interpreter? = runCatching {
        Interpreter(loadModel(context))
    }.getOrNull()

    override fun isAvailable(): Boolean = interpreter != null

    override fun nsfwScore(bitmap: Bitmap): Float {
        val itp = interpreter ?: return 0f
        val output = Array(1) { FloatArray(NUM_CLASSES) }
        return try {
            itp.run(preprocess(bitmap), output)
            val p = output[0]
            (p[1] + p[3] + p[4]).coerceIn(0f, 1f) // hentai + porn + sexy
        } catch (_: Exception) {
            0f
        }
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val buffer = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(SIZE * SIZE)
        resized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun loadModel(context: Context): ByteBuffer {
        context.assets.openFd(MODEL_ASSET).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    companion object {
        private const val MODEL_ASSET = "nsfw.tflite"
        private const val SIZE = 224
        private const val NUM_CLASSES = 5
    }
}
