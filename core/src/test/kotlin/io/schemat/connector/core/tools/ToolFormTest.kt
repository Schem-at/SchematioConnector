package io.schemat.connector.core.tools

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ToolFormTest {

    private val image = catalogue()[0]
    private val hollow = catalogue()[1]

    @Test
    fun `defaults seed the form`() {
        val form = ToolForm(image)
        assertEquals(64.0, form.number("width"))
        assertEquals("wool", form.text("palette"))
        assertEquals(false, form.bool("dither"))
        assertEquals("#ff5555", ToolForm(hollow).text("tint"))
        assertEquals("minecraft:air", ToolForm(hollow).text("fill"))
    }

    @Test
    fun `when hides an input until its condition holds, and hidden inputs are not sent`() {
        val form = ToolForm(image)
        assertTrue("strength" !in form.visibleInputs().map { it.name })
        form.setBool("dither", true)
        assertTrue("strength" in form.visibleInputs().map { it.name })
        form.setBool("dither", false)
        form.setFile("image", ToolInputValue.Upload(byteArrayOf(1), "cat.png"))
        assertTrue("strength" !in form.buildInputs().keys)
    }

    @Test
    fun `unknown input kinds are not shown or sent`() {
        val form = ToolForm(hollow)
        assertTrue("future" !in form.visibleInputs().map { it.name })
    }

    @Test
    fun `required file and schematic inputs must be supplied`() {
        val errors = ToolForm(image).validate()
        assertEquals(setOf("image"), errors.keys)
        val h = ToolForm(hollow)
        assertEquals(setOf("source"), h.validate().keys)
        h.setFile("source", ToolInputValue.SchematicRef("FqI-IX"))
        assertEquals(emptyMap(), h.validate())
    }

    @Test
    fun `numbers are range checked and snapped to step`() {
        val form = ToolForm(image)
        form.setFile("image", ToolInputValue.Upload(byteArrayOf(1), "cat.png"))
        form.setNumber("width", 300.0)
        assertEquals(setOf("width"), form.validate().keys)
        form.setNumber("width", 12.4)
        assertEquals(emptyMap(), form.validate())
        assertEquals(12.0, (form.buildInputs()["width"] as ToolInputValue.Number).value)
    }

    @Test
    fun `blocks, colours and selects are validated`() {
        val h = ToolForm(hollow)
        h.setFile("source", ToolInputValue.SchematicRef("x"))
        h.setText("fill", "Stone!")
        h.setText("tint", "red")
        assertEquals(setOf("fill", "tint"), h.validate().keys)
        h.setText("fill", "stone")
        h.setText("tint", "#AA00aa")
        assertEquals(emptyMap(), h.validate())
        // A bare block id gets the minecraft namespace; colours are sent lowercase.
        val inputs = h.buildInputs()
        assertEquals("minecraft:stone", (inputs["fill"] as ToolInputValue.Text).value)
        assertEquals("#aa00aa", (inputs["tint"] as ToolInputValue.Text).value)

        val f = ToolForm(image)
        f.setFile("image", ToolInputValue.Upload(byteArrayOf(1), "a.png"))
        f.setText("palette", "glass")
        assertEquals(setOf("palette"), f.validate().keys)
    }

    @Test
    fun `optional empty text and unset optional schematic are omitted`() {
        val h = ToolForm(hollow)
        h.setFile("source", ToolInputValue.SchematicRef("x"))
        val inputs = h.buildInputs()
        assertNull(inputs["note"])
        val f = ToolForm(image)
        f.setFile("image", ToolInputValue.Upload(byteArrayOf(1), "a.png"))
        assertNull(f.buildInputs()["base"])
    }

    @Test
    fun `server 422 field errors attach to inputs`() {
        val form = ToolForm(image)
        form.applyServerErrors(mapOf("width" to listOf("Too wide"), "other" to listOf("x")))
        assertEquals("Too wide", form.errorFor("width"))
        form.setNumber("width", 10.0)
        assertNull(form.errorFor("width"))
    }

    @Test
    fun `local validation errors attach to inputs and clear once the value changes`() {
        // The panel used to show "Minecraft username: Required" in the footer and keep it there
        // after the username was typed in, until the next Run.
        val form = ToolForm(hollow)
        form.showErrors(form.validate())
        assertEquals("Required", form.errorFor("source"))
        assertTrue(form.hasErrors())
        form.setFile("source", ToolInputValue.SchematicRef("FqI-IX"))
        assertNull(form.errorFor("source"))
        assertEquals(false, form.hasErrors())
    }

    @Test
    fun `the primary schematic input is the first required schematic, else the first schematic`() {
        assertEquals("source", ToolForm(hollow).primarySchematicInput()?.name)
        assertEquals("base", ToolForm(image).primarySchematicInput()?.name)
    }
}
