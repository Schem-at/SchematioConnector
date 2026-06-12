package io.schemat.connector.core.modapi

import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetObject
import io.schemat.connector.core.json.safeGetString
import io.schemat.connector.core.modapi.transport.ApiResponse

/**
 * Sealed error hierarchy for the /mod API client.
 *
 * [fromResponse] maps a non-2xx [ApiResponse] to the matching subtype, extracting
 * the human-readable message from the Laravel-style `message` / `error` JSON keys
 * and (for 422) the per-field `errors` map.
 */
sealed class ApiError {
    /** Network unreachable / timeout - no HTTP response. */
    data object Offline : ApiError()
    data class Unauthorized(val message: String) : ApiError()
    data class Forbidden(val message: String) : ApiError()
    data class NotFound(val message: String) : ApiError()
    data class Validation(val message: String, val fieldErrors: Map<String, List<String>>) : ApiError()

    /**
     * 409 - the resource already exists (e.g. re-uploading a schematic whose file
     * content hash-matches an existing one). [existingUrl] is the web link to the
     * existing resource when the backend includes `existing.url` (a short_id-keyed
     * URL), null otherwise. The slug is deliberately not used as a fallback - it is
     * not a URL, and slug-built links 404 on the website.
     */
    data class Conflict(val message: String, val existingUrl: String? = null) : ApiError()
    data class RateLimited(val retryAfterSeconds: Int?) : ApiError()
    data class Server(val status: Int, val message: String) : ApiError()
    data class Unexpected(val status: Int, val message: String) : ApiError()

    companion object {
        fun fromResponse(response: ApiResponse): ApiError {
            val json = parseJsonSafe(response.bodyAsString())
            val message = json.safeGetString("message")
                ?: json.safeGetString("error")
                ?: "Request failed (HTTP ${response.status})"
            return when (response.status) {
                401 -> Unauthorized(message)
                403 -> Forbidden(message)
                404 -> NotFound(message)
                422 -> {
                    val fields = mutableMapOf<String, List<String>>()
                    json.safeGetObject("errors")?.entrySet()?.forEach { (key, value) ->
                        if (value.isJsonArray) {
                            fields[key] = value.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                        }
                    }
                    Validation(message, fields)
                }
                409 -> Conflict(
                    message,
                    json.safeGetObject("existing")?.safeGetString("url"),
                )
                429 -> RateLimited(
                    response.headers.entries
                        .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                        ?.value?.toIntOrNull()
                )
                in 500..599 -> Server(response.status, message)
                else -> Unexpected(response.status, message)
            }
        }
    }
}

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()

    fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun valueOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): ApiError? = (this as? Failure)?.error
}
