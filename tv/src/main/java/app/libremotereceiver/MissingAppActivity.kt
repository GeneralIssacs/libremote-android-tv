package app.libremotereceiver

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.TextView

/**
 * Says on the TV that the app a phone's shortcut asked for isn't installed, on TVs without the Play Store (see
 * [LaunchActivity]). A small card over whatever the TV shows, gone after a few seconds, or at OK or Back.
 */
class MissingAppActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val close = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay)
        findViewById<TextView>(R.id.text).text = getString(R.string.launch_missing, intent.getStringExtra(NAME).orEmpty())
    }

    override fun onStart() {
        super.onStart()
        main.postDelayed(close, SHOWN_MS)
    }

    override fun onStop() {
        main.removeCallbacks(close)
        super.onStop()
        finish()
    }

    // The card has nothing to select: OK dismisses it, as Back does.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return super.onKeyUp(keyCode, event)
        finish()
        return true
    }

    companion object {
        const val NAME = "name"
        private const val SHOWN_MS = 5_000L
    }
}
