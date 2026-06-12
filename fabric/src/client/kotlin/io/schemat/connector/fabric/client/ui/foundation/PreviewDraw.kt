package io.schemat.connector.fabric.client.ui.foundation

import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * Aspect-ratio-correct texture drawing for preview images.
 *
 * - [drawCover]: scale preserving aspect so the texture FILLS the target rect, cropping
 *   the overflowing axis (centered) via UV region offsets - grid thumbnails.
 * - [drawContain]: scale preserving aspect so the texture FITS inside the target rect,
 *   letterboxed (centered) over a background fill - large detail previews.
 */
object PreviewDraw {

    fun drawCover(
        context: GuiGraphics,
        textureId: Identifier,
        textureWidth: Int,
        textureHeight: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        if (textureWidth <= 0 || textureHeight <= 0 || width <= 0 || height <= 0) return
        val targetAspect = width.toFloat() / height
        val textureAspect = textureWidth.toFloat() / textureHeight
        var regionWidth = textureWidth
        var regionHeight = textureHeight
        if (textureAspect > targetAspect) {
            // Texture is wider than the target: crop left/right
            regionWidth = (textureHeight * targetAspect).toInt().coerceIn(1, textureWidth)
        } else {
            // Texture is taller than the target: crop top/bottom
            regionHeight = (textureWidth / targetAspect).toInt().coerceIn(1, textureHeight)
        }
        val u = (textureWidth - regionWidth) / 2f
        val v = (textureHeight - regionHeight) / 2f
        context.blit(
            RenderPipelines.GUI_TEXTURED, textureId,
            x, y, u, v,
            width, height,
            regionWidth, regionHeight,
            textureWidth, textureHeight,
        )
    }

    fun drawContain(
        context: GuiGraphics,
        textureId: Identifier,
        textureWidth: Int,
        textureHeight: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        background: Int = 0xFF_111111.toInt(),
    ) {
        if (textureWidth <= 0 || textureHeight <= 0 || width <= 0 || height <= 0) return
        context.fill(x, y, x + width, y + height, background)
        val scale = minOf(width.toFloat() / textureWidth, height.toFloat() / textureHeight)
        val drawWidth = (textureWidth * scale).toInt().coerceAtLeast(1)
        val drawHeight = (textureHeight * scale).toInt().coerceAtLeast(1)
        val drawX = x + (width - drawWidth) / 2
        val drawY = y + (height - drawHeight) / 2
        context.blit(
            RenderPipelines.GUI_TEXTURED, textureId,
            drawX, drawY, 0f, 0f,
            drawWidth, drawHeight,
            textureWidth, textureHeight,
            textureWidth, textureHeight,
        )
    }
}
