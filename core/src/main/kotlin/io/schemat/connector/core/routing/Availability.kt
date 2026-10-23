package io.schemat.connector.core.routing

import io.schemat.connector.core.ipc.Capabilities

data class AvailabilityInput(
    val singleplayer: Boolean,
    val pluginPresent: Boolean,
    val serverCapabilities: Int,
    val localWorldEdit: Boolean,
    val litematica: Boolean,
    /** HELLO_SERVER plugin version; null/unknown/unparseable counts as a current (1.6.0+) plugin. */
    val pluginVersion: String?,
)

object ClientAvailability {

    /** First plugin release that advertises [Capabilities.SERVER_WORLDEDIT] per player. */
    private val SERVER_WORLDEDIT_SINCE = Triple(1, 6, 0)
    private val VERSION_PREFIX = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""")

    /**
     * Whether a plugin of [pluginVersion] advertises SERVER_WORLDEDIT (1.6.0+). Only the
     * leading `major.minor[.patch]` counts, so `1.6.0-SNAPSHOT` is new. Null, `unknown` and
     * anything unparseable also count as new: then only SERVER_WORLDEDIT is trusted, which
     * fails closed rather than letting the static LOAD_CLIPBOARD bit bypass per-player gating.
     */
    fun advertisesServerWorldEditBit(pluginVersion: String?): Boolean {
        val m = VERSION_PREFIX.find(pluginVersion?.trim() ?: return true) ?: return true
        val (major, minor, patch) = m.destructured
        val v = Triple(
            major.toIntOrNull() ?: return true,
            minor.toIntOrNull() ?: return true,
            patch.ifEmpty { "0" }.toIntOrNull() ?: return true,
        )
        val since = SERVER_WORLDEDIT_SINCE
        return compareValuesBy(v, since, { it.first }, { it.second }, { it.third }) >= 0
    }

    /** Maps each unavailable destination to its player-facing reason. [Destination.LOCAL_FILE] is never listed. */
    fun unavailable(input: AvailabilityInput): Map<Destination, String> {
        val result = linkedMapOf<Destination, String>()

        // Pre-1.6.0 plugins have no SERVER_WORLDEDIT bit, so their LOAD_CLIPBOARD is the signal.
        // Newer plugins withhold SERVER_WORLDEDIT per player; LOAD_CLIPBOARD must not undo that.
        val legacyLoadClipboard = !advertisesServerWorldEditBit(input.pluginVersion) &&
            Capabilities.has(input.serverCapabilities, Capabilities.LOAD_CLIPBOARD)
        val serverWorldEdit = Capabilities.has(input.serverCapabilities, Capabilities.SERVER_WORLDEDIT) ||
            legacyLoadClipboard
        when {
            input.singleplayer -> result[Destination.SERVER_WORLDEDIT] = "Singleplayer has no separate server"
            !input.pluginPresent -> result[Destination.SERVER_WORLDEDIT] = "This server has no Schematio Connector"
            !serverWorldEdit ->
                result[Destination.SERVER_WORLDEDIT] = "This server's Schematio can't load into WorldEdit for you"
        }
        if (!input.localWorldEdit) {
            result[Destination.LOCAL_WORLDEDIT] = "WorldEdit is only available in singleplayer or on a LAN host"
        }
        if (!input.litematica) {
            result[Destination.LOCAL_LITEMATICA] = "Litematica is not installed"
        }
        return result
    }
}
