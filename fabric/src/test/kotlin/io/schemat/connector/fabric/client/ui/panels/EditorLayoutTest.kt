package io.schemat.connector.fabric.client.ui.panels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EditorLayoutTest {

    @Test
    fun `the description editor fills the space left above the footer`() {
        // It was capped at 160 px, clipping a short description while ~400 px sat empty below.
        assertEquals(480f, EditorLayout.descriptionHeight(available = 480f + EditorLayout.FOOTER))
    }

    @Test
    fun `the description editor keeps a usable minimum in a small window`() {
        assertEquals(EditorLayout.MIN, EditorLayout.descriptionHeight(available = 40f))
    }
}
