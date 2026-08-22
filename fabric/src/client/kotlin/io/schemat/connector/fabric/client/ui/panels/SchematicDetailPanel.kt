package io.schemat.connector.fabric.client.ui.panels

import com.mojang.blaze3d.opengl.GlTexture
import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.ipc.ServerIpc
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.Icons
import dev.harrison.panellib.widgets.ConfirmModal
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ImGui panel for full schematic detail: metadata, preview image, tags and all
 * actions (download / open-in-game / delete / copy / quick share).
 *
 * Singleton panel — identity is fixed to "schematic-detail" so [PanelManager] dedup
 * keeps the existing instance when a row is re-clicked. Use [show] to set the target
 * and open; clicking a different row replaces [summary]/[detail] in place.
 *
 * PreviewDraw adapter: the [PreviewImageManager] already registers textures as
 * [Identifier]s via [Minecraft.textureManager]. To draw them in ImGui we need the
 * raw OpenGL texture handle. We resolve this by calling
 * [net.minecraft.client.renderer.texture.TextureManager.getTexture] to obtain the
 * [net.minecraft.client.renderer.texture.AbstractTexture], then access its underlying
 * [com.mojang.blaze3d.textures.GpuTexture] via [AbstractTexture.getTexture] and cast
 * to [GlTexture] (the concrete OpenGL implementation) to call [GlTexture.glId].
 * UV coordinates for contain (letterbox) are 0,0 → 1,1 (full image) since ImGui
 * [ImGui.image] maps UVs directly to [0..1].
 *
 * MANUAL VERIFICATION NEEDED: the [GlTexture.glId] path will produce a blank quad if
 * the engine is in a non-OpenGL backend. Confirm in-game that the preview renders
 * (a non-zero white quad means the GL id wasn't resolved; a black quad means the
 * texture hasn't loaded yet).
 */
object SchematicDetailPanel : Panel {

    override val id = "schematic-detail"

    private const val SUBFOLDER = "schemat.io"
    private const val OFFLINE_TOOLTIP = "Not available while offline"

    // ---- services ----
    private val services: ClientServices get() = SchematioClientMod.instance.services

    // ---- current target ----
    private var summary: SchematicSummary? = null
    private var detail: SchematicDetail? = null

    // ---- async state ----
    private var fetchError: String? = null
    private val fetchBusy = AtomicBoolean(false)
    private val actionBusy = AtomicBoolean(false)

    // ---- status messages ----
    private var statusText: String? = null
    private var statusKind: Widgets.StatusKind = Widgets.StatusKind.INFO

    /** requestId of an in-flight server clipboard load; null when idle. */
    private var pendingServerLoad: Int? = null

    /**
     * Set the target schematic and open the panel. If the panel is already open for
     * a different schematic, the target is replaced in place (same singleton, same
     * PanelManager entry).
     */
    fun show(schematic: SchematicSummary) {
        val replacing = summary?.id != schematic.id
        summary = schematic
        if (replacing) {
            detail = null
            fetchError = null
            fetchBusy.set(false)
            actionBusy.set(false)
            statusText = null
            pendingServerLoad = null
        }
        io.schemat.connector.fabric.client.ui.framework.PanelManager.open(SchematicDetailPanel)
        loadDetailIfNeeded()
    }

    private fun loadDetailIfNeeded() {
        val s = summary ?: return
        if (detail != null || fetchBusy.get()) return
        val loadId = s.id  // capture before async call to guard against stale responses
        services.call(
            busy = fetchBusy,
            block = { services.cached.schematic(s.id) },
        ) { result ->
            // Discard stale responses that arrived after the user switched to a different schematic
            if (summary?.id != loadId) return@call
            when (result) {
                is ApiResult.Success -> {
                    detail = result.value
                    fetchError = null
                }
                is ApiResult.Failure -> {
                    fetchError = result.error.toUserMessage()
                }
            }
        }
    }

    /**
     * Force-refreshes the loaded detail for [schematicId] if it is the currently displayed
     * schematic. Called by [SchematicEditPanel] after a successful save so the metadata
     * shown in this panel reflects the update without the user needing to close and reopen.
     */
    fun refreshDetail(schematicId: String) {
        if (summary?.id != schematicId) return
        detail = null
        fetchError = null
        fetchBusy.set(false)
        loadDetailIfNeeded()
    }

    // ---- Panel.render ----

    override fun render() {
        val s = summary ?: return
        val d = detail

        ImGui.setNextWindowSize(600f, 500f, imgui.flag.ImGuiCond.FirstUseEver)
        val open = imgui.type.ImBoolean(true)
        if (!ImGui.begin("${d?.name ?: s.name}###schematic-detail", open, ImGuiWindowFlags.None)) {
            ImGui.end()
            return
        }
        ImGuiTheme.windowTitleAccent()
        if (!open.get()) {
            ImGui.end()
            io.schemat.connector.fabric.client.ui.framework.PanelManager.close(id)
            return
        }

        renderPreviewImage(d?.previewImageUrl ?: s.previewImageUrl, d?.id ?: s.id)
        ImGui.separator()
        renderMetadata(d ?: summaryAsDetail(s))
        ImGui.separator()
        renderAuthors(d, s)
        renderTags(d, s)
        renderDescription(d)
        ImGui.separator()
        renderActions(d, s)

        ImGui.end()
    }

    // ---- preview image ----

    /**
     * Draw the preview using the ImGui image adapter.
     *
     * [PreviewImageManager.getEntry] returns an [Entry] with an [Identifier] registered
     * in [Minecraft.textureManager]. We resolve the OpenGL texture handle by:
     * 1. [TextureManager.getTexture] → [AbstractTexture]
     * 2. [AbstractTexture.getTexture] → [GpuTexture] (abstract)
     * 3. Cast to [GlTexture] (concrete GL impl) → [GlTexture.glId] → Int
     * 4. [ImGui.image] with uv0=(0,0) uv1=(1,1) for full-image contain.
     *
     * UV math: ImGui.image already maps (0,0)→(1,1) to the full texture, so a
     * contain/letterbox fit is approximated by sizing the ImGui region with the correct
     * aspect ratio (we fix width and compute height from the texture aspect).
     */
    private fun renderPreviewImage(previewUrl: String?, schematicId: String) {
        val entry = services.previewImages.getEntry(schematicId, previewUrl)
        if (entry != null) {
            val glId = resolveGlId(entry.id)
            if (glId != null && glId != 0) {
                val availWidth = ImGui.getContentRegionAvail().x.coerceAtMost(320f)
                val aspect = entry.width.toFloat() / entry.height.toFloat()
                val drawH = (availWidth / aspect).coerceAtLeast(1f)
                // contain: draw at computed aspect, centered in a fixed-height region
                val regionH = drawH.coerceAtMost(200f)
                val drawW = (regionH * aspect).coerceAtMost(availWidth)
                val offsetX = ((availWidth - drawW) / 2f).coerceAtLeast(0f)
                if (offsetX > 0f) ImGui.setCursorPosX(ImGui.getCursorPosX() + offsetX)
                ImGui.image(glId.toLong(), drawW, regionH, 0f, 0f, 1f, 1f)
            } else {
                Widgets.statusText("Preview loading...", Widgets.StatusKind.INFO)
            }
        } else {
            val msg = when {
                services.previewImages.isLoading(schematicId) -> "Loading preview..."
                previewUrl.isNullOrBlank() -> "No preview"
                else -> "Preview unavailable"
            }
            ImGui.textDisabled(msg)
        }
    }

    /**
     * Resolve the OpenGL texture handle from a registered [Identifier].
     *
     * Returns null if the texture is not registered or the backend is not OpenGL.
     * MANUAL CHECK REQUIRED in-game: a blank/white square means the GL id wasn't
     * resolved correctly.
     */
    private fun resolveGlId(id: net.minecraft.resources.Identifier): Int? {
        return try {
            val mc = Minecraft.getInstance()
            val abstractTexture = mc.textureManager.getTexture(id)
            val gpuTexture = abstractTexture.getTexture()
            if (gpuTexture is GlTexture) gpuTexture.glId() else null
        } catch (_: Exception) {
            null
        }
    }

    // ---- metadata ----

    private fun renderMetadata(d: SchematicDetail?) {
        if (d == null) {
            if (fetchError != null) {
                Widgets.statusText(fetchError!!, Widgets.StatusKind.DANGER)
                ImGui.sameLine()
                if (Widgets.button("Retry")) {
                    fetchError = null
                    loadDetailIfNeeded()
                }
            } else {
                ImGui.textDisabled("Loading detail...")
            }
            return
        }
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w)
        d.format?.let { ImGui.text("Format: $it") }
        ImGui.text("Access: ${if (d.isPublic) "Public" else "Private"}")
        d.createdAt?.take(10)?.let { ImGui.text("Created: $it") }
        d.updatedAt?.take(10)?.let { ImGui.text("Updated: $it") }
        d.webUrl?.let { url ->
            ImGui.text("URL: $url")
            if (ImGui.isItemClicked()) {
                try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Exception) {}
            }
        }
        ImGui.popStyleColor()
    }

    private fun summaryAsDetail(s: SchematicSummary): SchematicDetail = SchematicDetail(
        id = s.id,
        shortId = s.shortId,
        slug = s.slug,
        name = s.name,
        description = s.description,
        format = s.format,
        isPublic = s.isPublic,
        createdAt = s.createdAt,
        updatedAt = s.updatedAt,
        authors = s.authors,
        tags = s.tags,
        previewImageUrl = s.previewImageUrl,
        previewVideoUrl = s.previewVideoUrl,
        downloadLink = s.downloadLink,
        webUrl = s.webUrl,
    )

    // ---- authors ----

    private fun renderAuthors(d: SchematicDetail?, s: SchematicSummary) {
        val authors = d?.authors ?: s.authors
        if (authors.isEmpty()) return
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y, ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w)
        ImGui.text("Authors: ${authors.joinToString(", ") { it.lastSeenName.ifBlank { it.uuid } }}")
        ImGui.popStyleColor()
    }

    // ---- tags ----

    private fun renderTags(d: SchematicDetail?, s: SchematicSummary) {
        val tags = d?.tags ?: s.tags
        if (tags.isEmpty()) return
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w)
        ImGui.text("Tags: ${tags.joinToString(", ") { it.name }}")
        ImGui.popStyleColor()
    }

    // ---- description ----

    private fun renderDescription(d: SchematicDetail?) {
        val desc = d?.description ?: return
        if (desc.isBlank()) return
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y, ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w)
        ImGui.textWrapped(desc)
        ImGui.popStyleColor()
    }

    // ---- authorship check ----

    private val isAuthor: Boolean
        get() {
            val d = detail ?: return false
            val selfUuid = services.authManager.session?.playerUuid?.normalizedUuid() ?: return false
            return d.authors.any { it.uuid.normalizedUuid() == selfUuid }
        }

    private fun String.normalizedUuid(): String = lowercase().replace("-", "")

    // ---- actions ----

    private fun renderActions(d: SchematicDetail?, s: SchematicSummary) {
        val loaded = d != null
        val offline = services.isOffline()
        val actionsDisabled = actionBusy.get() || !loaded || offline

        // Status / error feedback
        statusText?.let { txt ->
            Widgets.statusText(txt, statusKind)
        }

        // Row 1: download / export — guard inside each handler with d?.let; buttons are
        // already behind actionsDisabled (which includes !loaded) so d is non-null in practice.
        if (Bridges.litematica.isAvailable) {
            if (actionsDisabled) ImGui.beginDisabled()
            if (Widgets.button("Load into Litematica")) { d?.let { loadIntoLitematica(it) } }
            if (actionsDisabled) ImGui.endDisabled()
            ImGui.sameLine()
        }

        if (actionsDisabled) ImGui.beginDisabled()
        if (Widgets.button("Save to disk")) { d?.let { saveToDisk(it) } }
        if (actionsDisabled) ImGui.endDisabled()
        ImGui.sameLine()

        if (Bridges.worldEdit.isAvailable) {
            if (actionsDisabled) ImGui.beginDisabled()
            if (Widgets.button("To WorldEdit clipboard")) { d?.let { toWorldEditClipboard(it) } }
            if (actionsDisabled) ImGui.endDisabled()
            ImGui.sameLine()
        }

        // Server-side WorldEdit clipboard, reference-pull (sub-project B): shown ONLY
        // when the session is VERIFIED and the server advertised LOAD_CLIPBOARD.
        if (ServerIpc.canLoadOnServer()) {
            val serverDisabled = actionsDisabled || pendingServerLoad != null
            if (serverDisabled) ImGui.beginDisabled()
            if (Widgets.button("${Icons.DOWNLOAD}  Load on server")) { d?.let { loadOnServer(it) } }
            if (serverDisabled) ImGui.endDisabled()
            ImGui.sameLine()
        }

        // Quick share — non-destructive, independent of actionBusy
        val qsDisabled = !loaded || offline
        if (qsDisabled) ImGui.beginDisabled()
        if (Widgets.button("Quick share")) { d?.let { openQuickShare(it) } }
        if (qsDisabled) ImGui.endDisabled()

        // Row 2: author-only manage actions
        if (isAuthor) {
            // Edit
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button,        ImGuiColors.ACCENT.x,      ImGuiColors.ACCENT.y,      ImGuiColors.ACCENT.z,      ImGuiColors.ACCENT.w)
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, ImGuiColors.ACCENT_HOVER.x, ImGuiColors.ACCENT_HOVER.y, ImGuiColors.ACCENT_HOVER.z, ImGuiColors.ACCENT_HOVER.w)
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive,  ImGuiColors.ACCENT_DIM.x,  ImGuiColors.ACCENT_DIM.y,  ImGuiColors.ACCENT_DIM.z,  ImGuiColors.ACCENT_DIM.w)
            val editDisabled = actionsDisabled || offline
            if (editDisabled) ImGui.beginDisabled()
            if (ImGui.button("Edit")) { d?.let { SchematicEditPanel.show(it) } }
            if (editDisabled) ImGui.endDisabled()
            ImGui.popStyleColor(3)
            ImGui.sameLine()

            // Delete — destructive
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button,        ImGuiColors.DANGER.x, ImGuiColors.DANGER.y, ImGuiColors.DANGER.z, ImGuiColors.DANGER.w)
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, ImGuiColors.DANGER.x, ImGuiColors.DANGER.y, ImGuiColors.DANGER.z, 0.8f)
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive,  ImGuiColors.DANGER.x, ImGuiColors.DANGER.y, ImGuiColors.DANGER.z, 0.6f)
            val deleteDisabled = actionsDisabled || offline
            if (deleteDisabled) ImGui.beginDisabled()
            if (ImGui.button("Delete")) {
                d?.let { det ->
                    ConfirmModal.show(
                        title = "Delete schematic",
                        message = "Delete \"${det.name}\"? This cannot be undone.",
                        confirmLabel = "Delete",
                        danger = true,
                    ) { performDelete(det) }
                }
            }
            if (deleteDisabled) ImGui.endDisabled()
            ImGui.popStyleColor(3)
            ImGui.sameLine()
        }

        // Close / Back
        if (ImGui.button("Close")) {
            io.schemat.connector.fabric.client.ui.framework.PanelManager.close(id)
        }
    }

    // ---- action implementations ----

    private fun downloadDirectory(): Path =
        (Bridges.litematica.schematicsDirectory()
            ?: FabricLoader.getInstance().gameDir.resolve("schematics"))
            .resolve(SUBFOLDER)

    private fun fileBaseName(d: SchematicDetail): String {
        val base = d.slug ?: d.shortId ?: d.id
        return base.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").ifBlank { "schematic" }
    }

    private fun downloadToFile(d: SchematicDetail, format: String, extension: String, onSaved: (Path) -> Unit) {
        services.call(
            busy = actionBusy,
            block = {
                when (val result = services.api.download(d.id, format)) {
                    is ApiResult.Success -> {
                        val dir = downloadDirectory()
                        Files.createDirectories(dir)
                        val file = dir.resolve("${fileBaseName(d)}.$extension")
                        Files.write(file, result.value)
                        ApiResult.Success(file)
                    }
                    is ApiResult.Failure -> result
                }
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> onSaved(result.value)
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    private fun loadIntoLitematica(d: SchematicDetail) {
        downloadToFile(d, "litematic", "litematic") { file ->
            Bridges.litematica.loadSchematic(file.toFile(), d.name) { ok, error ->
                if (ok) {
                    statusText = "Loaded \"${d.name}\" into Litematica"
                    statusKind = Widgets.StatusKind.SUCCESS
                } else {
                    statusText = error ?: "Failed to load schematic into Litematica"
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    private fun saveToDisk(d: SchematicDetail) {
        downloadToFile(d, "litematic", "litematic") { file ->
            statusText = "Saved to $file"
            statusKind = Widgets.StatusKind.SUCCESS
        }
    }

    private fun toWorldEditClipboard(d: SchematicDetail) {
        services.call(
            busy = actionBusy,
            block = { services.api.download(d.id, "schem") },
        ) { result ->
            when (result) {
                is ApiResult.Success -> Bridges.worldEdit.bytesToClipboard(result.value, "schem") { ok, error ->
                    if (ok) {
                        statusText = "Copied \"${d.name}\" to WorldEdit clipboard"
                        statusKind = Widgets.StatusKind.SUCCESS
                    } else {
                        statusText = error ?: "Failed to copy to WorldEdit clipboard"
                        statusKind = Widgets.StatusKind.DANGER
                    }
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    /**
     * Reference-pull server clipboard load: sends only the schematic id over IPC;
     * the SERVER pulls the bytes from the backend itself. STATUS updates stream
     * into [statusText] (toast-style, spec §Client); a terminal status (or the
     * tracker's 30 s synthetic ERROR) re-enables the button.
     */
    private fun loadOnServer(d: SchematicDetail) {
        statusText = "Requesting server-side load…"
        statusKind = Widgets.StatusKind.INFO
        val requestId = ServerIpc.sendLoadRequest(LoadRefType.SCHEMATIC, d.id) { state, detail ->
            if (state.isTerminal) pendingServerLoad = null
            val (text, kind) = serverLoadMessage(state, detail, d.name)
            statusText = text
            statusKind = kind
        }
        if (requestId != null) {
            pendingServerLoad = requestId // non-null disables the button until a terminal status/timeout
        } else {
            statusText = "No verified Schematio server connection"
            statusKind = Widgets.StatusKind.WARNING
        }
    }

    /** detail arrives pre-sanitized (ClipboardLoadTracker strips '§' codes). */
    private fun serverLoadMessage(state: StatusState, detail: String, name: String): Pair<String, Widgets.StatusKind> =
        when (state) {
            StatusState.RESOLVING -> "Server is resolving \"$name\"…" to Widgets.StatusKind.INFO
            StatusState.DOWNLOADING -> "Server is downloading \"$name\"…" to Widgets.StatusKind.INFO
            StatusState.OK -> "Loaded \"$name\" into your server-side WorldEdit clipboard — //paste to place it" to Widgets.StatusKind.SUCCESS
            StatusState.DENIED -> detail.ifBlank { "The server denied the request" } to Widgets.StatusKind.DANGER
            StatusState.NOT_FOUND -> "The server's backend could not find this schematic" to Widgets.StatusKind.DANGER
            StatusState.TOO_LARGE -> "Too large for a server clipboard load (8 MiB limit)" to Widgets.StatusKind.DANGER
            StatusState.RATE_LIMITED -> detail.ifBlank { "Rate limited — try again shortly" } to Widgets.StatusKind.WARNING
            StatusState.UNAVAILABLE -> detail.ifBlank { "The server can't load schematics right now" } to Widgets.StatusKind.DANGER
            StatusState.ERROR -> detail.ifBlank { "Unexpected error during the server-side load" } to Widgets.StatusKind.DANGER
        }

    private fun openQuickShare(d: SchematicDetail) {
        val format = d.format
            ?.takeIf { it in setOf("schem", "schematic", "litematic", "nbt", "mcstructure") }
            ?: "litematic"
        QuickShareCreatePanel.show(
            QuickShareCreatePanel.RemoteSchematicSource(d.id, d.name, format)
        )
    }

    private fun performDelete(d: SchematicDetail) {
        services.call(
            busy = actionBusy,
            block = { services.cached.deleteSchematic(d.id) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    io.schemat.connector.fabric.client.ui.framework.PanelManager.close(id)
                    // Invalidate the browse/mine listings so the deleted item disappears
                    BrowsePanel.invalidate()
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }
}
