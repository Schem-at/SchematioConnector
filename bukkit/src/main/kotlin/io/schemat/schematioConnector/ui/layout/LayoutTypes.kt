package io.schemat.schematioConnector.ui.layout

/**
 * Layout constraint system - defines min/max bounds for layout calculations.
 * Inspired by Compose UI's constraint system.
 */
data class Constraints(
    val minWidth: Float = 0f,
    val maxWidth: Float = Float.MAX_VALUE,
    val minHeight: Float = 0f,
    val maxHeight: Float = Float.MAX_VALUE
) {
    /** Check if constraints have a fixed width */
    val hasFixedWidth: Boolean get() = minWidth == maxWidth

    /** Check if constraints have a fixed height */
    val hasFixedHeight: Boolean get() = minHeight == maxHeight

    /** Check if constraints have bounded width */
    val hasBoundedWidth: Boolean get() = maxWidth != Float.MAX_VALUE

    /** Check if constraints have bounded height */
    val hasBoundedHeight: Boolean get() = maxHeight != Float.MAX_VALUE

    /** Constrain a width value to these constraints */
    fun constrainWidth(width: Float): Float = width.coerceIn(minWidth, maxWidth)

    /** Constrain a height value to these constraints */
    fun constrainHeight(height: Float): Float = height.coerceIn(minHeight, maxHeight)

    /** Constrain a size to these constraints */
    fun constrain(size: Size): Size = Size(
        constrainWidth(size.width),
        constrainHeight(size.height)
    )

    /** Create constraints with a specific fixed width */
    fun fixedWidth(width: Float): Constraints = copy(minWidth = width, maxWidth = width)

    /** Create constraints with a specific fixed height */
    fun fixedHeight(height: Float): Constraints = copy(minHeight = height, maxHeight = height)

    /** Create unbounded constraints (for measuring intrinsic size) */
    fun unbounded(): Constraints = copy(
        maxWidth = Float.MAX_VALUE,
        maxHeight = Float.MAX_VALUE
    )

    companion object {
        /** Fixed size constraints */
        fun fixed(width: Float, height: Float) = Constraints(
            minWidth = width, maxWidth = width,
            minHeight = height, maxHeight = height
        )

        /** Constraints with only max bounds */
        fun maxSize(width: Float, height: Float) = Constraints(
            maxWidth = width, maxHeight = height
        )

        /** Unbounded constraints */
        val Unbounded = Constraints()
    }
}

/**
 * Represents a 2D size (width x height)
 */
data class Size(val width: Float, val height: Float) {
    companion object {
        val Zero = Size(0f, 0f)
    }
}

/**
 * Represents a 2D offset/position (x, y)
 */
data class Offset(val x: Float, val y: Float) {
    operator fun plus(other: Offset) = Offset(x + other.x, y + other.y)
    operator fun minus(other: Offset) = Offset(x - other.x, y - other.y)

    companion object {
        val Zero = Offset(0f, 0f)
    }
}

/**
 * Padding values for all four sides
 */
data class Padding(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
) {
    /** Total horizontal padding (left + right) */
    val horizontal: Float get() = left + right

    /** Total vertical padding (top + bottom) */
    val vertical: Float get() = top + bottom

    companion object {
        val Zero = Padding()

        /** Same padding on all sides */
        fun all(value: Float) = Padding(value, value, value, value)

        /** Symmetric padding */
        fun symmetric(horizontal: Float = 0f, vertical: Float = 0f) =
            Padding(horizontal, vertical, horizontal, vertical)
    }
}

/**
 * Main axis alignment for flex containers (Row/Column)
 * Controls how children are distributed along the main axis.
 */
enum class MainAxisAlignment {
    /** Children packed at the start */
    Start,
    /** Children packed at the end */
    End,
    /** Children centered */
    Center,
    /** Equal space between children */
    SpaceBetween,
    /** Equal space around children */
    SpaceAround,
    /** Equal space between and around children */
    SpaceEvenly
}

/**
 * Cross axis alignment for flex containers (Row/Column)
 * Controls how children are aligned perpendicular to the main axis.
 */
enum class CrossAxisAlignment {
    /** Align to start of cross axis */
    Start,
    /** Align to end of cross axis */
    End,
    /** Center on cross axis */
    Center,
    /** Stretch to fill cross axis (if no explicit size) */
    Stretch
}

/**
 * Flex direction - determines main axis orientation
 */
enum class FlexDirection {
    /** Main axis is horizontal (left to right) */
    Row,
    /** Main axis is horizontal (right to left) */
    RowReverse,
    /** Main axis is vertical (top to bottom) */
    Column,
    /** Main axis is vertical (bottom to top) */
    ColumnReverse
}

/**
 * Layout result containing computed position and size
 */
data class LayoutResult(
    val offset: Offset,
    val size: Size
) {
    val x: Float get() = offset.x
    val y: Float get() = offset.y
    val width: Float get() = size.width
    val height: Float get() = size.height

    /** Right edge (x + width) */
    val right: Float get() = x + width

    /** Bottom edge (y + height) */
    val bottom: Float get() = y + height
}
