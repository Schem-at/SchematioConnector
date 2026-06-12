package io.schemat.connector.fabric.client.integration.worldedit

import io.schemat.connector.fabric.client.integration.WorldEditBridge
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/**
 * Classloading firewall around [WorldEditBridgeImpl] (mirrors `LitematicaBridgeLoader`).
 *
 * [WorldEditBridgeImpl] is compiled directly against worldedit-core classes, so merely
 * classloading it on a client without WorldEdit throws [NoClassDefFoundError]. This
 * loader is the ONLY place allowed to reference the impl class: it checks the mod is
 * present first and catches every [Throwable] (including linkage errors and signature
 * drift in future WorldEdit versions), falling back to `null` so the caller can keep
 * the Noop bridge installed.
 */
object WorldEditBridgeLoader {

    private val LOGGER = LoggerFactory.getLogger("schematioconnector-worldedit-bridge")

    /** @return a working bridge, or null when WorldEdit is absent or its API is incompatible. */
    fun tryCreate(): WorldEditBridge? {
        if (!FabricLoader.getInstance().isModLoaded("worldedit")) {
            return null
        }
        return try {
            // The impl's init block smoke-tests WorldEdit.getInstance() so
            // incompatibilities fail here.
            WorldEditBridgeImpl()
        } catch (t: Throwable) {
            LOGGER.warn(
                "WorldEdit is installed but the Schematio bridge could not initialize " +
                    "(API mismatch?). WorldEdit integration is disabled. Cause: ${t.message}",
                t
            )
            null
        }
    }
}
