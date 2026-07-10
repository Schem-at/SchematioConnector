package io.schemat.connector.fabric.client.ui.widgets
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiWindowFlags

/**
 * Global singleton ImGui confirm modal, replacing vanilla [ConfirmDialogScreen].
 *
 * Matches vanilla semantics: title, message, confirm/cancel labels, danger styling.
 * [danger] = true renders the confirm button in [ImGuiColors.DANGER] instead of accent.
 *
 * Usage:
 *   ConfirmModal.show("Delete?", "This cannot be undone.", danger = true) { performDelete() }
 *
 * The modal is rendered every frame via [render], called from [ImGuiOverlay.render]
 * after PanelManager.renderAll() and before ImGuiTheme.unapply()/endFrame().
 * Nothing is drawn unless the modal is open.
 */
object ConfirmModal {

    // Fixed popup ID — avoids issues with title strings containing special chars.
    private const val POPUP_ID = "##confirm-modal"

    private var title: String = ""
    private var message: String = ""
    private var confirmLabel: String = "Confirm"
    private var danger: Boolean = false
    private var onConfirm: (() -> Unit)? = null

    /** Set to true for one frame to trigger ImGui.openPopup. */
    private var pendingOpen = false

    /** True while the modal is visible (pending or currently shown). */
    fun isOpen(): Boolean = pendingOpen || onConfirm != null

    /**
     * Opens the confirm modal with the given parameters.
     *
     * Safe to call from the render thread (PanelManager callbacks, button lambdas).
     * [onConfirm] is invoked on the render thread when the user clicks Confirm.
     */
    fun show(
        title: String,
        message: String,
        confirmLabel: String = "Confirm",
        danger: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        this.title = title
        this.message = message
        this.confirmLabel = confirmLabel
        this.danger = danger
        this.onConfirm = onConfirm
        pendingOpen = true
    }

    /**
     * Renders the modal. Must be called every frame from [ImGuiOverlay.render],
     * inside the themed ImGui frame (after ImGuiTheme.apply(), before unapply()).
     *
     * Renders nothing unless the modal is open or pending open.
     */
    fun render() {
        // Trigger: openPopup must be called on the same frame as / just before beginPopupModal.
        if (pendingOpen) {
            ImGui.openPopup(POPUP_ID)
            pendingOpen = false
        }

        // Center the modal on the display each time it opens.
        val displayW = ImGui.getIO().displaySizeX
        val displayH = ImGui.getIO().displaySizeY
        ImGui.setNextWindowPos(displayW / 2f, displayH / 2f, imgui.flag.ImGuiCond.Always, 0.5f, 0.5f)
        ImGui.setNextWindowSize(320f, 0f)  // width=320, height auto-fits

        val flags = ImGuiWindowFlags.NoMove or
                    ImGuiWindowFlags.NoResize or
                    ImGuiWindowFlags.NoCollapse or
                    ImGuiWindowFlags.AlwaysAutoResize

        if (ImGui.beginPopupModal(POPUP_ID, flags)) {
            // Title text — bold via separator label trick; use textColored for danger tint.
            if (danger) {
                ImGui.textColored(
                    ImGuiColors.DANGER.x, ImGuiColors.DANGER.y,
                    ImGuiColors.DANGER.z, ImGuiColors.DANGER.w,
                    title,
                )
            } else {
                ImGui.textColored(
                    ImGuiColors.TEXT_PRIMARY.x, ImGuiColors.TEXT_PRIMARY.y,
                    ImGuiColors.TEXT_PRIMARY.z, ImGuiColors.TEXT_PRIMARY.w,
                    title,
                )
            }

            ImGui.separator()
            ImGui.spacing()

            ImGui.textWrapped(message)

            ImGui.spacing()
            ImGui.separator()
            ImGui.spacing()

            // [Cancel] — plain ghost button (uses theme default Button color).
            if (Widgets.button("Cancel")) {
                clearState()
                ImGui.closeCurrentPopup()
            }

            ImGui.sameLine()

            // [Confirm] — accent or danger styled.
            if (danger) {
                // Push danger palette for the confirm button; pop immediately after.
                ImGui.pushStyleColor(ImGuiCol.Button,        ImGuiColors.DANGER.x,       ImGuiColors.DANGER.y,       ImGuiColors.DANGER.z,       ImGuiColors.DANGER.w)
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiColors.DANGER.x,       ImGuiColors.DANGER.y,       ImGuiColors.DANGER.z,       0.8f)
                ImGui.pushStyleColor(ImGuiCol.ButtonActive,  ImGuiColors.DANGER.x,       ImGuiColors.DANGER.y,       ImGuiColors.DANGER.z,       0.6f)
                val clicked = ImGui.button(confirmLabel)
                ImGui.popStyleColor(3)
                if (clicked) {
                    val cb = onConfirm
                    clearState()
                    ImGui.closeCurrentPopup()
                    cb?.invoke()
                }
            } else {
                if (Widgets.button(confirmLabel, accent = true)) {
                    val cb = onConfirm
                    clearState()
                    ImGui.closeCurrentPopup()
                    cb?.invoke()
                }
            }

            ImGui.endPopup()
        }
    }

    private fun clearState() {
        onConfirm = null
    }
}
