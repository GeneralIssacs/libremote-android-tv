package app.libremotereceiver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The receiver: while this screen is open, paired phones can send files to the TV, and the files that arrived are
 * listed to open. Libremote on the phone opens this screen itself, with a link that also pairs the phone.
 */
class ReceiveActivity : Activity(), ReceiverHost.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val host get() = receiver.host
    private val confirm by lazy {
        ConfirmPhone(this) {
            if (firstQuestionsLater) {
                firstQuestionsLater = false
                firstQuestions()
            }
        }
    }
    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var allow: TextView
    private lateinit var address: TextView
    private lateinit var list: LinearLayout

    private var receiving = false

    /** The storage permission was denied for good: Allow opens the app's settings instead. */
    private var storageBlocked = false

    /**
     * The D-pad waits on a button until there are files to select: at first (Android focuses the first button
     * before the list loads), and while the list is empty. It moves to the list when files arrive.
     */
    private var focusParked = true

    /** Counts list loads, so an older one that finishes late is dropped. */
    private var loads = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)
        title = findViewById(R.id.title)
        text = findViewById(R.id.text)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        allow = findViewById(R.id.allow)
        address = findViewById(R.id.address)
        list = findViewById(R.id.list)
        findViewById<View>(R.id.settings).setOnClickListener { SettingsDialog(this).show() }
        allow.setOnClickListener { askStorage() }
        if (savedInstanceState == null) {
            // Not again when the screen is recreated: that link was dealt with.
            pair(intent)
            sayIfUpdated()
            // Not over the question about a new phone, which the phone is waiting on: after it.
            if (confirm.showing) firstQuestionsLater = true else firstQuestions()
        } else {
            // Recreated while the TV was asking: ask again, rather than leave the phone waiting for nothing.
            receiver.phones.pending()?.let(confirm::ask)
        }
    }

    /** The first-run questions wait for the answer about a new phone. */
    private var firstQuestionsLater = false

    /** After a phone updated the receiver: the first screen of the new version says so. */
    private fun sayIfUpdated() {
        val settings = receiver.settings
        val before = settings.lastVersion
        settings.lastVersion = BuildConfig.VERSION_CODE
        if (before in 1 until BuildConfig.VERSION_CODE) {
            Toast.makeText(this, getString(R.string.updated, BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pair(intent)
    }

    override fun onStart() {
        super.onStart()
        // Only while the screen is open: no port stays open in the background.
        host.addListener(this)
        host.hold(this)
        showState()
        showAddress()
        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        // Back from "Install unknown apps", which on TVs is a side panel: this screen was only paused.
        Downloads.resumeInstall(this)
    }

    override fun onStop() {
        host.release(this)
        host.removeListener(this)
        receiving = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    /** A link from a phone that isn't paired asks the user whether to allow it. */
    private fun pair(intent: Intent?) {
        receiver.phones.accept(intent?.data)?.let(confirm::ask)
    }

    override fun onDestroy() {
        confirm.dismiss()
        super.onDestroy()
    }

    // The first time the receiver opens: storage on Android 8 and 9, then the installer question. Both are dialogs
    // over this screen, so a transfer the phone started carries on behind them.

    private fun firstQuestions() {
        if (!Downloads.canSave(this)) askStorage() else askAutoInstall()
    }

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 29) return
        if (!storageBlocked) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            return
        }
        // Denied with "Don't ask again": only the app's settings can allow it now.
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return
        storageBlocked = grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        showState()
        loadFiles()
        askAutoInstall()
    }

    private fun askAutoInstall() {
        val settings = receiver.settings
        if (settings.askedAutoInstall) return
        AlertDialog.Builder(this)
            .setTitle(R.string.install_question_title)
            .setMessage(R.string.install_question_text)
            .setPositiveButton(R.string.yes) { _, _ -> settings.autoInstall = true }
            .setNegativeButton(R.string.not_now) { _, _ -> settings.autoInstall = false }
            .setOnDismissListener { settings.askedAutoInstall = true }
            .show()
    }

    // Transfer events

    override fun onListening(listening: Boolean) = showState()

    override fun onBegin(sender: String, count: Int, size: Long) {
        receiving = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title.text = getString(R.string.receiving_title, sender)
        text.text = ""
        progress.progress = 0
        progress.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = getString(R.string.receiving_progress, size(0), size(size))
    }

    override fun onFile(name: String, index: Int, count: Int) {
        if (receiving) text.text = if (count == 1) name else getString(R.string.receiving_file, name, index, count)
    }

    override fun onProgress(received: Long, total: Long) {
        if (!receiving) return
        progress.progress = if (total > 0) (received * progress.max / total).toInt() else progress.max
        progressText.text = getString(R.string.receiving_progress, size(received), size(total))
    }

    override fun onReceived(file: ReceivedFile) = loadFiles()

    override fun onUpdate(sender: String, version: String) {
        title.text = getString(R.string.update_title, version)
        text.text = getString(R.string.update_text, sender)
    }

    override fun onEnd(sender: String, count: Int, error: Throwable?) {
        receiving = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progress.visibility = View.GONE
        progressText.visibility = View.GONE
        showState()
        // What happened stays under "Ready to receive" until the next transfer.
        if (host.listening == true && Downloads.canSave(this)) {
            text.text = if (error == null) resources.getQuantityString(R.plurals.received_from, count, count, sender)
            else getString(R.string.receive_failed, sender, error.message ?: error.javaClass.simpleName)
        }
        loadFiles()
    }

    private fun showState() {
        if (receiving) return
        val canSave = Downloads.canSave(this)
        allow.visibility = if (canSave) View.GONE else View.VISIBLE
        when {
            host.listening == false -> {
                title.setText(R.string.busy_title)
                text.setText(R.string.busy_text)
            }
            !canSave -> {
                title.setText(R.string.storage_title)
                text.setText(R.string.storage_text)
            }
            host.listening == true -> {
                title.setText(R.string.ready_title)
                text.setText(R.string.ready_text)
            }
            else -> {
                title.setText(R.string.starting_title)
                text.text = ""
            }
        }
    }

    private fun showAddress() {
        val ip = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        address.text = if (ip == null) getString(R.string.address_none) else getString(R.string.address, deviceName(), ip)
    }

    /** The name set in the TV's settings, which is also how it shows up on the network. */
    private fun deviceName() =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // Received files

    private fun loadFiles() {
        // Several loads can overlap (a transfer reports each file); only the latest one is shown.
        val load = ++loads
        Thread({
            val files = runCatching { Downloads.list(this) }.getOrDefault(emptyList())
            main.post { if (!isDestroyed && load == loads) showFiles(files) }
        }, "receiver-list").start()
    }

    private fun showFiles(files: List<ReceivedFile>) {
        // Keep the D-pad on the same row when the list refreshes, unless the user moved it (to Settings, say).
        val focused = list.focusedChild?.tag
        val listHadFocus = focused != null || currentFocus == null || focusParked
        list.removeAllViews()
        focusParked = false
        if (files.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.received_empty)
                setTextColor(getColor(R.color.on_surface_muted))
                textSize = 16f
            })
            if (listHadFocus) {
                // Nothing to select in the list: wait on a button, and come back once files arrive.
                (if (allow.visibility == View.VISIBLE) allow else findViewById<View>(R.id.settings)).requestFocus()
                focusParked = true
            }
            return
        }
        for (file in files) list.addView(row(file))
        val rows = (0 until list.childCount).map(list::getChildAt)
        if (listHadFocus) (rows.firstOrNull { it.tag == focused } ?: rows.first()).requestFocus()
    }

    private fun row(file: ReceivedFile): View {
        val icon = ImageView(this).apply {
            setImageResource(iconOf(file))
            imageTintList = getColorStateList(R.color.accent)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val name = TextView(this).apply {
            text = file.name
            setTextColor(getColor(R.color.on_surface))
            textSize = 18f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val details = TextView(this).apply {
            // The time for today's files, else the date.
            text = getString(R.string.received_details, size(file.size), DateUtils.getRelativeTimeSpanString(context, file.addedMs))
            setTextColor(getColor(R.color.on_surface_muted))
            textSize = 14f
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(name)
            addView(details)
        }
        return LinearLayout(this).apply {
            tag = file.uri
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_row)
            isFocusable = true
            isClickable = true
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
            addView(icon)
            addView(texts)
            setOnClickListener { Downloads.open(this@ReceiveActivity, file) }
            setOnLongClickListener {
                confirmDelete(file)
                true
            }
        }
    }

    private fun confirmDelete(file: ReceivedFile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title, file.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Thread({
                    Downloads.delete(this, file)
                    main.post { loadFiles() }
                }, "receiver-delete").start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun iconOf(file: ReceivedFile) = when {
        file.isApk -> R.drawable.ic_android
        file.mimeType.startsWith("image/") -> R.drawable.ic_image
        file.mimeType.startsWith("video/") -> R.drawable.ic_movie
        file.mimeType.startsWith("audio/") -> R.drawable.ic_music_note
        else -> R.drawable.ic_draft
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val REQUEST_STORAGE = 1
    }
}
