package io.schemat.connector.core.attest

import io.schemat.connector.core.crypto.Ed25519
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetString
import kotlin.math.abs

/** Outcome of verifying a relayed ATTEST message on the client. */
sealed class AttestOutcome {
    data class Verified(
        val communityId: String,
        val tokenId: String,
        val platform: String,
    ) : AttestOutcome()

    data class Rejected(val reason: Reason) : AttestOutcome()

    enum class Reason {
        /** keyId not in the key document — caller should refetch once (rotation), then give up. */
        UNKNOWN_KEY,
        BAD_SIGNATURE,
        MALFORMED_PAYLOAD,
        NONCE_MISMATCH,
        STALE_ISSUED_AT,
        COMMUNITY_MISMATCH,
    }
}

/**
 * Pure attestation verification (contract C7). Signature first (over the received payload
 * bytes VERBATIM — never re-serialized), then payload claims. Clock injectable for tests.
 */
object AttestationVerifier {

    /** ±10 min issuedAt window; the nonce carries the real freshness (spec §Risks). */
    const val MAX_CLOCK_SKEW_SECONDS: Long = 600

    fun verify(
        payloadJson: String,
        signature: ByteArray,
        keyId: String,
        keysByKid: Map<String, ByteArray>,
        expectedNonceHex: String,
        expectedCommunityId: String?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): AttestOutcome {
        val key = keysByKid[keyId]
            ?: return AttestOutcome.Rejected(AttestOutcome.Reason.UNKNOWN_KEY)

        if (!Ed25519.verify(key, payloadJson.toByteArray(Charsets.UTF_8), signature)) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.BAD_SIGNATURE)
        }

        val obj = parseJsonSafe(payloadJson)
            ?: return AttestOutcome.Rejected(AttestOutcome.Reason.MALFORMED_PAYLOAD)
        val communityId = obj.safeGetString("communityId")
        val nonce = obj.safeGetString("nonce")
        val platform = obj.safeGetString("platform")
        val tokenId = obj.safeGetString("tokenId")
        val issuedAt = try {
            obj.get("issuedAt")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
        } catch (_: Exception) {
            null
        }
        if (communityId == null || nonce == null || platform == null || tokenId == null || issuedAt == null) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.MALFORMED_PAYLOAD)
        }

        if (nonce != expectedNonceHex) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.NONCE_MISMATCH)
        }
        if (abs(nowEpochSeconds - issuedAt) > MAX_CLOCK_SKEW_SECONDS) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.STALE_ISSUED_AT)
        }
        if (!expectedCommunityId.isNullOrEmpty() && communityId != expectedCommunityId) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.COMMUNITY_MISMATCH)
        }

        return AttestOutcome.Verified(communityId = communityId, tokenId = tokenId, platform = platform)
    }
}
