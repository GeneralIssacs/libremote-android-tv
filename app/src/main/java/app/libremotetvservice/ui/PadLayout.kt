package app.libremotetvservice.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.libremotetvservice.R
import app.libremotetvservice.data.PadMode

/**
 * The middle of the remote (layout remote_pad): volume on the left, the pad, the TV's own keys on the right, and in
 * Full mode a row of extra keys above them and a swipe strip below. It sizes them by rules rather than weights:
 * - the D-pad stays round and as large as the width between the columns allows, up to [DPAD_MAX]; the columns hug it
 *   and take its height, 48 to 84 dp per key;
 * - the touchpad takes the D-pad's place and size, so switching between them moves nothing else;
 * - in Full, the D-pad gets the height the extra keys and the strip (at least [STRIP_MIN]) leave, the strip takes the
 *   rest up to [STRIP_MAX], and whatever is still left is shared above and below.
 * Asked how tall it wants to be (the remote scrolls on small screens), it answers with the smallest comfortable height
 * for the mode, so the pad shrinks to that before the remote scrolls. While [typing], the strip and the extra keys step
 * aside and the pad may shrink to nothing.
 */
class PadLayout(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {

    var mode = PadMode.DPAD
        set(value) {
            field = value
            showChildren()
        }

    var typing = false
        set(value) {
            field = value
            showChildren()
        }

    private lateinit var volume: View
    private lateinit var keys: View
    private lateinit var dpad: View
    private lateinit var touchpad: View
    private lateinit var strip: TouchpadView
    private lateinit var extras: View

    /** Where each child goes, worked out in onMeasure. */
    private val places = HashMap<View, Rect>()

    private fun px(dp: Float) = context.dp(dp).toInt()
    private val columnWidth = px(56f)
    private val gap = px(8f)

    override fun onFinishInflate() {
        super.onFinishInflate()
        volume = findViewById(R.id.volumeColumn)
        keys = findViewById(R.id.keyColumn)
        dpad = findViewById(R.id.dpad)
        touchpad = findViewById(R.id.touchpad)
        strip = findViewById(R.id.strip)
        extras = findViewById(R.id.extraKeys)
        // The pills clip their keys' ripples to their rounded shape (android:clipToOutline only exists from Android 12).
        listOf(volume, keys, extras).forEach { it.clipToOutline = true }
        strip.compact = true
        showChildren()
    }

    private fun showChildren() {
        if (!::extras.isInitialized) return
        val full = mode == PadMode.FULL && !typing
        dpad.visibility = if (mode == PadMode.TOUCHPAD) GONE else VISIBLE
        touchpad.visibility = if (mode == PadMode.TOUCHPAD) VISIBLE else GONE
        strip.visibility = if (full) VISIBLE else GONE
        extras.visibility = if (full) VISIBLE else GONE
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val full = extras.visibility != GONE
        if (full) extras.measure(exactly(width), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val extrasHeight = if (full) extras.measuredHeight else 0
        val smallest = when {
            typing -> 0
            full -> extrasHeight + gap + px(DPAD_MIN_FULL) + gap + px(STRIP_MIN)
            else -> px(PAD_MIN)
        }
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(smallest, MeasureSpec.getSize(heightMeasureSpec))
            else -> smallest
        }
        places.clear()
        placePad(width, height, extrasHeight)
        for ((child, rect) in places) child.measure(exactly(rect.width()), exactly(rect.height()))
        setMeasuredDimension(width, height)
    }

    private fun placePad(width: Int, height: Int, extrasHeight: Int) {
        val full = extras.visibility != GONE
        val fullMin = if (full) extrasHeight + gap + gap + px(STRIP_MIN) else 0
        val size = minOf(width - 2 * (columnWidth + gap), px(DPAD_MAX), height - fullMin).coerceAtLeast(0)
        val stripHeight = if (full) (height - extrasHeight - size - 2 * gap).coerceIn(px(STRIP_MIN), px(STRIP_MAX)) else 0
        val stack = size + if (full) extrasHeight + 2 * gap + stripHeight else 0
        var top = ((height - stack) / 2).coerceAtLeast(0)
        if (full) {
            place(extras, 0, top, width, extrasHeight)
            top += extrasHeight + gap
        }
        // The columns stay next to the pad, centred on it, however wide the space is.
        val cluster = size + 2 * (gap + columnWidth)
        val left = (width - cluster) / 2
        val column = columnHeight(size, height)
        val columnTop = (top + (size - column) / 2).coerceIn(0, (height - column).coerceAtLeast(0))
        place(volume, left, columnTop, columnWidth, column)
        place(if (mode == PadMode.TOUCHPAD) touchpad else dpad, left + columnWidth + gap, top, size, size)
        place(keys, left + cluster - columnWidth, columnTop, columnWidth, column)
        if (full) place(strip, 0, top + size + gap, width, stripHeight)
    }

    /** Three keys of 48 to 84 dp, as tall as [pad] when it allows, never taller than the layout. */
    private fun columnHeight(pad: Int, height: Int) = pad.coerceIn(3 * px(KEY_MIN), 3 * px(KEY_MAX)).coerceAtMost(height)

    private fun place(child: View, left: Int, top: Int, width: Int, height: Int) {
        if (child.visibility == GONE) return
        places[child] = Rect(left, top, left + width.coerceAtLeast(0), top + height.coerceAtLeast(0))
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((child, rect) in places) child.layout(rect.left, rect.top, rect.right, rect.bottom)
    }

    private companion object {
        /** The D-pad and the touchpad never get smaller than this in their own modes, unless typing. */
        const val PAD_MIN = 176f
        const val DPAD_MIN_FULL = 144f
        const val DPAD_MAX = 320f
        const val STRIP_MIN = 64f
        const val STRIP_MAX = 144f
        const val KEY_MIN = 48f
        const val KEY_MAX = 84f
    }
}

/**
 * A LinearLayout no wider than its android:maxWidth, so on an unfolded phone or a tablet the remote keeps a phone's
 * proportions, centred by its layout_gravity, instead of stretching across the screen.
 */
class MaxWidthLinearLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val maxWidth = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxWidth)).run {
        getDimensionPixelSize(0, Int.MAX_VALUE).also { recycle() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && size > maxWidth) {
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY)
        } else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}
