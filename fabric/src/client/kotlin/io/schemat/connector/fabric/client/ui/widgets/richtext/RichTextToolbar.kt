package io.schemat.connector.fabric.client.ui.widgets.richtext

import imgui.ImGui
import imgui.ImVec2
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiMouseButton
import imgui.type.ImString
import io.schemat.connector.core.text.RichDocument.ListKind
import io.schemat.connector.core.text.RichGlyphs
import io.schemat.connector.core.text.RichPalette
import io.schemat.connector.core.text.RichText
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.theme.argbToImVec4
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.Companion.SWATCHES
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.StyleFlag
import io.schemat.connector.fabric.client.ui.widgets.Widgets

/**
 * The toolbar for [RichTextEditorWidget], offering exactly the shared description
 * contract: B / I / U / S / code, bullet and numbered lists, H2 / H3, quote, link, and a
 * colour popup with the 16 [RichPalette] colours. Buttons flow onto a new row when the
 * next one would not fit, so narrow panels never clip a label. Operates on the widget's
 * state via [w].
 */
internal class RichTextToolbar(private val w: RichTextEditorWidget) {

    private val linkBuffer = ImString(512)
    private var linkError: String? = null
    private var firstItem = true

    fun render() {
        // Reset each frame; any of the controls below sets it true via [markToolbar] when
        // active/hovered so handleInput knows a click landed on our own toolbar.
        w.toolbarInteracted = false
        firstItem = true
        styleButton("B", StyleFlag.BOLD, "Bold")
        styleButton("I", StyleFlag.ITALIC, "Italic")
        styleButton("U", StyleFlag.UNDERLINE, "Underline")
        styleButton("S", StyleFlag.STRIKE, "Strikethrough")
        styleButton("</>", StyleFlag.CODE, "Inline code")

        val line = w.lineFormat()
        if (toolButton(RichGlyphs.bulletPrefix { FontGlyphs.has(ImGui.getFont(), it) }.trim(), "bullets", "Bulleted list", line.list == ListKind.BULLET)) w.toggleList(ListKind.BULLET)
        if (toolButton("1.", "numbers", "Numbered list", line.list == ListKind.ORDERED)) w.toggleList(ListKind.ORDERED)
        if (toolButton("H2", "h2", "Heading", line.heading == 2)) w.toggleHeading(2)
        if (toolButton("H3", "h3", "Subheading", line.heading == 3)) w.toggleHeading(3)
        if (toolButton("Quote", "quote", "Quote", line.quote)) w.toggleQuote()

        linkButton()
        colorButton()
    }

    /** Place the next item on this row if [width] still fits, else start a new row. */
    private fun flow(width: Float) {
        if (!firstItem) {
            val spacing = ImGui.getStyle().itemSpacingX
            val right = ImGui.getWindowPosX() + ImGui.getWindowContentRegionMaxX()
            if (ImGui.getItemRectMaxX() + spacing + width <= right) ImGui.sameLine()
        }
        firstItem = false
    }

    private fun buttonWidth(label: String): Float {
        val size = ImVec2()
        ImGui.calcTextSize(size, label)
        return size.x + ImGui.getStyle().framePaddingX * 2
    }

    /** OR the just-rendered toolbar control's active/hovered-click state into [RichTextEditorWidget.toolbarInteracted]. */
    private fun markToolbar() {
        if (ImGui.isItemActive() || (ImGui.isItemHovered() && ImGui.isMouseClicked(ImGuiMouseButton.Left))) {
            w.toolbarInteracted = true
        }
    }

    private fun toolButton(label: String, key: String, tooltip: String, active: Boolean): Boolean {
        flow(buttonWidth(label))
        val clicked = Widgets.button("$label##${w.id}-$key", accent = active)
        markToolbar()
        if (ImGui.isItemHovered()) ImGui.setTooltip(tooltip)
        return clicked
    }

    private fun styleButton(label: String, flag: StyleFlag, tooltip: String) {
        if (toolButton(label, flag.name, tooltip, w.isStyleActive(flag))) w.toggleStyle(flag)
    }

    // ---- link ----

    private fun linkButton() {
        val popup = "##${w.id}-link-popup"
        val canLink = w.hasSelectionForLink()
        flow(buttonWidth("Link"))
        if (!canLink) ImGui.beginDisabled()
        if (Widgets.button("Link##${w.id}-link", accent = w.activeLink() != null)) {
            linkBuffer.set(w.activeLink() ?: "https://")
            linkError = null
            ImGui.openPopup(popup)
        }
        if (!canLink) ImGui.endDisabled()
        markToolbar()
        if (ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip(if (canLink) "Link the selected text" else "Select text to link it")
        }
        if (ImGui.beginPopup(popup)) {
            w.toolbarInteracted = true
            ImGui.setNextItemWidth(260f)
            ImGui.inputText("##${w.id}-link-url", linkBuffer)
            linkError?.let { Widgets.statusText(it, Widgets.StatusKind.DANGER) }
            if (Widgets.button("Apply##${w.id}-link-apply", accent = true)) {
                val url = linkBuffer.get().trim()
                if (RichText.isAllowedHref(url) && w.setLink(url)) {
                    ImGui.closeCurrentPopup()
                } else {
                    linkError = "Links must start with http:// or https://"
                }
            }
            ImGui.sameLine()
            if (Widgets.button("Remove##${w.id}-link-remove")) {
                w.setLink(null)
                ImGui.closeCurrentPopup()
            }
            ImGui.endPopup()
        }
    }

    // ---- colour ----

    private fun colorButton() {
        val popup = "##${w.id}-color-popup"
        val current = w.activeColor()
        val display = argbToImVec4(current ?: Theme.TEXT_SECONDARY)
        flow(SWATCH + ImGui.getStyle().framePaddingX * 2)
        ImGui.pushStyleColor(ImGuiCol.Button, display.x, display.y, display.z, display.w)
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, display.x, display.y, display.z, display.w)
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, display.x, display.y, display.z, display.w)
        if (ImGui.button("##${w.id}-color", SWATCH + ImGui.getStyle().framePaddingX * 2, ImGui.getFrameHeight())) {
            ImGui.openPopup(popup)
        }
        ImGui.popStyleColor(3)
        markToolbar()
        if (ImGui.isItemHovered()) ImGui.setTooltip("Text colour: " + (current?.let { RichPalette.entryOf(it)?.name } ?: "Default"))

        if (ImGui.beginPopup(popup)) {
            w.toolbarInteracted = true
            if (Widgets.button("Default##${w.id}-color-default", accent = current == null)) {
                w.setColor(null)
                ImGui.closeCurrentPopup()
            }
            // 4 x 4 grid, in palette order.
            for ((i, color) in SWATCHES.filterNotNull().withIndex()) {
                if (i % 4 != 0) ImGui.sameLine()
                val v = argbToImVec4(color)
                ImGui.pushStyleColor(ImGuiCol.Button, v.x, v.y, v.z, v.w)
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, v.x, v.y, v.z, v.w)
                ImGui.pushStyleColor(ImGuiCol.ButtonActive, v.x, v.y, v.z, v.w)
                val mark = if (current == color) "*" else " "
                if (ImGui.button("$mark##${w.id}-sw$i", SWATCH, SWATCH)) {
                    w.setColor(color)
                    ImGui.closeCurrentPopup()
                }
                ImGui.popStyleColor(3)
                if (ImGui.isItemHovered()) ImGui.setTooltip(RichPalette.entryOf(color)?.name ?: "")
            }
            ImGui.endPopup()
        }
    }

    private companion object {
        const val SWATCH = 20f
    }
}
