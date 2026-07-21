package tech.capullo.audio.snapcast

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ServerGetStatusRequest(
    val id: Int,
    @EncodeDefault val jsonrpc: String = "2.0",
    @EncodeDefault val method: String = "Server.GetStatus",
)

@Serializable
data class ServerGetStatusResponse(
    override val id: Int,
    override val jsonrpc: String,
    val result: ServerStatusResult,
) : RequestResponse()

@Serializable data class ServerStatusResult(val server: ServerInfo)
@Serializable data class ServerInfo(val groups: List<Group>, val server: Server, val streams: List<Stream>)

@Serializable
data class Group(
    val clients: List<Client>,
    val id: String,
    val muted: Boolean,
    val name: String,
    @SerialName("stream_id") val streamId: String,
)

@Serializable
data class Client(
    val config: ClientConfig,
    val connected: Boolean,
    val host: Host,
    val id: String,
    val lastSeen: LastSeen,
    val snapclient: SnapClient,
)

@Serializable data class ClientConfig(val instance: Int, val latency: Int, val name: String, val volume: Volume)
@Serializable data class ClientParams(val client: Client, val id: String)
@Serializable data class Volume(val muted: Boolean, val percent: Int)
@Serializable data class Host(val arch: String, val ip: String, val mac: String, val name: String, val os: String)
@Serializable data class LastSeen(val sec: Long, val usec: Long)
@Serializable data class SnapClient(val name: String, val protocolVersion: Int, val version: String)
@Serializable data class Server(val host: Host, val snapserver: SnapServer)
@Serializable data class SnapServer(val controlProtocolVersion: Int, val name: String, val protocolVersion: Int, val version: String)

@Serializable
data class Stream(val id: String, val properties: StreamProperties, val status: String, val uri: StreamUri)

@Serializable
data class ArtData(val data: String, val extension: String)

@Serializable
data class StreamProperties(
    val canControl: Boolean = false,
    val canGoNext: Boolean = false,
    val canGoPrevious: Boolean = false,
    val canPause: Boolean = false,
    val canPlay: Boolean = false,
    val canSeek: Boolean = false,
    val playbackStatus: String? = null,
    val metadata: StreamMetadata? = null,
)

@Serializable
data class StreamUri(val fragment: String, val host: String, val path: String, val query: StreamQuery, val raw: String, val scheme: String)

@Serializable
data class StreamQuery(
    @SerialName("chunk_ms") val chunkMs: String,
    val codec: String,
    @SerialName("dryout_ms") val dryoutMs: String,
    val mode: String,
    val name: String,
    val sampleformat: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientSetVolumeRequest(
    val id: Int,
    @EncodeDefault val jsonrpc: String = "2.0",
    @EncodeDefault val method: String = "Client.SetVolume",
    val params: VolumeParams,
)

@Serializable
data class VolumeParams(@SerialName("id") val clientId: String, val volume: Volume)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientSetLatencyRequest(
    val id: Int,
    @EncodeDefault val jsonrpc: String = "2.0",
    @EncodeDefault val method: String = "Client.SetLatency",
    val params: LatencyParams,
)

@Serializable
data class LatencyParams(@SerialName("id") val clientId: String, val latency: Int)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GroupSetClientsRequest(
    val id: Int,
    @EncodeDefault val jsonrpc: String = "2.0",
    @EncodeDefault val method: String = "Group.SetClients",
    val params: GroupClientsParams,
)

@Serializable
data class GroupClientsParams(@SerialName("id") val groupId: String, val clients: List<String>)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ServerDeleteClientRequest(
    val id: Int,
    @EncodeDefault val jsonrpc: String = "2.0",
    @EncodeDefault val method: String = "Server.DeleteClient",
    val params: DeleteClientParams,
)

@Serializable
data class DeleteClientParams(@SerialName("id") val clientId: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientSetNameRequest(
    val id: Int,
    @EncodeDefault val jsonrpc: String = "2.0",
    @EncodeDefault val method: String = "Client.SetName",
    val params: ClientNameParams,
)

@Serializable
data class ClientNameParams(@SerialName("id") val clientId: String, val name: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StreamControlRequest(
    val id: Int,
    @EncodeDefault val jsonrpc: String = "2.0",
    @EncodeDefault val method: String = "Stream.Control",
    val params: StreamControlParams,
)

@Serializable
data class StreamControlParams(val id: String, val command: String)

@Serializable
abstract class Notification : SnapcastJSONRPCResponse() {
    abstract override val jsonrpc: String
    abstract val method: String
}

@Serializable data class GenericNotification(override val jsonrpc: String, override val method: String, val params: JsonObject) : Notification()
@Serializable data class ClientOnVolumeChanged(override val jsonrpc: String, override val method: String, val params: VolumeParams) : Notification()
@Serializable data class ClientOnLatencyChanged(override val jsonrpc: String, override val method: String, val params: LatencyParams) : Notification()
@Serializable data class ClientOnDisconnect(override val jsonrpc: String, override val method: String, val params: ClientParams) : Notification()
@Serializable data class ClientOnConnect(override val jsonrpc: String, override val method: String, val params: ClientParams) : Notification()
@Serializable data class NameChangedParams(val id: String, val name: String)
@Serializable data class ClientOnNameChanged(override val jsonrpc: String, override val method: String, val params: NameChangedParams) : Notification()
@Serializable data class ServerOnUpdate(override val jsonrpc: String, override val method: String, val params: ServerStatusResult) : Notification()
@Serializable data class StreamOnProperties(override val jsonrpc: String, override val method: String, val params: StreamPropertiesParams) : Notification()

@Serializable data class StreamPropertiesParams(val id: String, val properties: StreamPlayerProperties)

@Serializable
data class StreamPlayerProperties(
    val playbackStatus: String = "",
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canGoNext: Boolean = false,
    val canGoPrevious: Boolean = false,
    val canControl: Boolean = false,
    val metadata: StreamMetadata? = null,
)

@Serializable
data class StreamMetadata(
    val title: String? = null,
    val artist: JsonElement? = null,
    @SerialName("artUrl") val artUrl: String? = null,
    val artData: ArtData? = null,
    val station: String? = null,
    val url: String? = null,
    val country: String? = null,
    @SerialName("countrycode") val countryCode: String? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    @SerialName("youtubeUrl") val youtubeUrl: String? = null,
    @SerialName("spotifyUrl") val spotifyUrl: String? = null,
    @SerialName("appleMusicUrl") val appleMusicUrl: String? = null,
    val tags: String? = null,
    val uuid: String? = null,
)

fun StreamMetadata.firstArtist(): String = when (val a = artist) {
    is JsonPrimitive -> a.content
    is JsonArray -> (a.firstOrNull() as? JsonPrimitive)?.content ?: ""
    else -> ""
}

object NotificationSerializer : JsonContentPolymorphicSerializer<Notification>(Notification::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Notification> =
        when (element.jsonObject["method"].toString()) {
            "\"Server.OnUpdate\"" -> ServerOnUpdate.serializer()
            "\"Client.OnVolumeChanged\"" -> ClientOnVolumeChanged.serializer()
            "\"Client.OnLatencyChanged\"" -> ClientOnLatencyChanged.serializer()
            "\"Client.OnDisconnect\"" -> ClientOnDisconnect.serializer()
            "\"Client.OnConnect\"" -> ClientOnConnect.serializer()
            "\"Client.OnNameChanged\"" -> ClientOnNameChanged.serializer()
            "\"Stream.OnProperties\"" -> StreamOnProperties.serializer()
            else -> GenericNotification.serializer()
        }
}

@Serializable
abstract class RequestResponse : SnapcastJSONRPCResponse() {
    abstract val id: Int
    abstract override val jsonrpc: String
}

/** Success response whose result shape isn't modeled (Client.SetLatency → `{"latency": N}`,
 *  Stream.Control → `"ok"`, ...). Carries the raw result; correlate with the request via [id]. */
@Serializable
data class GenericSuccessResponse(
    override val id: Int,
    override val jsonrpc: String,
    val result: JsonElement,
) : RequestResponse()

/** JSON-RPC error response. [id] is null when the server couldn't attribute the request. */
@Serializable
data class SnapcastErrorResponse(
    val id: Int? = null,
    override val jsonrpc: String,
    val error: JsonRpcError,
) : SnapcastJSONRPCResponse()

@Serializable
data class JsonRpcError(val code: Int, val message: String, val data: JsonElement? = null)

object RequestResponseSerializer : JsonContentPolymorphicSerializer<RequestResponse>(RequestResponse::class) {
    // Only Server.GetStatus carries a `server` object in its result; every other success reply
    // (SetLatency, SetVolume, Stream.Control "ok", ...) keeps its raw result in a generic envelope
    // instead of failing to decode as a GetStatus response.
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<RequestResponse> =
        if ((element.jsonObject["result"] as? JsonObject)?.containsKey("server") == true) {
            ServerGetStatusResponse.serializer()
        } else {
            GenericSuccessResponse.serializer()
        }
}

@Serializable
abstract class SnapcastJSONRPCResponse {
    abstract val jsonrpc: String
}

object SnapcastJSONRPCResponseSerializer : JsonContentPolymorphicSerializer<SnapcastJSONRPCResponse>(SnapcastJSONRPCResponse::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SnapcastJSONRPCResponse> {
        val obj = element.jsonObject
        return when {
            "method" in obj -> NotificationSerializer
            "error" in obj -> SnapcastErrorResponse.serializer()
            else -> RequestResponseSerializer
        }
    }
}
