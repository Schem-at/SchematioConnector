package io.schemat.connector.core.tools

import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.ApiTransport

/** The catalogue example from the remote-tools contract (section 3), plus a conditional input. */
internal const val CATALOGUE_JSON = """
{"data":[{"id":"image-to-schematic","name":"Image to schematic","description":"Turns an image into blocks","icon":"photo",
  "version":"3","runtime":"any","timeout":120,
  "inputs":[{"name":"image","label":"Image","kind":"file","accept":["image/png","image/jpeg"],"max_kb":4096,"required":true},
            {"name":"width","label":"Width","kind":"number","min":1,"max":256,"step":1,"default":64},
            {"name":"palette","label":"Palette","kind":"select","options":[{"value":"wool","label":"Wool"},{"value":"concrete","label":"Concrete"}],"default":"wool"},
            {"name":"dither","label":"Dither","kind":"boolean","default":false},
            {"name":"strength","label":"Dither strength","kind":"slider","min":0,"max":1,"step":0.1,"default":0.5,"when":{"field":"dither","equals":true}},
            {"name":"base","label":"Base schematic","kind":"schematic","required":false}],
  "outputs":[{"name":"result","kind":"schematic"}],
  "can_run_as":["user","community:ore"]},
 {"id":"hollow","name":"Hollow","description":"Hollows a build","inputs":[
            {"name":"source","label":"Source","kind":"schematic","required":true},
            {"name":"fill","label":"Fill block","kind":"block","default":"minecraft:air"},
            {"name":"tint","label":"Tint","kind":"color","default":"#ff5555","description":"Marker colour"},
            {"name":"note","label":"Note","kind":"text"},
            {"name":"future","label":"Future","kind":"hologram"}],
  "outputs":[{"name":"result","kind":"schematic"}],"can_run_as":["user"]}]}
"""

internal fun runJson(
    status: String,
    progress: Double? = null,
    message: String? = null,
    error: String? = null,
    outputs: String = "[]",
) = """{"id":"019f-run","tool":"image-to-schematic","status":"$status",""" +
    (progress?.let { "\"progress\":$it," } ?: "") +
    (message?.let { "\"message\":\"$it\"," } ?: "") +
    (error?.let { "\"error\":{\"code\":\"execution_failed\",\"message\":\"$it\"}," } ?: "\"error\":null,") +
    """"queued_position":2,"outputs":$outputs,"created_at":"2026-11-03T10:00:00Z"}"""

internal const val DONE_OUTPUTS = """[{"name":"result","kind":"schematic","format":"schem","filename":"result.schem","size":18342,
 "download_url":"https://evil.example/steal","quickshare":{"code":"AB12CD","url":"https://schemat.io/share/AB12CD","expires_at":"2026-11-04T10:00:00Z"}}]"""

internal class FakeTransport(private val respond: (ApiRequest) -> ApiResponse) : ApiTransport {
    val requests = mutableListOf<Pair<ApiRequest, String?>>()
    override suspend fun execute(request: ApiRequest, bearerToken: String?): ApiResponse {
        requests += request to bearerToken
        return respond(request)
    }
}

internal fun ok(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
    ApiResponse(status, body.toByteArray(Charsets.UTF_8), headers)

internal fun catalogue(): List<ToolDefinition> = ToolDefinition.listFromJson(CATALOGUE_JSON)
