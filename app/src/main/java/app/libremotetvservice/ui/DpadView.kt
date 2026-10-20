package app.libremotetvservice.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import app.libremotetvservice.R
import app.libremotetvservice.protocol.KeyCodes
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * A round D-pad: four arrows around an OK button. A tap presses a key; holding sends key-down until release,
 * so the TV long-presses OK and repeats arrows exactly as with its own remote.
 */
class DpadView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Pad(val keyCode: Int, val label: Int, val startAngle: Float) {
        RIGHT(KeyCodes.DPAD_RIGHT, R.string.key_right, -45f),
        DOWN(KeyCodes.DPAD_DOWN, R.string.key_down, 45f),
        LEFT(KeyCodes.DPAD_LEFT, R.string.key_left, 135f),
        UP(KeyCodes.DPAD_UP, R.string.key_up, 225f),
        CENTER(KeyCodes.DPAD_CENTER, R.string.key_ok, 0f),
    }

    interface Listener {
        fun onClick(keyCode: Int)
        fun onHold(keyCode: Int)
        fun onRelease(keyCode: Int)
    }

    var listener: Listener? = null

    private val ring = paint(R.color.surface)
    private val center = paint(R.color.accent_container)
    private val highlight = paint(R.color.accent)
    private val gap = paint(R.color.bg).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(4f) }
    private val gapFill = paint(R.color.bg)
    private val arrow = paint(R.color.on_surface_muted).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = paint(R.color.on_accent_container).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(18f)
        isFakeBoldText = true
    }

    private var cx = 0f
    private var cy = 0f
    private var outer = 0f
    private var inner = 0f
    private val sector = Path()
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val chevron = Path()

    private var pressed: Pad? = null

    /** The pad still glowing; it fades out after [pressed] is already cleared. */
    private var glowPad: Pad? = null
    private var held = false
    private var glow = 0f
    private var glowAnimator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val holdStart = Runnable {
        val pad = pressed ?: return@Runnable
        held = true
        Haptics.long(this)
        listener?.onHold(pad.keyCode)
    }

    init {
        isClickable = true
        // A touch surface: TalkBack reaches each pad through the virtual views below, not keyboard focus.
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = context.getColor(color) }

    /** Fills the space it is given and draws the largest circle that fits, up to [MAX_DP] across. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = context.dp(MAX_DP).toInt()
        fun size(spec: Int) = when (MeasureSpec.getMode(spec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
            MeasureSpec.AT_MOST -> minOf(MeasureSpec.getSize(spec), max)
            // Asked how tall it would like to be (in a scrolling parent): no wish, the container sets the minimum.
            else -> 0
        }
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        outer = minOf(min(w, h) / 2f, context.dp(MAX_DP) / 2f) - context.dp(2f)
        inner = outer * 0.42f
        outerRect.set(cx - outer, cy - outer, cx + outer, cy + outer)
        innerRect.set(cx - inner, cy - inner, cx + inner, cy + inner)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, outer, ring)
        val down = glowPad
        if (down != null && down != Pad.CENTER && glow > 0f) {
            highlight.alpha = (glow * 70).toInt()
            sectorPath(down)
            canvas.drawPath(sector, highlight)
        }
        // Separators between the four arrows.
        for (angle in 0 until 4) {
            canvas.save()
            canvas.rotate(45f + angle * 90f, cx, cy)
            canvas.drawLine(cx + inner, cy, cx + outer + 2, cy, gap)
            canvas.restore()
        }
        for (pad in Pad.entries) {
            if (pad == Pad.CENTER) continue
            arrow.color = context.getColor(if (pad == down) R.color.accent else R.color.on_surface_muted)
            drawChevron(canvas, pad)
        }
        canvas.drawCircle(cx, cy, inner, gapFill)
        val centerRadius = inner - context.dp(4f)
        canvas.drawCircle(cx, cy, centerRadius, center)
        if (down == Pad.CENTER && glow > 0f) {
            highlight.alpha = (glow * 90).toInt()
            canvas.drawCircle(cx, cy, centerRadius, highlight)
        }
        canvas.drawText(context.getString(R.string.key_ok), cx, cy - (label.descent() + label.ascent()) / 2, label)
    }

    private fun sectorPath(pad: Pad) {
        sector.reset()
        sector.arcTo(outerRect, pad.startAngle, 90f, true)
        sector.arcTo(innerRect, pad.startAngle + 90f, -90f, false)
        sector.close()
    }

    private fun drawChevron(canvas: Canvas, pad: Pad) {
        val size = context.dp(9f)
        val distance = (outer + inner) / 2f
        chevron.reset()
        // An upward chevron at the origin, rotated into place.
        chevron.moveTo(-size, size / 2)
        chevron.lineTo(0f, -size / 2)
        chevron.lineTo(size, size / 2)
        val rotation = when (pad) {
            Pad.UP -> 0f
            Pad.RIGHT -> 90f
            Pad.DOWN -> 180f
            Pad.LEFT -> 270f
            Pad.CENTER -> return
        }
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotation)
        canvas.translate(0f, -distance)
        canvas.drawPath(chevron, arrow)
        canvas.restore()
    }

    private fun padAt(x: Float, y: Float): Pad? {
        val dx = x - cx
        val dy = y - cy
        val distance = hypot(dx, dy)
        if (distance > outer * 1.08f) return null
        if (distance <= inner) return Pad.CENTER
        val angle = Math.toDegrees(atan2(dy, dx).toDouble())
        return when {
            angle >= -45 && angle < 45 -> Pad.RIGHT
            angle >= 45 && angle < 135 -> Pad.DOWN
            angle >= -135 && angle < -45 -> Pad.UP
            else -> Pad.LEFT
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pad = padAt(event.x, event.y) ?: return false
                // Inside the scrolling remote (small screens), a drag that starts on the pad stays on the pad.
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = pad
                glowPad = pad
                held = false
                Haptics.tap(this)
                animateGlow(1f)
                handler.postDelayed(holdStart, KeyTouch.HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pad = pressed ?: return false
                // Sliding off a button cancels it, as with any button; a key already held stays held until release.
                if (!held && padAt(event.x, event.y) != pad) {
                    handler.removeCallbacks(holdStart)
                    endPress()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val pad = pressed ?: return false
                handler.removeCallbacks(holdStart)
                if (held) listener?.onRelease(pad.keyCode) else {
                    listener?.onClick(pad.keyCode)
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
                endPress()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val pad = pressed ?: return false
                handler.removeCallbacks(holdStart)
                if (held) listener?.onRelease(pad.keyCode)
                endPress()
                return true
            }
        }
        return pressed != null
    }

    private fun endPress() {
        held = false
        pressed = null
        animateGlow(0f) { glowPad = null }
    }

    private fun animateGlow(target: Float, end: (() -> Unit)? = null) {
        glowAnimator?.cancel()
        glowAnimator = ValueAnimator.ofFloat(glow, target).apply {
            duration = if (target > glow) 60 else 220
            addUpdateListener {
                glow = it.animatedValue as Float
                invalidate()
            }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (glow == 0f) end()
                    invalidate()
                }
            })
            start()
        }
    }

    // Accessibility: each arrow and OK is a separate button for TalkBack and switch access.

    private var hovered = HOST

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (!manager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
        val id = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_EXIT -> HOST
            else -> padAt(event.x, event.y)?.ordinal ?: HOST
        }
        if (id != hovered) {
            if (id != HOST) sendVirtualEvent(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (hovered != HOST) sendVirtualEvent(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            hovered = id
        }
        return true
    }

    private fun sendVirtualEvent(id: Int, type: Int) {
        // The public constructor is Android 11+; obtain() is the only way before that.
        val event = if (Build.VERSION.SDK_INT >= 30) AccessibilityEvent(type) else @Suppress("DEPRECATION") AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = Button::class.java.name
        event.contentDescription = context.getString(Pad.entries[id].label)
        event.setSource(this, id)
        parent?.requestSendAccessibilityEvent(this, event)
    }

    private fun bounds(pad: Pad): Rect {
        val third = outer * 2 / 3
        val left = cx - outer
        val top = cy - outer
        val (col, row) = when (pad) {
            Pad.UP -> 1 to 0
            Pad.LEFT -> 0 to 1
            Pad.CENTER -> 1 to 1
            Pad.RIGHT -> 2 to 1
            Pad.DOWN -> 1 to 2
        }
        return Rect((left + col * third).toInt(), (top + row * third).toInt(), (left + (col + 1) * third).toInt(), (top + (row + 1) * third).toInt())
    }

    @Suppress("DEPRECATION")
    private val accessibility = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST) {
                val info = AccessibilityNodeInfo.obtain(this@DpadView)
                onInitializeAccessibilityNodeInfo(info)
                // Reading order, as the pad is laid out: Up, then Left, OK, Right, then Down.
                listOf(Pad.UP, Pad.LEFT, Pad.CENTER, Pad.RIGHT, Pad.DOWN).forEach { info.addChild(this@DpadView, it.ordinal) }
                return info
            }
            val pad = Pad.entries.getOrNull(virtualViewId) ?: return null
            val info = AccessibilityNodeInfo.obtain(this@DpadView, virtualViewId)
            info.packageName = context.packageName
            info.className = Button::class.java.name
            info.contentDescription = context.getString(pad.label)
            info.setParent(this@DpadView)
            info.isEnabled = isEnabled
            info.isClickable = true
            info.isFocusable = true
            info.isVisibleToUser = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            val rect = bounds(pad)
            info.setBoundsInParent(rect)
            val location = IntArray(2).also(::getLocationOnScreen)
            rect.offset(location[0], location[1])
            info.setBoundsInScreen(rect)
            return info
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == HOST) return performAccessibilityAction(action, arguments)
            val pad = Pad.entries.getOrNull(virtualViewId) ?: return false
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            listener?.onClick(pad.keyCode)
            sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    private companion object {
        const val HOST = AccessibilityNodeProvider.HOST_VIEW_ID
        const val MAX_DP = 320f
    }
}
