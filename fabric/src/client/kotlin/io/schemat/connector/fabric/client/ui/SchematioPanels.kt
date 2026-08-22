package io.schemat.connector.fabric.client.ui

import dev.harrison.panellib.api.PanelLibApi
import dev.harrison.panellib.api.PanelLibEntrypoint
import io.schemat.connector.fabric.client.ipc.ClipboardUploadFlow
import io.schemat.connector.fabric.client.ipc.ServerIpc
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.CommunityDetailPanel
import io.schemat.connector.fabric.client.ui.panels.PreviewComposerPanel
import io.schemat.connector.fabric.client.ui.panels.QuickShareCreatePanel
import io.schemat.connector.fabric.client.ui.panels.SchematicDetailPanel
import io.schemat.connector.fabric.client.ui.panels.SchematicEditPanel
import io.schemat.connector.fabric.client.ui.panels.SettingsPanel
import io.schemat.connector.fabric.client.ui.panels.SharesPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import io.schemat.connector.fabric.client.ui.theme.Icons
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup

/**
 * panel-lib entrypoint (`"panellib"` in fabric.mod.json): registers the Schematio workspace — every panel
 * as a raw panel (they own their ImGui windows), the Share/Tools menu entries, and the global tag-selector popup.
 */
object SchematioPanels : PanelLibEntrypoint {
    override fun init(api: PanelLibApi) {
        val mod = api.registerMod("schematioconnector", "Schematio", Icons.CUBE)

        fun raw(panel: Panel, title: String, icon: String?, listed: Boolean = true) {
            val handle = mod.rawPanel(panel.id, title, icon, listed) { panel.render() }
            PanelManager.bind(panel.id, handle)
        }
        raw(BrowsePanel, "Browse", Icons.SEARCH)
        raw(UploadWizardPanel, "Upload", Icons.UPLOAD)
        raw(SharesPanel, "My Quick Shares", Icons.SHARE)
        raw(SettingsPanel, "Settings", Icons.GEAR)
        // Context panels: opened from other panels, not from the menu.
        raw(QuickShareCreatePanel, "New Quick Share", Icons.BOLT, listed = false)
        raw(SchematicDetailPanel, "Schematic", Icons.CUBE, listed = false)
        raw(SchematicEditPanel, "Edit schematic", Icons.PEN, listed = false)
        raw(CommunityDetailPanel, "Community", Icons.USERS, listed = false)
        raw(PreviewComposerPanel, "Compose preview", Icons.EYE, listed = false)

        mod.menuSeparator()
        mod.menuItem("New Quick Share", Icons.BOLT) { QuickShareCreatePanel.show(null) }
        mod.menuItem(
            "Upload clipboard to server", Icons.UPLOAD,
            visible = { ServerIpc.canUploadClipboard() },
            enabled = { !ClipboardUploadFlow.isBusy() },
        ) { ClipboardUploadFlow.start() }

        // Global popup rendered every frame; keeps the overlay alive while it is showing.
        mod.frameHook(keepsOverlayOpen = { TagSelectorPopup.isOpen() }) { TagSelectorPopup.render() }
    }
}
