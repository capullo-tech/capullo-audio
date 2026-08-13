package tech.capullo.audio.calibration

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Offline replay of real rig evidence — Option 0 of HANDOFF-fable-balance-2026-08-10.md.
 * Not part of the shipped suite's contract: it consumes files under ~/capullo-tech and is
 * skipped when they are absent. Two questions, both answered from data already on disk:
 *
 *  1. In the 2026-08-10 20:04 failing calibration run, did [PeakAttribution.attribute]
 *     mis-assign the reference (the §4 greedy-consumption story), or did the reference
 *     never appear in the probed peak lists at all? Replayed with the EXACT peak lists
 *     the run logged.
 *
 *  2. On the real two-speaker probed PCM dumps, where does [Dsp.findPeaksPerSource]
 *     stage 1 place its two source anchors under the production PHAT path, and does the
 *     quiet source clear MIN_PEAK_Z there?
 */
class RigReplayTest {

    private val root = File("/home/neo/capullo-tech")
    private val report = StringBuilder()

    private fun say(s: String) {
        println(s)
        report.append(s).append('\n')
    }

    private fun flush(name: String) {
        File(root, name).writeText(report.toString())
    }

    // ---- 1. attribution replay of the failing run --------------------------------------

    private fun peaks(vararg lagZ: Pair<Double, Double>) = lagZ.map { Dsp.Peak(it.first, it.second) }

    @Test
    fun `failing run 20260810-2004 attribution replay`() {
        assumeTrue(root.isDirectory)
        val baselines = listOf(
            peaks(894.3 to 37.7, 899.5 to 35.8, 907.0 to 34.8, 927.6 to 31.0, 923.3 to 30.7),
            peaks(916.8 to 17.7, 931.4 to 15.1, 922.8 to 14.9, 939.9 to 14.6, 947.8 to 13.3, 967.5 to 11.4),
            peaks(890.4 to 28.5, 903.3 to 20.0, 918.6 to 19.9, 907.6 to 19.6, 928.1 to 19.6),
        )
        val probed = listOf(
            peaks(1375.1 to 20.5, 1403.1 to 16.2, 1391.1 to 15.7, 1408.3 to 15.3, 1446.8 to 13.9),
            peaks(
                1381.3 to 17.0, 1374.9 to 15.2, 1402.1 to 15.0, 1369.3 to 14.9, 1397.3 to 14.5,
                1412.8 to 13.7, 1392.8 to 11.1,
            ),
            peaks(1363.9 to 27.6, 1358.7 to 17.5, 1373.8 to 16.9, 1390.8 to 16.2, 1379.9 to 16.1, 1435.5 to 14.3),
        )
        val offsets = listOf(90, 470)
        val tol = 15.0
        say("=== attribution replay: failing run 08-10 20:04, offsets=$offsets tol=$tol ===")
        for ((bi, b) in baselines.withIndex()) for ((pi, p) in probed.withIndex()) {
            val a = PeakAttribution.attribute(b, p, offsets, tol)
            val bl = PeakAttribution.clusterLeaders(b).map { it.lagMs }
            val pl = PeakAttribution.clusterLeaders(p).map { it.lagMs }
            say("base$bi x probe$pi  leadersB=$bl leadersP=$pl")
            say(
                "  ref=${a.matches[0]?.let { "%.1f->%.1f".format(it.baselineLagMs, it.probedLagMs) } ?: "NULL"}" +
                    "  tgt=${a.matches[1]?.let { "%.1f->%.1f".format(it.baselineLagMs, it.probedLagMs) } ?: "NULL"}" +
                    "  drift=%.1f ghosts=${a.ghostLagsMs.map { g -> "%.1f".format(g) }}".format(a.driftMs),
            )
            // Was there ANY (baseline, probed) pair within tol of the reference's +90 shift,
            // at any plausible drift? Enumerate the raw shift errors so "no candidate" is a
            // printed fact rather than an inference.
            val refErrs = bl.flatMap { bb -> pl.map { pp -> pp - bb - offsets[0] } }
            say("  ref-shift residuals (p-b-90): " + refErrs.joinToString { "%.1f".format(it) })
        }
        flush("replay-attribution-20260810.txt")
    }

    // ---- 2. peak-search replay on real PCM dumps ---------------------------------------

    private fun readF32(f: File): FloatArray {
        val bytes = f.readBytes()
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun rotate(r: DoubleArray, pre: Int, span: Int) =
        DoubleArray(span) { j -> r[Math.floorMod(j - pre, r.size)] }

    /**
     * The known-answer test for the targeted rescue floor, on real hardware captures: at each
     * dump's TRUE quiet-source position (established by the per-source search / §5 of the
     * 2026-08-10 handoff) the ±15 ms window peak must clear RESCUE_MIN_Z = 6.0, and at the
     * expected position of a speaker that is genuinely absent (PFFM10 at 45 %, ~380 ms before
     * the loud blob) it must stay under it.
     */
    @Test
    fun `targeted window read separates real masked speakers from absent ones on real captures`() {
        val dumps = File(root, "dumps")
        assumeTrue(dumps.isDirectory)
        fun window(prefix: String, centerMs: Double): Double {
            val meta = File(dumps, "$prefix-meta.txt").readLines()
                .associate { l -> l.split('=', limit = 2).let { it[0] to it[1] } }
            val fs = meta.getValue("fs").toInt()
            val pre = meta.getValue("pre").toInt()
            val ref = readF32(File(dumps, "$prefix-ref.f32"))
            val mic = readF32(File(dumps, "$prefix-mic.f32"))
            val span = minOf(pre, DelayMeasurement.MAX_DELAY_MS * fs / 1000)
            val delayed = rotate(Dsp.gccPhat(ref, mic), pre, span)
            return Dsp.peakNear(delayed, fs, centerMs, 15.0)!!.z
        }
        val floor = 6.0 // RESCUE_MIN_Z (private in SyncCalibrator)
        for ((prefix, center) in listOf(
            "pcm-20260810-181036" to 1244.0,
            "pcm-20260810-193935" to 1139.4,
            "pcm-20260810-194202" to 1570.3,
        )) {
            val z = window(prefix, center)
            say("$prefix quiet source @%.1fms: window z=%.1f (floor $floor)".format(center, z))
            org.junit.Assert.assertTrue("$prefix: real masked speaker must clear the floor, z=$z", z >= floor)
        }
        val absent = window("pcm-20260810-181402", 1340.9)
        say("pcm-20260810-181402 absent speaker @1340.9ms: window z=%.1f (floor $floor)".format(absent))
        org.junit.Assert.assertTrue("absent speaker must stay under the floor, z=$absent", absent < floor)
        flush("replay-rescue-floor-20260810.txt")
    }

    /**
     * ANCHOR GUARD SWEEP. Stage 1 of [Dsp.findPeaksPerSource] separates its source anchors by
     * `spreadMs` (80). The rig-measured reflection tail of ONE source runs 84-127 ms at z≥6, so
     * the second anchor can land inside the loud speaker's own tail and the quiet source is never
     * searched — measured on-rig 2026-08-11 01:36, probe captures 2 and 3: all eight reported
     * peaks inside 1720-1832 ms while the reference sat unexamined at ~1330 ms (z 10.5-13.6 in
     * the same round's captures 1 and 4).
     *
     * Known answer per dump: the two anchors must straddle the TWO source regions, whose true
     * positions are established independently (probe offset 380 ms, plus §5 of the handoff).
     */
    @Test
    fun `anchor guard sweep - which separation puts one anchor on each source`() {
        val dumps = File(root, "dumps")
        assumeTrue(dumps.isDirectory)
        // prefix -> (loud region centre, quiet region centre) from the probe geometry; null quiet
        // where the second speaker is genuinely inaudible (45 % = -14 dB).
        val scenes = listOf(
            Triple("pcm-20260810-181036", 1714.0, 1244.0),
            Triple("pcm-20260810-193935", 1573.0, 1139.0),
            Triple("pcm-20260810-194202", 1148.0, 1570.0),
            Triple("pcm-20260811-013443", 1665.0, 1260.0),
        )
        for ((prefix, loud, quiet) in scenes) {
            val metaFile = File(dumps, "$prefix-meta.txt")
            if (!metaFile.exists()) continue
            val meta = metaFile.readLines().associate { l -> l.split('=', limit = 2).let { it[0] to it[1] } }
            val fs = meta.getValue("fs").toInt()
            val pre = meta.getValue("pre").toInt()
            val ref = readF32(File(dumps, "$prefix-ref.f32"))
            val mic = readF32(File(dumps, "$prefix-mic.f32"))
            val span = minOf(pre, DelayMeasurement.MAX_DELAY_MS * fs / 1000)
            val spanMs = span * 1000 / fs
            val delayed = rotate(Dsp.gccPhat(ref, mic), pre, span)
            say("")
            say("=== $prefix  true regions: loud≈%.0fms quiet≈%.0fms ===".format(loud, quiet))
            for (guard in listOf(80.0, 150.0)) {
                for (count in 2..5) {
                    val anchors = Dsp.findPeaks(delayed, fs, 0, spanMs, count = count, guardMs = guard)
                    val onLoud = anchors.any { abs(it.lagMs - loud) <= 90.0 }
                    val onQuiet = anchors.any { abs(it.lagMs - quiet) <= 90.0 }
                    say(
                        "  guard %3.0f n=%d -> %-52s %s".format(
                            guard,
                            count,
                            anchors.joinToString { "%.0f(%.1f)".format(it.lagMs, it.z) },
                            if (onLoud && onQuiet) "BOTH SOURCES" else if (onLoud) "loud only" else "??",
                        ),
                    )
                }
            }
        }
        flush("replay-anchorguard-20260811.txt")
    }

    /**
     * KNOWN-ANSWER regression for the anchor budget, on the capture that exposed it. In
     * `pcm-20260811-013443` the two speakers sit at ~1665 ms (loud) and ~1260 ms; the shipped
     * search spent its two anchor slots on the loud speaker and a 360 ms self-similarity ghost,
     * so the second speaker was never searched. The production call must now return peaks from
     * BOTH regions.
     */
    @Test
    fun `production peak search finds both speakers on the capture that starved the balance`() {
        val dumps = File(root, "dumps")
        val prefix = "pcm-20260811-013443"
        assumeTrue(File(dumps, "$prefix-ref.f32").exists())
        val meta = File(dumps, "$prefix-meta.txt").readLines()
            .associate { l -> l.split('=', limit = 2).let { it[0] to it[1] } }
        val fs = meta.getValue("fs").toInt()
        val pre = meta.getValue("pre").toInt()
        val ref = readF32(File(dumps, "$prefix-ref.f32"))
        val mic = readF32(File(dumps, "$prefix-mic.f32"))
        val span = minOf(pre, DelayMeasurement.MAX_DELAY_MS * fs / 1000)
        val delayed = rotate(Dsp.gccPhat(ref, mic), pre, span)
        val peaks = Dsp.findPeaksPerSource(delayed, fs, 0, span * 1000 / fs, sources = 2, perSource = 4)
        say("")
        say("=== $prefix production search ===")
        say(peaks.joinToString { "%.0fms(z=%.1f)".format(it.lagMs, it.z) })
        org.junit.Assert.assertTrue(
            "loud speaker region (~1665ms) must be represented, got $peaks",
            peaks.any { abs(it.lagMs - 1665.0) <= 90.0 },
        )
        org.junit.Assert.assertTrue(
            "quiet speaker region (~1260ms) must be represented, got $peaks",
            peaks.any { abs(it.lagMs - 1260.0) <= 90.0 },
        )
        flush("replay-anchorfix-20260811.txt")
    }

    @Test
    fun `dump replay - where do the two source anchors land`() {
        val dumps = File(root, "dumps")
        assumeTrue(dumps.isDirectory)
        val prefixes = listOf(
            "pcm-20260810-181036", // both 100%
            "pcm-20260810-193935", // both 100%
            "pcm-20260810-194202", // ONEPLUS at 51%
            "pcm-20260810-181402", // PFFM10 at 45%
        )
        for (prefix in prefixes) {
            val meta = File(dumps, "$prefix-meta.txt").takeIf { it.exists() }?.readLines()
                ?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                ?.toMap() ?: continue
            val fs = meta.getValue("fs").toInt()
            val pre = meta.getValue("pre").toInt()
            val ref = readF32(File(dumps, "$prefix-ref.f32"))
            val mic = readF32(File(dumps, "$prefix-mic.f32"))
            val span = minOf(pre, DelayMeasurement.MAX_DELAY_MS * fs / 1000)
            val spanMs = span * 1000 / fs
            val delayed = rotate(Dsp.gccPhat(ref, mic), pre, span)

            say("")
            say("=== $prefix  gains=${meta["gains"]} probe=${meta["probeMs"]}ms on ${meta["probeTarget"]} ===")
            val anchors = Dsp.findPeaks(delayed, fs, 0, spanMs, count = 2, guardMs = Dsp.SOURCE_SPREAD_MS)
            say("stage-1 anchors (guard 80ms): " + anchors.joinToString { "%.1fms(z=%.1f)".format(it.lagMs, it.z) })
            val prod = Dsp.findPeaksPerSource(delayed, fs, 0, spanMs, sources = 2, perSource = 4)
            say(
                "production peaks (perSource=4): " +
                    prod.joinToString { "%.1fms(z=%.1f%s)".format(it.lagMs, it.z, if (it.z < 9.0) " SUBFLOOR" else "") },
            )
            val fine = Dsp.findPeaks(delayed, fs, 0, spanMs, count = 16, guardMs = 4.0)
            say("fine top-16 (guard 4ms):      " + fine.joinToString { "%.1fms(z=%.1f)".format(it.lagMs, it.z) })
            // Targeted-window noise: the pair round knows WHERE the reference must sit
            // (baseline + offset), so what matters is the max z inside a ±15ms window at a
            // WRONG location — the false-alarm scale for a targeted search. Sample windows
            // every 10ms, excluding ±250ms around both source anchors.
            val noiseFloor = run {
                val lo0 = 0
                val hi0 = span
                val median = (lo0 until hi0).map { abs(delayed[it]) }.sorted()[(hi0 - lo0) / 2] + 1e-18
                val winMax = mutableListOf<Double>()
                var c = 200.0
                while (c < spanMs - 200.0) {
                    if (anchors.none { abs(c - it.lagMs) <= 250.0 }) {
                        val a = ((c - 15.0) * fs / 1000).toInt().coerceIn(0, span - 1)
                        val b = ((c + 15.0) * fs / 1000).toInt().coerceIn(a + 1, span)
                        winMax += (a until b).maxOf { delayed[it] } / median
                    }
                    c += 10.0
                }
                winMax.sorted()
            }
            say(
                "targeted ±15ms noise windows (n=${noiseFloor.size}): " +
                    "p50=%.1f p95=%.1f p99=%.1f max=%.1f".format(
                        noiseFloor[noiseFloor.size / 2],
                        noiseFloor[(noiseFloor.size * 95) / 100],
                        noiseFloor[(noiseFloor.size * 99) / 100],
                        noiseFloor.last(),
                    ),
            )
            // Same picture under WIENER (what the level path reads): does the quiet source
            // clear the floor there with a wider gap to the noise windows?
            val wDelayed = rotate(Dsp.crossCorrelateWiener(ref, mic, sampleRate = fs), pre, span)
            val wAnchors = Dsp.findPeaks(wDelayed, fs, 0, spanMs, count = 2, guardMs = Dsp.SOURCE_SPREAD_MS)
            say("WIENER anchors (guard 80ms):  " + wAnchors.joinToString { "%.1fms(z=%.1f)".format(it.lagMs, it.z) })
            val wNoise = run {
                val median = (0 until span).map { abs(wDelayed[it]) }.sorted()[span / 2] + 1e-18
                val winMax = mutableListOf<Double>()
                var c = 200.0
                while (c < spanMs - 200.0) {
                    if (wAnchors.none { abs(c - it.lagMs) <= 250.0 }) {
                        val a = ((c - 15.0) * fs / 1000).toInt().coerceIn(0, span - 1)
                        val b = ((c + 15.0) * fs / 1000).toInt().coerceIn(a + 1, span)
                        winMax += (a until b).maxOf { wDelayed[it] } / median
                    }
                    c += 10.0
                }
                winMax.sorted()
            }
            say(
                "WIENER ±15ms noise windows (n=${wNoise.size}): p50=%.1f p95=%.1f p99=%.1f max=%.1f".format(
                    wNoise[wNoise.size / 2],
                    wNoise[(wNoise.size * 95) / 100],
                    wNoise[(wNoise.size * 99) / 100],
                    wNoise.last(),
                ),
            )
            if (prefix == "pcm-20260810-181402") {
                val a = (1290 * fs / 1000)
                val b = (1490 * fs / 1000)
                val median = (0 until span).map { abs(wDelayed[it]) }.sorted()[span / 2] + 1e-18
                val best = (a until b).maxByOrNull { wDelayed[it] }!!
                say("WIENER PFFM10@45%% hunt 1290-1490ms: max %.1fms z=%.1f".format(best * 1000.0 / fs, wDelayed[best] / median))
            }
            // For the PFFM10-quiet dump: is the 45% speaker detectable ~380ms before the blob?
            if (prefix == "pcm-20260810-181402") {
                val a = (1290 * fs / 1000)
                val b = (1490 * fs / 1000)
                val median = (0 until span).map { abs(delayed[it]) }.sorted()[span / 2] + 1e-18
                val best = (a until b).maxByOrNull { delayed[it] }!!
                say("PFFM10@45%% hunt in 1290-1490ms: max %.1fms z=%.1f".format(best * 1000.0 / fs, delayed[best] / median))
            }
            // Tail extent of the strongest arrival: last fine peak within z>=6 of the blob.
            val top = fine.first()
            val blob = fine.filter { abs(it.lagMs - top.lagMs) <= 250.0 && it.z >= 6.0 }
            if (blob.isNotEmpty()) {
                val lo = blob.minOf { it.lagMs }
                val hi = blob.maxOf { it.lagMs }
                say("blob around %.1fms: %.1f..%.1fms (spread %.1fms at z>=6)".format(top.lagMs, lo, hi, hi - lo))
            }
        }
        flush("replay-dumps-20260810.txt")
    }
}
