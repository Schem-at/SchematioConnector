package io.schemat.connector.core.tools

import kotlin.math.roundToInt

/** Status wording shared by the mod's Tools panel and the plugin's chat/action bar. */
object ToolRunText {

    fun progressLine(run: ToolRun): String = when (run.status) {
        RunStatus.QUEUED -> "Queued" + (run.queuedPosition?.let { " (position $it)" } ?: "")
        RunStatus.RUNNING -> buildString {
            append("Running")
            // Zero means "not reported yet": show it as indeterminate rather than "0%".
            run.progress?.takeIf { it > 0.0 }?.let { append(" ").append((it.coerceIn(0.0, 1.0) * 100).roundToInt()).append('%') }
            run.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
        RunStatus.DONE -> "Done"
        RunStatus.CANCELLED -> "Cancelled"
        RunStatus.FAILED, RunStatus.TIMEOUT -> ToolErrors.runFailureMessage(run)
        RunStatus.UNKNOWN -> "Waiting for schemat.io"
    }

    /** Status after a result was saved to [path]. */
    fun savedMessage(toolName: String, output: RunOutput, path: String): String =
        output.quickshareError?.takeIf { it.isNotBlank() }
            ?.let { "$toolName finished, but its quick share failed (${it.trimEnd().trimEnd('.')}). Saved to $path instead." }
            ?: "$toolName finished. Saved to $path"

    /**
     * A local file name for [output]: the tool's name as a slug, then the server's file name
     * (servers often use generic names such as `schematic.schem`). Never contains a path.
     */
    fun outputFileName(toolName: String, output: RunOutput): String {
        val slug = toolName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val file = (output.filename ?: output.name).substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_").trimStart('.').ifBlank { "result" }
        return (if (slug.isEmpty()) file else "$slug-$file").take(100)
    }

    /** 0..1 for a progress bar; null while queued (no meaningful progress yet). */
    fun fraction(run: ToolRun): Float? = when {
        run.status == RunStatus.DONE -> 1f
        run.status == RunStatus.QUEUED -> null
        else -> run.progress?.takeIf { it > 0.0 }?.coerceIn(0.0, 1.0)?.toFloat()
    }
}

/**
 * Where a finished run's result goes. A schematic output that came with a quick share
 * routes through the existing quick-share load paths (server WorldEdit, local WorldEdit,
 * Litematica, file); one without is downloaded directly; other kinds are only saved.
 */
sealed class ToolResult {
    data class QuickShare(val code: String, val output: RunOutput) : ToolResult()
    data class Download(val output: RunOutput) : ToolResult()
    data class SaveOnly(val output: RunOutput) : ToolResult()
    data object None : ToolResult()

    companion object {
        fun pick(run: ToolRun): ToolResult {
            val schematic = run.outputs.firstOrNull { it.isSchematic }
            if (schematic != null) {
                return schematic.quickshareCode?.let { QuickShare(it, schematic) } ?: Download(schematic)
            }
            return run.outputs.firstOrNull()?.let { SaveOnly(it) } ?: None
        }
    }
}

/** Catalogue per actor (player uuid, or "" for the mod's own player), kept for [ttlMs]. */
class ToolCatalogueCache(
    private val ttlMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val tools: List<ToolDefinition>, val at: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun put(actor: String, tools: List<ToolDefinition>) {
        entries[actor] = Entry(tools, now())
    }

    fun get(actor: String): List<ToolDefinition>? =
        entries[actor]?.takeIf { now() - it.at <= ttlMs }?.tools

    /** The tool whose id or [ToolDefinition.commandName] is [toolId] (ignoring case); id wins. */
    fun find(actor: String, toolId: String): ToolDefinition? {
        val tools = get(actor) ?: return null
        return tools.firstOrNull { it.id.equals(toolId, ignoreCase = true) }
            ?: tools.firstOrNull { it.commandName.equals(toolId, ignoreCase = true) }
    }

    /** Names to offer for Tab completion: each tool's [ToolDefinition.commandName]. */
    fun commandNames(actor: String): List<String> = get(actor).orEmpty().map { it.commandName }.distinct()

    /**
     * The tool [toolId] for [actor], refreshing the cache via [fetch] once on a miss.
     * Success(null) means the tool is not in the actor's catalogue; a failed fetch (such as
     * the 403 for a player without the remote-tools grant) is returned as is.
     */
    suspend fun lookup(
        actor: String,
        toolId: String,
        fetch: suspend () -> io.schemat.connector.core.modapi.ApiResult<List<ToolDefinition>>,
    ): io.schemat.connector.core.modapi.ApiResult<ToolDefinition?> {
        find(actor, toolId)?.let { return io.schemat.connector.core.modapi.ApiResult.Success(it) }
        return fetch().map { tools ->
            put(actor, tools)
            find(actor, toolId)
        }
    }

    fun clear() = entries.clear()
}
