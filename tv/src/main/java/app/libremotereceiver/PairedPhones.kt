package app.libremotereceiver

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import app.libremotetvservice.protocol.PhoneRegistry
import app.libremotetvservice.protocol.ReceiverInvite
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the receiver keeps its paired phones, and the keys of the ones the user removed: SharedPreferences. The
 * rules (which links pair, what the TV asks first) are PhoneRegistry's, in :protocol.
 */
// commit, not apply: a removal must hold even if the TV loses power straight after.
@SuppressLint("ApplySharedPref")
class PairedPhones(context: Context) : PhoneRegistry.Store {
    private val prefs = context.getSharedPreferences("phones", Context.MODE_PRIVATE)

    override fun load(): List<PhoneRegistry.Phone> {
        val json = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until json.length()).map { json.getJSONObject(it) }.map {
            PhoneRegistry.Phone(it.getString("key"), it.optString("name"), it.optLong("pairedMs"))
        }
    }

    override fun loadRemoved(): Set<String> = prefs.getStringSet(REMOVED, null).orEmpty().toSet()

    override fun save(phones: List<PhoneRegistry.Phone>, removed: Set<String>) {
        val json = JSONArray()
        phones.forEach { json.put(JSONObject().put("key", it.key).put("name", it.name).put("pairedMs", it.pairedMs)) }
        prefs.edit().putString(KEY, json.toString()).putStringSet(REMOVED, removed).commit()
    }

    private companion object {
        const val KEY = "phones"
        const val REMOVED = "removed"
    }
}

/**
 * A link from a phone (`libremote://receive?…` or `libremote://sync?…`): a paired phone's secret is kept for its
 * next connection; for a new or removed one, returns what to ask the user. Null when there is nothing to ask.
 */
fun PhoneRegistry.accept(link: Uri?): PhoneRegistry.Request? {
    if (link?.scheme != "libremote") return null
    val invite = ReceiverInvite.parse(
        link.getQueryParameter(ReceiverInvite.KEY_PARAM),
        link.getQueryParameter(ReceiverInvite.SECRET_PARAM),
        link.getQueryParameter(ReceiverInvite.NAME_PARAM),
        link.getQueryParameter(ReceiverInvite.VERSION_PARAM),
    ) ?: return null
    return accept(invite)
}
