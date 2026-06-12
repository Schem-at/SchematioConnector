package io.schemat.connector.fabric.client.ui

import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.SchematicTag
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.core.text.RichText
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.foundation.RichDescriptionEditor
import io.schemat.connector.fabric.client.ui.foundation.ThemedTextField
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.widgets.PlayerListEditorWidget
import io.schemat.connector.fabric.client.ui.widgets.TagChips
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Edit a schematic the player authors: name, description, visibility, tags
 * (chip summary + "Edit tags..." opening the [TagSelectorScreen] in ASSIGN mode over
 * the loaded tag trees) and co-authors.
 *
 * Save diffs against [detail]: only changed fields go to `updateSchematic`; `setTags`
 * is sent only when the selection or the tag-filter values changed (it REPLACES the
 * tag set). Current tags are matched against the loaded trees by id when the server
 * provides one (falling back to name matching for older servers). Tags outside the
 * loaded trees stay toggleable when they carry an id (a synthetic "Current" selector
 * section, their ids re-submitted while selected); only id-less unmatched tags are
 * shown read-only and dropped by the server if the selection changes. Filter values
 * seed from [SchematicDetail.tagFilterValues]; required filters are enforced inside
 * the selector. Co-author changes are applied as add/remove diffs. All mutations run
 * sequentially under one busy flag.
 */
class SchematicEditScreen(
    private val parent: SchematicDetailScreen,
    private val detail: SchematicDetail,
) : Screen(Component.literal("Edit \"${detail.name}\"")) {

    companion object {
        private const val PADDING = Theme.XL
        private const val CONTROL_HEIGHT = Theme.INPUT_H
        private const val BUTTON_HEIGHT = Theme.BTN_H
        private const val GAP = Theme.MD
        private const val LABEL_HEIGHT = 11
        /** Vertical space reserved for the [Theme.header] drawn in render(). */
        private const val HEADER_RESERVED = 28
    }

    private val services get() = SchematioClientMod.instance.services

    // ---- editable state (survives re-init) ----
    private var nameText: String = detail.name

    /**
     * Descriptions are stored as HTML server-side; the inline editor edits them as
     * HTML directly. Both sides are normalized through the span model
     * (`htmlToSpans`/`spansToHtml`) so whitespace/serialization differences never
     * produce a false diff - only a real change submits a description, and an
     * untouched one is omitted from the update entirely.
     */
    private var initialDescriptionHtml: String =
        RichText.spansToHtml(RichText.htmlToSpans(detail.description ?: ""))
    private var descriptionText: String = initialDescriptionHtml
    private var descriptionEditor: RichDescriptionEditor? = null
    private var isPublic: Boolean = detail.isPublic

    private val coAuthors = PlayerListEditorWidget(
        services = SchematioClientMod.instance.services,
        initial = detail.authors.map { PlayerListEditorWidget.Entry(it.uuid, it.lastSeenName, it.headUrl) },
        minEntries = 1,
    )

    // ---- tag state ----
    private var tagSections: List<Pair<String, List<TagNode>>>? = null
    private val tagsBusy = AtomicBoolean(false)
    /** Selector sections: loaded trees + a synthetic "Current" one for unmatched id-bearing tags. */
    private var selectorSections: List<TagSelectorScreen.TagSection> = emptyList()
    private val selectedTagIds = LinkedHashSet<String>()
    private var initialTagIds: Set<String> = emptySet()
    private val tagFilterValues: MutableMap<Long, String> = detail.tagFilterValues.toMutableMap()
    private var initialFilterValues: Map<Long, String> = detail.tagFilterValues
    /** Current tags without ids: shown read-only, dropped by the server on change. */
    private var fixedTagNames: List<String> = emptyList()
    private val tagNameById = mutableMapOf<String, String>()
    private val tagColorById = mutableMapOf<String, String?>()
    private var editTagsButton: FlatButton? = null
    private var tagChipsArea = intArrayOf(0, 0, 0, 0) // x, y, w, h

    private val saveBusy = AtomicBoolean(false)
    private var saved = false
    private val banner = NoticeBanner()

    private var saveButton: FlatButton? = null

    // ---- layout positions for labels ----
    private var leftX = 0
    private var rightX = 0
    private var columnWidth = 0
    private var nameLabelY = 0
    private var descriptionLabelY = 0
    private var visibilityLabelY = 0
    private var tagsLabelY = 0
    private var coAuthorsLabelY = 0
    private var savingNoteY = 0

    override fun init() {
        super.init()
        val contentTop = PADDING + HEADER_RESERVED
        val buttonsTop = height - PADDING - BUTTON_HEIGHT
        val bannerY = buttonsTop - NoticeBanner.HEIGHT - Theme.SM
        savingNoteY = buttonsTop + (BUTTON_HEIGHT - 8) / 2

        columnWidth = ((width - PADDING * 3) / 2).coerceAtLeast(80)
        leftX = PADDING
        rightX = PADDING * 2 + columnWidth
        val contentBottom = bannerY - GAP

        // ---- left column: name, description, visibility ----
        var y = contentTop
        nameLabelY = y
        y += LABEL_HEIGHT
        val nameField = ThemedTextField(
            font, leftX, y, columnWidth, CONTROL_HEIGHT,
            Component.literal("Name"), placeholder = "Name...",
        )
        nameField.setMaxLength(255)
        nameField.value = nameText
        nameField.setResponder { nameText = it }
        addRenderableWidget(nameField)
        y += CONTROL_HEIGHT + GAP

        descriptionLabelY = y
        y += LABEL_HEIGHT
        val descriptionHeight = (contentBottom - y - GAP - LABEL_HEIGHT - CONTROL_HEIGHT - GAP)
            .coerceIn(RichDescriptionEditor.MIN_HEIGHT, 170)
        val editor = RichDescriptionEditor(font, leftX, y, columnWidth, descriptionHeight) {
            descriptionText = it
        }
        editor.setFromHtml(descriptionText)
        editor.widgets().forEach { addRenderableWidget(it) }
        descriptionEditor = editor
        y += descriptionHeight + GAP

        visibilityLabelY = y
        y += LABEL_HEIGHT
        lateinit var visibilityButton: FlatButton
        visibilityButton = FlatButton.secondary(
            leftX, y, 130, Component.literal(if (isPublic) "Public" else "Private"),
        ) {
            isPublic = !isPublic
            visibilityButton.message = Component.literal(if (isPublic) "Public" else "Private")
        }
        addRenderableWidget(visibilityButton)

        // ---- right column: tags (top half) + co-authors (bottom half) ----
        var ry = contentTop
        tagsLabelY = ry
        ry += LABEL_HEIGHT
        val tagsHeight = ((contentBottom - contentTop) / 2 - LABEL_HEIGHT - GAP).coerceAtLeast(40)
        val chipsHeight = (tagsHeight - BUTTON_HEIGHT - GAP).coerceAtLeast(20)
        tagChipsArea = intArrayOf(rightX, ry, columnWidth, chipsHeight)
        editTagsButton = addRenderableWidget(
            FlatButton.secondary(rightX, ry + chipsHeight + GAP, 100, Component.literal("Edit tags...")) { openTagSelector() }
        )
        ry += tagsHeight + GAP

        coAuthorsLabelY = ry
        ry += LABEL_HEIGHT
        coAuthors.layout(rightX, ry, columnWidth, (contentBottom - ry).coerceAtLeast(40))
            .forEach { addRenderableWidget(it) }

        // ---- banner + buttons ----
        addRenderableWidget(banner.layout(PADDING, bannerY, width - PADDING * 2))

        saveButton = addRenderableWidget(
            FlatButton.success(width - PADDING - 80, buttonsTop, 80, Component.literal("Save")) { save() }
        )
        addRenderableWidget(
            FlatButton.ghost(width - PADDING - 80 - GAP - 80, buttonsTop, 80, Component.literal("Back")) { onClose() }
        )

        if (tagSections == null && !tagsBusy.get()) loadTags()
    }

    // ---- tags ----

    /**
     * Tag trees for the picker: the global "Minecraft" tree via `globalTags()` first,
     * then per joined community via `communityTags(slug)` (inaccessible communities are
     * skipped silently; a `me()` failure only fails the load when the global tree also
     * failed). The schematic's current tags are matched by id (name fallback) to
     * preselect; non-manually-assignable nodes render disabled in the picker.
     */
    private fun loadTags() {
        services.call(
            busy = tagsBusy,
            block = {
                val sections = mutableListOf<Pair<String, List<TagNode>>>()
                val global = services.cached.globalTags()
                if (global is ApiResult.Success && global.value.value.isNotEmpty()) {
                    sections.add("Minecraft" to global.value.value)
                }
                when (val me = services.cached.me()) {
                    is ApiResult.Failure ->
                        if (sections.isEmpty()) ApiResult.Failure(me.error)
                        else ApiResult.Success(sections.toList())
                    is ApiResult.Success -> {
                        for (community in me.value.value.communities) {
                            val tags = services.cached.communityTags(community.slug)
                            if (tags is ApiResult.Success && tags.value.value.isNotEmpty()) {
                                sections.add(community.name to tags.value.value)
                            }
                        }
                        ApiResult.Success(sections.toList())
                    }
                }
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    tagSections = result.value
                    buildTagState(result.value)
                    rebuildWidgets()
                }
                is ApiResult.Failure -> {
                    tagSections = emptyList()
                    banner.show(NoticeBanner.Kind.ERROR, "Tags unavailable: ${result.error.toUserMessage()}") {
                        banner.clear()
                        tagSections = null
                        loadTags()
                    }
                }
            }
        }
    }

    /**
     * Build the selector sections and the initial selection: match the schematic's
     * current tags against the trees by id when the server provides one (name fallback
     * for older servers). Unmatched id-bearing tags become a synthetic "Current" section
     * so they remain toggleable and their ids keep being re-submitted while selected;
     * id-less unmatched tags are display-only.
     */
    private fun buildTagState(sections: List<Pair<String, List<TagNode>>>) {
        tagNameById.clear()
        tagColorById.clear()
        val treeIds = mutableSetOf<String>()
        val byName = mutableMapOf<String, String>() // lowercase name -> id
        sections.forEach { (_, nodes) ->
            fun walk(node: TagNode) {
                treeIds.add(node.id)
                val label = node.name.ifBlank { node.id }
                tagNameById[node.id] = label
                tagColorById[node.id] = node.color
                byName.putIfAbsent(label.lowercase(), node.id)
                node.children.forEach { walk(it) }
            }
            nodes.forEach { walk(it) }
        }

        val matched = LinkedHashSet<String>()
        val unknownWithId = mutableListOf<SchematicTag>()
        val unknownNames = mutableListOf<String>()
        detail.tags.forEach { tag ->
            val id = tag.id?.takeIf { it in treeIds } ?: byName[tag.name.lowercase()]
            when {
                id != null -> matched.add(id)
                tag.id != null -> unknownWithId.add(tag)
                else -> unknownNames.add(tag.name)
            }
        }

        val selectorList = sections
            .map { (label, nodes) -> TagSelectorScreen.TagSection(label, nodes) }
            .toMutableList()
        if (unknownWithId.isNotEmpty()) {
            val nodes = unknownWithId.mapNotNull { tag ->
                val tagId = tag.id ?: return@mapNotNull null
                tagNameById[tagId] = tag.name
                tagColorById[tagId] = tag.color
                matched.add(tagId)
                TagNode(id = tagId, name = tag.name, color = tag.color, scope = null, children = emptyList())
            }
            selectorList.add(TagSelectorScreen.TagSection("Current tags", nodes))
        }

        selectorSections = selectorList
        fixedTagNames = unknownNames
        selectedTagIds.clear()
        selectedTagIds.addAll(matched)
        initialTagIds = matched.toSet()
    }

    /** Open the [TagSelectorScreen] in ASSIGN mode; Done stores selection + filter values. */
    private fun openTagSelector() {
        if (tagSections == null) return
        minecraft!!.setScreen(
            TagSelectorScreen(
                parent = this,
                title = Component.literal("Edit tags"),
                sections = selectorSections,
                mode = TagSelectorScreen.Mode.ASSIGN,
                initialSelection = selectedTagIds.toSet(),
                initialFilterValues = tagFilterValues.toMap(),
                onDone = { selection ->
                    selectedTagIds.clear()
                    selectedTagIds.addAll(selection.tagIds)
                    tagFilterValues.clear()
                    tagFilterValues.putAll(selection.filterValues)
                },
            )
        )
    }

    // ---- save ----

    private fun save() {
        if (saveBusy.get()) return
        val newName = nameText.trim()
        if (newName.isEmpty()) {
            banner.show(NoticeBanner.Kind.ERROR, "Name must not be empty")
            return
        }
        val nameChanged = newName != detail.name
        val descriptionChanged = descriptionText != initialDescriptionHtml
        val visibilityChanged = isPublic != detail.isPublic

        val selectedTags = selectedTagIds.toList()
        val tagsChanged = selectedTags.toSet() != initialTagIds || tagFilterValues != initialFilterValues

        fun String.norm() = lowercase().replace("-", "")
        val initialAuthors = detail.authors.map { it.uuid }
        val currentAuthors = coAuthors.entries().map { it.uuid }
        val addedAuthors = currentAuthors.filter { uuid -> initialAuthors.none { it.norm() == uuid.norm() } }
        val removedAuthors = initialAuthors.filter { uuid -> currentAuthors.none { it.norm() == uuid.norm() } }

        if (!nameChanged && !descriptionChanged && !visibilityChanged && !tagsChanged &&
            addedAuthors.isEmpty() && removedAuthors.isEmpty()
        ) {
            banner.show(NoticeBanner.Kind.INFO, "No changes to save")
            return
        }

        banner.clear()
        services.call(
            busy = saveBusy,
            block = {
                if (nameChanged || descriptionChanged || visibilityChanged) {
                    val result = services.cached.updateSchematic(
                        detail.id,
                        name = newName.takeIf { nameChanged },
                        description = descriptionText.takeIf { descriptionChanged },
                        isPublic = isPublic.takeIf { visibilityChanged },
                    )
                    if (result is ApiResult.Failure) return@call ApiResult.Failure(result.error)
                }
                if (tagsChanged) {
                    val result = services.cached.setTags(detail.id, selectedTags, tagFilterValues.toMap())
                    if (result is ApiResult.Failure) return@call ApiResult.Failure(result.error)
                }
                for (uuid in addedAuthors) {
                    val result = services.cached.addCoAuthor(detail.id, uuid)
                    if (result is ApiResult.Failure) return@call ApiResult.Failure(result.error)
                }
                for (uuid in removedAuthors) {
                    val result = services.cached.removeCoAuthor(detail.id, uuid)
                    if (result is ApiResult.Failure) return@call ApiResult.Failure(result.error)
                }
                ApiResult.Success(Unit)
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    saved = true
                    initialTagIds = selectedTags.toSet()
                    initialFilterValues = tagFilterValues.toMap()
                    if (descriptionChanged) initialDescriptionHtml = descriptionText
                    banner.show(NoticeBanner.Kind.INFO, "Changes saved")
                }
                is ApiResult.Failure -> {
                    // Partial failures still count as "something may have changed".
                    saved = true
                    banner.show(NoticeBanner.Kind.ERROR, formatError(result.error))
                }
            }
        }
    }

    /** Validation errors list the offending fields; everything else uses the shared mapping. */
    private fun formatError(error: ApiError): String = when (error) {
        is ApiError.Validation ->
            if (error.fieldErrors.isEmpty()) error.message
            else error.fieldErrors.entries.joinToString("; ") { (field, messages) ->
                "$field: ${messages.firstOrNull() ?: "invalid"}"
            }
        else -> error.toUserMessage()
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
        Theme.header(
            context, font,
            font.plainSubstrByWidth(title.string, width - PADDING * 2),
            null, PADDING, PADDING, width - PADDING * 2,
        )
        Theme.sectionLabel(context, font, "Name", leftX, nameLabelY)
        Theme.sectionLabel(context, font, "Description", leftX, descriptionLabelY)
        Theme.sectionLabel(context, font, "Visibility", leftX, visibilityLabelY)
        Theme.sectionLabel(context, font, "Tags", rightX, tagsLabelY)
        Theme.sectionLabel(context, font, "Co-authors", rightX, coAuthorsLabelY)

        descriptionEditor?.render(context)
        renderTagChips(context)
        editTagsButton?.active = tagSections != null && !tagsBusy.get()
        saveButton?.active = !saveBusy.get()
        if (saveBusy.get()) {
            Theme.muted(context, font, "Saving...", PADDING, savingNoteY)
        }
        banner.render(context, font)
    }

    /** Chip summary of the current tag selection (plus read-only id-less tags). */
    private fun renderTagChips(context: GuiGraphics) {
        val areaX = tagChipsArea[0]
        val areaY = tagChipsArea[1]
        val areaW = tagChipsArea[2]
        val areaH = tagChipsArea[3]
        context.fill(areaX, areaY, areaX + areaW, areaY + areaH, Theme.SURFACE_ALT)
        Theme.stroke(context, areaX, areaY, areaW, areaH, Theme.BORDER)
        val chips = buildList {
            selectedTagIds.forEach { id -> add(TagChips.Chip(id, tagNameById[id] ?: id, tagColorById[id])) }
            fixedTagNames.forEach { add(TagChips.Chip(null, "$it (kept as-is)", null)) }
        }
        if (chips.isEmpty()) {
            val message = if (tagsBusy.get() || tagSections == null) "Loading tags..." else "No tags"
            Theme.hint(context, font, message, areaX + Theme.XS, areaY + Theme.XS)
            return
        }
        val (placed, overflow) = TagChips.layout(chips, areaX, areaY, areaW, areaH, font, withClose = false)
        TagChips.render(context, font, placed, overflow)
    }

    override fun onClose() {
        if (saved) parent.onEdited()
        minecraft!!.setScreen(parent)
    }
}
