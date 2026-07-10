package io.schemat.connector.fabric.client.ui.widgets.richtext

import imgui.ImGui
import imgui.flag.ImGuiMouseButton
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.theme.argbToImVec4
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.Companion.SWATCHES
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget.StyleFlag
import io.schemat.connector.fabric.client.ui.widgets.Widgets

/**
 * The B/I/U/S + bullet + color-swatch toolbar row for [RichTextEditorWidget].
 * Extracted verbatim from the widget; operates on the widget's state via [w].
 */
internal class RichTextToolbar(private val w: RichTextEditorWidget) {

    fun render() {
        // Reset each frame; any of the controls below sets it true via [markToolbar] when
        // active/hovered so handleInput knows a click landed on our own toolbar.
        w.toolbarInteracted = false
        styleButton("B", StyleFlag.BOLD)
        ImGui.sameLine()
        styleButton("I", StyleFlag.ITALIC)
        ImGui.sameLine()
        styleButton("U", StyleFlag.UNDERLINE)
        ImGui.sameLine()
        styleButton("S", StyleFlag.STRIKE)
        ImGui.sameLine()
        if (Widgets.button("• List")) w.toggleBulletLine()
        markToolbar()
        ImGui.sameLine()
        // Color swatches: clicking applies to the selection (or pending style).
        for ((i, color) in SWATCHES.withIndex()) {
            val display = color ?: Theme.TEXT_SECONDARY
            val v = argbToImVec4(display)
            val active = w.activeColor() == color
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, v.x, v.y, v.z, v.w)
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, v.x, v.y, v.z, v.w)
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, v.x, v.y, v.z, v.w)
            val label = (if (active) "*" else " ") + "##${w.id}-sw$i"
            if (ImGui.button(label, 18f, 18f)) w.setColor(color)
            markToolbar()
            ImGui.popStyleColor(3)
            if (i < SWATCHES.lastIndex) ImGui.sameLine()
        }
    }

    /** OR the just-rendered toolbar control's active/hovered-click state into [RichTextEditorWidget.toolbarInteracted]. */
    private fun markToolbar() {
        if (ImGui.isItemActive() || (ImGui.isItemHovered() && ImGui.isMouseClicked(ImGuiMouseButton.Left))) {
            w.toolbarInteracted = true
        }
    }

    private fun styleButton(label: String, flag: StyleFlag) {
        if (Widgets.button("$label##${w.id}-$flag", accent = w.isStyleActive(flag))) w.toggleStyle(flag)
        markToolbar()
    }
}
