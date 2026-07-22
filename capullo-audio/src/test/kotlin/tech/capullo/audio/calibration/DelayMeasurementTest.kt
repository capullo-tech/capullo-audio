package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** N-speaker scene: each (delayMs, amplitude) speaker plays the reference at its
     *  total delay. Mic sample j (starting at ring index m0) hears speaker k's copy of
     *  ring[m0 + j − delay_k]. */
    private fun sceneN(
        speakers: List<Pair<Int, Float>>,
        micSeconds: Int = 8,
        ringSeconds: Int = 20,
    ): Pair<ReferencePcmRing.Snapshot, MicCapture.Capture> {
        val ref = makeReference(ringSeconds, seed = 42)
        val now = 1_000_000_000_000L // arbitrary epoch

        // Ring snapshot: the full reference, last sample written "now".
        val snapshot = ReferencePcmRing.Snapshot(ref, now, fs)

        val micLen = micSeconds * fs
        val m0 = ref.size - micLen
        val micStartNanos = now - micLen.toLong() * 1_000_000_000L / fs
        val delays = speakers.map { it.first * fs / 1000 }
        val rnd = Random(7)
        val mic = FloatArray(micLen) { j ->
            var s = 0.02f * (rnd.nextFloat() * 2f - 1f)
            for (k in speakers.indices) {
                s += speakers[k].second * ref.getOrElse(m0 + j - delays[k]) { 0f }
            }
            s
        }
        return snapshot to MicCapture.Capture(mic, micStartNanos, fs)
    }

    private fun scene(
        delayAMs: Int,
        delayBMs: Int,
    ) = sceneN(listOf(delayAMs to 0.8f, delayBMs to 0.5f))

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
    fun `recovers three speaker delays`() {
        val (snap, mic) = sceneN(listOf(1200 to 0.8f, 1230 to 0.5f, 1310 to 0.4f))
        val peaks = DelayMeasurement.estimateSpeakerDelays(snap, mic, peakCount = 13)
        val salient = peaks.filter { it.z >= 9.0 }
        assertTrue("expected >=3 salient peaks, got $salient", salient.size >= 3)
        val lags = salient.sortedByDescending { it.z }.take(3).map { it.lagMs }.sorted()
        assertEquals(1200.0, lags[0], 2.0)
        assertEquals(1230.0, lags[1], 2.0)
        assertEquals(1310.0, lags[2], 2.0)
    }

    /** End-to-end v2 attribution: the same 3-speaker room re-rendered with EVERY speaker
     *  probe-shifted — reference +60, targets +90 and +150 — so the reference is
     *  identified by its own displacement (never by staying put). Matching must resolve
     *  all three in one pass. */
    @Test
    fun `probe shifts attribute reference and every target in one pass`() {
        val salient = { s: Pair<ReferencePcmRing.Snapshot, MicCapture.Capture> ->
            DelayMeasurement.estimateSpeakerDelays(s.first, s.second, peakCount = 13)
                .filter { it.z >= 9.0 }
        }
        val baseline = salient(sceneN(listOf(1200 to 0.8f, 1230 to 0.5f, 1310 to 0.4f)))
        val probed = salient(sceneN(listOf(1260 to 0.8f, 1320 to 0.5f, 1460 to 0.4f)))
        val res = PeakAttribution.attribute(baseline, probed, listOf(60, 90, 150), matchTolMs = 15.0)
        assertEquals(1200.0, res.matches[0]!!.baselineLagMs, 2.0) // reference, moved +60
        assertEquals(1260.0, res.matches[0]!!.probedLagMs, 2.0)
        assertEquals(1230.0, res.matches[1]!!.baselineLagMs, 2.0)
        assertEquals(1320.0, res.matches[1]!!.probedLagMs, 2.0)
        assertEquals(1310.0, res.matches[2]!!.baselineLagMs, 2.0)
        assertEquals(1460.0, res.matches[2]!!.probedLagMs, 2.0)
    }

    /** A speaker too quiet to clear the salience threshold must end unmatched (it degrades
     *  to a muted pair round) without disturbing the reference or the other target. */
    @Test
    fun `quiet target ends unmatched`() {
        val salient = { s: Pair<ReferencePcmRing.Snapshot, MicCapture.Capture> ->
            DelayMeasurement.estimateSpeakerDelays(s.first, s.second, peakCount = 13)
                .filter { it.z >= 9.0 }
        }
        val baseline = salient(sceneN(listOf(1200 to 0.8f, 1230 to 0.5f, 1310 to 0.02f)))
        val probed = salient(sceneN(listOf(1260 to 0.8f, 1320 to 0.5f, 1460 to 0.02f)))
        val res = PeakAttribution.attribute(baseline, probed, listOf(60, 90, 150), matchTolMs = 15.0)
        assertEquals(1200.0, res.matches[0]!!.baselineLagMs, 2.0)
        assertEquals(1230.0, res.matches[1]!!.baselineLagMs, 2.0)
        assertNull("quiet target 2 must be unmatched", res.matches[2])
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
