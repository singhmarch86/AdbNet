package dev.mobileshield.android.guard

import android.content.Context
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.Executor

/**
 * Tracks whether a phone call is active, using the modern [TelephonyCallback]
 * on API 31+ and the legacy [android.telephony.PhoneStateListener] below that.
 * Requires the READ_PHONE_STATE permission.
 */
class CallStateMonitor(context: Context) {

    @Volatile
    var isCallActive: Boolean = false
        private set

    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    // Touched only on the API 31+ path; lazy so API < 28 never calls getMainExecutor().
    private val mainExecutor: Executor by lazy { context.mainExecutor }

    private var modernCallback: TelephonyCallback? = null

    @Suppress("DEPRECATION")
    private var legacyListener: android.telephony.PhoneStateListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    isCallActive = state != TelephonyManager.CALL_STATE_IDLE
                }
            }
            modernCallback = cb
            telephony.registerTelephonyCallback(mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : android.telephony.PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    isCallActive = state != TelephonyManager.CALL_STATE_IDLE
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            telephony.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let { telephony.unregisterTelephonyCallback(it) }
            modernCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { telephony.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE) }
            legacyListener = null
        }
        isCallActive = false
    }
}
