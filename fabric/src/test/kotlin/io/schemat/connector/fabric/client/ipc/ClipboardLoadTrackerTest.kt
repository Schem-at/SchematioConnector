package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.StatusState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ClipboardLoadTrackerTest {

    private val events = mutableListOf<Pair<StatusState, String>>()
    private val sink: (StatusState, String) -> Unit = { state, detail -> events += state to detail }

    @BeforeEach
    fun setUp() = ClipboardLoadTracker.reset()

    @AfterEach
    fun tearDown() = ClipboardLoadTracker.reset()

    @Test
    fun `register issues distinct requestIds`() {
        val a = ClipboardLoadTracker.register(sink)
        val b = ClipboardLoadTracker.register(sink)
        assertNotEquals(a, b)
        assertEquals(2, ClipboardLoadTracker.pendingCount())
    }

    @Test
    fun `progress statuses invoke the callback and keep the request pending`() {
        val id = ClipboardLoadTracker.register(sink, nowMs = 0)
        ClipboardLoadTracker.onStatus(id, StatusState.RESOLVING, "", nowMs = 1)
        ClipboardLoadTracker.onStatus(id, StatusState.DOWNLOADING, "", nowMs = 2)
        assertEquals(listOf(StatusState.RESOLVING to "", StatusState.DOWNLOADING to ""), events)
        assertEquals(1, ClipboardLoadTracker.pendingCount())
    }

    @Test
    fun `a terminal status completes the request`() {
        val id = ClipboardLoadTracker.register(sink)
        ClipboardLoadTracker.onStatus(id, StatusState.OK, "")
        assertEquals(0, ClipboardLoadTracker.pendingCount())
        // Late duplicates are ignored.
        ClipboardLoadTracker.onStatus(id, StatusState.ERROR, "late")
        assertEquals(listOf(StatusState.OK to ""), events)
    }

    @Test
    fun `statuses for unknown requestIds are ignored`() {
        ClipboardLoadTracker.onStatus(999, StatusState.OK, "")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an idle request times out with a synthetic ERROR`() {
        ClipboardLoadTracker.register(sink, nowMs = 0)
        ClipboardLoadTracker.tick(nowMs = ClipboardLoadTracker.TIMEOUT_MS - 1)
        assertTrue(events.isEmpty())
        ClipboardLoadTracker.tick(nowMs = ClipboardLoadTracker.TIMEOUT_MS)
        assertEquals(1, events.size)
        assertEquals(StatusState.ERROR, events.single().first)
        assertEquals(0, ClipboardLoadTracker.pendingCount())
    }

    @Test
    fun `progress statuses refresh the deadline`() {
        val id = ClipboardLoadTracker.register(sink, nowMs = 0)
        ClipboardLoadTracker.onStatus(id, StatusState.DOWNLOADING, "", nowMs = 20_000)
        ClipboardLoadTracker.tick(nowMs = 40_000) // 40s after register, 20s after progress
        assertEquals(1, ClipboardLoadTracker.pendingCount()) // still alive
        ClipboardLoadTracker.tick(nowMs = 20_000 + ClipboardLoadTracker.TIMEOUT_MS)
        assertEquals(0, ClipboardLoadTracker.pendingCount())
    }

    @Test
    fun `detail text is sanitized before reaching the callback`() {
        val id = ClipboardLoadTracker.register(sink)
        ClipboardLoadTracker.onStatus(id, StatusState.DENIED, "§4§lDenied§r by admin§")
        assertEquals("Denied by admin", events.single().second)
    }

    @Test
    fun `sanitizeDetail strips formatting codes`() {
        assertEquals("hello", sanitizeDetail("§ahello"))
        assertEquals("ab", sanitizeDetail("a§xb"))
        assertEquals("plain", sanitizeDetail("plain"))
        assertEquals("trailing", sanitizeDetail("trailing§"))
        assertEquals("", sanitizeDetail("§a§b§c"))
    }

    @Test
    fun `a timeout that wins the race suppresses a subsequent progress callback`() {
        // Simulates the tick() vs onStatus() interleaving without real threads: the
        // entry is removed by a timeout first, then a progress status for the same
        // requestId arrives "late" (as it would if the network thread's onStatus
        // call had been in flight concurrently with the tick thread's timeout).
        val callCount = AtomicInteger(0)
        val countingSink: (StatusState, String) -> Unit = { state, detail ->
            callCount.incrementAndGet()
            events += state to detail
        }

        val id = ClipboardLoadTracker.register(countingSink, nowMs = 0)

        // Timeout path wins the race: fires ERROR and removes the entry.
        ClipboardLoadTracker.tick(nowMs = ClipboardLoadTracker.TIMEOUT_MS)
        assertEquals(1, callCount.get())
        assertEquals(0, ClipboardLoadTracker.pendingCount())

        // A progress status for the same requestId arrives after the timeout already
        // removed it; it must be a no-op, not a second callback invocation.
        ClipboardLoadTracker.onStatus(id, StatusState.DOWNLOADING, "", nowMs = ClipboardLoadTracker.TIMEOUT_MS + 1)

        assertEquals(1, callCount.get())
        assertEquals(listOf(StatusState.ERROR), events.map { it.first })
    }
}
