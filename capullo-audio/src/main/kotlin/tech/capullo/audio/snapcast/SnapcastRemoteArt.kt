package tech.capullo.audio.snapcast

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Listen-in side of [SnapcastArtwork]: a capullo broadcaster sends its art as a path on its own
 * snapserver (`artUrl = "/art/<hash>.jpg"`) plus a tiny embedded thumbnail (`artData`). A listener
 * shows the thumbnail at once and fetches the full image with [resolveUrl] + [download].
 */
object SnapcastRemoteArt {

    private const val TAG = "SnapcastRemoteArt"

    /** Download cap: full-size covers are a few hundred KB, FLAC rips embed a few MB. */
    const val MAX_BYTES = 5 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    /** Whole-transfer deadline: the read timeout alone would let a slow-drip server hold a fetch for ever. */
    private const val TOTAL_TIMEOUT_MS = 30_000L

    /**
     * Where a listener connected to the snapserver at [host]:[httpPort] fetches the art named by
     * [artUrl], or null when there is nothing worth fetching:
     *  - a path (what capullo broadcasters send) lives on that snapserver: `http://host:httpPort/path`;
     *  - an absolute http(s) URL (a radio station's logo) is used as is;
     *  - null for blank values, other schemes (`data:`, `file:`), loopback hosts (the broadcaster's own
     *    device, not this one) and snapserver's own `/__image_cache` URL: snapserver builds that from
     *    `artData` alone, under its configured host name (`localhost` on Android), so it holds nothing
     *    beyond the embedded image and is usually unreachable anyway.
     */
    fun resolveUrl(artUrl: String?, host: String, httpPort: Int): String? {
        val url = artUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        if (url.startsWith("//")) return resolveUrl("http:$url", host, httpPort)
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) {
            // No scheme: a path on the remote snapserver - unless it is `data:`, `blob:`, ….
            val colon = url.indexOf(':')
            if (colon >= 0 && (url.indexOf('/').let { it < 0 || colon < it })) return null
            if (host.isEmpty() || httpPort <= 0) return null
            val authority = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return "http://$authority:$httpPort" + (if (url.startsWith("/")) url else "/$url")
        }
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val hostPort = rest.substring(0, authorityEnd).substringAfterLast('@')
        val urlHost = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        val path = rest.substring(authorityEnd).substringBefore('?').substringBefore('#')
        if (path == "/__image_cache" || isLoopback(urlHost)) return null
        return url
    }

    private fun isLoopback(host: String): Boolean {
        val h = host.lowercase()
        return h.isEmpty() || h == "localhost" || h.startsWith("127.") || h == "[::1]" || h == "0.0.0.0"
    }

    /**
     * GET [url] on [Dispatchers.IO], with connect/read timeouts and an overall deadline. Null on any
     * failure, a non-200 answer, or a body larger than [maxBytes] (rejected from Content-Length up
     * front, and counted while reading when the server doesn't declare one).
     */
    suspend fun download(url: String, maxBytes: Int = MAX_BYTES): ByteArray? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            if (conn.contentLength > maxBytes) return@withContext null
            val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
            conn.inputStream.use { input ->
                readCapped(input, maxBytes) {
                    ensureActive()
                    System.currentTimeMillis() < deadline
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "art download failed: $url: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * All of [input], or null once it exceeds [maxBytes] or [keepGoing] (checked between reads)
     * returns false. Never buffers more than [maxBytes] plus one read.
     */
    internal fun readCapped(input: InputStream, maxBytes: Int, keepGoing: () -> Boolean = { true }): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            if (!keepGoing()) return null
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > maxBytes) return null
            out.write(buf, 0, n)
        }
    }
}
