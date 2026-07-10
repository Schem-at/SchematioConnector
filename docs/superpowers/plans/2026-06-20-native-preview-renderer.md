# Native Schematic Preview Renderer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder upload preview with a Minecraft-native, Litematica-free schematic preview renderer at web-uploader parity, built as a reusable render engine the future VCS diff viewer will reuse.

**Architecture:** Five layers — (1) Nucleation JNI parses schematic bytes + iterates blocks; (2) a pure mapping turns Nucleation blockstate strings into MC `BlockState`s feeding a fake `BlockAndTintGetter`; (3) `SchematicRenderEngine` renders that snapshot via MC's own client renderer into an offscreen `RenderTarget` (live texture + PNG capture), chunked/async/culled for big schematics, with a block-entity pass; (4) an ImGui composer panel (perspective/iso, FOV, presets, background, aspect, orbit, capture); (5) wiring into the upload/edit panels. Layers 1–3 are UI-agnostic and Litematica-free.

**Tech Stack:** Kotlin 2.0.21, Java 21, Fabric (MC 1.21.8/.9/.10/.11 + 26.1, Mojang mappings, Stonecutter), imgui-java + our custom GL3 renderer (existing), Nucleation (`com.github.schemat.nucleation`, Rust→JNI cdylib), MC `BlockStateParser`/`BlockRenderDispatcher`/`ModelBlockRenderer`/`BlockEntityRenderer`, the existing `fabric/.../client/render/` package.

## Global Constraints

- **JDK 21 for all Gradle** (`JAVA_HOME=$(/usr/libexec/java_home -v 21)`). Stonecutter active version is `1.21.11`; per-version tasks are `:fabric:<ver>:…` (e.g. `:fabric:26.1:compileClientKotlin`, `:fabric:1.21.11:test`).
- **Mojang mappings**; multi-version via Stonecutter `//? if` comments. Code is authored in 1.21.11 form. 26.1 uses Java 25 toolchain (auto-provisioned).
- **Build/shell is sandbox-redirected**: run gradle/grep/cargo via `ctx_execute(language:"shell")`, NOT plain Bash (intercepted). Plain Bash only for `git`.
- **Nucleation = iteration/parse/diff ONLY.** ALL rendering (models, meshing, textures, lighting) is done by the MC client. Modded blocks render natively when the mod is installed.
- **Loom `include` is non-transitive**: the Nucleation JAR AND its native (`.dylib`) must each be listed explicitly in `include(...)`; a missing native compiles but crashes at runtime. (Same lesson as imgui-java/JWT.)
- **macOS-arm64 native only for v1**; Linux/Windows + CI is a tracked follow-up. Document the local `cargo build` step.
- **Theme discipline ENFORCED** for ImGui panel code: colors only via `ImGuiColors`/`Widgets`/`ImGuiTheme` (the `checkThemeDiscipline` Gradle task fails on raw hex / numeric `ImVec4`).
- **Nucleation handles are `AutoCloseable`** → always wrap in Kotlin `use { }`.
- **Reference reports (read as authoritative for existing code + APIs):** `.git/sdd/nucleation-jni-study.md` (Nucleation JAR API), `.git/sdd/current-preview-state.md` (existing `render/` package + capture infra), `.git/sdd/webpreview-reference.md` (parity target). The existing `fabric/src/client/.../render/` package is the behavioral reference for layer 3.
- **Commit after every task.** Branch: `feature/imgui-ui-migration`.
- **Rendering is not unit-testable**: pure layers (1 data facade, 2 mapping) use real TDD; engine/UI tasks (3,4,5) use explicit manual in-game gates on 26.1 (the dev/primary version). 1.21.x rendering is a separate tracked task (overlay doesn't render there yet).

---

## PHASE 1 — Nucleation data layer

### Task 1: Build + bundle the Nucleation macOS native and JAR

**Files:**
- Modify: `gradle.properties` (add `nucleation_version`)
- Modify: `fabric/build.gradle.kts` (dependency + `include`)
- Create: `fabric/libs/` (local jar drop) OR a documented build output path
- Create: `docs/nucleation-build.md` (the local build step)

**Interfaces:**
- Produces: `com.github.schemat.nucleation.*` classes on the client classpath, with the macOS-arm64 native bundled into the remapped jar and loadable at runtime (`NativeLoader` extract-and-`System.load`).

- [ ] **Step 1: Build the JNI native + wrapper jar.** From `/Users/harrison/RustroverProjects/Nucleation/` (via `ctx_execute` shell): `cargo build --release -p nucleation-jvm` (produces the macOS-arm64 `.dylib`), then `./gradlew jar` in `nucleation-jvm/` to produce the fat wrapper JAR containing `native/macos-arm64/...`. Confirm the JAR path; copy it to `fabric/libs/nucleation-jvm-<ver>.jar`. (See `.git/sdd/nucleation-jni-study.md` for exact build/jar commands.)

- [ ] **Step 2: Add the dependency.** In `fabric/build.gradle.kts`, add `nucleation_version` to `gradle.properties` and the dep — local jar via `files(...)` and bundle it (jar already embeds the macOS native):
```kotlin
include(implementation(files("libs/nucleation-jvm-${project.property("nucleation_version")}.jar"))!!)
```
(If the wrapper jar embeds the native under `native/macos-arm64/` and `NativeLoader` extracts it, bundling the single fat jar suffices. Verify the native is inside the jar.)

- [ ] **Step 3: Verify resolution + native presence.** Run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:compileClientKotlin` → SUCCESS. Then `./gradlew :fabric:1.21.11:remapJar` and `unzip -l fabric/versions/1.21.11/build/libs/*.jar | grep -iE 'nucleation|\.dylib'` → the nucleation classes + the `.dylib` are present.

- [ ] **Step 4: Document.** Write `docs/nucleation-build.md` with the exact local build command + the note that Linux/Windows natives need `cross`/CI (follow-up).

- [ ] **Step 5: Commit.** `git add gradle.properties fabric/build.gradle.kts fabric/libs/ docs/nucleation-build.md && git commit -m "build(fabric): bundle Nucleation JNI (macOS-arm64) for schematic parsing"`

### Task 2: `SchematicData` Kotlin facade (TDD)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/render/data/SchematicData.kt`
- Test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/render/data/SchematicDataTest.kt`

**Interfaces:**
- Produces:
  - `class SchematicData(private val handle: Schematic) : AutoCloseable`
  - `companion object { fun fromBytes(bytes: ByteArray): SchematicData }` (auto-detect format)
  - `val sizeX/sizeY/sizeZ: Int`
  - `fun forEachBlock(action: (x: Int, y: Int, z: Int, stateString: String) -> Unit)` (uses Nucleation's iterator; `stateString` = canonical `minecraft:name[props]`)
  - `fun forEachBlockEntity(action: (x: Int, y: Int, z: Int, nbt: ...) -> Unit)` (block-entity NBT; type per the Nucleation API)
  - `override fun close()` (releases the native handle)
- Consumes: `com.github.schemat.nucleation.Schematic` (`fromBytes`, `Iterable<Block>`/`stream()`, dimensions, block-entity access — see `.git/sdd/nucleation-jni-study.md` for exact method names; adapt to the real API).

- [ ] **Step 1: Write the failing test.** Put a tiny real `.schem` fixture at `fabric/src/test/resources/schematic/single_stone.schem` (a 1×1×1 stone, or a small known build — generate via Nucleation in a scratch step or commit a known fixture). Test:
```kotlin
class SchematicDataTest {
    @Test fun parsesDimensionsAndBlocks() {
        val bytes = javaClass.getResourceAsStream("/schematic/single_stone.schem")!!.readBytes()
        SchematicData.fromBytes(bytes).use { d ->
            assertEquals(1, d.sizeX); assertEquals(1, d.sizeY); assertEquals(1, d.sizeZ)
            val states = mutableListOf<String>()
            d.forEachBlock { _, _, _, s -> states += s }
            assertEquals(1, states.size)
            assertTrue(states[0].startsWith("minecraft:stone"))
        }
    }
}
```
> Note: this test loads the native lib — it runs in the fabric test source set (JUnit5, set up earlier). If the native can't load in the bare test JVM (no extract path), mark this test `@EnabledOnOs(MAC)` and/or ensure `NativeLoader` runs; if it cannot load in unit tests at all, convert this to a in-client manual gate and unit-test only the pure string handling. Decide based on whether the native loads in the test JVM.

- [ ] **Step 2: Run — fails** (`:fabric:1.21.11:test --tests '*SchematicDataTest*'`): unresolved `SchematicData`.

- [ ] **Step 3: Implement `SchematicData`** wrapping `Schematic`, mapping Nucleation's `Block(x,y,z,name,properties)` to the canonical string (`name` + `[k=v,…]` sorted as Nucleation emits — prefer Nucleation's own `Display`/to-string if exposed, per the study report), exposing dimensions + iterators + `close()`.

- [ ] **Step 4: Run — passes.**

- [ ] **Step 5: Commit.** `git commit -m "feat(fabric): SchematicData facade over Nucleation (parse + iterate) (TDD)"`

---

## PHASE 2 — Block mapping + snapshot population

### Task 3: `BlockStateMapper` — Nucleation string → MC BlockState (TDD)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/render/data/BlockStateMapper.kt`
- Test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/render/data/BlockStateMapperTest.kt`

**Interfaces:**
- Produces:
  - `object BlockStateMapper`
  - `fun parse(stateString: String): BlockState?` — `null` when the namespace/block isn't registered in this client (missing mod). Caches by string.
  - `val unresolvedCount: Int` (count of distinct unresolved states this session) — for the "N blocks hidden" note.
- Consumes: Mojang `net.minecraft.commands.arguments.blocks.BlockStateParser` (or the version's blockstate parse API) + `BuiltInRegistries.BLOCK`/`HolderLookup`.

- [ ] **Step 1: Failing test** (requires MC registries → this is a game-test or needs bootstrap; if registries aren't available in plain unit tests, gate it as a client manual check and unit-test only the parse-string-splitting helper). Preferred: a Fabric gametest or a bootstrap-backed test:
```kotlin
@Test fun parsesVanillaState() {
    val s = BlockStateMapper.parse("minecraft:oak_log[axis=x]")
    assertNotNull(s); assertEquals(Direction.Axis.X, s!!.getValue(RotatedPillarBlock.AXIS))
}
@Test fun unknownBlockReturnsNull() {
    assertNull(BlockStateMapper.parse("nonexistmod:gizmo[foo=bar]"))
}
@Test fun cachesByString() {
    val a = BlockStateMapper.parse("minecraft:stone"); val b = BlockStateMapper.parse("minecraft:stone")
    assertSame(a, b)
}
```
> If MC registries can't bootstrap in the test JVM, implement the parse-string tokenizer as a pure helper (`splitNameAndProps`) and unit-test THAT; verify the BlockStateParser path via the in-client gate in Task 6. State which you did.

- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** using `BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), stateString, false)` → `.blockState()`; catch parse/unknown-block exceptions → `null` + increment `unresolvedCount`; memoize in a `HashMap<String, BlockState?>`.
- [ ] **Step 4: Run — passes.**
- [ ] **Step 5: Commit.** `git commit -m "feat(fabric): BlockStateMapper Nucleation-string→BlockState with cache + unresolved count (TDD)"`

### Task 4: Populate the snapshot from `SchematicData` (Litematica-free source)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/render/data/NucleationSnapshotSource.kt`
- Read (reference): the existing `render/` package — `SchematicSnapshot`/`SnapshotBlockRenderView`/`SchematicRenderSource` (per `.git/sdd/current-preview-state.md`).

**Interfaces:**
- Produces: `fun snapshotFromBytes(bytes: ByteArray): SchematicRenderSource` (or whatever the existing render source type is) — builds a `SchematicSnapshot` by iterating `SchematicData`, mapping each block via `BlockStateMapper.parse(...)` (skip nulls), and copying block-entity NBT into the snapshot's BE store.
- Consumes: `SchematicData` (Task 2), `BlockStateMapper` (Task 3), and the existing `SchematicSnapshot.Builder` API (the same one `LitematicaBridgeImpl.renderSourceFromSchematic` uses — read it as the template).

- [ ] **Step 1:** Read `LitematicaBridgeImpl.renderSourceFromSchematic` and `SchematicSnapshot.Builder` to learn the exact builder API (setBlock(pos,state), block-entity NBT, dimensions).
- [ ] **Step 2: Implement** `snapshotFromBytes`: `SchematicData.fromBytes(bytes).use { … }`, build a `SchematicSnapshot.Builder` of the right dimensions, `forEachBlock { x,y,z,s -> BlockStateMapper.parse(s)?.let { builder.setBlock(BlockPos(x,y,z), it) } }`, `forEachBlockEntity { … builder.setBlockEntityNbt(...) }`, return the built render source.
- [ ] **Step 3: Verify (compile + a tiny unit/integration check).** Compile `:fabric:1.21.11:compileClientKotlin`. If registries are available in tests, add a test that `snapshotFromBytes(single_stone.schem)` yields a snapshot of size 1×1×1 with a stone state at (0,0,0); else defer to the Task 6 gate. checkThemeDiscipline N/A (not panel code).
- [ ] **Step 4: Commit.** `git commit -m "feat(fabric): Litematica-free snapshot source (Nucleation→BlockStateMapper→SchematicSnapshot)"`

---

## PHASE 3 — Render engine (reusable core)

### Task 5: `SchematicRenderEngine` — live render-to-texture (build on existing `render/`)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/render/SchematicRenderEngine.kt`
- Read/Modify: existing `render/OffscreenSchematicRenderer.kt`, `ThumbnailCapture.kt`, `render target` infra (reuse/harden).

**Interfaces:**
- Produces:
  - `data class RenderRequest(val source: SchematicRenderSource, val camera: PreviewCamera, val background: PreviewBackground, val widthPx: Int, val heightPx: Int)`
  - `data class PreviewCamera(val projection: Projection /*PERSPECTIVE|ISOMETRIC*/, val fovDeg: Float, val yawDeg: Float, val pitchDeg: Float, val distance: Float, val target: Vec3)`
  - `enum class PreviewBackground { TRANSPARENT, SOLID /*#7ea8ff*/, MC_SKY }`
  - `object SchematicRenderEngine`: `fun render(req: RenderRequest): Int /* GL texture id of the RenderTarget color attachment */`; `fun capture(): ByteArray /* transparent PNG of the last render */`; `fun autoFit(source): PreviewCamera` (zoom-to-fit centered).
- Consumes: the existing offscreen renderer (block tessellation, version-split 1.21.x/26.1) + `RenderTarget`.

- [ ] **Step 1:** Read the existing `OffscreenSchematicRenderer`/`ThumbnailCapture`/render-target code (per `.git/sdd/current-preview-state.md`) to learn the current render-to-target + capture + 26.1 tessellation paths. The engine WRAPS/hardens these; do not rewrite the version-split tessellation.
- [ ] **Step 2: Implement** `SchematicRenderEngine.render(req)`: size/clear the offscreen `RenderTarget` (transparent clear for TRANSPARENT), build the projection+view matrix from `PreviewCamera` (ortho for ISOMETRIC, perspective for PERSPECTIVE/FOV), render the snapshot's block geometry (reuse existing tessellation) + a block-entity pass (BERs, version-split), return the color texture id. `capture()` reads the target → `NativeImage` → transparent PNG bytes (reuse `ThumbnailCapture`'s readback/flip).
- [ ] **Step 3: Performance — chunked cache + async (within this task or split if large):** tessellate per-16³ region buffers once on source change (cache keyed by source identity), redraw cached buffers each `render()`; build off-thread, upload on render thread; a `buildProgress: Float`/`isBuilding` flag; a size cap constant with a "showing region" flag. (If this balloons, land basic full-render first, then a follow-up task for chunked-async — note it.)
- [ ] **Step 4: MANUAL GATE (26.1) — the known risk.** Add a temporary dev hook (e.g. a debug keybind or reuse the composer once Task 7 exists) to render a known small snapshot and display its texture via `ImGui.image` (the working display path). Run `:fabric:26.1:runClient`; confirm the schematic renders into the texture visibly (blocks with real textures/lighting, transparent background). Expect to debug 26.1 GpuDevice/render-pass issues (budgeted). If 26.1 offscreen render fights the pipeline, apply the same investigation pattern used for the ImGui renderer (framebuffer binding, state, command encoder). Document findings.
- [ ] **Step 5: Commit.** `git commit -m "feat(fabric): SchematicRenderEngine live render-to-texture + capture (reuses offscreen renderer)"`

### Task 6: Render-engine in-client validation harness + block-entity pass verification

**Files:**
- Modify: `SchematicRenderEngine.kt` (BER pass), a temporary dev trigger.

**Interfaces:** Consumes Task 5's engine + Task 4's snapshot source.

- [ ] **Step 1:** Wire a temporary dev path: parse a bundled test `.schem` (incl. a chest + sign + a modded block if a mod is present) via `NucleationSnapshotSource`, render via the engine, show via `ImGui.image`.
- [ ] **Step 2: MANUAL GATE (26.1):** run client; confirm (a) blocks render with correct models/textures/lighting; (b) **block entities render** (chest 3D, sign — BER pass works, version-split correct); (c) a modded block (if installed) renders; (d) unknown/missing-mod block is skipped and counted; (e) transparent background is actually transparent in `capture()` PNG (inspect the saved PNG alpha).
- [ ] **Step 3: Fix** any BER/version-split/transparency issues found.
- [ ] **Step 4: Commit.** `git commit -m "feat(fabric): block-entity render pass + engine validation (26.1)"`

---

## PHASE 4 — Composer UI (web parity)

### Task 7: `PreviewComposerPanel` (ImGui)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/imgui/panels/PreviewComposerPanel.kt`

**Interfaces:**
- Produces: `object PreviewComposerPanel : Panel` (`id="preview-composer"`) with `fun show(sourceBytes: ByteArray, onCapture: (pngBytes: ByteArray) -> Unit)` — opens the composer for a schematic's bytes and calls `onCapture` with the captured PNG when the user confirms.
- Consumes: `SchematicRenderEngine`, `NucleationSnapshotSource`, `Widgets`/`ImGuiColors`/`ImGuiTheme`, `PanelManager`.

- [ ] **Step 1: Implement** `render()`: a live viewport via `ImGui.image(SchematicRenderEngine.render(request), w, h)` sized to the chosen aspect (16:9 default; 4:3; 1:1); mouse over the image: drag→orbit (yaw/pitch), scroll→zoom (distance), shift-drag→pan (target). Controls row (themed `Widgets`): projection toggle (Perspective/Isometric), FOV slider 20–90 (perspective only), view-preset buttons (6 faces + 4 iso corners default `iso-se` + top-down diagonals), background selector (Transparent/Solid `#7ea8ff`/MC sky), aspect toggle, "Reframe" (auto-fit). A "Capture" button → `SchematicRenderEngine.capture()` → `onCapture(png)` + close; auto-capture on confirm. Show the "N blocks hidden (missing mods)" note from `BlockStateMapper.unresolvedCount` + a "building…" state from the engine.
- [ ] **Step 2:** `:fabric:1.21.11:compileClientKotlin` + `:fabric:1.21.11:checkThemeDiscipline` SUCCESS; `:fabric:26.1:compileClientKotlin` SUCCESS.
- [ ] **Step 3: MANUAL GATE (26.1):** open composer on a real schematic; verify orbit/zoom/pan, projection+FOV, presets, backgrounds, aspect, auto-fit, and capture-produces-transparent-PNG — and that it "feels like" the web preview.
- [ ] **Step 4: Commit.** `git commit -m "feat(fabric): PreviewComposerPanel (ImGui) at web parity"`

---

## PHASE 5 — Integration

### Task 8: Wire composer into Upload + Edit, replace placeholder

**Files:**
- Modify: `fabric/src/client/.../ui/imgui/panels/UploadWizardPanel.kt` (replace `placeholderPng`)
- Modify: `fabric/src/client/.../ui/imgui/panels/SchematicEditPanel.kt`

**Interfaces:** Consumes `PreviewComposerPanel.show(bytes, onCapture)`.

- [ ] **Step 1:** In `UploadWizardPanel`, the source step already yields schematic bytes (from ExportSources). Add a "Generate preview" action that calls `PreviewComposerPanel.show(bytes) { png -> previewImagePng = png }`; show the captured thumbnail in the confirm step; remove the `placeholderPng(name)` default (keep a graceful fallback: if the user skips capture, fall back to placeholder so upload still succeeds). Remove the `// TODO(Task ...)` placeholder note.
- [ ] **Step 2:** In `SchematicEditPanel`, allow re-capturing the preview via the composer (optional but parity with web edit form): a "Change preview" button → composer → update the edit payload.
- [ ] **Step 3:** Compile 1.21.11 + 26.1; checkThemeDiscipline SUCCESS.
- [ ] **Step 4: MANUAL GATE (26.1):** full upload flow — pick a source, generate a real preview, upload; verify the uploaded `preview_image` is the captured render (not placeholder).
- [ ] **Step 5: Commit.** `git commit -m "feat(fabric): wire native preview composer into upload + edit (replace placeholder)"`

---

## Follow-ups (separate tasks/plans, NOT in this plan)

- **Cross-platform Nucleation natives + CI fat-JAR** (Linux via `cross`, Windows `.dll` via CI) — required before shipping non-macOS.
- **1.21.x overlay rendering** — the ImGui overlay only renders on 26.1 today; the preview engine's 1.21.x gate depends on that being fixed (tracked separately).
- **VCS diff viewer** — reuses layers 1–3 + `Schematic.diff`; its own spec.
- **BER perf** for very large schematics (cap/cull), HDRI-sky background, multi-angle capture — deferred.

## Self-review notes

- **Spec coverage:** data layer→T1–T2; mapping→T3; Litematica-free snapshot→T4; render engine (live texture, perf, BER, 26.1)→T5–T6; composer parity→T7; integration→T8; reusability seam (`RenderRequest`)→T5; follow-ups (cross-platform, diff, 1.21.x) listed. All spec sections covered.
- **TDD vs gates:** pure layers (T2 facade, T3 mapping) TDD with the honest caveat that native-load/registry-bootstrap may force some checks to in-client gates (stated in-task); rendering (T5–T8) uses explicit 26.1 manual gates — consistent with the spec's testing section.
- **Discovery-deferred specifics** (exact Nucleation JAR method names; existing `render/` signatures; 26.1 render internals) are pointed at the named `.git/sdd/` reports + the existing code as authoritative, with verification baked into each task — not silent placeholders.
- **Type consistency:** `SchematicData.fromBytes/forEachBlock`, `BlockStateMapper.parse/unresolvedCount`, `NucleationSnapshotSource.snapshotFromBytes`, `SchematicRenderEngine.render/capture/autoFit` + `RenderRequest`/`PreviewCamera`/`PreviewBackground`, `PreviewComposerPanel.show(bytes,onCapture)` used consistently across tasks.
