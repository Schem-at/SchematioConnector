package io.schemat.connector.fabric.client.ui

import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.core.text.RichText
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.ui.foundation.ConfirmDialogScreen
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.LoadingSpinner
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.foundation.OFFLINE_TOOLTIP
import io.schemat.connector.fabric.client.ui.foundation.PreviewDraw
import io.schemat.connector.fabric.client.ui.foundation.RichTextRender
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.widgets.TagChips
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.renderer.RenderPipelines
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full schematic detail, design-system styled: a 16:9 preview card (site preview drawn
 * via contain) with metadata rows beneath it on the left; authors (head avatars),
 * tag chips and the description on the right; and the action rows along the bottom -
 * load into Litematica / save to disk / send to the WorldEdit clipboard (each gated
 * on its [Bridges] availability where applicable), plus Edit/Delete for authors and
 * Quick Share creation.
 *
 * The detail is fetched on open; [summary] (when navigating from a listing) seeds the
 * header so the screen renders immediately.
 */
class SchematicDetailScreen(
    private val parent: Screen,
    private val schematicId: String,
    private val summary: SchematicSummary? = null,
) : Screen(Component.literal(summary?.name ?: "Schematic")) {

    companion object {
        private const val PAD = Theme.XL
        private const val BUTTON_HEIGHT = Theme.BTN_H
        private const val BUTTON_GAP = Theme.SM
        /** Vertical space reserved for the [Theme.header] drawn in render(). */
        private const val HEADER_RESERVED = 28
        private const val HEAD_SIZE = 12
        private const val AUTHOR_ROW_H = 16
        private const val SUBFOLDER = "schemat.io"
    }

    private val services get() = SchematioClientMod.instance.services

    private var detail: SchematicDetail? = null
    private var fetchError: String? = null
    private val fetchBusy = AtomicBoolean(false)
    private val actionBusy = AtomicBoolean(false)

    private val banner = NoticeBanner()
    private val actionButtons = mutableListOf<FlatButton>()

    /** True when the signed-in player is one of the schematic's authors. */
    private val isAuthor: Boolean
        get() {
            val selfUuid = services.authManager.session?.playerUuid?.normalizedUuid() ?: return false
            return detail?.authors?.any { it.uuid.normalizedUuid() == selfUuid } == true
        }

    private fun String.normalizedUuid(): String = lowercase().replace("-", "")

    override fun init() {
        super.init()
        actionButtons.clear()

        screenLayout()

        addRenderableWidget(banner.layout(PAD, bannerY, width - PAD * 2))

        if (detail == null && fetchError == null && !fetchBusy.get()) fetchDetail()

        buildButtons()
    }

    // ---- layout ----

    private var previewX = 0
    private var previewY = 0
    private var previewW = 0
    private var previewH = 0
    private var metaTop = 0
    private var infoX = 0
    private var infoTop = 0
    private var infoWidth = 0
    private var bannerY = 0
    private var buttonsTop = 0

    private fun screenLayout() {
        val contentTop = PAD + HEADER_RESERVED
        buttonsTop = height - PAD - (BUTTON_HEIGHT * 2 + BUTTON_GAP)
        bannerY = buttonsTop - NoticeBanner.HEIGHT - Theme.MD

        // 16:9 preview, sized to roughly the left half but never starving the
        // metadata rows below it of vertical room.
        previewW = ((width - PAD * 2) * 45 / 100).coerceIn(120, 320)
        previewH = previewW * 9 / 16
        val maxPreviewH = (bannerY - contentTop - Theme.MD - 56).coerceAtLeast(45)
        if (previewH > maxPreviewH) {
            previewH = maxPreviewH
            previewW = (previewH * 16 / 9).coerceAtLeast(80)
        }
        previewX = PAD
        previewY = contentTop
        metaTop = previewY + previewH + Theme.LG

        infoX = PAD + previewW + Theme.XL
        infoTop = contentTop
        infoWidth = (width - PAD - infoX).coerceAtLeast(60)
    }

    private fun buildButtons() {
        val loaded = detail != null
        var x = PAD
        var y = buttonsTop

        fun addAction(
            label: String,
            width: Int = 120,
            variant: FlatButton.Variant = FlatButton.Variant.SECONDARY,
            visible: Boolean = true,
            enabled: Boolean = true,
            tooltip: String? = null,
            mutating: Boolean = false,
            onPress: () -> Unit,
        ): FlatButton {
            // Mutating actions are hard-disabled while offline (and excluded from the
            // per-frame actionButtons re-activation in render()).
            val offline = mutating && services.isOffline()
            val tooltipText = if (offline) OFFLINE_TOOLTIP else tooltip
            val button = FlatButton(x, y, width, BUTTON_HEIGHT, Component.literal(label), variant, onPress)
            tooltipText?.let { button.setTooltip(Tooltip.create(Component.literal(it))) }
            button.visible = visible
            button.active = enabled && loaded && !offline
            if (visible) x += width + BUTTON_GAP
            addRenderableWidget(button)
            if (enabled && visible && !offline) actionButtons.add(button)
            return button
        }

        // Row 1: download/export actions
        addAction("Load into Litematica", 130, visible = Bridges.litematica.isAvailable) { loadIntoLitematica() }
        addAction("Save to disk", 100) { saveToDisk() }
        addAction("To WorldEdit clipboard", 140, visible = Bridges.worldEdit.isAvailable) { toWorldEditClipboard() }
        addAction("Quick share", 100, tooltip = "Create a temporary share link for this schematic",
                  mutating = true) { openQuickShare() }

        // Row 2: manage actions + back
        x = PAD
        y += BUTTON_HEIGHT + BUTTON_GAP
        addAction("Edit", 80, variant = FlatButton.Variant.PRIMARY, visible = isAuthor, mutating = true) { openEdit() }
        addAction("Delete", 80, variant = FlatButton.Variant.DANGER, visible = isAuthor, mutating = true) { confirmDelete() }

        addRenderableWidget(
            FlatButton.ghost(width - PAD - 80, y, 80, Component.literal("Back")) { onClose() }
        )
    }

    // ---- data ----

    private fun fetchDetail() {
        services.call(
            busy = fetchBusy,
            block = { services.cached.schematic(schematicId) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    detail = result.value
                    fetchError = null
                    rebuildWidgets() // rebuild buttons now that authorship/format are known
                }
                is ApiResult.Failure -> {
                    fetchError = result.error.toUserMessage()
                    banner.show(NoticeBanner.Kind.ERROR, fetchError!!) {
                        fetchError = null
                        banner.clear()
                        fetchDetail()
                    }
                }
            }
        }
    }

    // ---- actions ----

    /** Directory downloads land in: Litematica's schematics dir when known, else `run/schematics`. */
    private fun downloadDirectory(): Path =
        (Bridges.litematica.schematicsDirectory()
            ?: FabricLoader.getInstance().gameDir.resolve("schematics"))
            .resolve(SUBFOLDER)

    /** Defensive file-name sanitizer; slugs are already safe, anything else gets stripped. */
    private fun fileBaseName(detail: SchematicDetail): String {
        val base = detail.slug ?: detail.shortId ?: detail.id
        return base.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").ifBlank { "schematic" }
    }

    /** Download [format] bytes and write them under the downloads directory (runs on IO). */
    private fun downloadToFile(format: String, extension: String, onSaved: (Path) -> Unit) {
        val target = detail ?: return
        services.call(
            busy = actionBusy,
            block = {
                when (val result = services.api.download(target.id, format)) {
                    is ApiResult.Success -> {
                        val dir = downloadDirectory()
                        Files.createDirectories(dir)
                        val file = dir.resolve("${fileBaseName(target)}.$extension")
                        Files.write(file, result.value)
                        ApiResult.Success(file)
                    }
                    is ApiResult.Failure -> result
                }
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> onSaved(result.value)
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    private fun loadIntoLitematica() {
        val target = detail ?: return
        downloadToFile("litematic", "litematic") { file ->
            Bridges.litematica.loadSchematic(file.toFile(), target.name) { ok, error ->
                if (ok) {
                    banner.show(NoticeBanner.Kind.INFO, "Loaded \"${target.name}\" into Litematica")
                } else {
                    banner.show(NoticeBanner.Kind.ERROR, error ?: "Failed to load schematic into Litematica")
                }
            }
        }
    }

    private fun saveToDisk() {
        downloadToFile("litematic", "litematic") { file ->
            banner.show(NoticeBanner.Kind.INFO, "Saved to $file")
        }
    }

    private fun toWorldEditClipboard() {
        val target = detail ?: return
        services.call(
            busy = actionBusy,
            block = { services.api.download(target.id, "schem") },
        ) { result ->
            when (result) {
                is ApiResult.Success -> Bridges.worldEdit.bytesToClipboard(result.value, "schem") { ok, error ->
                    if (ok) {
                        banner.show(NoticeBanner.Kind.INFO, "Copied \"${target.name}\" to the WorldEdit clipboard")
                    } else {
                        banner.show(NoticeBanner.Kind.ERROR, error ?: "Failed to set the WorldEdit clipboard")
                    }
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    private fun openEdit() {
        val target = detail ?: return
        minecraft!!.setScreen(SchematicEditScreen(this, target))
    }

    /**
     * Quick-share this schematic: the create screen downloads the schematic bytes from
     * the API at create time and re-shares them (quick shares carry their own data).
     */
    private fun openQuickShare() {
        val target = detail ?: return
        val format = target.format
            ?.takeIf { it in setOf("schem", "schematic", "litematic", "nbt", "mcstructure") }
            ?: "litematic"
        minecraft!!.setScreen(
            QuickShareCreateScreen(
                this,
                QuickShareCreateScreen.RemoteSchematicSource(target.id, target.name, format),
            )
        )
    }

    /**
     * Called by [SchematicEditScreen] after a successful save: drop the loaded detail so
     * the next init (re-show via setScreen) refetches it, and refresh the listings.
     */
    fun onEdited() {
        detail = null
        fetchError = null
        (parent as? HomeScreen)?.invalidateListings()
    }

    /** Forward a listings invalidation to the host HomeScreen (e.g. after a quick share). */
    fun invalidateHomeListings() {
        (parent as? HomeScreen)?.invalidateListings()
    }

    private fun confirmDelete() {
        val target = detail ?: return
        minecraft!!.setScreen(
            ConfirmDialogScreen(
                this,
                "Delete schematic",
                "Permanently delete \"${target.name}\"? This cannot be undone.",
                confirmLabel = "Delete",
                danger = true,
            ) { performDelete() }
        )
    }

    private fun performDelete() {
        val target = detail ?: return
        services.call(
            busy = actionBusy,
            block = { services.cached.deleteSchematic(target.id) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    (parent as? HomeScreen)?.invalidateListings()
                    minecraft!!.setScreen(parent)
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    // ---- rendering ----

    // 26.x renamed the Screen render hooks: render -> extractRenderState,
    // renderBackground -> extractBackground.
    //? if >=26.1 {
    /*override fun extractBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
    *///?} else {
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.renderBackground(context, mouseX, mouseY, delta)
    //?}
        Theme.scrim(context, width, height)
    }

    //? if >=26.1 {
    /*override fun extractRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
    *///?} else {
    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    //?}

        val shown = detail
        val name = shown?.name ?: summary?.name ?: "Schematic"
        Theme.header(
            context, font,
            font.plainSubstrByWidth(name, width - PAD * 2),
            null, PAD, PAD, width - PAD * 2,
        )

        renderPreview(context, shown?.previewImageUrl ?: summary?.previewImageUrl)
        renderMetadata(context, shown)
        renderInfo(context, shown)

        actionButtons.forEach { it.active = shown != null && !actionBusy.get() }
        if (actionBusy.get()) {
            LoadingSpinner.render(context, font, width / 2, bannerY - 12, "Working")
        } else if (shown == null && fetchError == null) {
            LoadingSpinner.render(context, font, infoX + infoWidth / 2, infoTop + 20)
        }

        banner.render(context, font)
    }

    /** 16:9 preview card: site preview drawn via contain, letterboxed over SURFACE_ALT. */
    private fun renderPreview(context: GuiGraphics, previewUrl: String?) {
        Theme.card(context, previewX, previewY, previewW, previewH, fill = Theme.SURFACE_ALT)
        val entry = services.previewImages.getEntry(schematicId, previewUrl)
        if (entry != null) {
            PreviewDraw.drawContain(
                context, entry.id, entry.width, entry.height,
                previewX, previewY, previewW, previewH,
                background = Theme.SURFACE_ALT,
            )
        } else {
            val label = when {
                services.previewImages.isLoading(schematicId) -> "Loading preview..."
                previewUrl.isNullOrBlank() -> "No preview"
                else -> "Preview unavailable"
            }
            Theme.emptyState(context, font, label, previewX, previewY, previewW, previewH)
        }
    }

    /** Metadata rows under the preview: faint section label + value, one per line. */
    private fun renderMetadata(context: GuiGraphics, shown: SchematicDetail?) {
        var y = metaTop
        val labelW = 56

        fun row(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            Theme.sectionLabel(context, font, label, previewX, y + 1)
            Theme.value(
                context, font,
                font.plainSubstrByWidth(value, previewW - labelW),
                previewX + labelW, y,
            )
            y += 12
        }

        row("Format", shown?.format ?: summary?.format)
        row("Access", shown?.let { if (it.isPublic) "Public" else "Private" })
        row("Created", shown?.createdAt?.dateOnly())
        row("Updated", shown?.updatedAt?.dateOnly())
    }

    /** Right column: authors with head avatars, tag chips, description. */
    private fun renderInfo(context: GuiGraphics, shown: SchematicDetail?) {
        var y = infoTop

        val authors = shown?.authors ?: summary?.authors ?: emptyList()
        if (authors.isNotEmpty()) {
            Theme.sectionLabel(context, font, "Authors", infoX, y)
            y += 11
            authors.forEach { author ->
                val headY = y + (AUTHOR_ROW_H - HEAD_SIZE) / 2
                val headId = services.headAvatars.getHead(author.uuid, author.headUrl)
                if (headId != null) {
                    val (texW, texH) = services.headAvatars.getHeadSize(author.uuid) ?: (64 to 64)
                    context.blit(
                        RenderPipelines.GUI_TEXTURED, headId,
                        infoX, headY, 0f, 0f,
                        HEAD_SIZE, HEAD_SIZE, texW, texH, texW, texH,
                    )
                } else {
                    context.fill(infoX, headY, infoX + HEAD_SIZE, headY + HEAD_SIZE, Theme.BORDER)
                }
                Theme.value(
                    context, font,
                    font.plainSubstrByWidth(author.lastSeenName.ifBlank { author.uuid }, infoWidth - HEAD_SIZE - Theme.SM),
                    infoX + HEAD_SIZE + Theme.SM, y + (AUTHOR_ROW_H - 8) / 2,
                    Theme.TEXT_SECONDARY,
                )
                y += AUTHOR_ROW_H
            }
            y += Theme.SM
        }

        shown?.let { d ->
            if (d.tags.isNotEmpty()) {
                Theme.sectionLabel(context, font, "Tags", infoX, y)
                y += 11
                y = renderTagChips(context, d, y) + Theme.SM
            }

            d.description?.let { RichText.htmlToSpans(it) }?.takeIf { spans ->
                spans.any { it.text.isNotBlank() }
            }?.let { spans ->
                Theme.sectionLabel(context, font, "Description", infoX, y)
                y += 11
                // Styled rendering (bold/italic/underline/strikethrough + bullets).
                y += RichTextRender.drawWrapped(
                    context, font, spans, infoX, y, infoWidth,
                    lineHeight = 10, color = Theme.TEXT_SECONDARY, maxY = bannerY - 12,
                )
            }
        }
    }

    /** Tag chips via the shared design-system pill, wrapped into the info column. */
    private fun renderTagChips(context: GuiGraphics, shown: SchematicDetail, startY: Int): Int {
        val chips = shown.tags.map { TagChips.Chip(null, it.name, it.color) }
        val maxH = (bannerY - startY).coerceAtLeast(TagChips.CHIP_HEIGHT + 4)
        val (placed, overflow) = TagChips.layout(chips, infoX - 2, startY - 2, infoWidth, maxH, font, withClose = false)
        TagChips.render(context, font, placed, overflow)
        val bottom = placed.maxOfOrNull { it.y + TagChips.CHIP_HEIGHT } ?: startY
        return bottom + Theme.XS
    }

    private fun String.dateOnly(): String = take(10)

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }
}
