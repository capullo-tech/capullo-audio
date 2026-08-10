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

    /**
     * A REVERBERANT two-speaker room: each speaker arrives by its direct path and then again as
     * reflections spread over the following ~70 ms, which is what a real room does (rig-measured at
     * 50-80 ms). The near speaker is 12 dB louder at the mic, as when one client starts a balance
     * run at a low gain.
     *
     * Every other scene in this file is anechoic — one impulse per speaker — and that is exactly why
     * this defect survived every synthetic test: with no reflections a top-N search cannot spend its
     * budget on one source, so the bug is invisible. Reflections are the whole point of the scene.
     */
    private fun reverberantScene(
        nearMs: Int,
        nearAmp: Float,
        farMs: Int,
        farAmp: Float,
    ): Pair<ReferencePcmRing.Snapshot, MicCapture.Capture> {
        val speakers = mutableListOf<Pair<Int, Float>>()
        for ((delay, amp) in listOf(nearMs to nearAmp, farMs to farAmp)) {
            speakers += delay to amp
            // Reflections at +11..+68 ms, decaying — inside one SOURCE_SPREAD_MS window.
            var a = amp
            for (d in listOf(11, 23, 37, 52, 68)) {
                a *= 0.72f
                speakers += (delay + d) to a
            }
        }
        return sceneN(speakers)
    }

    @Test
    fun `the quiet speaker survives a reverberant room`() {
        // THE ITERATION-ZERO CASE. A balance run deliberately starts one client low; if the search
        // cannot see it, the run cannot correct it. 12 dB apart, both with full reflection tails.
        val (snap, mic) = reverberantScene(nearMs = 1200, nearAmp = 0.8f, farMs = 1600, farAmp = 0.2f)

        // What the pair path used to do: a plain top-N over the whole capture.
        val topN = DelayMeasurement.estimateSpeakerDelays(snap, mic, peakCount = SyncCalibrator.PAIR_PEAKS)
        val topNFar = topN.count { abs(it.lagMs - 1600.0) <= PeakAttribution.SOURCE_SPREAD_MS }

        // What it does now: the budget is spread across the expected number of sources.
        val perSource = DelayMeasurement.estimateSpeakerDelays(
            snap,
            mic,
            peakCount = SyncCalibrator.PAIR_PEAKS,
            sources = 2,
        )
        val near = perSource.filter { abs(it.lagMs - 1200.0) <= PeakAttribution.SOURCE_SPREAD_MS }
        val far = perSource.filter { abs(it.lagMs - 1600.0) <= PeakAttribution.SOURCE_SPREAD_MS }

        assertTrue(
            "the per-source search must find the near speaker, got ${perSource.map { it.lagMs }}",
            near.isNotEmpty(),
        )
        assertTrue(
            "the per-source search must find the QUIET speaker at 1600ms, got " +
                "${perSource.map { "%.0f(z=%.1f)".format(it.lagMs, it.z) }}",
            far.isNotEmpty(),
        )
        // The direct path, not a reflection of it: reflections arrive later, never earlier.
        assertEquals(1600.0, far.minOf { it.lagMs }, 8.0)
        assertEquals(1200.0, near.minOf { it.lagMs }, 8.0)
        // And it is a real improvement, not a no-op: state what the old search managed.
        assertTrue(
            "expected the per-source search to find at least as many views of the quiet speaker " +
                "as the top-N search (top-N found $topNFar)",
            far.size >= topNFar,
        )
    }

    @Test
    fun `an anechoic room is unchanged by the per-source search`() {
        // The per-source path must not disturb the scenes the sync half already works on.
        //
        // Compared by SALIENCE ORDER, not by sorting the lags: a correlation has sidelobes either
        // side of a real arrival (here at 1100 and 1150 ms, well clear of the z=9 floor), so the
        // earliest salient peak is not the first speaker. Sorting by lag and comparing element 0
        // compares whichever sidelobe each search happened to keep, which says nothing about
        // whether the speakers were found.
        val (snap, mic) = scene(delayAMs = 1200, delayBMs = 1250)
        fun strongestTwo(sources: Int) =
            DelayMeasurement.estimateSpeakerDelays(snap, mic, peakCount = 8, sources = sources)
                .filter { it.z >= 9.0 }
                .sortedByDescending { it.z }
                .take(2)
                .map { it.lagMs }
                .sorted()
        val plain = strongestTwo(0)
        val perSource = strongestTwo(2)
        assertEquals("both searches must recover the same two speakers", plain[0], perSource[0], 2.0)
        assertEquals("both searches must recover the same two speakers", plain[1], perSource[1], 2.0)
        assertEquals(1200.0, perSource[0], 2.0)
        assertEquals(1250.0, perSource[1], 2.0)
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
