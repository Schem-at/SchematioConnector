package io.schemat.schematioConnector.ui.page

import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.UIElement
import io.schemat.schematioConnector.ui.layout.Layout

/**
 * Abstract base class for page content.
 *
 * Subclasses define what is displayed inside a Page by:
 * 1. Building a layout tree in [buildLayout]
 * 2. Rendering UI elements based on layout results in [render]
 *
 * The Page handles lifecycle, sizing, and chrome. PageContent focuses
 * purely on the content area.
 */
abstract class PageContent(
    /** Unique identifier for this content type */
    val id: String,
    /** Display title for this content (shown in tabs, title bar, etc.) */
    val title: String = id
) {
    /** Reference to the containing page (set when attached) */
    var page: Page? = null
        internal set

    /** Currently rendered UI elements (for cleanup) */
    protected val elements = mutableListOf<UIElement>()

    /** Current layout instance */
    protected var layout: Layout? = null

    /**
     * Build the layout tree for this content.
     *
     * This method should use the Layout DSL to define the structure:
     * ```kotlin
     * override fun buildLayout(width: Float, height: Float): Layout {
     *     return Layout(width, height).apply {
     *         column("root", gap = 0.1f) {
     *             row("header", height = 0.5f) { ... }
     *             leaf("content", flexGrow = 1f)
     *         }
     *         compute()
     *     }
     * }
     * ```
     *
     * @param width Available width for the content
     * @param height Available height for the content
     * @return The computed Layout
     */
    abstract fun buildLayout(width: Float, height: Float): Layout

    /**
     * Render UI elements based on the computed layout.
     *
     * Use the provided [LayoutRenderer] to convert layout positions
     * to UI coordinates and create elements:
     * ```kotlin
     * override fun render(ui: FloatingUI, renderer: LayoutRenderer, bounds: PageBounds) {
     *     renderer.renderPanel("header", Material.BLUE_CONCRETE)?.let { elements.add(it) }
     *     renderer.renderLabel("title", "Hello")?.let { elements.add(it) }
     * }
     * ```
     *
     * @param ui The FloatingUI to add elements to
     * @param renderer Helper for converting layout to UI coordinates
     * @param bounds The bounds of this content area
     */
    abstract fun render(ui: FloatingUI, renderer: LayoutRenderer, bounds: PageBounds)

    /**
     * Called when the page is resized.
     * Default implementation triggers a full rebuild.
     */
    open fun onResize(newWidth: Float, newHeight: Float) {
        rebuild()
    }

    /**
     * Called when content state changes and UI needs updating.
     * Override to handle partial updates without full rebuild.
     */
    open fun onStateChanged() {
        rebuild()
    }

    /**
     * Rebuild the content (clear and re-render).
     */
    fun rebuild() {
        val p = page ?: return
        destroy()

        val contentBounds = p.getContentBounds()
        layout = buildLayout(contentBounds.width, contentBounds.height)

        val renderer = LayoutRenderer(p.ui, layout!!, contentBounds)
        render(p.ui, renderer, contentBounds)
    }

    /**
     * Clean up all rendered elements.
     */
    open fun destroy() {
        elements.forEach { element ->
            element.destroy()
            page?.ui?.removeElement(element)
        }
        elements.clear()
        layout = null
    }

    /**
     * Called when this content is attached to a page.
     */
    open fun onAttached(page: Page) {
        this.page = page
    }

    /**
     * Called when this content is detached from a page.
     */
    open fun onDetached() {
        destroy()
        this.page = null
    }
}

/**
 * Simple content that just shows a colored panel with optional title.
 * Useful for testing and as a placeholder.
 */
class SimpleContent(
    id: String,
    title: String = id,
    private val material: org.bukkit.Material = org.bukkit.Material.GRAY_CONCRETE
) : PageContent(id, title) {

    override fun buildLayout(width: Float, height: Float): Layout {
        return Layout(width = width, height = height).apply {
            build {
                leaf("background", width = width, height = height)
            }
            compute()
        }
    }

    override fun render(ui: FloatingUI, renderer: LayoutRenderer, bounds: PageBounds) {
        renderer.renderPanel("background", material)?.let { elements.add(it) }

        // Add title label if there's enough space
        if (bounds.height > 0.5f) {
            ui.addLabel(
                offsetRight = bounds.centerX.toDouble(),
                offsetUp = (bounds.y - 0.2).toDouble(),
                offsetForward = -0.05,
                text = title,
                scale = 0.4f
            ).let { elements.add(it) }
        }
    }
}
