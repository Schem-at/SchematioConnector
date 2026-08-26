package io.schemat.connector.fabric.client.ui.framework

import dev.harrison.panellib.PanelLib
import dev.harrison.panellib.api.PanelHandle

/**
 * Thin adapter over panel-lib: Schematio panels are registered as raw panels (they draw their own
 * windows) in [io.schemat.connector.fabric.client.ui.SchematioPanels]; this keeps the old call sites
 * (`PanelManager.open(BrowsePanel)`) working.
 */
object PanelManager {
    private val handles = HashMap<String, PanelHandle>()

    internal fun bind(id: String, handle: PanelHandle) { handles[id] = handle }
    private fun handle(id: String): PanelHandle = handles[id] ?: error("panel '$id' is not registered with panel-lib")

    fun open(panel: Panel) = handle(panel.id).open()
    fun close(id: String) = handles[id]?.close() ?: Unit
    fun toggle(panel: Panel) = handle(panel.id).toggle()
    fun isOpen(id: String): Boolean = handles[id]?.isOpen ?: false
    fun anyOpen(): Boolean = handles.values.any { it.isOpen }
    fun closeAll() = handles.values.forEach { it.close() }
    fun closeTop() = PanelLib.api().closeTopPanel()
}
