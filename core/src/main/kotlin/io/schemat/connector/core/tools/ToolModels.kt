package io.schemat.connector.core.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetObject
import io.schemat.connector.core.json.safeGetString

/*
 * Remote tool calling (contract v1): the catalogue (`GET /api/v1/tools`) and runs
 * (`/api/v1/tools/{tool}/runs`). Tools are Flow graphs executed on schemat.io; the
 * catalogue describes each tool's typed inputs so clients build the form generically.
 */

/** Input kinds from the catalogue; a kind this client does not know parses as [UNKNOWN] and is skipped. */
enum class InputKind {
    TEXT, NUMBER, SLIDER, BOOLEAN, SELECT, BLOCK, COLOR, FILE, SCHEMATIC, UNKNOWN;

    companion object {
        fun parse(raw: String?): InputKind =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

data class SelectOption(val value: String, val label: String)

/**
 * One tool input. [whenField]/[whenEquals] hide the input unless another input currently
 * equals the given value. [min]/[max]/[step] apply to numbers and sliders, [options] to
 * selects, [accept]/[maxKb] to files.
 */
data class ToolInputSpec(
    val name: String,
    val label: String,
    val kind: InputKind,
    val description: String? = null,
    val default: JsonElement? = null,
    val required: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: List<SelectOption> = emptyList(),
    val accept: List<String> = emptyList(),
    val maxKb: Int? = null,
    val whenField: String? = null,
    val whenEquals: JsonElement? = null,
) {
    /** printf format for a number field: as many decimals as the step, bounds and default use (at most 6). */
    val numberFormat: String
        get() {
            val values = listOfNotNull(step, min, max, default?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble)
            val decimals = values.maxOfOrNull { java.math.BigDecimal.valueOf(it).stripTrailingZeros().scale().coerceAtLeast(0) } ?: 0
            return "%.${decimals.coerceAtMost(6)}f"
        }

    companion object {
        fun fromJson(o: JsonObject): ToolInputSpec? {
            val name = o.safeGetString("name")?.takeIf { it.isNotBlank() } ?: return null
            val condition = o.safeGetObject("when")
            return ToolInputSpec(
                name = name,
                label = o.safeGetString("label")?.takeIf { it.isNotBlank() } ?: name,
                kind = InputKind.parse(o.safeGetString("kind")),
                description = o.safeGetString("description"),
                default = o.get("default")?.takeUnless { it.isJsonNull },
                required = o.bool("required"),
                min = o.double("min"),
                max = o.double("max"),
                step = o.double("step"),
                options = o.safeGetArray("options").mapNotNull { option ->
                    when {
                        option.isJsonObject -> option.asJsonObject.let { opt ->
                            val value = opt.safeGetString("value") ?: return@let null
                            SelectOption(value, opt.safeGetString("label") ?: value)
                        }
                        option.isJsonPrimitive -> SelectOption(option.asString, option.asString)
                        else -> null
                    }
                },
                accept = o.safeGetArray("accept").mapNotNull { if (it.isJsonPrimitive) it.asString else null },
                maxKb = o.double("max_kb")?.toInt(),
                whenField = condition.safeGetString("field"),
                whenEquals = condition?.get("equals")?.takeUnless { it.isJsonNull },
            )
        }
    }
}

data class ToolOutputSpec(val name: String, val kind: String)

/** One catalogue entry. [canRunAs] holds `"user"` and/or `"community:<slug>"`. */
data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String?,
    val icon: String?,
    val version: String?,
    val timeoutSeconds: Int?,
    val inputs: List<ToolInputSpec>,
    val outputs: List<ToolOutputSpec>,
    val canRunAs: List<String>,
) {
    fun input(name: String): ToolInputSpec? = inputs.firstOrNull { it.name == name }

    /**
     * What a player types for this tool in `/schematio tool <name>`: the name, lowercased, with
     * spaces and punctuation as dashes ("ROM Generator" -> "rom-generator"). Published user
     * flows have a UUID [id]; this spares typing it. Falls back to [id] for a blank name.
     */
    val commandName: String
        get() = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { id }

    /** The description to show, or null when the author left it blank. */
    val summary: String?
        get() = description?.trim()?.takeIf { it.isNotEmpty() }

    /** Community slugs this caller may run the tool as (quota charged to the community). */
    val runAsCommunities: List<String>
        get() = canRunAs.filter { it.startsWith(COMMUNITY_PREFIX) }.map { it.removePrefix(COMMUNITY_PREFIX) }

    /** True when the caller may run it on their own (user) grant. */
    val canRunAsUser: Boolean get() = "user" in canRunAs

    /** Who a run can be charged to: null is the caller themselves, then community slugs. */
    val runAsChoices: List<String?>
        get() = (if (canRunAsUser) listOf<String?>(null) else emptyList()) + runAsCommunities

    /** Show the picker whenever a community would pay, even as the only choice, so a run is never charged silently. */
    val showsRunAsPicker: Boolean get() = runAsCommunities.isNotEmpty()

    companion object {
        private const val COMMUNITY_PREFIX = "community:"

        /** The `{"data": [...]}` catalogue; malformed entries are skipped. */
        fun listFromJson(json: String): List<ToolDefinition> {
            val root = parseObject(json) ?: return emptyList()
            return root.safeGetArray("data").mapNotNull { if (it.isJsonObject) fromJson(it.asJsonObject) else null }
        }

        fun fromJson(o: JsonObject): ToolDefinition? {
            val id = o.safeGetString("id")?.takeIf { it.isNotBlank() } ?: return null
            return ToolDefinition(
                id = id,
                name = o.safeGetString("name") ?: id,
                description = o.safeGetString("description"),
                icon = o.safeGetString("icon"),
                version = o.get("version")?.takeIf { it.isJsonPrimitive }?.asString,
                timeoutSeconds = o.double("timeout")?.toInt(),
                inputs = o.safeGetArray("inputs").mapNotNull { if (it.isJsonObject) ToolInputSpec.fromJson(it.asJsonObject) else null },
                outputs = o.safeGetArray("outputs").mapNotNull { out ->
                    if (!out.isJsonObject) return@mapNotNull null
                    val name = out.asJsonObject.safeGetString("name") ?: return@mapNotNull null
                    ToolOutputSpec(name, out.asJsonObject.safeGetString("kind") ?: "schematic")
                },
                canRunAs = o.safeGetArray("can_run_as").mapNotNull { if (it.isJsonPrimitive) it.asString else null },
            )
        }
    }
}

enum class RunStatus(val terminal: Boolean) {
    QUEUED(false), RUNNING(false), DONE(true), FAILED(true), TIMEOUT(true), CANCELLED(true), UNKNOWN(false);

    companion object {
        fun parse(raw: String?): RunStatus =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** One run output; [kind] is schematic, image, text or json. [quickshareCode] is set when delivered as a quick share. */
data class RunOutput(
    val name: String,
    val kind: String,
    val format: String?,
    val filename: String?,
    val size: Long?,
    val quickshareCode: String?,
    /** Why a requested quick share was not created (contract v1.2); the output stays downloadable. */
    val quickshareError: String? = null,
) {
    val isSchematic: Boolean get() = kind.equals("schematic", ignoreCase = true)
}

/** A tool run. [error]/[errorCode] come from the contract's `error: {code, message}`. */
data class ToolRun(
    val id: String,
    val tool: String?,
    val status: RunStatus,
    val progress: Double?,
    val message: String?,
    val queuedPosition: Int?,
    val error: String?,
    val errorCode: String?,
    val outputs: List<RunOutput>,
) {
    companion object {
        fun fromJson(o: JsonObject): ToolRun? {
            val id = o.safeGetString("id")?.takeIf { it.isNotBlank() } ?: return null
            val error = o.get("error")
            val (errorCode, errorMessage) = when {
                error == null || error.isJsonNull -> null to null
                error.isJsonObject -> error.asJsonObject.let { it.safeGetString("code") to (it.safeGetString("message") ?: it.safeGetString("code")) }
                error.isJsonPrimitive -> null to error.asString
                else -> null to null
            }
            return ToolRun(
                id = id,
                tool = o.safeGetString("tool"),
                status = RunStatus.parse(o.safeGetString("status")),
                progress = o.double("progress"),
                message = o.safeGetString("message"),
                queuedPosition = o.double("queued_position")?.toInt(),
                error = errorMessage,
                errorCode = errorCode,
                outputs = o.safeGetArray("outputs").mapNotNull { out ->
                    if (!out.isJsonObject) return@mapNotNull null
                    val oo = out.asJsonObject
                    val name = oo.safeGetString("name") ?: return@mapNotNull null
                    RunOutput(
                        name = name,
                        kind = oo.safeGetString("kind") ?: "schematic",
                        format = oo.safeGetString("format"),
                        filename = oo.safeGetString("filename"),
                        size = oo.double("size")?.toLong(),
                        quickshareCode = oo.safeGetObject("quickshare").safeGetString("code"),
                        quickshareError = oo.safeGetString("quickshare_error"),
                    )
                },
            )
        }
    }
}

internal fun parseObject(json: String?): JsonObject? = try {
    json?.let { JsonParser.parseString(it) }?.takeIf { it.isJsonObject }?.asJsonObject
} catch (_: Exception) {
    null
}

private fun JsonObject.double(key: String): Double? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
