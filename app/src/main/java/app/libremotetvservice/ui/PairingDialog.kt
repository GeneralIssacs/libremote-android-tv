package app.libremotetvservice.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.CycleInterpolator
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.libremotetvservice.R
import app.libremotetvservice.libremote
import app.libremotetvservice.data.Tv
import app.libremotetvservice.protocol.PairingCode
import app.libremotetvservice.protocol.PairingException
import app.libremotetvservice.protocol.PairingSession
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/**
 * Pairs with the TV at [host]: the TV shows a code, the user types it here, and the TV is saved. [replacing] is
 * the pin of an older pairing with the same TV, removed once the new one works.
 */
class PairingDialog(
    private val activity: Activity,
    private val host: String,
    private val name: String,
    private val replacing: String? = null,
    private val onPaired: (Tv) -> Unit,
) {
    private val view = activity.layoutInflater.inflate(R.layout.dialog_pair, null)
    private val title = view.findViewById<TextView>(R.id.pairTitle)
    private val message = view.findViewById<TextView>(R.id.pairMessage)
    private val progress = view.findViewById<ProgressBar>(R.id.pairProgress)
    private val codeArea = view.findViewById<View>(R.id.codeArea)
    private val boxes = view.findViewById<LinearLayout>(R.id.codeBoxes)
    private val input = view.findViewById<EditText>(R.id.codeInput)
    private val error = view.findViewById<TextView>(R.id.pairError)
    private val cancel = view.findViewById<Button>(R.id.pairCancel)
    private val action = view.findViewById<Button>(R.id.pairAction)

    private val dialog = AlertDialog.Builder(activity).setView(view).create()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var session: PairingSession? = null
    private var closed = false
    private var submitted = ""

    private val slow = Runnable { message.setText(R.string.pair_slow) }

    fun show() {
        title.text = activity.getString(R.string.pair_title, name)
        cancel.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { close() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val code = PairingCode.normalize(s.toString())
                if (code != s.toString()) {
                    s.replace(0, s.length, code)
                    // A keyboard may still be composing the text just replaced: start it afresh, or it could type
                    // the old characters again (keyboards that ignore "no suggestions").
                    input.post { activity.getSystemService(InputMethodManager::class.java).restartInput(input) }
                    return
                }
                renderCode(code)
                if (code.length == PairingCode.LENGTH && code != submitted) submit(code)
            }
        })
        dialog.show()
        requestCode()
    }

    /** Opens a pairing session; the TV then shows a new code. */
    private fun requestCode() {
        session?.close()
        session = null
        submitted = ""
        input.setText("")
        progress.visibility = View.VISIBLE
        codeArea.visibility = View.GONE
        error.visibility = View.GONE
        action.visibility = View.GONE
        message.setText(R.string.pair_connecting)
        main.postDelayed(slow, SLOW_MS)
        worker.execute {
            val result = runCatching { PairingSession.start(host, activity.libremote.identity.get(), CLIENT_NAME) }
            main.post {
                main.removeCallbacks(slow)
                result.onSuccess { started ->
                    if (closed) {
                        worker.execute { started.close() }
                        return@post
                    }
                    session = started
                    showCodeEntry()
                }.onFailure(::showFailure)
            }
        }
    }

    private fun showCodeEntry() {
        progress.visibility = View.GONE
        codeArea.visibility = View.VISIBLE
        action.visibility = View.GONE
        message.setText(R.string.pair_enter_code)
        input.isEnabled = true
        input.requestFocus()
        input.post {
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        renderCode(PairingCode.normalize(input.text.toString()))
    }

    private fun renderCode(code: String) {
        for (i in 0 until boxes.childCount) {
            val box = boxes.getChildAt(i) as TextView
            box.text = code.getOrNull(i)?.toString() ?: ""
            box.isSelected = i == code.length.coerceAtMost(PairingCode.LENGTH - 1) && input.isEnabled
        }
        if (code.length < PairingCode.LENGTH) error.visibility = View.GONE
    }

    private fun submit(code: String) {
        val current = session ?: return
        submitted = code
        input.isEnabled = false
        message.setText(R.string.pair_verifying)
        worker.execute {
            val result = runCatching { current.submit(code) }
            main.post {
                if (closed) return@post
                result.onSuccess { paired ->
                    if (paired) finish(current) else wrongCode()
                }.onFailure { e ->
                    if (e is PairingException && e.status == 402) {
                        // A typo that slipped past the checksum: the TV ended this attempt, so ask for a new code.
                        Toast.makeText(activity, R.string.pair_rejected, Toast.LENGTH_LONG).show()
                        requestCode()
                    } else {
                        showFailure(e)
                    }
                }
            }
        }
    }

    /** The checksum caught a typo: nothing was sent, so the same code can simply be corrected. */
    private fun wrongCode() {
        input.isEnabled = true
        message.setText(R.string.pair_enter_code)
        error.setText(R.string.pair_wrong_code)
        error.visibility = View.VISIBLE
        Haptics.long(boxes)
        boxes.animate().translationX(activity.dp(8f)).setInterpolator(CycleInterpolator(3f)).setDuration(360)
            .withEndAction { boxes.translationX = 0f }.start()
        input.requestFocus()
        input.setSelection(input.text.length)
        input.post {
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun finish(paired: PairingSession) {
        val tvs = activity.libremote.tvs
        // The same TV paired again (after a reset, or removed on the TV) keeps its shortcuts and details: the TV
        // being replaced, or else the one paired before under this address and name.
        val previous = replacing ?: tvs.all().firstOrNull { it.host == host && it.name == name }?.pin
        if (previous != null && previous != paired.tvPin) {
            activity.libremote.shortcuts.move(previous, paired.tvPin)
            activity.libremote.tvDetails.move(previous, paired.tvPin)
        }
        replacing?.let(tvs::remove)
        // A TV that was paired before under the same address and name, then reset, is the same TV.
        tvs.all().filter { it.host == host && it.name == name }.forEach { tvs.remove(it.pin) }
        val tv = Tv(paired.tvPin, name, host, System.currentTimeMillis())
        tvs.save(tv)
        worker.execute { paired.close() }
        session = null
        Toast.makeText(activity, activity.getString(R.string.paired, name), Toast.LENGTH_SHORT).show()
        dialog.dismiss()
        onPaired(tv)
    }

    private fun showFailure(e: Throwable) {
        if (closed) return
        session?.let { old -> worker.execute { old.close() } }
        session = null
        progress.visibility = View.GONE
        codeArea.visibility = View.GONE
        message.text = when (e) {
            is ConnectException, is NoRouteToHostException, is UnknownHostException -> activity.getString(R.string.pair_unreachable)
            is SocketTimeoutException -> activity.getString(R.string.pair_slow)
            is PairingException -> activity.getString(R.string.pair_cancelled)
            else -> activity.getString(R.string.pair_failed, e.message ?: e.javaClass.simpleName)
        }
        action.setText(R.string.pair_retry)
        action.visibility = View.VISIBLE
        action.setOnClickListener { requestCode() }
    }

    private fun close() {
        closed = true
        // End any autofill session the dialog was part of without a save prompt (the code field never joins one).
        activity.getSystemService(AutofillManager::class.java)?.cancel()
        main.removeCallbacks(slow)
        val open = session
        session = null
        worker.execute { open?.close() }
        worker.shutdown()
    }

    private companion object {
        /** What the TV calls this phone: "Enter the following code on your Libremote". */
        const val CLIENT_NAME = "Libremote"
        const val SLOW_MS = 8_000L
    }
}
