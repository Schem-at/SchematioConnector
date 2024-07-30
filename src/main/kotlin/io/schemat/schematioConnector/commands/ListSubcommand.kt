package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.entity.Player
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import com.google.gson.Gson
import com.google.gson.JsonObject
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.gui.PagedGui
import xyz.xenondevs.invui.gui.structure.Markers
import xyz.xenondevs.invui.item.Click
import xyz.xenondevs.invui.item.builder.ItemBuilder
import xyz.xenondevs.invui.item.impl.SimpleItem
import xyz.xenondevs.invui.window.Window
import xyz.xenondevs.invui.window.AnvilWindow
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.invui.item.impl.AbstractItem
import xyz.xenondevs.invui.item.impl.controlitem.PageItem
import java.net.URLEncoder
import java.util.function.Consumer

class ListSubcommand(private val plugin: SchematioConnector) : Subcommand {
    private val SCHEMATICS_ENDPOINT = "/schematics"
    private val gson = Gson()
    private var currentPagedGui: PagedGui<Item>? = null

    override fun execute(player: Player, args: Array<out String>): Boolean {
        currentPagedGui = null
        openSchematicsListGui(player)
        player.sendMessage("Opening schematics list GUI...")
        return true
    }

    private fun openSchematicsListGui(player: Player, search: String? = null) {
        runBlocking {
            try {
                val schematics = fetchSchematics(search)
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

    private suspend fun urlEncode(search: String): String {
        return withContext(Dispatchers.IO) {
            URLEncoder.encode(search, "UTF-8")
        }
    }

    private suspend fun fetchSchematics(search: String? = null): List<JsonObject> {
        val queryParams = mutableMapOf<String, String>()
        if (search != null && search != "Enter search term") {
            queryParams["search"] = urlEncode(search)
        }

        val response = plugin.httpUtil.sendGetRequest("$SCHEMATICS_ENDPOINT?${queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }}")

        if (response == null) {
            throw Exception("Error fetching schematics")
        }

        val jsonResponse = gson.fromJson(response, JsonObject::class.java)
        return jsonResponse.getAsJsonArray("data").map { it.asJsonObject }
    }

    private fun createPagedGui(schematics: List<JsonObject>, player: Player): PagedGui<Item> {
        val border = SimpleItem(ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).setDisplayName(""))

        val items = createSchematicItems(schematics)

        return PagedGui.items()
            .setStructure(
                "# # # # # # # # #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# # # <  # > # # #"
            )
            .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL) // where paged items should be put
            .addIngredient('#', border)
            .addIngredient('<', BackItem())
            .addIngredient('>', ForwardItem())
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
                "Authors: $authors"
            )

        return SimpleItem(itemProvider, Consumer { click: Click ->
            click.player.performCommand("schematio download $id")
        })
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

    class BackItem : PageItem(false) {
        override fun getItemProvider(gui: PagedGui<*>): ItemProvider {
            val builder = ItemBuilder(Material.RED_STAINED_GLASS_PANE)
            builder.setDisplayName("Previous page")
                .addLoreLines(
                    if (gui.hasPreviousPage())
                        "Go to page " + gui.currentPage + "/" + gui.pageAmount
                    else "You can't go further back"
                )
            return builder
        }
    }

    class ForwardItem : PageItem(true) {
        override fun getItemProvider(gui: PagedGui<*>): ItemProvider {
            val builder = ItemBuilder(Material.GREEN_STAINED_GLASS_PANE)
            builder.setDisplayName("Next page")
                .addLoreLines(
                    if (gui.hasNextPage())
                        "Go to page " + (gui.currentPage + 2) + "/" + gui.pageAmount
                    else "You can't go further forward"
                )
            return builder
        }
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return emptyList()
    }
}
