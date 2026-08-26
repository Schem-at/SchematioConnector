# Client UI Declutter — Design

**Date:** 2026-07-17
**Repo:** SchematioConnector (fabric client only), branch `feature/ingame-diff-viewer`.
**Goal:** Reduce the ImGui overlay toolbar from 7 flat tool buttons (+2 disabled) to a focused set — two top-level tools plus grouped dropdown menus — merge the three schematic-listing panels into one scoped Browse panel, remove the duplicate Quick Share entry, and ship a sensible default dock layout with a reset.

**Decisions locked with Harrison (2026-07-17):** full-merge Browse model; `[Browse] [Upload]` top-level + `Share ▾` / `Tools ▾` / gear; Quick Share create de-duplicated; Browse-docked-right default layout.

## Current state (from exploration)

- `SchematicListView` (`ui/panels/SchematicListView.kt`) is already the SHARED grid renderer: `SchematicListState`, a `Context` sealed class (`Public`, `Mine`), `renderSchematicGrid(showAuthor, scrollId, loadNext)`, `renderSearchField/renderSortCycler/renderOrderToggle/renderStatusBanners/tickSearchDebounce`.
- `BrowsePanel` already owns a **context cycler** (`buildContextList()`, Public/Mine + tag filters). `MySchematicsPanel` is essentially BrowsePanel pinned to `Mine`, `showAuthor=false`, no cycler. `CommunitiesPanel` lists communities → opens `CommunityDetailPanel`.
- `Toolbar.kt` renders flat `toolButton`s in the DockHost menu bar. `DockHost` builds **NO default split** (deliberately — an empty `PassthruCentralNode` dockspace stays transparent; a naive split paints opaque `DockingEmptyBg` over the game). Its own doc comment flags "a correct auto-right-dock default" as a known follow-up.
- The IPC "Upload clipboard to server" toolbar entry (sub-project C11) and "Load on server" (in `SchematicDetailPanel`) exist; the former moves into the Share menu.

## 1. Unified Browse panel + scope switcher

`BrowsePanel` becomes the single listing surface. A **scope selector** renders above the existing search/filter row: `All · Mine · Communities ▾` (segmented buttons; Communities is a dropdown that also carries the picked community name).

- **All** and **Mine** reuse the existing `SchematicListView.Context.Public`/`Mine` paths — this is mostly promoting BrowsePanel's existing context cycler into a visible scope selector and folding `MySchematicsPanel`'s `mineOnly` behavior in (`showAuthor` = false when scope is Mine). The tag filter + search + sort controls stay, shared across scopes.
- **Communities** scope: selecting it shows a community **picker** (reuse `CommunitiesPanel`'s community-list load + row rendering, extracted to a small helper). Picking a community sets a community constraint on the query (via the existing `FilterConstraint`/api path) and shows that community's schematics in the same grid. A **"Manage community"** button in this scope opens the unchanged `CommunityDetailPanel` (members, invites, leave) — so nothing from Communities is lost; it's just not a separate top-level destination.
- Switching scope calls the existing `resetAndLoad()` with the new scope's query. Each scope keeps its own search text? No — one `SchematicListState`, cleared on scope change (simpler; a scope switch is a fresh browse). Debounce/paging unchanged.

`MySchematicsPanel` and `CommunitiesPanel` as standalone `Panel` objects are **retired** (deleted); their reusable bits (mine query, community list/row rendering) move into `BrowsePanel` or a shared helper. `CommunityDetailPanel`, `SchematicDetailPanel`, `SchematicEditPanel`, `PreviewComposerPanel`, `UploadWizardPanel`, `SharesPanel`, `QuickShareCreatePanel`, `SettingsPanel` all stay.

## 2. Toolbar restructure (`Toolbar.kt`)

Top-level, left→right: wordmark · `🔍 Browse` · `⬆ Upload` · (spacer) · `Share ▾` · `Tools ▾` · `⚙`.

- **Browse** / **Upload**: toggle their panels as today (`toolButton` kept for these two).
- **Share ▾** (`ImGui.beginMenu`): `My Quick Shares` (→ `SharesPanel`), `New Quick Share` (→ `QuickShareCreatePanel.show(null)`), and — only when the current server session is `VERIFIED` and advertised the UPLOAD capability — `Upload clipboard to server` (the C11 action, moved here from a standalone toolbar button).
- **Tools ▾**: `Version Control` and `Flow` as **disabled** menu items with a "coming soon" tooltip (kept, but out of the main row), plus `Reset layout` (see §4).
- **⚙**: opens `SettingsPanel` (icon-only button, far right).

The redundant top-level `Quick Share` (create) button and the `My Schematics` / `Communities` buttons are removed. Menu open-state highlight: a menu shows its accent underline when any of its panels is open (reuse the existing `windowTitleAccent`/open-underline idea, adapted to menus — or simply the native menu styling from `ImGuiTheme`).

## 3. Default dock layout + reset (`DockHost.kt`)

On a frame where the dockspace has **no persisted layout** (fresh imgui.ini — detected via `ImGui.dockBuilderGetNode(dockId)` returning null/0, or a one-time "layout built" flag persisted separately), build a default with the DockBuilder API:

- `dockBuilderRemoveNode(dockId)` → `dockBuilderAddNode(dockId, DockSpace flag)` → set node size to the viewport → `dockBuilderSplitNode(dockId, Right, 0.38f, &rightId, &centralId)` → `dockBuilderDockWindow("Browse###browse", rightId)` → `dockBuilderFinish(dockId)`.
- The **central node stays `PassthruCentralNode`** (transparent, game visible/clickable) — only the right node hosts panels. This is the exact constraint the current code avoided; the DockBuilder path must preserve it (dock only into the right split, never the central node; keep `NoDockingInCentralNode`).
- Other panels, when opened, tab into the right node (they dock next to Browse rather than floating) — achieved by docking them to the same right node id at build time OR letting ImGui tab them into the focused dock node.
- **Reset layout** (Tools ▾ item): clears the persisted arrangement (`dockBuilderRemoveNode` + rebuild the default, and/or delete the dockspace entry) so the user can recover from a messy manual arrangement. Because imgui.ini persists once touched, this is the only way back to the default after first use.

**Risk:** DockBuilder is imgui internal-docking API; imgui-java exposes it as `imgui.internal.ImGui` static calls. If a needed call is missing in the pinned imgui-java 1.89.0, fall back to a lighter approach that needs no full DockBuilder: when no saved layout exists, dock each panel to the right on its first open via `setNextWindowDockId`/`setNextWindowDockID` (the panel snaps to a right-side dock instead of floating), which still gives a non-manual default without programmatically splitting the node. The plan must verify the actual DockBuilder surface against the jar first and pick the full-layout path or this fallback accordingly.

## 4. Removed / deprecated

Deleted: `MySchematicsPanel.kt`, `CommunitiesPanel.kt` (content folded into Browse). Removed toolbar entries: My Schematics, Communities, the duplicate Quick Share, the standalone Upload-clipboard button. All keybind/PanelManager references to the deleted panels updated (grep `MySchematicsPanel`/`CommunitiesPanel` across fabric — Keybinds, PanelManager wiring, ServerIpc OPEN_UI mapping from sub-project D which maps `SHARES`/etc. to panels — the D `OpenUiSurface` mapping must be reconciled: `BROWSE`→BrowsePanel still valid; if any surface mapped to MySchematics/Communities, repoint to Browse with the right scope).

## 5. Testing

- **Unit (headless):** the scope→query mapping (All/Mine/Community constraint building) as a pure function; the Share-menu item visibility gate (VERIFIED + UPLOAD capability) as a pure predicate reused from the existing capability check; `buildContextList`/scope-reset logic.
- **Compile + visual QA:** merged Browse rendering, the three scopes, Communities picker + Manage handoff, the toolbar menus, and the default dock layout / reset (needs a live ImGui context + run-paper). The default-layout central-node-transparency and game-clickthrough are on the manual checklist.
- Reuse existing panel test patterns; update/remove tests that reference the deleted panels.

## 6. Non-goals

No redesign of the individual panels' internals (upload wizard, community detail, settings) beyond what the merge/menu wiring requires. No new features — this is purely reorganization. No change to the IPC flows themselves (only where their toolbar entry point lives).
