package io.schemat.connector.fabric.client.integration.litematica

import io.schemat.connector.fabric.client.integration.LitematicaBridge
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/**
 * Classloading firewall around [LitematicaBridgeImpl].
 *
 * [LitematicaBridgeImpl] is compiled directly against Litematica classes, so merely
 * classloading it on a client without Litematica throws [NoClassDefFoundError]. This
 * loader is the ONLY place allowed to reference the impl class: it checks the mod is
 * present first and catches every [Throwable] (including linkage errors and signature
 * drift in future Litematica versions), falling back to `null` so the caller can keep
 * the Noop bridge installed.
 */
object LitematicaBridgeLoader {

    private val LOGGER = LoggerFactory.getLogger("schematioconnector-litematica-bridge")

    /** @return a working bridge, or null when Litematica is absent or its API is incompatible. */
    fun tryCreate(): LitematicaBridge? {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return null
        }
        return try {
            // The impl's init block smoke-tests DataManager so incompatibilities fail here.
            LitematicaBridgeImpl()
        } catch (t: Throwable) {
            LOGGER.warn(
                "Litematica is installed but the Schematio bridge could not initialize " +
                    "(API mismatch?). Litematica integration is disabled. Cause: ${t.message}",
                t
            )
            null
        }
    }
}
