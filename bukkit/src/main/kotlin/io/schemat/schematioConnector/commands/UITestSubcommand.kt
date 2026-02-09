package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.GrabberElement
import io.schemat.schematioConnector.ui.ResizableWhiteboard
import io.schemat.schematioConnector.ui.UIElement
import io.schemat.schematioConnector.ui.components.*
import io.schemat.schematioConnector.ui.layout.*
import io.schemat.schematioConnector.ui.page.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Player

class UITestSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "ui"
    override val permission = "schematio.admin"
    override val description = "Test the floating UI library with an interactive whiteboard"

    // Track whiteboards per player
    private val whiteboards = mutableMapOf<java.util.UUID, ResizableWhiteboard>()
    
    override fun execute(player: Player, args: Array<out String>): Boolean {
        // Check for demo mode (tabbed demo with all features)
        if (args.isEmpty() || args[0].equals("demo", ignoreCase = true)) {
            return executeTabbedDemo(player)
        }

        // Check for layout test mode
        if (args[0].equals("layout", ignoreCase = true)) {
            return executeLayoutTest(player)
        }

        // Check for whiteboard mode
        if (args[0].equals("whiteboard", ignoreCase = true)) {
            return executeWhiteboardDemo(player, args.drop(1).toTypedArray())
        }

        // Check for page mode (new Page system demo)
        if (args[0].equals("page", ignoreCase = true)) {
            return executePageDemo(player)
        }

        // Unknown subcommand - show demo
        return executeTabbedDemo(player)
    }

    /**
     * Execute the whiteboard demo (original behavior)
     */
    private fun executeWhiteboardDemo(player: Player, args: Array<out String>): Boolean {
        // Close any existing UI
        FloatingUI.closeForPlayer(player)

        // Check for debug flag
        val debugMode = args.isNotEmpty() && args[0].equals("debug", ignoreCase = true)

        // Initial whiteboard size
        val initialWidth = 3.0f
        val initialHeight = 2.0f

        // Calculate position - 3 blocks in front of player at eye level
        val eyeLoc = player.eyeLocation.clone()
        val direction = eyeLoc.direction.normalize()

        // Remove vertical component to keep UI at a consistent height
        val horizontalDir = direction.clone().setY(0).normalize()
        val center = eyeLoc.clone().add(horizontalDir.multiply(3.0))

        // Create the floating UI with debug mode
        val ui = FloatingUI.create(plugin, player, center, direction, debugMode)

        if (debugMode) {
            player.sendMessage(Component.text("§e[DEBUG] Debug mode enabled").color(NamedTextColor.YELLOW))
            player.sendMessage(Component.text("§e[DEBUG] Check server console for hover logs").color(NamedTextColor.YELLOW))
        }

        // Create resizable whiteboard with demo components
        val whiteboard = ResizableWhiteboard(
            plugin = plugin,
            ui = ui,
            player = player,
            initialWidth = initialWidth,
            initialHeight = initialHeight,
            showDemoComponents = true
        )
        whiteboards[player.uniqueId] = whiteboard

        val width = initialWidth
        val height = initialHeight
        val halfW = width / 2.0
        val halfH = height / 2.0

        // Add coordinate system gizmo at the center for debugging
        if (debugMode) {
            val gizmoLength = 0.5f
            val gizmoThickness = 0.08f

            // X axis (right) - RED (horizontal bar)
            ui.addPanel(
                offsetRight = gizmoLength / 2.0,
                offsetUp = 0.0,
                offsetForward = 0.0,
                width = gizmoLength,
                height = gizmoThickness,
                material = Material.RED_CONCRETE,
                rotateToFace = true
            )

            // Y axis (up) - GREEN (vertical bar)
            ui.addPanel(
                offsetRight = 0.0,
                offsetUp = gizmoLength / 2.0,
                offsetForward = 0.0,
                width = gizmoThickness,
                height = gizmoLength,
                material = Material.LIME_CONCRETE,
                rotateToFace = true
            )

            // Z axis (forward) - BLUE (depth bar - make it thicker so it's visible)
            // This one extends away from the UI plane, so we make it a thick bar
            ui.addPanel(
                offsetRight = 0.0,
                offsetUp = 0.0,
                offsetForward = gizmoLength / 2.0,
                width = gizmoThickness,
                height = gizmoThickness,
                material = Material.BLUE_CONCRETE,
                rotateToFace = false  // Don't rotate, let it be thick in the Z direction
            )
        }
        
        // Add title label above the whiteboard (in front of frame)
        ui.addLabel(
            offsetRight = 0.0,
            offsetUp = halfH + 0.33,
            offsetForward = -0.05, // Negative = toward player
            text = "§6§lWhiteboard Test",
            scale = 0.9f,
            backgroundColor = Color.fromARGB(0, 0, 0, 0)
        )

        // Add info label below the whiteboard (in front of frame)
        ui.addLabel(
            offsetRight = 0.0,
            offsetUp = -halfH - 0.28,
            offsetForward = -0.05,
            text = "§7Right-click to interact",
            scale = 0.5f,
            backgroundColor = Color.fromARGB(120, 0, 0, 0)
        )

        // Add resize toggle button (bottom left, in front)
        ui.addButton(
            offsetRight = -0.7,
            offsetUp = -halfH - 0.68,
            offsetForward = -0.05,
            label = "§e⚙ Resize",
            material = Material.GRAY_CONCRETE,
            hoverMaterial = Material.LIGHT_GRAY_CONCRETE,
            size = 0.22f
        ) {
            whiteboard.toggleResizeMode()
        }

        // Add close button (bottom right, in front)
        ui.addButton(
            offsetRight = 0.7,
            offsetUp = -halfH - 0.68,
            offsetForward = -0.05,
            label = "§c✕ Close",
            material = Material.RED_CONCRETE,
            hoverMaterial = Material.ORANGE_CONCRETE,
            size = 0.22f
        ) {
            ui.destroy()
            player.sendMessage(Component.text("Whiteboard closed.").color(NamedTextColor.GRAY))
        }
        
        // Clean up on destroy
        ui.onDestroy = {
            whiteboard.destroy()
            whiteboards.remove(player.uniqueId)
        }
        
        // Send instructions
        player.sendMessage(Component.empty())
        player.sendMessage(Component.text("  ✨ Whiteboard Test UI").color(NamedTextColor.GOLD))
        player.sendMessage(Component.text("  §7▸ §eRight-click §7to interact with buttons"))
        player.sendMessage(Component.text("  §7▸ §eResize Mode §7toggles corner grabbers"))
        player.sendMessage(Component.text("  §7▸ §cWalk away §7(15 blocks) to dismiss"))
        if (debugMode) {
            player.sendMessage(Component.text("  §7▸ §6DEBUG §7mode enabled - check console"))
        }
        player.sendMessage(Component.empty())
        
        return true
    }
    
    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("demo", "whiteboard", "layout").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("whiteboard", ignoreCase = true)) {
            return listOf("debug").filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }

    /**
     * Tabbed demo - shows all UI components organized in tabs
     */
    private fun executeTabbedDemo(player: Player): Boolean {
        FloatingUI.closeForPlayer(player)

        val eyeLoc = player.eyeLocation.clone()
        val direction = eyeLoc.direction.normalize()
        val horizontalDir = direction.clone().setY(0).normalize()
        val center = eyeLoc.clone().add(horizontalDir.multiply(3.0))

        val ui = FloatingUI.create(plugin, player, center, direction, debugMode = false)

        val panelWidth = 4.5f
        val panelHeight = 3.0f
        val halfW = panelWidth / 2.0
        val halfH = panelHeight / 2.0

        // Background panel
        ui.addPanel(
            offsetRight = 0.0,
            offsetUp = 0.0,
            offsetForward = 0.05,
            width = panelWidth,
            height = panelHeight,
            material = Material.BLACK_CONCRETE
        )

        // Title
        ui.addLabel(
            offsetRight = 0.0,
            offsetUp = halfH - 0.2,
            offsetForward = -0.02,
            text = "§6§lUI Component Demo",
            scale = 0.6f,
            backgroundColor = Color.fromARGB(0, 0, 0, 0)
        )

        // Tab definitions
        val tabs = listOf(
            TabDefinition("components", "Components", "🎛 "),
            TabDefinition("todo", "Todo", "✓ "),
            TabDefinition("layout", "Layout", "📐 "),
            TabDefinition("about", "About", "ℹ ")
        )

        // Track content elements for each tab
        val tabContent = mutableMapOf<String, MutableList<UIElement>>()
        tabs.forEach { tabContent[it.id] = mutableListOf() }

        // Content area bounds
        val contentTop = halfH - 0.6
        val compY = contentTop - 0.3

        // Track progress bar for animation
        var progressBar: ProgressBarElement? = null

        // Function to clear all tab content
        fun clearTabContent() {
            tabContent.values.flatten().forEach { element ->
                ui.removeElement(element)
            }
            tabContent.values.forEach { it.clear() }
            progressBar = null
        }

        // Function to create Components tab content
        fun createComponentsTab() {
            // Checkbox
            tabContent["components"]!!.add(ui.addCheckbox(
                offsetRight = -halfW + 0.8,
                offsetUp = compY,
                offsetForward = -0.02,
                checked = false,
                label = "Enable feature"
            ) { checked ->
                player.sendMessage(Component.text("§7Checkbox: §e$checked").color(NamedTextColor.GRAY))
            })

            // Slider
            tabContent["components"]!!.add(ui.addSlider(
                offsetRight = 0.0,
                offsetUp = compY - 0.4,
                offsetForward = -0.02,
                width = 2.5f,
                minValue = 0f,
                maxValue = 100f,
                value = 50f,
                step = 5f,
                showValue = true,
                label = "Volume"
            ) { value ->
                player.sendMessage(Component.text("§7Slider: §e$value").color(NamedTextColor.GRAY))
            })

            // Progress bar
            progressBar = ui.addProgressBar(
                offsetRight = 0.0,
                offsetUp = compY - 0.8,
                offsetForward = -0.02,
                width = 2.5f,
                height = 0.12f,
                progress = 0.65f,
                showPercentage = true,
                label = "Progress"
            )
            tabContent["components"]!!.add(progressBar!!)

            // Text input
            tabContent["components"]!!.add(ui.addTextInput(
                offsetRight = 0.0,
                offsetUp = compY - 1.2,
                offsetForward = -0.02,
                width = 2.5f,
                height = 0.25f,
                placeholder = "Type something...",
                value = ""
            ) { value ->
                player.sendMessage(Component.text("§7Input: §e$value").color(NamedTextColor.GRAY))
            })

            // Buttons row
            tabContent["components"]!!.add(ui.addButton(
                offsetRight = -0.8,
                offsetUp = compY - 1.7,
                offsetForward = -0.02,
                label = "§aConfirm",
                material = Material.LIME_CONCRETE,
                hoverMaterial = Material.GREEN_CONCRETE,
                size = 0.25f
            ) {
                player.sendMessage(Component.text("§aConfirm clicked!").color(NamedTextColor.GREEN))
            })

            tabContent["components"]!!.add(ui.addButton(
                offsetRight = 0.0,
                offsetUp = compY - 1.7,
                offsetForward = -0.02,
                label = "§eAction",
                material = Material.YELLOW_CONCRETE,
                hoverMaterial = Material.GOLD_BLOCK,
                size = 0.25f
            ) {
                player.sendMessage(Component.text("§eAction clicked!").color(NamedTextColor.YELLOW))
            })

            tabContent["components"]!!.add(ui.addButton(
                offsetRight = 0.8,
                offsetUp = compY - 1.7,
                offsetForward = -0.02,
                label = "§cCancel",
                material = Material.RED_CONCRETE,
                hoverMaterial = Material.ORANGE_CONCRETE,
                size = 0.25f
            ) {
                player.sendMessage(Component.text("§cCancel clicked!").color(NamedTextColor.RED))
            })
        }

        // Function to create Layout tab content
        fun createLayoutTab() {
            // Create layout visualization
            val layout = Layout(width = 3.8f, height = 2.0f)
            layout.column("root", padding = Padding.all(0.05f), gap = 0.05f, crossAxisAlignment = CrossAxisAlignment.Stretch) {
                row("header", height = 0.3f, crossAxisAlignment = CrossAxisAlignment.Center) {
                    leaf("title", flexGrow = 1f, height = 0.3f)
                    leaf("close", width = 0.25f, height = 0.25f)
                }
                row("content", flexGrow = 1f, gap = 0.05f, crossAxisAlignment = CrossAxisAlignment.Stretch) {
                    leaf("sidebar", width = 0.6f)
                    column("main", flexGrow = 1f, gap = 0.05f, crossAxisAlignment = CrossAxisAlignment.Stretch) {
                        leaf("toolbar", height = 0.25f)
                        leaf("canvas", flexGrow = 1f)
                    }
                }
                row("footer", height = 0.25f, mainAxisAlignment = MainAxisAlignment.SpaceBetween) {
                    leaf("status", width = 1.2f, height = 0.25f)
                    leaf("actions", width = 0.8f, height = 0.25f)
                }
            }
            layout.compute()

            val layoutHalfW = 1.9f
            val layoutHalfH = 1.0f
            val layoutOffsetY = compY - 1.0

            // Background for layout
            tabContent["layout"]!!.add(ui.addPanel(
                offsetRight = 0.0,
                offsetUp = layoutOffsetY,
                offsetForward = 0.0,
                width = 3.9f,
                height = 2.1f,
                material = Material.GRAY_CONCRETE
            ))

            // Create colored panels for each layout element
            fun addLayoutPanel(id: String, material: Material) {
                val result = layout.getResult(id) ?: return
                val absolutePos = layout.getAbsolutePosition(id) ?: return
                // Use absolute position for nested elements
                val x = absolutePos.x + result.width / 2 - layoutHalfW
                val y = layoutHalfH - absolutePos.y - result.height / 2 + layoutOffsetY

                tabContent["layout"]!!.add(ui.addPanel(
                    offsetRight = x.toDouble(),
                    offsetUp = y.toDouble(),
                    offsetForward = -0.01,
                    width = result.width - 0.02f,
                    height = result.height - 0.02f,
                    material = material
                ))

                tabContent["layout"]!!.add(ui.addLabel(
                    offsetRight = x.toDouble(),
                    offsetUp = y.toDouble(),
                    offsetForward = -0.03,
                    text = "§f$id",
                    scale = 0.25f,
                    backgroundColor = Color.fromARGB(0, 0, 0, 0)
                ))
            }

            // Only render leaf elements (not containers) to avoid z-fighting
            addLayoutPanel("title", Material.LIGHT_BLUE_CONCRETE)
            addLayoutPanel("close", Material.RED_CONCRETE)
            addLayoutPanel("sidebar", Material.PURPLE_CONCRETE)
            addLayoutPanel("toolbar", Material.YELLOW_CONCRETE)
            addLayoutPanel("canvas", Material.WHITE_CONCRETE)
            addLayoutPanel("status", Material.LIME_CONCRETE)
            addLayoutPanel("actions", Material.ORANGE_CONCRETE)

            // Label explaining the layout
            tabContent["layout"]!!.add(ui.addLabel(
                offsetRight = 0.0,
                offsetUp = contentTop - 0.1,
                offsetForward = -0.02,
                text = "§eFlexbox Layout Demo",
                scale = 0.4f,
                backgroundColor = Color.fromARGB(0, 0, 0, 0)
            ))
        }

        // Todo list data
        data class TodoItem(val text: String, var completed: Boolean = false)
        val todoLists = mutableMapOf<String, MutableList<TodoItem>>(
            "Shopping" to mutableListOf(
                TodoItem("Buy milk"),
                TodoItem("Get bread"),
                TodoItem("Pick up eggs", true)
            ),
            "Work" to mutableListOf(
                TodoItem("Review PR"),
                TodoItem("Fix bug #123"),
                TodoItem("Update docs")
            )
        )
        var currentList = "Shopping"
        var newItemText = ""
        var tabScrollOffset = 0  // Index of first visible tab

        // Function to create Todo tab content - simple manual positioning
        fun createTodoTab() {
            val listNames = todoLists.keys.toList()
            val items = todoLists[currentList] ?: mutableListOf()
            val completedCount = items.count { it.completed }
            val maxVisibleItems = 6

            // Simple manual positioning
            val startY = compY - 0.05
            val leftEdge = -halfW + 0.3
            val rightEdge = halfW - 0.3

            // Background panel
            tabContent["todo"]!!.add(ui.addPanel(
                offsetRight = 0.0,
                offsetUp = compY - 1.0,
                offsetForward = 0.01,
                width = 4.2f,
                height = 2.2f,
                material = Material.GRAY_CONCRETE
            ))

            // Row 1: List tabs (scrollable if too many)
            val tabWidth = 0.55
            val tabGap = 0.08
            val maxVisibleTabs = 5  // Maximum tabs visible at once
            val arrowSize = 0.14f
            val arrowWidth = 0.25

            // Calculate available width for tabs
            val availableWidth = 3.5  // Total width available for tabs area
            val hasLeftArrow = tabScrollOffset > 0
            val hasRightArrow = tabScrollOffset + maxVisibleTabs < listNames.size

            // Ensure current list is visible by adjusting scroll offset
            val currentIndex = listNames.indexOf(currentList)
            if (currentIndex >= 0) {
                if (currentIndex < tabScrollOffset) {
                    tabScrollOffset = currentIndex
                } else if (currentIndex >= tabScrollOffset + maxVisibleTabs) {
                    tabScrollOffset = currentIndex - maxVisibleTabs + 1
                }
            }

            // Recalculate arrows after potential scroll adjustment
            val showLeftArrow = tabScrollOffset > 0
            val showRightArrow = tabScrollOffset + maxVisibleTabs < listNames.size

            // Calculate starting position
            val visibleTabs = listNames.drop(tabScrollOffset).take(maxVisibleTabs)
            val numVisibleTabs = visibleTabs.size
            val tabsContentWidth = numVisibleTabs * tabWidth + (numVisibleTabs - 1) * tabGap
            val addButtonWidth = 0.35
            val leftArrowSpace = if (showLeftArrow) arrowWidth + 0.05 else 0.0
            val rightArrowSpace = if (showRightArrow) arrowWidth + 0.05 else 0.0
            val totalContentWidth = leftArrowSpace + tabsContentWidth + rightArrowSpace + addButtonWidth

            var currentX = -totalContentWidth / 2

            // Left arrow button (if needed)
            if (showLeftArrow) {
                tabContent["todo"]!!.add(ui.addButton(
                    offsetRight = currentX + arrowWidth / 2,
                    offsetUp = startY,
                    offsetForward = -0.02,
                    label = "§7◀",
                    material = Material.GRAY_CONCRETE,
                    hoverMaterial = Material.LIGHT_GRAY_CONCRETE,
                    size = arrowSize
                ) {
                    tabScrollOffset = (tabScrollOffset - 1).coerceAtLeast(0)
                    clearTabContent()
                    createTodoTab()
                })
                currentX += arrowWidth + 0.05
            }

            // Visible list tabs
            visibleTabs.forEach { listName ->
                val isSelected = listName == currentList
                val shortName = if (listName.length > 8) listName.take(7) + ".." else listName
                tabContent["todo"]!!.add(ui.addButton(
                    offsetRight = currentX + tabWidth / 2,
                    offsetUp = startY,
                    offsetForward = -0.02,
                    label = if (isSelected) "§a§l$shortName" else "§7$shortName",
                    material = if (isSelected) Material.LIME_CONCRETE else Material.BLACK_CONCRETE,
                    hoverMaterial = if (isSelected) Material.GREEN_CONCRETE else Material.LIGHT_GRAY_CONCRETE,
                    size = 0.16f
                ) {
                    currentList = listName
                    clearTabContent()
                    createTodoTab()
                })
                currentX += tabWidth + tabGap
            }

            // Right arrow button (if needed)
            if (showRightArrow) {
                currentX -= tabGap  // Remove last gap
                currentX += 0.05  // Add small spacing before arrow
                tabContent["todo"]!!.add(ui.addButton(
                    offsetRight = currentX + arrowWidth / 2,
                    offsetUp = startY,
                    offsetForward = -0.02,
                    label = "§7▶",
                    material = Material.GRAY_CONCRETE,
                    hoverMaterial = Material.LIGHT_GRAY_CONCRETE,
                    size = arrowSize
                ) {
                    tabScrollOffset = (tabScrollOffset + 1).coerceAtMost(listNames.size - maxVisibleTabs)
                    clearTabContent()
                    createTodoTab()
                })
                currentX += arrowWidth + 0.05
            }

            // + button to add list
            currentX -= tabGap  // Remove last gap
            currentX += 0.08  // Add spacing before + button
            tabContent["todo"]!!.add(ui.addButton(
                offsetRight = currentX + 0.15,
                offsetUp = startY,
                offsetForward = -0.02,
                label = "§e+",
                material = Material.YELLOW_CONCRETE,
                hoverMaterial = Material.GOLD_BLOCK,
                size = 0.14f
            ) {
                val newListName = "List ${todoLists.size + 1}"
                todoLists[newListName] = mutableListOf()
                currentList = newListName
                // Scroll to show the new tab
                if (listNames.size >= maxVisibleTabs) {
                    tabScrollOffset = listNames.size - maxVisibleTabs + 1
                }
                clearTabContent()
                createTodoTab()
                player.sendMessage(Component.text("§aCreated: $newListName").color(NamedTextColor.GREEN))
            })

            // Row 2: Current list name (editable) + rename/delete buttons
            val row2Y = startY - 0.35

            // List name input for renaming
            tabContent["todo"]!!.add(ui.addTextInput(
                offsetRight = -0.5,
                offsetUp = row2Y,
                offsetForward = -0.02,
                width = 1.8f,
                height = 0.22f,
                placeholder = "List name",
                value = currentList
            ) { newName ->
                if (newName.isNotBlank() && newName != currentList && !todoLists.containsKey(newName)) {
                    val items = todoLists.remove(currentList)
                    if (items != null) {
                        todoLists[newName] = items
                        currentList = newName
                        clearTabContent()
                        createTodoTab()
                    }
                }
            })

            // Delete list button
            if (todoLists.size > 1) {
                tabContent["todo"]!!.add(ui.addButton(
                    offsetRight = rightEdge - 0.15,
                    offsetUp = row2Y,
                    offsetForward = -0.02,
                    label = "§cDel",
                    material = Material.RED_CONCRETE,
                    hoverMaterial = Material.ORANGE_CONCRETE,
                    size = 0.14f
                ) {
                    todoLists.remove(currentList)
                    currentList = todoLists.keys.first()
                    clearTabContent()
                    createTodoTab()
                    player.sendMessage(Component.text("§cList deleted").color(NamedTextColor.RED))
                })
            }

            // Stats on the right
            tabContent["todo"]!!.add(ui.addLabel(
                offsetRight = rightEdge - 0.6,
                offsetUp = row2Y,
                offsetForward = -0.02,
                text = "§7$completedCount/${items.size}",
                scale = 0.3f,
                backgroundColor = Color.fromARGB(0, 0, 0, 0)
            ))

            // Row 3+: Todo items
            val itemStartY = row2Y - 0.32
            val itemHeight = 0.28

            if (items.isEmpty()) {
                tabContent["todo"]!!.add(ui.addLabel(
                    offsetRight = 0.0,
                    offsetUp = itemStartY - 0.5,
                    offsetForward = -0.02,
                    text = "§7Empty list - add items below",
                    scale = 0.35f,
                    backgroundColor = Color.fromARGB(0, 0, 0, 0)
                ))
            } else {
                val visibleItems = items.take(maxVisibleItems)
                visibleItems.forEachIndexed { index, item ->
                    val itemY = itemStartY - (index * itemHeight)

                    // Checkbox (small)
                    tabContent["todo"]!!.add(ui.addCheckbox(
                        offsetRight = leftEdge + 0.1,
                        offsetUp = itemY,
                        offsetForward = -0.02,
                        checked = item.completed,
                        label = ""
                    ) { checked ->
                        item.completed = checked
                        clearTabContent()
                        createTodoTab()
                    })

                    // Item text as a button (click to see full text)
                    val displayText = if (item.completed) "§7§m${item.text}" else "§f${item.text}"
                    val shortText = if (displayText.length > 30) displayText.take(28) + ".." else displayText
                    tabContent["todo"]!!.add(ui.addLabel(
                        offsetRight = 0.0,
                        offsetUp = itemY,
                        offsetForward = -0.02,
                        text = shortText,
                        scale = 0.32f,
                        backgroundColor = Color.fromARGB(0, 0, 0, 0)
                    ))

                    // Delete button (small X)
                    tabContent["todo"]!!.add(ui.addButton(
                        offsetRight = rightEdge - 0.1,
                        offsetUp = itemY,
                        offsetForward = -0.02,
                        label = "§c✕",
                        material = Material.RED_CONCRETE,
                        hoverMaterial = Material.ORANGE_CONCRETE,
                        size = 0.12f
                    ) {
                        items.removeAt(index)
                        clearTabContent()
                        createTodoTab()
                    })
                }

                if (items.size > maxVisibleItems) {
                    tabContent["todo"]!!.add(ui.addLabel(
                        offsetRight = 0.0,
                        offsetUp = itemStartY - (maxVisibleItems * itemHeight),
                        offsetForward = -0.02,
                        text = "§7... and ${items.size - maxVisibleItems} more",
                        scale = 0.25f,
                        backgroundColor = Color.fromARGB(0, 0, 0, 0)
                    ))
                }
            }

            // Bottom row: Add new item
            val bottomY = compY - 1.95

            tabContent["todo"]!!.add(ui.addTextInput(
                offsetRight = -0.3,
                offsetUp = bottomY,
                offsetForward = -0.02,
                width = 2.8f,
                height = 0.22f,
                placeholder = "New todo item...",
                value = newItemText
            ) { value ->
                newItemText = value
            })

            tabContent["todo"]!!.add(ui.addButton(
                offsetRight = rightEdge - 0.2,
                offsetUp = bottomY,
                offsetForward = -0.02,
                label = "§a+",
                material = Material.LIME_CONCRETE,
                hoverMaterial = Material.GREEN_CONCRETE,
                size = 0.14f
            ) {
                if (newItemText.isNotBlank()) {
                    items.add(TodoItem(newItemText))
                    newItemText = ""
                    clearTabContent()
                    createTodoTab()
                }
            })
        }

        // Function to create About tab content
        fun createAboutTab() {
            tabContent["about"]!!.add(ui.addLabel(
                offsetRight = 0.0,
                offsetUp = compY,
                offsetForward = -0.02,
                text = "§6§lFloatingUI Library",
                scale = 0.5f,
                backgroundColor = Color.fromARGB(0, 0, 0, 0)
            ))

            tabContent["about"]!!.add(ui.addLabel(
                offsetRight = 0.0,
                offsetUp = compY - 0.4,
                offsetForward = -0.02,
                text = "§fA 3D UI system using Display Entities",
                scale = 0.35f,
                backgroundColor = Color.fromARGB(0, 0, 0, 0)
            ))

            tabContent["about"]!!.add(ui.addLabel(
                offsetRight = 0.0,
                offsetUp = compY - 0.7,
                offsetForward = -0.02,
                text = "§7Features:",
                scale = 0.35f,
                backgroundColor = Color.fromARGB(0, 0, 0, 0)
            ))

            val features = listOf(
                "• Buttons, Checkboxes, Sliders",
                "• Text Input, Progress Bars",
                "• Tabs, Panels, Labels",
                "• Flexbox Layout System",
                "• Raycasting Hit Detection"
            )

            features.forEachIndexed { index, feature ->
                tabContent["about"]!!.add(ui.addLabel(
                    offsetRight = 0.0,
                    offsetUp = compY - 1.0 - (index * 0.25),
                    offsetForward = -0.02,
                    text = "§7$feature",
                    scale = 0.3f,
                    backgroundColor = Color.fromARGB(0, 0, 0, 0)
                ))
            }
        }

        // Function to switch tabs
        fun switchToTab(tabId: String) {
            clearTabContent()
            when (tabId) {
                "components" -> createComponentsTab()
                "todo" -> createTodoTab()
                "layout" -> createLayoutTab()
                "about" -> createAboutTab()
            }
        }

        // Add tabs
        ui.addTabs(
            offsetRight = 0.0,
            offsetUp = halfH - 0.5,
            offsetForward = -0.02,
            tabs = tabs,
            tabWidth = 1.2f,
            tabHeight = 0.25f,
            gap = 0.08f,
            selectedIndex = 0
        ) { index, tab ->
            switchToTab(tab.id)
        }

        // Close button
        ui.addButton(
            offsetRight = halfW - 0.3,
            offsetUp = halfH - 0.2,
            offsetForward = -0.02,
            label = "§c✕",
            material = Material.RED_CONCRETE,
            hoverMaterial = Material.ORANGE_CONCRETE,
            size = 0.2f
        ) {
            ui.destroy()
            player.sendMessage(Component.text("§7UI closed.").color(NamedTextColor.GRAY))
        }

        // Footer info
        ui.addLabel(
            offsetRight = 0.0,
            offsetUp = -halfH + 0.15,
            offsetForward = -0.02,
            text = "§7Right-click to interact • Walk 15 blocks to close",
            scale = 0.3f,
            backgroundColor = Color.fromARGB(0, 0, 0, 0)
        )

        // Initialize with Components tab
        createComponentsTab()

        // Animate progress bar
        var tickCount = 0
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            if (ui.isDestroyed()) {
                task.cancel()
                return@runTaskTimer
            }
            tickCount++
            val progress = (tickCount % 100) / 100f
            progressBar?.updateProgress(progress)
        }, 0L, 2L)

        player.sendMessage(Component.empty())
        player.sendMessage(Component.text("  §6✨ UI Component Demo").color(NamedTextColor.GOLD))
        player.sendMessage(Component.text("  §7▸ §eRight-click §7to interact"))
        player.sendMessage(Component.text("  §7▸ §eClick tabs §7to switch views"))
        player.sendMessage(Component.text("  §7▸ §cWalk away §7(15 blocks) to close"))
        player.sendMessage(Component.empty())

        return true
    }

    /**
     * Test the layout system - runs unit tests and displays a layout-driven UI
     */
    private fun executeLayoutTest(player: Player): Boolean {
        player.sendMessage(Component.text("§6=== Layout System Test ===").color(NamedTextColor.GOLD))

        player.sendMessage(Component.text("§e→ Run unit tests with ./gradlew test").color(NamedTextColor.YELLOW))

        // Create a visual demo using the layout system
        FloatingUI.closeForPlayer(player)

        val eyeLoc = player.eyeLocation.clone()
        val direction = eyeLoc.direction.normalize()
        val horizontalDir = direction.clone().setY(0).normalize()
        val center = eyeLoc.clone().add(horizontalDir.multiply(3.0))

        val ui = FloatingUI.create(plugin, player, center, direction, debugMode = false)

        // Define a layout
        val layout = Layout(width = 4f, height = 3f)
        layout.column("root", padding = Padding.all(0.1f), gap = 0.1f, crossAxisAlignment = CrossAxisAlignment.Stretch) {
            // Header row
            row("header", height = 0.4f, crossAxisAlignment = CrossAxisAlignment.Center) {
                leaf("title", intrinsicWidth = 2f, intrinsicHeight = 0.4f, flexGrow = 1f)
                leaf("close", width = 0.3f, height = 0.3f)
            }
            // Content area with sidebar
            row("content", flexGrow = 1f, gap = 0.1f, crossAxisAlignment = CrossAxisAlignment.Stretch) {
                leaf("sidebar", width = 0.8f)
                column("main", flexGrow = 1f, gap = 0.1f, crossAxisAlignment = CrossAxisAlignment.Stretch) {
                    leaf("toolbar", height = 0.3f)
                    leaf("canvas", flexGrow = 1f)
                }
            }
            // Footer
            row("footer", height = 0.3f, mainAxisAlignment = MainAxisAlignment.SpaceBetween) {
                leaf("status", width = 1.5f, height = 0.3f)
                leaf("actions", width = 1f, height = 0.3f)
            }
        }
        layout.compute()

        player.sendMessage(Component.text("§7Layout computed:").color(NamedTextColor.GRAY))
        player.sendMessage(Component.text(layout.debugPrint()).color(NamedTextColor.GRAY))

        // Now create UI elements based on the layout
        val halfW = 2f  // half of layout width
        val halfH = 1.5f  // half of layout height

        // Background panel (furthest back)
        ui.addPanel(
            offsetRight = 0.0,
            offsetUp = 0.0,
            offsetForward = 0.06,  // Background is furthest back
            width = 4f,
            height = 3f,
            material = Material.BLACK_CONCRETE
        )

        // Z-layer constants to prevent z-fighting
        // More negative = closer to player
        val zLayer0 = 0.04   // Container level (root children: header, content, footer)
        val zLayer1 = 0.02   // First nested level (title, close, sidebar, main, status, actions)
        val zLayer2 = 0.0    // Second nested level (toolbar, canvas)
        val zLabel = -0.02   // Labels always in front

        // Create colored panels for each layout element to visualize
        fun addLayoutPanel(id: String, material: Material, zOffset: Double) {
            val result = layout.getResult(id) ?: return
            val absolutePos = layout.getAbsolutePosition(id) ?: return
            // Convert layout coordinates (0,0 = top-left) to UI coordinates (0,0 = center)
            // Use absolute position for nested elements
            val x = absolutePos.x + result.width / 2 - halfW
            val y = halfH - absolutePos.y - result.height / 2  // Flip Y axis

            ui.addPanel(
                offsetRight = x.toDouble(),
                offsetUp = y.toDouble(),
                offsetForward = zOffset,
                width = result.width,
                height = result.height,
                material = material
            )

            // Add label for the element (always in front)
            ui.addLabel(
                offsetRight = x.toDouble(),
                offsetUp = y.toDouble(),
                offsetForward = zLabel,
                text = "§f$id",
                scale = 0.3f,
                backgroundColor = Color.fromARGB(0, 0, 0, 0)
            )
        }

        // Visualize each element with a different color
        // Layer 0: Direct children of root (header, content, footer)
        addLayoutPanel("header", Material.BLUE_CONCRETE, zLayer0)
        addLayoutPanel("content", Material.BLACK_CONCRETE, zLayer0)  // Content container (optional, can skip)
        addLayoutPanel("footer", Material.PURPLE_CONCRETE, zLayer0)

        // Layer 1: Children of header, content, footer
        addLayoutPanel("title", Material.LIGHT_BLUE_CONCRETE, zLayer1)
        addLayoutPanel("close", Material.RED_CONCRETE, zLayer1)
        addLayoutPanel("sidebar", Material.GRAY_CONCRETE, zLayer1)
        addLayoutPanel("main", Material.BLACK_CONCRETE, zLayer1)  // Main container (optional)
        addLayoutPanel("status", Material.CYAN_CONCRETE, zLayer1)
        addLayoutPanel("actions", Material.ORANGE_CONCRETE, zLayer1)

        // Layer 2: Children of main (deepest nested)
        addLayoutPanel("toolbar", Material.YELLOW_CONCRETE, zLayer2)
        addLayoutPanel("canvas", Material.WHITE_CONCRETE, zLayer2)

        player.sendMessage(Component.text("§a✓ Layout visualization created!").color(NamedTextColor.GREEN))
        player.sendMessage(Component.text("§7Walk away (15 blocks) to dismiss").color(NamedTextColor.GRAY))

        return true
    }

    /**
     * Execute the Page system demo
     */
    private fun executePageDemo(player: Player): Boolean {
        // Close any existing UI
        FloatingUI.closeForPlayer(player)

        // Calculate position - 3 blocks in front of player at eye level
        val eyeLoc = player.eyeLocation.clone()
        val direction = eyeLoc.direction.normalize()
        val horizontalDir = direction.clone().setY(0).normalize()
        val center = eyeLoc.clone().add(horizontalDir.multiply(3.0))

        // Create the floating UI
        val ui = FloatingUI.create(plugin, player, center, direction, false)

        // Define page bounds (centered, 4x3 units)
        val bounds = PageBounds.centered(4f, 3f)

        // Create the page manager
        val pageManager = PageManager(plugin, ui, player, bounds)

        // Create a demo content that shows the Page system features
        val demoContent = DemoPageContent()

        // Show the page
        val page = pageManager.showPage(demoContent)

        player.sendMessage(Component.text("§a✓ Page demo created!").color(NamedTextColor.GREEN))
        player.sendMessage(Component.text("§7Use the X button to close, or the ⋮ menu for options").color(NamedTextColor.GRAY))
        player.sendMessage(Component.text("§7Try Split Horizontal/Vertical from the menu!").color(NamedTextColor.GRAY))

        return true
    }
}

/**
 * Demo content for testing the Page system.
 * Shows a simple layout with header, content area, and footer.
 */
class DemoPageContent : PageContent("demo", "Demo Page") {

    override fun buildLayout(width: Float, height: Float): Layout {
        return Layout(width = width, height = height).apply {
            column("root", padding = Padding.all(0.1f), gap = 0.08f, crossAxisAlignment = CrossAxisAlignment.Stretch) {
                // Header
                row("header", height = 0.35f, crossAxisAlignment = CrossAxisAlignment.Center) {
                    leaf("title", flexGrow = 1f, height = 0.35f)
                }
                // Content area
                leaf("content", flexGrow = 1f)
                // Footer with stats
                row("footer", height = 0.25f, mainAxisAlignment = MainAxisAlignment.SpaceBetween, crossAxisAlignment = CrossAxisAlignment.Stretch) {
                    leaf("left-info", width = 1.2f, height = 0.25f)
                    leaf("right-info", width = 1.2f, height = 0.25f)
                }
            }
            compute()
        }
    }

    override fun render(ui: FloatingUI, renderer: LayoutRenderer, bounds: PageBounds) {
        // Header background
        renderer.renderPanel("header", Material.BLUE_CONCRETE, -0.01)?.let { elements.add(it) }

        // Header title - use aligned label so it stays on the UI plane
        renderer.renderAlignedLabel("title", "§f§l$title", scale = 0.5f)?.let { elements.add(it) }

        // Content area - light gray background
        renderer.renderPanel("content", Material.LIGHT_GRAY_CONCRETE, 0.0)?.let { elements.add(it) }

        // Content text - use aligned labels
        val contentPos = renderer.getElementPosition("content")
        if (contentPos != null) {
            ui.addAlignedLabel(
                offsetRight = contentPos.uiX,
                offsetUp = contentPos.uiY + 0.2,
                offsetForward = -0.03,
                text = "§fPage System Demo",
                scale = 0.45f
            ).let { elements.add(it) }

            ui.addAlignedLabel(
                offsetRight = contentPos.uiX,
                offsetUp = contentPos.uiY - 0.2,
                offsetForward = -0.03,
                text = "§7Click the ⋮ menu for options",
                scale = 0.35f
            ).let { elements.add(it) }
        }

        // Footer background
        renderer.renderPanel("footer", Material.GRAY_CONCRETE, -0.01)?.let { elements.add(it) }

        // Footer labels - use aligned labels
        renderer.renderAlignedLabel(
            "left-info",
            "§7Size: ${String.format("%.1f", bounds.width)}x${String.format("%.1f", bounds.height)}",
            scale = 0.35f
        )?.let { elements.add(it) }

        renderer.renderAlignedLabel(
            "right-info",
            "§7Page System v1.0",
            scale = 0.35f
        )?.let { elements.add(it) }
    }
}
