package app.libremotetvservice.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.libremotetvservice.R

/**
 * Settings › Remote guide: every button of the remote, top to bottom, what it does, and where it may not work. The
 * remote itself shows icons only, so this is the one place with their names. The page is built from
 * [RemoteGuide.sections]: when the remote's layout changes, change that list.
 */
object RemoteGuide {
    /** One button. [caveat] says where it may not work, or null. */
    class Entry(val icon: Int, val name: Int, val does: Int, val caveat: Int? = null)

    /** [note] explains the section as a whole, above its buttons. */
    class Section(val title: Int, val entries: List<Entry>, val note: Int? = null)

    val sections = listOf(
        Section(
            R.string.guide_top, listOf(
                Entry(R.drawable.ic_expand_more, R.string.guide_tv_name, R.string.guide_tv_name_does),
                Entry(R.drawable.ic_upload_file, R.string.send_files, R.string.guide_send_files_does, R.string.guide_send_files_caveat),
                Entry(R.drawable.ic_power_settings_new, R.string.key_power, R.string.guide_power_does, R.string.guide_power_caveat),
                Entry(R.drawable.ic_settings, R.string.settings, R.string.guide_app_settings_does),
            ),
        ),
        // TvPickerSheet, in its order.
        Section(
            R.string.guide_tv_menu, listOf(
                Entry(R.drawable.ic_add, R.string.add_tv, R.string.guide_add_tv_does),
                Entry(R.drawable.ic_settings_remote, R.string.find_remote, R.string.guide_find_remote_does, R.string.guide_find_remote_caveat),
                Entry(R.drawable.ic_channels, R.string.tv_buttons, R.string.guide_tv_buttons_does, R.string.guide_tv_buttons_caveat),
                Entry(R.drawable.ic_info, R.string.about_tv, R.string.guide_about_tv_does),
            ),
        ),
        Section(
            R.string.guide_pad, listOf(
                Entry(R.drawable.ic_remote, R.string.mode_full, R.string.guide_full_does),
                Entry(R.drawable.ic_gamepad, R.string.mode_dpad, R.string.guide_dpad_does),
                Entry(R.drawable.ic_swipe, R.string.mode_touchpad, R.string.guide_touchpad_does),
                Entry(R.drawable.ic_arrow_range, R.string.strip, R.string.guide_strip_does),
            ),
        ),
        // Full mode's extra keys (remote_pad), in their order, less Search and Apps.
        Section(
            R.string.guide_extra_keys, listOf(
                Entry(R.drawable.ic_closed_caption, R.string.key_captions, R.string.guide_captions_does, R.string.guide_in_apps_caveat),
                Entry(R.drawable.ic_info, R.string.key_info, R.string.guide_info_does, R.string.guide_info_caveat),
                Entry(R.drawable.ic_live_tv, R.string.key_guide, R.string.guide_guide_does, R.string.guide_guide_caveat),
                Entry(R.drawable.ic_tv_settings, R.string.key_settings, R.string.guide_settings_does, R.string.guide_settings_caveat),
                Entry(R.drawable.ic_input, R.string.key_input, R.string.guide_input_does, R.string.guide_input_caveat),
            ),
            note = R.string.guide_extra_keys_note,
        ),
        Section(
            R.string.guide_volume, listOf(
                Entry(R.drawable.ic_volume_up, R.string.key_volume_up, R.string.guide_volume_up_does),
                Entry(R.drawable.ic_volume_off, R.string.key_mute, R.string.guide_mute_does),
                Entry(R.drawable.ic_volume_down, R.string.key_volume_down, R.string.guide_volume_down_does, R.string.guide_volume_caveat),
            ),
        ),
        // The right-hand column: RemoteActivity.showKind.
        Section(
            R.string.guide_right, listOf(
                Entry(R.drawable.ic_add, R.string.key_channel_up, R.string.guide_channel_up_does, R.string.guide_channel_caveat),
                Entry(R.drawable.ic_menu, R.string.key_menu, R.string.guide_menu_key_does, R.string.guide_menu_key_caveat),
                Entry(R.drawable.ic_remove, R.string.key_channel_down, R.string.guide_channel_down_does, R.string.guide_channel_caveat),
                Entry(R.drawable.ic_search, R.string.key_search, R.string.guide_search_does),
                Entry(R.drawable.ic_apps, R.string.key_apps, R.string.guide_apps_does, R.string.guide_apps_caveat),
            ),
            note = R.string.guide_right_note,
        ),
        Section(
            R.string.guide_media, listOf(
                Entry(R.drawable.ic_skip_previous, R.string.key_previous, R.string.guide_previous_does, R.string.guide_in_apps_caveat),
                Entry(R.drawable.ic_fast_rewind, R.string.key_rewind, R.string.guide_rewind_does, R.string.guide_media_caveat),
                Entry(R.drawable.ic_play_pause, R.string.key_play_pause, R.string.guide_play_pause_does),
                Entry(R.drawable.ic_fast_forward, R.string.key_fast_forward, R.string.guide_fast_forward_does, R.string.guide_media_caveat),
                Entry(R.drawable.ic_skip_next, R.string.key_next, R.string.guide_next_does, R.string.guide_in_apps_caveat),
            ),
        ),
        Section(
            R.string.guide_buttons, listOf(
                Entry(R.drawable.ic_arrow_back, R.string.key_back, R.string.guide_back_does),
                Entry(R.drawable.ic_home, R.string.key_home, R.string.guide_home_does, R.string.guide_home_caveat),
                Entry(R.drawable.ic_keyboard, R.string.keyboard, R.string.guide_keyboard_does, R.string.guide_keyboard_caveat),
            ),
        ),
        Section(
            R.string.guide_shortcuts, listOf(
                Entry(R.drawable.ic_apps, R.string.guide_shortcut, R.string.guide_shortcut_does, R.string.guide_shortcut_caveat),
                Entry(R.drawable.ic_add, R.string.shortcuts_add, R.string.guide_shortcut_add_does),
            ),
        ),
    )
}

class RemoteGuideActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_guide)
        edgeToEdge(findViewById(R.id.root))
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        val list = findViewById<LinearLayout>(R.id.guide)
        for (section in RemoteGuide.sections) {
            list.addView(sectionTitle(getString(section.title)))
            section.note?.let { note ->
                list.addView(TextView(this, null, 0, R.style.Libremote_Body).apply {
                    setText(note)
                    textSize = 14f
                    setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), dp(4f).toInt())
                })
            }
            section.entries.forEach { list.addView(entry(it)) }
        }
    }

    private fun entry(entry: RemoteGuide.Entry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt())
            // TalkBack reads each button as one item: name, what it does, where it may not work.
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        row.addView(ImageView(this).apply {
            setImageResource(entry.icon)
            imageTintList = getColorStateList(R.color.on_surface)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.surface_high))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()).apply { marginEnd = dp(16f).toInt() })
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply {
            setText(entry.name)
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(getColor(R.color.on_surface))
        })
        text.addView(TextView(this, null, 0, R.style.Libremote_Body).apply {
            setText(entry.does)
            textSize = 14f
            setPadding(0, dp(2f).toInt(), 0, 0)
        })
        entry.caveat?.let { caveat ->
            text.addView(TextView(this).apply {
                setText(caveat)
                textSize = 13f
                setTextColor(getColor(R.color.on_surface_muted))
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_info_small, 0, 0, 0)
                compoundDrawablePadding = dp(6f).toInt()
                compoundDrawableTintList = getColorStateList(R.color.accent)
                gravity = Gravity.TOP
                setPadding(0, dp(6f).toInt(), 0, 0)
            })
        }
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }
}
