package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.CommunitySummary
import io.schemat.connector.core.modapi.dto.MemberInfo
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import dev.harrison.panellib.widgets.ConfirmModal
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.theme.Icons
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ImGui panel for community detail: header (name / description / member count), member
 * list (with kick if the caller can manage members), the community's schematics list
 * (row-click → [SchematicDetailPanel]), and join/leave actions.
 *
 * Singleton panel — identity is fixed to "community-detail" so [PanelManager] dedup
 * keeps the existing instance when a row is re-clicked. Use [show] to set the target
 * and open; clicking a different community row replaces the target in place.
 *
 * Sub-flows deferred to follow-up tasks:
 *   TODO(Task 15b): member-role editing dialog (MemberRolesDialogScreen → ImGui modal)
 *   TODO(Task 15b): tag management tree (add/rename/delete community tags)
 *   TODO(Task 15b): invite-by-name picker modal
 */
object CommunityDetailPanel : Panel {

    override val id = "community-detail"

    // ---- services ----
    private val services: ClientServices get() = SchematioClientMod.instance.services

    // ---- current target ----
    private var slug: String = ""
    private var seed: CommunitySummary? = null

    // ---- community fetch ----
    private var community: CommunitySummary? = null
    private var communityFetched = false
    private var communityIsStale = false
    private val fetchBusy = AtomicBoolean(false)
    private var fetchError: String? = null

    // ---- member list ----
    private val members = mutableListOf<MemberInfo>()
    private var nextMembersPage = 1
    private var membersHasMore = false
    private var membersLoaded = false
    private val membersBusy = AtomicBoolean(false)
    private var membersError: String? = null

    // ---- schematics list — reuse the SchematicListView SchematicListState pattern ----
    private var schemItems = mutableListOf<io.schemat.connector.core.modapi.dto.SchematicSummary>()
    private var schemNextPage = 1
    private var schemHasMore = false
    private var schemHasLoaded = false
    private val schemLoadBusy = AtomicBoolean(false)
    private var schemError: String? = null
    private var schemIsStale = false

    // ---- action state ----
    private val actionBusy = AtomicBoolean(false)
    private var statusText: String? = null
    private var statusKind: Widgets.StatusKind = Widgets.StatusKind.INFO

    // ---- permission helpers ----
    private val selfUuid: String
        get() = services.authManager.session?.playerUuid?.normalized() ?: ""

    private fun String.normalized(): String = lowercase().replace("-", "")

    private fun isSelf(member: MemberInfo): Boolean = member.id.normalized() == selfUuid

    private val isSelfOwner: Boolean
        get() = members.any { isSelf(it) && it.isOwner }

    private val canManageMembers: Boolean
        get() = community?.can("manage-members") == true

    // ---- open / show ----

    /**
     * Set the target community and open the panel. If the panel is already open for a
     * different community, state is replaced in place (same singleton, same PanelManager entry).
     */
    fun show(community: CommunitySummary) {
        val replacing = slug != community.slug
        slug = community.slug
        seed = community
        if (replacing) {
            this.community = community   // seed immediately so header renders before fetch
            communityFetched = false
            communityIsStale = false
            fetchError = null
            fetchBusy.set(false)
            clearMembers()
            clearSchematics()
            actionBusy.set(false)
            statusText = null
        }
        io.schemat.connector.fabric.client.ui.framework.PanelManager.open(CommunityDetailPanel)
        loadIfNeeded()
    }

    private fun clearMembers() {
        members.clear()
        nextMembersPage = 1
        membersHasMore = false
        membersLoaded = false
        membersBusy.set(false)
        membersError = null
    }

    private fun clearSchematics() {
        schemItems.clear()
        schemNextPage = 1
        schemHasMore = false
        schemHasLoaded = false
        schemLoadBusy.set(false)
        schemError = null
        schemIsStale = false
    }

    private fun loadIfNeeded() {
        if (!communityFetched && !fetchBusy.get()) fetchCommunity()
        if (!membersLoaded && !membersBusy.get()) loadMembers()
        if (!schemHasLoaded && !schemLoadBusy.get()) loadSchematics()
    }

    // ---- data loading ----

    private fun fetchCommunity() {
        services.call(
            busy = fetchBusy,
            block = { services.cached.community(slug) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    community = result.value.value
                    communityFetched = true
                    communityIsStale = result.value.isStale
                    fetchError = null
                }
                is ApiResult.Failure -> {
                    communityFetched = true
                    fetchError = result.error.toUserMessage()
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
                    membersHasMore = result.value.hasMore
                    membersLoaded = true
                    membersError = null
                }
                is ApiResult.Failure -> {
                    membersLoaded = true
                    membersError = "Members unavailable: ${result.error.toUserMessage()}"
                }
            }
        }
    }

    private fun loadSchematics() {
        val page = schemNextPage
        val generation = page  // simple guard: ignore stale responses for earlier pages
        services.call(
            busy = schemLoadBusy,
            block = {
                services.cached.communitySchematics(
                    slug,
                    io.schemat.connector.core.modapi.BrowseQuery(
                        search = null,
                        tags = emptyList(),
                        sort = "created_at",
                        order = "desc",
                        page = page,
                    )
                )
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    val page2 = result.value.value
                    if (schemNextPage == generation) {
                        if (page == 1) schemItems.clear()
                        schemItems.addAll(page2.items)
                        schemNextPage = page + 1
                        schemHasMore = page2.hasMore
                        schemIsStale = result.value.isStale
                    }
                    schemHasLoaded = true
                    schemError = null
                }
                is ApiResult.Failure -> {
                    schemHasLoaded = true
                    schemError = result.error.toUserMessage()
                }
            }
        }
    }

    // ---- actions ----

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
                    // Invalidate the Browse list so it refreshes
                    BrowsePanel.invalidate()
                    io.schemat.connector.fabric.client.ui.framework.PanelManager.close(id)
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    private fun performKick(member: MemberInfo) {
        services.call(
            busy = actionBusy,
            block = { services.cached.kickMember(slug, member.id) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    statusText = "Kicked ${member.name.ifBlank { member.id }}"
                    statusKind = Widgets.StatusKind.INFO
                    clearMembers()
                    loadMembers()
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    // ---- Panel.render ----

    override fun render() {
        val c = community ?: seed ?: return

        ImGui.setNextWindowSize(700f, 560f, imgui.flag.ImGuiCond.FirstUseEver)
        val open = imgui.type.ImBoolean(true)
        if (!ImGui.begin("${c.name}###community-detail", open, ImGuiWindowFlags.None)) {
            ImGui.end()
            return
        }
        ImGuiTheme.windowTitleAccent()
        if (!open.get()) {
            ImGui.end()
            io.schemat.connector.fabric.client.ui.framework.PanelManager.close(id)
            return
        }

        renderHeader(c)
        ImGui.separator()
        renderStatusBanner()

        Widgets.tabBar("##cd_tabs", listOf(
            "Members" to ::renderMembersTab,
            "Schematics" to ::renderSchematicsTab,
        ))

        ImGui.end()
    }

    // ---- header ----

    private fun renderHeader(c: CommunitySummary) {
        // Name + visibility badge
        ImGui.textColored(
            ImGuiColors.TEXT_PRIMARY.x, ImGuiColors.TEXT_PRIMARY.y,
            ImGuiColors.TEXT_PRIMARY.z, ImGuiColors.TEXT_PRIMARY.w,
            c.name
        )
        ImGui.sameLine()
        ImGui.textDisabled(if (c.isPublic) "[public]" else "[private]")

        // Member count
        val memberCountStr = c.memberCount?.let { "$it member${if (it == 1) "" else "s"}" }
        if (memberCountStr != null) {
            ImGui.textDisabled(memberCountStr)
        }

        // Description
        val desc = c.description?.trim()?.takeIf { it.isNotBlank() }
        if (desc != null) {
            ImGui.textWrapped(desc)
        }

        // Stale data banner — shown when community data came from cache (offline)
        if (communityIsStale) {
            Widgets.statusText("Offline — showing cached data", Widgets.StatusKind.WARNING)
        }

        // Fetch error (shown inline, non-fatal — seed data still displayed)
        val fErr = fetchError
        if (fErr != null) {
            Widgets.statusText("Could not refresh: $fErr", Widgets.StatusKind.WARNING)
        }

        ImGui.spacing()

        // Action row
        val actionsDisabled = actionBusy.get() || services.isOffline()
        if (actionsDisabled) ImGui.beginDisabled()

        // Leave button — hidden for owner (matches vanilla CommunityDetailScreen);
        // also hidden until membersLoaded so it doesn't flash for owners before member data arrives.
        if (membersLoaded && !isSelfOwner) {
            if (Widgets.button("Leave##cd_leave")) {
                val communityName = community?.name ?: slug
                ConfirmModal.show(
                    title = "Leave community",
                    message = "Leave \"$communityName\"?",
                    confirmLabel = "Leave",
                    danger = true,
                ) { performLeave() }
            }
            ImGui.sameLine()
        }

        if (actionsDisabled) ImGui.endDisabled()

        if (Widgets.button("Close##cd_close")) {
            io.schemat.connector.fabric.client.ui.framework.PanelManager.close(id)
        }
    }

    // ---- status banner ----

    private fun renderStatusBanner() {
        val st = statusText
        if (st != null) {
            Widgets.statusText(st, statusKind)
            ImGui.sameLine()
            if (Widgets.button("X##cd_dismiss")) {
                statusText = null
            }
        }
    }

    // ---- Members tab ----

    private fun renderMembersTab() {
        val mErr = membersError
        if (mErr != null) {
            Widgets.statusText(mErr, Widgets.StatusKind.DANGER)
            ImGui.sameLine()
            if (Widgets.button("Retry##cd_members_retry")) {
                membersError = null
                clearMembers()
                loadMembers()
            }
            return
        }

        if (membersBusy.get() && members.isEmpty()) {
            ImGui.textDisabled("Loading members...")
            return
        }

        if (membersLoaded && members.isEmpty()) {
            Widgets.emptyState(Icons.USERS, "No members found")
            return
        }

        if (members.isNotEmpty()) {
            val colCount = if (canManageMembers) 4 else 3
            ImGuiTheme.withStandardTable("##cd_members", colCount) {
                ImGui.tableSetupScrollFreeze(0, 1)
                ImGui.tableSetupColumn("Name")
                ImGui.tableSetupColumn("Roles")
                ImGui.tableSetupColumn("Joined")
                if (canManageMembers) ImGui.tableSetupColumn("Actions")
                ImGui.tableHeadersRow()

                for (member in members.toList()) {
                    ImGui.tableNextRow()

                    ImGui.tableSetColumnIndex(0)
                    val displayName = member.name.ifBlank { member.id }
                    if (member.isOwner) {
                        ImGui.textColored(
                            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y,
                            ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
                            "$displayName (Owner)"
                        )
                    } else {
                        ImGui.text(displayName)
                    }

                    ImGui.tableSetColumnIndex(1)
                    val rolesStr = member.roles.take(3).joinToString(", ") { it.name }.ifEmpty { "—" }
                    ImGui.textDisabled(rolesStr)

                    ImGui.tableSetColumnIndex(2)
                    ImGui.textDisabled(member.joinedAt?.take(10) ?: "—")

                    if (canManageMembers) {
                        ImGui.tableSetColumnIndex(3)
                        val canKick = !isSelf(member) && !member.isOwner
                        val kickDisabled = actionBusy.get() || services.isOffline() || !canKick
                        if (kickDisabled) ImGui.beginDisabled()
                        if (Widgets.button("Kick##cd_kick_${member.id}")) {
                            val memberDisplayName = member.name.ifBlank { member.id }
                            val communityName = community?.name ?: slug
                            ConfirmModal.show(
                                title = "Remove member",
                                message = "Remove $memberDisplayName from $communityName?",
                                confirmLabel = "Remove",
                                danger = true,
                            ) { performKick(member) }
                        }
                        if (kickDisabled) ImGui.endDisabled()
                        // TODO(Task 15b): role-edit button → ImGui modal replacing MemberRolesDialogScreen
                    }
                }

                // "Load more" row
                if (membersHasMore) {
                    ImGui.tableNextRow()
                    ImGui.tableSetColumnIndex(0)
                    val loadMoreDisabled = membersBusy.get()
                    if (loadMoreDisabled) ImGui.beginDisabled()
                    if (Widgets.button("Load more##cd_members_more")) {
                        loadMembers()
                    }
                    if (loadMoreDisabled) ImGui.endDisabled()
                }

                // Refreshing indicator
                if (membersBusy.get() && members.isNotEmpty()) {
                    ImGui.tableNextRow()
                    ImGui.tableSetColumnIndex(0)
                    ImGui.textDisabled("Refreshing...")
                }
            }
        }

        // TODO(Task 15b): invite-by-name picker modal (services.cached.inviteMember(slug, username))
    }

    // ---- Schematics tab ----

    private fun renderSchematicsTab() {
        if (!schemHasLoaded && !schemLoadBusy.get()) loadSchematics()

        if (schemIsStale) {
            Widgets.statusText("Offline — showing cached results", Widgets.StatusKind.WARNING)
        }

        val sErr = schemError
        if (sErr != null) {
            Widgets.statusText(sErr, Widgets.StatusKind.DANGER)
            ImGui.sameLine()
            if (Widgets.button("Retry##cd_schem_retry")) {
                schemError = null
                clearSchematics()
                loadSchematics()
            }
            return
        }

        if (schemLoadBusy.get() && schemItems.isEmpty()) {
            ImGui.textDisabled("Loading schematics...")
            return
        }

        if (schemHasLoaded && schemItems.isEmpty()) {
            Widgets.emptyState(Icons.CUBE, "No schematics yet", "Uploads tagged to this community will appear here")
            return
        }

        if (schemItems.isNotEmpty()) {
            ImGuiTheme.withStandardTable("##cd_schematics", 4) {
                ImGui.tableSetupScrollFreeze(0, 1)
                ImGui.tableSetupColumn("Name")
                ImGui.tableSetupColumn("Author")
                ImGui.tableSetupColumn("Format")
                ImGui.tableSetupColumn("Created")
                ImGui.tableHeadersRow()

                for (item in schemItems.toList()) {
                    ImGui.tableNextRow()

                    ImGui.tableSetColumnIndex(0)
                    if (ImGui.selectable(
                            "${item.name}##cdschem_${item.id}",
                            false,
                            imgui.flag.ImGuiSelectableFlags.SpanAllColumns
                        )
                    ) {
                        SchematicDetailPanel.show(item)
                    }

                    ImGui.tableSetColumnIndex(1)
                    val author = item.authors.firstOrNull()?.lastSeenName ?: "—"
                    ImGui.textDisabled(author)

                    ImGui.tableSetColumnIndex(2)
                    ImGui.textDisabled(item.format ?: "—")

                    ImGui.tableSetColumnIndex(3)
                    ImGui.textDisabled(item.createdAt?.take(10) ?: "—")
                }

                // Load more row
                if (schemHasMore) {
                    ImGui.tableNextRow()
                    ImGui.tableSetColumnIndex(0)
                    val loadMoreDisabled = schemLoadBusy.get()
                    if (loadMoreDisabled) ImGui.beginDisabled()
                    if (Widgets.button("Load more##cd_schem_more")) {
                        loadSchematics()
                    }
                    if (loadMoreDisabled) ImGui.endDisabled()
                }

                if (schemLoadBusy.get() && schemItems.isNotEmpty()) {
                    ImGui.tableNextRow()
                    ImGui.tableSetColumnIndex(0)
                    ImGui.textDisabled("Refreshing...")
                }
            }
        }
    }
}
