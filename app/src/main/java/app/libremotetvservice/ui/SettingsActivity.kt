package app.libremotetvservice.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.TextAppearanceSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import app.libremotetvservice.BuildConfig
import app.libremotetvservice.R
import app.libremotetvservice.libremote
import app.libremotetvservice.data.Tv
import app.libremotetvservice.update.Updater

class SettingsActivity : Activity() {
    private lateinit var tvs: LinearLayout
    private val tvsChanged = { renderTvs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        edgeToEdge(findViewById(R.id.root))
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        val settings = libremote.settings
        bindSwitch(R.id.haptics, settings.haptics) { settings.haptics = it }
        bindSwitch(R.id.volumeKeys, settings.volumeKeys) { settings.volumeKeys = it }
        bindSwitch(R.id.autoKeyboard, settings.autoKeyboard) { settings.autoKeyboard = it }
        bindSwitch(R.id.keepScreenOn, settings.keepScreenOn) { settings.keepScreenOn = it }
        // Shortcuts open for the TV the remote shows (the last one used).
        findViewById<View>(R.id.shortcuts).setOnClickListener { ShortcutsActivity.open(this, libremote.tvs.last()) }
        findViewById<View>(R.id.guide).setOnClickListener { startActivity(android.content.Intent(this, RemoteGuideActivity::class.java)) }
        findViewById<TextView>(R.id.version).text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        findViewById<View>(R.id.report).setOnClickListener { ReportProblem(this).show() }
        tvs = findViewById(R.id.tvs)
        receiverUpdates = findViewById(R.id.receiverUpdates)
        checkNowSummary = findViewById(R.id.checkNowSummary)
        if (Updater.offered(this)) bindUpdates()
    }

    override fun onStart() {
        super.onStart()
        libremote.tvs.addListener(tvsChanged)
        libremote.receivers.addListener(receiversChanged)
        renderTvs()
        renderUpdates()
    }

    override fun onStop() {
        libremote.tvs.removeListener(tvsChanged)
        libremote.receivers.removeListener(receiversChanged)
        super.onStop()
    }

    // Updates (Updater.offered: the standard build, not installed by an app store, with the release key)

    private lateinit var receiverUpdates: LinearLayout
    private lateinit var checkNowSummary: TextView
    private var checking = false
    private val receiversChanged = { renderUpdates() }

    private fun bindUpdates() {
        findViewById<View>(R.id.updates).visibility = View.VISIBLE
        findViewById<Switch>(R.id.autoCheck).apply {
            // Title and summary in the switch's own label, so the whole row toggles and TalkBack reads both.
            text = SpannableStringBuilder(getString(R.string.settings_check_auto)).append('\n').append(
                getString(R.string.settings_check_auto_summary),
                TextAppearanceSpan(this@SettingsActivity, R.style.Libremote_SettingLinkSummary),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            isChecked = Updater.autoCheck(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> Updater.setAutoCheck(this@SettingsActivity, checked) }
        }
        findViewById<View>(R.id.checkNow).setOnClickListener {
            if (checking) return@setOnClickListener
            checking = true
            renderUpdates()
            Updater.checkNow(this) {
                checking = false
                renderUpdates()
            }
        }
        findViewById<TextView>(R.id.privacy).append(" " + getString(R.string.settings_privacy_updates))
    }

    /** The last check, and a row per TV whose Libremote Receiver has a newer version out. */
    private fun renderUpdates() {
        if (!Updater.offered(this)) return
        val last = Updater.lastCheck(this)
        checkNowSummary.text = when {
            checking -> getString(R.string.settings_checking)
            last == 0L -> getString(R.string.settings_check_now_summary, BuildConfig.VERSION_NAME)
            System.currentTimeMillis() - last < DateUtils.MINUTE_IN_MILLIS -> getString(R.string.settings_check_now_just, BuildConfig.VERSION_NAME)
            else -> getString(R.string.settings_check_now_checked, BuildConfig.VERSION_NAME,
                DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        }
        receiverUpdates.removeAllViews()
        for (tv in libremote.tvs.all()) {
            val version = Updater.receiverUpdate(this, tv) ?: continue
            val installed = libremote.receivers.versionName(tv) ?: continue
            receiverUpdates.addView(settingLink(R.drawable.ic_upload, getString(R.string.settings_update_receiver, tv.name),
                getString(R.string.settings_update_receiver_summary, installed, version)) { Updater.updateReceiver(this, tv) })
        }
    }

    /** A row like the layout's Libremote.SettingLink ones, made in code: icon, title and summary. */
    private fun settingLink(icon: Int, title: String, summary: String, onClick: () -> Unit) =
        LinearLayout(this, null, 0, R.style.Libremote_SettingLink).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ImageView(context, null, 0, R.style.Libremote_SettingLinkIcon).apply {
                setImageResource(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt()).apply { marginEnd = dp(20f).toInt() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context, null, 0, R.style.Libremote_SettingLinkTitle).apply { text = title })
                addView(TextView(context, null, 0, R.style.Libremote_SettingLinkSummary).apply { text = summary })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onClick() }
        }

    private fun bindSwitch(id: Int, value: Boolean, set: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> set(checked) }
        }
    }

    private fun renderTvs() {
        tvs.removeAllViews()
        val all = libremote.tvs.all()
        if (all.isEmpty()) {
            tvs.addView(TextView(this).apply {
                setText(R.string.settings_no_tvs)
                setTextAppearance(R.style.Libremote_Body)
                setPadding(dp(4f).toInt(), 0, 0, 0)
            })
        }
        for (tv in all) {
            val row = layoutInflater.inflate(R.layout.item_tv, tvs, false)
            row.findViewById<TextView>(R.id.tvTitle).text = tv.name
            row.findViewById<TextView>(R.id.tvSubtitle).text = tv.host
            row.findViewById<View>(R.id.tvMore).apply {
                visibility = View.VISIBLE
                (this as android.widget.ImageButton).setImageResource(R.drawable.ic_delete)
                contentDescription = getString(R.string.devices_forget)
                setOnClickListener { forget(tv) }
            }
            row.isClickable = false
            row.background = getDrawable(R.drawable.bg_pill)
            tvs.addView(row)
        }
        // The remote's TV menu has it too.
        tvs.addView(listRow(getDrawable(R.drawable.ic_add), getString(R.string.add_tv), iconTint = R.color.on_surface_muted) {
            startActivity(android.content.Intent(this, DevicesActivity::class.java).putExtra(DevicesActivity.EXTRA_STAY, true))
        })
    }

    private fun forget(tv: Tv) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.devices_forget_title, tv.name))
            .setMessage(R.string.devices_forget_text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.devices_forget) { _, _ ->
                libremote.remote.forget(tv.pin)
                libremote.tvs.remove(tv.pin)
            }
            .show()
    }
}
