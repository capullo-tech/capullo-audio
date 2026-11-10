package tech.capullo.audio.snapcast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a broadcaster's ports from the one port a listener knows.
 *
 * A snapclient must dial the raw-TCP stream port, which appears nowhere on the HTTP/JSON-RPC surface.
 * A capullo broadcaster publishes its resolved ports as `listen.json` in its served doc_root (see
 * [SnapserverProcess.writeListenInfo]), reachable on its HTTP port. Its ports are one consecutive
 * stream/tcp/http block (see [SnapserverPorts]), so the HTTP port is the last of the block: [resolve]
 * probes the typed port and the two above it, which reaches `listen.json` whether the listener typed
 * the stream, tcp, or HTTP port.
 */
object SnapserverListenInfo {

    private val TAG = SnapserverListenInfo::class.java.simpleName
    private const val TIMEOUT_MS = 4000
    private const val MAX_PORT = 65535

    /**
     * The broadcaster's ports given whichever port of its block the listener typed, or
     * [defaultHttpPort] for a bare host. Probes `listen.json` on typedPort, typedPort+1, typedPort+2
     * sequentially and returns the first hit that contains the typed port; a hit that doesn't is an
     * unrelated server next door and is skipped. Null when nothing resolves (stock/legacy snapserver,
     * unreachable host): callers should fall back to treating the typed port as the stream port.
     * Bounded: each probe has [TIMEOUT_MS] connect and read timeouts, a refused port fails fast, and
     * probing stops at the first hit.
     */
    suspend fun resolve(
        host: String,
        typedPort: Int?,
        defaultHttpPort: Int = SnapserverPorts.Fixed.httpPort,
    ): SnapserverPorts? = resolve(typedPort, defaultHttpPort) { fetch(host, it) }

    /** [resolve] with an injectable `listen.json` fetch (tests fake the broadcaster). */
    internal suspend fun resolve(
        typedPort: Int?,
        defaultHttpPort: Int,
        fetch: suspend (httpPort: Int) -> SnapserverPorts?,
    ): SnapserverPorts? {
        val known = typedPort ?: defaultHttpPort
        val candidates =
            if (typedPort == null) listOf(defaultHttpPort) else (typedPort..typedPort + 2).filter { it <= MAX_PORT }
        for (port in candidates) {
            val ports = fetch(port) ?: continue
            if (known == ports.streamPort || known == ports.tcpPort || known == ports.httpPort) return ports
            Log.d(TAG, "listen.json on :$port is $ports, which lacks :$known - not this broadcaster")
        }
        return null
    }

    /**
     * GET `http://$host:$httpPort/listen.json` and parse the ports. Returns null if the endpoint is
     * unreachable or not a capullo broadcaster (e.g. a stock snapserver, or the port is actually a
     * stream / tcp port).
     */
    suspend fun fetch(host: String, httpPort: Int): SnapserverPorts? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("http://$host:$httpPort/listen.json").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val streamPort = json.optInt("streamPort", 0)
            if (streamPort <= 0) return@withContext null
            SnapserverPorts(
                streamPort = streamPort,
                tcpPort = json.optInt("tcpPort", 0),
                httpPort = json.optInt("httpPort", httpPort),
            )
        } catch (e: Exception) {
            Log.d(TAG, "listen.json fetch from $host:$httpPort failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
