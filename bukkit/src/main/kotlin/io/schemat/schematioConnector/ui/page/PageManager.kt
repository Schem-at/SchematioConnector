package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.GrabberElement
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

/**
 * Where to place the new content when splitting.
 */
enum class SplitPosition {
    /** New content goes in the first position (left/top) */
    FIRST,
    /** New content goes in the second position (right/bottom) */
    SECOND
}

/**
 * Interaction mode for a page.
 */
enum class PageInteractionMode {
    NONE,
    MOVE,
    RESIZE
}

/**
 * Manages pages and containers for a player.
 *
 * The PageManager:
 * - Creates and tracks pages
 * - Handles split/merge operations
 * - Updates dividers each tick
 * - Provides dropdown options for pages
 *
 * @param plugin The plugin instance
 * @param ui The FloatingUI for rendering
 * @param player The player viewing the pages
 * @param totalBounds The total available bounds for pages
 */
class PageManager(
    private val plugin: SchematioConnector,
    val ui: FloatingUI,
    val player: Player,
    private var totalBounds: PageBounds
) {
    /** Root content (either a single page or a container) */
    private var root: PageOrContainer? = null

    /** Registry of all pages by ID */
    private val pageRegistry = mutableMapOf<String, Page>()

    /** Registry of all containers by ID */
    private val containerRegistry = mutableMapOf<String, PageContainer>()

    /** Tick update task for dividers */
    private var updateTask: BukkitTask? = null

    /** Whether the manager has been destroyed */
    private var isDestroyed = false

    /** Current interaction mode */
    private var interactionMode = PageInteractionMode.NONE

    /** Page being interacted with */
    private var interactionPage: Page? = null

    /** Grabber elements for move/resize handles */
    private val interactionHandles = mutableListOf<GrabberElement>()

    /** Offset from page center when moving (to keep relative position) */
    private var moveOffset: Pair<Float, Float>? = null

    /** Which resize edge is being dragged */
    private var resizeEdge: ResizeEdge? = null

    /** Original bounds when starting resize */
    private var originalBounds: PageBounds? = null

    /** Navigation stack for wizard-style navigation */
    private val navigationStack = mutableListOf<PageContent>()

    /** Current page reference for navigation */
    private var currentPage: Page? = null

    init {
        startUpdateTask()
    }

    /**
     * Resize edge identifiers.
     */
    enum class ResizeEdge {
        TOP, BOTTOM, LEFT, RIGHT,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    /**
     * Create a new standalone page.
     *
     * @param content The content for the page
     * @param bounds Optional bounds (defaults to total bounds)
     * @param showChrome Whether to show close/options buttons
     * @return The created page
     */
    fun createPage(
        content: PageContent,
        bounds: PageBounds? = null,
        showChrome: Boolean = true
    ): Page {
        val pageBounds = bounds ?: totalBounds
        val page = Page(
            id = "page_${System.currentTimeMillis()}_${pageRegistry.size}",
            ui = ui,
            player = player,
            bounds = pageBounds,
            content = content,
            showChrome = showChrome
        )
        page.manager = this
        pageRegistry[page.id] = page

        // If this is the first page, set it as root
        if (root == null) {
            root = PageOrContainer.SinglePage(page)
        }

        return page
    }

    /**
     * Create and display a page as the root.
     */
    fun showPage(content: PageContent, showChrome: Boolean = true): Page {
        // Destroy existing root
        root?.destroy()
        root = null
        pageRegistry.clear()
        containerRegistry.clear()
        navigationStack.clear()

        val page = createPage(content, totalBounds, showChrome)
        root = PageOrContainer.SinglePage(page)
        currentPage = page
        navigationStack.add(content)
        page.render()

        return page
    }

    /**
     * Navigate to a new page (push onto navigation stack).
     * The new page replaces the current content but the previous can be returned to.
     *
     * @param content The new content to display
     */
    fun navigateTo(content: PageContent) {
        val page = currentPage ?: return

        // Push current content onto stack (it's already there)
        navigationStack.add(content)

        // Replace the page's content
        page.setContent(content)
        page.render()
    }

    /**
     * Navigate back to the previous page.
     *
     * @return true if navigation occurred, false if already at root
     */
    fun navigateBack(): Boolean {
        if (navigationStack.size <= 1) return false

        val page = currentPage ?: return false

        // Pop current content
        navigationStack.removeAt(navigationStack.size - 1)

        // Get previous content
        val previousContent = navigationStack.last()

        // Replace page content with previous
        page.setContent(previousContent)
        page.render()

        return true
    }

    /**
     * Check if there's a previous page to navigate back to.
     */
    fun canNavigateBack(): Boolean = navigationStack.size > 1

    /**
     * Get the current navigation depth (1 = root).
     */
    fun getNavigationDepth(): Int = navigationStack.size

    /**
     * Split a page into two panes.
     *
     * @param page The page to split
     * @param direction Split direction
     * @param newContent Content for the new pane
     * @param position Where to put the new content
     * @param ratio Initial split ratio
     * @return The created SplitContainer
     */
    fun splitPage(
        page: Page,
        direction: SplitDirection,
        newContent: PageContent,
        position: SplitPosition = SplitPosition.SECOND,
        ratio: Float = 0.5f
    ): SplitContainer {
        val currentBounds = page.bounds

        // Save the old container reference BEFORE creating new container
        // (because SplitContainer's init will update page.container)
        val oldContainer = page.container

        // Destroy the page's existing content (we'll re-render after)
        page.destroy()

        // Create new page with the new content
        val newPage = Page(
            id = "page_${System.currentTimeMillis()}_${pageRegistry.size}",
            ui = ui,
            player = player,
            bounds = currentBounds,  // Will be resized by container
            content = newContent,
            showChrome = page.showChrome
        )
        newPage.manager = this
        pageRegistry[newPage.id] = newPage

        // Determine first/second based on position
        val (first, second) = if (position == SplitPosition.FIRST) {
            Pair(PageOrContainer.SinglePage(newPage), PageOrContainer.SinglePage(page))
        } else {
            Pair(PageOrContainer.SinglePage(page), PageOrContainer.SinglePage(newPage))
        }

        // Create split container
        val newContainer = SplitContainer(
            id = "split_${System.currentTimeMillis()}",
            ui = ui,
            bounds = currentBounds,
            direction = direction,
            first = first,
            second = second,
            splitRatio = ratio
        )
        newContainer.manager = this
        containerRegistry[newContainer.id] = newContainer

        // Replace in tree using the OLD container reference
        replaceInTreeWithOldContainer(page, oldContainer, newContainer)

        // Render the new container (this will render children too)
        newContainer.render()

        return newContainer
    }

    /**
     * Merge two pages into tabs.
     *
     * @param page1 First page (becomes first tab)
     * @param page2 Second page (becomes second tab)
     * @return The created TabbedContainer
     */
    fun mergeToTabs(page1: Page, page2: Page): TabbedContainer {
        val bounds = page1.bounds

        // Create tabbed container
        val container = TabbedContainer(
            id = "tabs_${System.currentTimeMillis()}",
            ui = ui,
            bounds = bounds,
            pages = mutableListOf(page1, page2)
        )
        container.manager = this
        containerRegistry[container.id] = container

        // Replace in tree
        replaceInTree(page1, container)

        // Remove page2 from its current location if different
        if (page2.container != null && page2.container != page1.container) {
            page2.container?.removePage(page2)
        }

        // Render
        container.render()

        return container
    }

    /**
     * Close a page.
     *
     * If the page is in a container, it's removed from the container.
     * If standalone, it's destroyed.
     */
    fun closePage(page: Page) {
        val container = page.container

        if (container == null) {
            // Standalone page - just destroy it
            page.destroy()
            pageRegistry.remove(page.id)

            if (root is PageOrContainer.SinglePage && (root as PageOrContainer.SinglePage).page == page) {
                root = null
            }
        } else {
            // Page is in a container - remove it
            val remaining = container.removePage(page)
            page.destroy()
            pageRegistry.remove(page.id)

            if (remaining != null) {
                // Replace container with remaining content
                replaceInTree(container, remaining)
            }
        }
    }

    /**
     * Get dropdown options for a page.
     */
    fun getOptionsForPage(page: Page): List<DropdownItem> {
        val options = mutableListOf<DropdownItem>()

        // Resize option (only for standalone pages - containers handle their own sizing)
        if (page.container == null) {
            options.add(DropdownItem(
                id = "resize",
                label = "Resize",
                icon = "\u2922"  // Resize arrows
            ) {
                enterResizeMode(page)
            })

            // Move option (only for standalone pages)
            options.add(DropdownItem(
                id = "move",
                label = "Move",
                icon = "\u2725"  // Four-way arrow
            ) {
                enterMoveMode(page)
            })
        }

        return options
    }

    /**
     * Replace an item in the tree with another.
     * Uses the page's CURRENT container reference.
     */
    private fun replaceInTree(old: Page, new: PageContainer) {
        replaceInTreeWithOldContainer(old, old.container, new)
    }

    /**
     * Replace a page in the tree with a container, using an explicit old container reference.
     * This is needed when the page's container reference has already been updated.
     */
    private fun replaceInTreeWithOldContainer(old: Page, oldContainer: PageContainer?, new: PageContainer) {
        if (oldContainer == null) {
            // Old was root - just set new as root
            root = PageOrContainer.Nested(new)
        } else {
            // Old was in a container - update parent
            when (oldContainer) {
                is SplitContainer -> {
                    // Check if the page was in the first position
                    if (oldContainer.isInFirstPosition(old)) {
                        oldContainer.setFirst(PageOrContainer.Nested(new))
                    } else {
                        oldContainer.setSecond(PageOrContainer.Nested(new))
                    }
                    // Set the new container's parent
                    new.parent = oldContainer
                }
                is TabbedContainer -> {
                    // Cannot nest containers in tabs directly
                }
            }
        }
    }

    private fun replaceInTree(old: PageContainer, new: PageOrContainer) {
        val parent = old.parent

        if (parent == null) {
            // Old was root
            root = new
            containerRegistry.remove(old.id)
        } else {
            when (parent) {
                is SplitContainer -> {
                    if (parent.getPages().firstOrNull()?.container == old) {
                        parent.setFirst(new)
                    } else {
                        parent.setSecond(new)
                    }
                }
                is TabbedContainer -> {
                    // Tabbed containers don't nest other containers
                }
            }
            containerRegistry.remove(old.id)
        }
    }

    /**
     * Enter move mode for a page.
     * Shows a center grabber that can be dragged to move the page.
     */
    fun enterMoveMode(page: Page) {
        exitInteractionMode()

        interactionMode = PageInteractionMode.MOVE
        interactionPage = page

        // Create a center grabber for moving
        val bounds = page.bounds
        var grabber: GrabberElement? = null
        grabber = ui.addGrabber(
            offsetRight = bounds.centerX.toDouble(),
            offsetUp = bounds.centerY.toDouble(),
            offsetForward = -0.1,
            material = Material.LIGHT_BLUE_CONCRETE,
            hoverMaterial = Material.YELLOW_CONCRETE,
            size = 0.2f,
            visible = true
        ) {
            // onClick is called AFTER toggle state changes
            // When toggled ON (first click): start dragging (do nothing here, update loop handles it)
            // When toggled OFF (second click): confirm and exit
            if (grabber?.isToggled == false) {
                exitInteractionMode()
            }
        }
        interactionHandles.add(grabber)

        player.sendMessage(net.kyori.adventure.text.Component.text("§aClick the handle to grab, move, then click again to confirm."))
    }

    /**
     * Enter resize mode for a page.
     * Shows corner and edge grabbers that can be dragged to resize.
     */
    fun enterResizeMode(page: Page) {
        exitInteractionMode()

        interactionMode = PageInteractionMode.RESIZE
        interactionPage = page
        originalBounds = page.bounds

        val bounds = page.bounds
        val handleSize = 0.12f
        val z = -0.1

        // Corner grabbers - stored in order: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
        val corners = listOf(
            Triple(bounds.x, bounds.y, ResizeEdge.TOP_LEFT),
            Triple(bounds.right, bounds.y, ResizeEdge.TOP_RIGHT),
            Triple(bounds.x, bounds.bottom, ResizeEdge.BOTTOM_LEFT),
            Triple(bounds.right, bounds.bottom, ResizeEdge.BOTTOM_RIGHT)
        )

        for ((x, y, edge) in corners) {
            var grabber: GrabberElement? = null
            grabber = ui.addGrabber(
                offsetRight = x.toDouble(),
                offsetUp = y.toDouble(),
                offsetForward = z,
                material = Material.ORANGE_CONCRETE,
                hoverMaterial = Material.YELLOW_CONCRETE,
                size = handleSize,
                visible = true
            ) {
                // Set which edge we're resizing when toggled ON
                if (grabber?.isToggled == true) {
                    resizeEdge = edge
                    // Store the current bounds as original when starting drag
                    originalBounds = page.bounds
                } else {
                    // Toggled OFF - confirm resize and exit
                    resizeEdge = null
                    exitInteractionMode()
                }
            }
            interactionHandles.add(grabber)
        }

        // Edge grabbers (middle of each edge) - stored in order: TOP, BOTTOM, LEFT, RIGHT
        val edges = listOf(
            Triple(bounds.centerX, bounds.y, ResizeEdge.TOP),
            Triple(bounds.centerX, bounds.bottom, ResizeEdge.BOTTOM),
            Triple(bounds.x, bounds.centerY, ResizeEdge.LEFT),
            Triple(bounds.right, bounds.centerY, ResizeEdge.RIGHT)
        )

        for ((x, y, edge) in edges) {
            var grabber: GrabberElement? = null
            grabber = ui.addGrabber(
                offsetRight = x.toDouble(),
                offsetUp = y.toDouble(),
                offsetForward = z,
                material = Material.CYAN_CONCRETE,
                hoverMaterial = Material.YELLOW_CONCRETE,
                size = handleSize * 0.8f,
                visible = true
            ) {
                // Set which edge we're resizing when toggled ON
                if (grabber?.isToggled == true) {
                    resizeEdge = edge
                    // Store the current bounds as original when starting drag
                    originalBounds = page.bounds
                } else {
                    // Toggled OFF - confirm resize and exit
                    resizeEdge = null
                    exitInteractionMode()
                }
            }
            interactionHandles.add(grabber)
        }

        player.sendMessage(net.kyori.adventure.text.Component.text("§aClick a handle to grab, drag to resize, then click again to confirm."))
    }

    /**
     * Exit the current interaction mode.
     */
    fun exitInteractionMode() {
        // Remove all interaction handles
        for (handle in interactionHandles) {
            handle.destroy()
            ui.removeElement(handle)
        }
        interactionHandles.clear()

        interactionMode = PageInteractionMode.NONE
        interactionPage = null
        moveOffset = null
        resizeEdge = null
        originalBounds = null
    }

    /**
     * Start the update task for dividers and interaction modes.
     */
    private fun startUpdateTask() {
        updateTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (isDestroyed) return@Runnable
            updateDividers()
            updateInteractionMode()
        }, 0L, 1L)
    }

    /**
     * Update all active dividers.
     */
    private fun updateDividers() {
        for (container in containerRegistry.values) {
            if (container is SplitContainer) {
                container.getDivider()?.update()
            }
        }
    }

    /**
     * Update the current interaction mode (move/resize).
     */
    private fun updateInteractionMode() {
        val page = interactionPage ?: return

        when (interactionMode) {
            PageInteractionMode.MOVE -> updateMoveMode(page)
            PageInteractionMode.RESIZE -> updateResizeMode(page)
            PageInteractionMode.NONE -> {}
        }
    }

    /**
     * Update move mode - track the center grabber and move the page.
     */
    private fun updateMoveMode(page: Page) {
        val grabber = interactionHandles.firstOrNull() ?: return

        // Check if the grabber is being dragged (toggled state)
        if (grabber.isToggled) {
            // Get the mouse position on the UI plane
            val mousePos = ui.getMousePositionOnPlane() ?: return

            // Move the page to center on the mouse position
            val newBounds = PageBounds(
                x = mousePos.first.toFloat() - page.bounds.width / 2,
                y = mousePos.second.toFloat() + page.bounds.height / 2,
                width = page.bounds.width,
                height = page.bounds.height
            )

            page.resize(newBounds)

            // Update grabber position to follow the page center
            grabber.setPosition(newBounds.centerX.toDouble(), newBounds.centerY.toDouble())
        }
    }

    /**
     * Update resize mode - track edge grabbers and resize the page.
     */
    private fun updateResizeMode(page: Page) {
        val activeEdge = resizeEdge ?: return
        val original = originalBounds ?: return

        // Find a toggled grabber
        val activeGrabber = interactionHandles.find { it.isToggled } ?: return

        // Get mouse position
        val mousePos = ui.getMousePositionOnPlane() ?: return
        val mouseX = mousePos.first.toFloat()
        val mouseY = mousePos.second.toFloat()

        // Calculate new bounds based on which edge is being dragged
        var newX = original.x
        var newY = original.y
        var newWidth = original.width
        var newHeight = original.height

        val minSize = 0.5f

        when (activeEdge) {
            ResizeEdge.LEFT -> {
                val delta = mouseX - original.x
                newX = mouseX
                newWidth = (original.width - delta).coerceAtLeast(minSize)
            }
            ResizeEdge.RIGHT -> {
                newWidth = (mouseX - original.x).coerceAtLeast(minSize)
            }
            ResizeEdge.TOP -> {
                val delta = mouseY - original.y
                newY = mouseY
                newHeight = (original.height + delta).coerceAtLeast(minSize)
            }
            ResizeEdge.BOTTOM -> {
                val delta = original.bottom - mouseY
                newHeight = (original.height + delta).coerceAtLeast(minSize)
            }
            ResizeEdge.TOP_LEFT -> {
                val deltaX = mouseX - original.x
                val deltaY = mouseY - original.y
                newX = mouseX
                newY = mouseY
                newWidth = (original.width - deltaX).coerceAtLeast(minSize)
                newHeight = (original.height + deltaY).coerceAtLeast(minSize)
            }
            ResizeEdge.TOP_RIGHT -> {
                val deltaY = mouseY - original.y
                newY = mouseY
                newWidth = (mouseX - original.x).coerceAtLeast(minSize)
                newHeight = (original.height + deltaY).coerceAtLeast(minSize)
            }
            ResizeEdge.BOTTOM_LEFT -> {
                val deltaX = mouseX - original.x
                newX = mouseX
                newWidth = (original.width - deltaX).coerceAtLeast(minSize)
                newHeight = (original.y - mouseY + original.height).coerceAtLeast(minSize)
            }
            ResizeEdge.BOTTOM_RIGHT -> {
                newWidth = (mouseX - original.x).coerceAtLeast(minSize)
                newHeight = (original.y - mouseY + original.height).coerceAtLeast(minSize)
            }
        }

        val newBounds = PageBounds(newX, newY, newWidth, newHeight)
        page.resize(newBounds)

        // Update handle positions to follow the new bounds
        updateResizeHandlePositions(newBounds)
    }

    /**
     * Update resize handle positions to match new bounds.
     * Handles are stored in order: 4 corners (TL, TR, BL, BR) then 4 edges (T, B, L, R)
     */
    private fun updateResizeHandlePositions(bounds: PageBounds) {
        if (interactionHandles.size < 8) return

        // Corner positions: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
        interactionHandles[0].setPosition(bounds.x.toDouble(), bounds.y.toDouble())
        interactionHandles[1].setPosition(bounds.right.toDouble(), bounds.y.toDouble())
        interactionHandles[2].setPosition(bounds.x.toDouble(), bounds.bottom.toDouble())
        interactionHandles[3].setPosition(bounds.right.toDouble(), bounds.bottom.toDouble())

        // Edge positions: TOP, BOTTOM, LEFT, RIGHT
        interactionHandles[4].setPosition(bounds.centerX.toDouble(), bounds.y.toDouble())
        interactionHandles[5].setPosition(bounds.centerX.toDouble(), bounds.bottom.toDouble())
        interactionHandles[6].setPosition(bounds.x.toDouble(), bounds.centerY.toDouble())
        interactionHandles[7].setPosition(bounds.right.toDouble(), bounds.centerY.toDouble())
    }

    /**
     * Get all pages.
     */
    fun getPages(): List<Page> = pageRegistry.values.toList()

    /**
     * Get a page by ID.
     */
    fun getPage(id: String): Page? = pageRegistry[id]

    /**
     * Resize the total bounds and re-render.
     */
    fun resize(newBounds: PageBounds) {
        totalBounds = newBounds
        root?.resize(newBounds)
        root?.render()
    }

    /**
     * Destroy the manager and all pages.
     */
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        updateTask?.cancel()
        updateTask = null

        root?.destroy()
        root = null

        pageRegistry.clear()
        containerRegistry.clear()
    }
}
