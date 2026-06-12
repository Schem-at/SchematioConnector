package io.schemat.connector.core.modapi

import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.TransportException

/** Scripted transport: enqueue responses, record requests. */
class FakeTransport : ApiTransport {
    val requests = mutableListOf<Pair<ApiRequest, String?>>()
    private val queue = ArrayDeque<Result<ApiResponse>>()

    fun enqueue(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        queue.addLast(Result.success(ApiResponse(status, body.toByteArray(), headers)))
    }

    fun enqueueBinary(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        queue.addLast(Result.success(ApiResponse(status, body, headers)))
    }

    fun enqueueNetworkFailure(message: String = "connection refused") {
        queue.addLast(Result.failure(TransportException(message)))
    }

    override suspend fun execute(request: ApiRequest, bearerToken: String?): ApiResponse {
        requests.add(request to bearerToken)
        val next = queue.removeFirstOrNull() ?: error("FakeTransport queue empty for ${request.method} ${request.path}")
        return next.getOrThrow()
    }

    fun lastRequest(): ApiRequest = requests.last().first
    fun lastToken(): String? = requests.last().second
}
