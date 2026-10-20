package app.libremotetvservice.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.libremotetvservice.R
import app.libremotetvservice.libremote
import app.libremotetvservice.data.PadMode
import app.libremotetvservice.data.Tv
import app.libremotetvservice.data.TvKind
import app.libremotetvservice.net.RemoteController
import app.libremotetvservice.net.RemoteController.Problem
import app.libremotetvservice.net.RemoteController.State
import app.libremotetvservice.protocol.Features
import app.libremotetvservice.protocol.KeyCodes
import app.libremotetvservice.protocol.TvInfo
import app.libremotetvservice.protocol.TvTextField
import app.libremotetvservice.protocol.VolumeState
import app.libremotetvservice.update.Updater

/** The remote itself, for one paired TV. */
class RemoteActivity : Activity(), RemoteController.Listener {

    private val remote get() = libremote.remote
    private val settings get() = libremote.settings
    private val main = Handler(Looper.getMainLooper())
    private lateinit var tv: Tv

    private lateinit var tvName: TextView
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var power: ImageButton
    private lateinit var banner: View
    private lateinit var bannerTitle: TextView
    private lateinit var bannerText: TextView
    private lateinit var bannerCountdown: TextView
    private lateinit var bannerAction: Button
    private lateinit var padArea: View
    private lateinit var pad: PadLayout
    private lateinit var modes: Map<PadMode, TextView>
    private lateinit var keyTop: ImageButton
    private lateinit var keyBottom: ImageButton
    private lateinit var extraSearch: View
    private lateinit var extraApps: View
    private lateinit var navRow: View
    private lateinit var mediaRow: View
    private lateinit var shortcuts: ShortcutsView
    private lateinit var keyboardBar: View
    private lateinit var text: EditText
    private lateinit var textSend: ImageButton
    private lateinit var volumeHud: View
    private lateinit var volumeBar: ProgressBar
    private lateinit var volumeText: TextView
    private lateinit var volumeIcon: ImageView

    /** The right-hand column's set, once shown; see showKind. */
    private var shownKind: TvKind? = null
    private var keyboardOpen = false
    private var keyboardFromTv = false
    private var applyingTvText = false
    private var lastVolume: VolumeState? = null

    /** Back closes the keyboard bar first (Android 13+; older versions use onBackPressed). */
    private val closeKeyboardOnBack: Any? =
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { closeKeyboard() } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = tvFrom(intent)
        if (tv == null) {
            startActivity(Intent(this, DevicesActivity::class.java))
            finish()
            return
        }
        this.tv = tv
        setContentView(R.layout.activity_remote)
        edgeToEdge(findViewById(R.id.root))
        bind()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = tvFrom(intent) ?: return
        if (next.pin == tv.pin) return
        tv = next
        closeKeyboard()
        lastVolume = null
        showKind()
        shortcuts.bind(tv)
        remote.start(tv)
        render(remote.state)
    }

    private fun tvFrom(intent: Intent): Tv? {
        val pin = intent.getStringExtra(EXTRA_PIN) ?: libremote.tvs.last()?.pin ?: return null
        return libremote.tvs.get(pin)
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        // A forgotten or replaced TV (after pairing again) leaves this screen pointing at a stale entry.
        libremote.tvs.get(tv.pin)?.let { tv = it } ?: libremote.tvs.last()?.let { tv = it } ?: run {
            startActivity(Intent(this, DevicesActivity::class.java))
            finish()
            return
        }
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showMode(settings.padMode)
        showKind()
        shortcuts.bind(tv)
        remote.start(tv)
        // After a rotation the connection is still up: pick up its state without replaying it as news.
        lastVolume = remote.volume
        remote.power?.let(::onPower)
        remote.addListener(this)
        // At most once a day, and only if the user turned it on (standard build only).
        Updater.checkOnStart(this, tv)
    }

    override fun onStop() {
        if (!::tv.isInitialized) return super.onStop()
        // An Apps press still holding Home ends now, while the connection is still there.
        if (holdingHome) {
            main.removeCallbacks(releaseHome)
            releaseHome.run()
        }
        remote.removeListener(this)
        remote.stop()
        main.removeCallbacks(countdown)
        super.onStop()
    }

    private fun bind() {
        tvName = findViewById(R.id.tvName)
        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        power = findViewById(R.id.power)
        banner = findViewById(R.id.banner)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerText = findViewById(R.id.bannerText)
        bannerCountdown = findViewById(R.id.bannerCountdown)
        bannerAction = findViewById(R.id.bannerAction)
        padArea = findViewById(R.id.padArea)
        pad = findViewById(R.id.pad)
        modes = mapOf(
            PadMode.FULL to findViewById(R.id.modeFull),
            PadMode.DPAD to findViewById(R.id.modeDpad),
            PadMode.TOUCHPAD to findViewById(R.id.modeTouch),
        )
        keyTop = findViewById(R.id.keyTop)
        keyBottom = findViewById(R.id.keyBottom)
        extraSearch = findViewById(R.id.extraSearch)
        extraApps = findViewById(R.id.extraApps)
        navRow = findViewById(R.id.navRow)
        mediaRow = findViewById(R.id.mediaRow)
        shortcuts = findViewById(R.id.appsScroll)
        keyboardBar = findViewById(R.id.keyboardBar)
        text = findViewById(R.id.text)
        textSend = findViewById(R.id.textSend)
        volumeHud = findViewById(R.id.volumeHud)
        volumeBar = findViewById(R.id.volumeBar)
        volumeText = findViewById(R.id.volumeText)
        volumeIcon = findViewById(R.id.volumeIcon)

        // When the keyboard opens or resizes the screen, keep the typing bar in view even if the remote scrolls.
        findViewById<View>(R.id.root).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (keyboardOpen) keyboardBar.post {
                keyboardBar.requestRectangleOnScreen(android.graphics.Rect(0, 0, keyboardBar.width, keyboardBar.height), true)
            }
        }
        findViewById<View>(R.id.tvSwitcher).setOnClickListener { chooseTv() }
        findViewById<View>(R.id.sendFiles).setOnClickListener {
            Haptics.tap(it)
            pickFiles()
        }
        keyButton(power, KeyCodes.POWER)
        findViewById<View>(R.id.settings).setOnClickListener {
            Haptics.tap(it)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val pads = object : DpadView.Listener {
            override fun onClick(keyCode: Int) { remote.press(keyCode) }
            override fun onHold(keyCode: Int) { remote.hold(keyCode) }
            override fun onRelease(keyCode: Int) { remote.release(keyCode) }
        }
        findViewById<DpadView>(R.id.dpad).listener = pads
        findViewById<TouchpadView>(R.id.touchpad).listener = pads
        findViewById<TouchpadView>(R.id.strip).listener = pads
        modes.forEach { (mode, view) -> view.setOnClickListener { setMode(mode) } }

        // Left column, and the right one's middle key, which is the same on every TV (showKind sets the others).
        KeyTouch(findViewById(R.id.volumeUp), onClick = { remote.volumeUp() }, onRepeat = { remote.volumeUp() })
        KeyTouch(findViewById(R.id.mute), onClick = { remote.toggleMute() })
        KeyTouch(findViewById(R.id.volumeDown), onClick = { remote.volumeDown() }, onRepeat = { remote.volumeDown() })
        keyButton(findViewById(R.id.keyMenu), KeyCodes.MENU)

        // Full's extra keys: a tap each, no hold, as agreed for this row. Search and Apps hide when the column has them.
        tapKey(extraSearch, KeyCodes.SEARCH)
        tapKey(findViewById(R.id.extraCaptions), KeyCodes.CAPTIONS)
        tapKey(findViewById(R.id.extraInfo), KeyCodes.INFO)
        tapAction(extraApps, ::openApps)
        tapKey(findViewById(R.id.extraGuide), KeyCodes.GUIDE)
        tapKey(findViewById(R.id.extraSettings), KeyCodes.SETTINGS)
        tapKey(findViewById(R.id.extraInput), KeyCodes.TV_INPUT)

        // A pill: it clips its keys' ripples to its rounded shape (android:clipToOutline only exists from Android 12).
        mediaRow.clipToOutline = true
        keyButton(findViewById(R.id.previous), KeyCodes.MEDIA_PREVIOUS)
        keyButton(findViewById(R.id.rewind), KeyCodes.MEDIA_REWIND)
        keyButton(findViewById(R.id.playPause), KeyCodes.MEDIA_PLAY_PAUSE)
        keyButton(findViewById(R.id.fastForward), KeyCodes.MEDIA_FAST_FORWARD)
        keyButton(findViewById(R.id.next), KeyCodes.MEDIA_NEXT)

        keyButton(findViewById(R.id.back), KeyCodes.BACK)
        keyButton(findViewById(R.id.home), KeyCodes.HOME)
        KeyTouch(findViewById(R.id.keyboard), onClick = { if (keyboardOpen) closeKeyboard() else openKeyboard(fromTv = false) })

        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (!applyingTvText) remote.setText(s.toString())
            }
        })
        text.setOnEditorActionListener { _, action, event ->
            val enter = action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) remote.submitText()
            enter
        }
        textSend.setOnClickListener {
            Haptics.tap(it)
            remote.submitText()
        }
        findViewById<View>(R.id.textClear).setOnClickListener {
            Haptics.tap(it)
            text.setText("")
        }
        findViewById<View>(R.id.textHide).setOnClickListener { closeKeyboard() }
    }

    /** A remote button: tap to press, hold for the TV's long press. */
    private fun keyButton(view: View, keyCode: Int) {
        view.setOnTouchListener(null)
        KeyTouch(view, onClick = { remote.press(keyCode) }, onHold = { remote.hold(keyCode) }, onRelease = { remote.release(keyCode) })
    }

    /** A button that only presses its key. */
    private fun tapKey(view: View, keyCode: Int) = tapAction(view) { remote.press(keyCode) }

    private fun tapAction(view: View, action: () -> Unit) {
        view.setOnTouchListener(null)
        KeyTouch(view, onClick = action)
    }

    /** Apps: holds Home long enough for the TV's long press, which opens its list of apps. */
    private fun openApps() {
        if (holdingHome) return
        holdingHome = true
        remote.hold(KeyCodes.HOME)
        main.postDelayed(releaseHome, APPS_HOLD_MS)
    }

    private var holdingHome = false
    private val releaseHome = Runnable {
        holdingHome = false
        remote.release(KeyCodes.HOME)
    }

    private fun setMode(mode: PadMode) {
        settings.padMode = mode
        showMode(mode)
    }

    private fun showMode(mode: PadMode) {
        pad.mode = mode
        modes.forEach { (it, view) -> view.isSelected = it == mode }
    }

    /**
     * The right-hand column for this TV: Channel +, Menu, Channel − on a TV, Search, Menu, Apps on a box (TvKind). Full's
     * extra keys leave out the ones the column has. [info] is what the TV just reported, when it is connecting.
     */
    private fun showKind(info: TvInfo? = null) {
        val kind = libremote.tvDetails.kind(tv.pin, info)
        if (kind == shownKind) return
        shownKind = kind
        val box = kind == TvKind.BOX
        if (box) {
            columnKey(keyTop, R.drawable.ic_search, R.string.key_search)
            keyButton(keyTop, KeyCodes.SEARCH)
            columnKey(keyBottom, R.drawable.ic_apps, R.string.key_apps)
            tapAction(keyBottom, ::openApps)
        } else {
            columnKey(keyTop, R.drawable.ic_add, R.string.key_channel_up)
            keyButton(keyTop, KeyCodes.CHANNEL_UP)
            columnKey(keyBottom, R.drawable.ic_remove, R.string.key_channel_down)
            keyButton(keyBottom, KeyCodes.CHANNEL_DOWN)
        }
        extraSearch.visibility = if (box) View.GONE else View.VISIBLE
        extraApps.visibility = if (box) View.GONE else View.VISIBLE
    }

    private fun columnKey(button: ImageButton, icon: Int, label: Int) {
        button.setImageResource(icon)
        button.contentDescription = getString(label)
    }

    // Connection state

    override fun onState(state: State) = render(state)

    private fun render(state: State) {
        // The controller may have renamed the TV (see RemoteController.onReady).
        (state as? State.Connected)?.tv?.takeIf { it.pin == tv.pin }?.let { tv = it }
        tvName.text = tv.name
        main.removeCallbacks(countdown)
        val connected = state is State.Connected
        val alpha = if (connected) 1f else 0.4f
        listOf(padArea, navRow, mediaRow, shortcuts, power).forEach { it.animate().alpha(alpha).setDuration(150).start() }
        when (state) {
            is State.Idle -> setStatus(getString(R.string.status_offline), R.color.on_surface_muted)
            is State.Connecting -> {
                setStatus(getString(if (state.attempt > 1) R.string.status_reconnecting else R.string.status_connecting), R.color.warn)
                if (state.attempt <= 1) banner.visibility = View.GONE else bannerCountdown.text = ""
            }
            is State.Connected -> {
                banner.visibility = View.GONE
                // The first connection says what the TV is: a box swaps the channel keys for Search and Apps.
                if (state.tv.pin == tv.pin) showKind(state.info)
                renderConnectedStatus()
            }
            is State.Failed -> {
                setStatus(getString(R.string.status_offline), R.color.error)
                showProblem(state)
            }
        }
    }

    private fun renderConnectedStatus() {
        if (remote.state !is State.Connected) return
        val app = AppLabels.statusName(libremote, tv, remote.field?.packageName)
        when {
            remote.power == false -> setStatus(getString(R.string.status_screen_off), R.color.warn)
            app != null -> setStatus(getString(R.string.status_connected_app, app), R.color.ok)
            else -> setStatus(getString(R.string.status_connected), R.color.ok)
        }
    }

    private fun setStatus(label: String, color: Int) {
        status.text = label
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(color))
    }

    private fun showProblem(state: State.Failed) {
        banner.visibility = View.VISIBLE
        val (title, body) = when (state.problem) {
            Problem.UNREACHABLE -> R.string.problem_unreachable_title to R.string.problem_unreachable_text
            Problem.NOT_PAIRED -> R.string.problem_not_paired_title to R.string.problem_not_paired_text
            Problem.IDENTITY_CHANGED -> R.string.problem_identity_title to R.string.problem_identity_text
            Problem.REFUSED -> R.string.problem_refused_title to R.string.problem_refused_text
        }
        bannerTitle.text = getString(title, tv.name)
        bannerText.setText(body)
        if (state.problem == Problem.NOT_PAIRED || state.problem == Problem.IDENTITY_CHANGED) {
            bannerAction.setText(R.string.pair_again)
            bannerAction.setOnClickListener {
                PairingDialog(this, tv.host, tv.name, replacing = tv.pin) { paired ->
                    tv = paired
                    remote.start(paired)
                }.show()
            }
            bannerCountdown.text = ""
        } else {
            bannerAction.setText(R.string.retry)
            bannerAction.setOnClickListener { remote.retry() }
            retryAt = state.retryAtMs
            countdown.run()
        }
    }

    private var retryAt: Long? = null

    private val countdown: Runnable = object : Runnable {
        override fun run() {
            val at = retryAt ?: return
            val seconds = ((at - System.currentTimeMillis() + 999) / 1000).toInt()
            bannerCountdown.text = if (seconds > 0) getString(R.string.retrying_in, seconds) else ""
            if (seconds > 0) main.postDelayed(this, 500)
        }
    }

    override fun onPower(on: Boolean) {
        power.backgroundTintList = if (on) null else ColorStateList.valueOf(getColor(R.color.accent))
        power.imageTintList = ColorStateList.valueOf(getColor(if (on) R.color.on_accent_container else R.color.on_accent))
        renderConnectedStatus()
    }

    // Volume

    override fun onVolume(volume: VolumeState) {
        val previous = lastVolume
        lastVolume = volume
        // The first report arrives on connecting; only changes after that are worth showing.
        if (previous == null || previous == volume) return
        showVolume(volume)
    }

    private fun showVolume(volume: VolumeState) {
        volumeIcon.setImageResource(if (volume.muted || volume.level == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        if (volume.absolute) {
            volumeBar.visibility = View.VISIBLE
            volumeBar.max = volume.max
            volumeBar.progress = if (volume.muted) 0 else volume.level
            volumeText.text = if (volume.muted) getString(R.string.volume_muted) else volume.level.toString()
        } else {
            volumeBar.visibility = View.GONE
            volumeText.text = getString(if (volume.muted) R.string.volume_muted else R.string.volume)
        }
        volumeHud.animate().cancel()
        volumeHud.visibility = View.VISIBLE
        volumeHud.animate().alpha(1f).setDuration(120).start()
        main.removeCallbacks(hideVolume)
        main.postDelayed(hideVolume, 1600)
    }

    private val hideVolume = Runnable {
        volumeHud.animate().alpha(0f).setDuration(250).withEndAction { volumeHud.visibility = View.GONE }.start()
    }

    /** The phone's own volume buttons drive the TV while the remote is in front. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (settings.volumeKeys && remote.state is State.Connected) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { remote.volumeUp(); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { remote.volumeDown(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (settings.volumeKeys && remote.state is State.Connected &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    // Typing

    override fun onTextField(field: TvTextField, newField: Boolean) {
        if (newField) {
            renderConnectedStatus()
            if (field.isEditable) {
                text.hint = field.hint ?: field.label ?: getString(R.string.keyboard_hint)
                textSend.setImageResource(if (field.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH) R.drawable.ic_search else R.drawable.ic_keyboard_return)
                showTvText(field.text)
            } else if (keyboardOpen && keyboardFromTv) {
                closeKeyboard()
            }
        } else if (field.isEditable) {
            // Another remote (or the TV's own keyboard) changed the text.
            showTvText(field.text)
        }
    }

    override fun onKeyboardRequest(show: Boolean) {
        if (show && settings.autoKeyboard && remote.field?.isEditable == true) {
            if (!keyboardOpen) openKeyboard(fromTv = true)
        } else if (!show && keyboardOpen && keyboardFromTv) {
            closeKeyboard()
        }
    }

    private fun showTvText(value: String) {
        if (text.text.toString() == value) return
        applyingTvText = true
        text.setText(value)
        text.setSelection(value.length)
        applyingTvText = false
    }

    private fun openKeyboard(fromTv: Boolean) {
        keyboardOpen = true
        keyboardFromTv = fromTv
        remote.field?.takeIf { it.isEditable }?.let { showTvText(it.text) }
        keyboardBar.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeKeyboardOnBack as OnBackInvokedCallback,
        )
        // Make room: the pad and navigation stay; playback, app shortcuts and Full's strip and extra keys step aside
        // while typing, and the pad may shrink below its usual minimum rather than push the keyboard bar out of view.
        mediaRow.visibility = View.GONE
        shortcuts.stepAside = true
        pad.typing = true
        text.requestFocus()
        text.post { getSystemService(InputMethodManager::class.java).showSoftInput(text, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun closeKeyboard() {
        if (!keyboardOpen) return
        keyboardOpen = false
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(text.windowToken, 0)
        keyboardBar.visibility = View.GONE
        mediaRow.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closeKeyboardOnBack as OnBackInvokedCallback)
        shortcuts.stepAside = false
        pad.typing = false
        text.clearFocus()
    }

    // Back before Android 13. From 13 on, the system calls closeKeyboardOnBack instead (see openKeyboard).
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (keyboardOpen) closeKeyboard() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // Top bar

    /** The system's file picker: any file, several at once, with no storage permission needed. */
    private fun pickFiles() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(pick, REQUEST_PICK)
        } catch (e: ActivityNotFoundException) {
            // No document picker (rare, some stripped-down builds); sharing from another app still works.
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return
        val clip = data.clipData
        val uris = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else listOfNotNull(data.data)
        if (uris.isNotEmpty()) SendActivity.open(this, tv, uris)
    }

    private fun findRemote() {
        remote.findRemote(true)
        AlertDialog.Builder(this)
            .setTitle(R.string.find_remote_ringing)
            .setPositiveButton(R.string.find_remote_stop, null)
            .setOnDismissListener { remote.findRemote(false) }
            .show()
    }

    /** The ▾ by the TV's name: switch TV, add a TV, Find my remote (when the TV offers it), the right-hand keys, About. */
    private fun chooseTv() {
        TvPickerSheet(
            this, tv,
            onSwitch = { chosen -> onNewIntent(Intent().putExtra(EXTRA_PIN, chosen.pin)) },
            onFindRemote = if (remote.supports(Features.FIND_MY_REMOTE)) ::findRemote else null,
            onKindChanged = { showKind() },
        ).show()
    }

    companion object {
        const val EXTRA_PIN = "pin"
        private const val REQUEST_PICK = 1

        /** How long Apps holds Home: past the TV's long-press delay (400 to 500 ms by default) with room to spare. */
        private const val APPS_HOLD_MS = 1_000L

        fun open(context: Context, tv: Tv) {
            context.startActivity(Intent(context, RemoteActivity::class.java).putExtra(EXTRA_PIN, tv.pin))
        }
    }
}
