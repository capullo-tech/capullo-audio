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
)

class SnapserverDiscoveryManager(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredServers = MutableStateFlow<List<DiscoveredSnapserver>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredSnapserver>> = _discoveredServers.asStateFlow()

    private val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val resolveListeners = mutableMapOf<String, NsdManager.ResolveListener>()
    private val resolvedInfos = mutableMapOf<String, NsdServiceInfo>()

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

    private fun startFor(serviceType: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed for $serviceType: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                resolvedInfos[service.serviceName] = service
                resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                resolvedInfos.remove(service.serviceName)
                rebuildList()
            }
        }
        discoveryListeners.add(listener)
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                resolveListeners.remove(serviceInfo.serviceName)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                resolveListeners.remove(resolved.serviceName)
                resolvedInfos[resolved.serviceName] = resolved
                rebuildList()
            }
        }
        resolveListeners[info.serviceName] = listener
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
        _discoveredServers.value = resolvedInfos.values.mapNotNull { info ->
            val host = info.host?.hostAddress ?: return@mapNotNull null
            if (host in localIps) return@mapNotNull null  // skip self
            val name = info.serviceName
                .let { if (it.startsWith(SERVICE_NAME_PREFIX)) it.substring(SERVICE_NAME_PREFIX.length) else it }
            DiscoveredSnapserver(name, info.serviceType, host, info.port)
        }
    }

    companion object {
        private val TAG = SnapserverDiscoveryManager::class.java.simpleName
        const val SERVICE_NAME_PREFIX = "Snapcast - "
        const val SERVICE_TYPE = "_snapcast._tcp"
        const val SERVICE_PORT = 1604
        const val STREAM_SERVICE_TYPE = "_snapcast-stream._tcp"
        const val STREAM_SERVICE_PORT = 1605
    }
}
