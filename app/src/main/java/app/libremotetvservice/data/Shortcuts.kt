package app.libremotetvservice.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** How shortcuts look on the remote, the same for every TV. [rows] is 1 to 3; the rows scroll sideways together. */
data class ShortcutLook(val style: Style = Style.TEXT, val size: Size = Size.M, val rows: Int = 1) {
    enum class Style { TEXT, ICONS, TILES }
    enum class Size { S, M, L }
}

/** An app in a TV's shortcuts. */
data class Shortcut(
    val packageName: String,
    /** The user's name for it; null for the default (built-in table, then the TV's label, then the package). */
    val customName: String? = null,
    /** How often the TV showed it in front while this phone was connected. */
    val uses: Int = 0,
    val lastUsed: Long = 0,
    /** Its place among the pinned apps, or -1 when not pinned. */
    val pinnedAt: Int = -1,
    val hidden: Boolean = false,
    /** Breaks ties between apps used equally: the template's order first, then the order apps arrived in. */
    val order: Long = 0,
) {
    val pinned get() = pinnedAt >= 0
}

/**
 * Each paired TV's shortcuts (by the TV's pin): a template to start from, apps learned from what the TV shows in
 * front, and the user's pins, names and hidden apps. Also remembers per TV whether it has no Play Store, and the
 * look of the shortcut row. Main thread only.
 */
class ShortcutStore(
    context: Context,
    private val template: List<String>,
    private val isIgnored: (String) -> Boolean,
) {
    enum class Change { EDITED, LEARNED, LOOK }

    fun interface Listener {
        /** [pin] is the TV whose shortcuts changed, or null for the look. */
        fun onShortcutsChanged(pin: String?, change: Change)
    }

    private class TvShortcuts(val apps: MutableList<Shortcut>, var noPlay: Boolean, var lastInFront: String?)

    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
    private val cache = HashMap<String, TvShortcuts>()
    private val listeners = mutableListOf<Listener>()

    var look: ShortcutLook
        get() = runCatching {
            val (style, size, rows) = prefs.getString(KEY_LOOK, null)!!.split(',')
            ShortcutLook(ShortcutLook.Style.valueOf(style), ShortcutLook.Size.valueOf(size), rows.toInt().coerceIn(1, MAX_ROWS))
        }.getOrDefault(ShortcutLook())
        set(value) {
            prefs.edit().putString(KEY_LOOK, "${value.style},${value.size},${value.rows}").apply()
            notify(null, Change.LOOK)
        }

    fun addListener(listener: Listener) = listeners.add(listener)

    fun removeListener(listener: Listener) = listeners.remove(listener)

    /** Every app the TV's list knows, hidden ones included. */
    fun all(pin: String): List<Shortcut> = load(pin).apps.toList()

    fun get(pin: String, packageName: String): Shortcut? = load(pin).apps.firstOrNull { it.packageName == packageName }

    /** What the remote shows: pinned apps in the user's order, then the rest, most used first. */
    fun onRemote(pin: String): List<Shortcut> {
        val shown = load(pin).apps.filter { !it.hidden }
        return shown.filter { it.pinned }.sortedBy { it.pinnedAt } + shown.filter { !it.pinned }.sortedWith(BY_USE)
    }

    fun hidden(pin: String): List<Shortcut> = load(pin).apps.filter { it.hidden }.sortedWith(BY_USE)

    /**
     * The TV reported [packageName] in front. A change of app counts as one use; reports for the app already in
     * front (focus moving inside it, a reconnection) don't. Home screens, settings and the like are never learned.
     */
    fun sawInFront(pin: String, packageName: String) {
        val tv = load(pin)
        if (tv.lastInFront == packageName) return
        tv.lastInFront = packageName
        if (isIgnored(packageName)) return save(pin, tv)
        val now = System.currentTimeMillis()
        val known = tv.apps.indexOfFirst { it.packageName == packageName }
        if (known >= 0) {
            tv.apps[known] = tv.apps[known].let { it.copy(uses = it.uses + 1, lastUsed = now) }
        } else {
            tv.apps += Shortcut(packageName, uses = 1, lastUsed = now, order = now)
        }
        save(pin, tv, Change.LEARNED)
    }

    /** Puts [packageName] on the remote: shows it again if it was hidden, or adds it after the others. */
    fun add(pin: String, packageName: String) = edit(pin) { apps ->
        val i = apps.indexOfFirst { it.packageName == packageName }
        if (i >= 0) apps[i] = apps[i].copy(hidden = false)
        else apps += Shortcut(packageName, order = System.currentTimeMillis())
    }

    /** Pins [packageName] after the other pinned apps, or unpins it. */
    fun setPinned(pin: String, packageName: String, pinned: Boolean) = edit(pin) { apps ->
        val next = (apps.maxOfOrNull { it.pinnedAt } ?: -1) + 1
        update(apps, packageName) { it.copy(pinnedAt = if (pinned) next else -1, hidden = false) }
        renumberPins(apps)
    }

    /** Sets the pinned apps' order; [order] lists their packages. */
    fun setPinnedOrder(pin: String, order: List<String>) = edit(pin) { apps ->
        for (i in apps.indices) {
            val at = order.indexOf(apps[i].packageName)
            if (apps[i].pinned && at >= 0) apps[i] = apps[i].copy(pinnedAt = at)
        }
        renumberPins(apps)
    }

    /** [name] null or blank goes back to the default name. */
    fun rename(pin: String, packageName: String, name: String?) = edit(pin) { apps ->
        update(apps, packageName) { it.copy(customName = name?.trim()?.takeIf(String::isNotEmpty)) }
    }

    fun hide(pin: String, packageName: String) = edit(pin) { apps ->
        update(apps, packageName) { it.copy(hidden = true, pinnedAt = -1) }
        renumberPins(apps)
    }

    /** Back to the template: forgets learned apps, pins, names and hidden apps, and whether the TV has Play. */
    fun reset(pin: String) {
        cache[pin] = fresh()
        save(pin, cache.getValue(pin), Change.EDITED)
    }

    /** True once a Play Store launch failed on this TV: it has no Play Store (or it is disabled). */
    fun noPlay(pin: String) = load(pin).noPlay

    fun setNoPlay(pin: String, noPlay: Boolean) {
        val tv = load(pin)
        if (tv.noPlay == noPlay) return
        tv.noPlay = noPlay
        save(pin, tv)
    }

    /** The TV was paired again under a new pin: keep its shortcuts. */
    fun move(fromPin: String, toPin: String) {
        if (fromPin == toPin || !prefs.contains(KEY_TV + fromPin)) return
        val tv = load(fromPin)
        cache.remove(fromPin)
        prefs.edit().remove(KEY_TV + fromPin).apply()
        cache[toPin] = tv
        save(toPin, tv, Change.EDITED)
    }

    /** Drops the lists of TVs that are no longer paired. */
    fun keepOnly(pins: Set<String>) {
        val stale = prefs.all.keys.filter { it.startsWith(KEY_TV) && it.removePrefix(KEY_TV) !in pins }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach(::remove) }.apply()
        stale.forEach { cache.remove(it.removePrefix(KEY_TV)) }
    }

    private inline fun edit(pin: String, change: (MutableList<Shortcut>) -> Unit) {
        val tv = load(pin)
        change(tv.apps)
        save(pin, tv, Change.EDITED)
    }

    private inline fun update(apps: MutableList<Shortcut>, packageName: String, change: (Shortcut) -> Shortcut) {
        val i = apps.indexOfFirst { it.packageName == packageName }
        if (i >= 0) apps[i] = change(apps[i])
    }

    /** Pinned positions 0, 1, 2… in their current order. */
    private fun renumberPins(apps: MutableList<Shortcut>) {
        apps.filter { it.pinned }.sortedBy { it.pinnedAt }.forEachIndexed { at, app ->
            val i = apps.indexOfFirst { it.packageName == app.packageName }
            apps[i] = app.copy(pinnedAt = at)
        }
    }

    private fun fresh() = TvShortcuts(
        template.mapIndexed { i, p -> Shortcut(p, order = i.toLong()) }.toMutableList(), noPlay = false, lastInFront = null,
    )

    private fun load(pin: String): TvShortcuts = cache.getOrPut(pin) {
        val json = prefs.getString(KEY_TV + pin, null) ?: return@getOrPut fresh()
        runCatching {
            val o = JSONObject(json)
            val list = o.getJSONArray("apps")
            val apps = (0 until list.length()).map { list.getJSONObject(it) }.map {
                Shortcut(
                    packageName = it.getString("p"),
                    customName = it.optString("n").takeIf(String::isNotEmpty),
                    uses = it.optInt("u"),
                    lastUsed = it.optLong("t"),
                    pinnedAt = it.optInt("pin", -1),
                    hidden = it.optBoolean("h"),
                    order = it.optLong("o"),
                )
            }
            TvShortcuts(apps.toMutableList(), o.optBoolean("noPlay"), o.optString("front").takeIf(String::isNotEmpty))
        }.getOrElse { fresh() }
    }

    private fun save(pin: String, tv: TvShortcuts, change: Change? = null) {
        val apps = JSONArray()
        tv.apps.forEach {
            apps.put(
                JSONObject().put("p", it.packageName).put("n", it.customName ?: "").put("u", it.uses).put("t", it.lastUsed)
                    .put("pin", it.pinnedAt).put("h", it.hidden).put("o", it.order),
            )
        }
        val json = JSONObject().put("v", 1).put("apps", apps).put("noPlay", tv.noPlay).put("front", tv.lastInFront ?: "")
        prefs.edit().putString(KEY_TV + pin, json.toString()).apply()
        if (change != null) notify(pin, change)
    }

    private fun notify(pin: String?, change: Change) = listeners.toList().forEach { it.onShortcutsChanged(pin, change) }

    companion object {
        const val MAX_ROWS = 3
        private const val KEY_TV = "tv:"
        private const val KEY_LOOK = "look"

        /** Most used first; equally used ones by recent use, then template order, then when they arrived. */
        private val BY_USE = compareByDescending<Shortcut> { it.uses }.thenByDescending { it.lastUsed }.thenBy { it.order }
    }
}
