package io.schemat.connector.fabric.client.ui.panels

/** Sizing for the schematic edit form's description editor. */
object EditorLayout {

    /** Room kept under the editor: separator, one status line and the Save/Cancel row. */
    const val FOOTER = 84f

    /** Smallest editor that still shows a few lines. */
    const val MIN = 70f

    /** Editor height given the vertical space [available] below the "Description" label. */
    fun descriptionHeight(available: Float): Float = (available - FOOTER).coerceAtLeast(MIN)
}
