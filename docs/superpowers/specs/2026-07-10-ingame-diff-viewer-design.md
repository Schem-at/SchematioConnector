# In-Game Diff Viewer + Conflict Resolution

**Date:** 2026-07-10 · **Status:** Approved (design review with Harrison)
**Repos:** `SchematioConnector` (bukkit primary, fabric later), `schemati` (plugin API)
**Prerequisite:** VCS hardening (schemati `docs/superpowers/specs/2026-07-10-vcs-hardening-design.md`), especially commit concurrency + the 409 contract.

## Goal

Players on a Paper server (no client mod required) can view schematic version diffs in-world and resolve commit-time conflicts, rendered with **client-bound-only display entities** so no other player sees anything. Modded clients get the same flows rendered natively. 3-way branch merge plugs into the same UI later.

## Stages

- **B1 (this spec's deliverable):** plugin API + plugin-local Nucleation + vanilla display-entity viewer + commit-time conflict resolution.
- **B2:** mod rendering backend over IPC (after handshake v2 lands; new capability bit `DIFF_VIEWER`).
- **C:** Nucleation 3-way merge engine + branch merge; reuses B1's session/resolution UI with conflict-region classification swapped in.

## B1 Design

### 1. Plugin API (schemati, `/api/plugin/*`, community-JWT)
- `GET  plugin/schematics/{id}/branches`
- `GET  plugin/schematics/{id}/branches/{branchId}/commits` (paginated)
- `GET  plugin/schematics/{id}/versions/{versionId}/download`
- `POST plugin/schematics/{id}/branches/{branchId}/commits` — multipart file + `message` + **`expected_head_version_id`**. If the branch HEAD ≠ expected: **409** `{error:"head_moved", head: <version resource>}`. Same quotas as the hardened user-token commit route; the acting player UUID is passed and membership-checked like existing plugin routes.

### 2. Plugin-local Nucleation (bukkit module)
- Bukkit module gains the Nucleation JVM bindings (same artifact the fabric client bundles; natives per platform, loaded lazily — if load fails, diff features degrade gracefully to "not available on this server" while up/download keep working).
- `BukkitSchematicBridge`: WorldEdit clipboard / world region → Nucleation schematic (`BlockData#getAsString()` both directions), preserving block entities.
- **Checkout tracking:** whenever a player downloads/pastes a version, the plugin records `{schematicId, versionId, branchId}` per player (persisted in the plugin's existing per-player storage) — the future 3-way base.

### 3. DiffSession (per-player, server-side)
`DiffSession { schematicId, base, other, labels, anchor (pos+rotation), regions[], cursor, choices: Map<regionIdx, MINE|THEIRS>, mode: VIEW|RESOLVE }`
- Entry points: `/schematio diff <id> <verA> <verB>` (VIEW, anchored where the player stands or at their WE selection), and automatic on commit-409 (RESOLVE, anchored at the player's edit site).
- One session per player; opening a new one disposes the old. Disposal on: done/cancel, quit, world change, 10-min idle.

### 4. Rendering: `DiffRenderer` interface
`show(session)`, `focusRegion(i)`, `setLayerVisible(kind, bool)`, `clear()` — implementations:

**VanillaDisplayRenderer (B1):**
- Per-player packet-level BlockDisplay/TextDisplay entities (Paper API; spawn packets to the viewing player only, entities never enter the world).
- Vocabulary: ADDED = real block ghost (scale 0.98, teal glow); REMOVED = old block, low-alpha equivalent + red glow; CHANGED = new block + orange glow; per-region bounding box (thin display slabs) + TextDisplay label (`Region 3/12 · +45 −12 ~7`).
- **Budget:** ≤2,000 block displays per player. Focused region renders per-block; unfocused regions render as bounding box + label only. A focused region larger than the budget renders its densest slice + warning label.
- Layer toggles hide/show kinds. All entity ids come from a reserved negative range to avoid collisions.

**IpcDiffRenderer (B2, later):** serialize `{regions, blocks (paletted), anchor}` over the IPC channel (new opcodes + `DIFF_VIEWER` capability); the fabric client renders via `SchematicRenderEngine` overlay. Server chooses backend per player from the handshake session.

### 5. Controls (dual-mode: chat components + native dialogs 1.21.7+, matching existing plugin UX)
`/schematio diff …` subcommands + clickable chat / dialog buttons:
- `next` / `prev` / `goto <n>` — focus region (teleport-free; camera hint via label glow)
- `layers <added|removed|changed> <on|off>`
- VIEW mode: `close`
- RESOLVE mode: `mine` / `theirs` (choice for the focused region), `done` (all regions chosen → compose), `abort`

### 6. Conflict resolution flow (RESOLVE)
1. Player commits (upload path unchanged) → 409 head_moved.
2. Plugin downloads new HEAD, diffs **player's edit vs new HEAD** locally (Nucleation), opens RESOLVE session ("Your commit conflicts with <n> newer change(s) by <authors>").
3. Player walks regions choosing mine/theirs. `done` composes: start from new HEAD, apply every MINE region's blocks from the player's edit (Nucleation region copy ops).
4. Plugin re-commits with `expected_head = new HEAD id`. A second 409 (HEAD moved again) restarts the flow against the newer HEAD.
- Choices default to none; `done` requires all regions decided (chat summary lists undecided ones).

### 7. Testing
- Bukkit unit: session lifecycle, budget/LOD math, region cursor, compose logic (pure Kotlin, Nucleation mocked behind the bridge interface).
- Integration: existing run-paper task — scripted bot or manual checklist in `fabric/MANUAL_TESTING.md` (spawn diff, verify second player sees nothing, resolve both ways, 409 loop).
- schemati Pest: the four plugin endpoints incl. 409 contract + quotas.

## Out of scope (B1)
Rotation/mirror-aware anchoring beyond what the WE paste applied; partial-region (per-block) resolution; entity diffs; viewing diffs of schematics the community token can't access.
