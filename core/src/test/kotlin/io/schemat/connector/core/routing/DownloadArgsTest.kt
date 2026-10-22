package io.schemat.connector.core.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadArgsTest {

    @Test
    fun `plain id`() {
        assertEquals(ParsedDownload.Ok("44srfK", null, null), DownloadArgs.parse("44srfK", false))
    }

    @Test
    fun `flags anywhere`() {
        assertEquals(
            ParsedDownload.Ok("44srfK", null, DestinationFilter(tool = Tool.WORLDEDIT)),
            DownloadArgs.parse("--we 44srfK", false),
        )
        assertEquals(
            ParsedDownload.Ok("44srfK", null, DestinationFilter(Side.SERVER, Tool.WORLDEDIT)),
            DownloadArgs.parse("44srfK --worldedit --server", false),
        )
    }

    @Test
    fun `schematic url becomes id`() {
        val r = DownloadArgs.parse("https://schemat.io/schematics/44srfK", false) as ParsedDownload.Ok
        assertEquals("44srfK", r.id)
    }

    @Test
    fun `quick share password`() {
        assertEquals(
            ParsedDownload.Ok("qs_abc123", "hunter2", DestinationFilter(tool = Tool.LITEMATICA)),
            DownloadArgs.parse("qs_abc123 hunter2 --lm", true),
        )
    }

    @Test
    fun `unknown flag is an error`() {
        assertEquals(
            ParsedDownload.Error("Unknown flag --x. Use --we, --lm, --file, --server or --local."),
            DownloadArgs.parse("--x 44srfK", false),
        )
    }

    @Test
    fun `two tools is an error`() {
        assertEquals(
            ParsedDownload.Error("Choose one of --we, --lm or --file."),
            DownloadArgs.parse("44srfK --we --lm", false),
        )
    }

    @Test
    fun `server file is an error`() {
        val expected = ParsedDownload.Error("Only WorldEdit can load on the server.")
        assertEquals(expected, DownloadArgs.parse("44srfK --server --file", false))
        assertEquals(expected, DownloadArgs.parse("44srfK --server --lm", false))
    }

    @Test
    fun `missing id`() {
        assertEquals(ParsedDownload.Error("Give a schematic ID or schemat.io link."), DownloadArgs.parse("--we", false))
        assertEquals(ParsedDownload.Error("Give a quick share code."), DownloadArgs.parse("", true))
    }

    @Test
    fun `local side flag`() {
        val r = DownloadArgs.parse("44srfK --local", false) as ParsedDownload.Ok
        assertTrue(r.filter == DestinationFilter(side = Side.LOCAL))
    }
}
