package tech.capullo.audio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedRateResamplerTest {

    private val in441 = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    @Test
    fun `output rate survives reset`() {
        val r = FixedRateResampler(48_000)
        assertEquals(48_000, r.configure(in441).sampleRate)
        r.reset()
        assertEquals(48_000, r.configure(in441).sampleRate)
        assertTrue(r.isActive)
    }

    /** Documents the Media3 behavior this class exists for; if it stops failing, drop the wrapper. */
    @Test
    fun `bare SonicAudioProcessor forgets its target on reset`() {
        val s = SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
        assertEquals(48_000, s.configure(in441).sampleRate)
        s.reset()
        assertEquals(44_100, s.configure(in441).sampleRate)
    }
}
