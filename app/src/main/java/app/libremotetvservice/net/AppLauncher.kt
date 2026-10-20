package app.libremotetvservice.net

import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.libremotetvservice.LibremoteApp
import app.libremotetvservice.data.Tv
import app.libremotetvservice.net.RemoteController.LinkResult
import app.libremotetvservice.net.RemoteController.State
import app.libremotetvservice.protocol.TvTextField
import app.libremotetvservice.ui.TvApps

/**
 * Opens an app on a TV through the remote connection, trying in turn what can open it there:
 *
 * 1. Libremote Receiver, when the TV is known to have it: it opens any app, without showing itself.
 * 2. The app's own link (YouTube's `vnd.youtube://`), for apps in [TvApps] that have one.
 * 3. The Play Store's launch link, unless the TV is known to have no Play Store.
 * 4. Libremote Receiver, when the TV isn't known to have it: it may have been installed since.
 *
 * Google's remote service can't see most apps (Android's package visibility), which is why a plain app link with a
 * package rarely works. A link nothing on the TV handles makes the TV drop the connection; the remote reconnects by
 * itself, and the next way is tried on the new connection. What failed is remembered per TV: a receiver that didn't
 * answer is marked absent, and a failed Play Store link marks the TV as having no Play Store. A missing app shows on
 * the TV as its Play Store page, or as the receiver naming it; either tells the phone the app isn't there.
 */
class AppLauncher(private val app: LibremoteApp) {

    enum class Outcome {
        /** The app came to the front, or the TV took the link and showed nothing else. */
        OPENED,
        /** The TV opened the app's Play Store page instead: the app isn't installed. */
        NOT_INSTALLED,
        /** Libremote Receiver said on the TV that the app isn't installed, and the TV has no Play Store. */
        NOT_ON_TV,
        /** Every way available on this TV failed. */
        FAILED,
        /** Nothing on this TV can open it: no Libremote Receiver, no Play Store, and no link of its own. */
        NEEDS_RECEIVER,
        /** Not connected to that TV, or it can't open links. */
        OFFLINE,
    }

    private enum class Way { RECEIVER, OWN_LINK, PLAY_STORE }

    private val main = Handler(Looper.getMainLooper())
    private var current: Launch? = null

    /** Whether a launch is still finding its way. */
    val busy get() = current != null

    /**
     * Opens [packageName], called [name], on [tv], which should be the TV the remote shows. Returns false, without
     * calling [onDone], while another launch is still in progress.
     */
    fun launch(tv: Tv, packageName: String, name: String, onDone: (Outcome) -> Unit): Boolean {
        if (current != null) return false
        Launch(tv, packageName, name, onDone).also { current = it }.start()
        return true
    }

    private inner class Launch(
        private val tv: Tv,
        private val packageName: String,
        private val name: String,
        private val onDone: (Outcome) -> Unit,
    ) : RemoteController.Listener {
        private val remote get() = app.remote
        private val tried = mutableSetOf<Way>()

        /** Apps the TV reported in front since the last link went out. */
        private val inFront = mutableListOf<String>()

        /** Called with the app in front while a link that seemed to work settles; null on time out. */
        private var watching: ((String?) -> Unit)? = null

        /** Waiting for a connection: called with true once it is up; the state to move past is [stale]. */
        private var connecting: ((Boolean) -> Unit)? = null
        private var stale: State? = null
        private var finished = false

        fun start() {
            remote.addListener(this)
            val state = remote.state
            val thisTv = when (state) {
                is State.Connected -> state.tv.pin == tv.pin
                is State.Connecting -> state.tv.pin == tv.pin
                is State.Failed -> state.tv.pin == tv.pin
                State.Idle -> false
            }
            if (thisTv) next() else finish(Outcome.OFFLINE)
        }

        private fun next() {
            if (finished) return
            val way = nextWay()
                ?: return finish(if (app.receivers.state(tv) == ReceiverState.ABSENT) Outcome.NEEDS_RECEIVER else Outcome.FAILED)
            tried += way
            whenConnected { up ->
                if (!up) return@whenConnected finish(Outcome.OFFLINE)
                inFront.clear()
                when (way) {
                    Way.RECEIVER -> remote.openLink(app.receivers.launchLink(packageName, name), null) { result(way, it) }
                    Way.OWN_LINK -> remote.openLink(TvApps.known(packageName)!!.scheme!!, null) { result(way, it) }
                    // With the flag, a TV without the Play Store reports an error instead of ignoring the link.
                    Way.PLAY_STORE -> remote.openLink(playLink(packageName), PLAY_STORE, storePageIfMissing = true) { result(way, it) }
                }
            }
        }

        private fun nextWay(): Way? = when {
            Way.RECEIVER !in tried && app.receivers.state(tv) == ReceiverState.PRESENT -> Way.RECEIVER
            Way.OWN_LINK !in tried && TvApps.known(packageName)?.scheme != null -> Way.OWN_LINK
            Way.PLAY_STORE !in tried && !app.shortcuts.noPlay(tv.pin) -> Way.PLAY_STORE
            // Last, as it costs a dropped connection when it isn't there; but it finds a receiver installed since.
            Way.RECEIVER !in tried -> Way.RECEIVER
            else -> null
        }

        private fun result(way: Way, result: LinkResult) {
            if (finished) return
            when (result) {
                LinkResult.OPENED -> {
                    if (way == Way.RECEIVER) app.receivers.setState(tv, ReceiverState.PRESENT)
                    settle(way)
                }
                LinkResult.UNAVAILABLE -> finish(Outcome.OFFLINE)
                LinkResult.FAILED -> {
                    when (way) {
                        Way.RECEIVER -> app.receivers.setState(tv, ReceiverState.ABSENT)
                        Way.PLAY_STORE -> app.shortcuts.setNoPlay(tv.pin, true)
                        Way.OWN_LINK -> Unit
                    }
                    // The TV dropped the connection; the controller is about to reconnect. Go on once it has.
                    stale = remote.state
                    main.post { next() }
                }
            }
        }

        /**
         * The TV took the link. If it showed the app's Play Store page instead (the Play Store link, or the receiver on
         * a TV with the Play Store), or the receiver naming the app, the app isn't installed.
         */
        private fun settle(way: Way) {
            fun verdict(front: String?) = when {
                front == packageName -> Outcome.OPENED
                front == PLAY_STORE && way != Way.OWN_LINK -> Outcome.NOT_INSTALLED
                front == RECEIVER && way == Way.RECEIVER -> Outcome.NOT_ON_TV
                else -> null
            }
            inFront.firstNotNullOfOrNull(::verdict)?.let { return finish(it) }
            watching = { front -> if (front == null) finish(Outcome.OPENED) else verdict(front)?.let(::finish) }
            main.postDelayed(watchTimeout, SETTLE_MS)
        }

        private val watchTimeout = Runnable { watching?.invoke(null) }

        private fun whenConnected(then: (Boolean) -> Unit) {
            if (isConnected()) return then(true)
            val state = remote.state
            val trying = state is State.Connecting && state.tv.pin == tv.pin || state is State.Failed && state.tv.pin == tv.pin ||
                stale != null
            if (!trying) return then(false)
            connecting = then
            main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        }

        private val connectTimeout = Runnable { connecting?.let { connecting = null; it(false) } }

        private fun isConnected(): Boolean {
            val state = remote.state
            return state is State.Connected && state.tv.pin == tv.pin && state !== stale
        }

        override fun onState(state: State) {
            val waiting = connecting ?: return
            if (isConnected()) {
                connecting = null
                stale = null
                main.removeCallbacks(connectTimeout)
                waiting(true)
            }
        }

        override fun onTextField(field: TvTextField, newField: Boolean) {
            val front = field.packageName ?: return
            if (!newField) return
            inFront += front
            watching?.invoke(front)
        }

        private fun finish(outcome: Outcome) {
            if (finished) return
            finished = true
            watching = null
            connecting = null
            main.removeCallbacks(watchTimeout)
            main.removeCallbacks(connectTimeout)
            remote.removeListener(this)
            if (current === this) current = null
            onDone(outcome)
        }
    }

    companion object {
        const val PLAY_STORE = "com.android.vending"
        const val RECEIVER = "app.libremotereceiver"

        /** How long to wait for the TV to show the app after it took the link. */
        private const val SETTLE_MS = 2_500L

        /** How long to wait for the connection to come back after a link that failed. */
        private const val CONNECT_TIMEOUT_MS = 8_000L

        /** The Play Store opens the installed app, or its store page when it isn't installed. */
        fun playLink(packageName: String) = "https://play.google.com/apps/launch?id=" + Uri.encode(packageName)
    }
}
