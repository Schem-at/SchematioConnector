package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.HelloClient
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcFormatException
import io.schemat.connector.core.ipc.IpcOpcode
import io.schemat.connector.core.ipc.IpcPayloadTooLargeException
import io.schemat.connector.core.ipc.IpcProtocol
import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.ipc.LoadRequest
import io.schemat.connector.core.ipc.OpenUi
import io.schemat.connector.core.ipc.OpenUiSurface
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.ipc.UploadClipboard
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.SchematicDetailPanel
import io.schemat.connector.fabric.client.ui.panels.SettingsPanel
import io.schemat.connector.fabric.client.ui.panels.SharesPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object ServerIpc {
    private val LOGGER = LoggerFactory.getLogger("SchematioIpc")
    private const val MOD_ID = "schematioconnector"

    /**
     * Client-side OPEN_UI policy (spec invariants 1-3): VERIFIED session + user toggle
     * + 1-per-2s. Time-windowed, so it needs no per-connection reset — a new server
     * must earn VERIFIED before any OPEN_UI is honored anyway.
     */
    private val openUiGate = OpenUiGate()

    fun init() {
        // Fabric networking API 6.x (>=26.1) renamed playS2C/playC2S to
        // clientboundPlay/serverboundPlay.
        //? if >=26.1 {
        /*PayloadTypeRegistry.clientboundPlay().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        *///?} else {
        PayloadTypeRegistry.playS2C().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        //?}

        ClientPlayNetworking.registerGlobalReceiver(SchematioPayload.TYPE) { payload, _ ->
            // Fabric invokes this on the client thread.
            handle(payload.data)
        }
    }

    private fun handle(data: ByteArray) {
        try {
            when (IpcCodec.peekOpcode(data)) {
                IpcOpcode.HELLO_SERVER -> {
                    val hello = IpcCodec.decodeHelloServer(data)
                    ServerSession.adopt(hello)
                    LOGGER.info("Connected to Schematio server v${hello.pluginVersion} (proto ${hello.protocolVersion})")
                    // 26.1 removed Player.displayClientMessage(Component, boolean);
                    // sendSystemMessage(Component) is the chat-log equivalent (the
                    // false/non-actionbar case used here).
                    //? if >=26.1 {
                    /*Minecraft.getInstance().player?.sendSystemMessage(
                        Component.literal("§aSchematio server detected: v${hello.pluginVersion}"),
                    )
                    *///?} else {
                    Minecraft.getInstance().player?.displayClientMessage(
                        Component.literal("§aSchematio server detected: v${hello.pluginVersion}"),
                        false,
                    )
                    //?}
                    sendClientHello()
                }
                IpcOpcode.ATTEST -> {
                    val attest = IpcCodec.decodeAttest(data)
                    LOGGER.info("Received attestation (keyId=${attest.keyId}); verifying against our backend…")
                    AttestFlow.onAttest(attest)
                }
                IpcOpcode.STATUS -> {
                    val status = IpcCodec.decodeStatus(data)
                    val state = StatusState.fromWire(status.state) ?: return
                    // STATUS is generic (contract C2): the load and upload trackers issue
                    // ids from disjoint ranges, so exactly one of these reacts.
                    ClipboardLoadTracker.onStatus(status.requestId, state, status.detail)
                    ClipboardUploadTracker.onStatus(status.requestId, state, status.detail)
                }
                IpcOpcode.DRAFT_CREATED -> {
                    val msg = IpcCodec.decodeDraftCreated(data)
                    ClipboardUploadTracker.onDraft(msg.requestId, msg.draftId)
                }
                IpcOpcode.OPEN_UI -> handleOpenUi(IpcCodec.decodeOpenUi(data))
                else -> { /* ignore unknown opcodes */ }
            }
        } catch (_: IpcPayloadTooLargeException) {
            // Spec: over-cap payloads are dropped quietly.
        } catch (e: IpcFormatException) {
            LOGGER.warn("Malformed Schematio IPC from server: ${e.message}")
        }
    }

    fun sendClientHello() {
        if (!ClientPlayNetworking.canSend(SchematioPayload.TYPE)) return
        // Idempotent per connection: only the first of the join/reply paths actually sends.
        if (!ServerSession.markHelloSent()) return
        val version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
        // Advertise WANTS_COMMAND_OWNERSHIP only while the user allows servers to open
        // UI: an opted-out client never invites handoffs, so /schematio keeps printing
        // chat menus server-side (mid-session opt-out is enforced by the OpenUiGate drop).
        val allowOpenUi = SchematioClientMod.instance.services.authManager
            .getConfigFlag(OpenUiPrefs.KEY, OpenUiPrefs.DEFAULT)
        val clientFlags = if (allowOpenUi) Capabilities.WANTS_COMMAND_OWNERSHIP else 0
        val bytes = IpcCodec.encodeHelloClient(
            HelloClient(IpcProtocol.VERSION, version, clientFlags, ServerSession.nonce),
        )
        ClientPlayNetworking.send(SchematioPayload(bytes))
    }

    /**
     * Reference-pull loads are offered ONLY against a VERIFIED (attested) session
     * that advertised the LOAD_CLIPBOARD capability (spec: UI gating).
     */
    fun canLoadOnServer(): Boolean =
        ServerSession.trust == TrustState.VERIFIED &&
            Capabilities.has(ServerSession.capabilities, Capabilities.LOAD_CLIPBOARD)

    /**
     * Sends a LOAD_REQUEST carrying a schematic REFERENCE (never bytes). [onStatus]
     * receives every STATUS for this request (detail already §-sanitized) plus a
     * synthetic ERROR on the 30 s timeout. Returns the requestId, or null without
     * sending when the session is not VERIFIED+capable or the channel is not sendable.
     */
    fun sendLoadRequest(
        refType: LoadRefType,
        refId: String,
        versionId: String = "",
        onStatus: (StatusState, String) -> Unit,
    ): Int? {
        if (!canLoadOnServer()) return null
        if (!ClientPlayNetworking.canSend(SchematioPayload.TYPE)) return null
        val requestId = ClipboardLoadTracker.register(onStatus)
        val bytes = IpcCodec.encodeLoadRequest(
            LoadRequest(IpcProtocol.VERSION, requestId, refType.wire, refId, versionId),
        )
        ClientPlayNetworking.send(SchematioPayload(bytes))
        return requestId
    }

    /** "Upload clipboard" is offered ONLY on VERIFIED sessions advertising UPLOAD (spec). */
    fun canUploadClipboard(): Boolean =
        ServerSession.trust == TrustState.VERIFIED &&
            Capabilities.has(ServerSession.capabilities, Capabilities.UPLOAD)

    /**
     * Asks the server to push ITS copy of the player's WE clipboard to the backend as
     * a draft (opcode 7 — carries only a requestId; no bytes ever travel on the MC
     * channel). [onDraft] receives the draftId; [onStatus] receives failures plus the
     * tracker's 30 s synthetic ERROR. Returns the requestId, or null without sending
     * when the session is not VERIFIED+capable or the channel is not sendable.
     */
    fun sendUploadClipboard(
        onStatus: (StatusState, String) -> Unit,
        onDraft: (String) -> Unit,
    ): Int? {
        if (!canUploadClipboard()) return null
        if (!ClientPlayNetworking.canSend(SchematioPayload.TYPE)) return null
        val requestId = ClipboardUploadTracker.register(onStatus, onDraft)
        val bytes = IpcCodec.encodeUploadClipboard(UploadClipboard(IpcProtocol.VERSION, requestId))
        ClientPlayNetworking.send(SchematioPayload(bytes))
        return requestId
    }

    // ---- OPEN_UI (command ownership handoff, sub-project D) ----

    /**
     * Server-requested UI open. Honored ONLY when [OpenUiGate] accepts: VERIFIED
     * (attested) session, user toggle on, and at most one per 2 s — everything else
     * is dropped silently (spec: a hostile-but-attested server may not pop UI
     * against the user's will).
     */
    private fun handleOpenUi(msg: OpenUi) {
        val services = SchematioClientMod.instance.services
        val allow = services.authManager.getConfigFlag(OpenUiPrefs.KEY, OpenUiPrefs.DEFAULT)
        if (!openUiGate.tryAccept(ServerSession.trust, allow, System.currentTimeMillis())) {
            LOGGER.debug("Dropped OPEN_UI (trust={}, allow={})", ServerSession.trust, allow)
            return
        }
        when (OpenUiSurface.fromWire(msg.surface)) {
            OpenUiSurface.BROWSE -> {
                ImGuiOverlay.ensureOpen()
                PanelManager.open(BrowsePanel)
            }
            OpenUiSurface.UPLOAD -> {
                ImGuiOverlay.ensureOpen()
                UploadWizardPanel.open()
            }
            OpenUiSurface.SHARES -> {
                ImGuiOverlay.ensureOpen()
                PanelManager.open(SharesPanel)
            }
            OpenUiSurface.SETTINGS -> {
                ImGuiOverlay.ensureOpen()
                PanelManager.open(SettingsPanel)
            }
            OpenUiSurface.SCHEMATIC_DETAIL -> openSchematicDetail(msg.contextId)
            null -> Unit // unreachable: decodeOpenUi validates the surface
        }
    }

    /**
     * contextId is OPAQUE (spec invariant 4): it is used for exactly one thing — a
     * lookup against the CLIENT'S OWN authenticated backend API. Unknown/inaccessible
     * ids surface a local error line and open nothing.
     */
    private fun openSchematicDetail(contextId: String) {
        if (contextId.isEmpty()) return
        val services = SchematioClientMod.instance.services
        services.call(
            block = { services.cached.schematic(contextId) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    ImGuiOverlay.ensureOpen()
                    SchematicDetailPanel.show(detailAsSummary(result.value))
                }
                is ApiResult.Failure -> clientChat(
                    "§cSchematio: couldn't open that schematic — ${result.error.toUserMessage()}",
                )
            }
        }
    }

    /** SchematicDetailPanel.show takes a summary; a fetched detail is a superset of one. */
    private fun detailAsSummary(d: SchematicDetail): SchematicSummary = SchematicSummary(
        id = d.id,
        shortId = d.shortId,
        slug = d.slug,
        name = d.name,
        description = d.description,
        format = d.format,
        isPublic = d.isPublic,
        createdAt = d.createdAt,
        updatedAt = d.updatedAt,
        authors = d.authors,
        tags = d.tags,
        previewImageUrl = d.previewImageUrl,
        previewVideoUrl = d.previewVideoUrl,
        downloadLink = d.downloadLink,
        webUrl = d.webUrl,
    )

    /** Client-local chat line (this repo has no toast framework; matches the HELLO pattern). */
    private fun clientChat(text: String) {
        //? if >=26.1 {
        /*Minecraft.getInstance().player?.sendSystemMessage(Component.literal(text))
        *///?} else {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal(text), false)
        //?}
    }
}
