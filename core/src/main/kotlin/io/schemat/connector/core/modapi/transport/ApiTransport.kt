package io.schemat.connector.core.modapi.transport

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

/** One file part of a multipart request. */
data class MultipartFile(
    val fieldName: String,
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MultipartFile) return false
        return fieldName == other.fieldName &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fieldName.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * A single API request. Exactly one of [jsonBody] / [multipart] may be non-null.
 * [path] is relative to the API base (e.g. "/mod/me"); [query] is appended URL-encoded.
 */
data class ApiRequest(
    val method: HttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val jsonBody: String? = null,
    val multipart: MultipartRequest? = null,
)

/**
 * [fields] is an ordered list of name/value pairs (not a map) because Laravel array
 * inputs are sent as repeated `name[]` keys, which a map cannot represent.
 */
data class MultipartRequest(
    val fields: List<Pair<String, String>>,
    val files: List<MultipartFile>,
)

data class ApiResponse(
    val status: Int,
    val body: ByteArray?,
    val headers: Map<String, String> = emptyMap(),
) {
    fun bodyAsString(): String? = body?.toString(Charsets.UTF_8)
    val isSuccess: Boolean get() = status in 200..299

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApiResponse) return false
        return status == other.status &&
            (body?.contentEquals(other.body ?: return false) ?: (other.body == null)) &&
            headers == other.headers
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        return result
    }
}

/** Thrown/returned transport-level failure (no HTTP response at all). */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface ApiTransport {
    /**
     * Execute the request with the given bearer token (null = unauthenticated).
     * @throws TransportException on network-level failure (no response).
     */
    suspend fun execute(request: ApiRequest, bearerToken: String?): ApiResponse
}
