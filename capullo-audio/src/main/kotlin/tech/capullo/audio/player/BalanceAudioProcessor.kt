package tech.capullo.audio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Applies a stereo balance (-1 = full left … 0 = center … +1 = full right) to
 * 16-bit stereo PCM. Volatile [balance] can be changed while playing - each
 * buffer picks up the latest value, so a balance slider is live.
 * Inactive (pass-through) for mono or non-16-bit content.
 *
 * A delivery-layer audio primitive: apps insert it into their ExoPlayer sink chain
 * (before the FIFO tee) to steer the broadcast mix.
 */
@UnstableApi
class BalanceAudioProcessor : BaseAudioProcessor() {

    @Volatile var balance: Float = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Only handle 16-bit stereo; report inactive otherwise so the sink bypasses us
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Guard: with 0 remaining, replaceOutputBuffer(0) can hand back the very same
        // (empty) buffer instance, and bulk copies then throw "source buffer is this buffer"
        if (!inputBuffer.hasRemaining()) return
        val b = balance.coerceIn(-1f, 1f)
        val leftGain = if (b > 0f) 1f - b else 1f
        val rightGain = if (b < 0f) 1f + b else 1f
        val output = replaceOutputBuffer(inputBuffer.remaining())
        // Always per-sample (never ByteBuffer.put(ByteBuffer) - no aliasing possible)
        while (inputBuffer.remaining() >= 4) {
            val left = (inputBuffer.short * leftGain).toInt().coerceIn(-32768, 32767)
            val right = (inputBuffer.short * rightGain).toInt().coerceIn(-32768, 32767)
            output.putShort(left.toShort())
            output.putShort(right.toShort())
        }
        // Defensive: copy any unaligned trailing bytes untouched
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }
}
