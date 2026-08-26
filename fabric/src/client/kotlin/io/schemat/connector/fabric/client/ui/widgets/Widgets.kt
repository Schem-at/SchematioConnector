package io.schemat.connector.fabric.client.ui.widgets

import dev.harrison.panellib.widgets.Anim
import imgui.ImGui
import imgui.ImVec4
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiStyleVar
import imgui.type.ImString
import io.schemat.connector.fabric.client.ui.theme.Fonts
import io.schemat.connector.fabric.client.ui.theme.Icons
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.withFont

/**
 * The SchematioConnector ImGui widget kit — thin themed helpers over native Dear ImGui.
 *
 * All colors reference [ImGuiColors] — no raw hex or numeric ImVec4 literals here.
 * Buttons: primary (accent fill) for THE action of a view, secondary for everything
 * else, ghost for inline/low-emphasis, danger for destructive. Hover states on
 * primary/danger fade via [Anim]; stock widgets keep instant hover.
 */
object Widgets {

    enum class Tone { SUCCESS, WARNING, DANGER, INFO, NEUTRAL }

    // ------------------------------------------------------------------ buttons

    /** Accent-filled call-to-action. SemiBold white label, hover fade. */
    fun primaryButton(label: String, width: Float = 0f): Boolean =
        fadingButton(label, width, ImGuiColors.ACCENT, ImGuiColors.ACCENT_HOVER, ImGuiColors.ACCENT_DIM)

    /** Destructive action button. */
    fun dangerButton(label: String, width: Float = 0f): Boolean =
        fadingButton(
            label, width,
            ImGuiColors.DANGER,
            ImGuiColors.lerp(ImGuiColors.DANGER, ImGuiColors.TEXT_PRIMARY, 0.2f),
            ImGuiColors.lerp(ImGuiColors.DANGER, ImGuiColors.BG, 0.35f),
        )

    private fun fadingButton(label: String, width: Float, base: ImVec4, hover: ImVec4, active: ImVec4): Boolean {
        val id = ImGui.getID(label)
        val bg = ImGuiColors.lerp(base, hover, Anim.peek(id))
        ImGui.pushStyleColor(ImGuiCol.Button, bg.x, bg.y, bg.z, bg.w)
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, bg.x, bg.y, bg.z, bg.w)
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, active.x, active.y, active.z, active.w)
        ImGui.pushStyleColor(ImGuiCol.Text, 1f, 1f, 1f, 1f)
        val clicked = withFont(Fonts.SEMIBOLD) {
            if (width > 0f) ImGui.button(label, width, 0f) else ImGui.button(label)
        }
        ImGui.popStyleColor(4)
        Anim.advance(id, ImGui.isItemHovered(), ImGui.getIO().deltaTime)
        return clicked
    }

    /** Neutral surface button with a subtle border — the default choice. */
    fun secondaryButton(label: String, width: Float = 0f): Boolean {
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 1f)
        ImGui.pushStyleColor(
            ImGuiCol.Border,
            ImGuiColors.BORDER.x, ImGuiColors.BORDER.y, ImGuiColors.BORDER.z, ImGuiColors.BORDER.w,
        )
        val clicked = if (width > 0f) ImGui.button(label, width, 0f) else ImGui.button(label)
        ImGui.popStyleColor(1)
        ImGui.popStyleVar(1)
        return clicked
    }

    /** Frameless low-emphasis button: transparent at rest, faint surface on hover. */
    fun ghostButton(label: String): Boolean {
        ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f)
        ImGui.pushStyleColor(
            ImGuiCol.ButtonHovered,
            ImGuiColors.SURFACE_HOVER.x, ImGuiColors.SURFACE_HOVER.y,
            ImGuiColors.SURFACE_HOVER.z, ImGuiColors.SURFACE_HOVER.w,
        )
        ImGui.pushStyleColor(
            ImGuiCol.ButtonActive,
            ImGuiColors.ACCENT_DIM.x, ImGuiColors.ACCENT_DIM.y,
            ImGuiColors.ACCENT_DIM.z, ImGuiColors.ACCENT_DIM.w,
        )
        val clicked = ImGui.button(label)
        ImGui.popStyleColor(3)
        return clicked
    }

    /** Square frameless icon button with optional tooltip. */
    fun iconButton(icon: String, tooltip: String? = null): Boolean {
        val clicked = ghostButton(icon)
        if (tooltip != null && ImGui.isItemHovered()) ImGui.setTooltip(tooltip)
        return clicked
    }

    /**
     * Back-compat alias (pre-kit API): accent=true → [primaryButton], else [secondaryButton].
     * New code should call those directly.
     */
    fun button(label: String, accent: Boolean = false): Boolean =
        if (accent) primaryButton(label) else secondaryButton(label)

    // ------------------------------------------------------------------ typography

    /** Panel title / hero text (Inter SemiBold 24). */
    fun h1(text: String) = withFont(Fonts.H1) { ImGui.text(text) }

    /** Section heading (Inter SemiBold 20). */
    fun h2(text: String) = withFont(Fonts.H2) { ImGui.text(text) }

    /** H2 heading with breathing room above and below — use between panel sections. */
    fun sectionHeader(text: String) {
        ImGui.spacing()
        h2(text)
        ImGui.spacing()
    }

    /** Muted `label: value` row with the value column aligned at [valueX]. */
    fun kvRow(label: String, value: String, valueX: Float = 150f) {
        ImGui.textColored(
            ImGuiColors.TEXT_FAINT.x, ImGuiColors.TEXT_FAINT.y,
            ImGuiColors.TEXT_FAINT.z, ImGuiColors.TEXT_FAINT.w, label,
        )
        ImGui.sameLine(valueX)
        ImGui.text(value)
    }

    // ------------------------------------------------------------------ status & structure

    /** Rounded tinted pill (draw-list) — role/status chips. */
    fun badge(text: String, tone: Tone) {
        val c = toneColor(tone)
        val padX = 7f
        val padY = 2f
        val size = ImGui.calcTextSize(text)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val w = size.x + padX * 2
        val h = size.y + padY * 2
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + w, y + h, ImGui.getColorU32(c.x, c.y, c.z, 0.16f), h / 2f)
        dl.addRect(x, y, x + w, y + h, ImGui.getColorU32(c.x, c.y, c.z, 0.5f), h / 2f)
        dl.addText(x + padX, y + padY, ImGui.getColorU32(c.x, c.y, c.z, 1f), text)
        ImGui.dummy(w, h)
    }

    /** Centered icon + title (+ optional hint) filling the remaining region — zero states. */
    fun emptyState(icon: String, title: String, hint: String? = null) {
        val availX = ImGui.getContentRegionAvailX()
        val availY = ImGui.getContentRegionAvailY()
        val lineH = ImGui.getTextLineHeightWithSpacing()
        val blockH = lineH * (if (hint != null) 3 else 2)
        if (availY > blockH) ImGui.dummy(0f, (availY - blockH) / 2f)

        fun centered(text: String, c: ImVec4) {
            val w = ImGui.calcTextSize(text).x
            ImGui.setCursorPosX(ImGui.getCursorPosX() + ((availX - w) / 2f).coerceAtLeast(0f))
            ImGui.textColored(c.x, c.y, c.z, c.w, text)
        }
        centered(icon, ImGuiColors.TEXT_FAINT)
        withFont(Fonts.SEMIBOLD) { centered(title, ImGuiColors.TEXT_MUTED) }
        if (hint != null) {
            val hintW = ImGui.calcTextSize(hint).x
            if (hintW <= availX) {
                centered(hint, ImGuiColors.TEXT_FAINT)
            } else {
                ImGui.pushStyleColor(ImGuiCol.Text, ImGuiColors.TEXT_FAINT.x, ImGuiColors.TEXT_FAINT.y, ImGuiColors.TEXT_FAINT.z, ImGuiColors.TEXT_FAINT.w)
                ImGui.textWrapped(hint)
                ImGui.popStyleColor(1)
            }
        }
    }

    /** Status severity used by [statusText] (pre-kit name kept for call sites). */
    enum class StatusKind { SUCCESS, DANGER, WARNING, INFO }

    /** Colored status line with a matching leading icon. */
    fun statusText(text: String, kind: StatusKind) {
        val (c, icon) = when (kind) {
            StatusKind.SUCCESS -> ImGuiColors.SUCCESS to Icons.CHECK_CIRCLE
            StatusKind.DANGER  -> ImGuiColors.DANGER to Icons.XMARK_CIRCLE
            StatusKind.WARNING -> ImGuiColors.WARNING to Icons.WARNING
            StatusKind.INFO    -> ImGuiColors.INFO to Icons.INFO_CIRCLE
        }
        ImGui.textColored(c.x, c.y, c.z, c.w, "$icon  $text")
    }

    private fun toneColor(tone: Tone): ImVec4 = when (tone) {
        Tone.SUCCESS -> ImGuiColors.SUCCESS
        Tone.WARNING -> ImGuiColors.WARNING
        Tone.DANGER  -> ImGuiColors.DANGER
        Tone.INFO    -> ImGuiColors.INFO
        Tone.NEUTRAL -> ImGuiColors.TEXT_MUTED
    }

    // ------------------------------------------------------------------ inputs (pre-kit, kept)

    /**
     * Single-line text input. Inputs are the one control that keeps a visible frame
     * border (FrameBorderSize is 0 globally) — pushed locally here.
     */
    fun textField(label: String, state: ImString, hint: String? = null): Boolean {
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 1f)
        ImGui.pushStyleColor(
            ImGuiCol.Border,
            ImGuiColors.BORDER.x, ImGuiColors.BORDER.y, ImGuiColors.BORDER.z, ImGuiColors.BORDER.w,
        )
        val changed = if (hint != null) {
            ImGui.inputTextWithHint(label, hint, state)
        } else {
            ImGui.inputText(label, state)
        }
        ImGui.popStyleColor(1)
        ImGui.popStyleVar(1)
        return changed
    }

    /** Tab bar helper (unchanged semantics: end* only called after successful begin*). */
    fun tabBar(id: String, tabs: List<Pair<String, () -> Unit>>) {
        if (ImGui.beginTabBar(id)) {
            for ((title, content) in tabs) {
                if (ImGui.beginTabItem(title)) {
                    content()
                    ImGui.endTabItem()
                }
            }
            ImGui.endTabBar()
        }
    }
}
