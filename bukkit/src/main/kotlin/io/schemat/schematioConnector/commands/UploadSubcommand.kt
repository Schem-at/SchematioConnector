package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ipc.ClipboardUploadService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Uploads the player's current WorldEdit clipboard to schemat.io as a DRAFT
 * (IPC sub-project C, standalone path — works without the client mod).
 *
 * Drives the same [ClipboardUploadService] as the IPC handler: guards, 2/min token
 * bucket, main-thread snapshot, async serialize + community-token POST. The result
 * is a clickable web link to finish labelling in the browser; the draft expires in
 * 48h if left unfinished. No user credentials are involved anywhere in this flow.
 *
 * Usage: /schematio upload
 */
class UploadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "upload"

    // The router checks this before execute() — the guard object re-checks it for
    // the IPC path, where there is no router.
    override val permission = io.schemat.schematioConnector.ipc.ClipboardUploadGuards.UPLOAD_PERMISSION

    override val description = "Upload your clipboard to schemat.io as a draft"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()
        audience.sendMessage(Component.text("Uploading your clipboard as a draft...").color(NamedTextColor.YELLOW))

        plugin.clipboardUploadService.uploadCurrentClipboard(
            player,
            requireAttested = false, // standalone chat path — no IPC session to attest
            attested = false,
        ) { result ->
            when (result) {
                is ClipboardUploadService.Result.Created -> showDraftLink(player, result.webUrl)
                is ClipboardUploadService.Result.Failed ->
                    if (result.notLinked) {
                        showAccountNotLinkedError(player)
                    } else {
                        player.audience().sendMessage(Component.text(result.detail).color(NamedTextColor.RED))
                    }
            }
        }

        return true
    }

    private fun showDraftLink(player: Player, url: String) {
        val audience = player.audience()

        audience.sendMessage(Component.text("Draft created!").color(NamedTextColor.GREEN))
        audience.sendMessage(
            Component.text("Finish it in your browser: ").color(NamedTextColor.GRAY)
                .append(
                    Component.text("[Complete your upload]")
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Open the draft upload page"))),
                ),
        )
        audience.sendMessage(
            Component.text("[Click to copy link]")
                .color(NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.copyToClipboard(url))
                .hoverEvent(HoverEvent.showText(Component.text("Copy link to clipboard"))),
        )
        audience.sendMessage(
            Component.text("The draft expires in 48 hours if left unfinished.").color(NamedTextColor.GRAY),
        )
    }

    /**
     * Shown when the backend can't match the uploader to an account (400 player_not_linked).
     * Two very different causes, told apart by the UUID version:
     *   - v3 (name-based UUID) -> an offline-mode / unauthenticated client. schemat.io can
     *     never match this to a real account — the real fix is to join in online mode.
     *   - v4 (random UUID) -> a genuine Mojang identity with no schemat.io account yet;
     *     point them at the site to sign up (clickable link).
     */
    private fun showAccountNotLinkedError(player: Player) {
        val audience = player.audience()
        if (player.uniqueId.version() == 3) {
            audience.sendMessage(Component.text("We couldn't verify your Minecraft account.").color(NamedTextColor.RED))
            audience.sendMessage(
                Component.text(
                    "This server is in offline mode, so schemat.io can't confirm who you are. " +
                        "Join from an online-mode (Mojang-authenticated) client to upload.",
                ).color(NamedTextColor.GRAY),
            )
        } else {
            val url = plugin.baseUrl.ifBlank { "https://schemat.io" }
            audience.sendMessage(Component.text("No schemat.io account is linked to your Minecraft yet.").color(NamedTextColor.RED))
            audience.sendMessage(
                Component.text("Sign up free and link your account at ").color(NamedTextColor.GRAY)
                    .append(
                        Component.text(url).color(NamedTextColor.AQUA)
                            .decorate(TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text("Open schemat.io"))),
                    )
                    .append(Component.text(", then try again.").color(NamedTextColor.GRAY)),
            )
        }
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> = emptyList()
}
