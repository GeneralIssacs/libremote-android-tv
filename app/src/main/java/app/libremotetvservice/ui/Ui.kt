package app.libremotetvservice.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import app.libremotetvservice.libremote

fun Context.dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.isNight() =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * Draws behind the system bars (Android 15 does this anyway) and pads [root] by the bars and the keyboard, so
 * content stays clear of them on every version.
 */
@Suppress("DEPRECATION")
fun Activity.edgeToEdge(root: View) {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        val light = if (isNight()) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        window.insetsController?.setSystemBarsAppearance(
            light,
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
    } else {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (!isNight()) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        window.decorView.systemUiVisibility = flags
    }
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
    root.setOnApplyWindowInsetsListener { view, insets ->
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            left = bars.left; top = bars.top; right = bars.right; bottom = maxOf(bars.bottom, ime.bottom)
        } else {
            left = insets.systemWindowInsetLeft; top = insets.systemWindowInsetTop
            right = insets.systemWindowInsetRight; bottom = insets.systemWindowInsetBottom
        }
        view.setPadding(base[0] + left, base[1] + top, base[2] + right, base[3] + bottom)
        insets
    }
    root.requestApplyInsets()
}

/** Haptic feedback that follows the app's setting and works without the VIBRATE permission. */
object Haptics {
    fun tap(view: View) = perform(view, HapticFeedbackConstants.VIRTUAL_KEY)

    fun tick(view: View) = perform(view, HapticFeedbackConstants.CLOCK_TICK)

    fun long(view: View) = perform(view, HapticFeedbackConstants.LONG_PRESS)

    private fun perform(view: View, feedback: Int) {
        if (view.context.libremote.settings.haptics) view.performHapticFeedback(feedback)
    }
}

/**
 * Makes [view] behave like a button on a real remote. A tap is a click (so TalkBack and keyboards work too);
 * holding past the long-press delay calls [onHold] and then [onRelease] when the finger lifts, or, for buttons
 * that should repeat (volume), calls [onRepeat] every [REPEAT_MS] instead.
 */
class KeyTouch(
    private val view: View,
    private val onClick: () -> Unit,
    private val onHold: (() -> Unit)? = null,
    private val onRelease: (() -> Unit)? = null,
    private val onRepeat: (() -> Unit)? = null,
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private var cancelled = false

    private val holdStart = Runnable {
        held = true
        // Once held, the button keeps the gesture even if the remote around it can scroll (small screens).
        view.parent?.requestDisallowInterceptTouchEvent(true)
        if (onRepeat != null) {
            Haptics.tick(view)
            repeat.run()
        } else {
            Haptics.long(view)
            onHold?.invoke()
        }
    }

    private val repeat: Runnable = object : Runnable {
        override fun run() {
            onRepeat?.invoke()
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    init {
        view.setOnClickListener {
            Haptics.tap(view)
            onClick()
        }
        if (onHold != null || onRepeat != null) view.setOnTouchListener(this)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = false
                cancelled = false
                v.isPressed = true
                handler.postDelayed(holdStart, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding off the button cancels a tap; a hold or repeat in progress continues until release.
                val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                val outside = event.x < -slop || event.y < -slop || event.x > v.width + slop || event.y > v.height + slop
                if (outside && !held && !cancelled) {
                    cancelled = true
                    handler.removeCallbacks(holdStart)
                    v.isPressed = false
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdStart)
                handler.removeCallbacks(repeat)
                v.isPressed = false
                if (held) onRelease?.invoke() else if (!cancelled) v.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdStart)
                handler.removeCallbacks(repeat)
                v.isPressed = false
                if (held) onRelease?.invoke()
            }
        }
        return true
    }

    companion object {
        /** Shorter than the system long-press timeout: TVs start their own long-press timing on key-down. */
        val HOLD_MS = (ViewConfiguration.getLongPressTimeout() * 0.6).toLong()
        const val REPEAT_MS = 140L
    }
}
