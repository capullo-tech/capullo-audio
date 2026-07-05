package tech.capullo.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.NowPlayingSource
import tech.capullo.audio.contracts.PlaybackQueue
import tech.capullo.audio.player.MediaPlayback

/**
 * The engine-side contract-validation deliverable (the twin of `capullo-source-telegram`'s
 * `TelegramSourceContractTest`): drives [QueuePlaybackLoop] - the engine's `queue().idAt(i) →
 * mediaRequestFor(id) → play → onQueueAdvanced(i)` loop - against fakes, so `capullo-audio-contracts`
 * is validated **bidirectionally** (the source side drove it only). Pure JVM: runs under
 * `:capullo-audio:testDebugUnitTest` with no emulator (ExoPlayer + the snapcast FIFO live behind the
 * [MediaPlayback] seam, faked here), so it runs on the build host and in CI.
 *
 * Each test pins one obligation the loop must honour:
 *  1. `idAt(i)` feeds `mediaRequestFor`, and `mediaRequestFor(i)` runs BEFORE `onQueueAdvanced(i)`.
 *  2. an unresolvable track (mediaRequestFor throws - the only "skip me" signal) is skipped.
 *  3. an all-unresolvable rotating queue is bounded (no infinite spin / CI hang).
 *  4. the engine's live clock (isPlaying/positionMs) overlays - and overrides - the source snapshot.
 *  5. a play/pause state change re-publishes the overlay.
 *  6. a track ending auto-advances a finite playlist (how `onQueueAdvanced` fires in production).
 *  7. the app-driven `play(id)` selection path resolves and plays that id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnginePlaybackContractTest {

    /** Records the exact call order across both SPI callbacks so ordering can be asserted literally. */
    private class FakeSource(
        private val ids: List<String>,
        private val rotating: Boolean = false,
        private val unresolvable: Set<String> = emptySet(),
        private val startIndex: Int = 0,
    ) : MediaSourceProvider {
        val events = mutableListOf<String>()

        override suspend fun mediaRequestFor(id: String): MediaRequest {
            events += "req:$id"
            if (id in unresolvable) throw IllegalStateException("unresolvable: $id")
            return MediaRequest(uri = "file:///$id")
        }

        override fun queue(): PlaybackQueue = object : PlaybackQueue {
            override val size = ids.size
            override val currentIndex = startIndex
            override val isRotating = rotating
            override fun idAt(index: Int): String? =
                if (rotating) ids[((index % ids.size) + ids.size) % ids.size]
                else ids.getOrNull(index)
        }

        override fun onQueueAdvanced(currentIndex: Int) { events += "adv:$currentIndex" }
    }

    private class FakeMediaPlayback : MediaPlayback {
        val played = mutableListOf<MediaRequest>()
        override var onEnded: (() -> Unit)? = null
        override var onStateChanged: (() -> Unit)? = null
        override var positionMs: Long = 0L
        override var isPlaying: Boolean = false

        override fun play(request: MediaRequest) { played += request; isPlaying = true }
        override fun release() {}

        fun fireEnded() = onEnded?.invoke()
        fun fireStateChanged() = onStateChanged?.invoke()
    }

    private fun nowPlayingOf(state: MutableStateFlow<NowPlaying>) = object : NowPlayingSource {
        override val nowPlaying: StateFlow<NowPlaying> = state
    }

    @Test
    fun `idAt feeds mediaRequestFor, which runs before onQueueAdvanced`() = runTest {
        val source = FakeSource(ids = listOf("a", "b", "c"))
        val playback = FakeMediaPlayback()
        val loop = QueuePlaybackLoop(source, nowPlayingOf(MutableStateFlow(NowPlaying(""))), playback, this)

        loop.advanceTo(0)

        // The literal contract link: the id handed to mediaRequestFor is exactly queue.idAt(0).
        assertEquals("a", source.queue().idAt(0))
        assertEquals(listOf(MediaRequest(uri = "file:///a")), playback.played)
        // …and the ordering obligation: resolve (enrich now-playing) BEFORE reporting the advance.
        assertEquals(listOf("req:a", "adv:0"), source.events)
        assertEquals(0, loop.currentIndex)
    }

    @Test
    fun `an unresolvable track is skipped and the next one plays`() = runTest {
        val source = FakeSource(ids = listOf("a", "b", "c"), unresolvable = setOf("b"))
        val playback = FakeMediaPlayback()
        val loop = QueuePlaybackLoop(source, nowPlayingOf(MutableStateFlow(NowPlaying(""))), playback, this)

        loop.advanceTo(1) // start on the unresolvable "b"

        // "b" was attempted then skipped (no adv:1); "c" resolved and played (adv:2).
        assertEquals(listOf("req:b", "req:c", "adv:2"), source.events)
        assertEquals(listOf(MediaRequest(uri = "file:///c")), playback.played)
        assertEquals(2, loop.currentIndex)
    }

    @Test
    fun `an all-unresolvable rotating queue is bounded and does not spin`() = runTest {
        // A rotating queue never returns null from idAt, so the skip loop must stop by attempt count.
        val source = FakeSource(ids = listOf("a"), rotating = true, unresolvable = setOf("a"))
        val playback = FakeMediaPlayback()
        val loop = QueuePlaybackLoop(source, nowPlayingOf(MutableStateFlow(NowPlaying(""))), playback, this)

        loop.advanceTo(0) // must return, not hang

        assertEquals("tried exactly size(=1) times then gave up", listOf("req:a"), source.events)
        assertTrue("nothing played", playback.played.isEmpty())
        assertEquals(-1, loop.currentIndex)
    }

    @Test
    fun `the engine clock overlays and overrides the source now-playing`() = runTest {
        // The source only ever sets isPlaying=true/positionMs=0; the engine owns the real clock.
        val np = MutableStateFlow(NowPlaying(title = "T", isPlaying = true, canGoNext = true))
        val playback = FakeMediaPlayback().apply { isPlaying = false; positionMs = 1234 }
        val loop = QueuePlaybackLoop(FakeSource(listOf("a")), nowPlayingOf(np), playback, this)

        val overlaid = loop.nowPlaying.value
        assertFalse("engine (paused) overrides source isPlaying=true", overlaid.isPlaying)
        assertEquals("engine position wins", 1234L, overlaid.positionMs)
        assertEquals("source metadata preserved", "T", overlaid.title)
        assertTrue("queue-owned flags stay the source's", overlaid.canGoNext)
    }

    @Test
    fun `a play state change re-publishes the overlay`() = runTest {
        val playback = FakeMediaPlayback()
        val loop = QueuePlaybackLoop(FakeSource(listOf("a")), nowPlayingOf(MutableStateFlow(NowPlaying(""))), playback, this)

        loop.advanceTo(0)
        playback.positionMs = 5000
        playback.fireStateChanged() // simulates ExoPlayer.onIsPlayingChanged

        assertEquals(5000L, loop.nowPlaying.value.positionMs)
    }

    @Test
    fun `a track ending auto-advances a finite playlist`() = runTest {
        val source = FakeSource(ids = listOf("a", "b", "c"))
        val playback = FakeMediaPlayback()
        val loop = QueuePlaybackLoop(source, nowPlayingOf(MutableStateFlow(NowPlaying(""))), playback, this)

        loop.advanceTo(0)
        playback.fireEnded() // ExoPlayer STATE_ENDED → scope.launch { advance() }
        advanceUntilIdle()

        assertEquals(1, loop.currentIndex)
        assertEquals(listOf(MediaRequest(uri = "file:///a"), MediaRequest(uri = "file:///b")), playback.played)
        assertEquals(listOf("req:a", "adv:0", "req:b", "adv:1"), source.events)
    }

    @Test
    fun `a rotating (radio) source does not auto-advance on track end`() = runTest {
        // isRotating is the contract flag the engine consumes: a live-radio STATE_ENDED must NOT
        // wrap onto the next rotation index (that would fight the app's app-driven station model).
        val source = FakeSource(ids = listOf("a", "b"), rotating = true)
        val playback = FakeMediaPlayback()
        val loop = QueuePlaybackLoop(source, nowPlayingOf(MutableStateFlow(NowPlaying(""))), playback, this)

        loop.advanceTo(0)
        playback.fireEnded()
        advanceUntilIdle()

        assertEquals("stayed on the current station", 0, loop.currentIndex)
        assertEquals(listOf(MediaRequest(uri = "file:///a")), playback.played)
    }

    @Test
    fun `app-driven play(id) resolves that id and reports its queue index`() = runTest {
        val source = FakeSource(ids = listOf("a", "b", "c"))
        val playback = FakeMediaPlayback()
        val loop = QueuePlaybackLoop(source, nowPlayingOf(MutableStateFlow(NowPlaying(""))), playback, this)

        loop.play("b")

        assertEquals(listOf(MediaRequest(uri = "file:///b")), playback.played)
        assertEquals(listOf("req:b", "adv:1"), source.events)
        assertEquals(1, loop.currentIndex)
    }
}
