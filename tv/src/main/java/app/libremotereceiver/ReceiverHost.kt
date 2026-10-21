package app.libremotereceiver

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.libremotetvservice.protocol.FileReceiver
import app.libremotetvservice.protocol.InstalledApp
import app.libremotetvservice.protocol.ReceiverServer
import java.util.concurrent.Executors

/**
 * Runs the receiver's server (ReceiverServer, on its fixed port) while at least one screen holds it: the receiver
 * screen, or the brief one a phone opens to fetch the app list. Nothing listens otherwise. Listener calls come on
 * the main thread.
 */
class ReceiverHost(private val app: ReceiverApplication) {

    interface Listener {
        /** False when another app holds the port. */
        fun onListening(listening: Boolean) {}
        fun onBegin(sender: String, count: Int, size: Long) {}
        fun onFile(name: String, index: Int, count: Int) {}
        fun onProgress(received: Long, total: Long) {}
        fun onReceived(file: ReceivedFile) {}

        /** The transfer ended: [error] is null when every file arrived. */
        fun onEnd(sender: String, count: Int, error: Throwable?) {}

        /** A phone that opened the receiver only to fetch the app list has it. */
        fun onCloseRequested() {}

        /** [sender] sent [version] of the receiver itself, and the installer is opening for it. */
        fun onUpdate(sender: String, version: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val holders = LinkedHashSet<Activity>()
    private val listeners = mutableListOf<Listener>()

    /** Starts and stops the server in the order asked, off the main thread: loading the identity takes a moment. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "receiver-host") }

    /** Only touched on [worker]. */
    private var server: ReceiverServer? = null

    init {
        // What a transfer cut short last time left behind: only what is older than this start, so a transfer that
        // begins meanwhile is left alone, and the server needn't wait for it.
        val started = System.currentTimeMillis()
        Thread({
            runCatching { SelfUpdate.cleanUp(app, started) }
            runCatching { Downloads.sweep(app, started) }
        }, "receiver-sweep").start()
    }

    /** Null while starting or stopped. */
    var listening: Boolean? = null
        private set

    /** The latest APK received while the installer should open by itself, and the phone it came from. */
    private var pendingInstall: Pair<String, ReceivedFile>? = null

    /** [activity] needs the receiver listening, from its onStart until [release]. */
    fun hold(activity: Activity) {
        holders += activity
        update()
    }

    fun release(activity: Activity) {
        holders -= activity
        update()
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Drops the connections of a phone that was just unpaired. */
    fun disconnect(phoneKey: String) = worker.execute { server?.disconnect(phoneKey) }

    private fun update() {
        val wanted = holders.isNotEmpty()
        worker.execute {
            if (wanted && server == null) {
                val started = runCatching {
                    ReceiverServer(app.identity.get(), app.phones, Events(), appVersion = BuildConfig.VERSION_CODE.toLong()).apply { start() }
                }.getOrNull()
                server = started
                main.post {
                    listening = started != null
                    listeners.toList().forEach { it.onListening(started != null) }
                }
            } else if (!wanted && server != null) {
                server?.close()
                server = null
                main.post { listening = null }
            }
        }
    }

    /** Server events, on its connection threads. One transfer runs at a time, so [transfer] is the current one. */
    private inner class Events : ReceiverServer.Handler {
        @Volatile private var transfer: Transfer? = null

        override fun sink(phone: ReceiverServer.Phone, update: Boolean): FileReceiver.Sink {
            val transfer = Transfer().also { transfer = it }
            return if (update) SelfUpdate.Sink(app, transfer) { transfer.update = it } else Downloads.Sink(app, transfer)
        }

        override fun sent(phone: ReceiverServer.Phone, error: Throwable?) {
            val transfer = transfer ?: return
            this.transfer = null
            // A transfer refused before it began (not paired, no permission) is reported to the phone only.
            val sender = transfer.sender ?: return
            main.post {
                listeners.toList().forEach { it.onEnd(sender, transfer.count, error) }
                val apk = synchronized(transfer.received) { transfer.received.firstOrNull { it.isApk } }
                val update = transfer.update
                if (update != null) {
                    // The user asked for this on the phone: the installer opens now, whatever autoInstall says.
                    if (error == null && apk != null) {
                        listeners.toList().forEach { it.onUpdate(sender, update) }
                        holders.lastOrNull()?.let { Downloads.open(it, apk) }
                    }
                    return@post
                }
                // Only for a batch that arrived whole: not one the phone cancelled or that failed.
                if (error == null && apk != null && app.settings.autoInstall) {
                    // Once the phone is done (it may fetch the app list next), or soon anyway.
                    pendingInstall = phone.key to apk
                    main.postDelayed(installLater, INSTALL_DELAY_MS)
                }
            }
        }

        override fun apps(): List<InstalledApp> = InstalledApps.list(app)

        override fun closeRequested(phone: ReceiverServer.Phone) {
            main.post { listeners.toList().forEach { it.onCloseRequested() } }
        }

        override fun disconnected(phone: ReceiverServer.Phone) {
            main.post { if (pendingInstall?.first == phone.key) installPending() }
        }

        // Only to logcat, on the TV: an unpaired phone turned away, or a TLS problem worth seeing with adb.
        override fun failed(error: Throwable) {
            Log.i(TAG, "A connection ended: $error", error.cause)
        }
    }

    private val installLater = Runnable { installPending() }

    /** Opens the installer for the APK that just arrived, from the receiver's screen. Android asks to confirm. */
    private fun installPending() {
        main.removeCallbacks(installLater)
        val (_, apk) = pendingInstall ?: return
        pendingInstall = null
        holders.lastOrNull()?.let { Downloads.open(it, apk) }
    }

    /** Passes one transfer's events to the main thread. Called on the connection's thread. */
    private inner class Transfer : Downloads.Sink.Events {
        @Volatile var sender: String? = null
        @Volatile var count = 0
        /** For an update of the receiver that checked out: its version name. */
        @Volatile var update: String? = null
        val received = mutableListOf<ReceivedFile>()
        private var lastProgress = 0L

        override fun begin(sender: String, count: Int, size: Long) {
            // The phone gives its name when it connects; an older or odd one may not.
            val from = sender.ifBlank { app.getString(R.string.a_phone) }
            this.sender = from
            this.count = count
            main.post { listeners.toList().forEach { it.onBegin(from, count, size) } }
        }

        override fun file(name: String, index: Int, count: Int) {
            main.post { listeners.toList().forEach { it.onFile(name, index, count) } }
        }

        override fun progress(received: Long, total: Long) {
            val now = System.nanoTime() / 1_000_000
            if (now - lastProgress < PROGRESS_MS && received < total) return
            lastProgress = now
            main.post { listeners.toList().forEach { it.onProgress(received, total) } }
        }

        override fun complete(file: ReceivedFile) {
            synchronized(received) { received += file }
            main.post { listeners.toList().forEach { it.onReceived(file) } }
        }
    }

    private companion object {
        const val PROGRESS_MS = 150L
        const val INSTALL_DELAY_MS = 5_000L
        const val TAG = "LibremoteReceiver"
    }
}
