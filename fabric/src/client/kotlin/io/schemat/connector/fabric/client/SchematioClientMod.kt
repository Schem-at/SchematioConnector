package io.schemat.connector.fabric.client

import io.schemat.connector.fabric.SchematioConnectorMod
import io.schemat.connector.fabric.client.auth.ClientAuthManager
import io.schemat.connector.fabric.client.command.SchematioClientCommands
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.NoopLitematicaBridge
import io.schemat.connector.fabric.client.integration.NoopWorldEditBridge
import io.schemat.connector.fabric.client.integration.litematica.LitematicaBridgeLoader
import io.schemat.connector.fabric.client.integration.worldedit.WorldEditBridgeLoader
import io.schemat.connector.fabric.client.keybind.Keybinds
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.HomeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import org.slf4j.LoggerFactory

class SchematioClientMod : ClientModInitializer {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-client")
        private const val LIMITED_MODE_NOTICE_FLAG = "limited_mode_notice_shown"
        lateinit var instance: SchematioClientMod
            private set
    }

    lateinit var authManager: ClientAuthManager
        private set
    lateinit var services: ClientServices
        private set

    override fun onInitializeClient() {
        instance = this
        LOGGER.info("Initializing Schematio Connector client...")

        val configDir = FabricLoader.getInstance().configDir.resolve(SchematioConnectorMod.MOD_ID)
        configDir.toFile().mkdirs()
        authManager = ClientAuthManager(configDir)
        services = ClientServices(authManager)

        // Silent auth on startup (regardless of Litematica), then warm the /mod/me snapshot.
        ClientLifecycleEvents.CLIENT_STARTED.register { _ ->
            services.scope.launch {
                delay(2000) // give the Mojang session a moment to be fully ready
                if (authManager.ensureAuthenticated()) {
                    services.refreshMe()
                }
            }
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register { _ ->
            services.shutdown()
            authManager.shutdown()
        }

        // One-time "limited mode" notice on world join when neither Litematica nor
        // WorldEdit is present: browse/upload still work, load/export do not.
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            if (Bridges.litematica.isAvailable || Bridges.worldEdit.isAvailable) {
                return@register
            }
            LOGGER.warn(
                "Running in limited mode: neither Litematica nor WorldEdit detected. " +
                    "Browsing and uploading work; loading/exporting schematics in-world is unavailable."
            )
            if (!authManager.getConfigFlag(LIMITED_MODE_NOTICE_FLAG)) {
                authManager.setConfigFlag(LIMITED_MODE_NOTICE_FLAG, true)
                services.scope.launch {
                    delay(1500) // let the join chat settle / player become available
                    services.onMainThread {
                        val notice = Component.literal("[Schematio] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                            .append(
                                Component.literal(
                                    "Neither Litematica nor WorldEdit is installed. You can still browse, " +
                                        "manage and upload schematics (press O), but loading them into the " +
                                        "world or exporting builds requires Litematica or WorldEdit."
                                ).withStyle(ChatFormatting.GRAY)
                            )
                        // 26.x split Player.displayClientMessage(text, actionBar) into
                        // sendSystemMessage(text) / sendOverlayMessage(text).
                        //? if >=26.1 {
                        /*client.player?.sendSystemMessage(notice)
                        *///?} else {
                        client.player?.displayClientMessage(notice, false)
                        //?}
                    }
                }
            }
        }

        // Register all client keybindings under the dedicated "Schematio
        // Connector" category, then dispatch presses each client tick.
        Keybinds.register()
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            Keybinds.handleInput(client)
        }

        // /schematio command tree: bare command opens the Home screen; subcommands
        // cover open/browse/upload/download/quickshareget/quickshare/help.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            SchematioClientCommands.register(dispatcher)
        }

        // Install the real Litematica bridge when the mod is present; the loader
        // classloads the Litematica-linked impl only after isModLoaded passes and
        // falls back to null (-> Noop) on any incompatibility.
        Bridges.litematica = LitematicaBridgeLoader.tryCreate() ?: NoopLitematicaBridge
        if (Bridges.litematica.isAvailable) {
            LOGGER.info("Litematica detected - load/export bridge installed.")
        } else {
            LOGGER.warn("Litematica not available. Schematic load/placement/export features are disabled.")
            LOGGER.warn("Install Litematica to enable loading schematics into the world.")
        }

        // Same pattern for WorldEdit. Note: the bridge only activates alongside an
        // integrated server (singleplayer / LAN host) - isAvailable stays false on
        // multiplayer servers, where the server-side plugin covers clipboard flows.
        Bridges.worldEdit = WorldEditBridgeLoader.tryCreate() ?: NoopWorldEditBridge
        if (Bridges.worldEdit !== NoopWorldEditBridge) {
            LOGGER.info("WorldEdit detected - clipboard bridge installed (active in singleplayer / LAN host).")
        } else {
            LOGGER.warn("WorldEdit not available. Client-side clipboard features are disabled.")
        }

        LOGGER.info("Schematio Connector client initialized!")
    }
}
