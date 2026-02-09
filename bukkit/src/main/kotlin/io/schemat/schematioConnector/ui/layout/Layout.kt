package io.schemat.schematioConnector.ui.layout

/**
 * Layout manager - the main entry point for using the layout system.
 *
 * Example usage:
 * ```kotlin
 * val layout = Layout(width = 4f, height = 3f)
 *
 * // Create a column with some rows
 * layout.column("root", gap = 0.1f) {
 *     row("header", height = 0.3f) {
 *         leaf("title", width = 2f, height = 0.3f)
 *         spacer(flexGrow = 1f)
 *         leaf("close", width = 0.3f, height = 0.3f)
 *     }
 *     row("content", flexGrow = 1f) {
 *         leaf("sidebar", width = 0.5f)
 *         leaf("main", flexGrow = 1f)
 *     }
 * }
 *
 * // Compute layout
 * layout.compute()
 *
 * // Get positions for your UI elements
 * val titlePos = layout.getResult("title")  // LayoutResult(offset, size)
 * ```
 */
class Layout(
    /** Total available width */
    val width: Float,
    /** Total available height */
    val height: Float
) {
    private var root: LayoutNode? = null
    private val nodeMap = mutableMapOf<String, LayoutNode>()

    /**
     * Set the root node of the layout tree
     */
    fun setRoot(node: LayoutNode) {
        root = node
        registerNode(node)
    }

    private fun registerNode(node: LayoutNode) {
        nodeMap[node.id] = node
        node.children.forEach { registerNode(it) }
    }

    /**
     * Compute the layout - measures and positions all nodes
     */
    fun compute() {
        val rootNode = root ?: return
        val constraints = Constraints.fixed(width, height)
        rootNode.measure(constraints)
        rootNode.place(Offset.Zero)
    }

    /**
     * Get the layout result for a specific node by ID
     */
    fun getResult(id: String): LayoutResult? = nodeMap[id]?.layoutResult

    /**
     * Get the absolute position for a node (relative to root)
     */
    fun getAbsolutePosition(id: String): Offset? = nodeMap[id]?.getAbsoluteOffset()

    /**
     * Get a node by ID
     */
    fun getNode(id: String): LayoutNode? = nodeMap[id]

    /**
     * DSL builder for creating layout trees
     */
    inner class LayoutBuilder(private val parent: LayoutNode?) {

        /**
         * Create a leaf node (no children, has intrinsic size)
         */
        fun leaf(
            id: String,
            intrinsicWidth: Float = 0f,
            intrinsicHeight: Float = 0f,
            width: Float? = null,
            height: Float? = null,
            flexGrow: Float = 0f,
            flexShrink: Float = 1f,
            flexBasis: Float? = null,
            padding: Padding = Padding.Zero
        ): LeafNode {
            val node = LeafNode(id, intrinsicWidth, intrinsicHeight).apply {
                this.width = width
                this.height = height
                this.flexGrow = flexGrow
                this.flexShrink = flexShrink
                this.flexBasis = flexBasis
                this.padding = padding
            }
            addToParentOrRoot(node)
            return node
        }

        /**
         * Create a spacer (flexible empty space)
         */
        fun spacer(
            id: String = "spacer_${System.nanoTime()}",
            flexGrow: Float = 1f,
            width: Float? = null,
            height: Float? = null
        ): LeafNode {
            return leaf(id, width = width, height = height, flexGrow = flexGrow)
        }

        /**
         * Create a box container (single child with padding)
         */
        fun box(
            id: String,
            width: Float? = null,
            height: Float? = null,
            padding: Padding = Padding.Zero,
            flexGrow: Float = 0f,
            flexShrink: Float = 1f,
            block: LayoutBuilder.() -> Unit = {}
        ): BoxNode {
            val node = BoxNode(id).apply {
                this.width = width
                this.height = height
                this.padding = padding
                this.flexGrow = flexGrow
                this.flexShrink = flexShrink
            }
            addToParentOrRoot(node)
            LayoutBuilder(node).block()
            return node
        }

        /**
         * Create a row container (horizontal flex layout)
         */
        fun row(
            id: String,
            width: Float? = null,
            height: Float? = null,
            padding: Padding = Padding.Zero,
            gap: Float = 0f,
            mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
            crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
            flexGrow: Float = 0f,
            flexShrink: Float = 1f,
            block: LayoutBuilder.() -> Unit = {}
        ): FlexNode {
            val node = RowNode(id, mainAxisAlignment, crossAxisAlignment, gap).apply {
                this.width = width
                this.height = height
                this.padding = padding
                this.flexGrow = flexGrow
                this.flexShrink = flexShrink
            }
            addToParentOrRoot(node)
            LayoutBuilder(node).block()
            return node
        }

        /**
         * Create a column container (vertical flex layout)
         */
        fun column(
            id: String,
            width: Float? = null,
            height: Float? = null,
            padding: Padding = Padding.Zero,
            gap: Float = 0f,
            mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
            crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
            flexGrow: Float = 0f,
            flexShrink: Float = 1f,
            block: LayoutBuilder.() -> Unit = {}
        ): FlexNode {
            val node = ColumnNode(id, mainAxisAlignment, crossAxisAlignment, gap).apply {
                this.width = width
                this.height = height
                this.padding = padding
                this.flexGrow = flexGrow
                this.flexShrink = flexShrink
            }
            addToParentOrRoot(node)
            LayoutBuilder(node).block()
            return node
        }

        private fun addToParentOrRoot(node: LayoutNode) {
            nodeMap[node.id] = node
            if (parent != null) {
                parent.addChild(node)
            } else {
                root = node
            }
        }
    }

    /**
     * DSL entry point - build the layout tree
     */
    fun build(block: LayoutBuilder.() -> Unit) {
        LayoutBuilder(null).block()
    }

    /**
     * Convenience: create a row as the root
     */
    fun row(
        id: String,
        padding: Padding = Padding.Zero,
        gap: Float = 0f,
        mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
        crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
        block: LayoutBuilder.() -> Unit = {}
    ): FlexNode {
        val node = RowNode(id, mainAxisAlignment, crossAxisAlignment, gap).apply {
            this.padding = padding
            this.width = this@Layout.width
            this.height = this@Layout.height
        }
        root = node
        nodeMap[node.id] = node
        LayoutBuilder(node).block()
        return node
    }

    /**
     * Convenience: create a column as the root
     */
    fun column(
        id: String,
        padding: Padding = Padding.Zero,
        gap: Float = 0f,
        mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
        crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
        block: LayoutBuilder.() -> Unit = {}
    ): FlexNode {
        val node = ColumnNode(id, mainAxisAlignment, crossAxisAlignment, gap).apply {
            this.padding = padding
            this.width = this@Layout.width
            this.height = this@Layout.height
        }
        root = node
        nodeMap[node.id] = node
        LayoutBuilder(node).block()
        return node
    }

    /**
     * Clear the layout and start fresh
     */
    fun clear() {
        root = null
        nodeMap.clear()
    }

    /**
     * Debug: print the layout tree
     */
    fun debugPrint(): String {
        val sb = StringBuilder()
        fun printNode(node: LayoutNode, indent: Int) {
            val prefix = "  ".repeat(indent)
            val result = node.layoutResult
            sb.appendLine("$prefix${node.id}: ${result?.offset} ${result?.size}")
            node.children.forEach { printNode(it, indent + 1) }
        }
        root?.let { printNode(it, 0) }
        return sb.toString()
    }
}
