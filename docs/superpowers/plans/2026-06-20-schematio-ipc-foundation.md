# Schematio IPC Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the client-mod ↔ plugin-server interop foundation: a plugin-messaging handshake that exchanges plugin version + capabilities, plus `/schematio` command deconfliction so the modded client and the server plugin stop colliding.

**Architecture:** A shared, pure-Kotlin wire protocol lives in `core/` (channel id, opcodes, capability bitset, and a `byte[]`-based codec). The Bukkit plugin uses the native `Messenger` API (raw `byte[]`) to answer a handshake with its version + capabilities. The Fabric client registers a typed `CustomPacketPayload` on the same channel, stores a per-connection `ServerSession`, and a `DispatchTable` decides — per `/schematio` subcommand — whether to run locally, forward the command to the server, or (reserved) use a packet.

**Tech Stack:** Kotlin 2.0.21, Java 21, Gradle 8.14, JUnit 5 (`org.junit.jupiter`), Bukkit/Spigot `Messenger` API, Fabric API networking (`PayloadTypeRegistry` / `ClientPlayNetworking`), Mojang official mappings, Stonecutter multi-version (1.21.8–26.1).

## Global Constraints

- **JDK 21 required.** Prefix every Gradle command with the JDK 21 home, e.g. `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew …`.
- **Server installs the plugin only** — no ProtocolLib or other server deps. Use Bukkit's built-in `Messenger` API exclusively.
- **Bandwidth-frugal** — one-shot handshake per modded join (~30–60 bytes total); no periodic traffic; zero cost for vanilla clients.
- **Channel id:** `schematio:c` (lowercase, namespaced). One channel, message types multiplexed via a leading opcode byte.
- **Protocol version:** integer constant, currently `1`, sent as a varint in every message.
- **Wire string format:** varint-length prefix + UTF-8 bytes (matches Minecraft `PacketByteBuf`). Do **not** use Guava `writeUTF` / `DataOutputStream.writeUTF` (incompatible modified-UTF).
- **Plugin stays feature-complete** with or without any mod.
- **Spec:** `docs/superpowers/specs/2026-06-20-schematio-ipc-foundation-design.md`.

## File Structure

**Created:**
- `core/src/main/kotlin/io/schemat/connector/core/ipc/IpcProtocol.kt` — channel id, opcodes, protocol version, capability bits.
- `core/src/main/kotlin/io/schemat/connector/core/ipc/IpcBuffer.kt` — `IpcWriter` / `IpcReader` (varint + MC-string primitives over `byte[]`).
- `core/src/main/kotlin/io/schemat/connector/core/ipc/IpcMessages.kt` — `HelloServer` / `HelloClient` data classes + `IpcCodec` encode/decode.
- `core/src/main/kotlin/io/schemat/connector/core/ipc/DispatchTable.kt` — pure dispatch-mode resolution.
- `core/src/test/kotlin/io/schemat/connector/core/ipc/IpcBufferTest.kt`
- `core/src/test/kotlin/io/schemat/connector/core/ipc/IpcCodecTest.kt`
- `core/src/test/kotlin/io/schemat/connector/core/ipc/DispatchTableTest.kt`
- `bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/PluginIpcService.kt` — channel registration + handshake responder.
- `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/SchematioPayload.kt` — typed `CustomPacketPayload` for `schematio:c`.
- `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerSession.kt` — per-connection detected state.
- `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerIpc.kt` — register payloads, receive HELLO_SERVER, reply HELLO_CLIENT.

**Modified:**
- `bukkit/src/main/kotlin/io/schemat/schematioConnector/SchematioConnector.kt` — instantiate + wire `PluginIpcService` in `onEnable`; register it as a `Listener`.
- `fabric/src/client/kotlin/io/schemat/connector/fabric/client/SchematioClientMod.kt` — init IPC, hold `ServerSession`, reset on connect/disconnect.
- `fabric/src/client/kotlin/io/schemat/connector/fabric/client/command/SchematioClientCommands.kt` — route overlapping subcommands through `DispatchTable`.

---

### Task 1: Core protocol constants

**Files:**
- Create: `core/src/main/kotlin/io/schemat/connector/core/ipc/IpcProtocol.kt`

**Interfaces:**
- Produces: `IpcProtocol.CHANNEL: String` = `"schematio:c"`, `IpcProtocol.VERSION: Int` = `1`; `IpcOpcode` (`HELLO_SERVER = 1`, `HELLO_CLIENT = 2`); `Capabilities` bit constants `DOWNLOAD_CMD`, `UPLOAD`, `VERSION_CONTROL`, `WANTS_COMMAND_OWNERSHIP` + `has(flags, bit)` helper.

- [ ] **Step 1: Write the file**

```kotlin
package io.schemat.connector.core.ipc

/** Wire protocol constants shared by the Bukkit plugin and the Fabric client. */
object IpcProtocol {
    /** Plugin-messaging channel id. Short to minimize per-packet bytes. */
    const val CHANNEL: String = "schematio:c"

    /** Current protocol version. Sent as a varint in every message; bump on breaking changes. */
    const val VERSION: Int = 1
}

/** First byte of every payload; selects the message type on a single multiplexed channel. */
object IpcOpcode {
    const val HELLO_SERVER: Int = 1
    const val HELLO_CLIENT: Int = 2
}

/** Capability flags advertised in the handshake (varint bitset). */
object Capabilities {
    const val DOWNLOAD_CMD: Int = 1 shl 0
    const val UPLOAD: Int = 1 shl 1               // reserved, off for POC
    const val VERSION_CONTROL: Int = 1 shl 2      // reserved (north star)
    const val WANTS_COMMAND_OWNERSHIP: Int = 1 shl 3

    fun has(flags: Int, bit: Int): Boolean = (flags and bit) != 0
}
```

- [ ] **Step 2: Compile core**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/io/schemat/connector/core/ipc/IpcProtocol.kt
git commit -m "feat(ipc): core protocol constants (channel, opcodes, capabilities)"
```

---

### Task 2: Core wire primitives (varint + string), TDD

**Files:**
- Create: `core/src/main/kotlin/io/schemat/connector/core/ipc/IpcBuffer.kt`
- Test: `core/src/test/kotlin/io/schemat/connector/core/ipc/IpcBufferTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `class IpcWriter()` with `writeByte(v: Int)`, `writeVarInt(v: Int)`, `writeString(s: String)`, `toByteArray(): ByteArray`.
  - `class IpcReader(bytes: ByteArray)` with `readByte(): Int` (unsigned 0–255), `readVarInt(): Int`, `readString(): String`, `remaining(): Int`.
  - `class IpcFormatException(message: String) : RuntimeException(message)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

class IpcBufferTest {

    @Test
    fun `byte round-trips as unsigned`() {
        val bytes = IpcWriter().apply { writeByte(200) }.toByteArray()
        assertEquals(200, IpcReader(bytes).readByte())
    }

    @Test
    fun `varint round-trips across boundaries`() {
        for (v in intArrayOf(0, 1, 127, 128, 255, 256, 16383, 16384, 2097151, Int.MAX_VALUE)) {
            val bytes = IpcWriter().apply { writeVarInt(v) }.toByteArray()
            assertEquals(v, IpcReader(bytes).readVarInt(), "varint $v")
        }
    }

    @Test
    fun `string round-trips including unicode`() {
        val s = "Schematio v1.2.4 — café ✓"
        val bytes = IpcWriter().apply { writeString(s) }.toByteArray()
        assertEquals(s, IpcReader(bytes).readString())
    }

    @Test
    fun `mixed sequence reads back in order`() {
        val bytes = IpcWriter().apply {
            writeByte(1); writeVarInt(300); writeString("hi")
        }.toByteArray()
        val r = IpcReader(bytes)
        assertEquals(1, r.readByte())
        assertEquals(300, r.readVarInt())
        assertEquals("hi", r.readString())
        assertEquals(0, r.remaining())
    }

    @Test
    fun `truncated buffer throws IpcFormatException`() {
        assertThrows(IpcFormatException::class.java) { IpcReader(ByteArray(0)).readByte() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test --tests "io.schemat.connector.core.ipc.IpcBufferTest"`
Expected: FAIL — unresolved references `IpcWriter` / `IpcReader` / `IpcFormatException`.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.schemat.connector.core.ipc

/** Thrown when a buffer is malformed or truncated. */
class IpcFormatException(message: String) : RuntimeException(message)

/**
 * Minimal big-endian-agnostic writer using Minecraft-compatible primitives:
 * varints and varint-length-prefixed UTF-8 strings.
 */
class IpcWriter {
    private val out = ArrayList<Byte>(32)

    fun writeByte(v: Int) { out.add((v and 0xFF).toByte()) }

    fun writeVarInt(v: Int) {
        var value = v
        while (true) {
            if ((value and 0x7F.inv()) == 0) { out.add(value.toByte()); return }
            out.add(((value and 0x7F) or 0x80).toByte())
            value = value ushr 7
        }
    }

    fun writeString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeVarInt(bytes.size)
        for (b in bytes) out.add(b)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads the primitives written by [IpcWriter]. */
class IpcReader(private val bytes: ByteArray) {
    private var pos = 0

    fun remaining(): Int = bytes.size - pos

    fun readByte(): Int {
        if (pos >= bytes.size) throw IpcFormatException("readByte past end of buffer")
        return bytes[pos++].toInt() and 0xFF
    }

    fun readVarInt(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = readByte()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            if (shift >= 35) throw IpcFormatException("varint too long")
        }
    }

    fun readString(): String {
        val len = readVarInt()
        if (len < 0 || len > remaining()) throw IpcFormatException("string length $len exceeds buffer")
        val s = String(bytes, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test --tests "io.schemat.connector.core.ipc.IpcBufferTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/io/schemat/connector/core/ipc/IpcBuffer.kt core/src/test/kotlin/io/schemat/connector/core/ipc/IpcBufferTest.kt
git commit -m "feat(ipc): wire primitives (varint + MC-string) with round-trip tests"
```

---

### Task 3: Core message codec (HELLO_SERVER / HELLO_CLIENT), TDD

**Files:**
- Create: `core/src/main/kotlin/io/schemat/connector/core/ipc/IpcMessages.kt`
- Test: `core/src/test/kotlin/io/schemat/connector/core/ipc/IpcCodecTest.kt`

**Interfaces:**
- Consumes: `IpcWriter`, `IpcReader`, `IpcOpcode`, `IpcFormatException` (Tasks 1–2).
- Produces:
  - `data class HelloServer(val protocolVersion: Int, val pluginVersion: String, val capabilities: Int)`
  - `data class HelloClient(val protocolVersion: Int, val modVersion: String, val clientFlags: Int)`
  - `object IpcCodec` with `encodeHelloServer(HelloServer): ByteArray`, `encodeHelloClient(HelloClient): ByteArray`, `peekOpcode(ByteArray): Int`, `decodeHelloServer(ByteArray): HelloServer`, `decodeHelloClient(ByteArray): HelloClient`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

class IpcCodecTest {

    @Test
    fun `hello server round-trips`() {
        val msg = HelloServer(
            protocolVersion = IpcProtocol.VERSION,
            pluginVersion = "1.2.4",
            capabilities = Capabilities.DOWNLOAD_CMD or Capabilities.WANTS_COMMAND_OWNERSHIP,
        )
        val bytes = IpcCodec.encodeHelloServer(msg)
        assertEquals(IpcOpcode.HELLO_SERVER, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeHelloServer(bytes))
    }

    @Test
    fun `hello client round-trips`() {
        val msg = HelloClient(IpcProtocol.VERSION, "1.2.4", 0)
        val bytes = IpcCodec.encodeHelloClient(msg)
        assertEquals(IpcOpcode.HELLO_CLIENT, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeHelloClient(bytes))
    }

    @Test
    fun `decoding wrong opcode throws`() {
        val bytes = IpcCodec.encodeHelloClient(HelloClient(1, "x", 0))
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeHelloServer(bytes) }
    }

    @Test
    fun `peekOpcode on empty buffer throws`() {
        assertThrows(IpcFormatException::class.java) { IpcCodec.peekOpcode(ByteArray(0)) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test --tests "io.schemat.connector.core.ipc.IpcCodecTest"`
Expected: FAIL — unresolved `HelloServer` / `HelloClient` / `IpcCodec`.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.schemat.connector.core.ipc

data class HelloServer(
    val protocolVersion: Int,
    val pluginVersion: String,
    val capabilities: Int,
)

data class HelloClient(
    val protocolVersion: Int,
    val modVersion: String,
    val clientFlags: Int,
)

/** Encodes/decodes IPC messages to/from raw byte arrays (the plugin-message body). */
object IpcCodec {

    fun encodeHelloServer(msg: HelloServer): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.HELLO_SERVER)
        writeVarInt(msg.protocolVersion)
        writeString(msg.pluginVersion)
        writeVarInt(msg.capabilities)
    }.toByteArray()

    fun encodeHelloClient(msg: HelloClient): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.HELLO_CLIENT)
        writeVarInt(msg.protocolVersion)
        writeString(msg.modVersion)
        writeVarInt(msg.clientFlags)
    }.toByteArray()

    /** Reads only the leading opcode without consuming the rest. */
    fun peekOpcode(bytes: ByteArray): Int = IpcReader(bytes).readByte()

    fun decodeHelloServer(bytes: ByteArray): HelloServer {
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.HELLO_SERVER) throw IpcFormatException("expected HELLO_SERVER, got $op")
        return HelloServer(
            protocolVersion = r.readVarInt(),
            pluginVersion = r.readString(),
            capabilities = r.readVarInt(),
        )
    }

    fun decodeHelloClient(bytes: ByteArray): HelloClient {
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.HELLO_CLIENT) throw IpcFormatException("expected HELLO_CLIENT, got $op")
        return HelloClient(
            protocolVersion = r.readVarInt(),
            modVersion = r.readString(),
            clientFlags = r.readVarInt(),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test --tests "io.schemat.connector.core.ipc.IpcCodecTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/io/schemat/connector/core/ipc/IpcMessages.kt core/src/test/kotlin/io/schemat/connector/core/ipc/IpcCodecTest.kt
git commit -m "feat(ipc): HELLO_SERVER/HELLO_CLIENT message codec with round-trip tests"
```

---

### Task 4: Core dispatch-table resolution, TDD

**Files:**
- Create: `core/src/main/kotlin/io/schemat/connector/core/ipc/DispatchTable.kt`
- Test: `core/src/test/kotlin/io/schemat/connector/core/ipc/DispatchTableTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class DispatchMode { LOCAL, SERVER_COMMAND, PACKET }`
  - `enum class ClientAction { BROWSE, UPLOAD, DOWNLOAD, QUICKSHARE, QUICKSHARE_GET }`
  - `object DispatchTable` with `resolve(action: ClientAction, pluginPresent: Boolean): DispatchMode`.

Rationale (POC policy): with no plugin, everything is `LOCAL` (today's behavior). With the plugin present, browsing stays `LOCAL` (the nice in-client UI), while download/upload/quickshare actions forward to the server (`SERVER_COMMAND`) so the authoritative plugin does the work and there is no command collision. `PACKET` is reserved (returned for no action yet) for later rich flows.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class DispatchTableTest {

    @Test
    fun `everything is local when no plugin`() {
        for (a in ClientAction.entries) {
            assertEquals(DispatchMode.LOCAL, DispatchTable.resolve(a, pluginPresent = false), a.name)
        }
    }

    @Test
    fun `browse stays local when plugin present`() {
        assertEquals(DispatchMode.LOCAL, DispatchTable.resolve(ClientAction.BROWSE, pluginPresent = true))
    }

    @Test
    fun `server-backed actions forward when plugin present`() {
        for (a in listOf(ClientAction.DOWNLOAD, ClientAction.UPLOAD, ClientAction.QUICKSHARE, ClientAction.QUICKSHARE_GET)) {
            assertEquals(DispatchMode.SERVER_COMMAND, DispatchTable.resolve(a, pluginPresent = true), a.name)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test --tests "io.schemat.connector.core.ipc.DispatchTableTest"`
Expected: FAIL — unresolved `DispatchMode` / `ClientAction` / `DispatchTable`.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.schemat.connector.core.ipc

enum class DispatchMode { LOCAL, SERVER_COMMAND, PACKET }

enum class ClientAction { BROWSE, UPLOAD, DOWNLOAD, QUICKSHARE, QUICKSHARE_GET }

/**
 * Decides how a client `/schematio` action is handled. POC policy only; later this will
 * also consult client config and negotiated server capabilities (e.g. WANTS_COMMAND_OWNERSHIP).
 */
object DispatchTable {
    fun resolve(action: ClientAction, pluginPresent: Boolean): DispatchMode {
        if (!pluginPresent) return DispatchMode.LOCAL
        return when (action) {
            ClientAction.BROWSE -> DispatchMode.LOCAL
            ClientAction.DOWNLOAD,
            ClientAction.UPLOAD,
            ClientAction.QUICKSHARE,
            ClientAction.QUICKSHARE_GET -> DispatchMode.SERVER_COMMAND
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test --tests "io.schemat.connector.core.ipc.DispatchTableTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/io/schemat/connector/core/ipc/DispatchTable.kt core/src/test/kotlin/io/schemat/connector/core/ipc/DispatchTableTest.kt
git commit -m "feat(ipc): dispatch-table resolution with tests"
```

---

### Task 5: Bukkit handshake responder

**Files:**
- Create: `bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/PluginIpcService.kt`
- Modify: `bukkit/src/main/kotlin/io/schemat/schematioConnector/SchematioConnector.kt` (in `onEnable`, after `server.pluginManager.registerEvents(this, this)`)

**Interfaces:**
- Consumes: `IpcProtocol`, `IpcCodec`, `HelloServer`, `HelloClient`, `Capabilities`, `IpcFormatException` (core).
- Produces: `class PluginIpcService(plugin: JavaPlugin)` implementing `PluginMessageListener, Listener` with `fun register()` and an internal handshake responder.

- [ ] **Step 1: Write `PluginIpcService`**

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.HelloClient
import io.schemat.connector.core.ipc.HelloServer
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcFormatException
import io.schemat.connector.core.ipc.IpcOpcode
import io.schemat.connector.core.ipc.IpcProtocol
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the Schematio IPC handshake over the plugin-messaging channel.
 * Runs entirely on the main thread; does no blocking work in the listener.
 */
class PluginIpcService(private val plugin: JavaPlugin) : PluginMessageListener, Listener {

    /** Players we have already greeted this session, to dedupe register-event vs client-hello triggers. */
    private val greeted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Capabilities this build supports. WANTS_COMMAND_OWNERSHIP advertises intent; client logic is POC-gated. */
    private val capabilities: Int = Capabilities.DOWNLOAD_CMD or Capabilities.WANTS_COMMAND_OWNERSHIP

    fun register() {
        val messenger = plugin.server.messenger
        messenger.registerOutgoingPluginChannel(plugin, IpcProtocol.CHANNEL)
        messenger.registerIncomingPluginChannel(plugin, IpcProtocol.CHANNEL, this)
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("Schematio IPC registered on channel ${IpcProtocol.CHANNEL}")
    }

    /** Client advertised our channel via minecraft:register — greet it proactively. */
    @EventHandler
    fun onRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (event.channel == IpcProtocol.CHANNEL) greet(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        greeted.remove(event.player.uniqueId)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != IpcProtocol.CHANNEL) return
        try {
            when (IpcCodec.peekOpcode(message)) {
                IpcOpcode.HELLO_CLIENT -> {
                    val hello: HelloClient = IpcCodec.decodeHelloClient(message)
                    plugin.logger.info("Schematio mod present for ${player.name}: v${hello.modVersion} (proto ${hello.protocolVersion})")
                    greet(player) // fallback path; deduped
                }
                else -> { /* unknown/opcode we don't handle as a request; ignore */ }
            }
        } catch (e: IpcFormatException) {
            plugin.logger.warning("Malformed Schematio IPC from ${player.name}: ${e.message}")
        }
    }

    private fun greet(player: Player) {
        if (!greeted.add(player.uniqueId)) return
        if (!player.listeningPluginChannels.contains(IpcProtocol.CHANNEL)) {
            greeted.remove(player.uniqueId) // not ready yet; allow a later trigger to retry
            return
        }
        val hello = HelloServer(
            protocolVersion = IpcProtocol.VERSION,
            pluginVersion = plugin.description.version,
            capabilities = capabilities,
        )
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeHelloServer(hello))
    }
}
```

- [ ] **Step 2: Wire it into `onEnable`**

In `bukkit/src/main/kotlin/io/schemat/schematioConnector/SchematioConnector.kt`, add the import near the other imports:

```kotlin
import io.schemat.schematioConnector.ipc.PluginIpcService
```

Then, immediately after the existing line `server.pluginManager.registerEvents(this, this)` in `onEnable()`, add:

```kotlin
    // Client↔mod interop (plugin-messaging handshake + capability advertisement)
    PluginIpcService(this).register()
```

- [ ] **Step 3: Compile the plugin**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/PluginIpcService.kt bukkit/src/main/kotlin/io/schemat/schematioConnector/SchematioConnector.kt
git commit -m "feat(ipc): Bukkit handshake responder advertising version + capabilities"
```

---

### Task 6: Fabric payload + networking + session

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/SchematioPayload.kt`
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerSession.kt`
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerIpc.kt`
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/SchematioClientMod.kt` (in `onInitializeClient`)

**Interfaces:**
- Consumes: `IpcProtocol`, `IpcCodec`, `HelloServer`, `HelloClient`, `Capabilities` (core).
- Produces:
  - `class SchematioPayload(val data: ByteArray)` — typed `CustomPacketPayload`, `TYPE`, `CODEC` (reads all readable bytes — Bukkit sends a raw body with no length prefix).
  - `object ServerSession` with `@Volatile var pluginPresent: Boolean`, `pluginVersion: String?`, `protocolVersion: Int`, `capabilities: Int`, `fun reset()`, `fun adopt(hello: HelloServer)`.
  - `object ServerIpc` with `fun init()` (register payload types + receiver), `fun sendClientHello()`.

> **Verify-first (Stonecutter / mappings):** Before writing code, confirm the exact symbol shapes for the *active* Fabric version. Run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:dependencies --configuration modImplementation | head -40` is not needed; instead check the API directly by searching the Loom-decompiled sources or Fabric docs for: `net.minecraft.network.protocol.common.custom.CustomPacketPayload` (`type()` returning `CustomPacketPayload.Type<T>`), `net.minecraft.network.codec.StreamCodec.of`, `net.minecraft.network.RegistryFriendlyByteBuf`, `net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry`, and `net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking` (`registerGlobalReceiver(Type, PlayPayloadHandler)`, `send(payload)`). The code below targets the 1.21.11 Mojang-mappings shape; if 26.1 differs, gate the diverging lines with a Stonecutter comment (`//? if >=1.21.11 {` … `}`) following the repo's existing render-layer convention.

- [ ] **Step 1: Write `SchematioPayload`**

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.IpcProtocol
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** Wraps the raw IPC body. One payload type per channel; opcode multiplexing happens inside [data]. */
class SchematioPayload(val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SchematioPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SchematioPayload> =
            CustomPacketPayload.Type(ResourceLocation.parse(IpcProtocol.CHANNEL))

        /** Reads/writes the entire buffer with no length prefix, matching Bukkit's raw byte[] body. */
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SchematioPayload> =
            StreamCodec.of(
                { buf, payload -> buf.writeBytes(payload.data) },
                { buf ->
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    SchematioPayload(bytes)
                },
            )
    }
}
```

- [ ] **Step 2: Write `ServerSession`**

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.HelloServer

/** Per-connection state about the server's Schematio plugin. Reset on join/disconnect. */
object ServerSession {
    @Volatile var pluginPresent: Boolean = false
        private set
    @Volatile var pluginVersion: String? = null
        private set
    @Volatile var protocolVersion: Int = 0
        private set
    @Volatile var capabilities: Int = 0
        private set

    fun adopt(hello: HelloServer) {
        pluginVersion = hello.pluginVersion
        protocolVersion = hello.protocolVersion
        capabilities = hello.capabilities
        pluginPresent = true
    }

    fun reset() {
        pluginPresent = false
        pluginVersion = null
        protocolVersion = 0
        capabilities = 0
    }
}
```

- [ ] **Step 3: Write `ServerIpc`**

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.HelloClient
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcFormatException
import io.schemat.connector.core.ipc.IpcOpcode
import io.schemat.connector.core.ipc.IpcProtocol
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

object ServerIpc {
    private val LOGGER = LoggerFactory.getLogger("SchematioIpc")
    private const val MOD_ID = "schematioconnector"

    fun init() {
        PayloadTypeRegistry.playS2C().register(SchematioPayload.TYPE, SchematioPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SchematioPayload.TYPE, SchematioPayload.CODEC)

        ClientPlayNetworking.registerGlobalReceiver(SchematioPayload.TYPE) { payload, _ ->
            // Fabric invokes this on the client thread.
            handle(payload.data)
        }
    }

    private fun handle(data: ByteArray) {
        try {
            when (IpcCodec.peekOpcode(data)) {
                IpcOpcode.HELLO_SERVER -> {
                    val hello = IpcCodec.decodeHelloServer(data)
                    ServerSession.adopt(hello)
                    LOGGER.info("Connected to Schematio server v${hello.pluginVersion} (proto ${hello.protocolVersion})")
                    Minecraft.getInstance().player?.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§aSchematio server detected: v${hello.pluginVersion}"),
                        false,
                    )
                    sendClientHello()
                }
                else -> { /* ignore unknown opcodes */ }
            }
        } catch (e: IpcFormatException) {
            LOGGER.warn("Malformed Schematio IPC from server: ${e.message}")
        }
    }

    fun sendClientHello() {
        val version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
        val bytes = IpcCodec.encodeHelloClient(HelloClient(IpcProtocol.VERSION, version, 0))
        if (ClientPlayNetworking.canSend(SchematioPayload.TYPE)) {
            ClientPlayNetworking.send(SchematioPayload(bytes))
        }
    }
}
```

- [ ] **Step 4: Wire into `onInitializeClient`**

In `SchematioClientMod.kt`, add imports:

```kotlin
import io.schemat.connector.fabric.client.ipc.ServerIpc
import io.schemat.connector.fabric.client.ipc.ServerSession
```

Inside `onInitializeClient()`, after `services = ClientServices(authManager)`, add:

```kotlin
        // Client↔plugin interop: register channel + handshake.
        ServerIpc.init()
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            ServerSession.reset()
            ServerIpc.sendClientHello() // fallback trigger; server greets on register-event too
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ServerSession.reset()
        }
```

(`ClientPlayConnectionEvents` is already imported in this file per the existing JOIN handler. If `DISCONNECT` is not yet imported, it is the same import path `net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents`.)

- [ ] **Step 5: Build the Fabric client (active Stonecutter version)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:build`
Expected: BUILD SUCCESSFUL. If symbol-resolution errors appear, apply the verify-first note (adjust to the active version's API / add Stonecutter gates), then rebuild.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ fabric/src/client/kotlin/io/schemat/connector/fabric/client/SchematioClientMod.kt
git commit -m "feat(ipc): Fabric handshake receiver, ServerSession, client hello"
```

---

### Task 7: Fabric command deconfliction via DispatchTable

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/command/SchematioClientCommands.kt`

**Interfaces:**
- Consumes: `DispatchTable`, `DispatchMode`, `ClientAction` (core); `ServerSession` (Task 6).
- Produces: a `forwardToServer(command: String)` helper and per-action routing in the existing command executors.

Approach: only the subcommands the client *defines* are intercepted locally; subcommands only the server defines already pass through. So we route each overlapping executor: if `DispatchTable.resolve(action, ServerSession.pluginPresent) == SERVER_COMMAND`, forward the equivalent server command; otherwise run today's local logic. `BROWSE` stays local.

- [ ] **Step 1: Add the forwarding helper**

In `SchematioClientCommands.kt`, add imports:

```kotlin
import io.schemat.connector.core.ipc.ClientAction
import io.schemat.connector.core.ipc.DispatchMode
import io.schemat.connector.core.ipc.DispatchTable
import io.schemat.connector.fabric.client.ipc.ServerSession
import net.minecraft.client.Minecraft
```

Add this private helper inside the `object SchematioClientCommands`:

```kotlin
    /** Sends a `/`-less command line to the server (vanilla command packet). */
    private fun forwardToServer(command: String): Int {
        Minecraft.getInstance().player?.connection?.sendCommand(command)
        return com.mojang.brigadier.Command.SINGLE_SUCCESS
    }
```

> **Verify-first:** confirm the command-send method name for the active mappings. In Mojang mappings 1.21.x, `net.minecraft.client.multiplayer.ClientPacketListener` (returned by `player.connection`) exposes `sendCommand(String)`. If the active version differs, use the equivalent (`sendUnsignedCommand` / `sendChatCommand`) and gate with a Stonecutter comment.

- [ ] **Step 2: Route the `download` executor**

Replace the body of the `download` `id` executor so it forwards when the plugin owns downloads:

```kotlin
                                .executes { ctx ->
                                    val id = StringArgumentType.getString(ctx, "id")
                                    if (DispatchTable.resolve(ClientAction.DOWNLOAD, ServerSession.pluginPresent) == DispatchMode.SERVER_COMMAND) {
                                        forwardToServer("schematio download $id")
                                    } else {
                                        downloadAndLoad(id, password = null, label = "schematic")
                                    }
                                }
```

- [ ] **Step 3: Route the `quickshareget` executor**

```kotlin
                                .executes { ctx ->
                                    val code = StringArgumentType.getString(ctx, "accessCode")
                                    if (DispatchTable.resolve(ClientAction.QUICKSHARE_GET, ServerSession.pluginPresent) == DispatchMode.SERVER_COMMAND) {
                                        forwardToServer("schematio quickshareget $code")
                                    } else {
                                        downloadAndLoad(code, password = null, label = "quick share")
                                    }
                                }
```

- [ ] **Step 4: Route `upload` and `quickshare` executors**

For the `upload` literal executor:

```kotlin
                        .executes { ctx ->
                            if (DispatchTable.resolve(ClientAction.UPLOAD, ServerSession.pluginPresent) == DispatchMode.SERVER_COMMAND) {
                                forwardToServer("schematio upload")
                            } else {
                                openPanel(ctx) { UploadWizardPanel.open() }
                            }
                        }
```

For the `quickshare` literal executor:

```kotlin
                        .executes { ctx ->
                            if (DispatchTable.resolve(ClientAction.QUICKSHARE, ServerSession.pluginPresent) == DispatchMode.SERVER_COMMAND) {
                                forwardToServer("schematio quickshare")
                            } else {
                                openPanel(ctx) { QuickShareCreatePanel.show(null) }
                            }
                        }
```

Leave the root / `open` / `browse` executors unchanged — `BROWSE` resolves to `LOCAL` in all cases, which is the desired in-client browser.

- [ ] **Step 5: Build the Fabric client**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/command/SchematioClientCommands.kt
git commit -m "feat(ipc): route /schematio actions via DispatchTable (deconflict with plugin)"
```

---

### Task 8: End-to-end manual verification

**Files:** none (verification only).

This is the integration gate the unit tests can't cover. Requires a local Spigot/Paper server with the built plugin jar and a dev client with the mod.

- [ ] **Step 1: Build both artifacts**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:build :fabric:build`
Expected: BUILD SUCCESSFUL; note the produced jars under `bukkit/build/libs/` and `fabric/build/libs/`.

- [ ] **Step 2: Install + run server**

Copy the bukkit jar into a local Spigot/Paper `plugins/` dir and start the server. Confirm the log line: `Schematio IPC registered on channel schematio:c`.

- [ ] **Step 3: Join with the modded client**

Launch the dev client (`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:runClient` for the active version) and connect to the server.
Expected:
- Client chat shows `Schematio server detected: v1.2.4`.
- Server log shows `Schematio mod present for <name>: v1.2.4`.

- [ ] **Step 4: Verify deconfliction**

On the server (plugin present), run `/schematio download <id>`.
Expected: the **server** plugin handles it (server-side download/paste), not the client's local HTTP path — confirm via server log / behavior. `/schematio` (no args) or `/schematio browse` still opens the **client** ImGui browser (LOCAL).

- [ ] **Step 5: Verify graceful absence**

Join a server **without** the plugin.
Expected: no detection message; `ServerSession.pluginPresent` stays false; `/schematio download <id>` runs the client's local path as before. No errors in client log.

- [ ] **Step 6: Document the result**

Append a short "Verified on <MC version> / <server type>" note to the spec's testing section (or a follow-up comment in the PR). No code commit required.

---

## Self-Review

**Spec coverage:**
- §1 protocol in `core/` → Tasks 1–4 ✓
- §2 handshake (server-initiated + client fallback) → Task 5 (server greet on register-event + on client-hello) + Task 6 (client sends hello on join) ✓
- §3 deconfliction + dispatch table → Tasks 4 + 7 (refined to per-executor forwarding; greedy capture dropped — documented) ✓
- §4 Bukkit side → Task 5 ✓
- §5 Fabric side → Tasks 6 ✓
- §6 error handling → malformed-payload guards in Tasks 5–6; `ServerSession.reset` on disconnect (Task 6) ✓
- Testing (codec TDD + manual E2E) → Tasks 2–4 (unit) + Task 8 (manual) ✓
- Reserved seams (PACKET mode, capability flags, VERSION_CONTROL bit) → Tasks 1 + 4 ✓

**Placeholder scan:** No TBD/TODO; all code blocks are complete. The two "verify-first" notes are deliberate API-confirmation steps (Stonecutter/mappings churn), each with concrete fallbacks — not deferred work.

**Type consistency:** `HelloServer`/`HelloClient` fields, `IpcCodec` method names, `ServerSession.adopt/reset`, `DispatchTable.resolve(action, pluginPresent)`, and `ClientAction` enum values are consistent across Tasks 1–7. `SchematioPayload.TYPE`/`CODEC` consistent between Tasks 5–6.

**Known risks carried forward:**
1. Fabric networking symbol churn across 1.21.8–26.1 (Task 6 verify-first + Stonecutter gating).
2. `ClientPacketListener.sendCommand` name across versions (Task 7 verify-first).
3. Bukkit register-event timing — mitigated by the dedupe + client-hello fallback (Task 5 `greet` retries if channel not yet listening).
