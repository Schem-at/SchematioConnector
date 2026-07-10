# Phase 1: ImGui Client Structural Cleanup + Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the Fabric client into one coherent UI package tree, split the three ~1000-line files, and squash-merge `feature/imgui-ui-migration` to `master` as v1.3.0.

**Architecture:** Pure behavior-preserving refactor: `git mv` moves committed separately from splits (preserves rename detection), package/import rewrites via full-prefix `sed`, every task gated by the same build+test command. Spec: `docs/superpowers/specs/2026-07-10-connector-cleanup-handshake-v2-design.md`.

**Tech Stack:** Kotlin 2.x, Gradle + Stonecutter (fabric versions 1.21.8–1.21.11, 26.1), imgui-java 1.89.0.

## Global Constraints

- Repo: `~/IdeaProjects/SchematioConnector`, branch `feature/imgui-ui-migration`. All commands run from repo root.
- **No behavior changes.** Moves and splits only; no logic edits, no renames of public symbols beyond package changes.
- Verification gate (run after EVERY task, expected `BUILD SUCCESSFUL`):
  `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
- `private` members that become cross-file within a split change to `internal` — never `public`.
- sed on macOS: use `sed -i ''`. Always match the FULL prefix `io.schemat.connector.fabric.client...` (the short `client.imgui` and long `client.ui.imgui` prefixes do not substring-collide, but full prefixes keep it safe).
- Do NOT push or tag anywhere in Tasks 1–8. Task 9 has an explicit human gate (pushing a tag triggers the GitHub release workflow).
- Kotlin client root (used below as `$K`): `fabric/src/client/kotlin/io/schemat/connector/fabric/client`

---

### Task 1: Repo hygiene

**Files:**
- Delete: `imgui/ImDrawData.class` (untracked debris at repo root)
- Revert: `$K/ui/imgui/Widgets.kt` (uncommitted diff adds only two unused imports: `ImGuiTableColumnFlags`, `ImGuiTreeNodeFlags`)

**Interfaces:** none — produces a clean working tree at branch HEAD.

- [ ] **Step 1: Delete debris and revert the unused-import diff**

```bash
rm -rf imgui/
git checkout -- fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/imgui/Widgets.kt
```

- [ ] **Step 2: Verify tree is clean**

Run: `git status --porcelain`
Expected: empty output. (`.history/` is already gitignored at `.gitignore:64` — nothing to add.)

- [ ] **Step 3: Baseline verification gate**

Run: `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
Expected: `BUILD SUCCESSFUL`. This is the baseline every later task must reproduce. No commit (nothing tracked changed).

---

### Task 2: Move `client/imgui/` → `client/ui/framework/`

**Files:**
- Move: `$K/imgui/{DockHost,ImGuiGl3Renderer,ImGuiManager,ImGuiOverlay,Panel,PanelManager,Toolbar}.kt` → `$K/ui/framework/`
- Move test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/imgui/PanelManagerTest.kt` → `.../client/ui/framework/PanelManagerTest.kt`
- Modify: every file importing `io.schemat.connector.fabric.client.imgui.*` (known importers include `$K/mixin/RenderSystemMixin.java`, `KeyboardMixin.java`, `MouseMixin.java`, `$K/integration/MixinBridge.kt`, `$K/keybind/Keybinds.kt`, `$K/command/SchematioClientCommands.kt`, `$K/SchematioClientMod.kt`, panels — the sed sweep catches all)

**Interfaces:**
- Produces: package `io.schemat.connector.fabric.client.ui.framework` containing `ImGuiManager`, `PanelManager`, `Panel`, `DockHost`, `Toolbar`, `ImGuiOverlay`, `ImGuiGl3Renderer` — all later tasks import framework types from here.

- [ ] **Step 1: git mv the sources and test**

```bash
K=fabric/src/client/kotlin/io/schemat/connector/fabric/client
mkdir -p $K/ui/framework fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/framework
git mv $K/imgui/*.kt $K/ui/framework/
git mv fabric/src/test/kotlin/io/schemat/connector/fabric/client/imgui/PanelManagerTest.kt \
       fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/framework/PanelManagerTest.kt
rmdir $K/imgui fabric/src/test/kotlin/io/schemat/connector/fabric/client/imgui 2>/dev/null || true
```

- [ ] **Step 2: Rewrite package declarations and all references repo-wide**

```bash
grep -rl 'io\.schemat\.connector\.fabric\.client\.imgui' fabric/src \
  | xargs sed -i '' 's/io\.schemat\.connector\.fabric\.client\.imgui/io.schemat.connector.fabric.client.ui.framework/g'
```

Then confirm nothing was missed: `grep -rn 'client\.imgui' fabric/src bukkit/src core/src` → expected: no matches.

- [ ] **Step 3: Verification gate**

Run: `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
Expected: `BUILD SUCCESSFUL` (mixins compile against the new package — if a mixins.json or reflection string referenced the old package, the fabric build fails here; fix by grepping `client.imgui` in `fabric/src/**/resources` too).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(fabric): move client/imgui framework layer to ui/framework"
```

---

### Task 3: Move `ui/imgui/panels/` → `ui/panels/`

**Files:**
- Move: every file in `$K/ui/imgui/panels/` → `$K/ui/panels/` (BrowsePanel, MySchematicsPanel, UploadWizardPanel, SchematicDetailPanel, SchematicEditPanel, SchematicListView, CommunitiesPanel, CommunityDetailPanel, SharesPanel, QuickShareCreatePanel, PreviewComposerPanel, SettingsPanel, …)
- Modify: all importers of `io.schemat.connector.fabric.client.ui.imgui.panels.*`

**Interfaces:**
- Produces: package `io.schemat.connector.fabric.client.ui.panels` — Tasks 6–8 operate on files at these new paths.

- [ ] **Step 1: git mv**

```bash
K=fabric/src/client/kotlin/io/schemat/connector/fabric/client
mkdir -p $K/ui/panels
git mv $K/ui/imgui/panels/*.kt $K/ui/panels/
rmdir $K/ui/imgui/panels
```

- [ ] **Step 2: Rewrite package + references**

```bash
grep -rl 'io\.schemat\.connector\.fabric\.client\.ui\.imgui\.panels' fabric/src \
  | xargs sed -i '' 's/io\.schemat\.connector\.fabric\.client\.ui\.imgui\.panels/io.schemat.connector.fabric.client.ui.panels/g'
grep -rn 'ui\.imgui\.panels' fabric/src   # expected: no matches
```

- [ ] **Step 3: Verification gate**

Run: `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(fabric): move ui/imgui/panels to ui/panels"
```

---

### Task 4: Consolidate widgets and theme; retire `ui/imgui` and `ui/compat`

**Files:**
- Move: `$K/ui/imgui/{Widgets,ConfirmModal,TagSelectorPopup,RichTextWidget,RichTextEditorWidget,PlayerListPicker}.kt` → `$K/ui/widgets/`
- Move: `$K/ui/imgui/{ImGuiTheme,ImGuiColors}.kt` → `$K/ui/theme/` (joins existing `Theme.kt`)
- Move: `$K/ui/compat/Draw.kt` → `$K/ui/widgets/Draw.kt`
- Move test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/imgui/ImGuiColorsTest.kt` → `.../ui/theme/ImGuiColorsTest.kt`
- Keep: `$K/ui/widgets/ExportSourcePicker.kt` (already in target package, untouched)
- Modify: all importers of the three old packages

**Interfaces:**
- Produces: packages `...client.ui.widgets` (Widgets, ConfirmModal, TagSelectorPopup, RichTextWidget, RichTextEditorWidget, PlayerListPicker, Draw, ExportSourcePicker) and `...client.ui.theme` (Theme, ImGuiTheme, ImGuiColors). Packages `...ui.imgui` and `...ui.compat` cease to exist.

- [ ] **Step 1: git mv all pieces**

```bash
K=fabric/src/client/kotlin/io/schemat/connector/fabric/client
T=fabric/src/test/kotlin/io/schemat/connector/fabric/client
git mv $K/ui/imgui/Widgets.kt $K/ui/imgui/ConfirmModal.kt $K/ui/imgui/TagSelectorPopup.kt \
       $K/ui/imgui/RichTextWidget.kt $K/ui/imgui/RichTextEditorWidget.kt $K/ui/imgui/PlayerListPicker.kt \
       $K/ui/widgets/
git mv $K/ui/imgui/ImGuiTheme.kt $K/ui/imgui/ImGuiColors.kt $K/ui/theme/
git mv $K/ui/compat/Draw.kt $K/ui/widgets/Draw.kt
mkdir -p $T/ui/theme
git mv $T/ui/imgui/ImGuiColorsTest.kt $T/ui/theme/ImGuiColorsTest.kt
rmdir $K/ui/imgui $K/ui/compat $T/ui/imgui 2>/dev/null || true
```

- [ ] **Step 2: Rewrite packages + references (order matters: most-specific first)**

Widgets moving to `ui.widgets`, theme files to `ui.theme`, Draw to `ui.widgets`. Per-class rewrites because one source package fans out to two targets:

```bash
for c in Widgets ConfirmModal TagSelectorPopup RichTextWidget RichTextEditorWidget PlayerListPicker; do
  grep -rl "io\.schemat\.connector\.fabric\.client\.ui\.imgui\.$c" fabric/src \
    | xargs sed -i '' "s/io\.schemat\.connector\.fabric\.client\.ui\.imgui\.$c/io.schemat.connector.fabric.client.ui.widgets.$c/g"
done
for c in ImGuiTheme ImGuiColors; do
  grep -rl "io\.schemat\.connector\.fabric\.client\.ui\.imgui\.$c" fabric/src \
    | xargs sed -i '' "s/io\.schemat\.connector\.fabric\.client\.ui\.imgui\.$c/io.schemat.connector.fabric.client.ui.theme.$c/g"
done
grep -rl 'io\.schemat\.connector\.fabric\.client\.ui\.compat\.Draw' fabric/src \
  | xargs sed -i '' 's/io\.schemat\.connector\.fabric\.client\.ui\.compat\.Draw/io.schemat.connector.fabric.client.ui.widgets.Draw/g'
```

Then fix the six moved widget files' own `package` lines (their declaration says `ui.imgui`, not covered by class-name sed) and the two theme files:

```bash
K=fabric/src/client/kotlin/io/schemat/connector/fabric/client
sed -i '' 's/^package io\.schemat\.connector\.fabric\.client\.ui\.imgui$/package io.schemat.connector.fabric.client.ui.widgets/' $K/ui/widgets/{Widgets,ConfirmModal,TagSelectorPopup,RichTextWidget,RichTextEditorWidget,PlayerListPicker}.kt
sed -i '' 's/^package io\.schemat\.connector\.fabric\.client\.ui\.imgui$/package io.schemat.connector.fabric.client.ui.theme/' $K/ui/theme/{ImGuiTheme,ImGuiColors}.kt
sed -i '' 's/^package io\.schemat\.connector\.fabric\.client\.ui\.compat$/package io.schemat.connector.fabric.client.ui.widgets/' $K/ui/widgets/Draw.kt
sed -i '' 's/^package io\.schemat\.connector\.fabric\.client\.ui\.imgui$/package io.schemat.connector.fabric.client.ui.theme/' fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/theme/ImGuiColorsTest.kt
```

Files that lived together in `ui.imgui` referenced each other without imports; after the fan-out, same-package references between `ui.widgets` and `ui.theme` files now need explicit imports. The verification build lists every unresolved reference — add the matching `import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme` (etc.) lines until green. Also sweep: `grep -rn 'ui\.imgui\|ui\.compat' fabric/src` → expected: no matches.

- [ ] **Step 3: Verification gate**

Run: `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
Expected: `BUILD SUCCESSFUL`. Note: `checkThemeDiscipline` validates theme usage patterns — if it flags the moved files, the fix is import placement only, never logic.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(fabric): one widgets package, one theme package; retire ui/imgui and ui/compat"
```

---

### Task 5: Move `ChatNotice` and `PreviewImageManager` to `services/`

**Files:**
- Move: `$K/ui/ChatNotice.kt` → `$K/services/ChatNotice.kt`; `$K/ui/PreviewImageManager.kt` → `$K/services/PreviewImageManager.kt`
- Modify: known importers `$K/command/SchematioClientCommands.kt`, `$K/services/ClientServices.kt`, `$K/services/HeadAvatarManager.kt`, `$K/ui/panels/{SchematicDetailPanel,SchematicListView,UploadWizardPanel}.kt`

**Interfaces:**
- Produces: `io.schemat.connector.fabric.client.services.ChatNotice`, `...services.PreviewImageManager` (same public members).

- [ ] **Step 1: git mv + rewrite**

```bash
K=fabric/src/client/kotlin/io/schemat/connector/fabric/client
git mv $K/ui/ChatNotice.kt $K/services/ChatNotice.kt
git mv $K/ui/PreviewImageManager.kt $K/services/PreviewImageManager.kt
for c in ChatNotice PreviewImageManager; do
  grep -rl "io\.schemat\.connector\.fabric\.client\.ui\.$c" fabric/src \
    | xargs sed -i '' "s/io\.schemat\.connector\.fabric\.client\.ui\.$c/io.schemat.connector.fabric.client.services.$c/g"
  sed -i '' "s/^package io\.schemat\.connector\.fabric\.client\.ui$/package io.schemat.connector.fabric.client.services/" $K/services/$c.kt
done
grep -rn 'client\.ui\.ChatNotice\|client\.ui\.PreviewImageManager' fabric/src   # expected: no matches
```

- [ ] **Step 2: Verification gate**

Run: `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor(fabric): ChatNotice + PreviewImageManager are services, not UI"
```

---

### Task 6: Split `UploadWizardPanel.kt` (969 lines → shell + 5 focused files)

**Files:**
- Modify: `$K/ui/panels/UploadWizardPanel.kt` (becomes the shell)
- Create: `$K/ui/panels/upload/UploadSourceStep.kt`, `upload/UploadDetailsStep.kt`, `upload/UploadConfirmStep.kt`, `upload/UploadPreview.kt`, `upload/UploadSubmit.kt` — package `io.schemat.connector.fabric.client.ui.panels.upload`

**Interfaces:**
- Consumes: `UploadWizardPanel` object state (fields flip `private` → `internal` as needed).
- Produces: `internal fun UploadWizardPanel.renderSourceStep()`, `renderDetailsStep()`, `validateDetailsAndAdvance()`, `renderConfirmStep()`, `generatePreview()`, `openComposer(bytes: ByteArray)`, `renderCapturedPreviewImage()`, `buildPreviewTexture(png: ByteArray)`, `releasePreviewTexture()`, `startUpload()`, `performUpload(source: ExportSource, bytesProvider: suspend () -> ByteArray)` — extension functions on the object, bodies moved verbatim.

**Split map (symbols by current line numbers in UploadWizardPanel.kt):**

| Target file | Moves verbatim |
|---|---|
| `UploadSourceStep.kt` | `renderSourceStep` (239), `sourceSection` (281), `sourceRow` (293), `kindTag` (319) |
| `UploadDetailsStep.kt` | `renderDetailsStep` (328), `validateDetailsAndAdvance` (433), `loadCommunitiesAndTags` (817) |
| `UploadConfirmStep.kt` | `renderConfirmStep` (449), `sectionHeading` (509), `summaryRow` (519), `webLink` (963) |
| `UploadPreview.kt` | `generatePreview` (576), `openComposer` (625), `renderCapturedPreviewImage` (642), `buildPreviewTexture` (661), `releasePreviewTexture` (692), `placeholderPng` (926), the `PREVIEW_TEX_ID` val (118) |
| `UploadSubmit.kt` | `startUpload` (530), `performUpload` (703), `isMissingUploadPermission` (808) |
| stays in shell | `Step` enum, `LOGGER`, all state fields, `open()` ×2, `reset`, `requestClose`, `renderStatus`, `renderNavButtons`, the `Panel` implementation/`render` entry |

- [ ] **Step 1: Create the five files, move symbols verbatim**

Each new file starts:

```kotlin
package io.schemat.connector.fabric.client.ui.panels.upload

import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
```

Move each listed function into its file converted to an extension: `private fun renderSourceStep()` → `internal fun UploadWizardPanel.renderSourceStep()`. Function BODIES are moved character-for-character; only the declaration line changes. `PREVIEW_TEX_ID` moves as a top-level `internal val` in `UploadPreview.kt`. Add the imports each file's body needs (copy from the original file's import block; delete now-unused imports from the shell).

- [ ] **Step 2: Flip touched state fields to `internal`**

In the shell, every `private` field or method still referenced by a moved body becomes `internal` (e.g. `internal var step`, `internal val LOGGER`). The compiler is the checklist: build, fix each "cannot access" error by widening exactly that member, repeat.

- [ ] **Step 3: Shell calls the extensions**

In the shell's `render`/dispatch code, call-sites are unchanged textually (`renderSourceStep()` still resolves — now as an imported extension). Add to `UploadWizardPanel.kt`:

```kotlin
import io.schemat.connector.fabric.client.ui.panels.upload.*
```

- [ ] **Step 4: Verification gate + line check**

Run: `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
Expected: `BUILD SUCCESSFUL`. Also: `wc -l $K/ui/panels/UploadWizardPanel.kt` → expected < 400.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(fabric): split UploadWizardPanel into per-step files"
```

---

### Task 7: Split `TagSelectorPopup.kt` (983 lines → popup + model + filter inputs)

**Files:**
- Modify: `$K/ui/widgets/TagSelectorPopup.kt` (keeps: `Mode`, state, `isOpen`, `show` ×2, `reset`, `beginOpen`, buf accessors, `render`, `renderContents`, `renderTree`, `renderNode`, `renderRow`, `renderChips`, `drawChevron`, `drawDisclosure`, `u32` ×2, `hexColor`)
- Create: `$K/ui/widgets/tagselector/TagTreeModel.kt` — moves verbatim as extensions on `TagSelectorPopup` (same pattern as Task 6): `nodePath` (471), `rebuildCachesIfNeeded` (802), `expandSelectionAncestors` (826), `collectAssignValues` (641), `collectConstraints` (652), `activeFilters` (670), `validationError` (682), `filterError` (695), `rangeError` (704), `formatNumber` (847)
- Create: `$K/ui/widgets/tagselector/TagFilterInputs.kt` — moves verbatim: `renderFilterRow` (487), `renderAssignInput` (512), `renderFilterInput` (529), `renderRangeFields` (543), `renderCycler` (572), `assignHint` (632)

**Interfaces:**
- Produces: package `...ui.widgets.tagselector` with the internal extensions above; `TagSelectorPopup`'s public API (`show`, `render`, `isOpen`, `Mode`) is unchanged.

- [ ] **Step 1: Create the two files, move symbols as `internal fun TagSelectorPopup.…` extensions** (same mechanics as Task 6 Step 1; new files' package is `io.schemat.connector.fabric.client.ui.widgets.tagselector`, importing `...ui.widgets.TagSelectorPopup`)

- [ ] **Step 2: Widen accessed members to `internal`, add `import ...ui.widgets.tagselector.*` to the popup, compile-fix until clean**

- [ ] **Step 3: Verification gate + line check**

Run: `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
Expected: `BUILD SUCCESSFUL`; `wc -l $K/ui/widgets/TagSelectorPopup.kt` → expected < 550.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(fabric): extract tag-tree model and filter inputs from TagSelectorPopup"
```

---

### Task 8: Split `RichTextEditorWidget.kt` (939 lines → widget + layout + toolbar)

`RichTextEditorWidget` is a **class** (per-instance state), so this split uses internal member extraction, not object extensions.

**Files:**
- Modify: `$K/ui/widgets/RichTextEditorWidget.kt` (keeps: public API `setHtml`/`setFromHtml`/`clear`/`toHtml`/`isEmpty`/`isChanged`/`plainText`/`render`, `StyleFlag`, companion, `RChar`, `TypingStyle`, input/editing: `edited`, `deleteSelection`, `typingStyle`, `styleKeyOf`, `derivedStyle`, `logicalLineStart`, `bulletForInsert`, `insertChar`, selection helpers, `renderSurface`)
- Create: `$K/ui/widgets/richtext/RichTextLayout.kt` — class `RichTextLayout` receiving the widget: moves verbatim `VLine` (144), `ensureLayout` (290), `rebuildLayout` (322), `lineIndexAt` (366), `hitTest` (375), `charWidth` (301), `bulletWidth` (311), `font`/`fontSize`/`lineHeight` (295–298)
- Create: `$K/ui/widgets/richtext/RichTextToolbar.kt` — moves verbatim `renderToolbar` (217), `markToolbar` (249), `styleButton` (255)

**Interfaces:**
- Produces: `internal class RichTextLayout(private val w: RichTextEditorWidget)` with `fun ensureLayout(width: Float)`, `fun hitTest(mouseX: Float, mouseY: Float, originX: Float, originY: Float): Int`, `fun lineIndexAt(pos: Int): Int`; `internal class RichTextToolbar(private val w: RichTextEditorWidget)` with `fun render()`. Widget holds `internal val layout = RichTextLayout(this)`, `internal val toolbar = RichTextToolbar(this)`; call-sites become `layout.ensureLayout(...)`, `toolbar.render()`.
- `RChar`, `TypingStyle`, `VLine` and the state fields the helpers touch (`chars`, `cursor`, `selAnchor`, `lines`, …) flip `private` → `internal`.

- [ ] **Step 1: Create `richtext/` files (package `io.schemat.connector.fabric.client.ui.widgets.richtext`), move bodies verbatim, references to widget state become `w.chars`, `w.cursor`, etc.**

- [ ] **Step 2: Wire `layout`/`toolbar` fields into the widget, update call-sites (`renderToolbar()` → `toolbar.render()`, `ensureLayout(x)` → `layout.ensureLayout(x)`), widen members to `internal` per compiler errors**

- [ ] **Step 3: Verification gate + regression check**

Run: `./gradlew :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
Expected: `BUILD SUCCESSFUL`; `:core:test` includes `RichDocumentTest` (document model unaffected). `wc -l $K/ui/widgets/RichTextEditorWidget.kt` → expected < 550.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(fabric): extract layout engine and toolbar from RichTextEditorWidget"
```

---

### Task 9: Final verification, version bump, squash-merge — **HUMAN GATE**

**Files:**
- Modify: `gradle.properties` (versionMinor 2→3, versionPatch→0 ⇒ v1.3.0), `fabric/MANUAL_TESTING.md` (any file paths referencing old packages)

**Interfaces:** none — produces master at v1.3.0.

- [ ] **Step 1: Sweep for stale references in docs/resources**

```bash
grep -rn 'ui\.imgui\|client\.imgui\|ui\.compat' fabric/MANUAL_TESTING.md README.md fabric/src/*/resources docs/ 2>/dev/null
```

Expected: no matches (fix any hits — path strings in docs only).

- [ ] **Step 2: Bump version to 1.3.0 in `gradle.properties`, commit**

```bash
git add gradle.properties fabric/MANUAL_TESTING.md
git commit -m "chore: v1.3.0"
```

- [ ] **Step 3: Full gate one last time**

Run: `./gradlew clean :core:test :bukkit:build :fabric:buildAllVersions checkThemeDiscipline`
Expected: `BUILD SUCCESSFUL`, 6 fabric jars in `build/libs/`.

- [ ] **Step 4: Manual smoke (run-paper)** — join local Paper server, confirm v1 handshake unchanged (ServerSession populated), open workspace, upload + download round-trip. Record result.

- [ ] **Step 5: STOP — ask Harrison before merging.** Present: test output, jar count, smoke result. On explicit go-ahead only:

```bash
git checkout master && git pull
git merge --squash feature/imgui-ui-migration
git commit -m "feat: ImGui client UI, IPC foundation, native preview renderer (v1.3.0)"
```

Tagging/pushing follows RELEASING.md and **triggers the GitHub release workflow** — Harrison's call, not the executor's.

---

## Deferred plans

- **Phase 2 (handshake v2):** planned after this merges — branches off new master; touches `core/ipc`, both server platforms, client `ServerSession`/UI, and schemati (`/api/plugin/attest` + key endpoint). See spec §Phase 2.
- **Phase 3 (ImGui parity):** planned after this merges. See spec §Phase 3.
