package io.schemat.connector.core.text

/**
 * The only text colours a description may carry: the 16 Minecraft chat colours.
 *
 * Shared with the website (bug #3 rich-text contract): both editors offer exactly these
 * swatches, both serializers emit them as `<span style="color:#rrggbb">` with lowercase
 * hex, and both renderers drop any other colour.
 */
object RichPalette {

    /** One palette colour; [name] is the Minecraft colour name, [argb] is opaque. */
    data class Entry(val name: String, val argb: Int)

    val COLORS: List<Entry> = listOf(
        Entry("Black", 0xFF000000.toInt()),
        Entry("Dark Blue", 0xFF0000AA.toInt()),
        Entry("Dark Green", 0xFF00AA00.toInt()),
        Entry("Dark Aqua", 0xFF00AAAA.toInt()),
        Entry("Dark Red", 0xFFAA0000.toInt()),
        Entry("Dark Purple", 0xFFAA00AA.toInt()),
        Entry("Gold", 0xFFFFAA00.toInt()),
        Entry("Gray", 0xFFAAAAAA.toInt()),
        Entry("Dark Gray", 0xFF555555.toInt()),
        Entry("Blue", 0xFF5555FF.toInt()),
        Entry("Green", 0xFF55FF55.toInt()),
        Entry("Aqua", 0xFF55FFFF.toInt()),
        Entry("Red", 0xFFFF5555.toInt()),
        Entry("Light Purple", 0xFFFF55FF.toInt()),
        Entry("Yellow", 0xFFFFFF55.toInt()),
        Entry("White", 0xFFFFFFFF.toInt()),
    )

    private val RGB = COLORS.map { it.argb and 0xFFFFFF }.toSet()
    private val HEX = Regex("""^#([0-9a-fA-F]{6})$""")

    /** True when [argb]'s RGB (alpha ignored) is a palette colour. */
    fun contains(argb: Int): Boolean = (argb and 0xFFFFFF) in RGB

    /** `#rrggbb`, lowercase, alpha ignored. */
    fun hex(argb: Int): String = "#%06x".format(argb and 0xFFFFFF)

    /** Opaque ARGB for a `#rrggbb` string, null when malformed (palette membership not checked). */
    fun parseHex(hex: String): Int? =
        HEX.find(hex.trim())?.groupValues?.get(1)?.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }

    /** The palette entry for [argb], or null. */
    fun entryOf(argb: Int): Entry? = COLORS.firstOrNull { (it.argb and 0xFFFFFF) == (argb and 0xFFFFFF) }
}
