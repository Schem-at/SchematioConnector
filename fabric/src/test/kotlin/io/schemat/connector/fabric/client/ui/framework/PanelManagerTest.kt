package io.schemat.connector.fabric.client.ui.framework

import kotlin.test.*

class PanelManagerTest {

    private class FakePanel(override val id: String) : Panel {
        var rendered = 0
        override fun render() { rendered++ }
    }

    @BeforeTest
    fun reset() = PanelManager.closeAll()

    @Test
    fun opensAndReportsOpen() {
        val p = FakePanel("browser")
        PanelManager.open(p)
        assertTrue(PanelManager.isOpen("browser"))
        assertTrue(PanelManager.anyOpen())
    }

    @Test
    fun toggleClosesWhenOpen() {
        val p = FakePanel("browser")
        PanelManager.open(p)
        PanelManager.toggle(p)
        assertFalse(PanelManager.isOpen("browser"))
    }

    @Test
    fun opensAreDeduplicatedById() {
        PanelManager.open(FakePanel("browser"))
        PanelManager.open(FakePanel("browser"))
        assertEquals(1, PanelManager.openPanels().count { it.id == "browser" })
    }

    @Test
    fun closeAllEmptiesRegistry() {
        PanelManager.open(FakePanel("a"))
        PanelManager.open(FakePanel("b"))
        PanelManager.closeAll()
        assertFalse(PanelManager.anyOpen())
        assertEquals(0, PanelManager.openPanels().size)
    }

    @Test
    fun renderAllCallsRenderOnEachOpenPanel() {
        val p1 = FakePanel("a")
        val p2 = FakePanel("b")
        PanelManager.open(p1)
        PanelManager.open(p2)
        PanelManager.renderAll()
        assertEquals(1, p1.rendered)
        assertEquals(1, p2.rendered)
    }

    @Test
    fun renderAllAllowsPanelToCloseItselfDuringRender() {
        val self = object : Panel {
            override val id = "self"
            override fun render() { PanelManager.close("self") }
        }
        PanelManager.open(self)
        PanelManager.renderAll()
        assertFalse(PanelManager.isOpen("self"))
    }
}
