package io.schemat.connector.core.attest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

class AttestationVerifierTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val rawPublic: ByteArray =
        keyPair.public.encoded.copyOfRange(keyPair.public.encoded.size - 32, keyPair.public.encoded.size)
    private val nonceHex = "000102030405060708090a0b0c0d0e0f"
    private val now = 1_760_000_000L

    private fun payload(
        communityId: String = "community-1",
        issuedAt: Long = now,
        nonce: String = nonceHex,
        platform: String = "PAPER_PLUGIN",
        tokenId: String = "token-1",
    ): String =
        """{"communityId":"$communityId","issuedAt":$issuedAt,"nonce":"$nonce","platform":"$platform","tokenId":"$tokenId"}"""

    private fun sign(payload: String): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(keyPair.private)
        update(payload.toByteArray(Charsets.UTF_8))
        sign()
    }

    private fun verify(
        payload: String,
        signature: ByteArray = sign(payload),
        keyId: String = "k1",
        keys: Map<String, ByteArray> = mapOf("k1" to rawPublic),
        expectedNonceHex: String = nonceHex,
        expectedCommunityId: String? = "community-1",
    ): AttestOutcome = AttestationVerifier.verify(
        payloadJson = payload,
        signature = signature,
        keyId = keyId,
        keysByKid = keys,
        expectedNonceHex = expectedNonceHex,
        expectedCommunityId = expectedCommunityId,
        nowEpochSeconds = now,
    )

    @Test
    fun `accepts a valid attestation`() {
        val outcome = verify(payload())
        assertTrue(outcome is AttestOutcome.Verified)
        assertEquals("community-1", (outcome as AttestOutcome.Verified).communityId)
        assertEquals("token-1", outcome.tokenId)
        assertEquals("PAPER_PLUGIN", outcome.platform)
    }

    @Test
    fun `rejects an unknown key id (rotation refetch trigger)`() {
        val outcome = verify(payload(), keyId = "k2")
        assertEquals(AttestOutcome.Rejected(AttestOutcome.Reason.UNKNOWN_KEY), outcome)
    }

    @Test
    fun `accepts a signature made by a rotated (second) key in the document`() {
        val outcome = verify(
            payload(),
            keyId = "k2",
            keys = mapOf("k1" to ByteArray(32), "k2" to rawPublic),
        )
        assertTrue(outcome is AttestOutcome.Verified)
    }

    @Test
    fun `rejects a bad signature`() {
        val bad = sign(payload()).also { it[3] = (it[3].toInt() xor 0x40).toByte() }
        assertEquals(AttestOutcome.Rejected(AttestOutcome.Reason.BAD_SIGNATURE), verify(payload(), signature = bad))
    }

    @Test
    fun `rejects a signed-but-wrong nonce (replay)`() {
        val outcome = verify(payload(nonce = "ffffffffffffffffffffffffffffffff"))
        assertEquals(AttestOutcome.Rejected(AttestOutcome.Reason.NONCE_MISMATCH), outcome)
    }

    @Test
    fun `rejects a stale issuedAt beyond plus-minus 10 minutes`() {
        assertEquals(
            AttestOutcome.Rejected(AttestOutcome.Reason.STALE_ISSUED_AT),
            verify(payload(issuedAt = now - 601)),
        )
        assertEquals(
            AttestOutcome.Rejected(AttestOutcome.Reason.STALE_ISSUED_AT),
            verify(payload(issuedAt = now + 601)),
        )
        assertTrue(verify(payload(issuedAt = now - 600)) is AttestOutcome.Verified)
    }

    @Test
    fun `rejects a community mismatch (server claimed a different community)`() {
        val outcome = verify(payload(communityId = "community-2"))
        assertEquals(AttestOutcome.Rejected(AttestOutcome.Reason.COMMUNITY_MISMATCH), outcome)
    }

    @Test
    fun `skips the community check when the server claimed none (empty or null)`() {
        assertTrue(verify(payload(), expectedCommunityId = null) is AttestOutcome.Verified)
        assertTrue(verify(payload(), expectedCommunityId = "") is AttestOutcome.Verified)
    }

    @Test
    fun `rejects malformed payloads that are validly signed`() {
        assertEquals(
            AttestOutcome.Rejected(AttestOutcome.Reason.MALFORMED_PAYLOAD),
            verify("""{"not":"an attestation"}"""),
        )
        assertEquals(
            AttestOutcome.Rejected(AttestOutcome.Reason.MALFORMED_PAYLOAD),
            verify("not json at all"),
        )
    }

    @Test
    fun `hex helper is lowercase and byte-exact`() {
        assertEquals("000102ff", bytesToHexLower(byteArrayOf(0, 1, 2, -1)))
        assertEquals("", bytesToHexLower(ByteArray(0)))
    }
}
