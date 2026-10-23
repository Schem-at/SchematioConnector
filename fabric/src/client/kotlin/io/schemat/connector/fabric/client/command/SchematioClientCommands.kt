package io.schemat.connector.fabric.client.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.schemat.connector.core.ipc.ClientAction
import io.schemat.connector.core.ipc.DispatchMode
import io.schemat.connector.core.ipc.DispatchTable
import io.schemat.connector.core.routing.DownloadArgs
import io.schemat.connector.core.routing.DownloadKind
import io.schemat.connector.core.routing.ParsedDownload
import io.schemat.connector.fabric.client.routing.DownloadRouter
import io.schemat.connector.fabric.client.ipc.ServerSession
import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.services.ChatNotice
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.QuickShareCreatePanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
// Fabric API for 26.x (command-api-v2 3.x) renamed ClientCommandManager to
// ClientCommands (same statics); the alias keeps call sites identical.
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommands as ClientCommandManager
*///?} else {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
//?}
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import org.slf4j.LoggerFactory

/**
 * `/schematio` client command tree (Fabric Client Command API v2 / Brigadier).
 *
 * - `/schematio` - open the Home screen (browser)
 * - `/schematio open` | `browse` - same as bare command
 * - `/schematio upload` - open the upload wizard (uses the current Litematica
 *   selection as the source when one exists, like the Home screen button)
 * - `/schematio download <id|link> [--we|--lm|--file] [--server|--local]` - download a
 *   schematic (short id / uuid / slug / schemat.io link) to your preferred destination
 *   (see [DownloadRouter] and the "Download destination" settings)
 * - `/schematio quickshareget <code> [password] [flags]` - download a quick share
 *   by its access code (e.g. `qs_re3LyVO5Dn`) to your preferred destination. The backend
 *   resolves access codes through the same `POST /schematics/{id}/download` route,
 *   consulting `password` for protected shares.
 * - `/schematio quickshare` - open the quick-share creation screen
 * - `/schematio help` - print this list to chat
 *
 * Screens are opened via `client.schedule { ... }` so the closing chat screen does not
 * immediately replace them. Downloads are handed to [DownloadRouter], which picks the
 * destination, runs the transfer off-thread and replies on the client thread.
 */
object SchematioClientCommands {

    private val LOGGER = LoggerFactory.getLogger("SchematioClientCommands")

    /** True when the connected server's plugin should own this action (forward instead of handling locally). */
    private fun forwards(action: ClientAction): Boolean =
        DispatchTable.resolve(action, ServerSession.pluginPresent, ServerSession.platform) == DispatchMode.SERVER_COMMAND

    /**
     * Forwards [command] to the server plugin via [DownloadRouter.forwardToServer]; a failed
     * forward is logged and reported to the player.
     */
    private fun forward(command: String): Int {
        if (DownloadRouter.forwardToServer(command)) return 1
        LOGGER.warn("Could not forward '{}' to the server: no server connection", command)
        ChatNotice.error("Not connected to a server")
        return 0
    }

    private fun download(args: String, quickShare: Boolean): Int {
        val kind = if (quickShare) DownloadKind.QUICK_SHARE else DownloadKind.SCHEMATIC
        when (val parsed = DownloadArgs.parse(args, quickShare)) {
            is ParsedDownload.Error -> ChatNotice.error(parsed.message)
            is ParsedDownload.Ok -> DownloadRouter.route(kind, parsed) { text, isError ->
                if (isError) ChatNotice.error(text) else ChatNotice.info(text)
            }
        }
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
                            if (forwards(ClientAction.UPLOAD)) forward("schematio upload")
                            else openPanel(ctx) { UploadWizardPanel.open() }
                        }
                )
                .then(
                    ClientCommandManager.literal("quickshare")
                        .executes { ctx ->
                            if (forwards(ClientAction.QUICKSHARE)) forward("schematio quickshare")
                            else openPanel(ctx) { QuickShareCreatePanel.show(null) }
                        }
                )
                .then(
                    ClientCommandManager.literal("download")
                        .then(
                            ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                .executes { ctx -> download(StringArgumentType.getString(ctx, "args"), quickShare = false) }
                        )
                )
                .then(
                    ClientCommandManager.literal("quickshareget")
                        .then(
                            ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                .executes { ctx -> download(StringArgumentType.getString(ctx, "args"), quickShare = true) }
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
            // 26.2 moved screen management from Minecraft onto Gui.
            //? if >=26.2 {
            /*client.gui.setScreen(null)
            *///?} else {
            client.setScreen(null)
            //?}
            ImGuiOverlay.ensureOpen() // commands open into the dockable workspace too
            open()
        }
        return 1
    }

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
            "/schematio download <id|link> [--we|--lm|--file] [--server|--local]" to "download to your preferred destination",
            "/schematio quickshareget <code> [password] [flags]" to "load a quick share by access code",
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
