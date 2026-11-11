package tech.capullo.audio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ArtFormatTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J'.code, 'F'.code, 'I'.code, 'F'.code, 0, 1, 1)
    private val png = bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
    private val gif = ascii("GIF89a") + ByteArray(10)
    private val webp = ascii("RIFF") + bytes(0x24, 0x08, 0, 0) + ascii("WEBPVP8 ")

    @Test
    fun `recognises the four published formats by their bytes`() {
        assertEquals(ArtFormat.JPEG, ArtFormat.sniff(jpeg))
        assertEquals(ArtFormat.PNG, ArtFormat.sniff(png))
        assertEquals(ArtFormat.GIF, ArtFormat.sniff(gif))
        assertEquals(ArtFormat.WEBP, ArtFormat.sniff(webp))
    }

    @Test
    fun `rejects other formats and truncated headers`() {
        assertNull(ArtFormat.sniff(ascii("RIFF") + bytes(0x24, 0x08, 0, 0) + ascii("WAVEfmt "))) // RIFF, not WebP
        assertNull(ArtFormat.sniff(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\">")))
        assertNull(ArtFormat.sniff(bytes(0, 0, 1, 0, 1, 0, 16, 16))) // ICO
        assertNull(ArtFormat.sniff(ascii("BM") + ByteArray(20))) // BMP
        assertNull(ArtFormat.sniff(webp.copyOf(10))) // RIFF without its WEBP tag
        assertNull(ArtFormat.sniff(bytes(0xFF, 0xD8)))
        assertNull(ArtFormat.sniff(ByteArray(0)))
    }

    @Test
    fun `sniffs base64 from its first characters, whatever the payload length`() {
        assertEquals(ArtFormat.JPEG, ArtFormat.sniffBase64(b64(jpeg + ByteArray(50_000))))
        assertEquals(ArtFormat.PNG, ArtFormat.sniffBase64(b64(png)))
        assertEquals(ArtFormat.GIF, ArtFormat.sniffBase64(b64(gif)))
        // The WEBP tag sits at bytes 8-11, i.e. straddles the third and fourth base64 quads.
        assertEquals(ArtFormat.WEBP, ArtFormat.sniffBase64(b64(webp)))
        assertNull(ArtFormat.sniffBase64(b64(ascii("<svg/>"))))
        assertNull(ArtFormat.sniffBase64("/9j"))
        assertNull(ArtFormat.sniffBase64(""))
    }
}
