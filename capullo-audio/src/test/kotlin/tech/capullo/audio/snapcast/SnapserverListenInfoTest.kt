package tech.capullo.audio.snapcast

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapserverListenInfoTest {

    /** A host serving `listen.json` on the given ports; records every port probed, in order. */
    private class FakeHost(private val served: Map<Int, SnapserverPorts>) {
        val probed = mutableListOf<Int>()

        suspend fun fetch(port: Int): SnapserverPorts? {
            probed += port
            return served[port]
        }
    }

    private val block = SnapserverPorts.fixed(41000)
    private val host = FakeHost(mapOf(block.httpPort to block))

    @Test
    fun `typed stream port reaches listen json two ports up`() = runTest {
        assertEquals(block, SnapserverListenInfo.resolve(block.streamPort, 1680, host::fetch))
        assertEquals(listOf(41000, 41001, 41002), host.probed)
    }

    @Test
    fun `typed tcp port reaches listen json one port up`() = runTest {
        assertEquals(block, SnapserverListenInfo.resolve(block.tcpPort, 1680, host::fetch))
        assertEquals(listOf(41001, 41002), host.probed)
    }

    @Test
    fun `typed http port stops at the first hit`() = runTest {
        assertEquals(block, SnapserverListenInfo.resolve(block.httpPort, 1680, host::fetch))
        assertEquals(listOf(41002), host.probed)
    }

    @Test
    fun `bare host probes only the default http port`() = runTest {
        val legacy = SnapserverPorts(streamPort = 1804, tcpPort = 1805, httpPort = 1880)
        val lanHost = FakeHost(mapOf(1880 to legacy))
        assertEquals(legacy, SnapserverListenInfo.resolve(null, 1880, lanHost::fetch))
        assertEquals(listOf(1880), lanHost.probed)
    }

    @Test
    fun `a hit that lacks the typed port is another server and is skipped`() = runTest {
        // A legacy non-consecutive broadcaster happens to serve listen.json on 41001; it doesn't own
        // 41000, so the probe moves on to the real block's HTTP port.
        val neighbour = SnapserverPorts(streamPort = 50123, tcpPort = 47011, httpPort = 41001)
        val lanHost = FakeHost(mapOf(41001 to neighbour, block.httpPort to block))
        assertEquals(block, SnapserverListenInfo.resolve(block.streamPort, 1680, lanHost::fetch))
        assertEquals(listOf(41000, 41001, 41002), lanHost.probed)
    }

    @Test
    fun `no consistent listen json resolves to null`() = runTest {
        val neighbour = SnapserverPorts(streamPort = 50123, tcpPort = 47011, httpPort = 41001)
        val lanHost = FakeHost(mapOf(41001 to neighbour))
        assertNull(SnapserverListenInfo.resolve(41000, 1680, lanHost::fetch))
        assertEquals(listOf(41000, 41001, 41002), lanHost.probed)
    }
}
