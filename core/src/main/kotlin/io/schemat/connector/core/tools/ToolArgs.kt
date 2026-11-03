package io.schemat.connector.core.tools

/**
 * `/schematio tool <id> [key=value ...]` for the server plugins: parses chat arguments
 * against a tool's inputs (through [ToolForm], so the rules match the mod's form) and
 * offers tab completions.
 *
 * Values: numbers, `true`/`false` (also yes/no/on/off), select values, block ids
 * (`stone` becomes `minecraft:stone`), `#rrggbb` colours; quote a value to include spaces
 * (`note="two words"`). Schematic inputs take `clipboard`, a schemat.io short id, or
 * `qs:<code>`; the primary schematic input defaults to the clipboard
 * ([ToolInputValue.ClipboardPlaceholder], which the caller replaces with its bytes).
 * File inputs (images and the like) cannot be given from chat.
 */
object ToolArgs {

    sealed class Result {
        data class Ok(val inputs: Map<String, ToolInputValue>) : Result()
        data class Error(val message: String) : Result()
    }

    private const val CLIPBOARD = "clipboard"
    private const val QUICKSHARE_PREFIX = "qs:"

    fun parse(def: ToolDefinition, args: List<String>): Result {
        val form = ToolForm(def)
        for (token in joinQuoted(args)) {
            val eq = token.indexOf('=')
            if (eq <= 0) return Result.Error("Expected key=value, got '$token'.")
            val key = token.substring(0, eq)
            val value = token.substring(eq + 1).let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it }
            val input = def.input(key)?.takeIf { it.kind != InputKind.UNKNOWN }
                ?: return Result.Error("Unknown input '$key'. Inputs: ${keys(def).joinToString(", ")}.")
            when (input.kind) {
                InputKind.NUMBER, InputKind.SLIDER -> {
                    val n = value.trim().toDoubleOrNull() ?: return Result.Error("${input.name} must be a number.")
                    form.setNumber(input.name, n)
                }
                InputKind.BOOLEAN -> {
                    val b = ToolValues.parseBool(value) ?: return Result.Error("${input.name} must be true or false.")
                    form.setBool(input.name, b)
                }
                InputKind.SCHEMATIC -> form.setFile(input.name, schematicValue(value))
                InputKind.FILE -> return Result.Error(fileMessage(input))
                else -> form.setText(input.name, value)
            }
        }
        form.primarySchematicInput()?.let { primary ->
            if (form.file(primary.name) == null) form.setFile(primary.name, ToolInputValue.ClipboardPlaceholder)
        }
        val errors = form.validate()
        val (name, message) = errors.entries.firstOrNull()?.toPair() ?: return Result.Ok(form.buildInputs())
        val input = def.input(name)!!
        return Result.Error(if (input.kind == InputKind.FILE) fileMessage(input) else "$name: $message")
    }

    /** Completions for the last element of [args] (the word being typed). */
    fun complete(def: ToolDefinition, args: List<String>): List<String> {
        val current = args.lastOrNull().orEmpty()
        val eq = current.indexOf('=')
        if (eq > 0) {
            val key = current.substring(0, eq)
            val input = def.input(key) ?: return emptyList()
            val values = when (input.kind) {
                InputKind.SELECT -> input.options.map { it.value }
                InputKind.BOOLEAN -> listOf("false", "true")
                InputKind.SCHEMATIC -> listOf(CLIPBOARD)
                InputKind.BLOCK -> listOfNotNull(input.default?.takeIf { it.isJsonPrimitive }?.asString)
                else -> emptyList()
            }
            return values.map { "$key=$it" }.filter { it.startsWith(current, ignoreCase = true) }.sorted()
        }
        val used = args.dropLast(1).mapNotNull { it.substringBefore('=', "").ifEmpty { null } }.toSet()
        return keys(def).filter { it !in used }.map { "$it=" }.filter { it.startsWith(current, ignoreCase = true) }.sorted()
    }

    /** Input names settable from chat. */
    private fun keys(def: ToolDefinition): List<String> =
        def.inputs.filter { it.kind != InputKind.UNKNOWN && it.kind != InputKind.FILE }.map { it.name }

    private fun schematicValue(raw: String): ToolInputValue {
        val v = raw.trim()
        return when {
            v.equals(CLIPBOARD, ignoreCase = true) -> ToolInputValue.ClipboardPlaceholder
            v.startsWith(QUICKSHARE_PREFIX, ignoreCase = true) -> ToolInputValue.QuickShareRef(v.substring(QUICKSHARE_PREFIX.length))
            else -> ToolInputValue.SchematicRef(v)
        }
    }

    private fun fileMessage(input: ToolInputSpec) =
        "${input.name} is a file input and can't be set from chat. Run this tool from the Tools panel in the Schematio mod."

    /** Re-join `key="two` `words"` (split by the command framework on spaces) into one token. */
    private fun joinQuoted(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var open: StringBuilder? = null
        for (arg in args) {
            if (arg.isEmpty()) continue
            val acc = open
            if (acc != null) {
                acc.append(' ').append(arg)
                if (arg.endsWith('"')) { out += acc.toString(); open = null }
                continue
            }
            val value = arg.substringAfter('=', "")
            if (value.startsWith('"') && !(value.length >= 2 && value.endsWith('"'))) open = StringBuilder(arg) else out += arg
        }
        open?.let { out += it.toString() }
        return out
    }
}
