package io.schemat.schematioConnector.ui.page

import com.google.gson.JsonObject
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.layout.CrossAxisAlignment
import io.schemat.schematioConnector.ui.layout.Layout
import io.schemat.schematioConnector.ui.layout.MainAxisAlignment
import io.schemat.schematioConnector.ui.layout.Padding
import io.schemat.schematioConnector.utils.safeGetArray
import io.schemat.schematioConnector.utils.safeGetBoolean
import io.schemat.schematioConnector.utils.safeGetString
import io.schemat.schematioConnector.utils.asJsonObjectOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Page content that displays detailed information about a schematic.
 *
 * Shows:
 * - Name and author
 * - Description
 * - Tags
 * - Public/private status
 * - Download button
 * - Back button
 */
class SchematicDetailContent(
    private val plugin: SchematioConnector,
    private val player: Player,
    private val schematic: JsonObject
) : PageContent("schematic_detail", schematic.safeGetString("name") ?: "Details") {

    companion object {
        const val PADDING = 0.08f
        const val GAP = 0.05f
        const val HEADER_HEIGHT = 0.3f
        const val ROW_HEIGHT = 0.18f
        const val BUTTON_HEIGHT = 0.22f
    }

    // Extract schematic data using safe JSON access
    private val shortId = schematic.safeGetString("short_id") ?: ""
    private val name = schematic.safeGetString("name") ?: "Unknown"
    private val description = schematic.safeGetString("description") ?: "No description"
    private val isPublic = schematic.safeGetBoolean("is_public")
    private val authors = schematic.safeGetArray("authors")
        .mapNotNull { it.asJsonObjectOrNull()?.safeGetString("last_seen_name") }
        .joinToString(", ")
        .ifEmpty { "Unknown" }
    private val tags = schematic.safeGetArray("tags")
        .mapNotNull { it.asJsonObjectOrNull()?.safeGetString("name") }
        .filter { it.isNotBlank() }
        .joinToString(", ")
        .ifEmpty { "None" }

    override fun buildLayout(width: Float, height: Float): Layout {
        return Layout(width = width, height = height).apply {
            column(
                "root",
                padding = Padding.all(PADDING),
                gap = GAP,
                crossAxisAlignment = CrossAxisAlignment.Stretch
            ) {
                // Header with back button and title
                row("header", height = HEADER_HEIGHT, gap = GAP, crossAxisAlignment = CrossAxisAlignment.Center) {
                    leaf("back_btn", width = 0.3f, height = HEADER_HEIGHT * 0.8f)
                    leaf("title", flexGrow = 1f, height = HEADER_HEIGHT)
                }

                // Info rows
                leaf("name_row", height = ROW_HEIGHT)
                leaf("author_row", height = ROW_HEIGHT)
                leaf("status_row", height = ROW_HEIGHT)
                leaf("tags_row", height = ROW_HEIGHT)

                // Description (taller)
                leaf("desc_label", height = ROW_HEIGHT * 0.7f)
                leaf("description", height = ROW_HEIGHT * 2)

                // Spacer
                spacer(flexGrow = 1f)

                // Action buttons
                row("actions", height = BUTTON_HEIGHT, gap = GAP, mainAxisAlignment = MainAxisAlignment.Center) {
                    leaf("download_btn", width = 0.9f, height = BUTTON_HEIGHT)
                    leaf("browser_btn", width = 0.9f, height = BUTTON_HEIGHT)
                }
            }
            compute()
        }
    }

    override fun render(ui: FloatingUI, renderer: LayoutRenderer, bounds: PageBounds) {
        // Header
        renderBackButton(ui, renderer)
        renderer.renderPanel("title", Material.BLUE_CONCRETE, 0.01)?.let { elements.add(it) }
        renderer.renderAlignedLabel("title", "§f§lSchematic Details", 0.25f, -0.02)?.let { elements.add(it) }

        // Name row
        renderer.renderPanel("name_row", Material.GRAY_CONCRETE, 0.01)?.let { elements.add(it) }
        val displayName = if (name.length > 30) name.take(28) + ".." else name
        renderer.renderAlignedLabel("name_row", "§7Name: §f$displayName", 0.18f, -0.02)?.let { elements.add(it) }

        // Author row
        renderer.renderPanel("author_row", Material.GRAY_CONCRETE, 0.01)?.let { elements.add(it) }
        val displayAuthors = if (authors.length > 30) authors.take(28) + ".." else authors
        renderer.renderAlignedLabel("author_row", "§7By: §f$displayAuthors", 0.18f, -0.02)?.let { elements.add(it) }

        // Status row
        renderer.renderPanel("status_row", Material.GRAY_CONCRETE, 0.01)?.let { elements.add(it) }
        val statusText = if (isPublic) "§aPublic" else "§cPrivate"
        renderer.renderAlignedLabel("status_row", "§7Status: $statusText", 0.18f, -0.02)?.let { elements.add(it) }

        // Tags row
        renderer.renderPanel("tags_row", Material.GRAY_CONCRETE, 0.01)?.let { elements.add(it) }
        val displayTags = if (tags.length > 30) tags.take(28) + ".." else tags
        renderer.renderAlignedLabel("tags_row", "§7Tags: §f$displayTags", 0.18f, -0.02)?.let { elements.add(it) }

        // Description label
        renderer.renderAlignedLabel("desc_label", "§7Description:", 0.16f, -0.02)?.let { elements.add(it) }

        // Description content
        renderer.renderPanel("description", Material.BLACK_CONCRETE, 0.01)?.let { elements.add(it) }
        val displayDesc = if (description.length > 80) description.take(77) + "..." else description
        renderer.renderAlignedLabel("description", "§f$displayDesc", 0.14f, -0.02)?.let { elements.add(it) }

        // Download button
        renderActionButton(ui, renderer, "download_btn", "§a§lDownload", Material.GREEN_CONCRETE, Material.LIME_CONCRETE) {
            onDownloadClicked()
        }

        // View in Browser button
        renderActionButton(ui, renderer, "browser_btn", "§b§lView Online", Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE) {
            onViewInBrowserClicked()
        }
    }

    private fun renderBackButton(ui: FloatingUI, renderer: LayoutRenderer) {
        val pos = renderer.getElementPosition("back_btn") ?: return

        val panel = ui.addInteractivePanel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = 0.01,
            width = pos.width,
            height = pos.height,
            material = Material.RED_CONCRETE,
            hoverMaterial = Material.ORANGE_CONCRETE
        ) {
            onBackClicked()
        }
        elements.add(panel)

        elements.add(ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = -0.02,
            text = "§f< Back",
            scale = 0.18f
        ))
    }

    private fun renderActionButton(
        ui: FloatingUI,
        renderer: LayoutRenderer,
        elementId: String,
        label: String,
        material: Material,
        hoverMaterial: Material,
        onClick: () -> Unit
    ) {
        val pos = renderer.getElementPosition(elementId) ?: return

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

        elements.add(ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = -0.02,
            text = label,
            scale = 0.2f
        ))
    }

    private fun onBackClicked() {
        page?.manager?.navigateBack()
    }

    private fun onDownloadClicked() {
        // Close UI and download
        page?.manager?.let { manager ->
            // Destroy the UI
            manager.destroy()
        }
        player.sendMessage("§7Downloading §f$name§7...")
        player.performCommand("schematio download $shortId")
    }

    private fun onViewInBrowserClicked() {
        // Close UI and send clickable link
        page?.manager?.let { manager ->
            manager.destroy()
        }
        val schematicUrl = "${plugin.baseUrl}/schematics/$shortId"

        // Cast player to Audience (Paper API provides this directly)
        val audience = player as net.kyori.adventure.audience.Audience

        audience.sendMessage(
            Component.text("View schematic: ").color(NamedTextColor.GRAY)
                .append(Component.text(schematicUrl)
                    .color(NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(schematicUrl))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to open in browser").color(NamedTextColor.GREEN))))
        )
    }
}
