package io.schemat.connector.fabric.client.ui.theme

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiTableFlags

/**
 * Pushes the SchematioConnector design palette and spacing scale onto ImGui's style stacks.
 *
 * Call [apply] once before rendering a frame/window and [unapply] in the matching finally block.
 * The push counts are tracked internally so [unapply] always pops exactly as many entries as
 * [apply] pushed — an imbalanced stack corrupts all subsequent ImGui rendering.
 *
 * Color mapping summary:
 *   WindowBg       → BG            (darkest background)
 *   ChildBg        → SURFACE       (card/panel surface)
 *   PopupBg        → SURFACE_ALT   (elevated surface for popups/dropdowns)
 *   Border         → BORDER        (default 1px border)
 *   FrameBg        → SURFACE_ALT   (input/frame background)
 *   FrameBgHovered → SURFACE_HOVER
 *   FrameBgActive  → SURFACE_HOVER
 *   Button         → SURFACE_ALT
 *   ButtonHovered  → SURFACE_HOVER
 *   ButtonActive   → ACCENT_DIM    (fuchsia dim on press)
 *   Header         → ACCENT_DIM    (selected row / collapsible header)
 *   HeaderHovered  → ACCENT_HOVER
 *   HeaderActive   → ACCENT        (full fuchsia on click)
 *   Tab            → SURFACE
 *   TabHovered     → ACCENT_HOVER
 *   Text           → TEXT_PRIMARY
 *   TextDisabled   → TEXT_MUTED
 *   CheckMark      → ACCENT        (fuchsia checkmark)
 *
 * Style var mapping (19 pushed colors, 7 pushed vars):
 *   WindowPadding(12,12), FramePadding(8,6), ItemSpacing(8,6)
 *   WindowRounding(4), FrameRounding(3), WindowBorderSize(1), FrameBorderSize(1)
 */
object ImGuiTheme {
    private var pushedColors = 0
    private var pushedVars = 0

    fun apply() {
        // Reset counters at start so repeated apply() calls don't accumulate
        pushedColors = 0
        pushedVars = 0

        fun col(idx: Int, c: imgui.ImVec4) {
            ImGui.pushStyleColor(idx, c.x, c.y, c.z, c.w)
            pushedColors++
        }

        fun varv(idx: Int, x: Float, y: Float) {
            ImGui.pushStyleVar(idx, x, y)
            pushedVars++
        }

        fun var1(idx: Int, v: Float) {
            ImGui.pushStyleVar(idx, v)
            pushedVars++
        }

        // --- Colors (18 total) ---
        col(ImGuiCol.WindowBg,       ImGuiColors.BG)
        col(ImGuiCol.ChildBg,        ImGuiColors.SURFACE)
        col(ImGuiCol.PopupBg,        ImGuiColors.SURFACE_ALT)
        col(ImGuiCol.Border,         ImGuiColors.BORDER)
        col(ImGuiCol.FrameBg,        ImGuiColors.SURFACE_ALT)
        col(ImGuiCol.FrameBgHovered, ImGuiColors.SURFACE_HOVER)
        col(ImGuiCol.FrameBgActive,  ImGuiColors.SURFACE_HOVER)
        col(ImGuiCol.Button,         ImGuiColors.SURFACE_ALT)
        col(ImGuiCol.ButtonHovered,  ImGuiColors.SURFACE_HOVER)
        col(ImGuiCol.ButtonActive,   ImGuiColors.ACCENT_DIM)
        col(ImGuiCol.Header,         ImGuiColors.ACCENT_DIM)
        col(ImGuiCol.HeaderHovered,  ImGuiColors.ACCENT_HOVER)
        col(ImGuiCol.HeaderActive,   ImGuiColors.ACCENT)
        col(ImGuiCol.Tab,            ImGuiColors.SURFACE)
        col(ImGuiCol.TabHovered,     ImGuiColors.ACCENT_HOVER)
        col(ImGuiCol.TabActive,      ImGuiColors.ACCENT_DIM)
        col(ImGuiCol.Text,           ImGuiColors.TEXT_PRIMARY)
        col(ImGuiCol.TextDisabled,   ImGuiColors.TEXT_MUTED)
        col(ImGuiCol.CheckMark,      ImGuiColors.ACCENT)
        // pushedColors == 19

        // --- Style vars (7 total) ---
        varv(ImGuiStyleVar.WindowPadding,  12f, 12f)
        varv(ImGuiStyleVar.FramePadding,    8f,  6f)
        varv(ImGuiStyleVar.ItemSpacing,     8f,  6f)
        var1(ImGuiStyleVar.WindowRounding,  4f)
        var1(ImGuiStyleVar.FrameRounding,   3f)
        var1(ImGuiStyleVar.WindowBorderSize, 1f)
        var1(ImGuiStyleVar.FrameBorderSize,  1f)
        // pushedVars == 7
    }

    /**
     * Pops exactly as many colors and vars as [apply] pushed.
     * Must be called in a finally block after every [apply].
     */
    fun unapply() {
        ImGui.popStyleVar(pushedVars)
        ImGui.popStyleColor(pushedColors)
    }

    /**
     * The only approved table entry point for SchematioConnector ImGui UIs.
     *
     * Wraps [ImGui.beginTable] / [ImGui.endTable] with RowBg + BordersInnerH + ScrollY flags.
     * [block] is only invoked if beginTable returns true; endTable is always called in that case.
     */
    inline fun withStandardTable(id: String, columns: Int, block: () -> Unit) {
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH or ImGuiTableFlags.ScrollY
        if (ImGui.beginTable(id, columns, flags)) {
            try {
                block()
            } finally {
                ImGui.endTable()
            }
        }
    }
}
