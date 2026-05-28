package io.schemat.connector.fabric.client.ui

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget
import net.minecraft.text.Text

class SchematicListWidget(
    client: MinecraftClient,
    width: Int,
    height: Int,
    y: Int
) : AlwaysSelectedEntryListWidget<SchematicListWidget.Entry>(client, width, height, y, ENTRY_HEIGHT) {

    companion object {
        const val ENTRY_HEIGHT = 44
    }

    fun setEntries(entries: List<SchematicEntry>) {
        clearEntries()
        entries.forEach { addEntry(Entry(it)) }
        if (children().isNotEmpty()) setSelected(children()[0])
    }

    override fun getRowWidth(): Int = width - 40

    class Entry(val schematic: SchematicEntry) : AlwaysSelectedEntryListWidget.Entry<Entry>() {

        override fun render(
            context: DrawContext,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            tickDelta: Float
        ) {
            val client = MinecraftClient.getInstance()
            val textRenderer = client.textRenderer

            // Position comes from the widget's layout, not render params
            val x = getX()
            val y = getY()
            val rowWidth = getWidth()

            // Hover background (selection highlight is drawn by the parent widget)
            if (hovered) {
                context.fill(x, y + 1, x + rowWidth, y + ENTRY_HEIGHT - 1, 0x30_FFFFFF.toInt())
            }

            val padding = 4
            val maxLineWidth = (rowWidth - padding * 2).coerceAtLeast(0)

            // Line 1: name (white) — clipped to the full row, no rival on this line
            val nameClipped = textRenderer.trimToWidth(schematic.name, maxLineWidth)
            context.drawTextWithShadow(textRenderer, nameClipped, x + padding, y + 3, -1)

            // Line 2: author · dimensions · downloads (gray)
            val details = buildString {
                schematic.authorName?.let { append("by $it") }
                if (schematic.dimensionsText.isNotEmpty()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(schematic.dimensionsText)
                }
                if (schematic.downloadCount > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append("${schematic.downloadCount} downloads")
                }
            }
            if (details.isNotEmpty()) {
                val detailsClipped = textRenderer.trimToWidth(details, maxLineWidth)
                context.drawTextWithShadow(textRenderer, detailsClipped, x + padding, y + 16, 0xFF_AAAAAA.toInt())
            }

            // Line 3: tags (cyan) — own line so they can never collide with the name
            if (schematic.tags.isNotEmpty()) {
                val tagText = schematic.tags.take(4).joinToString(", ")
                val tagsClipped = textRenderer.trimToWidth(tagText, maxLineWidth)
                context.drawTextWithShadow(textRenderer, tagsClipped, x + padding, y + 29, 0xFF_55FFFF.toInt())
            }
        }

        override fun getNarration(): Text = Text.literal(schematic.name)
    }
}
