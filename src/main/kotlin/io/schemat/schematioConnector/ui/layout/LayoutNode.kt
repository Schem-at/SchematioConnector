package io.schemat.schematioConnector.ui.layout

/**
 * Base interface for all layout nodes in the tree.
 * Layout happens in two phases:
 * 1. Measure - determine intrinsic size given constraints
 * 2. Place - position node at a specific offset
 */
interface LayoutNode {
    /** Unique identifier for this node */
    val id: String

    /** Parent node (null for root) */
    var parent: LayoutNode?

    /** Child nodes */
    val children: List<LayoutNode>

    /** Computed layout result after measure/place */
    var layoutResult: LayoutResult?

    /** Padding inside this node */
    var padding: Padding

    // Sizing properties
    /** Fixed width (null = use constraints/intrinsic) */
    var width: Float?
    /** Fixed height (null = use constraints/intrinsic) */
    var height: Float?
    /** Minimum width */
    var minWidth: Float
    /** Maximum width */
    var maxWidth: Float
    /** Minimum height */
    var minHeight: Float
    /** Maximum height */
    var maxHeight: Float

    // Flex properties
    /** Flex grow factor (0 = don't grow) */
    var flexGrow: Float
    /** Flex shrink factor (1 = can shrink) */
    var flexShrink: Float
    /** Flex basis (initial size before grow/shrink, null = use content size) */
    var flexBasis: Float?

    /**
     * Measure this node given constraints.
     * Returns the computed size.
     */
    fun measure(constraints: Constraints): Size

    /**
     * Place this node at the given offset.
     * Should be called after measure().
     */
    fun place(offset: Offset)

    /**
     * Add a child node
     */
    fun addChild(child: LayoutNode)

    /**
     * Remove a child node
     */
    fun removeChild(child: LayoutNode)

    /**
     * Remove all children
     */
    fun clearChildren()

    /**
     * Get the absolute position (relative to root)
     */
    fun getAbsoluteOffset(): Offset {
        val parentOffset = parent?.getAbsoluteOffset() ?: Offset.Zero
        val myOffset = layoutResult?.offset ?: Offset.Zero
        return parentOffset + myOffset
    }
}

/**
 * Abstract base implementation of LayoutNode with common functionality
 */
abstract class BaseLayoutNode(
    override val id: String
) : LayoutNode {

    override var parent: LayoutNode? = null

    protected val _children = mutableListOf<LayoutNode>()
    override val children: List<LayoutNode> get() = _children

    override var layoutResult: LayoutResult? = null

    override var padding: Padding = Padding.Zero

    // Sizing
    override var width: Float? = null
    override var height: Float? = null
    override var minWidth: Float = 0f
    override var maxWidth: Float = Float.MAX_VALUE
    override var minHeight: Float = 0f
    override var maxHeight: Float = Float.MAX_VALUE

    // Flex
    override var flexGrow: Float = 0f
    override var flexShrink: Float = 1f
    override var flexBasis: Float? = null

    override fun addChild(child: LayoutNode) {
        child.parent = this
        _children.add(child)
    }

    override fun removeChild(child: LayoutNode) {
        child.parent = null
        _children.remove(child)
    }

    override fun clearChildren() {
        _children.forEach { it.parent = null }
        _children.clear()
    }

    override fun place(offset: Offset) {
        layoutResult = layoutResult?.copy(offset = offset) ?: LayoutResult(offset, Size.Zero)
    }

    /**
     * Apply size constraints from this node's properties
     */
    protected fun applyOwnConstraints(constraints: Constraints): Constraints {
        var c = constraints

        // Apply fixed width if set
        width?.let { w ->
            c = c.copy(minWidth = w, maxWidth = w)
        }

        // Apply fixed height if set
        height?.let { h ->
            c = c.copy(minHeight = h, maxHeight = h)
        }

        // Apply min/max constraints
        c = c.copy(
            minWidth = maxOf(c.minWidth, minWidth),
            maxWidth = minOf(c.maxWidth, maxWidth),
            minHeight = maxOf(c.minHeight, minHeight),
            maxHeight = minOf(c.maxHeight, maxHeight)
        )

        return c
    }

    /**
     * Finalize size by applying constraints
     */
    protected fun finalizeSize(size: Size, constraints: Constraints): Size {
        return Size(
            width = constraints.constrainWidth(size.width),
            height = constraints.constrainHeight(size.height)
        )
    }
}

/**
 * Leaf node - has no children, represents a concrete UI element with intrinsic size
 */
class LeafNode(
    id: String,
    /** Intrinsic width of this element */
    var intrinsicWidth: Float = 0f,
    /** Intrinsic height of this element */
    var intrinsicHeight: Float = 0f
) : BaseLayoutNode(id) {

    override fun measure(constraints: Constraints): Size {
        val c = applyOwnConstraints(constraints)

        // Determine size:
        // - If we have intrinsic size, use it (constrained)
        // - If intrinsic size is 0 but we have bounded constraints (e.g., from Stretch),
        //   expand to fill the available space
        val effectiveWidth = when {
            intrinsicWidth > 0 -> intrinsicWidth
            c.hasFixedWidth -> c.maxWidth
            c.hasBoundedWidth && c.maxWidth < 10000f -> c.maxWidth
            else -> 0f
        }

        val effectiveHeight = when {
            intrinsicHeight > 0 -> intrinsicHeight
            c.hasFixedHeight -> c.maxHeight
            c.hasBoundedHeight && c.maxHeight < 10000f -> c.maxHeight
            else -> 0f
        }

        val size = finalizeSize(
            Size(effectiveWidth, effectiveHeight),
            c
        )

        layoutResult = LayoutResult(Offset.Zero, size)
        return size
    }

    override fun addChild(child: LayoutNode) {
        throw UnsupportedOperationException("LeafNode cannot have children")
    }
}

/**
 * Box node - single child container with padding support
 */
class BoxNode(id: String) : BaseLayoutNode(id) {

    override fun measure(constraints: Constraints): Size {
        val c = applyOwnConstraints(constraints)

        // Constraints for child (accounting for padding)
        val childConstraints = Constraints(
            minWidth = (c.minWidth - padding.horizontal).coerceAtLeast(0f),
            maxWidth = (c.maxWidth - padding.horizontal).coerceAtLeast(0f),
            minHeight = (c.minHeight - padding.vertical).coerceAtLeast(0f),
            maxHeight = (c.maxHeight - padding.vertical).coerceAtLeast(0f)
        )

        // Measure single child
        val child = _children.firstOrNull()
        val childSize = child?.measure(childConstraints) ?: Size.Zero

        // Our size = child size + padding
        val size = finalizeSize(
            Size(
                childSize.width + padding.horizontal,
                childSize.height + padding.vertical
            ),
            c
        )

        layoutResult = LayoutResult(Offset.Zero, size)

        // Place child with padding offset
        child?.place(Offset(padding.left, padding.top))

        return size
    }
}
