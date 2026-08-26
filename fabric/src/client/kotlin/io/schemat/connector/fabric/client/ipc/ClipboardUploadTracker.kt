package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.StatusState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dash/case-insensitive draft-ownership check (spec invariant 4: the client refuses
 * drafts not owned by the current user — a malicious server handing someone else's
 * draft id gets an error and nothing more).
 */
fun isDraftOwnedBy(authorUuids: List<String>, playerUuid: String?): Boolean {
    val me = playerUuid?.lowercase()?.replace("-", "")?.takeIf { it.isNotEmpty() } ?: return false
    return authorUuids.any { it.lowercase().replace("-", "") == me }
}

/**
 * Pending UPLOAD_CLIPBOARD requests: DRAFT_CREATED completes them, terminal STATUS
 * fails them, [tick] times them out with a synthetic ERROR after [TIMEOUT_MS] (spec).
 * Mirrors [ClipboardLoadTracker]; kept free of Minecraft classes for headless tests.
 */
object ClipboardUploadTracker {

    const val TIMEOUT_MS: Long = 30_000

    /**
     * Upload requestIds start here: [ClipboardLoadTracker] issues ids from 1 upward and
     * STATUS frames are dispatched to BOTH trackers (STATUS is generic — contract C2),
     * so the id spaces must stay disjoint within a session (resolved ambiguity 8).
     */
    const val ID_BASE: Int = 1_000_000

    private data class Pending(
        val onStatus: (StatusState, String) -> Unit,
        val onDraft: (String) -> Unit,
        val deadlineMs: Long,
    )

    private val nextId = AtomicInteger(ID_BASE)
    private val pending = ConcurrentHashMap<Int, Pending>()

    fun register(
        onStatus: (StatusState, String) -> Unit,
        onDraft: (String) -> Unit,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val id = nextId.getAndIncrement()
        pending[id] = Pending(onStatus, onDraft, nowMs + TIMEOUT_MS)
        return id
    }

    fun onStatus(requestId: Int, state: StatusState, detail: String, nowMs: Long = System.currentTimeMillis()) {
        // Terminal STATUS removes atomically; a non-terminal (progress) STATUS refreshes the
        // deadline via computeIfPresent so it can't race tick()'s atomic remove and double-fire
        // a callback after a timeout/terminal has already fired (see ClipboardLoadTracker).
        val entry = if (state.isTerminal) {
            pending.remove(requestId)
        } else {
            pending.computeIfPresent(requestId) { _, p -> p.copy(deadlineMs = nowMs + TIMEOUT_MS) }
        } ?: return
        entry.onStatus(state, sanitizeDetail(detail))
    }

    fun onDraft(requestId: Int, draftId: String) {
        val entry = pending.remove(requestId) ?: return
        entry.onDraft(draftId)
    }

    /** Called from the client tick: expires overdue requests with a synthetic ERROR. */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        for ((id, entry) in pending) {
            if (nowMs >= entry.deadlineMs && pending.remove(id) != null) {
                entry.onStatus(StatusState.ERROR, "Timed out waiting for the server")
            }
        }
    }

    fun pendingCount(): Int = pending.size

    fun reset() = pending.clear()
}
