package io.schemat.connector.fabric.client.ui

import io.schemat.connector.core.modapi.FilterConstraint
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.widgets.TagPickerPanel
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Popup tag picker: a thin wrapper ([Theme.scrim] + [Theme.panel] + header +
 * Done/Cancel) around the embeddable [TagPickerPanel], which provides the TABBED
 * tag UI - one tab per [TagSection] ("Minecraft" first/default, then a community,
 * the edit screen's "Current tags", ...) with the tree + search scoped to the
 * active tab and the selection chips / filter rows shared across tabs. The same
 * panel is embedded inline in the upload wizard, so the popup and inline pickers
 * look and behave identically.
 *
 * [Mode.ASSIGN] (edit): non-assignable rows disabled, literal filter values with
 * validation; Done is blocked (error line above the buttons) while a value is
 * invalid or a required filter is unset. [Mode.FILTER] (browse): browse
 * constraints (Any/value cyclers, min/max ranges).
 *
 * Done invokes [onDone] with the [Selection] then returns to [parent] (in that
 * order, so the parent's re-init already sees the new selection); Cancel / ESC
 * just returns. Branches containing the initial selection start expanded so a
 * re-opened picker shows the previously chosen tags.
 */
class TagSelectorScreen(
    private val parent: Screen,
    title: Component,
    sections: List<TagSection>,
    mode: Mode,
    initialSelection: Set<String> = emptySet(),
    initialFilterValues: Map<Long, String> = emptyMap(),
    initialConstraints: List<FilterConstraint> = emptyList(),
    private val onDone: (Selection) -> Unit,
) : Screen(title) {

    /** One labeled tree in the picker (e.g. "Minecraft", a community name) - one tab. */
    data class TagSection(val label: String, val nodes: List<TagNode>)

    enum class Mode { ASSIGN, FILTER }

    /**
     * Result of the dialog: the chosen tag ids plus - mode-dependent - either literal
     * [filterValues] (ASSIGN; for `setTags` / `UploadRequest.tagFilters`) or browse
     * [filterConstraints] (FILTER; for `BrowseQuery.filterConstraints`).
     */
    data class Selection(
        val tagIds: Set<String>,
        val filterValues: Map<Long, String>,
        val filterConstraints: List<FilterConstraint>,
    )

    companion object {
        private const val PAD = Theme.LG
        private const val GAP = Theme.MD
        private const val BUTTON_HEIGHT = Theme.BTN_H
        private const val BUTTON_WIDTH = 80
        /** Vertical space reserved for the [Theme.header] drawn in render(). */
        private const val HEADER_RESERVED = 28
    }

    // The panel outlives clearAndInit so selection/expansion/search survive resizes.
    private val picker = TagPickerPanel(
        mode = if (mode == Mode.ASSIGN) TagPickerPanel.Mode.ASSIGN else TagPickerPanel.Mode.FILTER,
        onMutated = {
            errorMessage = null
            rebuildWidgets()
        },
    ).apply {
        setInitial(initialSelection, initialFilterValues, initialConstraints)
        // Unknown selected ids are deliberate here (edit re-submits current tags
        // outside the loaded trees) - never prune.
        setTabs(sections.map { TagPickerPanel.Tab(it.label, it.nodes) })
    }

    private var errorMessage: String? = null

    // ---- layout (recomputed each init) ----
    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0

    override fun init() {
        super.init()
        panelW = (width * 4 / 5).coerceAtLeast(260).coerceAtMost(width - 8)
        panelH = (height * 4 / 5).coerceAtLeast(160).coerceAtMost(height - 8)
        panelX = (width - panelW) / 2
        panelY = (height - panelH) / 2

        val innerX = panelX + PAD
        val innerW = panelW - PAD * 2
        val contentTop = panelY + PAD + HEADER_RESERVED
        val buttonsTop = panelY + panelH - PAD - BUTTON_HEIGHT
        val contentBottom = buttonsTop - GAP - 12 // one error line above the buttons

        picker.layout(innerX, contentTop, innerW, (contentBottom - contentTop).coerceAtLeast(60))
            .forEach { addRenderableWidget(it) }

        addRenderableWidget(
            FlatButton.primary(panelX + panelW - PAD - BUTTON_WIDTH, buttonsTop, BUTTON_WIDTH, Component.literal("Done")) { done() }
        )
        addRenderableWidget(
            FlatButton.ghost(
                panelX + panelW - PAD - BUTTON_WIDTH - GAP - BUTTON_WIDTH, buttonsTop, BUTTON_WIDTH,
                Component.literal("Cancel"),
            ) { onClose() }
        )
    }

    // ---- done / cancel ----

    private fun done() {
        val invalid = picker.validationError()
        if (invalid != null) {
            errorMessage = invalid
            return
        }
        val selection = picker.selection()
        // onDone BEFORE returning to the parent: setScreen re-inits the parent, and its
        // init must already observe the new selection (e.g. chip summaries, listings).
        onDone(Selection(selection.tagIds, selection.filterValues, selection.filterConstraints))
        minecraft!!.setScreen(parent)
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true
        return picker.mouseScrolled(mouseX, mouseY, verticalAmount)
    }

    // ---- rendering ----

    // 26.x renamed the Screen render hooks: render -> extractRenderState,
    // renderBackground -> extractBackground.
    //? if >=26.1 {
    /*override fun extractBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
    *///?} else {
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.renderBackground(context, mouseX, mouseY, delta)
    //?}
        Theme.scrim(context, width, height)
        Theme.panel(context, panelX, panelY, panelW, panelH)
    }

    //? if >=26.1 {
    /*override fun extractRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
    *///?} else {
    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    //?}
        val innerX = panelX + PAD
        val innerW = panelW - PAD * 2
        Theme.header(
            context, font,
            font.plainSubstrByWidth(title.string, innerW),
            null, innerX, panelY + PAD, innerW,
        )
        picker.render(context)
        errorMessage?.let { message ->
            Theme.label(
                context, font,
                font.plainSubstrByWidth(message, innerW),
                innerX, panelY + panelH - PAD - BUTTON_HEIGHT - 12, Theme.DANGER,
            )
        }
    }
}
