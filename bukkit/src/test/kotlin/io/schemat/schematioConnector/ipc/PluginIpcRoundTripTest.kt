package io.schemat.schematioConnector.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sk89q.worldedit.extent.clipboard.Clipboard
import io.mockk.*
import io.schemat.connector.core.attest.*
import io.schemat.connector.core.ipc.*
import io.schemat.connector.core.modapi.ClipboardResolveClient
import io.schemat.connector.core.modapi.ClipboardUploadClient
import io.schemat.connector.core.modapi.transport.*
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.WorldEditUtil
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlin.test.*

/** Real plugin handler, codecs and backend clients; Minecraft scheduler/clipboard and HTTP are fakes. */
class PluginIpcRoundTripTest {
    private val plugin = mockk<SchematioConnector>(relaxed = true)
    private val player = mockk<Player>(relaxed = true)
    private val server = mockk<Server>(relaxed = true)
    private val scheduler = mockk<BukkitScheduler>(relaxed = true)
    private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val sent = mutableListOf<ByteArray>()
    private val requests = mutableListOf<ApiRequest>()
    private val pending = ArrayDeque<Runnable>()
    private val bytes = byteArrayOf(1, 2, 3)
    private val clipboard = mockk<Clipboard>()
    private val nonce = ByteArray(16) { it.toByte() }
    private val transport = object : ApiTransport {
        override suspend fun execute(request: ApiRequest, bearerToken: String?): ApiResponse {
            assertEquals("community-token", bearerToken)
            requests += request
            return when (request.path) {
                "/plugin/attest" -> {
                    val nonceHex = JsonParser.parseString(request.jsonBody).asJsonObject["nonce_hex"].asString
                    val payload = """{"communityId":"community","issuedAt":${System.currentTimeMillis() / 1000},"nonce":"$nonceHex","platform":"PAPER_PLUGIN","tokenId":"token"}"""
                    val signature = Signature.getInstance("Ed25519").run {
                        initSign(key.private)
                        update(payload.toByteArray())
                        sign()
                    }
                    val body = JsonObject().apply {
                        addProperty("payload", payload)
                        addProperty("signature_base64", Base64.getEncoder().encodeToString(signature))
                        addProperty("key_id", "test-key")
                    }
                    ApiResponse(200, body.toString().toByteArray())
                }
                "/plugin/clipboard/resolve" -> ApiResponse(200, bytes, mapOf("Content-Length" to bytes.size.toString()))
                "/plugin/clipboard/drafts" -> ApiResponse(201, """{"draft_id":"draft","web_url":"https://schemat.io/drafts/draft"}""".toByteArray())
                else -> error("Unexpected request ${request.path}")
            }
        }
    }

    init {
        every { plugin.server } returns server
        every { server.scheduler } returns scheduler
        every { plugin.description } returns PluginDescriptionFile("SchematioConnector", "test", "unused.Main")
        every { server.name } returns "Paper"
        every { server.minecraftVersion } returns "1.21.11"
        every { plugin.apiEndpoint } returns "https://schemat.io/api/v1"
        every { plugin.communityId } returns "community"
        every { plugin.communitySlug } returns "test-community"
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.name } returns "BridgeTest"
        every { player.isOnline } returns true
        every { player.listeningPluginChannels } returns setOf(IpcProtocol.CHANNEL)
        every { player.hasPermission(any<String>()) } returns true
        every { player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, any()) } answers {
            sent += thirdArg<ByteArray>()
        }
        every { scheduler.runTaskAsynchronously(plugin, any<Runnable>()) } answers {
            pending.addLast(secondArg())
            mockk<BukkitTask>()
        }
        every { scheduler.runTask(plugin, any<Runnable>()) } answers {
            secondArg<Runnable>().run()
            mockk<BukkitTask>()
        }
        every { plugin.attestationClient } returns AttestationClient(transport, { "community-token" })
        every { plugin.clipboardResolveClient } returns ClipboardResolveClient(transport, { "community-token" })
        every { plugin.clipboardUploadClient } returns ClipboardUploadClient(transport, { "community-token" })
        every { plugin.clipboardUploadService } returns ClipboardUploadService(plugin)
        mockkObject(WorldEditUtil)
        every { WorldEditUtil.byteArrayToClipboard(bytes) } returns clipboard
        every { WorldEditUtil.setClipboard(player, clipboard) } just Runs
        every { WorldEditUtil.getClipboard(player) } returns clipboard
        every { WorldEditUtil.clipboardToByteArray(clipboard) } returns bytes
    }

    @AfterEach fun cleanup() = unmockkObject(WorldEditUtil)

    private fun hello(service: PluginIpcService, helloNonce: ByteArray = nonce) {
        service.onPluginMessageReceived(IpcProtocol.CHANNEL, player, IpcCodec.encodeHelloClient(
            HelloClient(IpcProtocol.VERSION, "test", Capabilities.WANTS_COMMAND_OWNERSHIP, helloNonce)))
    }

    private fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }

    @Test fun `plugin hello attestation clipboard load upload and UI handoff round trip`() {
        val service = PluginIpcService(plugin)
        hello(service)
        assertFalse(service.canOpenUi(player))
        drain()
        val greeting = IpcCodec.decodeHelloServer(sent.first())
        assertTrue(Capabilities.has(greeting.capabilities, Capabilities.UPLOAD))
        assertTrue(Capabilities.has(greeting.capabilities, Capabilities.LOAD_CLIPBOARD))
        val attest = IpcCodec.decodeAttest(sent.last())
        assertIs<AttestOutcome.Verified>(AttestationVerifier.verify(
            attest.payloadJson, attest.signature, attest.keyId,
            mapOf("test-key" to key.public.encoded.takeLast(32).toByteArray()),
            bytesToHexLower(nonce), greeting.communityId))
        assertTrue(service.sendOpenUi(player, OpenUiSurface.BROWSE))
        assertEquals(OpenUiSurface.BROWSE.wire, IpcCodec.decodeOpenUi(sent.last()).surface)

        sent.clear()
        service.onPluginMessageReceived(IpcProtocol.CHANNEL, player, IpcCodec.encodeLoadRequest(
            LoadRequest(IpcProtocol.VERSION, 17, LoadRefType.SCHEMATIC.wire, "schematic", "version")))
        drain()
        assertEquals(listOf(StatusState.RESOLVING.wire, StatusState.DOWNLOADING.wire, StatusState.OK.wire),
            sent.map { IpcCodec.decodeStatus(it).state })
        assertTrue(sent.all { IpcCodec.decodeStatus(it).requestId == 17 })
        verify(exactly = 1) { WorldEditUtil.setClipboard(player, clipboard) }
        val resolve = JsonParser.parseString(requests.last().jsonBody).asJsonObject
        assertEquals(player.uniqueId.toString(), resolve["player_uuid"].asString)
        assertEquals("version", resolve["version_id"].asString)

        sent.clear()
        service.onPluginMessageReceived(IpcProtocol.CHANNEL, player,
            IpcCodec.encodeUploadClipboard(UploadClipboard(IpcProtocol.VERSION, 91)))
        drain()
        val draft = IpcCodec.decodeDraftCreated(sent.last())
        assertEquals(91, draft.requestId)
        assertEquals("draft", draft.draftId)
        assertContentEquals(bytes, requests.last().multipart!!.files.single().bytes)
    }

    @Test fun `duplicate hellos while attestation is pending do not flood the backend`() {
        val service = PluginIpcService(plugin)
        repeat(100) { hello(service, ByteArray(16) { it.toByte() }) }
        assertEquals(1, pending.size)
        drain()
        assertEquals(1, requests.size)
    }

    @Test fun `old attestation cannot authorize a reconnected player with the same UUID`() {
        val service = PluginIpcService(plugin)
        hello(service)
        service.onQuit(PlayerQuitEvent(player, "quit"))
        hello(service, ByteArray(16) { 42 })
        pending.removeFirst().run()
        assertFalse(service.canOpenUi(player))
        drain()
        assertTrue(service.canOpenUi(player))
    }

    @Test fun `permission denial sends terminal status without a backend load`() {
        val service = PluginIpcService(plugin)
        hello(service)
        drain()
        every { player.hasPermission(LoadRequestGuards.LOAD_PERMISSION) } returns false
        sent.clear()
        service.onPluginMessageReceived(IpcProtocol.CHANNEL, player, IpcCodec.encodeLoadRequest(
            LoadRequest(IpcProtocol.VERSION, 22, LoadRefType.SCHEMATIC.wire, "schematic", "")))
        assertEquals(StatusState.DENIED.wire, IpcCodec.decodeStatus(sent.single()).state)
        assertEquals(1, requests.size)
        assertTrue(pending.isEmpty())
    }
}
