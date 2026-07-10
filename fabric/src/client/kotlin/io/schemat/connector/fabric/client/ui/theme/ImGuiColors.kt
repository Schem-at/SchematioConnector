package io.schemat.connector.fabric.client.ui.theme

import imgui.ImVec4
import io.schemat.connector.fabric.client.ui.theme.Theme

/**
 * Converts an ARGB packed int (as used by [Theme]) to an [ImVec4] (r, g, b, a) in the 0..1 range
 * expected by Dear ImGui color APIs.
 */
fun argbToImVec4(argb: Int): ImVec4 {
    val a = (argb ushr 24 and 0xFF) / 255f
    val r = (argb ushr 16 and 0xFF) / 255f
    val g = (argb ushr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return ImVec4(r, g, b, a)
}

/**
 * Dear ImGui color palette derived from [Theme].
 *
 * All constants reference [Theme]'s ARGB ints directly — Theme.kt remains the
 * single source of truth; this object only translates to the [ImVec4] form that
 * imgui-java APIs accept.
 */
object ImGuiColors {
    val ACCENT         = argbToImVec4(Theme.ACCENT)
    val ACCENT_HOVER   = argbToImVec4(Theme.ACCENT_HOVER)
    val ACCENT_DIM     = argbToImVec4(Theme.ACCENT_DIM)

    val BG             = argbToImVec4(Theme.BG)
    val SURFACE        = argbToImVec4(Theme.SURFACE)
    val SURFACE_ALT    = argbToImVec4(Theme.SURFACE_ALT)
    val SURFACE_HOVER  = argbToImVec4(Theme.SURFACE_HOVER)

    val BORDER         = argbToImVec4(Theme.BORDER)
    val BORDER_SUBTLE  = argbToImVec4(Theme.BORDER_SUBTLE)
    val BORDER_ACCENT  = argbToImVec4(Theme.BORDER_ACCENT)

    val TEXT_PRIMARY   = argbToImVec4(Theme.TEXT_PRIMARY)
    val TEXT_SECONDARY = argbToImVec4(Theme.TEXT_SECONDARY)
    val TEXT_MUTED     = argbToImVec4(Theme.TEXT_MUTED)
    val TEXT_FAINT     = argbToImVec4(Theme.TEXT_FAINT)

    val SUCCESS        = argbToImVec4(Theme.SUCCESS)
    val SUCCESS_TEXT   = argbToImVec4(Theme.SUCCESS_TEXT)
    val DANGER         = argbToImVec4(Theme.DANGER)
    val WARNING        = argbToImVec4(Theme.WARNING)
    val INFO           = argbToImVec4(Theme.INFO)

    val SCRIM          = argbToImVec4(Theme.SCRIM)
}
