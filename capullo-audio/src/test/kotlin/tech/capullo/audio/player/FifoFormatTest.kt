package tech.capullo.audio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Util
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.audio.snapcast.SnapserverProcess
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FifoFormatTest {

    private fun pcm16(rate: Int, channels: Int) = AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)

    /** The stage as DefaultAudioSink runs it: inactive processors pass their input format on. */
    private fun stagePipeline(input: AudioProcessor.AudioFormat) = AudioProcessingPipeline(ImmutableList.copyOf(FifoFormat.stage())).apply {
        configure(input)
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    @Test
    fun `every supported input leaves the stage in the FIFO format`() {
        val fifo = pcm16(FifoFormat.SAMPLE_RATE_HZ, FifoFormat.CHANNEL_COUNT)
        for (rate in listOf(44_100, 48_000, 96_000, 192_000)) {
            for (channels in 1..8) {
                assertEquals("$rate Hz $channels ch", fifo, stagePipeline(pcm16(rate, channels)).outputAudioFormat)
            }
        }
    }

    @Test
    fun `snapserver reads the FIFO in the format the stage writes`() {
        val (rate, bits, channels) = SnapserverProcess.SAMPLE_FORMAT
            .removePrefix("sampleformat=")
            .split(":")
            .map { it.toInt() }
        val written = stagePipeline(pcm16(44_100, 1)).outputAudioFormat
        assertEquals(written.sampleRate, rate)
        assertEquals(Util.getByteDepth(written.encoding) * 8, bits)
        assertEquals(written.channelCount, channels)
    }

    @Test
    fun `six channels fold into stereo with the ITU BS 775 coefficients, LFE dropped`() {
        val pipeline = stagePipeline(pcm16(48_000, 6))
        // FL FR FC LFE BL BR
        val frame = shortArrayOf(1_000, 2_000, 10_000, 30_000, 4_000, 8_000)
        val input = ByteBuffer.allocateDirect(frame.size * 2).order(ByteOrder.nativeOrder())
        frame.forEach { input.putShort(it) }
        input.flip()
        pipeline.queueInput(input)
        val out = pipeline.output.order(ByteOrder.nativeOrder())
        assertEquals(4, out.remaining())
        assertEquals(1_000 + 0.707 * 10_000 + 0.707 * 4_000, out.short.toDouble(), 1.0)
        assertEquals(2_000 + 0.707 * 10_000 + 0.707 * 8_000, out.short.toDouble(), 1.0)
    }
}
