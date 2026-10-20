package app.libremotetvservice.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.libremotetvservice.R

/** The activity behind a view's context (views inflated in an activity, or in a dialog over it). */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** A section title, as in Settings. */
fun Context.sectionTitle(text: CharSequence) = TextView(this, null, 0, R.style.Libremote_Section).apply {
    this.text = text
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}

/**
 * A tappable list row: an optional icon ([iconTint] for monochrome symbols, none for app icons), a title, an optional
 * subtitle and an optional trailing view.
 */
fun Context.listRow(
    icon: Drawable?,
    title: CharSequence,
    subtitle: CharSequence? = null,
    iconTint: Int? = null,
    iconSize: Float = 40f,
    trailing: View? = null,
    onClick: ((View) -> Unit)?,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(56f).toInt()
    setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(6f).toInt())
    if (icon != null) addView(ImageView(context).apply {
        setImageDrawable(icon)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (iconTint != null) {
            imageTintList = context.getColorStateList(iconTint)
            scaleType = ImageView.ScaleType.CENTER
        }
        layoutParams = LinearLayout.LayoutParams(dp(iconSize).toInt(), dp(iconSize).toInt()).apply { marginEnd = dp(16f).toInt() }
    })
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(context.getColor(R.color.on_surface))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (subtitle != null) addView(TextView(context).apply {
            text = subtitle
            textSize = 14f
            setTextColor(context.getColor(R.color.on_surface_muted))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    if (trailing != null) addView(trailing)
    if (onClick != null) {
        background = context.getDrawable(R.drawable.bg_text_button)
        setOnClickListener { Haptics.tap(it); onClick(it) }
    }
}

/** A ScrollView that stops growing at [maxHeight], so a long list in a bottom sheet doesn't cover the screen. */
class MaxHeightScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    var maxHeight = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight
        else minOf(maxHeight, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}

/** A one-field dialog (dialog_text_input); [accept] returns false to keep it open and show [error]. */
fun Activity.textPrompt(
    title: String,
    message: String?,
    value: String,
    inputType: Int,
    hint: String? = null,
    positive: Int = R.string.save,
    error: String? = null,
    accept: (String) -> Boolean,
) {
    val view = layoutInflater.inflate(R.layout.dialog_text_input, null)
    val input = view.findViewById<EditText>(R.id.input)
    val errorView = view.findViewById<TextView>(R.id.inputError)
    view.findViewById<TextView>(R.id.inputMessage).apply {
        text = message
        visibility = if (message == null) View.GONE else View.VISIBLE
    }
    input.inputType = inputType
    input.hint = hint
    input.setText(value)
    input.setSelection(value.length)
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(view)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(positive, null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (accept(input.text.toString())) dialog.dismiss() else if (error != null) {
                errorView.text = error
                errorView.visibility = View.VISIBLE
            }
        }
        input.requestFocus()
        input.post { getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }
    dialog.show()
}

/** A small text button, for actions at the end of a list row. */
fun Context.rowButton(text: CharSequence, onClick: (View) -> Unit) = Button(this, null, 0, R.style.Libremote_Button_Text).apply {
    this.text = text
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f).toInt())
    setOnClickListener { Haptics.tap(it); onClick(it) }
}
