package io.schemat.schematioConnector.ipc

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.schemat.connector.core.attest.Attestation
import io.schemat.connector.core.attest.AttestationClient
import io.schemat.connector.core.attest.bytesToHexLower
import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.HelloClient
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.ipc.IpcProtocol
import io.schemat.schematioConnector.SchematioConnector
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A re-greet (reload / token change) restarts the handshake: Paper forgets each player's
 * attestation and client flags, so it never hands off to a client that is re-verifying,
 * and a second HELLO_CLIENT on the same connection re-runs attestation for the new nonce.
 */
class PluginIpcServiceRegreetTest {

    private val plugin = mockk<SchematioConnector>(relaxed = true)
    private val server = mockk<Server>(relaxed = true)
    private val scheduler = mockk<BukkitScheduler>(relaxed = true)
    private val attestClient = mockk<AttestationClient>()
    private val player = mockk<Player>(relaxed = true)

    init {
        val description = mockk<PluginDescriptionFile>(relaxed = true)
        every { plugin.server } returns server
        every { plugin.description } returns description
        every { description.version } returns "1.6.0"
        every { plugin.apiEndpoint } returns "https://schemat.io/api/v1"
        every { plugin.communityId } returns "community-123"
        every { plugin.communitySlug } returns "my-community"
        every { plugin.attestationClient } returns attestClient
        every { server.scheduler } returns scheduler
        every { server.onlinePlayers } returns listOf(player)
        // Run scheduled work inline so the attest round-trip completes synchronously.
        every { scheduler.runTaskAsynchronously(plugin, any<Runnable>()) } answers {
            secondArg<Runnable>().run(); mockk(relaxed = true)
        }
        every { scheduler.runTask(plugin, any<Runnable>()) } answers {
            secondArg<Runnable>().run(); mockk(relaxed = true)
        }
        coEvery { attestClient.requestAttestation(any(), IpcPlatform.PAPER_PLUGIN) } returns
            Attestation("{}", ByteArray(64), "k1")

        every { player.uniqueId } returns UUID.randomUUID()
        every { player.name } returns "Steve"
        every { player.isOnline } returns true
        every { player.listeningPluginChannels } returns setOf(IpcProtocol.CHANNEL)
    }

    private fun hello(
        nonce: ByteArray,
        flags: Int = Capabilities.WANTS_COMMAND_OWNERSHIP or Capabilities.ROUTES_DOWNLOADS,
    ) = IpcCodec.encodeHelloClient(HelloClient(IpcProtocol.VERSION, "1.6.0", flags, nonce))

    @Test
    fun `regreetAll clears attestation and a second HELLO_CLIENT re-attests the new nonce`() {
        val service = PluginIpcService(plugin)
        val n1 = ByteArray(16) { 1 }
        val n2 = ByteArray(16) { 2 }

        service.onPluginMessageReceived(IpcProtocol.CHANNEL, player, hello(n1))
        assertTrue(service.canOpenUi(player), "attested + WANTS_COMMAND_OWNERSHIP after the first hello")

        service.regreetAll()
        assertFalse(service.canOpenUi(player), "never hand off to a client that is re-verifying")

        service.onPluginMessageReceived(IpcProtocol.CHANNEL, player, hello(n2))
        coVerify(exactly = 1) { attestClient.requestAttestation(bytesToHexLower(n2), IpcPlatform.PAPER_PLUGIN) }
        assertTrue(service.canOpenUi(player), "re-attested on the same connection")
    }

    @Test
    fun `regreetAll leaves a 1_5 client that cannot re-hello attested`() {
        val service = PluginIpcService(plugin)
        service.onPluginMessageReceived(
            IpcProtocol.CHANNEL, player, hello(ByteArray(16) { 1 }, Capabilities.WANTS_COMMAND_OWNERSHIP),
        )
        service.regreetAll()
        assertTrue(service.canOpenUi(player), "a released client is not re-greeted, so it keeps its attestation")
    }

    @Test
    fun `a repeated HELLO_CLIENT without a re-greet is still ignored`() {
        val service = PluginIpcService(plugin)
        service.onPluginMessageReceived(IpcProtocol.CHANNEL, player, hello(ByteArray(16) { 1 }))
        service.onPluginMessageReceived(IpcProtocol.CHANNEL, player, hello(ByteArray(16) { 3 }))
        coVerify(exactly = 1) { attestClient.requestAttestation(any(), any()) }
    }
}
