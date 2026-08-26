# ImGui UI/UX Polish — Design

**Date:** 2026-07-16
**Branch:** `feature/ingame-diff-viewer` (stacked on the in-flight diff viewer work by request)
**Goal:** Kill every "default Dear ImGui" tell in the Fabric client overlay. Sleek, professional, easy to use; echoes schemat.io branding (dark surfaces, fuchsia `#db45f0` accent) without overdoing it. Stays entirely within ImGui's widget/docking model — no custom window chrome, no changes to docking or input behavior.

**Non-goals:** panel layout redesigns (Browse card grid etc. are a follow-up), custom-drawn title bars/drag handling, Minecraft-native styling, UI scale/DPI setting, bukkit/plugin side.

## 1. Palette coverage & geometry

`Theme.kt` stays the single source of truth (ARGB ints); `ImGuiColors.kt` keeps translating to `ImVec4`. Add derived shades to `Theme.kt`:

- `SURFACE_RAISED` — slightly lighter than `SURFACE_ALT`, for active tab / focused title fill
- `ACCENT_MUTED` — accent at ~25% alpha, for text selection and docking preview washes
- `STRIPE` — white at ~3% alpha, for table row striping

`ImGuiTheme.apply()` grows from 19 pushed colors to an explicit mapping for **every** `ImGuiCol` slot (~55). Key decisions:

| Area | Mapping |
|---|---|
| Title bars | `TitleBg`/`TitleBgCollapsed` → SURFACE; `TitleBgActive` → SURFACE_RAISED. Focused windows additionally get a 2px ACCENT underline drawn via the window draw list (brand cue, mirrors the site's active-tab inset shadow). |
| Docking tabs | `Tab` → SURFACE, `TabHovered` → SURFACE_HOVER, `TabActive` → SURFACE_RAISED (+ accent underline), unfocused variants dimmed. `DockingPreview` → ACCENT_MUTED. `DockingEmptyBg` → fully transparent — MUST stay transparent to preserve the pass-through central node (game visible/clickable through the dockspace). |
| Scrollbars | Track transparent; grab BORDER, hover TEXT_FAINT; `ScrollbarSize` 10, `ScrollbarRounding` full. |
| Tables | Header bg SURFACE_ALT; row stripe STRIPE; horizontal borders BORDER_SUBTLE only (no vertical grid). `ImGuiTheme.withStandardTable` updated to match. |
| Selection/checks | `CheckMark`, slider grabs, nav highlight → ACCENT; `TextSelectedBg` → ACCENT_MUTED. |
| Modals | `ModalWindowDimBg` → SCRIM. |
| Everything else | Separators, resize grips, drag-drop target, plot colors, etc. — explicit values from the palette; nothing left at ImGui defaults. |

**Style vars:** WindowRounding 8; Frame/Popup/Tab/Grab rounding 5; WindowPadding 14×14; FramePadding 10×7 (taller inputs/buttons); ItemSpacing 8×8; CellPadding 8×5; ScrollbarSize 10. Borders: window 1px BORDER_SUBTLE; **frame borders off globally** — inputs get their border back locally (flat fills elsewhere; boxed-everything is the "overdoing it" trap).

**Accent usage rule:** fuchsia appears only as interactive emphasis — primary buttons, active-tab/title underline, checkmarks/slider grabs, selection washes. Never as a default surface or border.

Push/pop counting in `apply()`/`unapply()` is preserved exactly (imbalance corrupts all subsequent rendering).

## 2. Typography & icons

**One atlas, four faces**, built once in `ImGuiManager.loadFonts`:

| Face | Size | Use |
|---|---|---|
| Inter Regular (existing) | 18 | body (default font) |
| Inter SemiBold | 18 | buttons, table headers, active tab labels, emphasis |
| Inter SemiBold | 20 | H2 — section headers inside panels |
| Inter SemiBold | 24 | H1 — panel titles / hero rows |

New `theme/Fonts.kt` exposes `Fonts.BODY / SEMIBOLD / H2 / H1` (`ImFont` refs) plus `withFont(font) { ... }`; `Widgets.h1/h2` render helpers. Bundle `Inter-SemiBold.ttf` (OFL) beside the existing Regular.

**Icons:** Font Awesome 6 Free Solid (`fa-solid-900.ttf`), **merge-moded into the two 18px faces only** (H1/H2 excluded), restricted to a curated glyph range of ~25 codepoints to keep the atlas small. New `theme/Icons.kt` holds string constants (`Icons.UPLOAD = "\uf093"` etc.) so usage is plain concatenation: `"${Icons.UPLOAD}  Upload"`.

Placement — restrained, function-first: toolbar tools (search, folder, users, share, upload, bolt, gear; git-branch + diagram for the disabled placeholders); widget-level icons (close, refresh, trash, tag, check, chevrons, external-link, warning/info). Not sprinkled into body copy.

Licensing: ship OFL/CC-BY attribution files beside the font assets.

## 3. Widget kit & chrome

`widgets/` becomes a small design system; panels adopt where 1:1 (no layout changes):

- **Buttons:** `primaryButton` (ACCENT fill, white SemiBold), `secondaryButton` (SURFACE_ALT + BORDER_SUBTLE), `ghostButton` (transparent; accent text on hover), `dangerButton`, `iconButton(icon, tooltip)`.
- **Structure:** `sectionHeader(text)` (H2 + spacing), `badge(text, tone)` (draw-list pill; success/warning/danger/info/neutral), `emptyState(icon, title, hint)`, `kvRow(label, value)`.
- **`Anim` helper** (new `widgets/Anim.kt`): ImGui-id → 0..1 float advanced by `io.deltaTime` (~120ms full fade), used **only inside custom widgets** for hover color fades; stock widgets keep instant hover. Stale entries decay/evict so the map stays small.
- **`ConfirmModal`:** SCRIM dim, icon + H2 title, right-aligned secondary-cancel / danger-or-primary-confirm.

**Toolbar:** left-aligned "Schematio" wordmark (SemiBold + accent dot), icon+label tool buttons, 2px accent **underline** when a tool's panel is open (replaces the full accent background — quieter), slightly taller bar via menu-bar frame padding.

## 4. Files

| File | Change |
|---|---|
| `theme/Theme.kt` | + SURFACE_RAISED, ACCENT_MUTED, STRIPE |
| `theme/ImGuiColors.kt` | + new ImVec4 translations |
| `theme/ImGuiTheme.kt` | full ~55-slot color table, expanded vars, updated `withStandardTable`, title/tab underline helper |
| `theme/Fonts.kt` (new) | font refs + `withFont` |
| `theme/Icons.kt` (new) | FA codepoint constants + glyph range |
| `framework/ImGuiManager.kt` | `loadFonts`: 4 faces + FA merge |
| `framework/Toolbar.kt` | wordmark, icon buttons, underline-open state |
| `widgets/Widgets.kt`, `widgets/Anim.kt` (new), `widgets/ConfirmModal.kt` | kit expansion + restyle |
| panels | swap bare `ImGui.button`/plain text for kit widgets where 1:1 only |
| `fabric/src/main/resources/assets/schematioconnector/fonts/` | + `Inter-SemiBold.ttf`, `fa-solid-900.ttf`, license files |

## 5. Testing & verification

- Extend the `ImGuiColorsTest` pattern: every `ImGuiCol` slot mapped exactly once; `apply`/`unapply` push/pop counts balance; `Icons` constants fall in FA's PUA range; glyph range array well-formed (even length, ascending, 0-terminated as imgui-java expects).
- `Anim` unit tests: fade direction, clamping, eviction.
- Manual visual pass via `:fabric:runClient` (client already running for diff-viewer testing): each panel, floating + docked states, modal, tag selector popup, docking drag preview, scrollbars in long lists.
- Regression watch: dockspace central node must remain transparent + click-through; keyboard input path (KeyboardMixin/charCallback) untouched.

## 6. Risks

- **Font atlas rebuild:** merge-mode + glyph ranges must be built before `buildFontTexture()`; our custom GL3 renderer is the sole owner of the atlas texture — keep the single `io.fonts.build()` call site.
- **Push/pop imbalance** is the highest-blast-radius bug class here; the counting pattern + test coverage guards it.
- **FA codepoint drift:** pin the FA version in a comment next to `Icons.kt`; constants are hand-curated so a font swap requires re-verifying the ~25 glyphs.
