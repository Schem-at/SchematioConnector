package io.schemat.schematioConnector.commands

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.schemat.schematioConnector.SchematioConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import xyz.xenondevs.invui.window.CartographyWindow
import java.net.URLEncoder

class ListSubcommand(private val plugin: SchematioConnector) : Subcommand {
    // ... (rest of your properties are correct)
    private val SCHEMATICS_ENDPOINT = "/schematics"
    private val gson = Gson()
    private var currentPagedGui: PagedGui<Item>? = null
    private var currentPage = 1
    private var lastPage = 1
    private var currentSearch: String? = null

    // Add the required properties from the interface
    override val name = "list"
    override val permission = "schematioconnector.list"
    override val description = "List schematics from schemat.io"

    // ... (rest of your file is correct)
    override fun execute(player: Player, args: Array<out String>): Boolean {
        currentPagedGui = null
        currentPage = 1
        currentSearch = null
        openSchematicsListGui(player)
        player.sendMessage("Opening schematics list GUI...")
        return true
    }

    // The rest of the file stays the same...
    private fun openSchematicsListGui(player: Player, search: String? = null, page: Int = 1) {
        runBlocking {
            try {
                val (schematics, meta) = fetchSchematics(search, page)
                lastPage = meta.getAsJsonPrimitive("last_page").asInt
                currentPage = page
                currentSearch = search

                val pagedGui = createPagedGui(schematics, player)

                if (currentPagedGui != null) {
                    // Update the existing GUI content
                    currentPagedGui?.setContent(createSchematicItems(schematics))
                } else {
                    // Create a new anvil split window with the initial content
                    currentPagedGui = pagedGui
                    openSearchAnvilGui(player, pagedGui)
                }
            } catch (e: Exception) {
                player.sendMessage("An error occurred while fetching schematics.")
                plugin.logger.severe("Error fetching schematics: ${e.message}")
            }
        }
    }

    private suspend fun fetchSchematics(search: String? = null, page: Int = 1): Pair<List<JsonObject>, JsonObject> {
        val queryParams = mutableMapOf<String, String>()
        if (search != null && search != "Enter search term") {
            queryParams["search"] = urlEncode(search)
        }
        queryParams["page"] = page.toString()

        val response = plugin.httpUtil.sendGetRequest("$SCHEMATICS_ENDPOINT?${queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }}")

        if (response == null) {
            throw Exception("Error fetching schematics")
        }

        val jsonResponse = gson.fromJson(response, JsonObject::class.java)
        return Pair(jsonResponse.getAsJsonArray("data").map { it.asJsonObject }, jsonResponse.getAsJsonObject("meta"))
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
        val id = schematic.get("short_id").asString
        val name = schematic.get("name").asString
        val isPublic = schematic.get("is_public").asBoolean
        val authors = schematic.getAsJsonArray("authors").joinToString(", ") { it.asJsonObject.get("last_seen_name").asString }

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
        plugin.logger.info(schematic.toString())
        val id = schematic.get("short_id").asString
        val name = schematic.get("name").asString
        val isPublic = schematic.get("is_public").asBoolean
        val authors = schematic.getAsJsonArray("authors").joinToString(", ") { it.asJsonObject.get("last_seen_name").asString }
        val tags = schematic.getAsJsonArray("tags").joinToString(", ") { it.asJsonObject.get("name").asString }
        val imageUrl = schematic.get("preview_image_url").asString
        val detailsGui = Gui.normal()
            .setStructure(
                "I D"
            )
            .addIngredient('I', SimpleItem(ItemBuilder(Material.REDSTONE)
                .setDisplayName("§6$name")
                .addLoreLines(
                    "§7ID: §f$id",
                    "§7Public: §f${if (isPublic) "Yes" else "No"}",
                    "§7Authors: §f$authors",
                    "§7Tags: §f$tags",
                    "",
                    "§eClick to go back"
                )) { click ->
                openSchematicsListGui(click.player, currentSearch, currentPage)
            })
            .addIngredient('D', SimpleItem(ItemBuilder(Material.HOPPER)
                .setDisplayName("§aDownload Schematic")
                .addLoreLines(
                    "§7Left-click to download §f$name",
                    "§eRight-click to open in browser"
                )) { click ->
                when (click.clickType) {
                    ClickType.LEFT -> click.player.performCommand("schematio download $id")
                    ClickType.RIGHT -> {
                        click.player.sendMessage("§aOpening $name in your browser...")
                        // Here you would send a clickable link in chat
                    }
                    else -> {}
                }
            })
            .build()

        val cartographyWindow = CartographyWindow
            .single()

            .setViewer(player)
            .setGui(detailsGui)
            .setTitle("Schematic: $name")
            .build()

        // TODO: This is slow and also blocks the main thread
        //cartographyWindow.updateMap(
        //    MapPatch(0, 0, 128, 128, plugin.httpUtil.fetchImageAsByteArray(imageUrl))
        //)
        cartographyWindow.open()
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

    class SearchItem(private val listSubcommand: ListSubcommand) : AbstractItem() {
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