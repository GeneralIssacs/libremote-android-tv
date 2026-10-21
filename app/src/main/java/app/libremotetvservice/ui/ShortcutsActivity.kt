package app.libremotetvservice.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import app.libremotetvservice.R
import app.libremotetvservice.data.Shortcut
import app.libremotetvservice.data.ShortcutLook
import app.libremotetvservice.data.ShortcutStore
import app.libremotetvservice.data.Tv
import app.libremotetvservice.libremote
import app.libremotetvservice.protocol.ReceiverDeclinedException

/**
 * Settings › Shortcuts: the look of the shortcut row (with a live preview) and, per TV, its apps: reorder the
 * pinned ones by dragging, pin, rename, hide and restore, get the TV's app list, or start over from the template.
 */
class ShortcutsActivity : Activity(), ShortcutStore.Listener {
    private val app get() = libremote
    private var tv: Tv? = null

    private lateinit var scroll: ScrollView
    private lateinit var preview: ShortcutsView
    private lateinit var chooser: TextView
    private lateinit var pinned: LinearLayout
    private lateinit var others: LinearLayout
    private lateinit var hidden: LinearLayout
    private lateinit var syncText: TextView
    private lateinit var sync: Button
    private lateinit var drag: DragReorder
    private var syncing = false

    /** While a sync waits for the TV to allow this phone: the code both show. */
    private var syncCode: String? = null

    /** A change arrived while a row was being dragged: redraw once it is dropped. */
    private var renderPending = false

    /** The row TalkBack should stay on after the list is rebuilt. */
    private var focusAfterRender: String? = null
    private val receiversChanged = { renderApps() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcuts)
        scroll = findViewById(R.id.root)
        edgeToEdge(scroll)
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        preview = findViewById<ShortcutsView>(R.id.preview).apply { preview = true }
        chooser = findViewById(R.id.tvChooser)
        pinned = findViewById(R.id.pinned)
        others = findViewById(R.id.others)
        hidden = findViewById(R.id.hidden)
        syncText = findViewById(R.id.syncText)
        sync = findViewById(R.id.sync)
        drag = DragReorder(pinned, scroll, onEnd = { if (renderPending) renderApps() }) { rows -> currentTv()?.let { tv -> app.shortcuts.setPinnedOrder(tv.pin, rows.map { it.tag as String }) } }
        findViewById<LinearLayout>(R.id.addRow).addView(listRow(
            getDrawable(R.drawable.ic_add), getString(R.string.shortcuts_add_title), iconTint = R.color.accent, iconSize = 40f,
        ) { currentTv()?.let { AppPickerSheet(this, it).show() } })
        sync.setOnClickListener { syncApps() }
        findViewById<View>(R.id.reset).setOnClickListener { confirmReset() }
        val pin = intent.getStringExtra(EXTRA_PIN)
        tv = pin?.let(app.tvs::get) ?: app.tvs.last()
        bindLook()
    }

    override fun onStart() {
        super.onStart()
        app.shortcuts.addListener(this)
        app.receivers.addListener(receiversChanged)
        tv = tv?.pin?.let(app.tvs::get) ?: app.tvs.last()
        render()
    }

    override fun onStop() {
        app.shortcuts.removeListener(this)
        app.receivers.removeListener(receiversChanged)
        super.onStop()
    }

    private fun currentTv() = tv

    override fun onShortcutsChanged(pin: String?, change: ShortcutStore.Change) {
        if (change == ShortcutStore.Change.LOOK) return bindLook()
        if (pin == tv?.pin) renderApps()
    }

    private fun render() {
        val tv = tv
        val all = app.tvs.all()
        findViewById<View>(R.id.perTv).visibility = if (tv == null) View.GONE else View.VISIBLE
        if (tv == null) {
            chooser.setText(R.string.shortcuts_no_tv)
            return
        }
        preview.bind(tv)
        chooser.text = getString(R.string.shortcuts_for, tv.name)
        if (all.size > 1) {
            chooser.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_expand_more, 0)
            chooser.compoundDrawableTintList = getColorStateList(R.color.on_surface_muted)
            chooser.background = getDrawable(R.drawable.bg_text_button)
            chooser.contentDescription = getString(R.string.shortcuts_choose_tv, tv.name)
            chooser.setOnClickListener { chooseTv(it) }
        } else {
            chooser.setOnClickListener(null)
            chooser.isClickable = false
        }
        renderApps()
    }

    private fun chooseTv(anchor: View) {
        val all = app.tvs.all()
        PopupMenu(this, anchor).apply {
            all.forEachIndexed { i, tv -> menu.add(0, i, i, tv.name) }
            setOnMenuItemClickListener {
                tv = all[it.itemId]
                render()
                true
            }
            show()
        }
    }

    // Look

    private fun bindLook() {
        val look = app.shortcuts.look
        segments(
            findViewById(R.id.styles),
            listOf(R.string.shortcuts_style_text, R.string.shortcuts_style_icons, R.string.shortcuts_style_tiles).map(::getString),
            look.style.ordinal,
        ) { app.shortcuts.look = app.shortcuts.look.copy(style = ShortcutLook.Style.entries[it]) }
        segments(
            findViewById(R.id.sizes),
            listOf(R.string.shortcuts_size_s, R.string.shortcuts_size_m, R.string.shortcuts_size_l).map(::getString),
            look.size.ordinal,
        ) { app.shortcuts.look = app.shortcuts.look.copy(size = ShortcutLook.Size.entries[it]) }
        segments(
            findViewById(R.id.rows),
            (1..ShortcutStore.MAX_ROWS).map { resources.getQuantityString(R.plurals.shortcuts_rows_count, it, it) },
            look.rows - 1,
        ) { app.shortcuts.look = app.shortcuts.look.copy(rows = it + 1) }
    }

    /** A row of choices; TalkBack hears them as radio buttons. */
    private fun segments(box: LinearLayout, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        box.removeAllViews()
        labels.forEachIndexed { i, label ->
            box.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(getColor(if (i == selected) R.color.on_accent_container else R.color.on_surface))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                background = getDrawable(R.drawable.bg_segment_selected)
                isSelected = i == selected
                setOnClickListener {
                    if (i == selected) return@setOnClickListener
                    Haptics.tap(it)
                    onPick(i)
                }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = RadioButton::class.java.name
                        info.isCheckable = true
                        if (android.os.Build.VERSION.SDK_INT >= 36) {
                            info.setChecked(if (i == selected) AccessibilityNodeInfo.CHECKED_STATE_TRUE else AccessibilityNodeInfo.CHECKED_STATE_FALSE)
                        } else {
                            @Suppress("DEPRECATION")
                            info.isChecked = i == selected
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(40f).toInt(), 1f))
        }
    }

    // Apps

    private fun renderApps() {
        val tv = tv ?: return
        if (drag.dragging) {
            renderPending = true
            return
        }
        renderPending = false
        val store = app.shortcuts
        val onRemote = store.onRemote(tv.pin)
        val pinnedApps = onRemote.filter { it.pinned }
        val otherApps = onRemote.filter { !it.pinned }
        pinned.removeAllViews()
        pinnedApps.forEachIndexed { i, shortcut -> pinned.addView(appRow(tv, shortcut, i, pinnedApps.size, indent = true)) }
        others.removeAllViews()
        otherApps.forEach { others.addView(appRow(tv, it, -1, 0, indent = pinnedApps.isNotEmpty())) }
        findViewById<View>(R.id.pinnedTitle).visibility = if (pinnedApps.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.othersTitle).visibility = if (otherApps.isEmpty() || pinnedApps.isEmpty()) View.GONE else View.VISIBLE
        hidden.removeAllViews()
        val hiddenApps = store.hidden(tv.pin)
        hiddenApps.forEach { shortcut ->
            val name = AppLabels.name(app, tv, shortcut.packageName, shortcut)
            hidden.addView(listRow(
                AppLabels.icon(app, tv, shortcut.packageName, name), name, uses(shortcut),
                trailing = rowButton(getString(R.string.shortcuts_restore)) { store.add(tv.pin, shortcut.packageName) },
                onClick = null,
            ).apply {
                background = getDrawable(R.drawable.bg_list_row)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(6f).toInt() }
            })
        }
        findViewById<View>(R.id.hiddenTitle).visibility = if (hiddenApps.isEmpty()) View.GONE else View.VISIBLE
        hidden.visibility = findViewById<View>(R.id.hiddenTitle).visibility
        val count = app.receivers.apps(tv).size
        syncText.text = when {
            syncing && syncCode != null -> getString(R.string.shortcuts_sync_confirm, syncCode)
            syncing -> getString(R.string.shortcuts_sync_running)
            count > 0 -> resources.getQuantityString(R.plurals.shortcuts_tv_apps_count, count, count, tv.name)
            else -> getString(R.string.shortcuts_tv_apps_text)
        }
        sync.isEnabled = !syncing
        focusAfterRender?.let { pkg ->
            focusAfterRender = null
            pinned.findViewWithTag<View>(pkg)?.let { row ->
                row.post { row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
            }
        }
    }

    private fun uses(shortcut: Shortcut) =
        if (shortcut.uses == 0) getString(R.string.shortcut_not_used) else resources.getQuantityString(R.plurals.shortcut_uses, shortcut.uses, shortcut.uses)

    /** An app on the remote: drag handle (pinned ones), icon, name and use count, and a menu. */
    private fun appRow(tv: Tv, shortcut: Shortcut, index: Int, pinnedCount: Int, indent: Boolean): View {
        val pkg = shortcut.packageName
        val name = AppLabels.name(app, tv, pkg, shortcut)
        val menu = ImageButton(this, null, 0, R.style.Libremote_Icon).apply {
            setImageResource(R.drawable.ic_more_vert)
            imageTintList = getColorStateList(R.color.on_surface_muted)
            contentDescription = getString(R.string.shortcut_menu_for, name)
            layoutParams = LinearLayout.LayoutParams(dp(48f).toInt(), dp(48f).toInt())
        }
        val row = listRow(AppLabels.icon(app, tv, pkg, name), name, uses(shortcut), trailing = menu) { showMenu(tv, pkg, it) }
        row.tag = pkg
        menu.setOnClickListener { showMenu(tv, pkg, it) }
        if (shortcut.pinned) {
            val handle = ImageView(this).apply {
                setImageResource(R.drawable.ic_drag_indicator)
                imageTintList = getColorStateList(R.color.on_surface_muted)
                scaleType = ImageView.ScaleType.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(32f).toInt(), dp(48f).toInt()).apply { marginEnd = dp(4f).toInt() }
            }
            row.addView(handle, 0)
            row.setPadding(0, row.paddingTop, row.paddingRight, row.paddingBottom)
            drag.attach(row, handle)
            row.accessibilityDelegate = MoveActions(tv, index, pinnedCount)
        } else if (indent) {
            // Lines up with the pinned rows' icons, past their drag handles.
            row.setPadding(dp(36f).toInt(), row.paddingTop, row.paddingRight, row.paddingBottom)
        }
        row.background = getDrawable(R.drawable.bg_list_row)
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(6f).toInt() }
        return row
    }

    /** TalkBack's alternative to dragging: Move up / Move down. */
    private inner class MoveActions(private val tv: Tv, private val index: Int, private val count: Int) : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            if (index > 0) info.addAction(AccessibilityAction(R.id.shortcut_action_move_up, getString(R.string.shortcut_move_up)))
            if (index < count - 1) info.addAction(AccessibilityAction(R.id.shortcut_action_move_down, getString(R.string.shortcut_move_down)))
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            val to = when (action) {
                R.id.shortcut_action_move_up -> index - 1
                R.id.shortcut_action_move_down -> index + 1
                else -> return super.performAccessibilityAction(host, action, args)
            }
            val order = app.shortcuts.onRemote(tv.pin).filter { it.pinned }.map { it.packageName }.toMutableList()
            if (index !in order.indices || to !in order.indices) return false
            val moved = order.removeAt(index)
            order.add(to, moved)
            // The list is rebuilt: keep TalkBack on the row that moved.
            focusAfterRender = moved
            app.shortcuts.setPinnedOrder(tv.pin, order)
            return true
        }
    }

    private fun showMenu(tv: Tv, pkg: String, anchor: View) {
        val store = app.shortcuts
        val isPinned = store.get(tv.pin, pkg)?.pinned == true
        PopupMenu(this, anchor).apply {
            menu.add(0, R.id.shortcut_action_pin, 0, if (isPinned) R.string.shortcut_unpin else R.string.shortcut_pin)
            menu.add(0, R.id.shortcut_action_rename, 1, R.string.shortcut_rename)
            menu.add(0, R.id.shortcut_action_hide, 2, R.string.shortcut_hide)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.shortcut_action_pin -> store.setPinned(tv.pin, pkg, !isPinned)
                    R.id.shortcut_action_rename -> ShortcutsView.renameShortcut(this@ShortcutsActivity, tv, pkg)
                    R.id.shortcut_action_hide -> store.hide(tv.pin, pkg)
                }
                true
            }
            show()
        }
    }

    private fun syncApps() {
        val tv = tv ?: return
        syncing = true
        renderApps()
        app.receivers.syncApps(tv, confirming = { code ->
            syncCode = code
            if (!isDestroyed) renderApps()
        }, cancelled = { isDestroyed }) { error ->
            syncing = false
            syncCode = null
            if (isDestroyed) return@syncApps
            renderApps()
            if (error is ReceiverDeclinedException) syncText.setText(R.string.shortcuts_sync_declined)
            else if (error != null) syncText.setText(R.string.shortcuts_sync_failed)
        }
    }

    private fun confirmReset() {
        val tv = tv ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.shortcuts_reset_title, tv.name))
            .setMessage(R.string.shortcuts_reset_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.shortcuts_reset_button) { _, _ -> app.shortcuts.reset(tv.pin) }
            .show()
    }

    companion object {
        private const val EXTRA_PIN = "pin"

        fun open(context: Context, tv: Tv?) {
            context.startActivity(Intent(context, ShortcutsActivity::class.java).apply { tv?.let { putExtra(EXTRA_PIN, it.pin) } })
        }
    }
}
