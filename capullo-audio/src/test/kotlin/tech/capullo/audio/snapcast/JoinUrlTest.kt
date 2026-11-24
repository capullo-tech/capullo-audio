package tech.capullo.audio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `intentLink opens the LAN join URL in the named app and falls back to the player root`() {
        assertEquals(
            "intent://192.168.1.20:40002/join/#Intent;scheme=http;package=tech.capullo.quantumcast;" +
                "S.browser_fallback_url=http%3A%2F%2F192.168.1.20%3A40002%2F;end",
            JoinUrl.intentLink(JoinTarget("192.168.1.20", 40002, false), JoinUrl.PACKAGE_QUANTUMCAST),
        )
    }

    @Test
    fun `intentLink keeps the tunnel form portless and brackets IPv6`() {
        assertEquals(
            "intent://quiet-river-blue-fox.trycloudflare.com/join/#Intent;scheme=https;" +
                "package=tech.capullo.tubecapullo;" +
                "S.browser_fallback_url=https%3A%2F%2Fquiet-river-blue-fox.trycloudflare.com%2F;end",
            JoinUrl.intentLink(
                JoinUrl.parse("https://quiet-river-blue-fox.trycloudflare.com/join/")!!,
                JoinUrl.PACKAGE_CAPULLO_TUBE,
            ),
        )
        assertEquals(
            "intent://[fd7a:115c::1]:40002/join/#Intent;scheme=http;package=tech.capullo.telecloudradio;" +
                "S.browser_fallback_url=http%3A%2F%2F%5Bfd7a%3A115c%3A%3A1%5D%3A40002%2F;end",
            JoinUrl.intentLink(JoinTarget("fd7a:115c::1", 40002, false), JoinUrl.PACKAGE_TELECLOUD_RADIO),
        )
    }

    @Test
    fun `the shipped landing page offers the broadcasting app and no other`() {
        val template = File("src/main/assets/webui/join/index.html").readText()
        val page = JoinUrl.landingPage(template, JoinUrl.PACKAGE_QUANTUMCAST)
        assertTrue("""const APP = {"pkg":"${JoinUrl.PACKAGE_QUANTUMCAST}","label":"QuantumCast"};""" in page)
        JoinUrl.APPS.map { it.packageName }.filter { it != JoinUrl.PACKAGE_QUANTUMCAST }
            .forEach { assertFalse(it, it in page) }
    }

    @Test
    fun `a broadcaster that joins no stations leaves the page redirecting to the player`() {
        val template = File("src/main/assets/webui/join/index.html").readText()
        assertTrue("const APP = /*CAPULLO_APP*/null;" in template)
        assertEquals(template, JoinUrl.landingPage(template, "tech.capullo.audio.harness"))
    }

    @Test
    fun `a cloned broadcaster offers the app a listener has`() {
        val template = File("src/main/assets/webui/join/index.html").readText()
        val page = JoinUrl.landingPage(template, JoinUrl.PACKAGE_CAPULLO_TUBE + ".clone")
        assertTrue("""const APP = {"pkg":"${JoinUrl.PACKAGE_CAPULLO_TUBE}","label":"CapulloTube"};""" in page)
    }
}
