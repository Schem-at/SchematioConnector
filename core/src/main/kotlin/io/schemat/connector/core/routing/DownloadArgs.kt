package io.schemat.connector.core.routing

import io.schemat.connector.core.validation.InputValidator
import io.schemat.connector.core.validation.ValidationResult

sealed interface ParsedDownload {
    data class Ok(val id: String, val password: String?, val filter: DestinationFilter?) : ParsedDownload

    data class Error(val message: String) : ParsedDownload
}

/** Parses the argument string of `/schematio download` and `/schematio quickshareget`. */
object DownloadArgs {

    private val TOOL_CONFLICT = ParsedDownload.Error("Choose one of --we, --lm or --file.")
    private val SCHEMATIC_URL = Regex("^https?://[^/]+/schematics/([^/?#]+)")

    fun parse(raw: String, quickShare: Boolean): ParsedDownload {
        var tool: Tool? = null
        var side: Side? = null
        val positional = mutableListOf<String>()

        for (token in raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            if (!token.startsWith("--")) {
                positional += token
                continue
            }
            when (token) {
                "--worldedit", "--we" -> {
                    if (tool != null && tool != Tool.WORLDEDIT) return TOOL_CONFLICT
                    tool = Tool.WORLDEDIT
                }
                "--litematica", "--lm" -> {
                    if (tool != null && tool != Tool.LITEMATICA) return TOOL_CONFLICT
                    tool = Tool.LITEMATICA
                }
                "--file" -> {
                    if (tool != null && tool != Tool.FILE) return TOOL_CONFLICT
                    tool = Tool.FILE
                }
                "--server" -> side = Side.SERVER
                "--local" -> side = Side.LOCAL
                else -> return ParsedDownload.Error(
                    "Unknown flag $token. Use --we, --lm, --file, --server or --local."
                )
            }
        }

        if (side == Side.SERVER && (tool == Tool.FILE || tool == Tool.LITEMATICA)) {
            return ParsedDownload.Error("Only WorldEdit can load on the server.")
        }

        var id = positional.firstOrNull()
            ?: return ParsedDownload.Error(
                if (quickShare) "Give a quick share code." else "Give a schematic ID or schemat.io link."
            )
        if (quickShare) {
            val v = InputValidator.validateQuickShareCode(id)
            if (v is ValidationResult.Invalid) return ParsedDownload.Error(v.message)
        } else {
            positional.getOrNull(1)?.let { extra ->
                return ParsedDownload.Error("Unexpected argument '$extra'. Use flags: --we, --lm, --file, --server, --local.")
            }
            SCHEMATIC_URL.find(id)?.let { id = it.groupValues[1] }
        }
        // Every positional after the code is the password, so passwords with spaces survive
        // the whitespace split (runs of whitespace join as a single space).
        val password = if (quickShare) {
            positional.drop(1).joinToString(" ").takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val filter = if (side == null && tool == null) null else DestinationFilter(side, tool)
        return ParsedDownload.Ok(id, password, filter)
    }
}
