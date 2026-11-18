package tech.capullo.audio.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi

/**
 * The PCM format of the Snapserver FIFO, and the processor stage that converts any decoded input
 * into it: 48000 Hz / 16-bit / stereo.
 *
 * This is the only definition of that format. [tech.capullo.audio.snapcast.SnapserverProcess]
 * declares the FIFO with [SNAPCAST_SAMPLE_FORMAT], and every player that feeds the FIFO starts its
 * sink chain with [stage], so snapserver reads exactly what the players write. A mismatch plays at
 * the wrong pitch and drives snapserver into a resync storm.
 */
object FifoFormat {
    /**
     * 48000, not 44100: 48 kHz sources (hi-res FLAC, Opus) pass through without a resample, and
     * 44.1 kHz sources take one clean upsample. The 48->44.1 downsample a 44.1 kHz FIFO forced on
     * 48 kHz sources drove snapserver into a resync storm (audible stutter).
     */
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNEL_COUNT = 2
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_FRAME = CHANNEL_COUNT * BITS_PER_SAMPLE / 8

    /** The format in Snapcast's `rate:bits:channels` notation, as `sampleformat=` takes it. */
    const val SNAPCAST_SAMPLE_FORMAT = "$SAMPLE_RATE_HZ:$BITS_PER_SAMPLE:$CHANNEL_COUNT"

    /** The FFmpeg decoders emit multichannel FLAC etc. at their native channel count. */
    private const val MAX_INPUT_CHANNELS = 8

    /**
     * New processors (they hold state: one call per sink) that turn 16-bit PCM of 1 to 8 channels
     * at any rate into [SAMPLE_RATE_HZ] / [CHANNEL_COUNT]: a stereo mixer, [HiResDecimator], then
     * [FixedRateResampler]. Spread it at the head of a `DefaultAudioSink` chain built with
     * `setEnableFloatOutput(false)`; whatever follows it (balance, gain, the FIFO tee) receives the
     * FIFO format.
     */
    @UnstableApi
    fun stage(): Array<AudioProcessor> = arrayOf(
        ChannelMixingAudioProcessor().apply {
            for (channels in 1..MAX_INPUT_CHANNELS) putChannelMixingMatrix(stereoMatrix(channels))
        },
        // Anti-alias low-pass + integer decimation for >48 kHz input. Sonic has no low-pass, so a
        // 192 kHz master's ultrasonic content would fold into the audible band.
        HiResDecimator(),
        // Not a bare SonicAudioProcessor: that forgets its target rate on every sink reset.
        FixedRateResampler(SAMPLE_RATE_HZ),
    )

    /**
     * Mono goes at unity to both channels and stereo passes through. 5.1 (FL FR FC LFE BL BR) uses
     * the ITU-R BS.775 coefficients; other counts route ch0 to L, ch1 to R and fold the remaining
     * channels equally into both.
     */
    private fun stereoMatrix(inputChannelCount: Int): ChannelMixingMatrix {
        if (inputChannelCount <= CHANNEL_COUNT) {
            return ChannelMixingMatrix.createForConstantGain(inputChannelCount, CHANNEL_COUNT)
        }
        val c = FloatArray(inputChannelCount * CHANNEL_COUNT)
        fun set(inCh: Int, l: Float, r: Float) {
            c[inCh * CHANNEL_COUNT] = l
            c[inCh * CHANNEL_COUNT + 1] = r
        }
        if (inputChannelCount == 6) {
            set(0, 1f, 0f) // FL -> L
            set(1, 0f, 1f) // FR -> R
            set(2, 0.707f, 0.707f) // FC -> both
            set(3, 0f, 0f) // LFE dropped
            set(4, 0.707f, 0f) // BL -> L
            set(5, 0f, 0.707f) // BR -> R
        } else {
            set(0, 1f, 0f)
            set(1, 0f, 1f)
            for (i in 2 until inputChannelCount) set(i, 0.707f, 0.707f)
        }
        return ChannelMixingMatrix(inputChannelCount, CHANNEL_COUNT, c)
    }
}
