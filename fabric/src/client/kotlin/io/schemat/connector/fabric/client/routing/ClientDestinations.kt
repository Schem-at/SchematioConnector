package io.schemat.connector.fabric.client.routing

import io.schemat.connector.core.routing.AvailabilityInput
import io.schemat.connector.core.routing.ClientAvailability
import io.schemat.connector.core.routing.Destination
import io.schemat.connector.core.routing.DestinationProfiles
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.ipc.ServerSession
import net.minecraft.client.Minecraft

/** Per-world download destination profiles and live availability. Client thread only. */
object ClientDestinations {

    private const val PREFIX = "download_destinations."

    private val auth get() = SchematioClientMod.instance.services.authManager

    /** Singleplayer, else the connected server's address, else the shared default. */
    fun profileKey(): String {
        val client = Minecraft.getInstance()
        if (client.singleplayerServer != null) return DestinationProfiles.SINGLEPLAYER_KEY
        val address = client.currentServer?.ip
        return if (address.isNullOrBlank()) DestinationProfiles.DEFAULT_KEY else DestinationProfiles.serverKey(address)
    }

    /** The stored profile, else the stored default, else [Destination.DEFAULT_ORDER]. */
    fun order(key: String = profileKey()): List<Destination> =
        DestinationProfiles.decode(auth.getConfigString(PREFIX + key))
            ?: DestinationProfiles.decode(auth.getConfigString(PREFIX + DestinationProfiles.DEFAULT_KEY))
            ?: Destination.DEFAULT_ORDER

    fun hasOwnProfile(key: String): Boolean = DestinationProfiles.decode(auth.getConfigString(PREFIX + key)) != null

    /** Stores [order] for [key]; null resets the profile. */
    fun save(key: String, order: List<Destination>?) {
        auth.setConfigString(PREFIX + key, order?.let { DestinationProfiles.encode(it) })
    }

    fun unavailable(): Map<Destination, String> = ClientAvailability.unavailable(
        AvailabilityInput(
            singleplayer = Minecraft.getInstance().singleplayerServer != null,
            pluginPresent = ServerSession.pluginPresent,
            serverCapabilities = ServerSession.capabilities,
            localWorldEdit = Bridges.worldEdit.isAvailable,
            litematica = Bridges.litematica.isAvailable,
        ),
    )
}
