package tech.capullo.audio.snapcast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a broadcaster's raw-TCP **stream** port from the HTTP port a user knows (the web-URL port).
 *
 * With OS-assigned ports ([SnapserverPorts.free]) the stream port a snapclient must dial is random and
 * exposed nowhere on the HTTP/JSON-RPC surface. A capullo broadcaster publishes its resolved ports as
 * `listen.json` in its served doc_root (see [SnapserverProcess.writeListenInfo]); this fetches it so a
 * cross-network listener who typed only `host:httpPort` can learn the real stream port and connect.
 */
object SnapserverListenInfo {

    private val TAG = SnapserverListenInfo::class.java.simpleName
    private const val TIMEOUT_MS = 4000

    /**
     * GET `http://$host:$httpPort/listen.json` and parse the ports. Returns null if the endpoint is
     * unreachable or not a capullo broadcaster (e.g. a stock snapserver, or the typed port is actually
     * a direct stream port) - callers should fall back to treating the typed port as the stream port.
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
