package io.schemat.connector.core.tools

import com.google.gson.JsonElement
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Editable values for one tool's inputs, seeded from the catalogue defaults. Pure: the
 * mod's ImGui form and the plugin's `/schematio tool` parser both drive it, then send
 * [buildInputs]. Hidden inputs (a failing `when`) and [InputKind.UNKNOWN] kinds are never
 * shown, validated or sent.
 */
class ToolForm(val definition: ToolDefinition) {

    private val numbers = mutableMapOf<String, Double?>()
    private val texts = mutableMapOf<String, String>()
    private val bools = mutableMapOf<String, Boolean>()
    private val files = mutableMapOf<String, ToolInputValue?>()
    private val serverErrors = mutableMapOf<String, String>()

    init {
        for (input in definition.inputs) {
            val d = input.default
            when (input.kind) {
                InputKind.NUMBER, InputKind.SLIDER -> numbers[input.name] = d.numberOrNull() ?: input.min
                InputKind.BOOLEAN -> bools[input.name] = d.boolOrNull() ?: false
                InputKind.SELECT -> texts[input.name] = d.stringOrNull() ?: input.options.firstOrNull()?.value ?: ""
                InputKind.TEXT, InputKind.BLOCK, InputKind.COLOR -> texts[input.name] = d.stringOrNull() ?: ""
                InputKind.FILE, InputKind.SCHEMATIC -> files[input.name] = null
                InputKind.UNKNOWN -> {}
            }
        }
    }

    fun number(name: String): Double? = numbers[name]
    fun text(name: String): String? = texts[name]
    fun bool(name: String): Boolean? = bools[name]
    fun file(name: String): ToolInputValue? = files[name]

    fun setNumber(name: String, value: Double) { numbers[name] = value; serverErrors.remove(name) }
    fun setText(name: String, value: String) { texts[name] = value; serverErrors.remove(name) }
    fun setBool(name: String, value: Boolean) { bools[name] = value; serverErrors.remove(name) }
    fun setFile(name: String, value: ToolInputValue?) { files[name] = value; serverErrors.remove(name) }

    /** Inputs to show now, in catalogue order. */
    fun visibleInputs(): List<ToolInputSpec> =
        definition.inputs.filter { it.kind != InputKind.UNKNOWN && conditionHolds(it) }

    private fun conditionHolds(input: ToolInputSpec): Boolean {
        val field = input.whenField ?: return true
        val expected = input.whenEquals ?: return true
        val controller = definition.input(field) ?: return true
        if (!expected.isJsonPrimitive) return true
        val p = expected.asJsonPrimitive
        return when {
            p.isBoolean -> bools[controller.name] == p.asBoolean
            p.isNumber -> numbers[controller.name] == p.asDouble
            else -> texts[controller.name] == p.asString
        }
    }

    /** Client-side problems with the visible inputs, keyed by input name. Empty = OK to run. */
    fun validate(): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        for (input in visibleInputs()) {
            problem(input)?.let { errors[input.name] = it }
        }
        return errors
    }

    private fun problem(input: ToolInputSpec): String? = when (input.kind) {
        InputKind.FILE, InputKind.SCHEMATIC ->
            if (input.required && files[input.name] == null) "Required" else null
        InputKind.NUMBER, InputKind.SLIDER -> {
            val v = numbers[input.name]
            when {
                v == null -> if (input.required) "Required" else null
                !ToolValues.inRange(ToolValues.snap(v, input), input) -> ToolValues.rangeMessage(input)
                else -> null
            }
        }
        InputKind.SELECT -> {
            val v = texts[input.name].orEmpty()
            if (input.options.isNotEmpty() && input.options.none { it.value == v }) {
                "Pick one of: " + input.options.joinToString(", ") { it.value }
            } else {
                null
            }
        }
        InputKind.BLOCK -> {
            val v = texts[input.name].orEmpty()
            when {
                v.isBlank() -> if (input.required) "Required" else null
                ToolValues.normalizeBlock(v) == null -> "Not a block id (e.g. minecraft:stone)"
                else -> null
            }
        }
        InputKind.COLOR -> {
            val v = texts[input.name].orEmpty()
            when {
                v.isBlank() -> if (input.required) "Required" else null
                ToolValues.normalizeColor(v) == null -> "Not a colour (use #rrggbb)"
                else -> null
            }
        }
        InputKind.TEXT -> if (input.required && texts[input.name].isNullOrBlank()) "Required" else null
        InputKind.BOOLEAN, InputKind.UNKNOWN -> null
    }

    /**
     * The run request's `inputs`: visible inputs only, numbers snapped to their step,
     * block ids namespaced, colours lowercase; empty optional values are omitted.
     */
    fun buildInputs(): Map<String, ToolInputValue> {
        val out = linkedMapOf<String, ToolInputValue>()
        for (input in visibleInputs()) {
            val value: ToolInputValue? = when (input.kind) {
                InputKind.FILE, InputKind.SCHEMATIC -> files[input.name]
                InputKind.NUMBER, InputKind.SLIDER -> numbers[input.name]?.let { ToolInputValue.Number(ToolValues.snap(it, input)) }
                InputKind.BOOLEAN -> ToolInputValue.Bool(bools[input.name] ?: false)
                InputKind.SELECT -> texts[input.name]?.takeIf { it.isNotEmpty() }?.let { ToolInputValue.Text(it) }
                InputKind.BLOCK -> texts[input.name]?.let { ToolValues.normalizeBlock(it) }?.let { ToolInputValue.Text(it) }
                InputKind.COLOR -> texts[input.name]?.let { ToolValues.normalizeColor(it) }?.let { ToolInputValue.Text(it) }
                InputKind.TEXT -> texts[input.name]?.takeIf { it.isNotBlank() || input.required }?.let { ToolInputValue.Text(it) }
                InputKind.UNKNOWN -> null
            }
            if (value != null) out[input.name] = value
        }
        return out
    }

    /** Attach a 422's field errors (bare input names; `_` and unknown keys are ignored here). */
    fun applyServerErrors(errors: Map<String, List<String>>) {
        for ((field, messages) in errors) {
            if (definition.input(field) != null) messages.firstOrNull()?.let { serverErrors[field] = it }
        }
    }

    /** Attach client-side [validate] errors to their inputs, like the server's 422 errors. */
    fun showErrors(errors: Map<String, String>) {
        serverErrors.putAll(errors)
    }

    /** The error shown under [name] (server 422 or [showErrors]), cleared as soon as the value changes. */
    fun errorFor(name: String): String? = serverErrors[name]

    /** True while any input still shows an error. */
    fun hasErrors(): Boolean = serverErrors.isNotEmpty()

    /** The input a "run on my selection / clipboard" action fills: first required schematic, else first schematic. */
    fun primarySchematicInput(): ToolInputSpec? {
        val schematics = definition.inputs.filter { it.kind == InputKind.SCHEMATIC }
        return schematics.firstOrNull { it.required } ?: schematics.firstOrNull()
    }
}

/** Value rules shared by [ToolForm] and [ToolArgs]. */
internal object ToolValues {
    private val BLOCK_ID = Regex("""^[a-z0-9_.-]+(:[a-z0-9_./-]+)?(\[[a-z0-9_=,]+])?$""")
    private val HEX_COLOR = Regex("""^#[0-9a-f]{6}$""")

    /** `stone` -> `minecraft:stone`; null when not a valid block id. */
    fun normalizeBlock(raw: String): String? {
        val v = raw.trim().lowercase()
        if (!BLOCK_ID.matches(v)) return null
        val bracket = v.indexOf('[')
        val id = if (bracket >= 0) v.substring(0, bracket) else v
        return if (':' in id) v else "minecraft:$v"
    }

    fun normalizeColor(raw: String): String? = raw.trim().lowercase().takeIf { HEX_COLOR.matches(it) }

    /** Snap to the input's step (from min, or 0), trimming float noise. */
    fun snap(value: Double, input: ToolInputSpec): Double {
        val step = input.step?.takeIf { it > 0 } ?: return value
        val base = input.min ?: 0.0
        val snapped = base + Math.round((value - base) / step) * step
        return BigDecimal(snapped).setScale(10, RoundingMode.HALF_UP).toDouble()
    }

    fun inRange(value: Double, input: ToolInputSpec): Boolean =
        (input.min == null || value >= input.min) && (input.max == null || value <= input.max)

    fun rangeMessage(input: ToolInputSpec): String = when {
        input.min != null && input.max != null -> "Must be between ${fmt(input.min)} and ${fmt(input.max)}"
        input.min != null -> "Must be at least ${fmt(input.min)}"
        else -> "Must be at most ${fmt(input.max!!)}"
    }

    fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    fun parseBool(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true", "yes", "on", "1", "y" -> true
        "false", "no", "off", "0", "n" -> false
        else -> null
    }
}

private fun JsonElement?.numberOrNull(): Double? =
    this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

private fun JsonElement?.boolOrNull(): Boolean? =
    this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

private fun JsonElement?.stringOrNull(): String? =
    this?.takeIf { it.isJsonPrimitive }?.asString
