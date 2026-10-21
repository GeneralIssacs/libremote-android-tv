package app.libremotetvservice.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import app.libremotetvservice.R
import app.libremotetvservice.data.Tv
import app.libremotetvservice.libremote
import app.libremotetvservice.net.SendController.Problem
import app.libremotetvservice.net.SendController.State

/**
 * Sends files to a TV: "Send to TV" in other apps' share menus, and "Send files" in the remote's menu, which picks
 * the files and hands them over here. Shows the progress, and what to do when the TV can't take them.
 */
class SendActivity : Activity() {

    private val sends get() = libremote.sends

    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var secondary: Button
    private lateinit var primary: Button

    private var tv: Tv? = null
    private var uris: List<Uri> = emptyList()

    /** Whether this screen started the batch that is on its way, rather than finding another one running. */
    private var started = false

    private val listener: (State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send)
        setFinishOnTouchOutside(false)
        title = findViewById(R.id.sendTitle)
        text = findViewById(R.id.sendText)
        progress = findViewById(R.id.sendProgress)
        status = findViewById(R.id.sendStatus)
        secondary = findViewById(R.id.sendSecondary)
        primary = findViewById(R.id.sendPrimary)

        uris = urisFrom(intent)
        val pin = savedInstanceState?.getString(STATE_PIN) ?: intent.getStringExtra(EXTRA_PIN)
        tv = pin?.let(libremote.tvs::get) ?: libremote.tvs.last()
        started = savedInstanceState?.getBoolean(STATE_STARTED) == true
        // From the notification: show the batch on its way, or how it ended.
        if (intent.getBooleanExtra(EXTRA_SHOW, false)) {
            val state = sends.state
            started = state !is State.Idle
            tv = stateTv(state) ?: tv
            uris = sends.lastUris
            return
        }
        // The TV is known (from the remote screen, or there is only one): no need to ask.
        val tv = tv
        if (savedInstanceState == null && tv != null && uris.isNotEmpty() && !sends.busy &&
            (intent.hasExtra(EXTRA_PIN) || libremote.tvs.all().size == 1)
        ) {
            start(tv)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_STARTED, started)
        tv?.let { outState.putString(STATE_PIN, it.pin) }
    }

    override fun onStart() {
        super.onStart()
        sends.addListener(listener)
    }

    override fun onStop() {
        sends.removeListener(listener)
        super.onStop()
    }

    private fun start(tv: Tv, retryAbsent: Boolean = false) {
        started = sends.send(tv, uris, retryAbsent)
        if (!started) return
        libremote.tvs.touch(tv.pin)
        // On the first send, ask to show its progress as a notification. Declined, sending works all the same.
        if (Build.VERSION.SDK_INT >= 33 && !sends.askedNotifications &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            sends.askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun stateTv(state: State): Tv? = when (state) {
        is State.Idle -> null
        is State.Preparing -> state.tv
        is State.OpeningReceiver -> state.tv
        is State.Confirming -> state.tv
        is State.Sending -> state.tv
        is State.Sent -> state.tv
        is State.Failed -> state.tv
    }

    private fun render(state: State) {
        val tv = tv
        val busy = sends.busy
        if (busy && started) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when {
            tv == null -> showMessage(getString(R.string.send_no_tv_title), getString(R.string.send_no_tv_text), R.string.add_tv) {
                startActivity(Intent(this, DevicesActivity::class.java).putExtra(DevicesActivity.EXTRA_STAY, true))
                finish()
            }
            uris.isEmpty() && !started -> showMessage(getString(R.string.send_nothing_title), getString(R.string.send_nothing_text), R.string.ok) { finish() }
            busy && !started -> showMessage(getString(R.string.send_busy_title), getString(R.string.send_busy_text), R.string.ok) { finish() }
            !started -> showReady(tv)
            else -> showTransfer(state)
        }
    }

    /** Several TVs, and nothing said which: let the user check before sending. */
    private fun showReady(tv: Tv) {
        title.text = getString(R.string.send_title, tv.name)
        text.text = resources.getQuantityString(R.plurals.send_file_count, uris.size, uris.size)
        progress.visibility = View.GONE
        status.visibility = View.GONE
        secondary.visibility = View.VISIBLE
        primary.visibility = View.VISIBLE
        if (libremote.tvs.all().size > 1) {
            secondary.setText(R.string.send_change_tv)
            secondary.setOnClickListener { chooseTv() }
        } else {
            secondary.setText(R.string.cancel)
            secondary.setOnClickListener { finish() }
        }
        primary.setText(R.string.send_button)
        primary.setOnClickListener { start(tv) }
    }

    private fun showTransfer(state: State) {
        primary.visibility = View.VISIBLE
        secondary.visibility = View.VISIBLE
        when (state) {
            // Cancelled.
            is State.Idle -> finish()
            is State.Preparing -> showWorking(state.tv, getString(R.string.send_preparing))
            is State.OpeningReceiver -> showWorking(state.tv, getString(R.string.send_opening_receiver))
            is State.Confirming -> {
                showWorking(state.tv, getString(R.string.send_confirm_text, state.code))
                title.text = getString(R.string.send_confirm_title, state.tv.name)
            }
            is State.Sending -> {
                showWorking(state.tv, null)
                text.text = if (state.names.size == 1) state.names[0]
                else getString(R.string.send_current_file, state.names[state.file], state.file + 1, state.names.size)
                progress.isIndeterminate = false
                progress.max = PROGRESS_STEPS
                progress.progress = if (state.total > 0) (state.sent * PROGRESS_STEPS / state.total).toInt() else PROGRESS_STEPS
                status.text = getString(R.string.send_progress, size(state.sent), size(state.total))
            }
            is State.Sent -> {
                title.text = getString(R.string.send_sent_title, state.tv.name)
                text.text = getString(R.string.send_sent_text)
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.text = resources.getQuantityString(R.plurals.send_sent_summary, state.count, state.count, size(state.total))
                secondary.visibility = View.GONE
                primary.setText(R.string.send_done)
                primary.setOnClickListener { close() }
            }
            is State.Failed -> showFailure(state)
        }
    }

    private fun showWorking(tv: Tv, message: String?) {
        title.text = getString(R.string.send_title_sending, tv.name)
        if (message != null) {
            text.text = message
            progress.isIndeterminate = true
            status.text = ""
        }
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        // Leaving this screen keeps the transfer going; Cancel stops it.
        secondary.setText(R.string.cancel)
        secondary.setOnClickListener { sends.cancel() }
        primary.visibility = View.GONE
    }

    private fun showFailure(state: State.Failed) {
        val name = state.tv.name
        val (heading, body) = when (state.problem) {
            // The "isn't on this TV" sheet: how to get the receiver, and Try again, which asks the TV once more.
            Problem.NO_RECEIVER -> getString(R.string.send_no_receiver_title, name) to getString(R.string.send_no_receiver_text)
            Problem.CANT_OPEN -> getString(R.string.send_cant_open_title, name) to getString(R.string.send_cant_open_text)
            Problem.NOT_LISTENING -> getString(R.string.send_not_listening_title, name) to getString(R.string.send_not_listening_text)
            Problem.UNVERIFIED -> getString(R.string.send_unverified_title, name) to getString(R.string.send_unverified_text)
            Problem.DECLINED -> getString(R.string.send_declined_title, name) to getString(R.string.send_declined_text)
            Problem.UNREACHABLE -> getString(R.string.problem_unreachable_title, name) to getString(R.string.problem_unreachable_text)
            Problem.UNREADABLE -> getString(R.string.send_unreadable_title, state.detail ?: "") to getString(R.string.send_unreadable_text)
            Problem.REFUSED -> getString(R.string.send_refused_title, name) to getString(R.string.send_refused_text, state.detail ?: "")
            Problem.STALLED -> getString(R.string.send_stalled_title, name) to getString(R.string.send_stalled_text)
            Problem.LOST -> getString(R.string.send_lost_title, name) to resources.lostText(state.lost, state.detail ?: "")
        }
        title.text = heading
        text.text = body
        progress.visibility = View.GONE
        status.visibility = View.GONE
        secondary.setText(R.string.close)
        secondary.setOnClickListener { close() }
        primary.setText(R.string.pair_retry)
        primary.visibility = if (uris.isEmpty()) View.GONE else View.VISIBLE
        primary.setOnClickListener {
            // The same files, less those that arrived before a connection broke.
            uris = sends.lastUris.ifEmpty { uris }
            // Forget the failed batch without reading its Idle state as "cancelled", which closes this screen.
            started = false
            sends.dismiss()
            start(state.tv, retryAbsent = state.problem == Problem.NO_RECEIVER)
        }
    }

    private fun showMessage(heading: String, body: String, action: Int, onAction: () -> Unit) {
        title.text = heading
        text.text = body
        progress.visibility = View.GONE
        status.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.visibility = View.VISIBLE
        primary.setText(action)
        primary.setOnClickListener { onAction() }
    }

    private fun chooseTv() {
        val tvs = libremote.tvs.all()
        AlertDialog.Builder(this)
            .setTitle(R.string.switch_tv)
            .setSingleChoiceItems(tvs.map { it.name }.toTypedArray(), tvs.indexOfFirst { it.pin == tv?.pin }) { dialog, which ->
                dialog.dismiss()
                tv = tvs[which]
                render(sends.state)
            }
            .show()
    }

    /** A finished batch is done with: the next screen starts fresh. */
    private fun close() {
        sends.dismiss()
        finish()
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

    companion object {
        const val EXTRA_PIN = "pin"

        /** Shows the latest batch, from its notification. */
        const val EXTRA_SHOW = "show"
        private const val REQUEST_NOTIFICATIONS = 1
        private const val STATE_PIN = "pin"
        private const val STATE_STARTED = "started"
        private const val PROGRESS_STEPS = 1000

        /** Sends [uris], picked in the remote screen, to [tv]; passes on the permission to read them. */
        fun open(context: Context, tv: Tv, uris: List<Uri>) {
            val intent = Intent(context, SendActivity::class.java)
                .setAction(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .putExtra(EXTRA_PIN, tv.pin)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            context.startActivity(intent)
        }

        /** The files in a share (ACTION_SEND or ACTION_SEND_MULTIPLE). Text and links without a file are ignored. */
        @Suppress("DEPRECATION")
        private fun urisFrom(intent: Intent): List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE -> (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ).orEmpty().filterNotNull()
            else -> emptyList()
        }.filter { it.scheme == "content" }
    }
}
