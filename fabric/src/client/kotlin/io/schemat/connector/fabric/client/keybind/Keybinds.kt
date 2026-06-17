package io.schemat.connector.fabric.client.keybind

import com.mojang.blaze3d.platform.InputConstants
import io.schemat.connector.fabric.client.ui.HomeScreen
import io.schemat.connector.fabric.client.ui.QuickShareCreateScreen
import io.schemat.connector.fabric.client.ui.UploadWizardScreen
// 26.x fabric-api replaced fabric-key-binding-api-v1 (KeyBindingHelper) with
// fabric-key-mapping-api-v1 (KeyMappingHelper.registerKeyMapping).
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
*///?} else {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
//?}
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
// 1.21.9+ keys keybinding categories by a registered location id; 1.21.8 uses a
// plain translation-key string. The id type name differs across versions and is
// normalized by the Stonecutter replacement in stonecutter.gradle.kts (sources
// are written in the 1.21.11+ form).
//? if >=1.21.9 {
import net.minecraft.resources.Identifier
//?}
import org.lwjgl.glfw.GLFW

/**
 * All client keybindings for SchematioConnector, grouped under a dedicated
 * "Schematio Connector" controls category. Only [browser] is bound by default
 * (K); the rest ship unbound and are user-assignable in Options > Controls.
 *
 * Category handling is version-split:
 *  - 1.21.9+ registers a [KeyMapping.Category] keyed by a location; its label
 *    resolves to `key.category.schematioconnector.main`.
 *  - 1.21.8 passes the translation key `category.schematioconnector` directly.
 */
object Keybinds {

    //? if >=1.21.9 {
    private val CATEGORY: KeyMapping.Category = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("schematioconnector", "main")
    )
    //?}

    lateinit var browser: KeyMapping
        private set
    lateinit var upload: KeyMapping
        private set
    lateinit var quickShare: KeyMapping
        private set
    lateinit var settings: KeyMapping
        private set

    /** Registers every binding. Call once from onInitializeClient. */
    fun register() {
        browser = bind("key.schematioconnector.browser", GLFW.GLFW_KEY_K)
        upload = bind("key.schematioconnector.upload", GLFW.GLFW_KEY_UNKNOWN)
        quickShare = bind("key.schematioconnector.quickshare", GLFW.GLFW_KEY_UNKNOWN)
        settings = bind("key.schematioconnector.settings", GLFW.GLFW_KEY_UNKNOWN)
    }

    /** Drains pending presses and opens the matching screen. Call each client tick. */
    fun handleInput(client: Minecraft) {
        while (browser.consumeClick()) client.setScreen(HomeScreen())
        while (upload.consumeClick()) client.setScreen(UploadWizardScreen(HomeScreen()))
        while (quickShare.consumeClick()) client.setScreen(QuickShareCreateScreen(HomeScreen()))
        while (settings.consumeClick()) client.setScreen(HomeScreen(HomeScreen.Tab.SETTINGS))
    }

    private fun bind(translationKey: String, defaultKey: Int): KeyMapping {
        val mapping = KeyMapping(
            translationKey,
            InputConstants.Type.KEYSYM,
            defaultKey,
            //? if >=1.21.9 {
            CATEGORY
            //?} else {
            /*"category.schematioconnector"
            *///?}
        )
        //? if >=26.1 {
        /*return KeyMappingHelper.registerKeyMapping(mapping)
        *///?} else {
        return KeyBindingHelper.registerKeyBinding(mapping)
        //?}
    }
}
