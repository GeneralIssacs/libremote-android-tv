package app.libremotetvservice.ui

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import app.libremotetvservice.R

/** A dialog pinned to the bottom edge with rounded top corners, holding [content]. */
fun Activity.showSheet(content: View): Dialog {
    val dialog = Dialog(this, R.style.Theme_Libremote_Sheet)
    val frame = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.bg_sheet)
        setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(24f).toInt())
        // A grab handle, as people expect from a bottom sheet.
        addView(View(context).apply {
            background = getDrawable(R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(dp(36f).toInt(), dp(4f).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12f).toInt()
            }
        })
        addView(content)
    }
    dialog.setContentView(frame)
    dialog.window?.apply {
        // Full width on a phone; on an unfolded phone or a tablet, no wider than 640 dp, centred, as sheets should be.
        setLayout(minOf(resources.displayMetrics.widthPixels, dp(640f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
    dialog.show()
    return dialog
}
