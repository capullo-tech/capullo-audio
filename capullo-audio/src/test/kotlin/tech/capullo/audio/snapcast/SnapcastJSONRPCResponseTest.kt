package tech.capullo.audio.snapcast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for response decoding: the serializer used to select ServerGetStatusResponse
 * for EVERY request-response, so SetLatency ({"latency":N}) and Stream.Control ("ok") replies —
 * and all error responses — threw instead of decoding (the on-device
 * "Field 'server' is required" / "Expected JsonObject" failures).
 */
class SnapcastJSONRPCResponseTest {

    // Mirrors the client's decode config (ignoreUnknownKeys).
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String): SnapcastJSONRPCResponse =
        json.decodeFromString(SnapcastJSONRPCResponseSerializer, raw)

    @Test
    fun `SetLatency reply decodes as generic success with its result intact`() {
        val response = decode("""{"id":7,"jsonrpc":"2.0","result":{"latency":180}}""")
        val success = response as GenericSuccessResponse
        assertEquals(7, success.id)
        assertEquals(180, success.result.jsonObject["latency"]!!.jsonPrimitive.int)
    }

    @Test
    fun `Stream Control "ok" string reply decodes as generic success`() {
        val response = decode("""{"id":3,"jsonrpc":"2.0","result":"ok"}""")
        val success = response as GenericSuccessResponse
        assertEquals(3, success.id)
        assertEquals("ok", success.result.jsonPrimitive.content)
    }

    @Test
    fun `error reply decodes with code and message`() {
        val response = decode(
            """{"id":4,"jsonrpc":"2.0","error":{"code":-32603,"message":"Internal error"}}"""
        )
        val error = response as SnapcastErrorResponse
        assertEquals(4, error.id)
        assertEquals(-32603, error.error.code)
        assertEquals("Internal error", error.error.message)
    }

    @Test
    fun `error reply with null id decodes`() {
        val response = decode(
            """{"id":null,"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"}}"""
        )
        assertNull((response as SnapcastErrorResponse).id)
    }

    @Test
    fun `GetStatus reply still decodes as ServerGetStatusResponse`() {
        val host = """{"arch":"arm64","ip":"192.168.1.2","mac":"00:11:22:33:44:55","name":"oppo","os":"Android"}"""
        val raw = """
            {"id":1,"jsonrpc":"2.0","result":{"server":{
                "groups":[],
                "server":{"host":$host,"snapserver":{"controlProtocolVersion":1,"name":"Snapserver","protocolVersion":1,"version":"0.34.0"}},
                "streams":[]
            }}}
        """.trimIndent()
        val response = decode(raw) as ServerGetStatusResponse
        assertEquals(1, response.id)
        assertEquals("Snapserver", response.result.server.server.snapserver.name)
        assertTrue(response.result.server.groups.isEmpty())
    }

    @Test
    fun `notifications still route through the method-based selector`() {
        val response = decode(
            """{"jsonrpc":"2.0","method":"Client.OnLatencyChanged","params":{"id":"abc","latency":5}}"""
        )
        val notification = response as ClientOnLatencyChanged
        assertEquals("abc", notification.params.clientId)
        assertEquals(5, notification.params.latency)
    }
}
