package io.schemat.connector.core.api.dialog

sealed class DialogInputDef {
    abstract val id: String
    abstract val label: DialogText
    abstract val width: Int

    data class Text(
        override val id: String,
        override val label: DialogText,
        override val width: Int = 200,
        val initial: String = "",
        val maxLength: Int = 256
    ) : DialogInputDef()

    data class SingleOption(
        override val id: String,
        override val label: DialogText,
        override val width: Int = 200,
        val options: List<OptionEntry>
    ) : DialogInputDef()

    data class OptionEntry(
        val value: String,
        val label: DialogText,
        val isDefault: Boolean = false
    )
}
