package dev.hotword.android.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * An opt-in, genuinely visible status dot while listening. Android 15 requires a
 * visible overlay (not merely SYSTEM_ALERT_WINDOW permission) for background
 * activity launch eligibility. Does not capture taps or screen contents.
 *
 * TYPE_APPLICATION_OVERLAY is BELOW the lock screen and other secure system UI:
 * this can help on Home / inside other apps but is not a lock-screen guarantee.
 */
internal class AssistantOverlay(private val context: Context) {
    // Since API 30 WindowManager should come from a window context, not
    // an arbitrary Service/application context (important for Android 15 OEMs).
    private val windowContext: Context = if (Build.VERSION.SDK_INT >= 30)
        context.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    else context
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private var dot: View? = null

    fun sync() {
        if (!Settings.canDrawOverlays(context)) {
            remove()
            return
        }
        if (dot != null) return
        val size = (12 * context.resources.displayMetrics.density).toInt().coerceAtLeast(12)
        val view = View(windowContext).apply {
            contentDescription = "Hotword active"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(210, 53, 143, 120))
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (8 * context.resources.displayMetrics.density).toInt()
        }
        runCatching {
            windowManager.addView(view, params)
            dot = view
        }.onFailure { Log.w("HotwordOverlay", "Overlay not available", it) }
    }

    fun remove() {
        dot?.let { view ->
            runCatching { windowManager.removeView(view) }
            dot = null
        }
    }

    val isAttached: Boolean get() = dot?.isAttachedToWindow == true
}
