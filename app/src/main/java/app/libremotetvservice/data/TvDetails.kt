package app.libremotetvservice.data

import android.content.Context
import app.libremotetvservice.protocol.TvInfo
import org.json.JSONObject

/**
 * What each paired TV last said about itself when it connected (model, Android version, remote service, features),
 * kept on the phone so "About this TV" and the shortcut row work while it is offline. Never sent anywhere. Also
 * whether it is a TV or a box ([kind]), which the user can set in the TV picker.
 */
class TvDetails(context: Context) {
    private val prefs = context.getSharedPreferences("tv_details", Context.MODE_PRIVATE)

    /** The user's choice of TV or box, per TV; absent means detect it. */
    private val kinds = context.getSharedPreferences("tv_kinds", Context.MODE_PRIVATE)

    fun get(pin: String): TvInfo? = runCatching {
        val o = JSONObject(prefs.getString(pin, null)!!)
        fun text(key: String) = o.optString(key).takeIf(String::isNotEmpty)
        TvInfo(o.getInt("features"), text("model"), text("maker"), text("android"), text("service"), text("serviceVersion"))
    }.getOrNull()

    /** When [get] was last reported, or 0. */
    fun seenAt(pin: String): Long = runCatching { JSONObject(prefs.getString(pin, null)!!).optLong("at") }.getOrDefault(0)

    fun save(pin: String, info: TvInfo) {
        val o = JSONObject().put("features", info.features).put("model", info.model ?: "").put("maker", info.manufacturer ?: "")
            .put("android", info.osVersion ?: "").put("service", info.servicePackage ?: "")
            .put("serviceVersion", info.serviceVersion ?: "").put("at", System.currentTimeMillis())
        prefs.edit().putString(pin, o.toString()).apply()
    }

    /** TV or box: the user's choice, else detected from what the TV reported ([info], or what it reported last). */
    fun kind(pin: String, info: TvInfo? = null): TvKind = chosenKind(pin) ?: TvKind.detect(info ?: get(pin))

    fun chosenKind(pin: String): TvKind? = kinds.getString(pin, null)?.let { runCatching { TvKind.valueOf(it) }.getOrNull() }

    /** Sets [pin]'s kind; a choice that matches what detection says is stored as no choice, so detection stays in charge. */
    fun chooseKind(pin: String, kind: TvKind) {
        if (kind == TvKind.detect(get(pin))) kinds.edit().remove(pin).apply()
        else kinds.edit().putString(pin, kind.name).apply()
    }

    /** The TV was paired again under a new pin. */
    fun move(fromPin: String, toPin: String) {
        if (fromPin == toPin) return
        prefs.getString(fromPin, null)?.let { prefs.edit().putString(toPin, it).remove(fromPin).apply() }
        kinds.getString(fromPin, null)?.let { kinds.edit().putString(toPin, it).remove(fromPin).apply() }
    }

    /** Drops what TVs that are no longer paired said, and the choices made for them. */
    fun keepOnly(pins: Set<String>) {
        for (store in listOf(prefs, kinds)) {
            val stale = store.all.keys.filter { it !in pins }
            if (stale.isNotEmpty()) store.edit().apply { stale.forEach(::remove) }.apply()
        }
    }
}
