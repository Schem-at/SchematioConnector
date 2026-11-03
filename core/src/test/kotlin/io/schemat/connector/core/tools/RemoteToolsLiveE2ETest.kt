package io.schemat.connector.core.tools

import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.SchematioApi
import io.schemat.connector.core.modapi.transport.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live end-to-end run of the connector's remote-tools client against a real schemat.io
 * (the local `schemati.test` stack). Skipped unless `SCHEMATIO_BASE_URL` is set; never
 * prints tokens. Each path appends PASS/FAIL lines to `E2E_REPORT` (or stdout).
 *
 * Paths mirror the shipped code: the plugin's chat command ([ToolArgs] with the clipboard
 * placeholder replaced by `.schem` bytes, community token plus actor uuid), the mod's panel
 * ([ToolForm] defaults, user grant and "Run as" community), quick-share delivery opened the
 * way the mod's download router does (`POST /schematics/{code}/download`), and cancel.
 */
@EnabledIfEnvironmentVariable(named = "SCHEMATIO_BASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RemoteToolsLiveE2ETest {

    private companion object {
        const val PACE_MS = 6_000L
    }

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fail("missing env $name")

    private val base = env("SCHEMATIO_BASE_URL").trimEnd('/') + "/api/v1"
    private val transport = HttpTransport(base, Logger.getLogger("e2e"), trustAllCertificates = true)
    private val report = StringBuilder()
    private var failures = 0

    private val stone: ByteArray = listOf("../bukkit/src/test/resources/schematic/single_stone.schem", "bukkit/src/test/resources/schematic/single_stone.schem")
        .map(::File).first { it.exists() }.readBytes()

    private val png: ByteArray = ByteArrayOutputStream().also { out ->
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 16) for (y in 0 until 16) img.setRGB(x, y, if ((x / 4 + y / 4) % 2 == 0) 0xAA0000 else 0x55FF55)
        ImageIO.write(img, "png", out)
    }.toByteArray()

    /** A unit cube in Wavefront OBJ. */
    private val cubeObj: ByteArray = buildString {
        for (x in 0..1) for (y in 0..1) for (z in 0..1) appendLine("v $x $y $z")
        for (f in listOf("1 3 4 2", "5 6 8 7", "1 2 6 5", "3 7 8 4", "1 5 7 3", "2 4 8 6")) appendLine("f $f")
    }.toByteArray()

    private fun api(token: String) = ToolsApi(transport) { token }

    private fun log(line: String) {
        report.appendLine(line)
        println(line)
    }

    private fun check(path: String, ok: Boolean, detail: String) {
        if (!ok) failures++
        log("${if (ok) "PASS" else "FAIL"} [$path] $detail")
    }

    private fun describe(e: ApiError): String = when (e) {
        is ApiError.Validation -> "422 ${e.message} ${e.fieldErrors}"
        is ApiError.Forbidden -> "403 ${e.message}"
        is ApiError.Unauthorized -> "401 ${e.message}"
        is ApiError.NotFound -> "404 ${e.message}"
        is ApiError.RateLimited -> "429 retry=${e.retryAfterSeconds}"
        is ApiError.Server -> "5xx ${e.status}"
        is ApiError.Unexpected -> "unexpected ${e.status} ${e.message}"
        else -> e.toString()
    }

    /** Fills required inputs the way a player would; schematic/file inputs get uploads. */
    private fun formInputs(def: ToolDefinition): Map<String, ToolInputValue> {
        val form = ToolForm(def)
        for (input in form.visibleInputs()) {
            when (input.kind) {
                InputKind.SCHEMATIC -> if (input.required || input == form.primarySchematicInput()) form.setFile(input.name, ToolInputValue.Upload(stone, "clipboard.schem"))
                InputKind.FILE -> if (input.required) form.setFile(
                    input.name,
                    if (input.accept.any { it.startsWith("model/") }) ToolInputValue.Upload(cubeObj, "e2e.obj")
                    else ToolInputValue.Upload(png, "e2e.png"),
                )
                InputKind.TEXT -> if (input.required && form.text(input.name).isNullOrBlank()) form.setText(input.name, "e2e")
                InputKind.BLOCK -> if (input.required && form.text(input.name).isNullOrBlank()) form.setText(input.name, "stone")
                InputKind.SELECT -> if (input.required && form.text(input.name) == null) input.options.firstOrNull()?.let { form.setText(input.name, it.value) }
                InputKind.COLOR -> if (input.required && form.text(input.name) == null) form.setText(input.name, "#aa0000")
                else -> Unit
            }
        }
        val errors = form.validate()
        if (errors.isNotEmpty()) log("  form validation for ${def.id}: $errors")
        return form.buildInputs()
    }

    private suspend fun runToEnd(
        path: String, api: ToolsApi, def: ToolDefinition, request: RunRequest, actor: String? = null,
    ): ToolRun? {
        // schemat.io's API throttle (60/min) is shared by every token from this machine.
        kotlinx.coroutines.delay(PACE_MS)
        val started = api.startRun(def.id, request, actor)
        if (started is ApiResult.Failure) {
            check(path, false, "start ${def.id}: ${describe(started.error)}")
            return null
        }
        val run = (started as ApiResult.Success).value
        var updates = 0
        val outcome = ToolRunPoller(api).await(run.id, def.timeoutSeconds, actor) { updates++ }
        return when (outcome) {
            is ToolRunOutcome.Finished -> {
                val r = outcome.run
                val ok = r.status == RunStatus.DONE
                check(path, ok, "${def.id} run ${r.id} -> ${r.status}${r.errorCode?.let { " ($it: ${r.error})" } ?: ""}, polls=$updates, outputs=${r.outputs.map { "${it.name}:${it.kind}${it.quickshareCode?.let { " qs" } ?: ""}" }}")
                r
            }
            is ToolRunOutcome.Failed -> { check(path, false, "${def.id} poll: ${describe(outcome.error)}"); null }
            is ToolRunOutcome.StillRunning -> { check(path, false, "${def.id} still running after timeout+grace"); null }
        }
    }

    private suspend fun downloadAll(path: String, api: ToolsApi, run: ToolRun, actor: String? = null) {
        for (out in run.outputs) {
            when (val bytes = api.downloadOutput(run, out, actor)) {
                is ApiResult.Success -> check(path, bytes.value.isNotEmpty(), "output ${out.name} (${out.kind}) ${bytes.value.size} bytes")
                is ApiResult.Failure -> check(path, false, "output ${out.name}: ${describe(bytes.error)}")
            }
        }
    }

    @Test
    @Order(1)
    fun `plugin - community token with actor, chat command path`() = runBlocking {
        val path = "plugin /schematio tool"
        val api = api(env("PLUGIN_TOKEN"))
        val actor = env("MEMBER_UUID")
        val catalogue = api.catalogue(actor)
        if (catalogue is ApiResult.Failure) return@runBlocking check(path, false, "catalogue: ${describe(catalogue.error)}")
        val tools = (catalogue as ApiResult.Success).value
        check(path, tools.isNotEmpty(), "catalogue as member: ${tools.size} tools ${tools.map { it.id }}")

        val noActor = api.catalogue(null)
        check(path, noActor is ApiResult.Failure || (noActor as ApiResult.Success).value.isEmpty(),
            "catalogue without actor refused or empty: ${(noActor as? ApiResult.Failure)?.let { describe(it.error) } ?: "empty"}")

        val schematicTools = tools.filter { def -> def.inputs.none { it.kind == InputKind.FILE && it.required } }
        check(path, schematicTools.isNotEmpty(), "chat-runnable tools (no required file input): ${schematicTools.map { it.id }}; " +
            "with a schematic input: ${tools.filter { ToolForm(it).primarySchematicInput() != null }.map { it.id }}")
        for (def in tools.filter { def -> def.inputs.any { it.kind == InputKind.FILE && it.required } }) {
            val parsed = ToolArgs.parse(def, emptyList())
            check(path, parsed is ToolArgs.Result.Error, "${def.id} refused in chat: ${(parsed as? ToolArgs.Result.Error)?.message}")
        }
        for (def in schematicTools) {
            // Required text inputs without a default are typed by the player, e.g. username=e2e.
            val typed = def.inputs.filter { it.required && it.kind == InputKind.TEXT && it.default == null }.map { "${it.name}=e2e" }
            val parsed = ToolArgs.parse(def, typed)
            if (parsed is ToolArgs.Result.Error) { check(path, false, "${def.id} args $typed: ${parsed.message}"); continue }
            val inputs = (parsed as ToolArgs.Result.Ok).inputs.mapValues { (_, v) ->
                if (v == ToolInputValue.ClipboardPlaceholder) ToolInputValue.Upload(stone, "clipboard.schem") else v
            }
            val run = runToEnd(path, api, def, RunRequest(inputs), actor) ?: continue
            val result = ToolResult.pick(run)
            check(path, result !is ToolResult.None, "${def.id} result routing: ${result::class.simpleName}")
            downloadAll(path, api, run, actor)
        }
    }

    @Test
    @Order(2)
    fun `plugin - non-member actor is refused`() = runBlocking {
        val path = "plugin /schematio tool"
        val api = api(env("PLUGIN_TOKEN"))
        val stranger = "00000000-0000-0000-0000-00000000e2e0"
        val def = (api.catalogue(env("MEMBER_UUID")) as? ApiResult.Success)?.value?.firstOrNull() ?: return@runBlocking
        val r = api.startRun(def.id, RunRequest(formInputs(def)), stranger)
        check(path, r is ApiResult.Failure && ToolErrors.isNotPermitted(r.error),
            "unknown actor refused: ${(r as? ApiResult.Failure)?.let { describe(it.error) + " -> " + ToolErrors.userMessage(it.error) } ?: "ACCEPTED"}")
    }

    @Test
    @Order(3)
    fun `mod - user grant, every tool through the panel form`() = runBlocking {
        val path = "mod Tools panel (user)"
        val api = api(env("MOD_TOKEN_GRANTED"))
        val catalogue = api.catalogue()
        if (catalogue is ApiResult.Failure) return@runBlocking check(path, false, "catalogue: ${describe(catalogue.error)}")
        val tools = (catalogue as ApiResult.Success).value
        check(path, tools.isNotEmpty() && tools.all { it.canRunAsUser }, "catalogue ${tools.size} tools, all runnable as user; ids ${tools.map { it.id }}")
        val unknownKinds = tools.flatMap { d -> d.inputs.filter { it.kind == InputKind.UNKNOWN }.map { "${d.id}.${it.name}" } }
        check(path, unknownKinds.isEmpty(), "input kinds all recognised ${if (unknownKinds.isEmpty()) "" else unknownKinds}")
        for (def in tools) {
            val run = runToEnd(path, api, def, RunRequest(formInputs(def), deliver = listOf("download", "quickshare"))) ?: continue
            downloadAll(path, api, run)
        }
    }

    @Test
    @Order(4)
    fun `mod - run as community, then open the quick share like the download router`() = runBlocking {
        val path = "mod Run as community"
        val token = env("MOD_TOKEN_MEMBER")
        val slug = env("COMMUNITY_SLUG")
        val api = api(token)
        val catalogue = api.catalogue()
        if (catalogue is ApiResult.Failure) return@runBlocking check(path, false, "catalogue: ${describe(catalogue.error)}")
        val tools = (catalogue as ApiResult.Success).value
        check(path, tools.isNotEmpty() && tools.all { slug in it.runAsCommunities }, "catalogue ${tools.size} tools offering community:$slug; can_run_as of first: ${tools.firstOrNull()?.canRunAs}")
        val def = tools.firstOrNull { d -> d.outputs.any { it.kind == "schematic" } } ?: tools.first()

        if (!tools.first().canRunAsUser) {
            val asUser = api.startRun(def.id, RunRequest(formInputs(def)))
            check(path, asUser is ApiResult.Failure && ToolErrors.isNotPermitted(asUser.error),
                "member without user grant refused when not running as community: ${(asUser as? ApiResult.Failure)?.let { describe(it.error) } ?: "ACCEPTED"}")
        }

        val run = runToEnd(path, api, def, RunRequest(formInputs(def), community = slug, deliver = listOf("download", "quickshare"))) ?: return@runBlocking
        val qpath = "quickshare open"
        when (val result = ToolResult.pick(run)) {
            is ToolResult.QuickShare -> {
                val sio = SchematioApi(transport, tokenProvider = { token })
                for (format in listOf("litematic", "schem")) {
                    when (val bytes = sio.download(result.code, format)) {
                        is ApiResult.Success -> check(qpath, bytes.value.size > 16, "POST /schematics/{code}/download format=$format -> ${bytes.value.size} bytes")
                        is ApiResult.Failure -> check(qpath, false, "download format=$format: ${describe(bytes.error)}")
                    }
                }
                when (val info = sio.quickShare(result.code)) {
                    is ApiResult.Success -> check(qpath, true, "quick share info: name=${info.value.name}")
                    is ApiResult.Failure -> check(qpath, false, "quick share info: ${describe(info.error)}")
                }
            }
            else -> check(qpath, false, "${def.id} with deliver=quickshare gave ${result::class.simpleName}, outputs=${run.outputs}")
        }
    }

    @Test
    @Order(5)
    fun `cancel - mod and plugin`() = runBlocking {
        val path = "cancel"
        suspend fun cancelOne(label: String, api: ToolsApi, actor: String?, community: String?) {
            val def = (api.catalogue(actor) as? ApiResult.Success)?.value?.maxByOrNull { it.timeoutSeconds ?: 0 }
                ?: return check(path, false, "$label: no catalogue")
            val started = api.startRun(def.id, RunRequest(formInputs(def), community), actor)
            if (started is ApiResult.Failure) return check(path, false, "$label start: ${describe(started.error)}")
            val run = (started as ApiResult.Success).value
            val outcome = ToolRunPoller(api).await(run.id, def.timeoutSeconds, actor, isCancelled = { true }) {}
            val final = (outcome as? ToolRunOutcome.Finished)?.run
            // A run that finished before the DELETE landed is also acceptable; anything else is not.
            check(path, final != null && final.status in setOf(RunStatus.CANCELLED, RunStatus.DONE),
                "$label ${def.id} DELETE -> ${final?.status ?: outcome}")
            val after = api.run(run.id, actor)
            check(path, after is ApiResult.Success && after.value.run.status.terminal,
                "$label poll after cancel -> ${(after as? ApiResult.Success)?.value?.run?.status ?: (after as ApiResult.Failure).let { describe(it.error) }}")
        }
        cancelOne("mod", api(env("MOD_TOKEN_GRANTED")), null, null)
        cancelOne("plugin", api(env("PLUGIN_TOKEN")), env("MEMBER_UUID"), null)
    }

    @AfterAll
    fun writeReport() {
        log("TOTAL failures=$failures")
        System.getenv("E2E_REPORT")?.let { File(it).writeText(report.toString()) }
        transport.close()
        assertTrue(failures == 0, "E2E had $failures failing checks; see report")
    }
}
