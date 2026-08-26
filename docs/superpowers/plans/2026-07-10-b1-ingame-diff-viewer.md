# B1: In-Game Diff Viewer + Conflict Resolution — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (TDD where testable). Spec: `docs/superpowers/specs/2026-07-10-ingame-diff-viewer-design.md` (authoritative for behavior).

**Part 1 = SchematioConnector bukkit (+core) — executable now. Part 2 = schemati plugin endpoints — execute only after the VCS-hardening changes in schemati land (same routes file).**

## Part 1 — SchematioConnector (branch `feature/ingame-diff-viewer` off master)

Verification gate after every task: `./gradlew :core:test :bukkit:build` (fabric untouched). Commit per task (this repo commits are fine; do not push).

### Task 1: Nucleation runtime in bukkit
- `bukkit/build.gradle.kts`: shade the same Nucleation JVM artifact fabric uses (`:nucleation:<nucleation_version>` from `fabric/build.gradle.kts:154-155` — hoist `nucleation_version` consumption; jar carries natives, see `docs/nucleation-build.md`).
- New `bukkit .../vcs/NucleationRuntime.kt`: lazy singleton, `val available: Boolean` (native load in try/catch, log once). All diff features check it; unavailable → user-facing "diff not available on this server platform".

### Task 2: schematic bridge via .schem bytes (no per-block mapping)
- `bukkit .../vcs/SchematicBridge.kt`: WE clipboard → sponge `.schem` bytes (existing WorldEditUtil write path used by upload) → Nucleation parse; and Nucleation schematic → `.schem` bytes → WE clipboard (existing download path). Block entities ride along for free. Unit test with a tiny fixture (reuse pattern from fabric's `single_stone.schem` test resource).

### Task 3: checkout tracking
- Extend `BukkitPlayerStorage` (bukkit/src/main/kotlin/io/schemat/connector/bukkit/adapter/BukkitPlayerStorage.kt) with `checkout: {schematicId, versionId, branchId}` persisted per player; write it in DownloadSubcommand (and any paste path) when a versioned schematic is fetched. Read-only consumer for now (stage C's 3-way base).

### Task 4: version API client (core)
- `core .../modapi/VersionApi.kt` (+ wire into `CachedSchematioApi` style): the four plugin endpoints from the spec §1; commit returns sealed `CommitResult { Ok(version) | HeadMoved(newHead) | Error(ApiError) }` mapping the 409 `head_moved` contract. Pure-JVM tests with a fake transport (follow existing core http test patterns).

### Task 5: DiffSession model (pure Kotlin, core or bukkit/vcs)
- `DiffSession` per spec §3 + `DiffRegion` parsed from Nucleation `diff_regions_json`/summary (id, bounds, counts, kind mix). Cursor ops, per-region choice map, `allDecided`, lifecycle state machine VIEW/RESOLVE. Fully unit-tested without Bukkit.

### Task 6: VanillaDisplayRenderer (ProtocolLib)
- `bukkit .../vcs/render/VanillaDisplayRenderer.kt` implementing `DiffRenderer` (spec §4): spawn/metadata/destroy packets to the ONE viewing player via ProtocolLib (`ProtocolLibHandler` exists — extend it). BlockDisplay for blocks (ADDED real block teal glow / REMOVED old block red glow / CHANGED new block orange glow via glow-color metadata + scale 0.98 transform), TextDisplay region labels, bounding boxes from thin BlockDisplay slabs.
- Entity ids from a reserved negative range; track per-player spawned ids; `clear()` sends destroy. Budget/LOD per spec (≤2000; focused region full detail, others box+label; oversized focused region renders densest slice + warning label). Listener: cleanup on quit/world change; scheduler task for 10-min idle disposal.
- Testable core extracted: `DisplayBudget.kt` (pure math: which cells render for a focus+budget) unit-tested; packet layer thin.

### Task 7: commands + dual-mode controls
- `commands/DiffSubcommand.kt` following `Subcommand.kt` pattern: `diff <id> <verA> <verB>`, `next/prev/goto/layers/close/mine/theirs/done/abort` per spec §5; clickable chat components (adventure) + dialog variant via core `DialogService` builders, chosen by `UIModeResolver`. Permission node `schematio.diff` (+ `schematio.version.commit` for resolve actions) registered in plugin.yml.

### Task 8: conflict resolve flow
- Upload/commit path: when the player commits to a versioned schematic (new `commit` action on UploadSubcommand or dedicated `CommitSubcommand`), call VersionApi with `expected_head_version_id` from checkout tracking (fallback: fetch current head first). On `HeadMoved`: download new head, `SchematicBridge` both sides, Nucleation diff (guarded by NucleationRuntime.available — unavailable = show "conflict; resolve on the web" with link), open RESOLVE session.
- `done` → compose per spec §6 (start from new HEAD schematic, copy MINE regions' cells from player edit via Nucleation region ops), serialize, re-commit with new expected head; second HeadMoved restarts. Unit-test compose with small schematics through the real bridge.

### Task 9: verification + docs
- Full gate `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline` (fabric must still build untouched). Update `fabric/MANUAL_TESTING.md` with the run-paper diff checklist from spec §7 (second-player-sees-nothing check included).

## Part 2 — schemati plugin endpoints (AFTER hardening merge-ready)

### Task 10: routes + controller
- `routes/api.php` plugin group: the four endpoints (spec §1) → new `App\Http\Controllers\Api\PluginVersionController` delegating to `SchematicVersioningService`; commit accepts `expected_head_version_id`, compares to branch HEAD inside the service's locked transaction, returns 409 `{error:"head_moved", head: SchematicVersionResource}` on mismatch; acting player UUID membership-checked like existing PluginController endpoints; hardened quotas (S5) applied via the same middleware.
### Task 11: Pest tests
- Endpoint matrix: token scoping, membership, download bytes, commit happy path, 409 contract (expected≠head), quota/throttle presence, non-versioned schematic behavior (404/422).
