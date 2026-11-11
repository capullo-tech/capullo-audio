package tech.capullo.audio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // caller falls back to free() instead of leaving the broadcast silently dead.
        ServerSocket(base + 1).use {
            assertNull(SnapserverPorts.fixedIfFree(base))
        }
    }

    @Test
    fun `free returns a released consecutive block in stream-tcp-http order`() {
        val p = SnapserverPorts.free()
        assertEquals(p.streamPort + 1, p.tcpPort)
        assertEquals(p.streamPort + 2, p.httpPort)
        assertTrue("base ${p.streamPort} outside the ephemeral range", p.streamPort >= 32768 && p.httpPort <= 60999)
        // The pre-flight sockets are closed again, so Snapserver can bind all three.
        ServerSocket(p.streamPort).use { ServerSocket(p.tcpPort).use { ServerSocket(p.httpPort).use { } } }
    }

    @Test
    fun `free skips a busy block and takes the next draw`() {
        ServerSocket(0).use { held ->
            val busyBase = held.localPort - 1 // the held port is this block's tcp port
            val freeBase = ServerSocket(0).use { it.localPort }
            val draws = ArrayDeque(listOf(busyBase, freeBase))
            assertEquals(SnapserverPorts.fixed(freeBase), SnapserverPorts.free { draws.removeFirst() })
        }
    }

    @Test
    fun `free falls back to three distinct ports when every block is busy`() {
        ServerSocket(0).use { held ->
            // Every draw collides on the held port; free() must still hand back three usable
            // ports (OS-assigned, so no block) instead of throwing or looping.
            val p = SnapserverPorts.free { held.localPort }
            assertEquals(3, setOf(p.streamPort, p.tcpPort, p.httpPort).size)
        }
    }
}
