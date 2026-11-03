package io.schemat.connector.fabric.client.ui.widgets

/**
 * Read-only display of a website description (contract HTML, or plain text) with the
 * same layout as [RichTextEditorWidget], so a description looks the same in the editor,
 * in the mod's detail panels and on schemat.io. Re-parses only when the HTML changes.
 */
class RichTextView(id: String) {

    private val doc = RichTextEditorWidget(id)
    private var shown: String? = null

    fun render(html: String) {
        if (html != shown) {
            doc.setHtml(html)
            shown = html
        }
        doc.renderReadOnly()
    }
}
