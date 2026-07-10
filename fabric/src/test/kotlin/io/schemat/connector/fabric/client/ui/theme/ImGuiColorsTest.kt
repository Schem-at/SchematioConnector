package io.schemat.connector.fabric.client.ui.theme

import imgui.ImVec4
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ImGuiColorsTest {

    private fun assertVec(expected: FloatArray, v: ImVec4, message: String = "") {
        assertEquals(expected[0], v.x, 0.001f, "$message r")
        assertEquals(expected[1], v.y, 0.001f, "$message g")
        assertEquals(expected[2], v.z, 0.001f, "$message b")
        assertEquals(expected[3], v.w, 0.001f, "$message a")
    }

    @Test
    fun `opaque white 0xFFFFFFFF maps to (1,1,1,1)`() =
        assertVec(floatArrayOf(1f, 1f, 1f, 1f), argbToImVec4(0xFFFFFFFF.toInt()), "opaqueWhite")

    @Test
    fun `accent fuchsia 0xFFDB45F0 maps correctly`() =
        assertVec(
            floatArrayOf(0xDB / 255f, 0x45 / 255f, 0xF0 / 255f, 1f),
            argbToImVec4(0xFFDB45F0.toInt()),
            "accent"
        )

    @Test
    fun `half-alpha black 0x80000000 maps to (0,0,0,0x80div255)`() =
        assertVec(floatArrayOf(0f, 0f, 0f, 0x80 / 255f), argbToImVec4(0x80000000.toInt()), "halfAlpha")

    @Test
    fun `SCRIM 0xF00A0A0C has alpha 0xF0 div 255`() =
        assertVec(
            floatArrayOf(0x0A / 255f, 0x0A / 255f, 0x0C / 255f, 0xF0 / 255f),
            argbToImVec4(0xF00A0A0C.toInt()),
            "scrim"
        )
}
