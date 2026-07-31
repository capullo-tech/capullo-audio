package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the client-side OS-volume boost contract: it raises but never lowers, journals before it
 * touches anything, restores on release, and — the point of the lease — restores by itself when
 * the calibrating server stops refreshing (crash / kill / network loss).
 */
class OsVolumeBoostTest {

    private class FakeDevice(override val maxIndex: Int = 15, var index: Int = 6) : DeviceVolume {
        override fun current() = index
        override fun set(index: Int) { this.index = index }
    }

    private class FakeJournal(var stored: Int? = null, private val saveOk: Boolean = true) : OsVolumeJournal {
        override fun save(preBoostIndex: Int): Boolean {
            if (saveOk) stored = preBoostIndex
            return saveOk
        }
        override fun load(): Int? = stored
        override fun clear() { stored = null }
    }

    @Test
    fun `apply journals the pre-boost volume and raises to the target`() {
        val device = FakeDevice(maxIndex = 15, index = 6)
        val journal = FakeJournal()
        val boost = OsVolumeBoost(device, journal)

        assertTrue(boost.apply(targetPercent = 100, nowMs = 0, leaseMs = 30_000))
        assertEquals("boosted to the cap", 15, device.index)
        assertEquals("pre-boost value journaled before the write", 6, journal.load())
        assertTrue(boost.isBoosted)
    }

    @Test
    fun `a ramp step keeps the original pre-boost value`() {
        // Two-level coarse ramp: moderate, then max. Restoring must land on where the USER had it,
        // not on the intermediate step.
        val device = FakeDevice(maxIndex = 160, index = 40)
        val boost = OsVolumeBoost(device, FakeJournal())

        boost.apply(targetPercent = 60, nowMs = 0, leaseMs = 30_000)
        assertEquals(96, device.index)
        boost.apply(targetPercent = 100, nowMs = 10_000, leaseMs = 30_000)
        assertEquals(160, device.index)

        boost.release()
        assertEquals("restores the original, not the ramp step", 40, device.index)
    }

    @Test
    fun `release restores and clears the journal`() {
        val device = FakeDevice(index = 5)
        val journal = FakeJournal()
        val boost = OsVolumeBoost(device, journal)

        boost.apply(targetPercent = 100, nowMs = 0, leaseMs = 30_000)
        boost.release()

        assertEquals(5, device.index)
        assertNull("journal cleared once released", journal.load())
        assertFalse(boost.isBoosted)
    }

    @Test
    fun `the lease restores the volume when the server stops refreshing`() {
        // The failsafe: a server that crashed mid-run can never send a restore message, so the
        // boost must undo itself. Doing nothing IS the restore.
        val device = FakeDevice(index = 4)
        val boost = OsVolumeBoost(device, FakeJournal())
        boost.apply(targetPercent = 100, nowMs = 1_000, leaseMs = 30_000)

        assertFalse("still leased", boost.expireIfLapsed(nowMs = 20_000))
        assertEquals(15, device.index)

        assertTrue("lease lapsed", boost.expireIfLapsed(nowMs = 31_001))
        assertEquals("restored by the lease alone", 4, device.index)
        assertFalse(boost.isBoosted)
    }

    @Test
    fun `refreshing the lease keeps the boost alive across a long run`() {
        // A healthy run refreshes on a ticker, so however many measure-and-adjust cycles the mic
        // feedback needs, the boost must not drop out mid-measurement.
        val device = FakeDevice(index = 4)
        val boost = OsVolumeBoost(device, FakeJournal())

        boost.apply(targetPercent = 100, nowMs = 0, leaseMs = 30_000)
        for (t in listOf(10_000L, 20_000L, 30_000L, 40_000L)) {
            boost.apply(targetPercent = 100, nowMs = t, leaseMs = 30_000) // refresh
            assertFalse("must not lapse while refreshed", boost.expireIfLapsed(t + 1))
        }
        assertEquals(15, device.index)
    }

    @Test
    fun `recover restores a boost that a process death interrupted`() {
        // App was killed while boosted: the journal outlives it and the next start puts the user's
        // volume back.
        val device = FakeDevice(index = 15) // left loud by the dead process
        val journal = FakeJournal(stored = 5)
        val boost = OsVolumeBoost(device, journal)

        assertTrue(boost.recover())
        assertEquals(5, device.index)
        assertNull(journal.load())
    }

    @Test
    fun `recover is a no-op when nothing was interrupted`() {
        val device = FakeDevice(index = 9)
        val boost = OsVolumeBoost(device, FakeJournal(stored = null))
        assertFalse(boost.recover())
        assertEquals(9, device.index)
    }

    @Test
    fun `a failed journal save leaves the volume untouched`() {
        // An un-journaled boost is unrecoverable — it would strand a user's phone loud.
        val device = FakeDevice(index = 6)
        val boost = OsVolumeBoost(device, FakeJournal(saveOk = false))

        assertFalse(boost.apply(targetPercent = 100, nowMs = 0, leaseMs = 30_000))
        assertEquals(6, device.index)
        assertFalse(boost.isBoosted)
    }

    @Test
    fun `the cap limits how loud a boost may go`() {
        val device = FakeDevice(maxIndex = 160, index = 10)
        val boost = OsVolumeBoost(device, FakeJournal(), capPercent = 50)

        boost.apply(targetPercent = 100, nowMs = 0, leaseMs = 30_000)
        assertEquals("clamped to the cap, not the requested 100%", 80, device.index)
    }

    @Test
    fun `a boost never turns the user down`() {
        // Target below where the user had it must not reduce their volume.
        val device = FakeDevice(maxIndex = 15, index = 12)
        val boost = OsVolumeBoost(device, FakeJournal())

        boost.apply(targetPercent = 50, nowMs = 0, leaseMs = 30_000)
        assertEquals(12, device.index)
    }
}
