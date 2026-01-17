package io.schemat.schematioConnector.commands

import com.google.gson.JsonObject
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.ValidationResult
import io.schemat.schematioConnector.utils.asJsonObjectOrNull
import io.schemat.schematioConnector.utils.parseJsonSafe
import io.schemat.schematioConnector.utils.safeGetArray
import io.schemat.schematioConnector.utils.safeGetBoolean
import io.schemat.schematioConnector.utils.safeGetInt
import io.schemat.schematioConnector.utils.safeGetObject
import io.schemat.schematioConnector.utils.safeGetString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.gui.PagedGui
import xyz.xenondevs.invui.gui.structure.Markers
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.invui.item.builder.ItemBuilder
import xyz.xenondevs.invui.item.impl.AbstractItem
import xyz.xenondevs.invui.item.impl.SimpleItem
import xyz.xenondevs.invui.item.impl.controlitem.PageItem
import xyz.xenondevs.invui.window.AnvilWindow
import xyz.xenondevs.invui.window.Window
import java.net.URLEncoder

/**
 * Inventory-based UI for browsing and searching schematics on schemat.io.
 *
 * This command opens an inventory GUI with pagination and search functionality.
 * Uses the InvUI library for the inventory interface.
 *
 * Usage: /schematio list-inv [search term]
 *
 * Requires:
 * - schematio.list permission (base permission for listing)
 * - schematio.tier.inventory permission (tier permission for inventory UI)
 * - WorldEdit plugin for clipboard operations
 *
 * @property plugin The main plugin instance
 */
class ListInvSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val SCHEMATICS_ENDPOINT = "/schematics"
    private var currentPagedGui: PagedGui<Item>? = null
    private var currentPage = 1
    private var lastPage = 1
    private var currentSearch: String? = null

    override val name = "list-inv"
    override val permission = "schematio.list"
    override val description = "Browse schematics in an inventory GUI"

    /** The tier permission required for this UI variant */
    val tierPermission = "schematio.tier.inventory"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Check tier permission
        if (!player.hasPermission(tierPermission)) {
            audience.sendMessage(
                Component.text("You don't have permission for inventory UI. Try ")
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

        // Validate search if provided via args
        val searchArg = if (args.isNotEmpty()) args.joinToString(" ") else null
        val searchResult = InputValidator.validateSearchQuery(searchArg)
        if (searchResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(searchResult.message).color(NamedTextColor.RED))
            return true
        }

        // Note: Rate limiting moved to API call only
        currentPagedGui = null
        currentPage = 1
        currentSearch = null
        audience.sendMessage(Component.text("Loading schematics...").color(NamedTextColor.GRAY))
        openSchematicsListGui(player)
        return true
    }

    private fun openSchematicsListGui(player: Player, search: String? = null, page: Int = 1) {
        val httpUtil = plugin.httpUtil ?: return
        val cache = plugin.schematicCache
        val offlineMode = plugin.offlineMode

        // Build cache key from query parameters
        val cacheKey = buildCacheKey(search, page)

        // Check cache first - cache hits bypass rate limiting for fast UX
        val cachedResult = cache.getListings(cacheKey)
        if (cachedResult != null) {
            // Cache hit - display immediately without rate limiting
            displaySchematicResults(player, page, cachedResult.schematics, cachedResult.meta)
            return
        }

        // Check if we should skip API call (offline mode with backoff)
        if (offlineMode.shouldSkipApiCall()) {
            // Try stale cache
            val staleResult = cache.getListingsStale(cacheKey)
            if (staleResult != null) {
                val (data, ageMs) = staleResult
                val ageMinutes = (ageMs / 60000).toInt()
                player.audience().sendMessage(Component.text("Using cached data (${ageMinutes}m old)").color(NamedTextColor.YELLOW))
                displaySchematicResults(player, page, data.schematics, data.meta)
                return
            }

            // No cached data available
            val waitTime = offlineMode.getTimeUntilRetrySeconds()
            player.audience().sendMessage(Component.text("API is offline. Retry in ${waitTime}s").color(NamedTextColor.RED))
            return
        }

        // Rate limit only applies to actual API calls
        val rateLimitResult = plugin.rateLimiter.tryAcquire(player.uniqueId)
        if (rateLimitResult == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            player.audience().sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return
        }

        // Run fetch asynchronously to avoid blocking the main thread
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            offlineMode.recordAttempt()

            try {
                val (schematics, meta) = runBlocking { fetchSchematics(search, page) }

                // Cache the successful result
                cache.putListings(cacheKey, schematics, meta)
                offlineMode.recordSuccess()

                // Switch back to main thread for Bukkit/InvUI operations
                plugin.server.scheduler.runTask(plugin, Runnable {
                    displaySchematicResults(player, page, schematics, meta)
                })
            } catch (e: Exception) {
                // Record failure for offline mode
                val enteredOffline = offlineMode.recordFailure()

                // Try stale cache as fallback
                val staleResult = cache.getListingsStale(cacheKey)

                // Handle errors and notify on main thread
                plugin.server.scheduler.runTask(plugin, Runnable {
                    val audience = player.audience()

                    if (staleResult != null) {
                        // Show stale data with warning
                        val (data, ageMs) = staleResult
                        val ageMinutes = (ageMs / 60000).toInt()
                        audience.sendMessage(Component.text("API unavailable - showing cached data (${ageMinutes}m old)").color(NamedTextColor.YELLOW))
                        displaySchematicResults(player, page, data.schematics, data.meta)
                    } else {
                        // No cache available - show error
                        val errorMsg = e.message ?: "Unknown error"
                        when {
                            errorMsg.contains("API not connected") -> {
                                audience.sendMessage(Component.text("Cannot browse schematics - not connected to schemat.io").color(NamedTextColor.RED))
                                audience.sendMessage(Component.text("Configure a community token in config.yml").color(NamedTextColor.GRAY))
                            }
                            errorMsg.contains("Connection refused") ||
                            errorMsg.contains("timed out") ||
                            errorMsg.contains("No route to host") -> {
                                audience.sendMessage(Component.text("schemat.io API is currently unavailable").color(NamedTextColor.RED))
                                if (enteredOffline) {
                                    audience.sendMessage(Component.text("Entered offline mode - will retry automatically").color(NamedTextColor.GRAY))
                                } else {
                                    audience.sendMessage(Component.text("Please try again later").color(NamedTextColor.GRAY))
                                }
                            }
                            else -> {
                                audience.sendMessage(Component.text("Error loading schematics").color(NamedTextColor.RED))
                                audience.sendMessage(Component.text(errorMsg.take(50)).color(NamedTextColor.GRAY))
                                plugin.logger.warning("Error fetching schematics: $errorMsg")
                            }
                        }
                    }
                })
            }
        })
    }

    private fun buildCacheKey(search: String?, page: Int): String {
        val normalizedSearch = if (search == "Enter search term") null else search
        return "list-inv:search=${normalizedSearch ?: ""}:page=$page"
    }

    private fun displaySchematicResults(player: Player, page: Int, schematics: List<JsonObject>, meta: JsonObject) {
        // Use safe JSON parsing
        lastPage = meta.safeGetInt("last_page", 1)
        currentPage = page
        currentSearch = currentSearch

        val pagedGui = createPagedGui(schematics, player)

        if (currentPagedGui != null) {
            // Update the existing GUI content
            currentPagedGui?.setContent(createSchematicItems(schematics))
        } else {
            // Create a new anvil split window with the initial content
            currentPagedGui = pagedGui
            openSearchAnvilGui(player, pagedGui)
        }
    }

    private suspend fun fetchSchematics(search: String? = null, page: Int = 1): Pair<List<JsonObject>, JsonObject> {
        val queryParams = mutableMapOf<String, String>()
        if (search != null && search != "Enter search term") {
            queryParams["search"] = urlEncode(search)
        }
        queryParams["page"] = page.toString()

        val httpUtil = plugin.httpUtil ?: throw Exception("API not connected - no token configured")
        val response = httpUtil.sendGetRequest("$SCHEMATICS_ENDPOINT?${queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }}")

        if (response == null) {
            throw Exception("Connection refused - API may be unavailable")
        }

        val jsonResponse = parseJsonSafe(response)
            ?: throw Exception("Invalid JSON response from API")

        // Use safe JSON access
        val dataArray = jsonResponse.safeGetArray("data")
            .mapNotNull { it.asJsonObjectOrNull() }

        val meta = jsonResponse.safeGetObject("meta")
            ?: JsonObject()

        return Pair(dataArray, meta)
    }

    private fun createPagedGui(schematics: List<JsonObject>, player: Player): PagedGui<Item> {
        val border = SimpleItem(ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).setDisplayName(""))

        val items = createSchematicItems(schematics)

        return PagedGui.items()
            .setStructure(
                "# # # # # # # # #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# # # < S # > # #"
            )
            .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
            .addIngredient('#', border)
            .addIngredient('<', BackItem())
            .addIngredient('>', ForwardItem())
            .addIngredient('S', SearchItem(this))
            .setContent(items)
            .build()
    }

    private fun createSchematicItems(schematics: List<JsonObject>): List<Item> {
        return schematics.map { createSchematicItem(it) }
    }

    private fun createSimpleItem(material: Material, name: String): SimpleItem {
        val itemProvider = ItemBuilder(material).setDisplayName(name)
        return SimpleItem(itemProvider)
    }

    private fun createSchematicItem(schematic: JsonObject): Item {
        // Use safe JSON access
        val id = schematic.safeGetString("short_id") ?: "unknown"
        val name = schematic.safeGetString("name") ?: "Unknown"
        val isPublic = schematic.safeGetBoolean("is_public", false)
        val authors = schematic.safeGetArray("authors")
            .mapNotNull { it.asJsonObjectOrNull()?.safeGetString("last_seen_name") }
            .joinToString(", ")
            .ifEmpty { "Unknown" }

        val itemProvider = ItemBuilder(Material.PAPER)
            .setDisplayName(name)
            .addLoreLines(
                "ID: $id",
                "Public: ${if (isPublic) "Yes" else "No"}",
                "Authors: $authors",
                "§eClick to view details"
            )

        return SimpleItem(itemProvider) { click ->
            openSchematicDetailsWindow(click.player, schematic)
        }
    }

    private fun openSchematicDetailsWindow(player: Player, schematic: JsonObject) {
        // Use safe JSON access
        val id = schematic.safeGetString("short_id") ?: "unknown"
        val name = schematic.safeGetString("name") ?: "Unknown"
        val isPublic = schematic.safeGetBoolean("is_public", false)
        val authors = schematic.safeGetArray("authors")
            .mapNotNull { it.asJsonObjectOrNull()?.safeGetString("last_seen_name") }
            .joinToString(", ")
            .ifEmpty { "Unknown" }
        val tags = schematic.safeGetArray("tags")
            .mapNotNull { it.asJsonObjectOrNull()?.safeGetString("name") }
            .joinToString(", ")
        val description = schematic.safeGetString("description")?.take(50) ?: "No description"

        val border = SimpleItem(ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).setDisplayName(""))
        val guiBuilder = Gui.normal()
            .setStructure(
                "# # # # # # # # #",
                "# . . I . P D . #",
                "# # # B # # # # #"
            )
            .addIngredient('#', border)
            .addIngredient('.', SimpleItem(ItemBuilder(Material.AIR)))
            .addIngredient('I', SimpleItem(ItemBuilder(Material.PAPER)
                .setDisplayName("§6§l$name")
                .addLoreLines(
                    "",
                    "§7ID: §f$id",
                    "§7Public: §f${if (isPublic) "Yes" else "No"}",
                    "§7Authors: §f$authors",
                    "§7Tags: §f${if (tags.isNotEmpty()) tags else "None"}",
                    "",
                    "§7$description"
                )))
            .addIngredient('D', SimpleItem(ItemBuilder(Material.EMERALD)
                .setDisplayName("§a§lDownload")
                .addLoreLines(
                    "",
                    "§7Click to download §f$name",
                    "§7to your WorldEdit clipboard",
                    "",
                    "§eClick to download"
                )) { click ->
                click.player.closeInventory()
                click.player.performCommand("schematio download $id")
            })
            .addIngredient('B', SimpleItem(ItemBuilder(Material.ARROW)
                .setDisplayName("§c§lBack")
                .addLoreLines(
                    "",
                    "§7Return to schematic list"
                )) { click ->
                plugin.server.scheduler.runTask(plugin, Runnable {
                    openSchematicsListGui(click.player, currentSearch, currentPage)
                })
            })

        // Add "View in Browser" button - sends clickable link to chat
        val schematicUrl = "${plugin.baseUrl}/schematics/$id"
        guiBuilder.addIngredient('P', SimpleItem(ItemBuilder(Material.BOOK)
            .setDisplayName("§b§lView in Browser")
            .addLoreLines(
                "",
                "§7Opens schematic page on schemat.io",
                "",
                "§eClick to get link"
            )) { click ->
            click.player.closeInventory()
            val audience = click.player.audience()
            audience.sendMessage(
                Component.text("View schematic: ").color(NamedTextColor.GRAY)
                    .append(Component.text(schematicUrl)
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(schematicUrl))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to open in browser").color(NamedTextColor.GREEN))))
            )
        })

        val detailsGui = guiBuilder.build()

        Window.single()
            .setViewer(player)
            .setTitle("§8Schematic: $name")
            .setGui(detailsGui)
            .build()
            .open()
    }

    private fun openSearchAnvilGui(player: Player, pagedGui: PagedGui<Item>) {
        val anvilWindow = AnvilWindow.split()
            .setViewer(player)
            .setTitle("Search Schematics")
            .setUpperGui(
                Gui.normal()
                    .setStructure("X D #")
                    .addIngredient('X', createSimpleItem(Material.PAPER, "Enter search term"))
                    .addIngredient('D', createSimpleItem(Material.AIR, "")) // Empty slot for the confirm button
                    .addIngredient('#', createSimpleItem(Material.AIR, ""))
                    .build()
            )
            .setLowerGui(pagedGui)
            .addRenameHandler { searchTerm ->
                // Update the search results dynamically
                openSchematicsListGui(player, searchTerm)
            }
            .build()

        anvilWindow.open()
    }

    class SearchItem(private val listSubcommand: ListInvSubcommand) : AbstractItem() {
        override fun getItemProvider(): ItemProvider {
            return ItemBuilder(Material.COMPASS).setDisplayName("Search Schematics")
        }

        override fun handleClick(clickType: ClickType, player: Player, event: InventoryClickEvent) {
            listSubcommand.openSearchAnvilGui(player, listSubcommand.createPagedGui(emptyList(), player))
        }
    }

    inner class BackItem : PageItem(false) {
        override fun handleClick(clickType: ClickType, player: Player, event: InventoryClickEvent) {
            if (currentPage > 1) {
                openSchematicsListGui(player, currentSearch, currentPage - 1)
            }
        }

        override fun getItemProvider(gui: PagedGui<*>): ItemProvider {
            val builder = ItemBuilder(Material.RED_STAINED_GLASS_PANE)
            builder.setDisplayName("Previous page")
                .addLoreLines(
                    if (currentPage > 1)
                        "Go to page ${currentPage - 1}/$lastPage"
                    else "You can't go further back"
                )
            return builder
        }
    }

    inner class ForwardItem : PageItem(true) {
        override fun handleClick(clickType: ClickType, player: Player, event: InventoryClickEvent) {
            if (currentPage < lastPage) {
                openSchematicsListGui(player, currentSearch, currentPage + 1)
            }
        }

        override fun getItemProvider(gui: PagedGui<*>): ItemProvider {
            val builder = ItemBuilder(Material.GREEN_STAINED_GLASS_PANE)
            builder.setDisplayName("Next page")
                .addLoreLines(
                    if (currentPage < lastPage)
                        "Go to page ${currentPage + 1}/$lastPage"
                    else "You can't go further forward"
                )
            return builder
        }
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }

    private suspend fun urlEncode(search: String): String {
        return withContext(Dispatchers.IO) {
            URLEncoder.encode(search, "UTF-8")
        }
    }
}
