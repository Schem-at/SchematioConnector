package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.StatusState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server-supplied detail text is rendered as PLAIN text: strip every '§' together
 * with its following formatting-code character (spec invariant 5).
 */
fun sanitizeDetail(detail: String): String = detail.replace(Regex("§."), "").replace("§", "")

/**
 * Pending LOAD_REQUESTs awaiting STATUS replies. requestIds are client-generated
 * and monotonically increasing; a request completes on its first terminal status,
 * or expires with a synthetic ERROR after [TIMEOUT_MS] of silence (progress
 * statuses refresh the deadline). Clock-injectable for tests; call [tick] from the
 * client tick and [reset] on join/disconnect.
 */
object ClipboardLoadTracker {

    const val TIMEOUT_MS: Long = 30_000

    private data class Pending(
        val onStatus: (StatusState, String) -> Unit,
        val deadlineMs: Long,
    )

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pending>()

    fun register(onStatus: (StatusState, String) -> Unit, nowMs: Long = System.currentTimeMillis()): Int {
        val id = nextId.getAndIncrement()
        pending[id] = Pending(onStatus, nowMs + TIMEOUT_MS)
        return id
    }

    /**
     * Each requestId's callback fires at most once total across the terminal,
     * progress, and timeout ([tick]) paths. Removal (terminal/timeout) and the
     * deadline refresh (progress) are done atomically against the map so that a
     * concurrent [tick] on another thread can't race a progress update here:
     * whichever side wins the atomic map operation is the only side that invokes
     * the callback.
     */
    fun onStatus(requestId: Int, state: StatusState, detail: String, nowMs: Long = System.currentTimeMillis()) {
        val sanitized = sanitizeDetail(detail)
        if (state.isTerminal) {
            val entry = pending.remove(requestId) ?: return // unknown or already completed: ignore
            entry.onStatus(state, sanitized)
        } else {
            // Atomically refresh the deadline; if another thread (tick()) already
            // removed this entry, computeIfPresent is a no-op and returns null, so
            // the progress callback is skipped rather than firing after a timeout.
            val entry = pending.computeIfPresent(requestId) { _, p ->
                p.copy(deadlineMs = nowMs + TIMEOUT_MS)
            } ?: return
            entry.onStatus(state, sanitized)
        }
    }

    /** Expire overdue requests with a synthetic ERROR (spec: 30 s client-side timeout). */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        for ((id, entry) in pending) {
            if (nowMs >= entry.deadlineMs && pending.remove(id) != null) {
                entry.onStatus(StatusState.ERROR, "Timed out waiting for the server")
            }
        }
    }

    fun pendingCount(): Int = pending.size

    fun reset() {
        pending.clear()
    }
}
