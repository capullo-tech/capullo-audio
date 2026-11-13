package tech.capullo.audio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Anti-alias low-pass + integer decimation for hi-res 16-bit PCM, ahead of [FixedRateResampler].
 *
 * Sonic's rate conversion has no anti-alias filter: downsampling 192k to 48k folds everything above
 * 24 kHz (a hi-res master's ultrasonic noise / noise-shaped dither) straight into the audible band at
 * full level. This filters to 20 kHz and decimates by an integer factor first:
 * 96k/192k/384k -> 48k, 88.2k/176.4k/352.8k -> 44.1k (Sonic then upsamples to 48k, which cannot alias).
 * Kaiser-windowed sinc, ~80 dB stopband from the output Nyquist; only output frames are computed.
 *
 * Inactive (pass-through) for rates <= 48k, non-integer ratios, and non-16-bit PCM.
 */
@UnstableApi
class HiResDecimator : BaseAudioProcessor() {
    // Parameters for the stream being configured; applied on flush (BaseAudioProcessor contract:
    // the previous stream may still drain between configure() and flush()).
    private var pendingFactor = 1
    private var pendingChannels = 0
    private var pendingTaps = FloatArray(0)

    private var factor = 1
    private var channels = 0
    private var taps = FloatArray(0)

    // Per channel: 2*N floats, each sample written twice so the newest N are always contiguous.
    private var history = FloatArray(0)
    private var pos = 0
    private var phase = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        val rate = inputAudioFormat.sampleRate
        val outRate = when {
            rate > 48_000 && rate % 48_000 == 0 -> 48_000
            rate > 44_100 && rate % 44_100 == 0 -> 44_100
            else -> return AudioFormat.NOT_SET
        }
        pendingFactor = rate / outRate
        pendingChannels = inputAudioFormat.channelCount
        pendingTaps = lowPass(rate, outRate)
        return AudioFormat(outRate, pendingChannels, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val n = taps.size
        val frameBytes = 2 * channels
        val inFrames = inputBuffer.remaining() / frameBytes
        val output = replaceOutputBuffer((phase + inFrames) / factor * frameBytes)
        repeat(inFrames) {
            for (ch in 0 until channels) {
                val v = inputBuffer.short.toFloat()
                val base = ch * 2 * n
                history[base + pos] = v
                history[base + pos + n] = v
            }
            if (++phase == factor) {
                phase = 0
                for (ch in 0 until channels) {
                    val start = ch * 2 * n + pos + 1
                    var acc = 0f
                    for (k in 0 until n) acc += taps[k] * history[start + k]
                    output.putShort(acc.toInt().coerceIn(-32768, 32767).toShort())
                }
            }
            pos = if (pos + 1 == n) 0 else pos + 1
        }
        // Unaligned trailing bytes cannot occur for whole-frame PCM; drop defensively.
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        factor = pendingFactor
        channels = pendingChannels
        taps = pendingTaps
        history = FloatArray(channels * 2 * taps.size)
        pos = 0
        phase = 0
    }

    override fun onReset() {
        pendingFactor = 1
        pendingChannels = 0
        pendingTaps = FloatArray(0)
        factor = 1
        channels = 0
        taps = FloatArray(0)
        history = FloatArray(0)
    }

    private companion object {
        const val PASS_EDGE_HZ = 20_000.0
        const val STOPBAND_DB = 80.0

        /** Kaiser-windowed sinc: passband to 20 kHz, stopband from [outRate]/2, odd length, unity DC gain. */
        fun lowPass(rate: Int, outRate: Int): FloatArray {
            val stopEdge = outRate / 2.0
            val transition = 2 * PI * (stopEdge - PASS_EDGE_HZ) / rate
            var n = ceil((STOPBAND_DB - 8) / (2.285 * transition)).toInt() + 1
            if (n % 2 == 0) n++
            val beta = 0.1102 * (STOPBAND_DB - 8.7)
            val fc = (PASS_EDGE_HZ + stopEdge) / 2 / rate // normalized cutoff, cycles/sample
            val mid = (n - 1) / 2.0
            val i0Beta = besselI0(beta)
            val h = DoubleArray(n) { i ->
                val x = i - mid
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val r = x / mid
                sinc * besselI0(beta * sqrt(1 - r * r)) / i0Beta
            }
            val sum = h.sum()
            return FloatArray(n) { (h[it] / sum).toFloat() }
        }

        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                val q = x / (2 * k)
                term *= q * q
                sum += term
                k++
            }
            return sum
        }
    }
}
