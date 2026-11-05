package tech.capullo.audio.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmPauseGateTest {

    private val fade = 40 * 4 // 40 frames

    /** Stereo frames whose samples are all [value]. */
    private fun frames(count: Int, value: Short = 10_000): ByteBuffer {
        val b = ByteBuffer.allocate(count * 4).order(ByteOrder.nativeOrder())
        repeat(count * 2) { b.putShort(value) }
        b.flip()
        return b
    }

    private fun shorts(b: ByteBuffer): ShortArray {
        val d = b.duplicate().order(ByteOrder.nativeOrder())
        return ShortArray(d.remaining() / 2) { d.short }
    }

    @Test
    fun `nothing is lost - tail plus resume covers every held frame exactly once`() {
        val g = PcmPauseGate(capacityBytes = 10_000, fadeBytes = fade)
        g.pause()
        assertNull(g.offer(frames(30))) // 30 frames < the 40-frame fade: no tail yet
        val t = g.offer(frames(100))
        assertNotNull(t)
        val tailSamples = shorts(t!!)
        val rest = shorts(g.resume()!!)
        assertEquals(130 * 2, tailSamples.size + rest.size)
    }

    @Test
    fun `tail fades to silence and resume fades back in from silence`() {
        val g = PcmPauseGate(capacityBytes = 10_000, fadeBytes = fade)
        g.pause()
        val tail = shorts(g.offer(frames(200))!!)
        assertEquals(10_000, tail[0].toInt())
        assertEquals(0, tail[tail.size - 1].toInt())
        val rest = shorts(g.resume()!!)
        assertEquals(0, rest[0].toInt())
        assertEquals(10_000, rest[rest.size - 1].toInt()) // past the fade-in, untouched
        assertEquals((200 - 40) * 2, rest.size)
    }

    /** Output already quiet when the first held audio arrives: no tail, all held, resume fades in. */
    @Test
    fun `late first buffer is held whole with no fade-out tail`() {
        val g = PcmPauseGate(capacityBytes = 10_000, fadeBytes = fade)
        g.pause()
        assertNull(g.offer(frames(200), fadeOutAllowed = false))
        assertNull(g.offer(frames(100))) // later buffers can't produce a tail either
        val rest = shorts(g.resume()!!)
        assertEquals(300 * 2, rest.size)
        assertEquals(0, rest[0].toInt())
        assertEquals(10_000, rest[rest.size - 1].toInt())
    }

    @Test
    fun `resume before a tail was made returns the held audio unfaded`() {
        val g = PcmPauseGate(capacityBytes = 10_000, fadeBytes = fade)
        g.pause()
        assertNull(g.offer(frames(10)))
        val rest = shorts(g.resume()!!)
        assertEquals(20, rest.size)
        assertTrue(rest.all { it.toInt() == 10_000 })
    }

    @Test
    fun `discard drops held audio and ends the hold`() {
        val g = PcmPauseGate(capacityBytes = 10_000, fadeBytes = fade)
        g.pause()
        g.offer(frames(200))
        g.discard()
        assertFalse(g.holding)
        assertNull(g.resume())
    }

    @Test
    fun `overflow is counted, input fully consumed, and nothing past capacity is kept`() {
        val g = PcmPauseGate(capacityBytes = 400, fadeBytes = fade) // 100 frames
        g.pause()
        val input = frames(150)
        g.offer(input)
        assertEquals(0, input.remaining())
        assertEquals(50L * 4, g.droppedBytes)
        val rest = shorts(g.resume()!!)
        assertEquals((100 - 40) * 2, rest.size)
    }

    @Test
    fun `not holding means resume is a no-op`() {
        val g = PcmPauseGate(capacityBytes = 400, fadeBytes = fade)
        assertNull(g.resume())
    }
}
