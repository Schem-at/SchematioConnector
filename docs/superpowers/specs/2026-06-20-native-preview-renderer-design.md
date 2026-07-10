# Native Schematic Preview Renderer — Design

**Date:** 2026-06-20
**Status:** Approved (design); pending implementation plan
**Branch:** `feature/imgui-ui-migration` (continues the ImGui UI work)
**Scope:** Replace the placeholder upload preview with a Minecraft-native schematic preview renderer at feature-parity with the web uploader, built as reusable, Litematica-free rendering infrastructure that the future VCS diff viewer will reuse.

## Motivation

The ImGui `UploadWizardPanel`/`SchematicEditPanel` currently submit a **placeholder PNG** (`placeholderPng(name)`). We want a real, in-client preview that:
- Matches the web uploader's look and feel (perspective/isometric selector, FOV, view presets, transparent background, aspect ratio, orbit, capture).
- Renders via **Minecraft's own client renderer** (real baked models, meshing, textures, lighting) — which automatically supports **modded blocks** whenever the mod is installed.
- Is **fully independent of Litematica** (Litematica becomes just one optional byte-source).
- Is a clean, reusable rendering layer for upcoming **VCS** features (notably an interactive schematic **diff viewer**).

The web reference is the Laravel app at `/Users/harrison/Documents/code/schemati/` (renderer: `schematic-renderer` npm + `nucleation` WASM, client-side three.js).

## Key prior findings (from exploration; see `.git/sdd/` reports)

- **Web preview** (`webpreview-reference.md`): client-side three.js; both perspective (default, FOV 20–90°, default 45°) and isometric (default `iso-se`); view presets = 6 faces + 9 top-down diagonals + 4 iso corners; full orbit/zoom/pan; transparent background option (+ HDRI sky + solid `#7ea8ff`); 16:9 (desktop) / 4:3 (mobile); auto zoom-to-fit; auto-capture + manual capture; Sponge `.schem` via `nucleation`.
- **Current MC state** (`current-preview-state.md`): upload ships a placeholder PNG. A complete **MC-native, Litematica-decoupled `render/` package already exists** (`SchematicSnapshot` → `SnapshotBlockRenderView` (fake `BlockAndTintGetter`) → `OffscreenSchematicRenderer` → `ThumbnailCapture`), Stonecutter-gated for 1.21.x **and** the hard 26.1 path (`ModelBlockRenderer.tesselateBlock`). Litematica is only the *data source* via one seam. The UI driver (`ThumbnailComposerScreen`, 614-line vanilla Screen) was deleted in the ImGui cutover and never re-ported. The 26.1 render path is implemented but almost certainly **never runtime-verified**.
- **Nucleation** (`nucleation-jni-study.md`): Rust lib at `/Users/harrison/RustroverProjects/Nucleation/` with a **mature JNI binding** (`nucleation-jvm/` cdylib + Java wrapper `com.github.schemat.nucleation`, `RegisterNatives` in `JNI_OnLoad`). `Schematic.fromBytes(byte[])` auto-detects litematic/.schem/mcstructure/snapshot/MCEdit/anvil; iterates `Block(x,y,z,name,Map<String,String> properties)` with chunked iterators; block states serialize to canonical MC strings (`minecraft:redstone_wire[east=side,power=0]`); has a **built-in `diff(a,b,preset)` API** (future VCS engine). Build: `cargo build --release -p nucleation-jvm` → `.dylib`; cross-platform via `cross`/CI (Windows `.dll` needs CI); no Maven publish yet (local `files(...)` jar); `AutoCloseable` native handles.

## Locked decisions

| Decision | Choice |
|---|---|
| Build approach | Build on the existing `render/` engine; rebuild/​harden where needed for big-schematic performance, full Litematica independence, and VCS extensibility. |
| Data/parse layer | **Bundle Nucleation (JNI)** as the schematic iterator + future diff engine. |
| Rendering | **MC client renders everything** (baked models, meshing, textures, lighting). Nucleation is iteration-only. Modded blocks render natively when the mod is installed. |
| Block entities | **Render the dynamic BlockEntityRenderer layer in v1** (chests/signs/banners/beds/shulkers), not just static models. |
| Rendering model | **Live render-to-texture** (per-frame, orbitable, displayed via `ImGui.image`) + `capture() → PNG`. Not one-shot capture. |
| Native bundling | **macOS-arm64 first** (build `nucleation-jvm` locally, bundle JAR + native via Loom `include`). Linux/Windows natives + CI = tracked follow-up. |
| Unknown blocks | Blocks whose namespace isn't registered in *this* client (missing mod) are skipped + counted ("N blocks hidden"); everything the client can render, renders. |

## Architecture — layered, reusable

```
[1] Data layer (Nucleation JNI)   bytes(any format) → Schematic → iterate Block(name,props)+BE NBT
                                  + future diff(a,b). Native .dylib bundled (Loom include, explicit).
        │  Litematica = ONE optional byte-source, not a renderer dependency
        ▼
[2] Block mapping (pure)          Block → "minecraft:…[props]" → Mojang BlockStateParser → BlockState
                                  (cached by string) → populate SnapshotBlockRenderView; carry BE NBT.
        ▼
[3] Render engine                 SchematicRenderEngine: snapshot + camera → offscreen RenderTarget
                                  (live per-frame). Chunked+cached meshing, interior face culling,
                                  async build, BER pass, size cap. Exposes GL texture + capture()→PNG.
                                  RenderRequest seam supports a future renderDiff(a,b,Diff).
        ▼
[4] Composer UI (ImGui)           Live orbit viewport (ImGui.image), perspective/iso, FOV, view presets,
                                  background, aspect, auto-fit, Capture.
        ▼
[5] Integration                   UploadWizardPanel + SchematicEditPanel: captured PNG replaces placeholder.
```

Each layer has one responsibility and a well-defined interface; layers 1–3 are UI-agnostic and Litematica-free so the VCS diff viewer reuses them unchanged.

### Layer 1 — Nucleation data layer
- Build `nucleation-jvm` for macOS-arm64 (`cargo build --release`), produce its wrapper JAR; bundle JAR + the `.dylib` via Loom `include`, **listing the native explicitly** (non-transitive-`include` rule; missing native = runtime crash — same lesson as imgui-java/JWT). Pin a version in `gradle.properties`; document the local build step. Cross-platform natives + CI fat-JAR are a tracked follow-up.
- Thin Kotlin facade `SchematicData` over `com.github.schemat.nucleation.Schematic`: `fromBytes(ByteArray): SchematicData` (auto-detect), exposing dimensions, a chunked block iterator (`Block(x,y,z,name,props)` + block-entity NBT), and later `diff(other)`. `AutoCloseable`; always used via Kotlin `use { }`.
- Becomes the single block-source. Litematica/WorldEdit/local-file/the API all just provide bytes.

### Layer 2 — Block mapping (pure, unit-testable)
- `Block(name, properties)` → canonical string `minecraft:name[k=v,…]` → Mojang `BlockStateParser.parseForBlock(...)` → `BlockState`. Cache by string (unique states parsed once; big schematics repeat heavily).
- Modded blocks map and render natively when the mod is installed. Namespaces absent from the client registry → skip + count (surfaced as a small "N blocks hidden (missing mods)" note).
- Populate the existing `SnapshotBlockRenderView` (fake `BlockAndTintGetter`) so layer 3 renders through real MC models/textures/lighting/neighbors. Carry block-entity NBT for the BER pass.

### Layer 3 — Render engine (reusable core)
- `SchematicRenderEngine`: owns an offscreen `RenderTarget`; renders snapshot + camera each frame; exposes a **live GL texture** (`ImGui.image`) and `capture(): ByteArray` (read target → `NativeImage` → transparent PNG). Built on the existing `render/` package; the version-split block tessellation (1.21.x `BlockRenderDispatcher` / 26.1 `ModelBlockRenderer.tesselateBlock`) is reused.
- **Performance (big schematics):**
  - Chunked meshing + cache: tessellate per-16³-region vertex buffers once on load; redraw cached buffers under the camera each frame; rebuild only on snapshot change.
  - Interior face culling: free via the real-neighbor `BlockAndTintGetter` + MC `shouldRenderFace`.
  - Async build: tessellate off-thread, upload on the render thread; "building preview…" progress; never block the UI.
  - Size cap: above a threshold, render a centered N×N×N region with a clear "schematic too large — showing region" notice (no freeze).
  - Block entities render in the live pass (dynamic, not cacheable); sane cap on BER count.
- **26.1 risk:** the offscreen block-render path exists but is likely unverified; budget an in-game debug pass (GpuDevice/render-pass), mirroring the ImGui effort. The *display* half is already solved (render into `RenderTarget` → show via the working `ImGui.image` path), so risk is contained to "MC draws blocks into our target on 26.1."
- **Reusability:** `render(request: RenderRequest)` where the request carries snapshot(s), camera, background, output size; a future `renderDiff(a, b, Diff)` (colored added/removed/changed) slots in without touching layers 1–2. Build the seam now, not the diff.

### Layer 4 — Composer UI (full web parity)
- `PreviewComposerPanel` (ImGui), opened from upload/edit. Live orbit viewport = `ImGui.image` of the engine texture. Mouse: drag=orbit, scroll=zoom, shift-drag=pan.
- Aspect toggle 16:9 (default) / 4:3 / 1:1; auto zoom-to-fit on load + reframe button.
- Projection perspective (default) ↔ isometric; FOV slider 20–90° (default 45°, perspective only).
- View presets: 6 faces + 4 iso corners (default `iso-se`) + top-down diagonals (full web parity).
- Background: transparent (capture default), solid `#7ea8ff`, MC sky (nicer-than-web; fall back to transparent+solid and defer sky if it fights the 26.1 pipeline).
- Capture → transparent PNG → upload preview; auto-capture on confirm + manual capture; "N blocks hidden" note when relevant.
- Theme discipline: colors via `ImGuiColors`/`Widgets`/`ImGuiTheme` (the `checkThemeDiscipline` build check applies).

### Layer 5 — Integration
- `UploadWizardPanel` + `SchematicEditPanel`: open the composer, capture → replace `placeholderPng` with the real PNG. Remove the placeholder path once wired (keep a graceful fallback if capture fails).
- All upload sources (Litematica selection, WorldEdit clipboard, local file, API bytes) feed Nucleation as bytes — no Litematica dependency in the render path.

## Implementation phasing (bottom-up; each independently testable)

1. **P1 — Nucleation data layer**: build macOS native + bundle (Loom include, explicit native), Kotlin `SchematicData` facade, `fromBytes`/iterate. Gate: parse a real `.schem`/`.litematic` and iterate block count (unit + a tiny in-client check).
2. **P2 — Block mapping + snapshot population**: string→`BlockState` (cached, with fallback/count), populate `SnapshotBlockRenderView` incl. block-entity NBT. Gate: unit tests for mapping; populate a snapshot from a parsed schematic.
3. **P3 — Render engine**: live render-to-texture, chunked/async/culled meshing, BER pass, size cap; **verify+fix on 26.1 in-game**. Gate: a snapshot renders to a visible, orbitable texture in an ImGui window on 26.1 (+ 1.21.x once its overlay renders).
4. **P4 — Composer UI**: parity controls + capture. Gate: in-game compose + capture a transparent PNG that matches the web's feel.
5. **P5 — Integration**: wire into upload/edit, replace placeholder. Gate: end-to-end upload with a real captured preview.
- **Follow-up (separate):** Linux/Windows Nucleation natives + CI fat-JAR; (later, separate spec) VCS diff viewer reusing layers 1–3 + `Schematic.diff`.

## Testing & risks

- **Testing:** pure layers unit-tested (string→`BlockState` mapping; Nucleation parse/iterate; snapshot population). Rendering validated by **manual in-game gates per version** (immediate-mode/offscreen GL isn't meaningfully unit-testable), reusing the now-working `ImGui.image` display path. 26.1 is the key gate.
- **Risks & mitigations:**
  1. **26.1 offscreen block-render (GpuDevice)** — likely unverified; budget an in-game debug pass; display half already solved.
  2. **Native bundling** — macOS-first; Windows `.dll` needs CI (follow-up); explicit Loom `include` of the native.
  3. **Big-schematic performance** — chunked-cache + async build + interior culling + size cap.
  4. **Block-entity rendering** — heavier + 26.1 BER signature differences; cap BER count; version-split.
  5. **Nucleation handle lifecycle** — `AutoCloseable`; always `use { }` to avoid native leaks.

## Out of scope (v1)

- The VCS diff viewer itself (only the reusable seam is built now).
- Cross-platform/CI native bundling (macOS-first; follow-up).
- HDRI-sky background (transparent + solid + MC-sky considered; HDRI not matched).
- Animated/auto-rotating previews; multiple captured angles per schematic.
