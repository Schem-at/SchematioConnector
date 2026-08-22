package io.schemat.connector.fabric.client.ui.theme

import dev.harrison.panellib.theme.Theme
import imgui.ImVec4

/** ARGB int → ImVec4 (kept for the rich-text widgets). */
fun argbToImVec4(argb: Int): ImVec4 = dev.harrison.panellib.theme.argbToImVec4(argb)

/**
 * Colour tokens for the ImGui UI, resolved from panel-lib's active [Theme] every access so the
 * Schematio panels follow whatever theme the shared overlay is using. The names are the
 * pre-panel-lib ones so panel code did not have to change.
 */
object ImGuiColors {
    private val t: Theme get() = Theme.current

    val ACCENT: ImVec4 get() = t.accent
    val ACCENT_HOVER: ImVec4 get() = t.accentHover
    val ACCENT_DIM: ImVec4 get() = t.accentDim
    val BG: ImVec4 get() = t.bg
    val SURFACE: ImVec4 get() = t.surface
    val SURFACE_ALT: ImVec4 get() = t.surfaceAlt
    val SURFACE_HOVER: ImVec4 get() = t.surfaceHover
    val BORDER: ImVec4 get() = t.border
    val BORDER_SUBTLE: ImVec4 get() = t.borderSubtle
    val BORDER_ACCENT: ImVec4 get() = t.accentDim
    val TEXT_PRIMARY: ImVec4 get() = t.text
    val TEXT_SECONDARY: ImVec4 get() = t.textSecondary
    val TEXT_MUTED: ImVec4 get() = t.textMuted
    val TEXT_FAINT: ImVec4 get() = t.textFaint
    val SUCCESS: ImVec4 get() = t.success
    val SUCCESS_TEXT: ImVec4 get() = dev.harrison.panellib.theme.lerp(t.success, t.bg, 0.85f)
    val DANGER: ImVec4 get() = t.danger
    val WARNING: ImVec4 get() = t.warning
    val INFO: ImVec4 get() = t.info
    val SCRIM: ImVec4 get() = t.scrim
    val SURFACE_RAISED: ImVec4 get() = t.surfaceRaised
    val ACCENT_MUTED: ImVec4 get() = t.accentMuted
    val STRIPE: ImVec4 get() = t.stripe
    val SCRIM_SOFT: ImVec4 get() = t.scrim
    val TRANSPARENT: ImVec4 = Theme.TRANSPARENT

    fun lerp(a: ImVec4, b: ImVec4, t: Float): ImVec4 = dev.harrison.panellib.theme.lerp(a, b, t)
}
