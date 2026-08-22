package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import imgui.type.ImString
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.UploadRequest
import io.schemat.connector.core.modapi.dto.CommunitySummary
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.ExportSource
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.panels.upload.*
import io.schemat.connector.fabric.client.ui.widgets.PlayerListPicker
import dev.harrison.panellib.widgets.ConfirmModal
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import io.schemat.connector.fabric.client.ui.widgets.ExportSources
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ImGui panel replacement for [io.schemat.connector.fabric.client.ui.UploadWizardScreen].
 *
 * Three-step upload wizard:
 * 1. **Source** — lists Litematica placements/selections, WorldEdit clipboard, and local
 *    `.litematic`/`.schem`/`.schematic` files. Auto-skipped (starts on Details) when
 *    Litematica has a current selection (same logic as the vanilla wizard).
 * 2. **Details** — name, description (WYSIWYG rich text via RichTextEditorWidget, saved as HTML),
 *    visibility toggle, community cycler, tag picker via TagSelectorPopup, collaborators via
 *    player-name lookup ([PlayerListPicker]).
 *    Validation gates Next: name required, description required, signed-in check.
 * 3. **Confirm** — review card + Upload button; full upload via the same
 *    [services.cached.uploadSchematic] path and [uploadWithPermissionSelfHeal] logic.
 *
 * Singleton object: all state is object-level and persists across close/reopen.
 * Call [open] to start fresh (resets transient state), or [PanelManager.toggle] for a
 * keybind toggle.
 *
 * The tag picker uses TagSelectorPopup in ASSIGN mode: it covers tag
 *   selection AND per-tag literal filter-VALUE entry with required-filter validation. The
 *   collected values flow into [UploadRequest.tagFilters].
 *
 * Step rendering, preview composition, and submission live as internal extension
 * functions in `io.schemat.connector.fabric.client.ui.panels.upload`.
 */
object UploadWizardPanel : Panel {

    override val id = "upload-wizard"

    internal val LOGGER = LoggerFactory.getLogger("schematioconnector-upload-wizard-panel")

    // ---- services ----
    internal val services: ClientServices get() = SchematioClientMod.instance.services

    // ---- wizard step ----
    internal enum class Step { SOURCE, DETAILS, CONFIRM }
    internal var step = Step.SOURCE

    // ---- step 1: source ----
    internal var sources: List<ExportSource> = emptyList()
    internal var selectedSource: ExportSource? = null

    // ---- step 2: details ----
    // ImStrings allocated once — never re-created; buffers survive close/reopen
    internal val nameBuf = ImString(256)
    /** Rich-text WYSIWYG description editor; submit via [RichTextEditorWidget.toHtml]. */
    internal val descEditor = RichTextEditorWidget("upload-desc")
    internal val coAuthorPicker = PlayerListPicker(maxEntries = 10)
    internal val visibilityBuf = ImBoolean(true)

    internal var isPublic = true

    internal var communities: List<CommunitySummary> = emptyList()
    internal var communitiesLoaded = false
    internal val communityIndexBuf = ImInt(0)  // 0 = None
    internal val selectedCommunity: CommunitySummary?
        get() = communities.getOrNull(communityIndexBuf.get() - 1)  // index 0 = None

    internal var selectedTagIds: Set<String> = emptySet()
    /** ASSIGN-mode literal tag-filter values (filterId -> value) for [UploadRequest.tagFilters]. */
    internal var selectedTagFilters: Map<Long, String> = emptyMap()
    internal var globalTagNodes: List<TagNode> = emptyList()

    /** Non-null = "complete draft" mode: the bytes are already on the backend (sub-project C). */
    internal var completingDraft: SchematicDetail? = null

    // ---- async guards ----
    internal val loadBusy = AtomicBoolean(false)
    internal val uploadBusy = AtomicBoolean(false)
    internal var exporting = false

    /** PNG captured by the preview composer; uploaded when set, else a placeholder. */
    internal var capturedPreviewPng: ByteArray? = null

    // ---- captured-preview thumbnail texture (built once per [capturedPreviewPng]) ----
    /** The exact [capturedPreviewPng] reference the current texture was built from (identity). */
    internal var previewTexBuiltFor: ByteArray? = null
    /** Resolved GL texture handle for [PREVIEW_TEX_ID], or null while unbuilt/failed. */
    internal var previewTexGlId: Int? = null
    /** Pixel size of the decoded preview, for aspect-correct drawing. */
    internal var previewTexW = 0
    internal var previewTexH = 0
    /** Preview box width (px) in the wizard; height derives from the 16:9 capture aspect. */
    internal const val PREVIEW_BOX_W = 320f

    /** Label-column width (px) for the aligned confirm-step summary rows. */
    internal const val SUMMARY_LABEL_COL = 120f

    /** Row height (px) for a source-picker entry. */
    internal const val SOURCE_ROW_H = 24f

    // ---- status / error ----
    internal var statusMessage: String? = null
    internal var statusKind: Widgets.StatusKind = Widgets.StatusKind.INFO

    // ---- panel state ----
    /** True on the first frame after [open] to reset transient UI state. */
    private var freshOpen = false

    /**
     * Open the wizard, resetting all transient state (source selection, form fields,
     * status). Call from the keybind handler for a clean start.
     */
    fun open() {
        reset()
        PanelManager.open(this)
    }

    /**
     * Open the wizard pre-seeded with [preselect] as the source (used by MixinBridge when
     * a specific placement or file is already known). Jumps straight to the Details step.
     * If [preselect] is null, behaves identically to [open].
     */
    fun open(preselect: ExportSource?) {
        reset()
        if (preselect != null) {
            selectedSource = preselect
            step = Step.DETAILS
        }
        PanelManager.open(this)
    }

    /**
     * Open in "complete draft" mode (IPC sub-project C): the schematic bytes already
     * live on the backend as [detail]; the Source step is skipped and Save publishes
     * the draft via the USER's normal update path (name-carrying PUT clears the
     * expiry server-side). The preview composer is unavailable in this mode — the
     * update API cannot carry an image.
     */
    fun openCompleteDraft(detail: SchematicDetail) {
        reset()
        completingDraft = detail
        selectedSource = null           // reset() may have auto-picked a Litematica source
        nameBuf.set(detail.name)
        isPublic = detail.isPublic
        visibilityBuf.set(detail.isPublic)
        step = Step.DETAILS
        PanelManager.open(this)
    }

    private fun reset() {
        step = Step.SOURCE
        sources = emptyList()
        selectedSource = null
        completingDraft = null
        nameBuf.set("")
        descEditor.clear()
        coAuthorPicker.clear()
        isPublic = true
        visibilityBuf.set(true)
        communities = emptyList()
        communitiesLoaded = false
        communityIndexBuf.set(0)
        selectedTagIds = emptySet()
        selectedTagFilters = emptyMap()
        globalTagNodes = emptyList()
        statusMessage = null
        exporting = false
        capturedPreviewPng = null
        releasePreviewTexture()
        uploadBusy.set(false)
        loadBusy.set(false)
        freshOpen = true

        // Auto-advance: try Litematica's current selection
        val litSel = runCatching { Bridges.litematica.currentSelectionSource() }
            .onFailure { LOGGER.warn("Could not query Litematica current selection", it) }
            .getOrNull()
        if (litSel != null) {
            selectedSource = litSel
            step = Step.DETAILS
        }
    }

    // ---- Panel.render ----

    override fun render() {
        if (freshOpen) {
            // Collect sources on first frame (blocking on render thread like vanilla initSourceStep)
            if (step == Step.SOURCE) {
                sources = ExportSources.collect()
            }
            freshOpen = false
        }

        ImGui.setNextWindowSize(700f, 540f, imgui.flag.ImGuiCond.FirstUseEver)
        val open = ImBoolean(true)
        val title = when (step) {
            Step.SOURCE  -> "Upload Schematic — Step 1/3: Choose Source###upload-wizard"
            Step.DETAILS -> "Upload Schematic — Step 2/3: Details###upload-wizard"
            Step.CONFIRM -> "Upload Schematic — Step 3/3: Confirm###upload-wizard"
        }
        if (!ImGui.begin(title, open, ImGuiWindowFlags.None)) {
            ImGui.end()
            return
        }
        ImGuiTheme.windowTitleAccent()
        if (!open.get()) {
            ImGui.end()
            requestClose()
            return
        }

        when (step) {
            Step.SOURCE  -> renderSourceStep()
            Step.DETAILS -> renderDetailsStep()
            Step.CONFIRM -> renderConfirmStep()
        }

        ImGui.end()
    }

    // ---- upload logic (mirrors UploadWizardScreen exactly) ----

    /**
     * `POST /schematics` with a one-time permission-403 self-heal (mirrors vanilla).
     */
    internal suspend fun uploadWithPermissionSelfHeal(request: UploadRequest): ApiResult<SchematicDetail> {
        val first = services.cached.uploadSchematic(request)
        val error = (first as? ApiResult.Failure)?.error
        if (error !is ApiError.Forbidden || !isMissingUploadPermission(error)) return first
        LOGGER.info("Upload returned a permission 403 - forcing re-authentication and retrying once")
        if (!services.authManager.forceReauthenticate()) return first
        return services.cached.uploadSchematic(request)
    }

    // ---- helpers ----

    private fun requestClose() {
        val hasWork = nameBuf.get().isNotBlank() || !descEditor.isEmpty() || selectedSource != null
        if (hasWork && step != Step.SOURCE) {
            ConfirmModal.show(
                title = "Discard upload?",
                message = "You have unsaved changes. Close the upload wizard?",
                confirmLabel = "Discard",
                danger = true,
            ) {
                PanelManager.close(id)
            }
        } else {
            PanelManager.close(id)
        }
    }

    internal fun renderStatus() {
        val msg = statusMessage
        if (msg != null) {
            ImGui.spacing()
            Widgets.statusText(msg, statusKind)
        }
    }

    /**
     * Bottom navigation row: Cancel (left), optional Back, Next/Upload (right).
     * [nextAccent] renders the next button with the accent color (used for the Upload CTA).
     */
    internal fun renderNavButtons(
        backStep: Step?,
        nextLabel: String,
        nextEnabled: Boolean,
        nextAccent: Boolean = false,
        onNext: () -> Unit,
    ) {
        ImGui.spacing()
        ImGui.separator()
        ImGui.spacing()

        if (Widgets.button("Cancel")) {
            requestClose()
        }

        if (backStep != null) {
            ImGui.sameLine()
            if (Widgets.button("< Back")) {
                step = backStep
                statusMessage = null
            }
        }

        // Right-align the Next/Upload button
        val nextW = 100f
        ImGui.sameLine(ImGui.getContentRegionAvailX() - nextW + ImGui.getCursorPosX())
        if (!nextEnabled) {
            ImGui.beginDisabled()
        }
        val clicked = Widgets.button("$nextLabel###nav-next", accent = nextAccent)
        if (!nextEnabled) {
            ImGui.endDisabled()
        }
        if (clicked && nextEnabled) {
            onNext()
        }
    }
}
