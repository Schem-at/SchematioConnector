package io.schemat.connector.core.crypto

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verify-only Ed25519 over JDK `java.security` (JDK >= 15; this project targets 21 — no
 * dependencies). The backend serves raw 32-byte public keys; the JDK wants X.509
 * SubjectPublicKeyInfo, which for Ed25519 is a constant 12-byte DER header + the raw key.
 */
object Ed25519 {

    /** DER: SEQUENCE(42) { SEQUENCE(5) { OID 1.3.101.112 } BIT STRING(33, 0 unused) }. */
    private val DER_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    /**
     * @param publicKeyRaw raw 32-byte Ed25519 public key (as served in schematio-keys.json)
     * @param message the exact signed bytes (canonical JSON payload as UTF-8)
     * @param signature 64-byte detached signature
     * @return true iff the signature verifies; false on any malformed input (never throws)
     */
    fun verify(publicKeyRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKeyRaw.size != 32 || signature.size != 64) return false
        return try {
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(DER_PREFIX + publicKeyRaw))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }
}
