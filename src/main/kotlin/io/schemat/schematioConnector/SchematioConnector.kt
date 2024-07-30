package io.schemat.schematioConnector

import com.sk89q.worldedit.WorldEdit
import io.schemat.schematioConnector.commands.SchematioCommand
import io.schemat.schematioConnector.utils.HttpUtil
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin

class SchematioConnector : JavaPlugin() {

    lateinit var httpUtil: HttpUtil
    lateinit var worldEditInstance: WorldEdit
        private set

    companion object {
        lateinit var instance: SchematioConnector
            private set
    }


    override fun onEnable() {
        instance = this
        logger.info("SchematioConnector enabled, loading configuration")
        saveDefaultConfig()

        val config = config
        val apiKey = config.getString("api-key")
        if (apiKey.isNullOrEmpty()) {
            logger.warning("API key not set, plugin will not work")
            return
        }
        val apiEndpoint = config.getString("api-endpoint")
        if (apiEndpoint.isNullOrEmpty()) {
            logger.warning("API endpoint not set, plugin will not work")
            return
        }

        httpUtil = HttpUtil(apiKey, apiEndpoint, logger)

        // Initialize WorldEdit instance
        worldEditInstance = WorldEdit.getInstance()

        // Check connection on plugin enable
        runBlocking {
            val connectionSuccessful = httpUtil.checkConnection()
            if (connectionSuccessful) {
                logger.info("Successfully connected to the API")
            } else {
                logger.warning("Failed to connect to the API")
                return@runBlocking
            }
        }

        val schematioCommand = SchematioCommand(this)
        getCommand("schematio")?.setExecutor(schematioCommand)
        getCommand("schematio")?.tabCompleter = schematioCommand
    }

    override fun onDisable() {
        logger.info("SchematioConnector disabled")
    }
}