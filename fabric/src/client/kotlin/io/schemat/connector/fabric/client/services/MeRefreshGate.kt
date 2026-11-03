package io.schemat.connector.fabric.client.services

/**
 * When a missing `/mod/me` snapshot may be fetched again: never while a fetch is running, and at most
 * once per [intervalMs], so a panel asking every frame costs one request per interval.
 */
class MeRefreshGate(private val intervalMs: Long = 30_000) {
    private var running = false
    private var lastStart: Long? = null

    @Synchronized
    fun tryStart(nowMs: Long): Boolean {
        val last = lastStart
        if (running || (last != null && nowMs - last < intervalMs)) return false
        running = true
        lastStart = nowMs
        return true
    }

    @Synchronized
    fun finish() {
        running = false
    }
}
