# Schematio IPC Foundation — Design

**Date:** 2026-06-20
**Branch:** `feature/imgui-ui-migration` (or a dedicated `feature/ipc-foundation`)
**Status:** Approved design — pending implementation plan

## Goal

Establish a client↔server interop layer between the **Fabric client mod** and the **Spigot/Bukkit plugin** so that a modded client gets an enhanced experience while a server running only the plugin remains fully feature-complete. This document covers the **interop foundation only** — a thin vertical slice that proves the channel works end to end. Version control (the eventual north star) and rich upload/download flows are explicitly out of scope here and ride on this layer later.

### North star (context, not this deliverable)
Add version control to schematics, surfaced through both plugin and mod. The mod should make it a nice experience; the plugin must stand alone.

### This deliverable's visible win
1. A modded client **detects** that the server runs the plugin and learns its version ("Connected to Schematio server v1.2.4").
2. The `/schematio` **command collision** between the client mod and the server plugin is resolved — commands stop double-firing; the server owns the command surface by default.

## Constraints

- **Server installs the plugin only.** No ProtocolLib, no extra server dependencies. This is satisfied natively by Bukkit's plugin-messaging (`Messenger`) API.
- **Bandwidth-frugal.** Target servers are large (e.g. OpenRedstoneEngineers) with many players. The handshake must be one-shot per join and tiny; no periodic traffic. Vanilla clients must incur zero cost.
- **Plugin remains authoritative and feature-complete** with or without any mod present.
- **Designed for future nuance from day one** — per-action behavior must be tweakable later (uploads are complex; downloads may want local browsing) without a protocol break.

## Current State (from codebase exploration)

- No existing plugin-messaging / custom-payload / `PacketByteBuf` usage in either module. Clean slate.
- Transport today is HTTP to the schemat.io API (`HttpUtil` in `core/`).
- **Command collision is real:** the Fabric mod registers a *client* `/schematio` via `ClientCommandManager` (`fabric/src/client/.../command/SchematioClientCommands.kt`), and the Bukkit plugin registers a *server* `/schematio` (`bukkit/.../plugin.yml` + `commands/`). Fabric client commands intercept matching input *locally before it reaches the server*, so a modded player on a plugin server currently cannot reach the server's `/schematio`.
  - Note: the Fabric module also has a *server-side* command (main sourceSet, `CommandRegistrationCallback`) for when the mod runs on a Fabric server. That is **not** the collision target — the collision is the **client** command on a Spigot server.
- Schematics are remote objects on the schemat.io API (DTOs in `core/.../modapi/dto/SchematicDtos.kt`), stored locally as `.litematic`. No local version model exists yet.
- Current version: **1.2.4** (`gradle.properties`).
- Entry points: Bukkit `bukkit/.../SchematioConnector.kt` (`onEnable`); Fabric client `fabric/src/client/.../client/SchematioClientMod.kt` (`onInitializeClient`).

## Architecture

### 1. Protocol lives in `core/` (single source of truth)

`core/` is pure Kotlin with no Minecraft deps and is shared by both platforms, so the wire protocol lives there:

- **`SchematioChannel`** — channel id constant `schematio:c` (kept short to minimize per-packet bytes; the channel id is sent with every custom payload).
- **Opcodes** — `HELLO_SERVER`, `HELLO_CLIENT`, plus a reserved range for later (`DOWNLOAD_ACK`, `UPLOAD_NEGOTIATE`, `VERSION_CONTROL_*`).
- **`Capabilities`** — a varint bitset advertised in the handshake:
  - `DOWNLOAD_CMD` — server supports the download command path
  - `UPLOAD` — reserved, off for POC
  - `VERSION_CONTROL` — reserved (north star)
  - `WANTS_COMMAND_OWNERSHIP` — server prefers to own the command surface
  - (free bits reserved for growth)
- **`IpcCodec`** — pure encode/decode over `byte[]`. Uses varints and length-prefixed UTF-8 strings, **hand-rolled to match Minecraft's `PacketByteBuf` string encoding** (varint length prefix + UTF-8 bytes). Important: Guava's `ByteArrayDataOutput.writeUTF` is *not* wire-compatible (Java modified-UTF, 2-byte length), so we define our own primitives and use them identically on both sides.
- **`ProtocolVersion`** — a varint included in every message. Both sides degrade gracefully on mismatch (treat as "no enhanced features").

Bukkit and Fabric each get a thin transport adapter that converts their native buffer ↔ `byte[]` and delegates to the shared codec.

### 2. Handshake flow (server-initiated, client fallback)

One-shot per join; no periodic traffic.

1. Client joins → Fabric registers the `schematio:c` receiver → Minecraft auto-sends `minecraft:register` advertising the channel.
2. Bukkit's `PlayerRegisterChannelEvent` fires for `schematio:c` → plugin sends **`HELLO_SERVER`** = `{ protoVersion, pluginVersion, capabilities }`.
3. Client stores a per-connection **`ServerSession`** `{ pluginPresent, pluginVersion, protocolVersion, capabilities }`, applies command deconfliction, and replies **`HELLO_CLIENT`** = `{ protoVersion, modVersion, clientFlags }` so the server knows the mod is present.

**Fallback:** the client also sends `HELLO_CLIENT` on play-join. The server responds to *either* trigger (register event or client hello), deduped per player. This guards against Bukkit register-event timing quirks.

**No plugin present:** no `HELLO_SERVER` ever arrives → `ServerSession.pluginPresent` stays `false` → mod runs in standalone/HTTP mode. ~30–60 bytes total per modded join. Vanilla clients silently ignore the channel — zero cost.

### 3. Command deconfliction + dispatch table

The client `/schematio` command intercepts locally before the server sees it — this is the collision. The fix doubles as the "command alias" transport the design wants.

- The client `/schematio` is registered as a **router** with a greedy fallback capture so `/schematio <anything>` always parses client-side instead of erroring (otherwise subcommands the server defines but the client doesn't would error locally instead of reaching the server).
- Each action resolves through a small **`DispatchTable`** to one of:
  - **`SERVER_COMMAND`** — forward the raw line to the server via `sendChatCommand("schematio …")`. Reuses the plugin's existing command logic; no custom packet needed. **POC default when `pluginPresent`.**
  - **`LOCAL`** — handle client-side; server never involved (e.g. open the ImGui browser, load a schematic into local Litematica).
  - **`PACKET`** — custom payload; reserved for things commands can't express (upload negotiation, version control).
- **POC behavior:**
  - `pluginPresent == true` → everything defaults to `SERVER_COMMAND` (true pass-through; satisfies "ignore commands so they don't conflict"). The local ImGui browser stays reachable as a `LOCAL` action via keybind.
  - `pluginPresent == false` → table falls back to today's local/HTTP handling; client commands behave as they do now (no server `/schematio` exists to collide with).
- **Wire for negotiation, logic for client-config:** the handshake carries `WANTS_COMMAND_OWNERSHIP` from day one, but POC dispatch decisions are driven only by client config + `pluginPresent`. Full negotiation logic can be added later with no protocol change.

**Implementation risk to validate in the plan (highest priority):** the exact Fabric client-command interception / fall-through semantics for the repo's Fabric API version, and whether greedy root capture degrades tab-completion for `LOCAL` subcommands. If greedy capture is too costly, fall back to per-executor forwarding plus a `redirect()`-based approach. This must be verified against the actual API before building the router.

### 4. Bukkit side

- `onEnable`: `messenger.registerOutgoingPluginChannel(this, "schematio:c")` + `registerIncomingPluginChannel(this, "schematio:c", listener)`.
- A new **`PluginIpcService`**:
  - Incoming listener parses on the main thread — **no blocking work**, just decode + respond.
  - Gates sends on `player.getListeningPluginChannels().contains("schematio:c")`.
  - Responds with `pluginVersion` + `capabilities`, deduped per player so the register-event and client-hello triggers don't double-send.
- Plugin stays fully feature-complete regardless of mod presence.

### 5. Fabric side (client)

- `PayloadTypeRegistry.playS2C()` / `playC2S()` register a `CustomPayload` type for `schematio:c`.
- `ClientPlayNetworking.registerGlobalReceiver` handles `HELLO_SERVER`.
- A new **`ServerIpcClient`** updates **`ServerSession`** (lives in `ClientServices`); `ClientPlayConnectionEvents` (join/disconnect) create/reset it.
- Sends `HELLO_CLIENT` on join (fallback path).
- Visible proof: a chat/log line "Connected to Schematio server vX.Y.Z" and `/schematio` no longer double-fires.

### 6. Error handling

- Malformed/short payload → log + ignore; never crash the listener (both sides).
- Protocol-version mismatch → treat as "no enhanced features"; `ServerSession` keeps `pluginPresent=false` semantics for behavior gating (or a separate `compatible=false` flag) so dispatch falls back safely.
- Late `HELLO_SERVER` (after the player already typed a command once) → acceptable; that one command runs via the not-yet-known path. Rare, non-fatal.
- Disconnect/reconnect → `ServerSession` reset via `ClientPlayConnectionEvents`.

## Testing

- **TDD on the `core` codec** — round-trip unit tests (`encode → decode`) for every opcode, protocol version, capability bitset, and string value, plus truncated/garbage-input cases. Pure JVM, no Minecraft runtime. This is where the real correctness risk concentrates and it is cheap to cover.
- **IPC end-to-end** — manual verification against a local Spigot server + dev client, backed by log lines and a small debug aid. Document the manual steps in the plan.

## Deliverable Scope

**In scope:**
- `core/` protocol: `SchematioChannel`, opcodes, `Capabilities`, `IpcCodec`, `ProtocolVersion` (+ codec unit tests).
- Bukkit: `PluginIpcService` — channel registration + handshake responder.
- Fabric client: `ServerIpcClient` + `ServerSession` + `HELLO_CLIENT` send; `/schematio` router implementing `SERVER_COMMAND` pass-through deconfliction with a `DispatchTable`.
- Visible confirmation: "detected server vX" + commands stop colliding.

**Out of scope (reserved seams only — no implementation):**
- Actual schematic version control.
- Upload negotiation.
- Packet-based downloads (downloads in POC ride the `SERVER_COMMAND` path).
- Server-side negotiation logic for `WANTS_COMMAND_OWNERSHIP` (wire field exists; logic deferred).

## Open Decisions (resolved)

- **Channel:** single channel `schematio:c`, message type multiplexed via leading opcode byte. ✓
- **Deconfliction:** client `/schematio` router with greedy capture, forwarding via `sendChatCommand` when `pluginPresent`. ✓ (pending API-semantics validation in plan)
- **Capability model:** wire carries flags incl. `WANTS_COMMAND_OWNERSHIP` from day one; POC logic driven by client config + `pluginPresent`. ✓
