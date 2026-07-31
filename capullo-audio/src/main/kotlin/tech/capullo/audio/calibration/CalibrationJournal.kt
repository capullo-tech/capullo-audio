package tech.capullo.audio.calibration

/**
 * A client's pre-run state the calibrator may mutate: server-persisted latency AND volume
 * (percent + mute flag). Volume is part of the snapshot because the muted-pair fallback
 * silences the "other" clients via `Client.SetVolume`; without journaling it, a process death
 * mid-round would strand a whole room MUTED (snapserver persists client volume server-side,
 * across restarts) with no record of the originals.
 */
data class ClientSnapshot(
    val latencyMs: Int,
    val volumePercent: Int,
    val volumeMuted: Boolean,
)

/**
 * Durable record of the client state a calibration run is about to change, so a process death
 * mid-run (e.g. ColorOS killing the app when its own mic opens) can be undone. Both
 * `Client.SetLatency` and `Client.SetVolume` are server-persisted and survive server restarts,
 * so a run killed during a probe window would otherwise leave a client parked at
 * `latency − probe` (and, if it was a muted "other" in a pair round, muted) forever with no
 * record of the original.
 *
 * Contract: [save] the originals before the first mutating write; [clear] once the run has
 * finished (success or handled failure, both of which already restored/committed). If a journal
 * is still present at startup, the previous run died mid-flight — [SyncCalibrator.recover]
 * restores those originals and clears it. Restore is UNCONDITIONAL (blind restore of pre-run
 * state), never compare-and-restore: the most probable crash state is a transient probe value
 * (`latency − probe`) that matches no durable record, so any "restore only if unchanged" guard
 * would refuse to act on exactly the state it exists to fix. The host supplies the storage (it
 * owns the filesystem); the lib only defines the shape.
 */
interface CalibrationJournal {
    /** Persist the pre-run state of every client the run may touch. Returns false if the write
     *  did not durably land: the caller MUST NOT then mutate any client, since a subsequent
     *  crash would be unrecoverable. */
    fun save(originals: Map<String, ClientSnapshot>): Boolean

    /** The originals from an unfinished run, or null if none is journaled. */
    fun load(): Map<String, ClientSnapshot>?

    /** Drop the journal — the run finished and its writes are intentional. */
    fun clear()
}
