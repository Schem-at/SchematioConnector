package io.schemat.connector.fabric.client.ui

import io.schemat.connector.fabric.client.ui.foundation.TabBarWidget
import io.schemat.connector.fabric.client.ui.tabs.BrowseTab
import io.schemat.connector.fabric.client.ui.tabs.CommunitiesTab
import io.schemat.connector.fabric.client.ui.tabs.QuickSharesTab
import io.schemat.connector.fabric.client.ui.tabs.SettingsTab
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.Minecraft
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.components.Renderable
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The tabbed schemat.io home screen: a tab bar across the top, with the active
 * tab's [TabContent] hosted below. The last-open tab is remembered across reopens.
 *
 * BROWSE / MINE / COMMUNITIES / SHARES are placeholders until Tasks 3-5 land;
 * SETTINGS is functional.
 */
class HomeScreen : Screen(Component.literal("Schemat.io")) {

    enum class Tab(val label: String) {
        BROWSE("Browse"),
        MINE("My Schematics"),
        COMMUNITIES("Communities"),
        SHARES("Quick Shares"),
        SETTINGS("Settings"),
    }

    /**
     * One tab's content. Lifecycle mirrors [Screen]: [init] is called whenever the
     * host (re)initializes (open, resize, tab switch) and should register widgets via
     * [HomeScreen.register]; [render]/[tick] are forwarded while the tab is active.
     */
    interface TabContent {
        fun init(screen: HomeScreen, width: Int, height: Int)
        fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float)

        /** Drawn after the scrim but before widgets - for wells/cards that sit behind controls. */
        fun renderBackground(context: GuiGraphics, width: Int, height: Int) {}
        fun tick() {}
    }

    companion object {
        /** Last selected tab - survives screen close/reopen within the session. */
        private var lastTab: Tab = Tab.BROWSE

        /** Content side margin. */
        const val PADDING = Theme.LG
        const val TAB_BAR_HEIGHT = 24

        /** Top inset of the tab bar inside the header band. */
        private const val TAB_BAR_TOP = Theme.MD

        /** Bottom edge of the header band (tab bar + its baseline). */
        private const val HEADER_BOTTOM = TAB_BAR_TOP + TAB_BAR_HEIGHT
    }

    private val contents: Map<Tab, TabContent> = mapOf(
        Tab.BROWSE to BrowseTab(),
        Tab.MINE to BrowseTab(
            mineOnly = true,
            actionLabel = "Upload",
            onAction = { host -> Minecraft.getInstance().setScreen(UploadWizardScreen(host)) },
            secondaryActionLabel = "Quick link",
            onSecondaryAction = { host -> Minecraft.getInstance().setScreen(QuickShareCreateScreen(host)) },
        ),
        Tab.COMMUNITIES to CommunitiesTab(),
        Tab.SHARES to QuickSharesTab(),
        Tab.SETTINGS to SettingsTab(),
    )

    /**
     * Drop accumulated listing state so the listing tabs refetch on their next init
     * (called after mutations like delete; the API cache is already invalidated).
     */
    fun invalidateListings() {
        contents.values.forEach { content ->
            when (content) {
                is BrowseTab -> content.invalidate()
                is CommunitiesTab -> content.invalidate()
                is QuickSharesTab -> content.invalidate()
            }
        }
    }

    /** Re-run [init] for the active tab (public wrapper over [clearAndInit] for tab content). */
    fun reinit() {
        rebuildWidgets()
    }

    /**
     * Switch to the Browse tab pre-scoped to a community (used by the community detail
     * screen's "Browse schematics"); takes effect when this screen is next (re)initialized.
     */
    fun openBrowseForCommunity(slug: String, name: String) {
        (contents.getValue(Tab.BROWSE) as BrowseTab).presetCommunity(slug, name)
        lastTab = Tab.BROWSE
    }

    private lateinit var tabBar: TabBarWidget

    private val current: TabContent get() = contents.getValue(lastTab)

    /** Y where tab content starts (below the header band). */
    val contentTop: Int get() = HEADER_BOTTOM + Theme.LG

    override fun init() {
        super.init()
        tabBar = TabBarWidget(
            PADDING, TAB_BAR_TOP, width - PADDING * 2, TAB_BAR_HEIGHT,
            Tab.entries.map { it.label },
            { lastTab.ordinal },
        ) { index ->
            val tab = Tab.entries[index]
            if (tab != lastTab) {
                lastTab = tab
                rebuildWidgets()
            }
        }
        tabBar.widgets().forEach { addRenderableWidget(it) }
        current.init(this, width, height)
    }

    /** Widget registration hook for [TabContent] implementations. */
    fun <T> register(widget: T): T where T : GuiEventListener, T : Renderable, T : NarratableEntry =
        addRenderableWidget(widget)

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
        // Header band behind the tab bar, closed off by a full-width divider
        // (the tab bar redraws its own baseline + accent underline on top).
        context.fill(0, 0, width, HEADER_BOTTOM, Theme.SURFACE)
        context.fill(0, HEADER_BOTTOM - 1, width, HEADER_BOTTOM, Theme.BORDER)
        current.renderBackground(context, width, height)
    }

    //? if >=26.1 {
    /*override fun extractRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
    *///?} else {
    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    //?}
        tabBar.render(context)
        current.render(context, mouseX, mouseY, delta)
    }

    override fun tick() {
        super.tick()
        current.tick()
    }
}
