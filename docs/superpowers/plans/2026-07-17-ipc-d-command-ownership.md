# IPC Sub-project D — Command Ownership Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-07-17-ipc-command-ownership-design.md` (opcode renumbered: OPEN_UI = 9).

**Goal:** When a modded player on an attested session advertised `WANTS_COMMAND_OWNERSHIP`, the plugin's menu-style `/schematio` surfaces open the client's ImGui UI (via a new `OPEN_UI` S→C message) instead of printing chat menus; chat remains the standalone fallback and vanilla clients are untouched.

**Architecture:** core gains the `OPEN_UI = 9` opcode + `OpenUi` message + 128-byte cap, following sub-project B's codec conventions exactly. On bukkit, a pure `CommandOwnershipRouting` object decides (a) whether a player qualifies (attested × client-flag matrix) and (b) which invocation maps to which surface; `SchematioCommand` consults it before dispatch and `PluginIpcService` sends the frame + tracks per-player `clientFlags`. On fabric, a pure clock-injected `OpenUiGate` enforces the spec's three client-side invariants (VERIFIED session, user toggle, 1-per-2s), `ServerIpc` maps surfaces onto the existing `PanelManager` opens, and `SettingsPanel` gets the persisted `allow_server_open_ui` toggle (config.properties via `ClientAuthManager`).

**Tech Stack:** Kotlin 2.4, JDK 21, Gradle + Stonecutter, JUnit 5. No new dependencies.

## Global Constraints

- **Do NOT git commit.** Leave the tree dirty for review. Stay on branch `feature/ingame-diff-viewer`.
- **Prerequisites:** sub-projects A (`2026-07-17-ipc-a-handshake-v2.md`) and B (`2026-07-17-ipc-b-clipboard-load.md`) are implemented first. This plan's "Modify" anchors quote **their final file states** (e.g. `PluginIpcService` already has the `attested` set and `checkCap` exists in `IpcCodec`). If a landed line differs trivially, keep the landed line and apply this plan's *additions* around it.
- **Test commands** (always run from `/Users/harrison/IdeaProjects/SchematioConnector`, always with the JAVA_HOME prefix):
  - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
  - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
  - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
- **Touch only the files each task names.** Never reformat or "fix" unrelated code.
- **No command behavior change for vanilla clients.** A player whose client never sent `HELLO_CLIENT` flags (or is unattested) must go through the exact pre-existing chat code paths.
- SchematioConnector repo only — no schemati (backend) changes in this sub-project.

## Resolved ambiguities (spec ↔ this codebase)

1. **"`/schematio here <id>`-style detail commands":** no `here` subcommand exists. The repo's detail-by-id command is `/schematio download <id>` (it is also what chat list rows click-run). On an ownership-qualified session it maps to `SCHEMATIC_DETAIL` with `contextId = <id>`; the client detail panel carries its own Download / "Load on server" actions, so no capability is lost.
2. **help surface:** the no-args `/schematio` help menu hands off to `BROWSE` (the client UI hub — there is no "help" surface in the protocol).
3. **Bare-only handoff:** `list`/`search`/`upload`/`quickshare`/`settings` hand off only when invoked **bare**. Argful invocations (`list castle 2`, `upload <name>`, `settings ui chat`, …) carry query/action context `OPEN_UI` cannot convey and keep the classic flow. `download <id>` is the one argful handoff (ambiguity 1).
4. **"client advertised WANTS_COMMAND_OWNERSHIP":** the client sets the existing `Capabilities.WANTS_COMMAND_OWNERSHIP` bit (1 shl 3) in `HELLO_CLIENT.clientFlags` — and only while the user's `allow_server_open_ui` toggle is ON at hello time. Mid-session toggling OFF is enforced by the client-side drop (spec invariant 2); the full chat fallback returns after rejoin. The server side ("capability negotiated") is already satisfied: bukkit's `PluginIpcService.capabilities` has advertised `WANTS_COMMAND_OWNERSHIP` since the IPC foundation.
5. **"toast error":** the fabric client has no toast framework; the error surface is a client-local chat line via the existing stonecutter-guarded `displayClientMessage`/`sendSystemMessage` pattern already used in `ServerIpc`.
6. **Rate limit semantics:** only an **accepted** `OPEN_UI` consumes the 2 s slot; dropped ones (unattested / toggle-off / over-rate) never extend the window.
7. **Opcodes 7–8** are left reserved for sub-project C (clipboard upload); D uses 9 per the renumbered spec.

---

## Task 1 — :core — OPEN_UI opcode 9, surface enum, message, 128-byte cap, codec

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcProtocol.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcMessages.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/ipc/IpcCodecTest.kt` (append tests + extend one existing test)

**Interfaces:**
- Consumes: B's codec conventions — `IpcWriter`/`IpcReader` primitives, `IpcCodec.checkCap(bytes, opcode)` (private cap guard), `IpcFormatException`, `IpcPayloadTooLargeException`, `IpcCaps.forOpcode`.
- Produces (exact signatures — later tasks rely on these):

```kotlin
// IpcProtocol.kt
object IpcOpcode { /* existing 1..6 */ const val OPEN_UI: Int = 9 }   // 7-8 reserved for sub-project C
object IpcCaps { /* existing */ const val OPEN_UI: Int = 128 /* forOpcode gains an arm */ }
enum class OpenUiSurface(val wire: Int) { BROWSE(0), UPLOAD(1), SHARES(2), SCHEMATIC_DETAIL(3), SETTINGS(4);
    companion object { fun fromWire(wire: Int): OpenUiSurface? } }

// IpcMessages.kt
data class OpenUi(val protocolVersion: Int, val surface: Int, val contextId: String = "") {
    companion object { const val MAX_CONTEXT_CHARS: Int = 64 } }
// IpcCodec gains: encodeOpenUi(msg: OpenUi): ByteArray, decodeOpenUi(bytes: ByteArray): OpenUi
```

- [ ] **Step 1.1: Write the failing tests**

Append to `IpcCodecTest.kt` (same class, after the STATUS tests):

```kotlin
    @Test
    fun `open ui round-trips for every surface`() {
        for (surface in OpenUiSurface.entries) {
            val msg = OpenUi(
                protocolVersion = IpcProtocol.VERSION,
                surface = surface.wire,
                contextId = if (surface == OpenUiSurface.SCHEMATIC_DETAIL) "11111111-2222-3333-4444-555555555555" else "",
            )
            val bytes = IpcCodec.encodeOpenUi(msg)
            assertEquals(IpcOpcode.OPEN_UI, IpcCodec.peekOpcode(bytes))
            assertTrue(bytes.size <= IpcCaps.OPEN_UI)
            assertEquals(msg, IpcCodec.decodeOpenUi(bytes))
        }
    }

    @Test
    fun `open ui rejects bad fields at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenUi(2, 9) // unknown surface
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenUi(2, OpenUiSurface.SCHEMATIC_DETAIL.wire, "a".repeat(65)) // contextId over 64 chars
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenUi(2, OpenUiSurface.BROWSE.wire, "some-id") // contextId only valid for SCHEMATIC_DETAIL
        }
    }

    @Test
    fun `decoding a hand-built open ui with an unknown surface throws IpcFormatException`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.OPEN_UI)
            writeVarInt(2)
            writeByte(9) // surface from the future
            writeString("")
        }.toByteArray()
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeOpenUi(bytes) }
    }

    @Test
    fun `decoding open ui with wrong opcode throws`() {
        val bytes = IpcCodec.encodeStatus(Status(2, 1, StatusState.OK.wire, ""))
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeOpenUi(bytes) }
    }

    @Test
    fun `oversize open ui payloads are rejected before parsing`() {
        val fat = ByteArray(IpcCaps.OPEN_UI + 1).also { it[0] = IpcOpcode.OPEN_UI.toByte() }
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeOpenUi(fat) }
    }
```

Also EXTEND the existing test `every live opcode has a size cap` (added by B): append `IpcOpcode.OPEN_UI` to its `intArrayOf(...)` so it reads:

```kotlin
        for (opcode in intArrayOf(
            IpcOpcode.HELLO_SERVER, IpcOpcode.HELLO_CLIENT, IpcOpcode.ATTEST,
            IpcOpcode.LOAD_REQUEST, IpcOpcode.STATUS, IpcOpcode.OPEN_UI,
        )) {
            assertNotNull(IpcCaps.forOpcode(opcode), "opcode $opcode has no cap")
        }
```

- [ ] **Step 1.2: Run to verify compilation fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: compilation FAILS (no `OpenUi`, `OpenUiSurface`, `IpcOpcode.OPEN_UI`, `IpcCaps.OPEN_UI`).

- [ ] **Step 1.3: Implement**

`IpcProtocol.kt` — three edits:

(a) Append to `IpcOpcode` (after `const val STATUS: Int = 6`):

```kotlin
    // Opcodes 7-8 are reserved for IPC sub-project C (clipboard upload).

    /**
     * S2C: command-ownership handoff — ask the client to open a UI surface instead of a
     * chat menu (protocol v2, sub-project D). Honored client-side only on a VERIFIED
     * session, with the user's allow-server-open-ui toggle on, at most once per 2 s.
     */
    const val OPEN_UI: Int = 9
```

(b) In `IpcCaps`, add the constant (after `const val STATUS: Int = 512`) and the `forOpcode` arm (before `else -> null`):

```kotlin
    const val OPEN_UI: Int = 128
```

```kotlin
        IpcOpcode.OPEN_UI -> OPEN_UI
```

(c) Append at the end of the file (after `StatusState`):

```kotlin
/** UI surface targeted by an OPEN_UI handoff. Wire values are frozen protocol. */
enum class OpenUiSurface(val wire: Int) {
    BROWSE(0),
    UPLOAD(1),
    SHARES(2),
    SCHEMATIC_DETAIL(3),
    SETTINGS(4),
    ;

    companion object {
        /** Null for wire values from newer protocol revisions (forward compat). */
        fun fromWire(wire: Int): OpenUiSurface? = entries.firstOrNull { it.wire == wire }
    }
}
```

`IpcMessages.kt` — two edits:

(a) Append the message class (after `Status`):

```kotlin
/**
 * S2C: command-ownership handoff (sub-project D). The server asks the client to open a
 * UI surface instead of printing a chat menu. The client honors it ONLY on a VERIFIED
 * (attested) session, only while the user's "server may open UI" toggle is on, and at
 * most once per 2 s — a hostile-but-attested server may not pop UI against the user's
 * will. [contextId] is an OPAQUE id (SCHEMATIC_DETAIL only) that the client resolves
 * exclusively through its OWN authenticated backend API — never trusted for anything else.
 */
data class OpenUi(
    val protocolVersion: Int,
    val surface: Int,
    val contextId: String = "",
) {
    init {
        require(OpenUiSurface.fromWire(surface) != null) { "unknown surface $surface" }
        require(contextId.length <= MAX_CONTEXT_CHARS) { "contextId must be at most $MAX_CONTEXT_CHARS chars" }
        require(contextId.isEmpty() || surface == OpenUiSurface.SCHEMATIC_DETAIL.wire) {
            "contextId is only valid for SCHEMATIC_DETAIL"
        }
    }

    companion object {
        const val MAX_CONTEXT_CHARS: Int = 64
    }
}
```

(b) Add the codec pair to `IpcCodec` (after `decodeStatus`; `checkCap` is B's existing private guard):

```kotlin
    fun encodeOpenUi(msg: OpenUi): ByteArray {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.OPEN_UI)
            writeVarInt(msg.protocolVersion)
            writeByte(msg.surface)
            writeString(msg.contextId)
        }.toByteArray()
        require(bytes.size <= IpcCaps.OPEN_UI) {
            "encoded OPEN_UI is ${bytes.size}B (cap ${IpcCaps.OPEN_UI}B) — contextIds must be ASCII ids"
        }
        return bytes
    }

    fun decodeOpenUi(bytes: ByteArray): OpenUi {
        checkCap(bytes, IpcOpcode.OPEN_UI)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.OPEN_UI) throw IpcFormatException("expected OPEN_UI, got $op")
        try {
            return OpenUi(
                protocolVersion = r.readVarInt(),
                surface = r.readByte(),
                contextId = r.readString(),
            )
        } catch (e: IllegalArgumentException) {
            throw IpcFormatException("invalid OPEN_UI: ${e.message}")
        }
    }
```

- [ ] **Step 1.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: BUILD SUCCESSFUL, all new + existing tests green.

*(No commit — global constraint.)*

---

## Task 2 — bukkit — CommandOwnershipRouting (pure attested × capability matrix + surface mapping)

**Files:**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/CommandOwnershipRouting.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/test/kotlin/io/schemat/schematioConnector/ipc/CommandOwnershipRoutingTest.kt`

**Interfaces:**
- Consumes: core `Capabilities.WANTS_COMMAND_OWNERSHIP` / `Capabilities.has(flags, bit)`; `OpenUiSurface`; `OpenUi.MAX_CONTEXT_CHARS` (Task 1).
- Produces (Task 3 relies on these):

```kotlin
object CommandOwnershipRouting {
    data class Handoff(val surface: OpenUiSurface, val contextId: String = "")
    fun shouldHandOff(attested: Boolean, clientFlags: Int): Boolean
    fun handoffFor(args: List<String>): Handoff?
}
```

- [ ] **Step 2.1: Write the failing test**

`CommandOwnershipRoutingTest.kt` (pure JVM — no Bukkit types, mirroring `LoadRequestGuardsTest`):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.OpenUiSurface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandOwnershipRoutingTest {

    private val wants = Capabilities.WANTS_COMMAND_OWNERSHIP

    @Test
    fun `hand off only when attested AND client advertised ownership`() {
        // Full attested x capability matrix (spec: routing decision table).
        assertTrue(CommandOwnershipRouting.shouldHandOff(attested = true, clientFlags = wants))
        assertFalse(CommandOwnershipRouting.shouldHandOff(attested = true, clientFlags = 0))
        assertFalse(CommandOwnershipRouting.shouldHandOff(attested = false, clientFlags = wants))
        assertFalse(CommandOwnershipRouting.shouldHandOff(attested = false, clientFlags = 0))
        // Other flag bits alone never qualify (vanilla / other-capability clients).
        assertFalse(CommandOwnershipRouting.shouldHandOff(attested = true, clientFlags = Capabilities.DOWNLOAD_CMD))
    }

    @Test
    fun `menu-style invocations map to their surfaces`() {
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.BROWSE),
            CommandOwnershipRouting.handoffFor(emptyList()), // no-args help menu
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.BROWSE),
            CommandOwnershipRouting.handoffFor(listOf("list")),
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.BROWSE),
            CommandOwnershipRouting.handoffFor(listOf("search")),
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.UPLOAD),
            CommandOwnershipRouting.handoffFor(listOf("upload")),
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.SHARES),
            CommandOwnershipRouting.handoffFor(listOf("quickshare")),
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.SETTINGS),
            CommandOwnershipRouting.handoffFor(listOf("settings")),
        )
        // Case-insensitive, like the SchematioCommand router itself.
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.BROWSE),
            CommandOwnershipRouting.handoffFor(listOf("LIST")),
        )
    }

    @Test
    fun `download with an id maps to SCHEMATIC_DETAIL with contextId`() {
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.SCHEMATIC_DETAIL, "11111111-2222-3333-4444-555555555555"),
            CommandOwnershipRouting.handoffFor(listOf("download", "11111111-2222-3333-4444-555555555555")),
        )
    }

    @Test
    fun `argful and action invocations stay on the chat flow`() {
        assertNull(CommandOwnershipRouting.handoffFor(listOf("list", "castle")))       // search context
        assertNull(CommandOwnershipRouting.handoffFor(listOf("list", "2")))            // pagination context
        assertNull(CommandOwnershipRouting.handoffFor(listOf("upload", "my-build")))   // explicit named upload
        assertNull(CommandOwnershipRouting.handoffFor(listOf("quickshare", "3600")))   // explicit share action
        assertNull(CommandOwnershipRouting.handoffFor(listOf("settings", "ui", "chat")))
        assertNull(CommandOwnershipRouting.handoffFor(listOf("download")))             // no id -> usage message
        assertNull(CommandOwnershipRouting.handoffFor(listOf("download", "id", "x")))  // over-arity
        assertNull(CommandOwnershipRouting.handoffFor(listOf("download", "a".repeat(65)))) // over-long id
        assertNull(CommandOwnershipRouting.handoffFor(listOf("info")))                 // status output, not a menu
        assertNull(CommandOwnershipRouting.handoffFor(listOf("reload")))               // admin action
        assertNull(CommandOwnershipRouting.handoffFor(listOf("quickshareget", "qs_abc")))
        assertNull(CommandOwnershipRouting.handoffFor(listOf("diff")))                 // vcs, in-world
        assertNull(CommandOwnershipRouting.handoffFor(listOf("nonsense")))
    }
}
```

- [ ] **Step 2.2: Run to verify compilation fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
Expected: compilation FAILS (no `CommandOwnershipRouting`).

- [ ] **Step 2.3: Implement**

`CommandOwnershipRouting.kt` (new file — deliberately free of Bukkit imports so it runs under plain JUnit):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.OpenUi
import io.schemat.connector.core.ipc.OpenUiSurface

/**
 * Pure decision logic for the command-ownership handoff (spec D): whether a player's
 * session qualifies, and which /schematio invocation maps to which OPEN_UI surface.
 * Kept Bukkit-free so it runs under plain JUnit.
 */
object CommandOwnershipRouting {

    /** An OPEN_UI to send instead of a chat menu. [contextId] only for SCHEMATIC_DETAIL. */
    data class Handoff(val surface: OpenUiSurface, val contextId: String = "")

    /**
     * Server-side ownership gate (attested x capability matrix): the connection must be
     * attested (ATTEST relayed this session) AND the client's HELLO_CLIENT flags must
     * advertise WANTS_COMMAND_OWNERSHIP. Vanilla clients send no flags -> never hand off,
     * so their command behavior is untouched by construction.
     */
    fun shouldHandOff(attested: Boolean, clientFlags: Int): Boolean =
        attested && Capabilities.has(clientFlags, Capabilities.WANTS_COMMAND_OWNERSHIP)

    /**
     * OPEN_UI surface for a /schematio invocation, or null to run the classic chat flow.
     * Only menu-style invocations hand off:
     * - ``            (no args, help menu)  -> BROWSE (the client UI hub)
     * - `list`/`search` (bare)              -> BROWSE (argful forms carry query/page
     *                                          context OPEN_UI cannot convey -> chat)
     * - `upload`      (bare)                -> UPLOAD (argful `upload <name>` is an
     *                                          explicit server-clipboard action -> chat)
     * - `quickshare`  (bare)                -> SHARES
     * - `settings`    (bare)                -> SETTINGS
     * - `download <id>`                     -> SCHEMATIC_DETAIL with contextId (this
     *                                          repo's "here <id>"-style detail command)
     */
    fun handoffFor(args: List<String>): Handoff? {
        if (args.isEmpty()) return Handoff(OpenUiSurface.BROWSE)
        val sub = args[0].lowercase()
        val bare = args.size == 1
        return when {
            bare && (sub == "list" || sub == "search") -> Handoff(OpenUiSurface.BROWSE)
            bare && sub == "upload" -> Handoff(OpenUiSurface.UPLOAD)
            bare && sub == "quickshare" -> Handoff(OpenUiSurface.SHARES)
            bare && sub == "settings" -> Handoff(OpenUiSurface.SETTINGS)
            sub == "download" && args.size == 2 &&
                args[1].isNotEmpty() && args[1].length <= OpenUi.MAX_CONTEXT_CHARS ->
                Handoff(OpenUiSurface.SCHEMATIC_DETAIL, args[1])
            else -> null
        }
    }
}
```

- [ ] **Step 2.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
Expected: BUILD SUCCESSFUL — 4 new tests plus the existing bukkit tests green.

*(No commit — global constraint.)*

---

## Task 3 — bukkit — clientFlags tracking, sendOpenUi, command-handler wiring + chat ack

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/PluginIpcService.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/SchematioConnector.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/commands/SchematioCommand.kt`

No new unit test file: the decision logic is fully covered by Task 2 (pure matrix + mapping); this task is wiring, verified by compilation + the existing bukkit suite + the Task 7 manual checklist — the same convention sub-project B used for its fabric wiring task.

**Interfaces:**
- Consumes: `CommandOwnershipRouting` (Task 2); `OpenUi`, `OpenUiSurface`, `IpcCodec.encodeOpenUi` (Task 1); B's final `PluginIpcService` (fields `greeted`, `attested`, the `HELLO_CLIENT` branch in `onPluginMessageReceived`, `onQuit`).
- Produces:

```kotlin
// PluginIpcService.kt
fun canOpenUi(player: Player): Boolean
fun sendOpenUi(player: Player, surface: OpenUiSurface, contextId: String = ""): Boolean

// SchematioConnector.kt
var ipcService: PluginIpcService? = null   // private set
```

- [ ] **Step 3.1: Implement PluginIpcService changes**

(a) Add imports (alphabetical, next to the existing `io.schemat.connector.core.ipc.*` imports):

```kotlin
import io.schemat.connector.core.ipc.OpenUi
import io.schemat.connector.core.ipc.OpenUiSurface
```

(b) Next to the `attested` set declaration, add:

```kotlin
    /**
     * clientFlags from each player's HELLO_CLIENT this session (bitset of Capabilities.*).
     * Absent entry = vanilla client (or no hello yet) -> flags 0 -> never hands off.
     */
    private val clientFlags = ConcurrentHashMap<UUID, Int>()
```

(c) In the `IpcOpcode.HELLO_CLIENT` branch of `onPluginMessageReceived`, directly after the
`val hello: HelloClient = IpcCodec.decodeHelloClient(message)` line, add:

```kotlin
                    clientFlags[player.uniqueId] = hello.clientFlags
```

(d) In `onQuit`, next to `attested.remove(event.player.uniqueId)`, add:

```kotlin
        clientFlags.remove(event.player.uniqueId)
```

(e) Add the two public functions (place them after the `onQuit` handler, before
`onPluginMessageReceived`):

```kotlin
    // ---- command ownership handoff (sub-project D) ----

    /**
     * Server-side ownership gate: attested this session AND the client advertised
     * WANTS_COMMAND_OWNERSHIP in its HELLO_CLIENT flags. Pure matrix in
     * [CommandOwnershipRouting.shouldHandOff]; this only supplies the per-player state.
     */
    fun canOpenUi(player: Player): Boolean =
        CommandOwnershipRouting.shouldHandOff(
            attested = attested.contains(player.uniqueId),
            clientFlags = clientFlags[player.uniqueId] ?: 0,
        )

    /**
     * Sends an OPEN_UI handoff if the gate passes and the channel is deliverable.
     * Returns whether it was sent — false lets the caller run the classic chat flow,
     * so vanilla clients (and races before HELLO/ATTEST complete) degrade gracefully.
     * Main thread only (sendPluginMessage).
     */
    fun sendOpenUi(player: Player, surface: OpenUiSurface, contextId: String = ""): Boolean {
        if (!canOpenUi(player)) return false
        if (!player.listeningPluginChannels.contains(IpcProtocol.CHANNEL)) return false
        val msg = OpenUi(IpcProtocol.VERSION, surface.wire, contextId)
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeOpenUi(msg))
        return true
    }
```

- [ ] **Step 3.2: Implement SchematioConnector changes**

(a) Near the other service properties (next to `var httpUtil: HttpUtil? = null`), add:

```kotlin
    /** IPC service (handshake + OPEN_UI handoff); command routing queries its ownership gate. */
    var ipcService: io.schemat.schematioConnector.ipc.PluginIpcService? = null
        private set
```

(b) Replace the registration line

```kotlin
        PluginIpcService(this).register()
```

with:

```kotlin
        ipcService = PluginIpcService(this).also { it.register() }
```

- [ ] **Step 3.3: Implement SchematioCommand changes**

(a) Add the import:

```kotlin
import io.schemat.schematioConnector.ipc.CommandOwnershipRouting
```

(b) Replace the whole `onCommand` function with (the unknown-subcommand and permission blocks
are byte-identical to the current code — only the two `tryHandOff` insertions and the
`handoff` val are new):

```kotlin
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.audience().sendMessage(Component.text("This command can only be used by players.").color(NamedTextColor.RED))
            return true
        }

        // Command-ownership handoff (IPC sub-project D): menu-style surfaces open the
        // client's ImGui UI when the session is attested and the client asked for it.
        // Vanilla clients never satisfy the gate, so their behavior is unchanged.
        val handoff = CommandOwnershipRouting.handoffFor(args.toList())

        if (args.isEmpty()) {
            if (handoff != null && tryHandOff(sender, handoff)) return true
            sendHelpMessage(sender)
            return true
        }

        val subcommand = subcommands[args[0].lowercase()]
        if (subcommand == null) {
            // If WorldEdit is missing, provide a more helpful message for those specific commands
            val missingCommand = args[0].lowercase()
            if (missingCommand in listOf("upload", "download", "list") && !plugin.hasWorldEdit) {
                sender.audience().sendMessage(
                    Component.text("This command requires WorldEdit, which was not found on the server.")
                        .color(NamedTextColor.RED)
                )
            } else {
                sender.audience().sendMessage(
                    Component.text("Unknown subcommand. Use /schematio for help.").color(NamedTextColor.RED)
                )
            }
            return true
        }

        // Check permissions
        if (!sender.hasPermission(subcommand.permission)) {
            sender.audience().sendMessage(Component.text("You don't have permission to use this command.").color(NamedTextColor.RED))
            return true
        }

        // Ownership handoff runs AFTER the permission check: modded players obey exactly
        // the same permission gates as vanilla players.
        if (handoff != null && tryHandOff(sender, handoff)) return true

        return subcommand.execute(sender, args.drop(1).toTypedArray())
    }

    /**
     * Sends the OPEN_UI handoff; on success prints the spec's one-line chat ack and
     * swallows the command. Returns false (caller falls through to the chat flow) when
     * the IPC service is absent, the player's session doesn't qualify, or the channel
     * isn't deliverable.
     */
    private fun tryHandOff(player: Player, handoff: CommandOwnershipRouting.Handoff): Boolean {
        val ipc = plugin.ipcService ?: return false
        if (!ipc.sendOpenUi(player, handoff.surface, handoff.contextId)) return false
        player.audience().sendMessage(
            Component.text("Opened in your Schematio client.").color(NamedTextColor.GREEN)
        )
        return true
    }
```

- [ ] **Step 3.4: Run to verify**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
Expected: BUILD SUCCESSFUL — everything compiles, Task 2 tests + A/B's bukkit tests stay green.

*(No commit — global constraint.)*

---

## Task 4 — fabric — OpenUiGate + OpenUiPrefs (client-side invariants 1–3) + config default param

**Files:**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/OpenUiGate.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/auth/ClientAuthManager.kt` (`getConfigFlag` default parameter only)
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/test/kotlin/io/schemat/connector/fabric/client/ipc/OpenUiGateTest.kt`

**Interfaces:**
- Consumes: A's `TrustState` (same package `io.schemat.connector.fabric.client.ipc`).
- Produces (Tasks 5–6 rely on these):

```kotlin
object OpenUiPrefs { const val KEY: String = "allow_server_open_ui"; const val DEFAULT: Boolean = true }
class OpenUiGate(minIntervalMs: Long = 2_000L) {
    fun tryAccept(trust: TrustState, allowServerOpenUi: Boolean, nowMs: Long): Boolean
    fun reset()
}
// ClientAuthManager
fun getConfigFlag(name: String, default: Boolean = false): Boolean   // was: implicit false
```

- [ ] **Step 4.1: Write the failing test**

`OpenUiGateTest.kt` (pure JVM — `TrustState` has no Minecraft imports, proven by A's `ServerSessionTrustTest` living in the same source set):

```kotlin
package io.schemat.connector.fabric.client.ipc

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenUiGateTest {

    @Test
    fun `spec invariant 1 - OPEN_UI from a non-attested session is dropped`() {
        val gate = OpenUiGate()
        assertFalse(gate.tryAccept(TrustState.NONE, allowServerOpenUi = true, nowMs = 0L))
        assertFalse(gate.tryAccept(TrustState.LEGACY_V1, allowServerOpenUi = true, nowMs = 0L))
        assertFalse(gate.tryAccept(TrustState.UNVERIFIED, allowServerOpenUi = true, nowMs = 0L))
        assertTrue(gate.tryAccept(TrustState.VERIFIED, allowServerOpenUi = true, nowMs = 0L))
    }

    @Test
    fun `spec invariant 2 - toggle off drops even a VERIFIED session`() {
        val gate = OpenUiGate()
        assertFalse(gate.tryAccept(TrustState.VERIFIED, allowServerOpenUi = false, nowMs = 0L))
    }

    @Test
    fun `spec invariant 3 - at most one accept per 2 seconds`() {
        val gate = OpenUiGate()
        assertTrue(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 10_000L))
        assertFalse(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 10_001L))
        assertFalse(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 11_999L))
        assertTrue(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 12_000L))
    }

    @Test
    fun `denied attempts do not consume the rate slot`() {
        val gate = OpenUiGate()
        assertFalse(gate.tryAccept(TrustState.VERIFIED, allowServerOpenUi = false, nowMs = 10_000L))
        assertFalse(gate.tryAccept(TrustState.UNVERIFIED, allowServerOpenUi = true, nowMs = 10_001L))
        // The very next qualifying attempt is accepted — denials never started the window.
        assertTrue(gate.tryAccept(TrustState.VERIFIED, allowServerOpenUi = true, nowMs = 10_002L))
    }

    @Test
    fun `reset reopens the window immediately`() {
        val gate = OpenUiGate()
        assertTrue(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 10_000L))
        gate.reset()
        assertTrue(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 10_001L))
    }

    @Test
    fun `prefs constants match the spec (key name, default ON)`() {
        org.junit.jupiter.api.Assertions.assertEquals("allow_server_open_ui", OpenUiPrefs.KEY)
        assertTrue(OpenUiPrefs.DEFAULT)
    }
}
```

- [ ] **Step 4.2: Run to verify compilation fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: compilation FAILS (no `OpenUiGate`, no `OpenUiPrefs`).

- [ ] **Step 4.3: Implement**

`OpenUiGate.kt` (new file):

```kotlin
package io.schemat.connector.fabric.client.ipc

/**
 * config.properties key for the "server may open UI" preference (spec: default ON,
 * visible toggle in SettingsPanel). Read via ClientAuthManager.getConfigFlag(KEY, DEFAULT).
 */
object OpenUiPrefs {
    const val KEY: String = "allow_server_open_ui"
    const val DEFAULT: Boolean = true
}

/**
 * Client-side OPEN_UI acceptance policy (spec invariants 1-3): the session must be
 * VERIFIED (attested), the user's toggle must be on, and at most one OPEN_UI is honored
 * per [minIntervalMs] — extras are silently dropped, and DENIED attempts never consume
 * the slot. Pure and clock-injected for unit tests. Single-threaded use (the Fabric
 * client thread that ServerIpc handles payloads on).
 */
class OpenUiGate(private val minIntervalMs: Long = 2_000L) {

    /** Timestamp of the last ACCEPTED handoff; -1 = never. */
    private var lastAcceptedAtMs: Long = -1L

    fun tryAccept(trust: TrustState, allowServerOpenUi: Boolean, nowMs: Long): Boolean {
        if (trust != TrustState.VERIFIED) return false     // invariant 1: unattested -> drop
        if (!allowServerOpenUi) return false               // invariant 2: toggle off -> drop
        if (lastAcceptedAtMs >= 0 && nowMs - lastAcceptedAtMs < minIntervalMs) return false // invariant 3
        lastAcceptedAtMs = nowMs
        return true
    }

    fun reset() {
        lastAcceptedAtMs = -1L
    }
}
```

`ClientAuthManager.kt` — replace the `getConfigFlag` function (signature gains a default
parameter; existing single-argument callers are unaffected):

```kotlin
    /**
     * Read a boolean flag from config.properties (e.g. `limited_mode_notice_shown`,
     * `allow_server_open_ui`). Missing/malformed values read as [default].
     */
    fun getConfigFlag(name: String, default: Boolean = false): Boolean {
        return try {
            val configFile = configDir.resolve("config.properties").toFile()
            if (!configFile.exists()) return default
            val props = Properties()
            configFile.inputStream().use { props.load(it) }
            props.getProperty(name)?.toBooleanStrictOrNull() ?: default
        } catch (e: Exception) {
            LOGGER.warn("Failed to read config flag $name: ${e.message}")
            default
        }
    }
```

(`toBooleanStrictOrNull` keeps `"false"` working while making garbage values fall back to
the default — important now that a default can be `true`.)

- [ ] **Step 4.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL — 6 new tests plus the existing fabric suite green.

*(No commit — global constraint.)*

---

## Task 5 — fabric — ServerIpc OPEN_UI handler: gating, surface→PanelManager map, contextId via own API, flag advertisement

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerIpc.kt`

No new unit test file: invariants 1–3 are proven by Task 4's `OpenUiGateTest`; the codec by Task 1; this task is thread-safe wiring onto singleton panels (unloadable under plain JUnit — they import Minecraft classes), verified by compilation + the fabric suite + the Task 7 manual checklist. This mirrors sub-project B's Task 7 convention for the same file.

**Interfaces:**
- Consumes: `OpenUi`, `OpenUiSurface`, `IpcCodec.decodeOpenUi`, `Capabilities.WANTS_COMMAND_OWNERSHIP` (Task 1 / core); `OpenUiGate`, `OpenUiPrefs`, `ClientAuthManager.getConfigFlag(name, default)` (Task 4); A's `ServerSession.trust`; B's final `ServerIpc.handle()` (has `ATTEST` and `STATUS` branches and the `IpcPayloadTooLargeException` catch); `PanelManager.open`, `ImGuiOverlay.ensureOpen()`, `BrowsePanel`, `SharesPanel`, `SettingsPanel`, `UploadWizardPanel.open()`, `SchematicDetailPanel.show(SchematicSummary)`; `ClientServices.call(busy, block, onResult)` + `toUserMessage()`; `services.cached.schematic(id): ApiResult<SchematicDetail>`.
- Produces: the `OPEN_UI` branch in `handle()`; `HELLO_CLIENT.clientFlags` now advertises `WANTS_COMMAND_OWNERSHIP` while the toggle is on (Task 3's bukkit gate reads this).

- [ ] **Step 5.1: Implement**

(a) Add imports (next to the existing core/ipc and client imports; `Capabilities` is already imported by B):

```kotlin
import io.schemat.connector.core.ipc.OpenUi
import io.schemat.connector.core.ipc.OpenUiSurface
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.SchematicDetailPanel
import io.schemat.connector.fabric.client.ui.panels.SettingsPanel
import io.schemat.connector.fabric.client.ui.panels.SharesPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
```

(b) Add the gate field near the top of the `ServerIpc` object (next to `LOGGER`):

```kotlin
    /**
     * Client-side OPEN_UI policy (spec invariants 1-3): VERIFIED session + user toggle
     * + 1-per-2s. Time-windowed, so it needs no per-connection reset — a new server
     * must earn VERIFIED before any OPEN_UI is honored anyway.
     */
    private val openUiGate = OpenUiGate()
```

(c) In `handle()`'s `when`, after the `IpcOpcode.STATUS` branch (added by B), add:

```kotlin
                IpcOpcode.OPEN_UI -> handleOpenUi(IpcCodec.decodeOpenUi(data))
```

(d) In `sendClientHello()`, replace the encode lines

```kotlin
        val bytes = IpcCodec.encodeHelloClient(
            HelloClient(IpcProtocol.VERSION, version, 0, ServerSession.nonce),
        )
```

with:

```kotlin
        // Advertise WANTS_COMMAND_OWNERSHIP only while the user allows servers to open
        // UI: an opted-out client never invites handoffs, so /schematio keeps printing
        // chat menus server-side (mid-session opt-out is enforced by the OpenUiGate drop).
        val allowOpenUi = SchematioClientMod.instance.services.authManager
            .getConfigFlag(OpenUiPrefs.KEY, OpenUiPrefs.DEFAULT)
        val clientFlags = if (allowOpenUi) Capabilities.WANTS_COMMAND_OWNERSHIP else 0
        val bytes = IpcCodec.encodeHelloClient(
            HelloClient(IpcProtocol.VERSION, version, clientFlags, ServerSession.nonce),
        )
```

(e) Append the handler functions at the end of the object (after `sendLoadRequest`):

```kotlin
    // ---- OPEN_UI (command ownership handoff, sub-project D) ----

    /**
     * Server-requested UI open. Honored ONLY when [OpenUiGate] accepts: VERIFIED
     * (attested) session, user toggle on, and at most one per 2 s — everything else
     * is dropped silently (spec: a hostile-but-attested server may not pop UI
     * against the user's will).
     */
    private fun handleOpenUi(msg: OpenUi) {
        val services = SchematioClientMod.instance.services
        val allow = services.authManager.getConfigFlag(OpenUiPrefs.KEY, OpenUiPrefs.DEFAULT)
        if (!openUiGate.tryAccept(ServerSession.trust, allow, System.currentTimeMillis())) {
            LOGGER.debug("Dropped OPEN_UI (trust={}, allow={})", ServerSession.trust, allow)
            return
        }
        when (OpenUiSurface.fromWire(msg.surface)) {
            OpenUiSurface.BROWSE -> {
                ImGuiOverlay.ensureOpen()
                PanelManager.open(BrowsePanel)
            }
            OpenUiSurface.UPLOAD -> {
                ImGuiOverlay.ensureOpen()
                UploadWizardPanel.open()
            }
            OpenUiSurface.SHARES -> {
                ImGuiOverlay.ensureOpen()
                PanelManager.open(SharesPanel)
            }
            OpenUiSurface.SETTINGS -> {
                ImGuiOverlay.ensureOpen()
                PanelManager.open(SettingsPanel)
            }
            OpenUiSurface.SCHEMATIC_DETAIL -> openSchematicDetail(msg.contextId)
            null -> Unit // unreachable: decodeOpenUi validates the surface
        }
    }

    /**
     * contextId is OPAQUE (spec invariant 4): it is used for exactly one thing — a
     * lookup against the CLIENT'S OWN authenticated backend API. Unknown/inaccessible
     * ids surface a local error line and open nothing.
     */
    private fun openSchematicDetail(contextId: String) {
        if (contextId.isEmpty()) return
        val services = SchematioClientMod.instance.services
        services.call(
            block = { services.cached.schematic(contextId) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    ImGuiOverlay.ensureOpen()
                    SchematicDetailPanel.show(detailAsSummary(result.value))
                }
                is ApiResult.Failure -> clientChat(
                    "§cSchematio: couldn't open that schematic — ${result.error.toUserMessage()}",
                )
            }
        }
    }

    /** SchematicDetailPanel.show takes a summary; a fetched detail is a superset of one. */
    private fun detailAsSummary(d: SchematicDetail): SchematicSummary = SchematicSummary(
        id = d.id,
        shortId = d.shortId,
        slug = d.slug,
        name = d.name,
        description = d.description,
        format = d.format,
        isPublic = d.isPublic,
        createdAt = d.createdAt,
        updatedAt = d.updatedAt,
        authors = d.authors,
        tags = d.tags,
        previewImageUrl = d.previewImageUrl,
        previewVideoUrl = d.previewVideoUrl,
        downloadLink = d.downloadLink,
        webUrl = d.webUrl,
    )

    /** Client-local chat line (this repo has no toast framework; matches the HELLO pattern). */
    private fun clientChat(text: String) {
        //? if >=26.1 {
        /*Minecraft.getInstance().player?.sendSystemMessage(Component.literal(text))
        *///?} else {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal(text), false)
        //?}
    }
```

- [ ] **Step 5.2: Run to verify**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL — compiles across all stonecutter versions built by this target; Task 4 + A/B fabric tests stay green.

Also run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test :bukkit:test`
Expected: still green (cross-module regression guard).

*(No commit — global constraint.)*

---

## Task 6 — fabric — SettingsPanel "allow server to open UI" toggle (persisted)

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/SettingsPanel.kt`

No new unit test file: `OpenUiPrefs` constants and the default-ON read are covered by Task 4's tests; ImGui rendering is unloadable under plain JUnit. Verified by compilation + the Task 7 manual checklist.

**Interfaces:**
- Consumes: `OpenUiPrefs` (Task 4); `ClientAuthManager.getConfigFlag(name, default)` / `setConfigFlag(name, value)`; existing `ImBoolean`, `Widgets`, `ImGuiColors` imports in this file.
- Produces: the user-visible toggle backing `handleOpenUi`'s config read (Task 5) — same key, same default.

- [ ] **Step 6.1: Implement**

(a) Add the import:

```kotlin
import io.schemat.connector.fabric.client.ipc.OpenUiPrefs
```

(b) Add fields next to `reauthBusy`:

```kotlin
    /** "Server may open UI" toggle (spec D, default ON); loaded from config on first render. */
    private val allowServerOpenUi = ImBoolean(OpenUiPrefs.DEFAULT)
    private var prefsLoaded = false
```

(c) In `renderContent()`, directly after the offline-warning block's `ImGui.spacing()` (i.e.
between the CONNECTION and ACTIONS sections), insert:

```kotlin
        // ---- PREFERENCES section ----
        ImGui.textColored(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
            "Preferences"
        )
        ImGui.separator()

        if (!prefsLoaded) {
            allowServerOpenUi.set(authManager.getConfigFlag(OpenUiPrefs.KEY, OpenUiPrefs.DEFAULT))
            prefsLoaded = true
        }
        if (ImGui.checkbox("Allow this server to open Schematio windows", allowServerOpenUi)) {
            authManager.setConfigFlag(OpenUiPrefs.KEY, allowServerOpenUi.get())
            statusText = if (allowServerOpenUi.get()) {
                "Attested servers may now open Schematio windows for /schematio commands"
            } else {
                "Servers can no longer open Schematio windows — /schematio returns to chat menus after rejoin"
            }
            statusKind = Widgets.StatusKind.INFO
        }
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "When on, /schematio menus on attested Schematio servers open here instead of chat"
        )

        ImGui.spacing()
```

- [ ] **Step 6.2: Run to verify**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL, suite green.

*(No commit — global constraint.)*

---

## Task 7 — Integration checkpoint: full suites + manual run-paper checklist

**Files:** none (verification only).

- [ ] **Step 7.1: Run every suite**

```bash
cd /Users/harrison/IdeaProjects/SchematioConnector
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:build
```

Expected: all BUILD SUCCESSFUL. New tests this sub-project: 5 core (+1 extended), 4 bukkit, 6 fabric.

- [ ] **Step 7.2: Manual run-paper checklist** (report to Harrison; uses the bukkit `runServer`
integration setup + a dev client with the mod, community token configured so attestation
succeeds — same environment as A/B's checklists):

1. **Modded + attested, toggle ON (default):**
   - `/schematio` → Browse panel opens + green "Opened in your Schematio client." chat line; NO chat help menu.
   - `/schematio list` → Browse panel. `/schematio upload` → Upload wizard. `/schematio quickshare` → Quick Shares panel. `/schematio settings` → client Settings panel.
   - `/schematio download <known schematic uuid>` → Schematic detail panel for that schematic (fetched via the CLIENT's own API — verify the plugin log shows no extra backend call for it).
   - `/schematio download <bogus uuid>` → red client chat "couldn't open that schematic…", nothing opens (spec: unknown contextId → error, nothing opens).
   - `/schematio list castle` → classic CHAT search results (argful invocations keep chat).
   - `/schematio download <id>` without `schematio.download` permission → classic red permission message, no panel (handoff sits behind the permission check).
2. **Rate limit:** run `/schematio` twice within 2 s → both print the server ack, only the first opens a panel (client-side 1-per-2s drop is silent).
3. **Toggle OFF mid-session** (client Settings → uncheck): `/schematio` → server still acks but NO panel opens (client-side drop wins). **After rejoin:** `/schematio` prints the classic chat help (client no longer advertises the flag; server routes to chat).
4. **Unattested:** clear the plugin's community token (or stop the backend) and rejoin → with the mod, `/schematio` prints the classic chat menu (no ack, no panel).
5. **Vanilla client** (mod removed): every command above behaves exactly as on current master — chat menus, chat search, clipboard download.

*(No commit — global constraint. Leave the tree dirty for Harrison's review.)*

---

## Self-review notes (spec coverage)

- **Protocol:** `OPEN_UI = 9`, surface byte 0–4, contextId ≤ 64 utf8 / empty unless SCHEMATIC_DETAIL, 128-byte cap → Task 1 (construction + decode validation + cap test).
- **Client honors only ATTESTED (=VERIFIED) + toggle (default ON, visible in SettingsPanel) + 1-per-2s silent drop** → Tasks 4 (gate + tests = spec's three unit-testable invariants), 5 (wiring), 6 (toggle UI + persistence via the mod's existing config.properties mechanism).
- **contextId opaque, resolved via the client's own user-auth API; unknown → error, nothing opens** → Task 5 `openSchematicDetail` (review invariant 4 noted in code comments; error surfaced as a client chat line since no toast framework exists — resolved ambiguity 5).
- **Plugin: attested + client-advertised WANTS_COMMAND_OWNERSHIP + capability negotiated → OPEN_UI + one-line ack, else unchanged chat flow** → Tasks 2 (pure decision table with the full matrix as unit tests) and 3 (wiring after the existing permission check; ack text "Opened in your Schematio client.").
- **`here <id>`-style detail → SCHEMATIC_DETAIL with contextId** → resolved to `/schematio download <id>` (ambiguity 1), Tasks 2–3.
- **No command removed or behavior-changed for vanilla clients** → gate is opt-in by clientFlags (absent for vanilla), handoff insertions strictly precede otherwise-identical code paths, and `sendOpenUi` returning false falls through to the classic flow; checklist item 5 verifies.
- **Testing section of the spec** (core round-trip/caps; bukkit routing decision table; fabric gating; manual run-paper) → Tasks 1, 2, 4, 7 respectively.
- **Type consistency check:** `OpenUiSurface`/`OpenUi` names match across Tasks 1/2/3/5; `OpenUiPrefs.KEY`/`DEFAULT` match across 4/5/6; `getConfigFlag(name, default)` signature matches 4/5/6; `Handoff(surface, contextId)` matches 2/3.
