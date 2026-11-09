package tech.capullo.audio.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuppressedLossLatchTest {

    // AudioManager values, inlined so the test stays off Android.
    private val loss = -1
    private val lossTransient = -2

    @Test
    fun `a loss while suppressing is held and replayed once after the lift`() {
        val l = SuppressedLossLatch()
        l.setSuppressing(true)
        assertTrue(l.offerLoss(loss))
        assertTrue(l.setSuppressing(false))
        assertEquals(loss, l.takeReplay())
        assertEquals(0, l.takeReplay())
    }

    @Test
    fun `without suppression a loss is not latched`() {
        val l = SuppressedLossLatch()
        assertFalse(l.offerLoss(loss))
        assertEquals(0, l.takeReplay())
    }

    @Test
    fun `the most recent loss wins`() {
        val l = SuppressedLossLatch()
        l.setSuppressing(true)
        l.offerLoss(lossTransient)
        l.offerLoss(loss)
        l.setSuppressing(false)
        assertEquals(loss, l.takeReplay())
    }

    /** Transient loss then GAIN inside one calibration: replaying would silence a client that holds focus. */
    @Test
    fun `focus settled after the loss drops it`() {
        val l = SuppressedLossLatch()
        l.setSuppressing(true)
        l.offerLoss(lossTransient)
        l.focusSettled()
        l.setSuppressing(false)
        assertEquals(0, l.takeReplay())
    }

    /** Back-to-back calibrations: the replay posted by the first lift runs during the second. */
    @Test
    fun `re-suppressed before the replay keeps the loss for the next lift`() {
        val l = SuppressedLossLatch()
        l.setSuppressing(true)
        l.offerLoss(loss)
        l.setSuppressing(false)
        l.setSuppressing(true)
        assertEquals(0, l.takeReplay())
        assertTrue(l.setSuppressing(false))
        assertEquals(loss, l.takeReplay())
    }

    @Test
    fun `only a true-to-false change counts as a lift`() {
        val l = SuppressedLossLatch()
        assertFalse(l.setSuppressing(false))
        l.setSuppressing(true)
        assertFalse(l.setSuppressing(true))
        assertTrue(l.setSuppressing(false))
    }
}
