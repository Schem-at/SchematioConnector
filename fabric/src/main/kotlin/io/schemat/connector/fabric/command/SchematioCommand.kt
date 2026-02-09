package io.schemat.connector.fabric.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.schemat.connector.core.validation.InputValidator
import io.schemat.connector.core.validation.ValidationResult
import io.schemat.connector.fabric.SchematioConnectorMod
import io.schemat.connector.fabric.worldedit.FabricWorldEditUtil
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import org.apache.http.HttpResponse
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.util.EntityUtils
import java.util.Base64

/**
 * Main command handler for /schematio commands in Fabric.
 */
object SchematioCommand {

    /**
     * Cross-version permission check using reflection.
     * Handles API changes between MC versions where method names differ in mappings.
     */
    private fun hasOpPermission(source: ServerCommandSource): Boolean {
        // Try different method names that may exist across versions/mappings
        val methodNames = listOf("hasPermissionLevel", "hasPermission", "method_9259")

        for (methodName in methodNames) {
            try {
                val method = source.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
                return method.invoke(source, 4) as Boolean
            } catch (_: NoSuchMethodException) {
                continue
            } catch (_: Exception) {
                continue
            }
        }

        // If no permission method found, deny by default (safer)
        return false
    }

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>, mod: SchematioConnectorMod) {
        val command = literal("schematio")
            // Info subcommand
            .then(literal("info")
                .executes { ctx -> executeInfo(ctx, mod) })

            // List subcommand
            .then(literal("list")
                .executes { ctx -> executeList(ctx, mod, null) }
                .then(argument("search", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val search = StringArgumentType.getString(ctx, "search")
                        executeList(ctx, mod, search)
                    }))

            // Download subcommand
            .then(literal("download")
                .then(argument("id", StringArgumentType.word())
                    .executes { ctx ->
                        val id = StringArgumentType.getString(ctx, "id")
                        executeDownload(ctx, mod, id)
                    }))

            // Upload subcommand
            .then(literal("upload")
                .executes { ctx -> executeUpload(ctx, mod) })

            // Quick share subcommand
            .then(literal("quickshare")
                .executes { ctx -> executeQuickShare(ctx, mod) })

            // Quick share get subcommand (greedyString to support full URLs)
            .then(literal("quickshareget")
                .then(argument("args", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val args = StringArgumentType.getString(ctx, "args").split(" ", limit = 2)
                        val code = args[0]
                        val password = args.getOrNull(1)?.takeIf { it.isNotBlank() }
                        executeQuickShareGet(ctx, mod, code, password)
                    }))

            // Set token subcommand (admin only)
            .then(literal("settoken")
                .requires { hasOpPermission(it) }
                .then(argument("token", StringArgumentType.string())
                    .executes { ctx ->
                        val token = StringArgumentType.getString(ctx, "token")
                        executeSetToken(ctx, mod, token)
                    }))

            // Reload subcommand (admin only)
            .then(literal("reload")
                .requires { hasOpPermission(it) }
                .executes { ctx -> executeReload(ctx, mod) })

            .build()

        dispatcher.root.addChild(command)

        // Register aliases
        val schemAlias = literal("schem").redirect(command).build()
        val schAlias = literal("sch").redirect(command).build()
        dispatcher.root.addChild(schemAlias)
        dispatcher.root.addChild(schAlias)
    }

    private fun executeInfo(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source

        source.sendMessage(Text.literal("§6=== Schematio Connector ==="))
        source.sendMessage(Text.literal("§7Platform: §fFabric"))
        source.sendMessage(Text.literal("§7API URL: §f${mod.apiEndpoint}"))

        val connected = mod.httpUtil != null
        val status = if (connected) "§aConnected" else "§cNot connected"
        source.sendMessage(Text.literal("§7Status: $status"))

        if (!connected) {
            source.sendMessage(Text.literal("§7Run §e/schematio settoken <token>§7 to connect"))
        }

        return 1
    }

    private fun executeList(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod, search: String?): Int {
        val source = ctx.source

        if (mod.httpUtil == null) {
            source.sendMessage(Text.literal("§cNot connected to API. Set token first."))
            return 0
        }

        source.sendMessage(Text.literal("§7Fetching schematics..."))

        // Run async
        mod.platformAdapter.runAsync {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    mod.schematicsApiService.fetchSchematics(search, 1, 10)
                }

                val schematics = result.first
                val meta = result.second

                mod.platformAdapter.runOnMainThread {
                    if (schematics.isEmpty()) {
                        source.sendMessage(Text.literal("§7No schematics found."))
                    } else {
                        source.sendMessage(Text.literal("§6=== Schematics ==="))
                        schematics.forEach { schematic ->
                            val name = schematic.get("name")?.asString ?: "Unknown"
                            val shortId = schematic.get("short_id")?.asString ?: ""
                            source.sendMessage(Text.literal("§7- §f$name §8($shortId)"))
                        }

                        val currentPage = meta.get("current_page")?.asInt ?: 1
                        val lastPage = meta.get("last_page")?.asInt ?: 1
                        source.sendMessage(Text.literal("§7Page $currentPage of $lastPage"))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("§cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    private fun executeDownload(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod, id: String): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("§cThis command must be run by a player."))
            return 0
        }

        if (mod.httpUtil == null) {
            source.sendMessage(Text.literal("§cNot connected to API. Set token first."))
            return 0
        }

        if (!FabricWorldEditUtil.isAvailable()) {
            source.sendMessage(Text.literal("§cWorldEdit is not installed. Install WorldEdit to use download."))
            return 0
        }

        source.sendMessage(Text.literal("§7Downloading schematic §f$id§7..."))

        mod.platformAdapter.runAsync {
            try {
                val requestBody = """{"format":"schem"}"""
                val response: HttpResponse? = kotlinx.coroutines.runBlocking {
                    mod.httpUtil!!.sendGetRequestWithBodyFullResponse(
                        "/schematics/$id/download", requestBody
                    ) { _ -> }
                }

                if (response == null) {
                    mod.platformAdapter.runOnMainThread {
                        source.sendMessage(Text.literal("§cFailed to download schematic."))
                    }
                    return@runAsync
                }

                val statusCode = response.statusLine.statusCode
                if (statusCode != 200) {
                    mod.platformAdapter.runOnMainThread {
                        source.sendMessage(Text.literal("§cDownload failed (status $statusCode)."))
                    }
                    return@runAsync
                }

                val bytes = EntityUtils.toByteArray(response.entity)
                if (bytes == null || bytes.isEmpty()) {
                    mod.platformAdapter.runOnMainThread {
                        source.sendMessage(Text.literal("§cFailed to read schematic data."))
                    }
                    return@runAsync
                }

                // Parse schematic on async thread
                val clipboard = FabricWorldEditUtil.byteArrayToClipboard(bytes)

                mod.platformAdapter.runOnMainThread {
                    if (clipboard == null) {
                        source.sendMessage(Text.literal("§cFailed to parse schematic data."))
                        return@runOnMainThread
                    }

                    FabricWorldEditUtil.setClipboard(player, clipboard)
                    source.sendMessage(Text.literal("§aSchematic loaded into clipboard! (${bytes.size} bytes)"))
                    source.sendMessage(Text.literal("§7Use §e//paste§7 to place it."))
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("§cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    private fun executeUpload(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("§cThis command must be run by a player."))
            return 0
        }

        if (mod.httpUtil == null) {
            source.sendMessage(Text.literal("§cNot connected to API. Set token first."))
            return 0
        }

        if (!FabricWorldEditUtil.isAvailable()) {
            source.sendMessage(Text.literal("§cWorldEdit is not installed. Install WorldEdit to use upload."))
            return 0
        }

        val clipboard = FabricWorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            source.sendMessage(Text.literal("§cNo clipboard found. Use §e//copy§c first."))
            return 0
        }

        val schematicBytes = FabricWorldEditUtil.clipboardToByteArray(clipboard)
        if (schematicBytes == null) {
            source.sendMessage(Text.literal("§cFailed to convert clipboard to schematic format."))
            return 0
        }

        source.sendMessage(Text.literal("§7Uploading schematic (${schematicBytes.size} bytes)..."))

        mod.platformAdapter.runAsync {
            try {
                val multipart = MultipartEntityBuilder.create()
                    .addTextBody("author", player.uuidAsString)
                    .addBinaryBody("schematic", schematicBytes, ContentType.DEFAULT_BINARY, "schematic")
                    .build()

                val response = kotlinx.coroutines.runBlocking {
                    mod.httpUtil!!.sendMultiPartRequest("/schematics/upload", multipart)
                }

                mod.platformAdapter.runOnMainThread {
                    if (response == null) {
                        source.sendMessage(Text.literal("§cCould not connect to schemat.io API."))
                        return@runOnMainThread
                    }

                    try {
                        val responseString = EntityUtils.toString(response)
                        val json = com.google.gson.JsonParser.parseString(responseString).asJsonObject

                        val link = json.get("link")?.asString
                        if (link != null) {
                            source.sendMessage(Text.literal("§aSchematic uploaded successfully!"))
                            source.sendMessage(Text.literal("§7Link: §f$link"))
                        } else {
                            val error = json.get("message")?.asString ?: json.get("error")?.asString ?: "Unknown error"
                            source.sendMessage(Text.literal("§cUpload failed: $error"))
                        }
                    } catch (e: Exception) {
                        source.sendMessage(Text.literal("§cError processing server response."))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("§cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    private fun executeQuickShare(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("§cThis command must be run by a player."))
            return 0
        }

        if (mod.httpUtil == null) {
            source.sendMessage(Text.literal("§cNot connected to API. Set token first."))
            return 0
        }

        if (!FabricWorldEditUtil.isAvailable()) {
            source.sendMessage(Text.literal("§cWorldEdit is not installed. Install WorldEdit to use quickshare."))
            return 0
        }

        val clipboard = FabricWorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            source.sendMessage(Text.literal("§cNo clipboard found. Use §e//copy§c first."))
            return 0
        }

        val schematicBytes = FabricWorldEditUtil.clipboardToByteArray(clipboard)
        if (schematicBytes == null) {
            source.sendMessage(Text.literal("§cFailed to convert clipboard to schematic format."))
            return 0
        }

        source.sendMessage(Text.literal("§7Creating quick share..."))

        mod.platformAdapter.runAsync {
            try {
                val base64Data = Base64.getEncoder().encodeToString(schematicBytes)
                val requestBody = JsonObject().apply {
                    addProperty("schematic_data", base64Data)
                    addProperty("format", "schem")
                    addProperty("expires_in", 86400) // 24 hours
                    addProperty("player_uuid", player.uuidAsString)
                }

                val (statusCode, responseBody) = kotlinx.coroutines.runBlocking {
                    mod.httpUtil!!.sendPostRequest("/plugin/quick-shares", requestBody.toString())
                }

                mod.platformAdapter.runOnMainThread {
                    if (statusCode == 201 && responseBody != null) {
                        try {
                            val json = JsonParser.parseString(responseBody).asJsonObject
                            val quickShare = json.getAsJsonObject("quick_share")
                            val webUrl = quickShare?.get("web_url")?.asString

                            if (webUrl != null) {
                                source.sendMessage(Text.literal("§aQuick share created! (expires in 24h)"))
                                source.sendMessage(Text.literal("§7Link: §f$webUrl"))
                            } else {
                                source.sendMessage(Text.literal("§eShare created but no URL returned."))
                            }
                        } catch (e: Exception) {
                            source.sendMessage(Text.literal("§cError parsing response."))
                        }
                    } else {
                        val errorMsg = when (statusCode) {
                            400 -> "Invalid schematic data"
                            403 -> "Permission denied"
                            413 -> "Schematic too large (max 10MB)"
                            -1 -> "Connection failed"
                            else -> "Error (code: $statusCode)"
                        }
                        source.sendMessage(Text.literal("§c$errorMsg"))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("§cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    private fun executeQuickShareGet(
        ctx: CommandContext<ServerCommandSource>,
        mod: SchematioConnectorMod,
        rawCode: String,
        password: String?
    ): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("§cThis command must be run by a player."))
            return 0
        }

        if (mod.httpUtil == null) {
            source.sendMessage(Text.literal("§cNot connected to API. Set token first."))
            return 0
        }

        if (!FabricWorldEditUtil.isAvailable()) {
            source.sendMessage(Text.literal("§cWorldEdit is not installed. Install WorldEdit to use quickshareget."))
            return 0
        }

        // Validate and extract access code (handles URLs too)
        val codeValidation = InputValidator.validateQuickShareCode(rawCode)
        if (codeValidation is ValidationResult.Invalid) {
            source.sendMessage(Text.literal("§c${codeValidation.message}"))
            return 0
        }
        val accessCode = (codeValidation as ValidationResult.Valid).value

        source.sendMessage(Text.literal("§7Downloading quick share..."))

        mod.platformAdapter.runAsync {
            try {
                val requestBody = JsonObject().apply {
                    addProperty("player_uuid", player.uuidAsString)
                    if (!password.isNullOrBlank()) {
                        addProperty("password", password)
                    }
                }

                val (statusCode, bytes, errorBody) = kotlinx.coroutines.runBlocking {
                    mod.httpUtil!!.sendPostRequestForBinary(
                        "/plugin/quick-shares/$accessCode/download",
                        requestBody.toString()
                    )
                }

                if (statusCode == 200 && bytes != null) {
                    // Parse schematic on async thread
                    val clipboard = FabricWorldEditUtil.byteArrayToClipboard(bytes)

                    mod.platformAdapter.runOnMainThread {
                        if (clipboard != null) {
                            FabricWorldEditUtil.setClipboard(player, clipboard)
                            source.sendMessage(Text.literal("§aQuick share downloaded to clipboard!"))
                            source.sendMessage(Text.literal("§7Use §e//paste§7 to place it."))
                        } else {
                            source.sendMessage(Text.literal("§cFailed to parse schematic data."))
                        }
                    }
                } else {
                    mod.platformAdapter.runOnMainThread {
                        val errorMsg = when (statusCode) {
                            401 -> {
                                if (password.isNullOrBlank())
                                    "This share requires a password. Use: /schematio quickshareget $accessCode <password>"
                                else
                                    "Incorrect password."
                            }
                            403 -> parseErrorMessage(errorBody) ?: "Access denied"
                            404 -> "Quick share not found."
                            410 -> parseErrorMessage(errorBody) ?: "This share has expired or been revoked."
                            429 -> parseErrorMessage(errorBody) ?: "Download limit reached."
                            -1 -> "Connection failed."
                            else -> "Error (code: $statusCode)"
                        }
                        source.sendMessage(Text.literal("§c$errorMsg"))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("§cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    private fun parseErrorMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val json = JsonParser.parseString(errorBody).asJsonObject
            json.get("message")?.asString ?: json.get("error")?.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun executeSetToken(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod, token: String): Int {
        val source = ctx.source

        mod.setApiToken(token)
        source.sendMessage(Text.literal("§aAPI token updated!"))
        source.sendMessage(Text.literal("§7Testing connection..."))

        mod.platformAdapter.runAsync {
            try {
                val connected = kotlinx.coroutines.runBlocking {
                    mod.httpUtil?.checkConnection() ?: false
                }

                mod.platformAdapter.runOnMainThread {
                    if (connected) {
                        source.sendMessage(Text.literal("§aSuccessfully connected to schemat.io!"))
                    } else {
                        source.sendMessage(Text.literal("§cConnection failed. Check your token."))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("§cConnection error: ${e.message}"))
                }
            }
        }

        return 1
    }

    private fun executeReload(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source

        mod.reload()
        source.sendMessage(Text.literal("§aConfiguration reloaded!"))

        return 1
    }
}
