package app.libremotereceiver

import android.app.Application
import android.content.Context
import app.libremotetvservice.protocol.PhoneRegistry

/** Holds the app-wide pieces: the paired phones, the receiver's identity and settings, and the server. */
class ReceiverApplication : Application() {
    lateinit var phones: PhoneRegistry
        private set
    lateinit var identity: IdentityStore
        private set
    lateinit var settings: ReceiverSettings
        private set
    lateinit var host: ReceiverHost
        private set

    override fun onCreate() {
        super.onCreate()
        phones = PhoneRegistry(PairedPhones(this))
        identity = IdentityStore(this)
        settings = ReceiverSettings(this)
        host = ReceiverHost(this)
    }
}

val Context.receiver get() = applicationContext as ReceiverApplication

/** The receiver's own settings. */
class ReceiverSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Open the system installer when an APK arrives. Off unless the user said yes. */
    var autoInstall: Boolean
        get() = prefs.getBoolean("autoInstall", false)
        set(value) = prefs.edit().putBoolean("autoInstall", value).apply()

    /** Whether the user was asked about [autoInstall], the first time the receiver opened. */
    var askedAutoInstall: Boolean
        get() = prefs.getBoolean("askedAutoInstall", false)
        set(value) = prefs.edit().putBoolean("askedAutoInstall", value).apply()

    /** The versionCode that last opened the receiver's screen, or 0: says "updated" once after an update. */
    var lastVersion: Int
        get() = prefs.getInt("lastVersion", 0)
        set(value) = prefs.edit().putInt("lastVersion", value).apply()
}
