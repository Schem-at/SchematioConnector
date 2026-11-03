package io.schemat.connector.core.text

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class RichGlyphsTest {

    @Test
    fun `bullets use U+2022 when the font has it`() {
        assertEquals("• ", RichGlyphs.bulletPrefix { true })
    }

    @Test
    fun `bullets fall back to the Latin-1 middle dot instead of a missing-glyph question mark`() {
        // panel-lib's font atlas only bakes Basic Latin + Latin-1, so U+2022 drew as "?" in game.
        assertEquals("· ", RichGlyphs.bulletPrefix { it != 0x2022 })
    }
}
