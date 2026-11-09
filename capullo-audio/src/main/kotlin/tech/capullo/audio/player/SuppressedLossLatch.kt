package tech.capullo.audio.player

/**
 * The focus-loss bookkeeping behind [AudioFocusController.suppressLosses], kept free of Android so
 * its ordering rules are unit-testable. Values are AudioManager focus-change codes; 0 = none.
 *
 * While suppressing, a loss is LATCHED instead of acted on, and replayed when suppression lifts.
 * Only the most recent loss is kept (it is the latest word on where focus stands), and anything
 * later that also says where focus stands - a GAIN, a granted request, abandon - clears it via
 * [focusSettled]. If suppression is back on when the replay runs, the latch is kept for the next
 * lift. [suppressing] may be written from another thread; the rest runs on the owner's thread.
 */
internal class SuppressedLossLatch {
    @Volatile
    var suppressing: Boolean = false
        private set

    private var latched = 0

    /** Sets suppression; returns true when this call lifted it (the owner should schedule [takeReplay]). */
    fun setSuppressing(value: Boolean): Boolean {
        val was = suppressing
        suppressing = value
        return was && !value
    }

    /** Latches [change] and returns true while suppressing; returns false (act on it now) otherwise. */
    fun offerLoss(change: Int): Boolean {
        if (!suppressing) return false
        latched = change
        return true
    }

    /** Focus state is known again (GAIN, granted request, abandon): a latched loss is stale. */
    fun focusSettled() {
        latched = 0
    }

    /** The loss to replay now, or 0. Consumes it, unless suppression came back on first. */
    fun takeReplay(): Int {
        if (suppressing) return 0
        return latched.also { latched = 0 }
    }
}
