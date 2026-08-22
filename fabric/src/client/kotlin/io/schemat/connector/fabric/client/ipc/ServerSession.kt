package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.HelloServer
import io.schemat.connector.core.ipc.IpcPlatform
import java.security.SecureRandom

/**
 * How much we trust the connected Schematio server (spec: handshake v2).
 * NONE = no plugin; LEGACY_V1 = v1 plugin (works, unproven identity);
 * UNVERIFIED = v2 plugin, attestation absent/failed; VERIFIED = attested by our backend.
 */
enum class TrustState { NONE, LEGACY_V1, UNVERIFIED, VERIFIED }

/** Per-connection state about the server's Schematio plugin. Reset on join/disconnect. */
object ServerSession {

    private val random = SecureRandom()

    @Volatile var pluginPresent: Boolean = false
        private set
    @Volatile var pluginVersion: String? = null
        private set
    @Volatile var protocolVersion: Int = 0
        private set
    @Volatile var capabilities: Int = 0
        private set

    // --- v2 identity (empty/null until a v2 HELLO_SERVER arrives) ---
    @Volatile var platform: IpcPlatform? = null
        private set
    @Volatile var serverSoftware: String = ""
        private set
    @Volatile var mcVersion: String = ""
        private set
    @Volatile var backendHost: String = ""
        private set
    @Volatile var communityId: String = ""
        private set
    @Volatile var communitySlug: String = ""
        private set

    @Volatile var trust: TrustState = TrustState.NONE
        private set

    /** 16-byte nonce sent inside HELLO_CLIENT this connection; rotated by [reset]. */
    @Volatile var nonce: ByteArray = newNonce()
        private set

    /** Whether we've already announced ourselves to the server this connection. */
    @Volatile private var helloSent: Boolean = false

    private fun newNonce(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun adopt(hello: HelloServer) {
        pluginVersion = hello.pluginVersion
        protocolVersion = hello.protocolVersion
        capabilities = hello.capabilities
        platform = hello.platform
        serverSoftware = hello.serverSoftware
        mcVersion = hello.mcVersion
        backendHost = hello.backendHost
        communityId = hello.communityId
        communitySlug = hello.communitySlug
        pluginPresent = true
        trust = if (hello.protocolVersion >= 2) TrustState.UNVERIFIED else TrustState.LEGACY_V1
    }

    /**
     * Called by the ATTEST flow after the verifier accepts the payload. UNVERIFIED-only, AND
     * only if [expectedNonce] still matches this connection's current nonce — verification runs
     * async, so the player may have disconnected and joined a different server (which rotates
     * the nonce via [reset]) while a stale ATTEST for the OLD connection was in flight. Binding
     * to the nonce (the connection epoch) prevents that late ATTEST from flipping the NEW,
     * unrelated session to VERIFIED. Returns true iff the upgrade was applied.
     */
    @Synchronized
    fun markVerified(expectedNonce: ByteArray): Boolean {
        if (trust == TrustState.UNVERIFIED && nonce.contentEquals(expectedNonce)) {
            trust = TrustState.VERIFIED
            return true
        }
        return false
    }

    /**
     * Marks the client HELLO as sent; returns true only the first time per connection.
     * Both the join-fallback and the reply-to-HELLO_SERVER paths call this, so it keeps
     * us to a single HELLO_CLIENT on the wire when both fire (the common case).
     */
    fun markHelloSent(): Boolean = if (helloSent) false else { helloSent = true; true }

    fun reset() {
        pluginPresent = false
        pluginVersion = null
        protocolVersion = 0
        capabilities = 0
        platform = null
        serverSoftware = ""
        mcVersion = ""
        backendHost = ""
        communityId = ""
        communitySlug = ""
        trust = TrustState.NONE
        nonce = newNonce()
        helloSent = false
    }
}
