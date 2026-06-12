package io.schemat.connector.fabric.client.ui

import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.UploadRequest
import io.schemat.connector.core.modapi.dto.CommunitySummary
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.core.text.RichText
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.ExportSource
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.LoadingSpinner
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.foundation.PreviewDraw
import io.schemat.connector.fabric.client.ui.foundation.RichDescriptionEditor
import io.schemat.connector.fabric.client.ui.foundation.RichTextRender
import io.schemat.connector.fabric.client.ui.foundation.ThemedTextField
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.renderer.RenderPipelines
import io.schemat.connector.fabric.client.ui.widgets.ExportSourceListWidget
import io.schemat.connector.fabric.client.ui.widgets.ExportSources
import io.schemat.connector.fabric.client.ui.widgets.PlayerListEditorWidget
import io.schemat.connector.fabric.client.ui.widgets.TagChips
import io.schemat.connector.fabric.client.ui.widgets.TagPickerPanel
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.AbstractWidget
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * Three-step upload wizard:
 * 1. **Source** - Litematica export sources, the WorldEdit clipboard, and local
 *    `.litematic`/`.schem`/`.schematic` files (Litematica's schematics dir, or
 *    `run/schematics`). Skipped when a source is already known: an explicit
 *    [preselect] (mixin entry points) or Litematica's current selection
 *    ([LitematicaBridge.currentSelectionSource]) starts the wizard on Details,
 *    with a "Source: … / Change..." affordance to reopen this step.
 * 2. **Details** - name, description, visibility, community, co-authors, an INLINE
 *    tabbed tag picker ([TagPickerPanel]: "Minecraft" default tab + the selected
 *    community's tab - no popup), and an optional composed thumbnail
 *    ("Compose preview…" opens [ThumbnailComposerScreen] when Litematica can load
 *    the source).
 * 3. **Confirm** - uploads via `POST /schematics` with the composed preview PNG, or a
 *    generated placeholder when none was captured (the API requires `preview_image`).
 *
 * Success closes the wizard back to the game and posts a clickable chat message
 * with the web link (see [ChatNotice]); validation failures jump back to the
 * details step with the field errors in the banner.
 */
class UploadWizardScreen(
    private val parent: HomeScreen,
    preselect: ExportSource? = null,
) : Screen(Component.literal("Upload schematic")) {

    private enum class Step { SOURCE, DETAILS, CONFIRM }

    /**
     * "None" is a real object wrapping a null [CommunitySummary] rather than a raw
     * null in the choices list. (Historic: the old CyclingButtonWidget copied values
     * into a Guava ImmutableList which NPE'd on nulls and aborted details-step init
     * halfway through, leaving the screen without its nav buttons. The wrapper also
     * keeps the cycler's list/index logic null-safe.)
     */
    private class CommunityChoice(val community: CommunitySummary?)

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-upload-wizard")
        private const val PADDING = Theme.XL
        private const val CONTROL_HEIGHT = Theme.INPUT_H
        private const val BUTTON_HEIGHT = Theme.BTN_H
        private const val GAP = Theme.MD
        private const val LABEL_HEIGHT = 11
        /** Vertical space consumed by the [Theme.header] block (title + subtitle + rule + gap). */
        private const val HEADER_USED = 39
        private const val NAV_BTN_W = 90
    }

    private val services get() = SchematioClientMod.instance.services

    // ---- wizard state (survives re-init) ----
    private var step = Step.SOURCE

    private var sources: List<ExportSource> = emptyList()
    private var selectedSource: ExportSource? = preselect

    init {
        // No explicit preselect (HomeScreen "Upload"): default to Litematica's current
        // selection (selected placement, else current area selection). Whenever a source
        // is already known the wizard starts on the details step - the source step stays
        // reachable via "Change..." / "< Back".
        if (selectedSource == null) {
            selectedSource = runCatching { Bridges.litematica.currentSelectionSource() }
                .onFailure { LOGGER.warn("Could not query the current Litematica selection", it) }
                .getOrNull()
        }
        if (selectedSource != null) step = Step.DETAILS
    }

    private var nameText = ""

    /** Sanitizer-safe website HTML, kept in sync with the inline description editor. */
    private var descriptionText = ""
    private var isPublic = true
    private var communities: List<CommunitySummary> = emptyList()
    private var communitiesLoaded = false
    private var selectedCommunity: CommunitySummary? = null
    /** Global "Minecraft" tag tree - offered regardless of community selection. */
    private var globalTagNodes: List<TagNode> = emptyList()
    /** Tag tree of the currently selected community (empty while none selected). */
    private var communityTagNodes: List<TagNode> = emptyList()
    /** True once the selected community's tag tree finished loading (ok or failed). */
    private var communityTagsLoaded = false
    /** Every node of the currently loaded trees, for the confirm card's chip labels. */
    private val tagNodeById = mutableMapOf<String, TagNode>()
    /**
     * Inline tabbed tag picker (Minecraft default tab + the selected community's tab),
     * embedded in the details step - no popup. A long-lived field so the selection,
     * filter values, search and expansion survive step changes and re-inits; the
     * upload reads [TagPickerPanel.selectedTagIds]/[TagPickerPanel.filterValues]
     * directly at submit time.
     */
    private val tagPicker = TagPickerPanel(TagPickerPanel.Mode.ASSIGN) {
        if (step == Step.DETAILS) rebuildWidgets()
    }
    private val coAuthors = PlayerListEditorWidget(
        services = SchematioClientMod.instance.services,
        maxEntries = 10,
    )

    init {
        // Tabs exist from the first frame: empty trees show their loading/empty hints.
        updateTagTabs(prune = false)
    }

    // Composed thumbnail (survives the ThumbnailComposerScreen round-trip because the
    // composer's parent IS this wizard instance and re-init never resets these fields).
    private var capturedPreview: ByteArray? = null
    private var previewTextureId: Identifier? = null
    private var previewTextureWidth = 0
    private var previewTextureHeight = 0
    private var composerLoading = false

    private val loadBusy = AtomicBoolean(false)
    private val uploadBusy = AtomicBoolean(false)
    private var exporting = false

    private val banner = NoticeBanner()

    // ---- label positions ----
    private var leftX = 0
    private var rightX = 0
    private var columnWidth = 0
    private var sourceLabelY = 0
    private var nameLabelY = 0
    private var descriptionLabelY = 0
    private var descriptionEditor: RichDescriptionEditor? = null
    private var visibilityLabelY = 0
    private var communityLabelY = 0
    private var previewLabelY = 0
    private var previewSlotArea = intArrayOf(0, 0, 0, 0) // x, y, w, h
    private var composeButton: AbstractWidget? = null
    private var tagsLabelY = 0
    private var coAuthorsLabelY = 0
    private var contentTop = 0

    private var nextButton: AbstractWidget? = null

    override fun init() {
        super.init()
        contentTop = PADDING + HEADER_USED
        val buttonsTop = height - PADDING - BUTTON_HEIGHT
        val bannerY = buttonsTop - NoticeBanner.HEIGHT - 4
        addRenderableWidget(banner.layout(PADDING, bannerY, width - PADDING * 2))
        nextButton = null
        descriptionEditor = null

        // removed() destroys the captured-preview texture (e.g. while the
        // composer screen is on top); restore it from the retained bytes.
        capturedPreview?.let { if (previewTextureId == null) registerPreviewTexture(it) }

        when (step) {
            Step.SOURCE -> initSourceStep(buttonsTop, bannerY)
            Step.DETAILS -> initDetailsStep(buttonsTop, bannerY)
            Step.CONFIRM -> initConfirmStep(buttonsTop, bannerY)
        }
    }

    private fun goTo(next: Step) {
        step = next
        banner.clear()
        rebuildWidgets()
    }

    /**
     * Bottom navigation, added FIRST on every step so Cancel / Back / Next exist no
     * matter what happens while the rest of the step builds (and independent of any
     * still-loading tags/me data): Cancel (left), optional Back, Next/Upload (right).
     */
    private fun addNavButtons(buttonsTop: Int, back: Step?, nextLabel: String, primary: Boolean = false, next: () -> Unit) {
        addRenderableWidget(FlatButton.ghost(PADDING, buttonsTop, 80, Component.literal("Cancel")) { onClose() })
        if (back != null) {
            addRenderableWidget(FlatButton.secondary(PADDING + 80 + GAP, buttonsTop, 80, Component.literal("< Back")) { goTo(back) })
        }
        // The final action (Upload) echoes the website's emerald publish CTA;
        // intermediate steps advance with the accent primary.
        nextButton = addRenderableWidget(
            if (primary) {
                FlatButton.success(width - PADDING - NAV_BTN_W, buttonsTop, NAV_BTN_W, Component.literal(nextLabel)) { next() }
            } else {
                FlatButton.primary(width - PADDING - NAV_BTN_W, buttonsTop, NAV_BTN_W, Component.literal(nextLabel)) { next() }
            }
        )
    }

    // ---- step 1: source ----

    private fun initSourceStep(buttonsTop: Int, bannerY: Int) {
        addNavButtons(buttonsTop, back = null, nextLabel = "Next >") {
            if (selectedSource != null) goTo(Step.DETAILS)
        }

        sources = ExportSources.collect()
        val preselected = selectedSource
        if (preselected != null && sources.none { it.id == preselected.id }) {
            // A LOCAL_FILE preselect (e.g. from Litematica's load GUI) can fall outside the
            // collected list (depth/count caps, other directory) - keep it by prepending.
            val existsOnDisk = preselected.kind == SourceKind.LOCAL_FILE &&
                runCatching { Files.isRegularFile(Path.of(preselected.id)) }.getOrDefault(false)
            if (existsOnDisk) {
                sources = listOf(preselected) + sources
            } else {
                selectedSource = null
            }
        }

        val listHeight = (bannerY - GAP - contentTop).coerceAtLeast(40)
        addRenderableWidget(
            ExportSourceListWidget(
                PADDING, contentTop, width - PADDING * 2, listHeight,
                sources = { sources },
                selectedId = { selectedSource?.id },
                onSelect = {
                    // A composed preview belongs to the source it was rendered from.
                    if (it.id != selectedSource?.id) clearCapturedPreview()
                    selectedSource = it
                },
            )
        )
    }

    // ---- step 2: details ----

    private fun initDetailsStep(buttonsTop: Int, bannerY: Int) {
        // Nav first: Back/Next/Cancel must exist even if a form widget throws below
        // (a half-built details screen previously had NO way forward or back).
        addNavButtons(buttonsTop, back = Step.SOURCE, nextLabel = "Next >") { validateDetailsAndContinue() }

        runCatching { buildDetailsForm(bannerY) }.onFailure { t ->
            LOGGER.error("Failed to build the upload details form", t)
            banner.show(NoticeBanner.Kind.ERROR, "Form failed to build: ${t.message ?: t.javaClass.simpleName}")
        }

        if (!communitiesLoaded && !loadBusy.get()) loadCommunities()
    }

    private fun buildDetailsForm(bannerY: Int) {
        // Two aligned columns separated by an XL gutter.
        columnWidth = ((width - PADDING * 2 - Theme.XL) / 2).coerceAtLeast(80)
        leftX = PADDING
        rightX = PADDING + columnWidth + Theme.XL
        val contentBottom = bannerY - GAP

        var y = contentTop
        // Where the upload comes from, with a jump back to the source picker.
        sourceLabelY = y + (BUTTON_HEIGHT - font.lineHeight) / 2 + 1
        addRenderableWidget(
            FlatButton.secondary(leftX + columnWidth - 70, y, 70, Component.literal("Change…")) { goTo(Step.SOURCE) }
        )
        y += BUTTON_HEIGHT + Theme.LG

        nameLabelY = y
        y += LABEL_HEIGHT
        val nameField = ThemedTextField(
            font, leftX, y, columnWidth, CONTROL_HEIGHT,
            Component.literal("Name"), placeholder = "Schematic name",
        )
        nameField.setMaxLength(255)
        nameField.value = nameText
        nameField.setResponder { nameText = it }
        addRenderableWidget(nameField)
        y += CONTROL_HEIGHT + Theme.LG

        descriptionLabelY = y
        y += LABEL_HEIGHT
        // Reserve room below the description for visibility + community + the preview
        // label/slot/button block.
        val reservedBelow = (LABEL_HEIGHT + BUTTON_HEIGHT + Theme.LG) * 2 +
            LABEL_HEIGHT + 32 + GAP + BUTTON_HEIGHT
        val descriptionHeight = (contentBottom - y - reservedBelow)
            .coerceIn(RichDescriptionEditor.MIN_HEIGHT, 136)
        val editor = RichDescriptionEditor(font, leftX, y, columnWidth, descriptionHeight) {
            descriptionText = it
        }
        editor.setFromHtml(descriptionText)
        editor.widgets().forEach { addRenderableWidget(it) }
        descriptionEditor = editor
        y += descriptionHeight + Theme.LG

        visibilityLabelY = y
        y += LABEL_HEIGHT
        lateinit var visibilityButton: FlatButton
        visibilityButton = FlatButton.secondary(
            leftX, y, columnWidth.coerceAtMost(140), Component.literal(visibilityText()),
        ) {
            isPublic = !isPublic
            visibilityButton.message = Component.literal(visibilityText())
        }
        addRenderableWidget(visibilityButton)
        y += BUTTON_HEIGHT + Theme.LG

        // Community selector (None + joined communities); tags load per selection.
        // NOTE: values must not contain null (see [CommunityChoice]).
        communityLabelY = y
        y += LABEL_HEIGHT
        val noneChoice = CommunityChoice(null)
        val choices = listOf(noneChoice) + communities.map { CommunityChoice(it) }
        val currentChoice = choices.firstOrNull { it.community?.slug == selectedCommunity?.slug } ?: noneChoice
        selectedCommunity = currentChoice.community
        // Themed cycler (FlatButton.secondary stepping through the choices) -
        // mirrors BrowseTab's cycler() so no vanilla CyclingButtonWidget chrome remains.
        var communityIndex = choices.indexOf(currentChoice).coerceAtLeast(0)
        fun communityLabel(choice: CommunityChoice) = "Community: ${choice.community?.name ?: "None"}"
        lateinit var communityButton: FlatButton
        communityButton = FlatButton.secondary(
            leftX, y, columnWidth, Component.literal(communityLabel(currentChoice)), BUTTON_HEIGHT,
        ) {
            communityIndex = (communityIndex + 1) % choices.size
            val value = choices[communityIndex]
            communityButton.message = Component.literal(communityLabel(value))
            if (value.community?.slug != selectedCommunity?.slug) {
                selectedCommunity = value.community
                // Swap the Community tab immediately: drop the old community's tree
                // (and, via prune, its checks/filter values - minecraft-tree
                // selections survive), then load the new community's tags.
                communityTagNodes = emptyList()
                communityTagsLoaded = false
                rebuildTagLookup()
                updateTagTabs(prune = true)
                loadCommunityTags()
            }
        }
        addRenderableWidget(communityButton)
        y += BUTTON_HEIGHT + Theme.LG

        // Thumbnail preview slot + "Compose preview..." (renders the captured PNG when
        // present; the composer needs Litematica to load the source). The slot
        // is 16:9 like the capture and the website's preview crop, shrunk to
        // fit the remaining column height.
        previewLabelY = y
        y += LABEL_HEIGHT
        val previewMaxHeight = (contentBottom - y - GAP - BUTTON_HEIGHT).coerceIn(24, 96)
        var previewSlotWidth = columnWidth
        var previewSlotHeight = previewSlotWidth * 9 / 16
        if (previewSlotHeight > previewMaxHeight) {
            previewSlotHeight = previewMaxHeight
            previewSlotWidth = (previewSlotHeight * 16 / 9).coerceAtMost(columnWidth)
        }
        previewSlotArea = intArrayOf(leftX, y, previewSlotWidth, previewSlotHeight)
        y += previewSlotHeight + GAP
        composeButton = addRenderableWidget(
            FlatButton.secondary(leftX, y, columnWidth.coerceAtMost(140), Component.literal("Compose preview…")) {
                openThumbnailComposer()
            }
        )

        // Right column: the inline tabbed tag picker takes the lion's share (it
        // scrolls internally), the co-author editor keeps a compact strip below.
        var ry = contentTop
        tagsLabelY = ry
        ry += LABEL_HEIGHT
        val rightHeight = (contentBottom - contentTop).coerceAtLeast(80)
        val coAuthorsHeight = (rightHeight / 3).coerceIn(48, 88)
        val tagsHeight = (contentBottom - ry - (LABEL_HEIGHT + Theme.LG + coAuthorsHeight))
            .coerceAtLeast(72)
        tagPicker.layout(rightX, ry, columnWidth, tagsHeight).forEach { addRenderableWidget(it) }
        ry += tagsHeight + Theme.LG

        coAuthorsLabelY = ry
        ry += LABEL_HEIGHT
        coAuthors.layout(rightX, ry, columnWidth, (contentBottom - ry).coerceAtLeast(40))
            .forEach { addRenderableWidget(it) }
    }

    private fun visibilityText(): String = if (isPublic) "Public" else "Private"

    /**
     * Load the global "Minecraft" tag tree and the player's communities in one go.
     * A `me()` failure only fails the call when the global tree is also unavailable;
     * otherwise the picker still offers the minecraft tags.
     */
    private fun loadCommunities() {
        services.call(
            busy = loadBusy,
            block = {
                val global = (services.cached.globalTags() as? ApiResult.Success)?.value?.value ?: emptyList()
                when (val me = services.cached.me()) {
                    is ApiResult.Failure ->
                        if (global.isEmpty()) ApiResult.Failure(me.error)
                        else ApiResult.Success(global to null)
                    is ApiResult.Success -> ApiResult.Success(global to me.value.value.communities)
                }
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    val (global, loadedCommunities) = result.value
                    globalTagNodes = global
                    communitiesLoaded = true
                    if (loadedCommunities != null) {
                        communities = loadedCommunities
                    } else {
                        banner.show(NoticeBanner.Kind.ERROR, "Communities unavailable") {
                            banner.clear()
                            communitiesLoaded = false
                            loadCommunities()
                        }
                    }
                    rebuildTagLookup()
                    updateTagTabs(prune = false)
                    if (step == Step.DETAILS) rebuildWidgets()
                }
                is ApiResult.Failure -> {
                    communitiesLoaded = true
                    banner.show(NoticeBanner.Kind.ERROR, "Communities unavailable: ${result.error.toUserMessage()}") {
                        banner.clear()
                        communitiesLoaded = false
                        loadCommunities()
                    }
                }
            }
        }
    }

    /** Rebuild [tagNodeById] from the loaded global + community trees. */
    private fun rebuildTagLookup() {
        tagNodeById.clear()
        fun walk(node: TagNode) {
            tagNodeById.putIfAbsent(node.id, node)
            node.children.forEach { walk(it) }
        }
        globalTagNodes.forEach { walk(it) }
        communityTagNodes.forEach { walk(it) }
    }

    /**
     * Refresh the inline picker's tabs: "Minecraft" (default, the global tree) and
     * "Community" (the selected community's tree, labeled with its name). Empty
     * trees explain themselves via the tab's empty hint - no community selected,
     * still loading, or genuinely tag-less. [prune] drops selections/filter values
     * that no longer exist in any tab (used when the community changes so the old
     * community's tags don't linger invisibly on the upload).
     */
    private fun updateTagTabs(prune: Boolean) {
        val community = selectedCommunity
        val minecraftHint = if (communitiesLoaded) "No tags available" else "Loading tags..."
        val communityHint = when {
            community == null -> "Pick a community to use its tags"
            !communityTagsLoaded -> "Loading tags..."
            else -> "No tags in ${community.name}"
        }
        tagPicker.setTabs(
            listOf(
                TagPickerPanel.Tab("Minecraft", globalTagNodes, minecraftHint),
                TagPickerPanel.Tab(community?.name ?: "Community", communityTagNodes, communityHint),
            ),
            pruneSelectionToKnown = prune,
        )
    }

    // ---- thumbnail composer round-trip ----

    /**
     * Resolve the selected source into a [io.schemat.connector.fabric.client.render.SchematicRenderSource]
     * via the Litematica bridge and open the [ThumbnailComposerScreen] over it.
     *
     * The composer's parent is THIS wizard instance: capturing restores the wizard via
     * `setScreen(parent)` (which re-inits widgets from the persistent fields above) and
     * then delivers the PNG through `onCaptured`, so name/description/tags/source all
     * survive the round-trip. Without Litematica the bridge reports an error and the
     * placeholder preview remains in effect.
     */
    private fun openThumbnailComposer() {
        if (composerLoading) return
        val source = selectedSource
        if (source == null) {
            banner.show(NoticeBanner.Kind.ERROR, "Select a source first")
            return
        }
        composerLoading = true
        Bridges.litematica.loadRenderSource(source) { renderSource, error ->
            services.onMainThread {
                composerLoading = false
                // The user may have Esc'd or navigated away while the render
                // source loaded - never yank navigation from a screen we no
                // longer own. Drop the result silently; the source is a frozen
                // snapshot with no side effects to undo.
                if (minecraft?.screen !== this) {
                    return@onMainThread
                }
                if (renderSource != null) {
                    minecraft!!.setScreen(
                        ThumbnailComposerScreen(this, renderSource) { png -> setCapturedPreview(png) }
                    )
                } else {
                    banner.show(NoticeBanner.Kind.ERROR, error ?: "Preview needs Litematica")
                }
            }
        }
    }

    /** Store the composed PNG and register it as a texture for the preview slot. */
    private fun setCapturedPreview(png: ByteArray) {
        capturedPreview = png
        registerPreviewTexture(png)
        banner.show(NoticeBanner.Kind.INFO, "Preview captured")
    }

    /**
     * Decode [png] and register it under a fresh Identifier for the preview
     * slot. On failure the decoded NativeImage is closed (no native-memory
     * leak) and the slot falls back to a text confirmation - the bytes are
     * still uploaded either way.
     */
    private fun registerPreviewTexture(png: ByteArray) {
        destroyPreviewTexture()
        runCatching {
            val image = NativeImage.read(png)
            try {
                val id = Identifier.fromNamespaceAndPath("schematioconnector", "upload_wizard/captured_${System.nanoTime()}")
                val texture = DynamicTexture({ id.toString() }, image)
                minecraft!!.textureManager.register(id, texture)
                previewTextureWidth = image.width
                previewTextureHeight = image.height
                previewTextureId = id
            } catch (e: Exception) {
                // registerTexture (or texture construction) failed AFTER the
                // decode - close the NativeImage instead of leaking it.
                image.close()
                throw e
            }
        }.onFailure {
            previewTextureId = null
        }
    }

    private fun clearCapturedPreview() {
        capturedPreview = null
        destroyPreviewTexture()
    }

    private fun destroyPreviewTexture() {
        previewTextureId?.let { id ->
            runCatching { minecraft?.textureManager?.release(id) }
        }
        previewTextureId = null
        previewTextureWidth = 0
        previewTextureHeight = 0
    }

    private fun loadCommunityTags() {
        val community = selectedCommunity
        if (community == null) {
            communityTagNodes = emptyList()
            communityTagsLoaded = false
            rebuildTagLookup()
            updateTagTabs(prune = true)
            if (step == Step.DETAILS) rebuildWidgets()
            return
        }
        services.call(
            busy = loadBusy,
            block = { services.cached.communityTags(community.slug) },
        ) { result ->
            // The user may have cycled away while the request was in flight - a stale
            // tree must not land in the (renamed) Community tab. If the new
            // community's own load was skipped by the busy guard, re-issue it now.
            if (selectedCommunity?.slug != community.slug) {
                if (selectedCommunity != null && !communityTagsLoaded && !loadBusy.get()) loadCommunityTags()
                return@call
            }
            when (result) {
                is ApiResult.Success -> communityTagNodes = result.value.value
                is ApiResult.Failure -> {
                    communityTagNodes = emptyList()
                    banner.show(NoticeBanner.Kind.ERROR, "Tags unavailable: ${result.error.toUserMessage()}")
                }
            }
            communityTagsLoaded = true
            rebuildTagLookup()
            updateTagTabs(prune = false)
            if (step == Step.DETAILS) rebuildWidgets()
        }
    }

    private fun validateDetailsAndContinue() {
        val problems = mutableListOf<String>()
        if (nameText.isBlank()) problems.add("name is required")
        if (descriptionText.isBlank()) problems.add("description is required")
        if (services.authManager.session?.playerUuid == null) problems.add("not signed in to schemat.io")
        // Mirror the popup's Done gating: invalid filter values / unset required
        // filters block leaving the details step.
        tagPicker.validationError()?.let { problems.add(it) }
        if (problems.isNotEmpty()) {
            banner.show(NoticeBanner.Kind.ERROR, problems.joinToString("; ").replaceFirstChar { it.uppercase() })
            return
        }
        goTo(Step.CONFIRM)
    }

    // ---- step 3: confirm + upload ----

    private fun initConfirmStep(buttonsTop: Int, bannerY: Int) {
        // The confirm content is a review card drawn in [renderConfirmCard];
        // only navigation widgets are added here (Upload = emerald primary CTA).
        addNavButtons(buttonsTop, back = Step.DETAILS, nextLabel = "Upload", primary = true) { startUpload() }
    }

    private fun startUpload() {
        if (uploadBusy.get() || exporting) return
        val source = selectedSource ?: return
        banner.clear()
        when (source.kind) {
            SourceKind.LOCAL_FILE ->
                performUpload(source) { Files.readAllBytes(Path.of(source.id)) }
            SourceKind.WORLDEDIT_CLIPBOARD -> {
                exporting = true
                Bridges.worldEdit.clipboardToBytes { bytes, error ->
                    exporting = false
                    if (bytes == null) {
                        banner.show(NoticeBanner.Kind.ERROR, error ?: "Failed to read the WorldEdit clipboard")
                    } else {
                        performUpload(source) { bytes }
                    }
                }
            }
            SourceKind.PLACEMENT, SourceKind.AREA_SELECTION -> {
                exporting = true
                Bridges.litematica.exportToBytes(source) { bytes, error ->
                    exporting = false
                    if (bytes == null) {
                        banner.show(NoticeBanner.Kind.ERROR, error ?: "Failed to export the schematic")
                    } else {
                        performUpload(source) { bytes }
                    }
                }
            }
        }
    }

    private fun performUpload(source: ExportSource, bytesProvider: suspend () -> ByteArray) {
        val authorId = services.authManager.session?.playerUuid
        if (authorId == null) {
            banner.show(NoticeBanner.Kind.ERROR, "Not signed in to schemat.io")
            return
        }
        val name = nameText.trim()
        val format = ExportSources.formatFor(source)
        val fileName = name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").ifBlank { "schematic" } + "." + format
        // The inline picker IS the source of truth - read it at upload time (the
        // old popup delivered these through onDone instead).
        val tagIds = tagPicker.selectedTagIds().toList()
        val tagFilters = tagPicker.filterValues()
        val coAuthorIds = coAuthors.entries()
            .map { it.uuid }
            .filter { it.lowercase().replace("-", "") != authorId.lowercase().replace("-", "") }
        val communityId = selectedCommunity?.id?.takeIf { it.isNotBlank() }

        services.call(
            busy = uploadBusy,
            block = {
                val bytes = bytesProvider()
                val request = UploadRequest(
                    name = name,
                    // Descriptions are rich-text server-side: submit the editor
                    // markup as sanitizer-allowlisted HTML.
                    description = descriptionText,
                    authorId = authorId,
                    schematicBytes = bytes,
                    schematicFileName = fileName,
                    previewImagePng = capturedPreview ?: placeholderPng(name),
                    format = format,
                    isPublic = isPublic,
                    tagIds = tagIds,
                    tagFilters = tagFilters,
                    coAuthorIds = coAuthorIds,
                    communityId = communityId,
                )
                uploadWithPermissionSelfHeal(request)
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    val detail = result.value
                    parent.invalidateListings()
                    // No success screen - drop back to the game and post a
                    // clickable chat notice with the web link instead.
                    minecraft?.setScreen(null)
                    ChatNotice.success(
                        "Uploaded \"${detail.name}\" successfully",
                        webLink(detail),
                        "Open in browser",
                    )
                }
                is ApiResult.Failure -> {
                    val error = result.error
                    if (error is ApiError.Validation) {
                        // Field errors belong to the details form - jump back there.
                        goTo(Step.DETAILS)
                        val message = if (error.fieldErrors.isEmpty()) error.message
                        else error.fieldErrors.entries.joinToString("; ") { (field, messages) ->
                            "$field: ${messages.firstOrNull() ?: "invalid"}"
                        }
                        banner.show(NoticeBanner.Kind.ERROR, message)
                    } else if (error is ApiError.Conflict) {
                        // Duplicate content - the backend already has this exact
                        // file. Friendly banner instead of a raw 409, plus a
                        // clickable chat link to the existing schematic if the
                        // backend told us where it lives.
                        val existingUrl = error.existingUrl
                        if (existingUrl != null) {
                            banner.show(
                                NoticeBanner.Kind.ERROR,
                                "This schematic already exists on schemat.io - link posted in chat",
                            )
                            ChatNotice.success(
                                "This schematic is already uploaded",
                                existingUrl,
                                "Open existing",
                            )
                        } else {
                            banner.show(NoticeBanner.Kind.ERROR, "This schematic already exists on schemat.io")
                        }
                    } else if (error is ApiError.Forbidden && isMissingUploadPermission(error)) {
                        banner.show(
                            NoticeBanner.Kind.ERROR,
                            "Upload was rejected. Your account may not have upload permission, or the server needs updating.",
                        )
                    } else {
                        banner.show(NoticeBanner.Kind.ERROR, error.toUserMessage())
                    }
                }
            }
        }
    }

    /**
     * `POST /schematics`, self-healing a stale cached token ONCE: the backend grants
     * `upload_schematic` to player tokens nowadays, but a cached pre-change JWT lacks
     * the claim and 403s forever (the core client only re-auths on 401). On a
     * permission-shaped 403, force a fresh handshake and retry the upload a single
     * time. Deliberately scoped to the upload flow - a blanket 403 retry in the core
     * client would re-auth on legitimate forbidden responses (e.g. community actions).
     */
    private suspend fun uploadWithPermissionSelfHeal(request: UploadRequest): ApiResult<SchematicDetail> {
        val first = services.cached.uploadSchematic(request)
        val error = (first as? ApiResult.Failure)?.error
        if (error !is ApiError.Forbidden || !isMissingUploadPermission(error)) return first
        LOGGER.info("Upload returned a permission 403 - forcing re-authentication and retrying once")
        if (!services.authManager.forceReauthenticate()) return first
        return services.cached.uploadSchematic(request)
    }

    /**
     * True when a 403 looks like a missing-permission rejection (EnsureValidJWT says
     * "Insufficient permissions. Required: upload_schematic"). Lenient on purpose:
     * any mention of a permission, or of the specific claim, counts.
     */
    private fun isMissingUploadPermission(error: ApiError.Forbidden): Boolean =
        error.message.contains("permission", ignoreCase = true) ||
            error.message.contains("upload_schematic", ignoreCase = true)

    /**
     * Placeholder preview (the API requires `preview_image`): 256x256 PNG, background
     * color derived from the name hash, with the name's initials drawn via AWT.
     */
    private fun placeholderPng(name: String): ByteArray {
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

    /**
     * Public web page for an uploaded schematic, used by the success chat notice.
     * Prefers the server-provided `web_url`; the fallback (older servers) is built
     * from the short_id - never the slug, which the web route 404s on.
     */
    private fun webLink(detail: SchematicDetail): String {
        detail.webUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val base = services.authManager.apiEndpoint.substringBefore("/api/").trimEnd('/')
        val key = detail.shortId ?: detail.id
        return "$base/schematics/$key"
    }

    // ---- rendering ----

    private fun stepHeading(): String = when (step) {
        Step.SOURCE -> "Step 1 of 3 - Choose what to upload"
        Step.DETAILS -> "Step 2 of 3 - Details"
        Step.CONFIRM -> "Step 3 of 3 - Confirm"
    }

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
        Theme.header(context, font, title.string, stepHeading(), PADDING, PADDING, width - PADDING * 2)

        when (step) {
            Step.SOURCE -> {
                if (sources.isEmpty()) {
                    Theme.emptyState(
                        context, font,
                        "No sources found - put .litematic/.schem files in ${ExportSources.localFilesDirectory()}",
                        0, 0, width, height,
                    )
                }
                nextButton?.active = selectedSource != null
            }
            Step.DETAILS -> {
                val sourceText = "Source: " + (selectedSource?.let { ExportSources.label(it) } ?: "none selected")
                Theme.label(
                    context, font,
                    font.plainSubstrByWidth(sourceText, columnWidth - 70 - GAP),
                    leftX, sourceLabelY,
                )
                Theme.sectionLabel(context, font, "Name", leftX, nameLabelY)
                Theme.sectionLabel(context, font, "Description", leftX, descriptionLabelY)
                Theme.sectionLabel(context, font, "Visibility", leftX, visibilityLabelY)
                Theme.sectionLabel(context, font, "Community", leftX, communityLabelY)
                Theme.sectionLabel(context, font, "Preview", leftX, previewLabelY)
                Theme.sectionLabel(context, font, "Tags", rightX, tagsLabelY)
                Theme.sectionLabel(context, font, "Co-authors", rightX, coAuthorsLabelY)
                descriptionEditor?.render(context)
                renderPreviewSlot(context)
                tagPicker.render(context)
                composeButton?.active = !composerLoading && selectedSource != null
            }
            Step.CONFIRM -> {
                renderConfirmCard(context)
                val busy = uploadBusy.get() || exporting
                // While uploading the CTA is replaced by a clear spinner.
                nextButton?.active = !busy
                nextButton?.visible = !busy
                if (busy) {
                    val buttonsTop = height - PADDING - BUTTON_HEIGHT
                    LoadingSpinner.render(
                        context, font,
                        width - PADDING - 40, buttonsTop + (BUTTON_HEIGHT - 8) / 2,
                        "Uploading",
                    )
                }
            }
        }

        banner.render(context, font)
    }

    /**
     * Step 3 review card: composed thumbnail on the left, an uppercase-label /
     * white-value summary (name, description, visibility, community, tags,
     * co-authors with head avatars) on the right.
     */
    private fun renderConfirmCard(context: GuiGraphics) {
        val buttonsTop = height - PADDING - BUTTON_HEIGHT
        val bannerY = buttonsTop - NoticeBanner.HEIGHT - 4
        val cardX = PADDING
        val cardY = contentTop
        val cardW = width - PADDING * 2
        val cardH = (bannerY - GAP - cardY).coerceAtLeast(60)
        Theme.card(context, cardX, cardY, cardW, cardH)

        val pad = Theme.LG
        val innerX = cardX + pad
        val innerY = cardY + pad
        val innerW = cardW - pad * 2
        val innerH = cardH - pad * 2
        val innerBottom = innerY + innerH

        // -- Left: thumbnail in a bordered 16:9 slot (matches the capture and
        // the website's aspect-video cards) --
        var slotW = (innerW * 2 / 5).coerceAtLeast(48)
        var slotH = slotW * 9 / 16
        val slotMaxH = (innerH - 12).coerceAtLeast(27)
        if (slotH > slotMaxH) {
            slotH = slotMaxH
            slotW = slotH * 16 / 9
        }
        Theme.stroke(context, innerX - 1, innerY - 1, slotW + 2, slotH + 2, Theme.BORDER)
        context.fill(innerX, innerY, innerX + slotW, innerY + slotH, Theme.SURFACE_ALT)
        val textureId = previewTextureId
        if (textureId != null && previewTextureWidth > 0 && previewTextureHeight > 0) {
            PreviewDraw.drawContain(
                context, textureId, previewTextureWidth, previewTextureHeight,
                innerX, innerY, slotW, slotH, background = Theme.SURFACE_ALT,
            )
        } else {
            Theme.emptyState(context, font, "No preview", innerX, innerY, slotW, slotH)
        }
        // Source · format caption under the slot.
        val sourceText = (selectedSource?.let { ExportSources.label(it) } ?: "?") +
            " · " + ExportSources.formatFor(selectedSource)
        if (innerY + slotH + 4 + 10 <= innerBottom) {
            Theme.muted(
                context, font, font.plainSubstrByWidth(sourceText, slotW),
                innerX, innerY + slotH + Theme.XS,
            )
        }

        // -- Right: labelled summary rows --
        val colX = innerX + slotW + Theme.XL
        val colW = (innerX + innerW - colX).coerceAtLeast(60)
        var y = innerY

        Theme.sectionLabel(context, font, "Name", colX, y)
        y += 10
        context.drawString(
            font,
            Component.literal(font.plainSubstrByWidth(nameText.trim(), colW)).withStyle(ChatFormatting.BOLD),
            colX, y, Theme.TEXT_PRIMARY, false,
        )
        y += 14

        Theme.sectionLabel(context, font, "Description", colX, y)
        y += 10
        // Styled preview of exactly what will be uploaded (html -> spans).
        val descriptionSpans = RichText.htmlToSpans(descriptionText)
        if (descriptionSpans.isEmpty()) {
            Theme.value(context, font, "-", colX, y, Theme.TEXT_SECONDARY)
            y += 10
        } else {
            y += RichTextRender.drawWrapped(
                context, font, descriptionSpans, colX, y, colW,
                lineHeight = 10, color = Theme.TEXT_SECONDARY, maxY = y + 20,
            )
        }
        y += Theme.XS

        // Visibility + community side by side.
        val half = colW / 2
        Theme.sectionLabel(context, font, "Visibility", colX, y)
        Theme.sectionLabel(context, font, "Community", colX + half, y)
        y += 10
        Theme.value(context, font, if (isPublic) "Public" else "Private", colX, y)
        Theme.value(
            context, font,
            font.plainSubstrByWidth(selectedCommunity?.name ?: "None", half - 6),
            colX + half, y,
        )
        y += 14

        Theme.sectionLabel(context, font, "Tags", colX, y)
        y += 10
        val confirmTagIds = tagPicker.selectedTagIds()
        if (confirmTagIds.isEmpty()) {
            Theme.value(context, font, "None", colX, y)
            y += 14
        } else {
            val chips = confirmTagIds.map { id ->
                val node = tagNodeById[id]
                TagChips.Chip(id, node?.name?.ifBlank { id } ?: id, node?.color)
            }
            val chipsH = 26.coerceAtMost((innerBottom - y).coerceAtLeast(13))
            val (placed, overflow) = TagChips.layout(chips, colX, y, colW, chipsH, font, withClose = false)
            TagChips.render(context, font, placed, overflow)
            y += chipsH + 4
        }

        Theme.sectionLabel(context, font, "Co-authors", colX, y)
        y += 10
        val authors = coAuthors.entries()
        if (authors.isEmpty()) {
            Theme.value(context, font, "None", colX, y)
        } else {
            val rowH = 14
            val headSize = 12
            for ((index, entry) in authors.withIndex()) {
                if (y + rowH > innerBottom) {
                    Theme.muted(context, font, "+${authors.size - index} more", colX, y.coerceAtMost(innerBottom - 10))
                    break
                }
                val headId = services.headAvatars.getHead(entry.uuid, entry.headUrl)
                if (headId != null) {
                    val (texW, texH) = services.headAvatars.getHeadSize(entry.uuid) ?: (64 to 64)
                    context.blit(
                        RenderPipelines.GUI_TEXTURED, headId,
                        colX, y, 0f, 0f, headSize, headSize, texW, texH, texW, texH,
                    )
                } else {
                    context.fill(colX, y, colX + headSize, y + headSize, Theme.BORDER)
                }
                Theme.value(
                    context, font,
                    font.plainSubstrByWidth(entry.name.ifBlank { entry.uuid }, colW - headSize - 4),
                    colX + headSize + 4, y + 2,
                )
                y += rowH
            }
        }
    }

    /** Thumbnail slot in the details step: captured image, or a "no preview yet" box. */
    private fun renderPreviewSlot(context: GuiGraphics) {
        val x = previewSlotArea[0]
        val y = previewSlotArea[1]
        val w = previewSlotArea[2]
        val h = previewSlotArea[3]
        if (w <= 0 || h <= 0) return
        Theme.stroke(context, x - 1, y - 1, w + 2, h + 2, Theme.BORDER)
        val textureId = previewTextureId
        if (textureId != null && previewTextureWidth > 0 && previewTextureHeight > 0) {
            PreviewDraw.drawContain(context, textureId, previewTextureWidth, previewTextureHeight, x, y, w, h)
        } else {
            context.fill(x, y, x + w, y + h, Theme.SURFACE_ALT)
            val message = when {
                composerLoading -> "Loading schematic..."
                capturedPreview != null -> "Preview captured"
                else -> "No preview yet"
            }
            val trimmed = font.plainSubstrByWidth(message, (w - Theme.MD).coerceAtLeast(8))
            Theme.hint(context, font, trimmed, x + (w - font.width(trimmed)) / 2, y + (h - 8) / 2)
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true
        // The inline picker's filter rows scroll outside any single widget's bounds.
        return step == Step.DETAILS && tagPicker.mouseScrolled(mouseX, mouseY, verticalAmount)
    }

    override fun onClose() {
        destroyPreviewTexture()
        minecraft!!.setScreen(parent)
    }

    override fun removed() {
        // Mirror close(): external screen replacement (disconnect, the
        // composer opening on top, another mod's setScreen) bypasses close(),
        // so tear the captured-preview texture down here too. init()
        // re-registers it from [capturedPreview] when the wizard is shown
        // again, so the composer round-trip keeps its thumbnail.
        destroyPreviewTexture()
        super.removed()
    }
}
