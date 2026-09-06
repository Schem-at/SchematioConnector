package io.schemat.connector.fabric.client.integration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

object SchematicFiles {
    /** Publish a complete file without replacing a file held by another editor. */
    fun save(directory: Path, name: String, extension: String, bytes: ByteArray): Path {
        require(extension in setOf("schem", "schematic", "litematic"))
        Files.createDirectories(directory)
        val safe = name.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(100).ifBlank { "schematic" }
        val target = directory.resolve("$safe-${UUID.randomUUID()}.$extension")
        val staging = Files.createTempFile(directory, ".schematio-", ".tmp")
        try {
            Files.write(staging, bytes)
            return Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(staging) }
    }
}
