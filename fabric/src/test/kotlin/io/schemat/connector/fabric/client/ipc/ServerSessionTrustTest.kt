package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.HelloServer
import io.schemat.connector.core.ipc.IpcPlatform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ServerSessionTrustTest {

    @BeforeEach
    fun setUp() = ServerSession.reset()

    @AfterEach
    fun tearDown() = ServerSession.reset()

    private fun v2Hello() = HelloServer(
        protocolVersion = 2,
        pluginVersion = "1.4.0",
        capabilities = 1,
        platform = IpcPlatform.PAPER_PLUGIN,
        serverSoftware = "Paper 1.21.8",
        mcVersion = "1.21.8",
        backendHost = "https://schemat.io",
        communityId = "community-1",
        communitySlug = "build-team",
    )

    @Test
    fun `starts at NONE with a 16-byte nonce`() {
        assertEquals(TrustState.NONE, ServerSession.trust)
        assertEquals(16, ServerSession.nonce.size)
    }

    @Test
    fun `v1 hello lands at LEGACY_V1 with empty identity`() {
        ServerSession.adopt(HelloServer(1, "1.2.4", 1))
        assertEquals(TrustState.LEGACY_V1, ServerSession.trust)
        assertTrue(ServerSession.pluginPresent)
        assertEquals(null, ServerSession.platform)
        assertEquals("", ServerSession.communityId)
    }

    @Test
    fun `v2 hello lands at UNVERIFIED and carries identity`() {
        ServerSession.adopt(v2Hello())
        assertEquals(TrustState.UNVERIFIED, ServerSession.trust)
        assertEquals(IpcPlatform.PAPER_PLUGIN, ServerSession.platform)
        assertEquals("community-1", ServerSession.communityId)
        assertEquals("https://schemat.io", ServerSession.backendHost)
    }

    @Test
    fun `markVerified only upgrades UNVERIFIED`() {
        assertFalse(ServerSession.markVerified(ServerSession.nonce)) // NONE — must not move
        assertEquals(TrustState.NONE, ServerSession.trust)

        ServerSession.adopt(HelloServer(1, "1.2.4", 0))
        assertFalse(ServerSession.markVerified(ServerSession.nonce)) // LEGACY_V1 — must not move
        assertEquals(TrustState.LEGACY_V1, ServerSession.trust)

        ServerSession.reset()
        ServerSession.adopt(v2Hello())
        assertTrue(ServerSession.markVerified(ServerSession.nonce))
        assertEquals(TrustState.VERIFIED, ServerSession.trust)
    }

    @Test
    fun `markVerified rejects a nonce that does not match the current connection`() {
        ServerSession.adopt(v2Hello())
        val staleNonce = ByteArray(16) // definitely not the real (random) nonce

        assertFalse(ServerSession.markVerified(staleNonce))
        assertEquals(TrustState.UNVERIFIED, ServerSession.trust)
    }

    @Test
    fun `reconnect trust-race - stale ATTEST verified against an old nonce cannot verify the new session`() {
        // Simulate: connect to server A, HELLO_SERVER arrives, ATTEST verification kicks off
        // async and captures the current (N1) nonce as its expectation.
        ServerSession.adopt(v2Hello())
        val n1 = ServerSession.nonce.copyOf()

        // Player disconnects from A and joins server B before the async verify for A completes.
        // reset() rotates to a fresh nonce (N2) — the new connection's epoch.
        ServerSession.reset()
        ServerSession.adopt(v2Hello())
        val n2 = ServerSession.nonce.copyOf()
        assertFalse(n1.contentEquals(n2))

        // The late-arriving, validly-signed ATTEST for the OLD server (A) now resolves and
        // attempts to upgrade trust using the nonce it captured before the reconnect (N1).
        val applied = ServerSession.markVerified(n1)

        assertFalse(applied)
        assertEquals(TrustState.UNVERIFIED, ServerSession.trust) // B's session must NOT be VERIFIED

        // The happy path still works: verifying against B's actual current nonce succeeds.
        assertTrue(ServerSession.markVerified(n2))
        assertEquals(TrustState.VERIFIED, ServerSession.trust)
    }

    @Test
    fun `reset clears trust and rotates the nonce`() {
        val before = ServerSession.nonce.copyOf()
        ServerSession.adopt(v2Hello())
        ServerSession.markVerified(ServerSession.nonce)

        ServerSession.reset()

        assertEquals(TrustState.NONE, ServerSession.trust)
        assertFalse(ServerSession.pluginPresent)
        assertEquals(16, ServerSession.nonce.size)
        assertFalse(before.contentEquals(ServerSession.nonce)) // 2^-128 flake risk: acceptable
    }

    @Test
    fun `markHelloSent stays single-shot per connection`() {
        assertTrue(ServerSession.markHelloSent())
        assertFalse(ServerSession.markHelloSent())
        ServerSession.reset()
        assertTrue(ServerSession.markHelloSent())
    }
}
