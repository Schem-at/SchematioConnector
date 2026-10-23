package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.utils.UIMode
import io.schemat.schematioConnector.utils.UIModeResolver

/**
 * Parsed arguments of `/schematio quickshareget <accessCode|url> [password] [--chat|--dialog]`.
 *
 * The password may contain spaces (the 1.6 client forwards it verbatim), so every word after
 * the code is joined back with single spaces. UI flags are only recognised before the code or
 * after the password; a flag-like word between them is part of the password. A blank password
 * (an empty dialog field) means "no password".
 */
data class QuickShareGetArgs(val flagMode: UIMode?, val code: String?, val password: String?) {

    /** Never prints the password, so the parsed args are safe to log. */
    override fun toString(): String =
        "QuickShareGetArgs(flagMode=$flagMode, code=$code, password=${if (password == null) "none" else "<redacted>"})"

    companion object {
        fun parse(args: Array<out String>): QuickShareGetArgs {
            var flagMode: UIMode? = null
            var start = 0
            var end = args.size
            while (start < end) {
                flagMode = flagModeOf(args[start]) ?: break
                start++
            }
            while (end > start) {
                val mode = flagModeOf(args[end - 1]) ?: break
                // The last flag on the line wins, as with UIModeResolver.parseFlags.
                if (end == args.size) flagMode = mode
                end--
            }
            if (start >= end) return QuickShareGetArgs(flagMode, null, null)
            val password = args.copyOfRange(start + 1, end).joinToString(" ").takeUnless { it.isBlank() }
            return QuickShareGetArgs(flagMode, args[start], password)
        }

        private fun flagModeOf(arg: String): UIMode? = when (arg.lowercase()) {
            UIModeResolver.FLAG_CHAT, "-c" -> UIMode.CHAT
            UIModeResolver.FLAG_DIALOG, "-d" -> UIMode.DIALOG
            else -> null
        }
    }
}
