package io.schemat.connector.fabric.client.ui.framework

import dev.harrison.panellib.PanelLib

/** Adapter over panel-lib's shared overlay (kept for the old call sites). */
object ImGuiOverlay {
    @JvmStatic fun toggleOverlay() = PanelLib.api().toggleOverlay()
    @JvmStatic fun ensureOpen() = PanelLib.api().openOverlay()
    @JvmStatic fun isFocused(): Boolean = PanelLib.isReady() && PanelLib.api().isOverlayFocused
}
