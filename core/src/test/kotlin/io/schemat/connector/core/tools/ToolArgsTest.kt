package io.schemat.connector.core.tools

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** `/schematio tool <id> [k=v ...]` argument parsing and tab completion (plugin). */
class ToolArgsTest {

    private val image = catalogue()[0]
    private val hollow = catalogue()[1]

    /** image-to-schematic with its file input made optional, to exercise the other kinds. */
    private val imageOptional = image.copy(inputs = image.inputs.map { if (it.name == "image") it.copy(required = false) else it })

    private fun parse(def: ToolDefinition, vararg args: String) = ToolArgs.parse(def, args.toList())

    @Test
    fun `key=value pairs are typed against the tool's inputs`() {
        val r = assertIs<ToolArgs.Result.Ok>(parse(hollow, "fill=stone", "tint=#55FF55"))
        assertEquals("minecraft:stone", (r.inputs["fill"] as ToolInputValue.Text).value)
        assertEquals("#55ff55", (r.inputs["tint"] as ToolInputValue.Text).value)
    }

    @Test
    fun `the primary schematic input defaults to the player's clipboard`() {
        val r = assertIs<ToolArgs.Result.Ok>(parse(hollow))
        assertEquals(ToolInputValue.ClipboardPlaceholder, r.inputs["source"])
    }

    @Test
    fun `schematic inputs accept a short id, qs code or clipboard`() {
        assertEquals(ToolInputValue.SchematicRef("FqI-IX"), (parse(hollow, "source=FqI-IX") as ToolArgs.Result.Ok).inputs["source"])
        assertEquals(ToolInputValue.QuickShareRef("AB12CD"), (parse(hollow, "source=qs:AB12CD") as ToolArgs.Result.Ok).inputs["source"])
        assertEquals(ToolInputValue.ClipboardPlaceholder, (parse(hollow, "source=clipboard") as ToolArgs.Result.Ok).inputs["source"])
    }

    @Test
    fun `quoted values may contain spaces`() {
        val r = assertIs<ToolArgs.Result.Ok>(parse(hollow, "note=\"two", "words\"", "fill=dirt"))
        assertEquals("two words", (r.inputs["note"] as ToolInputValue.Text).value)
        assertEquals("minecraft:dirt", (r.inputs["fill"] as ToolInputValue.Text).value)
    }

    @Test
    fun `booleans, numbers and selects`() {
        val r = assertIs<ToolArgs.Result.Ok>(parse(imageOptional, "width=32", "dither=yes", "strength=0.3", "palette=concrete"))
        assertEquals(32.0, (r.inputs["width"] as ToolInputValue.Number).value)
        assertEquals(true, (r.inputs["dither"] as ToolInputValue.Bool).value)
        assertEquals(0.3, (r.inputs["strength"] as ToolInputValue.Number).value)
        assertEquals("concrete", (r.inputs["palette"] as ToolInputValue.Text).value)
    }

    @Test
    fun `errors name the problem`() {
        assertTrue(assertIs<ToolArgs.Result.Error>(parse(hollow, "nope=1")).message.contains("nope"))
        assertTrue(assertIs<ToolArgs.Result.Error>(parse(hollow, "fill")).message.contains("key=value"))
        assertTrue(assertIs<ToolArgs.Result.Error>(parse(imageOptional, "width=abc")).message.contains("width"))
        assertTrue(assertIs<ToolArgs.Result.Error>(parse(imageOptional, "width=999")).message.contains("256"))
        assertTrue(assertIs<ToolArgs.Result.Error>(parse(imageOptional, "dither=maybe")).message.contains("dither"))
        // A file input (an image upload) cannot come from chat, set or required.
        assertTrue(assertIs<ToolArgs.Result.Error>(parse(image)).message.contains("image"))
        assertTrue(assertIs<ToolArgs.Result.Error>(parse(imageOptional, "image=x")).message.contains("Tools panel"))
    }

    @Test
    fun `tab completion offers unused keys, then values for the key being typed`() {
        assertEquals(listOf("fill=", "note=", "source=", "tint="), ToolArgs.complete(hollow, listOf("")))
        assertEquals(listOf("note=", "source=", "tint="), ToolArgs.complete(hollow, listOf("fill=stone", "")))
        assertEquals(listOf("palette=concrete", "palette=wool"), ToolArgs.complete(image, listOf("palette=")))
        assertEquals(listOf("dither=false", "dither=true"), ToolArgs.complete(image, listOf("dither=")))
        assertEquals(listOf("source=clipboard"), ToolArgs.complete(hollow, listOf("source=c")))
    }
}
