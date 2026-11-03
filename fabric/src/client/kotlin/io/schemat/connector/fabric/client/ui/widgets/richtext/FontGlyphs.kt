package io.schemat.connector.fabric.client.ui.widgets.richtext

import imgui.ImFont
import imgui.ImFontGlyph

/** Glyph presence checks against the ImGui font atlas. */
object FontGlyphs {

    /**
     * True when a `findGlyphNoFallback` result is a real glyph. imgui-java wraps the native
     * pointer without a null check, so a missing glyph is a non-null object with a null pointer.
     */
    fun isFound(glyph: ImFontGlyph?): Boolean = glyph != null && glyph.ptr != 0L

    /** True when [font] has its own glyph for [codepoint] (not the fallback "?"). */
    fun has(font: ImFont, codepoint: Int): Boolean = isFound(font.findGlyphNoFallback(codepoint))
}
