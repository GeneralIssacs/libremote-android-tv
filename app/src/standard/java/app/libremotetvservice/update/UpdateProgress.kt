package app.libremotetvservice.update

import android.app.Activity
import android.app.Dialog
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import app.libremotetvservice.R
import app.libremotetvservice.ui.dp

/**
 * A download or a receiver update on its way: title, text, progress bar and status, then how it ended. Cancel (or
 * Back) calls [onCancel] while it runs; once [finish]ed, Done just closes it. Main thread only.
 */
internal class UpdateProgress(private val activity: Activity, title: String, private val onCancel: () -> Unit) {
    private val dialog = Dialog(activity, R.style.Theme_Libremote_Floating)
    private val view = activity.layoutInflater.inflate(R.layout.dialog_update, null)
    private val title = view.findViewById<TextView>(R.id.updateTitle)
    private val text = view.findViewById<TextView>(R.id.updateText)
    private val bar = view.findViewById<ProgressBar>(R.id.updateProgress)
    private val status = view.findViewById<TextView>(R.id.updateStatus)
    private val secondary = view.findViewById<Button>(R.id.updateSecondary)
    private val primary = view.findViewById<Button>(R.id.updatePrimary)
    private var finished = false

    init {
        this.title.text = title
        text.visibility = View.GONE
        bar.isIndeterminate = true
        secondary.setOnClickListener { cancel() }
        primary.setOnClickListener { dismiss() }
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { if (!finished) onCancel() }
        dialog.window?.setLayout(minOf(activity.resources.displayMetrics.widthPixels - activity.dp(32f).toInt(), activity.dp(560f).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        if (!activity.isFinishing) dialog.show()
    }

    val showing get() = dialog.isShowing

    /** What is happening, in a sentence; null hides it. */
    fun text(value: String?) {
        text.text = value
        text.visibility = if (value == null) View.GONE else View.VISIBLE
    }

    /** A step with no measurable progress. */
    fun step(value: String) {
        bar.isIndeterminate = true
        status.text = value
    }

    /** [done] of [total] bytes (total 0 or less when unknown). */
    fun download(done: Long, total: Long) {
        if (total > 0) {
            bar.isIndeterminate = false
            bar.max = 1000
            bar.progress = (done * 1000 / total).toInt()
            status.text = activity.getString(R.string.send_progress, size(done), size(total))
        } else {
            status.text = size(done)
        }
    }

    /** How it ended: no more progress, and Done (or Close, when it [failed]) closes it. */
    fun finish(title: String, text: String?, failed: Boolean = false) {
        primary.setText(if (failed) R.string.close else R.string.send_done)
        finished = true
        this.title.text = title
        text(text)
        bar.visibility = View.GONE
        status.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.visibility = View.VISIBLE
        primary.requestFocus()
    }

    fun dismiss() {
        if (dialog.isShowing && !activity.isDestroyed) dialog.dismiss()
    }

    private fun cancel() {
        if (!finished) onCancel()
        dismiss()
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(activity, bytes)
}
