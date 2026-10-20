package app.libremotetvservice.net

import android.os.Handler
import android.os.Looper
import app.libremotetvservice.BuildConfig
import app.libremotetvservice.LibremoteApp
import app.libremotetvservice.data.Tv
import app.libremotetvservice.protocol.ClientInfo
import app.libremotetvservice.protocol.Features
import app.libremotetvservice.protocol.NotPairedException
import app.libremotetvservice.protocol.RemoteConnection
import app.libremotetvservice.protocol.TvErrorException
import app.libremotetvservice.protocol.TvIdentityChangedException
import app.libremotetvservice.protocol.TvInfo
import app.libremotetvservice.protocol.TvTextField
import app.libremotetvservice.protocol.VolumeState

/**
 * Keeps the connection to the TV the remote screen shows: connects when the screen opens, retries with backoff
 * while it stays open, and disconnects shortly after it closes, which also gives the TV its own keyboard back.
 * Everything here runs on the main thread; commands are safe to call any time and do nothing while offline.
 */
class RemoteController(private val app: LibremoteApp) {

    sealed interface State {
        data object Idle : State
        data class Connecting(val tv: Tv, val attempt: Int) : State
        data class Connected(val tv: Tv, val info: TvInfo, val features: Int) : State {
            fun supports(feature: Int) = features and feature != 0
        }
        data class Failed(val tv: Tv, val problem: Problem, val retryAtMs: Long?) : State
    }

    enum class Problem {
        /** No answer: the TV is off, on another network, or its address changed. */
        UNREACHABLE,
        /** The TV no longer trusts this phone; pair again. */
        NOT_PAIRED,
        /** A different device answers at this TV's address, or the TV was reset; pair again. */
        IDENTITY_CHANGED,
        /** The TV accepted the phone, then reported an error. */
        REFUSED,
    }

    interface Listener {
        fun onState(state: State) {}
        fun onPower(on: Boolean) {}
        fun onVolume(volume: VolumeState) {}
        fun onTextField(field: TvTextField, newField: Boolean) {}
        fun onKeyboardRequest(show: Boolean) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()
    private var connection: RemoteConnection? = null
    private var tv: Tv? = null
    private var attempt = 0
    private var wanted = false
    private var discovery: Discovery? = null

    /** How an app link went. */
    enum class LinkResult {
        /** Sent, and the TV reported no error: something on the TV took it. */
        OPENED,
        /** Nothing on the TV handles it: the TV reported an error and dropped the connection, which comes back by itself. */
        FAILED,
        /** Never sent: no connection in time, the TV can't open links, or the remote screen shows another TV. */
        UNAVAILABLE,
    }

    /** Links waiting for a connection to their TV; see [openOn]. */
    private val pendingLinks = mutableListOf<PendingLink>()

    private class PendingLink(val pin: String, val url: String, val onResult: (LinkResult) -> Unit)

    /** Links sent but not yet known to have worked: the TV only ever answers one with an error. */
    private val sentLinks = mutableListOf<SentLink>()

    private class SentLink(val connection: RemoteConnection, val onResult: (LinkResult) -> Unit)

    var state: State = State.Idle
        private set
    var power: Boolean? = null
        private set
    var volume: VolumeState? = null
        private set
    var field: TvTextField? = null
        private set

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onState(state)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** The remote screen for [tv] is visible: connect, or keep the current connection. */
    fun start(tv: Tv) {
        main.removeCallbacks(disconnect)
        main.removeCallbacks(retry)
        wanted = true
        if (this.tv?.pin == tv.pin && (connection != null || state is State.Connecting)) return
        closeConnection()
        this.tv = tv
        attempt = 0
        connect()
    }

    /** The remote screen is gone: disconnect unless it comes back soon (rotation, a quick app switch). */
    fun stop() {
        wanted = false
        main.removeCallbacks(retry)
        main.postDelayed(disconnect, GRACE_MS)
    }

    /** Try again now, after a failure. */
    fun retry() {
        if (!wanted) return
        main.removeCallbacks(retry)
        closeConnection()
        connect()
    }

    /** Drops the connection to [pin], e.g. because that TV was forgotten. */
    fun forget(pin: String) {
        if (tv?.pin != pin) return
        closeConnection()
        tv = null
        setState(State.Idle)
    }

    // Commands. RemoteConnection queues them on its own thread and drops them when offline or unsupported.

    fun press(keyCode: Int) { connection?.press(keyCode) }
    fun hold(keyCode: Int) { connection?.hold(keyCode) }
    fun release(keyCode: Int) { connection?.release(keyCode) }
    fun volumeUp() { connection?.volumeUp() }
    fun volumeDown() { connection?.volumeDown() }
    fun toggleMute() { connection?.toggleMute() }
    fun findRemote(ring: Boolean) { connection?.findRemote(ring) }
    fun setText(text: String) { connection?.setText(text) }
    fun submitText() { connection?.submitText() }

    fun supports(feature: Int) = (state as? State.Connected)?.supports(feature) == true

    /**
     * Opens [url] on the connected TV, in [packageName] when given (see RemoteConnection.openLink). The TV only
     * answers a link that fails, so [onResult] says OPENED once [LINK_SETTLE_MS] pass without an error.
     */
    fun openLink(url: String, packageName: String? = null, onResult: (LinkResult) -> Unit = {}) =
        openLink(url, packageName, storePageIfMissing = false, onResult)

    /**
     * As above. With [storePageIfMissing], a TV that doesn't see [packageName] opens its Play Store page instead of
     * ignoring the link, and a TV without the Play Store reports an error (FAILED) rather than doing nothing. So a
     * Play Store link sent with package com.android.vending and this flag shows whether the TV has the Play Store.
     */
    fun openLink(url: String, packageName: String?, storePageIfMissing: Boolean, onResult: (LinkResult) -> Unit) {
        val connection = connection
        if (connection == null || !supports(Features.APP_LINKS)) return onResult(LinkResult.UNAVAILABLE)
        connection.openLink(url, packageName, storePageIfMissing)
        val sent = SentLink(connection, onResult)
        sentLinks += sent
        main.postDelayed({ if (sentLinks.remove(sent)) onResult(LinkResult.OPENED) }, LINK_SETTLE_MS)
    }

    /**
     * Opens [url] on [tv] from outside the remote screen: over the open connection when there is one, else over one
     * made for the purpose, which then lapses as the remote screen's does when it closes. [onResult] gets
     * UNAVAILABLE when the TV can't be reached in time, or when the remote screen is open on a different TV.
     *
     * No package is named: the TV's remote service can only name apps that Android lets it see (package
     * visibility), and it reports the others as not installed. An app's own link scheme finds it without that.
     * A link no app on the TV handles makes the TV report INVALID_ARGUMENT and drop the connection.
     */
    fun openOn(tv: Tv, url: String, onResult: (LinkResult) -> Unit) {
        val current = state
        // After a drop outside the remote screen the state still says Connected, but the connection is gone.
        if (current is State.Connected && current.tv.pin == tv.pin && connection != null) return openLink(url, null, onResult)
        if (wanted && this.tv?.pin != tv.pin) return onResult(LinkResult.UNAVAILABLE)
        val link = PendingLink(tv.pin, url, onResult)
        pendingLinks += link
        main.postDelayed({ if (pendingLinks.remove(link)) onResult(LinkResult.UNAVAILABLE) }, LINK_TIMEOUT_MS)
        if (!wanted) {
            start(tv)
            stop()
        }
    }

    private fun sendPendingLinks(pin: String) {
        val ready = pendingLinks.filter { it.pin == pin }
        pendingLinks -= ready.toSet()
        ready.forEach { openLink(it.url, null, it.onResult) }
    }

    /** [closed] ended: the first link still unsettled on it failed if the TV reported an error; the rest never ran. */
    private fun settleLinks(closed: RemoteConnection, error: Throwable?) {
        val unsettled = sentLinks.filter { it.connection === closed }
        sentLinks -= unsettled.toSet()
        unsettled.forEachIndexed { i, link ->
            link.onResult(if (i == 0 && error is TvErrorException) LinkResult.FAILED else LinkResult.UNAVAILABLE)
        }
    }

    private val disconnect = Runnable {
        closeConnection()
        stopDiscovery()
        setState(State.Idle)
    }

    private val retry = Runnable { if (wanted) connect() }

    /** Identifies the latest connection attempt, so a slower, superseded one never takes over. */
    private var attemptToken = Any()

    private fun connect() {
        val tv = tv ?: return
        main.removeCallbacks(retry)
        connection?.close() // Never two connections to the TV at once.
        connection = null
        attempt++
        setState(State.Connecting(tv, attempt))
        val token = Any().also { attemptToken = it }
        // Loading the identity decrypts a key through the keystore: keep it off the main thread.
        Thread({
            val identity = runCatching { app.identity.get() }.getOrNull()
            main.post {
                if (attemptToken !== token || this.tv?.pin != tv.pin) return@post
                if (identity == null) {
                    setState(State.Failed(tv, Problem.REFUSED, null))
                    return@post
                }
                val events = Events(tv)
                val connection = RemoteConnection(tv.host, tv.pin, identity, CLIENT_INFO, events)
                events.owner = connection
                this.connection = connection
                Thread(connection::run, "libremote-reader").start()
            }
        }, "libremote-connect").start()
    }

    private fun closeConnection() {
        attemptToken = Any()
        connection?.close()
        connection = null
        power = null
        volume = null
        field = null
    }

    /** Relays connection events to the main thread, ignoring a connection that has since been replaced. */
    private inner class Events(private val tv: Tv) : RemoteConnection.Listener {
        var owner: RemoteConnection? = null

        private fun post(block: () -> Unit) {
            main.post { if (owner != null && owner === connection) block() }
        }

        override fun onReady(tv: TvInfo, features: Int) = post {
            attempt = 0
            stopDiscovery()
            app.tvs.touch(this.tv.pin)
            // A TV added by address has no name yet; its model ("BRAVIA 4K VH2") beats an IP address.
            var named = app.tvs.get(this.tv.pin) ?: this.tv
            val model = tv.model?.trim()
            if (named.name == named.host && !model.isNullOrEmpty()) {
                named = named.copy(name = model)
                app.tvs.save(named)
                this@RemoteController.tv = named
            }
            setState(State.Connected(named, tv, features))
            sendPendingLinks(named.pin)
        }

        override fun onPower(on: Boolean) = post {
            power = on
            listeners.toList().forEach { it.onPower(on) }
        }

        override fun onVolume(volume: VolumeState) = post {
            this@RemoteController.volume = volume
            listeners.toList().forEach { it.onVolume(volume) }
        }

        override fun onTextField(field: TvTextField, newField: Boolean) = post {
            this@RemoteController.field = field
            listeners.toList().forEach { it.onTextField(field, newField) }
        }

        override fun onKeyboardRequest(show: Boolean) = post {
            listeners.toList().forEach { it.onKeyboardRequest(show) }
        }

        override fun onClosed(error: Throwable?) {
            main.post {
                val current = owner != null && owner === connection
                // Drop the dead connection before telling the links, so a caller that reacts to FAILED at once
                // sees "no connection" (UNAVAILABLE) instead of sending on it.
                if (current) connection = null
                owner?.let { settleLinks(it, error) }
                if (!current) return@post
                if (error != null) recordError(error)
                if (error == null || !wanted) return@post
                val wasConnected = state is State.Connected
                val problem = when (error) {
                    is NotPairedException -> Problem.NOT_PAIRED
                    is TvIdentityChangedException -> Problem.IDENTITY_CHANGED
                    is TvErrorException -> Problem.REFUSED
                    else -> Problem.UNREACHABLE
                }
                if (problem == Problem.NOT_PAIRED || problem == Problem.IDENTITY_CHANGED) {
                    setState(State.Failed(tv, problem, null))
                    return@post
                }
                if (wasConnected) {
                    // A dropped link (Wi-Fi blip, TV restarting its service): reconnect straight away.
                    attempt = 0
                    connect()
                    return@post
                }
                val delay = BACKOFF_MS[minOf(attempt - 1, BACKOFF_MS.lastIndex)]
                setState(State.Failed(tv, problem, System.currentTimeMillis() + delay))
                main.postDelayed(retry, delay)
                if (problem == Problem.UNREACHABLE) findMovedTv(tv)
            }
        }
    }

    /** TVs usually get their address from DHCP, so look for this one by name in case it moved. */
    private fun findMovedTv(tv: Tv) {
        if (discovery != null) return
        discovery = Discovery(app) { found ->
            val match = found.firstOrNull { it.name == tv.name } ?: return@Discovery
            if (match.host != tv.host && this.tv?.pin == tv.pin) {
                // The pin still guards against a different device that happens to share the name.
                val moved = tv.copy(host = match.host)
                app.tvs.save(moved)
                this.tv = moved
                stopDiscovery()
                retry()
            }
        }.also { it.start() }
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    private fun setState(state: State) {
        this.state = state
        listeners.toList().forEach { it.onState(state) }
    }

    /** The last connection problem, for Settings › Report a problem: kept on the phone, shown only there. */
    private val diagnostics = app.getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE)

    /** What ended the last connection that failed, as "Exception: message", and when; null if none did. */
    val lastError: Pair<String, Long>?
        get() = diagnostics.getString("lastError", null)?.let { it to diagnostics.getLong("lastErrorAt", 0) }

    private fun recordError(error: Throwable) {
        val text = error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "")
        diagnostics.edit().putString("lastError", text.take(300)).putLong("lastErrorAt", System.currentTimeMillis()).apply()
    }

    private companion object {
        const val GRACE_MS = 10_000L
        const val LINK_TIMEOUT_MS = 8_000L

        /** A failing link gets its error back well within this; the TV says nothing about one that worked. */
        const val LINK_SETTLE_MS = 1_500L
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

        /**
         * What the phone tells the TV about itself. The TV adds it to the usage logs it sends Google when usage
         * reporting is on, so it says nothing about the phone: no model, maker or Android version.
         */
        val CLIENT_INFO = ClientInfo(
            model = "Libremote",
            manufacturer = "Libremote",
            osVersion = "",
            appName = "Libremote",
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}
