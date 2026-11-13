package tech.capullo.audio.snapcast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class SnapcastRemoteArtTest {

    private fun resolve(artUrl: String?, host: String = "192.168.1.20", port: Int = 41234) = SnapcastRemoteArt.resolveUrl(artUrl, host, port)

    @Test
    fun `a path is fetched from the snapserver the listener is connected to`() {
        assertEquals("http://192.168.1.20:41234/art/ab12.jpg", resolve("/art/ab12.jpg"))
        assertEquals("http://192.168.1.20:41234/art/ab12.jpg", resolve("art/ab12.jpg"))
        assertEquals("http://[fe80::1]:41234/art/ab12.jpg", resolve("/art/ab12.jpg", host = "fe80::1"))
        assertNull("no host to fetch from", resolve("/art/ab12.jpg", host = ""))
    }

    @Test
    fun `an absolute station logo URL is used as is`() {
        assertEquals("https://radio.example/logo.png?v=2", resolve("https://radio.example/logo.png?v=2"))
        assertEquals("http://203.0.113.7:8000/favicon.ico", resolve("http://203.0.113.7:8000/favicon.ico"))
        assertEquals("http://cdn.example/a.jpg", resolve("//cdn.example/a.jpg"))
    }

    @Test
    fun `nothing to fetch for the image cache, loopback hosts and non-http schemes`() {
        // Snapserver's own artData mirror, built from its configured host name.
        assertNull(resolve("http://localhost:1780/__image_cache?name=5d41402abc4b2a76b9719d911017c592"))
        assertNull(resolve("http://phone.lan:1780/__image_cache?name=5d41402abc4b2a76b9719d911017c592"))
        assertNull(resolve("http://127.0.0.1:41234/art/ab12.jpg"))
        assertNull(resolve("http://[::1]:41234/art/ab12.jpg"))
        assertNull(resolve("data:image/png;base64,iVBORw0KGgo="))
        assertNull(resolve("file:///sdcard/a.jpg"))
        assertNull(resolve(""))
        assertNull(resolve(null))
    }

    @Test
    fun `reads a body up to the cap and gives up past it`() {
        val atCap = ByteArray(40_000) { it.toByte() }
        assertArrayEquals(atCap, SnapcastRemoteArt.readCapped(ByteArrayInputStream(atCap), maxBytes = 40_000))
        assertNull(SnapcastRemoteArt.readCapped(ByteArrayInputStream(ByteArray(40_001)), maxBytes = 40_000))
    }

    @Test
    fun `stops reading when told to`() {
        var reads = 0
        val result = SnapcastRemoteArt.readCapped(ByteArrayInputStream(ByteArray(100_000)), maxBytes = 1_000_000) {
            reads++ < 2
        }
        assertNull(result)
    }
}
