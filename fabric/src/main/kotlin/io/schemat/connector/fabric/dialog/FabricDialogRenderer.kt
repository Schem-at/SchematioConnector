package io.schemat.connector.fabric.dialog

import com.mojang.serialization.JsonOps
import io.schemat.connector.core.api.ClickAction
import io.schemat.connector.core.api.MessageColor
import io.schemat.connector.core.api.dialog.*
import net.minecraft.dialog.AfterAction
import net.minecraft.dialog.DialogActionButtonData
import net.minecraft.dialog.DialogButtonData
import net.minecraft.dialog.DialogCommonData
import net.minecraft.dialog.action.DialogAction
import net.minecraft.dialog.action.DynamicRunCommandDialogAction
import net.minecraft.dialog.action.ParsedTemplate
import net.minecraft.dialog.action.SimpleDialogAction
import net.minecraft.dialog.body.DialogBody
import net.minecraft.dialog.body.PlainMessageDialogBody
import net.minecraft.dialog.input.InputControl
import net.minecraft.dialog.input.SingleOptionInputControl
import net.minecraft.dialog.input.TextInputControl
import net.minecraft.dialog.type.Dialog
import net.minecraft.dialog.type.DialogInput
import net.minecraft.dialog.type.MultiActionDialog
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.net.URI
import java.util.Optional

object FabricDialogRenderer {

    fun showDialog(player: ServerPlayerEntity, definition: DialogDefinition) {
        val dialog = buildDialog(definition)
        val entry = RegistryEntry.of(dialog)
        player.openDialog(entry)
    }

    private fun buildDialog(def: DialogDefinition): Dialog {
        val title = toText(def.title)
        val externalTitle: Optional<Text> = if (def.externalTitle != null) {
            Optional.of(Text.literal(def.externalTitle) as Text)
        } else {
            Optional.empty()
        }

        val bodyElements: List<DialogBody> = def.body.map { bodyText ->
            PlainMessageDialogBody(toText(bodyText), PlainMessageDialogBody.DEFAULT_WIDTH)
        }

        val inputs: List<DialogInput> = def.inputs.map { inputDef ->
            DialogInput(inputDef.id, toInputControl(inputDef))
        }

        val commonData = DialogCommonData(
            title,
            externalTitle,
            def.canCloseWithEscape,
            false, // pause
            AfterAction.CLOSE,
            bodyElements,
            inputs
        )

        // Separate exit action (cancel button = button with no action)
        val actionButtons = mutableListOf<DialogActionButtonData>()
        var exitAction: Optional<DialogActionButtonData> = Optional.empty()

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

    private fun toText(dialogText: DialogText): Text {
        val style = Style.EMPTY.let { s ->
            var style = s
            dialogText.color?.let { style = style.withFormatting(toFormatting(it)) }
            if (dialogText.bold) style = style.withBold(true)
            if (dialogText.italic) style = style.withItalic(true)
            style
        }

        val text = Text.literal(dialogText.text).setStyle(style)

        for (child in dialogText.children) {
            text.append(toText(child))
        }

        return text
    }

    private fun toFormatting(color: MessageColor): Formatting = when (color) {
        MessageColor.BLACK -> Formatting.BLACK
        MessageColor.DARK_BLUE -> Formatting.DARK_BLUE
        MessageColor.DARK_GREEN -> Formatting.DARK_GREEN
        MessageColor.DARK_AQUA -> Formatting.DARK_AQUA
        MessageColor.DARK_RED -> Formatting.DARK_RED
        MessageColor.DARK_PURPLE -> Formatting.DARK_PURPLE
        MessageColor.GOLD -> Formatting.GOLD
        MessageColor.GRAY -> Formatting.GRAY
        MessageColor.DARK_GRAY -> Formatting.DARK_GRAY
        MessageColor.BLUE -> Formatting.BLUE
        MessageColor.GREEN -> Formatting.GREEN
        MessageColor.AQUA -> Formatting.AQUA
        MessageColor.RED -> Formatting.RED
        MessageColor.LIGHT_PURPLE -> Formatting.LIGHT_PURPLE
        MessageColor.YELLOW -> Formatting.YELLOW
        MessageColor.WHITE -> Formatting.WHITE
    }

    private fun toInputControl(def: DialogInputDef): InputControl = when (def) {
        is DialogInputDef.Text -> TextInputControl(
            def.width,
            Text.literal(def.label.text),
            true, // labelVisible
            def.initial,
            def.maxLength,
            Optional.empty() // multiline
        )
        is DialogInputDef.SingleOption -> SingleOptionInputControl(
            def.width,
            def.options.map { entry ->
                SingleOptionInputControl.Entry(
                    entry.value,
                    Optional.of(toText(entry.label)),
                    entry.isDefault
                )
            },
            Text.literal(def.label.text),
            true // labelVisible
        )
    }

    private fun toButtonData(def: DialogButtonDef): DialogActionButtonData {
        val tooltipDef = def.tooltip
        val tooltip: Optional<Text> = if (tooltipDef != null) {
            Optional.of(toText(tooltipDef) as Text)
        } else {
            Optional.empty()
        }

        val buttonData = DialogButtonData(toText(def.label), tooltip, def.width)

        val action: Optional<DialogAction> = when (val a = def.action) {
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

        return DialogActionButtonData(buttonData, action)
    }

    private fun createCommandTemplateAction(template: String): DialogAction {
        // ParsedTemplate has no public constructor - decode via CODEC
        val jsonElement = com.google.gson.JsonPrimitive(template)
        val result = ParsedTemplate.CODEC.parse(JsonOps.INSTANCE, jsonElement)
        val parsed = result.result().orElseThrow {
            IllegalArgumentException("Failed to parse command template: $template")
        }
        return DynamicRunCommandDialogAction(parsed)
    }

    private fun createStaticAction(clickAction: ClickAction): DialogAction {
        val event: net.minecraft.text.ClickEvent = when (clickAction) {
            is ClickAction.RunCommand -> net.minecraft.text.ClickEvent.RunCommand(clickAction.command)
            is ClickAction.SuggestCommand -> net.minecraft.text.ClickEvent.SuggestCommand(clickAction.command)
            is ClickAction.OpenUrl -> net.minecraft.text.ClickEvent.OpenUrl(URI.create(clickAction.url))
            is ClickAction.CopyToClipboard -> net.minecraft.text.ClickEvent.CopyToClipboard(clickAction.text)
        }
        return SimpleDialogAction(event)
    }
}
