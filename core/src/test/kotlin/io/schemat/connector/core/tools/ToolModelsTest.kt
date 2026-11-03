package io.schemat.connector.core.tools

import com.google.gson.JsonParser
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ToolModelsTest {

    @Test
    fun `catalogue parses every contract field`() {
        val tools = catalogue()
        assertEquals(listOf("image-to-schematic", "hollow"), tools.map { it.id })
        val t = tools[0]
        assertEquals("Image to schematic", t.name)
        assertEquals(120, t.timeoutSeconds)
        assertEquals(listOf("user", "community:ore"), t.canRunAs)
        assertEquals(listOf("ore"), t.runAsCommunities)
        assertTrue(t.canRunAsUser)

        val image = t.input("image")!!
        assertEquals(InputKind.FILE, image.kind)
        assertEquals(listOf("image/png", "image/jpeg"), image.accept)
        assertEquals(4096, image.maxKb)
        assertTrue(image.required)

        val width = t.input("width")!!
        assertEquals(InputKind.NUMBER, width.kind)
        assertEquals(1.0, width.min)
        assertEquals(256.0, width.max)
        assertEquals(1.0, width.step)
        assertEquals(64.0, width.default?.asDouble)

        assertEquals(listOf("wool" to "Wool", "concrete" to "Concrete"), t.input("palette")!!.options.map { it.value to it.label })
        val strength = t.input("strength")!!
        assertEquals("dither", strength.whenField)
        assertEquals(true, strength.whenEquals?.asBoolean)
        assertEquals(listOf("result"), t.outputs.map { it.name })
        assertEquals(listOf("schematic"), t.outputs.map { it.kind })
    }

    @Test
    fun `a blank description has no summary, so lists do not print a dangling colon`() {
        // "rom-generator - ROM Generator: " in /schematio tool list.
        assertNull(catalogue()[0].copy(description = "  ").summary)
        assertNull(catalogue()[0].copy(description = null).summary)
        assertEquals("Turns an image into blocks", catalogue()[0].summary)
    }

    @Test
    fun `unknown input kinds parse as UNKNOWN instead of failing the catalogue`() {
        val hollow = catalogue()[1]
        assertEquals(InputKind.UNKNOWN, hollow.input("future")!!.kind)
        assertEquals("Marker colour", hollow.input("tint")!!.description)
        assertEquals(false, hollow.canRunAsUser.not())
        assertEquals(emptyList(), hollow.runAsCommunities)
    }

    @Test
    fun `run-as choices list the caller first and the picker shows whenever a community pays`() {
        fun def(vararg canRunAs: String) = catalogue()[0].copy(canRunAs = canRunAs.toList())
        val both = def("user", "community:ore")
        assertEquals(listOf(null, "ore"), both.runAsChoices)
        assertTrue(both.showsRunAsPicker)
        // A member without their own grant can only run as the community: still show who pays.
        val communityOnly = def("community:ore")
        assertEquals(listOf("ore"), communityOnly.runAsChoices)
        assertTrue(communityOnly.showsRunAsPicker)
        val userOnly = def("user")
        assertEquals(listOf<String?>(null), userOnly.runAsChoices)
        assertEquals(false, userOnly.showsRunAsPicker)
    }

    @Test
    fun `number inputs display as many decimals as their step and bounds need`() {
        val width = catalogue()[0].input("width")!!
        assertEquals("%.0f", width.numberFormat)
        assertEquals("%.2f", width.copy(step = 0.25).numberFormat)
        assertEquals("%.1f", width.copy(step = null, min = 0.0, max = 1.0, default = JsonParser.parseString("0.5")).numberFormat)
        assertEquals("%.0f", width.copy(step = null, min = 2.0, max = 64.0, default = JsonParser.parseString("16")).numberFormat)
        assertEquals("%.6f", width.copy(step = 1e-9).numberFormat)
    }

    @Test
    fun `run parses status, progress and quickshare output`() {
        val run = ToolRun.fromJson(JsonParser.parseString(runJson("done", 1.0, "Finished", outputs = DONE_OUTPUTS)).asJsonObject)!!
        assertEquals(RunStatus.DONE, run.status)
        assertTrue(run.status.terminal)
        assertEquals(1.0, run.progress)
        assertEquals("Finished", run.message)
        val out = run.outputs.single()
        assertEquals("result", out.name)
        assertEquals("schematic", out.kind)
        assertEquals("result.schem", out.filename)
        assertEquals(18342L, out.size)
        assertEquals("AB12CD", out.quickshareCode)
        assertNull(run.error)
        assertEquals(2, run.queuedPosition)
    }

    @Test
    fun `terminal statuses`() {
        assertEquals(
            setOf(RunStatus.DONE, RunStatus.FAILED, RunStatus.TIMEOUT, RunStatus.CANCELLED),
            RunStatus.entries.filter { it.terminal }.toSet(),
        )
        assertEquals(RunStatus.UNKNOWN, RunStatus.parse("exploded"))
    }

    @Test
    fun `input values encode as the contract json`() {
        assertEquals("""{"${'$'}base64":"AQID","filename":"sel.schem"}""", ToolInputValue.Upload(byteArrayOf(1, 2, 3), "sel.schem").toJson().toString())
        assertEquals("""{"${'$'}schematic":"FqI-IX"}""", ToolInputValue.SchematicRef("FqI-IX").toJson().toString())
        assertEquals("""{"${'$'}quickshare":"AB12CD"}""", ToolInputValue.QuickShareRef("AB12CD").toJson().toString())
        assertEquals("""{"${'$'}clipboard":"draft-1"}""", ToolInputValue.ClipboardRef("draft-1").toJson().toString())
        assertEquals("64", ToolInputValue.Number(64.0).toJson().toString())
        assertEquals("0.5", ToolInputValue.Number(0.5).toJson().toString())
        assertEquals("true", ToolInputValue.Bool(true).toJson().toString())
        assertEquals("\"minecraft:stone\"", ToolInputValue.Text("minecraft:stone").toJson().toString())
    }
}
