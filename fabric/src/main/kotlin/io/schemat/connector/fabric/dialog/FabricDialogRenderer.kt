package io.schemat.connector.fabric.dialog

import com.mojang.serialization.JsonOps
import io.schemat.connector.core.api.ClickAction
import io.schemat.connector.core.api.MessageColor
import io.schemat.connector.core.api.dialog.*
import net.minecraft.ChatFormatting
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.dialog.ActionButton
import net.minecraft.server.dialog.CommonButtonData
import net.minecraft.server.dialog.CommonDialogData
import net.minecraft.server.dialog.Dialog
import net.minecraft.server.dialog.DialogAction
import net.minecraft.server.dialog.Input
import net.minecraft.server.dialog.MultiActionDialog
import net.minecraft.server.dialog.action.Action
import net.minecraft.server.dialog.action.CommandTemplate
import net.minecraft.server.dialog.action.ParsedTemplate
import net.minecraft.server.dialog.action.StaticAction
import net.minecraft.server.dialog.body.DialogBody
import net.minecraft.server.dialog.body.PlainMessage
import net.minecraft.server.dialog.input.InputControl
import net.minecraft.server.dialog.input.SingleOptionInput
import net.minecraft.server.dialog.input.TextInput
import net.minecraft.server.level.ServerPlayer
import java.net.URI
import java.util.Optional

object FabricDialogRenderer {

    fun showDialog(player: ServerPlayer, definition: DialogDefinition) {
        val dialog = buildDialog(definition)
        val entry = Holder.direct(dialog)
        player.openDialog(entry)
    }

    private fun buildDialog(def: DialogDefinition): Dialog {
        val title = toText(def.title)
        val externalTitleText = def.externalTitle
        val externalTitle: Optional<Component> = if (externalTitleText != null) {
            Optional.of(Component.literal(externalTitleText) as Component)
        } else {
            Optional.empty()
        }

        val bodyElements: List<DialogBody> = def.body.map { bodyText ->
            PlainMessage(toText(bodyText), PlainMessage.DEFAULT_WIDTH)
        }

        val inputs: List<Input> = def.inputs.map { inputDef ->
            Input(inputDef.id, toInputControl(inputDef))
        }

        val commonData = CommonDialogData(
            title,
            externalTitle,
            def.canCloseWithEscape,
            false, // pause
            DialogAction.CLOSE,
            bodyElements,
            inputs
        )

        // Separate exit action (cancel button = button with no action)
        val actionButtons = mutableListOf<ActionButton>()
        var exitAction: Optional<ActionButton> = Optional.empty()

        for (button in def.buttons) {
            val buttonData = toButtonData(button)
            if (button.action == null) {
                // This is the cancel/close button - use as exit action
                exitAction = Optional.of(buttonData)
            } else {
                actionButtons.add(buttonData)
            }
        }

        return MultiActionDialog(commonData, actionButtons, exitAction, def.columns)
    }

    private fun toText(dialogText: DialogText): Component {
        val style = Style.EMPTY.let { s ->
            var style = s
            dialogText.color?.let { style = style.applyFormat(toFormatting(it)) }
            if (dialogText.bold) style = style.withBold(true)
            if (dialogText.italic) style = style.withItalic(true)
            style
        }

        val text = Component.literal(dialogText.text).setStyle(style)

        for (child in dialogText.children) {
            text.append(toText(child))
        }

        return text
    }

    private fun toFormatting(color: MessageColor): ChatFormatting = when (color) {
        MessageColor.BLACK -> ChatFormatting.BLACK
        MessageColor.DARK_BLUE -> ChatFormatting.DARK_BLUE
        MessageColor.DARK_GREEN -> ChatFormatting.DARK_GREEN
        MessageColor.DARK_AQUA -> ChatFormatting.DARK_AQUA
        MessageColor.DARK_RED -> ChatFormatting.DARK_RED
        MessageColor.DARK_PURPLE -> ChatFormatting.DARK_PURPLE
        MessageColor.GOLD -> ChatFormatting.GOLD
        MessageColor.GRAY -> ChatFormatting.GRAY
        MessageColor.DARK_GRAY -> ChatFormatting.DARK_GRAY
        MessageColor.BLUE -> ChatFormatting.BLUE
        MessageColor.GREEN -> ChatFormatting.GREEN
        MessageColor.AQUA -> ChatFormatting.AQUA
        MessageColor.RED -> ChatFormatting.RED
        MessageColor.LIGHT_PURPLE -> ChatFormatting.LIGHT_PURPLE
        MessageColor.YELLOW -> ChatFormatting.YELLOW
        MessageColor.WHITE -> ChatFormatting.WHITE
    }

    private fun toInputControl(def: DialogInputDef): InputControl = when (def) {
        is DialogInputDef.Text -> TextInput(
            def.width,
            Component.literal(def.label.text),
            true, // labelVisible
            def.initial,
            def.maxLength,
            Optional.empty() // multiline
        )
        is DialogInputDef.SingleOption -> SingleOptionInput(
            def.width,
            def.options.map { entry ->
                SingleOptionInput.Entry(
                    entry.value,
                    Optional.of(toText(entry.label)),
                    entry.isDefault
                )
            },
            Component.literal(def.label.text),
            true // labelVisible
        )
    }

    private fun toButtonData(def: DialogButtonDef): ActionButton {
        val tooltipDef = def.tooltip
        val tooltip: Optional<Component> = if (tooltipDef != null) {
            Optional.of(toText(tooltipDef) as Component)
        } else {
            Optional.empty()
        }

        val buttonData = CommonButtonData(toText(def.label), tooltip, def.width)

        val action: Optional<Action> = when (val a = def.action) {
            null -> Optional.empty()
            is DialogButtonAction.CommandTemplate -> {
                // Convert {key} to $(key) for vanilla MC template syntax
                val vanillaTemplate = a.template.replace(Regex("\\{(\\w+)}"), "\$($1)")
                Optional.of(createCommandTemplateAction(vanillaTemplate))
            }
            is DialogButtonAction.StaticAction -> {
                Optional.of(createStaticAction(a.clickAction))
            }
        }

        return ActionButton(buttonData, action)
    }

    private fun createCommandTemplateAction(template: String): Action {
        // ParsedTemplate has no public constructor - decode via CODEC
        val jsonElement = com.google.gson.JsonPrimitive(template)
        val result = ParsedTemplate.CODEC.parse(JsonOps.INSTANCE, jsonElement)
        val parsed = result.result().orElseThrow {
            IllegalArgumentException("Failed to parse command template: $template")
        }
        return CommandTemplate(parsed)
    }

    private fun createStaticAction(clickAction: ClickAction): Action {
        val event: net.minecraft.network.chat.ClickEvent = when (clickAction) {
            is ClickAction.RunCommand -> net.minecraft.network.chat.ClickEvent.RunCommand(clickAction.command)
            is ClickAction.SuggestCommand -> net.minecraft.network.chat.ClickEvent.SuggestCommand(clickAction.command)
            is ClickAction.OpenUrl -> net.minecraft.network.chat.ClickEvent.OpenUrl(URI.create(clickAction.url))
            is ClickAction.CopyToClipboard -> net.minecraft.network.chat.ClickEvent.CopyToClipboard(clickAction.text)
        }
        return StaticAction(event)
    }
}
