package io.schemat.schematioConnector.ipc

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.ipc.IpcProtocol
import io.schemat.schematioConnector.SchematioConnector
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression guard for the v2 identity wiring in [PluginIpcService.greet]. Before this wiring,
 * `greet()` built a v2 [io.schemat.connector.core.ipc.HelloServer] without a `platform`, which
 * crashes at [IpcCodec.encodeHelloServer] via `requireNotNull(msg.platform)` — this test pins
 * that greet() always supplies platform + the community identity sourced from the plugin.
 */
class PluginIpcServiceGreetTest {

    @Test
    fun `greet sends a v2 HELLO_SERVER carrying platform and community identity`() {
        val plugin = mockk<SchematioConnector>(relaxed = true)
        val server = mockk<Server>(relaxed = true)
        val description = mockk<PluginDescriptionFile>(relaxed = true)
        val player = mockk<Player>(relaxed = true)

        every { plugin.server } returns server
        every { plugin.description } returns description
        every { description.version } returns "1.4.0"
        every { server.name } returns "Paper"
        every { server.minecraftVersion } returns "1.21.8"
        every { plugin.apiEndpoint } returns "https://schemat.io/api/v1"
        every { plugin.communityId } returns "community-123"
        every { plugin.communitySlug } returns "my-community"

        every { player.uniqueId } returns UUID.randomUUID()
        every { player.listeningPluginChannels } returns setOf(IpcProtocol.CHANNEL)

        val sentBytes = slot<ByteArray>()
        every { player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, capture(sentBytes)) } returns Unit

        val service = PluginIpcService(plugin)
        val event = PlayerRegisterChannelEvent(player, IpcProtocol.CHANNEL)

        // Must not throw — this is exactly the requireNotNull(platform) crash this test pins.
        service.onRegisterChannel(event)

        val decoded = IpcCodec.decodeHelloServer(sentBytes.captured)
        assertEquals(2, decoded.protocolVersion)
        assertEquals("1.4.0", decoded.pluginVersion)
        assertEquals(IpcPlatform.PAPER_PLUGIN, decoded.platform)
        assertEquals("Paper 1.21.8", decoded.serverSoftware)
        assertEquals("1.21.8", decoded.mcVersion)
        assertEquals("https://schemat.io", decoded.backendHost)
        assertEquals("community-123", decoded.communityId)
        assertEquals("my-community", decoded.communitySlug)
    }
}
