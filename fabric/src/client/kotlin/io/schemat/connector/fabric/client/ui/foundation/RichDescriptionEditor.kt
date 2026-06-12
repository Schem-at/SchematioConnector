package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.core.text.RichSpan
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

/**
 * The description editor: a **B / I / U / S** + color-swatch toolbar over ONE
 * inline WYSIWYG surface ([RichTextEditor]) - the user types and sees the
 * formatting (bold/italic/underline/strikethrough/color, plus rendered links
 * and bullets) exactly as it will publish. No raw markup, no separate preview.
 *
 * Toolbar buttons style the selection (toggle: cleared when the whole
 * selection already has the style) or, with nothing selected, the style of the
 * next typed characters; each button lights up (accent border/fill) when the
 * selection or pending style carries it. After a button press focus returns to
 * the editor surface so typing continues uninterrupted.
 *
 * Content is website HTML throughout: load with [setFromHtml], save [getHtml]
 * (`RichText.htmlToSpans`/`spansToHtml`, sanitizer-allowlisted tags only).
 * [isChanged] compares against the normalized form captured by [setFromHtml],
 * so an untouched description never produces a false diff. [getSpans] feeds
 * read-only previews ([RichTextRender]).
 *
 * Not itself a widget: add [widgets] as drawable children and call [render]
 * from the screen's render pass (focus ring around the surface).
 */
class RichDescriptionEditor(
    font: Font,
    private val x: Int,
    private val y: Int,
    private val width: Int,
    totalHeight: Int,
    placeholder: String = "Description...",
    private val onChanged: (String) -> Unit = {},
) {

    companion object {
        private const val TOOLBAR_H = 14
        private const val TOOLBAR_BTN_W = 16
        private const val SWATCH_W = 12
        private const val GAP = 3

        /** Smallest sensible total height (toolbar + ~5 text lines). */
        const val MIN_HEIGHT = TOOLBAR_H + GAP + 60

        /** Color authoring presets: default + brand fuchsia + the flux palette trio. */
        private val SWATCHES: List<Int?> = listOf(
            null,
            Theme.ACCENT,
            0xFFE03E2D.toInt(),
            0xFF2DC26B.toInt(),
            0xFF3598DB.toInt(),
        )
    }

    private val editorY = y + TOOLBAR_H + GAP
    private val editorHeight = (totalHeight - TOOLBAR_H - GAP).coerceAtLeast(40)

    private val editor = RichTextEditor(font, x, editorY, width, editorHeight, placeholder)

    /** The HTML as loaded (normalized through the span model) for change detection. */
    private var initialNormalizedHtml: String = ""

    init {
        editor.onChanged = { onChanged(editor.getHtml()) }
    }

    private val toolbar: List<AbstractWidget> = buildList {
        var bx = x
        fun styleButton(flag: RichTextEditor.StyleFlag, label: Component) {
            add(
                FlatButton(
                    bx, y, TOOLBAR_BTN_W, TOOLBAR_H, label,
                    bg = { if (editor.isStyleActive(flag)) Theme.withAlpha(Theme.ACCENT, 0x40) else Theme.SURFACE },
                    fg = { if (editor.isStyleActive(flag)) Theme.TEXT_PRIMARY else Theme.TEXT_MUTED },
                    border = { if (editor.isStyleActive(flag)) Theme.BORDER_ACCENT else Theme.BORDER_SUBTLE },
                ) {
                    editor.toggleStyle(flag)
                    refocusEditor()
                }
            )
            bx += TOOLBAR_BTN_W + 2
        }
        styleButton(RichTextEditor.StyleFlag.BOLD, Component.literal("B").withStyle { it.withBold(true) })
        styleButton(RichTextEditor.StyleFlag.ITALIC, Component.literal("I").withStyle { it.withItalic(true) })
        styleButton(RichTextEditor.StyleFlag.UNDERLINE, Component.literal("U").withStyle { it.withUnderlined(true) })
        styleButton(RichTextEditor.StyleFlag.STRIKE, Component.literal("S").withStyle { it.withStrikethrough(true) })

        bx += Theme.XS
        for (color in SWATCHES) {
            val display = color ?: Theme.TEXT_SECONDARY
            add(
                FlatButton(
                    bx, y, SWATCH_W, TOOLBAR_H,
                    Component.literal("A").withStyle { it.withColor(TextColor.fromRgb(display and 0xFFFFFF)) },
                    bg = { Theme.SURFACE },
                    fg = { display },
                    border = { if (editor.activeColor() == color) Theme.BORDER_ACCENT else Theme.BORDER_SUBTLE },
                ) {
                    editor.setColor(color)
                    refocusEditor()
                }
            )
            bx += SWATCH_W + 2
        }
    }

    /** Toolbar buttons + the inline editor surface; add these as drawable children. */
    fun widgets(): List<AbstractWidget> = toolbar + editor

    /**
     * Hand keyboard focus back to the surface after a toolbar press. Deferred via
     * [Minecraft.send] because the screen focuses the clicked button *after*
     * its onPress returns - a synchronous refocus would be overwritten.
     */
    private fun refocusEditor() {
        val client = Minecraft.getInstance()
        client.schedule {
            client.screen?.focused = editor
            editor.isFocused = true
        }
    }

    // ---- content API ----

    /** Load an existing website HTML description (also resets [isChanged]'s baseline). */
    fun setFromHtml(html: String) {
        editor.setHtml(html)
        initialNormalizedHtml = editor.getHtml()
    }

    /** The current content as sanitizer-safe website HTML (for saving/uploading). */
    fun getHtml(): String = editor.getHtml()

    /** The current content as styled spans (for read-only previews). */
    fun getSpans(): List<RichSpan> = editor.getSpans()

    fun isEmpty(): Boolean = editor.isEmpty()

    /** True when the content differs from what [setFromHtml] loaded (normalized). */
    fun isChanged(): Boolean = editor.getHtml() != initialNormalizedHtml

    // ---- rendering ----

    fun render(context: GuiGraphics) {
        // Focus ring around the surface; the surface fills itself.
        Theme.stroke(
            context,
            x - 1, editorY - 1, width + 2, editorHeight + 2,
            if (editor.isFocused) Theme.BORDER_ACCENT else Theme.BORDER,
        )
    }
}
