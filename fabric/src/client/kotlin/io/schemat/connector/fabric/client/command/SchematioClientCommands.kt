package io.schemat.connector.fabric.client.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.schemat.connector.core.ipc.ClientAction
import io.schemat.connector.core.ipc.DispatchMode
import io.schemat.connector.core.ipc.DispatchTable
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ipc.ServerSession
import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.services.ChatNotice
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.QuickShareCreatePanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import kotlinx.coroutines.launch
// Fabric API for 26.x (command-api-v2 3.x) renamed ClientCommandManager to
// ClientCommands (same statics); the alias keeps call sites identical.
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommands as ClientCommandManager
*///?} else {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
//?}
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/schematio` client command tree (Fabric Client Command API v2 / Brigadier).
 *
 * - `/schematio` - open the Home screen (browser)
 * - `/schematio open` | `browse` - same as bare command
 * - `/schematio upload` - open the upload wizard (uses the current Litematica
 *   selection as the source when one exists, like the Home screen button)
 * - `/schematio download <id>` - download a schematic (short id / uuid / slug)
 *   as `.litematic` and load it into Litematica
 * - `/schematio quickshareget <accessCode> [password]` - download a quick share
 *   by its access code (e.g. `qs_re3LyVO5Dn`) and load it into Litematica.
 *   The backend resolves access codes through the same
 *   `POST /schematics/{id}/download` route, consulting `password` for protected shares.
 * - `/schematio quickshare` - open the quick-share creation screen
 * - `/schematio help` - print this list to chat
 *
 * Screens are opened via `client.schedule { ... }` so the closing chat screen does not
 * immediately replace them. Downloads run on [io.schemat.connector.fabric.client.services.ClientServices.scope]
 * (IO dispatcher); file writes happen there too, then the Litematica load and all chat
 * feedback are marshalled to the render thread ([ChatNotice] hops internally).
 */
object SchematioClientCommands {

    private const val LITEMATIC_EXTENSION = ".litematic"

    private val services get() = SchematioClientMod.instance.services

    /** True when the connected server's plugin should own this action (forward instead of handling locally). */
    private fun forwards(action: ClientAction): Boolean =
        DispatchTable.resolve(action, ServerSession.pluginPresent) == DispatchMode.SERVER_COMMAND

    /** Sends a `/`-less command line to the server as a vanilla command packet. */
    private fun forwardToServer(command: String): Int {
        net.minecraft.client.Minecraft.getInstance().player?.connection?.sendCommand(command)
        return 1
    }

    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(
            ClientCommandManager.literal("schematio")
                .executes { ctx -> openPanel(ctx) { PanelManager.open(BrowsePanel) } }
                .then(
                    ClientCommandManager.literal("open")
                        .executes { ctx -> openPanel(ctx) { PanelManager.open(BrowsePanel) } }
                )
                .then(
                    ClientCommandManager.literal("browse")
                        .executes { ctx -> openPanel(ctx) { PanelManager.open(BrowsePanel) } }
                )
                .then(
                    ClientCommandManager.literal("upload")
                        .executes { ctx ->
                            if (forwards(ClientAction.UPLOAD)) forwardToServer("schematio upload")
                            else openPanel(ctx) { UploadWizardPanel.open() }
                        }
                )
                .then(
                    ClientCommandManager.literal("quickshare")
                        .executes { ctx ->
                            if (forwards(ClientAction.QUICKSHARE)) forwardToServer("schematio quickshare")
                            else openPanel(ctx) { QuickShareCreatePanel.show(null) }
                        }
                )
                .then(
                    ClientCommandManager.literal("download")
                        .then(
                            ClientCommandManager.argument("id", StringArgumentType.word())
                                .executes { ctx ->
                                    val id = StringArgumentType.getString(ctx, "id")
                                    if (forwards(ClientAction.DOWNLOAD)) forwardToServer("schematio download $id")
                                    else downloadAndLoad(id, password = null, label = "schematic")
                                }
                        )
                )
                .then(
                    ClientCommandManager.literal("quickshareget")
                        .then(
                            ClientCommandManager.argument("accessCode", StringArgumentType.word())
                                .executes { ctx ->
                                    val code = StringArgumentType.getString(ctx, "accessCode")
                                    if (forwards(ClientAction.QUICKSHARE_GET)) forwardToServer("schematio quickshareget $code")
                                    else downloadAndLoad(code, password = null, label = "quick share")
                                }
                                .then(
                                    ClientCommandManager.argument("password", StringArgumentType.greedyString())
                                        .executes { ctx ->
                                            val code = StringArgumentType.getString(ctx, "accessCode")
                                            val password = StringArgumentType.getString(ctx, "password")
                                            if (forwards(ClientAction.QUICKSHARE_GET)) forwardToServer("schematio quickshareget $code $password")
                                            else downloadAndLoad(code, password = password, label = "quick share")
                                        }
                                )
                        )
                )
                .then(
                    ClientCommandManager.literal("help")
                        .executes { ctx -> sendHelp(ctx.source) }
                )
        )
    }

    // ------------------------------------------------------------------ panels

    /**
     * Defer opening to the next client-loop iteration: the chat screen is still
     * closing while the command executes, so we must wait before the panel is visible
     * (the HUD event fires only when no Screen is open).
     */
    private fun openPanel(ctx: CommandContext<FabricClientCommandSource>, open: () -> Unit): Int {
        val client = ctx.source.client
        client.schedule {
            client.setScreen(null)
            ImGuiOverlay.ensureOpen() // commands open into the dockable workspace too
            open()
        }
        return 1
    }

    // ------------------------------------------------------------------ download / quick share get

    /**
     * Download [id] (schematic id/short-id/slug, or a quick-share access code - the
     * backend's download route resolves both) as `.litematic`, write it into the
     * Litematica schematics directory (or `<game>/schematics/schemat.io` without
     * Litematica) and create a placement via [Bridges.litematica].
     */
    private fun downloadAndLoad(id: String, password: String?, label: String): Int {
        ChatNotice.info("Downloading $label \"$id\"…")
        services.scope.launch {
            val result = try {
                services.cached.download(id, "litematic", password)
            } catch (e: Exception) {
                ApiResult.Failure(ApiError.Unexpected(0, e.message ?: "Unexpected client error"))
            }
            when (result) {
                is ApiResult.Success -> saveAndLoad(id, result.value)
                is ApiResult.Failure -> ChatNotice.error(
                    "Could not download $label \"$id\": ${result.error.toUserMessage()}"
                )
            }
        }
        return 1
    }

    /** IO thread. Write [bytes] to disk, then load into Litematica on the render thread. */
    private fun saveAndLoad(name: String, bytes: ByteArray) {
        val file = try {
            val dir = downloadDirectory()
            Files.createDirectories(dir)
            dir.resolve(sanitizeFileName(name) + LITEMATIC_EXTENSION).also { Files.write(it, bytes) }
        } catch (e: Exception) {
            ChatNotice.error("Could not save \"$name\": ${e.message ?: "unexpected error"}")
            return
        }
        services.onMainThread {
            if (Bridges.litematica.isAvailable) {
                Bridges.litematica.loadSchematic(file.toFile(), name) { ok, error ->
                    if (ok) {
                        ChatNotice.info("Loaded \"$name\" into Litematica")
                    } else {
                        ChatNotice.error(error ?: "Failed to load \"$name\" into Litematica")
                    }
                }
            } else {
                ChatNotice.info("Litematica is not installed - saved to $file")
            }
        }
    }

    /** Downloads land in Litematica's schematics dir when known, else `<game>/schematics/schemat.io`. */
    private fun downloadDirectory(): Path =
        Bridges.litematica.schematicsDirectory()
            ?: FabricLoader.getInstance().gameDir.resolve("schematics").resolve("schemat.io")

    /** Defensive file-name sanitizer (ids/access codes are already safe). */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").ifBlank { "schematic" }

    // ------------------------------------------------------------------ help

    private fun sendHelp(source: FabricClientCommandSource): Int {
        source.sendFeedback(
            Component.literal("[Schemat.io] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("Commands:").withStyle(ChatFormatting.WHITE))
        )
        listOf(
            "/schematio" to "open the schematic browser",
            "/schematio open|browse" to "open the schematic browser",
            "/schematio upload" to "upload the current selection / a local file",
            "/schematio download <id>" to "download a schematic into Litematica",
            "/schematio quickshareget <code> [password]" to "load a quick share by access code",
            "/schematio quickshare" to "create a quick share",
            "/schematio help" to "show this list",
        ).forEach { (cmd, desc) ->
            source.sendFeedback(
                Component.literal("  $cmd").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(" - $desc").withStyle(ChatFormatting.GRAY))
            )
        }
        return 1
    }
}
