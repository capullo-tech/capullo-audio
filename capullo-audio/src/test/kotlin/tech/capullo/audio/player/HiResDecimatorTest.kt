package tech.capullo.audio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class HiResDecimatorTest {

    private fun fmt(rate: Int) = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)

    /** Output level of a full-chain (decimator -> 48k resampler) stereo tone, dB relative to input. */
    private fun levelDb(inRate: Int, toneHz: Double): Double {
        val dec = HiResDecimator()
        val res = FixedRateResampler(48_000)
        res.configure(dec.configure(fmt(inRate)))
        dec.flush(AudioProcessor.StreamMetadata.DEFAULT)
        res.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val amp = 16_000.0
        val out = ArrayList<Short>()
        fun collect(b: ByteBuffer) {
            while (b.remaining() >= 4) { out.add(b.short); b.short }
        }
        val chunkFrames = 1024
        var i = 0
        while (i < inRate) {
            val buf = ByteBuffer.allocateDirect(chunkFrames * 4).order(ByteOrder.nativeOrder())
            repeat(chunkFrames) {
                val s = (sin(2 * PI * toneHz * i++ / inRate) * amp).toInt().toShort()
                buf.putShort(s); buf.putShort(s)
            }
            buf.flip()
            dec.queueInput(buf)
            if (res.isActive) {
                res.queueInput(dec.output)
                collect(res.output.order(ByteOrder.nativeOrder()))
            } else {
                collect(dec.output.order(ByteOrder.nativeOrder()))
            }
        }
        val steady = out.drop(out.size / 4)
        val rms = sqrt(steady.sumOf { it.toDouble() * it } / steady.size)
        return 20 * log10(rms / (amp / sqrt(2.0)) + 1e-9)
    }

    @Test
    fun `audible band passes, ultrasonic content does not fold down`() {
        assertEquals(0.0, levelDb(192_000, 1_000.0), 0.5)
        assertEquals(0.0, levelDb(192_000, 18_000.0), 0.5)
        assertTrue(levelDb(192_000, 30_000.0) < -70) // Sonic alone: 0 dB, folded to 18 kHz
        assertTrue(levelDb(176_400, 30_000.0) < -70)
        assertTrue(levelDb(96_000, 30_000.0) < -70)
    }

    @Test
    fun `decimates by integer factor to the matching base rate`() {
        assertEquals(48_000, HiResDecimator().configure(fmt(192_000)).sampleRate)
        assertEquals(44_100, HiResDecimator().configure(fmt(176_400)).sampleRate)
    }

    @Test
    fun `inactive at or below 48k and for non-integer ratios`() {
        for (rate in listOf(44_100, 48_000, 64_000)) {
            val d = HiResDecimator()
            d.configure(fmt(rate))
            assertFalse("rate=$rate", d.isActive)
        }
    }
}
