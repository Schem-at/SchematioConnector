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
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.SchematicsApiService
import io.schemat.schematioConnector.utils.SchematicsApiService.QueryOptions
import io.schemat.schematioConnector.utils.SchematicsApiService.SortField
import io.schemat.schematioConnector.utils.SchematicsApiService.SortOrder
import io.schemat.schematioConnector.utils.SchematicsApiService.Visibility
import io.schemat.schematioConnector.utils.ValidationResult
import io.schemat.schematioConnector.utils.safeGetArray
import io.schemat.schematioConnector.utils.safeGetBoolean
import io.schemat.schematioConnector.utils.safeGetInt
import io.schemat.schematioConnector.utils.safeGetString
import io.schemat.schematioConnector.utils.asJsonObjectOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Dialog-based UI for browsing and searching schematics on schemat.io.
 *
 * This command uses Minecraft's native Dialog API (1.21.7+) to display
 * an in-game dialog with schematic listings and action buttons.
 *
 * Usage: /schematio list-dialog [search term] [options]
 *
 * Options:
 *   --visibility=all|public|private
 *   --sort=created_at|updated_at|name|downloads
 *   --order=asc|desc
 *   [page number]
 *
 * Requires:
 * - schematio.list permission (base permission for listing)
 * - schematio.tier.dialog permission (tier permission for dialog UI)
 * - WorldEdit plugin for clipboard operations
 * - Minecraft 1.21.7+ client
 *
 * @property plugin The main plugin instance
 */
class ListDialogSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val ITEMS_PER_PAGE = 6
    private val CACHE_KEY_PREFIX = "list-dialog"

    override val name = "list-dialog"
    override val permission = "schematio.list"
    override val description = "Browse schematics in a native dialog"

    /** The tier permission required for this UI variant */
    val tierPermission = "schematio.tier.dialog"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Check tier permission
        if (!player.hasPermission(tierPermission)) {
            audience.sendMessage(
                Component.text("You don't have permission for dialog UI. Try ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("/schematio list").color(NamedTextColor.YELLOW))
            )
            return true
        }

        // Early check for API availability
        if (plugin.httpUtil == null) {
            audience.sendMessage(Component.text("Cannot browse schematics - not connected to schemat.io").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Configure a community token in config.yml and run /schematio reload").color(NamedTextColor.GRAY))
            return true
        }

        // Parse arguments
        val options = parseArgs(args)

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
            perPage = ITEMS_PER_PAGE
        )

        audience.sendMessage(Component.text("Loading schematics...").color(NamedTextColor.GRAY))
        fetchAndShowDialog(player, finalOptions)
        return true
    }

    private fun parseArgs(args: Array<out String>): QueryOptions {
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

        // Inputs - search box with built-in search action
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

        // Search button - applies all filters
        actionButtons.add(
            ActionButton.builder(Component.text("Search").color(NamedTextColor.GREEN))
                .width(200)
                .action(DialogAction.commandTemplate("/schematio list-dialog \$(search) --visibility=\$(visibility) --sort=\$(sort) 1"))
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
                val name = schematic.safeGetString("name") ?: "Unknown"
                val isPublic = schematic.safeGetBoolean("is_public", false)
                val downloads = schematic.safeGetInt("download_count", 0)

                // Get dimensions
                val width = schematic.safeGetInt("width", 0)
                val height = schematic.safeGetInt("height", 0)
                val length = schematic.safeGetInt("length", 0)
                val hasSize = width > 0 && height > 0 && length > 0

                // Get first author
                val authorsArray = schematic.safeGetArray("authors")
                val author = authorsArray
                    .mapNotNull { it.asJsonObjectOrNull()?.safeGetString("last_seen_name") }
                    .firstOrNull() ?: "Unknown"

                // Button label: visibility dot, name, author
                val visColor = if (isPublic) NamedTextColor.GREEN else NamedTextColor.RED
                val buttonLabel = Component.empty()
                    .append(Component.text("\u25CF ").color(visColor))
                    .append(Component.text(name.take(28)).color(NamedTextColor.WHITE))
                    .append(Component.text(" - $author").color(NamedTextColor.GRAY))

                // Tooltip with full details
                val tooltip = Component.empty()
                    .append(Component.text(name).color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
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

        // Pagination at bottom
        val baseCmd = buildCommand(options.search, options.visibility, options.sort, options.order)

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
        }
    }

    private fun buildCommand(search: String?, visibility: Visibility, sort: SortField, order: SortOrder): String {
        val parts = mutableListOf("/schematio list-dialog")
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

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()

        val partial = args.last().lowercase()
        val suggestions = mutableListOf<String>()

        // Suggest options based on prefix
        if (partial.startsWith("--")) {
            if ("--visibility=".startsWith(partial)) {
                suggestions.addAll(listOf("--visibility=all", "--visibility=public", "--visibility=private"))
            }
            if ("--sort=".startsWith(partial)) {
                suggestions.addAll(listOf("--sort=created_at", "--sort=updated_at", "--sort=name", "--sort=downloads"))
            }
            if ("--order=".startsWith(partial)) {
                suggestions.addAll(listOf("--order=desc", "--order=asc"))
            }
        } else if (partial.isEmpty()) {
            suggestions.addAll(listOf("--visibility=", "--sort=", "--order="))
        }

        return suggestions.filter { it.startsWith(partial, ignoreCase = true) }
    }
}
