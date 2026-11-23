package tech.capullo.audio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinUrlTest {

    @Test
    fun `build emits the LAN form with an explicit port and a trailing slash`() {
        assertEquals("http://192.168.1.20:40002/join/", JoinUrl.build("192.168.1.20", 40002))
    }

    @Test
    fun `build leaves 443 implicit for the tunnel form but keeps any other https port`() {
        assertEquals(
            "https://quiet-river-blue-fox.trycloudflare.com/join/",
            JoinUrl.build("quiet-river-blue-fox.trycloudflare.com", 443, https = true),
        )
        assertEquals("https://example.org:8443/join/", JoinUrl.build("example.org", 8443, https = true))
        assertEquals("http://10.0.0.2:443/join/", JoinUrl.build("10.0.0.2", 443))
    }

    @Test
    fun `build brackets an IPv6 host whether given bare or bracketed`() {
        assertEquals("http://[fd7a:115c::1]:1680/join/", JoinUrl.build("fd7a:115c::1", 1680))
        assertEquals("http://[fd7a:115c::1]:1680/join/", JoinUrl.build("[fd7a:115c::1]", 1680))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `build rejects an out-of-range port`() {
        JoinUrl.build("10.0.0.2", 70000)
    }

    @Test
    fun `parse round-trips build`() {
        for ((host, port, https) in listOf(
            Triple("192.168.1.20", 40002, false),
            Triple("fd7a:115c::1", 1680, false),
            Triple("quiet-river-blue-fox.trycloudflare.com", 443, true),
            Triple("example.org", 8443, true),
        )) {
            assertEquals(JoinTarget(host, port, https), JoinUrl.parse(JoinUrl.build(host, port, https)))
        }
    }

    @Test
    fun `LAN target dials tcp to the stream port of the block`() {
        val t = JoinUrl.parse("http://192.168.1.20:40002/join/")!!
        assertEquals("192.168.1.20", t.host)
        assertEquals("tcp", t.scheme)
        assertEquals(40000, t.port)
        assertEquals(SnapserverPorts.fixed(40000), t.ports)
    }

    @Test
    fun `legacy http port 1680 maps to the legacy fixed ports`() {
        val t = JoinUrl.parse("http://192.168.1.20:1680/join/")!!
        assertEquals(SnapserverPorts.Fixed, t.ports)
        assertEquals(1604, t.port)
    }

    @Test
    fun `tunnel target dials wss to 443 and has no derivable port block`() {
        val t = JoinUrl.parse("https://quiet-river-blue-fox.trycloudflare.com/join/")!!
        assertEquals("quiet-river-blue-fox.trycloudflare.com", t.host)
        assertEquals(443, t.httpPort)
        assertEquals("wss", t.scheme)
        assertEquals(443, t.port)
        assertNull(t.ports)
    }

    @Test
    fun `parse strips IPv6 brackets from the host`() {
        val t = JoinUrl.parse("http://[fd7a:115c::1]:40002/join/")!!
        assertEquals("fd7a:115c::1", t.host)
        assertEquals(40000, t.port)
    }

    @Test
    fun `parse accepts the path without the trailing slash and ignores case of the scheme`() {
        assertEquals(JoinTarget("10.0.0.2", 40002, false), JoinUrl.parse("http://10.0.0.2:40002/join"))
        assertEquals(JoinTarget("10.0.0.2", 40002, false), JoinUrl.parse("HTTP://10.0.0.2:40002/join/"))
        assertEquals(JoinTarget("10.0.0.2", 40002, false), JoinUrl.parse("  http://10.0.0.2:40002/join/\n"))
    }

    @Test
    fun `parse rejects URLs that are not join URLs`() {
        listOf(
            "http://10.0.0.2:40002/",
            "http://10.0.0.2:40002",
            "http://10.0.0.2:40002/joinme/",
            "http://10.0.0.2:40002/join/extra",
            "http://10.0.0.2:40002/x/join/",
            "http://10.0.0.2/join/",
            "ftp://10.0.0.2:40002/join/",
            "tcp://10.0.0.2:40000",
            "/join/",
            "not a url",
            "",
        ).forEach { assertNull(it, JoinUrl.parse(it)) }
    }
}
