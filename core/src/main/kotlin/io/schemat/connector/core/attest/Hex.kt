package io.schemat.connector.core.attest

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex, the encoding used for nonces in the attestation payload (contract C1). */
fun bytesToHexLower(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        append(HEX_DIGITS[v ushr 4])
        append(HEX_DIGITS[v and 0x0F])
    }
}
