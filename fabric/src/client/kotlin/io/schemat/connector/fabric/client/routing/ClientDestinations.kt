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

    private val auth get() = SchematioClientMod.instance.services.authManager

    /**
     * Profiles are read every frame by the panels, so they come from an in-memory cache, not
     * disk. There is no client config-reload hook; config.properties is only written through
     * [save] while the game runs, so the cache cannot go stale.
     */
    private val store = DestinationProfileStore(
        read = { name -> auth.getConfigString(name) },
        write = { name, value -> auth.setConfigString(name, value) },
    )

    /**
     * LAN-guest and Realms addresses may vary between sessions, so their profile may not persist.
     * Singleplayer, else the connected server's address, else the shared default. */
    fun profileKey(): String {
        val client = Minecraft.getInstance()
        if (client.singleplayerServer != null) return DestinationProfiles.SINGLEPLAYER_KEY
        val address = client.currentServer?.ip
        return if (address.isNullOrBlank()) DestinationProfiles.DEFAULT_KEY else DestinationProfiles.serverKey(address)
    }

    /** The stored profile, else the stored default, else [Destination.DEFAULT_ORDER]. */
    fun order(key: String = profileKey()): List<Destination> = store.order(key)

    fun hasOwnProfile(key: String): Boolean = store.hasOwnProfile(key)

    /** Stores [order] for [key]; null resets the profile. */
    fun save(key: String, order: List<Destination>?) = store.save(key, order)

    fun unavailable(): Map<Destination, String> = ClientAvailability.unavailable(
        AvailabilityInput(
            singleplayer = Minecraft.getInstance().singleplayerServer != null,
            pluginPresent = ServerSession.pluginPresent,
            serverCapabilities = ServerSession.capabilities,
            localWorldEdit = Bridges.worldEdit.isAvailable,
            litematica = Bridges.litematica.isAvailable,
            pluginVersion = ServerSession.pluginVersion,
        ),
    )
}
