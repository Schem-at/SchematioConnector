package io.schemat.connector.fabric.client.ui.widgets

import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.services.MojangLookup
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.ThemedTextField
import io.schemat.connector.fabric.client.ui.theme.Theme
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.input.MouseButtonEvent
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component

/**
 * Player-list editor, design-system styled: a name input ([ThemedTextField] well) +
 * Add button on top, and a scrollable list of resolved players (head avatar + name +
 * ghost [x] remove) below in a SURFACE_ALT well. Names are resolved to uuids via
 * [MojangLookup]; resolving state and "player not found" errors are shown inline.
 *
 * The instance is stateful and survives the host screen's re-init: call [layout] from
 * the host's `init()` each time to (re)create the actual widgets.
 */
class PlayerListEditorWidget(
    private val services: ClientServices,
    initial: List<Entry> = emptyList(),
    /** Removal is blocked when it would leave fewer than this many entries. */
    private val minEntries: Int = 0,
    private val maxEntries: Int = Int.MAX_VALUE,
) {

    data class Entry(val uuid: String, val name: String, val headUrl: String? = null)

    companion object {
        private const val CONTROL_HEIGHT = Theme.INPUT_H
        private const val ADD_WIDTH = 40
        private const val ROW_HEIGHT = 16
        private const val HEAD_SIZE = 12
        private const val SCROLL_STEP = 24.0
        private const val GAP = Theme.XS
        /** Width of the [x] remove hit zone at the right edge of a row. */
        private const val REMOVE_ZONE = 14
    }

    private val entries = initial.toMutableList()
    private var error: String? = null
    private var resolving = false
    /** Field text survives re-init (resize) like BrowseTab's search text. */
    private var pendingText = ""

    private var nameField: ThemedTextField? = null
    private var addButton: FlatButton? = null

    fun entries(): List<Entry> = entries.toList()

    fun setEntries(list: List<Entry>) {
        entries.clear()
        entries.addAll(list)
    }

    /**
     * (Re)create the editor's widgets inside the given bounds; the caller registers
     * each returned widget (`addRenderableWidget` / `HomeScreen.register`).
     */
    fun layout(x: Int, y: Int, width: Int, height: Int): List<AbstractWidget> {
        val font = Minecraft.getInstance().font
        val field = ThemedTextField(
            font, x, y, (width - ADD_WIDTH - GAP).coerceAtLeast(40), CONTROL_HEIGHT,
            Component.literal("Player name"), placeholder = "Player name...",
        )
        field.setMaxLength(16)
        field.value = pendingText
        field.setResponder { pendingText = it }
        nameField = field

        val button = FlatButton.secondary(
            x + width - ADD_WIDTH, y, ADD_WIDTH, Component.literal("Add"), height = CONTROL_HEIGHT,
        ) { addByName() }
        addButton = button

        val list = RowListWidget(x, y + CONTROL_HEIGHT + GAP, width, (height - CONTROL_HEIGHT - GAP).coerceAtLeast(20))
        return listOf(field, button, list)
    }

    private fun addByName() {
        if (resolving) return
        val name = pendingText.trim()
        if (name.isEmpty()) {
            error = "Enter a player name"
            return
        }
        if (entries.size >= maxEntries) {
            error = "Too many players (max $maxEntries)"
            return
        }
        if (entries.any { it.name.equals(name, ignoreCase = true) }) {
            error = "$name is already in the list"
            return
        }
        resolving = true
        error = null
        services.scope.launch {
            val result = try {
                MojangLookup.resolve(name)
            } catch (e: Exception) {
                services.onMainThread {
                    resolving = false
                    error = e.message ?: "Lookup failed"
                }
                return@launch
            }
            services.onMainThread {
                resolving = false
                when {
                    result == null -> error = "Player not found"
                    entries.any { it.uuid.equals(result.uuid, ignoreCase = true) } ->
                        error = "${result.name} is already in the list"
                    else -> {
                        entries.add(Entry(result.uuid, result.name))
                        pendingText = ""
                        nameField?.value = ""
                    }
                }
            }
        }
    }

    private fun removeAt(index: Int) {
        if (index !in entries.indices) return
        if (entries.size <= minEntries) {
            error = "At least $minEntries player(s) required"
            return
        }
        entries.removeAt(index)
        error = null
    }

    /** The scrollable rows area (one widget so remove buttons need no re-layout). */
    private inner class RowListWidget(x: Int, y: Int, width: Int, height: Int) :
        AbstractWidget(x, y, width, height, Component.literal("Players")) {

        private var scrollOffset = 0.0

        /** One status line (resolving / error) is drawn above the rows when present. */
        private val statusLines: Int get() = if (resolving || error != null) 1 else 0
        private val contentHeight: Int get() = (entries.size + statusLines) * ROW_HEIGHT
        private val maxScroll: Double get() = (contentHeight - height).coerceAtLeast(0).toDouble()

        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
            if (!isMouseOver(mouseX, mouseY)) return false
            scrollOffset = (scrollOffset - verticalAmount * SCROLL_STEP).coerceIn(0.0, maxScroll)
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
            val index = ((click.y() - y + scrollOffset) / ROW_HEIGHT).toInt() - statusLines
            // Only the [x] zone at the right edge removes
            if (click.x() >= x + width - REMOVE_ZONE) removeAt(index)
        }

        // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
        //? if >=26.1 {
        /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        *///?} else {
        override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        //?}
            addButton?.active = !resolving
            val font = Minecraft.getInstance().font
            context.fill(x, y, x + width, y + height, Theme.SURFACE_ALT)
            Theme.stroke(context, x, y, width, height, Theme.BORDER)
            context.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1)

            var row = 0
            if (resolving) {
                Theme.muted(
                    context, font, "Resolving...",
                    x + Theme.XS, y + row * ROW_HEIGHT + 3 - scrollOffset.toInt(),
                )
                row++
            } else error?.let {
                Theme.label(
                    context, font, font.plainSubstrByWidth(it, width - Theme.MD),
                    x + Theme.XS, y + row * ROW_HEIGHT + 3 - scrollOffset.toInt(), Theme.DANGER,
                )
                row++
            }

            if (entries.isEmpty() && row == 0) {
                Theme.hint(context, font, "No players added", x + Theme.XS, y + 3)
            }

            entries.forEachIndexed { index, entry ->
                val rowY = y + (index + statusLines) * ROW_HEIGHT - scrollOffset.toInt()
                if (rowY + ROW_HEIGHT < y || rowY > y + height) return@forEachIndexed
                val rowHovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                if (rowHovered) {
                    context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, Theme.SURFACE_HOVER)
                }
                // Head avatar (head_url from the API when present, else mc-heads by
                // uuid); a neutral placeholder box renders while it loads.
                val headX = x + Theme.XS
                val headY = rowY + (ROW_HEIGHT - HEAD_SIZE) / 2
                val headId = services.headAvatars.getHead(entry.uuid, entry.headUrl)
                if (headId != null) {
                    val (texW, texH) = services.headAvatars.getHeadSize(entry.uuid) ?: (64 to 64)
                    context.blit(
                        RenderPipelines.GUI_TEXTURED, headId,
                        headX, headY, 0f, 0f,
                        HEAD_SIZE, HEAD_SIZE, texW, texH, texW, texH,
                    )
                } else {
                    context.fill(headX, headY, headX + HEAD_SIZE, headY + HEAD_SIZE, Theme.BORDER)
                }
                Theme.label(
                    context, font,
                    font.plainSubstrByWidth(
                        entry.name.ifBlank { entry.uuid },
                        width - REMOVE_ZONE - HEAD_SIZE - Theme.MD * 2,
                    ),
                    headX + HEAD_SIZE + Theme.XS, rowY + (ROW_HEIGHT - 8) / 2,
                    if (rowHovered) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY,
                )
                val hoveringRemove = mouseX >= x + width - REMOVE_ZONE && mouseX < x + width - 2 &&
                    mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                Theme.label(
                    context, font, "x",
                    x + width - 10, rowY + (ROW_HEIGHT - 8) / 2,
                    if (hoveringRemove) Theme.DANGER else Theme.TEXT_MUTED,
                )
            }

            context.disableScissor()

            Theme.scrollbar(context, x + width - 4, y + 1, height - 2, contentHeight, height, scrollOffset.toInt())
        }

        override fun updateWidgetNarration(builder: NarrationElementOutput) {
            defaultButtonNarrationText(builder)
        }
    }
}
