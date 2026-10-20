package app.libremotetvservice.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewStructure
import android.view.autofill.AutofillValue
import android.widget.EditText

/**
 * A text field that autofill services and password managers never see: for one-time values such as the TV's
 * pairing code, which nobody should be offered to save.
 *
 * - Autofill type NONE: Android never starts an autofill session from it or reports its text to one, even when it
 *   triggers autofill on views apps marked unimportant (Android 14's "autofill for all apps").
 * - Nothing goes into an autofill structure built for another field, so its text can't be saved along with that.
 * - Its input type must not be a password variation either: password managers read the variation bits, and
 *   `textVisiblePassword` (0x90) contains the password bit (0x80).
 */
class PrivateEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    override fun getAutofillType() = View.AUTOFILL_TYPE_NONE

    override fun getAutofillValue(): AutofillValue? = null

    override fun onProvideAutofillStructure(structure: ViewStructure, flags: Int) {
        // Deliberately empty: no class name, text, hint or input type for a service to classify.
    }
}
