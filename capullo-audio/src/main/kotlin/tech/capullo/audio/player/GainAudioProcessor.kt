package tech.capullo.audio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Applies a volume-normalization gain (linear multiplier, 1.0 = no change) to 16-bit stereo PCM.
 * Volatile [gain] can change mid-playback - a caller sets it however it determines the right
 * value for the content currently flowing (a static per-track lookup for on-demand playback with
 * known files, e.g. Telecloud's TrackGainDao; a continuously-updating live estimate for streamed
 * radio with no fixed track boundaries, e.g. QuantumCast's LiveLoudnessAgcProcessor) - this class
 * only applies whatever gain it is given, live, one buffer at a time.
 *
 * Sits in the same sink-chain position as [BalanceAudioProcessor] (pre-tee, post-resample).
 * Inactive (pass-through) for mono or non-16-bit content, matching BalanceAudioProcessor's same
 * guard.
 *
 * A delivery-layer audio primitive: apps insert it into their ExoPlayer sink chain
 * (before the FIFO tee) to steer the broadcast mix.
 */
@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile var gain: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val g = gain
        if (g == 1f) {
            // No-op passthrough - skip the per-sample loop entirely for the common case
            // (untagged/unanalyzed content, or normalization computed exactly unity gain).
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
            return
        }
        val output = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= 2) {
            val sample = (inputBuffer.short * g).toInt().coerceIn(-32768, 32767)
            output.putShort(sample.toShort())
        }
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }
}
