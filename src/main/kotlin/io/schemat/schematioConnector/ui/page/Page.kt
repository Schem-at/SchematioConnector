package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.PanelElement
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * A Page is the primary UI unit in the Page system.
 *
 * Each Page:
 * - Has defined bounds (position and size)
 * - Contains PageContent that uses the layout engine
 * - Has chrome (close button, options button) unless hidden
 * - Can be standalone or part of a container (split/tabbed)
 *
 * @param id Unique identifier for this page
 * @param ui The FloatingUI this page renders to
 * @param player The player viewing this page
 * @param bounds Position and size of this page
 * @param content The content to display
 * @param showChrome Whether to show close/options buttons
 */
class Page(
    val id: String,
    val ui: FloatingUI,
    val player: Player,
    var bounds: PageBounds,
    private var content: PageContent,
    var showChrome: Boolean = true
) {
    /** Parent container (null if standalone) */
    var container: PageContainer? = null
        internal set

    /** Reference to the page manager */
    var manager: PageManager? = null
        internal set

    /** Chrome elements (close button, options button) */
    private var chrome: PageChrome? = null

    /** Background panel for the page */
    private var backgroundPanel: PanelElement? = null

    /** Frame panels around the page */
    private val framePanels = mutableListOf<PanelElement>()

    /** Whether this page has been rendered */
    private var isRendered = false

    /** Whether this page has been destroyed */
    private var isDestroyed = false

    /** Frame thickness */
    private val frameThickness = 0.06f

    /** Height reserved for chrome at the top */
    private val chromeHeight: Float
        get() = if (showChrome) PageChrome.CHROME_HEIGHT else 0f

    init {
        content.onAttached(this)
    }

    /**
     * Get the bounds available for content (excluding chrome area).
     */
    fun getContentBounds(): PageBounds {
        return if (showChrome) {
            bounds.inset(0f, chromeHeight, 0f, 0f)
        } else {
            bounds
        }
    }

    /**
     * Render this page and its content.
     */
    fun render() {
        if (isDestroyed) return

        // Render background panel
        renderBackground()

        // Render chrome if enabled
        if (showChrome) {
            chrome?.destroy()
            chrome = PageChrome(this, ui)
            chrome?.render(bounds)
        }

        // Render content
        content.rebuild()

        isRendered = true
    }

    /**
     * Render the page background and frame.
     */
    private fun renderBackground() {
        // Remove existing background/frame
        backgroundPanel?.let { ui.removeElement(it) }
        framePanels.forEach { ui.removeElement(it) }
        framePanels.clear()

        // Main background panel (at the back)
        backgroundPanel = ui.addPanel(
            offsetRight = bounds.centerX.toDouble(),
            offsetUp = bounds.centerY.toDouble(),
            offsetForward = 0.05,  // Behind content
            width = bounds.width,
            height = bounds.height,
            material = Material.WHITE_CONCRETE
        )

        // Frame around the page
        val halfW = bounds.width / 2
        val halfH = bounds.height / 2

        // Top frame
        framePanels.add(ui.addPanel(
            offsetRight = bounds.centerX.toDouble(),
            offsetUp = (bounds.y + frameThickness / 2).toDouble(),
            offsetForward = 0.02,
            width = bounds.width + frameThickness * 2,
            height = frameThickness,
            material = Material.BLACK_CONCRETE
        ))

        // Bottom frame
        framePanels.add(ui.addPanel(
            offsetRight = bounds.centerX.toDouble(),
            offsetUp = (bounds.bottom - frameThickness / 2).toDouble(),
            offsetForward = 0.02,
            width = bounds.width + frameThickness * 2,
            height = frameThickness,
            material = Material.BLACK_CONCRETE
        ))

        // Left frame
        framePanels.add(ui.addPanel(
            offsetRight = (bounds.x - frameThickness / 2).toDouble(),
            offsetUp = bounds.centerY.toDouble(),
            offsetForward = 0.02,
            width = frameThickness,
            height = bounds.height,
            material = Material.BLACK_CONCRETE
        ))

        // Right frame
        framePanels.add(ui.addPanel(
            offsetRight = (bounds.right + frameThickness / 2).toDouble(),
            offsetUp = bounds.centerY.toDouble(),
            offsetForward = 0.02,
            width = frameThickness,
            height = bounds.height,
            material = Material.BLACK_CONCRETE
        ))
    }

    /**
     * Resize this page to new bounds and re-render.
     */
    fun resize(newBounds: PageBounds) {
        if (isDestroyed) return

        bounds = newBounds

        // Re-render background and frame
        renderBackground()

        // Re-render chrome
        chrome?.destroy()
        if (showChrome) {
            chrome = PageChrome(this, ui)
            chrome?.render(bounds)
        }

        // Notify content of resize
        val contentBounds = getContentBounds()
        content.onResize(contentBounds.width, contentBounds.height)
    }

    /**
     * Set new content for this page.
     */
    fun setContent(newContent: PageContent) {
        content.onDetached()
        content = newContent
        content.onAttached(this)

        if (isRendered) {
            content.rebuild()
        }
    }

    /**
     * Get the current content.
     */
    fun getContent(): PageContent = content

    /**
     * Close this page.
     *
     * If the page is in a container, it will be removed from the container.
     * If standalone, it will be destroyed.
     */
    fun close() {
        manager?.closePage(this) ?: destroy()
    }

    /**
     * Destroy this page and all its elements.
     */
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        chrome?.destroy()
        chrome = null

        // Clean up background and frame panels
        backgroundPanel?.let { ui.removeElement(it) }
        backgroundPanel = null
        framePanels.forEach { ui.removeElement(it) }
        framePanels.clear()

        content.onDetached()

        isRendered = false
    }

    /**
     * Detach this page from its container (for moving/merging).
     */
    internal fun detachFromContainer() {
        container = null
    }

    /**
     * Get dropdown options for this page.
     */
    internal fun getDropdownOptions(): List<DropdownItem> {
        return manager?.getOptionsForPage(this) ?: emptyList()
    }
}
