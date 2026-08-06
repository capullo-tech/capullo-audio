package tech.capullo.audio.calibration

/**
 * Durable record of the volumes the balance overwrote, so applying it is a reversible action.
 *
 * Distinct from [CalibrationJournal] in both lifetime and purpose, and the two must not be merged.
 * The journal covers a run that DIED: it is written before the first mutating write and cleared the
 * moment the run finishes, because once a run has completed its writes are intentional and blindly
 * reverting them would undo the calibration. This record covers a run that SUCCEEDED and is written
 * as the run ends, precisely so an intentional write can still be taken back afterwards.
 *
 * Why it exists at all: the balance writes persistent, server-side volumes off a mic estimator whose
 * accuracy on real hardware is bounded but not proven. Requiring near-perfect confidence before
 * writing anything is the wrong way to manage that. Bounding the mistake instead — a cap on the size
 * of any single correction ([VolumeBalance.MAX_CORRECTION_DB]) plus a record that makes it revertible
 * — turns "we might be wrong" from a reason not to ship into a cost of one action.
 *
 * Only volume is recorded. Latency corrections are verified by their own measurement inside the run
 * and are not what this protects.
 */
interface VolumeUndo {
    /** Record the pre-balance volume of every client whose volume is about to be written. Replaces
     *  any previous record: only the most recent balance is undoable, since an older record no
     *  longer describes a state the user would recognise. */
    fun save(previous: Map<String, Int>)

    /** The volumes the last balance overwrote, or null if no balance is outstanding. */
    fun load(): Map<String, Int>?

    /** Drop the record — either it has been applied, or the user has moved on. */
    fun clear()
}
