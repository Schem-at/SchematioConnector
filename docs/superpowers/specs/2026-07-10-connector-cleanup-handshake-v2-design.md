# SchematioConnector: Client Cleanup, Merge, and Verified Handshake v2

**Date:** 2026-07-10
**Status:** Approved (design review with Harrison, 2026-07-10)
**Repos touched:** `SchematioConnector` (primary), `schemati` (one backend endpoint pair)

## Context

`feature/imgui-ui-migration` is 109 commits ahead of `master` (v1.2.4), 0 behind. It contains the
full Dear ImGui client UI (dockable workspace, browser, upload wizard, WYSIWYG editor, native
preview renderer) plus the IPC foundation (channel `schematio:c`, `HELLO_SERVER`/`HELLO_CLIENT`,
capability bitset with a reserved `VERSION_CONTROL` bit). The version-control integration is
blocked on (a) this branch being merged and (b) the client knowing, with confidence, what kind of
server it is talking to.

## Goals

1. Merge the ImGui client to `master` in structurally clean shape (Phase 1).
2. Give the client a **verified identity handshake**: platform, versions, backend/community
   binding, cryptographic proof (Phase 2).
3. Capability-driven UI: one connection indicator; actions route by advertised capabilities
   (Phase 2).
4. Close the remaining ImGui feature-parity TODOs (Phase 3).

## Non-goals

- The version-control system itself (this work unblocks it; it does not build it).
- Any change to existing panel UX beyond file moves/splits.
- Protocol v1 removal — v1 peers must keep working, downgraded to "legacy" trust.

## Staging

Phase 1 lands on `feature/imgui-ui-migration` and **squash-merges to master first** (v1.3.0).
Phase 2 (`feature/handshake-v2`) and Phase 3 (`feature/imgui-parity`) branch off the new master
and may proceed in parallel. The schemati backend change lands independently ahead of Phase 2
client/server work.

---

## Phase 1 — Structural cleanup + merge

### Repo hygiene
- Delete untracked debris `imgui/ImDrawData.class`; add `.history/` to `.gitignore`.
- Resolve the uncommitted 2-line `Widgets.kt` change (commit or drop after inspection).

### Target package layout (fabric client)

| From | To | Notes |
|---|---|---|
| `client/imgui/*` (ImGuiManager, ImGuiGl3Renderer, DockHost, PanelManager, Panel, ImGuiOverlay, Toolbar) | `client/ui/framework/` | Runtime/windowing layer, no app logic |
| `client/ui/imgui/panels/*` | `client/ui/panels/` | App panels |
| `client/ui/imgui/*.kt` (Widgets, ConfirmModal, TagSelectorPopup, RichTextWidget, RichTextEditorWidget, PlayerListPicker, ImGuiTheme) | `client/ui/widgets/` (theme → `client/ui/theme/`) | Absorbs legacy `ui/widgets/ExportSourcePicker.kt` |
| `ui/theme/Theme.kt` + `ui/imgui/ImGuiTheme.kt` | `client/ui/theme/` | Single theme home |
| `ui/compat/Draw.kt` | fold into `client/ui/widgets/` | 3 external refs |
| `ui/ChatNotice.kt`, `ui/PreviewImageManager.kt` | `client/services/` | Not UI surfaces |
| `ui/foundation/` | unchanged | Healthy; 20 external refs |

After the moves, the packages `client/imgui` and `client/ui/imgui` no longer exist.

### File splits (mechanical, no behavior change)
- `UploadWizardPanel.kt` (969) → one file per wizard step + a thin panel shell.
- `TagSelectorPopup.kt` (983) → tag-tree model/filtering vs popup rendering.
- `RichTextEditorWidget.kt` (939) → editing core (caret/selection/commands) vs draw-list renderer.

### Merge criteria
- Full Gradle test suite green across all Stonecutter versions (1.21.8–1.21.11, 26.1) + `:core` + `:bukkit`.
- `run-paper` smoke: join, handshake v1 works as before, upload/download round-trip.
- Squash-merge to `master`, tag **v1.3.0** per RELEASING.md.

---

## Phase 2 — Verified handshake (protocol v2)

### Flow

```
S→C  HELLO_SERVER   (announce; unattested)
C→S  HELLO_CLIENT   (now carries a 16-byte SecureRandom nonce)
S→B  POST /api/plugin/attest {nonce,...}     (server → backend, community JWT)
S→C  ATTEST         (backend-signed attestation, relayed verbatim)
```

- Client-first joins (server never sent HELLO_SERVER, e.g. race): existing join-fallback
  HELLO_CLIENT already carries the nonce; server replies HELLO_SERVER then ATTEST.
- Attestation failure/timeout (backend down, rate-limited): server sends nothing further;
  client settles at `UNVERIFIED` after 10 s. Never blocks gameplay or v1 features.

### Message changes (`core/ipc`, protocol VERSION = 2)

- `HELLO_SERVER` v2 adds: `platform` (enum: `PAPER_PLUGIN`, `FABRIC_SERVER`), `serverSoftware`
  (e.g. "Paper 1.21.8"), `mcVersion`, `backendHost`, `communityId`, `communitySlug`.
- `HELLO_CLIENT` v2 adds: `nonce: ByteArray(16)`.
- New opcode `ATTEST = 4`: `payloadJson: String`, `signature: ByteArray(64)`, `keyId: String`.
- v1 decoding kept: a v1 `HELLO_SERVER` yields trust `LEGACY_V1`; a v2 server receiving a v1
  `HELLO_CLIENT` (no nonce) skips attestation.

### Attestation payload (canonical JSON, sorted keys, signed as UTF-8 bytes)

```json
{"communityId":"…","issuedAt":1760000000,"nonce":"<hex>","platform":"PAPER_PLUGIN","tokenId":"…"}
```

- Signature: **Ed25519**. Laravel signs via `sodium_crypto_sign_detached`; JVM verifies via
  `java.security` (JDK ≥15; project is on 21 — no new dependency).
- Client checks: signature valid against a key from *its own* backend, `nonce` equals the one it
  sent this connection, `issuedAt` within ±10 min. Community claimed in HELLO_SERVER must match
  the attested `communityId`.
- Spoofing/staging mismatch both collapse into "signature does not verify" because the client
  only ever trusts keys fetched from the backend *it* is configured against.

### Backend (schemati)

- Ed25519 keypair in config (`config/schematio.php` + env, raw 32-byte keys base64).
- `GET /.well-known/schematio-keys.json` (public, cached): `{"keys":[{"kid":"…","alg":"Ed25519","key":"<base64>"}]}` — list form enables rotation.
- `POST /api/plugin/attest` under the existing community-JWT plugin route group:
  request `{nonce_hex, platform}` → response `{payload, signature_base64, key_id}`.
  Rate limit 30/min per token; audit via `CommunityTokenAudit`. Pest tests for both endpoints.

### Server side (bukkit + fabric server)

- Shared attestation client in `:core` (HTTP call + response cache keyed by nonce, 5 s timeout).
- Bukkit `PluginIpcService`: on HELLO_CLIENT-with-nonce → request attestation → send ATTEST.
- Fabric server (`fabric/src/main`): today it only *receives*; it gains the full sender role —
  HELLO_SERVER on join, ATTEST on nonce — reusing the same `:core` pieces, with
  `platform = FABRIC_SERVER`.

### Client

- `ServerSession` v2: adds `platform`, `serverSoftware`, `mcVersion`, `backendHost`,
  `communityId/slug`, and `trust: NONE | LEGACY_V1 | UNVERIFIED | VERIFIED`. Reset on disconnect
  (unchanged).
- `BackendKeyCache`: fetch `/.well-known/schematio-keys.json` from the configured backend once
  per session, cache by `kid`, refetch on unknown `kid` (rotation).
- Toolbar **connection indicator**: icon states (none/legacy/unverified/verified) + detail popup
  (platform, versions, community, trust, capabilities).
- `ActionRouter` (small, pure, unit-tested): given `ServerSession` + intent, picks the path —
  e.g. download → server WorldEdit clipboard iff `LOAD_CLIPBOARD` capability is advertised
  (any trust level incl. LEGACY_V1 — v1 servers keep working), else local Litematica; upload
  sources analogous. Panels call the router instead of
  hardcoding paths. VERIFIED-only actions (future VCS) simply require `trust == VERIFIED`.
- Release: **v1.4.0**.

---

## Phase 3 — ImGui feature parity

- **Task 15b** (`CommunityDetailPanel`): member-role editing modal, community tag management
  (add/rename/delete tree), invite-by-name picker — all ImGui modals reusing `PlayerListPicker`
  and the tag-tree widget extracted in Phase 1.
- **Task 19** (`SchematicEditPanel`): co-author management, reusing the upload wizard's
  collaborator chips + head avatars.
- Release: rolls into v1.4.0 if concurrent, else v1.4.1.

---

## Testing

| Layer | Tests |
|---|---|
| `:core` codec | Round-trips for v2 HELLO_SERVER/HELLO_CLIENT/ATTEST; v1↔v2 cross-version matrix |
| Crypto | Sign(PHP fixture)/verify(JVM) golden vectors + throwaway-keypair unit tests both sides |
| Backend | Pest: attest happy path, bad nonce, rate limit, key endpoint shape |
| Client | `ActionRouter` decision table; `ServerSession` trust transitions; existing panel tests stay green |
| Integration | `run-paper` task: full v2 handshake against local backend; MANUAL_TESTING.md updated |

## Risks

- **Move churn (Phase 1):** 107-file branch being reorganized — mitigated by doing moves as
  dedicated commits (git rename detection) before any splits.
- **Backend call on join (Phase 2):** per-join attest request from the server; mitigated by 5 s
  timeout → UNVERIFIED fallback and rate-limit headroom (30/min ≫ typical join rates).
- **Clock skew:** ±10 min window on `issuedAt`; nonce binding carries the real freshness.
