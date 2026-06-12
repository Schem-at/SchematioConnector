package io.schemat.connector.core.api

/**
 * Platform-agnostic rich text message representation.
 * Supports colors, formatting, click actions, and hover text.
 * Each platform adapter converts this to its native text format.
 */
data class RichMessage(
    val text: String,
    val color: MessageColor? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underlined: Boolean = false,
    val strikethrough: Boolean = false,
    val clickAction: ClickAction? = null,
    val hoverText: String? = null,
    val children: List<RichMessage> = emptyList()
) {
    companion object {
        /**
         * Create a simple text message.
         */
        fun text(text: String): RichMessage = RichMessage(text)

        /**
         * Create a colored text message.
         */
        fun colored(text: String, color: MessageColor): RichMessage = RichMessage(text, color = color)

        /**
         * Create an error message (red text).
         */
        fun error(text: String): RichMessage = RichMessage(text, color = MessageColor.RED)

        /**
         * Create a success message (green text).
         */
        fun success(text: String): RichMessage = RichMessage(text, color = MessageColor.GREEN)

        /**
         * Create a warning message (yellow text).
         */
        fun warning(text: String): RichMessage = RichMessage(text, color = MessageColor.YELLOW)

        /**
         * Create an info message (aqua text).
         */
        fun info(text: String): RichMessage = RichMessage(text, color = MessageColor.AQUA)
    }

    /**
     * Add a child message (for building complex messages).
     */
    fun append(child: RichMessage): RichMessage = copy(children = children + child)

    /**
     * Add a click action to run a command.
     */
    fun runCommand(command: String): RichMessage = copy(clickAction = ClickAction.RunCommand(command))

    /**
     * Add a click action to suggest a command.
     */
    fun suggestCommand(command: String): RichMessage = copy(clickAction = ClickAction.SuggestCommand(command))

    /**
     * Add a click action to open a URL.
     */
    fun openUrl(url: String): RichMessage = copy(clickAction = ClickAction.OpenUrl(url))

    /**
     * Add a click action to copy text to clipboard.
     */
    fun copyToClipboard(text: String): RichMessage = copy(clickAction = ClickAction.CopyToClipboard(text))

    /**
     * Add hover text.
     */
    fun hover(text: String): RichMessage = copy(hoverText = text)

    /**
     * Make text bold.
     */
    fun bold(): RichMessage = copy(bold = true)

    /**
     * Make text italic.
     */
    fun italic(): RichMessage = copy(italic = true)
}

/**
 * Available message colors for cross-platform text.
 */
enum class MessageColor {
    BLACK,
    DARK_BLUE,
    DARK_GREEN,
    DARK_AQUA,
    DARK_RED,
    DARK_PURPLE,
    GOLD,
    GRAY,
    DARK_GRAY,
    BLUE,
    GREEN,
    AQUA,
    RED,
    LIGHT_PURPLE,
    YELLOW,
    WHITE
}

/**
 * Click actions for interactive text.
 */
sealed class ClickAction {
    /**
     * Run a command when clicked.
     */
    data class RunCommand(val command: String) : ClickAction()

    /**
     * Suggest a command (fills chat input) when clicked.
     */
    data class SuggestCommand(val command: String) : ClickAction()

    /**
     * Open a URL when clicked.
     */
    data class OpenUrl(val url: String) : ClickAction()

    /**
     * Copy text to clipboard when clicked.
     */
    data class CopyToClipboard(val text: String) : ClickAction()
}
