package tech.capullo.audio.snapcast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface

data class DiscoveredSnapserver(
    val serviceName: String,
    val serviceType: String,
    val hostAddress: String,
    val port: Int,
    /**
     * The broadcaster's HTTP port (web player + JSON-RPC/WebSocket control), read from the service's
     * `http` TXT attribute. Needed for listen-in control when the broadcaster uses OS-assigned ports
     * (the port isn't a fixed convention anymore). Falls back to the legacy fixed port when absent.
     */
    val httpPort: Int = SnapserverDiscoveryManager.HTTP_SERVICE_PORT,
)

class SnapserverDiscoveryManager(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredServers = MutableStateFlow<List<DiscoveredSnapserver>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredSnapserver>> = _discoveredServers.asStateFlow()

    private val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()

    // A capullo broadcaster advertises the SAME service name under TWO service types - `_snapcast._tcp`
    // (whose `port` is the client-facing STREAM port) and `_snapcast-stream._tcp` (whose `port` is the
    // JSON-RPC/control port). Keying purely by service name lets the second-resolved record clobber the
    // first, so a listener could end up dialing the CONTROL port as if it were the stream port (TCP
    // connects to the open control socket, then the snapcast hello handshake times out - silent no-audio).
    // Key by the ORIGINATING service type + name so the two records coexist, then surface rows strictly
    // from the `_snapcast._tcp` (stream) record in [rebuildList].
    private val resolveListeners = mutableMapOf<String, NsdManager.ResolveListener>()
    private val resolvedInfos = mutableMapOf<String, NsdServiceInfo>()

    private fun keyOf(originType: String, serviceName: String) = "$originType|$serviceName"

    fun startDiscovery() {
        startFor(SERVICE_TYPE)
        startFor(STREAM_SERVICE_TYPE)
    }

    fun stopDiscovery() {
        discoveryListeners.forEach {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListeners.clear()

        resolveListeners.values.forEach {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
                ) {
                    nsdManager.stopServiceResolution(it)
                }
            } catch (_: Exception) {}
        }
        resolveListeners.clear()
        resolvedInfos.clear()
        _discoveredServers.value = emptyList()
    }

    private fun startFor(originType: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed for $serviceType: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                resolvedInfos[keyOf(originType, service.serviceName)] = service
                resolve(service, originType)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                resolvedInfos.remove(keyOf(originType, service.serviceName))
                rebuildList()
            }
        }
        discoveryListeners.add(listener)
        nsdManager.discoverServices(originType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(info: NsdServiceInfo, originType: String) {
        val key = keyOf(originType, info.serviceName)
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                resolveListeners.remove(key)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                resolveListeners.remove(key)
                resolvedInfos[keyOf(originType, resolved.serviceName)] = resolved
                rebuildList()
            }
        }
        resolveListeners[key] = listener
        nsdManager.resolveService(info, listener)
    }

    private fun localIpAddresses(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filter { !it.isLoopbackAddress }
            ?.mapNotNull { it.hostAddress }
            ?.toSet() ?: emptySet()
    } catch (_: Exception) { emptySet() }

    private fun rebuildList() {
        val localIps = localIpAddresses()
        // Build rows STRICTLY from the `_snapcast._tcp` records - their `port` is the stream port a
        // snapclient must dial. The `_snapcast-stream._tcp` records (control port) are supplemental and
        // never used to source a row (they would poison the dial target). The `http` TXT rides on the
        // stream record too, so nothing is lost. One row per broadcaster (records are keyed per type).
        _discoveredServers.value = resolvedInfos.entries
            .filter { it.key.startsWith("$SERVICE_TYPE|") }
            .mapNotNull { (_, info) ->
                val host = info.host?.hostAddress ?: return@mapNotNull null
                if (host in localIps) return@mapNotNull null // skip self
                val name = info.serviceName
                    .let { if (it.startsWith(SERVICE_NAME_PREFIX)) it.substring(SERVICE_NAME_PREFIX.length) else it }
                val httpPort = info.attributes["http"]
                    ?.let { runCatching { String(it, Charsets.UTF_8).toInt() }.getOrNull() }
                    ?: HTTP_SERVICE_PORT
                DiscoveredSnapserver(name, info.serviceType, host, info.port, httpPort)
            }
    }

    companion object {
        private val TAG = SnapserverDiscoveryManager::class.java.simpleName
        const val SERVICE_NAME_PREFIX = "Snapcast - "
        const val SERVICE_TYPE = "_snapcast._tcp"
        const val SERVICE_PORT = 1604
        const val STREAM_SERVICE_TYPE = "_snapcast-stream._tcp"
        const val STREAM_SERVICE_PORT = 1605
        const val HTTP_SERVICE_PORT = 1680
    }
}
