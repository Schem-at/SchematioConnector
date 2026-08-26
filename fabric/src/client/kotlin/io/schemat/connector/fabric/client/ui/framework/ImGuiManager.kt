package io.schemat.connector.fabric.client.ui.framework

import dev.harrison.panellib.PanelLib

/** Adapter: the ImGui context is owned by panel-lib. */
object ImGuiManager {
    fun drainTypedChars(): List<Int> = if (PanelLib.isReady()) PanelLib.api().drainTypedChars() else emptyList()
}
