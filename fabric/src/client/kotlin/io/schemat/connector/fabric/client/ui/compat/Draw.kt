package io.schemat.connector.fabric.client.ui.compat

/**
 * GUI drawing compat layer for the MC 26.x GUI rewrite.
 *
 * MC 26.x replaced [net.minecraft.client.gui.GuiGraphics] with
 * `net.minecraft.client.gui.GuiGraphicsExtractor` (screens/widgets now
 * "extract" render state instead of rendering immediately). The extractor
 * keeps immediate-style primitives with identical signatures for
 * `fill`/`fillGradient`/`enableScissor`/`disableScissor`/`pose`, but the text
 * entry points were renamed (`drawString` -> `text`,
 * `drawCenteredString` -> `centeredText`, `drawWordWrap` -> `textWithWordWrap`).
 *
 * Usage: UI files import this package's [GuiGraphics] alias (plus the 26.x-only
 * extension shims via the same wildcard import) instead of the vanilla class:
 *
 *     import io.schemat.connector.fabric.client.ui.compat.*
 *
 * On 1.21.x the alias points at the real GuiGraphics and no extensions exist,
 * so call sites resolve to the vanilla members - zero behavioral change.
 * On 26.x the alias points at GuiGraphicsExtractor and the renamed text
 * methods are bridged by the extensions below, so call sites stay untouched.
 *
 * NOT covered here (handled with per-site Stonecutter conditionals, because
 * override signatures cannot be shimmed):
 *  - Screen:  render -> extractRenderState, renderBackground -> extractBackground
 *  - Widgets: renderWidget -> extractWidgetRenderState
 */

//? if >=26.1 {
/*typealias GuiGraphics = net.minecraft.client.gui.GuiGraphicsExtractor

fun GuiGraphics.drawString(
    font: net.minecraft.client.gui.Font,
    text: String,
    x: Int,
    y: Int,
    color: Int,
    shadow: Boolean = true,
) = this.text(font, text, x, y, color, shadow)

fun GuiGraphics.drawString(
    font: net.minecraft.client.gui.Font,
    text: net.minecraft.network.chat.Component,
    x: Int,
    y: Int,
    color: Int,
    shadow: Boolean = true,
) = this.text(font, text, x, y, color, shadow)

fun GuiGraphics.drawString(
    font: net.minecraft.client.gui.Font,
    text: net.minecraft.util.FormattedCharSequence,
    x: Int,
    y: Int,
    color: Int,
    shadow: Boolean = true,
) = this.text(font, text, x, y, color, shadow)

fun GuiGraphics.drawCenteredString(
    font: net.minecraft.client.gui.Font,
    text: String,
    x: Int,
    y: Int,
    color: Int,
) = this.centeredText(font, text, x, y, color)

fun GuiGraphics.drawCenteredString(
    font: net.minecraft.client.gui.Font,
    text: net.minecraft.network.chat.Component,
    x: Int,
    y: Int,
    color: Int,
) = this.centeredText(font, text, x, y, color)

fun GuiGraphics.drawWordWrap(
    font: net.minecraft.client.gui.Font,
    text: net.minecraft.network.chat.FormattedText,
    x: Int,
    y: Int,
    width: Int,
    color: Int,
) = this.textWithWordWrap(font, text, x, y, width, color)

/**
 * 26.x renamed Screen.renderBackground to extractBackground; this bridges
 * plain CALLS (e.g. `renderBackground(ctx, ...)` inside a Screen subclass).
 * Screens that OVERRIDE it still need a per-site Stonecutter conditional.
 */
fun net.minecraft.client.gui.screens.Screen.renderBackground(
    context: GuiGraphics,
    mouseX: Int,
    mouseY: Int,
    delta: Float,
) = this.extractBackground(context, mouseX, mouseY, delta)
*///?} else {
typealias GuiGraphics = net.minecraft.client.gui.GuiGraphics
//?}
