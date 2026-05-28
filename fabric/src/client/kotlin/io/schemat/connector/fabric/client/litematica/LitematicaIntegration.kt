package io.schemat.connector.fabric.client.litematica

import io.schemat.connector.fabric.client.SchematioClientMod
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import org.slf4j.LoggerFactory

class LitematicaIntegration(private val mod: SchematioClientMod) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-litematica")
    }

    lateinit var downloader: SchematicDownloader
        private set

    fun initialize() {
        downloader = SchematicDownloader(mod)

        ClientLifecycleEvents.CLIENT_STARTED.register { _ ->
            LOGGER.info("Litematica integration ready")
            mod.authManager.authenticateAsync()
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register { _ ->
            downloader.shutdown()
            mod.authManager.shutdown()
        }

        LOGGER.info("Litematica integration initialized")
    }
}
