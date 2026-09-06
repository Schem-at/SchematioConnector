package io.schemat.connector.fabric.client.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SchematicFilesTest {
    @TempDir lateinit var directory: Path

    @Test fun `a second download cannot overwrite a file already loaded by an editor`() {
        val first = SchematicFiles.save(directory, "tree", "schem", byteArrayOf(1, 2))
        val second = SchematicFiles.save(directory, "tree", "schem", byteArrayOf(3))
        assertNotEquals(first, second)
        assertContentEquals(byteArrayOf(1, 2), Files.readAllBytes(first))
        assertContentEquals(byteArrayOf(3), Files.readAllBytes(second))
        Files.list(directory).use { files -> assertEquals(2, files.count()) }
    }

    @Test fun `download names cannot escape the destination directory`() {
        val path = SchematicFiles.save(directory, "../../tree", "schem", byteArrayOf(1))
        assertEquals(directory, path.parent)
        assertFailsWith<IllegalArgumentException> { SchematicFiles.save(directory, "tree", "../schem", byteArrayOf()) }
    }
}
