package tech.capullo.audio.snapcast

/**
 * Implements the Snapcast stream-plugin JSON-RPC 2.0 protocol.
 *
 * Snapserver spawns libsnapcontrol.so as a `controlscript`; that binary is a
 * stdio <-> Unix-abstract-socket proxy and connects to the abstract socket
 * bound here. This class accepts connections, sends Plugin.Stream.Ready,
 * answers Plugin.Stream.Player.GetProperties, and pushes
 * Plugin.Stream.Player.Properties notifications when metadata changes.
 *
 * ENGINE VERSION: driven entirely by the platform contract - a
 * `StateFlow<NowPlaying>` (read) plus a [PlaybackController] (transport). App-specific
 * fields (country, codec, bitrate, youtubeUrl, …) ride in [NowPlaying.extras] and are
 * emitted verbatim into the JSON metadata, so the contract stays stable as apps evolve.
 */

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

private const val TAG = "SnapcontrolPlugin"
private const val DEFAULT_SOCKET_NAME = "snapcontrol"
private const val NOTIFY_READY = """{"jsonrpc":"2.0","method":"Plugin.Stream.Ready"}"""

/**
 * How a [NowPlaying] maps onto the Snapcast web-player metadata JSON. Defaults reproduce
 * QuantumCast's radio schema (album → "station"); an app can override the album key.
 *
 * ART: snapserver parses this JSON on the thread that reads the audio pipe, so a large embedded image
 * stalls the broadcast at every track change (OnePlus 3: 150-290 ms of `onResync` with full-size art).
 * Full-size art therefore travels as `artUrl` (a file served by this snapserver, see
 * [SnapcastArtwork]), and [NowPlaying.artworkBase64] may only be a small fallback thumbnail: the mapper
 * embeds it as `artData` only up to [MAX_ART_DATA_CHARS] and only when its bytes are a recognised
 * image ([embeddedArt]). Anything bigger is dropped here, not shrunk - decoding a large image has no
 * place on the serialization path - so no app can bring the stall back by handing over full-size art.
 */
class SnapcontrolMetadataMapper(
    private val albumKey: String = "station",
) {
    fun playbackStatus(np: NowPlaying): String = if (np.isPlaying) "playing" else "paused"

    fun metadata(np: NowPlaying): JSONObject? {
        val hasCore = np.title.isNotEmpty() || np.artist.isNotEmpty() || np.album.isNotEmpty()
        val art = embeddedArt(np.artworkBase64)
        if (!hasCore && np.extras.isEmpty() && art == null) return null
        val meta = JSONObject()
        if (np.title.isNotEmpty()) meta.put("title", np.title)
        if (np.artist.isNotEmpty()) meta.put("artist", JSONArray().put(np.artist))
        if (np.album.isNotEmpty()) meta.put(albumKey, np.album)
        np.streamUrl?.takeIf { it.isNotEmpty() }?.let { meta.put("url", it) }
        // App-specific fields verbatim (country, countrycode, codec, bitrate, youtubeUrl, artUrl, …).
        // Except `artData`: that key belongs to the capped embed below.
        for ((k, v) in np.extras) if (v.isNotEmpty() && k != "artData") meta.put(k, v)
        art?.let { meta.put("artData", JSONObject().put("data", it.data).put("extension", it.extension)) }
        return meta
    }

    companion object {
        /**
         * Largest `artData` payload embedded, in base64 characters (~12 KB of image). A
         * [SnapcastArtwork] thumbnail is a few KB; this leaves room without letting a real image through.
         */
        const val MAX_ART_DATA_CHARS = 16 * 1024

        /**
         * The `artData` to embed for [base64], or null: none given, over [MAX_ART_DATA_CHARS], or not a
         * JPEG/PNG/WebP/GIF. The extension is read from the bytes, never taken from the caller.
         */
        fun embeddedArt(base64: String?): ArtData? {
            if (base64.isNullOrEmpty()) return null
            if (base64.length > MAX_ART_DATA_CHARS) {
                Log.w(TAG, "artData of ${base64.length} chars over the $MAX_ART_DATA_CHARS cap - not embedded; publish full art via SnapcastArtwork")
                return null
            }
            val format = ArtFormat.sniffBase64(base64) ?: return null
            return ArtData(base64, format.extension)
        }
    }
}

class SnapcontrolPlugin(
    private val state: StateFlow<NowPlaying>,
    private val controller: PlaybackController,
    parentJob: Job,
    private val mapper: SnapcontrolMetadataMapper = SnapcontrolMetadataMapper(),
    /**
     * Abstract Unix socket name to bind - MUST equal the paired [SnapserverProcess.controlSocketName]
     * (feed `snapserver.controlSocketName` here). Default `"snapcontrol"` matches
     * [SnapserverProcess.DEFAULT_CONTROL_SOCKET_NAME]; pass a per-app value so two capullo apps on
     * one device don't collide on this device-global name.
     */
    private val socketName: String = DEFAULT_SOCKET_NAME,
) {
    private val pluginJob = SupervisorJob(parentJob)
    private val scope = CoroutineScope(Dispatchers.IO + pluginJob)

    private var listener: LocalServerSocket? = null

    @Volatile private var currentSession: SnapcontrolSession? = null

    @Volatile var isStreamLocked: Boolean = false
        set(value) {
            field = value
            notifyPropertiesChanged()
        }

    fun start() {
        if (listener != null) return
        listener = try {
            LocalServerSocket(socketName)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to bind abstract:$socketName - is another instance running?", e)
            return
        }
        Log.d(TAG, "Listening on abstract:$socketName")
        scope.launch { acceptLoop() }
    }

    fun stop() {
        val srv = listener
        listener = null
        try {
            srv?.close()
        } catch (_: IOException) {}
        currentSession?.close()
        currentSession = null
        pluginJob.cancel()
    }

    fun notifyPropertiesChanged() {
        currentSession?.notifyProperties()
    }

    private suspend fun acceptLoop() {
        val srv = listener ?: return
        while (scope.isActive) {
            val sock = try {
                srv.accept()
            } catch (e: IOException) {
                Log.d(TAG, "Accept loop ending: ${e.message}")
                return
            }
            Log.d(TAG, "New session")
            val session = SnapcontrolSession(sock, state, controller, mapper, scope, { isStreamLocked })
            currentSession = session
            try {
                session.run()
            } finally {
                currentSession = null
                Log.d(TAG, "Session ended")
            }
        }
    }
}

private class SnapcontrolSession(
    private val socket: LocalSocket,
    private val state: StateFlow<NowPlaying>,
    private val controller: PlaybackController,
    private val mapper: SnapcontrolMetadataMapper,
    parentScope: CoroutineScope,
    private val getLocked: () -> Boolean,
) {
    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)

    // Serializes notifyProperties sends so a late emission can't re-broadcast stale metadata
    // (snapserver stores the last properties; web clients would keep showing an old song).
    private val propsMutex = Mutex()

    suspend fun run() {
        outbox.trySend(NOTIFY_READY)
        val writerJob = scope.launch(Dispatchers.IO) { writerLoop() }
        try {
            withContext(Dispatchers.IO) { readerLoop() }
        } finally {
            outbox.close()
            writerJob.cancel()
            try {
                socket.close()
            } catch (_: IOException) {}
            sessionJob.cancel()
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: IOException) {}
        outbox.close()
        sessionJob.cancel()
    }

    fun notifyProperties() {
        scope.launch {
            propsMutex.withLock {
                outbox.trySend(
                    """{"jsonrpc":"2.0","method":"Plugin.Stream.Player.Properties","params":${propertiesString()}}""",
                )
            }
        }
    }

    // Metadata serialization cache. notifyProperties() fires on every transport flip and
    // handleLine() answers every snapserver GetProperties poll - both re-emit the full properties.
    // The metadata block used to embed full-size art (a ~300KB base64 blob that org.json escapes
    // char-by-char, pegging a core and starving the real-time FIFO writer). The mapper now caps the
    // embed at a thumbnail, but the block still only changes per track, so it is serialized once and
    // reused until a field it reads moves (which also keeps an over-cap warning to once per track).
    private val metaCacheLock = Any()
    private var metaCachePrimed = false
    private var cachedMetaFields: String? = null
    private var cachedMetaJson: String? = null

    private fun metadataJson(np: NowPlaying): String? = synchronized(metaCacheLock) {
        // Identity of everything mapper.metadata() reads. The art is hashed (length + hashCode)
        // rather than compared by value; String caches its hashCode, so once the producer hands a
        // stable per-track instance this is O(1). A changed blob just reserializes once.
        val artKey = np.artworkBase64?.let { "${it.length}:${it.hashCode()}" }
        val fields = "${np.title} ${np.artist} ${np.album} ${np.streamUrl} " +
            "${np.extras} $artKey"
        if (!metaCachePrimed || fields != cachedMetaFields) {
            cachedMetaFields = fields
            cachedMetaJson = mapper.metadata(np)?.toString()
            metaCachePrimed = true
        }
        cachedMetaJson
    }

    private fun propertiesString(): String {
        val np = state.value
        val locked = getLocked()
        val obj = JSONObject()
            .put("playbackStatus", mapper.playbackStatus(np))
            .put("loopStatus", "none")
            .put("shuffle", false)
            .put("volume", 100)
            .put("mute", false)
            .put("canPlay", !locked)
            .put("canPause", !locked)
            .put("canSeek", false)
            .put("canGoNext", !locked && np.canGoNext)
            .put("canGoPrevious", !locked && np.canGoPrevious)
            .put("canControl", true)
        val base = obj.toString()
        val meta = metadataJson(np) ?: return base
        // Splice the pre-serialized metadata fragment in verbatim rather than putting the JSONObject
        // on `obj`: a top-level toString() would re-escape the whole block. `base` is a non-empty
        // object, so it ends in '}'.
        return base.substring(0, base.length - 1) + ",\"metadata\":" + meta + "}"
    }

    private suspend fun readerLoop() {
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        while (scope.isActive) {
            val line = try {
                reader.readLine() ?: return
            } catch (e: IOException) {
                Log.d(TAG, "Reader end: ${e.message}")
                return
            }
            if (line.isBlank()) continue
            handleLine(line)
        }
    }

    private suspend fun writerLoop() {
        val out = socket.outputStream
        try {
            for (line in outbox) {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Writer end: ${e.message}")
        }
    }

    private fun handleLine(line: String) {
        val req = try {
            JSONObject(line)
        } catch (e: JSONException) {
            Log.w(TAG, "JSON parse error: ${e.message}")
            return
        }
        val id: Any? = if (req.has("id") && !req.isNull("id")) req.get("id") else null
        val method = req.optString("method", "")
        if (method.isEmpty()) return

        try {
            val result: Any = when (method) {
                "Plugin.Stream.Player.GetProperties" -> {
                    // Splice the pre-serialized properties in verbatim; routing the JSONObject
                    // through sendResult() would re-escape the metadata block on every poll.
                    if (id != null) sendRawResult(id, propertiesString())
                    return
                }
                "Plugin.Stream.Player.Control" -> {
                    val command = req.optJSONObject("params")?.optString("command") ?: ""
                    if (getLocked()) {
                        Log.d(TAG, "Stream locked - ignoring control command: $command")
                    } else {
                        when (command) {
                            "play" -> controller.play()
                            "pause", "stop" -> controller.pause()
                            "playPause" -> if (state.value.isPlaying) controller.pause() else controller.play()
                            "next" -> controller.next()
                            "previous" -> controller.previous()
                            else -> Log.d(TAG, "Unhandled control command: $command")
                        }
                    }
                    "ok"
                }
                "Plugin.Stream.Player.SetProperty" -> "ok"
                else -> {
                    if (id != null) sendError(id, -32601, "Method not found: $method")
                    return
                }
            }
            if (id != null) sendResult(id, result)
        } catch (e: Throwable) {
            Log.e(TAG, "Dispatch error", e)
            if (id != null) sendError(id, -32603, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun sendResult(id: Any, result: Any) {
        outbox.trySend(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
    }

    // Sends a result whose value is already-serialized JSON, spliced in verbatim so a large art
    // blob inside it is not re-escaped. The head object is tiny, so its toString() is cheap; it is
    // always non-empty and ends in '}'.
    private fun sendRawResult(id: Any, rawResult: String) {
        val head = JSONObject().put("jsonrpc", "2.0").put("id", id).toString()
        outbox.trySend(head.substring(0, head.length - 1) + ",\"result\":" + rawResult + "}")
    }

    private fun sendError(id: Any, code: Int, message: String) {
        outbox.trySend(
            JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message)).toString(),
        )
    }
}
