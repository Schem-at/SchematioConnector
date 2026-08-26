package io.schemat.connector.core.modapi

import com.google.gson.JsonObject
import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.ResponseTooLargeException
import io.schemat.connector.core.modapi.transport.TransportException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Outcome of a reference-pull clipboard resolve. Maps 1:1 onto terminal STATUS
 * states (contract B1): Denied -> DENIED, NotFound -> NOT_FOUND, TooLarge ->
 * TOO_LARGE, RateLimited -> RATE_LIMITED, Unavailable -> UNAVAILABLE, Error -> ERROR.
 */
sealed class ClipboardResolveOutcome {
    class Bytes(val bytes: ByteArray, val format: String) : ClipboardResolveOutcome()
    object Denied : ClipboardResolveOutcome()
    object NotFound : ClipboardResolveOutcome()
    object TooLarge : ClipboardResolveOutcome()
    object RateLimited : ClipboardResolveOutcome()
    object Unavailable : ClipboardResolveOutcome()
    object Error : ClipboardResolveOutcome()
}

/**
 * Server-side (plugin) client for POST /plugin/clipboard/resolve. Enforces the
 * client-side caps REGARDLESS of what the backend claims: Content-Length is
 * required and <= [maxBytes]; the received byte count is re-checked after the
 * transport's own byte-counted read (defense against a lying Content-Length).
 */
class ClipboardResolveClient(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
    private val timeoutMs: Long = 30_000,
    private val maxBytes: Int = MAX_SCHEMATIC_BYTES,
) {

    companion object {
        /** Hard schematic cap (contract B1): 8 MiB. */
        const val MAX_SCHEMATIC_BYTES: Int = 8 * 1024 * 1024
    }

    suspend fun resolve(
        playerUuid: String,
        refType: LoadRefType,
        refId: String,
        versionId: String,
    ): ClipboardResolveOutcome {
        val token = tokenProvider() ?: return ClipboardResolveOutcome.Unavailable

        val body = JsonObject().apply {
            addProperty("player_uuid", playerUuid)
            addProperty("ref_type", if (refType == LoadRefType.SHARE_TOKEN) "share" else "schematic")
            addProperty("ref_id", refId)
            if (versionId.isNotEmpty()) addProperty("version_id", versionId)
        }

        val response = try {
            withTimeoutOrNull(timeoutMs) {
                transport.execute(
                    ApiRequest(HttpMethod.POST, "/plugin/clipboard/resolve", jsonBody = body.toString()),
                    token,
                )
            }
        } catch (_: ResponseTooLargeException) {
            return ClipboardResolveOutcome.TooLarge
        } catch (_: TransportException) {
            return ClipboardResolveOutcome.Unavailable
        } ?: return ClipboardResolveOutcome.Unavailable

        return when {
            response.status == 200 -> parseBytes(response)
            response.status == 401 || response.status == 403 -> ClipboardResolveOutcome.Denied
            response.status == 404 -> ClipboardResolveOutcome.NotFound
            response.status == 413 -> ClipboardResolveOutcome.TooLarge
            response.status == 429 -> ClipboardResolveOutcome.RateLimited
            response.status >= 500 -> ClipboardResolveOutcome.Unavailable
            else -> ClipboardResolveOutcome.Error
        }
    }

    private fun parseBytes(response: ApiResponse): ClipboardResolveOutcome {
        // Content-Length is mandatory (contract B1) — its absence is a protocol violation.
        val declared = header(response, "Content-Length")?.toLongOrNull()
            ?: return ClipboardResolveOutcome.Error
        if (declared > maxBytes) return ClipboardResolveOutcome.TooLarge

        val bytes = response.body ?: return ClipboardResolveOutcome.Error
        if (bytes.size > maxBytes) return ClipboardResolveOutcome.TooLarge // lying Content-Length

        val format = header(response, "X-Schematio-Format")?.takeIf { it.isNotBlank() } ?: "schem"
        return ClipboardResolveOutcome.Bytes(bytes, format)
    }

    private fun header(response: ApiResponse, name: String): String? =
        response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
