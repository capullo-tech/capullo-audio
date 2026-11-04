package tech.capullo.audio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Continuously estimates the rolling loudness of a LIVE PCM stream (internet radio - no fixed
 * track boundaries, no file to pre-analyze) and slews [gainProcessor]'s gain toward [targetLufs]
 * over many seconds. The live counterpart to Telecloud-radio's discrete per-track TrackGainDao
 * lookup, which depends on a stable downloaded file + message id that simply don't exist for an
 * ICY/HTTP radio stream - there is nothing to key a cache against, and nothing to decode offline
 * ahead of time.
 *
 * Deliberately slow and heavily time-averaged: the goal is to correct for one STATION being much
 * louder/quieter than another (or drifting over a long broadcast), never to react within a single
 * song - anything fast enough to "fix" a quiet verse vs. a loud chorus would be audible pumping,
 * which is worse than the loudness difference it is correcting. [ROLLING_WINDOW_BLOCKS] *
 * [SUB_BLOCK_SECONDS] (30s) sets how much history the current estimate reflects; [SMOOTHING_ALPHA]
 * (~10s time constant at the 10 Hz block-update cadence) sets how slowly the applied gain is
 * allowed to drift toward that estimate. Combined, a sudden level change (station switch, a loud
 * jingle ending) takes on the order of a minute to fully correct - intentional.
 *
 * Pure passthrough - NEVER modifies samples itself (the actual multiply happens downstream in
 * [gainProcessor]), so where it sits relative to anything else that scales the signal doesn't
 * affect measurement accuracy via feedback.
 *
 * Measurement is ITU-R BS.1770 / EBU R128 integrated loudness: [KWeighting] (both stages derived
 * from the sample rate, so any input rate measures correctly), channels summed, -70 LUFS absolute
 * gate and (ungated mean - 10 LU) relative gate - the same definition Telecloud-radio's per-track
 * analysis uses, so -18 means the same thing in both apps. It runs incrementally on streaming
 * 100ms sub-blocks with persistent per-channel filter state instead of a single whole-buffer pass,
 * since there is no "whole file" here - audio keeps arriving for as long as the station broadcasts.
 *
 * Positive gain is also capped by the window's sample peak ([PEAK_CEILING], ~-1 dBFS), because
 * [GainAudioProcessor] hard-clips: a quiet but peaky station (classical, speech) is lifted only as
 * far as its loudest recent sample allows, never into flat-topped peaks.
 *
 * All DoubleArray/state is preallocated in [onConfigure]; [updateGain] runs primitive loops only
 * (no boxing, no per-block allocation) - this runs continuously for a multi-hour broadcast, and a
 * steady small per-buffer allocation under GC pressure was the exact root cause of a prior audible
 * stutter in this codebase (FLAC decoder buffer churn → periodic GC → FIFO dryout), so this path
 * is held to the same standard even though it is DSP, not a decoder.
 */
@UnstableApi
class LiveLoudnessAgcProcessor(
    private val gainProcessor: GainAudioProcessor,
    private val targetLufs: Float = -18f,
) : BaseAudioProcessor() {

    /**
     * Normalization on/off. Only the flag is written here; the audio thread notices the change in
     * [queueInput] and resets gain / history itself, because [appliedGain] and the rolling window are
     * owned by that thread - resetting them from the caller's thread races an in-flight [updateGain]
     * and can leave a stale gain applied while the toggle shows off.
     */
    @Volatile
    var enabled: Boolean = true

    /** Latest gated loudness estimate of the incoming stream in LUFS (NaN until the first one). */
    @Volatile
    var measuredLufs: Float = Float.NaN
        private set

    // Per-channel biquad state (index 0 = L, 1 = R), carried across queueInput calls.
    private class BiquadState {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }
    }
    private val stage1 = arrayOf(BiquadState(), BiquadState())
    private val stage2 = arrayOf(BiquadState(), BiquadState())
    private var shelf = KWeighting.shelf(48_000)
    private var highPass = KWeighting.highPass(48_000)

    private var subBlockSize = 4_800 // samples-per-channel; recomputed from sampleRate
    private var subBlockCursor = 0
    private var subBlockSumSq = 0.0
    private var subBlockPeak = 0

    private val rollingPower = DoubleArray(ROLLING_WINDOW_BLOCKS)
    private val rollingPeak = IntArray(ROLLING_WINDOW_BLOCKS)
    private var rollingFilled = 0
    private var rollingCursor = 0

    private var appliedGain = 1f

    // Audio-thread view of [enabled], to act on transitions exactly once.
    private var wasEnabled = true

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET
        }
        val sampleRate = inputAudioFormat.sampleRate
        subBlockSize = (sampleRate * SUB_BLOCK_SECONDS).toInt().coerceAtLeast(1)
        shelf = KWeighting.shelf(sampleRate)
        highPass = KWeighting.highPass(sampleRate)
        resetWindow()
        appliedGain = gainProcessor.gain
        return inputAudioFormat
    }

    private fun resetWindow() {
        subBlockCursor = 0
        subBlockSumSq = 0.0
        subBlockPeak = 0
        rollingFilled = 0
        rollingCursor = 0
        for (s in stage1) s.reset()
        for (s in stage2) s.reset()
    }

    private fun biquadStep(state: BiquadState, f: KWeighting.Biquad, xi: Double): Double {
        val yi = f.b0 * xi + f.b1 * state.x1 + f.b2 * state.x2 - f.a1 * state.y1 - f.a2 * state.y2
        state.x2 = state.x1
        state.x1 = xi
        state.y2 = state.y1
        state.y1 = yi
        return yi
    }

    private fun kWeight(channel: Int, x: Double): Double =
        biquadStep(stage2[channel], highPass, biquadStep(stage1[channel], shelf, x))

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val on = enabled
        if (on != wasEnabled) {
            wasEnabled = on
            if (!on) {
                appliedGain = 1f
                gainProcessor.gain = 1f
                measuredLufs = Float.NaN
            } else {
                resetWindow() // don't act on history from before it was switched off
            }
        }
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!on) {
            // Still forward the audio unmodified - just skip measurement/gain-steering work.
            output.put(inputBuffer)
            output.flip()
            return
        }
        while (inputBuffer.remaining() >= 4) {
            val left = inputBuffer.short
            val right = inputBuffer.short
            output.putShort(left)
            output.putShort(right)
            val lk = kWeight(0, left / 32768.0)
            val rk = kWeight(1, right / 32768.0)
            subBlockSumSq += lk * lk + rk * rk
            val peak = maxOf(abs(left.toInt()), abs(right.toInt()))
            if (peak > subBlockPeak) subBlockPeak = peak
            subBlockCursor++
            if (subBlockCursor >= subBlockSize) finalizeSubBlock()
        }
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }

    private fun finalizeSubBlock() {
        // Sum of the per-channel mean squares (BS.1770: channels are summed, not averaged).
        rollingPower[rollingCursor] = subBlockSumSq / subBlockCursor
        rollingPeak[rollingCursor] = subBlockPeak
        rollingCursor = (rollingCursor + 1) % ROLLING_WINDOW_BLOCKS
        if (rollingFilled < ROLLING_WINDOW_BLOCKS) rollingFilled++
        subBlockSumSq = 0.0
        subBlockPeak = 0
        subBlockCursor = 0
        updateGain()
    }

    private fun updateGain() {
        if (rollingFilled < MIN_BLOCKS_BEFORE_ADJUST) return
        // Absolute gate: -70 LUFS - ignore near-silence (station ID gaps, intro silence) so it
        // never drags the rolling mean down and overcorrects.
        val absoluteThreshold = 10.0.pow((-70.0 - KWeighting.LOUDNESS_OFFSET_DB) / 10.0)
        var sum = 0.0
        var count = 0
        for (i in 0 until rollingFilled) {
            val p = rollingPower[i]
            if (p > absoluteThreshold) { sum += p; count++ }
        }
        if (count == 0) return // whole window is near-silent - hold the last gain, don't chase it
        val ungatedMean = sum / count
        if (ungatedMean <= 0.0) return
        // Relative gate: (ungated mean) - 10 LU - also drop quiet passages (talk breaks, pauses
        // between songs) so the estimate tracks the music, not the silence between it.
        val relativeThresholdLufs = (KWeighting.LOUDNESS_OFFSET_DB + 10 * log10(ungatedMean)) - 10.0
        val relativeThreshold = 10.0.pow((relativeThresholdLufs - KWeighting.LOUDNESS_OFFSET_DB) / 10.0)
        var sum2 = 0.0
        var count2 = 0
        var windowPeak = 0
        for (i in 0 until rollingFilled) {
            val p = rollingPower[i]
            if (p > relativeThreshold) { sum2 += p; count2++ }
            if (rollingPeak[i] > windowPeak) windowPeak = rollingPeak[i]
        }
        val finalMean = if (count2 > 0) sum2 / count2 else ungatedMean
        val currentLufs = (KWeighting.LOUDNESS_OFFSET_DB + 10 * log10(finalMean)).toFloat()
        measuredLufs = currentLufs
        // Tighter negative bound than a file-based measurement would use: a 30s rolling, noisier
        // estimate is less trustworthy than a full-track gated pass, so this is more conservative
        // about how hard it will pull a station down on the strength of one loud window.
        val targetGainDb = (targetLufs - currentLufs).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        var targetGainLinear = 10f.pow(targetGainDb / 20f)
        // Headroom: never lift the window's loudest sample above PEAK_CEILING (GainAudioProcessor
        // hard-clips). Only limits boost; attenuation is always allowed.
        if (windowPeak > 0) {
            val peakCap = PEAK_CEILING * 32767f / windowPeak
            if (targetGainLinear > peakCap) targetGainLinear = maxOf(peakCap, minOf(1f, targetGainLinear))
        }
        // Exponential slew toward the target, not an instantaneous jump - see class doc.
        appliedGain += (targetGainLinear - appliedGain) * SMOOTHING_ALPHA
        gainProcessor.gain = appliedGain
    }

    companion object {
        private const val SUB_BLOCK_SECONDS = 0.1 // 100ms granularity, matches BS.1770 step size
        private const val ROLLING_WINDOW_BLOCKS = 300 // 300 * 100ms = 30s of history
        private const val MIN_BLOCKS_BEFORE_ADJUST = 20 // ~2s - don't react to a near-empty window
        private const val SMOOTHING_ALPHA = 0.01f // ~10s time constant at 10 Hz block cadence
        private const val MIN_GAIN_DB = -12f
        private const val MAX_GAIN_DB = 6f

        /** Max sample peak after gain, as a fraction of full scale (~-1 dBFS). */
        private const val PEAK_CEILING = 0.89f
    }
}
