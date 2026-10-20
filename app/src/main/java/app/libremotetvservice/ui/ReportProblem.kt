package app.libremotetvservice.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.libremotetvservice.BuildConfig
import app.libremotetvservice.R
import app.libremotetvservice.libremote
import app.libremotetvservice.net.ReceiverState
import app.libremotetvservice.net.SendController

/**
 * Settings › Report a problem: opens Libremote's "new issue" page on GitHub, and shows details the user may copy
 * into it. Nothing is sent from the app, and the details stay out of the link. They leave out the phone's model,
 * which Libremote doesn't even tell the TV; the screen size covers layout problems.
 */
class ReportProblem(private val activity: Activity) {

    fun show() {
        val details = details()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = activity.dp(24f).toInt()
            setPadding(side, activity.dp(8f).toInt(), side, 0)
            addView(TextView(activity, null, 0, R.style.Libremote_Body).apply { setText(R.string.report_text) })
            addView(MaxHeightScrollView(activity).apply {
                maxHeight = (activity.resources.displayMetrics.heightPixels * 0.4f).toInt()
                background = activity.getDrawable(R.drawable.bg_field)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = activity.dp(16f).toInt() }
                addView(TextView(activity).apply {
                    text = details
                    textSize = 13f
                    setLineSpacing(0f, 1.1f)
                    setTextColor(activity.getColor(R.color.on_surface))
                    setTextIsSelectable(true)
                    val pad = activity.dp(12f).toInt()
                    setPadding(pad, pad, pad, pad)
                })
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.report_title)
            .setView(content)
            .setPositiveButton(R.string.report_open) { _, _ -> openIssues() }
            .setNeutralButton(R.string.report_copy, null)
            .setNegativeButton(R.string.close, null)
            .show()
        // Copying keeps the dialog open, so Open GitHub can follow.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { copy(details) }
    }

    /**
     * What helps with a report, in English whatever the phone's language, since it is for the developer: this
     * app, Android, the screen, and what the current TV and its receiver last reported, then the last problems.
     */
    private fun details(): String {
        val app = activity.libremote
        val lines = mutableListOf<String>()
        lines += "Libremote ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR})"
        val metrics = activity.resources.displayMetrics
        lines += "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${(metrics.widthPixels / metrics.density).toInt()} × ${(metrics.heightPixels / metrics.density).toInt()} dp"
        val tv = app.tvs.last()
        if (tv == null) {
            lines += "TV: none paired"
        } else {
            val info = app.tvDetails.get(tv.pin)
            if (info == null) {
                lines += "TV: not connected yet"
            } else {
                val model = listOfNotNull(info.model, info.manufacturer?.let { "($it)" }).joinToString(" ").ifEmpty { "unknown model" }
                lines += "TV: $model, Android ${info.osVersion ?: "?"}"
                lines += "Remote service ${info.serviceVersion ?: "?"} (${info.servicePackage ?: "?"})"
            }
            lines += "Libremote Receiver: " + when (app.receivers.state(tv)) {
                ReceiverState.PRESENT -> app.receivers.versionName(tv) ?: "older than 1.0.0"
                ReceiverState.ABSENT -> "not found"
                ReceiverState.UNKNOWN -> "not seen yet"
            }
        }
        lines += "Last connection problem: " + (app.remote.lastError?.let { (error, at) -> "$error (${ago(at)})" } ?: "none")
        (app.sends.state as? SendController.State.Failed)?.let { failed ->
            lines += "Last send: ${failed.problem}" + (failed.detail?.let { " ($it)" } ?: "")
        }
        return lines.joinToString("\n")
    }

    private fun ago(at: Long) =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_NUMERIC_DATE)

    private fun copy(details: String) {
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.report_title), details))
        // Android 13 and later confirm a copy themselves.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(activity, R.string.report_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openIssues() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.ISSUES_URL)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.report_no_browser, BuildConfig.ISSUES_URL), Toast.LENGTH_LONG).show()
        }
    }
}
