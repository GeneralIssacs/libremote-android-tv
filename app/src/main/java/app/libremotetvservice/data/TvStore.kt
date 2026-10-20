package app.libremotetvservice.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A paired TV. [pin] identifies it (see publicKeyPin); [host] is where it was last found. */
data class Tv(val pin: String, val name: String, val host: String, val lastUsed: Long = 0)

/** Paired TVs, most recently used first. */
class TvStore(context: Context) {
    private val prefs = context.getSharedPreferences("tvs", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()

    fun all(): List<Tv> {
        val json = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until json.length()).map { json.getJSONObject(it) }.map {
            Tv(it.getString("pin"), it.getString("name"), it.getString("host"), it.optLong("lastUsed"))
        }.sortedByDescending { it.lastUsed }
    }

    fun get(pin: String) = all().firstOrNull { it.pin == pin }

    /** The TV to open on launch. */
    fun last() = all().firstOrNull()

    fun save(tv: Tv) = write(all().filter { it.pin != tv.pin } + tv)

    fun touch(pin: String) = get(pin)?.let { save(it.copy(lastUsed = System.currentTimeMillis())) }

    fun remove(pin: String) = write(all().filter { it.pin != pin })

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun write(tvs: List<Tv>) {
        val json = JSONArray()
        tvs.forEach {
            json.put(JSONObject().put("pin", it.pin).put("name", it.name).put("host", it.host).put("lastUsed", it.lastUsed))
        }
        prefs.edit().putString(KEY, json.toString()).apply()
        listeners.toList().forEach { it() }
    }

    private companion object {
        const val KEY = "tvs"
    }
}
