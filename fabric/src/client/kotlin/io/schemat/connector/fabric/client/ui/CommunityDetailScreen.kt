package io.schemat.connector.fabric.client.ui

import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.CommunitySummary
import io.schemat.connector.core.modapi.dto.MemberInfo
import io.schemat.connector.core.modapi.dto.RoleInfo
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.core.text.HtmlText
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.foundation.ConfirmDialogScreen
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.LoadingSpinner
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.foundation.ThemedTextField
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.disabledWhenOffline
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Community detail: header (name/description/member count), a "Browse schematics"
 * jump into the Browse tab, Leave (hidden once the caller is known to be the owner),
 * the paginated member list (kick / role assignment per the caller's permissions) and -
 * for `manage-tags` holders - the community tag tree with add/rename/delete.
 *
 * Role management: `manage-roles` holders get a per-member [Roles] action opening
 * [MemberRolesDialogScreen], which lists the community's role definitions (from
 * `GET /mod/communities/{slug}/roles`) as checkboxes. System roles (e.g. Admin) are
 * shown disabled - they cannot be assigned or revoked via the API.
 */
class CommunityDetailScreen(
    private val parent: HomeScreen,
    private val slug: String,
    seed: CommunitySummary? = null,
) : Screen(Component.literal(seed?.name ?: "Community")) {

    companion object {
        private const val PADDING = Theme.LG
        private const val MEMBER_ROW_HEIGHT = 20
        private const val TAG_ROW_HEIGHT = 16
        private const val NAME_COLUMN_WIDTH = 100
        private const val KICK_ZONE_WIDTH = 34
        private const val ROLES_ZONE_WIDTH = 40
        private const val ZONE_HEIGHT = 14

        private val TAG_SCOPES = listOf("private", "public_viewing", "public_use")
    }

    private val services get() = SchematioClientMod.instance.services

    // ---- state (survives re-init) ----
    private var community: CommunitySummary? = seed
    private var communityFetched = false

    private val members = mutableListOf<MemberInfo>()
    private var nextMembersPage = 1
    private var membersHasMore = false
    private var membersLoaded = false

    private var tags: List<TagNode> = emptyList()
    private var tagsLoaded = false

    private val fetchBusy = AtomicBoolean(false)
    private val membersBusy = AtomicBoolean(false)
    private val actionBusy = AtomicBoolean(false)

    private val banner = NoticeBanner()

    private val selfUuid: String?
        get() = services.authManager.session?.playerUuid?.normalized()

    private fun String.normalized(): String = lowercase().replace("-", "")

    private fun isSelf(member: MemberInfo): Boolean = member.id.normalized() == selfUuid

    /** True once the caller is known (from the loaded member pages) to be the owner. */
    private val isSelfOwner: Boolean
        get() = members.any { isSelf(it) && it.isOwner }

    private val canManageMembers: Boolean get() = community?.can("manage-members") == true
    private val canManageRoles: Boolean get() = community?.can("manage-roles") == true
    private val canManageTags: Boolean get() = community?.can("manage-tags") == true

    // ---- layout ----
    private var headerBottom = 0
    private var membersX = 0
    private var membersWidth = 0
    private var tagsX = 0
    private var tagsWidth = 0
    private var sectionTop = 0
    private var bannerY = 0
    private var leaveButton: AbstractWidget? = null

    override fun init() {
        super.init()
        val contentWidth = width - PADDING * 2

        // Matches the height [Theme.header] consumes for a title + subtitle.
        headerBottom = PADDING + (font.lineHeight + 2) * 2 + Theme.XS + 1 + Theme.LG
        bannerY = height - PADDING - NoticeBanner.HEIGHT
        addRenderableWidget(banner.layout(PADDING, bannerY, contentWidth))

        // Header action row.
        var x = PADDING
        addRenderableWidget(
            FlatButton.primary(x, headerBottom, 124, Component.literal("Browse schematics")) { browseSchematics() }
        )
        x += 124 + Theme.SM
        leaveButton = addRenderableWidget(
            FlatButton.danger(x, headerBottom, 60, Component.literal("Leave")) { confirmLeave() }
                .disabledWhenOffline(services)
        )
        leaveButton!!.visible = !isSelfOwner
        addRenderableWidget(
            FlatButton.ghost(width - PADDING - 60, headerBottom, 60, Component.literal("Back")) { onClose() }
        )

        // Room below the action row for the uppercase section labels.
        sectionTop = headerBottom + Theme.BTN_H + Theme.LG + 12

        // Members column (full width unless the tags section is shown).
        if (canManageTags) {
            membersWidth = (contentWidth * 3) / 5 - Theme.SM
            tagsX = PADDING + membersWidth + Theme.LG
            tagsWidth = width - PADDING - tagsX
        } else {
            membersWidth = contentWidth
            tagsX = 0
            tagsWidth = 0
        }
        membersX = PADDING

        val sectionBottom = bannerY - Theme.MD
        val membersListHeight = (sectionBottom - sectionTop - Theme.BTN_H - Theme.MD).coerceAtLeast(40)
        addRenderableWidget(MemberListWidget(membersX, sectionTop, membersWidth, membersListHeight))
        if (membersHasMore) {
            addRenderableWidget(
                FlatButton.ghost(membersX, sectionTop + membersListHeight + Theme.MD, 90, Component.literal("Load more")) {
                    loadMembers()
                }
            )
        }

        if (canManageTags) {
            val tagsListHeight = (sectionBottom - sectionTop - Theme.BTN_H - Theme.MD).coerceAtLeast(40)
            addRenderableWidget(TagListWidget(tagsX, sectionTop, tagsWidth, tagsListHeight))
            addRenderableWidget(
                FlatButton.secondary(tagsX, sectionTop + tagsListHeight + Theme.MD, 80, Component.literal("Add tag")) {
                    openTagDialog(parentTag = null, existing = null)
                }.disabledWhenOffline(services)
            )
            if (!tagsLoaded) loadTags()
        }

        if (!communityFetched) fetchCommunity()
        if (!membersLoaded) loadMembers()
    }

    // ---- data ----

    private fun fetchCommunity() {
        services.call(
            busy = fetchBusy,
            block = { services.cached.community(slug) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    community = result.value.value
                    communityFetched = true
                    if (result.value.isStale) {
                        banner.show(NoticeBanner.Kind.STALE, "Offline - showing cached data")
                    }
                    rebuildWidgets() // permission-gated sections depend on the fresh summary
                }
                is ApiResult.Failure -> {
                    communityFetched = true
                    banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage()) {
                        banner.clear()
                        communityFetched = false
                        fetchCommunity()
                    }
                }
            }
        }
    }

    private fun loadMembers() {
        val page = nextMembersPage
        services.call(
            busy = membersBusy,
            block = { services.cached.communityMembers(slug, page) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    if (page == 1) members.clear()
                    members.addAll(result.value.items)
                    nextMembersPage = page + 1
                    val hadMore = membersHasMore
                    membersHasMore = result.value.hasMore
                    membersLoaded = true
                    leaveButton?.visible = !isSelfOwner
                    if (hadMore != membersHasMore) rebuildWidgets() // Load more button visibility
                }
                is ApiResult.Failure -> {
                    membersLoaded = true
                    banner.show(NoticeBanner.Kind.ERROR, "Members unavailable: ${result.error.toUserMessage()}") {
                        banner.clear()
                        loadMembers()
                    }
                }
            }
        }
    }

    private fun resetMembers() {
        members.clear()
        nextMembersPage = 1
        membersHasMore = false
        membersLoaded = false
        loadMembers()
    }

    private fun loadTags() {
        tagsLoaded = true
        services.call(
            busy = actionBusy,
            block = { services.cached.communityTags(slug) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> tags = result.value.value
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, "Tags unavailable: ${result.error.toUserMessage()}") {
                    banner.clear()
                    tagsLoaded = false
                    loadTags()
                }
            }
        }
    }

    // ---- header actions ----

    private fun browseSchematics() {
        parent.openBrowseForCommunity(slug, community?.name ?: slug)
        parent.invalidateListings()
        minecraft!!.setScreen(parent)
    }

    private fun confirmLeave() {
        val name = community?.name ?: slug
        minecraft!!.setScreen(
            ConfirmDialogScreen(
                this,
                "Leave community",
                "Leave \"$name\"? You will need a new invitation to rejoin private communities.",
                confirmLabel = "Leave",
                danger = true,
            ) { performLeave() }
        )
    }

    private fun performLeave() {
        services.call(
            busy = actionBusy,
            block = {
                val result = services.cached.leaveCommunity(slug)
                if (result is ApiResult.Success) services.refreshMe()
                result
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    parent.invalidateListings()
                    minecraft!!.setScreen(parent)
                }
                // Owners get the server's 422 ("transfer ownership first") surfaced here.
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    // ---- member actions ----

    private fun confirmKick(member: MemberInfo) {
        minecraft!!.setScreen(
            ConfirmDialogScreen(
                this,
                "Kick member",
                "Kick ${member.name.ifBlank { member.id }} from ${community?.name ?: slug}?",
                confirmLabel = "Kick",
                danger = true,
            ) { performKick(member) }
        )
    }

    private fun performKick(member: MemberInfo) {
        services.call(
            busy = actionBusy,
            block = { services.cached.kickMember(slug, member.id) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    banner.show(NoticeBanner.Kind.INFO, "Kicked ${member.name}")
                    resetMembers()
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    private fun openMemberRolesDialog(member: MemberInfo) {
        services.call(
            busy = actionBusy,
            block = { services.cached.communityRoles(slug) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> minecraft!!.setScreen(
                    MemberRolesDialogScreen(
                        parent = this,
                        memberName = member.name.ifBlank { member.id },
                        roles = result.value.value,
                        currentRoleIds = member.roles.map { it.id }.toSet(),
                    ) { roleIds -> saveMemberRoles(member, roleIds) }
                )
                is ApiResult.Failure ->
                    banner.show(NoticeBanner.Kind.ERROR, "Roles unavailable: ${result.error.toUserMessage()}")
            }
        }
    }

    /**
     * [roleIds] must contain only non-system role ids (the dialog guarantees this):
     * the PUT rejects system ids with 422, and the server leaves the member's current
     * system roles untouched regardless of the submitted set.
     */
    private fun saveMemberRoles(member: MemberInfo, roleIds: List<String>) {
        services.call(
            busy = actionBusy,
            block = { services.cached.syncMemberRoles(slug, member.id, roleIds) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    val index = members.indexOfFirst { it.id == member.id }
                    if (index >= 0) members[index] = member.copy(roles = result.value)
                    banner.show(NoticeBanner.Kind.INFO, "Updated roles for ${member.name}")
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    // ---- tag actions ----

    private fun openTagDialog(parentTag: TagNode?, existing: TagNode?) {
        minecraft!!.setScreen(
            TagEditDialogScreen(
                parent = this,
                heading = when {
                    existing != null -> "Rename tag \"${existing.name}\""
                    parentTag != null -> "Add tag under \"${parentTag.name}\""
                    else -> "Add tag"
                },
                initialName = existing?.name ?: "",
                initialScope = existing?.scope?.takeIf { it in TAG_SCOPES } ?: "private",
                initialColor = existing?.color ?: "",
            ) { name, scope, color ->
                if (existing != null) {
                    saveTag { services.cached.updateCommunityTag(slug, existing.id, name, scope, color) }
                } else {
                    saveTag { services.cached.createCommunityTag(slug, name, scope, color, parentId = parentTag?.id) }
                }
            }
        )
    }

    private fun saveTag(block: suspend () -> ApiResult<TagNode>) {
        services.call(
            busy = actionBusy,
            block = block,
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    tagsLoaded = false
                    loadTags()
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    private fun confirmDeleteTag(tag: TagNode) {
        val childNote = if (tag.children.isNotEmpty()) " Its ${tag.children.size} child tag(s) are deleted too." else ""
        minecraft!!.setScreen(
            ConfirmDialogScreen(
                this,
                "Delete tag",
                "Delete the \"${tag.name}\" tag?$childNote",
                confirmLabel = "Delete",
                danger = true,
            ) { performDeleteTag(tag) }
        )
    }

    private fun performDeleteTag(tag: TagNode) {
        services.call(
            busy = actionBusy,
            block = { services.cached.deleteCommunityTag(slug, tag.id) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    tagsLoaded = false
                    loadTags()
                }
                // Root tag / tags with schematics 422 - show the server's message.
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    // ---- rendering ----

    /** Parse a `#RRGGBB` (or `RRGGBB`) hex color into an opaque ARGB int. */
    private fun parseColor(hex: String?, fallback: Int): Int {
        val cleaned = hex?.removePrefix("#")?.takeIf { it.length == 6 } ?: return fallback
        return cleaned.toIntOrNull(16)?.let { 0xFF_000000.toInt() or it } ?: fallback
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

        val shown = community
        val name = shown?.name ?: slug
        val contentWidth = width - PADDING * 2

        val meta = buildString {
            shown?.memberCount?.let { append("$it member${if (it == 1) "" else "s"}") }
            if (shown != null) {
                if (isNotEmpty()) append("  ·  ")
                append(if (shown.isPublic) "public" else "private")
            }
        }
        val description = shown?.description?.let { HtmlText.toPlain(it) }?.takeIf { it.isNotBlank() }
        val subtitle = listOfNotNull(meta.takeIf { it.isNotEmpty() }, description)
            .joinToString("  ·  ")
            .ifEmpty { "Loading…" }
        Theme.header(
            context, font,
            font.plainSubstrByWidth(name, contentWidth),
            font.plainSubstrByWidth(subtitle, contentWidth),
            PADDING, PADDING, contentWidth,
        )

        Theme.sectionLabel(context, font, "Members", membersX, sectionTop - 12)
        if (canManageTags) {
            Theme.sectionLabel(context, font, "Tags", tagsX, sectionTop - 12)
        }

        if (actionBusy.get() || membersBusy.get() || fetchBusy.get()) {
            LoadingSpinner.render(context, font, width / 2, bannerY - 12, "Working")
        }

        banner.render(context, font)
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }

    // ---- member list widget ----

    /**
     * Scrollable member rows: name (owner-marked), the member's role chips (display-only),
     * a Roles zone when the caller may manage roles, and a Kick zone when permitted.
     */
    private inner class MemberListWidget(x: Int, y: Int, width: Int, height: Int) :
        AbstractWidget(x, y, width, height, Component.literal("Members")) {

        private var scrollOffset = 0.0
        private val contentHeight: Int get() = members.size * MEMBER_ROW_HEIGHT
        private val maxScroll: Double get() = (contentHeight - height).coerceAtLeast(0).toDouble()

        private fun kickable(member: MemberInfo): Boolean =
            canManageMembers && !member.isOwner && !isSelf(member)

        private fun roleEditable(member: MemberInfo): Boolean =
            canManageRoles && !member.isOwner && !isSelf(member)

        /** The left edge of the row's action zones (Roles / Kick), in widget coordinates. */
        private fun actionZoneLeft(member: MemberInfo): Int =
            x + width - (if (kickable(member)) KICK_ZONE_WIDTH + Theme.XS else 0) -
                (if (roleEditable(member)) ROLES_ZONE_WIDTH + Theme.XS else 0)

        /** Chip x-ranges for a member's roles, in widget coordinates. */
        private fun chipRanges(member: MemberInfo): List<Pair<RoleInfo, IntRange>> {
            val font = Minecraft.getInstance().font
            var chipX = x + NAME_COLUMN_WIDTH + Theme.SM
            val limit = actionZoneLeft(member) - Theme.SM
            val out = mutableListOf<Pair<RoleInfo, IntRange>>()
            for (role in member.roles) {
                val chipWidth = Theme.XS + font.width(role.name) + Theme.XS
                if (chipX + chipWidth > limit) break
                out.add(role to (chipX until (chipX + chipWidth)))
                chipX += chipWidth + Theme.XS
            }
            return out
        }

        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
            if (!isMouseOver(mouseX, mouseY)) return false
            scrollOffset = (scrollOffset - verticalAmount * 24.0).coerceIn(0.0, maxScroll)
            return true
        }

        // 1.21.9 changed input handlers to take event objects; on 1.21.8 the
        // primitive override delegates to the event-shaped method (compat shim).
        //? if <1.21.9 {
        /*override fun onClick(clickX: Double, clickY: Double) {
            onClick(MouseButtonEvent(clickX, clickY), false)
        }
        *///?}
        //? if >=1.21.9
        override
        fun onClick(click: MouseButtonEvent, doubled: Boolean) {
            val index = ((click.y() - y + scrollOffset) / MEMBER_ROW_HEIGHT).toInt()
            val member = members.getOrNull(index) ?: return
            val clickX = click.x().toInt()
            if (kickable(member) && clickX >= x + width - KICK_ZONE_WIDTH - Theme.XS) {
                confirmKick(member)
                return
            }
            if (roleEditable(member) && clickX >= actionZoneLeft(member)) {
                openMemberRolesDialog(member)
            }
        }

        // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
        //? if >=26.1 {
        /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        *///?} else {
        override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        //?}
            val font = Minecraft.getInstance().font
            Theme.card(context, x, y, width, height)
            context.enableScissor(x, y, x + width, y + height)
            members.forEachIndexed { index, member ->
                val rowY = y + index * MEMBER_ROW_HEIGHT - scrollOffset.toInt()
                if (rowY + MEMBER_ROW_HEIGHT < y || rowY > y + height) return@forEachIndexed
                val rowHovered = isMouseOver(mouseX.toDouble(), mouseY.toDouble()) &&
                    mouseY in rowY until (rowY + MEMBER_ROW_HEIGHT)
                if (rowHovered) context.fill(x, rowY, x + width, rowY + MEMBER_ROW_HEIGHT, Theme.SURFACE_HOVER)
                if (index > 0) Theme.divider(context, x + Theme.MD, rowY, width - Theme.MD * 2)

                val nameLabel = member.name.ifBlank { member.id } + (if (member.isOwner) " ♛" else "")
                Theme.value(
                    context, font,
                    font.plainSubstrByWidth(nameLabel, NAME_COLUMN_WIDTH),
                    x + Theme.MD, rowY + (MEMBER_ROW_HEIGHT - font.lineHeight + 1) / 2,
                    if (isSelf(member)) Theme.SUCCESS else Theme.TEXT_PRIMARY,
                )

                val chipY = rowY + (MEMBER_ROW_HEIGHT - Theme.CHIP_H) / 2
                for ((role, range) in chipRanges(member)) {
                    Theme.chip(
                        context, font, role.name,
                        parseColor(role.color, Theme.ACCENT),
                        range.first, chipY, range.last - range.first + 1,
                    )
                }

                if (roleEditable(member)) {
                    drawZone(context, "Roles", actionZoneLeft(member), ROLES_ZONE_WIDTH, rowY, mouseX, mouseY, Theme.INFO)
                }
                if (kickable(member)) {
                    drawZone(context, "Kick", x + width - KICK_ZONE_WIDTH - Theme.XS, KICK_ZONE_WIDTH, rowY, mouseX, mouseY, Theme.DANGER)
                }
            }
            if (members.isEmpty() && membersLoaded && !membersBusy.get()) {
                Theme.emptyState(context, font, "No members loaded", x, y, width, height)
            }
            context.disableScissor()

            Theme.scrollbar(context, x + width - 3, y, height, contentHeight, height, scrollOffset.toInt())
        }

        /** Small ghost-style action pill inside a member row. */
        private fun drawZone(context: GuiGraphics, label: String, zoneX: Int, zoneW: Int, rowY: Int, mouseX: Int, mouseY: Int, tint: Int) {
            val font = Minecraft.getInstance().font
            val zoneY = rowY + (MEMBER_ROW_HEIGHT - ZONE_HEIGHT) / 2
            val hovered = mouseX in zoneX until (zoneX + zoneW) &&
                mouseY in rowY until (rowY + MEMBER_ROW_HEIGHT) &&
                isMouseOver(mouseX.toDouble(), mouseY.toDouble())
            if (hovered) {
                context.fill(zoneX, zoneY, zoneX + zoneW, zoneY + ZONE_HEIGHT, Theme.withAlpha(tint, 0x22))
            }
            Theme.stroke(context, zoneX, zoneY, zoneW, ZONE_HEIGHT, Theme.withAlpha(tint, if (hovered) 0xAA else 0x55))
            context.drawString(
                font, label,
                zoneX + (zoneW - font.width(label)) / 2,
                zoneY + (ZONE_HEIGHT - font.lineHeight + 1) / 2,
                if (hovered) Theme.lighten(tint, 0.3f) else tint, false,
            )
        }

        override fun updateWidgetNarration(builder: NarrationElementOutput) {
            defaultButtonNarrationText(builder)
        }
    }

    // ---- tag list widget ----

    /** A flattened tag-tree row. */
    private data class TagRow(val node: TagNode, val depth: Int)

    private fun flattenTags(): List<TagRow> {
        val out = mutableListOf<TagRow>()
        fun walk(node: TagNode, depth: Int) {
            out.add(TagRow(node, depth))
            node.children.forEach { walk(it, depth + 1) }
        }
        tags.forEach { walk(it, 0) }
        return out
    }

    /** Scrollable tag tree with per-row [+] (add child) / [Ren] / [Del] click zones. */
    private inner class TagListWidget(x: Int, y: Int, width: Int, height: Int) :
        AbstractWidget(x, y, width, height, Component.literal("Tags")) {

        private val addZone = 16
        private val renZone = 30
        private val delZone = 28

        private var scrollOffset = 0.0
        private val rows: List<TagRow> get() = flattenTags()
        private val contentHeight: Int get() = rows.size * TAG_ROW_HEIGHT
        private val maxScroll: Double get() = (contentHeight - height).coerceAtLeast(0).toDouble()

        private val delX: Int get() = x + width - delZone - Theme.SM
        private val renX: Int get() = delX - renZone - 2
        private val addX: Int get() = renX - addZone - 2

        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
            if (!isMouseOver(mouseX, mouseY)) return false
            scrollOffset = (scrollOffset - verticalAmount * 24.0).coerceIn(0.0, maxScroll)
            return true
        }

        // 1.21.9 changed input handlers to take event objects; on 1.21.8 the
        // primitive override delegates to the event-shaped method (compat shim).
        //? if <1.21.9 {
        /*override fun onClick(clickX: Double, clickY: Double) {
            onClick(MouseButtonEvent(clickX, clickY), false)
        }
        *///?}
        //? if >=1.21.9
        override
        fun onClick(click: MouseButtonEvent, doubled: Boolean) {
            val index = ((click.y() - y + scrollOffset) / TAG_ROW_HEIGHT).toInt()
            val row = rows.getOrNull(index) ?: return
            val clickX = click.x().toInt()
            when {
                clickX >= delX -> confirmDeleteTag(row.node)
                clickX >= renX -> openTagDialog(parentTag = null, existing = row.node)
                clickX >= addX -> openTagDialog(parentTag = row.node, existing = null)
            }
        }

        // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
        //? if >=26.1 {
        /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        *///?} else {
        override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        //?}
            val font = Minecraft.getInstance().font
            Theme.card(context, x, y, width, height)
            context.enableScissor(x, y, x + width, y + height)
            val flat = rows
            flat.forEachIndexed { index, row ->
                val rowY = y + index * TAG_ROW_HEIGHT - scrollOffset.toInt()
                if (rowY + TAG_ROW_HEIGHT < y || rowY > y + height) return@forEachIndexed
                val rowHovered = isMouseOver(mouseX.toDouble(), mouseY.toDouble()) &&
                    mouseY in rowY until (rowY + TAG_ROW_HEIGHT)
                if (rowHovered) context.fill(x, rowY, x + width, rowY + TAG_ROW_HEIGHT, Theme.SURFACE_HOVER)

                val indent = Theme.MD + row.depth * 10
                val color = parseColor(row.node.color, Theme.TEXT_SECONDARY)
                val textY = rowY + (TAG_ROW_HEIGHT - font.lineHeight + 1) / 2
                Theme.label(
                    context, font,
                    font.plainSubstrByWidth(row.node.name, addX - x - indent - Theme.SM),
                    x + indent, textY, color,
                )
                tagAction(context, "[+]", addX, textY, rowY, mouseX, mouseY, Theme.INFO)
                tagAction(context, "[Ren]", renX, textY, rowY, mouseX, mouseY, Theme.INFO)
                tagAction(context, "[Del]", delX, textY, rowY, mouseX, mouseY, Theme.DANGER)
            }
            if (flat.isEmpty()) {
                val message = if (tagsLoaded && !actionBusy.get()) "No tags yet - use Add tag" else "Loading tags..."
                Theme.emptyState(context, font, message, x, y, width, height)
            }
            context.disableScissor()

            Theme.scrollbar(context, x + width - 3, y, height, contentHeight, height, scrollOffset.toInt())
        }

        private fun tagAction(context: GuiGraphics, label: String, actionX: Int, textY: Int, rowY: Int, mouseX: Int, mouseY: Int, tint: Int) {
            val font = Minecraft.getInstance().font
            val hovered = mouseX >= actionX && mouseX < actionX + font.width(label) + 2 &&
                mouseY in rowY until (rowY + TAG_ROW_HEIGHT) &&
                isMouseOver(mouseX.toDouble(), mouseY.toDouble())
            context.drawString(
                font, label, actionX, textY,
                if (hovered) Theme.lighten(tint, 0.35f) else Theme.withAlpha(tint, 0xCC), false,
            )
        }

        override fun updateWidgetNarration(builder: NarrationElementOutput) {
            defaultButtonNarrationText(builder)
        }
    }
}

/**
 * Small modal for creating/renaming a community tag: name, scope cycler
 * (private / public_viewing / public_use) and an optional hex color. [onSubmit]
 * runs after the screen returns to [parent].
 */
class TagEditDialogScreen(
    private val parent: Screen,
    private val heading: String,
    initialName: String,
    initialScope: String,
    initialColor: String,
    private val onSubmit: (name: String, scope: String, color: String?) -> Unit,
) : Screen(Component.literal(heading)) {

    companion object {
        private const val FIELD_WIDTH = 200
        private const val LABEL_H = 10

        private val SCOPES = listOf("private", "public_viewing", "public_use")
        private val SCOPE_LABELS = mapOf(
            "private" to "Private",
            "public_viewing" to "Public viewing",
            "public_use" to "Public use",
        )
    }

    private var nameText = initialName
    private var scope = if (initialScope in SCOPES) initialScope else "private"
    private var colorText = initialColor
    private var error: String? = null

    // Computed in init(), shared with render().
    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0
    private var nameLabelY = 0
    private var scopeLabelY = 0
    private var colorLabelY = 0
    private var errorY = 0

    private fun scopeLabel(): Component = Component.literal(SCOPE_LABELS[scope] ?: scope)

    override fun init() {
        super.init()
        panelW = FIELD_WIDTH + Theme.XL * 2
        panelH = Theme.XL + font.lineHeight + Theme.LG + // heading
            (LABEL_H + Theme.INPUT_H) + Theme.MD +               // name
            (LABEL_H + Theme.BTN_H) + Theme.MD +                 // scope
            (LABEL_H + Theme.INPUT_H) + Theme.MD +               // color
            LABEL_H + Theme.LG +                                 // error line
            Theme.BTN_H + Theme.XL                               // buttons
        panelX = (width - panelW) / 2
        panelY = ((height - panelH) / 2).coerceAtLeast(Theme.XL)

        val x = panelX + Theme.XL
        var y = panelY + Theme.XL + font.lineHeight + Theme.LG

        nameLabelY = y
        y += LABEL_H
        val nameField = ThemedTextField(font, x, y, FIELD_WIDTH, label = Component.literal("Name"))
        nameField.setMaxLength(255)
        nameField.value = nameText
        nameField.setResponder { nameText = it }
        addRenderableWidget(nameField)
        y += Theme.INPUT_H + Theme.MD

        scopeLabelY = y
        y += LABEL_H
        var scopeButton: FlatButton? = null
        val scopeWidget = FlatButton.secondary(x, y, FIELD_WIDTH, scopeLabel()) {
            scope = SCOPES[(SCOPES.indexOf(scope) + 1) % SCOPES.size]
            scopeButton?.message = scopeLabel()
        }
        scopeButton = scopeWidget
        addRenderableWidget(scopeWidget)
        y += Theme.BTN_H + Theme.MD

        colorLabelY = y
        y += LABEL_H
        val colorField = ThemedTextField(font, x, y, FIELD_WIDTH, label = Component.literal("Color"))
        colorField.setMaxLength(7)
        colorField.value = colorText
        colorField.setResponder { colorText = it }
        addRenderableWidget(colorField)
        y += Theme.INPUT_H + Theme.MD

        errorY = y
        y += LABEL_H + Theme.LG

        addRenderableWidget(
            FlatButton.ghost(x, y, 95, Component.literal("Cancel")) { onClose() }
        )
        addRenderableWidget(
            FlatButton.primary(x + FIELD_WIDTH - 95, y, 95, Component.literal("Save")) { submit() }
        )
    }

    private fun submit() {
        val name = nameText.trim()
        if (name.isEmpty()) {
            error = "Name is required"
            return
        }
        val color = colorText.trim().takeIf { it.isNotEmpty() }
        if (color != null && !Regex("^#?[0-9a-fA-F]{6}$").matches(color)) {
            error = "Color must be a hex value like #aabbcc"
            return
        }
        val normalizedColor = color?.let { if (it.startsWith("#")) it else "#$it" }
        minecraft!!.setScreen(parent)
        onSubmit(name, scope, normalizedColor)
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
        Theme.panel(context, panelX, panelY, panelW, panelH)
    }

    //? if >=26.1 {
    /*override fun extractRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
    *///?} else {
    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    //?}
        val x = panelX + Theme.XL
        val bold = Component.literal(heading).withStyle { it.withBold(true) }
        context.drawString(
            font, bold,
            width / 2 - font.width(bold) / 2, panelY + Theme.XL,
            Theme.TEXT_PRIMARY, false,
        )
        Theme.sectionLabel(context, font, "Name", x, nameLabelY)
        Theme.sectionLabel(context, font, "Scope", x, scopeLabelY)
        Theme.sectionLabel(context, font, "Color (optional, #RRGGBB)", x, colorLabelY)
        error?.let {
            Theme.label(
                context, font, it,
                width / 2 - font.width(it) / 2, errorY, Theme.DANGER,
            )
        }
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }
}

/**
 * Small modal for editing a member's roles: one checkbox row per non-system role
 * definition, pre-checked from the member's current roles. System roles (e.g. the
 * immutable Admin role) are listed disabled with a "(system)" label - they cannot be
 * granted or revoked via the API. [onSubmit] receives ONLY the checked non-system role
 * ids: the PUT members/roles endpoint rejects system ids with 422, and the server
 * leaves the member's current system roles untouched regardless of the submitted set.
 */
class MemberRolesDialogScreen(
    private val parent: Screen,
    memberName: String,
    private val roles: List<RoleInfo>,
    currentRoleIds: Set<String>,
    private val onSubmit: (roleIds: List<String>) -> Unit,
) : Screen(Component.literal("Edit roles")) {

    companion object {
        private const val FIELD_WIDTH = 220
        private const val ROW_GAP = 2
        private const val HINT_LINE_H = 10
    }

    private val heading = "Roles for $memberName"
    private val hint = "System roles are managed by the server and cannot be changed here."

    /** Checked state is tracked for non-system roles only - system ids are never submitted. */
    private val checked: MutableSet<String> =
        roles.filter { !it.isSystem && it.id in currentRoleIds }.map { it.id }.toMutableSet()

    /** System roles the member currently holds, shown checked but immutable. */
    private val heldSystemIds: Set<String> =
        roles.filter { it.isSystem && it.id in currentRoleIds }.map { it.id }.toSet()

    // Computed in init(), shared with render().
    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0
    private var listTop = 0
    private var hintLineCount = 1

    override fun init() {
        super.init()
        panelW = FIELD_WIDTH + Theme.XL * 2
        hintLineCount = font.split(Component.literal(hint), panelW - Theme.XL * 2).size.coerceAtLeast(1)

        val rowsHeight =
            if (roles.isEmpty()) HINT_LINE_H + Theme.MD
            else roles.size * (Theme.BTN_H + ROW_GAP)
        panelH = Theme.XL + font.lineHeight + Theme.SM +  // heading
            hintLineCount * HINT_LINE_H + Theme.LG +              // hint
            rowsHeight + Theme.LG +                               // role rows
            Theme.BTN_H + Theme.XL                                // buttons
        panelX = (width - panelW) / 2
        panelY = ((height - panelH) / 2).coerceAtLeast(Theme.LG)

        val x = panelX + Theme.XL
        listTop = panelY + Theme.XL + font.lineHeight + Theme.SM + hintLineCount * HINT_LINE_H + Theme.LG
        var y = listTop

        for (role in roles) {
            var button: FlatButton? = null
            val widget = FlatButton.secondary(x, y, FIELD_WIDTH, roleLabel(role)) {
                if (!checked.add(role.id)) checked.remove(role.id)
                button?.message = roleLabel(role)
            }
            button = widget
            if (role.isSystem) widget.active = false
            addRenderableWidget(widget)
            y += Theme.BTN_H + ROW_GAP
        }
        if (roles.isEmpty()) y += HINT_LINE_H + Theme.MD

        y += Theme.LG
        addRenderableWidget(
            FlatButton.ghost(x, y, 105, Component.literal("Cancel")) { onClose() }
        )
        addRenderableWidget(
            FlatButton.primary(x + FIELD_WIDTH - 105, y, 105, Component.literal("Save")) { submit() }
        )
    }

    private fun roleLabel(role: RoleInfo): Component = Component.literal(
        when {
            role.isSystem && role.id in heldSystemIds -> "[x] ${role.name} (system)"
            role.isSystem -> "[ ] ${role.name} (system)"
            role.id in checked -> "[x] ${role.name}"
            else -> "[ ] ${role.name}"
        }
    )

    private fun submit() {
        minecraft!!.setScreen(parent)
        onSubmit(checked.toList())
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
        Theme.panel(context, panelX, panelY, panelW, panelH)
    }

    //? if >=26.1 {
    /*override fun extractRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
    *///?} else {
    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    //?}
        val bold = Component.literal(heading).withStyle { it.withBold(true) }
        context.drawString(
            font, bold,
            width / 2 - font.width(bold) / 2, panelY + Theme.XL,
            Theme.TEXT_PRIMARY, false,
        )
        var hintY = panelY + Theme.XL + font.lineHeight + Theme.SM
        for (line in font.split(Component.literal(hint), panelW - Theme.XL * 2)) {
            context.drawString(
                font, line,
                width / 2 - font.width(line) / 2, hintY,
                Theme.TEXT_MUTED, false,
            )
            hintY += HINT_LINE_H
        }
        if (roles.isEmpty()) {
            val message = "No roles defined for this community."
            Theme.muted(
                context, font, message,
                width / 2 - font.width(message) / 2, listTop,
            )
        }
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }
}
