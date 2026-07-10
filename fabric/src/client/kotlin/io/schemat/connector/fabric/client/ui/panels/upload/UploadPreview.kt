package io.schemat.connector.fabric.client.ui.panels.upload

import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.platform.NativeImage
import imgui.ImGui
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.ui.panels.PreviewComposerPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.lwjgl.opengl.GL11C
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Stable Identifier the decoded preview is registered under in the TextureManager. */
internal val PREVIEW_TEX_ID: Identifier =
    Identifier.fromNamespaceAndPath("schematioconnector", "upload-wizard/captured-preview")

/**
 * Resolve the selected source's schematic bytes (same 3-way path as [startUpload])
 * and open the [PreviewComposerPanel] on them. The composer hands back a captured
 * PNG into [capturedPreviewPng], which [performUpload] then uploads.
 */
internal fun UploadWizardPanel.generatePreview() {
    if (uploadBusy.get() || exporting) return
    val source = selectedSource ?: return
    statusMessage = null

    when (source.kind) {
        SourceKind.LOCAL_FILE -> {
            // User-initiated one-off read; local schematics are small.
            val bytes = runCatching { Files.readAllBytes(Path.of(source.id)) }.getOrElse { e ->
                statusMessage = "Failed to read file: ${e.message ?: e.javaClass.simpleName}"
                statusKind = Widgets.StatusKind.DANGER
                return
            }
            openComposer(bytes)
        }

        SourceKind.WORLDEDIT_CLIPBOARD -> {
            exporting = true
            Bridges.worldEdit.clipboardToBytes { bytes, error ->
                exporting = false
                if (bytes == null) {
                    services.onMainThread {
                        statusMessage = error ?: "Failed to read the WorldEdit clipboard"
                        statusKind = Widgets.StatusKind.DANGER
                    }
                } else {
                    services.onMainThread { openComposer(bytes) }
                }
            }
        }

        SourceKind.PLACEMENT, SourceKind.AREA_SELECTION -> {
            exporting = true
            Bridges.litematica.exportToBytes(source) { bytes, error ->
                exporting = false
                if (bytes == null) {
                    services.onMainThread {
                        statusMessage = error ?: "Failed to export the schematic"
                        statusKind = Widgets.StatusKind.DANGER
                    }
                } else {
                    services.onMainThread { openComposer(bytes) }
                }
            }
        }
    }
}

/** Open the composer on [bytes]; its capture callback stores the PNG for upload. */
internal fun UploadWizardPanel.openComposer(bytes: ByteArray) {
    PreviewComposerPanel.show(bytes) { png -> capturedPreviewPng = png }
}

// ---- captured-preview thumbnail rendering ----

/**
 * Decode [capturedPreviewPng] into a GPU texture and draw it with [ImGui.image],
 * sized to a 16:9 box [PREVIEW_BOX_W] wide. The texture is (re)built ONLY when the
 * [capturedPreviewPng] reference changes (identity-compared), never per frame, and
 * registered under [PREVIEW_TEX_ID]; [releasePreviewTexture] unregisters it.
 *
 * Reuses the established MC-texture → ImGui pattern (PreviewImageManager registers a
 * DynamicTexture; SchematicListView.resolvePreviewGlId resolves the GL id and applies the
 * one-time GL_LINEAR completeness fix needed on strict core-profile drivers).
 * No-op (renders nothing) when no preview is captured yet.
 */
internal fun UploadWizardPanel.renderCapturedPreviewImage() {
    val png = capturedPreviewPng ?: return
    if (previewTexBuiltFor !== png) buildPreviewTexture(png)
    val glId = previewTexGlId ?: return
    if (previewTexW <= 0 || previewTexH <= 0) return
    val drawW = PREVIEW_BOX_W
    val drawH = drawW * previewTexH.toFloat() / previewTexW.toFloat()
    // uv0=(0,0) uv1=(1,1): NativeImage is top-down, so no V-flip is needed here
    // (unlike the bottom-up framebuffer blit in ThumbnailCapture.drawTargetInto).
    ImGui.image(glId.toLong(), drawW, drawH, 0f, 0f, 1f, 1f)
}

/**
 * Decode [png] → [NativeImage] → [DynamicTexture], register it under [PREVIEW_TEX_ID]
 * (releasing any prior registration first), then resolve + cache the GL handle and
 * dimensions. Applies the one-time GL_LINEAR fix (copied from
 * SchematicListView.resolvePreviewGlId) so the texture samples correctly on Apple GL 4.1.
 * Render thread only (TextureManager + GL). Safe to call repeatedly.
 */
internal fun UploadWizardPanel.buildPreviewTexture(png: ByteArray) {
    releasePreviewTexture()
    previewTexBuiltFor = png
    try {
        val nativeImage = NativeImage.read(png)
        val texture = DynamicTexture({ "schematioconnector/upload-wizard/captured-preview" }, nativeImage)
        val tm = Minecraft.getInstance().textureManager
        tm.register(PREVIEW_TEX_ID, texture)
        previewTexW = nativeImage.width
        previewTexH = nativeImage.height
        val gpuTexture = tm.getTexture(PREVIEW_TEX_ID).getTexture()
        if (gpuTexture is GlTexture) {
            val glId = gpuTexture.glId()
            if (glId != 0) {
                // GL_LINEAR completeness fix (no mipmaps on a DynamicTexture).
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glId)
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR)
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR)
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0)
                previewTexGlId = glId
            }
        }
    } catch (e: Exception) {
        LOGGER.warn("Failed to build captured-preview texture: ${e.message}")
        previewTexGlId = null
        previewTexW = 0
        previewTexH = 0
    }
}

/** Unregister the captured-preview texture and clear its cached handle. Render thread only. */
internal fun UploadWizardPanel.releasePreviewTexture() {
    if (previewTexBuiltFor != null) {
        runCatching { Minecraft.getInstance().textureManager.release(PREVIEW_TEX_ID) }
            .onFailure { LOGGER.debug("Error releasing captured-preview texture: {}", it.message) }
    }
    previewTexBuiltFor = null
    previewTexGlId = null
    previewTexW = 0
    previewTexH = 0
}

/**
 * Placeholder preview PNG (the API requires `preview_image`).
 * 256x256, hue from name hash with initials, "Preview pending" footer.
 * Identical to the vanilla wizard's placeholder.
 */
internal fun UploadWizardPanel.placeholderPng(name: String): ByteArray {
    val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
        val hue = ((name.hashCode() % 360 + 360) % 360) / 360f
        g.color = Color.getHSBColor(hue, 0.45f, 0.55f)
        g.fillRect(0, 0, 256, 256)
        g.color = Color.getHSBColor(hue, 0.5f, 0.35f)
        g.fillRect(0, 200, 256, 56)

        val initials = name.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifBlank { "?" }
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 96)
        var metrics = g.fontMetrics
        g.drawString(initials, (256 - metrics.stringWidth(initials)) / 2, 110 + metrics.ascent / 2)

        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
        metrics = g.fontMetrics
        val note = "Preview pending"
        g.drawString(note, (256 - metrics.stringWidth(note)) / 2, 234)
    } finally {
        g.dispose()
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}
