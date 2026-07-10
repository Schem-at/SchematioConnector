# ImGui UI Migration — Design

**Date:** 2026-06-19
**Status:** Approved (design); pending implementation plan
**Scope:** Migrate the SchematioConnector Fabric client UI from Minecraft's vanilla `Screen`/`GuiGraphics` system to Dear ImGui (imgui-java), preserving the existing design language, as the foundation for an upcoming in-game schematic version-control feature.

## Motivation

The current vanilla `Screen`-based UI is hard to extend for the complex, dynamic interfaces the planned version-control feature needs (tables, trees, diff views, resizable panels). ImGui gives:

- The polished, dense look and feel of [ParkourCalculatorMod](https://github.com/Leg0shii/ParkourCalculatorMod) (which uses `io.github.spair:imgui-java-binding`).
- Much faster iteration on complex/dynamic UI than retained-mode `Screen`/`Widget`.
- First-class complex widgets (tables, trees, dockable/resizable windows, diff views).
- Live overlay/HUD rendering over the game world.

Version-control UI will be built native in ImGui *after* this migration lands — no vanilla detour.

## Locked decisions

| Decision | Choice |
|---|---|
| Migration scope | **Full migration** of all client screens (planned end-to-end up front, executed in phases). |
| Input model | **Pure overlay** — hotkey-toggled floating ImGui layer over live gameplay; no MC `Screen` involvement. Cursor grab/release + GLFW input callbacks managed manually. |
| Rich description editing | **Custom WYSIWYG rich-text widget** in ImGui (highest risk; sequenced last; markdown+preview fallback available without rework). |
| Design language | **Preserved** — port `Theme.kt` constants to an ImGui theme. |

## Current-state summary (baseline)

- Every UI screen extends `net.minecraft.client.gui.screens.Screen` and draws via `GuiGraphics`. ~8 screens: `HomeScreen` (tabbed: Browse/My Schematics/Communities/Quick Shares/Settings), `UploadWizardScreen`, `SchematicDetailScreen`, `SchematicEditScreen`, `CommunityDetailScreen`, `QuickShareCreateScreen`, `TagSelectorScreen`, `foundation/ConfirmDialogScreen`.
- Foundation widgets: `FlatButton`, `TabBarWidget`, `ThemedTextField`, `RichDescriptionEditor`, `RichTextEditor`, `LoadingSpinner`, `NoticeBanner`, `PreviewDraw`, `ui/compat/Draw.kt`.
- Tabs: `BrowseTab`, `SettingsTab`, `CommunitiesTab`, `QuickSharesTab` (implement a `TabContent` interface).
- **Design language is already centralized** in `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/Theme.kt`:
  - Accent `#db45f0` (hover `#e978fa`, dim `#7a2e88`); backgrounds `#0a0a0c`, surfaces `#15151b`/`#1c1c24`, hover `#24242e`; borders `#2a2a33`/`#1e1e26`/`#7a2e88`; text `#ffffff`/`#b4b4c0`/`#8a8a96`/`#5e5e68`; status SUCCESS `#34d399`, DANGER `#f87171`, WARNING `#fbBF24`, INFO `#7ea8ff`.
  - Spacing scale `XS=4, SM=6, MD=8, LG=12, XL=16, XXL=24`; button/input height 20, row height 22, chip height 12.
  - Flat dark surfaces, 1px thin borders, generous spacing, no drop shadow.
- Entry points: `keybind/Keybinds.kt` — `browser` (K) opens `HomeScreen`; `upload`/`quickShare`/`settings` unbound by default; checked in `Keybinds.tick()` → `Minecraft.setScreen(...)`.
- `core/` is **pure** (renderer-agnostic dialog DTOs, `DialogService` interface, `CachedSchematioApi`, models, auth/caching) — no rendering.
- Server-side `FabricDialogService`/`FabricDialogRenderer` (native server→client dialogs) is a **separate concern, out of scope**.
- `bukkit/` is server-side, no client UI — unaffected.

## Out of scope

- `core/` data/logic/API layer (consumed unchanged).
- Server-side native dialog system (`DialogService`, `FabricDialogService`, `FabricDialogRenderer`).
- `bukkit/` module.
- The version-control feature itself (this migration is its prerequisite).

## Architecture

Chosen approach: **mixin render hook + panel manager, all in the `fabric/` client source set.**

Rejected alternatives:
- Fabric `HudRenderCallback` for the render hook — runs inside MC's 2D ortho GL state, which fights ImGui's own frame setup; timing awkward.
- Extracting ImGui into a standalone Fabric sub-module (DisplayKit-style) — premature for a single consumer; the panel layer is written so a later extraction is cheap.

### Section 1 — Module & dependency layout

- All ImGui code lives in the **`fabric/` client source set only**. `core/` and `bukkit/` untouched.
- Dependencies: `imgui-java` **binding** + **`imgui-java-lwjgl3` backend** + **native artifacts** for the platforms targeted, matching MC 1.21.11's LWJGL3 (3.3.x). Exact coordinates/version pinned during Phase 1.
- **Native bundling (critical):** Loom `include(...)` is **non-transitive** (see project memory `fabric-include-nontransitive`; root cause of the v1.2.0 Jackson/JWT prod-only crash). Every ImGui jar — binding, lwjgl3 backend, **and each native classifier** — must be listed **explicitly** in `include(...)`. A missing native compiles fine in dev and crashes only in a built jar. Phase 1 explicitly verifies native loading from a **built jar**, not just the dev run.

### Section 2 — Runtime architecture (overlay engine)

```
ImGuiLayer (Mixin into MC frame loop, renders after world/HUD)
  ├─ init once (lazy, on first toggle):
  │     create ImGui context; ImGuiImplGlfw + ImGuiImplGl3;
  │     bind MC's GLFW window handle; load font atlas + theme
  ├─ each frame: if any panel open → newFrame → render open panels → render draw data
  └─ owns ImGuiInputRouter + PanelManager
```

- **`PanelManager`** — registry of open panels and their open/close state, z-order, focus. A keybind toggles panels (e.g. `K` → `BrowserPanel`). Replaces today's `Minecraft.setScreen(...)`.
- **`ImGuiInputRouter`** — installs GLFW char/key/mouse/scroll callbacks, **chaining** MC's existing callbacks. While any panel is open: free the cursor (`GLFW_CURSOR_NORMAL`), feed events to ImGui, and **swallow** events where `io.getWantCaptureMouse()` / `io.getWantCaptureKeyboard()` is true so the player does not move/look/break blocks through the UI. When the last panel closes: restore cursor grab and stop swallowing.
- **Lifecycle:** init lazily on first toggle (GLFW window guaranteed to exist by then); single shutdown hook on client stop to destroy the ImGui context.

This is the highest-risk integration work and is **Phase 1**, validated with a throwaway "hello window" before any real panel.

### Section 3 — Theme & fonts (preserving the design language)

- **`ImGuiTheme`** ports `Theme.kt`: ARGB constants → `ImVec4` colors; spacing scale → `ImGuiStyle` vars (window/frame padding, item spacing); `border size = 1px`, minimal rounding to match the thin-border flat look.
- **Discipline (from ParkourCalculator):** all colors and table styling go through `ImGuiTheme` only — no inline hex literals or numeric `ImVec4` constructors in panel code. A **lightweight Gradle check** enforces this (scans the panel source dir, fails the build on raw color literals outside the theme package).
- **Fonts:** ImGui cannot use MC's font renderer; it needs a TTF atlas. The design language is colors + spacing, not the MC pixel font, so **bundle one clean TTF** (e.g. Inter or Roboto) as a client resource, loaded once at init to build the atlas. This moves the look toward the ParkourCalculator aesthetic.

### Section 4 — Panel layer (porting the screens)

Each current `Screen` becomes a plain **`Panel`** class with a `render()` that issues ImGui calls — no MC base class, no `GuiGraphics`. Data flow is unchanged: panels call the same `core/` API (`CachedSchematioApi`, models). Only the render/input layer swaps.

| Today (vanilla `Screen`) | Becomes (ImGui `Panel`) |
|---|---|
| `HomeScreen` + tabs (`BrowseTab`, `SettingsTab`, `CommunitiesTab`, `QuickSharesTab`) | `BrowserPanel` with `ImGui.beginTabBar` + tab render methods |
| `UploadWizardScreen` | `UploadWizardPanel` (stepper) |
| `SchematicDetailScreen` | `SchematicDetailPanel` |
| `SchematicEditScreen` | `SchematicEditPanel` |
| `CommunityDetailScreen` | `CommunityDetailPanel` |
| `QuickShareCreateScreen` | `QuickShareCreatePanel` |
| `TagSelectorScreen` | inline ImGui popup |
| `foundation/ConfirmDialogScreen` | `ImGui.openPopup` modal |
| `FlatButton` / `TabBarWidget` / `ThemedTextField` | thin `Widgets` helpers over native ImGui |
| `LoadingSpinner` / `NoticeBanner` / `PreviewDraw` | ImGui custom-draw helpers |
| `RichDescriptionEditor` / `RichTextEditor` | **`RichTextWidget`** — custom, sequenced last |

- **`PreviewDraw`** (schematic thumbnail) needs a per-case adapter: it currently draws via `GuiGraphics`. In overlay mode it either renders to an offscreen texture that ImGui samples (`ImGui.image`), or is reimplemented with ImGui draw-list primitives. Flagged as a per-case implementation item in Phase 3/4.

### Section 5 — Migration phasing

1. **Phase 1 — Integration spike (risk).** Bundle deps + natives; `ImGuiLayer` mixin; "hello window" toggled by keybind; cursor grab/release; input swallowing. **Gate:** ImGui window renders over live gameplay, game does not eat clicks, **and it runs from a built jar** (natives load).
2. **Phase 2 — Theme + widget foundation.** `ImGuiTheme` from `Theme.kt`; font atlas; `Widgets` helpers (button/tabbar/textfield); `PanelManager`; the Gradle theme-discipline check. **Gate:** a styled demo panel matches the palette.
3. **Phase 3 — Port read-only panels.** `BrowserPanel` + tabs, `SchematicDetailPanel`, `CommunityDetailPanel`, wired to the existing `core/` API; resolve `PreviewDraw`. **Gate:** browse/inspect works end to end.
4. **Phase 4 — Port write flows.** `UploadWizardPanel`, `SchematicEditPanel`, `QuickShareCreatePanel`, tag selector, confirm modal. **Gate:** full CRUD parity with the old screens.
5. **Phase 5 — `RichTextWidget`** (isolated, highest risk; markdown+preview fallback available without rework).
6. **Phase 6 — Cutover & cleanup.** Repoint keybinds (`Keybinds.tick()`) to `PanelManager`; delete vanilla `Screen` classes + `ui/compat/Draw.kt`; update Litematica/WorldEdit integration entry points.

### Section 6 — Testing & risks

- **Testing:** `core/` logic remains unit-testable and unchanged. Immediate-mode ImGui rendering is not meaningfully unit-testable; each phase has an explicit **manual in-game verification gate** (above). Use the `verify`/`run` skills to launch the client and confirm behavior per phase.
- **Top risks & mitigations:**
  1. Natives not bundled → prod-only crash — Phase 1 built-jar test + explicit `include` of every native classifier.
  2. Input bleed-through (player acts behind the UI) — swallow events on `wantCaptureMouse/Keyboard`.
  3. `RichTextWidget` cost overrun — isolated last, markdown+preview fallback.
  4. `PreviewDraw` render path — flagged per-case (texture sample vs ImGui primitives).

## Open items resolved during implementation

- Exact `imgui-java` version/coordinates and native classifiers (Phase 1).
- Chosen bundled TTF (Phase 2).
- `PreviewDraw` strategy: offscreen texture vs ImGui draw-list (Phase 3/4).
