package io.schemat.connector.fabric.client.ui.widgets
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors

import com.mojang.blaze3d.opengl.GlTexture
import imgui.ImGui
import imgui.flag.ImGuiKey
import imgui.type.ImString
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.services.MojangLookup
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.lwjgl.opengl.GL11C

/**
 * Reusable ImGui collaborator / co-author picker — the ImGui port of the old vanilla
 * `PlayerListEditorWidget`. A name field + Add resolves the player name to a UUID via
 * [MojangLookup] (async); resolved players show as removable chips WITH head avatars.
 * As the user types, a debounced live preview shows the matched player (head + name)
 * before they commit. "Player not found" / "already added" / "max reached" inline.
 *
 * Stateful; create one per form field and call [render] inside an open ImGui window.
 */
class PlayerListPicker(private val maxEntries: Int = 10) {
    data class Entry(val uuid: String, val name: String)

    private enum class PreviewStatus { IDLE, PENDING, RESOLVING, FOUND, NOT_FOUND }

    private val entries = mutableListOf<Entry>()
    private val nameBuf = ImString(48)
    private var error: String? = null
    private var resolving = false

    // Debounced live preview of the typed name (head + name shown before Add).
    private var lastPreviewText = ""
    private var lastEditNs = 0L
    private var previewGen = 0
    private var previewProfile: MojangLookup.Profile? = null
    private var previewStatus = PreviewStatus.IDLE

    /** GL ids already patched to LINEAR filtering (textures sample black otherwise on Apple GL). */
    private val glLinearFixed = HashSet<Int>()

    private val services get() = SchematioClientMod.instance.services

    /** Resolved UUIDs, in add order. */
    fun uuids(): List<String> = entries.map { it.uuid }

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Seed the list (e.g. existing co-authors when editing); capped at [maxEntries]. */
    fun setEntries(list: List<Entry>) {
        entries.clear()
        entries.addAll(list.take(maxEntries))
        error = null
    }

    fun clear() {
        entries.clear()
        nameBuf.set("")
        error = null
        resolving = false
        resetPreview()
    }

    /** Render inline. Returns true if the entry set changed this frame (add/remove). */
    fun render(): Boolean {
        var changed = false

        ImGui.setNextItemWidth(200f)
        // Plain edit-synced field (NOT inputText(..., EnterReturnsTrue), which only synced the
        // buffer back to nameBuf on Enter — so clicking Add read an empty name). Enter-to-add
        // is handled explicitly via isKeyPressed below.
        Widgets.textField("##player-name", nameBuf, hint = "Player name")
        val enterSubmit = ImGui.isItemFocused() && ImGui.isKeyPressed(ImGuiKey.Enter)
        if (error == "Enter a player name" && nameBuf.get().isNotBlank()) error = null
        ImGui.sameLine()
        val canAdd = !resolving && entries.size < maxEntries
        val addClicked = Widgets.button(if (resolving) "Adding…" else "Add")
        if ((addClicked || enterSubmit) && canAdd) addByName()

        tickPreview()
        renderPreviewRow()

        if (entries.isEmpty()) {
            muted("No collaborators added")
        } else {
            var removeIndex = -1
            for ((i, e) in entries.withIndex()) {
                if (Widgets.button("x##player-$i")) removeIndex = i
                ImGui.sameLine()
                drawHead(e.uuid)
                ImGui.text(e.name)
            }
            if (removeIndex >= 0) {
                entries.removeAt(removeIndex)
                error = null
                changed = true
            }
        }

        error?.let { Widgets.statusText(it, Widgets.StatusKind.DANGER) }
        if (entries.size >= maxEntries) muted("Maximum $maxEntries collaborators")
        return changed
    }

    // ---- debounced live preview ----

    private fun tickPreview() {
        val current = nameBuf.get().trim()
        if (current != lastPreviewText) {
            lastPreviewText = current
            lastEditNs = System.nanoTime()
            previewProfile = null
            previewGen++ // invalidate any in-flight lookup
            previewStatus = if (current.isEmpty()) PreviewStatus.IDLE else PreviewStatus.PENDING
        }
        if (previewStatus == PreviewStatus.PENDING && System.nanoTime() - lastEditNs >= DEBOUNCE_NS) {
            launchPreview(current)
        }
    }

    private fun launchPreview(name: String) {
        previewStatus = PreviewStatus.RESOLVING
        val gen = ++previewGen
        services.scope.launch {
            val profile = runCatching { MojangLookup.resolve(name) }.getOrNull()
            services.onMainThread {
                if (gen != previewGen) return@onMainThread // stale: user kept typing
                if (profile == null) {
                    previewStatus = PreviewStatus.NOT_FOUND
                    previewProfile = null
                } else {
                    previewStatus = PreviewStatus.FOUND
                    previewProfile = profile
                }
            }
        }
    }

    private fun renderPreviewRow() {
        if (resolving) return // an Add is already in flight
        when (previewStatus) {
            PreviewStatus.RESOLVING -> muted("Looking up \"$lastPreviewText\"…")
            PreviewStatus.NOT_FOUND -> muted("No player named \"$lastPreviewText\"")
            PreviewStatus.FOUND -> {
                val p = previewProfile ?: return
                if (entries.any { it.uuid.equals(p.uuid, ignoreCase = true) }) {
                    muted("${p.name} is already added")
                } else {
                    drawHead(p.uuid)
                    ImGui.text(p.name)
                    ImGui.sameLine()
                    muted("(press Add)")
                }
            }
            else -> {}
        }
    }

    private fun resetPreview() {
        lastPreviewText = ""
        previewProfile = null
        previewStatus = PreviewStatus.IDLE
        previewGen++
    }

    // ---- head avatar ----

    /** Draw a ~16px player head for [uuid] (placeholder gap until it loads), then sameLine. */
    private fun drawHead(uuid: String) {
        val glId = services.headAvatars.getHead(uuid)?.let { resolveGlId(it) }
        if (glId != null) ImGui.image(glId.toLong(), HEAD_PX, HEAD_PX) else ImGui.dummy(HEAD_PX, HEAD_PX)
        ImGui.sameLine()
    }

    /** Resolve a registered MC texture [id] to its GL id, patching LINEAR filtering once. */
    private fun resolveGlId(id: Identifier): Int? = try {
        val gpu = Minecraft.getInstance().textureManager.getTexture(id).getTexture()
        if (gpu !is GlTexture) {
            null
        } else {
            val glId = gpu.glId()
            if (glId == 0) {
                null
            } else {
                if (glLinearFixed.add(glId)) {
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glId)
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR)
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR)
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0)
                }
                glId
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun addByName() {
        val name = nameBuf.get().trim()
        when {
            name.isEmpty() -> { error = "Enter a player name"; return }
            entries.size >= maxEntries -> { error = "Too many players (max $maxEntries)"; return }
            entries.any { it.name.equals(name, ignoreCase = true) } -> { error = "$name is already in the list"; return }
        }
        error = null
        resolving = true
        services.scope.launch {
            val result = runCatching { MojangLookup.resolve(name) }
            services.onMainThread {
                resolving = false
                result.onSuccess { profile ->
                    when {
                        profile == null -> error = "Player not found"
                        entries.any { it.uuid.equals(profile.uuid, ignoreCase = true) } ->
                            error = "${profile.name} is already in the list"
                        else -> {
                            entries.add(Entry(profile.uuid, profile.name))
                            nameBuf.set("")
                            resetPreview()
                        }
                    }
                }.onFailure { error = it.message ?: "Lookup failed" }
            }
        }
    }

    private fun muted(text: String) {
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w, text,
        )
    }

    companion object {
        private const val DEBOUNCE_NS = 300_000_000L // 300ms
        private const val HEAD_PX = 16f
    }
}
