package io.schemat.schematioConnector.commands

import com.google.gson.JsonObject
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.connector.core.validation.InputValidator
import io.schemat.connector.core.service.SchematicsApiService
import io.schemat.connector.core.service.SchematicsApiService.QueryOptions
import io.schemat.connector.core.service.SchematicsApiService.SortField
import io.schemat.connector.core.service.SchematicsApiService.SortOrder
import io.schemat.connector.core.service.SchematicsApiService.Visibility
import io.schemat.schematioConnector.utils.UIMode
import io.schemat.schematioConnector.utils.UIModeResolver
import io.schemat.connector.core.validation.ValidationResult
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetBoolean
import io.schemat.connector.core.json.safeGetInt
import io.schemat.connector.core.json.safeGetString
import io.schemat.connector.core.json.asJsonObjectOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Unified command for browsing and searching schematics on schemat.io.
 *
 * Supports both chat and dialog UI modes based on user preference, config,
 * or command flags (--chat, --dialog).
 *
 * Usage: /schematio list [search term] [page] [--chat|--dialog] [options]
 *
 * Options (dialog mode only):
 *   --visibility=all|public|private
 *   --sort=created_at|updated_at|name|downloads
 *   --order=asc|desc
 *
 * Examples:
 * - /schematio list - Show first page of all schematics
 * - /schematio list castle - Search for "castle"
 * - /schematio list castle 2 - Show page 2 of "castle" search
 * - /schematio list --dialog - Force dialog UI
 * - /schematio list castle --chat - Force chat UI
 *
 * @property plugin The main plugin instance
 */
class ListSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val ITEMS_PER_PAGE_CHAT = 10
    private val ITEMS_PER_PAGE_DIALOG = 6
    private val CACHE_KEY_PREFIX = "list"

    override val name = "list"
    override val permission = "schematio.list"
    override val description = "Browse schematics"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()
        val resolver = plugin.uiModeResolver

        // Check if player has any UI permission
        if (!resolver.hasAnyUIPermission(player)) {
            audience.sendMessage(
                Component.text("You don't have permission to use any UI mode.").color(NamedTextColor.RED)
            )
            return true
        }

        // Early check for API availability
        if (plugin.httpUtil == null) {
            audience.sendMessage(Component.text("Cannot browse schematics - not connected to schemat.io").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Configure a community token in config.yml and run /schematio reload").color(NamedTextColor.GRAY))
            return true
        }

        // Resolve UI mode and clean args
        val (uiMode, cleanedArgs) = resolver.resolveWithArgs(player, args)

        // Parse arguments based on UI mode
        return when (uiMode) {
            UIMode.CHAT -> executeChatMode(player, cleanedArgs)
            UIMode.DIALOG -> executeDialogMode(player, cleanedArgs)
        }
    }

    // ===========================================
    // CHAT MODE
    // ===========================================

    private fun executeChatMode(player: Player, args: Array<String>): Boolean {
        val audience = player.audience()

        // Parse arguments: [search] [page]
        var search: String? = null
        var page = 1

        if (args.isNotEmpty()) {
            val lastArg = args.last()
            if (lastArg.toIntOrNull() != null && args.size > 1) {
                page = lastArg.toInt().coerceAtLeast(1)
                search = args.dropLast(1).joinToString(" ")
            } else if (lastArg.toIntOrNull() != null && args.size == 1) {
                page = lastArg.toInt().coerceAtLeast(1)
            } else {
                search = args.joinToString(" ")
            }
        }

        // Validate search query
        val searchResult = InputValidator.validateSearchQuery(search)
        if (searchResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(searchResult.message).color(NamedTextColor.RED))
            return true
        }
        search = (searchResult as ValidationResult.Valid).value.ifEmpty { null }

        // Validate page number
        val pageResult = InputValidator.validatePageNumber(page)
        if (pageResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(pageResult.message).color(NamedTextColor.RED))
            return true
        }
        page = (pageResult as ValidationResult.Valid).value

        audience.sendMessage(Component.text("Loading schematics...").color(NamedTextColor.GRAY))
        fetchAndDisplayChat(player, search, page)
        return true
    }

    private fun fetchAndDisplayChat(player: Player, search: String?, page: Int) {
        val apiService = plugin.schematicsApiService

        apiService.fetchSchematicsWithCache(
            playerId = player.uniqueId,
            search = search,
            page = page,
            perPage = ITEMS_PER_PAGE_CHAT,
            cacheKeyPrefix = CACHE_KEY_PREFIX
        ) { result ->
            val audience = player.audience()

            when (result) {
                is SchematicsApiService.FetchResult.Success -> {
                    if (result.staleMinutes > 0) {
                        audience.sendMessage(Component.text("API unavailable - showing cached data").color(NamedTextColor.YELLOW))
                    }
                    displayChatResults(player, search, result.schematics, result.meta, result.fromCache, result.staleMinutes)
                }

                is SchematicsApiService.FetchResult.RateLimited -> {
                    audience.sendMessage(Component.text("Rate limited. Please wait ${result.waitSeconds}s before making another request.").color(NamedTextColor.RED))
                }

                is SchematicsApiService.FetchResult.OfflineNoCache -> {
                    audience.sendMessage(Component.text("API is offline. Retry in ${result.retrySeconds}s").color(NamedTextColor.RED))
                    audience.sendMessage(Component.text("No cached data available for this query.").color(NamedTextColor.GRAY))
                }

                is SchematicsApiService.FetchResult.Error -> {
                    handleFetchError(player, apiService, result)
                }
            }
        }
    }

    private fun displayChatResults(
        player: Player,
        search: String?,
        schematics: List<JsonObject>,
        meta: JsonObject,
        fromCache: Boolean,
        staleMinutes: Int = 0
    ) {
        val audience = player.audience()

        val currentPage = meta.safeGetInt("current_page", 1)
        val lastPage = meta.safeGetInt("last_page", 1)
        val total = meta.safeGetInt("total", 0)

        // Header
        audience.sendMessage(Component.empty())
        val headerText = if (search != null) {
            Component.text("Schematics matching \"$search\"").color(NamedTextColor.GOLD)
        } else {
            Component.text("All Schematics").color(NamedTextColor.GOLD)
        }

        val cacheIndicator = when {
            staleMinutes > 0 -> Component.text(" [cached ${staleMinutes}m ago]").color(NamedTextColor.YELLOW)
            fromCache -> Component.text(" [cached]").color(NamedTextColor.DARK_GRAY)
            else -> Component.empty()
        }

        audience.sendMessage(
            Component.text("=== ").color(NamedTextColor.DARK_GRAY)
                .append(headerText)
                .append(Component.text(" ($total total)").color(NamedTextColor.DARK_GRAY))
                .append(cacheIndicator)
                .append(Component.text(" ===").color(NamedTextColor.DARK_GRAY))
        )

        if (schematics.isEmpty()) {
            audience.sendMessage(Component.text("No schematics found.").color(NamedTextColor.GRAY))
        } else {
            for (schematic in schematics) {
                val id = schematic.safeGetString("short_id") ?: continue
                val name = schematic.safeGetString("name") ?: "Unknown"
                val isPublic = schematic.safeGetBoolean("is_public", false)

                val authorsArray = schematic.safeGetArray("authors")
                val authors = authorsArray
                    .mapNotNull { it.asJsonObjectOrNull()?.safeGetString("last_seen_name") }
                    .take(2)
                    .joinToString(", ")
                    .ifEmpty { "Unknown" }
                val authorsSuffix = if (authorsArray.size > 2) "..." else ""

                val downloadButton = Component.text("[DL]")
                    .color(NamedTextColor.GREEN)
                    .decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/schematio download $id"))
                    .hoverEvent(HoverEvent.showText(
                        Component.text("Click to download to clipboard").color(NamedTextColor.GREEN)
                    ))

                val schematicUrl = "${plugin.baseUrl}/schematics/$id"
                val webButton = Component.text("[Web]")
                    .color(NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.openUrl(schematicUrl))
                    .hoverEvent(HoverEvent.showText(
                        Component.text("Open in browser").color(NamedTextColor.AQUA)
                    ))

                val nameComponent = Component.text(name)
                    .color(NamedTextColor.WHITE)
                    .hoverEvent(HoverEvent.showText(
                        Component.text("ID: $id\n").color(NamedTextColor.GRAY)
                            .append(Component.text("Public: ${if (isPublic) "Yes" else "No"}\n").color(NamedTextColor.GRAY))
                            .append(Component.text("Authors: $authors$authorsSuffix").color(NamedTextColor.GRAY))
                    ))

                val visibilityIcon = if (isPublic) {
                    Component.text(" [P]").color(NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Public").color(NamedTextColor.GREEN)))
                } else {
                    Component.text(" [X]").color(NamedTextColor.RED)
                        .hoverEvent(HoverEvent.showText(Component.text("Private").color(NamedTextColor.RED)))
                }

                audience.sendMessage(
                    downloadButton
                        .append(Component.text(" ").color(NamedTextColor.DARK_GRAY))
                        .append(webButton)
                        .append(Component.text(" ").color(NamedTextColor.DARK_GRAY))
                        .append(nameComponent)
                        .append(visibilityIcon)
                        .append(Component.text(" by $authors$authorsSuffix").color(NamedTextColor.GRAY))
                )
            }
        }

        // Pagination footer
        audience.sendMessage(Component.empty())
        audience.sendMessage(buildChatPaginationLine(search, currentPage, lastPage))

        // Show dialog hint if available
        if (player.hasPermission(UIModeResolver.PERMISSION_DIALOG)) {
            audience.sendMessage(
                Component.text("Tip: Use ").color(NamedTextColor.DARK_GRAY)
                    .append(Component.text("--dialog").color(NamedTextColor.AQUA))
                    .append(Component.text(" for an interactive UI").color(NamedTextColor.DARK_GRAY))
            )
        }
    }

    private fun buildChatPaginationLine(search: String?, currentPage: Int, lastPage: Int): Component {
        val searchParam = if (search != null) "$search " else ""

        val prevButton = if (currentPage > 1) {
            Component.text("[< Prev]")
                .color(NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/schematio list $searchParam${currentPage - 1}"))
                .hoverEvent(HoverEvent.showText(Component.text("Go to page ${currentPage - 1}")))
        } else {
            Component.text("[< Prev]").color(NamedTextColor.DARK_GRAY)
        }

        val pageIndicator = Component.text(" Page $currentPage/$lastPage ").color(NamedTextColor.GRAY)

        val nextButton = if (currentPage < lastPage) {
            Component.text("[Next >]")
                .color(NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/schematio list $searchParam${currentPage + 1}"))
                .hoverEvent(HoverEvent.showText(Component.text("Go to page ${currentPage + 1}")))
        } else {
            Component.text("[Next >]").color(NamedTextColor.DARK_GRAY)
        }

        return prevButton.append(pageIndicator).append(nextButton)
    }

    // ===========================================
    // DIALOG MODE
    // ===========================================

    private fun executeDialogMode(player: Player, args: Array<String>): Boolean {
        val audience = player.audience()

        // Parse arguments with options
        val options = parseDialogArgs(args)

        // Validate search query
        val searchResult = InputValidator.validateSearchQuery(options.search)
        if (searchResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(searchResult.message).color(NamedTextColor.RED))
            return true
        }
        val validatedSearch = (searchResult as ValidationResult.Valid).value.ifEmpty { null }

        // Validate page number
        val pageResult = InputValidator.validatePageNumber(options.page)
        if (pageResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(pageResult.message).color(NamedTextColor.RED))
            return true
        }
        val validatedPage = (pageResult as ValidationResult.Valid).value

        val finalOptions = options.copy(
            search = validatedSearch,
            page = validatedPage,
            perPage = ITEMS_PER_PAGE_DIALOG
        )

        audience.sendMessage(Component.text("Loading schematics...").color(NamedTextColor.GRAY))
        fetchAndShowDialog(player, finalOptions)
        return true
    }

    private fun parseDialogArgs(args: Array<String>): QueryOptions {
        var search: String? = null
        var page = 1
        var visibility = Visibility.ALL
        var sort = SortField.CREATED_AT
        var order = SortOrder.DESC

        if (args.isNotEmpty()) {
            val remainingArgs = mutableListOf<String>()

            for (arg in args) {
                when {
                    arg.startsWith("--visibility=") -> {
                        val value = arg.removePrefix("--visibility=").lowercase()
                        visibility = when (value) {
                            "public" -> Visibility.PUBLIC
                            "private" -> Visibility.PRIVATE
                            else -> Visibility.ALL
                        }
                    }
                    arg.startsWith("--sort=") -> {
                        val value = arg.removePrefix("--sort=").lowercase()
                        sort = when (value) {
                            "updated_at" -> SortField.UPDATED_AT
                            "name" -> SortField.NAME
                            "downloads" -> SortField.DOWNLOADS
                            else -> SortField.CREATED_AT
                        }
                    }
                    arg.startsWith("--order=") -> {
                        val value = arg.removePrefix("--order=").lowercase()
                        order = if (value == "asc") SortOrder.ASC else SortOrder.DESC
                    }
                    arg.toIntOrNull() != null -> {
                        page = arg.toInt().coerceAtLeast(1)
                    }
                    else -> {
                        remainingArgs.add(arg)
                    }
                }
            }

            if (remainingArgs.isNotEmpty()) {
                search = remainingArgs.joinToString(" ")
            }
        }

        return QueryOptions(
            search = search,
            visibility = visibility,
            sort = sort,
            order = order,
            page = page
        )
    }

    private fun fetchAndShowDialog(player: Player, options: QueryOptions) {
        val apiService = plugin.schematicsApiService

        apiService.fetchSchematicsWithCacheAndOptions(
            playerId = player.uniqueId,
            options = options,
            cacheKeyPrefix = CACHE_KEY_PREFIX
        ) { result ->
            val audience = player.audience()

            when (result) {
                is SchematicsApiService.FetchResult.Success -> {
                    if (result.staleMinutes > 0) {
                        audience.sendMessage(Component.text("API unavailable - showing cached data").color(NamedTextColor.YELLOW))
                    }
                    showSchematicsDialog(player, options, result.schematics, result.meta)
                }

                is SchematicsApiService.FetchResult.RateLimited -> {
                    audience.sendMessage(Component.text("Rate limited. Please wait ${result.waitSeconds}s before making another request.").color(NamedTextColor.RED))
                }

                is SchematicsApiService.FetchResult.OfflineNoCache -> {
                    audience.sendMessage(Component.text("API is offline. Retry in ${result.retrySeconds}s").color(NamedTextColor.RED))
                    audience.sendMessage(Component.text("No cached data available for this query.").color(NamedTextColor.GRAY))
                }

                is SchematicsApiService.FetchResult.Error -> {
                    handleFetchError(player, apiService, result)
                }
            }
        }
    }

    private fun showSchematicsDialog(
        player: Player,
        options: QueryOptions,
        schematics: List<JsonObject>,
        meta: JsonObject
    ) {
        val lastPage = meta.safeGetInt("last_page", 1)
        val total = meta.safeGetInt("total", 0)
        val currentPage = options.page

        // Title
        val title = Component.text("Schematics")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        // Body - status info
        val bodyElements = mutableListOf<DialogBody>()
        val filterDesc = when (options.visibility) {
            Visibility.PUBLIC -> "public"
            Visibility.PRIVATE -> "private"
            else -> "all"
        }
        val sortDesc = when (options.sort) {
            SortField.UPDATED_AT -> "updated"
            SortField.NAME -> "name"
            SortField.DOWNLOADS -> "popular"
            else -> "newest"
        }
        bodyElements.add(DialogBody.plainMessage(
            Component.text("$total schematics").color(NamedTextColor.WHITE)
                .append(Component.text(" \u2022 $filterDesc \u2022 $sortDesc").color(NamedTextColor.GRAY))
        ))

        // Inputs
        val inputs = mutableListOf<DialogInput>()

        inputs.add(
            DialogInput.text("search", Component.text("Search").color(NamedTextColor.WHITE))
                .width(200)
                .initial(options.search ?: "")
                .maxLength(50)
                .build()
        )

        val visibilityOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create("all", Component.text("All"), options.visibility == Visibility.ALL),
            SingleOptionDialogInput.OptionEntry.create("public", Component.text("Public"), options.visibility == Visibility.PUBLIC),
            SingleOptionDialogInput.OptionEntry.create("private", Component.text("Private"), options.visibility == Visibility.PRIVATE)
        )
        inputs.add(
            DialogInput.singleOption("visibility", Component.text("Visibility").color(NamedTextColor.WHITE), visibilityOptions)
                .width(200)
                .build()
        )

        val sortOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create("created_at", Component.text("Newest First"), options.sort == SortField.CREATED_AT),
            SingleOptionDialogInput.OptionEntry.create("updated_at", Component.text("Recently Updated"), options.sort == SortField.UPDATED_AT),
            SingleOptionDialogInput.OptionEntry.create("name", Component.text("Name (A-Z)"), options.sort == SortField.NAME),
            SingleOptionDialogInput.OptionEntry.create("downloads", Component.text("Most Popular"), options.sort == SortField.DOWNLOADS)
        )
        inputs.add(
            DialogInput.singleOption("sort", Component.text("Sort By").color(NamedTextColor.WHITE), sortOptions)
                .width(200)
                .build()
        )

        // Action buttons
        val actionButtons = mutableListOf<ActionButton>()

        // Search button
        actionButtons.add(
            ActionButton.builder(Component.text("Search").color(NamedTextColor.GREEN))
                .width(200)
                .action(DialogAction.commandTemplate("/schematio list \$(search) --visibility=\$(visibility) --sort=\$(sort) --dialog 1"))
                .build()
        )

        // Schematic entries
        if (schematics.isEmpty()) {
            bodyElements.add(DialogBody.plainMessage(
                Component.text("No schematics found.").color(NamedTextColor.YELLOW)
            ))
        } else {
            for (schematic in schematics) {
                val id = schematic.safeGetString("short_id") ?: continue
                val schematicName = schematic.safeGetString("name") ?: "Unknown"
                val isPublic = schematic.safeGetBoolean("is_public", false)
                val downloads = schematic.safeGetInt("download_count", 0)

                val width = schematic.safeGetInt("width", 0)
                val height = schematic.safeGetInt("height", 0)
                val length = schematic.safeGetInt("length", 0)
                val hasSize = width > 0 && height > 0 && length > 0

                val authorsArray = schematic.safeGetArray("authors")
                val author = authorsArray
                    .mapNotNull { it.asJsonObjectOrNull()?.safeGetString("last_seen_name") }
                    .firstOrNull() ?: "Unknown"

                val visColor = if (isPublic) NamedTextColor.GREEN else NamedTextColor.RED
                val buttonLabel = Component.empty()
                    .append(Component.text("\u25CF ").color(visColor))
                    .append(Component.text(schematicName.take(28)).color(NamedTextColor.WHITE))
                    .append(Component.text(" - $author").color(NamedTextColor.GRAY))

                val tooltip = Component.empty()
                    .append(Component.text(schematicName).color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                    .append(Component.text("\nby $author").color(NamedTextColor.GRAY))
                    .append(Component.text("\n"))
                    .append(Component.text(if (isPublic) "Public" else "Private").color(visColor))
                    .append(Component.text(" \u2022 $downloads downloads").color(NamedTextColor.GRAY))
                    .let {
                        if (hasSize) {
                            it.append(Component.text("\n${width}x${height}x${length} blocks").color(NamedTextColor.GRAY))
                        } else it
                    }
                    .append(Component.text("\n\nClick to download").color(NamedTextColor.YELLOW))

                actionButtons.add(
                    ActionButton.builder(buttonLabel)
                        .tooltip(tooltip)
                        .width(280)
                        .action(DialogAction.staticAction(ClickEvent.runCommand("/schematio download $id")))
                        .build()
                )
            }
        }

        // Pagination
        val baseCmd = buildDialogCommand(options.search, options.visibility, options.sort, options.order)

        if (currentPage > 1) {
            actionButtons.add(
                ActionButton.builder(Component.text("\u25C0 Previous").color(NamedTextColor.AQUA))
                    .width(90)
                    .action(DialogAction.staticAction(ClickEvent.runCommand("$baseCmd ${currentPage - 1}")))
                    .build()
            )
        }

        actionButtons.add(
            ActionButton.builder(Component.text("Page $currentPage of $lastPage").color(NamedTextColor.GRAY))
                .width(100)
                .action(DialogAction.staticAction(ClickEvent.suggestCommand("$baseCmd ")))
                .build()
        )

        if (currentPage < lastPage) {
            actionButtons.add(
                ActionButton.builder(Component.text("Next \u25B6").color(NamedTextColor.AQUA))
                    .width(90)
                    .action(DialogAction.staticAction(ClickEvent.runCommand("$baseCmd ${currentPage + 1}")))
                    .build()
            )
        }

        // Build dialog
        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("Browse Schematics"))
            .body(bodyElements)
            .inputs(inputs)
            .canCloseWithEscape(true)
            .build()

        try {
            val dialog = Dialog.create { builder ->
                builder.empty()
                    .base(dialogBase)
                    .type(DialogType.multiAction(actionButtons, null, 1))
            }

            player.showDialog(dialog)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to show dialog: ${e.message}")
            player.audience().sendMessage(
                Component.text("Failed to open dialog. Your client may not support this feature.").color(NamedTextColor.RED)
            )
            // Fall back to chat mode
            player.audience().sendMessage(Component.text("Falling back to chat mode...").color(NamedTextColor.GRAY))
            executeChatMode(player, arrayOf(options.search ?: "", options.page.toString()))
        }
    }

    private fun buildDialogCommand(search: String?, visibility: Visibility, sort: SortField, order: SortOrder): String {
        val parts = mutableListOf("/schematio list --dialog")
        if (!search.isNullOrBlank()) {
            parts.add(search)
        }
        if (visibility != Visibility.ALL) {
            parts.add("--visibility=${visibility.apiValue}")
        }
        if (sort != SortField.CREATED_AT) {
            parts.add("--sort=${sort.apiValue}")
        }
        if (order != SortOrder.DESC) {
            parts.add("--order=${order.apiValue}")
        }
        return parts.joinToString(" ")
    }

    // ===========================================
    // SHARED UTILITIES
    // ===========================================

    private fun handleFetchError(player: Player, apiService: SchematicsApiService, result: SchematicsApiService.FetchResult.Error) {
        val audience = player.audience()
        when (apiService.categorizeError(result.message)) {
            SchematicsApiService.ErrorCategory.NOT_CONNECTED -> {
                audience.sendMessage(Component.text("Cannot browse schematics - not connected to schemat.io").color(NamedTextColor.RED))
                audience.sendMessage(Component.text("Configure a community token in config.yml").color(NamedTextColor.GRAY))
            }
            SchematicsApiService.ErrorCategory.API_UNAVAILABLE -> {
                audience.sendMessage(Component.text("schemat.io API is currently unavailable").color(NamedTextColor.RED))
                if (result.enteredOffline) {
                    audience.sendMessage(Component.text("Entered offline mode - will retry automatically").color(NamedTextColor.GRAY))
                } else {
                    audience.sendMessage(Component.text("Please try again later").color(NamedTextColor.GRAY))
                }
            }
            SchematicsApiService.ErrorCategory.OTHER -> {
                audience.sendMessage(Component.text("Error loading schematics").color(NamedTextColor.RED))
                audience.sendMessage(Component.text(result.message.take(50)).color(NamedTextColor.GRAY))
                plugin.logger.warning("Error fetching schematics: ${result.message}")
            }
        }
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()

        val partial = args.last().lowercase()
        val suggestions = mutableListOf<String>()

        // Suggest flags
        if (partial.startsWith("--")) {
            if ("--chat".startsWith(partial)) suggestions.add("--chat")
            if ("--dialog".startsWith(partial)) suggestions.add("--dialog")
            if ("--visibility=".startsWith(partial)) {
                suggestions.addAll(listOf("--visibility=all", "--visibility=public", "--visibility=private"))
            }
            if ("--sort=".startsWith(partial)) {
                suggestions.addAll(listOf("--sort=created_at", "--sort=updated_at", "--sort=name", "--sort=downloads"))
            }
            if ("--order=".startsWith(partial)) {
                suggestions.addAll(listOf("--order=desc", "--order=asc"))
            }
        } else if (partial.isEmpty() || partial.startsWith("-")) {
            suggestions.addAll(listOf("-c", "-d", "--visibility=", "--sort=", "--order="))
        }

        return suggestions.filter { it.startsWith(partial, ignoreCase = true) }
    }
}
