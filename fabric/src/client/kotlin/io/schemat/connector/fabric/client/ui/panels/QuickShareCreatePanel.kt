package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImString
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.QuickShareRequest
import io.schemat.connector.core.modapi.dto.QuickShareInfo
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.ExportSource
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.ui.widgets.ExportSources
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ImGui replacement for [io.schemat.connector.fabric.client.ui.QuickShareCreateScreen].
 *
 * Supports two modes:
 *  - **Remote source** (non-null [source]): share an already-uploaded schematic by
 *    downloading its bytes at create time. Skips the source-picker step entirely.
 *  - **Local source** (null [source]): show the source-picker (local files / WorldEdit /
 *    Litematica placements), then the details form. Matches the vanilla 2-step flow.
 *
 * Call [show] to set mode and open. State is object-level; [show] resets transient
 * fields so a fresh open never leaks previous form content.
 *
 * Entry points wired in Task 19:
 *  - [SchematicDetailPanel] "Quick share" button → `show(RemoteSchematicSource(...))`
 *  - [SharesPanel] Quick Shares "New Share" → `show(null)`
 *  - [MySchematicsPanel] "Quick link" → `show(null)`
 *  - Keybinds `quickShare` → `show(null)`
 */
object QuickShareCreatePanel : Panel {

    override val id = "quickshare-create"

    /** An already-uploaded schematic used as the share source (bytes fetched via download). */
    data class RemoteSchematicSource(val schematicId: String, val name: String, val format: String)

    private enum class Step { SOURCE, DETAILS, SUCCESS }

    // ---- services ----
    private val services: ClientServices get() = SchematioClientMod.instance.services

    // ---- source mode ----
    /** Non-null when sharing an existing remote schematic; null for local-source flow. */
    private var source: RemoteSchematicSource? = null

    // ---- step 1: source picker (only used when source == null) ----
    private var sources: List<ExportSource> = emptyList()
    private var selectedSource: ExportSource? = null

    // ---- step 2: details — ImStrings allocated once, buffers survive close/reopen ----
    private val nameBuf     = ImString(256)
    private val passwordBuf = ImString(128)
    private val maxUsesBuf  = ImString(8)

    // ---- step 3: success — link buffer hoisted so it is not allocated every frame ----
    private val linkBuf = ImString(512)

    /** Index into EXPIRY_OPTIONS. */
    private var expiryIndex = 2   // default: 24 hours

    // ---- step 3: success ----
    private var successShare: QuickShareInfo? = null

    // ---- async state ----
    private val createBusy  = AtomicBoolean(false)
    private var exporting   = false

    // ---- status / error ----
    private var statusText: String? = null
    private var statusKind: Widgets.StatusKind = Widgets.StatusKind.DANGER

    // ---- panel state ----
    private var step = Step.SOURCE
    private var freshOpen = false

    // ---- expiry options ----
    private val EXPIRY_OPTIONS = listOf(
        "30 minutes" to 1_800,
        "1 hour"     to 3_600,
        "24 hours"   to 86_400,
        "7 days"     to 604_800,
        "30 days"    to 2_592_000,
    )

    /**
     * Open the panel, optionally pre-seeded with a remote [source].
     * - If [source] is non-null: jumps straight to the Details step.
     * - If [source] is null: starts at the Source picker step.
     */
    fun show(source: RemoteSchematicSource?) {
        this.source = source
        reset(skipToDetails = source != null)
        PanelManager.open(this)
    }

    private fun reset(skipToDetails: Boolean) {
        step = if (skipToDetails) Step.DETAILS else Step.SOURCE
        sources = emptyList()
        selectedSource = null
        // Pre-fill name from remote source; clear otherwise
        nameBuf.set(source?.name ?: "")
        passwordBuf.set("")
        maxUsesBuf.set("")
        expiryIndex = 2
        successShare = null
        linkBuf.set("")
        statusText = null
        exporting = false
        freshOpen = true
    }

    // ---- render ----

    override fun render() {
        if (freshOpen && step == Step.SOURCE) {
            sources = ExportSources.collect()
            if (selectedSource != null && sources.none { it.id == selectedSource!!.id }) {
                selectedSource = null
            }
            freshOpen = false
        } else if (freshOpen) {
            freshOpen = false
        }

        val title = when (step) {
            Step.SOURCE  -> "New Quick Share — Step 1/2: Choose Source###quickshare-create"
            Step.DETAILS -> if (source != null) "New Quick Share — Share Settings###quickshare-create"
                            else "New Quick Share — Step 2/2: Share Settings###quickshare-create"
            Step.SUCCESS -> "Quick Share Created###quickshare-create"
        }

        ImGui.setNextWindowSize(520f, 420f, imgui.flag.ImGuiCond.FirstUseEver)
        val open = ImBoolean(true)
        if (!ImGui.begin(title, open, ImGuiWindowFlags.None)) {
            ImGui.end()
            return
        }
        if (!open.get()) {
            PanelManager.close(id)
            ImGui.end()
            return
        }

        try {
            when (step) {
                Step.SOURCE  -> renderSourceStep()
                Step.DETAILS -> renderDetailsStep()
                Step.SUCCESS -> renderSuccessStep()
            }
        } finally {
            ImGui.end()
        }
    }

    // ---- step 1: source picker ----

    private fun renderSourceStep() {
        ImGui.textColored(
            ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y,
            ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w,
            "Choose what to share:"
        )
        ImGui.separator()

        if (sources.isEmpty()) {
            ImGui.textDisabled("No local sources found — put .litematic/.schem files in your schematics folder,")
            ImGui.textDisabled("or have a WorldEdit clipboard or Litematica placement active.")
        } else {
            for (src in sources) {
                val selected = selectedSource?.id == src.id
                if (ImGui.selectable(ExportSources.label(src) + "##src_${src.id}", selected)) {
                    selectedSource = src
                    if (nameBuf.get().isBlank()) {
                        nameBuf.set(defaultNameFor(src))
                    }
                }
            }
        }

        ImGui.separator()

        // Status
        statusText?.let { Widgets.statusText(it, statusKind) }

        ImGui.spacing()

        // Buttons
        if (Widgets.button("Cancel")) {
            PanelManager.close(id)
        }
        ImGui.sameLine()
        val canNext = selectedSource != null
        if (!canNext) ImGui.beginDisabled()
        if (Widgets.button("Next >", accent = true)) {
            step = Step.DETAILS
            statusText = null
        }
        if (!canNext) ImGui.endDisabled()
    }

    private fun defaultNameFor(src: ExportSource): String =
        src.label.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".litematic").removeSuffix(".schem").removeSuffix(".schematic")

    // ---- step 2: details ----

    private fun renderDetailsStep() {
        // Source description header
        val desc = source?.let { "Sharing: \"${it.name}\" (from schemat.io, ${it.format})" }
            ?: selectedSource?.let { "Sharing: ${ExportSources.label(it)}" }
            ?: "Sharing: (unknown)"
        ImGui.textColored(
            ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y,
            ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w,
            desc
        )
        ImGui.separator()
        ImGui.spacing()

        // Name
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "Name"
        )
        ImGui.setNextItemWidth(340f)
        Widgets.textField("##qs_name", nameBuf, "Share name")
        ImGui.spacing()

        // Expiry
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "Expires in"
        )
        if (Widgets.button("${EXPIRY_OPTIONS[expiryIndex].first}  v##qs_expiry")) {
            expiryIndex = (expiryIndex + 1) % EXPIRY_OPTIONS.size
        }
        ImGui.spacing()

        // Password (optional)
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "Password (optional)"
        )
        ImGui.setNextItemWidth(340f)
        Widgets.textField("##qs_password", passwordBuf, "No password")
        ImGui.spacing()

        // Max uses (optional)
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "Max uses (optional)"
        )
        ImGui.setNextItemWidth(100f)
        Widgets.textField("##qs_maxuses", maxUsesBuf, "∞")
        ImGui.spacing()

        ImGui.separator()

        // Status / error
        statusText?.let {
            Widgets.statusText(it, statusKind)
            ImGui.spacing()
        }

        // Busy indicator
        val busy = createBusy.get() || exporting
        if (busy) {
            ImGui.textDisabled("Creating share...")
            ImGui.spacing()
        }

        // Buttons
        if (Widgets.button("Cancel")) {
            PanelManager.close(id)
        }
        // "< Back" only for local-source flow (source == null means we have a source step)
        if (source == null) {
            ImGui.sameLine()
            if (Widgets.button("< Back")) {
                step = Step.SOURCE
                statusText = null
            }
        }
        ImGui.sameLine()
        if (busy) ImGui.beginDisabled()
        if (Widgets.button("Create", accent = true)) {
            validateAndCreate()
        }
        if (busy) ImGui.endDisabled()
    }

    // ---- step 3: success ----

    private fun renderSuccessStep() {
        val share = successShare ?: run {
            PanelManager.close(id)
            return
        }

        ImGui.textColored(
            ImGuiColors.SUCCESS.x, ImGuiColors.SUCCESS.y,
            ImGuiColors.SUCCESS.z, ImGuiColors.SUCCESS.w,
            "Quick share created!"
        )
        ImGui.separator()
        ImGui.spacing()

        val url = shareUrl(share)
        ImGui.textWrapped("Share link:")
        ImGui.spacing()
        ImGui.setNextItemWidth(-1f)
        ImGui.inputText("##qs_link_display", linkBuf, imgui.flag.ImGuiInputTextFlags.ReadOnly)
        ImGui.spacing()

        if (Widgets.button("Copy link", accent = true)) {
            Minecraft.getInstance().keyboardHandler.setClipboard(url)
        }

        share.name?.let { name ->
            ImGui.sameLine()
            ImGui.textColored(
                ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
                ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
                "\"$name\""
            )
        }

        ImGui.spacing()
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "Access code: ${share.accessCode}"
        )
        ImGui.spacing()
        ImGui.separator()

        if (Widgets.button("Done")) {
            PanelManager.close(id)
        }
    }

    // ---- create logic ----

    private fun validateAndCreate() {
        if (createBusy.get() || exporting) return

        val name = nameBuf.get().trim()
        val password = passwordBuf.get().trim().takeIf { it.isNotEmpty() }
        val maxUsesRaw = maxUsesBuf.get().trim()

        val problems = mutableListOf<String>()
        if (name.isBlank()) problems.add("Name is required")
        if (password != null && password.length < 4) problems.add("Password must be at least 4 characters")
        val maxUses: Int? = if (maxUsesRaw.isNotEmpty()) {
            val parsed = maxUsesRaw.toIntOrNull()
            if (parsed == null || parsed !in 1..10_000) {
                problems.add("Max uses must be a number between 1 and 10000")
                null
            } else parsed
        } else null

        if (problems.isNotEmpty()) {
            statusText = problems.joinToString("; ")
            statusKind = Widgets.StatusKind.DANGER
            return
        }
        statusText = null

        val remoteSource = source
        if (remoteSource != null) {
            // Remote: download bytes then create
            createShare(remoteSource.format, name, password, maxUses) {
                services.api.download(remoteSource.schematicId, remoteSource.format)
            }
            return
        }

        val src = selectedSource ?: run {
            statusText = "No source selected"
            statusKind = Widgets.StatusKind.DANGER
            return
        }
        val format = ExportSources.formatFor(src)
        when (src.kind) {
            SourceKind.LOCAL_FILE ->
                createShare(format, name, password, maxUses) {
                    ApiResult.Success(Files.readAllBytes(Path.of(src.id)))
                }
            SourceKind.WORLDEDIT_CLIPBOARD -> {
                exporting = true
                Bridges.worldEdit.clipboardToBytes { bytes, error ->
                    exporting = false
                    if (bytes == null) {
                        statusText = error ?: "Failed to read the WorldEdit clipboard"
                        statusKind = Widgets.StatusKind.DANGER
                    } else {
                        createShare(format, name, password, maxUses) { ApiResult.Success(bytes) }
                    }
                }
            }
            SourceKind.PLACEMENT, SourceKind.AREA_SELECTION -> {
                exporting = true
                Bridges.litematica.exportToBytes(src) { bytes, error ->
                    exporting = false
                    if (bytes == null) {
                        statusText = error ?: "Failed to export the schematic"
                        statusKind = Widgets.StatusKind.DANGER
                    } else {
                        createShare(format, name, password, maxUses) { ApiResult.Success(bytes) }
                    }
                }
            }
        }
    }

    private fun createShare(
        format: String,
        name: String,
        password: String?,
        maxUses: Int?,
        bytesProvider: suspend () -> ApiResult<ByteArray>,
    ) {
        val expirySeconds = EXPIRY_OPTIONS[expiryIndex].second
        services.call(
            busy = createBusy,
            block = {
                when (val bytes = bytesProvider()) {
                    is ApiResult.Failure -> bytes
                    is ApiResult.Success -> services.cached.createQuickShare(
                        QuickShareRequest(
                            schematicBytes = bytes.value,
                            name = name,
                            format = format,
                            expiresInSeconds = expirySeconds,
                            password = password,
                            maxUses = maxUses,
                        )
                    )
                }
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    successShare = result.value
                    linkBuf.set(shareUrl(result.value))
                    step = Step.SUCCESS
                    statusText = null
                    // Invalidate the Quick Shares list so it refreshes when SharesPanel is visible
                    SharesPanel.invalidate()
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    /** Share link: the API's webUrl, or host/share/<code>. */
    private fun shareUrl(share: QuickShareInfo): String {
        share.webUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val base = services.authManager.apiEndpoint.substringBefore("/api/").trimEnd('/')
        return "$base/share/${share.accessCode}"
    }
}
