package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.StatusState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ClipboardUploadTrackerTest {

    private val statuses = mutableListOf<Pair<StatusState, String>>()
    private var draftId: String? = null
    private val onStatus: (StatusState, String) -> Unit = { s, d -> statuses += s to d }
    private val onDraft: (String) -> Unit = { draftId = it }

    @BeforeEach
    fun setUp() {
        ClipboardUploadTracker.reset()
        statuses.clear()
        draftId = null
    }

    @AfterEach
    fun tearDown() = ClipboardUploadTracker.reset()

    @Test
    fun `a timeout that wins the race suppresses a later progress status`() {
        // Interleaving without threads: the request times out (tick removes + fires ERROR),
        // then a same-id non-terminal STATUS arrives late. It must be a no-op — the callback
        // fires exactly once (the ERROR), never twice.
        val id = ClipboardUploadTracker.register(onStatus, onDraft, nowMs = 0)
        ClipboardUploadTracker.tick(nowMs = ClipboardUploadTracker.TIMEOUT_MS + 1) // -> ERROR, entry removed
        ClipboardUploadTracker.onStatus(id, StatusState.RESOLVING, "late progress", nowMs = ClipboardUploadTracker.TIMEOUT_MS + 2)
        assertEquals(1, statuses.size, "late progress after timeout must not fire a second callback")
        assertEquals(StatusState.ERROR, statuses.single().first)
        assertEquals(0, ClipboardUploadTracker.pendingCount())
    }

    @Test
    fun `register issues distinct ids from the reserved range`() {
        val a = ClipboardUploadTracker.register(onStatus, onDraft)
        val b = ClipboardUploadTracker.register(onStatus, onDraft)
        assertNotEquals(a, b)
        assertTrue(a >= ClipboardUploadTracker.ID_BASE, "ids must never collide with the load tracker's")
        assertEquals(2, ClipboardUploadTracker.pendingCount())
    }

    @Test
    fun `onDraft completes the request exactly once`() {
        val id = ClipboardUploadTracker.register(onStatus, onDraft)
        ClipboardUploadTracker.onDraft(id, "draft-1")
        assertEquals("draft-1", draftId)
        assertEquals(0, ClipboardUploadTracker.pendingCount())
        draftId = null
        ClipboardUploadTracker.onDraft(id, "draft-2") // already completed: no-op
        assertNull(draftId)
    }

    @Test
    fun `a terminal STATUS completes the request and sanitizes the detail`() {
        val id = ClipboardUploadTracker.register(onStatus, onDraft)
        ClipboardUploadTracker.onStatus(id, StatusState.DENIED, "§cnot §lallowed")
        assertEquals(listOf(StatusState.DENIED to "not allowed"), statuses)
        assertEquals(0, ClipboardUploadTracker.pendingCount())
    }

    @Test
    fun `unknown requestIds are ignored (STATUS frames are shared with the load tracker)`() {
        ClipboardUploadTracker.onStatus(1, StatusState.OK, "") // a load-tracker id
        ClipboardUploadTracker.onDraft(1, "not-ours")
        assertTrue(statuses.isEmpty())
        assertNull(draftId)
    }

    @Test
    fun `tick expires overdue requests with a synthetic ERROR`() {
        val now = 1_000L
        ClipboardUploadTracker.register(onStatus, onDraft, nowMs = now)
        ClipboardUploadTracker.tick(now + ClipboardUploadTracker.TIMEOUT_MS - 1)
        assertTrue(statuses.isEmpty())
        ClipboardUploadTracker.tick(now + ClipboardUploadTracker.TIMEOUT_MS)
        assertEquals(1, statuses.size)
        assertEquals(StatusState.ERROR, statuses.single().first)
        assertEquals(0, ClipboardUploadTracker.pendingCount())
    }

    @Test
    fun `ownership check is dash- and case-insensitive`() {
        val authors = listOf("AABBCCDD-1122-3344-5566-778899AABBCC")
        assertTrue(isDraftOwnedBy(authors, "aabbccdd-1122-3344-5566-778899aabbcc"))
        assertTrue(isDraftOwnedBy(authors, "aabbccdd11223344556677" + "8899aabbcc"))
        assertFalse(isDraftOwnedBy(authors, "00000000-0000-0000-0000-000000000000"))
        assertFalse(isDraftOwnedBy(authors, null))
        assertFalse(isDraftOwnedBy(emptyList(), "aabbccdd-1122-3344-5566-778899aabbcc"))
    }
}
