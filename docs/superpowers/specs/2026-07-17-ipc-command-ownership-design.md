# IPC Sub-project D: Command Ownership Handoff — Design

**Date:** 2026-07-17
**Depends on:** Sub-project A (attested sessions). Pure UX; no new trust surface.
**Repos:** SchematioConnector only (core/bukkit/fabric).
**Decisions locked with Harrison (2026-07-17):** when the mod is present on an attested session and advertised `WANTS_COMMAND_OWNERSHIP`, the plugin's `/schematio` command surfaces open the client's ImGui UI instead of chat menus; chat remains the standalone fallback.

## Protocol (core/ipc, VERSION = 2 additions)

- `OPEN_UI = 9` (S→C): `{ surface: byte (0=BROWSE, 1=UPLOAD, 2=SHARES, 3=SCHEMATIC_DETAIL, 4=SETTINGS), contextId: utf8 (≤64, empty unless SCHEMATIC_DETAIL) }`. Max 128 bytes.
- Client honors `OPEN_UI` ONLY from an ATTESTED session **and** only if the user enabled "server may open UI" in the mod's settings (default ON, visible toggle in SettingsPanel) — a hostile-but-attested server may not pop UI against the user's will. Rate-limited client-side: max 1 `OPEN_UI` per 2s, silently dropped beyond.
- `contextId` is opaque: SCHEMATIC_DETAIL resolves it through the client's own user-auth API; unknown/inaccessible → toast error, nothing opens.

## Plugin (bukkit)

- Where `/schematio` subcommands today print chat menus (browse/upload/shares/help), the command handler first checks: player session attested + client advertised `WANTS_COMMAND_OWNERSHIP` + capability negotiated → send matching `OPEN_UI` and a one-line chat ack ("opened in your Schematio client"); otherwise the existing chat flow runs unchanged.
- `/schematio here <id>`-style detail commands map to `SCHEMATIC_DETAIL` with contextId.
- No command is removed or behavior-changed for vanilla-client players.

## Client (fabric)

- Handler maps surface → existing `PanelManager` open calls (`BrowsePanel`, `UploadWizardPanel.open()`, `SharesPanel`, `SchematicDetailPanel` via API fetch, `SettingsPanel`).
- Settings toggle `allowServerOpenUi` persisted with the mod's existing config mechanism.

## Security invariants (testable)

1. `OPEN_UI` from unattested session → dropped (unit test).
2. Toggle off → dropped (unit test).
3. Client-side rate limit enforced (unit test).
4. contextId never used for anything but the client's own API lookup (review invariant).

## Testing

core: opcode round-trip/caps. bukkit: routing decision table (attested×capability matrix → chat vs OPEN_UI) as unit tests. fabric: handler gating tests. Manual run-paper: `/schematio` with mod opens Browse; without mod prints chat menu.
