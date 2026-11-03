package io.schemat.connector.core.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.TransportException
import java.net.URLEncoder

/** The body of `POST /tools/{tool}/runs`. [community] runs the tool as that community (its role grant and quota). */
data class RunRequest(
    val inputs: Map<String, ToolInputValue>,
    val community: String? = null,
    val deliver: List<String> = listOf("download"),
)

/** A polled run plus the server's `Retry-After` hint in seconds. */
data class PolledRun(val run: ToolRun, val retryAfterSeconds: Int?)

/**
 * Client for the remote-tools API, shared by the mod (player JWT) and the server plugins
 * (community token). Plugin calls pass the in-game player's uuid as [actorUuid]: it goes
 * in the JSON body of a run POST and as `X-Actor-Uuid` on every other request.
 *
 * Outputs are always fetched from `/tools/runs/{id}/outputs/{name}` under the transport's
 * API base, never from the run's absolute `download_url`, so the bearer token is never
 * sent to another host.
 */
class ToolsApi(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
) {

    suspend fun catalogue(actorUuid: String? = null): ApiResult<List<ToolDefinition>> =
        json(ApiRequest(HttpMethod.GET, "/tools", headers = actorHeader(actorUuid))).map { root ->
            root.getAsJsonArray("data")?.mapNotNull { if (it.isJsonObject) ToolDefinition.fromJson(it.asJsonObject) else null }
                ?: emptyList()
        }

    suspend fun tool(id: String, actorUuid: String? = null): ApiResult<ToolDefinition> =
        json(ApiRequest(HttpMethod.GET, "/tools/${enc(id)}", headers = actorHeader(actorUuid)))
            .parsed { ToolDefinition.fromJson(it.data()) }

    suspend fun startRun(toolId: String, request: RunRequest, actorUuid: String? = null): ApiResult<ToolRun> {
        val body = JsonObject().apply {
            add("inputs", JsonObject().apply { request.inputs.forEach { (name, value) -> add(name, value.toJson()) } })
            request.community?.let { addProperty("community", it) }
            add("deliver", JsonArray().apply { request.deliver.forEach { add(it) } })
            actorUuid?.let { addProperty("actor_uuid", it) }
        }
        return json(ApiRequest(HttpMethod.POST, "/tools/${enc(toolId)}/runs", jsonBody = body.toString()))
            .parsed { ToolRun.fromJson(it.data()) }
    }

    suspend fun run(runId: String, actorUuid: String? = null): ApiResult<PolledRun> {
        val response = when (val r = send(ApiRequest(HttpMethod.GET, "/tools/runs/${enc(runId)}", headers = actorHeader(actorUuid)))) {
            is ApiResult.Failure -> return r
            is ApiResult.Success -> r.value
        }
        val run = parseObject(response.bodyAsString())?.let { ToolRun.fromJson(it.data()) }
            ?: return ApiResult.Failure(ApiError.Unexpected(response.status, "Malformed run response"))
        val retryAfter = response.headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.trim()?.toIntOrNull()
        return ApiResult.Success(PolledRun(run, retryAfter))
    }

    suspend fun cancel(runId: String, actorUuid: String? = null): ApiResult<ToolRun> =
        json(ApiRequest(HttpMethod.DELETE, "/tools/runs/${enc(runId)}", headers = actorHeader(actorUuid)))
            .parsed { ToolRun.fromJson(it.data()) }

    suspend fun downloadOutput(run: ToolRun, output: RunOutput, actorUuid: String? = null): ApiResult<ByteArray> =
        send(ApiRequest(HttpMethod.GET, "/tools/runs/${enc(run.id)}/outputs/${enc(output.name)}", headers = actorHeader(actorUuid)))
            .map { it.body ?: ByteArray(0) }

    // ---- plumbing ----

    private suspend fun send(request: ApiRequest): ApiResult<ApiResponse> {
        val token = tokenProvider()
            ?: return ApiResult.Failure(ApiError.Unauthorized("Not signed in to schemat.io"))
        val response = try {
            transport.execute(request, token)
        } catch (_: TransportException) {
            return ApiResult.Failure(ApiError.Offline)
        }
        if (!response.isSuccess) return ApiResult.Failure(ApiError.fromResponse(response))
        return ApiResult.Success(response)
    }

    private suspend fun json(request: ApiRequest): ApiResult<JsonObject> = when (val r = send(request)) {
        is ApiResult.Failure -> r
        is ApiResult.Success -> parseObject(r.value.bodyAsString())?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError.Unexpected(r.value.status, "Malformed response body"))
    }

    private fun <T> ApiResult<JsonObject>.parsed(parse: (JsonObject) -> T?): ApiResult<T> = when (this) {
        is ApiResult.Failure -> this
        is ApiResult.Success -> parse(value)?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError.Unexpected(200, "Malformed response body"))
    }

    /** The `{"data": {...}}` envelope's object, or the body itself when unwrapped. */
    private fun JsonObject.data(): JsonObject = get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: this

    private fun actorHeader(actorUuid: String?): Map<String, String> =
        actorUuid?.let { mapOf("X-Actor-Uuid" to it) } ?: emptyMap()

    private fun enc(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
}
