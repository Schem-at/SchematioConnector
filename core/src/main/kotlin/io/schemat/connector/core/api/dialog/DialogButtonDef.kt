package io.schemat.connector.core.api.dialog

import io.schemat.connector.core.api.ClickAction

data class DialogButtonDef(
    val label: DialogText,
    val tooltip: DialogText? = null,
    val width: Int = 100,
    val action: DialogButtonAction? = null
)

sealed class DialogButtonAction {
    /** Command template with {key} placeholders for input substitution. */
    data class CommandTemplate(val template: String) : DialogButtonAction()

    /** Static click action (run command, open URL, etc). */
    data class StaticAction(val clickAction: ClickAction) : DialogButtonAction()
}
