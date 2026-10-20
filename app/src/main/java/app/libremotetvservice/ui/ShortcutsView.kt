package app.libremotetvservice.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import app.libremotetvservice.R
import app.libremotetvservice.data.Shortcut
import app.libremotetvservice.data.ShortcutLook
import app.libremotetvservice.data.ShortcutLook.Size
import app.libremotetvservice.data.ShortcutLook.Style
import app.libremotetvservice.data.ShortcutStore
import app.libremotetvservice.data.Tv
import app.libremotetvservice.libremote
import app.libremotetvservice.net.AppLauncher.Outcome
import app.libremotetvservice.net.RemoteController
import app.libremotetvservice.net.RemoteController.State
import app.libremotetvservice.protocol.Features
import app.libremotetvservice.protocol.TvTextField

/**
 * The row of app shortcuts for one TV ([bind]): a tap opens the app on the TV, a long press pins, renames or hides
 * it, and "+" adds one. It follows the look chosen in Settings › Shortcuts (text, icons or tiles; three sizes; one to
 * three rows that scroll sideways together), marks the app the TV shows in front, and hides itself on TVs that can't
 * open apps. Self-contained: it listens to the stores and the connection itself, so a screen only places and binds
 * it. With [preview] (Settings), it only shows how the row looks.
 */
class ShortcutsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), ShortcutStore.Listener, RemoteController.Listener {

    /** Show the row without acting on it, for the preview in Settings. */
    var preview = false

    private val app = context.libremote
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** At least as wide as the row (fillViewport), so shortcuts that don't fill it sit centred. */
    private val content = FrameLayout(context)
    private var tv: Tv? = null

    /** Packages in the order shown, kept while the screen is up so learning doesn't reshuffle the row. */
    private var order: List<String> = emptyList()
    private val items = HashMap<String, View>()
    private var front: String? = null
    private var pulse: ValueAnimator? = null
    private val receiversChanged = { render(rerank = false) }

    init {
        content.addView(rows, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        scroll.isFillViewport = true
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Shows [tv]'s shortcuts, ranked afresh. */
    fun bind(tv: Tv) {
        if (this.tv?.pin != tv.pin) scroll.scrollTo(0, 0)
        this.tv = tv
        front = (app.remote.state as? State.Connected)?.takeIf { it.tv.pin == tv.pin }?.let { app.remote.field?.packageName }
        render(rerank = true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        app.shortcuts.addListener(this)
        app.receivers.addListener(receiversChanged)
        if (!preview) app.remote.addListener(this)
        render(rerank = false)
    }

    override fun onDetachedFromWindow() {
        app.shortcuts.removeListener(this)
        app.receivers.removeListener(receiversChanged)
        app.remote.removeListener(this)
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onShortcutsChanged(pin: String?, change: ShortcutStore.Change) {
        if (pin != null && pin != tv?.pin) return
        // Edits re-rank at once; learning only adds new apps at the end until the row is shown again.
        render(rerank = change == ShortcutStore.Change.EDITED)
    }

    override fun onState(state: State) = updateVisibility()

    override fun onTextField(field: TvTextField, newField: Boolean) {
        if (!newField || field.packageName == front) return
        front = field.packageName
        items.forEach { (pkg, view) -> view.isSelected = pkg == front }
    }

    private fun render(rerank: Boolean) {
        val tv = tv ?: return
        val ranked = app.shortcuts.onRemote(tv.pin).map { it.packageName }
        order = if (rerank || order.isEmpty()) ranked else order.filter { it in ranked } + ranked.filter { it !in order }
        val look = app.shortcuts.look
        val m = Metrics.of(look.size, this)
        val views = order.map { pkg -> item(tv, pkg, app.shortcuts.get(tv.pin, pkg), look, m).also { items[pkg] = it } } + addItem(look, m)
        items.keys.retainAll(order.toSet())
        stopPulse()
        rows.removeAllViews()
        val count = minOf(look.rows, views.size)
        val lines = List(count) {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                rows.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (it < count - 1) bottomMargin = m.gap })
            }
        }
        // Column by column, so the most used apps stay in view on the left of every row. Gaps only between items,
        // so a row that doesn't fill the width is centred exactly.
        views.forEachIndexed { i, view ->
            lines[i % count].addView(view, LinearLayout.LayoutParams(view.layoutParams).apply { if (i >= count) marginStart = m.gap })
        }
        updateVisibility()
        val busy = app.launcher.busy
        if (!busy) launching = null
        launching?.let { items[it] }?.let(::startPulse)
    }

    /** While true (the screen needs the room, as while typing), the row steps aside. */
    var stepAside = false
        set(value) {
            field = value
            updateVisibility()
        }

    /** Hidden on a TV that can't open apps (known from this connection, or from the last one). */
    private fun updateVisibility() {
        val tv = tv
        val state = app.remote.state
        val offers = when {
            preview || tv == null -> true
            state is State.Connected && state.tv.pin == tv.pin -> state.supports(Features.APP_LINKS)
            else -> app.tvDetails.get(tv.pin)?.supports(Features.APP_LINKS) ?: true
        }
        visibility = if (offers && !stepAside) View.VISIBLE else View.GONE
    }

    // Items

    private class Metrics(
        val chipHeight: Int, val chipText: Float, val chipPadding: Int, val chipMaxWidth: Int,
        val icon: Int, val tileIcon: Int, val tileWidth: Int, val tileText: Float, val gap: Int,
    ) {
        companion object {
            fun of(size: Size, view: View): Metrics {
                fun dp(v: Float) = view.context.dp(v).toInt()
                return when (size) {
                    Size.S -> Metrics(dp(40f), 14f, dp(16f), dp(180f), dp(52f), dp(48f), dp(76f), 12f, dp(8f))
                    Size.M -> Metrics(dp(52f), 17f, dp(22f), dp(210f), dp(66f), dp(62f), dp(92f), 14f, dp(10f))
                    Size.L -> Metrics(dp(60f), 19f, dp(26f), dp(240f), dp(76f), dp(72f), dp(104f), 15f, dp(12f))
                }
            }
        }
    }

    private fun item(tv: Tv, pkg: String, shortcut: Shortcut?, look: ShortcutLook, m: Metrics): View {
        val name = AppLabels.name(app, tv, pkg, shortcut)
        val view: View = when (look.style) {
            Style.TEXT -> TextView(context).apply {
                text = name
                textSize = m.chipText
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(selectableText())
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = m.chipMaxWidth
                setPadding(m.chipPadding, 0, m.chipPadding, 0)
                background = itemBackground(m.chipHeight / 2f, filled = true)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, m.chipHeight)
            }
            Style.ICONS -> FrameLayout(context).apply {
                val pad = context.dp(4f).toInt()
                setPadding(pad, pad, pad, pad)
                background = itemBackground(m.icon * 0.3f, filled = false)
                contentDescription = name
                addView(ImageView(context).apply {
                    setImageDrawable(AppLabels.icon(app, tv, pkg, name))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(m.icon - 2 * pad, m.icon - 2 * pad))
                layoutParams = LinearLayout.LayoutParams(m.icon, m.icon)
            }
            Style.TILES -> tile(name, m) { it.setImageDrawable(AppLabels.icon(app, tv, pkg, name)) }
        }
        view.isSelected = pkg == front
        view.setOnClickListener { open(tv, pkg, name, it) }
        view.setOnLongClickListener { showMenu(tv, pkg, it); true }
        view.accessibilityDelegate = ItemAccessibility(tv, pkg, shortcut?.pinned == true)
        return view
    }

    private fun tile(name: String, m: Metrics, icon: (ImageView) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = context.dp(6f).toInt()
        setPadding(context.dp(2f).toInt(), pad, context.dp(2f).toInt(), pad)
        background = itemBackground(context.dp(16f), filled = false)
        addView(ImageView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            icon(this)
        }, LinearLayout.LayoutParams(m.tileIcon, m.tileIcon))
        addView(TextView(context).apply {
            text = name
            textSize = m.tileText
            setTextColor(selectableText())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(4f).toInt()
        })
        contentDescription = name
        layoutParams = LinearLayout.LayoutParams(m.tileWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /** "+": adds an app. Outlined, so it never reads as an app. */
    private fun addItem(look: ShortcutLook, m: Metrics): View {
        val label = context.getString(R.string.shortcuts_add)
        val view: View = when (look.style) {
            Style.TEXT -> ImageView(context).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = context.getColorStateList(R.color.on_surface_muted)
                scaleType = ImageView.ScaleType.CENTER
                background = outlined(m.chipHeight / 2f)
                layoutParams = LinearLayout.LayoutParams(m.chipHeight, m.chipHeight)
            }
            Style.ICONS -> ImageView(context).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = context.getColorStateList(R.color.on_surface_muted)
                scaleType = ImageView.ScaleType.CENTER
                val inset = context.dp(4f).toInt()
                background = android.graphics.drawable.InsetDrawable(outlined((m.icon - 2 * inset) * 0.26f), inset)
                layoutParams = LinearLayout.LayoutParams(m.icon, m.icon)
            }
            Style.TILES -> tile(label, m) {
                it.background = outlined(m.tileIcon * 0.26f)
                it.setImageResource(R.drawable.ic_add)
                it.imageTintList = context.getColorStateList(R.color.on_surface_muted)
                it.scaleType = ImageView.ScaleType.CENTER
            }
        }
        view.contentDescription = label
        view.setOnClickListener {
            if (preview) return@setOnClickListener
            Haptics.tap(it)
            val tv = tv ?: return@setOnClickListener
            context.findActivity()?.let { activity -> AppPickerSheet(activity, tv).show() }
        }
        view.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        return view
    }

    /** Surface-coloured (or clear) when idle, tonal accent for the app in front, with a ripple. */
    private fun itemBackground(radius: Float, filled: Boolean): Drawable {
        fun shape(color: Int) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (color == 0) 0 else context.getColor(color))
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), shape(R.color.accent_container))
            addState(intArrayOf(), shape(if (filled) R.color.surface else 0))
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(context.getColorStateList(R.color.ripple), states, mask)
    }

    private fun outlined(radius: Float): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setStroke(context.dp(1.5f).toInt(), context.getColor(R.color.on_surface_muted) and 0x66FFFFFF)
        }
        return RippleDrawable(context.getColorStateList(R.color.ripple), shape, null)
    }

    private fun selectableText() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(context.getColor(R.color.on_accent_container), context.getColor(R.color.on_surface)),
    )

    // Actions

    /** The package being opened, which pulses until the TV answers. */
    private var launching: String? = null

    private fun open(tv: Tv, pkg: String, name: String, view: View) {
        if (preview) return
        Haptics.tap(view)
        val started = app.launcher.launch(tv, pkg, name) { outcome ->
            launching = null
            stopPulse()
            message(outcome, name, tv)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
        when {
            // Another app is still on its way: say so rather than ignore the tap.
            !started -> Toast.makeText(context, R.string.shortcut_busy, Toast.LENGTH_SHORT).show()
            // Some launches end at once (offline, nothing that can open the app): nothing to wait for then.
            app.launcher.busy -> {
                launching = pkg
                startPulse(view)
            }
        }
    }

    private fun message(outcome: Outcome, name: String, tv: Tv): String? = when (outcome) {
        Outcome.OPENED -> null
        Outcome.NOT_INSTALLED -> context.getString(R.string.shortcut_not_installed, name)
        Outcome.NOT_ON_TV -> context.getString(R.string.shortcut_not_on_tv, name)
        Outcome.FAILED -> context.getString(R.string.shortcut_failed, name)
        Outcome.NEEDS_RECEIVER -> context.getString(R.string.shortcut_needs_receiver, name)
        Outcome.OFFLINE -> context.getString(R.string.shortcut_offline, tv.name)
    }

    private fun startPulse(view: View) {
        stopPulse()
        pulse = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.45f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.let { it.cancel(); (it as ObjectAnimator).target.let { v -> (v as? View)?.alpha = 1f } }
        pulse = null
    }

    private fun showMenu(tv: Tv, pkg: String, anchor: View) {
        if (preview) return
        Haptics.long(anchor)
        val pinned = app.shortcuts.get(tv.pin, pkg)?.pinned == true
        PopupMenu(context, anchor).apply {
            menu.add(0, R.id.shortcut_action_pin, 0, if (pinned) R.string.shortcut_unpin else R.string.shortcut_pin)
            menu.add(0, R.id.shortcut_action_rename, 1, R.string.shortcut_rename)
            menu.add(0, R.id.shortcut_action_hide, 2, R.string.shortcut_hide)
            setOnMenuItemClickListener { act(tv, pkg, it.itemId) }
            show()
        }
    }

    /** Pin or unpin, rename, hide: from the long-press menu or TalkBack's actions. */
    private fun act(tv: Tv, pkg: String, action: Int): Boolean {
        val store = app.shortcuts
        when (action) {
            R.id.shortcut_action_pin -> store.setPinned(tv.pin, pkg, store.get(tv.pin, pkg)?.pinned != true)
            R.id.shortcut_action_rename -> context.findActivity()?.let { renameShortcut(it, tv, pkg) }
            R.id.shortcut_action_hide -> {
                store.hide(tv.pin, pkg)
                Toast.makeText(context, context.getString(R.string.shortcut_hidden, AppLabels.name(app, tv, pkg)), Toast.LENGTH_SHORT).show()
            }
            else -> return false
        }
        return true
    }

    /** TalkBack: a button, with Pin, Rename and Hide in its actions menu. */
    private inner class ItemAccessibility(private val tv: Tv, private val pkg: String, private val pinned: Boolean) : AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
            if (preview) return
            info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, context.getString(R.string.shortcut_menu)))
            info.addAction(AccessibilityAction(R.id.shortcut_action_pin, context.getString(if (pinned) R.string.shortcut_unpin else R.string.shortcut_pin)))
            info.addAction(AccessibilityAction(R.id.shortcut_action_rename, context.getString(R.string.shortcut_rename)))
            info.addAction(AccessibilityAction(R.id.shortcut_action_hide, context.getString(R.string.shortcut_hide)))
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
            act(tv, pkg, action) || super.performAccessibilityAction(host, action, args)
    }

    companion object {
        /** Asks for a new name; an empty one goes back to the default. */
        fun renameShortcut(activity: android.app.Activity, tv: Tv, pkg: String) {
            val app = activity.libremote
            val default = AppLabels.defaultName(app, tv, pkg)
            val current = app.shortcuts.get(tv.pin, pkg)?.customName
            activity.textPrompt(
                title = activity.getString(R.string.shortcut_rename_title),
                message = activity.getString(R.string.shortcut_rename_text, default),
                value = current ?: default,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
                hint = default,
            ) { name ->
                app.shortcuts.rename(tv.pin, pkg, name.trim().takeIf { it.isNotEmpty() && it != default })
                true
            }
        }
    }
}
