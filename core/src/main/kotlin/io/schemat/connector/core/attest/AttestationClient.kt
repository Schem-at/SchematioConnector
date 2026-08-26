package io.schemat.connector.core.attest

import com.google.gson.JsonObject
import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetString
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.TransportException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A backend-signed attestation, relayed verbatim to the client in an ATTEST message. */
class Attestation(
    val payloadJson: String,
    val signature: ByteArray,
    val keyId: String,
)

/**
 * Server-side attestation fetcher shared by the Bukkit plugin and (later) the Fabric server:
 * POST /plugin/attest with the community token; response cached by nonce so retried HELLOs
 * on the same connection never re-hit the backend. All failures (timeout, HTTP error, junk
 * body) return null — the server then simply sends no ATTEST and the client settles at
 * UNVERIFIED. Never blocks gameplay.
 */
class AttestationClient(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
    private val timeoutMs: Long = 5_000,
) {

    private val cache = ConcurrentHashMap<String, Attestation>()

    suspend fun requestAttestation(nonceHex: String, platform: IpcPlatform): Attestation? {
        cache[nonceHex]?.let { return it }
        val token = tokenProvider() ?: return null

        val body = JsonObject().apply {
            addProperty("nonce_hex", nonceHex)
            addProperty("platform", platform.name)
        }
        val response = try {
            withTimeoutOrNull(timeoutMs) {
                transport.execute(
                    ApiRequest(HttpMethod.POST, "/plugin/attest", jsonBody = body.toString()),
                    token,
                )
            }
        } catch (_: TransportException) {
            null
        } ?: return null

        if (!response.isSuccess) return null
        val json = parseJsonSafe(response.bodyAsString() ?: return null) ?: return null
        val payload = json.safeGetString("payload") ?: return null
        val signatureB64 = json.safeGetString("signature_base64") ?: return null
        val keyId = json.safeGetString("key_id") ?: return null
        val signature = try {
            Base64.getDecoder().decode(signatureB64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (signature.size != 64) return null

        val attestation = Attestation(payload, signature, keyId)
        cache[nonceHex] = attestation
        return attestation
    }
}
