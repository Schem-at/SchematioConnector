package io.schemat.connector.fabric.client.litematica

import com.google.gson.JsonObject
import io.schemat.connector.fabric.client.SchematioClientMod
import kotlinx.coroutines.*
import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory
import java.io.File

class SchematicDownloader(private val mod: SchematioClientMod) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-downloader")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Download a schematic from the API and load it into Litematica.
     *
     * @param schematicId The schematic ID or short_id to download
     * @param name The schematic name (used for the filename)
     * @param password Optional password for protected schematics
     * @param onSuccess Called on the render thread when download + load succeeds
     * @param onError Called on the render thread when download fails
     */
    fun download(
        schematicId: String,
        name: String,
        password: String? = null,
        onSuccess: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val httpUtil = mod.authManager.httpUtil
        if (httpUtil == null) {
            MinecraftClient.getInstance().execute { onError("Not authenticated yet. Please wait for login to complete.") }
            return
        }

        scope.launch {
            try {
                LOGGER.info("Downloading schematic: $schematicId")

                val requestBody = JsonObject().apply {
                    addProperty("format", "litematic")
                    MinecraftClient.getInstance().session.uuidOrNull?.let { uuid ->
                        addProperty("player_uuid", uuid.toString())
                    }
                    if (!password.isNullOrBlank()) {
                        addProperty("password", password)
                    }
                }

                val (statusCode, bytes, errorBody) = httpUtil.sendPostRequestForBinary(
                    "/schematics/$schematicId/download",
                    requestBody.toString()
                )

                if (statusCode == 200 && bytes != null) {
                    val schematicsDir = getSchematicsDirectory()
                    schematicsDir.mkdirs()

                    val sanitizedName = name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
                    val destFile = File(schematicsDir, "${sanitizedName}.litematic")
                    destFile.writeBytes(bytes)

                    LOGGER.info("Saved schematic to: ${destFile.absolutePath} (${bytes.size} bytes)")

                    MinecraftClient.getInstance().execute {
                        loadIntoLitematica(destFile)
                        onSuccess(destFile)
                    }
                } else {
                    val errorMsg = parseDownloadError(statusCode, password, errorBody)
                    LOGGER.warn("Download failed: $errorMsg")
                    MinecraftClient.getInstance().execute { onError(errorMsg) }
                }
            } catch (e: Exception) {
                LOGGER.error("Download failed: ${e.message}", e)
                MinecraftClient.getInstance().execute { onError(e.message ?: "Unknown error") }
            }
        }
    }

    private fun parseDownloadError(statusCode: Int, password: String?, errorBody: String?): String {
        return when (statusCode) {
            401 -> if (password.isNullOrBlank()) "This schematic requires a password" else "Incorrect password"
            403 -> "Access denied"
            404 -> "Schematic not found"
            410 -> "This download has expired"
            429 -> "Rate limited. Please try again later."
            -1 -> "Connection failed"
            else -> "Download failed (code: $statusCode)"
        }
    }

    private fun getSchematicsDirectory(): File {
        return File(MinecraftClient.getInstance().runDirectory, "schematics")
    }

    /**
     * Load a schematic file into Litematica and create a placement at the player's position.
     * Uses reflection for compatibility across Litematica versions.
     */
    private fun loadIntoLitematica(file: File) {
        try {
            // Load via SchematicHolder
            val holderClass = Class.forName("fi.dy.masa.litematica.data.SchematicHolder")
            val getInstanceMethod = holderClass.getMethod("getInstance")
            val holder = getInstanceMethod.invoke(null)

            val getOrLoadMethod = holderClass.methods.firstOrNull { m ->
                m.name == "getOrLoad" && m.parameterCount == 1 && m.parameterTypes[0] == File::class.java
            }

            val schematic = if (getOrLoadMethod != null) {
                getOrLoadMethod.invoke(holder, file)
            } else {
                LOGGER.warn("Could not find SchematicHolder.getOrLoad(File) — Litematica API may have changed")
                return
            }

            if (schematic == null) {
                LOGGER.warn("Failed to load schematic from: ${file.name}")
                return
            }

            // Create a placement at the player's position
            val player = MinecraftClient.getInstance().player ?: return
            val pos = player.blockPos

            val placementClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement")
            val createForMethod = placementClass.methods.firstOrNull { m ->
                m.name == "createFor" && m.parameterCount >= 3
            }

            if (createForMethod != null) {
                val placement = createForMethod.invoke(null, schematic, pos, file.nameWithoutExtension, true, true)

                if (placement != null) {
                    val dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager")
                    val getPlacementManagerMethod = dataManagerClass.getMethod("getSchematicPlacementManager")
                    val placementManager = getPlacementManagerMethod.invoke(null)

                    val addMethod = placementManager.javaClass.methods.firstOrNull { m ->
                        m.name == "addSchematicPlacement" && m.parameterCount >= 1
                    }
                    addMethod?.invoke(placementManager, placement, true)

                    LOGGER.info("Created Litematica placement for: ${file.name}")
                }
            } else {
                LOGGER.warn("Could not find SchematicPlacement.createFor — Litematica API may have changed")
                LOGGER.info("Schematic saved to schematics folder. Load it manually from Litematica.")
            }
        } catch (e: ClassNotFoundException) {
            LOGGER.error("Litematica classes not found — is Litematica installed?", e)
        } catch (e: Exception) {
            LOGGER.error("Failed to load schematic into Litematica: ${e.message}", e)
            LOGGER.info("Schematic was saved to schematics folder. You can load it manually.")
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}
