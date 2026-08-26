package io.schemat.connector.core.modapi

import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetString
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.MultipartFile
import io.schemat.connector.core.modapi.transport.MultipartRequest
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of a draft upload, one variant per contract-C1 mapping row. */
sealed class ClipboardUploadOutcome {
    class Created(val draftId: String, val webUrl: String, val expiresAt: String) : ClipboardUploadOutcome()
    object NotLinked : ClipboardUploadOutcome()
    object Denied : ClipboardUploadOutcome()
    object QuotaExceeded : ClipboardUploadOutcome()
    object TooLarge : ClipboardUploadOutcome()
    object RateLimited : ClipboardUploadOutcome()
    object Unavailable : ClipboardUploadOutcome()
    object Error : ClipboardUploadOutcome()
}

/**
 * POSTs serialized clipboard bytes to `POST /plugin/clipboard/drafts` (contract C1)
 * with the COMMUNITY token. This client never sees user credentials — the draft is
 * inert until the USER publishes it from their own session (spec invariant 1).
 */
class ClipboardUploadClient(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
    private val timeoutMs: Long = 30_000,
) {
    companion object {
        /** Hard cap, checked BEFORE any network call (backend re-checks with 413). */
        const val MAX_UPLOAD_BYTES: Int = 8 * 1024 * 1024
    }

    suspend fun upload(playerUuid: String, schemBytes: ByteArray): ClipboardUploadOutcome {
        if (schemBytes.size > MAX_UPLOAD_BYTES) return ClipboardUploadOutcome.TooLarge
        val token = tokenProvider() ?: return ClipboardUploadOutcome.Unavailable

        val request = ApiRequest(
            method = HttpMethod.POST,
            path = "/plugin/clipboard/drafts",
            multipart = MultipartRequest(
                fields = listOf("player_uuid" to playerUuid),
                files = listOf(
                    MultipartFile("file", "clipboard.schem", "application/octet-stream", schemBytes),
                ),
            ),
        )

        val response = try {
            withTimeoutOrNull(timeoutMs) { transport.execute(request, token) }
        } catch (_: Exception) {
            null
        } ?: return ClipboardUploadOutcome.Unavailable

        return when (response.status) {
            201 -> parseCreated(response)
            400 -> if (errorCode(response) == "player_not_linked") {
                ClipboardUploadOutcome.NotLinked
            } else {
                ClipboardUploadOutcome.Error
            }
            401, 403 -> ClipboardUploadOutcome.Denied
            409 -> ClipboardUploadOutcome.QuotaExceeded
            413 -> ClipboardUploadOutcome.TooLarge
            429 -> ClipboardUploadOutcome.RateLimited
            in 500..599 -> ClipboardUploadOutcome.Unavailable
            else -> ClipboardUploadOutcome.Error
        }
    }

    private fun parseCreated(response: ApiResponse): ClipboardUploadOutcome {
        val json = parseJsonSafe(response.bodyAsString())
        val draftId = json.safeGetString("draft_id")
        val webUrl = json.safeGetString("web_url")
        if (draftId.isNullOrEmpty() || webUrl.isNullOrEmpty()) return ClipboardUploadOutcome.Error
        return ClipboardUploadOutcome.Created(draftId, webUrl, json.safeGetString("expires_at") ?: "")
    }

    private fun errorCode(response: ApiResponse): String? =
        parseJsonSafe(response.bodyAsString()).safeGetString("error")
}
