package io.schemat.schematioConnector

import com.sk89q.worldedit.WorldEdit
import io.schemat.schematioConnector.commands.*
import io.schemat.schematioConnector.utils.HttpUtil
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin

class SchematioConnector : JavaPlugin() {

    lateinit var httpUtil: HttpUtil
        private set
    var worldEditInstance: WorldEdit? = null // Make nullable
        private set

    // A public property to easily check if WorldEdit is available
    val hasWorldEdit: Boolean
        get() = worldEditInstance != null

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
            logger.warning("API key not set, plugin will be disabled.")
            server.pluginManager.disablePlugin(this) // Disable plugin if config is bad
            return
        }
        val apiEndpoint = config.getString("api-endpoint")
        if (apiEndpoint.isNullOrEmpty()) {
            logger.warning("API endpoint not set, plugin will be disabled.")
            server.pluginManager.disablePlugin(this)
            return
        }

        httpUtil = HttpUtil(apiKey, apiEndpoint, logger)

        // Check for WorldEdit
        if (server.pluginManager.isPluginEnabled("WorldEdit")) {
            worldEditInstance = WorldEdit.getInstance()
            logger.info("WorldEdit found, full functionality enabled.")
        } else {
            logger.warning("WorldEdit not found! Schematics functionality (upload, download, list) will be disabled.")
        }

        // Check API connection
        val connectionSuccessful = runBlocking { httpUtil.checkConnection() }
        if (!connectionSuccessful) {
            logger.severe("Failed to connect to the API. Please check your API key and endpoint. Disabling plugin.")
            server.pluginManager.disablePlugin(this)
            return
        }
        logger.info("Successfully connected to the API.")

        // Setup commands based on dependencies
        setupCommands()
    }

    private fun setupCommands() {
        val subcommands = mutableListOf<Subcommand>()

        // These commands are always available
        subcommands.add(SetPasswordSubcommand(this))

        // These commands require WorldEdit
        if (hasWorldEdit) {
            subcommands.add(UploadSubcommand(this))
            subcommands.add(DownloadSubcommand(this))
            subcommands.add(ListSubcommand(this))
        }

        // The command handler now receives the list of available commands
        val schematioCommand = SchematioCommand(this, subcommands.associateBy { it.name })
        getCommand("schematio")?.let {
            it.setExecutor(schematioCommand)
            it.tabCompleter = schematioCommand
        }
    }

    override fun onDisable() {
        logger.info("SchematioConnector disabled")
    }
}