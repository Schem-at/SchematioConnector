package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.ui.FloatingUI

/**
 * Direction for split containers.
 */
enum class SplitDirection {
    /** Left/Right split */
    HORIZONTAL,
    /** Top/Bottom split */
    VERTICAL
}

/**
 * Represents either a single Page or a nested PageContainer.
 * Used for recursive container structures.
 */
sealed class PageOrContainer {
    data class SinglePage(val page: Page) : PageOrContainer()
    data class Nested(val container: PageContainer) : PageOrContainer()

    fun getPages(): List<Page> = when (this) {
        is SinglePage -> listOf(page)
        is Nested -> container.getPages()
    }

    fun render() {
        when (this) {
            is SinglePage -> page.render()
            is Nested -> container.render()
        }
    }

    fun resize(newBounds: PageBounds) {
        when (this) {
            is SinglePage -> page.resize(newBounds)
            is Nested -> container.resize(newBounds)
        }
    }

    fun destroy() {
        when (this) {
            is SinglePage -> page.destroy()
            is Nested -> container.destroy()
        }
    }
}

/**
 * Abstract base class for page containers.
 *
 * Containers hold multiple pages and manage their layout.
 * Containers can be nested for recursive splitting.
 *
 * @param id Unique identifier for this container
 * @param ui The FloatingUI to render to
 * @param bounds The bounds of this container
 */
sealed class PageContainer(
    val id: String,
    val ui: FloatingUI,
    var bounds: PageBounds
) {
    /** Parent container (for nested containers) */
    var parent: PageContainer? = null
        internal set

    /** Reference to the page manager */
    var manager: PageManager? = null
        internal set

    /**
     * Get all pages in this container (recursively).
     */
    abstract fun getPages(): List<Page>

    /**
     * Render this container and its contents.
     */
    abstract fun render()

    /**
     * Resize this container to new bounds.
     */
    abstract fun resize(newBounds: PageBounds)

    /**
     * Clean up this container and its contents.
     */
    abstract fun destroy()

    /**
     * Remove a page from this container.
     *
     * @param page The page to remove
     * @return The remaining PageOrContainer, or null if container is now empty
     */
    abstract fun removePage(page: Page): PageOrContainer?

    /**
     * Check if this container contains the given page.
     */
    fun containsPage(page: Page): Boolean {
        return getPages().contains(page)
    }
}
