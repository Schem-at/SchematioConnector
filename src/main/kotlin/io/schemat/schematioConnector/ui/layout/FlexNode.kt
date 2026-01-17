package io.schemat.schematioConnector.ui.layout

/**
 * Flex container node - implements flexbox-like layout for Row/Column.
 *
 * Layout algorithm (two-pass approach inspired by CLAY):
 *
 * Pass 1 - Main axis sizing:
 * 1. Measure fixed-size children to get their main axis size
 * 2. Calculate remaining space after fixed children and gaps
 * 3. Distribute remaining space to flex-grow children
 * 4. Handle shrinking if total exceeds available space
 *
 * Pass 2 - Cross axis sizing and final layout:
 * 1. Re-measure all children with their final main axis size
 * 2. This allows nested containers to know their constraints
 * 3. Position children based on alignment
 */
class FlexNode(
    id: String,
    /** Direction of the main axis */
    var direction: FlexDirection = FlexDirection.Row
) : BaseLayoutNode(id) {

    /** How to align children along the main axis */
    var mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start

    /** How to align children on the cross axis */
    var crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start

    /** Gap between children */
    var gap: Float = 0f

    /** Whether this is a row (horizontal main axis) */
    private val isRow: Boolean
        get() = direction == FlexDirection.Row || direction == FlexDirection.RowReverse

    /** Whether layout is reversed */
    private val isReversed: Boolean
        get() = direction == FlexDirection.RowReverse || direction == FlexDirection.ColumnReverse

    override fun measure(constraints: Constraints): Size {
        val c = applyOwnConstraints(constraints)

        if (_children.isEmpty()) {
            val size = finalizeSize(Size(padding.horizontal, padding.vertical), c)
            layoutResult = LayoutResult(Offset.Zero, size)
            return size
        }

        // Available space for children (minus padding)
        val availableMain = if (isRow) {
            (c.maxWidth - padding.horizontal).coerceAtLeast(0f)
        } else {
            (c.maxHeight - padding.vertical).coerceAtLeast(0f)
        }

        val availableCross = if (isRow) {
            (c.maxHeight - padding.vertical).coerceAtLeast(0f)
        } else {
            (c.maxWidth - padding.horizontal).coerceAtLeast(0f)
        }

        // Check if we have bounded main axis - needed for flex grow
        val hasBoundedMain = availableMain.isFinite() && availableMain < 10000f
        val hasBoundedCross = availableCross.isFinite() && availableCross < 10000f

        // ============ PASS 1: Main axis sizing ============
        // Calculate how much space each child gets on the main axis

        val totalGaps = if (_children.size > 1) gap * (_children.size - 1) else 0f

        // First, measure children that have fixed sizes to know how much space they take
        // For flex children, we'll calculate their size from remaining space
        val childMainSizes = mutableListOf<Float>()
        var totalFixedMain = 0f
        var totalFlexGrow = 0f

        for (child in _children) {
            val hasFlexGrow = child.flexGrow > 0

            if (hasFlexGrow && hasBoundedMain && child.flexBasis == null) {
                // Flex child - will be sized later
                childMainSizes.add(0f)
                totalFlexGrow += child.flexGrow
            } else {
                // Fixed child or has flexBasis - measure to get intrinsic size
                val mainConstraint = child.flexBasis ?: Float.MAX_VALUE
                val crossConstraint = if (hasBoundedCross) availableCross else Float.MAX_VALUE

                val measureConstraints = if (isRow) {
                    Constraints(maxWidth = mainConstraint, maxHeight = crossConstraint)
                } else {
                    Constraints(maxWidth = crossConstraint, maxHeight = mainConstraint)
                }

                val childSize = child.measure(measureConstraints)
                val mainSize = if (isRow) childSize.width else childSize.height

                childMainSizes.add(mainSize)
                totalFixedMain += mainSize
            }
        }

        // Calculate remaining space for flex children
        val remainingMain = if (hasBoundedMain) {
            (availableMain - totalGaps - totalFixedMain).coerceAtLeast(0f)
        } else {
            0f
        }

        // Distribute remaining space to flex children
        if (totalFlexGrow > 0 && remainingMain > 0) {
            _children.forEachIndexed { index, child ->
                if (child.flexGrow > 0 && childMainSizes[index] == 0f) {
                    val flexShare = (remainingMain * child.flexGrow) / totalFlexGrow
                    childMainSizes[index] = flexShare
                }
            }
        }

        // Handle shrinking if we exceed available space
        val totalMainBeforeShrink = childMainSizes.sum()
        if (hasBoundedMain && totalMainBeforeShrink > (availableMain - totalGaps)) {
            val overflow = totalMainBeforeShrink - (availableMain - totalGaps)
            val totalShrink = _children.mapIndexed { index, child ->
                child.flexShrink * childMainSizes[index]
            }.sum()

            if (totalShrink > 0) {
                _children.forEachIndexed { index, child ->
                    if (child.flexShrink > 0) {
                        val shrinkFactor = child.flexShrink * childMainSizes[index]
                        val shrinkAmount = (overflow * shrinkFactor) / totalShrink
                        childMainSizes[index] = (childMainSizes[index] - shrinkAmount).coerceAtLeast(0f)
                    }
                }
            }
        }

        // ============ PASS 2: Cross axis sizing and final measurement ============
        // Now that we know each child's main axis size, re-measure with proper constraints
        // This allows nested flex containers to layout correctly

        var maxChildCross = 0f
        val childCrossSizes = mutableListOf<Float>()

        for ((index, child) in _children.withIndex()) {
            val childMainSize = childMainSizes[index]

            // Determine cross axis constraint
            val crossConstraint = when {
                crossAxisAlignment == CrossAxisAlignment.Stretch && hasBoundedCross -> availableCross
                else -> Float.MAX_VALUE
            }

            // Create proper constraints for this child
            val childConstraints = if (isRow) {
                Constraints(
                    minWidth = childMainSize,
                    maxWidth = childMainSize,
                    maxHeight = crossConstraint
                )
            } else {
                Constraints(
                    maxWidth = crossConstraint,
                    minHeight = childMainSize,
                    maxHeight = childMainSize
                )
            }

            val childSize = child.measure(childConstraints)
            val childCrossSize = if (isRow) childSize.height else childSize.width

            childCrossSizes.add(childCrossSize)
            maxChildCross = maxOf(maxChildCross, childCrossSize)
        }

        // ============ Calculate final container size ============
        val totalMainAfterFlex = childMainSizes.sum() + totalGaps

        // For main axis size: if we have bounded constraints, fill the available space
        // This ensures containers expand to fill their allocated space
        val finalMainSize = if (isRow) {
            if (hasBoundedMain) {
                // Fill the available width (constrained)
                c.constrainWidth(availableMain + padding.horizontal)
            } else {
                // Use content width
                c.constrainWidth(totalMainAfterFlex + padding.horizontal)
            }
        } else {
            if (hasBoundedMain) {
                // Fill the available height (constrained)
                c.constrainHeight(availableMain + padding.vertical)
            } else {
                // Use content height
                c.constrainHeight(totalMainAfterFlex + padding.vertical)
            }
        }

        // For cross axis: if we have bounded constraints, fill the available space
        val finalCrossSize = if (isRow) {
            if (hasBoundedCross) {
                c.constrainHeight(availableCross + padding.vertical)
            } else {
                c.constrainHeight(maxChildCross + padding.vertical)
            }
        } else {
            if (hasBoundedCross) {
                c.constrainWidth(availableCross + padding.horizontal)
            } else {
                c.constrainWidth(maxChildCross + padding.horizontal)
            }
        }

        val size = if (isRow) {
            Size(finalMainSize, finalCrossSize)
        } else {
            Size(finalCrossSize, finalMainSize)
        }

        layoutResult = LayoutResult(Offset.Zero, size)

        // ============ PASS 3: Position children ============
        positionChildren(childMainSizes, childCrossSizes, size)

        return size
    }

    /**
     * Position all children based on alignment and computed sizes
     */
    private fun positionChildren(
        childMainSizes: List<Float>,
        childCrossSizes: List<Float>,
        containerSize: Size
    ) {
        val contentMain = childMainSizes.sum() +
            (if (_children.size > 1) gap * (_children.size - 1) else 0f)

        val availableMain = if (isRow) {
            containerSize.width - padding.horizontal
        } else {
            containerSize.height - padding.vertical
        }

        val availableCross = if (isRow) {
            containerSize.height - padding.vertical
        } else {
            containerSize.width - padding.horizontal
        }

        // Calculate starting position based on main axis alignment
        var mainPos = when (mainAxisAlignment) {
            MainAxisAlignment.Start -> 0f
            MainAxisAlignment.End -> availableMain - contentMain
            MainAxisAlignment.Center -> (availableMain - contentMain) / 2
            MainAxisAlignment.SpaceBetween -> 0f
            MainAxisAlignment.SpaceAround -> {
                val totalGaps = (availableMain - childMainSizes.sum())
                totalGaps / (_children.size * 2)
            }
            MainAxisAlignment.SpaceEvenly -> {
                val totalGaps = availableMain - childMainSizes.sum()
                totalGaps / (_children.size + 1)
            }
        }

        // Calculate gap between items for space-* alignments
        val effectiveGap = when (mainAxisAlignment) {
            MainAxisAlignment.SpaceBetween -> {
                if (_children.size > 1) {
                    (availableMain - childMainSizes.sum()) / (_children.size - 1)
                } else 0f
            }
            MainAxisAlignment.SpaceAround -> {
                val aroundGap = (availableMain - childMainSizes.sum()) / (_children.size * 2)
                aroundGap * 2
            }
            MainAxisAlignment.SpaceEvenly -> {
                (availableMain - childMainSizes.sum()) / (_children.size + 1)
            }
            else -> gap
        }

        // Handle reversed layout
        val indices = if (isReversed) {
            _children.indices.reversed()
        } else {
            _children.indices.toList()
        }

        for (i in indices) {
            val child = _children[i]
            val childMainSize = childMainSizes[i]
            val childCrossSize = childCrossSizes[i]

            // Calculate cross axis position
            val crossPos = when (crossAxisAlignment) {
                CrossAxisAlignment.Start -> 0f
                CrossAxisAlignment.End -> availableCross - childCrossSize
                CrossAxisAlignment.Center -> (availableCross - childCrossSize) / 2
                CrossAxisAlignment.Stretch -> 0f
            }

            // Place the child
            val offset = if (isRow) {
                Offset(padding.left + mainPos, padding.top + crossPos)
            } else {
                Offset(padding.left + crossPos, padding.top + mainPos)
            }
            child.place(offset)

            mainPos += childMainSize + effectiveGap
        }
    }
}

/**
 * Convenience function to create a Row layout
 */
fun RowNode(
    id: String,
    mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
    crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
    gap: Float = 0f
): FlexNode = FlexNode(id, FlexDirection.Row).apply {
    this.mainAxisAlignment = mainAxisAlignment
    this.crossAxisAlignment = crossAxisAlignment
    this.gap = gap
}

/**
 * Convenience function to create a Column layout
 */
fun ColumnNode(
    id: String,
    mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
    crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
    gap: Float = 0f
): FlexNode = FlexNode(id, FlexDirection.Column).apply {
    this.mainAxisAlignment = mainAxisAlignment
    this.crossAxisAlignment = crossAxisAlignment
    this.gap = gap
}
