package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImString
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ImGui edit panel for a schematic the player authors: title/name, description
 * (rich-text via [RichTextWidget] — markup source + live preview, saved as HTML),
 * visibility, and tags.
 *
 * Mirrors the field set and save logic of the vanilla [SchematicEditScreen]:
 *  - name / description / isPublic → [services.cached.updateSchematic] (diff — only changed fields)
 *  - tags → [services.cached.setTags] (replacement set; only sent when selection changed)
 *
 * Co-author editing is NOT included here because it requires the PlayerListEditorWidget
 * which is vanilla-screen-only for now. TODO(Task 19?): add co-author management.
 *
 * Singleton panel — identity fixed to "schematic-edit" so PanelManager dedup keeps the
 * existing instance. Use [show] to seed fields and open.
 */
object SchematicEditPanel : Panel {

    override val id = "schematic-edit"

    private val NAME_MAX = 255

    // ---- services ----
    private val services: ClientServices get() = SchematioClientMod.instance.services

    // ---- current target ----
    private var target: SchematicDetail? = null

    // ---- editable ImGui state ----
    private val nameStr   = ImString(NAME_MAX)
    /** Rich-text WYSIWYG description editor; save via [RichTextEditorWidget.toHtml]. */
    private val descEditor = RichTextEditorWidget("edit-desc")
    private var isPublic  = false

    // ---- tag state ----
    /** Ids selected in the tag popup for this edit session. */
    private val selectedTagIds = LinkedHashSet<String>()
    /** Ids that were already on the schematic when show() was called. */
    private var initialTagIds: Set<String> = emptySet()
    /** tagFilterValues from the original detail — re-submitted unchanged on save. */
    private var tagFilterValues: Map<Long, String> = emptyMap()
    /** Maps tag id → display name, built from detail.tags in show(). */
    private var tagNameById: Map<String, String> = emptyMap()

    // ---- original values for diff ----
    private var originalName: String = ""
    private var originalDesc: String = ""
    private var originalIsPublic: Boolean = false

    // ---- async / status ----
    private val saveBusy = AtomicBoolean(false)
    private var statusText: String? = null
    private var statusKind: Widgets.StatusKind = Widgets.StatusKind.INFO

    // ---- tag display ----
    /** Comma-joined display string rebuilt whenever the selection changes. */
    private var tagDisplayText: String = ""

    /**
     * Seeds the editable fields from [detail] and opens the panel.
     * Replaces any in-progress edit of a different schematic.
     */
    fun show(detail: SchematicDetail) {
        target = detail

        // Seed name / description / visibility. The description is stored as
        // sanitizer-safe HTML; seed the rich editor from it and capture the
        // normalized HTML it round-trips to so the save diff compares like-for-like.
        originalName    = detail.name
        originalIsPublic = detail.isPublic
        nameStr.set(detail.name)
        descEditor.setHtml(detail.description ?: "")
        originalDesc    = descEditor.toHtml()
        isPublic = detail.isPublic

        // Seed tag selection from the detail
        initialTagIds = detail.tags.mapNotNull { it.id }.toSet()
        selectedTagIds.clear()
        selectedTagIds.addAll(initialTagIds)
        tagFilterValues = detail.tagFilterValues.toMap()
        // Build id→name map from the detail's tag list (id is nullable; skip entries without one)
        tagNameById = detail.tags.mapNotNull { tag -> tag.id?.let { it to tag.name } }.toMap()
        rebuildTagDisplay()

        // Clear stale status
        statusText = null
        saveBusy.set(false)

        PanelManager.open(SchematicEditPanel)
    }

    // ---- Panel.render ----

    override fun render() {
        val t = target ?: return

        ImGui.setNextWindowSize(540f, 500f, imgui.flag.ImGuiCond.FirstUseEver)
        val openFlag = ImBoolean(true)
        if (!ImGui.begin("Edit: ${t.name}###schematic-edit", openFlag, ImGuiWindowFlags.None)) {
            ImGui.end()
            return
        }
        if (!openFlag.get()) {
            ImGui.end()
            PanelManager.close(id)
            return
        }

        val busy = saveBusy.get()

        // ---- Name ----
        ImGui.textColored(
            ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y,
            ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w,
            "Name",
        )
        ImGui.setNextItemWidth(-1f)
        if (busy) ImGui.beginDisabled()
        Widgets.textField("##edit-name", nameStr, hint = "Schematic name…")
        if (busy) ImGui.endDisabled()

        ImGui.spacing()

        // ---- Visibility ----
        ImGui.textColored(
            ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y,
            ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w,
            "Visibility",
        )
        if (busy) ImGui.beginDisabled()
        val publicBuf = ImBoolean(isPublic)
        if (ImGui.checkbox("Public", publicBuf)) {
            isPublic = publicBuf.get()
        }
        if (busy) ImGui.endDisabled()

        ImGui.spacing()

        // ---- Tags ----
        ImGui.textColored(
            ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y,
            ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w,
            "Tags",
        )
        if (tagDisplayText.isNotEmpty()) {
            ImGui.textWrapped(tagDisplayText)
        } else {
            ImGui.textDisabled("No tags selected")
        }
        if (busy) ImGui.beginDisabled()
        if (Widgets.button("Edit tags…")) {
            TagSelectorPopup.show(selectedTagIds.toSet()) { chosen ->
                selectedTagIds.clear()
                selectedTagIds.addAll(chosen)
                rebuildTagDisplay()
            }
        }
        if (busy) ImGui.endDisabled()

        ImGui.spacing()

        // ---- Description ----
        ImGui.textColored(
            ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y,
            ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w,
            "Description",
        )
        val availH = ImGui.getContentRegionAvail().y - 60f
        val editorH = (availH * 0.5f).coerceIn(70f, 160f)
        if (busy) ImGui.beginDisabled()
        descEditor.render(editorHeight = editorH, previewHeight = editorH)
        if (busy) ImGui.endDisabled()

        ImGui.spacing()
        ImGui.separator()
        ImGui.spacing()

        // ---- Status ----
        statusText?.let { Widgets.statusText(it, statusKind) }

        // ---- Buttons ----
        if (busy) ImGui.beginDisabled()
        if (Widgets.button("Save", accent = true)) {
            save(t)
        }
        if (busy) ImGui.endDisabled()

        ImGui.sameLine()
        if (ImGui.button("Cancel")) {
            PanelManager.close(id)
        }

        ImGui.end()
    }

    // ---- save ----

    private fun save(t: SchematicDetail) {
        if (saveBusy.get()) return

        val newName = nameStr.get().trim()
        if (newName.isEmpty()) {
            statusText = "Name must not be empty"
            statusKind = Widgets.StatusKind.DANGER
            return
        }

        val newDesc     = descEditor.toHtml()
        val nameChanged = newName != originalName
        val descChanged = newDesc != originalDesc
        val visChanged  = isPublic != originalIsPublic

        val currentTags = selectedTagIds.toList()
        val tagsChanged = currentTags.toSet() != initialTagIds

        if (!nameChanged && !descChanged && !visChanged && !tagsChanged) {
            statusText = "No changes to save"
            statusKind = Widgets.StatusKind.INFO
            return
        }

        statusText = null
        services.call(
            busy = saveBusy,
            block = {
                if (nameChanged || descChanged || visChanged) {
                    val result = services.cached.updateSchematic(
                        t.id,
                        name        = newName.takeIf { nameChanged },
                        description = newDesc.takeIf { descChanged },
                        isPublic    = isPublic.takeIf { visChanged },
                    )
                    if (result is ApiResult.Failure) return@call ApiResult.Failure(result.error)
                }
                if (tagsChanged) {
                    val result = services.cached.setTags(t.id, currentTags, tagFilterValues)
                    if (result is ApiResult.Failure) return@call ApiResult.Failure(result.error)
                }
                ApiResult.Success(Unit)
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    // Update originals so repeated saves don't re-submit unchanged fields
                    if (nameChanged) originalName = newName
                    if (descChanged) originalDesc = newDesc
                    if (visChanged)  originalIsPublic = isPublic
                    if (tagsChanged) initialTagIds = currentTags.toSet()

                    // Refresh the browse/mine listings and the open SchematicDetailPanel
                    BrowsePanel.invalidate()
                    MySchematicsPanel.invalidate()
                    SchematicDetailPanel.refreshDetail(t.id)

                    statusText = "Changes saved"
                    statusKind = Widgets.StatusKind.SUCCESS

                    PanelManager.close(id)
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    // ---- helpers ----

    private fun rebuildTagDisplay() {
        tagDisplayText = selectedTagIds.joinToString(", ") { id -> tagNameById[id] ?: id }
    }
}
