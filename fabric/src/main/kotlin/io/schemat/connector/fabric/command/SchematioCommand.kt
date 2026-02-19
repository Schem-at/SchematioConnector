package io.schemat.connector.fabric.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.schemat.connector.core.api.dialog.*
import io.schemat.connector.core.dialog.DialogBuilders
import io.schemat.connector.core.dialog.ListOptions
import io.schemat.connector.core.dialog.PaginationMeta
import io.schemat.connector.core.dialog.SchematicSummary
import io.schemat.connector.core.validation.InputValidator
import io.schemat.connector.core.validation.ValidationResult
import io.schemat.connector.fabric.SchematioConnectorMod
import io.schemat.connector.fabric.dialog.FabricDialogRenderer
import io.schemat.connector.fabric.worldedit.FabricWorldEditUtil
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import org.apache.http.entity.ContentType
import org.apache.http.util.EntityUtils
import org.apache.http.entity.mime.MultipartEntityBuilder
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
        val disabled = mod.disabledCommands

        val root = literal("schematio")
            // Admin commands - always available, cannot be disabled
            .then(literal("info")
                .executes { ctx -> executeInfo(ctx, mod) })
            .then(literal("settoken")
                .requires { hasOpPermission(it) }
                .executes { ctx -> showSetTokenDialog(ctx, mod) }
                .then(argument("token", StringArgumentType.string())
                    .executes { ctx ->
                        val token = StringArgumentType.getString(ctx, "token")
                        executeSetToken(ctx, mod, token)
                    }))
            .then(literal("setpassword")
                .requires { hasOpPermission(it) }
                .executes { ctx -> showSetPasswordDialog(ctx, mod) }
                .then(argument("args", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val args = StringArgumentType.getString(ctx, "args").split(" ")
                        val password = args[0]
                        val confirm = args.getOrNull(1) ?: ""
                        val isDialog = args.any { it == "--dialog" }
                        executeSetPassword(ctx, mod, password, confirm, isDialog)
                    }))
            .then(literal("reload")
                .requires { hasOpPermission(it) }
                .executes { ctx -> executeReload(ctx, mod) })

        // Optional commands - respect disabled-commands config
        if ("settings" !in disabled) {
            root.then(literal("settings")
                .executes { ctx -> showSettingsDialog(ctx, mod) }
                .then(literal("ui")
                    .then(argument("mode", StringArgumentType.word())
                        .executes { ctx ->
                            val mode = StringArgumentType.getString(ctx, "mode")
                            executeSettingsSetMode(ctx, mod, mode)
                        }))
                .then(literal("reset")
                    .executes { ctx -> executeSettingsReset(ctx, mod) }))
        }

        if ("list" !in disabled) {
            root.then(literal("list")
                .executes { ctx -> executeList(ctx, mod, null) }
                .then(argument("search", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val search = StringArgumentType.getString(ctx, "search")
                        executeList(ctx, mod, search)
                    }))
        }

        if ("search" !in disabled) {
            // Search is an alias for list with search args
            root.then(literal("search")
                .then(argument("query", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val query = StringArgumentType.getString(ctx, "query")
                        executeList(ctx, mod, query)
                    }))
        }

        if ("download" !in disabled) {
            root.then(literal("download")
                .executes { ctx -> showDownloadInputDialog(ctx, mod) }
                .then(argument("id", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val args = StringArgumentType.getString(ctx, "id")
                        executeDownload(ctx, mod, args)
                    }))
            // "get" is an alias for "download"
            root.then(literal("get")
                .executes { ctx -> showDownloadInputDialog(ctx, mod) }
                .then(argument("id", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val args = StringArgumentType.getString(ctx, "id")
                        executeDownload(ctx, mod, args)
                    }))
        }

        if ("upload" !in disabled) {
            root.then(literal("upload")
                .requires { hasOpPermission(it) }
                .executes { ctx -> executeUpload(ctx, mod) })
        }

        if ("quickshare" !in disabled) {
            root.then(literal("quickshare")
                .executes { ctx -> executeQuickShare(ctx, mod) }
                .then(argument("args", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val args = StringArgumentType.getString(ctx, "args")
                        executeQuickShareWithArgs(ctx, mod, args)
                    }))
        }

        val command = root.build()
        dispatcher.root.addChild(command)

        // Register aliases
        val schemAlias = literal("schem").redirect(command).build()
        val schAlias = literal("sch").redirect(command).build()
        val sioAlias = literal("sio").redirect(command).build()
        dispatcher.root.addChild(schemAlias)
        dispatcher.root.addChild(schAlias)
        dispatcher.root.addChild(sioAlias)
    }

    // ===== Info =====

    private fun executeInfo(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source

        source.sendMessage(Text.literal("\u00a76=== Schematio Connector ==="))
        source.sendMessage(Text.literal("\u00a77Platform: \u00a7fFabric"))
        source.sendMessage(Text.literal("\u00a77API URL: \u00a7f${mod.apiEndpoint}"))

        val connected = mod.httpUtil != null
        val status = if (connected) "\u00a7aConnected" else "\u00a7cNot connected"
        source.sendMessage(Text.literal("\u00a77Status: $status"))

        if (!connected) {
            source.sendMessage(Text.literal("\u00a77Run \u00a7e/schematio settoken <token>\u00a77 to connect"))
        }

        return 1
    }

    // ===== List =====

    private fun executeList(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod, rawSearch: String?): Int {
        val source = ctx.source

        if (mod.httpUtil == null) {
            source.sendMessage(Text.literal("\u00a7cNot connected to API. Set token first."))
            return 0
        }

        // Parse --dialog, --visibility, --sort flags from search string
        val parts = rawSearch?.split(" ") ?: emptyList()
        val isDialog = parts.any { it == "--dialog" }
        val visibilityArg = parts.firstOrNull { it.startsWith("--visibility=") }
            ?.substringAfter("=") ?: "all"
        val sortArg = parts.firstOrNull { it.startsWith("--sort=") }
            ?.substringAfter("=") ?: "newest"
        val pageArg = parts.lastOrNull()?.toIntOrNull() ?: 1
        val search = parts.filter {
            !it.startsWith("--") && it.toIntOrNull() == null
        }.joinToString(" ").takeIf { it.isNotBlank() }

        if (isDialog) {
            return executeListDialog(ctx, mod, search, visibilityArg, sortArg, pageArg)
        }

        source.sendMessage(Text.literal("\u00a77Fetching schematics..."))

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
                        source.sendMessage(Text.literal("\u00a77No schematics found."))
                    } else {
                        source.sendMessage(Text.literal("\u00a76=== Schematics ==="))
                        schematics.forEach { schematic ->
                            val name = schematic.get("name")?.asString ?: "Unknown"
                            val shortId = schematic.get("short_id")?.asString ?: ""
                            source.sendMessage(Text.literal("\u00a77- \u00a7f$name \u00a78($shortId)"))
                        }

                        val currentPage = meta.get("current_page")?.asInt ?: 1
                        val lastPage = meta.get("last_page")?.asInt ?: 1
                        source.sendMessage(Text.literal("\u00a77Page $currentPage of $lastPage"))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("\u00a7cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    private fun executeListDialog(
        ctx: CommandContext<ServerCommandSource>,
        mod: SchematioConnectorMod,
        search: String?,
        visibility: String,
        sort: String,
        page: Int
    ): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("\u00a7cThis command must be run by a player."))
            return 0
        }

        // Convert sort shorthand to API values
        val (sortField, sortOrder) = when (sort) {
            "newest" -> "created_at" to "desc"
            "oldest" -> "created_at" to "asc"
            "updated" -> "updated_at" to "desc"
            "name" -> "name" to "asc"
            "popular" -> "downloads" to "desc"
            else -> "created_at" to "desc"
        }

        source.sendMessage(Text.literal("\u00a77Fetching schematics..."))

        mod.platformAdapter.runAsync {
            try {
                val options = io.schemat.connector.core.service.SchematicsApiService.QueryOptions(
                    search = search,
                    visibility = when (visibility) {
                        "public" -> io.schemat.connector.core.service.SchematicsApiService.Visibility.PUBLIC
                        "private" -> io.schemat.connector.core.service.SchematicsApiService.Visibility.PRIVATE
                        else -> io.schemat.connector.core.service.SchematicsApiService.Visibility.ALL
                    },
                    sort = when (sortField) {
                        "updated_at" -> io.schemat.connector.core.service.SchematicsApiService.SortField.UPDATED_AT
                        "name" -> io.schemat.connector.core.service.SchematicsApiService.SortField.NAME
                        "downloads" -> io.schemat.connector.core.service.SchematicsApiService.SortField.DOWNLOADS
                        else -> io.schemat.connector.core.service.SchematicsApiService.SortField.CREATED_AT
                    },
                    order = if (sortOrder == "asc") io.schemat.connector.core.service.SchematicsApiService.SortOrder.ASC
                    else io.schemat.connector.core.service.SchematicsApiService.SortOrder.DESC,
                    page = page,
                    perPage = 10
                )

                val (schematics, meta) = kotlinx.coroutines.runBlocking {
                    mod.schematicsApiService.fetchSchematicsWithOptions(options)
                }

                val summaries = schematics.map { jsonToSchematicSummary(it) }
                val paginationMeta = PaginationMeta(
                    currentPage = meta.get("current_page")?.asInt ?: 1,
                    lastPage = meta.get("last_page")?.asInt ?: 1,
                    total = meta.get("total")?.asInt ?: summaries.size
                )
                val listOptions = ListOptions(
                    search = search,
                    visibility = visibility,
                    sort = sortField,
                    order = sortOrder
                )

                val dialogDef = DialogBuilders.schematicsListDialog(
                    schematics = summaries,
                    meta = paginationMeta,
                    options = listOptions
                )

                mod.platformAdapter.runOnMainThread {
                    FabricDialogRenderer.showDialog(player, dialogDef)
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("\u00a7cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    private fun jsonToSchematicSummary(json: JsonObject): SchematicSummary {
        return SchematicSummary(
            shortId = json.get("short_id")?.asString ?: "",
            name = json.get("name")?.asString ?: "Unknown",
            isPublic = json.get("is_public")?.asBoolean ?: true,
            downloadCount = json.get("download_count")?.asInt ?: 0,
            width = json.get("width")?.asInt ?: 0,
            height = json.get("height")?.asInt ?: 0,
            length = json.get("length")?.asInt ?: 0,
            authorName = json.get("author_name")?.asString
        )
    }

    // ===== Download =====

    private fun showDownloadInputDialog(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("\u00a7cThis command must be run by a player."))
            return 0
        }

        val dialogDef = DialogBuilders.downloadInputDialog()
        FabricDialogRenderer.showDialog(player, dialogDef)
        return 1
    }

    private fun executeDownload(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod, rawArgs: String): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("\u00a7cThis command must be run by a player."))
            return 0
        }

        val http = mod.httpUtil
        if (http == null) {
            source.sendMessage(Text.literal("\u00a7cNot connected to API. Set token first."))
            return 0
        }

        if (!FabricWorldEditUtil.isAvailable()) {
            source.sendMessage(Text.literal("\u00a7cWorldEdit is not installed. Install WorldEdit to use download."))
            return 0
        }

        // Parse flags and input
        val parts = rawArgs.split(" ")
        val isDialog = parts.any { it == "--dialog" }
        val nonFlagParts = parts.filter { !it.startsWith("--") }
        val id = nonFlagParts.firstOrNull() ?: ""

        val isQuickShare = id.startsWith("http://") || id.startsWith("https://") || id.startsWith("qs_")
        var password: String? = null
        val downloadId: String

        if (isQuickShare) {
            val codeValidation = InputValidator.validateQuickShareCode(id)
            if (codeValidation is ValidationResult.Invalid) {
                source.sendMessage(Text.literal("\u00a7c${codeValidation.message}"))
                return 0
            }
            downloadId = (codeValidation as ValidationResult.Valid).value
            password = nonFlagParts.getOrNull(1)?.takeIf { it.isNotBlank() }
        } else {
            downloadId = id
        }

        val label = if (isQuickShare) "quick share" else "schematic"
        source.sendMessage(Text.literal("\u00a77Downloading $label \u00a7f$downloadId\u00a77..."))

        mod.platformAdapter.runAsync {
            try {
                val requestBody = JsonObject().apply {
                    addProperty("format", "schem")
                    addProperty("player_uuid", player.uuidAsString)
                    if (!password.isNullOrBlank()) {
                        addProperty("password", password)
                    }
                }

                val (statusCode, bytes, errorBody) = kotlinx.coroutines.runBlocking {
                    http.sendPostRequestForBinary(
                        "/schematics/$downloadId/download",
                        requestBody.toString()
                    )
                }

                if (statusCode == 200 && bytes != null) {
                    val clipboard = FabricWorldEditUtil.byteArrayToClipboard(bytes)

                    mod.platformAdapter.runOnMainThread {
                        if (clipboard != null) {
                            FabricWorldEditUtil.setClipboard(player, clipboard)

                            if (isDialog && !isQuickShare) {
                                val dialogDef = DialogBuilders.downloadSuccessDialog(downloadId, "schem", mod.baseUrl)
                                FabricDialogRenderer.showDialog(player, dialogDef)
                            } else {
                                source.sendMessage(Text.literal("\u00a7a${label.replaceFirstChar { it.uppercase() }} downloaded to clipboard!"))
                                source.sendMessage(Text.literal("\u00a77Use \u00a7e//paste\u00a77 to place it."))
                            }
                        } else {
                            source.sendMessage(Text.literal("\u00a7cFailed to parse schematic data."))
                        }
                    }
                } else {
                    mod.platformAdapter.runOnMainThread {
                        val errorMsg = when (statusCode) {
                            401 -> {
                                if (password.isNullOrBlank() && isDialog) {
                                    val dialogDef = DialogBuilders.quickShareGetPasswordDialog(downloadId)
                                    FabricDialogRenderer.showDialog(player, dialogDef)
                                    return@runOnMainThread
                                }
                                if (password.isNullOrBlank()) {
                                    "This download requires a password. Use: /schematio download $downloadId <password>"
                                } else {
                                    "Incorrect password."
                                }
                            }
                            403 -> parseErrorMessage(errorBody) ?: "Access denied"
                            404 -> "Not found. Check the ID or code and try again."
                            410 -> parseErrorMessage(errorBody) ?: "This download has expired or been revoked."
                            422 -> parseErrorMessage(errorBody) ?: "Invalid request."
                            429 -> parseErrorMessage(errorBody) ?: "Rate limited. Please try again later."
                            -1 -> "Connection failed."
                            else -> "Error (code: $statusCode)"
                        }
                        source.sendMessage(Text.literal("\u00a7c$errorMsg"))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("\u00a7cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    // ===== Upload =====

    private fun executeUpload(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("\u00a7cThis command must be run by a player."))
            return 0
        }

        val http = mod.httpUtil
        if (http == null) {
            source.sendMessage(Text.literal("\u00a7cNot connected to API. Set token first."))
            return 0
        }

        if (!FabricWorldEditUtil.isAvailable()) {
            source.sendMessage(Text.literal("\u00a7cWorldEdit is not installed. Install WorldEdit to use upload."))
            return 0
        }

        val clipboard = FabricWorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            source.sendMessage(Text.literal("\u00a7cNo clipboard found. Use \u00a7e//copy\u00a7c first."))
            return 0
        }

        val schematicBytes = FabricWorldEditUtil.clipboardToByteArray(clipboard)
        if (schematicBytes == null) {
            source.sendMessage(Text.literal("\u00a7cFailed to convert clipboard to schematic format."))
            return 0
        }

        source.sendMessage(Text.literal("\u00a77Uploading schematic (${schematicBytes.size} bytes)..."))

        mod.platformAdapter.runAsync {
            try {
                val multipart = MultipartEntityBuilder.create()
                    .addTextBody("author", player.uuidAsString)
                    .addBinaryBody("schematic", schematicBytes, ContentType.DEFAULT_BINARY, "schematic")
                    .build()

                val response = kotlinx.coroutines.runBlocking {
                    http.sendMultiPartRequest("/schematics/upload", multipart)
                }

                mod.platformAdapter.runOnMainThread {
                    if (response == null) {
                        source.sendMessage(Text.literal("\u00a7cCould not connect to schemat.io API."))
                        return@runOnMainThread
                    }

                    try {
                        val responseString = EntityUtils.toString(response)
                        val json = JsonParser.parseString(responseString).asJsonObject

                        val link = json.get("link")?.asString
                        if (link != null) {
                            source.sendMessage(Text.literal("\u00a7aSchematic uploaded successfully!"))
                            source.sendMessage(Text.literal("\u00a77Link: \u00a7f$link"))
                        } else {
                            val error = json.get("message")?.asString ?: json.get("error")?.asString ?: "Unknown error"
                            source.sendMessage(Text.literal("\u00a7cUpload failed: $error"))
                        }
                    } catch (e: Exception) {
                        source.sendMessage(Text.literal("\u00a7cError processing server response."))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("\u00a7cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    // ===== Quick Share =====

    private fun executeQuickShare(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("\u00a7cThis command must be run by a player."))
            return 0
        }

        if (mod.httpUtil == null) {
            source.sendMessage(Text.literal("\u00a7cNot connected to API. Set token first."))
            return 0
        }

        if (!FabricWorldEditUtil.isAvailable()) {
            source.sendMessage(Text.literal("\u00a7cWorldEdit is not installed. Install WorldEdit to use quickshare."))
            return 0
        }

        val clipboard = FabricWorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            source.sendMessage(Text.literal("\u00a7cNo clipboard found. Use \u00a7e//copy\u00a7c first."))
            return 0
        }

        val schematicBytes = FabricWorldEditUtil.clipboardToByteArray(clipboard)
        if (schematicBytes == null) {
            source.sendMessage(Text.literal("\u00a7cFailed to convert clipboard to schematic format."))
            return 0
        }

        val sizeKb = schematicBytes.size / 1024

        // Show dialog with share options
        val dialogDef = DialogBuilders.quickShareDialog(sizeKb)
        FabricDialogRenderer.showDialog(player, dialogDef)

        return 1
    }

    private fun executeQuickShareWithArgs(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod, rawArgs: String): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("\u00a7cThis command must be run by a player."))
            return 0
        }

        val http = mod.httpUtil
        if (http == null) {
            source.sendMessage(Text.literal("\u00a7cNot connected to API. Set token first."))
            return 0
        }

        if (!FabricWorldEditUtil.isAvailable()) {
            source.sendMessage(Text.literal("\u00a7cWorldEdit is not installed."))
            return 0
        }

        val clipboard = FabricWorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            source.sendMessage(Text.literal("\u00a7cNo clipboard found. Use \u00a7e//copy\u00a7c first."))
            return 0
        }

        val schematicBytes = FabricWorldEditUtil.clipboardToByteArray(clipboard)
        if (schematicBytes == null) {
            source.sendMessage(Text.literal("\u00a7cFailed to convert clipboard to schematic format."))
            return 0
        }

        // Parse option flags (supports --expires=1h / -e 1h, --limit=10 / -l 10, --password=x / -p x)
        val parts = rawArgs.split(" ")
        var expiresIn = 86400
        var maxDownloads: Int? = null
        var password: String? = null

        var idx = 0
        while (idx < parts.size) {
            val part = parts[idx]
            when {
                part.startsWith("--expires=") || part.startsWith("-e=") ->
                    expiresIn = InputValidator.parseDuration(part.substringAfter("=")) ?: 86400
                part == "--expires" || part == "-e" ->
                    if (idx + 1 < parts.size) expiresIn = InputValidator.parseDuration(parts[++idx]) ?: 86400
                part.startsWith("--limit=") || part.startsWith("-l=") ->
                    maxDownloads = InputValidator.parseDownloadLimit(part.substringAfter("="))
                part == "--limit" || part == "-l" ->
                    if (idx + 1 < parts.size) maxDownloads = InputValidator.parseDownloadLimit(parts[++idx])
                part.startsWith("--password=") || part.startsWith("-p=") ->
                    password = part.substringAfter("=").ifBlank { null }
                part == "--password" || part == "-p" ->
                    if (idx + 1 < parts.size) password = parts[++idx].ifBlank { null }
            }
            idx++
        }

        source.sendMessage(Text.literal("\u00a77Creating quick share..."))

        mod.platformAdapter.runAsync {
            try {
                val base64Data = Base64.getEncoder().encodeToString(schematicBytes)
                val requestBody = JsonObject().apply {
                    addProperty("schematic_data", base64Data)
                    addProperty("format", "schem")
                    addProperty("expires_in", expiresIn)
                    addProperty("player_uuid", player.uuidAsString)
                    if (maxDownloads != null) addProperty("max_downloads", maxDownloads)
                    if (!password.isNullOrBlank()) addProperty("password", password)
                }

                val (statusCode, responseBody) = kotlinx.coroutines.runBlocking {
                    http.sendPostRequest("/plugin/quick-shares", requestBody.toString())
                }

                mod.platformAdapter.runOnMainThread {
                    if (statusCode == 201 && responseBody != null) {
                        try {
                            val json = JsonParser.parseString(responseBody).asJsonObject
                            val quickShare = json.getAsJsonObject("quick_share")
                            val webUrl = quickShare?.get("web_url")?.asString

                            if (webUrl != null) {
                                source.sendMessage(Text.literal("\u00a7aQuick share created!"))
                                source.sendMessage(Text.literal("\u00a77Link: \u00a7f$webUrl"))
                            } else {
                                source.sendMessage(Text.literal("\u00a7eShare created but no URL returned."))
                            }
                        } catch (e: Exception) {
                            source.sendMessage(Text.literal("\u00a7cError parsing response: ${e.message}"))
                        }
                    } else {
                        val errorMsg = when (statusCode) {
                            400 -> "Invalid schematic data"
                            403 -> "Permission denied"
                            413 -> "Schematic too large (max 10MB)"
                            -1 -> "Connection failed"
                            else -> "Error (code: $statusCode)"
                        }
                        source.sendMessage(Text.literal("\u00a7c$errorMsg"))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("\u00a7cError: ${e.message}"))
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

    // ===== Set Token =====

    private fun showSetTokenDialog(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("\u00a7cThis command must be run by a player."))
            return 0
        }

        val dialogDef = DialogBuilders.setTokenDialog()
        FabricDialogRenderer.showDialog(player, dialogDef)
        return 1
    }

    private fun executeSetToken(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod, token: String): Int {
        val source = ctx.source

        mod.setApiToken(token)
        source.sendMessage(Text.literal("\u00a7aAPI token updated!"))
        source.sendMessage(Text.literal("\u00a77Testing connection..."))

        mod.platformAdapter.runAsync {
            try {
                val connected = kotlinx.coroutines.runBlocking {
                    mod.httpUtil?.checkConnection() ?: false
                }

                mod.platformAdapter.runOnMainThread {
                    if (connected) {
                        source.sendMessage(Text.literal("\u00a7aSuccessfully connected to schemat.io!"))
                    } else {
                        source.sendMessage(Text.literal("\u00a7cConnection failed. Check your token."))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("\u00a7cConnection error: ${e.message}"))
                }
            }
        }

        return 1
    }

    // ===== Set Password =====

    private fun showSetPasswordDialog(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("\u00a7cThis command must be run by a player."))
            return 0
        }

        val dialogDef = DialogBuilders.setPasswordDialog()
        FabricDialogRenderer.showDialog(player, dialogDef)
        return 1
    }

    private fun executeSetPassword(
        ctx: CommandContext<ServerCommandSource>,
        mod: SchematioConnectorMod,
        password: String,
        confirm: String,
        isDialog: Boolean
    ): Int {
        val source = ctx.source

        val http = mod.httpUtil
        if (http == null) {
            source.sendMessage(Text.literal("\u00a7cNot connected to API. Set token first."))
            return 0
        }

        if (password != confirm) {
            source.sendMessage(Text.literal("\u00a7cPasswords do not match."))
            return 0
        }

        val validation = InputValidator.validatePassword(password)
        if (validation is ValidationResult.Invalid) {
            source.sendMessage(Text.literal("\u00a7c${validation.message}"))
            return 0
        }

        source.sendMessage(Text.literal("\u00a77Setting password..."))

        mod.platformAdapter.runAsync {
            try {
                val requestBody = JsonObject().apply {
                    addProperty("password", password)
                }

                val (statusCode, responseBody) = kotlinx.coroutines.runBlocking {
                    http.sendPostRequest("/plugin/set-password", requestBody.toString())
                }

                mod.platformAdapter.runOnMainThread {
                    if (statusCode in 200..299) {
                        source.sendMessage(Text.literal("\u00a7aPassword set successfully!"))
                    } else {
                        val errorMsg = if (responseBody != null) {
                            parseErrorMessage(responseBody) ?: "Error (code: $statusCode)"
                        } else {
                            "Error (code: $statusCode)"
                        }
                        source.sendMessage(Text.literal("\u00a7c$errorMsg"))
                    }
                }
            } catch (e: Exception) {
                mod.platformAdapter.runOnMainThread {
                    source.sendMessage(Text.literal("\u00a7cError: ${e.message}"))
                }
            }
        }

        return 1
    }

    // ===== Settings =====

    private fun showSettingsDialog(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source
        val player = source.player

        if (player == null) {
            source.sendMessage(Text.literal("\u00a7cThis command must be run by a player."))
            return 0
        }

        val modes = listOf(
            DialogBuilders.UIModeOption("dialog", "Dialog", true),
            DialogBuilders.UIModeOption("chat", "Chat", false)
        )

        val dialogDef = DialogBuilders.settingsDialog(
            currentMode = "dialog",
            isUserPref = false,
            availableModes = modes
        )
        FabricDialogRenderer.showDialog(player, dialogDef)
        return 1
    }

    private fun executeSettingsSetMode(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod, mode: String): Int {
        val source = ctx.source
        source.sendMessage(Text.literal("\u00a7aUI mode set to: $mode"))
        return 1
    }

    private fun executeSettingsReset(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source
        source.sendMessage(Text.literal("\u00a7aUI mode reset to server default."))
        return 1
    }

    // ===== Reload =====

    private fun executeReload(ctx: CommandContext<ServerCommandSource>, mod: SchematioConnectorMod): Int {
        val source = ctx.source

        mod.reload()
        source.sendMessage(Text.literal("\u00a7aConfiguration reloaded!"))

        return 1
    }
}
