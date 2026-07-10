package io.schemat.connector.fabric.client.ui.framework

/**
 * Registry of open ImGui panels, preserving insertion/z-order via [LinkedHashMap].
 *
 * Dedup policy: [open] is a no-op if a panel with the same [Panel.id] is already open.
 * This prevents accidental double-registration while keeping the existing panel instance.
 */
object PanelManager {

    private val panels = LinkedHashMap<String, Panel>()

    /** Opens [panel]. No-op if a panel with the same id is already open. */
    fun open(panel: Panel) {
        panels.putIfAbsent(panel.id, panel)
    }

    /** Closes the panel with the given [id]. No-op if not open. */
    fun close(id: String) {
        panels.remove(id)
    }

    /**
     * Toggles [panel]: closes it if open, opens it if closed.
     * Uses [panel.id] for the lookup.
     */
    fun toggle(panel: Panel) {
        if (panels.containsKey(panel.id)) close(panel.id) else open(panel)
    }

    /** Returns true if a panel with [id] is currently open. */
    fun isOpen(id: String): Boolean = panels.containsKey(id)

    /** Returns true if at least one panel is open. */
    fun anyOpen(): Boolean = panels.isNotEmpty()

    /** Returns a snapshot list of open panels in insertion order. */
    fun openPanels(): List<Panel> = panels.values.toList()

    /** Closes all open panels. */
    fun closeAll() {
        panels.clear()
    }

    /**
     * Closes the most-recently-opened (topmost/last-in-order) panel.
     * Called by the Escape key handler so Escape dismisses panels one at a time
     * instead of opening the MC pause menu.
     * No-op if no panels are open.
     */
    fun closeTop() {
        val lastKey = panels.keys.lastOrNull() ?: return
        panels.remove(lastKey)
    }

    /** Calls [Panel.render] on each open panel in insertion order. */
    fun renderAll() {
        panels.values.toList().forEach { it.render() }
    }
}
