# IPC Sub-project C: Server Clipboard → Draft Upload + In-Game Labelling — Design

**Date:** 2026-07-17
**Depends on:** Sub-project A (attested sessions), Sub-project B (`STATUS` opcode, backend plugin-endpoint patterns).
**Repos:** SchematioConnector (core/bukkit/fabric) + schemati (draft endpoints + expiry job).
**Decisions locked with Harrison (2026-07-17):** server pushes clipboard bytes to the backend only (never to the client); drafts are owned by the player's schemati account; the client's own wizard finishes labelling in-game with the USER's auth (user tokens never transit the server); plugin fully standalone (chat + web-link path without the mod).

## Goal

Player (or their client UI) triggers "upload my server-side WorldEdit clipboard" → plugin serializes it and creates a **draft schematic** on schemati attributed to the player → with the mod: the client's upload wizard opens pre-filled against the draft and the user completes name/tags/community **directly with the backend**; without the mod: chat link to the web upload page for that draft (existing web tooling).

## Threat model highlights

- A malicious **server** can at worst create size-capped, quota-capped, auto-expiring drafts attributed to players who ran the upload command on it — drafts are invisible to others and inert until the USER publishes them from their own authenticated session (client wizard or web). The server never receives or forwards user credentials, and cannot publish.
- A malicious **client** can only trigger uploads of the player's own current server clipboard — content the server already has; no new exfiltration surface. Rate/quota limits bound abuse.

## Protocol (core/ipc, VERSION = 2 additions)

- `UPLOAD_CLIPBOARD = 7` (C→S): `{ requestId: varint }` — no other fields; the subject is always "the requesting player's current WE clipboard". Max 64 bytes. Requires ATTESTED session + `UPLOAD` capability bit (finally un-reserving `1 shl 1`, advertised only when WorldEdit + backend configured).
- `DRAFT_CREATED = 8` (S→C): `{ requestId: varint, draftId: utf8 (≤64) }`. Max 256 bytes. Client treats draftId as an opaque ID for its own backend API — nothing else from the server is trusted.
- Failures reuse `STATUS` (states from sub-project B; `TOO_LARGE` = clipboard over cap, `DENIED` = permission/attestation, `UNAVAILABLE` = no WE clipboard / backend down).
- Standalone path: `/schematio upload` chat command drives the same plugin service; result is a clickable web link (`<backend>/upload/drafts/{id}` — exact web route per schemati's existing upload page conventions) instead of `DRAFT_CREATED`.

## Backend (schemati)

Under the `ensure_valid_jwt` plugin group:

- `POST /api/plugin/clipboard/drafts` — multipart: `player_uuid`, `file` (schem bytes). Creates a **draft**: reuse the `Schematic` model with a `draft` state (new `is_draft` boolean or status enum value — follow whatever state field the model already has; if none, add nullable `draft_expires_at`, non-null = draft). Owner = User where uuid = player_uuid (400 if unlinked, mapped to `DENIED` + chat hint to link account). Caps: **8 MiB** file, **10 drafts max per user** (409 over quota), rate limit **4/min per (JWT, player_uuid)**. Draft gets `draft_expires_at = now + 48h`.
- Drafts are excluded from every public listing/search/feed (Grid, API listings) — enforced centrally (global scope or the existing visibility query path), with a Pest test proving a draft never appears in the public browse endpoint.
- Publishing = the existing user-authenticated update flow: when the owner submits required metadata (the wizard's normal save), `draft_expires_at` is cleared. No new publish endpoint if the existing schematic-update path can clear it; otherwise one `POST /api/schematics/{id}/publish` user-auth endpoint.
- Expiry: scheduled job (daily) deletes drafts past `draft_expires_at` including stored files. Pest test with time travel.
- Audit line per draft creation (community, player uuid, size, draft id).

## Plugin (bukkit)

`handleUploadClipboard(player, msg)` + `/schematio upload` command → shared service:

1. Guards → `STATUS`: WE present + player has a non-empty clipboard (`UNAVAILABLE`), permission `schematio.clipboard.upload` default true (`DENIED`), attested session for the IPC path (`DENIED`), token bucket **2/min** per player (`RATE_LIMITED`).
2. Serialize clipboard to Sponge `.schem` off-main-thread (WE clipboard snapshot taken on main thread first — WE session API is main-thread; the byte serialization of the snapshot happens async). Cap **8 MiB** post-serialization (`TOO_LARGE`).
3. POST to `/api/plugin/clipboard/drafts`; on 201 → IPC `DRAFT_CREATED` (mod path) or chat clickable link (standalone path). Map 4xx/5xx to terminal `STATUS`.

## Client (fabric)

- On `DRAFT_CREATED`: fetch the draft via the client's own user-authenticated backend API (existing API client) — verifying it exists AND is owned by the current user (if not: show error, do nothing — protects against a malicious server handing someone else's ID). Then open `UploadWizardPanel` in "complete draft" mode: file step skipped (bytes already uploaded), metadata steps as normal, save = normal user-auth update, which publishes (clears expiry).
- Entry point: "Upload clipboard" toolbar action / schematic-area button, visible only on ATTESTED sessions advertising `UPLOAD`.
- 30s timeout on the pending request → synthetic `ERROR`.

## Security invariants (testable)

1. User auth never transits the server: the plugin's HTTP layer has no code path that receives or forwards a user token (grep-level + review invariant).
2. Draft creation is bounded: size 413, quota 409, rate 429 — Pest tests.
3. Drafts invisible until published: public listing/search Pest test; direct GET by non-owner 403/404.
4. Client refuses drafts not owned by the current user (fabric unit test with mocked API response).
5. Expired drafts are really deleted (files included) — Pest time-travel test.

## Testing

core: opcode round-trip/caps. bukkit: guard order, serialization cap, main-thread snapshot discipline (unit with fakes). schemati: full Pest matrix above in `tests/Feature/Api/`. fabric: draft-ownership check, timeout. Manual run-paper: mod path end-to-end; standalone `/schematio upload` link path; quota exhaustion message.
