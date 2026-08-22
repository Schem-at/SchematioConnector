package io.schemat.connector.fabric.client.ui.panels

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class BrowseScopeTest {

    @Test
    fun `All maps to Public context`() {
        assertEquals(SchematicListView.Context.Public, BrowseScope.toContext(BrowseScope.All))
    }

    @Test
    fun `Mine maps to Mine context`() {
        assertEquals(SchematicListView.Context.Mine, BrowseScope.toContext(BrowseScope.Mine))
    }

    @Test
    fun `Community scope maps to a Community context carrying slug and name`() {
        val ctx = BrowseScope.toContext(BrowseScope.Community("castles", "Castle Builders"))
        assertTrue(ctx is SchematicListView.Context.Community)
        ctx as SchematicListView.Context.Community
        assertEquals("castles", ctx.slug)
        assertEquals("Castle Builders", ctx.name)
    }
}
