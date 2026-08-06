package tech.capullo.audio.calibration

/**
 * Pure math for one acoustic measurement: given a reference-ring snapshot and a mic
 * capture, return the per-speaker total delays (pipeline + acoustic) as correlation
 * peaks. Wall-clock alignment between the two captures only needs to be coarse — an
 * alignment error shifts every speaker's peak equally, and calibration only uses peak
 * spacing and probe-induced movement.
 */
object DelayMeasurement {

    /** Total delay search range. Snapcast end-to-end sits near bufferMs (~1 s); 4 s covers
     *  any buffer setting plus route latency with margin. */
    const val MAX_DELAY_MS = 4_000

    /**
     * One capture's peaks (whitened, for timing) plus a SEPARATE un-whitened correlation for level.
     *
     * Two arrays because the two jobs need opposite things. PHAT whitening flattens every bin to
     * unit magnitude, which sharpens timing and destroys amplitude; level needs the amplitude kept.
     * The peaks alone cannot answer "how loud" either: they are top-N maxima, so their heights stay
     * close together even when nothing is playing. Sync uses [peaks]; the balance uses [levelAt].
     */
    data class Measurement(
        val peaks: List<Dsp.Peak>,
        /** Un-whitened normalized correlation, rotated so index j == a delay of j samples at [fs].
         *  Only ratios BETWEEN CLIENTS WITHIN THIS CAPTURE are meaningful. */
        val levelCorrelation: DoubleArray,
        val fs: Int,
        val spanMs: Int,
    ) {
        /** This capture's correlation value at [lagMs]. Compare only against another client's value
         *  from the SAME capture; the ratio is the level ratio. */
        fun levelAt(lagMs: Double): Double = Dsp.levelAt(levelCorrelation, fs, lagMs)
    }

    /** Peaks only — the sync path's view. */
    fun estimateSpeakerDelays(
        ref: ReferencePcmRing.Snapshot,
        mic: MicCapture.Capture,
        peakCount: Int = 4,
    ): List<Dsp.Peak> = measure(ref, mic, peakCount)?.peaks ?: emptyList()

    /**
     * The two decimated arrays the correlation actually consumes, plus the indexing they need.
     *
     * Exposed as its own step so a diagnostic can DUMP exactly what the estimator sees. Every
     * estimator question so far has cost a rig session and a slow APK install, and offline models
     * have mispredicted the real rig twice — dumping these makes those questions answerable from a
     * file instead. Reproducing the windowing in an offline script would risk answering a subtly
     * different question, which is the whole failure mode being avoided here.
     */
    class Prepared(val refD: FloatArray, val micD: FloatArray, val pre: Int, val fs: Int)

    fun prepare(ref: ReferencePcmRing.Snapshot, mic: MicCapture.Capture): Prepared? {
        require(ref.sampleRate == mic.sampleRate) { "rate mismatch" }
        val fsFull = ref.sampleRate

        // Ring index (snapshot coordinates, oldest sample = 0) of the sample written at the
        // wall instant the mic capture started.
        val lastIdx = ref.pcm.size - 1L
        val nanosBack = ref.lastSampleNanos - mic.firstSampleNanos
        val iMicStart = lastIdx - (nanosBack * fsFull / 1_000_000_000L)

        val maxDelay = fsFull.toLong() * MAX_DELAY_MS / 1000
        val w0 = (iMicStart - maxDelay).coerceAtLeast(0L)
        val w1 = (iMicStart + mic.pcm.size).coerceAtMost(ref.pcm.size.toLong())
        if (w1 - w0 < fsFull) return null // ring didn't cover the capture
        val window = ref.pcm.copyOfRange(w0.toInt(), w1.toInt())

        // Decimate 4x (48k → 12k): sub-ms lag resolution at a 16x cheaper FFT.
        // How far the window reaches into the past before the mic's first sample, in
        // decimated samples: a speaker with total delay D peaks at circular lag (D − pre).
        return Prepared(
            refD = Dsp.decimateBy4(window),
            micD = Dsp.decimateBy4(mic.pcm),
            pre = ((iMicStart - w0) / 4).toInt(),
            fs = fsFull / 4,
        )
    }

    fun measure(
        ref: ReferencePcmRing.Snapshot,
        mic: MicCapture.Capture,
        peakCount: Int = 4,
    ): Measurement? {
        val p = prepare(ref, mic) ?: return null
        val refD = p.refD
        val micD = p.micD
        val pre = p.pre
        val fs = p.fs

        val r = Dsp.gccPhat(refD, micD) // whitened: timing
        // REGULARIZED DECONVOLUTION for level, not the un-whitened correlation.
        //
        // The un-whitened version returns the impulse response convolved with the PROGRAM's own
        // autocorrelation. On broadband material that is nearly a delta and the estimator looks
        // perfect, which is why every synthetic test passed. On sustained or tonal music it is wide,
        // so the value at one speaker's arrival is dominated by the other speaker's sidelobes and
        // stops responding to gain at all. Measured on tonal material at 90 ms separation: a true
        // 12 dB difference read as +0.06 dB, i.e. the two speakers reported identical. On hardware
        // the same defect turned a commanded 12 dB into 4.3 dB and produced the 2026-08-06 NO-GO.
        // Dividing by (|Ref|^2 + lambda) removes that autocorrelation and restores it to -13.3 dB.
        val rl = Dsp.crossCorrelateWiener(refD, micD) // deconvolved: level
        val n = r.size

        // Rotate so index j == total delay of j decimated samples: delayed[j] = r[(j − pre) mod n].
        val span = pre.coerceAtMost(n - 1)
        val delayed = DoubleArray(span) { j -> r[Math.floorMod(j - pre, n)] }
        // Same rotation for the level array so both are indexed by total delay.
        val delayedLevel = DoubleArray(span) { j -> rl[Math.floorMod(j - pre, rl.size)] }
        val spanMs = span * 1000 / fs
        return Measurement(
            peaks = Dsp.findPeaks(delayed, fs, loMs = 0, hiMs = spanMs, count = peakCount),
            levelCorrelation = delayedLevel,
            fs = fs,
            spanMs = spanMs,
        )
    }
}
