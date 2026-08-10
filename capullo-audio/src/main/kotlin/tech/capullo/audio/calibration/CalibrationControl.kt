package tech.capullo.audio.calibration

/**
 * The snapserver control surface the calibrator needs: set a client's latency, mute/unmute
 * it, and request a status refresh. [tech.capullo.audio.snapcast.SnapcastControlClient]
 * implements this in production; tests supply a fake so the orchestration (probe → attribute
 * → verify → commit/restore → fallback) can be driven deterministically without a server.
 */
interface CalibrationControl {
    /** Set a client's server-persisted latency; returns the request id (or null if unsent). */
    suspend fun sendSetLatency(clientId: String, latencyMs: Int): Int?

    /** Mute/unmute a client (used for pair-round isolation). */
    suspend fun sendSetVolume(clientId: String, muted: Boolean, percent: Int)

    /** Ask the server for a fresh status (so a read-back can observe the latest latencies). */
    suspend fun sendGetStatus()
}

/**
 * One acoustic measurement, abstracted so tests can script peaks instead of recording a
 * room. The production implementation records the mic and cross-correlates it against the
 * reference ring; a fake returns predetermined peaks.
 */
interface Measurer {
    /**
     * Salient peaks of a full capture, or null if inconclusive after retry.
     *
     * @param sources how many speakers are expected to be audible. With >0 the search covers each
     *  source region separately, so one loud speaker's own reflections cannot fill the whole list
     *  and hide the other speaker (see [Dsp.findPeaksPerSource]). 0 keeps the plain top-N search.
     */
    suspend fun measure(peakCount: Int, sources: Int = 0): List<Dsp.Peak>?

    /** Full-capture peaks plus each 6 s half's peaks (for the split-half gate), or null. */
    suspend fun measureHalves(
        peakCount: Int,
        sources: Int = 0,
    ): Triple<List<Dsp.Peak>, List<Dsp.Peak>, List<Dsp.Peak>>?

    /**
     * A reader BOUND TO THE CAPTURE [measure] just returned: given a lag, how loud the arrival there
     * was, as a multiple of that capture's own correlation floor. Null when levels aren't available.
     *
     * Bound per capture rather than offered as a "level of the latest capture" call, because a
     * baseline's levels are needed after several more captures have been taken — a latest-capture
     * accessor would silently answer from the wrong audio.
     *
     * Separate from the peaks on purpose. A peak's z is a DETECTION statistic from a top-N search,
     * so an absent speaker still yields one (the biggest noise lump) and those are order statistics
     * that sit close together whatever the real gains are — measured at a 0.93 ratio between two
     * pure-noise peaks, which is exactly what the balance was reading off the rig. Asking for a
     * KNOWN lag removes the selection: nothing arriving there reads ~1, the floor itself.
     */
    fun levelReader(): ((Double) -> Double)? = null
}
