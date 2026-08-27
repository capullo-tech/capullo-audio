package tech.capullo.audio.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelUrlParserTest {

    @Test
    fun `boxed banner line yields trycloudflare url`() {
        // Real cloudflared log shape (live-captured): boxed banner, padded with spaces.
        val line =
            "2026-08-19T13:48:01Z INF |  https://citation-correct-sisters-once.trycloudflare.com" +
                "                                   |"
        assertEquals(
            "https://citation-correct-sisters-once.trycloudflare.com",
            parsePublicUrl(line),
        )
    }

    @Test
    fun `tunnel url wins over earlier decoy links in the banner`() {
        // The thank-you banner carries doc/ToS links BEFORE the tunnel URL is issued.
        val log = """
            2026-08-19T13:47:58Z INF Thank you for trying Cloudflare Tunnel. ... (https://www.cloudflare.com/website-terms/) ... https://developers.cloudflare.com/cloudflare-one/connections/connect-apps
            2026-08-19T13:47:58Z INF Requesting new quick Tunnel on trycloudflare.com...
            2026-08-19T13:48:01Z INF |  https://jackson-scored-picking-description.trycloudflare.com  |
        """.trimIndent()
        assertEquals(
            "https://jackson-scored-picking-description.trycloudflare.com",
            parsePublicUrl(log),
        )
    }

    @Test
    fun `requesting line alone does not match`() {
        assertNull(parsePublicUrl("2026-08-19T13:47:58Z INF Requesting new quick Tunnel on trycloudflare.com..."))
    }

    @Test
    fun `log without url returns null`() {
        assertNull(parsePublicUrl("2026-08-19T13:48:03Z INF Initial protocol quic"))
    }

    @Test
    fun `ansi wrapped url still matches`() {
        val line = "[1mhttps://word-word-word-word.trycloudflare.com[0m"
        assertEquals("https://word-word-word-word.trycloudflare.com", parsePublicUrl(line))
    }
}
