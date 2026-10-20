package app.libremotereceiver

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Opens an app for a phone's shortcut: `libremote://launch?id=<package>&name=<its name>`, which the TV's remote
 * service starts with ACTION_VIEW. The TV's remote service can't see most apps (package visibility), so it can't
 * open them itself; this can (see <queries> in the manifest). No window: it starts the app's TV launcher entry, or
 * else its launcher entry, and finishes at once. Any app on the TV could start apps this way already, so it needs
 * no pairing.
 *
 * When the app isn't there, the TV says so where the user is looking: the Play Store shows its page, so it can be
 * installed, or, on TVs without the Play Store, [MissingAppActivity] names it. The phone sees which of them came to
 * the front and says the same.
 */
@SuppressLint("CustomSplashScreen") // Not a splash screen, despite the name: it has no window at all.
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.data?.getQueryParameter("id")?.trim().orEmpty()
        if (packageName.isNotEmpty()) {
            val name = intent.data?.getQueryParameter("name")?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
            val launch = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
            // Not installed, or its entry is not open to other apps (SecurityException).
            if (!start(launch) && !start(storePage(packageName))) {
                start(Intent(this, MissingAppActivity::class.java).putExtra(MissingAppActivity.NAME, name ?: packageName))
            }
        }
        // Theme.NoDisplay: it must finish before it would be shown.
        finish()
    }

    private fun start(intent: Intent?): Boolean =
        intent != null && runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun storePage(packageName: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Uri.encode(packageName))).setPackage(PLAY_STORE)

    private companion object {
        const val PLAY_STORE = "com.android.vending"

        /** Names come from the phone; a long one would only overflow the card. */
        const val MAX_NAME = 60
    }
}
