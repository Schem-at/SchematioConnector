package io.schemat.connector.fabric.client.ui.widgets

import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.ExportSource
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Shared "what schematic do I share/upload" source mechanics, used by the upload
 * wizard's step 1 and the quick-share create screen: source discovery (Litematica
 * exports, the WorldEdit clipboard, local files) plus labeling/format helpers.
 */
object ExportSources {

    private const val MAX_LOCAL_FILES = 200L

    /** Litematica placements/selections + WorldEdit clipboard + local schematic files. */
    fun collect(): List<ExportSource> {
        val out = mutableListOf<ExportSource>()
        out.addAll(Bridges.litematica.listExportSources())
        if (Bridges.worldEdit.isAvailable) {
            out.add(ExportSource("worldedit:clipboard", "WorldEdit clipboard", SourceKind.WORLDEDIT_CLIPBOARD))
        }
        out.addAll(listLocalFiles())
        return out
    }

    fun localFilesDirectory(): Path =
        Bridges.litematica.schematicsDirectory()
            ?: FabricLoader.getInstance().gameDir.resolve("schematics")

    private fun listLocalFiles(): List<ExportSource> {
        val dir = localFilesDirectory()
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.walk(dir, 2).use { stream ->
                stream
                    .filter { path ->
                        Files.isRegularFile(path) &&
                            path.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("litematic", "schem", "schematic")
                    }
                    .limit(MAX_LOCAL_FILES)
                    .sorted()
                    .map { path -> ExportSource(path.toAbsolutePath().toString(), dir.relativize(path).toString(), SourceKind.LOCAL_FILE) }
                    .collect(Collectors.toList())
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun label(source: ExportSource): String = when (source.kind) {
        SourceKind.PLACEMENT -> "[Placement] ${source.label}"
        SourceKind.AREA_SELECTION -> "[Selection] ${source.label}"
        SourceKind.WORLDEDIT_CLIPBOARD -> "[WorldEdit] ${source.label}"
        SourceKind.LOCAL_FILE -> "[File] ${source.label}"
    }

    /** The upload/share format implied by a source. */
    fun formatFor(source: ExportSource?): String = when (source?.kind) {
        SourceKind.WORLDEDIT_CLIPBOARD -> "schem"
        SourceKind.LOCAL_FILE -> when (source.id.substringAfterLast('.', "").lowercase()) {
            "schem" -> "schem"
            "schematic" -> "schematic"
            else -> "litematic"
        }
        // Litematica exports (placements/area selections) always produce .litematic bytes.
        else -> "litematic"
    }
}

/**
 * Scrollable single-select list of [ExportSource]s. State lives with the caller:
 * [sources] and [selectedId] are read each frame, [onSelect] fires on click.
 */
class ExportSourceListWidget(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val sources: () -> List<ExportSource>,
    private val selectedId: () -> String?,
    private val onSelect: (ExportSource) -> Unit,
) : AbstractWidget(x, y, width, height, Component.literal("Sources")) {

    private val rowHeight = Theme.ROW_H
    private var scrollOffset = 0.0
    private val contentHeight: Int get() = sources().size * rowHeight
    private val maxScroll: Double get() = (contentHeight - height).coerceAtLeast(0).toDouble()

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        scrollOffset = (scrollOffset - verticalAmount * 24.0).coerceIn(0.0, maxScroll)
        return true
    }

    // 1.21.9 changed input handlers to take event objects; on 1.21.8 the
    // primitive override delegates to the event-shaped method (compat shim).
    //? if <1.21.9 {
    /*override fun onClick(clickX: Double, clickY: Double) {
        onClick(MouseButtonEvent(clickX, clickY), false)
    }
    *///?}
    //? if >=1.21.9
    override
    fun onClick(click: MouseButtonEvent, doubled: Boolean) {
        val index = ((click.y() - y + scrollOffset) / rowHeight).toInt()
        sources().getOrNull(index)?.let { onSelect(it) }
    }

    // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
    //? if >=26.1 {
    /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    *///?} else {
    override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    //?}
        val font = Minecraft.getInstance().font
        // Card surface with a 1px ring; rows are drawn inside a scissor.
        Theme.card(context, x, y, width, height)
        context.enableScissor(x, y, x + width, y + height)
        val selected = selectedId()
        sources().forEachIndexed { index, source ->
            val rowY = y + index * rowHeight - scrollOffset.toInt()
            if (rowY + rowHeight < y || rowY > y + height) return@forEachIndexed
            val isSelected = selected == source.id
            val hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight &&
                mouseY >= y && mouseY < y + height
            when {
                isSelected -> {
                    context.fill(x, rowY, x + width, rowY + rowHeight, Theme.withAlpha(Theme.ACCENT, 0x2E))
                    context.fill(x, rowY, x + 2, rowY + rowHeight, Theme.ACCENT)
                }
                hovered -> context.fill(x, rowY, x + width, rowY + rowHeight, Theme.SURFACE_HOVER)
            }
            val textColor = if (isSelected) Theme.TEXT_PRIMARY else if (hovered) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
            context.drawString(
                font,
                font.plainSubstrByWidth(ExportSources.label(source), width - Theme.MD * 2 - 4),
                x + Theme.MD, rowY + (rowHeight - font.lineHeight) / 2 + 1,
                textColor, false,
            )
            Theme.divider(context, x + 1, rowY + rowHeight - 1, width - 2)
        }
        context.disableScissor()

        Theme.scrollbar(context, x + width - 4, y + 1, height - 2, contentHeight, height, scrollOffset.toInt())
    }

    override fun updateWidgetNarration(builder: NarrationElementOutput) {
        defaultButtonNarrationText(builder)
    }
}
