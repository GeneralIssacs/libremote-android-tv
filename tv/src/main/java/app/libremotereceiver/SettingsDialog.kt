package app.libremotereceiver

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.libremotetvservice.protocol.PhoneRegistry

/**
 * The receiver's settings, over its screen: a dialog rather than a screen of its own, so the receiver keeps
 * listening (and a transfer keeps going) while it is open. The installer switch, then the paired phones, each
 * removable.
 */
class SettingsDialog(private val activity: ReceiveActivity) {
    private val app = activity.receiver
    private val dialog = Dialog(activity, R.style.Theme_Receiver_Settings)
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val phonesChanged: () -> Unit = { activity.runOnUiThread { if (dialog.isShowing) fill() } }

    fun show() {
        val padding = dp(32)
        dialog.setContentView(ScrollView(activity).apply {
            setPadding(padding, padding, padding, padding)
            addView(list)
        })
        dialog.window?.setLayout(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT)
        app.phones.addListener(phonesChanged)
        dialog.setOnDismissListener { app.phones.removeListener(phonesChanged) }
        fill()
        dialog.show()
    }

    private fun fill() {
        list.removeAllViews()
        list.addView(heading(R.string.settings, 28f, top = 0))
        val install = Switch(activity).apply {
            isChecked = app.settings.autoInstall
            isFocusable = false
            isClickable = false
        }
        list.addView(row(R.drawable.ic_android, activity.getString(R.string.settings_auto_install), activity.getString(R.string.settings_auto_install_summary), install) {
            app.settings.autoInstall = !app.settings.autoInstall
            app.settings.askedAutoInstall = true
            install.isChecked = app.settings.autoInstall
        })
        list.addView(heading(R.string.settings_phones, 18f, top = 24))
        val phones = app.phones.all()
        if (phones.isEmpty()) {
            list.addView(TextView(activity).apply {
                setText(R.string.settings_no_phones)
                setTextColor(activity.getColor(R.color.on_surface_muted))
                textSize = 16f
            })
        }
        for (phone in phones) {
            val name = phone.name.ifEmpty { activity.getString(R.string.settings_phone_unnamed) }
            val paired = activity.getString(R.string.settings_phone_paired, DateUtils.getRelativeTimeSpanString(activity, phone.pairedMs))
            list.addView(row(R.drawable.ic_smartphone, name, paired, null) { confirmRemove(phone, name) })
        }
        list.getChildAt(1).requestFocus()
    }

    private fun confirmRemove(phone: PhoneRegistry.Phone, name: String) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.settings_remove_title, name))
            .setMessage(R.string.settings_remove_text)
            .setPositiveButton(R.string.remove) { _, _ ->
                app.phones.remove(phone.key)
                app.host.disconnect(phone.key)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun heading(text: Int, size: Float, top: Int) = TextView(activity).apply {
        setText(text)
        setTextColor(activity.getColor(if (size > 20) R.color.on_surface else R.color.accent))
        textSize = size
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top); bottomMargin = dp(12) }
    }

    /** A focusable row, as in the received list: an icon, a title, details, and an optional switch at the end. */
    private fun row(icon: Int, title: String, details: String, end: View?, onClick: () -> Unit): View {
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(activity).apply {
                text = title
                setTextColor(activity.getColor(R.color.on_surface))
                textSize = 18f
            })
            addView(TextView(activity).apply {
                text = details
                setTextColor(activity.getColor(R.color.on_surface_muted))
                textSize = 14f
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = activity.getDrawable(R.drawable.bg_row)
            isFocusable = true
            isClickable = true
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = activity.getColorStateList(R.color.accent)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(16) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(texts)
            end?.let { addView(it) }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
}
