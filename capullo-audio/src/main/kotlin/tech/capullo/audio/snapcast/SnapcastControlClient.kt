package tech.capullo.audio.snapcast

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC control of the snapserver at [snapserverHostAddress]: its `/jsonrpc` websocket on the
 * HTTP port [websocketPort]. [secure] dials `wss` instead of `ws`, for a public-link join: the
 * tunnel terminates TLS on 443 and forwards to that HTTP port, websocket upgrade included.
 */
class SnapcastControlClient(
    snapserverHostAddress: String,
    websocketPort: Int = 1680,
    secure: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : tech.capullo.audio.calibration.CalibrationControl {
    private val url = jsonRpcUrl(snapserverHostAddress, websocketPort, secure)

    // internal, not public: consumers tear down via close() instead of touching the ktor client, so
    // ktor stays an implementation detail of this module (apps don't need ktor on their classpath).
    internal val client = HttpClient(OkHttp) {
        engine {
            config { pingInterval(20, TimeUnit.SECONDS) }
        }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json)
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private var requestIdCounter: Int = 1

    enum class ConnectionState { STARTING, CONNECTED, ERROR }

    private val _connectionState = MutableStateFlow(ConnectionState.STARTING)
    val connectionState = _connectionState.asStateFlow()

    suspend fun initialize() = withContext(ioDispatcher) {
        while (true) {
            Log.d(TAG, "Connecting to $url")
            try {
                session = client.webSocketSession(url)
                _connectionState.update { ConnectionState.CONNECTED }
                sendGetStatus()
                break
            } catch (e: Exception) {
                Log.d(TAG, "WebSocket connection failed: $e")
                _connectionState.update { ConnectionState.ERROR }
                delay(1000)
            }
        }
    }

    val notifications: Flow<SnapcastJSONRPCResponse?> = flow {
        while (true) {
            val frame = withContext(ioDispatcher) {
                try {
                    return@withContext session?.incoming?.receive() as? Frame.Text
                } catch (e: Exception) {
                    // The collector went away (a leave, a new join): stop here, that is no dropped link.
                    ensureActive()
                    Log.d(TAG, "Frame read error: $e")
                    delay(1000)
                    // Any failed read means the session is gone: a dropped link ends with an I/O
                    // error, a server or tunnel that closed it cleanly with
                    // ClosedReceiveChannelException. Reconnecting only on EOFException left a
                    // cleanly closed session dead for good (no client list, no volume).
                    _connectionState.update { ConnectionState.ERROR }
                    initialize()
                }
                return@withContext null
            }
            frame?.readText()?.also { json ->
                val response = try {
                    decodeSnapcastResponse(json)
                } catch (e: Exception) {
                    Log.d(TAG, "Decode error: $e")
                    null
                }
                emit(response)
            } ?: run {
                delay(1000)
            }
        }
    }

    override suspend fun sendGetStatus() {
        session?.sendSerialized(ServerGetStatusRequest(id = requestIdCounter++))
    }

    override suspend fun sendSetVolume(clientId: String, muted: Boolean, percent: Int) {
        session?.sendSerialized(
            ClientSetVolumeRequest(
                id = requestIdCounter++,
                params = VolumeParams(clientId, Volume(muted, percent)),
            ),
        )
    }

    suspend fun sendGroupSetClients(groupId: String, clientIds: List<String>) {
        session?.sendSerialized(
            GroupSetClientsRequest(
                id = requestIdCounter++,
                params = GroupClientsParams(groupId, clientIds),
            ),
        )
    }

    suspend fun sendDeleteClient(clientId: String) {
        session?.sendSerialized(
            ServerDeleteClientRequest(
                id = requestIdCounter++,
                params = DeleteClientParams(clientId),
            ),
        )
    }

    suspend fun sendSetClientName(clientId: String, name: String) {
        session?.sendSerialized(
            ClientSetNameRequest(
                id = requestIdCounter++,
                params = ClientNameParams(clientId, name),
            ),
        )
    }

    /** Returns the JSON-RPC request id (its [GenericSuccessResponse]/[SnapcastErrorResponse] ack
     *  arrives on [notifications]), or null when there is no session — i.e. nothing was sent. */
    override suspend fun sendSetLatency(clientId: String, latencyMs: Int): Int? = session?.let { s ->
        val requestId = requestIdCounter++
        s.sendSerialized(
            ClientSetLatencyRequest(
                id = requestId,
                params = LatencyParams(clientId, latencyMs),
            ),
        )
        requestId
    }

    suspend fun sendStreamControl(streamId: String, command: String) {
        Log.d(TAG, "sendStreamControl: stream=$streamId command=$command session=${session != null}")
        session?.sendSerialized(
            StreamControlRequest(
                id = requestIdCounter++,
                params = StreamControlParams(id = streamId, command = command),
            ),
        )
    }

    /** Release the underlying ktor client + its connection pool. Call on teardown. */
    fun close() {
        client.close()
    }

    companion object {
        private val TAG = SnapcastControlClient::class.simpleName

        /**
         * `ws://host:port/jsonrpc`, or `wss://host/jsonrpc` when [secure] (443 left implicit). Built
         * as a string because ktor's `webSocketSession(host = …)` puts an IPv6 host into the URL
         * without brackets, which OkHttp then rejects.
         */
        internal fun jsonRpcUrl(host: String, port: Int, secure: Boolean): String = "${if (secure) "wss" else "ws"}://${JoinUrl.authority(host, port, secure)}/jsonrpc"
    }
}
