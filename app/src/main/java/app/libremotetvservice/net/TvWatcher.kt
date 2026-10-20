package app.libremotetvservice.net

import app.libremotetvservice.LibremoteApp
import app.libremotetvservice.net.RemoteController.State
import app.libremotetvservice.protocol.TvTextField

/**
 * Listens to every remote connection, whichever screen opened it, and keeps what is worth remembering per TV:
 * what the TV said about itself, and which apps it showed in front (the shortcuts learn from these). Everything
 * stays on the phone.
 */
class TvWatcher(private val app: LibremoteApp) : RemoteController.Listener {

    override fun onState(state: State) {
        if (state is State.Connected) app.tvDetails.save(state.tv.pin, state.info)
    }

    override fun onTextField(field: TvTextField, newField: Boolean) {
        val tv = (app.remote.state as? State.Connected)?.tv ?: return
        val front = field.packageName ?: return
        if (!newField) return
        app.shortcuts.sawInFront(tv.pin, front)
        // The Play Store showed up, so this TV has one after all (it may have been disabled for a while).
        if (front == AppLauncher.PLAY_STORE) app.shortcuts.setNoPlay(tv.pin, false)
    }
}
