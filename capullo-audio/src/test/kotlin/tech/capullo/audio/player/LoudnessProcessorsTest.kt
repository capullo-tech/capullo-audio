package tech.capullo.audio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class LoudnessProcessorsTest {

    private fun format(rate: Int) = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)

    /** Interleaved stereo 997 Hz sine at [peakDbfs], [seconds] long. */
    private fun sine(rate: Int, peakDbfs: Double, seconds: Double, freq: Double = 997.0): ShortArray {
        val amp = 32767 * 10.0.pow(peakDbfs / 20)
        val n = (rate * seconds).toInt()
        return ShortArray(n * 2) { i -> (amp * sin(2 * PI * freq * (i / 2) / rate)).roundToInt().toShort() }
    }

    /** Push [pcm] through [p] in 10 ms buffers, returning everything it output. */
    private fun run(p: AudioProcessor, pcm: ShortArray, rate: Int): ShortArray {
        val out = ArrayList<Short>(pcm.size)
        val chunk = rate / 100 * 2
        var i = 0
        while (i < pcm.size) {
            val len = minOf(chunk, pcm.size - i)
            val buf = ByteBuffer.allocateDirect(len * 2).order(ByteOrder.nativeOrder())
            for (k in 0 until len) buf.putShort(pcm[i + k])
            buf.flip()
            p.queueInput(buf)
            val o = p.output
            while (o.remaining() >= 2) out.add(o.short)
            i += len
        }
        return out.toShortArray()
    }

    private fun agc(rate: Int = 48_000): Pair<LiveLoudnessAgcProcessor, GainAudioProcessor> {
        val gain = GainAudioProcessor()
        val agc = LiveLoudnessAgcProcessor(gain)
        agc.configure(format(rate))
        agc.flush()
        return agc to gain
    }

    @Test
    fun `measures a -20 dBFS stereo 997 Hz sine as -20 LUFS`() {
        // BS.1770: a 997 Hz sine at X dBFS peak on both channels reads X LUFS (-20.03 at -20).
        val (a, _) = agc()
        run(a, sine(48_000, -20.0, 10.0), 48_000)
        assertEquals(-20.0, a.measuredLufs.toDouble(), 0.1)
    }

    @Test
    fun `measures the same at 44_1 kHz - K-weighting follows the sample rate`() {
        val (a, _) = agc(44_100)
        run(a, sine(44_100, -20.0, 10.0), 44_100)
        assertEquals(-20.0, a.measuredLufs.toDouble(), 0.1)
    }

    @Test
    fun `AGC is a pure passthrough - output samples equal input`() {
        val (a, _) = agc()
        val pcm = sine(48_000, -30.0, 3.0)
        assertArrayEquals(pcm, run(a, pcm, 48_000))
    }

    @Test
    fun `gain slews toward target instead of jumping`() {
        // -30 LUFS wants +12 dB, clamped to +6 (x2). After ~3 s only a handful of 1%-steps applied.
        val (a, g) = agc()
        run(a, sine(48_000, -30.0, 3.0), 48_000)
        assertTrue("gain ${g.gain} should have started rising", g.gain > 1f)
        assertTrue("gain ${g.gain} should not have jumped to target", g.gain < 1.2f)
    }

    @Test
    fun `quiet steady signal converges to the +6 dB cap`() {
        val (a, g) = agc()
        run(a, sine(48_000, -30.0, 70.0), 48_000)
        assertEquals(10f.pow(6f / 20f), g.gain, 0.02f)
    }

    @Test
    fun `boost is limited by sample-peak headroom`() {
        // Loudness ~-28 LUFS (wants +6 dB) but a -1.5 dBFS click every second: lifting it by +6 dB
        // would clip, so gain may only rise until the click reaches ~-1 dBFS.
        val rate = 48_000
        val pcm = sine(rate, -28.0, 70.0)
        val click = (32767 * 10.0.pow(-1.5 / 20)).toInt().toShort()
        for (s in 0 until 70) { pcm[s * rate * 2] = click; pcm[s * rate * 2 + 1] = click }
        val (a, g) = agc()
        run(a, pcm, rate)
        val cap = 0.89f * 32767f / click
        assertTrue("gain ${g.gain} exceeded peak cap $cap", g.gain <= cap + 0.01f)
    }

    @Test
    fun `disabling resets gain to unity on the audio thread`() {
        val (a, g) = agc()
        run(a, sine(48_000, -30.0, 10.0), 48_000)
        assertTrue(g.gain > 1f)
        a.enabled = false
        run(a, sine(48_000, -30.0, 0.05), 48_000)
        assertEquals(1f, g.gain, 0f)
        assertTrue(a.measuredLufs.isNaN())
    }

    @Test
    fun `a sudden loud passage pulls boost down within one block`() {
        val (a, g) = agc()
        run(a, sine(48_000, -30.0, 70.0), 48_000)
        assertTrue(g.gain > 1.9f)
        run(a, sine(48_000, -3.0, 0.2), 48_000)
        val peak = 32767 * 10.0.pow(-3.0 / 20)
        assertTrue("gain ${g.gain} would clip a -3 dBFS peak", g.gain * peak <= 0.89 * 32767 + 1)
    }

    @Test
    fun `reconfiguring drops a carried-over boost but keeps a cut`() {
        val (a, g) = agc()
        run(a, sine(48_000, -30.0, 70.0), 48_000)
        assertTrue(g.gain > 1.9f)
        a.configure(format(48_000)); a.flush()
        assertEquals(1f, g.gain, 0f)
        run(a, sine(48_000, -6.0, 40.0), 48_000)
        val cut = g.gain
        assertTrue("expected a cut, got $cut", cut < 1f)
        a.configure(format(48_000)); a.flush()
        assertEquals(cut, g.gain, 0f)
    }

    @Test
    fun `gain processor at unity is a bit-exact passthrough`() {
        val g = GainAudioProcessor()
        g.configure(format(48_000))
        g.flush()
        val pcm = sine(48_000, -3.0, 0.5)
        assertArrayEquals(pcm, run(g, pcm, 48_000))
    }

    @Test
    fun `gain processor clamps instead of wrapping`() {
        val g = GainAudioProcessor().apply { gain = 4f }
        g.configure(format(48_000))
        g.flush()
        val out = run(g, shortArrayOf(20_000, -20_000, 100, -100), 48_000)
        assertArrayEquals(shortArrayOf(32767, -32768, 400, -400), out)
    }
}
