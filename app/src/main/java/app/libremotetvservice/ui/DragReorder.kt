package app.libremotetvservice.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Reorders the rows of [list] by dragging their handles: the row lifts and follows the finger, the others make
 * room, and the list scrolls when the finger nears an edge of [scroll]. [onDrop] gets the rows in their new order;
 * [onEnd] runs after every drag, moved or not. While [dragging], the screen should not rebuild [list].
 * TalkBack users reorder through the rows' Move up / Move down actions instead (see ShortcutsActivity).
 */
class DragReorder(
    private val list: LinearLayout,
    private val scroll: ScrollView,
    private val onEnd: () -> Unit = {},
    private val onDrop: (List<View>) -> Unit,
) {
    private var row: View? = null

    val dragging get() = row != null
    private var from = 0
    private var to = 0
    private var downY = 0f
    private var lastY = 0f
    private var scrollAtDown = 0

    @SuppressLint("ClickableViewAccessibility")
    fun attach(row: View, handle: View) {
        handle.setOnTouchListener { _, event -> onTouch(row, event) }
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (row != null) return false
                row = view
                from = list.indexOfChild(view)
                to = from
                downY = event.rawY
                lastY = event.rawY
                scrollAtDown = scroll.scrollY
                scroll.requestDisallowInterceptTouchEvent(true)
                view.animate().scaleX(1.02f).scaleY(1.02f).translationZ(view.context.dp(8f)).setDuration(120).start()
                Haptics.long(view)
            }
            MotionEvent.ACTION_MOVE -> {
                if (row !== view) return false
                lastY = event.rawY
                follow()
                autoScroll()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (row !== view) return false
                // A cancelled gesture puts the row back where it was.
                drop(commit = event.actionMasked == MotionEvent.ACTION_UP)
            }
        }
        return true
    }

    /** Moves the lifted row with the finger and shifts the rows it passes. */
    private fun follow() {
        val view = row ?: return
        if (view.parent !== list) return
        val dy = lastY - downY + (scroll.scrollY - scrollAtDown)
        val top = view.top
        val bottom = list.getChildAt(list.childCount - 1).bottom - view.height
        view.translationY = dy.coerceIn((list.getChildAt(0).top - top).toFloat(), (bottom - top).toFloat())
        val center = view.top + view.translationY + view.height / 2f
        var target = 0
        for (i in 0 until list.childCount) {
            val other = list.getChildAt(i)
            if (other === view) continue
            if (other.top + other.height / 2f < center) target++
        }
        if (target != to) {
            to = target
            Haptics.tick(view)
        }
        val margins = (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0
        val step = (view.height + margins).toFloat()
        for (i in 0 until list.childCount) {
            val other = list.getChildAt(i)
            if (other === view) continue
            val shift = when {
                i in (from + 1)..to -> -step
                i in to until from -> step
                else -> 0f
            }
            if (other.translationY != shift) other.animate().translationY(shift).setDuration(120).start()
        }
    }

    private val scroller = object : Runnable {
        override fun run() {
            if (row == null) return
            val edge = scroll.context.dp(56f)
            val location = IntArray(2).also(scroll::getLocationOnScreen)
            val step = when {
                lastY < location[1] + edge -> -scroll.context.dp(8f).toInt()
                lastY > location[1] + scroll.height - edge -> scroll.context.dp(8f).toInt()
                else -> 0
            }
            if (step == 0 || !scroll.canScrollVertically(step)) return
            scroll.scrollBy(0, step)
            follow()
            scroll.postOnAnimation(this)
        }
    }

    private fun autoScroll() {
        scroll.removeCallbacks(scroller)
        scroll.postOnAnimation(scroller)
    }

    private fun drop(commit: Boolean) {
        val view = row ?: return
        row = null
        scroll.removeCallbacks(scroller)
        for (i in 0 until list.childCount) list.getChildAt(i).apply {
            animate().cancel()
            translationY = 0f
        }
        view.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120).start()
        // The list may have been rebuilt under the finger; then there is nothing left to move.
        if (commit && to != from && view.parent === list) {
            list.removeView(view)
            list.addView(view, to.coerceIn(0, list.childCount))
            onDrop((0 until list.childCount).map(list::getChildAt))
        }
        onEnd()
    }
}
