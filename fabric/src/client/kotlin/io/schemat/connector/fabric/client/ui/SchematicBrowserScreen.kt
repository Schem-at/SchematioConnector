package io.schemat.connector.fabric.client.ui

import io.schemat.connector.core.json.*
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.layout.Rect
import kotlinx.coroutines.*
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.input.KeyInput
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

class SchematicBrowserScreen : Screen(Text.literal("Schematio Browser")) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-browser")
        private const val ITEMS_PER_PAGE = 10

        private const val SCREEN_PADDING = 8
        private const val TOP_BAR_HEIGHT = 20
        private const val BOTTOM_BAR_HEIGHT = 20
        private const val TITLE_HEIGHT = 16
        private const val STATUS_HEIGHT = 12
        private const val SECTION_GAP = 6
        private const val PANEL_GAP = 6

        private const val DETAIL_PANEL_MIN = 180
        private const val DETAIL_PANEL_MAX = 280
        private const val DETAIL_PANEL_RATIO = 0.32f

        private const val PREVIEW_MAX_SIZE = 140

        private const val SEARCH_BUTTON_WIDTH = 50
        private const val SEARCH_FIELD_MAX_WIDTH = 320
        private const val PAGE_BUTTON_WIDTH = 55
        private const val DOWNLOAD_BUTTON_WIDTH = 100

        private val HTML_BLOCK_BREAK = Regex("(?i)</p>|</h[1-6]>")
        private val HTML_LINE_BREAK = Regex("(?i)<br\\s*/?>|</li>")
        private val HTML_TAG = Regex("<[^>]+>")
        private val MULTI_WS = Regex("[ \\t]+")
        private val MULTI_NL = Regex("\\n{3,}")

        fun stripHtml(raw: String): String {
            var s = raw
                .replace(HTML_BLOCK_BREAK, "\n\n")
                .replace(HTML_LINE_BREAK, "\n")
            s = HTML_TAG.replace(s, "")
            s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            s = MULTI_WS.replace(s, " ")
            s = MULTI_NL.replace(s, "\n\n")
            return s.trim()
        }
    }

    private lateinit var searchField: TextFieldWidget
    private lateinit var listWidget: SchematicListWidget
    private lateinit var searchButton: ButtonWidget
    private lateinit var prevButton: ButtonWidget
    private lateinit var nextButton: ButtonWidget
    private lateinit var downloadButton: ButtonWidget

    // Cached layout rects — populated in init(), read during render()
    private lateinit var titleRect: Rect
    private lateinit var listColumn: Rect
    private lateinit var detailRect: Rect
    private lateinit var pageInfoRect: Rect
    private lateinit var statusRect: Rect

    private var currentPage = 1
    private var totalPages = 1
    private var totalItems = 0
    private var isLoading = false
    private var errorMessage: String? = null
    private var statusMessage: String? = null
    private var hasFetched = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fetchJob: Job? = null

    private var cachedEntries: List<SchematicEntry> = emptyList()
    private val previewManager = PreviewImageManager(scope)

    private val descriptionCache = mutableMapOf<String, String>()

    override fun init() {
        super.init()

        // Build the layout by splitting the screen rect top-down.
        val root = Rect(0, 0, width, height).inset(SCREEN_PADDING)
        val (titleRow, belowTitle) = root.splitTop(TITLE_HEIGHT, SECTION_GAP)
        val (bottomStatus, aboveStatus) = belowTitle.splitBottom(STATUS_HEIGHT, SECTION_GAP)
        val (bottomBar, body) = aboveStatus.splitBottom(BOTTOM_BAR_HEIGHT, SECTION_GAP)

        val detailWidth = (width * DETAIL_PANEL_RATIO).toInt().coerceIn(DETAIL_PANEL_MIN, DETAIL_PANEL_MAX)
        val (detailCol, leftCol) = body.splitRight(detailWidth, PANEL_GAP)
        val (topBar, listArea) = leftCol.splitTop(TOP_BAR_HEIGHT, SECTION_GAP)

        titleRect = titleRow
        listColumn = leftCol
        detailRect = detailCol
        pageInfoRect = bottomBar
        statusRect = bottomStatus

        // Search field + button share the top bar (button is fixed width on the right)
        val (searchBtnRect, searchFieldArea) = topBar.splitRight(SEARCH_BUTTON_WIDTH, SECTION_GAP)
        val searchFieldWidth = searchFieldArea.w.coerceAtMost(SEARCH_FIELD_MAX_WIDTH)
        val searchFieldRect = searchFieldArea.centered(searchFieldWidth, topBar.h)

        searchField = TextFieldWidget(
            textRenderer,
            searchFieldRect.x, searchFieldRect.y,
            searchFieldRect.w, searchFieldRect.h,
            Text.literal("Search")
        )
        searchField.setPlaceholder(Text.literal("Search schematics...").styled { it.withColor(0x888888) })
        addDrawableChild(searchField)

        searchButton = ButtonWidget.builder(Text.literal("Search")) { performSearch() }
            .dimensions(searchBtnRect.x, searchBtnRect.y, searchBtnRect.w, searchBtnRect.h)
            .build()
        addDrawableChild(searchButton)

        // List
        listWidget = SchematicListWidget(client!!, listArea.w, listArea.h, listArea.y)
        addDrawableChild(listWidget)
        if (cachedEntries.isNotEmpty()) {
            listWidget.setEntries(cachedEntries)
        }

        // Bottom bar: prev | page-info (drawn in render) | next, centered in listColumn
        val pageBarCenter = listColumn.centerX
        prevButton = ButtonWidget.builder(Text.literal("< Prev")) { changePage(-1) }
            .dimensions(pageBarCenter - 100, bottomBar.y, PAGE_BUTTON_WIDTH, BOTTOM_BAR_HEIGHT)
            .build()
        addDrawableChild(prevButton)

        nextButton = ButtonWidget.builder(Text.literal("Next >")) { changePage(1) }
            .dimensions(pageBarCenter + 45, bottomBar.y, PAGE_BUTTON_WIDTH, BOTTOM_BAR_HEIGHT)
            .build()
        addDrawableChild(nextButton)

        // Download button: centered in detail column's bottom strip
        val downloadRect = Rect(detailCol.x, bottomBar.y, detailCol.w, BOTTOM_BAR_HEIGHT)
            .centered(DOWNLOAD_BUTTON_WIDTH.coerceAtMost(detailCol.w), BOTTOM_BAR_HEIGHT)
        downloadButton = ButtonWidget.builder(Text.literal("Download")) { downloadSelected() }
            .dimensions(downloadRect.x, downloadRect.y, downloadRect.w, downloadRect.h)
            .build()
        addDrawableChild(downloadButton)

        if (!hasFetched) {
            hasFetched = true
            fetchSchematics()
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        // Title
        context.drawCenteredTextWithShadow(
            textRenderer, "Schematio Browser",
            listColumn.centerX, titleRect.y + (titleRect.h - 8) / 2, -1
        )

        // Pagination text — between prev/next buttons
        context.drawCenteredTextWithShadow(
            textRenderer, "Page $currentPage/$totalPages",
            listColumn.centerX, pageInfoRect.y + (pageInfoRect.h - 8) / 2, 0xFF_AAAAAA.toInt()
        )

        // Loading / error overlay (centered over list area)
        if (isLoading) {
            context.drawCenteredTextWithShadow(
                textRenderer, "Loading...", listColumn.centerX, height / 2, 0xFF_FFFF55.toInt()
            )
        } else if (errorMessage != null) {
            context.drawCenteredTextWithShadow(
                textRenderer, errorMessage!!, listColumn.centerX, height / 2, 0xFF_FF5555.toInt()
            )
        }

        // Button states
        prevButton.active = currentPage > 1 && !isLoading
        nextButton.active = currentPage < totalPages && !isLoading
        downloadButton.active = listWidget.selectedOrNull != null && !isLoading
        searchButton.active = !isLoading

        // Detail panel
        renderDetailPanel(context)

        // Status row at the very bottom
        val authManager = SchematioClientMod.instance.authManager
        if (!authManager.isAuthenticated) {
            context.drawCenteredTextWithShadow(
                textRenderer, "Not authenticated...",
                listColumn.centerX, statusRect.y + 2, 0xFF_FF5555.toInt()
            )
        }
        statusMessage?.let {
            context.drawCenteredTextWithShadow(
                textRenderer, it, listColumn.centerX, statusRect.y + 2, 0xFF_55FF55.toInt()
            )
        }
    }

    private fun renderDetailPanel(context: DrawContext) {
        val selected = listWidget.selectedOrNull?.schematic ?: return
        val panel = detailRect

        // Panel background + left border
        context.fill(panel.x, panel.y, panel.right, panel.bottom, 0xC0_1A1A1A.toInt())
        context.fill(panel.x, panel.y, panel.x + 1, panel.bottom, 0xFF_333333.toInt())

        // Reserve space at the bottom for the Download button so content never overlaps it
        val downloadReserve = BOTTOM_BAR_HEIGHT + SECTION_GAP
        val content = panel.inset(8, 8, 8, downloadReserve)
        var cursorY = content.y
        val contentRight = content.right
        val contentBottom = content.bottom

        // Fixed-size preview slot — same size whether loaded, loading, or missing
        val previewSize = content.w.coerceAtMost(PREVIEW_MAX_SIZE)
        val previewRect = Rect(content.x + (content.w - previewSize) / 2, cursorY, previewSize, previewSize)

        val httpUtil = SchematioClientMod.instance.authManager.httpUtil
        val textureId = previewManager.getTexture(selected.shortId, selected.previewImageUrl, httpUtil)
        when {
            textureId != null -> {
                context.drawTexture(
                    RenderPipelines.GUI_TEXTURED, textureId,
                    previewRect.x, previewRect.y, 0f, 0f,
                    previewRect.w, previewRect.h, previewRect.w, previewRect.h
                )
            }
            previewManager.isLoading(selected.shortId) -> {
                context.fill(previewRect.x, previewRect.y, previewRect.right, previewRect.bottom, 0xFF_222222.toInt())
                context.drawCenteredTextWithShadow(
                    textRenderer, "Loading...",
                    previewRect.centerX, previewRect.centerY - 4, 0xFF_666666.toInt()
                )
            }
            else -> {
                context.fill(previewRect.x, previewRect.y, previewRect.right, previewRect.bottom, 0xFF_222222.toInt())
                val placeholder = if (selected.previewImageUrl.isNullOrBlank()) "No preview" else "Preview unavailable"
                context.drawCenteredTextWithShadow(
                    textRenderer, placeholder,
                    previewRect.centerX, previewRect.centerY - 4, 0xFF_555555.toInt()
                )
            }
        }
        cursorY = previewRect.bottom + 6

        // Name (wrapped, clamped)
        cursorY = drawWrappedClamped(context, selected.name, content.x, cursorY, content.w, contentBottom, -1)
        cursorY += 4

        // Author
        selected.authorName?.let { author ->
            if (cursorY + 10 > contentBottom) return
            context.drawTextWithShadow(textRenderer, "by $author", content.x, cursorY, 0xFF_AAAAAA.toInt())
            cursorY += 12
        }

        // Dimensions
        if (selected.dimensionsText.isNotEmpty() && cursorY + 10 <= contentBottom) {
            context.drawTextWithShadow(textRenderer, selected.dimensionsText, content.x, cursorY, 0xFF_88AACC.toInt())
            cursorY += 12
        }

        // Downloads
        if (selected.downloadCount > 0 && cursorY + 10 <= contentBottom) {
            context.drawTextWithShadow(
                textRenderer, "${selected.downloadCount} downloads",
                content.x, cursorY, 0xFF_AAAAAA.toInt()
            )
            cursorY += 12
        }

        // Tags (cyan, wrapped, clamped)
        if (selected.tags.isNotEmpty() && cursorY + 10 <= contentBottom) {
            cursorY += 2
            cursorY = drawWrappedClamped(
                context, selected.tags.joinToString(", "),
                content.x, cursorY, content.w, contentBottom, 0xFF_55FFFF.toInt()
            )
            cursorY += 4
        }

        // Description (HTML-stripped, wrapped, clamped)
        val rawDescription = selected.description
        if (!rawDescription.isNullOrBlank() && cursorY + 8 <= contentBottom) {
            cursorY += 4
            context.fill(content.x, cursorY, contentRight, cursorY + 1, 0xFF_333333.toInt())
            cursorY += 6
            val stripped = descriptionCache.getOrPut(selected.shortId) { stripHtml(rawDescription) }
            drawWrappedClamped(context, stripped, content.x, cursorY, content.w, contentBottom, 0xFF_999999.toInt())
        }
    }

    /**
     * Draws wrapped text starting at (x, y), wrapping to maxWidth, stopping before
     * any line would extend past bottomY. Honors literal newlines as paragraph breaks.
     * Returns the y of the next line that would have been drawn.
     */
    private fun drawWrappedClamped(
        context: DrawContext, text: String,
        x: Int, y: Int, maxWidth: Int, bottomY: Int, color: Int
    ): Int {
        val lineHeight = 10
        var drawY = y
        // Split on paragraph breaks first so HTML-stripped text keeps structure
        val paragraphs = text.split('\n')
        for ((i, para) in paragraphs.withIndex()) {
            if (para.isBlank()) {
                if (i > 0) drawY += lineHeight / 2
                continue
            }
            val lines = textRenderer.wrapLines(Text.literal(para), maxWidth)
            for (line in lines) {
                if (drawY + lineHeight > bottomY) return drawY
                context.drawTextWithShadow(textRenderer, line, x, drawY, color)
                drawY += lineHeight
            }
        }
        return drawY
    }

    override fun keyPressed(input: KeyInput): Boolean {
        if (searchField.isFocused && input.key == GLFW.GLFW_KEY_ENTER) {
            performSearch()
            return true
        }
        return super.keyPressed(input)
    }

    override fun close() {
        previewManager.cleanup()
        scope.cancel()
        super.close()
    }

    private fun performSearch() {
        currentPage = 1
        fetchSchematics()
    }

    private fun changePage(delta: Int) {
        val newPage = currentPage + delta
        if (newPage in 1..totalPages) {
            currentPage = newPage
            fetchSchematics()
        }
    }

    private fun fetchSchematics() {
        val httpUtil = SchematioClientMod.instance.authManager.httpUtil
        if (httpUtil == null) {
            errorMessage = "Not authenticated. Please wait for login to complete."
            return
        }

        if (isLoading) return

        isLoading = true
        errorMessage = null

        fetchJob = scope.launch {
            try {
                val searchText = searchField.text.takeIf { it.isNotBlank() }
                val queryParams = mutableListOf<String>()
                if (searchText != null) {
                    queryParams.add("search=${java.net.URLEncoder.encode(searchText, "UTF-8")}")
                }
                queryParams.add("page=$currentPage")
                queryParams.add("per_page=$ITEMS_PER_PAGE")
                queryParams.add("sort=created_at")
                queryParams.add("order=desc")

                val endpoint = "/schematics?${queryParams.joinToString("&")}"
                LOGGER.info("Fetching: $endpoint")
                val response = httpUtil.sendGetRequest(endpoint)

                MinecraftClient.getInstance().execute {
                    isLoading = false

                    if (response == null) {
                        errorMessage = "Connection failed - API may be unavailable"
                        return@execute
                    }

                    val json = parseJsonSafe(response)
                    if (json == null) {
                        errorMessage = "Invalid response from API"
                        return@execute
                    }

                    val dataArray = json.safeGetArray("data")
                        .mapNotNull { it.asJsonObjectOrNull() }
                    val meta = json.safeGetObject("meta")

                    val entries = dataArray.map { SchematicEntry.fromJson(it) }
                    currentPage = meta.safeGetInt("current_page", 1)
                    totalPages = meta.safeGetInt("last_page", 1)
                    totalItems = meta.safeGetInt("total", 0)

                    LOGGER.info("Loaded ${entries.size} entries, page $currentPage/$totalPages, total $totalItems")

                    cachedEntries = entries
                    listWidget.setEntries(entries)

                    if (entries.isEmpty()) {
                        errorMessage = if (searchText != null) "No schematics found for \"$searchText\""
                        else "No schematics available"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOGGER.error("Failed to fetch schematics: ${e.message}", e)
                MinecraftClient.getInstance().execute {
                    isLoading = false
                    errorMessage = "Error: ${e.message}"
                }
            }
        }
    }

    private fun downloadSelected() {
        val entry = listWidget.selectedOrNull?.schematic ?: return
        val integration = SchematioClientMod.instance.litematicaIntegration ?: return

        statusMessage = "Downloading ${entry.name}..."
        downloadButton.active = false

        integration.downloader.download(
            schematicId = entry.shortId,
            name = entry.name,
            onSuccess = {
                statusMessage = "Downloaded! Placed at your position."
                scope.launch {
                    delay(3000)
                    MinecraftClient.getInstance().execute { statusMessage = null }
                }
            },
            onError = { error ->
                statusMessage = null
                errorMessage = error
            }
        )
    }
}
