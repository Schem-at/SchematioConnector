package io.schemat.schematioConnector.ui.page

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.layout.CrossAxisAlignment
import io.schemat.schematioConnector.ui.layout.Layout
import io.schemat.schematioConnector.ui.layout.MainAxisAlignment
import io.schemat.schematioConnector.ui.layout.Padding
import io.schemat.schematioConnector.utils.safeGetArray
import io.schemat.schematioConnector.utils.safeGetInt
import io.schemat.schematioConnector.utils.safeGetObject
import io.schemat.schematioConnector.utils.safeGetString
import io.schemat.schematioConnector.utils.asJsonObjectOrNull
import io.schemat.schematioConnector.utils.parseJsonSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bukkit.Material
import org.bukkit.entity.Player
import java.net.URLEncoder

/**
 * Page content that displays a browsable list of schematics from schemat.io.
 *
 * Features:
 * - Paginated schematic list
 * - Search functionality with filter buttons
 * - Click to view schematic details
 */
class ListGuiContent(
    private val plugin: SchematioConnector,
    private val player: Player,
    private var initialSearch: String? = null
) : PageContent("list_gui", "Schematics") {

    private val gson = Gson()
    private val SCHEMATICS_ENDPOINT = "/schematics"

    // Pagination state
    private var currentPage = 1
    private var lastPage = 1
    private var currentSearch: String? = initialSearch
    private var schematics: List<JsonObject> = emptyList()

    // Loading state
    private var isLoading = false
    private var errorMessage: String? = null

    // Items per page
    private val itemsPerPage = 5

    // Search presets
    private val searchPresets = listOf(
        "All" to null,
        "Public" to "public:true",
        "Mine" to "mine:true"
    )
    private var activePresetIndex = 0

    companion object {
        const val ITEM_HEIGHT = 0.18f
        const val ITEM_GAP = 0.03f
        const val HEADER_HEIGHT = 0.22f
        const val FILTER_HEIGHT = 0.18f
        const val FOOTER_HEIGHT = 0.18f
        const val PADDING = 0.05f
    }

    init {
        // Load initial data
        loadSchematics()
    }

    override fun buildLayout(width: Float, height: Float): Layout {
        return Layout(width = width, height = height).apply {
            column(
                "root",
                padding = Padding.all(PADDING),
                gap = ITEM_GAP,
                crossAxisAlignment = CrossAxisAlignment.Stretch
            ) {
                // Header with title
                leaf("header", height = HEADER_HEIGHT)

                // Filter row
                row("filters", height = FILTER_HEIGHT, gap = ITEM_GAP, crossAxisAlignment = CrossAxisAlignment.Center) {
                    for (i in searchPresets.indices) {
                        leaf("filter_$i", width = 0.5f, height = FILTER_HEIGHT * 0.9f)
                    }
                    spacer(flexGrow = 1f)
                    leaf("search_btn", width = 0.6f, height = FILTER_HEIGHT * 0.9f)
                }

                // Content area - list of schematics
                if (isLoading) {
                    leaf("loading", flexGrow = 1f)
                } else if (errorMessage != null) {
                    leaf("error", flexGrow = 1f)
                } else if (schematics.isEmpty()) {
                    leaf("empty", flexGrow = 1f)
                } else {
                    // Simple column of items
                    for (itemIndex in 0 until minOf(schematics.size, itemsPerPage)) {
                        leaf("item_$itemIndex", height = ITEM_HEIGHT)
                    }
                    // Spacer to push footer down if fewer items
                    if (schematics.size < itemsPerPage) {
                        spacer(flexGrow = 1f)
                    }
                }

                // Footer with pagination
                row("footer", height = FOOTER_HEIGHT, crossAxisAlignment = CrossAxisAlignment.Center, mainAxisAlignment = MainAxisAlignment.Center, gap = ITEM_GAP * 2) {
                    leaf("prev_btn", width = 0.25f, height = FOOTER_HEIGHT * 0.85f)
                    leaf("page_info", width = 0.5f, height = FOOTER_HEIGHT * 0.85f)
                    leaf("next_btn", width = 0.25f, height = FOOTER_HEIGHT * 0.85f)
                }
            }
            compute()
        }
    }

    override fun render(ui: FloatingUI, renderer: LayoutRenderer, bounds: PageBounds) {
        // Header
        renderer.renderPanel("header", Material.BLUE_CONCRETE, 0.01)?.let { elements.add(it) }
        val headerText = if (currentSearch.isNullOrBlank()) "§f§lSchematics" else "§f§l${currentSearch?.take(20)}"
        renderer.renderAlignedLabel("header", headerText, 0.22f, -0.02)?.let { elements.add(it) }

        // Filter buttons
        for (i in searchPresets.indices) {
            val (label, _) = searchPresets[i]
            val isActive = i == activePresetIndex
            renderFilterButton(ui, renderer, "filter_$i", label, isActive) {
                activePresetIndex = i
                currentSearch = searchPresets[i].second
                currentPage = 1
                loadSchematics()
            }
        }

        // Search button
        renderSearchButton(ui, renderer)

        // Content area
        if (isLoading) {
            renderer.renderPanel("loading", Material.GRAY_CONCRETE, 0.01)?.let { elements.add(it) }
            renderer.renderAlignedLabel("loading", "§7Loading...", 0.25f, -0.02)?.let { elements.add(it) }
        } else if (errorMessage != null) {
            renderer.renderPanel("error", Material.RED_CONCRETE, 0.01)?.let { elements.add(it) }
            renderer.renderAlignedLabel("error", "§c$errorMessage", 0.18f, -0.02)?.let { elements.add(it) }
        } else if (schematics.isEmpty()) {
            renderer.renderPanel("empty", Material.GRAY_CONCRETE, 0.01)?.let { elements.add(it) }
            renderer.renderAlignedLabel("empty", "§7No schematics found", 0.2f, -0.02)?.let { elements.add(it) }
        } else {
            // Render schematic items
            for (itemIndex in 0 until minOf(schematics.size, itemsPerPage)) {
                val schematic = schematics[itemIndex]
                renderSchematicItem(ui, renderer, "item_$itemIndex", schematic)
            }
        }

        // Footer - pagination buttons
        renderPaginationButton(ui, renderer, "prev_btn", "§c<", currentPage > 1) {
            if (currentPage > 1) {
                currentPage--
                loadSchematics()
            }
        }

        renderer.getElementPosition("page_info")?.let { pos ->
            elements.add(ui.addPanel(
                offsetRight = pos.uiX,
                offsetUp = pos.uiY,
                offsetForward = 0.01,
                width = pos.width,
                height = pos.height,
                material = Material.BLACK_CONCRETE
            ))
            elements.add(ui.addAlignedLabel(
                offsetRight = pos.uiX,
                offsetUp = pos.uiY,
                offsetForward = -0.02,
                text = "§7$currentPage/$lastPage",
                scale = 0.18f
            ))
        }

        renderPaginationButton(ui, renderer, "next_btn", "§a>", currentPage < lastPage) {
            if (currentPage < lastPage) {
                currentPage++
                loadSchematics()
            }
        }
    }

    private fun renderFilterButton(
        ui: FloatingUI,
        renderer: LayoutRenderer,
        elementId: String,
        label: String,
        isActive: Boolean,
        onClick: () -> Unit
    ) {
        val pos = renderer.getElementPosition(elementId) ?: return

        val material = if (isActive) Material.LIGHT_BLUE_CONCRETE else Material.GRAY_CONCRETE
        val hoverMaterial = if (isActive) Material.CYAN_CONCRETE else Material.LIGHT_GRAY_CONCRETE

        val panel = ui.addInteractivePanel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = 0.01,
            width = pos.width,
            height = pos.height,
            material = material,
            hoverMaterial = hoverMaterial
        ) {
            onClick()
        }
        elements.add(panel)

        val textColor = if (isActive) "§f" else "§7"
        elements.add(ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = -0.02,
            text = "$textColor$label",
            scale = 0.14f
        ))
    }

    private fun renderSearchButton(ui: FloatingUI, renderer: LayoutRenderer) {
        val pos = renderer.getElementPosition("search_btn") ?: return

        val panel = ui.addInteractivePanel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = 0.01,
            width = pos.width,
            height = pos.height,
            material = Material.ORANGE_CONCRETE,
            hoverMaterial = Material.YELLOW_CONCRETE
        ) {
            // Prompt player for search term in chat
            player.sendMessage("§6Type your search term in chat (or 'cancel' to cancel):")
            player.sendMessage("§7Current search: §f${currentSearch ?: "none"}")

            // We can't do chat input in 3D UI, so close and use chat
            page?.manager?.destroy()

            // Register a chat listener for this player
            registerSearchListener()
        }
        elements.add(panel)

        elements.add(ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = -0.02,
            text = "§fSearch...",
            scale = 0.14f
        ))
    }

    private fun registerSearchListener() {
        // Create a one-time chat listener
        val listener = object : org.bukkit.event.Listener {
            @Suppress("DEPRECATION")
            @org.bukkit.event.EventHandler
            fun onChat(event: org.bukkit.event.player.AsyncPlayerChatEvent) {
                if (event.player.uniqueId != player.uniqueId) return

                event.isCancelled = true
                val message = event.message

                // Unregister this listener
                org.bukkit.event.HandlerList.unregisterAll(this)

                // Handle on main thread
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (message.equals("cancel", ignoreCase = true)) {
                        player.sendMessage("§7Search cancelled.")
                        // Reopen the list UI
                        player.performCommand("schematio listgui")
                    } else {
                        player.sendMessage("§7Searching for: §f$message")
                        // Reopen with search
                        reopenWithSearch(message)
                    }
                })
            }
        }

        plugin.server.pluginManager.registerEvents(listener, plugin)

        // Auto-unregister after 30 seconds
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            org.bukkit.event.HandlerList.unregisterAll(listener)
        }, 600L)
    }

    private fun reopenWithSearch(searchTerm: String) {
        // Close any existing UI and reopen with search
        io.schemat.schematioConnector.ui.FloatingUI.closeForPlayer(player)

        val eyeLocation = player.eyeLocation
        val direction = eyeLocation.direction.normalize()
        val center = eyeLocation.clone().add(direction.multiply(3.0))

        val ui = io.schemat.schematioConnector.ui.FloatingUI.create(plugin, player, center)
        val bounds = PageBounds(x = -1.5f, y = 1.0f, width = 3.0f, height = 2.0f)
        val manager = PageManager(plugin, ui, player, bounds)

        val content = ListGuiContent(plugin, player, searchTerm)
        manager.showPage(content)
    }

    private fun renderSchematicItem(
        ui: FloatingUI,
        renderer: LayoutRenderer,
        elementId: String,
        schematic: JsonObject
    ) {
        val pos = renderer.getElementPosition(elementId) ?: return

        val name = schematic.safeGetString("name") ?: "Unknown"
        val authors = schematic.safeGetArray("authors")
            .firstOrNull()?.asJsonObjectOrNull()?.safeGetString("last_seen_name")
            ?: "Unknown"

        // Truncate name if too long
        val displayName = if (name.length > 22) name.take(20) + ".." else name

        // Background panel (interactive)
        val panel = ui.addInteractivePanel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = 0.01,
            width = pos.width,
            height = pos.height,
            material = Material.GRAY_CONCRETE,
            hoverMaterial = Material.LIGHT_GRAY_CONCRETE
        ) {
            onSchematicClicked(schematic)
        }
        elements.add(panel)

        // Combined label: name by author
        elements.add(ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = -0.02,
            text = "§f$displayName §7by $authors",
            scale = 0.13f
        ))
    }

    private fun renderPaginationButton(
        ui: FloatingUI,
        renderer: LayoutRenderer,
        elementId: String,
        label: String,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        val pos = renderer.getElementPosition(elementId) ?: return

        val material = if (enabled) Material.BLACK_CONCRETE else Material.GRAY_CONCRETE
        val hoverMaterial = if (enabled) Material.GRAY_CONCRETE else null

        val panel = ui.addInteractivePanel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = 0.01,
            width = pos.width,
            height = pos.height,
            material = material,
            hoverMaterial = hoverMaterial
        ) {
            if (enabled) onClick()
        }
        elements.add(panel)

        elements.add(ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = -0.02,
            text = label,
            scale = 0.22f
        ))
    }

    private fun onSchematicClicked(schematic: JsonObject) {
        // Navigate to detail page instead of downloading directly
        val detailContent = SchematicDetailContent(plugin, player, schematic)
        page?.manager?.navigateTo(detailContent)
    }

    private fun loadSchematics() {
        isLoading = true
        errorMessage = null
        rebuild()

        // Run async fetch
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val result = runBlocking {
                    fetchSchematics(currentSearch, currentPage)
                }
                schematics = result.first
                val meta = result.second
                lastPage = meta.safeGetInt("last_page", 1)
                currentPage = meta.safeGetInt("current_page", currentPage)
                isLoading = false
                errorMessage = null
            } catch (e: Exception) {
                schematics = emptyList()
                isLoading = false
                errorMessage = when {
                    e.message?.contains("API not connected") == true -> "Not connected to API"
                    e.message?.contains("Connection refused") == true -> "API unavailable"
                    else -> e.message?.take(25) ?: "Error loading"
                }
            }

            // Update UI on main thread
            plugin.server.scheduler.runTask(plugin, Runnable {
                rebuild()
            })
        })
    }

    private suspend fun fetchSchematics(search: String? = null, page: Int = 1): Pair<List<JsonObject>, JsonObject> {
        val queryParams = mutableMapOf<String, String>()
        if (!search.isNullOrBlank()) {
            queryParams["search"] = withContext(Dispatchers.IO) {
                URLEncoder.encode(search, "UTF-8")
            }
        }
        queryParams["page"] = page.toString()
        queryParams["per_page"] = itemsPerPage.toString()

        val httpUtil = plugin.httpUtil ?: throw Exception("API not connected - no token configured")
        val response = httpUtil.sendGetRequest("$SCHEMATICS_ENDPOINT?${queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }}")

        if (response == null) {
            throw Exception("Connection refused - API may be unavailable")
        }

        val jsonResponse = parseJsonSafe(response)
            ?: throw Exception("Invalid JSON response from API")
        return Pair(
            jsonResponse.safeGetArray("data").mapNotNull { it.asJsonObjectOrNull() },
            jsonResponse.safeGetObject("meta") ?: JsonObject()
        )
    }

    /**
     * Search for schematics with the given query.
     */
    fun search(query: String?) {
        currentSearch = query
        currentPage = 1
        loadSchematics()
    }

    /**
     * Refresh the current page.
     */
    fun refresh() {
        loadSchematics()
    }
}
