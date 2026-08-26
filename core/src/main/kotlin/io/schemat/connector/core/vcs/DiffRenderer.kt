package io.schemat.connector.core.vcs

/**
 * Rendering backend for a [DiffSession] (spec §4). B1 ships VanillaDisplayRenderer
 * (bukkit, per-player ProtocolLib display entities); B2 adds IpcDiffRenderer (modded
 * clients). The server picks the backend per player.
 *
 * Implementations render for exactly one viewing player and must never leak anything
 * into the shared world.
 */
interface DiffRenderer {

    /** Renders (or re-renders) the whole session at its anchor. */
    fun show(session: DiffSession)

    /** Re-renders with region [index] focused (per-block detail; others box+label). */
    fun focusRegion(index: Int)

    /** Toggles a change-kind layer and re-renders. */
    fun setLayerVisible(kind: DiffKind, visible: Boolean)

    /** Removes everything this renderer spawned for the player. Idempotent. */
    fun clear()
}
