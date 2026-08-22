package io.schemat.connector.fabric.client.ui.theme

import imgui.ImGui
import imgui.flag.ImGuiTableFlags

/** Leftover helpers from the pre-panel-lib theme; styling itself is applied by panel-lib. */
object ImGuiTheme {
    /** 2px accent underline across the title bar of the current (floating, focused) window. */
    fun windowTitleAccent() {
        if (ImGui.isWindowDocked() || !ImGui.isWindowFocused()) return
        val x = ImGui.getWindowPosX(); val y = ImGui.getWindowPosY(); val w = ImGui.getWindowWidth()
        val titleH = ImGui.getFrameHeight()
        val a = ImGuiColors.ACCENT
        val dl = ImGui.getWindowDrawList()
        dl.pushClipRect(x, y, x + w, y + titleH, false)
        dl.addRectFilled(x, y + titleH - 2f, x + w, y + titleH, ImGui.getColorU32(a.x, a.y, a.z, 1f))
        dl.popClipRect()
    }

    inline fun withStandardTable(id: String, columns: Int, block: () -> Unit) {
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH or ImGuiTableFlags.ScrollY
        if (ImGui.beginTable(id, columns, flags)) {
            try { block() } finally { ImGui.endTable() }
        }
    }
}
