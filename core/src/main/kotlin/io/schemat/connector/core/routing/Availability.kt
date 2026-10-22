package io.schemat.connector.core.routing

import io.schemat.connector.core.ipc.Capabilities

data class AvailabilityInput(
    val singleplayer: Boolean,
    val pluginPresent: Boolean,
    val serverCapabilities: Int,
    val localWorldEdit: Boolean,
    val litematica: Boolean,
)

object ClientAvailability {

    /** Maps each unavailable destination to its player-facing reason. [Destination.LOCAL_FILE] is never listed. */
    fun unavailable(input: AvailabilityInput): Map<Destination, String> {
        val result = linkedMapOf<Destination, String>()

        val serverWorldEdit = Capabilities.has(input.serverCapabilities, Capabilities.SERVER_WORLDEDIT) ||
            Capabilities.has(input.serverCapabilities, Capabilities.LOAD_CLIPBOARD)
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
