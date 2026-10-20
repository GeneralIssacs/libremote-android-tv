package app.libremotetvservice.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress

/** A TV announcing Google's remote service on the local network. [name] is the TV's device name. */
data class FoundTv(val name: String, val host: String)

/**
 * Finds TVs over mDNS (service `_androidtvremote2._tcp`). Results arrive on the main thread, as the full list
 * each time it changes.
 */
class Discovery(context: Context, private val onChange: (List<FoundTv>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, FoundTv>()
    private var listener: NsdManager.DiscoveryListener? = null

    // Before Android 14 a phone resolves one service at a time.
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val infoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    val tvs: List<FoundTv> get() = found.values.toList()

    fun start() {
        if (listener != null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { main.post { resolve(info) } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { lost(info.serviceName) } }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { listener = null } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = discovery
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        infoCallbacks.clear()
        queue.clear()
    }

    private fun resolve(info: NsdServiceInfo) {
        if (listener == null) return
        if (Build.VERSION.SDK_INT >= 34) {
            if (info.serviceName in infoCallbacks) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                    main.post { add(resolved.serviceName, resolved.hostAddresses) }
                }
                override fun onServiceLost() { main.post { lost(info.serviceName) } }
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            infoCallbacks[info.serviceName] = callback
            runCatching { nsd.registerServiceInfoCallback(info, { main.post(it) }, callback) }
        } else {
            queue.addLast(info)
            resolveNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || listener == null) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                main.post {
                resolving = false
                resolved.host?.let { add(resolved.serviceName, listOf(it)) }
                resolveNext()
                }
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                main.post {
                resolving = false
                resolveNext()
                }
            }
        })
    }

    private fun add(name: String, addresses: List<InetAddress>) {
        if (listener == null) return
        // Prefer IPv4: it is what people recognise in "add by address", and it needs no interface scope.
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return
        val tv = FoundTv(name, address.hostAddress ?: return)
        if (found[name] != tv) {
            found[name] = tv
            onChange(tvs)
        }
    }

    private fun lost(name: String) {
        if (found.remove(name) != null) onChange(tvs)
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.remove(name)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
    }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}
