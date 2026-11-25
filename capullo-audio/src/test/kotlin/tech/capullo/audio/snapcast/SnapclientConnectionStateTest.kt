package tech.capullo.audio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.audio.snapcast.SnapclientProcess.ConnectionState
import tech.capullo.audio.snapcast.SnapclientProcess.ConnectionState.CONNECTED
import tech.capullo.audio.snapcast.SnapclientProcess.ConnectionState.ERROR
import tech.capullo.audio.snapcast.SnapclientProcess.ConnectionState.STARTING

/**
 * Log lines are verbatim from the phones (2026-11-24 and 11-25) with the timestamps cut, except
 * the hello timeout, whose text is the one in the fork's `client/controller.cpp`.
 */
class SnapclientConnectionStateTest {

    private fun states(vararg lines: String, from: ConnectionState = STARTING): List<ConnectionState> {
        var state = from
        return lines.map { line ->
            state = SnapclientProcess.connectionStateAfter(line, SnapclientStats.parse(line), state)
            state
        }
    }

    @Test
    fun `a join is connected when the time sync completes, not when the socket opens`() {
        assertEquals(
            listOf(STARTING, STARTING, STARTING, STARTING, CONNECTED),
            states(
                "[Info] (Connection) Connecting to host: 192.168.16.162:34395, port: 34395, protocol: tcp",
                "[Notice] (Connection) Connected to 192.168.16.162",
                "[Info] (OboePlayer) Start",
                "[Debug] (Stats) Chunk: 0\t0\t0\t0\t1\t88\t0",
                "[Info] (Controller) diff to server [ms]: 1.54129e+09",
            ),
        )
    }

    @Test
    fun `a reconnect loop on a lossy link never reads as connected`() {
        // Tailscale path with 40% loss and 1.2 to 1.8 s round trips: the TCP connect succeeds on
        // every turn and a moment of audio plays, then the time sync times out.
        val turn = arrayOf(
            "[Info] (Connection) Connecting to host: 100.94.68.102:50772, port: 50772, protocol: tcp",
            "[Notice] (Connection) Connected to 100.94.68.102",
            "[Info] (OboePlayer) Start",
            "[Info] (Stream) No chunks available",
            "[Debug] (Stats) Chunk: 0\t0\t0\t0\t6\t77\t0",
            "[Error] (Controller) Time sync request failed: Connection timed out",
            "[Info] (Controller) Reconnecting",
            "[Error] (Connection) Error reading message body of length 970: Operation Canceled",
            "[Error] (Controller) Error receiving next message: system:125",
        )
        val seen = states(*turn, *turn, *turn)
        assertEquals(emptyList<ConnectionState>(), seen.filter { it == CONNECTED })
        // Still "connecting" until the first failure, then failed for good: no flapping back.
        assertEquals(listOf(STARTING, ERROR), seen.distinct())
    }

    @Test
    fun `a hello that times out fails the link without a connection error line`() {
        assertEquals(
            listOf(STARTING, STARTING, ERROR),
            states(
                "[Notice] (Connection) Connected to 100.121.62.21",
                "[Error] (Controller) Failed to send hello request, error: Connection timed out",
                "[Info] (Controller) Reconnecting",
            ),
        )
    }

    @Test
    fun `a link that failed is connected again only on new proof`() {
        assertEquals(
            listOf(ERROR, ERROR, ERROR, CONNECTED),
            states(
                "[Info] (Controller) Reconnecting",
                "[Notice] (Connection) Connected to 192.168.16.162",
                "[Debug] (Stats) Chunk: 0\t0\t0\t0\t74\t88\t0",
                "[Info] (Controller) diff to server [ms]: 1.54129e+09",
                from = CONNECTED,
            ),
        )
    }

    @Test
    fun `unbroken audio proves a link whose time sync is still running`() {
        assertEquals(
            listOf(STARTING, STARTING, STARTING, CONNECTED),
            states(
                "[Notice] (Connection) Connected to calm-river-1234.trycloudflare.com",
                "[Debug] (Stats) Chunk: 0\t0\t0\t0\t192\t86\t0",
                "[Debug] (Stats) Chunk: 0\t0\t0\t0\t452\t86\t0",
                "[Debug] (Stats) Chunk: 0\t0\t0\t0\t500\t86\t0",
            ),
        )
    }
}
