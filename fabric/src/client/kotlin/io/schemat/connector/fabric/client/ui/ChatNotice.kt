package io.schemat.connector.fabric.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import java.net.URI

/**
 * Posts mod notices to the in-game chat instead of holding a full-screen UI open.
 * Used for terminal "it worked" moments (upload complete, quick share created):
 * the screen closes back to the game and a one-line message with a clickable
 * link lands in chat - less intrusive than a success screen.
 *
 * MC 1.21.x click/hover events are sealed record types: [ClickEvent.OpenUrl]
 * wraps a [URI], [ClickEvent.CopyToClipboard] a [String], and
 * [HoverEvent.ShowText] a [Component].
 */
object ChatNotice {

    /**
     * `[Schemat.io] <message> [<linkLabel>] [Copy link]` - the link label opens
     * [url] in the browser (hover shows the full url), Copy link puts it on the
     * clipboard. Falls back to plain text when [url] doesn't parse as a URI.
     * Always hops to the client thread before touching the chat HUD.
     */
    fun success(message: String, url: String, linkLabel: String = "Click to view") {
        val text = Component.empty()
            .append(Component.literal("[Schemat.io] ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(message).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" "))

        val uri = runCatching { URI.create(url) }.getOrNull()
        if (uri != null) {
            text.append(
                Component.literal("[$linkLabel]").withStyle { style ->
                    style.withColor(ChatFormatting.LIGHT_PURPLE)
                        .withClickEvent(ClickEvent.OpenUrl(uri))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal(url)))
                }
            )
            text.append(Component.literal(" "))
            text.append(
                Component.literal("[Copy link]").withStyle { style ->
                    style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(ClickEvent.CopyToClipboard(url))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("Copy link to clipboard")))
                }
            )
        } else {
            // Malformed url - still show it so the user can copy it manually.
            text.append(Component.literal(url).withStyle(ChatFormatting.AQUA))
        }

        val client = Minecraft.getInstance()
        // 26.x made ChatComponent.addMessage private; client-originated notices
        // go through the public addClientSystemMessage instead.
        //? if >=26.1 {
        /*client.execute { client.gui.chat.addClientSystemMessage(text) }
        *///?} else {
        client.execute { client.gui.chat.addMessage(text) }
        //?}
    }

    /** `[Schemat.io] <message>` in white - plain informational notice. */
    fun info(message: String) = plain(message, ChatFormatting.WHITE)

    /** `[Schemat.io] <message>` in red - failure notice. */
    fun error(message: String) = plain(message, ChatFormatting.RED)

    private fun plain(message: String, color: ChatFormatting) {
        val text = Component.empty()
            .append(Component.literal("[Schemat.io] ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(message).withStyle(color))
        val client = Minecraft.getInstance()
        //? if >=26.1 {
        /*client.execute { client.gui.chat.addClientSystemMessage(text) }
        *///?} else {
        client.execute { client.gui.chat.addMessage(text) }
        //?}
    }
}
