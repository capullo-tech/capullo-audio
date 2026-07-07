package tech.capullo.audio.snapcast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

class SnapserverNsdRegistrar(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()
    private var advertisedName = Build.MODEL

    /**
     * Advertise the running Snapserver over NSD. Pass the server's resolved ports
     * ([SnapserverProcess.ports]) so discovery carries the real ports when they are auto-assigned;
     * the defaults keep the legacy fixed ports for callers that have not opted into free ports.
     */
    fun start(
        customName: String = "",
        streamPort: Int = SnapserverDiscoveryManager.SERVICE_PORT,
        tcpPort: Int = SnapserverDiscoveryManager.STREAM_SERVICE_PORT,
        httpPort: Int = SnapserverDiscoveryManager.HTTP_SERVICE_PORT,
    ) {
        advertisedName = customName.trim().ifBlank { Build.MODEL }
        // The HTTP port (web player + JSON-RPC/WebSocket control) isn't the service port, so it rides
        // along as a `http` TXT attribute - put on both records so whichever a listener resolves
        // carries it. This lets a listener reach a broadcaster's control socket even when the ports
        // are OS-assigned (no fixed-port convention).
        register(SnapserverDiscoveryManager.SERVICE_TYPE, streamPort, httpPort)
        register(SnapserverDiscoveryManager.STREAM_SERVICE_TYPE, tcpPort, httpPort)
        Log.d(TAG, "Snapserver NSD registered as '${SnapserverDiscoveryManager.SERVICE_NAME_PREFIX}$advertisedName'")
    }

    fun stop() {
        listeners.forEach {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        listeners.clear()
        Log.d(TAG, "Snapserver NSD unregistered")
    }

    private fun register(serviceType: String, port: Int, httpPort: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "${SnapserverDiscoveryManager.SERVICE_NAME_PREFIX}$advertisedName"
            this.serviceType = serviceType
            this.port = port
            setAttribute("http", httpPort.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Registration failed for $serviceType: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        listeners.add(listener)
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    companion object {
        private val TAG = SnapserverNsdRegistrar::class.java.simpleName
    }
}
