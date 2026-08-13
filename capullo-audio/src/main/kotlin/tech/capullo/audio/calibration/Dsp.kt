package tech.capullo.audio.calibration

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSP core for acoustic sync calibration: GCC-PHAT cross-correlation between the
 * broadcast reference PCM and a microphone capture. Each audible speaker playing the
 * stream produces one correlation peak at its total (pipeline + acoustic) delay; the
 * SPACING between two speakers' peaks is their sync offset, independent of any clock
 * alignment between the two captures.
 */
object Dsp {

    data class Peak(val lagMs: Double, val z: Double)

    /** In-place iterative radix-2 FFT. Arrays are (re, im), length must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "FFT size must be a power of two" }
        // bit reversal
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (j >= m && m > 0) { j -= m; m = m shr 1 }
            j += m
        }
        var len = 2
        val sign = if (inverse) 1.0 else -1.0
        while (len <= n) {
            val ang = sign * 2.0 * Math.PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]; val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe; im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe; im[i + k + len / 2] = aIm - bIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (k in 0 until n) { re[k] /= n; im[k] /= n }
        }
    }

    /**
     * Two cascaded half-band stages ([1,2,1]/4 smoothing + take every 2nd sample) = 4x
     * decimation. Crude anti-aliasing is fine here: correlation integrates over seconds,
     * and aliased HF content decorrelates into the noise floor.
     */
    fun decimateBy4(x: FloatArray): FloatArray {
        var cur = x
        repeat(2) {
            val out = FloatArray(cur.size / 2)
            for (i in out.indices) {
                val c = i * 2
                val a = if (c > 0) cur[c - 1] else cur[c]
                val b = cur[c]
                val d = if (c + 1 < cur.size) cur[c + 1] else cur[c]
                out[i] = 0.25f * a + 0.5f * b + 0.25f * d
            }
            cur = out
        }
        return cur
    }

    /**
     * GCC-PHAT circular cross-correlation of [mic] against [ref]. Returns the full
     * circular array r (power-of-two length): a peak at index k means "mic matches ref
     * delayed by k samples", with negative lags wrapped to n−|lag|. PHAT whitening
     * (unit magnitude per bin) sharpens the peaks and removes the music's spectral
     * coloration. The exact lag convention is pinned by DspTest.
     */
    fun gccPhat(ref: FloatArray, mic: FloatArray): DoubleArray = crossCorrelate(ref, mic, phat = true)

    /**
     * Un-whitened normalized cross-correlation of [mic] against [ref], same lag convention as
     * [gccPhat]. Divided by sqrt(energy(ref) * energy(mic)), so a value at a lag is the fraction of
     * the mic signal explained by the reference arriving there.
     *
     * **SUPERSEDED for level measurement by [crossCorrelateWiener], and kept only as the comparison
     * baseline its tests measure against.** It is not wired into [DelayMeasurement] any more. The
     * defect is not subtle: this returns the impulse response CONVOLVED WITH THE PROGRAM'S OWN
     * AUTOCORRELATION, and on sustained or tonal music that autocorrelation is hundreds of
     * milliseconds wide. Measured on tonal material with two speakers 90 ms apart, a true 12 dB
     * difference reads as **+0.06 dB** — the two speakers reported identical, whatever their gains.
     * On hardware it turned a commanded 12 dB into 4.3 dB and produced the 2026-08-06 NO-GO.
     *
     * It looks excellent on broadband material, where the autocorrelation is nearly a delta: a true
     * 0.50/0.25/0.10 comes back as 0.498/0.250/0.101 (PHAT's z-ratio gives 0.281/0.130/0.052). Every
     * synthetic validation used exactly that kind of signal, which is why the defect survived three
     * attempts to find it. Do not re-adopt this on the strength of a broadband test.
     *
     * ONLY RATIOS BETWEEN CLIENTS IN ONE CAPTURE ARE MEANINGFUL — true of both estimators. On a solo
     * capture the value is nearly independent of gain (0.996 at full scale, 0.991 at a quarter)
     * because the one speaker present IS the mic signal, so the normalization divides out the very
     * thing being measured. Two speakers in one capture share that normalization, so it cancels in
     * their ratio.
     */
    fun crossCorrelateLevel(ref: FloatArray, mic: FloatArray): DoubleArray {
        val r = crossCorrelate(ref, mic, phat = false)
        var eRef = 0.0
        var eMic = 0.0
        for (v in ref) eRef += v.toDouble() * v
        for (v in mic) eMic += v.toDouble() * v
        val norm = sqrt(eRef * eMic) + 1e-18
        for (i in r.indices) r[i] /= norm
        return r
    }

    /**
     * REGULARIZED DECONVOLUTION of [mic] by [ref] — `R / (|Ref|^2 + lambda)` with
     * `lambda = eps * mean(|Ref|^2)`. Same lag convention as [gccPhat].
     *
     * The middle ground between the two estimators above, and the answer to why neither works.
     * PHAT divides by `|R|`, forcing every bin to unit magnitude: sharp timing, amplitude destroyed.
     * [crossCorrelateLevel] divides by nothing: amplitude kept, but the result is the true impulse
     * response CONVOLVED WITH THE PROGRAM'S OWN AUTOCORRELATION. On broadband material that
     * autocorrelation is nearly a delta and the smearing is invisible — which is why every synthetic
     * validation passed. On sustained or tonal music it is wide, so the value read at one speaker's
     * arrival is dominated by the OTHER speaker's autocorrelation sidelobes, which do not change when
     * the first speaker's gain changes. That is the measured cause of the balance's failure: on tonal
     * material a commanded 6 dB move shifted the reported ratio by 0.06 dB
     * (`LevelPositionBiasTest`), and on hardware a commanded 12 dB move produced 4.3 dB.
     *
     * Dividing by `|Ref|^2` removes exactly that autocorrelation while keeping amplitude, since
     * `R/|Ref|^2` is the impulse response itself rather than a smeared copy. The `lambda` term is
     * what stops that division exploding in bins where the program has no energy — the whole reason
     * plain deconvolution is unusable on real signals. [eps] sets how much noise is traded for how
     * much sharpening: 0 is exact deconvolution (unstable), large eps degenerates toward the
     * un-whitened correlation.
     *
     * NOT NORMALIZED, unlike [crossCorrelateLevel], and deliberately so: the deconvolution already
     * has the units of the impulse response, so a peak's height IS the arrival's amplitude rather
     * than something proportional to it through a per-capture constant. Ratios between clients in one
     * capture — the only comparison the balance ever makes — are unaffected either way.
     *
     * [sampleRate] is REQUIRED rather than defaulted because [hpHz] is meaningless without it, and a
     * cutoff silently applied at the wrong rate is exactly the kind of unit error this effort has
     * already lost a measurement to.
     */
    fun crossCorrelateWiener(
        ref: FloatArray,
        mic: FloatArray,
        sampleRate: Int,
        eps: Double = WIENER_EPS,
        hpHz: Double = WIENER_HP_HZ,
    ): DoubleArray = crossCorrelate(ref, mic, phat = false, wienerEps = eps, hpBins = hpHz, rate = sampleRate)

    /**
     * Regularization strength for [crossCorrelateWiener], as a fraction of mean band power.
     *
     * 0.01, chosen from the measured sweep in `WienerLevelTest` rather than from the literature's
     * usual hand-wave, and never tuned against rig data. Sensitivity to a commanded -6/-12 dB change,
     * where the raw estimator's failure on tonal material is the whole reason this exists:
     *
     * ```
     * program     raw            eps=0.01        eps=0.1        eps=1.0
     * tonal       -0.06 / -0.11  -6.23 / -12.68  -4.42 / -8.48  -1.79 / -3.25
     * looped      -7.89 / -17.66 -5.97 / -11.91  -5.99 / -11.96 -6.01 / -12.01
     * broadband   -6.46 / -13.20 -6.10 / -12.26  -6.10 / -12.26 -6.08 / -12.23
     * ```
     *
     * RE-MEASURED after [WIENER_HP_HZ] was introduced, because the cutoff is now inside every one of
     * these numbers. Only `looped` moved materially (-5.85/-11.57 to -5.97/-11.91, i.e. TOWARD
     * truth); everything else shifted under 0.1 dB and no conclusion below changes.
     *
     * Two things that decide the value. Going LOWER over-sharpens (0.001 gives tonal -6.62/-13.60,
     * overshooting) because too little regularization amplifies bins where the program has no energy.
     * Going HIGHER degenerates back toward the un-whitened estimator, and the tonal column shows that
     * happening smoothly from 0.03 onward. 0.01 also beats raw on broadband and fixes the looped
     * case's over-sensitivity, so it is not a tonal-only patch.
     */
    const val WIENER_EPS = 0.01

    /**
     * Bins below this are DROPPED from the deconvolution, not merely regularized. Measured on a real
     * capture, and the difference between a profile that has arrivals in it and one that does not.
     *
     * The division is by `|Ref|^2 + lambda`. Below roughly 20 Hz a music program has essentially no
     * energy, so the denominator is LAMBDA ALONE and the bin passes through as `R/lambda` — the
     * regularizer stops the division exploding but does not stop it AMPLIFYING. What it amplifies is
     * not music: mic DC wander, AGC and rumble, none of it correlated with any speaker. The result is
     * a slow baseline that swamps the arrivals.
     *
     * On `dumps/pcm-20260806-180313` (ambient program, two speakers ~1187 ms):
     *
     * ```
     * cutoff   mean over 1140-1610 ms    top peaks reported
     * none     -2.502e-04                1056.0, 1082.6, 1040.3   <- the baseline, not arrivals
     * 20 Hz    +5.4e-08                  1178.0, 1227.6, 1194.3
     * 50 Hz    -9.3e-09                  1178.0, 1194.3, 1227.6
     * 100 Hz   -3.7e-08                  1178.0, 1194.3, 1227.6
     * ```
     *
     * Uncut, the profile is NEGATIVE across a 470 ms span — which an impulse response cannot be — and
     * the arrivals it reports are the least-negative points on that baseline. `levelAt` reads a max
     * over +/-3 ms, so the offset survives the readout and is added to BOTH clients, inflating the
     * weaker one toward the stronger. That is a live candidate for the 20 dB spread two
     * identical-gain hardware captures produced on 2026-08-06.
     *
     * NOT A TUNED PARAMETER: 20, 50 and 100 Hz give the same peaks to three significant figures. The
     * value only has to sit above the program's noise floor and below the speakers' usable band; a BT
     * speaker produces nothing usable below 50 Hz anyway.
     */
    const val WIENER_HP_HZ = 50.0

    /** Shared FFT cross-correlation core. [phat] selects whitened (timing); [wienerEps] non-null
     *  selects regularized deconvolution (level); neither selects raw (level, smeared).
     *  [hpBins]/[rate] drop the low bins for the Wiener path only — see [WIENER_HP_HZ]. */
    private fun crossCorrelate(
        ref: FloatArray,
        mic: FloatArray,
        phat: Boolean,
        wienerEps: Double? = null,
        hpBins: Double = 0.0,
        rate: Int = 0,
    ): DoubleArray {
        var n = 1
        while (n < ref.size + mic.size) n = n shl 1
        val aRe = DoubleArray(n); val aIm = DoubleArray(n)
        val bRe = DoubleArray(n); val bIm = DoubleArray(n)
        for (i in ref.indices) aRe[i] = ref[i].toDouble()
        for (i in mic.indices) bRe[i] = mic[i].toDouble()
        fft(aRe, aIm); fft(bRe, bIm)
        // The regularizer is a fraction of the program's MEAN band power, so it adapts to the
        // material and to the overall level instead of being an absolute floor that means different
        // things on quiet and loud passages. Computed before the loop below overwrites the reference
        // spectrum in place.
        var lambda = 0.0
        if (wienerEps != null) {
            var sum = 0.0
            for (k in 0 until n) sum += aRe[k] * aRe[k] + aIm[k] * aIm[k]
            lambda = wienerEps * sum / n
        }
        // Bin k and bin n-k are the same frequency and must be dropped together, or the inverse
        // transform is no longer real.
        val kCut = if (wienerEps != null && rate > 0) (hpBins * n / rate).toInt() else 0
        // R = MIC * conj(REF), then whiten (PHAT), deconvolve (Wiener), or leave raw.
        for (k in 0 until n) {
            if (kCut > 0 && minOf(k, n - k) < kCut) {
                aRe[k] = 0.0
                aIm[k] = 0.0
                continue
            }
            val rRe = bRe[k] * aRe[k] + bIm[k] * aIm[k]
            val rIm = bIm[k] * aRe[k] - bRe[k] * aIm[k]
            if (phat) {
                val mag = sqrt(rRe * rRe + rIm * rIm) + 1e-12
                aRe[k] = rRe / mag
                aIm[k] = rIm / mag
            } else if (wienerEps != null) {
                val den = aRe[k] * aRe[k] + aIm[k] * aIm[k] + lambda + 1e-18
                aRe[k] = rRe / den
                aIm[k] = rIm / den
            } else {
                aRe[k] = rRe
                aIm[k] = rIm
            }
        }
        fft(aRe, aIm, inverse = true)
        return aRe
    }

    /**
     * The correlation value at [lagMs], read out of a [crossCorrelateLevel] array.
     *
     * Two clients' values from the SAME capture are comparable, and their ratio is the level ratio;
     * values from different captures are not comparable (see [crossCorrelateLevel]).
     *
     * Never read a level off a peak found by [findPeaks]. That is a top-N search, so when a speaker
     * is absent or below the floor it still returns something — the largest lumps of noise — and
     * those are order statistics of one distribution, hence always close together. Measured: two
     * pure-noise peaks sit at a ratio of 0.93 on average, which is exactly what the rig reported
     * between clients and exactly what it reported for a speaker that was inaudible.
     *
     * Takes the max over ±[halfWindowMs] so a lag a fraction of a bin off still finds its own peak.
     */
    fun levelAt(
        r: DoubleArray,
        fs: Int,
        lagMs: Double,
        halfWindowMs: Double = 3.0,
    ): Double {
        val center = (lagMs * fs / 1000.0).toInt()
        val half = (fs * halfWindowMs / 1000.0).toInt().coerceAtLeast(1)
        var peak = Double.NEGATIVE_INFINITY
        for (i in (center - half).coerceAtLeast(0) until (center + half + 1).coerceAtMost(r.size)) {
            if (r[i] > peak) peak = r[i]
        }
        return if (peak == Double.NEGATIVE_INFINITY) 0.0 else peak
    }

    /**
     * The strongest sample within ±[tolMs] of [centerMs], z-scored against the median |value|
     * of the WHOLE array — the same floor every blind search uses.
     *
     * This is the TARGETED detector for a source whose position is already pinned by geometry
     * (a probed reference must sit at baseline + its own offset). A blind search cannot find it:
     * a PHAT peak carries only that source's SHARE of the capture's energy, so a speaker that
     * measures z≈12 solo reads z≈6-9 the moment a sibling plays (rig-measured 2026-08-10:
     * 6.5/9.0/8.2 across three two-speaker dumps against a MIN_PEAK_Z of 9), and the anchor
     * guard of [findPeaksPerSource] can land on the loud speaker's reflection tail instead
     * (rig-measured tail spread 84-127 ms against an 80 ms guard). At a KNOWN lag both traps
     * vanish: the ±15 ms noise-window maxima on the same dumps read p95 5.1-6.0, so a real
     * arrival at 6.5+ stands clear where a whole-range threshold cannot separate anything.
     *
     * Returns the peak WHATEVER its z — thresholding is the caller's decision, made against
     * [SyncCalibrator] rescue gates, not here.
     */
    fun peakNear(r: DoubleArray, fs: Int, centerMs: Double, tolMs: Double): Peak? {
        if (r.isEmpty()) return null
        val lo = ((centerMs - tolMs) * fs / 1000.0).toInt().coerceIn(0, r.size - 1)
        val hi = ((centerMs + tolMs) * fs / 1000.0).toInt().coerceIn(lo + 1, r.size)
        var best = lo
        for (i in lo until hi) if (r[i] > r[best]) best = i
        val noise = r.map { abs(it) }.sorted()[r.size / 2] + 1e-18
        return Peak(best * 1000.0 / fs, r[best] / noise)
    }

    /** How far one source's arrivals spread: direct path plus the room's reflections of it.
     *  Rig-measured at 50-80 ms indoors. Mirrors [PeakAttribution.SOURCE_SPREAD_MS], kept here so
     *  the DSP layer does not depend on the matching layer. */
    const val SOURCE_SPREAD_MS = 80.0

    /** Minimum separation between the SOURCE ANCHORS of [findPeaksPerSource]. Wider than
     *  [SOURCE_SPREAD_MS] because the measured tail of one source (84-127 ms at z≥6 across four
     *  rig dumps, 2026-08-10) exceeds that window, and an anchor landing in a tail spends a source
     *  slot on a speaker already found. Two probed speakers are 380 ms apart by design
     *  ([SyncCalibrator.LEVEL_PROBE_SET_MS]), so this cannot merge them; two UNPROBED speakers
     *  closer than this share one anchor, and the stage-2 window then covers both. */
    const val ANCHOR_GUARD_MS = 150.0

    /** Extra anchor slots beyond the expected source count. Music self-similarity peaks and noise
     *  lumps compete for slots and can out-rank a real but quieter speaker (rig 2026-08-11: a
     *  360 ms ghost at z 8.2 displaced the real second speaker at z 7.4). */
    const val ANCHOR_HEADROOM = 1

    /**
     * Peaks covering UP TO [sources] distinct sources, [perSource] peaks from each.
     *
     * **A plain top-N search cannot answer "where are the speakers".** A source arrives as a cluster
     * — direct path plus reflections, spread 50-80 ms indoors — so with a few-ms guard the loudest
     * speaker's own cluster supplies peak after peak and a top-N list is N views of ONE speaker.
     * Measured on `dumps/pcm-20260806-195004`: the top SIX peaks span 1565-1657 ms and every one is
     * the same source; the same holds in every probed capture of `sweep-124101.log` and
     * `sweep-225432.log`. Clustering downstream ([PeakAttribution.clusterLeaders]) cannot rescue it,
     * because it merges peaks that were REPORTED and cannot recover a speaker that never made the
     * list. That is why the quiet speaker vanishes at iteration zero of a balance run.
     *
     * Two stages rather than simply widening the guard, because the two jobs conflict. A wide guard
     * would return one peak per source but that peak would be the cluster's LOUDEST, which is often
     * a reflection rather than the direct path — precisely the later-biased lag
     * [PeakAttribution.clusterLeaders] exists to avoid. So: locate source regions with a
     * source-width guard, then search inside each region with the fine guard, giving both coverage
     * across sources and the direct path within one.
     *
     * The result is still ordered by salience, so callers that just want "the strongest peaks" are
     * unaffected in the single-source case.
     */
    fun findPeaksPerSource(
        r: DoubleArray,
        fs: Int,
        loMs: Int,
        hiMs: Int,
        sources: Int,
        perSource: Int = 4,
        guardMs: Double = 4.0,
        spreadMs: Double = SOURCE_SPREAD_MS,
        anchorGuardMs: Double = ANCHOR_GUARD_MS,
        anchorHeadroom: Int = ANCHOR_HEADROOM,
    ): List<Peak> {
        // Stage 1: locate source REGIONS. Two things this stage must survive, both rig-measured:
        //
        // - A source's own reflection tail must not take a second anchor. Tails run 84-127 ms at
        //   z≥6 on this rig, so anchors are separated by [ANCHOR_GUARD_MS], not by the narrower
        //   stage-2 window [spreadMs] (which stays at one source's width, its own job).
        // - SPURIOUS peaks compete for anchor slots, and asking for exactly `sources` anchors
        //   assumes the top-N peaks ARE the speakers — the same assumption that made a plain
        //   top-N search fail. Measured on `dumps/pcm-20260811-013443`: the top two anchors are
        //   the loud speaker (z 18.0) and a music self-similarity peak at 360 ms (z 8.2), while
        //   the real second speaker at 1260 ms (z 7.4) gets no anchor and is never searched — the
        //   defect that starved the 01:36 balance run of half its level captures. With one slot
        //   of headroom it is found on every capture on file.
        //
        // Extra anchors cost peaks in the returned list, which the caller's salience filter and
        // the matcher's gates already handle; a missed source cannot be recovered downstream at
        // all.
        val anchors = findPeaks(
            r,
            fs,
            loMs,
            hiMs,
            count = sources + anchorHeadroom,
            guardMs = anchorGuardMs,
        )
        // Stage 2: the fine structure around each anchor, which is where the direct path is.
        //
        // z MUST STAY SCORED AGAINST THE WHOLE RANGE. `findPeaks` divides by the median |value| of
        // the range it is given, so calling it on a narrow window renormalises the floor against
        // that window's own contents — which are dominated by the very arrival being measured. On
        // `dumps/pcm-20260806-195004` that alone dropped the loud speaker's z from 16.6 to 6.9, i.e.
        // below the detection floor, and would have made this fix look like it deleted both
        // speakers. Compute the noise once, over the full range, and z-score against it.
        val lo0 = (fs.toLong() * loMs / 1000).toInt().coerceIn(0, r.size - 1)
        val hi0 = (fs.toLong() * hiMs / 1000).toInt().coerceIn(lo0 + 1, r.size)
        val fullNoise = (lo0 until hi0).map { abs(r[it]) }.sorted()[(hi0 - lo0) / 2] + 1e-18

        val out = LinkedHashMap<Double, Peak>() // by lag, so overlapping regions cannot double-count
        for (a in anchors) {
            val lo = (a.lagMs - spreadMs).toInt().coerceAtLeast(loMs)
            val hi = (a.lagMs + spreadMs).toInt().coerceAtMost(hiMs)
            if (hi <= lo) continue
            for (p in findPeaks(r, fs, lo, hi, count = perSource, guardMs = guardMs)) {
                // Re-score against the full-range floor: recover the raw value from the window's
                // own normalisation, then divide by the noise every other caller uses.
                val raw = levelAt(r, fs, p.lagMs, halfWindowMs = 0.5)
                out.putIfAbsent(p.lagMs, Peak(p.lagMs, raw / fullNoise))
            }
        }
        return out.values.sortedByDescending { it.z }
    }

    /**
     * Top-[count] peaks of [r] in lag range [loMs]..[hiMs], each z-scored against the
     * median absolute value of that range and separated by at least [guardMs].
     *
     * Returns peaks, NOT sources: with the default guard several of these can be the same speaker's
     * direct path and its reflections. Use [findPeaksPerSource] when the question is "where are the
     * speakers", which is what every calibration caller actually wants.
     *
     * The z here is a DETECTION statistic — "is this lump distinguishable from the floor" — and is
     * used for exactly that plus ranking. It is NOT a level: see [levelAt] for why reading it as one
     * silently reports every speaker as equally loud.
     */
    fun findPeaks(
        r: DoubleArray,
        fs: Int,
        loMs: Int,
        hiMs: Int,
        count: Int = 4,
        guardMs: Double = 4.0,
    ): List<Peak> {
        val lo = (fs.toLong() * loMs / 1000).toInt().coerceIn(0, r.size - 1)
        val hi = (fs.toLong() * hiMs / 1000).toInt().coerceIn(lo + 1, r.size)
        val seg = DoubleArray(hi - lo) { r[lo + it] }
        val sorted = seg.map { abs(it) }.sorted()
        val noise = sorted[sorted.size / 2] + 1e-18
        val guard = (fs * guardMs / 1000.0).toInt().coerceAtLeast(1)
        val peaks = mutableListOf<Peak>()
        val work = seg.copyOf()
        repeat(count) {
            var best = 0
            for (i in work.indices) if (work[i] > work[best]) best = i
            if (work[best] == Double.NEGATIVE_INFINITY) return@repeat
            peaks += Peak((lo + best) * 1000.0 / fs, work[best] / noise)
            for (i in (best - guard).coerceAtLeast(0) until (best + guard).coerceAtMost(work.size)) {
                work[i] = Double.NEGATIVE_INFINITY
            }
        }
        return peaks
    }
}
