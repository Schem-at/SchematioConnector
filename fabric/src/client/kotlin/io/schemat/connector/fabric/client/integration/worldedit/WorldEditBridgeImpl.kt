package io.schemat.connector.fabric.client.integration.worldedit

import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.session.ClipboardHolder
import io.schemat.connector.fabric.client.integration.WorldEditBridge
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Real [WorldEditBridge] backed by the WorldEdit mod's own runtime (`worldedit` mod id).
 *
 * **Honest scope - singleplayer / LAN host only.** WorldEdit on Fabric runs on the
 * (integrated) server; there is no client-side clipboard on multiplayer servers. This
 * bridge therefore reports [isAvailable] only when an integrated server is running in
 * this JVM. On dedicated/multiplayer servers the bridge stays unavailable and the
 * server-side Schematio plugin path (which has its own WorldEdit integration) covers
 * clipboard workflows instead.
 *
 * Compiled against `worldedit-core` only (`compileOnly` dep). The actor needed to
 * resolve a [LocalSession] comes from `worldedit-fabric`'s `FabricAdapter`, reached via
 * reflection (same idiom as the server-side `FabricWorldEditUtil`) because the fabric
 * artifact is not on the compile classpath. If adaptation fails we fall back to
 * `SessionManager.findByName`, which finds an existing session but cannot create one.
 *
 * Format notes: clipboards are serialized as Sponge v3 `.schem`
 * ([BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC]). For [bytesToClipboard] WorldEdit can
 * only parse Sponge/MCEdit data - `.litematic` is rejected with a friendly error (the
 * detail screen always requests the `schem` conversion for this path).
 *
 * Threading: all WorldEdit calls run on the integrated server thread
 * ([MinecraftServer.execute]); results are delivered on the render thread
 * ([Minecraft.execute]) as the [WorldEditBridge] contract requires.
 *
 * Verified against worldedit-core 7.3.10:
 * - `WorldEdit.getInstance().sessionManager` - `get(SessionOwner)`, `findByName(String)`
 * - `LocalSession.getClipboard(): ClipboardHolder` (throws [EmptyClipboardException]),
 *   `setClipboard(ClipboardHolder)`
 * - `BuiltInClipboardFormat`: `SPONGE_V3_SCHEMATIC`, `SPONGE_SCHEMATIC` (v2),
 *   `SPONGE_V1_SCHEMATIC`, `MCEDIT_SCHEMATIC`
 * - `ClipboardFormats.findByInputStream(Supplier<InputStream>)`
 */
class WorldEditBridgeImpl : WorldEditBridge {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-worldedit-bridge")

        /** Formats WorldEdit can actually parse; everything else gets a clear error. */
        private val READABLE_FORMATS = setOf("schem", "sponge", "schematic")

        private const val NEEDS_SINGLEPLAYER =
            "WorldEdit clipboard access works in singleplayer (or as LAN host) only - " +
                "on servers, use the Schematio server plugin instead"
    }

    init {
        // Smoke test: touching worldedit-core here makes the loader's classload check
        // fail fast (NoClassDefFoundError / linkage errors) when WorldEdit is absent
        // or incompatible, so callers keep the Noop bridge installed.
        WorldEdit.getInstance().sessionManager
    }

    /** Available only while an integrated server (singleplayer / LAN host) is running. */
    override val isAvailable: Boolean
        get() = Minecraft.getInstance().singleplayerServer != null

    override fun clipboardToBytes(onResult: (ByteArray?, String?) -> Unit) {
        val client = Minecraft.getInstance()
        val server = client.singleplayerServer ?: return onResult(null, NEEDS_SINGLEPLAYER)
        val player = client.player ?: return onResult(null, "Join a world before exporting the WorldEdit clipboard")
        val uuid = player.uuid
        val name = player.gameProfile.name
        server.execute {
            var bytes: ByteArray? = null
            var error: String? = null
            try {
                val session = localSession(server, uuid, name)
                if (session == null) {
                    error = "No WorldEdit session found - run a WorldEdit command (e.g. //copy) once, then retry"
                } else {
                    val clipboard = session.clipboard.clipboard
                    val out = ByteArrayOutputStream()
                    BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out).use { it.write(clipboard) }
                    bytes = out.toByteArray()
                }
            } catch (e: EmptyClipboardException) {
                error = "The WorldEdit clipboard is empty - //copy something first"
            } catch (t: Throwable) {
                LOGGER.warn("Failed to serialize the WorldEdit clipboard", t)
                error = "Failed to read the WorldEdit clipboard: ${t.message ?: "unexpected error"}"
            }
            client.execute { onResult(bytes, error) }
        }
    }

    override fun bytesToClipboard(bytes: ByteArray, format: String, onResult: (Boolean, String?) -> Unit) {
        val client = Minecraft.getInstance()
        val server = client.singleplayerServer ?: return onResult(false, NEEDS_SINGLEPLAYER)
        val normalized = format.lowercase().removePrefix(".")
        if (normalized !in READABLE_FORMATS) {
            return onResult(
                false,
                "WorldEdit cannot read .$normalized files - download the .schem conversion instead"
            )
        }
        val player = client.player ?: return onResult(false, "Join a world before using the WorldEdit clipboard")
        val uuid = player.uuid
        val name = player.gameProfile.name
        server.execute {
            var ok = false
            var error: String? = null
            try {
                val session = localSession(server, uuid, name)
                if (session == null) {
                    error = "No WorldEdit session found - run a WorldEdit command (e.g. //pos1) once, then retry"
                } else {
                    val clipboard = readClipboard(bytes)
                    if (clipboard == null) {
                        error = "WorldEdit could not parse the schematic data (expected Sponge .schem)"
                    } else {
                        session.setClipboard(ClipboardHolder(clipboard))
                        ok = true
                    }
                }
            } catch (t: Throwable) {
                LOGGER.warn("Failed to set the WorldEdit clipboard", t)
                error = "Failed to set the WorldEdit clipboard: ${t.message ?: "unexpected error"}"
            }
            client.execute { onResult(ok, error) }
        }
    }

    /**
     * Resolve the local player's [LocalSession] on the integrated server. Prefers
     * `sessionManager.get(actor)` (creates the session when missing) via the reflective
     * FabricAdapter; falls back to `findByName` (existing sessions only).
     */
    private fun localSession(server: MinecraftServer, uuid: UUID, name: String): LocalSession? {
        val serverPlayer = server.playerList.getPlayer(uuid)
        if (serverPlayer != null) {
            adaptPlayer(serverPlayer)?.let { actor ->
                try {
                    return WorldEdit.getInstance().sessionManager.get(actor)
                } catch (t: Throwable) {
                    LOGGER.debug("sessionManager.get failed; falling back to findByName", t)
                }
            }
        }
        return try {
            WorldEdit.getInstance().sessionManager.findByName(name)
        } catch (t: Throwable) {
            LOGGER.debug("sessionManager.findByName failed", t)
            null
        }
    }

    /**
     * `com.sk89q.worldedit.fabric.FabricAdapter.adaptPlayer(ServerPlayer)` via
     * reflection - worldedit-fabric is not on the compile classpath (Loom mapping
     * mismatch; see fabric/build.gradle.kts), but its classes are present at runtime
     * whenever the `worldedit` mod is loaded.
     */
    private fun adaptPlayer(player: ServerPlayer): com.sk89q.worldedit.entity.Player? {
        return try {
            val adapterClass = Class.forName("com.sk89q.worldedit.fabric.FabricAdapter")
            val method = adapterClass.methods.first { it.name == "adaptPlayer" && it.parameterCount == 1 }
            method.invoke(null, player) as com.sk89q.worldedit.entity.Player
        } catch (t: Throwable) {
            LOGGER.debug("FabricAdapter.adaptPlayer unavailable: {}", t.toString())
            null
        }
    }

    /** Parse schematic [data]: detect the format, then try Sponge v3/v2 and MCEdit explicitly. */
    private fun readClipboard(data: ByteArray): Clipboard? {
        try {
            val detected = ClipboardFormats.findByInputStream { ByteArrayInputStream(data) }
            if (detected != null) {
                detected.getReader(ByteArrayInputStream(data)).use { reader ->
                    return reader.read()
                }
            }
        } catch (_: Exception) {
            // fall through to explicit attempts
        }
        val formatsToTry = listOf(
            BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC,
            BuiltInClipboardFormat.SPONGE_SCHEMATIC,
            BuiltInClipboardFormat.MCEDIT_SCHEMATIC,
        )
        for (format in formatsToTry) {
            try {
                format.getReader(ByteArrayInputStream(data)).use { reader ->
                    return reader.read()
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }
}
