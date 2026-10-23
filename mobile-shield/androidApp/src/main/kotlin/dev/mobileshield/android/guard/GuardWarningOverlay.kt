package dev.mobileshield.android.guard

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.mobileshield.core.guard.GuardAction
import dev.mobileshield.core.guard.GuardDecision

/**
 * Draws a full-width warning banner over whatever app is in the foreground when
 * the guard flags a payment moment — the same "stop before you pay" intervention
 * Google ships, done with a standard application overlay. Needs the
 * SYSTEM_ALERT_WINDOW ("display over other apps") grant; [canDraw] checks it.
 */
object GuardWarningOverlay {

    private var view: View? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, decision: GuardDecision) {
        if (!canDraw(context) || view != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val critical = decision.action == GuardAction.BLOCK_RECOMMENDED
        val bg = if (critical) Color.parseColor("#B00020") else Color.parseColor("#B36B00")

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(context).apply {
            text = if (critical) "⚠ Likely scam — do not pay" else "⚠ Check before you pay"
            setTextColor(Color.WHITE)
            textSize = 20f
        })
        root.addView(TextView(context).apply {
            text = decision.headline
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 24, 0, 24)
        })
        root.addView(Button(context).apply {
            text = "Dismiss"
            setOnClickListener { dismiss(context) }
        })

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        view = root
        wm.addView(root, params)
    }

    fun dismiss(context: Context) {
        val current = view ?: return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeView(current)
        } catch (_: Exception) {
        }
        view = null
    }
}
