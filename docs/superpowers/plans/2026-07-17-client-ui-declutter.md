# Client UI Declutter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the 7-button (+2 disabled) ImGui toolbar to `[Browse] [Upload]` + `Share ▾` / `Tools ▾` / `⚙` menus, merge My Schematics and Communities into a single scoped Browse panel, remove the duplicate Quick Share button, and add a Browse-docked-right default layout with a reset.

**Architecture:** `BrowsePanel` already browses by `SchematicListView.Context` (`Public`/`Mine`/`Community(slug,name)`) via `buildContextList()`; this plan promotes that cycler into an explicit `All · Mine · Communities ▾` scope selector, adds a "Manage community" handoff to the (unchanged) `CommunityDetailPanel`, then deletes the now-redundant `MySchematicsPanel`/`CommunitiesPanel`. The toolbar switches to real `ImGui.beginMenu` dropdowns. `DockHost` gains a DockBuilder default layout (imgui-java 1.89.0 internal API confirmed present) that docks Browse to a right split while keeping the central node `PassthruCentralNode` (game visible/clickable).

**Tech Stack:** Kotlin, imgui-java 1.89.0 (incl. `imgui.internal.ImGui` DockBuilder), Fabric + Stonecutter (dev version 1.21.11), JUnit 5.

## Global Constraints

- **Do NOT `git commit`** unless Harrison explicitly approves (standing rule). Skip every commit step; leave the tree dirty for review.
- Test command: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test` (stonecutter — plain `:fabric:test` does not exist). If a PreToolUse hook blocks `./gradlew` in Bash, run via the context-mode sandbox exec tool.
- Touch only the files each task names. All paths are under `fabric/src/client/kotlin/io/schemat/connector/fabric/client/` unless stated; shorthand `.../ui/...` = that prefix + `ui/...`.
- The central dockspace node MUST stay `PassthruCentralNode or NoDockingInCentralNode` (transparent, game clickable) — the default layout docks ONLY into a right-side split, never the central node.
- No new features and no changes to IPC flows — reorganization only. The "Upload clipboard to server" action (from `ServerIpc.canUploadClipboard()` / the C11 work) only moves its entry point into the Share menu.
- Icons come from `io.schemat.connector.fabric.client.ui.theme.Icons`; menus/widgets from `Widgets`/`ImGuiTheme`. No raw hex colors.
- File:line references are as of plan-writing — re-locate with the given grep before editing.

---

### Task 1: Browse scope selector (All / Mine / Communities ▾) + Manage handoff

Promote `BrowsePanel`'s "Context: X" cycler into an explicit scope selector and add a community picker + "Manage community" button, so Browse fully covers what My Schematics and Communities did (prerequisite for deleting them in Tasks 2–3).

**Files:**
- Modify: `.../ui/panels/BrowsePanel.kt` (the `renderControls()` scope UI — currently the cycler at lines ~72–86, `buildContextList()` ~128–131)
- Create: `.../ui/panels/BrowseScope.kt` (pure scope model + selection helper, headless-testable)
- Test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/panels/BrowseScopeTest.kt`

**Interfaces:**
- Consumes: `SchematicListView.Context` (`Context.Public`, `Context.Mine`, `Context.Community(slug: String, name: String)`), `ClientServices.me?.communities` (each has `slug`, `name`), `BrowsePanel.resetAndLoad()`.
- Produces: `BrowseScope` sealed model (`All`, `Mine`, `Community(slug, name)`) + `BrowseScope.toContext(scope): SchematicListView.Context` (companion fn). `BrowsePanel` exposes unchanged `invalidate()` and gains private `scope`/`setScope`/`scopeButton`. The communities list is read inline from `services.me?.communities`.

- [ ] **Step 1: Write the failing test** — `BrowseScopeTest.kt`:

```kotlin
package io.schemat.connector.fabric.client.ui.panels

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class BrowseScopeTest {

    @Test
    fun `All maps to Public context`() {
        assertEquals(SchematicListView.Context.Public, BrowseScope.toContext(BrowseScope.All))
    }

    @Test
    fun `Mine maps to Mine context`() {
        assertEquals(SchematicListView.Context.Mine, BrowseScope.toContext(BrowseScope.Mine))
    }

    @Test
    fun `Community scope maps to a Community context carrying slug and name`() {
        val ctx = BrowseScope.toContext(BrowseScope.Community("castles", "Castle Builders"))
        assertTrue(ctx is SchematicListView.Context.Community)
        ctx as SchematicListView.Context.Community
        assertEquals("castles", ctx.slug)
        assertEquals("Castle Builders", ctx.name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test --tests '*BrowseScopeTest*'`
Expected: FAIL — unresolved reference `BrowseScope`.

- [ ] **Step 3: Create `BrowseScope.kt`**

```kotlin
package io.schemat.connector.fabric.client.ui.panels

/**
 * The Browse panel's top-level scope. [All] and [Mine] are fixed; [Community] is one
 * per community the player belongs to (chosen from the Communities dropdown). Maps 1:1
 * onto the existing [SchematicListView.Context] query contexts.
 */
sealed class BrowseScope {
    object All : BrowseScope()
    object Mine : BrowseScope()
    data class Community(val slug: String, val name: String) : BrowseScope()

    companion object {
        fun toContext(scope: BrowseScope): SchematicListView.Context = when (scope) {
            All -> SchematicListView.Context.Public
            Mine -> SchematicListView.Context.Mine
            is Community -> SchematicListView.Context.Community(scope.slug, scope.name)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test --tests '*BrowseScopeTest*'`
Expected: PASS.

- [ ] **Step 5: Wire the scope selector into `BrowsePanel.renderControls()`**

Read `BrowsePanel.kt`. Replace the single `if (ImGui.button("Context: ${currentContext.label}"))` cycler block (~lines 72–90) with a segmented scope row + a communities dropdown. Keep `state`, `resetAndLoad()`, `loadNextPage()`, tag filter, search/sort/order controls exactly as they are. Track the current scope in a new field `private var scope: BrowseScope = BrowseScope.All` and derive `context` from it. The row:

```kotlin
        // --- Scope selector: All | Mine | Communities ▾ ---
        scopeButton("All", scope is BrowseScope.All) { setScope(BrowseScope.All) }
        ImGui.sameLine()
        scopeButton("Mine", scope is BrowseScope.Mine) { setScope(BrowseScope.Mine) }
        ImGui.sameLine()
        val communities = services.me?.communities.orEmpty()
        val communityLabel = (scope as? BrowseScope.Community)?.name ?: "Communities"
        if (ImGui.beginMenu("$communityLabel  ${Icons.CHEVRON_DOWN}", communities.isNotEmpty())) {
            for (c in communities) {
                if (ImGui.menuItem(c.name, "", scope.let { it is BrowseScope.Community && it.slug == c.slug })) {
                    setScope(BrowseScope.Community(c.slug, c.name))
                }
            }
            ImGui.endMenu()
        }
        // "Manage community" only in a Community scope
        (scope as? BrowseScope.Community)?.let { cs ->
            ImGui.sameLine()
            if (Widgets.secondaryButton("Manage")) {
                services.me?.communities?.firstOrNull { it.slug == cs.slug }?.let {
                    CommunityDetailPanel.show(it)   // existing show(summary) entry point
                }
            }
        }
```

Add these helpers to `BrowsePanel` (and `setScope` which updates `scope`, derives the context, and reloads):

```kotlin
    private fun scopeButton(label: String, active: Boolean, onClick: () -> Unit) {
        if (active) { if (Widgets.primaryButton(label)) onClick() }
        else { if (Widgets.secondaryButton(label)) onClick() }
    }

    private fun setScope(next: BrowseScope) {
        if (next == scope) return
        scope = next
        context = BrowseScope.toContext(next)
        resetAndLoad()
    }
```

`showAuthor` in the grid call: pass `showAuthor = scope !is BrowseScope.Mine` (Mine hides the author column, matching old MySchematicsPanel). Verify `CommunityDetailPanel.show(...)`'s exact parameter type by reading `CommunityDetailPanel.kt` (it is called today from `CommunitiesPanel`); match that signature. Add imports for `Icons`, `Widgets`, `CommunityDetailPanel`, `BrowseScope`.

- [ ] **Step 6: Build the module + run tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL — BrowseScopeTest green, BrowsePanel compiles with the new selector.

- [ ] **Step 7: Commit (SKIP — no-commit mode).**

---

### Task 2: Retire `MySchematicsPanel`

Delete the redundant panel (Browse's Mine scope now covers it) and repoint every `MySchematicsPanel.invalidate()` caller to `BrowsePanel.invalidate()`.

**Files:**
- Delete: `.../ui/panels/MySchematicsPanel.kt`
- Modify: `.../ui/panels/SchematicDetailPanel.kt` (~line 542), `.../ui/panels/SchematicEditPanel.kt` (~line 273), `.../ui/panels/upload/UploadSubmit.kt` (~lines 14 import, 106, 211), `.../ui/panels/QuickShareCreatePanel.kt` (~line 44 comment), `.../ui/panels/BrowsePanel.kt` (~line 22 doc comment), `.../ui/panels/SchematicListView.kt` (~line 27 doc comment)

**Interfaces:**
- Consumes: `BrowsePanel.invalidate()` (unchanged public fun).
- Produces: nothing new; removes `MySchematicsPanel` from the codebase.

- [ ] **Step 1: Re-locate all references**

Run: `grep -rn 'MySchematicsPanel' fabric/src/client/kotlin/`
Expected: hits in Toolbar.kt (handled in Task 4, leave for now — but its import will break compile until Task 4; so ALSO neutralize Toolbar's use here: see Step 3), SchematicDetailPanel, SchematicEditPanel, UploadSubmit, QuickShareCreatePanel (comment), BrowsePanel (comment), SchematicListView (comment).

- [ ] **Step 2: Repoint invalidate() callers**

In `SchematicDetailPanel.kt`, `SchematicEditPanel.kt`, and `UploadSubmit.kt` (both call sites), replace `MySchematicsPanel.invalidate()` → `BrowsePanel.invalidate()`. Update the `import ...MySchematicsPanel` line in `UploadSubmit.kt` to `import ...BrowsePanel` (and remove any now-unused `MySchematicsPanel` import in the other two files). Update the doc-comment mentions in `QuickShareCreatePanel.kt`, `BrowsePanel.kt`, `SchematicListView.kt` to drop `MySchematicsPanel` (e.g. `[BrowsePanel]` only).

- [ ] **Step 3: Neutralize Toolbar's reference so the module still compiles**

In `Toolbar.kt`, temporarily remove the `import ...MySchematicsPanel` line and the `toolButton("My Schematics", ...)` line (Task 4 rebuilds the toolbar fully; removing this line now keeps the build green between tasks). Leave the rest of Toolbar for Task 4.

- [ ] **Step 4: Delete the panel**

Run: `rm fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/MySchematicsPanel.kt`

- [ ] **Step 5: Delete its test if one exists**

Run: `ls fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/panels/ | grep -i myschematics || echo none`
If a `MySchematicsPanelTest.kt` (or similar) exists, delete it. Expected: `none` (there was no dedicated test).

- [ ] **Step 6: Build + tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL — no unresolved `MySchematicsPanel` anywhere.

Verify: `grep -rn 'MySchematicsPanel' fabric/src/client/kotlin/ || echo CLEAN` → `CLEAN`.

- [ ] **Step 7: Commit (SKIP).**

---

### Task 3: Retire `CommunitiesPanel` as a standalone panel

Delete the top-level Communities list panel (Browse's Communities scope + Manage covers it) and repoint its `invalidate()` caller.

**Files:**
- Delete: `.../ui/panels/CommunitiesPanel.kt`
- Modify: `.../ui/panels/CommunityDetailPanel.kt` (~line 236 `CommunitiesPanel.invalidate()`), `.../ui/framework/Toolbar.kt` (remove its import + button, like Task 2 Step 3)

**Interfaces:**
- Consumes: `BrowsePanel.invalidate()`.
- Produces: removes `CommunitiesPanel`.

- [ ] **Step 1: Re-locate references**

Run: `grep -rn 'CommunitiesPanel' fabric/src/client/kotlin/`
Expected: Toolbar.kt (import + button), CommunityDetailPanel.kt:~236.

- [ ] **Step 2: Repoint the invalidate() caller**

In `CommunityDetailPanel.kt`, replace `CommunitiesPanel.invalidate()` → `BrowsePanel.invalidate()` (so after leaving a community, the Browse list refreshes). Add `import ...BrowsePanel` if absent; remove the `CommunitiesPanel` import.

- [ ] **Step 3: Neutralize Toolbar's reference**

In `Toolbar.kt`, remove the `import ...CommunitiesPanel` line and the `toolButton("Communities", ...)` line.

- [ ] **Step 4: Delete the panel**

Run: `rm fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/CommunitiesPanel.kt`

- [ ] **Step 5: Build + tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL. Verify: `grep -rn 'CommunitiesPanel' fabric/src/client/kotlin/ || echo CLEAN` → `CLEAN` (note: `CommunityDetailPanel` is a different name and stays — the grep for exactly `CommunitiesPanel` should be clean; use `grep -rn 'CommunitiesPanel\b'`).

- [ ] **Step 6: Commit (SKIP).**

---

### Task 4: Toolbar restructure — Browse/Upload + Share ▾ / Tools ▾ / ⚙

Rewrite `Toolbar.kt` to two top-level tools plus dropdown menus.

**Files:**
- Modify (full rewrite of `renderMenuBar` + helpers): `.../ui/framework/Toolbar.kt`
- Test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/framework/ToolbarShareGateTest.kt`

**Interfaces:**
- Consumes: `PanelManager.toggle/open/isOpen`, `BrowsePanel`/`UploadWizardPanel`/`SharesPanel`/`QuickShareCreatePanel`/`SettingsPanel`, `Icons`, `Widgets`, `ServerIpc.canUploadClipboard()` (existing, returns Boolean), `ServerIpc.sendUploadClipboard()` (existing), `DockHost.resetLayout()` (Task 5 — call it; if Task 5 not yet done, this reference must still compile: Task 5 adds `resetLayout()`; order Task 5 BEFORE this task if executing strictly, OR stub is not allowed — SEE ordering note below).
- Produces: same public surface `Toolbar.renderMenuBar()`; new pure `Toolbar.shareUploadVisible(canUpload: Boolean): Boolean` (returns `canUpload`) so the Share-item gate is unit-testable without ImGui.

**Ordering note:** This task calls `DockHost.resetLayout()` (Task 5). Execute **Task 5 before Task 4**, or if executing in listed order, have Task 5's `resetLayout()` already present. The subagent-driven controller should dispatch Task 5 first, then Task 4. (Tasks 1–3 are independent of 4/5.)

- [ ] **Step 1: Write the failing test** — `ToolbarShareGateTest.kt`:

```kotlin
package io.schemat.connector.fabric.client.ui.framework

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ToolbarShareGateTest {
    // The "Upload clipboard to server" Share item is shown iff canUploadClipboard() is true.
    @Test
    fun `share upload item visibility mirrors canUploadClipboard`() {
        assertEquals(true, Toolbar.shareUploadVisible(canUpload = true))
        assertEquals(false, Toolbar.shareUploadVisible(canUpload = false))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test --tests '*ToolbarShareGateTest*'`
Expected: FAIL — unresolved `Toolbar.shareUploadVisible`.

- [ ] **Step 3: Rewrite `Toolbar.kt`**

```kotlin
package io.schemat.connector.fabric.client.ui.framework

import imgui.ImGui
import imgui.flag.ImGuiHoveredFlags
import io.schemat.connector.fabric.client.ipc.ServerIpc
import io.schemat.connector.fabric.client.ui.theme.Fonts
import io.schemat.connector.fabric.client.ui.theme.Icons
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.withFont
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.QuickShareCreatePanel
import io.schemat.connector.fabric.client.ui.panels.SettingsPanel
import io.schemat.connector.fabric.client.ui.panels.SharesPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel

/**
 * The Schematio toolbar, rendered inline as the [DockHost] top menu bar. Two top-level
 * tools (Browse, Upload) plus grouped dropdown menus (Share, Tools) and a gear. Vanilla
 * dropdowns via [ImGui.beginMenu].
 */
object Toolbar {

    /** Pure gate used by the Share menu (and the test): show "Upload clipboard" iff [canUpload]. */
    fun shareUploadVisible(canUpload: Boolean): Boolean = canUpload

    fun renderMenuBar() {
        wordmark()
        ImGui.textDisabled("|")

        toolButton("Browse", Icons.SEARCH, BrowsePanel.id) { PanelManager.toggle(BrowsePanel) }
        toolButton("Upload", Icons.UPLOAD, UploadWizardPanel.id) {
            if (PanelManager.isOpen(UploadWizardPanel.id)) PanelManager.close(UploadWizardPanel.id)
            else UploadWizardPanel.open()
        }

        ImGui.textDisabled("|")

        // --- Share ▾ ---
        if (ImGui.beginMenu("${Icons.SHARE}  Share")) {
            if (ImGui.menuItem("${Icons.SHARE}  My Quick Shares")) PanelManager.toggle(SharesPanel)
            if (ImGui.menuItem("${Icons.BOLT}  New Quick Share")) QuickShareCreatePanel.show(null)
            if (shareUploadVisible(ServerIpc.canUploadClipboard())) {
                if (ImGui.menuItem("${Icons.UPLOAD}  Upload clipboard to server")) {
                    ServerIpc.sendUploadClipboard()
                }
            }
            ImGui.endMenu()
        }

        // --- Tools ▾ (future tools + layout reset) ---
        if (ImGui.beginMenu("${Icons.CUBE}  Tools")) {
            ImGui.beginDisabled()
            ImGui.menuItem("${Icons.CODE_BRANCH}  Version Control")
            ImGui.menuItem("${Icons.DIAGRAM}  Flow")
            ImGui.endDisabled()
            if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) ImGui.setTooltip("coming soon")
            ImGui.separator()
            if (ImGui.menuItem("${Icons.REFRESH}  Reset layout")) DockHost.resetLayout()
            ImGui.endMenu()
        }

        // --- Gear (Settings) ---
        if (ImGui.menuItem("${Icons.GEAR}")) PanelManager.toggle(SettingsPanel)
    }

    private fun wordmark() {
        ImGui.textColored(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
            Icons.CUBE,
        )
        ImGui.sameLine(0f, 6f)
        withFont(Fonts.SEMIBOLD) { ImGui.text("Schematio") }
    }

    private fun toolButton(label: String, icon: String, windowId: String, open: () -> Unit) {
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0f, 0f, 0f, 0f)
        ImGui.pushStyleColor(
            imgui.flag.ImGuiCol.ButtonHovered,
            ImGuiColors.SURFACE_HOVER.x, ImGuiColors.SURFACE_HOVER.y, ImGuiColors.SURFACE_HOVER.z, ImGuiColors.SURFACE_HOVER.w,
        )
        ImGui.pushStyleColor(
            imgui.flag.ImGuiCol.ButtonActive,
            ImGuiColors.ACCENT_DIM.x, ImGuiColors.ACCENT_DIM.y, ImGuiColors.ACCENT_DIM.z, ImGuiColors.ACCENT_DIM.w,
        )
        val clicked = ImGui.button("$icon  $label")
        ImGui.popStyleColor(3)
        if (PanelManager.isOpen(windowId)) {
            val minX = ImGui.getItemRectMinX(); val maxX = ImGui.getItemRectMaxX(); val maxY = ImGui.getItemRectMaxY()
            val col = ImGui.getColorU32(ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, 1f)
            ImGui.getWindowDrawList().addRectFilled(minX, maxY - 2f, maxX, maxY, col)
        }
        if (clicked) open()
    }
}
```

Note: verify `ServerIpc.canUploadClipboard()` and `ServerIpc.sendUploadClipboard()` exact names by reading `ipc/ServerIpc.kt` (canUploadClipboard confirmed at ~line 162–166; sendUploadClipboard is the C11 sender — match its actual signature; if it needs no args, call as shown). Verify `QuickShareCreatePanel.show(null)` and `UploadWizardPanel.open()`/`SharesPanel`/`SettingsPanel` ids exist (they do). If `SharesPanel` is toggled by object not id elsewhere, keep the object form `PanelManager.toggle(SharesPanel)`.

- [ ] **Step 4: Run to verify pass + full module compile**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL, ToolbarShareGateTest green, no `My Schematics`/`Communities`/duplicate `Quick Share` buttons remain.

- [ ] **Step 5: Commit (SKIP).**

---

### Task 5: Default dock layout + reset (`DockHost.kt`)

Build a Browse-docked-right default with the DockBuilder API on a fresh layout, and a `resetLayout()`. **Execute before Task 4** (Task 4 calls `resetLayout()`).

**Files:**
- Modify: `.../ui/framework/DockHost.kt`

**Interfaces:**
- Consumes: `ImGui.getID(DOCKSPACE_ID)`, `imgui.internal.ImGui` DockBuilder statics (confirmed present in imgui-java 1.89.0: `dockBuilderGetNode(int): ImGuiDockNode`, `dockBuilderRemoveNode(int)`, `dockBuilderAddNode(int, int): int`, `dockBuilderSetNodeSize(int, float, float)`, `dockBuilderSplitNode(int, int, float, ImInt, ImInt): int`, `dockBuilderDockWindow(String, int)`, `dockBuilderFinish(int)`), `imgui.flag.ImGuiDir.Right`, the Browse window title `"Browse###browse"`.
- Produces: `DockHost.resetLayout()` (sets a `pendingLayoutRebuild` flag consumed next frame); a one-time default build when no layout exists.

- [ ] **Step 1: Add the default-layout builder + reset to `DockHost`**

Read `DockHost.kt` first (the `render()` that calls `ImGui.dockSpace(dockId, 0f, 0f, PassthruCentralNode or NoDockingInCentralNode)` ~line 81). Add, and call `maybeBuildDefaultLayout(dockId)` immediately AFTER the `ImGui.dockSpace(...)` call each frame:

```kotlin
    // Set true by resetLayout(); also implicitly true on first run (no saved node).
    private var pendingLayoutRebuild = false

    /** Tools ▾ → "Reset layout": rebuild the default dock arrangement next frame. */
    fun resetLayout() { pendingLayoutRebuild = true }

    /**
     * Builds the default layout — Browse docked to a right split (~38%), central node left
     * transparent/pass-through — when there is no saved layout, or when [resetLayout] asked.
     * Uses imgui internal DockBuilder. Central node is never docked into (game stays visible).
     */
    private fun maybeBuildDefaultLayout(dockId: Int) {
        val noSavedLayout = imgui.internal.ImGui.dockBuilderGetNode(dockId).ptr == 0L
        if (!pendingLayoutRebuild && !noSavedLayout) return
        pendingLayoutRebuild = false

        val io = ImGui.getIO()
        imgui.internal.ImGui.dockBuilderRemoveNode(dockId)
        imgui.internal.ImGui.dockBuilderAddNode(dockId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace)
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockId, io.displaySizeX, io.displaySizeY)

        val rightId = imgui.type.ImInt()
        val centralId = imgui.type.ImInt()
        // split off the RIGHT 38% as the tool dock; the remainder (central) stays pass-through.
        imgui.internal.ImGui.dockBuilderSplitNode(dockId, imgui.flag.ImGuiDir.Right, 0.38f, rightId, centralId)

        imgui.internal.ImGui.dockBuilderDockWindow("Browse###browse", rightId.get())
        imgui.internal.ImGui.dockBuilderFinish(dockId)
    }
```

Call site (inside `render()`, right after the existing `ImGui.dockSpace(...)`):

```kotlin
        maybeBuildDefaultLayout(dockId)
```

API adaptation: verify the exact package/enum for `ImGuiDockNodeFlags.DockSpace` and `ImGuiDockNode.ptr` against imgui-java 1.89.0 (javap `imgui.internal.ImGui` and `imgui.internal.ImGuiDockNode`). If `dockBuilderGetNode` returns a non-null object whose emptiness is checked differently (e.g. an `isNotNull`/`ptr != 0` accessor), use that; the intent is "no saved node → build default". If `ImGuiDockNodeFlags.DockSpace` is unavailable, use `dockBuilderAddNode(dockId)` (no-flag overload, also present) and set size — the split still works.

- [ ] **Step 2: Compile the module**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL (no unit test for DockHost — it needs a live ImGui context; correctness is the Task 6 visual pass). If any DockBuilder symbol is unresolved, adapt per the javap output and re-run.

- [ ] **Step 3: Commit (SKIP).**

---

### Task 6: Integration checkpoint + visual QA

**Files:** none (verification only).

- [ ] **Step 1: Full multi-version build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build`
Expected: BUILD SUCCESSFUL across all stonecutter versions (1.21.8–26.2). Failures here mean an API used in a new file differs on another MC version — none expected (UI code is version-agnostic).

- [ ] **Step 2: Reference sweep**

Run: `grep -rn 'MySchematicsPanel\|CommunitiesPanel\b' fabric/src/client/kotlin/ || echo CLEAN`
Expected: `CLEAN`.

- [ ] **Step 3: Visual QA (run-paper / `:fabric:1.21.11:runClient`)** — checklist:
  - Toolbar shows exactly: wordmark · Browse · Upload · Share ▾ · Tools ▾ · ⚙. No My Schematics / Communities / duplicate Quick Share buttons.
  - Browse: scope selector `All · Mine · Communities ▾`; All shows public, Mine shows your schematics (no author column), Communities ▾ lists your communities → picking one shows its schematics; "Manage" appears only in a Community scope and opens the community detail (members/leave) unchanged.
  - Share ▾: My Quick Shares, New Quick Share, and — only when connected to an attested modded server — "Upload clipboard to server".
  - Tools ▾: Version Control / Flow disabled with "coming soon"; "Reset layout" present.
  - ⚙ opens Settings.
  - First open (delete/rename `schematioconnector-imgui.ini` to simulate fresh): Browse auto-docks to the right ~38%, game visible and clickable in the center-left; other panels tab into the right dock.
  - "Reset layout" after manually dragging panels around restores the Browse-right default.
  - Toggle overlay off → game fully clean.

- [ ] **Step 4: Report** results against the checklist; flag any visual misses as follow-ups rather than improvising layout changes.
