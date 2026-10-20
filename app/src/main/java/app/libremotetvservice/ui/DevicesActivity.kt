package app.libremotetvservice.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import app.libremotetvservice.R
import app.libremotetvservice.libremote
import app.libremotetvservice.data.Tv
import app.libremotetvservice.net.Discovery
import app.libremotetvservice.net.FoundTv

/**
 * The start screen: paired TVs and TVs found on the network. With a TV already paired, launching the app goes
 * straight to its remote; this screen opens when the user asks for it (pass [EXTRA_STAY]).
 */
class DevicesActivity : Activity() {
    private lateinit var saved: LinearLayout
    private lateinit var savedHeader: View
    private lateinit var nearby: LinearLayout
    private lateinit var searching: View
    private lateinit var searchingText: View
    private lateinit var empty: View
    private lateinit var discovery: Discovery
    private val main = Handler(Looper.getMainLooper())
    private val tvsChanged = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val last = libremote.tvs.last()
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_STAY, false) && last != null) {
            openRemote(last)
            return
        }
        setContentView(R.layout.activity_devices)
        edgeToEdge(findViewById(R.id.root))
        saved = findViewById(R.id.saved)
        savedHeader = findViewById(R.id.savedHeader)
        nearby = findViewById(R.id.nearby)
        searching = findViewById(R.id.searching)
        searchingText = findViewById(R.id.searchingText)
        empty = findViewById(R.id.empty)
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.addByAddress).setOnClickListener { addByAddress() }
        discovery = Discovery(this) { found ->
            rememberMovedTvs(found)
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        libremote.tvs.addListener(tvsChanged)
        discovery.start()
        render()
        main.postDelayed(searchTimeout, SEARCH_HINT_MS)
    }

    override fun onStop() {
        main.removeCallbacks(searchTimeout)
        if (::discovery.isInitialized) {
            discovery.stop()
            libremote.tvs.removeListener(tvsChanged)
        }
        super.onStop()
    }

    /** After a while without results, explain what to check instead of spinning forever. */
    private val searchTimeout = Runnable {
        searching.visibility = View.GONE
        searchingText.visibility = View.GONE
        if (nearby.childCount == 0) empty.visibility = View.VISIBLE
    }

    private fun render() {
        val tvs = libremote.tvs.all()
        val found = discovery.tvs
        savedHeader.visibility = if (tvs.isEmpty()) View.GONE else View.VISIBLE
        saved.removeAllViews()
        for (tv in tvs) {
            val here = found.firstOrNull { it.name == tv.name || it.host == tv.host }
            saved.addView(row(
                title = tv.name,
                subtitle = if (here != null) getString(R.string.devices_online, here.host) else getString(R.string.devices_offline, tv.host),
                online = here != null,
                onMore = { anchor -> savedMenu(anchor, tv) },
            ) { openRemote(tv) })
        }
        nearby.removeAllViews()
        val unpaired = found.filter { tv -> tvs.none { it.name == tv.name || it.host == tv.host } }
        for (tv in unpaired) {
            nearby.addView(row(tv.name, "${getString(R.string.devices_tap_to_pair)} · ${tv.host}", online = true, onMore = null) {
                pair(tv.host, tv.name)
            })
        }
        if (unpaired.isNotEmpty()) {
            empty.visibility = View.GONE
            searchingText.visibility = View.GONE
        }
    }

    private fun row(title: String, subtitle: String, online: Boolean, onMore: ((View) -> Unit)?, onClick: () -> Unit): View {
        val view = layoutInflater.inflate(R.layout.item_tv, saved, false)
        view.findViewById<TextView>(R.id.tvTitle).text = title
        view.findViewById<TextView>(R.id.tvSubtitle).text = subtitle
        view.findViewById<View>(R.id.tvOnline).visibility = if (online) View.VISIBLE else View.GONE
        val more = view.findViewById<View>(R.id.tvMore)
        if (onMore != null) {
            more.visibility = View.VISIBLE
            more.setOnClickListener(onMore)
        } else {
            view.findViewById<View>(R.id.tvChevron).visibility = View.VISIBLE
        }
        view.setOnClickListener {
            Haptics.tap(it)
            onClick()
        }
        return view
    }

    /** TVs get new addresses from the router now and then; follow them by name. */
    private fun rememberMovedTvs(found: List<FoundTv>) {
        for (tv in libremote.tvs.all()) {
            val match = found.firstOrNull { it.name == tv.name } ?: continue
            if (match.host != tv.host) libremote.tvs.save(tv.copy(host = match.host))
        }
    }

    private fun savedMenu(anchor: View, tv: Tv) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.devices_rename)
            menu.add(0, 2, 1, R.string.devices_forget)
            setOnMenuItemClickListener {
                if (it.itemId == 1) rename(tv) else forget(tv)
                true
            }
            show()
        }
    }

    private fun rename(tv: Tv) {
        textDialog(getString(R.string.devices_rename), null, tv.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { name ->
            if (name.isBlank()) false else {
                libremote.tvs.save(tv.copy(name = name.trim()))
                true
            }
        }
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

    private fun addByAddress() {
        textDialog(
            getString(R.string.address_title), getString(R.string.address_text), "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, hint = getString(R.string.address_hint),
            positive = R.string.address_pair, error = getString(R.string.address_invalid),
        ) { input ->
            val host = input.trim()
            if (!HOST.matches(host)) false else {
                // Use the TV's own name when discovery already knows it.
                pair(host, discovery.tvs.firstOrNull { it.host == host }?.name ?: host)
                true
            }
        }
    }

    private fun pair(host: String, name: String) {
        PairingDialog(this, host, name) { tv -> openRemote(tv) }.show()
    }

    private fun openRemote(tv: Tv) {
        RemoteActivity.open(this, tv)
        finish()
    }

    /** A one-field dialog; [accept] returns false to keep it open and show [error]. */
    private fun textDialog(
        title: String,
        message: String?,
        value: String,
        inputType: Int,
        hint: String? = null,
        positive: Int = R.string.save,
        error: String? = null,
        accept: (String) -> Boolean,
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val input = view.findViewById<EditText>(R.id.input)
        val errorView = view.findViewById<TextView>(R.id.inputError)
        view.findViewById<TextView>(R.id.inputMessage).apply {
            text = message
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        input.inputType = inputType
        input.hint = hint
        input.setText(value)
        input.setSelection(value.length)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(positive, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (accept(input.text.toString())) dialog.dismiss() else if (error != null) {
                    errorView.text = error
                    errorView.visibility = View.VISIBLE
                }
            }
            input.requestFocus()
            input.post { getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        }
        dialog.show()
    }

    companion object {
        /** Show this screen even when a TV is paired. */
        const val EXTRA_STAY = "stay"
        private const val SEARCH_HINT_MS = 8_000L

        /** An IPv4 address, or a host name such as living-room-tv.local. */
        private val HOST = Regex("""^(\d{1,3}(\.\d{1,3}){3}|[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*)$""")
    }
}
