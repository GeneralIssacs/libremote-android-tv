package app.libremotetvservice.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.format.DateUtils
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import app.libremotetvservice.R
import app.libremotetvservice.data.Tv
import app.libremotetvservice.data.TvKind
import app.libremotetvservice.libremote
import app.libremotetvservice.net.ReceiverState
import app.libremotetvservice.net.RemoteController.State
import app.libremotetvservice.protocol.Features
import app.libremotetvservice.protocol.TvInfo
import app.libremotetvservice.update.Updater

/** A symbol on a tonal circle, like the TV icons in the TV list. */
fun Activity.roundIcon(icon: Int, size: Float = 40f): Drawable {
    val circle = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(getColor(R.color.accent_container))
    }
    val symbol = getDrawable(icon)!!.mutate().apply { setTint(getColor(R.color.on_accent_container)) }
    val inset = dp((size - 24f) / 2).toInt()
    return LayerDrawable(arrayOf(circle, symbol)).apply { setLayerInset(1, inset, inset, inset, inset) }
}

/**
 * The ▾ by the TV's name: switch TV or add one; then, for this TV, Find my remote ([onFindRemote], only when the TV
 * offers it), its right-hand keys (channels for a TV, Search and Apps for a box; [onKindChanged]) and About this TV.
 */
class TvPickerSheet(
    private val activity: Activity,
    private val current: Tv,
    private val onSwitch: (Tv) -> Unit,
    private val onFindRemote: (() -> Unit)? = null,
    private val onKindChanged: () -> Unit = {},
) {
    fun show() {
        val app = activity.libremote
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(activity, null, 0, R.style.Libremote_Title).apply {
            setText(R.string.switch_tv)
            setPadding(activity.dp(8f).toInt(), 0, 0, activity.dp(8f).toInt())
        })
        val connected = (app.remote.state as? State.Connected)?.tv?.pin
        for (tv in app.tvs.all()) {
            val here = tv.pin == current.pin
            val check = if (here) ImageView(activity).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = activity.getColorStateList(R.color.accent)
                contentDescription = activity.getString(R.string.tv_picker_current)
            } else null
            val subtitle = if (tv.pin == connected) activity.getString(R.string.tv_picker_connected, tv.host) else tv.host
            content.addView(activity.listRow(activity.roundIcon(R.drawable.ic_tv), tv.name, subtitle, trailing = check) {
                dialog.dismiss()
                if (!here) onSwitch(tv)
            })
        }
        content.addView(activity.listRow(activity.getDrawable(R.drawable.ic_add), activity.getString(R.string.add_tv), iconTint = R.color.on_surface_muted) {
            dialog.dismiss()
            activity.startActivity(Intent(activity, DevicesActivity::class.java).putExtra(DevicesActivity.EXTRA_STAY, true))
        })
        // This TV.
        content.addView(View(activity).apply {
            setBackgroundColor(activity.getColor(R.color.surface_high))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(1f).toInt()).apply {
                topMargin = activity.dp(8f).toInt()
                bottomMargin = activity.dp(8f).toInt()
            }
        })
        receiverNote()?.let(content::addView)
        onFindRemote?.let { find ->
            content.addView(activity.listRow(activity.getDrawable(R.drawable.ic_settings_remote), activity.getString(R.string.find_remote), iconTint = R.color.on_surface_muted) {
                dialog.dismiss()
                find()
            })
        }
        content.addView(kindRow())
        content.addView(activity.listRow(
            activity.getDrawable(R.drawable.ic_info), activity.getString(R.string.about_tv), current.name, iconTint = R.color.on_surface_muted,
        ) {
            dialog.dismiss()
            AboutTvSheet(activity, current).show()
        })
        dialog = activity.showSheet(content)
    }

    /**
     * A light note when Libremote Receiver on this TV has a newer version out (standard build): selecting it updates
     * the receiver, × hides it until the next version. About this TV keeps offering it either way.
     */
    private fun receiverNote(): View? {
        val version = Updater.receiverNote(activity, current) ?: return null
        lateinit var row: View
        val dismiss = android.widget.ImageButton(activity, null, 0, R.style.Libremote_Icon).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = activity.getColorStateList(R.color.on_surface_muted)
            contentDescription = activity.getString(R.string.receiver_update_dismiss)
            layoutParams = LinearLayout.LayoutParams(activity.dp(48f).toInt(), activity.dp(48f).toInt())
            setOnClickListener {
                Updater.dismissReceiverNote(activity, current)
                row.visibility = View.GONE
            }
        }
        row = activity.listRow(activity.getDrawable(R.drawable.ic_upload), activity.getString(R.string.receiver_update_note),
            activity.getString(R.string.receiver_update_note_summary, version), iconTint = R.color.accent, trailing = dismiss) {
            dialog.dismiss()
            Updater.updateReceiver(activity, current)
        }
        return row
    }

    private lateinit var dialog: Dialog

    /**
     * "Channel buttons": on for a TV (Channel +, Menu, Channel −), off for a streaming box (Search, Menu, Apps).
     * Detected from the model the TV reports (TvKind); switching it here overrides that for this TV.
     */
    private fun kindRow(): View {
        val details = activity.libremote.tvDetails
        val toggle = Switch(activity).apply {
            isChecked = details.kind(current.pin) == TvKind.TV
            // The row is the control: it toggles the switch and speaks for it.
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fun summary() = activity.getString(if (toggle.isChecked) R.string.tv_buttons_channels else R.string.tv_buttons_apps)
        lateinit var row: LinearLayout
        row = activity.listRow(activity.getDrawable(R.drawable.ic_channels), activity.getString(R.string.tv_buttons), summary(),
            iconTint = R.color.on_surface_muted, trailing = toggle) {
            toggle.isChecked = !toggle.isChecked
            details.chooseKind(current.pin, if (toggle.isChecked) TvKind.TV else TvKind.BOX)
            // listRow holds the title and the summary in its middle child.
            ((row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView).text = summary()
            onKindChanged()
        }
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = toggle.isChecked
            }
        }
        return row
    }
}

/**
 * What [tv] reported about itself: model, maker, Android version, the remote service and the features it offers,
 * plus its address and whether Libremote Receiver was seen. Only shows what is on the phone; nothing is sent.
 */
class AboutTvSheet(private val activity: Activity, private val tv: Tv) {
    private var dialog: Dialog? = null

    fun show() {
        val app = activity.libremote
        val state = app.remote.state
        val live = (state as? State.Connected)?.takeIf { it.tv.pin == tv.pin }?.info
        val info = live ?: app.tvDetails.get(tv.pin)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(8f).toInt(), 0, activity.dp(8f).toInt(), 0)
        }
        content.addView(TextView(activity, null, 0, R.style.Libremote_Title).apply { setText(R.string.about_tv) })
        content.addView(TextView(activity, null, 0, R.style.Libremote_Body).apply {
            text = tv.name
            setPadding(0, activity.dp(2f).toInt(), 0, activity.dp(8f).toInt())
        })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun fact(label: Int, value: String?) {
            if (value.isNullOrBlank()) return
            body.addView(TextView(activity).apply {
                setText(label)
                textSize = 13f
                setTextColor(activity.getColor(R.color.on_surface_muted))
                setPadding(0, activity.dp(10f).toInt(), 0, 0)
            })
            body.addView(TextView(activity).apply {
                text = value
                textSize = 16f
                setTextColor(activity.getColor(R.color.on_surface))
                setTextIsSelectable(true)
            })
        }
        if (info != null) {
            fact(R.string.about_model, info.model)
            fact(R.string.about_maker, info.manufacturer)
            fact(R.string.about_android, info.osVersion)
            fact(R.string.about_service, info.servicePackage)
            fact(R.string.about_service_version, info.serviceVersion)
            fact(R.string.about_features, features(info))
        }
        fact(R.string.about_address, tv.host)
        val receiverVersion = app.receivers.versionName(tv)
        fact(R.string.about_receiver, when (app.receivers.state(tv)) {
            ReceiverState.PRESENT -> receiverVersion?.let { activity.getString(R.string.about_receiver_version, it) }
                ?: activity.getString(R.string.about_receiver_present)
            ReceiverState.ABSENT -> activity.getString(R.string.about_receiver_absent)
            ReceiverState.UNKNOWN -> activity.getString(R.string.about_receiver_unknown)
        })
        // Standard build: a newer receiver is out (from the last update check).
        Updater.receiverUpdate(activity, tv)?.let { version ->
            body.addView(activity.rowButton(activity.getString(R.string.about_receiver_update, version)) {
                dialog?.dismiss()
                Updater.updateReceiver(activity, tv)
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = activity.dp(4f).toInt() })
        }
        if (app.shortcuts.noPlay(tv.pin)) fact(R.string.about_play_store, activity.getString(R.string.about_play_store_missing))
        content.addView(MaxHeightScrollView(activity).apply {
            maxHeight = (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()
            isVerticalScrollBarEnabled = false
            addView(body)
        })
        val seen = app.tvDetails.seenAt(tv.pin)
        val footer = when {
            info == null -> activity.getString(R.string.about_never_connected)
            live != null -> activity.getString(R.string.about_reported_now)
            else -> activity.getString(
                R.string.about_reported_before,
                DateUtils.getRelativeTimeSpanString(seen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
        content.addView(TextView(activity, null, 0, R.style.Libremote_Body).apply {
            text = footer
            textSize = 13f
            setPadding(0, activity.dp(16f).toInt(), 0, 0)
        })
        dialog = activity.showSheet(content)
    }

    /** The feature bits the TV offers, in words, marking those Libremote doesn't use. */
    private fun features(info: TvInfo): String {
        val names = listOf(
            Features.KEYS to R.string.feature_keys,
            Features.IME to R.string.feature_typing,
            Features.VOLUME to R.string.feature_volume,
            Features.POWER to R.string.feature_power,
            Features.APP_LINKS to R.string.feature_app_links,
            Features.FIND_MY_REMOTE to R.string.feature_find_remote,
            Features.VOICE to R.string.feature_voice,
            ASSISTANT to R.string.feature_assistant,
            Features.GAMEPAD to R.string.feature_gamepad,
            Features.PING to R.string.feature_ping,
        )
        val lines = names.filter { (bit, _) -> info.supports(bit) }.map { (bit, name) ->
            val text = activity.getString(name)
            if (bit and Features.WANTED == 0) activity.getString(R.string.feature_unused, text) else text
        }.toMutableList()
        val known = names.fold(0) { all, (bit, _) -> all or bit }
        val other = (0 until 31).map { 1 shl it }.filter { info.features and it != 0 && known and it == 0 }
        if (other.isNotEmpty()) lines += activity.getString(R.string.feature_other, other.joinToString(", "))
        return lines.joinToString("\n") { "• $it" }
    }

    private companion object {
        /** Voice through the Google Assistant (24 = voice and Assistant). */
        const val ASSISTANT = 16
    }
}
