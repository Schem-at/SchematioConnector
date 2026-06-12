package io.schemat.connector.fabric.client.ui

import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.QuickShareRequest
import io.schemat.connector.core.modapi.dto.QuickShareInfo
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.ExportSource
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.LoadingSpinner
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.foundation.ThemedTextField
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.widgets.ExportSourceListWidget
import io.schemat.connector.fabric.client.ui.widgets.ExportSources
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quick-share creation: pick a source (same mechanics as the upload wizard's step 1),
 * set name/expiry and optional password/max-uses, then `POST /mod/quick-shares`.
 *
 * When [preset] is given (e.g. "share this schematic" from the detail screen) the
 * source step is skipped entirely - the schematic bytes are downloaded from the API
 * at create time and re-shared.
 */
class QuickShareCreateScreen(
    private val parent: Screen,
    private val preset: RemoteSchematicSource? = null,
) : Screen(Component.literal("New quick share")) {

    /** An already-uploaded schematic used as the share source (bytes via `download`). */
    data class RemoteSchematicSource(val schematicId: String, val name: String, val format: String)

    private enum class Step { SOURCE, DETAILS }

    companion object {
        private const val PADDING = Theme.XL
        private const val CONTROL_HEIGHT = Theme.INPUT_H
        private const val BUTTON_HEIGHT = Theme.BTN_H
        private const val GAP = Theme.MD
        private const val LABEL_HEIGHT = 11
        /** Vertical space consumed by the [Theme.header] block (title + subtitle + rule + gap). */
        private const val HEADER_USED = 39

        /** Expiry choices: label → seconds. */
        private val EXPIRY_OPTIONS = listOf(
            "30 minutes" to 1_800,
            "1 hour" to 3_600,
            "24 hours" to 86_400,
            "7 days" to 604_800,
            "30 days" to 2_592_000,
        )
    }

    private val services get() = SchematioClientMod.instance.services

    // ---- state (survives re-init) ----
    private var step = if (preset != null) Step.DETAILS else Step.SOURCE

    private var sources: List<ExportSource> = emptyList()
    private var selectedSource: ExportSource? = null

    private var nameText = preset?.name ?: ""
    private var expirySeconds = 86_400
    private var passwordText = ""
    private var maxUsesText = ""

    private val createBusy = AtomicBoolean(false)
    private var exporting = false

    private val banner = NoticeBanner()
    private var nextButton: AbstractWidget? = null
    private var contentTop = 0
    private var bodyLines: List<String> = emptyList()

    // ---- label positions ----
    private var nameLabelY = 0
    private var expiryLabelY = 0
    private var passwordLabelY = 0
    private var maxUsesLabelY = 0

    override fun init() {
        super.init()
        contentTop = PADDING + HEADER_USED
        val buttonsTop = height - PADDING - BUTTON_HEIGHT
        val bannerY = buttonsTop - NoticeBanner.HEIGHT - 4
        addRenderableWidget(banner.layout(PADDING, bannerY, width - PADDING * 2))
        nextButton = null

        when (step) {
            Step.SOURCE -> initSourceStep(buttonsTop, bannerY)
            Step.DETAILS -> initDetailsStep(buttonsTop)
        }
    }

    private fun goTo(next: Step) {
        step = next
        banner.clear()
        rebuildWidgets()
    }

    // ---- step: source ----

    private fun initSourceStep(buttonsTop: Int, bannerY: Int) {
        sources = ExportSources.collect()
        if (selectedSource != null && sources.none { it.id == selectedSource!!.id }) selectedSource = null

        val listHeight = (bannerY - GAP - contentTop).coerceAtLeast(40)
        addRenderableWidget(
            ExportSourceListWidget(
                PADDING, contentTop, width - PADDING * 2, listHeight,
                sources = { sources },
                selectedId = { selectedSource?.id },
                onSelect = { source ->
                    selectedSource = source
                    if (nameText.isBlank()) nameText = defaultNameFor(source)
                },
            )
        )

        addRenderableWidget(FlatButton.ghost(PADDING, buttonsTop, 80, Component.literal("Cancel")) { onClose() })
        nextButton = addRenderableWidget(
            FlatButton.primary(width - PADDING - 90, buttonsTop, 90, Component.literal("Next >")) {
                if (selectedSource != null) goTo(Step.DETAILS)
            }
        )
    }

    private fun defaultNameFor(source: ExportSource): String =
        source.label.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".litematic").removeSuffix(".schem")

    // ---- step: details ----

    private fun initDetailsStep(buttonsTop: Int) {
        val fieldWidth = (width - PADDING * 2).coerceAtMost(260)
        var y = contentTop

        bodyLines = listOf("Sharing: ${sourceDescription()}")
        y += LABEL_HEIGHT + Theme.LG

        nameLabelY = y
        y += LABEL_HEIGHT
        val nameField = ThemedTextField(
            font, PADDING, y, fieldWidth, CONTROL_HEIGHT,
            Component.literal("Name"), placeholder = "Share name",
        )
        nameField.setMaxLength(255)
        nameField.value = nameText
        nameField.setResponder { nameText = it }
        addRenderableWidget(nameField)
        y += CONTROL_HEIGHT + Theme.LG

        expiryLabelY = y
        y += LABEL_HEIGHT
        val currentExpiry = EXPIRY_OPTIONS.firstOrNull { it.second == expirySeconds } ?: EXPIRY_OPTIONS[2]
        expirySeconds = currentExpiry.second
        // Themed cycler (FlatButton.secondary stepping through the options) -
        // replaces the default-skinned vanilla CyclingButtonWidget.
        var expiryIndex = EXPIRY_OPTIONS.indexOf(currentExpiry).coerceAtLeast(0)
        lateinit var expiryButton: FlatButton
        expiryButton = FlatButton.secondary(
            PADDING, y, fieldWidth, Component.literal(EXPIRY_OPTIONS[expiryIndex].first), BUTTON_HEIGHT,
        ) {
            expiryIndex = (expiryIndex + 1) % EXPIRY_OPTIONS.size
            expiryButton.message = Component.literal(EXPIRY_OPTIONS[expiryIndex].first)
            expirySeconds = EXPIRY_OPTIONS[expiryIndex].second
        }
        addRenderableWidget(expiryButton)
        y += BUTTON_HEIGHT + Theme.LG

        passwordLabelY = y
        y += LABEL_HEIGHT
        val passwordField = ThemedTextField(
            font, PADDING, y, fieldWidth, CONTROL_HEIGHT,
            Component.literal("Password"), placeholder = "No password",
        )
        passwordField.setMaxLength(100)
        passwordField.value = passwordText
        passwordField.setResponder { passwordText = it }
        addRenderableWidget(passwordField)
        y += CONTROL_HEIGHT + Theme.LG

        maxUsesLabelY = y
        y += LABEL_HEIGHT
        val maxUsesField = ThemedTextField(
            font, PADDING, y, 80, CONTROL_HEIGHT,
            Component.literal("Max uses"), placeholder = "∞",
        )
        maxUsesField.setMaxLength(5)
        maxUsesField.value = maxUsesText
        maxUsesField.setResponder { maxUsesText = it }
        addRenderableWidget(maxUsesField)

        if (preset == null) {
            addRenderableWidget(FlatButton.ghost(PADDING, buttonsTop, 80, Component.literal("Cancel")) { onClose() })
            addRenderableWidget(
                FlatButton.secondary(PADDING + 80 + GAP, buttonsTop, 80, Component.literal("< Back")) { goTo(Step.SOURCE) }
            )
        } else {
            addRenderableWidget(FlatButton.ghost(PADDING, buttonsTop, 80, Component.literal("Cancel")) { onClose() })
        }
        nextButton = addRenderableWidget(
            FlatButton.primary(width - PADDING - 90, buttonsTop, 90, Component.literal("Create")) { validateAndCreate() }
        )
    }

    private fun sourceDescription(): String =
        preset?.let { "\"${it.name}\" (from schemat.io, ${it.format})" }
            ?: selectedSource?.let { ExportSources.label(it) }
            ?: "?"

    private fun validateAndCreate() {
        if (createBusy.get() || exporting) return
        val problems = mutableListOf<String>()
        if (nameText.isBlank()) problems.add("name is required")
        if (passwordText.isNotEmpty() && passwordText.length < 4) problems.add("password must be at least 4 characters")
        val maxUses = maxUsesText.trim().takeIf { it.isNotEmpty() }?.let {
            val parsed = it.toIntOrNull()
            if (parsed == null || parsed !in 1..10_000) problems.add("max uses must be a number between 1 and 10000")
            parsed
        }
        if (problems.isNotEmpty()) {
            banner.show(NoticeBanner.Kind.ERROR, problems.joinToString("; ").replaceFirstChar { it.uppercase() })
            return
        }
        banner.clear()

        if (preset != null) {
            createShare(preset.format) { services.api.download(preset.schematicId, preset.format) }
            return
        }
        val source = selectedSource ?: run {
            banner.show(NoticeBanner.Kind.ERROR, "No source selected")
            return
        }
        val format = ExportSources.formatFor(source)
        when (source.kind) {
            SourceKind.LOCAL_FILE ->
                createShare(format) { ApiResult.Success(Files.readAllBytes(Path.of(source.id))) }
            SourceKind.WORLDEDIT_CLIPBOARD -> {
                exporting = true
                Bridges.worldEdit.clipboardToBytes { bytes, error ->
                    exporting = false
                    if (bytes == null) {
                        banner.show(NoticeBanner.Kind.ERROR, error ?: "Failed to read the WorldEdit clipboard")
                    } else {
                        createShare(format) { ApiResult.Success(bytes) }
                    }
                }
            }
            SourceKind.PLACEMENT, SourceKind.AREA_SELECTION -> {
                exporting = true
                Bridges.litematica.exportToBytes(source) { bytes, error ->
                    exporting = false
                    if (bytes == null) {
                        banner.show(NoticeBanner.Kind.ERROR, error ?: "Failed to export the schematic")
                    } else {
                        createShare(format) { ApiResult.Success(bytes) }
                    }
                }
            }
        }
    }

    private fun createShare(format: String, bytesProvider: suspend () -> ApiResult<ByteArray>) {
        val maxUses = maxUsesText.trim().toIntOrNull()
        services.call(
            busy = createBusy,
            block = {
                when (val bytes = bytesProvider()) {
                    is ApiResult.Failure -> bytes
                    is ApiResult.Success -> services.cached.createQuickShare(
                        QuickShareRequest(
                            schematicBytes = bytes.value,
                            name = nameText.trim(),
                            format = format,
                            expiresInSeconds = expirySeconds,
                            password = passwordText.takeIf { it.isNotEmpty() },
                            maxUses = maxUses,
                        )
                    )
                }
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    when (parent) {
                        is HomeScreen -> parent.invalidateListings()
                        is SchematicDetailScreen -> parent.invalidateHomeListings()
                        else -> {}
                    }
                    // No success panel - drop back to the game and post a
                    // clickable chat notice with the share link instead.
                    minecraft?.setScreen(null)
                    ChatNotice.success("Quick share created", shareUrl(result.value), "Open link")
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    /** Share link for the success chat notice: the API's webUrl, or host/share/<code>. */
    private fun shareUrl(share: QuickShareInfo): String {
        share.webUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val base = services.authManager.apiEndpoint.substringBefore("/api/").trimEnd('/')
        return "$base/share/${share.accessCode}"
    }

    // ---- rendering ----

    private fun stepHeading(): String = when (step) {
        Step.SOURCE -> "Step 1 of 2 - Choose what to share"
        Step.DETAILS -> if (preset != null) "Share settings" else "Step 2 of 2 - Share settings"
    }

    // 26.x renamed the Screen render hooks: render -> extractRenderState,
    // renderBackground -> extractBackground.
    //? if >=26.1 {
    /*override fun extractBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
    *///?} else {
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.renderBackground(context, mouseX, mouseY, delta)
    //?}
        Theme.scrim(context, width, height)
    }

    //? if >=26.1 {
    /*override fun extractRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
    *///?} else {
    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    //?}
        Theme.header(context, font, title.string, stepHeading(), PADDING, PADDING, width - PADDING * 2)

        when (step) {
            Step.SOURCE -> {
                if (sources.isEmpty()) {
                    Theme.emptyState(
                        context, font,
                        "No sources found - put .litematic/.schem files in ${ExportSources.localFilesDirectory()}",
                        0, 0, width, height,
                    )
                }
                nextButton?.active = selectedSource != null
            }
            Step.DETAILS -> {
                bodyLines.firstOrNull()?.let {
                    Theme.label(context, font, font.plainSubstrByWidth(it, width - PADDING * 2), PADDING, contentTop)
                }
                Theme.sectionLabel(context, font, "Name", PADDING, nameLabelY)
                Theme.sectionLabel(context, font, "Expires in", PADDING, expiryLabelY)
                Theme.sectionLabel(context, font, "Password (optional)", PADDING, passwordLabelY)
                Theme.sectionLabel(context, font, "Max uses (optional)", PADDING, maxUsesLabelY)
                val busy = createBusy.get() || exporting
                nextButton?.active = !busy
                if (busy) LoadingSpinner.render(context, font, width / 2, maxUsesLabelY + 34, "Creating share")
            }
        }

        banner.render(context, font)
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }
}
