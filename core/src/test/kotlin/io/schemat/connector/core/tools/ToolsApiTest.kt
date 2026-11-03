package io.schemat.connector.core.tools

import com.google.gson.JsonParser
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.TransportException
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ToolsApiTest {

    @Test
    fun `catalogue is a GET with the token and no actor header for player callers`() = runTest {
        val t = FakeTransport { ok(CATALOGUE_JSON) }
        val api = ToolsApi(t) { "player-jwt" }
        val tools = (api.catalogue() as ApiResult.Success).value
        assertEquals(2, tools.size)
        val (req, token) = t.requests.single()
        assertEquals(HttpMethod.GET, req.method)
        assertEquals("/tools", req.path)
        assertEquals("player-jwt", token)
        assertEquals(emptyMap(), req.headers)
    }

    @Test
    fun `plugin callers send X-Actor-Uuid on GET and actor_uuid in the POST body`() = runTest {
        val t = FakeTransport { r -> if (r.method == HttpMethod.POST) ok("""{"data":${runJson("queued")}}""", 202) else ok(CATALOGUE_JSON) }
        val api = ToolsApi(t) { "community-token" }
        val actor = "0f1e2d3c-0000-4000-8000-000000000001"
        api.catalogue(actor)
        api.startRun("hollow", RunRequest(mapOf("source" to ToolInputValue.ClipboardRef("c1"))), actor)
        assertEquals(mapOf("X-Actor-Uuid" to actor), t.requests[0].first.headers)
        val post = t.requests[1].first
        assertEquals("/tools/hollow/runs", post.path)
        val body = JsonParser.parseString(post.jsonBody).asJsonObject
        assertEquals(actor, body["actor_uuid"].asString)
        assertEquals(emptyMap(), post.headers)
    }

    @Test
    fun `startRun posts inputs, community and deliver and parses the 202 run`() = runTest {
        val t = FakeTransport { ok("""{"data":${runJson("queued")}}""", 202) }
        val api = ToolsApi(t) { "jwt" }
        val run = (
            api.startRun(
                "image-to-schematic",
                RunRequest(
                    inputs = mapOf("width" to ToolInputValue.Number(64.0), "base" to ToolInputValue.SchematicRef("FqI-IX")),
                    community = "ore",
                    deliver = listOf("download", "quickshare"),
                ),
            ) as ApiResult.Success
            ).value
        assertEquals(RunStatus.QUEUED, run.status)
        val body = JsonParser.parseString(t.requests.single().first.jsonBody).asJsonObject
        assertEquals(64, body["inputs"].asJsonObject["width"].asInt)
        assertEquals("FqI-IX", body["inputs"].asJsonObject["base"].asJsonObject["\$schematic"].asString)
        assertEquals("ore", body["community"].asString)
        assertEquals(listOf("download", "quickshare"), body["deliver"].asJsonArray.map { it.asString })
        assertNull(body["actor_uuid"])
    }

    @Test
    fun `tool ids are path-encoded`() = runTest {
        val t = FakeTransport { ok("""{"data":${runJson("queued")}}""", 202) }
        ToolsApi(t) { "jwt" }.startRun("a b/c", RunRequest(emptyMap()))
        assertEquals("/tools/a%20b%2Fc/runs", t.requests.single().first.path)
    }

    @Test
    fun `poll returns the run and the Retry-After hint`() = runTest {
        val t = FakeTransport { ok("""{"data":${runJson("running", 0.4, "Placing blocks")}}""", headers = mapOf("retry-after" to "3")) }
        val polled = (ToolsApi(t) { "jwt" }.run("019f-run") as ApiResult.Success).value
        assertEquals(RunStatus.RUNNING, polled.run.status)
        assertEquals(0.4, polled.run.progress)
        assertEquals(3, polled.retryAfterSeconds)
        assertEquals("/tools/runs/019f-run", t.requests.single().first.path)
    }

    @Test
    fun `cancel is a DELETE on the run`() = runTest {
        val t = FakeTransport { ok("""{"data":${runJson("cancelled")}}""") }
        ToolsApi(t) { "jwt" }.cancel("019f-run", "uuid-1")
        val req = t.requests.single().first
        assertEquals(HttpMethod.DELETE, req.method)
        assertEquals("/tools/runs/019f-run", req.path)
        assertEquals(mapOf("X-Actor-Uuid" to "uuid-1"), req.headers)
    }

    @Test
    fun `outputs are fetched from the API base, never from the absolute download_url`() = runTest {
        val t = FakeTransport { ApiResponse(200, byteArrayOf(9, 8, 7)) }
        val run = ToolRun.fromJson(JsonParser.parseString(runJson("done", outputs = DONE_OUTPUTS)).asJsonObject)!!
        val bytes = (ToolsApi(t) { "jwt" }.downloadOutput(run, run.outputs.single()) as ApiResult.Success).value
        assertTrue(bytes.contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals("/tools/runs/019f-run/outputs/result", t.requests.single().first.path)
    }

    @Test
    fun `remote_tools_not_permitted is recognised and explained`() = runTest {
        val t = FakeTransport { ok("""{"error":"remote_tools_not_permitted"}""", 403) }
        val err = (ToolsApi(t) { "jwt" }.catalogue() as ApiResult.Failure).error
        assertTrue(ToolErrors.isNotPermitted(err))
        assertTrue(ToolErrors.userMessage(err).contains("permission", ignoreCase = true))
        assertTrue(!ToolErrors.userMessage(err).contains("remote_tools_not_permitted"))
    }

    @Test
    fun `remote_tools_not_permitted is recognised when the server also sends a message`() = runTest {
        // The live schemat.io body: both the machine code and a human message.
        for (message in listOf(
            "Remote tool calling is not enabled for this account.",
            "Server tokens must name an actor_uuid who is an active member of this community.",
        )) {
            val t = FakeTransport { ok("""{"error":"remote_tools_not_permitted","message":"$message"}""", 403) }
            val err = (ToolsApi(t) { "jwt" }.catalogue() as ApiResult.Failure).error
            assertTrue(ToolErrors.isNotPermitted(err), message)
            assertTrue(!ToolErrors.userMessage(err).contains("actor_uuid"), "no API jargon for players")
        }
    }

    @Test
    fun `an unrelated 403 is not mistaken for the remote tools refusal`() = runTest {
        val t = FakeTransport { ok("""{"error":"forbidden","message":"This schematic is private."}""", 403) }
        val err = (ToolsApi(t) { "jwt" }.catalogue() as ApiResult.Failure).error
        assertTrue(!ToolErrors.isNotPermitted(err))
        assertEquals("Not allowed: This schematic is private.", ToolErrors.userMessage(err))
    }

    @Test
    fun `error codes map to clear messages`() {
        assertTrue(ToolErrors.userMessage(ApiError.Server(503, "x")).contains("disabled", ignoreCase = true))
        assertTrue(ToolErrors.userMessage(ApiError.RateLimited(30)).contains("30"))
        assertTrue(ToolErrors.userMessage(ApiError.NotFound("x")).contains("not found", ignoreCase = true))
        assertTrue(ToolErrors.userMessage(ApiError.Unauthorized("x")).contains("sign in", ignoreCase = true))
        val v = ApiError.Validation("The given data was invalid.", mapOf("width" to listOf("Too wide")))
        assertEquals("width: Too wide", ToolErrors.userMessage(v))
        assertTrue(ToolErrors.userMessage(ApiError.Offline).contains("reach", ignoreCase = true))
    }

    @Test
    fun `a missing token fails as Unauthorized without a request`() = runTest {
        val t = FakeTransport { ok(CATALOGUE_JSON) }
        assertIs<ApiError.Unauthorized>((ToolsApi(t) { null }.catalogue() as ApiResult.Failure).error)
        assertEquals(0, t.requests.size)
    }

    @Test
    fun `transport failure is Offline and malformed bodies are Unexpected`() = runTest {
        val down = FakeTransport { throw TransportException("no route") }
        assertEquals(ApiError.Offline, (ToolsApi(down) { "jwt" }.catalogue() as ApiResult.Failure).error)
        val junk = FakeTransport { ok("not json") }
        assertIs<ApiError.Unexpected>((ToolsApi(junk) { "jwt" }.run("x") as ApiResult.Failure).error)
    }
}
