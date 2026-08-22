package io.schemat.connector.fabric.client.ipc

/**
 * config.properties key for the "server may open UI" preference (spec: default ON,
 * visible toggle in SettingsPanel). Read via ClientAuthManager.getConfigFlag(KEY, DEFAULT).
 */
object OpenUiPrefs {
    const val KEY: String = "allow_server_open_ui"
    const val DEFAULT: Boolean = true
}

/**
 * Client-side OPEN_UI acceptance policy (spec invariants 1-3): the session must be
 * VERIFIED (attested), the user's toggle must be on, and at most one OPEN_UI is honored
 * per [minIntervalMs] — extras are silently dropped, and DENIED attempts never consume
 * the slot. Pure and clock-injected for unit tests. Single-threaded use (the Fabric
 * client thread that ServerIpc handles payloads on).
 */
class OpenUiGate(private val minIntervalMs: Long = 2_000L) {

    /** Timestamp of the last ACCEPTED handoff; -1 = never. */
    private var lastAcceptedAtMs: Long = -1L

    fun tryAccept(trust: TrustState, allowServerOpenUi: Boolean, nowMs: Long): Boolean {
        if (trust != TrustState.VERIFIED) return false     // invariant 1: unattested -> drop
        if (!allowServerOpenUi) return false               // invariant 2: toggle off -> drop
        if (lastAcceptedAtMs >= 0 && nowMs - lastAcceptedAtMs < minIntervalMs) return false // invariant 3
        lastAcceptedAtMs = nowMs
        return true
    }

    fun reset() {
        lastAcceptedAtMs = -1L
    }
}
