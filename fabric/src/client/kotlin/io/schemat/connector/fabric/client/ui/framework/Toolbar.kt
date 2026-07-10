package io.schemat.connector.fabric.client.ui.framework

import imgui.ImGui
import imgui.flag.ImGuiHoveredFlags
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.CommunitiesPanel
import io.schemat.connector.fabric.client.ui.panels.MySchematicsPanel
import io.schemat.connector.fabric.client.ui.panels.QuickShareCreatePanel
import io.schemat.connector.fabric.client.ui.panels.SettingsPanel
import io.schemat.connector.fabric.client.ui.panels.SharesPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel

/**
 * The persistent Schematio toolbar, rendered INLINE as the [DockHost] top menu bar
 * (the caller wraps this in `beginMenuBar()`/`endMenuBar()`). Themed buttons TOGGLE each
 * main tool window via [PanelManager]; items flow left-to-right along the top bar.
 *
 * Buttons highlight (accent) while their window is open. "Version Control" and
 * "Flow" are disabled placeholders that advertise the framework's future tools.
 *
 * NOTE: bundled font is Latin-1 (Inter-Regular) — ASCII strings only, no icons.
 */
object Toolbar {

    /** Render the toolbar buttons inside the host's menu bar. Items lay out horizontally. */
    fun renderMenuBar() {
        // --- Active tools (toggle open/closed) ---------------------------------
        // Each former BrowserPanel tab is now its own standalone dockable window.
        toolButton("Browse", BrowsePanel.id, open = { PanelManager.toggle(BrowsePanel) })
        toolButton("My Schematics", MySchematicsPanel.id, open = { PanelManager.toggle(MySchematicsPanel) })
        toolButton("Communities", CommunitiesPanel.id, open = { PanelManager.toggle(CommunitiesPanel) })
        toolButton("Quick Shares", SharesPanel.id, open = { PanelManager.toggle(SharesPanel) })
        toolButton("Upload", UploadWizardPanel.id, open = {
            if (PanelManager.isOpen(UploadWizardPanel.id)) PanelManager.close(UploadWizardPanel.id)
            else UploadWizardPanel.open()
        })
        toolButton("Quick Share", QuickShareCreatePanel.id, open = {
            if (PanelManager.isOpen(QuickShareCreatePanel.id)) PanelManager.close(QuickShareCreatePanel.id)
            else QuickShareCreatePanel.show(null)
        })
        toolButton("Settings", SettingsPanel.id, open = { PanelManager.toggle(SettingsPanel) })

        ImGui.textDisabled("|")

        // --- Future tools (disabled placeholders) ------------------------------
        placeholderButton("Version Control")
        placeholderButton("Flow")
    }

    /**
     * A themed toolbar button. Highlights with the accent palette while [windowId]'s
     * window is open. Clicking runs [open].
     */
    private fun toolButton(label: String, windowId: String, open: () -> Unit) {
        val active = PanelManager.isOpen(windowId)
        // Full-width buttons read as a vertical tool list.
        if (Widgets.button(label, accent = active)) {
            open()
        }
    }

    /** A greyed-out, no-op button advertising a tool that is not built yet. */
    private fun placeholderButton(label: String) {
        ImGui.beginDisabled()
        Widgets.button(label)
        ImGui.endDisabled()
        // AllowWhenDisabled: by default disabled items don't report hover, so the tooltip
        // would never show — pass the flag so "coming soon" appears on hover.
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("coming soon")
        }
    }
}
