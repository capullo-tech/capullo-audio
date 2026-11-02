package tech.capullo.audio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

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
 * K-weighting filter + absolute/relative loudness gating mirror Telecloud-radio's
 * AudioAnalyzer.applyKWeighting / gatedIntegratedLufs (BS.1770 pre-filter + RLB high-pass at
 * 38.13506 Hz, -70 LUFS absolute gate, (ungated mean - 10 LU) relative gate), adapted to run
 * incrementally on streaming 100ms sub-blocks with persistent per-channel filter state instead of
 * a single whole-buffer pass, since there is no "whole file" here - audio keeps arriving for as
 * long as the station broadcasts.
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

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                appliedGain = 1f
                gainProcessor.gain = 1f
            }
        }

    private var sampleRate = 48_000

    // Stage1 (BS.1770 pre-filter, high-shelf) and stage2 (RLB high-pass) biquad state, kept PER
    // CHANNEL (index 0 = L, 1 = R) and carried across queueInput calls - unlike
    // AudioAnalyzer.biquadFilter, which resets state because it processes a whole file's
    // FloatArray in a single call.
    private class BiquadState {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
    }
    private val stage1 = arrayOf(BiquadState(), BiquadState())
    private val stage2 = arrayOf(BiquadState(), BiquadState())
    private var b1 = DoubleArray(3)
    private var a1 = DoubleArray(2)
    private var b2 = DoubleArray(3)
    private var a2 = DoubleArray(2)

    private var subBlockSize = 4_800 // samples-per-channel; recomputed from sampleRate
    private var subBlockCursor = 0
    private var subBlockSumSq = 0.0

    private var rollingPower = DoubleArray(ROLLING_WINDOW_BLOCKS)
    private var rollingFilled = 0
    private var rollingCursor = 0

    private var appliedGain = 1f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        subBlockSize = (sampleRate * SUB_BLOCK_SECONDS).toInt().coerceAtLeast(1)
        subBlockCursor = 0
        subBlockSumSq = 0.0
        rollingFilled = 0
        rollingCursor = 0
        appliedGain = gainProcessor.gain
        for (s in stage1) { s.x1 = 0.0; s.x2 = 0.0; s.y1 = 0.0; s.y2 = 0.0 }
        for (s in stage2) { s.x1 = 0.0; s.x2 = 0.0; s.y1 = 0.0; s.y2 = 0.0 }
        b1 = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285)
        a1 = doubleArrayOf(-1.69065929318241, 0.73248077421585)
        val k = tan(PI * 38.13506 / sampleRate)
        val q = 1.0 / sqrt(2.0)
        val d = 1.0 + k / q + k * k
        b2 = doubleArrayOf(1.0 / d, -2.0 / d, 1.0 / d)
        a2 = doubleArrayOf(2.0 * (k * k - 1.0) / d, (1.0 - k / q + k * k) / d)
        return inputAudioFormat
    }

    private fun biquadStep(state: BiquadState, b: DoubleArray, a: DoubleArray, xi: Double): Double {
        val yi = b[0] * xi + b[1] * state.x1 + b[2] * state.x2 - a[0] * state.y1 - a[1] * state.y2
        state.x2 = state.x1
        state.x1 = xi
        state.y2 = state.y1
        state.y1 = yi
        return yi
    }

    private fun kWeight(channel: Int, x: Double): Double {
        val s1 = biquadStep(stage1[channel], b1, a1, x)
        return biquadStep(stage2[channel], b2, a2, s1)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!enabled) {
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
            subBlockCursor++
            if (subBlockCursor >= subBlockSize) finalizeSubBlock()
        }
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }

    private fun finalizeSubBlock() {
        val meanSq = subBlockSumSq / (subBlockCursor * 2) // 2 channels
        rollingPower[rollingCursor] = meanSq
        rollingCursor = (rollingCursor + 1) % ROLLING_WINDOW_BLOCKS
        if (rollingFilled < ROLLING_WINDOW_BLOCKS) rollingFilled++
        subBlockSumSq = 0.0
        subBlockCursor = 0
        updateGain()
    }

    private fun updateGain() {
        if (rollingFilled < MIN_BLOCKS_BEFORE_ADJUST) return
        // Absolute gate: -70 LUFS - ignore near-silence (station ID gaps, intro silence) so it
        // never drags the rolling mean down and overcorrects.
        val absoluteThreshold = 10.0.pow((-70.0 + 0.691) / 10.0)
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
        val relativeThresholdLufs = (-0.691 + 10 * log10(ungatedMean)) - 10.0
        val relativeThreshold = 10.0.pow((relativeThresholdLufs + 0.691) / 10.0)
        var sum2 = 0.0
        var count2 = 0
        for (i in 0 until rollingFilled) {
            val p = rollingPower[i]
            if (p > relativeThreshold) { sum2 += p; count2++ }
        }
        val finalMean = if (count2 > 0) sum2 / count2 else ungatedMean
        val currentLufs = (-0.691 + 10 * log10(finalMean)).toFloat()
        // Tighter negative bound than a file-based measurement would use: a 30s rolling, noisier
        // estimate is less trustworthy than a full-track gated pass, so this is more conservative
        // about how hard it will pull a station down on the strength of one loud window.
        val targetGainDb = (targetLufs - currentLufs).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val targetGainLinear = 10f.pow(targetGainDb / 20f)
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
    }
}
