package io.schemat.connector.fabric.client.ui.widgets
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.type.ImString

/**
 * Thin themed helpers over native Dear ImGui, baking in the SchematioConnector design language.
 *
 * All colors reference [ImGuiColors] — no raw hex or numeric ImVec4 literals here.
 */
object Widgets {

    /**
     * Renders a button with the given [label].
     *
     * When [accent] is true, the button/hovered/active colors are overridden with the
     * accent palette for the duration of the call. Exactly 3 colors are pushed and
     * popped — the push/pop is balanced regardless of the return value.
     *
     * @return true on the frame the button is clicked.
     */
    fun button(label: String, accent: Boolean = false): Boolean {
        if (accent) {
            ImGui.pushStyleColor(ImGuiCol.Button,        ImGuiColors.ACCENT.x,       ImGuiColors.ACCENT.y,       ImGuiColors.ACCENT.z,       ImGuiColors.ACCENT.w)
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiColors.ACCENT_HOVER.x, ImGuiColors.ACCENT_HOVER.y, ImGuiColors.ACCENT_HOVER.z, ImGuiColors.ACCENT_HOVER.w)
            ImGui.pushStyleColor(ImGuiCol.ButtonActive,  ImGuiColors.ACCENT_DIM.x,   ImGuiColors.ACCENT_DIM.y,   ImGuiColors.ACCENT_DIM.z,   ImGuiColors.ACCENT_DIM.w)
        }
        val clicked = ImGui.button(label)
        if (accent) {
            ImGui.popStyleColor(3)
        }
        return clicked
    }

    /**
     * Renders a single-line text input field.
     *
     * When [hint] is non-null, the hint string is shown as placeholder text via
     * [ImGui.inputTextWithHint]; otherwise [ImGui.inputText] is used.
     * The caller owns the [ImString] buffer.
     *
     * @return true while the field is being edited (each frame a character changes).
     */
    fun textField(label: String, state: ImString, hint: String? = null): Boolean {
        return if (hint != null) {
            ImGui.inputTextWithHint(label, hint, state)
        } else {
            ImGui.inputText(label, state)
        }
    }

    /**
     * Renders a tab bar with the given [id].
     *
     * Each entry in [tabs] is a pair of (tab title, content lambda). The content lambda
     * is only invoked when its tab is active. [ImGui.endTabItem] is called only when
     * [ImGui.beginTabItem] returned true; [ImGui.endTabBar] is called only when
     * [ImGui.beginTabBar] returned true.
     */
    fun tabBar(id: String, tabs: List<Pair<String, () -> Unit>>) {
        if (ImGui.beginTabBar(id)) {
            for ((title, content) in tabs) {
                if (ImGui.beginTabItem(title)) {
                    content()
                    ImGui.endTabItem()
                }
            }
            ImGui.endTabBar()
        }
    }

    /**
     * Status severity used by [statusText] to select the appropriate color.
     */
    enum class StatusKind { SUCCESS, DANGER, WARNING, INFO }

    /**
     * Renders [text] in the color corresponding to [kind], using [ImGuiColors] constants.
     */
    fun statusText(text: String, kind: StatusKind) {
        val c = when (kind) {
            StatusKind.SUCCESS -> ImGuiColors.SUCCESS
            StatusKind.DANGER  -> ImGuiColors.DANGER
            StatusKind.WARNING -> ImGuiColors.WARNING
            StatusKind.INFO    -> ImGuiColors.INFO
        }
        ImGui.textColored(c.x, c.y, c.z, c.w, text)
    }
}
