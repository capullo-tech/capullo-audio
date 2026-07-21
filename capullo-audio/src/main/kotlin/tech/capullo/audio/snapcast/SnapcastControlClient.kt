package tech.capullo.audio.snapcast

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

// Snapserver includes unknown stream query keys (e.g. controlscript) in GetStatus responses.
// ignoreUnknownKeys prevents SerializationException → decode failure → empty client list.
private val snapJson = Json { ignoreUnknownKeys = true }

class SnapcastControlClient(
    private val snapserverHostAddress: String,
    private val websocketPort: Int = 1680,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
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
            Log.d(TAG, "Connecting to $snapserverHostAddress")
            try {
                session = client.webSocketSession(
                    method = HttpMethod.Get,
                    host = snapserverHostAddress,
                    port = websocketPort,
                    path = "/jsonrpc",
                )
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
                    Log.d(TAG, "Frame read error: $e")
                    delay(1000)
                    if (e is java.io.EOFException) {
                        _connectionState.update { ConnectionState.ERROR }
                        initialize()
                    }
                }
                return@withContext null
            }
            frame?.readText()?.also { json ->
                val response = try {
                    snapJson.decodeFromString(SnapcastJSONRPCResponseSerializer, json)
                } catch (e: Exception) {
                    Log.d(TAG, "Decode error: $e"); null
                }
                emit(response)
            } ?: run {
                delay(1000)
            }
        }
    }

    suspend fun sendGetStatus() {
        session?.sendSerialized(ServerGetStatusRequest(id = requestIdCounter++))
    }

    suspend fun sendSetVolume(clientId: String, muted: Boolean, percent: Int) {
        session?.sendSerialized(
            ClientSetVolumeRequest(
                id = requestIdCounter++,
                params = VolumeParams(clientId, Volume(muted, percent)),
            )
        )
    }

    suspend fun sendGroupSetClients(groupId: String, clientIds: List<String>) {
        session?.sendSerialized(
            GroupSetClientsRequest(
                id = requestIdCounter++,
                params = GroupClientsParams(groupId, clientIds),
            )
        )
    }

    suspend fun sendDeleteClient(clientId: String) {
        session?.sendSerialized(
            ServerDeleteClientRequest(
                id = requestIdCounter++,
                params = DeleteClientParams(clientId),
            )
        )
    }

    suspend fun sendSetClientName(clientId: String, name: String) {
        session?.sendSerialized(
            ClientSetNameRequest(
                id = requestIdCounter++,
                params = ClientNameParams(clientId, name),
            )
        )
    }

    /** Returns the JSON-RPC request id (its [GenericSuccessResponse]/[SnapcastErrorResponse] ack
     *  arrives on [notifications]), or null when there is no session — i.e. nothing was sent. */
    suspend fun sendSetLatency(clientId: String, latencyMs: Int): Int? = session?.let { s ->
        val requestId = requestIdCounter++
        s.sendSerialized(
            ClientSetLatencyRequest(
                id = requestId,
                params = LatencyParams(clientId, latencyMs),
            )
        )
        requestId
    }

    suspend fun sendStreamControl(streamId: String, command: String) {
        Log.d(TAG, "sendStreamControl: stream=$streamId command=$command session=${session != null}")
        session?.sendSerialized(
            StreamControlRequest(
                id = requestIdCounter++,
                params = StreamControlParams(id = streamId, command = command),
            )
        )
    }

    /** Release the underlying ktor client + its connection pool. Call on teardown. */
    fun close() {
        client.close()
    }

    companion object {
        private val TAG = SnapcastControlClient::class.simpleName
    }
}
