package io.schemat.connector.fabric.client.ui.framework

/**
 * A UI panel that can be registered with [PanelManager].
 *
 * Implementations call ImGui inside [render]; the interface itself has no ImGui dependency
 * so that [PanelManager] remains pure and unit-testable.
 */
interface Panel {
    val id: String
    fun render()
}
