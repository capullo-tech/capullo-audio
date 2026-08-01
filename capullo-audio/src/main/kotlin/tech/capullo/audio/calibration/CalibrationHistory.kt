package tech.capullo.audio.calibration

/**
 * Append-only log of verified corrections, per sink, persisted by the host. The BT/web
 * sinks wander on a timescale longer than one capture, so a run can commit a correction
 * that is already ~25 ms stale; deciding a damping policy (skip re-corrections within a
 * sink's observed wander band, or median-of-N) needs a history to reason over. This just
 * accumulates that history — the policy is deliberately NOT built on the current handful
 * of data points. Fire-and-forget: an implementation must never throw into a run.
 */
interface CalibrationHistory {
    /** A correction that passed verification and was committed for [clientId]. */
    fun record(clientId: String, deltaMs: Int, newLatencyMs: Int)

    /**
     * The most recent committed deltas for [clientId], newest first, at most [limit]. Feeds the
     * damping check: one run resolves this residual to only about ±20 ms (rig-established), so a
     * correction that contradicts the sink's own recent history by more than that is more likely a
     * bad sample than a real change, and is deferred rather than applied. Returns empty when the
     * host keeps no history or the sink has none yet.
     */
    fun recent(clientId: String, limit: Int): List<Int> = emptyList()
}
