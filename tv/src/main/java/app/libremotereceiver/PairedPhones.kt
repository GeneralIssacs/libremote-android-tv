package app.libremotereceiver

import android.content.Context
import android.net.Uri
import app.libremotetvservice.protocol.ReceiverInvite
import app.libremotetvservice.protocol.ReceiverServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phones that may use the receiver. A phone pairs by opening the receiver with a link (ReceiverInvite) through
 * the TV's remote service, which only phones paired with the TV can do; the link carries the phone's key hash and
 * a one-time secret. Secrets stay in memory: each is used for one connection, soon after the link arrives.
 */
class PairedPhones(context: Context) : ReceiverServer.Phones {

    /** A paired phone: its key hash, the name it last gave (empty until it connects), and when it paired. */
    data class Phone(val key: String, val name: String, val pairedMs: Long)

    private val prefs = context.getSharedPreferences("phones", Context.MODE_PRIVATE)
    private val secrets = HashMap<String, ByteArray>()
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun all(): List<Phone> {
        val json = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until json.length()).map { json.getJSONObject(it) }.map {
            Phone(it.getString("key"), it.optString("name"), it.optLong("pairedMs"))
        }
    }

    /**
     * Pairs the phone in [link] (`libremote://receive?…` or `libremote://sync?…`) and keeps its secret for its next
     * connection. Returns true when the phone is new here, false when it was paired already or the link holds none.
     */
    fun accept(link: Uri?): Boolean {
        if (link?.scheme != "libremote") return false
        val invite = ReceiverInvite.parse(link.getQueryParameter(ReceiverInvite.KEY_PARAM), link.getQueryParameter(ReceiverInvite.SECRET_PARAM))
            ?: return false
        val added = synchronized(this) {
            secrets[invite.phoneKey] = invite.secret
            val phones = all()
            if (phones.any { it.key == invite.phoneKey }) false else {
                write(phones + Phone(invite.phoneKey, "", System.currentTimeMillis()))
                true
            }
        }
        if (added) notifyListeners()
        return added
    }

    fun remove(key: String) {
        synchronized(this) {
            secrets.remove(key)
            write(all().filter { it.key != key })
        }
        notifyListeners()
    }

    override fun isPaired(phoneKey: String) = all().any { it.key == phoneKey }

    @Synchronized
    override fun takeSecret(phoneKey: String): ByteArray? = secrets.remove(phoneKey)

    override fun named(phoneKey: String, name: String) {
        val changed = synchronized(this) {
            val phones = all()
            if (phones.none { it.key == phoneKey && it.name != name }) false else {
                write(phones.map { if (it.key == phoneKey) it.copy(name = name) else it })
                true
            }
        }
        if (changed) notifyListeners()
    }

    /** Listener calls come on whichever thread changed the list. */
    fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }

    fun removeListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }

    private fun notifyListeners() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    private fun write(phones: List<Phone>) {
        val json = JSONArray()
        phones.forEach { json.put(JSONObject().put("key", it.key).put("name", it.name).put("pairedMs", it.pairedMs)) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "phones"
    }
}
