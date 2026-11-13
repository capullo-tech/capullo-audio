package tech.capullo.audio.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * [SonicAudioProcessor] whose output sample rate survives [reset].
 *
 * Media3's `SonicAudioProcessor.reset()` drops the target set by `setOutputSampleRateHz` back to
 * "same as input". `DefaultAudioSink.reset()` resets every processor, and ExoPlayer resets the sink
 * whenever the audio renderer is disabled - which happens on every decoder-path switch (platform
 * MediaCodec <-> FFmpeg extension, e.g. MP3 <-> FLAC) and on player reset. After that the resampler
 * became a pass-through, so 44.1k / 192k PCM went into the FIFO snapserver reads at a fixed
 * 48000 Hz: ~8% short of real time (resync storm, audible crackle) or 4x slow motion.
 *
 * Re-pinning the target inside [configure] covers every path: the sink always configures the
 * pipeline before any data flows after a reset.
 */
@UnstableApi
class FixedRateResampler(private val outputSampleRateHz: Int) : AudioProcessor {
    private val sonic = SonicAudioProcessor()

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sonic.setOutputSampleRateHz(outputSampleRateHz)
        return sonic.configure(inputAudioFormat)
    }

    override fun isActive(): Boolean = sonic.isActive
    override fun queueInput(inputBuffer: ByteBuffer) = sonic.queueInput(inputBuffer)
    override fun queueEndOfStream() = sonic.queueEndOfStream()
    override fun getOutput(): ByteBuffer = sonic.output
    override fun isEnded(): Boolean = sonic.isEnded
    override fun flush() = sonic.flush()
    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) = sonic.flush(streamMetadata)
    override fun reset() = sonic.reset()
    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        sonic.getDurationAfterProcessorApplied(durationUs)
}
