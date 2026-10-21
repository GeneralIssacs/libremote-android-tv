package app.libremotereceiver

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Opened by `libremote://sync?…` when a phone wants only the TV's app list: the receiver listens for a moment, with
 * a small card over whatever the TV shows, then closes when the phone says it has the list (or after a while), and
 * the TV goes back to what it was showing. A phone that isn't paired is asked about first, as `libremote://receive`
 * does; the card waits for the answer.
 */
class SyncActivity : Activity(), ReceiverHost.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { finish() }
    // Allowed, the phone has a while to fetch the list; not allowed, there is nothing to wait for.
    private val confirm by lazy { ConfirmPhone(this) { paired -> if (paired) waitForPhone() else finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay)
        receiver.phones.accept(intent.data)?.let(confirm::ask)
    }

    override fun onStart() {
        super.onStart()
        receiver.host.addListener(this)
        receiver.host.hold(this)
        if (!confirm.showing) waitForPhone()
    }

    override fun onStop() {
        main.removeCallbacks(giveUp)
        confirm.dismiss()
        receiver.host.release(this)
        receiver.host.removeListener(this)
        super.onStop()
        // Never left behind: it exists only for this one sync.
        finish()
    }

    /** From now (or the user's answer about the phone), the phone has a while to fetch the list. */
    private fun waitForPhone() {
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, MAX_OPEN_MS)
    }

    override fun onListening(listening: Boolean) {
        if (!listening) finish()
    }

    override fun onCloseRequested() = finish()

    private companion object {
        /** Long enough for a slow TV to list its apps; the phone normally closes it within seconds. */
        const val MAX_OPEN_MS = 20_000L
    }
}
