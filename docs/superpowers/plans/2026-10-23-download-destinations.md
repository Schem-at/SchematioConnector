# Download Destinations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route `/schematio download` and quick-share gets to the player's highest-priority available destination (server WorldEdit > local WorldEdit > local Litematica > file), with per-world priority lists, per-command flags, a Fabric-server handshake, and a backend rule letting a community token read what its member can view.

**Architecture:** Pure routing logic in `core` (destinations, resolver, arg parsing, dispatch, capability computation) with JUnit tests. Each side then uses it:
- the Fabric server (main source set) announces itself on `schematio:c`;
- the Paper plugin computes a per-player `SERVER_WORLDEDIT` bit;
- the Fabric client builds an availability snapshot, executes the resolved destination, and exposes it in commands, the detail panel and settings.

The backend change is a separate Laravel PR in `Schem-at/schemati`.

**Tech Stack:** Kotlin 2.x, Fabric Loom + Stonecutter (1.21.8–26.3), Fabric API networking, Brigadier, Dear ImGui (imgui-java), Paper/Bukkit Messenger, JUnit 5; Laravel 12 + Pest (backend).

**Spec:** `docs/superpowers/specs/2026-10-23-download-destinations-design.md`

## Global Constraints

- Every Fabric target 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, 26.2, 26.3 must compile and pass tests. Use Stonecutter `//? if >=26.1` blocks exactly like `ServerIpc.init()` for networking API renames.
- The IPC protocol stays at `IpcProtocol.VERSION = 2`. The new bit is additive: `Capabilities.SERVER_WORLDEDIT = 1 shl 5`.
- Forwarded commands are exactly `schematio download <id>` / `schematio quickshareget <code> [password]` (Paper) or `schematio download <qs_code> [password]` (Fabric). Never append flags.
- The integrated server (singleplayer / LAN host) never sends HELLO_SERVER.
- No silent failures. Every failure reaches the player, and on servers it is logged to the console (1.5.1 standard).
- The client never sends the player's schemat.io credentials to a server.
- Not-found copy (both servers, and the LOAD_REQUEST STATUS detail): `Not found (404). Check the ID. If the schematic is private, its owner must join the <community> community on schemat.io for this server to load it.`, where `<community>` falls back to `this server's` when the community is unknown.
- Version bump: `versionMinor=6`, `versionPatch=0` (1.6.0).
- Gradle: run through `ctx_execute`, never backgrounded in Bash. Per-target tasks look like `:fabric:26.3:test`, `:core:test`, `:bukkit:test`.

## Review Focus

1. **Singleplayer / LAN host after this change.** The player must see no "Schematio server detected" message, and `upload`/`quickshare` must still run locally. Owned by Task 5: test `FabricServerIpcTest.integrated server never greets`.
2. **Old Paper plugin (1.5.x) that sends LOAD_CLIPBOARD but no SERVER_WORLDEDIT.** Server WorldEdit must still be available. Owned by Task 2: `ClientAvailabilityTest.legacy paper LOAD_CLIPBOARD counts as server worldedit`.
3. **Forced flag when the destination is missing**, e.g. `--we` in singleplayer without WorldEdit. The player gets an explicit failure, never a Litematica load. Owned by Task 2: `DestinationResolverTest.forced tool never falls back`.
4. **Stored profile from a newer/older version** with unknown or missing entries. Parsing must not crash; unknown names are dropped and missing ones appended. Owned by Task 2: `DestinationProfilesTest.decode tolerates unknown and missing entries`.
5. **Download id that is a full schemat.io URL, or a quick-share code without the `qs_` prefix**, sent to a Fabric server. Owned by Task 3: `DownloadArgsTest.schematic url becomes id` and `ServerDownloadCommandTest.fabric quick share gets qs prefix`.

---

### Task 1: Shared capability bit and server readiness

**Files:**
- Modify: `core/src/main/kotlin/io/schemat/connector/core/ipc/IpcProtocol.kt` (add `Capabilities.SERVER_WORLDEDIT`)
- Create: `core/src/main/kotlin/io/schemat/connector/core/ipc/ServerReadiness.kt`
- Test: `core/src/test/kotlin/io/schemat/connector/core/ipc/ServerReadinessTest.kt`

**Interfaces:**
- Produces:
  - `Capabilities.SERVER_WORLDEDIT: Int = 1 shl 5`
  - `object ServerReadiness { fun serverWorldEdit(worldEdit: Boolean, tokenConfigured: Boolean, downloadEnabled: Boolean, hasPermission: Boolean): Boolean }`
  - `fun notFoundMessage(communityName: String?): String`, returning the Global Constraints copy.

- [ ] **Step 1: Write failing tests** `ServerReadinessTest`:
  - `ready only when all four hold`: iterate the 16 combinations; true only for (true,true,true,true).
  - `not found names the community`: `notFoundMessage("Redshift")` contains `"join the Redshift community"`.
  - `not found falls back`: `notFoundMessage(null)` and `notFoundMessage("")` both contain `"join this server's community"`.
  - `bit is distinct`: `SERVER_WORLDEDIT` and-ed with each existing bit is 0.
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*ServerReadinessTest*'`. Expected: FAIL (unresolved reference).
- [ ] **Step 3:** Implement as specified. Use the exact copy string from Global Constraints.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit `feat(core): SERVER_WORLDEDIT capability and server readiness`.

### Task 2: Destination model, profiles, availability, resolver (core)

**Files:**
- Create: `core/src/main/kotlin/io/schemat/connector/core/routing/Destination.kt`
- Create: `core/src/main/kotlin/io/schemat/connector/core/routing/DestinationProfiles.kt`
- Create: `core/src/main/kotlin/io/schemat/connector/core/routing/DestinationResolver.kt`
- Create: `core/src/main/kotlin/io/schemat/connector/core/routing/Availability.kt`
- Test: `core/src/test/kotlin/io/schemat/connector/core/routing/{DestinationProfilesTest,DestinationResolverTest,ClientAvailabilityTest}.kt`

**Interfaces:**
- Consumes: `Capabilities.SERVER_WORLDEDIT`, `Capabilities.LOAD_CLIPBOARD`, `IpcPlatform`.
- Produces:
  - `enum class Side { SERVER, LOCAL }`; `enum class Tool { WORLDEDIT, LITEMATICA, FILE }`.
  - `enum class Destination(val side: Side, val tool: Tool, val label: String)` with entries, in default order:
    - `SERVER_WORLDEDIT(SERVER, WORLDEDIT, "WorldEdit (server)")`
    - `LOCAL_WORLDEDIT(LOCAL, WORLDEDIT, "WorldEdit (local)")`
    - `LOCAL_LITEMATICA(LOCAL, LITEMATICA, "Litematica")`
    - `LOCAL_FILE(LOCAL, FILE, "Save to disk")`

    `Destination.DEFAULT_ORDER: List<Destination> = entries`.
  - `object DestinationProfiles`:
    - `const val DEFAULT_KEY = "default"`, `const val SINGLEPLAYER_KEY = "singleplayer"`
    - `fun serverKey(address: String): String` → `"server:" + address.trim().lowercase()`
    - `fun encode(order: List<Destination>): String` → comma-joined names
    - `fun decode(raw: String?): List<Destination>?` → null for null/blank. Unknown names are dropped, duplicates removed, missing entries appended in default order.
  - `data class DestinationFilter(val side: Side? = null, val tool: Tool? = null)`.
  - `sealed interface Resolution`:
    - `data class Chosen(val destination: Destination, val fellBackFrom: Destination?)`
    - `data class Unavailable(val reason: String)`
  - `object DestinationResolver { fun resolve(order: List<Destination>, filter: DestinationFilter?, unavailable: Map<Destination, String>): Resolution }`. `unavailable` maps each unavailable destination to its player-facing reason.
  - `object ClientAvailability { fun unavailable(input: AvailabilityInput): Map<Destination, String> }` with `data class AvailabilityInput(val singleplayer: Boolean, val pluginPresent: Boolean, val serverCapabilities: Int, val localWorldEdit: Boolean, val litematica: Boolean)`.

- [ ] **Step 1: Write failing tests:**
  - `DestinationProfilesTest`:
    - `round trip default order`: decode(encode(DEFAULT_ORDER)) == DEFAULT_ORDER.
    - `decode tolerates unknown and missing entries`: `decode("LOCAL_LITEMATICA,BOGUS,LOCAL_LITEMATICA")` == `[LOCAL_LITEMATICA, SERVER_WORLDEDIT, LOCAL_WORLDEDIT, LOCAL_FILE]`.
    - `decode blank is null`.
    - `server key normalizes`: `serverKey(" Play.Example.com:25565 ")` == `"server:play.example.com:25565"`.
  - `DestinationResolverTest`:
    - `first available wins without fallback note`: nothing unavailable → `Chosen(SERVER_WORLDEDIT, null)`.
    - `falls back and remembers the preferred one`: SERVER_WORLDEDIT unavailable → `Chosen(LOCAL_WORLDEDIT, fellBackFrom = SERVER_WORLDEDIT)`.
    - `forced tool never falls back`: filter `tool=WORLDEDIT` with both WorldEdit destinations unavailable (reasons "A", "B") → `Unavailable` whose reason contains "A".
    - `forced tool picks the first matching side`: filter `tool=WORLDEDIT`, nothing unavailable → `Chosen(SERVER_WORLDEDIT, null)`.
    - `side and tool combine`: filter `(LOCAL, WORLDEDIT)` → `Chosen(LOCAL_WORLDEDIT, null)`.
    - `file is the last resort`: everything except LOCAL_FILE unavailable → `Chosen(LOCAL_FILE, SERVER_WORLDEDIT)`.
    - `respects custom order`: order `[LOCAL_LITEMATICA, SERVER_WORLDEDIT, …]` → `Chosen(LOCAL_LITEMATICA, null)`.
  - `ClientAvailabilityTest`:
    - `singleplayer has no server destinations` → SERVER_WORLDEDIT is in the map with reason "Singleplayer has no separate server".
    - `no plugin` → reason "This server has no Schematio Connector".
    - `plugin without worldedit bit` → reason "This server's Schematio can't load into WorldEdit for you".
    - `legacy paper LOAD_CLIPBOARD counts as server worldedit` → not in the map.
    - `local worldedit needs the bridge` → reason "WorldEdit is only available in singleplayer or on a LAN host".
    - `litematica missing` → reason "Litematica is not installed".
    - `file always available`.
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*routing*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Resolver algorithm:
  - candidates = `order` filtered by `filter` (a null filter allows everything).
  - First pass: if any candidate is available, return `Chosen(c, fellBackFrom = first candidate if c != first candidate, else null)`. The fallback note only appears when `filter == null`. With a filter, `fellBackFrom` is always null.
  - Otherwise return `Unavailable(reasons of the candidates joined with "; ")`. With no candidates at all, the reason is "No destination matches those flags".
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit `feat(core): download destination model, profiles and resolver`.

### Task 3: Download argument parsing and forwarded command (core)

**Files:**
- Create: `core/src/main/kotlin/io/schemat/connector/core/routing/DownloadArgs.kt`
- Create: `core/src/main/kotlin/io/schemat/connector/core/routing/ServerDownloadCommand.kt`
- Test: `core/src/test/kotlin/io/schemat/connector/core/routing/{DownloadArgsTest,ServerDownloadCommandTest}.kt`

**Interfaces:**
- Consumes: `DestinationFilter`, `Side`, `Tool`, `IpcPlatform`, `InputValidator.validateQuickShareCode`.
- Produces:
  - `sealed interface ParsedDownload { data class Ok(val id: String, val password: String?, val filter: DestinationFilter?) : ParsedDownload; data class Error(val message: String) : ParsedDownload }`
  - `object DownloadArgs { fun parse(raw: String, quickShare: Boolean): ParsedDownload }`. Flags:
    - `--worldedit|--we` → tool WORLDEDIT
    - `--litematica|--lm` → tool LITEMATICA
    - `--file` → tool FILE
    - `--server` → side SERVER
    - `--local` → side LOCAL

    Any other `--x` → `Error("Unknown flag --x. Use --we, --lm, --file, --server or --local.")`. Two tool flags → `Error("Choose one of --we, --lm or --file.")`. `--server` with `--file` or `--lm` → `Error("Only WorldEdit can load on the server.")`. The first non-flag token is the id; for `quickShare` the second is the password. A non-quickShare id matching `https?://[^/]+/schematics/([^/?#]+)` is replaced by the captured group.
  - `enum class DownloadKind { SCHEMATIC, QUICK_SHARE }`
  - `object ServerDownloadCommand { fun build(platform: IpcPlatform?, kind: DownloadKind, id: String, password: String?): String }`. The command has no leading slash. Platform null (pre-v2 hello) counts as Paper.

- [ ] **Step 1: Write failing tests:**
  - `DownloadArgsTest`:
    - `plain id` → `Ok("44srfK", null, null)`.
    - `flags anywhere` → `parse("--we 44srfK", false)` == `Ok("44srfK", null, DestinationFilter(tool=WORLDEDIT))`.
    - `schematic url becomes id` → `parse("https://schemat.io/schematics/44srfK", false)` id == `"44srfK"`.
    - `quick share password` → `parse("qs_abc123 hunter2 --lm", true)` == `Ok("qs_abc123", "hunter2", DestinationFilter(tool=LITEMATICA))`.
    - `unknown flag is an error`, `two tools is an error`, `server file is an error`.
    - `missing id` → `Error("Give a schematic ID or schemat.io link.")`. For quick shares: `"Give a quick share code."`.
  - `ServerDownloadCommandTest`:
    - `paper schematic` → `"schematio download 44srfK"`.
    - `legacy null platform is paper quick share` → `build(null, QUICK_SHARE, "qs_abc123", "pw")` == `"schematio quickshareget qs_abc123 pw"`.
    - `fabric quick share gets qs prefix` → `build(FABRIC_SERVER, QUICK_SHARE, "abc12345", null)` == `"schematio download qs_abc12345"`.
    - `fabric keeps existing prefix` → `"qs_abc12345"` stays as is.
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*DownloadArgsTest*' --tests '*ServerDownloadCommandTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. For the Fabric quick-share prefix, use the `validateQuickShareCode` result value (it extracts a code from URLs too), then prefix `qs_` when absent.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit `feat(core): download flags, schematic URLs and per-platform forwarded commands`.

### Task 4: Platform-aware dispatch (core)

**Files:**
- Modify: `core/src/main/kotlin/io/schemat/connector/core/ipc/DispatchTable.kt`
- Modify: `core/src/test/kotlin/io/schemat/connector/core/ipc/DispatchTableTest.kt`

**Interfaces:**
- Produces: `DispatchTable.resolve(action: ClientAction, pluginPresent: Boolean, platform: IpcPlatform?): DispatchMode`. DOWNLOAD and QUICKSHARE_GET always return `LOCAL`; the client router decides them now. UPLOAD and QUICKSHARE return `SERVER_COMMAND` only when the plugin is present and the platform is `PAPER_PLUGIN` or null. BROWSE stays `LOCAL`.

- [ ] **Step 1:** Rewrite `DispatchTableTest`:
  - `fabric server keeps upload local`.
  - `paper forwards upload and quickshare`.
  - `legacy null platform forwards like paper`.
  - `download is never dispatched here`: DOWNLOAD and QUICKSHARE_GET → LOCAL for every platform.
  - `no plugin is local`.
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*DispatchTableTest*'`. Expected: FAIL (signature).
- [ ] **Step 3:** Implement. Update the single caller (`SchematioClientCommands.forwards`) to pass `ServerSession.platform` so `:fabric:*` still compiles; Task 8 rewrites that caller.
- [ ] **Step 4:** Run `:core:test` and `:fabric:26.3:compileClientKotlin`. Expected: PASS / BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `feat(core): keep upload/quickshare local on Fabric servers`.

### Task 5: Fabric server handshake and not-found message

**Files:**
- Move: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/SchematioPayload.kt` → `fabric/src/main/kotlin/io/schemat/connector/fabric/ipc/SchematioPayload.kt` (package `io.schemat.connector.fabric.ipc`). Update client imports.
- Create: `fabric/src/main/kotlin/io/schemat/connector/fabric/ipc/FabricServerIpc.kt`
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerIpc.kt` (remove `PayloadTypeRegistry` registration; keep the receiver)
- Modify: `fabric/src/main/kotlin/io/schemat/connector/fabric/SchematioConnectorMod.kt` (call `FabricServerIpc.register(this)` in `onInitialize`; call `FabricServerIpc.greetAll()` at the end of `reload()` and `setApiToken()`; expose `val apiTokenConfigured: Boolean`)
- Modify: `fabric/src/main/kotlin/io/schemat/connector/fabric/command/SchematioCommand.kt` (404 copy via `notFoundMessage(null)`; the existing WARN log stays)
- Test: `fabric/src/test/kotlin/io/schemat/connector/fabric/ipc/FabricServerIpcTest.kt`

**Interfaces:**
- Consumes: `ServerReadiness.serverWorldEdit`, `notFoundMessage`, `Capabilities`, `IpcCodec.encodeHelloServer`, `HelloServer`, `IpcPlatform.FABRIC_SERVER`.
- Produces:
  - `object FabricServerIpc`:
    - `fun register(mod: SchematioConnectorMod)`: registers the payload type both directions, once, in the main initializer. It runs on the client too, so `ServerIpc` must no longer register it.
    - `fun greetAll()`
    - `internal fun capabilitiesFor(dedicated: Boolean, worldEdit: Boolean, tokenConfigured: Boolean, downloadEnabled: Boolean): Int?`: null means "do not greet". Otherwise `DOWNLOAD_CMD or (SERVER_WORLDEDIT if ready)`.

- [ ] **Step 1: Write failing tests** `FabricServerIpcTest`:
  - `integrated server never greets`: `capabilitiesFor(dedicated=false, …)` is null for all inputs.
  - `dedicated with worldedit and token advertises server worldedit`: `== DOWNLOAD_CMD or SERVER_WORLDEDIT`.
  - `disabled download hides server worldedit`: `== DOWNLOAD_CMD`.
  - `never advertises attestation-gated bits`: the result and-ed with `LOAD_CLIPBOARD or UPLOAD or WANTS_COMMAND_OWNERSHIP` is 0.
- [ ] **Step 2:** Run `./gradlew :fabric:26.3:test --tests '*FabricServerIpcTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement `FabricServerIpc`:
  - `ServerPlayConnectionEvents.JOIN` → greet the player when `ServerPlayNetworking.canSend(player, TYPE)`.
  - `ServerPlayNetworking.registerGlobalReceiver(TYPE)`:
    - HELLO_CLIENT → greet, at most once per connection; reset on DISCONNECT. `greetAll()` clears that state and re-greets.
    - Other opcodes → `LOGGER.debug` and ignore.
  - The HELLO_SERVER fields are protocolVersion `IpcProtocol.VERSION`, pluginVersion = mod version, platform `FABRIC_SERVER`, serverSoftware `"Fabric"`, mcVersion from `SharedConstants`, backendHost = `mod.baseUrl`, communityId/Slug `""`.
  - `downloadEnabled = "download" !in mod.disabledCommands`.
  - Mirror `ServerIpc.init()`'s Stonecutter split for the `PayloadTypeRegistry` names.
- [ ] **Step 4:** Run `./gradlew :fabric:26.3:test :fabric:1.21.8:test :fabric:26.1:compileClientKotlin`. Expected: PASS, all green.
- [ ] **Step 5:** Commit `feat(fabric): announce the Fabric server Connector on schematio:c`.

### Task 6: Paper per-player SERVER_WORLDEDIT, re-greet, not-found copy

**Files:**
- Modify: `bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/PluginIpcService.kt`:
  - `capabilitiesFor(worldEditAvailable, uploadConfigured, tokenConfigured, downloadEnabled, hasPermission)` adds `SERVER_WORLDEDIT` via `ServerReadiness`;
  - `greet(player)` passes `player.hasPermission("schematio.download")`;
  - new `fun regreetAll()` clears `greeted` and greets each online player.
- Modify: `ReloadSubcommand.kt` and `SetTokenSubcommand.kt`: call `plugin.ipcService?.regreetAll()` after a successful reload or token save.
- Modify: `DownloadSubcommand.kt:332-333` and `QuickShareGetSubcommand.kt:336`: send `notFoundMessage(plugin.communitySlug)` and log a WARN with the id and player.
- Modify: `PluginIpcService.finishLoadRequest`: the `ClipboardResolveOutcome.NotFound` STATUS detail becomes `notFoundMessage(plugin.communitySlug)`.
- Modify: `bukkit/src/test/kotlin/io/schemat/schematioConnector/ipc/PluginIpcCapabilitiesTest.kt`

**Interfaces:**
- Consumes: `ServerReadiness.serverWorldEdit`, `notFoundMessage`, `Capabilities.SERVER_WORLDEDIT`.

- [ ] **Step 1:** Extend `PluginIpcCapabilitiesTest`:
  - `server worldedit needs permission`: has=false → bit clear; has=true with token, download enabled and WorldEdit → bit set.
  - `load clipboard unchanged`: LOAD_CLIPBOARD still depends only on WorldEdit.
  - Update the existing assertions to the new signature.
- [ ] **Step 2:** Run `./gradlew :bukkit:test --tests '*PluginIpcCapabilitiesTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Token configured = `plugin.httpUtil != null` (or the plugin's existing "connected" check). Download enabled = `"download"` is not disabled in config (use the plugin's existing disabled-commands accessor).
- [ ] **Step 4:** Run `./gradlew :bukkit:test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat(paper): per-player SERVER_WORLDEDIT and explicit not-found message`.

### Task 7: Client availability, profile storage and download router

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/auth/ClientAuthManager.kt` (add `fun getConfigString(name: String): String?` and `fun setConfigString(name: String, value: String?)`; null removes the key; reuse `updateConfig`)
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/routing/ClientDestinations.kt`
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/routing/DownloadRouter.kt`

**Interfaces:**
- Consumes: Task 2/3 types; `ServerSession` (`pluginPresent`, `capabilities`, `platform`); `ServerIpc.canLoadOnServer()`, `ServerIpc.sendLoadRequest`; `Bridges.worldEdit`, `Bridges.litematica`; `services.cached.download(id, format, password)`.
- Produces:
  - `object ClientDestinations`:
    - `fun profileKey(): String`: `SINGLEPLAYER_KEY` when `Minecraft.getInstance().singleplayerServer != null`; `serverKey(currentServer.ip)` when connected to a server; otherwise `DEFAULT_KEY`.
    - `fun order(key: String = profileKey()): List<Destination>`: the stored profile, else the stored default, else `DEFAULT_ORDER`.
    - `fun hasOwnProfile(key: String): Boolean`
    - `fun save(key: String, order: List<Destination>?)`: null resets.
    - `fun unavailable(): Map<Destination, String>`: builds `AvailabilityInput` from live state.

    Config keys are `download_destinations.<profile key>`.
  - `object DownloadRouter { fun route(kind: DownloadKind, parsed: ParsedDownload.Ok, reply: (String, Boolean) -> Unit) }`. `reply(text, isError)` sends chat or panel status.
  - `fun DownloadRouter.load(destination: Destination, kind: DownloadKind, id: String, password: String?, name: String, reply: (String, Boolean) -> Unit, onFailure: (String) -> Unit)` executes a single destination.

- [ ] **Step 1:** No unit test can run against live Minecraft state here: Task 2's `ClientAvailabilityTest` covers the mapping, and this task only wires it.
- [ ] **Step 2:** Implement `route`:
  - Resolve via `DestinationResolver`.
  - `Unavailable` → `reply(reason, true)`.
  - `Chosen` with `fellBackFrom != null` → first `reply("<fellBackFrom.label> unavailable: <reason>. Loading into <destination.label> instead.", false)`.
- [ ] **Step 3:** Implement `load` per destination:
  - **SERVER_WORLDEDIT:**
    - If `ServerIpc.canLoadOnServer()`: `sendLoadRequest(LoadRefType.SCHEMATIC or SHARE_TOKEN, id)`. A terminal non-OK status calls `onFailure(detail)`. `route` passes an `onFailure` that, when the parsed filter is null, re-resolves with this destination marked unavailable (reason = status detail) and loads the next one.
    - Else `forwardToServer(ServerDownloadCommand.build(ServerSession.platform, kind, id, password))`, then `reply("Sent to the server's WorldEdit. The server will confirm in chat.", false)`.
  - **LOCAL_WORLDEDIT:** `services.cached.download(id, "schem", password)` → `Bridges.worldEdit.bytesToClipboard(bytes, "schem")`, then reply "Loaded "<name>" into your WorldEdit clipboard".
  - **LOCAL_LITEMATICA:** the existing `downloadAndLoad` behavior, moved here from `SchematioClientCommands`, then reply "Loaded "<name>" into Litematica".
  - **LOCAL_FILE:** download `litematic`, then `SchematicFiles.save`, then reply "Saved to <path>".
  - Every API failure → `reply("Could not download …: <toUserMessage()>", true)`.
  - Move `forwardToServer` (with its KDoc) into `DownloadRouter` as `internal fun forwardToServer(command: String): Boolean`.
- [ ] **Step 4:** Run `./gradlew :fabric:26.3:compileClientKotlin :fabric:1.21.8:compileClientKotlin`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `feat(fabric-client): destination availability, per-world profiles and download router`.

### Task 8: Client commands use the router

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/command/SchematioClientCommands.kt`

**Interfaces:**
- Consumes: `DownloadArgs.parse`, `DownloadRouter.route`, `DownloadRouter.forwardToServer`, `DispatchTable.resolve(action, pluginPresent, platform)`.

- [ ] **Step 1:** `download` and `quickshareget` each take one `StringArgumentType.greedyString()` argument named `args`. Their executors call `DownloadArgs.parse(args, quickShare)`. An `Error` goes to `ChatNotice.error(message)`; an `Ok` goes to `DownloadRouter.route`, with `reply` mapped to `ChatNotice.info` / `ChatNotice.error`.
- [ ] **Step 2:** Remove `downloadAndLoad`/`saveAndLoad` and the local `forwardToServer` (they moved in Task 7). UPLOAD and QUICKSHARE use `DispatchTable.resolve(action, ServerSession.pluginPresent, ServerSession.platform)`.
- [ ] **Step 3:** Update the KDoc and the `help` list:
  - `/schematio download <id|link> [--we|--lm|--file] [--server|--local]`: "download to your preferred destination".
  - `/schematio quickshareget <code> [password] [flags]`.
- [ ] **Step 4:** Run `./gradlew :fabric:26.3:test :fabric:1.21.8:test :fabric:26.1:compileClientKotlin`. Expected: PASS.
- [ ] **Step 5:** Commit `feat(fabric-client): route /schematio download by destination priority`.

### Task 9: Detail panel buttons in priority order

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/SchematicDetailPanel.kt:343-375` (the load-button row) and its handlers `loadIntoLitematica`, `toWorldEditClipboard`, `loadOnServer`, `saveToDisk`.

**Interfaces:**
- Consumes: `ClientDestinations.order()`, `ClientDestinations.unavailable()`, `DownloadRouter.load`.

- [ ] **Step 1:** Replace the fixed buttons with a loop over `ClientDestinations.order()`, skipping unavailable entries (LOCAL_FILE always shows). The first rendered button uses the existing ACCENT style (as the Edit button does); the rest use `Widgets.button`. Labels:
  - "Load into WorldEdit (server)"
  - "Load into WorldEdit"
  - "Load into Litematica"
  - "Save to disk"

  Keep `actionsDisabled` gating, and keep `pendingServerLoad` disabling the server button.
- [ ] **Step 2:** Each button calls `DownloadRouter.load(dest, SCHEMATIC, d.id, null, d.name, reply = { text, err -> statusText = text; statusKind = if (err) DANGER else SUCCESS }, onFailure = { statusText = it; statusKind = DANGER })`. There's no automatic fallback from a button: the player picked it. The existing transfer-epoch and cancel behavior stays for the local paths.
- [ ] **Step 3:** Run `./gradlew :fabric:26.3:compileClientKotlin :fabric:1.21.8:compileClientKotlin`, plus `:fabric:26.3:themeCheck` if that task exists (grep the build for it). Expected: BUILD SUCCESSFUL.
- [ ] **Step 4:** Commit `feat(fabric-client): detail panel offers every destination in priority order`.

### Task 10: Settings section "Download destination"

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/SettingsPanel.kt` (new section after the "Allow this server to open Schematio windows" block, around line 135)

**Interfaces:**
- Consumes: `ClientDestinations.{profileKey, order, hasOwnProfile, save, unavailable}`, `DestinationProfiles.DEFAULT_KEY`.

- [ ] **Step 1:** Render the section:
  - A header names the current world: "This server (<address>)" or "Singleplayer". When `!hasOwnProfile(key)`, add the line "Using your default order".
  - A numbered row per destination, with up/down arrow buttons. Pressing one saves the swapped order under the current key.
  - Show the availability reason in muted text when the destination is unavailable.
  - A "Reset to default" button calls `save(key, null)`.
  - Below that, a collapsible "Default order" with the same controls for `DEFAULT_KEY`.

  Follow the panel's existing `Widgets`/`ImGuiColors` usage.
- [ ] **Step 2:** Run `./gradlew :fabric:26.3:compileClientKotlin :fabric:1.21.8:compileClientKotlin`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `feat(fabric-client): per-world download destination settings`.

### Task 11: Backend rule: community token reads what its member can view

**Repo:** `Schem-at/schemati`. Make a worktree from `origin/main`, branch `feat/community-member-read`.

**Files:**
- Modify: `app/Http/Controllers/SchematicController.php`, `communityTokenCanRead` (around line 713)
- Test: `tests/Feature/Api/ApiObservabilityAndRegistryAccessTest.php`

**Interfaces:**
- Produces: `communityTokenCanRead($request, $schematic)` returns true when `communityTokenCanReach` holds, or the guest can view, or **all** of:
  - the request's `player_uuid` resolves to a `Player`;
  - `$community->hasMember($player)` for the token's `community_id`;
  - `TagVisibilityResolver::canView($schematic, $playerUser)`, where `$playerUser` is the `User` with that player's uuid.

  `update`/`destroy` still use `communityTokenCanReach`.

- [ ] **Step 1: Write failing Pest tests**, all through `postJson("/api/v1/schematics/{id}/download", ['player_uuid' => …])` with a community token:
  - `lets a community token download a member's private schematic` → 200.
  - `hides a private schematic when the player is not a community member` → 404.
  - `hides a private schematic the member cannot view` (owned by someone else) → 404.

  Use the existing helpers `modPlayer`, `modCommunity`, `modSchematic(..., isPublic: false)`. Add membership with whatever `Community::members()` attaches (check the `hasMember` implementation).
- [ ] **Step 2:** Run `php artisan test --filter=ApiObservabilityAndRegistryAccessTest`. Expected: the first new test fails with 404.
- [ ] **Step 3:** Implement as specified. Resolve the player with the same lookup that `actor_uuid` uses at line ~681.
- [ ] **Step 4:** Run the same filter. Expected: all tests in the file pass (9 existing + 3 new).
- [ ] **Step 5:** Commit, push, and open a PR titled "Let community tokens read what their member can view". Do not merge or deploy without the user.

### Task 12: Release notes, full validation, headless handshake check, PR

**Files:**
- Modify: `gradle.properties` (`versionMinor=6`, `versionPatch=0`), `README.md` (release line and jar names)
- Create: `docs/release-1.6.0.md`

- [ ] **Step 1:** Run `scripts/validate.py` (non-client checks), or at minimum `:core:test :bukkit:test` and `:fabric:<v>:test` for all seven targets. Expected: every task passes; record the counts.
- [ ] **Step 2: Headless handshake check on schematio0** (harness `/root/sc-headless`, see memory `headless-server-testing`):
  - Build `:fabric:26.1:assemble` and run `e2e_261.sh` with a bot variant that registers `schematio:c` (`bot._client.registerChannel('schematio:c', ['buffer', []], true)`) and records the first payload.
  - Expected: byte 0 is `1` (HELLO_SERVER), and the decoded capability varint has bits `DOWNLOAD_CMD` and `SERVER_WORLDEDIT` set. Decoding follows `IpcCodec.decodeHelloServer`'s layout: opcode, varint version, string pluginVersion, varint capabilities.
  - The existing download → `//paste` end-to-end still passes.
- [ ] **Step 3:** Write `docs/release-1.6.0.md`. Say plainly that client routing was unit-tested and the server side was tested headlessly, but routing was not exercised in a real client.
- [ ] **Step 4:** Commit, push `feat/download-destinations`, and open the PR. Do not merge, tag or deploy without the user.
