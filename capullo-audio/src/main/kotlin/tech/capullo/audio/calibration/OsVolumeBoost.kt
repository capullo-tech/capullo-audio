package tech.capullo.audio.calibration

import kotlin.math.roundToInt

/**
 * The device's own media-volume control (Android `AudioManager` / `STREAM_MUSIC` in production).
 * Abstracted so the boost state machine below is unit-testable off-device.
 */
interface DeviceVolume {
    /** Highest valid index for the media stream. Device-specific: 15 on most phones, but OEM
     *  builds vary wildly (160 on the ColorOS rig device), so targets are expressed in percent
     *  and converted here rather than hard-coded anywhere. */
    val maxIndex: Int
    fun current(): Int
    fun set(index: Int)
}

/**
 * Durable pre-boost device volume, so a process death mid-boost is undone on the next start.
 * The server's [CalibrationJournal] cannot cover this: OS volume is not server state and no
 * `Client.SetVolume` read-back can observe it, so the boosted client owns its own recovery.
 */
interface OsVolumeJournal {
    /** Persist the pre-boost index. Returns false if it did not durably land, in which case the
     *  caller must NOT boost — an un-journaled boost is unrecoverable and would leave a user's
     *  phone loud with no record of the original. */
    fun save(preBoostIndex: Int): Boolean

    /** The pre-boost index of an unfinished boost, or null if none is journaled. */
    fun load(): Int?

    /** Drop the journal — the boost has been released. */
    fun clear()
}

/**
 * Client-side OS media-volume boost for sync-calibration detectability, as a pure state machine.
 *
 * Why this exists: the snapclient's SW gain ([CalibrationControl.sendSetVolume]) is 100% by
 * default, so it has no headroom for a client that is simply too quiet — boosting it is a no-op
 * for the common case. The device's OS media volume is the knob that does have room, and it
 * drives a paired BT speaker as well as the built-in one (AVRCP absolute volume, rig-proven:
 * with SW pinned at 100, sweeping OS volume moved a speaker from below the z=9 attribution floor
 * to above it). See `SPEC-os-volume-boost.md`.
 *
 * LEASED, NOT LATCHED. [apply] arms a lease with an expiry sized to outlast the measurement round
 * (extendable via [renew]), and the host calls [expireIfLapsed] from its own ticker. A server that
 * crashes, is killed, or loses the network simply stops renewing and the boost undoes itself. An
 * explicit "restore" message could never cover those cases; here, doing nothing IS the restore. On
 * a clean finish the server still calls [release] so the volume drops immediately rather than at
 * the end of the lease window.
 *
 * Thread-safe by construction. The two callers genuinely differ: leases arrive on the host's
 * notification collector while the expiry failsafe runs on its own ticker, both on a pool
 * dispatcher, so relying on call-site confinement would leave the crash-safety path racing (a
 * ticker reading a stale expiry could restore mid-measurement, or collide with [release]). Every
 * entry point locks instead, which costs nothing at this call rate.
 */
class OsVolumeBoost(
    private val device: DeviceVolume,
    private val journal: OsVolumeJournal,
    /** Ceiling on how loud a boost may go. Auto-triggered runs should pass a lower cap than
     *  user-initiated ones: the boost is transient (~30 s), so the risk is peak startle. */
    private val capPercent: Int = 100,
) {

    private var preBoostIndex: Int? = null
    private var leaseExpiryMs: Long = 0L

    val isBoosted: Boolean get() = synchronized(this) { preBoostIndex != null }

    /**
     * Boost to [targetPercent] (clamped to the cap) and arm/extend the lease to
     * [nowMs] + [leaseMs]. Also the refresh and the ramp-step call: re-applying with a new target
     * keeps the ORIGINAL pre-boost value, so a two-step ramp still restores to where the user had
     * it, and stepping up costs no settle time (no release-then-reacquire).
     *
     * Returns false if the pre-boost state could not be journaled; the device volume is then left
     * untouched.
     */
    @Synchronized
    fun apply(targetPercent: Int, nowMs: Long, leaseMs: Long): Boolean {
        if (preBoostIndex == null) {
            val pre = device.current()
            if (!journal.save(pre)) return false
            preBoostIndex = pre
        }
        leaseExpiryMs = nowMs + leaseMs
        // Never quieter than the user's own setting: a "boost" that computes a lower index than
        // the current one (target below where they had it) must not turn their music down.
        device.set(maxOf(indexFor(targetPercent), preBoostIndex!!))
        return true
    }

    /**
     * Extend an existing lease WITHOUT re-writing the device volume. Deliberately distinct from
     * [apply]: if a listener turns their own volume down mid-round, a renewal must not shove it
     * back up — the boost is ours to time out, but the volume knob stays theirs. No-op when not
     * boosted (a renewal for a boost that already lapsed must not silently re-arm it).
     */
    @Synchronized
    fun renew(nowMs: Long, leaseMs: Long) {
        if (preBoostIndex == null) return
        leaseExpiryMs = nowMs + leaseMs
    }

    /** Restore the pre-boost volume now (the clean-finish path). No-op when not boosted. */
    @Synchronized
    fun release() {
        val pre = preBoostIndex ?: return
        device.set(pre)
        journal.clear()
        preBoostIndex = null
        leaseExpiryMs = 0L
    }

    /**
     * Restore if the lease has lapsed — the failsafe for a server that stopped refreshing
     * (crashed, killed, network lost). Host calls this from a ticker. Returns true if it restored.
     */
    @Synchronized
    fun expireIfLapsed(nowMs: Long): Boolean {
        if (preBoostIndex == null || nowMs < leaseExpiryMs) return false
        release()
        return true
    }

    /**
     * Undo a boost that a process death interrupted: if the journal still holds a pre-boost index,
     * the previous run never released, so restore it. Call once at app start, before any new boost.
     * Blind restore, deliberately — same ruling as the server journal: the likeliest interrupted
     * state is one no durable record matches, so a "restore only if unchanged" guard would refuse
     * to act on exactly the case it exists to fix. Returns true if it restored something.
     */
    @Synchronized
    fun recover(): Boolean {
        val pre = journal.load() ?: return false
        device.set(pre)
        journal.clear()
        return true
    }

    private fun indexFor(targetPercent: Int): Int {
        val pct = targetPercent.coerceIn(0, capPercent)
        return (device.maxIndex * pct / 100.0).roundToInt().coerceIn(0, device.maxIndex)
    }
}
