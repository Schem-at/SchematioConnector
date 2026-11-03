package io.schemat.connector.core.text

/** Glyph choices that depend on what the UI font can actually draw. */
object RichGlyphs {

    private const val BULLET = 0x2022
    private const val MIDDLE_DOT = 0x00B7

    /**
     * The list-item prefix: U+2022 when [hasGlyph] says the font has it, else the Latin-1
     * middle dot, which every font atlas built from the default ranges includes.
     */
    fun bulletPrefix(hasGlyph: (Int) -> Boolean): String =
        (if (hasGlyph(BULLET)) BULLET else MIDDLE_DOT).toChar() + " "
}
