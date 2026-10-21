package app.libremotetvservice.ui

import android.app.Activity
import android.app.Dialog
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.libremotetvservice.R
import app.libremotetvservice.data.Tv
import app.libremotetvservice.libremote
import app.libremotetvservice.protocol.ReceiverDeclinedException

/**
 * "+" on the shortcut row: adds an app to [tv]'s shortcuts. Offers the apps hidden from the remote, the TV's own
 * list once Libremote Receiver has shared it (or a way to ask for it), popular apps until then, and any package
 * name typed in.
 */
class AppPickerSheet(private val activity: Activity, private val tv: Tv) {
    private val app = activity.libremote
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var dialog: Dialog? = null
    private var syncing = false
    private var syncFailed = false
    private var syncDeclined = false

    /** While a sync waits for the TV to allow this phone: the code both show. */
    private var syncCode: String? = null

    fun show() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity, null, 0, R.style.Libremote_Title).apply {
                setText(R.string.shortcuts_add_title)
                setPadding(activity.dp(8f).toInt(), 0, activity.dp(8f).toInt(), 0)
            })
            addView(TextView(activity, null, 0, R.style.Libremote_Body).apply {
                text = activity.getString(R.string.shortcuts_add_for, tv.name)
                setPadding(activity.dp(8f).toInt(), activity.dp(2f).toInt(), activity.dp(8f).toInt(), activity.dp(8f).toInt())
            })
            addView(MaxHeightScrollView(activity).apply {
                maxHeight = (activity.resources.displayMetrics.heightPixels * 0.66f).toInt()
                isVerticalScrollBarEnabled = false
                addView(list)
            })
        }
        render()
        dialog = activity.showSheet(content)
    }

    private fun render() {
        list.removeAllViews()
        val shown = app.shortcuts.onRemote(tv.pin).map { it.packageName }.toSet()
        val tvList = app.receivers.apps(tv)
        val synced = tvList.isNotEmpty()
        val hidden = app.shortcuts.hidden(tv.pin).map { it.packageName }
        if (hidden.isNotEmpty()) {
            list.addView(activity.sectionTitle(activity.getString(R.string.shortcuts_hidden)))
            hidden.forEach { list.addView(appRow(it)) }
        }
        if (synced) {
            list.addView(activity.sectionTitle(activity.getString(R.string.shortcuts_on_tv, tv.name)))
            val others = tvList.filter { it.packageName !in shown && it.packageName !in hidden }
                .sortedBy { AppLabels.name(app, tv, it.packageName).lowercase() }
            if (others.isEmpty()) list.addView(note(activity.getString(R.string.shortcuts_all_added)))
            others.forEach { list.addView(appRow(it.packageName)) }
        } else {
            list.addView(activity.sectionTitle(activity.getString(R.string.shortcuts_on_tv, tv.name)))
            list.addView(syncRow())
            val popular = TvApps.popular.filter { it !in shown && it !in hidden }
            if (popular.isNotEmpty()) {
                list.addView(activity.sectionTitle(activity.getString(R.string.shortcuts_popular)))
                popular.forEach { list.addView(appRow(it)) }
            }
        }
        list.addView(activity.sectionTitle(activity.getString(R.string.shortcuts_other)))
        list.addView(activity.listRow(
            activity.getDrawable(R.drawable.ic_edit), activity.getString(R.string.shortcuts_by_package),
            activity.getString(R.string.shortcuts_by_package_hint), iconTint = R.color.on_surface_muted,
        ) { askPackage() })
    }

    private fun appRow(pkg: String): View {
        val name = AppLabels.name(app, tv, pkg)
        return activity.listRow(AppLabels.icon(app, tv, pkg, name), name, pkg.takeIf { it != name }) { pick(pkg) }
    }

    private fun note(text: String) = TextView(activity, null, 0, R.style.Libremote_Body).apply {
        this.text = text
        setPadding(activity.dp(8f).toInt(), 0, activity.dp(8f).toInt(), activity.dp(8f).toInt())
    }

    /** Asks Libremote Receiver on the TV for its app list. */
    private fun syncRow(): View {
        val subtitle = when {
            syncing && syncCode != null -> activity.getString(R.string.shortcuts_sync_confirm, syncCode)
            syncing -> activity.getString(R.string.shortcuts_sync_running)
            syncDeclined -> activity.getString(R.string.shortcuts_sync_declined)
            syncFailed -> activity.getString(R.string.shortcuts_sync_failed)
            else -> activity.getString(R.string.shortcuts_sync_hint)
        }
        val spinner = if (syncing) ProgressBar(activity).apply {
            indeterminateTintList = activity.getColorStateList(R.color.accent)
            layoutParams = LinearLayout.LayoutParams(activity.dp(24f).toInt(), activity.dp(24f).toInt())
        } else null
        return activity.listRow(
            activity.getDrawable(R.drawable.ic_sync), activity.getString(R.string.shortcuts_sync), subtitle,
            iconTint = R.color.accent, trailing = spinner, onClick = if (syncing) null else { _ -> sync() },
        )
    }

    private fun sync() {
        syncing = true
        syncFailed = false
        syncDeclined = false
        render()
        app.receivers.syncApps(tv, confirming = { code ->
            syncCode = code
            if (dialog?.isShowing == true) render()
        }, cancelled = { dialog?.isShowing != true }) { error ->
            syncing = false
            syncCode = null
            syncFailed = error != null
            syncDeclined = error is ReceiverDeclinedException
            if (dialog?.isShowing == true) render()
        }
    }

    private fun askPackage() {
        activity.textPrompt(
            title = activity.getString(R.string.shortcuts_by_package),
            message = activity.getString(R.string.shortcuts_by_package_text),
            value = "",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            hint = "org.videolan.vlc",
            positive = R.string.shortcuts_add_button,
            error = activity.getString(R.string.shortcuts_by_package_invalid),
        ) { text ->
            val pkg = text.trim()
            if (!TvApps.isPackageName(pkg)) false else {
                pick(pkg)
                true
            }
        }
    }

    private fun pick(pkg: String) {
        app.shortcuts.add(tv.pin, pkg)
        dialog?.dismiss()
    }
}
