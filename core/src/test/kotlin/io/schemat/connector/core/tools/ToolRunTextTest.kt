package io.schemat.connector.core.tools

import com.google.gson.JsonParser
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ToolRunTextTest {

    private fun run(json: String) = ToolRun.fromJson(JsonParser.parseString(json).asJsonObject)!!

    @Test
    fun `progress lines for each status`() {
        assertEquals("Queued (position 2)", ToolRunText.progressLine(run(runJson("queued"))))
        assertEquals("Running 40%: Placing blocks", ToolRunText.progressLine(run(runJson("running", 0.4, "Placing blocks"))))
        assertEquals("Running", ToolRunText.progressLine(run(runJson("running"))))
        assertEquals("Done", ToolRunText.progressLine(run(runJson("done", 1.0))))
        assertEquals("The tool failed: Graph error", ToolRunText.progressLine(run(runJson("failed", error = "Graph error"))))
    }

    @Test
    fun `zero progress reads as indeterminate, not 0 percent`() {
        // Local runs finish between polls, so the panel only ever saw "Running 0%: Starting".
        assertEquals("Running: Starting", ToolRunText.progressLine(run(runJson("running", 0.0, "Starting"))))
        assertNull(ToolRunText.fraction(run(runJson("running", 0.0))))
        assertEquals("Running 1%", ToolRunText.progressLine(run(runJson("running", 0.01))))
    }

    @Test
    fun `progress fraction is clamped and absent before running`() {
        assertEquals(0.4f, ToolRunText.fraction(run(runJson("running", 0.4))))
        assertEquals(1f, ToolRunText.fraction(run(runJson("running", 7.0))))
        assertNull(ToolRunText.fraction(run(runJson("queued"))))
        assertEquals(1f, ToolRunText.fraction(run(runJson("done"))))
    }

    @Test
    fun `a schematic result with a quick share routes as the quick share`() {
        val r = run(runJson("done", outputs = DONE_OUTPUTS))
        assertEquals(ToolResult.QuickShare("AB12CD", r.outputs.single()), ToolResult.pick(r))
    }

    @Test
    fun `a schematic result without a quick share is downloaded, other kinds are saved`() {
        val plain = run(runJson("done", outputs = """[{"name":"result","kind":"schematic","filename":"r.schem"}]"""))
        assertIs<ToolResult.Download>(ToolResult.pick(plain))
        val image = run(runJson("done", outputs = """[{"name":"preview","kind":"image","filename":"p.png"}]"""))
        assertEquals(ToolResult.SaveOnly(image.outputs.single()), ToolResult.pick(image))
        assertEquals(ToolResult.None, ToolResult.pick(run(runJson("done"))))
    }

    @Test
    fun `a failed quick share delivery keeps its reason and falls back to download`() {
        val r = run(runJson("done", outputs = """[{"name":"result","kind":"schematic","filename":"r.schem","quickshare":null,"quickshare_error":"Quick share could not be created."}]"""))
        val output = r.outputs.single()
        assertEquals("Quick share could not be created.", output.quickshareError)
        assertNull(output.quickshareCode)
        assertEquals(ToolResult.Download(output), ToolResult.pick(r))
        assertEquals(
            "Statue finished, but its quick share failed (Quick share could not be created). Saved to /dl/r.schem instead.",
            ToolRunText.savedMessage("Statue", output, "/dl/r.schem"),
        )
    }

    @Test
    fun `saved outputs are named after the tool so generic server filenames stay distinguishable`() {
        fun out(filename: String?, name: String = "schematic") = RunOutput(name, "schematic", "schem", filename, null, null)
        assertEquals("sdf-playground-schematic.schem", ToolRunText.outputFileName("SDF playground", out("schematic.schem")))
        assertEquals("skin-statue-preview", ToolRunText.outputFileName("Skin statue", out(null, name = "preview")))
        // Server paths never escape the tools folder, and odd characters are replaced.
        assertEquals("rom-generator-rom_1_.schem", ToolRunText.outputFileName("ROM Generator!", out("../../x/rom(1).schem")))
        assertEquals("result.schem", ToolRunText.outputFileName("???", out("result.schem")))
    }

    @Test
    fun `a saved result without a quick share problem says where it went`() {
        val r = run(runJson("done", outputs = """[{"name":"preview","kind":"image","filename":"p.png","quickshare_error":null}]"""))
        val output = r.outputs.single()
        assertNull(output.quickshareError)
        assertEquals("Statue finished. Saved to /dl/p.png", ToolRunText.savedMessage("Statue", output, "/dl/p.png"))
    }

    @Test
    fun `catalogue cache expires and is per actor`() {
        var clock = 0L
        val cache = ToolCatalogueCache(ttlMs = 60_000, now = { clock })
        val tools = catalogue()
        cache.put("a", tools)
        assertEquals(tools, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("hollow", cache.find("a", "HOLLOW")?.id)
        clock = 60_001
        assertNull(cache.get("a"))
    }

    @Test
    fun `tools addressed by a flow uuid can be named by their name instead`() {
        // Published user flows have a UUID id ("ROM Generator"); nobody should have to type it.
        val rom = ToolDefinition("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b", "ROM Generator", null, null, null, null, emptyList(), emptyList(), emptyList())
        assertEquals("rom-generator", rom.commandName)
        assertEquals("image-to-schematic", catalogue()[0].commandName)
        val cache = ToolCatalogueCache()
        cache.put("a", catalogue() + rom)
        assertEquals(rom, cache.find("a", "ROM-Generator"))
        assertEquals(rom, cache.find("a", rom.id))
        assertEquals("image-to-schematic", cache.find("a", "Image-To-Schematic")?.id)
        assertTrue("rom-generator" in cache.commandNames("a"))
        assertTrue(rom.id !in cache.commandNames("a"))
    }

    @Test
    fun `a failed catalogue fetch reaches the caller instead of reading as an unknown tool`() = runTest {
        // A player without the remote-tools grant gets a 403 on the catalogue; /schematio tool <id>
        // used to report "Unknown tool" instead of the permission message that tool list shows.
        val cache = ToolCatalogueCache()
        val denied = ApiError.Forbidden("Forbidden", "remote_tools_not_permitted")
        assertEquals(ApiResult.Failure(denied), cache.lookup("a", "hollow") { ApiResult.Failure(denied) })

        assertEquals("hollow", cache.lookup("a", "HOLLOW") { ApiResult.Success(catalogue()) }.valueOrNull()?.id)
        assertEquals(ApiResult.Success(null), cache.lookup("a", "missing") { ApiResult.Success(catalogue()) })
        // A cached hit does not fetch again.
        assertEquals("hollow", cache.lookup("a", "hollow") { error("no fetch on a hit") }.valueOrNull()?.id)
    }
}
