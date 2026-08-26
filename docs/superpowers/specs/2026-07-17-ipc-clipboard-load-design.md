# IPC Sub-project B: Reference-Pull Clipboard Load + Status Channel — Design

**Date:** 2026-07-17
**Depends on:** Sub-project A (verified handshake v2, `2026-07-10-connector-cleanup-handshake-v2-design.md`) — all new opcodes here require an ATTESTED session.
**Repos:** SchematioConnector (core/bukkit/fabric) + schemati (backend endpoint).
**Decisions locked with Harrison (2026-07-17):** reference-pull data path (client never sends schematic bytes to the server); flows gated on ATTESTED trust; plugin fully standalone without the mod.

## Goal

Client picks a schematic in the ImGui UI → sends a **reference** over `schematio:c` → the plugin **pulls the bytes itself from schemati** (community JWT + requesting player's UUID) → loads them into that player's server-side WorldEdit clipboard → structured status messages drive real UI feedback. The v1 raw-bytes `LOAD_CLIPBOARD` path is **removed** (it was a POC; protocol v2 clients never use it, and the server stops accepting it — feature bit no longer advertised).

## Protocol (core/ipc, protocol VERSION = 2 additions)

New opcodes (all payloads little-JSON-free: use the existing binary writer conventions in `core/ipc`):

- `LOAD_REQUEST = 5` (C→S): `{ requestId: varint, refType: byte (0=SCHEMATIC, 1=SHARE_TOKEN), refId: utf8 (≤64 chars), versionId: utf8 (≤64, empty = default branch head) }`. Max encoded size **256 bytes**.
- `STATUS = 6` (S→C): `{ requestId: varint, state: byte, detail: utf8 (≤256, optional human text) }`. States: `0 RESOLVING, 1 DOWNLOADING, 2 OK, 3 DENIED, 4 NOT_FOUND, 5 TOO_LARGE, 6 RATE_LIMITED, 7 UNAVAILABLE (WorldEdit/backend missing), 8 ERROR`. Max encoded size **512 bytes**. `STATUS` is generic: sub-project C reuses it with its own requestIds.

Rules (cross-cutting, enforced in `core/ipc` decode): unknown opcode → ignore + one rate-limited log line; payload over its opcode cap → drop connection-quietly (no parse attempt); all strings length-prefixed and validated UTF-8; `LOAD_REQUEST` accepted ONLY on sessions whose handshake reached trust `ATTESTED` **and** whose HELLO advertised `LOAD_CLIPBOARD` capability (bit stays, semantics change to reference-pull).

## Backend (schemati)

`POST /api/plugin/clipboard/resolve` under the existing `ensure_valid_jwt` plugin route group:

- Request: `{ player_uuid, ref_type: "schematic"|"share", ref_id, version_id? }`.
- Resolution: user = User where uuid = player_uuid (404 → DENIED if unlinked); authorization reuses the exact same policy path as the web download for that user (public/community visibility, share-token validity incl. expiry/revocation); version resolution = existing version/branch logic (default branch head when unspecified).
- Response: `200` with raw schematic bytes (`Content-Type: application/octet-stream`, `X-Schematio-Format: schem|litematic|…`, `Content-Length` mandatory) or `403/404/413/429` JSON. Hard cap: **8 MiB** response body; backend refuses larger with 413.
- Rate limit: **10/min per (community JWT, player_uuid)** via the standard Laravel rate limiter; 429 maps to `RATE_LIMITED`.
- Audit: one log/DB entry per resolve (community, player uuid, schematic, outcome) — reuse the existing plugin API logging pattern if present, else `Log::info` structured line.

## Plugin (bukkit)

`PluginIpcService.handleLoadRequest(player, msg)`:

1. Guards in order → early `STATUS`: WorldEdit present (`UNAVAILABLE`), session attested (`DENIED`), bukkit permission `schematio.clipboard.load` default true (`DENIED`), per-player token bucket **5/min** (`RATE_LIMITED`).
2. `STATUS RESOLVING` → async (off main thread) HTTPS call to `/api/plugin/clipboard/resolve` with the plugin's existing backend client + community JWT. Enforce client-side caps regardless of backend: response `Content-Length` required and ≤ **8 MiB**; read with a hard byte-counting stream (defense against lying length); 30s timeout.
3. `STATUS DOWNLOADING` while streaming; then hop back to the main thread, parse via existing `WorldEditUtil` (format from `X-Schematio-Format`), `setClipboard`, `STATUS OK` + the existing chat confirmation (chat kept so the flow is observable without looking at the UI).
4. Every failure path maps to exactly one terminal `STATUS`; no schematic bytes are ever echoed back over the channel.

Legacy: `handleLoadClipboard` (raw bytes, opcode 3) is deleted; opcode 3 becomes reserved-dead (decode → ignore+log). Standalone servers lose nothing: that path had no shipped client sender.

## Client (fabric)

- `PluginIpcClient` (existing ipc package): `requestClipboardLoad(ref): requestId`, pending-request map requestId → callback, 30s client-side timeout → synthetic `ERROR` status.
- UI: schematic detail panel + browse context row gain "Load on server" (icon `Icons.DOWNLOAD`) shown ONLY when: connected to a server whose session is ATTESTED + advertised capability. Status updates render as a toast-style line in the panel (reuse `Widgets.statusText`) — states map 1:1 to `StatusKind`.
- The client never opens URLs, never writes files, and renders `detail` as plain text (no formatting codes honored from the server: strip `§`).

## Security invariants (testable)

1. No schematic bytes on the MC channel in either direction (grep-level invariant: no opcode carries a byte-array field).
2. `LOAD_REQUEST` from an unattested session → `DENIED`, no backend call (bukkit unit test with fake session state).
3. Backend refuses resolve for a player without access — policy parity with web download (Pest feature tests: public OK, private non-member DENIED, revoked share DENIED, oversized 413).
4. Rate limits fire on both sides (Pest 429 test; bukkit token-bucket unit test).
5. Client strips `§` from server-supplied detail text (unit test).

## Testing

- core: opcode encode/decode round-trip + size-cap rejection tests (plain JUnit, no MC).
- bukkit: guard-order unit tests with mocked WE/session (MockBukkit if already used, else constructor-injected fakes).
- schemati: Pest feature tests for resolve endpoint (auth matrix above) in `tests/Feature/Api/`.
- fabric: pending-map + timeout unit tests; `§`-strip test.
- Manual: run-paper checklist — load public schematic OK; private schematic of another user DENIED; spam clicking → RATE_LIMITED; WorldEdit removed → UNAVAILABLE.
