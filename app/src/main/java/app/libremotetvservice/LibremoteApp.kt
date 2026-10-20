package app.libremotetvservice

import android.app.Application
import app.libremotetvservice.data.IdentityStore
import app.libremotetvservice.data.Settings
import app.libremotetvservice.data.ShortcutStore
import app.libremotetvservice.data.TvDetails
import app.libremotetvservice.data.TvStore
import app.libremotetvservice.net.AppLauncher
import app.libremotetvservice.net.Receivers
import app.libremotetvservice.net.RemoteController
import app.libremotetvservice.net.SendController
import app.libremotetvservice.net.TvWatcher
import app.libremotetvservice.ui.TvApps

/** Holds the app-wide pieces; the remote connection and file transfers outlive activity restarts such as rotation. */
class LibremoteApp : Application() {
    lateinit var tvs: TvStore
        private set
    lateinit var identity: IdentityStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var remote: RemoteController
        private set
    lateinit var sends: SendController
        private set
    lateinit var receivers: Receivers
        private set

    // Shortcuts, About this TV.
    lateinit var shortcuts: ShortcutStore
        private set
    lateinit var tvDetails: TvDetails
        private set
    lateinit var launcher: AppLauncher
        private set

    override fun onCreate() {
        super.onCreate()
        tvs = TvStore(this)
        identity = IdentityStore(this)
        settings = Settings(this)
        receivers = Receivers(this)
        remote = RemoteController(this)
        sends = SendController(this)

        shortcuts = ShortcutStore(this, TvApps.template, TvApps::isIgnored)
        tvDetails = TvDetails(this)
        launcher = AppLauncher(this)
        // Forget what TVs that were unpaired since the last start said or showed.
        val paired = tvs.all().map { it.pin }.toSet()
        shortcuts.keepOnly(paired)
        tvDetails.keepOnly(paired)
        remote.addListener(TvWatcher(this))
    }
}

val android.content.Context.libremote get() = applicationContext as LibremoteApp
