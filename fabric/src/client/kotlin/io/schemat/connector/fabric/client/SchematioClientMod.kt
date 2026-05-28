package io.schemat.connector.fabric.client

import io.schemat.connector.fabric.SchematioConnectorMod
import io.schemat.connector.fabric.client.auth.ClientAuthManager
import io.schemat.connector.fabric.client.litematica.LitematicaIntegration
import io.schemat.connector.fabric.client.ui.SchematicBrowserScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

class SchematioClientMod : ClientModInitializer {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-client")
        lateinit var instance: SchematioClientMod
            private set
    }

    lateinit var authManager: ClientAuthManager
        private set
    var litematicaIntegration: LitematicaIntegration? = null
        private set

    private lateinit var browserKeybinding: KeyBinding

    override fun onInitializeClient() {
        instance = this
        LOGGER.info("Initializing Schematio Connector client...")

        val configDir = FabricLoader.getInstance().configDir.resolve(SchematioConnectorMod.MOD_ID)
        configDir.toFile().mkdirs()
        authManager = ClientAuthManager(configDir)

        if (FabricLoader.getInstance().isModLoaded("litematica")) {
            LOGGER.info("Litematica detected, initializing integration...")
            litematicaIntegration = LitematicaIntegration(this)
            litematicaIntegration!!.initialize()

            // Register keybinding for browser
            browserKeybinding = KeyBindingHelper.registerKeyBinding(
                KeyBinding(
                    "key.schematioconnector.browser",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_O,
                    KeyBinding.Category.MISC
                )
            )

            // Tick handler to detect key press
            ClientTickEvents.END_CLIENT_TICK.register { client ->
                while (browserKeybinding.wasPressed()) {
                    client.setScreen(SchematicBrowserScreen())
                }
            }
        } else {
            LOGGER.warn("Litematica not found. Client-side schematic features are disabled.")
            LOGGER.warn("Install Litematica to enable schematic browsing and downloading.")
        }

        LOGGER.info("Schematio Connector client initialized!")
    }
}
