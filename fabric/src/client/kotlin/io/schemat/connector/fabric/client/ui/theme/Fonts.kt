package io.schemat.connector.fabric.client.ui.theme

import imgui.ImFont

/** Font faces come from panel-lib's shared atlas. */
object Fonts {
    val BODY: ImFont? get() = dev.harrison.panellib.theme.Fonts.BODY
    val SEMIBOLD: ImFont? get() = dev.harrison.panellib.theme.Fonts.SEMIBOLD
    val H2: ImFont? get() = dev.harrison.panellib.theme.Fonts.H2
    val H1: ImFont? get() = dev.harrison.panellib.theme.Fonts.H1
}

inline fun <T> withFont(font: ImFont?, block: () -> T): T = dev.harrison.panellib.theme.withFont(font, block)
