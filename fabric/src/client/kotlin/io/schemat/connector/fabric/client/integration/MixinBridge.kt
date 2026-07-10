package io.schemat.connector.fabric.client.integration

import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import net.minecraft.client.Minecraft
import java.nio.file.Path

/**
 * Thin, always-classloadable facade for the Java mixins under
 * `io.schemat.connector.fabric.client.mixin`.
 *
 * The mixins are compiled against Litematica/MaLiLib and are only applied (and only
 * classloaded) when Litematica is present - conditional-mixin's `@Restriction` takes
 * care of that. This object deliberately references NO Litematica types so the mixins'
 * call sites stay one-liners and all Kotlin-side logic lives here.
 */
object MixinBridge {

    /** Opens the Schemat.io browser panel (used by the button injected into Litematica's load GUI). */
    @JvmStatic
    fun openBrowser() {
        val client = Minecraft.getInstance()
        // Close the current MC screen first: the ImGui overlay fires only when no Screen is open.
        client.execute { client.setScreen(null); ImGuiOverlay.ensureOpen(); PanelManager.open(BrowsePanel) }
    }

    /**
     * Opens the upload wizard, pre-seeded with the placement at [placementIndex] in
     * Litematica's placement-manager list (the bridge enumerates placements in that
     * same order). Pass a negative index to open the wizard without a preselection.
     */
    @JvmStatic
    fun openUploadWizardForPlacement(placementIndex: Int) {
        val client = Minecraft.getInstance()
        client.execute {
            val preselect = if (placementIndex >= 0) {
                Bridges.litematica.listExportSources()
                    .filter { it.kind == SourceKind.PLACEMENT }
                    .getOrNull(placementIndex)
            } else {
                null
            }
            // Close the current MC screen first: the ImGui overlay fires only when no Screen is open.
            client.setScreen(null)
            ImGuiOverlay.ensureOpen()
            UploadWizardPanel.open(preselect)
        }
    }

    /**
     * Opens the upload wizard pre-seeded with the schematic file at [path] (used by the
     * "Upload to schemat.io" button injected into Litematica's load GUI - the mixin
     * validates the selection/extension before calling this).
     */
    @JvmStatic
    fun openUploadWizardForFile(path: String) {
        val client = Minecraft.getInstance()
        client.execute {
            val file = Path.of(path).toAbsolutePath()
            val preselect = ExportSource(
                id = file.toString(),
                label = file.fileName.toString(),
                kind = SourceKind.LOCAL_FILE,
            )
            // Close the current MC screen first: the ImGui overlay fires only when no Screen is open.
            client.setScreen(null)
            ImGuiOverlay.ensureOpen()
            UploadWizardPanel.open(preselect)
        }
    }
}
