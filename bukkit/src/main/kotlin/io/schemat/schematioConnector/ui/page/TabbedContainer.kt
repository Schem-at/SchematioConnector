package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.UIElement
import io.schemat.schematioConnector.ui.components.TabDefinition
import io.schemat.schematioConnector.ui.components.TabsElement

/**
 * A container that shows pages as tabs.
 *
 * Displays a tab bar at the top with one tab per page.
 * Only the selected page is visible at any time.
 *
 * @param id Unique identifier
 * @param ui The FloatingUI to render to
 * @param bounds The bounds of this container
 * @param pages The pages to show as tabs
 */
class TabbedContainer(
    id: String,
    ui: FloatingUI,
    bounds: PageBounds,
    private val pages: MutableList<Page>
) : PageContainer(id, ui, bounds) {

    companion object {
        const val TAB_BAR_HEIGHT = 0.35f
        const val TAB_WIDTH = 0.9f
        const val TAB_GAP = 0.05f
    }

    /** Currently selected tab index */
    private var selectedIndex: Int = 0

    /** Tab bar element */
    private var tabBar: TabsElement? = null

    /** Track UI elements for cleanup */
    private val elements = mutableListOf<UIElement>()

    init {
        // Set container reference for all pages
        pages.forEach { it.container = this }
    }

    override fun getPages(): List<Page> = pages.toList()

    override fun render() {
        // Clear previous elements
        destroyElements()

        // Render tab bar
        renderTabBar()

        // Calculate content bounds (below tab bar)
        val contentBounds = bounds.inset(0f, TAB_BAR_HEIGHT, 0f, 0f)

        // Resize all pages to content bounds (they share the same space)
        pages.forEachIndexed { index, page ->
            page.resize(contentBounds)

            // Only render the selected page
            if (index == selectedIndex) {
                page.render()
            }
        }
    }

    override fun resize(newBounds: PageBounds) {
        bounds = newBounds
        render()
    }

    override fun destroy() {
        destroyElements()
        pages.forEach { it.destroy() }
        pages.clear()
    }

    override fun removePage(page: Page): PageOrContainer? {
        val index = pages.indexOf(page)
        if (index < 0) return null

        page.detachFromContainer()
        pages.removeAt(index)

        // Adjust selected index if needed
        if (selectedIndex >= pages.size) {
            selectedIndex = (pages.size - 1).coerceAtLeast(0)
        }

        return when {
            pages.isEmpty() -> null  // Container is now empty
            pages.size == 1 -> PageOrContainer.SinglePage(pages.first())  // Convert to single page
            else -> {
                render()  // Re-render with updated tabs
                null  // Container still exists
            }
        }
    }

    /**
     * Select a tab by index.
     */
    fun selectTab(index: Int) {
        if (index < 0 || index >= pages.size) return
        if (index == selectedIndex) return

        // Hide current page
        // (Pages are re-rendered on selection, so no explicit hide needed)

        selectedIndex = index
        render()
    }

    /**
     * Add a page as a new tab.
     */
    fun addPage(page: Page) {
        page.container = this
        pages.add(page)
        render()
    }

    /**
     * Get the currently selected page.
     */
    fun getSelectedPage(): Page? = pages.getOrNull(selectedIndex)

    /**
     * Get the selected tab index.
     */
    fun getSelectedIndex(): Int = selectedIndex

    /**
     * Render the tab bar.
     */
    private fun renderTabBar() {
        val tabDefinitions = pages.map { page ->
            TabDefinition(
                id = page.id,
                label = page.getContent().title
            )
        }

        val tabBarCenterX = bounds.centerX
        val tabBarCenterY = bounds.y - TAB_BAR_HEIGHT / 2

        tabBar = ui.addTabs(
            offsetRight = tabBarCenterX.toDouble(),
            offsetUp = tabBarCenterY.toDouble(),
            offsetForward = -0.02,
            tabs = tabDefinitions,
            tabWidth = TAB_WIDTH,
            tabHeight = TAB_BAR_HEIGHT - 0.05f,
            gap = TAB_GAP,
            selectedIndex = selectedIndex
        ) { index, _ ->
            selectTab(index)
        }

        elements.add(tabBar!!)
        tabBar!!.getTabButtons().forEach { elements.add(it) }
    }

    /**
     * Clean up UI elements (not pages).
     */
    private fun destroyElements() {
        elements.forEach {
            it.destroy()
            ui.removeElement(it)
        }
        elements.clear()
        tabBar = null
    }
}
