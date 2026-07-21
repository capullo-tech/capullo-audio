package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Pins the lag/sign conventions of the calibration DSP with a synthetic two-speaker
 * scene: a noise "broadcast" is placed in a reference ring, and a fake mic capture
 * hears it twice at two different total delays. The estimator must recover both
 * delays, which is exactly what SyncCalibrator's probe/correction math builds on.
 */
class DelayMeasurementTest {

    private val fs = 48_000

    /** Music-ish reference: white noise low-passed by a running average. */
    private fun makeReference(seconds: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val x = FloatArray(seconds * fs)
        var acc = 0.0f
        for (i in x.indices) {
            acc = 0.9f * acc + 0.1f * (rnd.nextFloat() * 2f - 1f)
            x[i] = acc
        }
        return x
    }

    private fun scene(
        delayAMs: Int,
        delayBMs: Int,
        micSeconds: Int = 8,
        ringSeconds: Int = 20,
    ): Pair<ReferencePcmRing.Snapshot, MicCapture.Capture> {
        val ref = makeReference(ringSeconds, seed = 42)
        val now = 1_000_000_000_000L // arbitrary epoch

        // Ring snapshot: the full reference, last sample written "now".
        val snapshot = ReferencePcmRing.Snapshot(ref, now, fs)

        // Mic capture of the last micSeconds, ending "now": mic sample j (starting at
        // ring index m0) hears speaker k's copy of ring[m0 + j − delay_k].
        val micLen = micSeconds * fs
        val m0 = ref.size - micLen
        val micStartNanos = now - micLen.toLong() * 1_000_000_000L / fs
        val dA = delayAMs * fs / 1000
        val dB = delayBMs * fs / 1000
        val rnd = Random(7)
        val mic = FloatArray(micLen) { j ->
            val a = ref.getOrElse(m0 + j - dA) { 0f }
            val b = ref.getOrElse(m0 + j - dB) { 0f }
            0.8f * a + 0.5f * b + 0.02f * (rnd.nextFloat() * 2f - 1f)
        }
        return snapshot to MicCapture.Capture(mic, micStartNanos, fs)
    }

    @Test
    fun `recovers both speaker delays`() {
        val (snap, mic) = scene(delayAMs = 1200, delayBMs = 1250)
        val peaks = DelayMeasurement.estimateSpeakerDelays(snap, mic)
        assertTrue("expected >=2 salient peaks, got $peaks", peaks.size >= 2)
        val lags = peaks.sortedByDescending { it.z }.take(2).map { it.lagMs }.sorted()
        assertEquals(1200.0, lags[0], 2.0)
        assertEquals(1250.0, lags[1], 2.0)
    }

    @Test
    fun `peak spacing equals the sync offset regardless of clock alignment error`() {
        val (snap, mic) = scene(delayAMs = 1400, delayBMs = 1330)
        // Skew the mic clock by 300ms: absolute lags shift, spacing must not.
        val skewed = MicCapture.Capture(mic.pcm, mic.firstSampleNanos - 300_000_000L, fs)
        val peaks = DelayMeasurement.estimateSpeakerDelays(snap, skewed)
        val lags = peaks.sortedByDescending { it.z }.take(2).map { it.lagMs }.sorted()
        assertEquals(70.0, lags[1] - lags[0], 2.0)
    }

    @Test
    fun `aligned speakers merge into a single peak`() {
        val (snap, mic) = scene(delayAMs = 1300, delayBMs = 1300)
        val peaks = DelayMeasurement.estimateSpeakerDelays(snap, mic)
        val salient = peaks.filter { it.z >= 9.0 }
        assertTrue("aligned scene should have one salient peak, got $salient", salient.isNotEmpty())
        // No second salient peak more than 8ms away from the strongest.
        val main = salient.maxBy { it.z }
        assertTrue(salient.none { abs(it.lagMs - main.lagMs) > 8.0 })
    }

    @Test
    fun `fft roundtrip is identity`() {
        val rnd = Random(1)
        val re = DoubleArray(1024) { rnd.nextDouble() - 0.5 }
        val im = DoubleArray(1024)
        val orig = re.copyOf()
        Dsp.fft(re, im)
        Dsp.fft(re, im, inverse = true)
        for (i in re.indices) assertEquals(orig[i], re[i], 1e-9)
    }
}
