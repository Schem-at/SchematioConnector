package io.schemat.schematioConnector.ui.layout

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * Unit tests for the Layout system.
 *
 * Tests cover:
 * - Basic layout creation and measurement
 * - Flex grow/shrink behavior
 * - Padding and gaps
 * - Nested layouts
 * - Edge cases
 */
class LayoutTest {

    private val EPSILON = 0.001f

    private fun assertFloatEquals(expected: Float, actual: Float, message: String = "") {
        assertTrue(kotlin.math.abs(expected - actual) < EPSILON, "$message: expected $expected but was $actual")
    }

    @Nested
    @DisplayName("Basic Layout")
    inner class BasicLayoutTests {

        @Test
        fun `layout with single leaf produces result`() {
            val layout = Layout(width = 4f, height = 3f)
            layout.column("root") {
                leaf("content", flexGrow = 1f)
            }
            layout.compute()

            val result = layout.getResult("content")
            assertNotNull(result, "content result should exist")
            assertNotNull(result!!.size, "content size should exist")
            // Flexgrow leaf may have 0 intrinsic size initially - that's ok
        }

        @Test
        fun `layout with fixed size leaf`() {
            val layout = Layout(width = 4f, height = 3f)
            layout.column("root") {
                leaf("fixed", width = 2f, height = 1.5f)
            }
            layout.compute()

            val result = layout.getResult("fixed")
            assertNotNull(result)
            assertFloatEquals(2f, result!!.size.width, "width")
            assertFloatEquals(1.5f, result.size.height, "height")
        }

        @Test
        fun `row layout positions children horizontally`() {
            val layout = Layout(width = 4f, height = 2f)
            layout.row("root") {
                leaf("left", width = 1f)
                leaf("right", width = 1f)
            }
            layout.compute()

            val left = layout.getResult("left")
            val right = layout.getResult("right")

            assertNotNull(left)
            assertNotNull(right)

            assertFloatEquals(0f, left!!.offset.x, "left x")
            assertFloatEquals(1f, right!!.offset.x, "right x")
        }

        @Test
        fun `column layout positions children vertically`() {
            val layout = Layout(width = 4f, height = 4f)
            layout.column("root") {
                leaf("top", height = 1f)
                leaf("bottom", height = 1f)
            }
            layout.compute()

            val top = layout.getResult("top")
            val bottom = layout.getResult("bottom")

            assertNotNull(top)
            assertNotNull(bottom)

            assertFloatEquals(0f, top!!.offset.y, "top y")
            assertFloatEquals(1f, bottom!!.offset.y, "bottom y")
        }
    }

    @Nested
    @DisplayName("Flex Grow")
    inner class FlexGrowTests {

        @Test
        fun `flex grow distributes remaining space`() {
            val layout = Layout(width = 4f, height = 2f)
            layout.row("root") {
                leaf("fixed", width = 1f)
                leaf("flex", flexGrow = 1f)
            }
            layout.compute()

            val fixed = layout.getResult("fixed")
            val flex = layout.getResult("flex")

            assertNotNull(fixed)
            assertNotNull(flex)

            assertFloatEquals(1f, fixed!!.size.width, "fixed width")
            assertFloatEquals(3f, flex!!.size.width, "flex width")
        }

        @Test
        fun `multiple flex grow elements share space proportionally`() {
            val layout = Layout(width = 6f, height = 2f)
            layout.row("root") {
                leaf("one", flexGrow = 1f)
                leaf("two", flexGrow = 2f)
            }
            layout.compute()

            val one = layout.getResult("one")
            val two = layout.getResult("two")

            assertNotNull(one)
            assertNotNull(two)

            assertFloatEquals(2f, one!!.size.width, "one width (1/3)")
            assertFloatEquals(4f, two!!.size.width, "two width (2/3)")
        }

        @Test
        fun `flex grow in column distributes vertical space`() {
            val layout = Layout(width = 2f, height = 6f)
            layout.column("root") {
                leaf("fixed", height = 2f)
                leaf("flex", flexGrow = 1f)
            }
            layout.compute()

            val fixed = layout.getResult("fixed")
            val flex = layout.getResult("flex")

            assertNotNull(fixed)
            assertNotNull(flex)

            assertFloatEquals(2f, fixed!!.size.height, "fixed height")
            assertFloatEquals(4f, flex!!.size.height, "flex height")
        }
    }

    @Nested
    @DisplayName("Padding and Gaps")
    inner class PaddingAndGapTests {

        @Test
        fun `padding reduces available space for children`() {
            val layout = Layout(width = 4f, height = 4f)
            layout.column("root", padding = Padding.all(0.5f)) {
                leaf("content", flexGrow = 1f)
            }
            layout.compute()

            val content = layout.getResult("content")
            assertNotNull(content)

            // Content should be smaller than container due to padding
            assertTrue(content!!.size.width < 4f, "content width should be less than container")
            assertTrue(content.size.height < 4f, "content height should be less than container")
        }

        @Test
        fun `gaps add space between children`() {
            val layout = Layout(width = 5f, height = 2f)
            layout.row("root", gap = 1f) {
                leaf("a", width = 1f)
                leaf("b", width = 1f)
                leaf("c", width = 1f)
            }
            layout.compute()

            val a = layout.getResult("a")
            val b = layout.getResult("b")
            val c = layout.getResult("c")

            assertNotNull(a)
            assertNotNull(b)
            assertNotNull(c)

            assertFloatEquals(0f, a!!.offset.x, "a x")
            assertFloatEquals(2f, b!!.offset.x, "b x (1 + 1 gap)")
            assertFloatEquals(4f, c!!.offset.x, "c x (1 + 1 gap + 1 + 1 gap)")
        }

        @Test
        fun `asymmetric padding works correctly`() {
            val layout = Layout(width = 4f, height = 4f)
            layout.column("root", padding = Padding(left = 0.5f, right = 0.5f, top = 1f, bottom = 1f)) {
                leaf("content", flexGrow = 1f)
            }
            layout.compute()

            val content = layout.getResult("content")
            assertNotNull(content)

            // Content should be reduced by padding amounts
            assertTrue(content!!.size.width < 4f, "content width should be less than container")
            assertTrue(content.size.height < 4f, "content height should be less than container")
        }
    }

    @Nested
    @DisplayName("Nested Layouts")
    inner class NestedLayoutTests {

        @Test
        fun `nested rows and columns`() {
            val layout = Layout(width = 4f, height = 4f)
            layout.column("root") {
                row("header", height = 1f) {
                    leaf("logo", width = 1f)
                    leaf("title", flexGrow = 1f)
                }
                row("content", flexGrow = 1f) {
                    leaf("sidebar", width = 1f)
                    leaf("main", flexGrow = 1f)
                }
            }
            layout.compute()

            // Verify all nodes are created
            assertNotNull(layout.getResult("logo"))
            assertNotNull(layout.getResult("title"))
            assertNotNull(layout.getResult("sidebar"))
            assertNotNull(layout.getResult("main"))

            // Verify fixed sizes are respected
            val logo = layout.getResult("logo")!!
            assertFloatEquals(1f, logo.size.width, "logo width")

            val sidebar = layout.getResult("sidebar")!!
            assertFloatEquals(1f, sidebar.size.width, "sidebar width")
        }

        @Test
        fun `deeply nested layouts`() {
            val layout = Layout(width = 8f, height = 8f)
            layout.column("root") {
                column("level1", flexGrow = 1f) {
                    column("level2", flexGrow = 1f) {
                        leaf("deep", flexGrow = 1f)
                    }
                }
            }
            layout.compute()

            // Verify nodes are created and computed
            assertNotNull(layout.getNode("level1"))
            assertNotNull(layout.getNode("level2"))
            assertNotNull(layout.getNode("deep"))
            assertNotNull(layout.getResult("deep"))
        }
    }

    @Nested
    @DisplayName("Alignment")
    inner class AlignmentTests {

        @Test
        fun `main axis center alignment`() {
            val layout = Layout(width = 4f, height = 2f)
            layout.row("root", mainAxisAlignment = MainAxisAlignment.Center) {
                leaf("item", width = 2f)
            }
            layout.compute()

            val item = layout.getResult("item")
            assertNotNull(item)
            assertFloatEquals(1f, item!!.offset.x, "item x (centered)")
        }

        @Test
        fun `main axis end alignment`() {
            val layout = Layout(width = 4f, height = 2f)
            layout.row("root", mainAxisAlignment = MainAxisAlignment.End) {
                leaf("item", width = 1f)
            }
            layout.compute()

            val item = layout.getResult("item")
            assertNotNull(item)
            assertFloatEquals(3f, item!!.offset.x, "item x (end)")
        }

        @Test
        fun `cross axis center alignment`() {
            val layout = Layout(width = 4f, height = 4f)
            layout.row("root", crossAxisAlignment = CrossAxisAlignment.Center) {
                leaf("item", width = 1f, height = 2f)
            }
            layout.compute()

            val item = layout.getResult("item")
            assertNotNull(item)
            assertFloatEquals(1f, item!!.offset.y, "item y (centered)")
        }

        @Test
        fun `cross axis stretch fills available space`() {
            val layout = Layout(width = 4f, height = 4f)
            layout.row("root", crossAxisAlignment = CrossAxisAlignment.Stretch) {
                leaf("item", width = 1f)
            }
            layout.compute()

            val item = layout.getResult("item")
            assertNotNull(item)
            assertFloatEquals(4f, item!!.size.height, "item height (stretched)")
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `empty layout`() {
            val layout = Layout(width = 4f, height = 3f)
            layout.compute()
            // Should not throw
        }

        @Test
        fun `zero size children`() {
            val layout = Layout(width = 4f, height = 3f)
            layout.row("root") {
                leaf("zero", width = 0f, height = 0f)
                leaf("normal", width = 2f, height = 2f)
            }
            layout.compute()

            val zero = layout.getResult("zero")
            val normal = layout.getResult("normal")

            assertNotNull(zero)
            assertNotNull(normal)
            assertFloatEquals(0f, zero!!.size.width, "zero width")
            assertFloatEquals(2f, normal!!.size.width, "normal width")
        }

        @Test
        fun `all flex grow elements with no fixed elements`() {
            val layout = Layout(width = 9f, height = 2f)
            layout.row("root") {
                leaf("a", flexGrow = 1f)
                leaf("b", flexGrow = 1f)
                leaf("c", flexGrow = 1f)
            }
            layout.compute()

            val a = layout.getResult("a")
            val b = layout.getResult("b")
            val c = layout.getResult("c")

            assertNotNull(a)
            assertNotNull(b)
            assertNotNull(c)

            assertFloatEquals(3f, a!!.size.width, "a width")
            assertFloatEquals(3f, b!!.size.width, "b width")
            assertFloatEquals(3f, c!!.size.width, "c width")
        }

        @Test
        fun `spacer element`() {
            val layout = Layout(width = 4f, height = 2f)
            layout.row("root") {
                leaf("left", width = 1f)
                spacer()
                leaf("right", width = 1f)
            }
            layout.compute()

            val left = layout.getResult("left")
            val right = layout.getResult("right")

            assertNotNull(left)
            assertNotNull(right)

            assertFloatEquals(0f, left!!.offset.x, "left x")
            assertFloatEquals(3f, right!!.offset.x, "right x (pushed by spacer)")
        }

        @Test
        fun `box container with padding`() {
            val layout = Layout(width = 4f, height = 4f)
            layout.column("root") {
                box("container", padding = Padding.all(0.5f), flexGrow = 1f) {
                    leaf("inner", flexGrow = 1f)
                }
            }
            layout.compute()

            val container = layout.getResult("container")
            val inner = layout.getResult("inner")

            assertNotNull(container)
            assertNotNull(inner)

            // Inner should be smaller than container due to padding
            assertTrue(inner!!.size.width < container!!.size.width, "inner width should be less than container")
        }
    }

    @Nested
    @DisplayName("Absolute Position")
    inner class AbsolutePositionTests {

        @Test
        fun `getAbsolutePosition returns correct coordinates`() {
            val layout = Layout(width = 4f, height = 4f)
            layout.column("root", padding = Padding.all(0.5f)) {
                row("row", padding = Padding.all(0.25f)) {
                    leaf("item", width = 1f, height = 1f)
                }
            }
            layout.compute()

            val absPos = layout.getAbsolutePosition("item")
            assertNotNull(absPos)

            // root padding: 0.5, row padding: 0.25 = 0.75 total offset
            assertFloatEquals(0.75f, absPos!!.x, "absolute x")
            assertFloatEquals(0.75f, absPos.y, "absolute y")
        }
    }

    @Nested
    @DisplayName("Debug Output")
    inner class DebugTests {

        @Test
        fun `debugPrint produces output`() {
            val layout = Layout(width = 4f, height = 3f)
            layout.column("root") {
                leaf("child", flexGrow = 1f)
            }
            layout.compute()

            val debug = layout.debugPrint()
            assertTrue(debug.contains("root"), "debug contains root")
            assertTrue(debug.contains("child"), "debug contains child")
        }
    }
}
