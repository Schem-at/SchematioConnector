package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImDouble
import imgui.type.ImString
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.routing.DownloadKind
import io.schemat.connector.core.routing.ParsedDownload
import io.schemat.connector.core.tools.InputKind
import io.schemat.connector.core.tools.RunRequest
import io.schemat.connector.core.tools.RunStatus
import io.schemat.connector.core.tools.ToolDefinition
import io.schemat.connector.core.tools.ToolErrors
import io.schemat.connector.core.tools.ToolForm
import io.schemat.connector.core.tools.ToolInputSpec
import io.schemat.connector.core.tools.ToolInputValue
import io.schemat.connector.core.tools.ToolResult
import io.schemat.connector.core.tools.ToolRun
import io.schemat.connector.core.tools.ToolRunOutcome
import io.schemat.connector.core.tools.ToolRunPoller
import io.schemat.connector.core.tools.ToolRunText
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.ExportSource
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.routing.DownloadRouter
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.theme.Icons
import io.schemat.connector.fabric.client.ui.widgets.ExportSources
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remote tools: pick a tool from the schemat.io catalogue, fill the form generated from its
 * inputs (schematic inputs come from the same local sources as Upload and Quick Share), run
 * it on schemat.io and load the result.
 *
 * A schematic result arrives as a quick share and is loaded through [DownloadRouter], so it
 * honours the player's destination order (server WorldEdit, WorldEdit, Litematica, file).
 * Other outputs are saved under the downloads folder.
 *
 * A run keeps going when the panel closes; reopening shows its progress.
 */
object ToolsPanel : Panel {

    override val id = "tools"

    private val LOGGER = LoggerFactory.getLogger("SchematioTools")
    private const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    private const val LIST_WIDTH = 210f

    private val services: ClientServices get() = SchematioClientMod.instance.services

    // ---- catalogue ----
    private var tools: List<ToolDefinition>? = null
    private var catalogueError: String? = null
    private val catalogueBusy = AtomicBoolean(false)
    private var freshOpen = true

    // ---- selected tool + form ----
    private var selected: ToolDefinition? = null
    private var form: ToolForm? = null
    /** Index into [runAsChoices]: null means the user's own grant. */
    private var runAsIndex = 0
    private val textBufs = HashMap<String, ImString>()
    private val schematicSources = HashMap<String, ExportSource?>()
    private var sources: List<ExportSource> = emptyList()

    // ---- run ----
    private var preparing = false
    private var run: ToolRun? = null
    private var runJob: Job? = null
    private val cancelRequested = AtomicBoolean(false)
    private var statusText: String? = null
    private var statusKind = Widgets.StatusKind.INFO
    /** The status only repeats per-input errors, so it goes once every input is fixed. */
    private var statusFromInputs = false

    fun show() {
        freshOpen = true
        PanelManager.open(this)
    }

    private fun isRunning() = runJob?.isActive == true || preparing

    // ---- render ----

    override fun render() {
        if (freshOpen) {
            freshOpen = false
            sources = ExportSources.collect()
            if (tools == null) loadCatalogue()
        }

        ImGui.setNextWindowSize(720f, 480f, ImGuiCond.FirstUseEver)
        val open = ImBoolean(true)
        if (!ImGui.begin("Tools###schematio-tools", open, ImGuiWindowFlags.None)) {
            ImGui.end()
            return
        }
        ImGuiTheme.windowTitleAccent()
        if (!open.get()) {
            PanelManager.close(id)
            ImGui.end()
            return
        }
        try {
            ImGui.beginChild("##tools-list", LIST_WIDTH, 0f, true)
            renderToolList()
            ImGui.endChild()
            ImGui.sameLine()
            ImGui.beginChild("##tools-body", 0f, 0f, false)
            renderBody()
            ImGui.endChild()
        } finally {
            ImGui.end()
        }
    }

    private fun renderToolList() {
        val list = tools
        when {
            list == null && catalogueError == null -> ImGui.textDisabled("Loading tools...")
            list == null -> {
                ImGui.pushTextWrapPos(0f)
                Widgets.statusText(catalogueError!!, Widgets.StatusKind.DANGER)
                ImGui.popTextWrapPos()
                if (Widgets.button("Retry")) loadCatalogue()
            }
            list.isEmpty() -> {
                ImGui.pushTextWrapPos(0f)
                ImGui.textDisabled("No tools are available to you. Remote tools need the tools grant on your account, or a community role with \"run remote tools\".")
                ImGui.popTextWrapPos()
            }
            else -> for (tool in list) {
                if (ImGui.selectable("${tool.name}##tool_${tool.id}", selected?.id == tool.id)) select(tool)
                tool.summary?.let { if (ImGui.isItemHovered()) ImGui.setTooltip(it) }
            }
        }
        if (list != null) {
            ImGui.spacing()
            if (Widgets.iconButton(Icons.REFRESH, "Refresh the tool list")) loadCatalogue()
        }
    }

    private fun renderBody() {
        val def = selected
        val f = form
        if (def == null || f == null) {
            if (run != null || statusText != null) renderRunState() else
                Widgets.emptyState(Icons.BOLT, "Pick a tool", "Tools run on schemat.io and load their result for you.")
            return
        }
        Widgets.h2(def.name)
        def.summary?.let {
            ImGui.pushTextWrapPos(0f)
            ImGui.textColored(ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y, ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w, it)
            ImGui.popTextWrapPos()
        }
        ImGui.separator()

        if (statusFromInputs && form?.hasErrors() == false) {
            statusText = null
            statusFromInputs = false
        }
        // Room for the run button or progress plus the wrapped status, so a long status never hides the button.
        val status = statusText
        val statusHeight = if (status == null) 0f else
            ImGui.calcTextSize("WW  $status", false, ImGui.getContentRegionAvailX()).y + ImGui.getStyle().itemSpacingY
        val footer = (ImGui.getFrameHeightWithSpacing() * 2.2f + statusHeight).coerceAtMost(ImGui.getContentRegionAvailY() * 0.6f)
        ImGui.beginChild("##tool-form", 0f, -footer, false)
        val running = isRunning()
        if (running) ImGui.beginDisabled()
        renderRunAs(def)
        for (input in f.visibleInputs()) renderInput(f, input)
        if (running) ImGui.endDisabled()
        ImGui.endChild()

        renderRunState()
        if (running) {
            if (Widgets.button("Cancel run")) cancelRequested.set(true)
        } else {
            val canRun = def.canRunAsUser || def.runAsCommunities.isNotEmpty()
            if (!canRun) ImGui.beginDisabled()
            if (Widgets.button("Run ${def.name}", accent = true)) startRun(def, f)
            if (!canRun) ImGui.endDisabled()
        }
    }

    private fun runAsChoices(def: ToolDefinition): List<String?> = def.runAsChoices

    private fun renderRunAs(def: ToolDefinition) {
        if (!def.showsRunAsPicker) return
        val choices = runAsChoices(def)
        fun choiceLabel(c: String?) = c?.let { "Community: $it" } ?: "Myself"
        runAsIndex = runAsIndex.coerceIn(0, choices.size - 1)
        label("Run as")
        ImGui.setNextItemWidth(260f)
        if (ImGui.beginCombo("##runas", choiceLabel(choices[runAsIndex]))) {
            choices.forEachIndexed { i, c -> if (ImGui.selectable(choiceLabel(c), i == runAsIndex)) runAsIndex = i }
            ImGui.endCombo()
        }
        ImGui.spacing()
    }

    private fun label(text: String) =
        ImGui.textColored(ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w, text)

    private fun renderInput(f: ToolForm, input: ToolInputSpec) {
        val key = "##in_${input.name}"
        // A checkbox carries its own label.
        if (input.kind != InputKind.BOOLEAN) label(input.label + if (input.required) " *" else "")
        when (input.kind) {
            InputKind.NUMBER -> {
                val v = ImDouble(f.number(input.name) ?: input.min ?: 0.0)
                ImGui.setNextItemWidth(160f)
                if (ImGui.inputDouble(key, v, 0.0, 0.0, input.numberFormat)) f.setNumber(input.name, v.get())
            }
            InputKind.SLIDER -> {
                val min = input.min
                val max = input.max
                if (min == null || max == null) {
                    val v = ImDouble(f.number(input.name) ?: min ?: 0.0)
                    ImGui.setNextItemWidth(160f)
                    if (ImGui.inputDouble(key, v, 0.0, 0.0, input.numberFormat)) f.setNumber(input.name, v.get())
                } else {
                    val ref = floatArrayOf((f.number(input.name) ?: min).toFloat())
                    ImGui.setNextItemWidth(260f)
                    if (ImGui.sliderFloat(key, ref, min.toFloat(), max.toFloat(), input.numberFormat)) f.setNumber(input.name, ref[0].toDouble())
                }
            }
            InputKind.BOOLEAN -> {
                val b = ImBoolean(f.bool(input.name) ?: false)
                if (ImGui.checkbox(input.label + key, b)) f.setBool(input.name, b.get())
            }
            InputKind.SELECT -> {
                val current = f.text(input.name)
                val shown = input.options.firstOrNull { it.value == current }?.label ?: current ?: "Choose..."
                ImGui.setNextItemWidth(260f)
                if (ImGui.beginCombo(key, shown)) {
                    for (opt in input.options) if (ImGui.selectable(opt.label, opt.value == current)) f.setText(input.name, opt.value)
                    ImGui.endCombo()
                }
            }
            InputKind.COLOR -> {
                val hex = f.text(input.name)?.removePrefix("#")?.toIntOrNull(16) ?: 0
                val rgb = floatArrayOf(((hex shr 16) and 0xff) / 255f, ((hex shr 8) and 0xff) / 255f, (hex and 0xff) / 255f)
                if (ImGui.colorEdit3(key, rgb)) {
                    fun c(v: Float) = Math.round(v.coerceIn(0f, 1f) * 255)
                    f.setText(input.name, "#%02x%02x%02x".format(c(rgb[0]), c(rgb[1]), c(rgb[2])))
                }
            }
            InputKind.SCHEMATIC -> renderSchematicInput(input)
            InputKind.FILE -> {
                val buf = textBufs.getOrPut(input.name) { ImString(512) }
                ImGui.setNextItemWidth(-1f)
                val hint = if (input.accept.isEmpty()) "Path to a file" else "Path to a file (${input.accept.joinToString(", ")})"
                Widgets.textField(key, buf, hint)
            }
            InputKind.TEXT, InputKind.BLOCK -> {
                val buf = textBufs.getOrPut(input.name) { ImString(f.text(input.name).orEmpty(), 512) }
                ImGui.setNextItemWidth(if (input.kind == InputKind.BLOCK) 260f else -1f)
                if (Widgets.textField(key, buf, if (input.kind == InputKind.BLOCK) "minecraft:stone" else null)) f.setText(input.name, buf.get())
            }
            InputKind.UNKNOWN -> Unit
        }
        input.description?.let { if (ImGui.isItemHovered()) ImGui.setTooltip(it) }
        f.errorFor(input.name)?.let { Widgets.statusText(it, Widgets.StatusKind.DANGER) }
        ImGui.spacing()
    }

    private fun renderSchematicInput(input: ToolInputSpec) {
        val chosen = schematicSources[input.name]
        ImGui.setNextItemWidth(-1f)
        val preview = chosen?.let { ExportSources.label(it) } ?: if (sources.isEmpty()) "No local sources" else "Choose a source..."
        if (ImGui.beginCombo("##in_${input.name}", preview)) {
            if (!input.required && ImGui.selectable("(none)", chosen == null)) schematicSources[input.name] = null
            for (src in sources) {
                if (ImGui.selectable(ExportSources.label(src) + "##src_${src.id}", chosen?.id == src.id)) schematicSources[input.name] = src
            }
            ImGui.endCombo()
        }
        if (sources.isEmpty()) {
            ImGui.textDisabled("Copy something with WorldEdit, place a Litematica schematic, or add files to your schematics folder.")
        }
    }

    private fun renderRunState() {
        val r = run
        if (r != null && !r.status.terminal) {
            val fraction = ToolRunText.fraction(r)
            if (fraction != null) ImGui.progressBar(fraction, -1f, 0f, ToolRunText.progressLine(r))
            else ImGui.textColored(ImGuiColors.INFO.x, ImGuiColors.INFO.y, ImGuiColors.INFO.z, ImGuiColors.INFO.w, ToolRunText.progressLine(r))
        } else if (preparing) {
            ImGui.textDisabled("Preparing inputs...")
        }
        statusText?.let {
            ImGui.pushTextWrapPos(0f)
            Widgets.statusText(it, statusKind)
            ImGui.popTextWrapPos()
        }
    }

    // ---- actions ----

    private fun loadCatalogue() {
        catalogueError = null
        services.call(catalogueBusy, block = { services.withReauth { services.tools.catalogue() } }) { result ->
            when (result) {
                is ApiResult.Success -> {
                    tools = result.value
                    selected?.let { sel -> result.value.firstOrNull { it.id == sel.id }?.let { if (it != sel) select(it) } ?: deselect() }
                }
                is ApiResult.Failure -> {
                    tools = null
                    catalogueError = ToolErrors.userMessage(result.error)
                }
            }
        }
    }

    private fun select(def: ToolDefinition) {
        if (isRunning()) return
        selected = def
        form = ToolForm(def)
        runAsIndex = 0
        textBufs.clear()
        schematicSources.clear()
        // The first schematic input defaults to the WorldEdit clipboard, then the first source.
        ToolForm(def).primarySchematicInput()?.let { primary ->
            schematicSources[primary.name] = sources.firstOrNull { it.kind == SourceKind.WORLDEDIT_CLIPBOARD } ?: sources.firstOrNull()
        }
        statusText = null
    }

    private fun deselect() {
        selected = null
        form = null
    }

    private fun fail(message: String) {
        preparing = false
        statusFromInputs = false
        statusText = message
        statusKind = Widgets.StatusKind.DANGER
    }

    private fun startRun(def: ToolDefinition, f: ToolForm) {
        if (isRunning()) return
        statusText = null
        statusFromInputs = false
        run = null
        cancelRequested.set(false)

        // Schematic and file inputs become uploads; everything else is already in the form.
        val visible = f.visibleInputs()
        for (input in visible.filter { it.kind == InputKind.FILE }) {
            val raw = textBufs[input.name]?.get()?.trim().orEmpty().removeSurrounding("\"")
            if (raw.isEmpty()) { f.setFile(input.name, null); continue }
            val path = runCatching { Path.of(raw) }.getOrNull()
            if (path == null || !Files.isRegularFile(path)) return fail("${input.label}: file not found")
            if (Files.size(path) > MAX_INPUT_BYTES) return fail("${input.label}: file is larger than 16 MB")
            f.setFile(input.name, ToolInputValue.Upload(Files.readAllBytes(path), path.fileName.toString()))
        }
        val pending = visible.filter { it.kind == InputKind.SCHEMATIC }.mapNotNull { input -> schematicSources[input.name]?.let { input to it } }
        visible.filter { it.kind == InputKind.SCHEMATIC && schematicSources[it.name] == null }.forEach { f.setFile(it.name, null) }

        preparing = true
        capture(pending, f) {
            val errors = f.validate()
            if (errors.isNotEmpty()) {
                val (name, message) = errors.entries.first()
                f.showErrors(errors)
                fail("${def.input(name)?.label ?: name}: $message")
                statusFromInputs = true
                return@capture
            }
            val community = runAsChoices(def).getOrNull(runAsIndex)
            launchRun(def, f, RunRequest(f.buildInputs(), community, deliver = listOf("download", "quickshare")))
        }
    }

    /** Main thread: export each chosen source to bytes, one after another, then [done]. */
    private fun capture(pending: List<Pair<ToolInputSpec, ExportSource>>, f: ToolForm, done: () -> Unit) {
        if (pending.isEmpty()) return done()
        val (input, src) = pending.first()
        val format = ExportSources.formatFor(src)
        fun accept(bytes: ByteArray?, error: String?) {
            when {
                bytes == null -> fail("${input.label}: ${error ?: "could not read ${ExportSources.label(src)}"}")
                bytes.size > MAX_INPUT_BYTES -> fail("${input.label}: the schematic is larger than 16 MB")
                else -> {
                    f.setFile(input.name, ToolInputValue.Upload(bytes, "${input.name}.$format"))
                    capture(pending.drop(1), f, done)
                }
            }
        }
        when (src.kind) {
            SourceKind.LOCAL_FILE -> {
                val bytes = runCatching { Files.readAllBytes(Path.of(src.id)) }
                accept(bytes.getOrNull(), bytes.exceptionOrNull()?.message)
            }
            SourceKind.WORLDEDIT_CLIPBOARD -> Bridges.worldEdit.clipboardToBytes { bytes, error -> accept(bytes, error) }
            SourceKind.PLACEMENT, SourceKind.AREA_SELECTION -> Bridges.litematica.exportToBytes(src) { bytes, error -> accept(bytes, error) }
        }
    }

    private fun launchRun(def: ToolDefinition, f: ToolForm, request: RunRequest) {
        val svc = services
        runJob = svc.scope.launch {
            try {
                val started = svc.withReauth { svc.tools.startRun(def.id, request) }
                val first = when (started) {
                    is ApiResult.Failure -> return@launch svc.onMainThread {
                        preparing = false
                        val error = started.error
                        if (error is ApiError.Validation) {
                            f.applyServerErrors(error.fieldErrors)
                            val general = error.fieldErrors["_"]?.firstOrNull()
                            fail(general ?: "Some inputs need fixing.")
                            statusFromInputs = general == null && f.hasErrors()
                        } else {
                            fail(ToolErrors.userMessage(error))
                        }
                    }
                    is ApiResult.Success -> started.value
                }
                svc.onMainThread { preparing = false; run = first }
                val outcome = ToolRunPoller(svc.tools).await(first.id, def.timeoutSeconds, isCancelled = { cancelRequested.get() }) { update ->
                    svc.onMainThread { run = update }
                }
                finish(def, outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOGGER.error("Tool run of {} failed", def.id, e)
                svc.onMainThread { fail("The tool run failed: ${e.message ?: "unexpected error"}") }
            }
        }
    }

    /** Background: turn the outcome into a loaded or saved result. */
    private suspend fun finish(def: ToolDefinition, outcome: ToolRunOutcome) {
        val svc = services
        fun say(text: String, kind: Widgets.StatusKind) = svc.onMainThread { statusText = text; statusKind = kind }
        when (outcome) {
            is ToolRunOutcome.Failed -> say(ToolErrors.userMessage(outcome.error), Widgets.StatusKind.DANGER)
            is ToolRunOutcome.StillRunning -> say("${def.name} is still running on schemat.io. Stopped waiting.", Widgets.StatusKind.WARNING)
            is ToolRunOutcome.Finished -> {
                val r = outcome.run
                if (r.status != RunStatus.DONE) {
                    return say(ToolRunText.progressLine(r), if (r.status == RunStatus.CANCELLED) Widgets.StatusKind.INFO else Widgets.StatusKind.DANGER)
                }
                when (val result = ToolResult.pick(r)) {
                    is ToolResult.QuickShare -> svc.onMainThread {
                        statusText = "${def.name} finished. Loading the result..."
                        statusKind = Widgets.StatusKind.INFO
                        DownloadRouter.route(DownloadKind.QUICK_SHARE, ParsedDownload.Ok(result.code, null, null, label = def.name)) { text, isError ->
                            statusText = text
                            statusKind = if (isError) Widgets.StatusKind.DANGER else Widgets.StatusKind.SUCCESS
                        }
                    }
                    is ToolResult.Download -> save(def, r, result.output)
                    is ToolResult.SaveOnly -> save(def, r, result.output)
                    ToolResult.None -> say("${def.name} finished without a result.", Widgets.StatusKind.WARNING)
                }
            }
        }
    }

    private suspend fun save(def: ToolDefinition, r: ToolRun, output: io.schemat.connector.core.tools.RunOutput) {
        val svc = services
        when (val bytes = svc.withReauth { svc.tools.downloadOutput(r, output) }) {
            is ApiResult.Failure -> svc.onMainThread { fail(ToolErrors.userMessage(bytes.error)) }
            is ApiResult.Success -> {
                val saved = runCatching { writeOutput(ToolRunText.outputFileName(def.name, output), bytes.value) }
                svc.onMainThread {
                    saved.fold(
                        onSuccess = {
                            statusText = ToolRunText.savedMessage(def.name, output, shownPath(it))
                            statusKind = if (output.quickshareError.isNullOrBlank()) Widgets.StatusKind.SUCCESS else Widgets.StatusKind.WARNING
                        },
                        onFailure = { fail("Could not save the result: ${it.message ?: "unexpected error"}") },
                    )
                }
            }
        }
    }

    /** Paths inside the game folder are shown relative to it; long absolute paths crowd the status line. */
    private fun shownPath(path: Path): String {
        val game = runCatching { net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize() }.getOrNull()
        val abs = path.toAbsolutePath().normalize()
        return if (game != null && abs.startsWith(game)) game.relativize(abs).toString() else abs.toString()
    }

    /** Writes into `<downloads>/tools`, never replacing an existing file. */
    private fun writeOutput(filename: String, bytes: ByteArray): Path {
        val dir = DownloadRouter.downloadDirectory().resolve("tools")
        Files.createDirectories(dir)
        val safe = filename.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(100).ifBlank { "result" }
        val stem = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var target = dir.resolve(safe)
        var n = 1
        while (Files.exists(target)) target = dir.resolve("$stem-${n++}$ext")
        val staging = Files.createTempFile(dir, ".schematio-", ".tmp")
        try {
            Files.write(staging, bytes)
            return Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    override fun onClose() {
        // The run keeps going; the next open refreshes sources (and the catalogue if it failed).
        if (tools == null) catalogueError = null
    }
}
