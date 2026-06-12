package io.schemat.schematioConnector.ui.components

import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.UIElement
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Vector
import org.joml.Matrix4f

/**
 * Represents a single tab definition
 */
data class TabDefinition(
    val id: String,
    val label: String,
    val icon: String? = null  // Optional icon prefix like "⚙ " or "📊 "
)

/**
 * Tabs component - horizontal tab bar for switching between content sections.
 *
 * Note: This component only manages the tab bar UI. Content switching should be
 * handled by the parent via the onTabChange callback.
 */
class TabsElement(
    ui: FloatingUI,
    localOffset: Vector,
    val tabs: List<TabDefinition>,
    val tabWidth: Float = 0.8f,
    val tabHeight: Float = 0.3f,
    val gap: Float = 0.05f,
    var selectedIndex: Int = 0,
    val onTabChange: (Int, TabDefinition) -> Unit = { _, _ -> }
) : UIElement(
    ui = ui,
    localOffset = localOffset,
    isInteractive = false,  // The element itself isn't interactive, but tab buttons are
    hitboxSize = 0.0
) {
    // Individual tab button displays
    private val tabBackgrounds = mutableListOf<org.bukkit.entity.BlockDisplay>()
    private val tabLabels = mutableListOf<TextDisplay>()

    // Track which tab is being hovered (for visual feedback)
    private var hoveredTabIndex: Int = -1

    // Child tab button elements for hit detection
    private val tabButtons = mutableListOf<TabButtonElement>()

    val totalWidth: Float
        get() = tabs.size * tabWidth + (tabs.size - 1) * gap

    override fun spawn(location: Location) {
        val world = location.world ?: return

        // Calculate starting X position (centered around localOffset)
        val startX = -totalWidth / 2 + tabWidth / 2

        tabs.forEachIndexed { index, tab ->
            val tabX = startX + index * (tabWidth + gap)

            // Create tab background
            val bgLocation = location.clone()
            val background = world.spawn(bgLocation, org.bukkit.entity.BlockDisplay::class.java) { display ->
                display.brightness = Display.Brightness(15, 15)
                updateTabBackground(display, index)

                display.setTransformationMatrix(
                    ui.buildUIElementMatrix(
                        localOffsetX = tabX - tabWidth / 2,
                        localOffsetY = -tabHeight / 2,
                        localOffsetZ = 0f,
                        scaleX = tabWidth,
                        scaleY = tabHeight,
                        scaleZ = 0.02f
                    )
                )
            }
            tabBackgrounds.add(background)

            // Create tab label at a position in front of the tab
            val labelText = if (tab.icon != null) "${tab.icon}${tab.label}" else tab.label
            val labelPosition = ui.calculatePosition(
                localOffset.x + tabX,
                localOffset.y,
                localOffset.z - 0.15  // In front of tab (toward player)
            )
            val label = world.spawn(labelPosition, TextDisplay::class.java) { display ->
                display.text(Component.text(labelText))
                display.billboard = Display.Billboard.CENTER  // Face the player
                display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
                display.isSeeThrough = false
                display.brightness = Display.Brightness(15, 15)
                display.alignment = TextDisplay.TextAlignment.CENTER
                display.viewRange = 1.0f

                // Simple scale transformation
                val textScale = 0.35f
                val scaleMatrix = Matrix4f().scaling(textScale, textScale, textScale)
                display.setTransformationMatrix(scaleMatrix)
            }
            tabLabels.add(label)

            // Create invisible hit detection button
            val tabButton = TabButtonElement(
                ui = ui,
                localOffset = Vector(localOffset.x + tabX, localOffset.y, localOffset.z),
                tabIndex = index,
                tabWidth = tabWidth,
                tabHeight = tabHeight,
                parent = this
            )
            tabButton.spawn(location)
            tabButtons.add(tabButton)
        }

        updateAllTabs()
    }

    private fun updateTabBackground(display: org.bukkit.entity.BlockDisplay, index: Int) {
        val material = when {
            index == selectedIndex -> Material.LIGHT_BLUE_CONCRETE
            index == hoveredTabIndex -> Material.CYAN_CONCRETE
            else -> Material.GRAY_CONCRETE
        }
        display.block = material.createBlockData()
    }

    private fun updateAllTabs() {
        tabBackgrounds.forEachIndexed { index, display ->
            updateTabBackground(display, index)
        }

        // Update label colors based on selection
        tabLabels.forEachIndexed { index, display ->
            val color = if (index == selectedIndex) "§f" else "§7"
            val tab = tabs[index]
            val labelText = if (tab.icon != null) "$color${tab.icon}${tab.label}" else "$color${tab.label}"
            display.text(Component.text(labelText))
        }
    }

    /**
     * Called when a tab is clicked
     */
    internal fun onTabClicked(index: Int) {
        if (index != selectedIndex && index in tabs.indices) {
            selectedIndex = index
            updateAllTabs()
            onTabChange(index, tabs[index])
        }
    }

    /**
     * Called when hover state changes on a tab
     */
    internal fun onTabHoverChanged(index: Int, isHovered: Boolean) {
        hoveredTabIndex = if (isHovered) index else -1
        if (index in tabBackgrounds.indices) {
            updateTabBackground(tabBackgrounds[index], index)
        }
    }

    /**
     * Select a tab programmatically
     */
    fun selectTab(index: Int) {
        if (index in tabs.indices) {
            selectedIndex = index
            updateAllTabs()
            onTabChange(index, tabs[index])
        }
    }

    /**
     * Get the tab buttons for registration with the UI
     */
    fun getTabButtons(): List<TabButtonElement> = tabButtons.toList()

    override fun destroy() {
        tabBackgrounds.forEach { it.remove() }
        tabLabels.forEach { it.remove() }
        tabButtons.forEach { it.destroy() }
        tabBackgrounds.clear()
        tabLabels.clear()
        tabButtons.clear()
    }

    override fun onHoverChanged() {
        // Main element doesn't handle hover - individual tabs do
    }
}

/**
 * Internal helper element for tab hit detection
 */
class TabButtonElement(
    ui: FloatingUI,
    localOffset: Vector,
    val tabIndex: Int,
    tabWidth: Float,
    tabHeight: Float,
    private val parent: TabsElement
) : UIElement(
    ui = ui,
    localOffset = localOffset,
    isInteractive = true,
    hitboxSize = 0.0,
    hitboxWidth = tabWidth.toDouble(),
    hitboxHeight = tabHeight.toDouble()
) {
    init {
        this.onClick = {
            parent.onTabClicked(tabIndex)
        }
    }

    override fun spawn(location: Location) {
        // No visual elements - just for hit detection
    }

    override fun destroy() {
        // Nothing to destroy
    }

    override fun onHoverChanged() {
        parent.onTabHoverChanged(tabIndex, isHovered)
    }
}
