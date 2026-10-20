package app.libremotetvservice.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import app.libremotetvservice.R
import app.libremotetvservice.protocol.KeyCodes
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A swipe surface: dragging moves the TV's focus one step per [STEP_DP] in the drag's direction, a quick fling adds
 * a few more steps, a tap is OK and holding long-presses OK. Each step ticks, so moves can be counted by feel.
 */
class TouchpadView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Reuses the D-pad's callbacks: clicks for steps and taps, hold/release for a long press of OK. */
    var listener: DpadView.Listener? = null

    /**
     * The swipe strip under Full mode's D-pad: a short, wide touchpad with shorter steps (so a vertical swipe still
     * moves), a one-line hint in the middle and arrows close to its edges.
     */
    var compact = false
        set(value) {
            field = value
            stepPx = context.dp(if (value) STRIP_STEP_DP else STEP_DP)
            contentDescription = context.getString(if (value) R.string.strip else R.string.mode_touchpad)
            requestLayout()
        }

    private var stepPx = context.dp(STEP_DP)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val handler = Handler(Looper.getMainLooper())

    private val surface = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
    private val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = context.dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.on_surface_muted)
        textSize = context.dp(14f)
    }
    private var hint: StaticLayout? = null
    private val bounds = RectF()
    private val chevron = Path()

    private var downX = 0f
    private var downY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var touchX = -1f
    private var touchY = -1f
    private var dragging = false
    private var holding = false
    private var velocity: VelocityTracker? = null

    /** Direction of the last step and how visible its arrow still is. */
    private var flashKey = 0
    private var flash = 0f
    private var flashAnimator: ValueAnimator? = null

    private val holdStart = Runnable {
        if (dragging) return@Runnable
        holding = true
        Haptics.long(this)
        listener?.onHold(KeyCodes.DPAD_CENTER)
        invalidate()
    }

    init {
        isClickable = true
        isFocusable = false
        contentDescription = context.getString(R.string.mode_touchpad)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        val width = (w - context.dp(48f)).toInt().coerceAtLeast(1)
        val line = context.getString(if (compact) R.string.strip_hint else R.string.touchpad_hint)
        // Too long for one line (the touchpad between the columns is narrow): one tip per line, never one split.
        val text = if (!compact && hintPaint.measureText(line) > width) line.replace(" · ", "\n") else line
        hint = StaticLayout.Builder.obtain(text, 0, text.length, hintPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(if (compact) 1 else Int.MAX_VALUE)
            .setEllipsize(if (compact) android.text.TextUtils.TruncateAt.END else null)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(context.dp(32f), height / 2f)
        canvas.drawRoundRect(bounds, radius, radius, surface)
        hint?.let {
            // The strip's hint sits in its middle, the touchpad's at the bottom, clear of the finger.
            val top = if (compact) (height - it.height) / 2f else height - it.height - context.dp(20f)
            canvas.save()
            canvas.translate((width - it.width) / 2f, top)
            it.draw(canvas)
            canvas.restore()
        }
        if (touchX >= 0) {
            finger.alpha = if (holding) 110 else 60
            val size = if (compact) minOf(context.dp(28f), height / 2f) else context.dp(36f)
            canvas.drawCircle(touchX, touchY, if (holding) size * 1.2f else size, finger)
        }
        if (flash > 0f) {
            arrow.alpha = (flash * 255).toInt()
            drawArrow(canvas)
        }
    }

    private fun drawArrow(canvas: Canvas) {
        val size = context.dp(if (compact) 10f else 14f)
        val inset = context.dp(if (compact) 14f else 28f)
        // The touchpad's down arrow stays above its hint; the strip's hint is in the middle, out of the way.
        val bottom = if (compact) inset else inset + context.dp(40f)
        val (x, y, rotation) = when (flashKey) {
            KeyCodes.DPAD_UP -> Triple(width / 2f, inset, 0f)
            KeyCodes.DPAD_RIGHT -> Triple(width - inset, height / 2f, 90f)
            KeyCodes.DPAD_DOWN -> Triple(width / 2f, height - bottom, 180f)
            else -> Triple(inset, height / 2f, 270f)
        }
        chevron.reset()
        chevron.moveTo(-size, size / 2)
        chevron.lineTo(0f, -size / 2)
        chevron.lineTo(size, size / 2)
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.drawPath(chevron, arrow)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y
                anchorX = event.x; anchorY = event.y
                touchX = event.x; touchY = event.y
                dragging = false
                holding = false
                handler.removeCallbacks(fling)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
                handler.postDelayed(holdStart, ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                touchX = event.x; touchY = event.y
                if (!holding && !dragging && hypot(event.x - downX, event.y - downY) > slop) {
                    dragging = true
                    handler.removeCallbacks(holdStart)
                }
                if (dragging) stepTowards(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdStart)
                velocity?.addMovement(event)
                when {
                    holding -> listener?.onRelease(KeyCodes.DPAD_CENTER)
                    dragging -> velocity?.let(::flingSteps)
                    else -> {
                        Haptics.tap(this)
                        listener?.onClick(KeyCodes.DPAD_CENTER)
                    }
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdStart)
                if (holding) listener?.onRelease(KeyCodes.DPAD_CENTER)
                endTouch()
            }
        }
        return true
    }

    private fun endTouch() {
        holding = false
        dragging = false
        touchX = -1f
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    /** Sends one step per [stepPx] of travel along the dominant axis since the last step. */
    private fun stepTowards(x: Float, y: Float) {
        while (true) {
            val dx = x - anchorX
            val dy = y - anchorY
            if (abs(dx) < stepPx && abs(dy) < stepPx) return
            if (abs(dx) >= abs(dy)) {
                step(if (dx > 0) KeyCodes.DPAD_RIGHT else KeyCodes.DPAD_LEFT)
                anchorX += if (dx > 0) stepPx else -stepPx
                anchorY = y
            } else {
                step(if (dy > 0) KeyCodes.DPAD_DOWN else KeyCodes.DPAD_UP)
                anchorY += if (dy > 0) stepPx else -stepPx
                anchorX = x
            }
        }
    }

    private fun step(keyCode: Int) {
        Haptics.tick(this)
        listener?.onClick(keyCode)
        flashKey = keyCode
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 260
            addUpdateListener {
                flash = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private var flingKey = 0
    private var flingLeft = 0
    private val fling: Runnable = object : Runnable {
        override fun run() {
            if (flingLeft-- <= 0) return
            step(flingKey)
            handler.postDelayed(this, FLING_STEP_MS)
        }
    }

    /** A fast swipe keeps going for a few steps, to cross long rows quickly. */
    private fun flingSteps(tracker: VelocityTracker) {
        tracker.computeCurrentVelocity(1000)
        val vx = tracker.xVelocity
        val vy = tracker.yVelocity
        val speed = hypot(vx, vy) / resources.displayMetrics.density
        if (speed < FLING_MIN_DP_S) return
        flingKey = if (abs(vx) >= abs(vy)) {
            if (vx > 0) KeyCodes.DPAD_RIGHT else KeyCodes.DPAD_LEFT
        } else {
            if (vy > 0) KeyCodes.DPAD_DOWN else KeyCodes.DPAD_UP
        }
        flingLeft = ((speed - FLING_MIN_DP_S) / FLING_DP_S_PER_STEP).toInt().coerceIn(1, FLING_MAX_STEPS)
        handler.postDelayed(fling, FLING_STEP_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val STEP_DP = 44f
        const val STRIP_STEP_DP = 32f
        const val FLING_MIN_DP_S = 1600f
        const val FLING_DP_S_PER_STEP = 900f
        const val FLING_MAX_STEPS = 6
        const val FLING_STEP_MS = 70L
    }
}
