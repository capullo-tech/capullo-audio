package tech.capullo.audio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ServerSocket

class SnapserverPortsTest {

    @Test
    fun `fixed returns three consecutive ports`() {
        val p = SnapserverPorts.fixed(34000)
        assertEquals(34000, p.streamPort)
        assertEquals(34001, p.tcpPort)
        assertEquals(34002, p.httpPort)
    }

    @Test
    fun `fixedIfFree returns the trio when the ports are free`() {
        // A free base: grab an ephemeral port, release it, use it as the base. Small TOCTOU,
        // but adequate for the happy path (the collision path is the deterministic test below).
        val base = ServerSocket(0).use { it.localPort }
        val p = SnapserverPorts.fixedIfFree(base)
        assertNotNull("expected a free trio at $base", p)
        assertEquals(base, p!!.streamPort)
        assertEquals(base + 2, p.httpPort)
    }

    @Test
    fun `fixedIfFree returns null when a port in the trio is taken`() {
        val base = ServerSocket(0).use { it.localPort }
        // Occupy the middle port of the trio; the pre-flight must fail and return null so the
        // caller falls back to OS-assigned instead of leaving the broadcast silently dead.
        ServerSocket(base + 1).use {
            assertNull(SnapserverPorts.fixedIfFree(base))
        }
    }
}
