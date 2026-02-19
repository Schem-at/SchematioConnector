package io.schemat.connector.core.api.dialog

import io.schemat.connector.core.api.MessageColor

data class DialogDefinition(
    val title: DialogText,
    val externalTitle: String? = null,
    val body: List<DialogText> = emptyList(),
    val inputs: List<DialogInputDef> = emptyList(),
    val buttons: List<DialogButtonDef>,
    val canCloseWithEscape: Boolean = true,
    val columns: Int = 1
)

data class DialogText(
    val text: String,
    val color: MessageColor? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val children: List<DialogText> = emptyList()
) {
    companion object {
        fun plain(text: String) = DialogText(text)
        fun colored(text: String, color: MessageColor) = DialogText(text, color = color)
    }

    fun bold() = copy(bold = true)
    fun italic() = copy(italic = true)
    fun append(child: DialogText) = copy(children = children + child)
}
