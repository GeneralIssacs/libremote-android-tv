package app.libremotereceiver

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.libremotetvservice.protocol.PhoneRegistry

/**
 * Asks the user whether to allow a phone that isn't paired (or was removed) to use the receiver, over the screen
 * the link opened. Its phone, if it's Libremote 1.0.1 or later, shows the same code and waits for the answer.
 * "Don't allow" has the focus, and Back, leaving the screen or the time running out all count as a no. A question
 * that another screen answered, or that a newer link from the same phone replaced, closes here by itself.
 * [answered] gets whether the phone is now paired.
 */
class ConfirmPhone(private val activity: Activity, private val answered: (paired: Boolean) -> Unit = {}) {
    private val phones = activity.receiver.phones
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var asked: PhoneRegistry.Request? = null
    private val lapse = Runnable { answer(allow = false) }

    /** The registry changed (from any thread): a question that is no longer the open one goes. */
    private val changed: () -> Unit = {
        main.post {
            val shown = asked
            if (shown != null && phones.pending() !== shown) {
                close()
                answered(phones.isPaired(shown.key))
            }
        }
    }

    val showing get() = asked != null

    /** Asks about [request], in place of a question already showing. */
    fun ask(request: PhoneRegistry.Request) {
        if (request === asked) return
        close()
        asked = request
        phones.addListener(changed)
        val name = request.name
        val title = when {
            name == null -> activity.getString(if (request.wasRemoved) R.string.confirm_title_unnamed_again else R.string.confirm_title_unnamed)
            request.wasRemoved -> activity.getString(R.string.confirm_title_again, name)
            else -> activity.getString(R.string.confirm_title, name)
        }
        val text = buildString {
            if (request.wasRemoved) append(activity.getString(R.string.confirm_removed)).append(' ')
            append(activity.getString(R.string.confirm_text))
            append("\n\n")
            append(if (request.showsCode) activity.getString(R.string.confirm_code, request.code) else activity.getString(R.string.confirm_no_code))
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(R.string.allow) { _, _ -> answer(allow = true) }
            .setNegativeButton(R.string.dont_allow) { _, _ -> answer(allow = false) }
            .setOnCancelListener { answer(allow = false) }
            .show()
            .also {
                // Also where the first D-pad key lands when the TV was in touch mode, rather than the first button.
                it.getButton(DialogInterface.BUTTON_NEGATIVE).apply {
                    isFocusedByDefault = true
                    requestFocus()
                }
            }
        main.postDelayed(lapse, (request.untilMs - System.currentTimeMillis()).coerceAtLeast(0))
    }

    /** The screen is going away: a question still showing is a no. */
    fun dismiss() {
        if (showing) answer(allow = false)
    }

    private fun answer(allow: Boolean) {
        val request = asked ?: return
        close()
        // False also when this question had lapsed or been replaced meanwhile: then nothing is paired.
        val paired = phones.decide(request, allow)
        if (paired) {
            val name = request.name ?: activity.getString(R.string.a_phone)
            Toast.makeText(activity, activity.getString(R.string.paired_new, name), Toast.LENGTH_LONG).show()
        }
        answered(paired)
    }

    private fun close() {
        phones.removeListener(changed)
        main.removeCallbacks(lapse)
        asked = null
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
    }
}
