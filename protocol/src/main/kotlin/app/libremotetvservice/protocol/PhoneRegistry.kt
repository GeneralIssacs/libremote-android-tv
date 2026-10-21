package app.libremotetvservice.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phones Libremote Receiver accepts, and the one the TV is asking about. A phone pairs by opening the receiver
 * with a link ([ReceiverInvite]); a link from a key the receiver doesn't know pairs nothing by itself, since any
 * app on the TV can open one. The TV asks the user first ([accept] returns what to ask), and only [decide] with
 * allow pairs it. One question at a time: links from other new keys are ignored while it is open.
 *
 * Secrets stay in memory: each is used for one connection, soon after its link arrives. Thread-safe; listener
 * calls come on whichever thread changed something.
 */
class PhoneRegistry(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long the TV asks before it gives up, which counts as a no. */
    val confirmMs: Long = CONFIRM_MS,
) : ReceiverServer.Phones {

    /** Where the paired phones, and the keys of removed ones, are kept: SharedPreferences on the TV. */
    interface Store {
        fun load(): List<Phone>
        fun loadRemoved(): Set<String>

        /** Both at once: one write. */
        fun save(phones: List<Phone>, removed: Set<String>)
    }

    /** Keeps them in memory, for tests. */
    class MemoryStore : Store {
        @Volatile private var phones = emptyList<Phone>()
        @Volatile private var removed = emptySet<String>()
        override fun load() = phones
        override fun loadRemoved() = removed
        override fun save(phones: List<Phone>, removed: Set<String>) {
            this.phones = phones
            this.removed = removed
        }
    }

    /** A paired phone: its key hash, the name it last gave (empty until it gives one), and when it paired. */
    data class Phone(val key: String, val name: String, val pairedMs: Long)

    /**
     * What the TV asks the user about: the phone's [key], the [name] in its link (anyone can write any name there),
     * the [code] the phone shows too, [untilMs], when the question lapses, and whether the user removed this phone
     * before ([wasRemoved]). [showsCode] is false for a link from Libremote before 1.0.1, which shows no code and
     * can't wait: it tries again once allowed.
     */
    class Request internal constructor(
        val key: String,
        val name: String?,
        val code: String,
        val untilMs: Long,
        val wasRemoved: Boolean,
        val showsCode: Boolean,
    ) {
        internal val answered = CountDownLatch(1)
        @Volatile internal var allowed = false

        /** A newer link from the same phone took this question's place: whoever waited on it waits on that one. */
        @Volatile internal var replaced = false
    }

    private val secrets = HashMap<String, ByteArray>()
    private var request: Request? = null
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun all(): List<Phone> = store.load()

    /**
     * A link arrived. A paired phone gets its secret kept for its next connection, and null back: nothing to ask.
     * Otherwise the Request to put to the user, or null while another phone's question is open.
     */
    fun accept(invite: ReceiverInvite): Request? {
        var replaced: Request? = null
        val asked = synchronized(this) {
            val key = invite.phoneKey
            if (store.load().any { it.key == key }) {
                secrets[key] = invite.secret
                return null
            }
            val open = open()
            if (open != null && open.key != key) return null
            // The same phone again (another link, say a sync while a send waits, or a retry): the new link's code
            // replaces the old one's, and connections waiting on the old question wait on this one.
            replaced = open
            secrets[key] = invite.secret
            val wasRemoved = key in store.loadRemoved()
            Request(key, invite.name, invite.code, clock() + confirmMs, wasRemoved, invite.canWait).also { request = it }
        }
        replaced?.let {
            it.replaced = true
            it.answered.countDown()
        }
        notifyListeners()
        return asked
    }

    /** The question open now, if any. */
    @Synchronized
    fun pending(): Request? = open()

    /**
     * The user answered [asked]: allow pairs its phone, anything else leaves it out. Only the question still open
     * counts: an answer to one that lapsed or was replaced changes nothing. True when the phone was paired.
     */
    fun decide(asked: Request, allow: Boolean): Boolean {
        val paired = synchronized(this) {
            if (request !== asked) return false
            request = null
            val key = asked.key
            if (allow && clock() < asked.untilMs) {
                store.save(store.load() + Phone(key, asked.name.orEmpty(), clock()), store.loadRemoved() - key)
                asked.allowed = true
            } else {
                secrets.remove(key)
            }
            asked.allowed
        }
        asked.answered.countDown()
        notifyListeners()
        return paired
    }

    /**
     * The user removed [key]: it is no longer paired, and stays marked as removed, so its next link asks again
     * (saying so) rather than pairing it. A question open about it is turned down. The caller drops its connections.
     */
    fun remove(key: String) {
        val turnedDown = synchronized(this) {
            secrets.remove(key)
            store.save(store.load().filter { it.key != key }, store.loadRemoved() + key)
            request?.takeIf { it.key == key }?.also { request = null }
        }
        turnedDown?.answered?.countDown()
        notifyListeners()
    }

    override fun isPaired(phoneKey: String) = all().any { it.key == phoneKey }

    @Synchronized
    override fun isWaiting(phoneKey: String) = open()?.let { it.key == phoneKey && it.showsCode } == true

    override fun awaitApproval(phoneKey: String, timeoutMs: Long): Boolean {
        val deadline = clock() + timeoutMs
        while (true) {
            val asked = synchronized(this) { open()?.takeIf { it.key == phoneKey } } ?: return isPaired(phoneKey)
            val wait = minOf(deadline, asked.untilMs) - clock()
            if (wait <= 0 || !asked.answered.await(wait, TimeUnit.MILLISECONDS)) {
                decide(asked, allow = false)
                return false
            }
            // Replaced by a newer link from the same phone: wait for the answer to that one.
            if (!asked.replaced) return asked.allowed
        }
    }

    @Synchronized
    override fun takeSecret(phoneKey: String): ByteArray? = secrets.remove(phoneKey)

    override fun named(phoneKey: String, name: String) {
        val changed = synchronized(this) {
            val phones = store.load()
            if (phones.none { it.key == phoneKey && it.name != name }) false else {
                store.save(phones.map { if (it.key == phoneKey) it.copy(name = name) else it }, store.loadRemoved())
                true
            }
        }
        if (changed) notifyListeners()
    }

    fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }

    fun removeListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }

    private fun notifyListeners() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    /** The open question; one that lapsed is dropped. Holds the lock. */
    private fun open(): Request? {
        val open = request ?: return null
        if (clock() < open.untilMs) return open
        request = null
        secrets.remove(open.key)
        open.answered.countDown()
        return null
    }

    companion object {
        /** A minute to walk to the TV and find the remote. */
        const val CONFIRM_MS = 60_000L
    }
}
