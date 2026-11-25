package tech.capullo.audio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Test

/** The URLs a listener dials on a broadcaster: JSON-RPC control, `listen.json` and the snapclient's. */
class ListenerUrlsTest {

    @Test
    fun `a public-link join controls over wss on the tunnel host with no port`() {
        val tunnel = JoinUrl.parse("https://quiet-river.trycloudflare.com/join/")!!
        assertEquals(
            "wss://quiet-river.trycloudflare.com/jsonrpc",
            SnapcastControlClient.jsonRpcUrl(tunnel.host, tunnel.httpPort, tunnel.secure),
        )
    }

    @Test
    fun `a LAN join controls over ws on the HTTP port`() {
        assertEquals("ws://192.168.1.20:41234/jsonrpc", SnapcastControlClient.jsonRpcUrl("192.168.1.20", 41234, secure = false))
        assertEquals("ws://localhost:1680/jsonrpc", SnapcastControlClient.jsonRpcUrl("localhost", 1680, secure = false))
    }

    @Test
    fun `an IPv6 host is bracketed in every listener URL`() {
        for (host in listOf("fd7a:115c::1", "[fd7a:115c::1]")) {
            assertEquals("ws://[fd7a:115c::1]:41234/jsonrpc", SnapcastControlClient.jsonRpcUrl(host, 41234, secure = false))
            assertEquals("http://[fd7a:115c::1]:41234/listen.json", SnapserverListenInfo.listenJsonUrl(host, 41234))
            assertEquals("tcp://[fd7a:115c::1]:41232", SnapclientProcess.serverUri("tcp", host, 41232))
        }
    }

    @Test
    fun `IPv4 hosts and names are left as they are`() {
        assertEquals("http://192.168.1.20:41234/listen.json", SnapserverListenInfo.listenJsonUrl("192.168.1.20", 41234))
        assertEquals("tcp://192.168.1.20:41232", SnapclientProcess.serverUri("tcp", "192.168.1.20", 41232))
        assertEquals("wss://quiet-river.trycloudflare.com:443", SnapclientProcess.serverUri("wss", "quiet-river.trycloudflare.com", 443))
    }
}
