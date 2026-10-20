package app.libremotereceiver

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Opened by `libremote://sync?…` when a phone wants only the TV's app list: the receiver listens for a moment, with
 * a small card over whatever the TV shows, then closes when the phone says it has the list (or after a while), and
 * the TV goes back to what it was showing. The link pairs the phone, as `libremote://receive` does.
 */
class SyncActivity : Activity(), ReceiverHost.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay)
        receiver.phones.accept(intent.data)
    }

    override fun onStart() {
        super.onStart()
        receiver.host.addListener(this)
        receiver.host.hold(this)
        main.postDelayed(giveUp, MAX_OPEN_MS)
    }

    override fun onStop() {
        main.removeCallbacks(giveUp)
        receiver.host.release(this)
        receiver.host.removeListener(this)
        super.onStop()
        // Never left behind: it exists only for this one sync.
        finish()
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
