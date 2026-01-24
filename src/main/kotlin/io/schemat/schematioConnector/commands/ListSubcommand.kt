package io.schemat.schematioConnector.commands

import com.google.gson.JsonObject
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.SchematicsApiService
import io.schemat.schematioConnector.utils.ValidationResult
import io.schemat.schematioConnector.utils.safeGetArray
import io.schemat.schematioConnector.utils.safeGetBoolean
import io.schemat.schematioConnector.utils.safeGetInt
import io.schemat.schematioConnector.utils.safeGetString
import io.schemat.schematioConnector.utils.asJsonObjectOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Chat-based UI for browsing and searching schematics on schemat.io.
 *
 * This is the default list command that displays schematics in chat
 * with clickable actions for downloading and navigation.
 *
 * Usage: /schematio list [search term] [page]
 *
 * Examples:
 * - /schematio list - Show first page of all schematics
 * - /schematio list castle - Search for "castle"
 * - /schematio list castle 2 - Show page 2 of "castle" search
 *
 * Requires:
 * - schematio.list permission (base permission for listing)
 * - schematio.tier.chat permission (tier permission for chat UI)
 * - WorldEdit plugin for clipboard operations
 *
 * @property plugin The main plugin instance
 */
class ListSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val ITEMS_PER_PAGE = 10
    private val CACHE_KEY_PREFIX = "list"

    override val name = "list"
    override val permission = "schematio.list"
    override val description = "Browse schematics in chat"

    /** The tier permission required for this UI variant */
    val tierPermission = "schematio.tier.chat"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Check tier permission
        if (!player.hasPermission(tierPermission)) {
            audience.sendMessage(
                Component.text("You don't have permission for chat UI.").color(NamedTextColor.RED)
            )
            return true
        }

        // Early check for API availability
        if (plugin.httpUtil == null) {
            audience.sendMessage(Component.text("Cannot browse schematics - not connected to schemat.io").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Configure a community token in config.yml and run /schematio reload").color(NamedTextColor.GRAY))
            return true
        }

        // Parse arguments: [search] [page]
        var search: String? = null
        var page = 1

        if (args.isNotEmpty()) {
            // Check if last arg is a page number
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

        // Note: Rate limiting moved to API call only - cache hits are always allowed
        audience.sendMessage(Component.text("Loading schematics...").color(NamedTextColor.GRAY))
        displaySchematics(player, search, page)
        return true
    }

    private fun displaySchematics(player: Player, search: String?, page: Int) {
        val apiService = plugin.schematicsApiService

        apiService.fetchSchematicsWithCache(
            playerId = player.uniqueId,
            search = search,
            page = page,
            perPage = ITEMS_PER_PAGE,
            cacheKeyPrefix = CACHE_KEY_PREFIX
        ) { result ->
            val audience = player.audience()

            when (result) {
                is SchematicsApiService.FetchResult.Success -> {
                    if (result.staleMinutes > 0) {
                        audience.sendMessage(Component.text("API unavailable - showing cached data").color(NamedTextColor.YELLOW))
                    }
                    displaySchematicResults(player, search, result.schematics, result.meta, result.fromCache, result.staleMinutes)
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

    private fun displaySchematicResults(
        player: Player,
        search: String?,
        schematics: List<JsonObject>,
        meta: JsonObject,
        fromCache: Boolean,
        staleMinutes: Int = 0
    ) {
        val audience = player.audience()

        // Use safe JSON parsing
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

        // Add cache indicator to header
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
            // List schematics with safe JSON access
            for (schematic in schematics) {
                val id = schematic.safeGetString("short_id") ?: continue
                val name = schematic.safeGetString("name") ?: "Unknown"
                val isPublic = schematic.safeGetBoolean("is_public", false)

                // Safe author extraction
                val authorsArray = schematic.safeGetArray("authors")
                val authors = authorsArray
                    .mapNotNull { it.asJsonObjectOrNull()?.safeGetString("last_seen_name") }
                    .take(2)
                    .joinToString(", ")
                    .ifEmpty { "Unknown" }
                val authorsSuffix = if (authorsArray.size > 2) "..." else ""

                // Build schematic line with clickable buttons
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
        val paginationLine = buildPaginationLine(search, currentPage, lastPage)
        audience.sendMessage(paginationLine)

        // Show alternative UI hints if player has permissions
        val alternatives = mutableListOf<Component>()
        if (player.hasPermission("schematio.tier.inventory")) {
            alternatives.add(
                Component.text("/schematio list-inv")
                    .color(NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/schematio list-inv"))
                    .hoverEvent(HoverEvent.showText(Component.text("Open inventory GUI")))
            )
        }
        if (player.hasPermission("schematio.tier.floating")) {
            alternatives.add(
                Component.text("/schematio list-gui")
                    .color(NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/schematio list-gui"))
                    .hoverEvent(HoverEvent.showText(Component.text("Open 3D floating GUI")))
            )
        }
        if (alternatives.isNotEmpty()) {
            var altLine = Component.text("Try: ").color(NamedTextColor.DARK_GRAY)
            alternatives.forEachIndexed { index, component ->
                if (index > 0) {
                    altLine = altLine.append(Component.text(" | ").color(NamedTextColor.DARK_GRAY))
                }
                altLine = altLine.append(component)
            }
            audience.sendMessage(altLine)
        }
    }

    private fun buildPaginationLine(search: String?, currentPage: Int, lastPage: Int): Component {
        val searchParam = if (search != null) "$search " else ""

        // Previous button
        val prevButton = if (currentPage > 1) {
            Component.text("[< Prev]")
                .color(NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/schematio list $searchParam${currentPage - 1}"))
                .hoverEvent(HoverEvent.showText(Component.text("Go to page ${currentPage - 1}")))
        } else {
            Component.text("[< Prev]").color(NamedTextColor.DARK_GRAY)
        }

        // Page indicator
        val pageIndicator = Component.text(" Page $currentPage/$lastPage ").color(NamedTextColor.GRAY)

        // Next button
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

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        // Could add search term suggestions or page numbers
        return emptyList()
    }
}
