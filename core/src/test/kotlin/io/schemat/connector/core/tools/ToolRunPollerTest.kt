package io.schemat.connector.core.tools

import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.TransportException
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ToolRunPollerTest {

    private fun scripted(vararg bodies: () -> io.schemat.connector.core.modapi.transport.ApiResponse): FakeTransport {
        var i = 0
        return FakeTransport { bodies[minOf(i++, bodies.lastIndex)]() }
    }

    @Test
    fun `polls until done, reporting progress and honouring Retry-After`() = runTest {
        val t = scripted(
            { ok("""{"data":${runJson("queued")}}""") },
            { ok("""{"data":${runJson("running", 0.5, "Placing")}}""", headers = mapOf("Retry-After" to "4")) },
            { ok("""{"data":${runJson("done", 1.0, outputs = DONE_OUTPUTS)}}""") },
        )
        val sleeps = mutableListOf<Long>()
        val updates = mutableListOf<RunStatus>()
        val outcome = ToolRunPoller(ToolsApi(t) { "jwt" }, sleep = { sleeps += it }, now = { 0L })
            .await("019f-run", timeoutSeconds = 120) { updates += it.status }
        val done = assertIs<ToolRunOutcome.Finished>(outcome)
        assertEquals(RunStatus.DONE, done.run.status)
        assertEquals(listOf(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.DONE), updates)
        // Default 1.5 s, then the server's 4 s hint.
        assertEquals(listOf(1500L, 4000L), sleeps)
    }

    @Test
    fun `retry hints are clamped to 1 to 10 seconds`() = runTest {
        val t = scripted(
            { ok("""{"data":${runJson("running")}}""", headers = mapOf("Retry-After" to "0")) },
            { ok("""{"data":${runJson("running")}}""", headers = mapOf("Retry-After" to "60")) },
            { ok("""{"data":${runJson("failed", error = "Graph error")}}""") },
        )
        val sleeps = mutableListOf<Long>()
        val outcome = ToolRunPoller(ToolsApi(t) { "jwt" }, sleep = { sleeps += it }, now = { 0L }).await("r", 120) {}
        assertEquals(listOf(1000L, 10000L), sleeps)
        val failed = assertIs<ToolRunOutcome.Finished>(outcome).run
        assertEquals("Graph error", failed.error)
        assertEquals("execution_failed", failed.errorCode)
    }

    @Test
    fun `a few transient network failures are tolerated, many are not`() = runTest {
        var calls = 0
        val flaky = FakeTransport {
            calls++
            if (calls <= 2) throw TransportException("blip") else ok("""{"data":${runJson("done")}}""")
        }
        assertIs<ToolRunOutcome.Finished>(ToolRunPoller(ToolsApi(flaky) { "jwt" }, sleep = {}, now = { 0L }).await("r", 120) {})

        val down = FakeTransport { throw TransportException("down") }
        val outcome = ToolRunPoller(ToolsApi(down) { "jwt" }, sleep = {}, now = { 0L }).await("r", 120) {}
        assertIs<ToolRunOutcome.Failed>(outcome)
    }

    @Test
    fun `a 4xx while polling stops immediately with that error`() = runTest {
        val t = FakeTransport { ok("""{"error":"remote_tools_not_permitted"}""", 403) }
        val outcome = assertIs<ToolRunOutcome.Failed>(ToolRunPoller(ToolsApi(t) { "jwt" }, sleep = {}, now = { 0L }).await("r", 120) {})
        assertTrue(ToolErrors.isNotPermitted(outcome.error))
        assertEquals(1, t.requests.size)
    }

    @Test
    fun `gives up after the tool timeout plus grace without cancelling the run`() = runTest {
        var clock = 0L
        val t = FakeTransport { ok("""{"data":${runJson("running")}}""") }
        val outcome = ToolRunPoller(ToolsApi(t) { "jwt" }, sleep = { clock += it }, now = { clock })
            .await("r", timeoutSeconds = 10) {}
        assertIs<ToolRunOutcome.StillRunning>(outcome)
        // 10 s tool timeout + 60 s grace (queue wait included).
        assertTrue(clock >= 70_000L)
        assertTrue(t.requests.none { it.first.method == HttpMethod.DELETE })
    }

    @Test
    fun `cancel stops the wait and DELETEs the run`() = runTest {
        val t = FakeTransport { r ->
            if (r.method == HttpMethod.DELETE) ok("""{"data":${runJson("cancelled")}}""") else ok("""{"data":${runJson("running")}}""")
        }
        val poller = ToolRunPoller(ToolsApi(t) { "jwt" }, sleep = {}, now = { 0L })
        var n = 0
        val outcome = poller.await("r", 120, isCancelled = { n++ >= 1 }) {}
        assertEquals(RunStatus.CANCELLED, assertIs<ToolRunOutcome.Finished>(outcome).run.status)
        assertTrue(t.requests.any { it.first.method == HttpMethod.DELETE })
    }
}
