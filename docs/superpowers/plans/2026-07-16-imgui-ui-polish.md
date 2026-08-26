# ImGui UI/UX Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove every "default Dear ImGui" tell from the Fabric client overlay: full palette coverage, typography scale (Inter SemiBold H1/H2), Font Awesome icons, softer geometry, and a small widget kit — per the spec at `docs/superpowers/specs/2026-07-16-imgui-ui-polish-design.md`.

**Architecture:** `Theme.kt` stays the single ARGB source of truth → `ImGuiColors.kt` translates to `ImVec4` → `ImGuiTheme.kt` becomes a data-driven map covering every `ImGuiCol` slot (reflection-tested). Fonts/icons are baked once into the shared atlas in `ImGuiManager.loadFonts` (our custom GL3 renderer owns the atlas texture — single `io.fonts.build()` call site). Widgets grow into a small kit; panels adopt 1:1 only.

**Tech Stack:** Kotlin, imgui-java 1.89.0 (`io.github.spair`), Fabric + Stonecutter (versions 1.21.8–26.2), JUnit 5.

## Global Constraints

- **Do NOT `git commit` unless Harrison has explicitly approved committing** (his standing workflow rule). At execution start, ask once whether to commit per-task; if declined, leave the tree dirty and skip every Commit step. Commit messages below are for when approved.
- Test command (from repo root): `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test` (add `--tests '*Name*'` to scope). Expected baseline: currently green.
- All UI code lives in `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/`; tests in `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/`; font assets in `fabric/src/client/resources/assets/schematioconnector/fonts/`. Paths below are repo-relative.
- No raw hex colors outside `Theme.kt`. No `ImVec4` literals in widgets — reference `ImGuiColors`.
- `ImGuiTheme.apply()`/`unapply()` push/pop counts MUST stay balanced (imbalance corrupts all subsequent ImGui rendering).
- `DockingEmptyBg` MUST be fully transparent — the pass-through central dockspace node is how the game stays visible/clickable.
- Branch: `feature/ingame-diff-viewer` (already checked out at `~/IdeaProjects/SchematioConnector`).
- `curl`/`wget` are intercepted in this environment — the download task uses a sandboxed `fetch` (snippet provided) + `unzip`.
- File:line references are as of plan-writing; verify with the given grep before editing.

---

### Task 1: Theme derived shades + ImGuiColors additions

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/Theme.kt` (palette block, after `SCRIM` at ~line 73)
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/ImGuiColors.kt`
- Test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/theme/ImGuiColorsTest.kt`

**Interfaces:**
- Consumes: existing `Theme` ARGB consts, `argbToImVec4`.
- Produces: `Theme.SURFACE_RAISED`, `Theme.ACCENT_MUTED`, `Theme.STRIPE`, `Theme.SCRIM_SOFT` (Int ARGB); `ImGuiColors.SURFACE_RAISED/ACCENT_MUTED/STRIPE/SCRIM_SOFT/TRANSPARENT` (ImVec4); `ImGuiColors.lerp(a: ImVec4, b: ImVec4, t: Float): ImVec4`.

- [ ] **Step 1: Write failing tests** — append to `ImGuiColorsTest.kt`:

```kotlin
    @Test
    fun `ACCENT_MUTED is accent at 25 percent alpha`() =
        assertVec(
            floatArrayOf(0xDB / 255f, 0x45 / 255f, 0xF0 / 255f, 0x40 / 255f),
            ImGuiColors.ACCENT_MUTED, "accentMuted"
        )

    @Test
    fun `STRIPE is 3 percent white`() =
        assertVec(floatArrayOf(1f, 1f, 1f, 0x08 / 255f), ImGuiColors.STRIPE, "stripe")

    @Test
    fun `TRANSPARENT is all zero`() =
        assertVec(floatArrayOf(0f, 0f, 0f, 0f), ImGuiColors.TRANSPARENT, "transparent")

    @Test
    fun `lerp midpoint blends channels`() {
        val a = ImVec4(0f, 0f, 0f, 0f)
        val b = ImVec4(1f, 1f, 1f, 1f)
        assertVec(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), ImGuiColors.lerp(a, b, 0.5f), "lerpMid")
    }

    @Test
    fun `lerp clamps t`() {
        val a = ImVec4(0f, 0f, 0f, 1f)
        val b = ImVec4(1f, 1f, 1f, 1f)
        assertVec(floatArrayOf(1f, 1f, 1f, 1f), ImGuiColors.lerp(a, b, 2f), "lerpClamp")
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*ImGuiColorsTest*'`
Expected: FAIL — unresolved references `ACCENT_MUTED`, `STRIPE`, `TRANSPARENT`, `lerp`.

- [ ] **Step 3: Implement** — in `Theme.kt`, insert after the `SCRIM` const (keep the existing comment style):

```kotlin
    /** Raised surface - active tabs, focused title bars (between ALT and HOVER). */
    const val SURFACE_RAISED = 0xFF21212A.toInt()
    /** Accent wash at ~25% alpha - text selection, docking preview. */
    const val ACCENT_MUTED = 0x40DB45F0
    /** 3% white - table row striping. */
    const val STRIPE = 0x08FFFFFF
    /** Soft dim behind modals (game stays faintly visible). */
    const val SCRIM_SOFT = 0xA00A0A0C.toInt()
```

In `ImGuiColors.kt`, add inside the object (after `SCRIM`) and a top-level import of nothing new (`ImVec4` already imported):

```kotlin
    val SURFACE_RAISED = argbToImVec4(Theme.SURFACE_RAISED)
    val ACCENT_MUTED   = argbToImVec4(Theme.ACCENT_MUTED)
    val STRIPE         = argbToImVec4(Theme.STRIPE)
    val SCRIM_SOFT     = argbToImVec4(Theme.SCRIM_SOFT)
    val TRANSPARENT    = ImVec4(0f, 0f, 0f, 0f)

    /** Per-channel linear blend of [a] toward [b] by [t] (clamped 0..1). */
    fun lerp(a: ImVec4, b: ImVec4, t: Float): ImVec4 {
        val tt = t.coerceIn(0f, 1f)
        return ImVec4(
            a.x + (b.x - a.x) * tt,
            a.y + (b.y - a.y) * tt,
            a.z + (b.z - a.z) * tt,
            a.w + (b.w - a.w) * tt,
        )
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*ImGuiColorsTest*'`
Expected: PASS (all tests).

- [ ] **Step 5: Commit (if approved)**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/Theme.kt fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/ImGuiColors.kt fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/theme/ImGuiColorsTest.kt
git commit -m "feat(ui): derived theme shades (raised/muted/stripe/soft-scrim) + ImVec4 lerp"
```

---

### Task 2: Icons.kt — Font Awesome codepoints + glyph ranges

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/Icons.kt`
- Test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/theme/IconsTest.kt`

**Interfaces:**
- Produces: `Icons.<NAME>: String` constants (single PUA codepoint each), `Icons.ALL: List<String>`, `Icons.GLYPH_RANGES: ShortArray` (imgui-java format: `[lo1,hi1,lo2,hi2,...,0]`).

- [ ] **Step 1: Write failing test** — `IconsTest.kt`:

```kotlin
package io.schemat.connector.fabric.client.ui.theme

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class IconsTest {

    @Test
    fun `all icons are single codepoints in the PUA range`() {
        assertTrue(Icons.ALL.isNotEmpty())
        for (icon in Icons.ALL) {
            assertEquals(1, icon.codePointCount(0, icon.length), "icon '$icon' must be one codepoint")
            val cp = icon.codePointAt(0)
            assertTrue(cp in 0xE000..0xF8FF, "codepoint 0x${cp.toString(16)} outside PUA")
        }
    }

    @Test
    fun `icons are distinct`() =
        assertEquals(Icons.ALL.size, Icons.ALL.toSet().size)

    @Test
    fun `glyph ranges are pair-per-icon, ascending, zero-terminated`() {
        val r = Icons.GLYPH_RANGES
        assertEquals(Icons.ALL.size * 2 + 1, r.size)
        assertEquals(0.toShort(), r.last())
        val cps = Icons.ALL.map { it.codePointAt(0) }.sorted()
        cps.forEachIndexed { i, cp ->
            assertEquals(cp.toShort(), r[i * 2], "lo of pair $i")
            assertEquals(cp.toShort(), r[i * 2 + 1], "hi of pair $i")
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*IconsTest*'`
Expected: FAIL — unresolved reference `Icons`.

- [ ] **Step 3: Implement** — `Icons.kt`:

```kotlin
package io.schemat.connector.fabric.client.ui.theme

/**
 * Curated Font Awesome 6 Free Solid glyphs (v6.7.2 — codepoints are stable within FA6;
 * re-verify this table if the bundled ttf is ever swapped).
 *
 * Each constant is a single PUA codepoint rendered by the icon font merged into the
 * 18px text faces (see Fonts). Usage: string concat — `"${Icons.UPLOAD}  Upload"`.
 * [GLYPH_RANGES] limits atlas rasterization to exactly these glyphs.
 */
object Icons {
    const val SEARCH        = "\uf002"  // magnifying-glass
    const val FOLDER        = "\uf07b"
    const val USERS         = "\uf0c0"
    const val SHARE         = "\uf1e0"  // share-nodes
    const val UPLOAD        = "\uf093"
    const val BOLT          = "\uf0e7"
    const val GEAR          = "\uf013"
    const val CODE_BRANCH   = "\uf126"
    const val DIAGRAM       = "\uf542"  // diagram-project
    const val XMARK         = "\uf00d"
    const val REFRESH       = "\uf021"  // arrows-rotate
    const val TRASH         = "\uf1f8"
    const val TAG           = "\uf02b"
    const val CHECK         = "\uf00c"
    const val CHEVRON_LEFT  = "\uf053"
    const val CHEVRON_RIGHT = "\uf054"
    const val CHEVRON_UP    = "\uf077"
    const val CHEVRON_DOWN  = "\uf078"
    const val EXTERNAL      = "\uf35d"  // up-right-from-square
    const val WARNING       = "\uf071"  // triangle-exclamation
    const val INFO_CIRCLE   = "\uf05a"
    const val CHECK_CIRCLE  = "\uf058"
    const val XMARK_CIRCLE  = "\uf057"
    const val DOWNLOAD      = "\uf019"
    const val EYE           = "\uf06e"
    const val CUBE          = "\uf1b2"
    const val PEN           = "\uf304"
    const val COPY          = "\uf0c5"
    const val GLOBE         = "\uf0ac"
    const val CLOCK         = "\uf017"
    const val USER          = "\uf007"
    const val HEART         = "\uf004"

    val ALL: List<String> = listOf(
        SEARCH, FOLDER, USERS, SHARE, UPLOAD, BOLT, GEAR, CODE_BRANCH, DIAGRAM,
        XMARK, REFRESH, TRASH, TAG, CHECK, CHEVRON_LEFT, CHEVRON_RIGHT, CHEVRON_UP,
        CHEVRON_DOWN, EXTERNAL, WARNING, INFO_CIRCLE, CHECK_CIRCLE, XMARK_CIRCLE,
        DOWNLOAD, EYE, CUBE, PEN, COPY, GLOBE, CLOCK, USER, HEART,
    )

    /**
     * imgui-java glyph ranges: flat shorts `[lo1, hi1, lo2, hi2, ..., 0]`.
     * One degenerate (lo==hi) pair per icon, ascending, zero-terminated — keeps the
     * atlas to exactly the glyphs above instead of all ~1400 FA solid icons.
     */
    val GLYPH_RANGES: ShortArray = run {
        val cps = ALL.map { it.codePointAt(0) }.sorted()
        ShortArray(cps.size * 2 + 1).also { out ->
            cps.forEachIndexed { i, cp ->
                out[i * 2] = cp.toShort()
                out[i * 2 + 1] = cp.toShort()
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*IconsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit (if approved)**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/Icons.kt fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/theme/IconsTest.kt
git commit -m "feat(ui): curated FA6 icon codepoints + atlas glyph ranges"
```

---

### Task 3: Font assets (Inter SemiBold + FA solid) + presence test

**Files:**
- Create: `fabric/src/client/resources/assets/schematioconnector/fonts/Inter-SemiBold.ttf`
- Create: `fabric/src/client/resources/assets/schematioconnector/fonts/fa-solid-900.ttf`
- Create: `fabric/src/client/resources/assets/schematioconnector/fonts/LICENSE-Inter.txt`
- Create: `fabric/src/client/resources/assets/schematioconnector/fonts/LICENSE-FontAwesome.txt`
- Test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/theme/FontAssetsTest.kt`

**Interfaces:**
- Produces: classpath resources `/assets/schematioconnector/fonts/Inter-SemiBold.ttf` and `/assets/schematioconnector/fonts/fa-solid-900.ttf` (consumed by Task 4).

- [ ] **Step 1: Write failing test** — `FontAssetsTest.kt`:

```kotlin
package io.schemat.connector.fabric.client.ui.theme

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FontAssetsTest {

    private fun resourceBytes(path: String): ByteArray? =
        javaClass.getResourceAsStream(path)?.readBytes()

    @Test
    fun `Inter SemiBold is bundled and non-trivial`() {
        val bytes = resourceBytes("/assets/schematioconnector/fonts/Inter-SemiBold.ttf")
        assertNotNull(bytes, "Inter-SemiBold.ttf missing from client resources")
        assertTrue(bytes!!.size > 100_000, "suspiciously small ttf: ${bytes.size} bytes")
    }

    @Test
    fun `FA solid is bundled and non-trivial`() {
        val bytes = resourceBytes("/assets/schematioconnector/fonts/fa-solid-900.ttf")
        assertNotNull(bytes, "fa-solid-900.ttf missing from client resources")
        assertTrue(bytes!!.size > 100_000, "suspiciously small ttf: ${bytes.size} bytes")
    }
}
```

Note: `Inter-Regular.ttf` already loads via this exact classpath at runtime, and the test source set sees client classes — if `getResourceAsStream` returns null for BOTH files after Step 3, the client `resources` dir isn't on the test classpath; in that case add to `fabric/build.gradle.kts` inside the existing `tasks.test` block: `classpath += files(sourceSets["client"].resources.srcDirs)`.

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*FontAssetsTest*'`
Expected: FAIL — both assertNotNull assertions.

- [ ] **Step 3: Download the fonts.** `curl`/`wget` are blocked in this environment; fetch the zips in the sandbox, then unzip with plain shell. Run via `ctx_execute(language: "javascript")`:

```javascript
const dl = async (url, path) => {
  const r = await fetch(url, { redirect: "follow" });
  if (!r.ok) throw new Error(`${url} -> ${r.status}`);
  const buf = await r.arrayBuffer();
  await Bun.write(path, buf);
  console.log(path, buf.byteLength, "bytes");
};
const tmp = "/tmp/ui-fonts";
await Bun.$`mkdir -p ${tmp}`;
await dl("https://use.fontawesome.com/releases/v6.7.2/fontawesome-free-6.7.2-web.zip", `${tmp}/fa.zip`);
await dl("https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip", `${tmp}/inter.zip`);
```

Then in shell (Bash is fine, short output):

```bash
FONTS=fabric/src/client/resources/assets/schematioconnector/fonts
cd ~/IdeaProjects/SchematioConnector
unzip -o -j /tmp/ui-fonts/fa.zip 'fontawesome-free-6.7.2-web/webfonts/fa-solid-900.ttf' -d "$FONTS"
unzip -o -j /tmp/ui-fonts/fa.zip 'fontawesome-free-6.7.2-web/LICENSE.txt' -d /tmp/ui-fonts && mv /tmp/ui-fonts/LICENSE.txt "$FONTS/LICENSE-FontAwesome.txt"
unzip -l /tmp/ui-fonts/inter.zip | grep -i 'SemiBold.ttf'   # find the exact member path first
unzip -o -j /tmp/ui-fonts/inter.zip 'extras/ttf/Inter-SemiBold.ttf' -d "$FONTS"   # adjust member path to the grep hit (want static Inter-SemiBold.ttf — NOT Italic, NOT InterDisplay, NOT Variable)
unzip -o -j /tmp/ui-fonts/inter.zip 'LICENSE.txt' -d /tmp/ui-fonts && mv /tmp/ui-fonts/LICENSE.txt "$FONTS/LICENSE-Inter.txt"
file "$FONTS"/*.ttf   # each must report "TrueType Font data"
```

If a URL 404s (release renamed), any FA 6.x `fa-solid-900.ttf` and any Inter 4.x static `Inter-SemiBold.ttf` are acceptable — record the actual version in the license filename or a comment.

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*FontAssetsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit (if approved)**

```bash
git add fabric/src/client/resources/assets/schematioconnector/fonts fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/theme/FontAssetsTest.kt
git commit -m "feat(ui): bundle Inter SemiBold + FA6 solid fonts with licenses"
```

---

### Task 4: Fonts.kt + atlas build in ImGuiManager

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/Fonts.kt`
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/framework/ImGuiManager.kt:82-87` (`loadFonts`)

**Interfaces:**
- Consumes: `Icons.GLYPH_RANGES` (Task 2), bundled ttfs (Task 3).
- Produces: `Fonts.BODY / SEMIBOLD / H2 / H1: ImFont?` (null until `Fonts.load` runs — widget code must tolerate null so JVM unit tests never crash); `Fonts.load(io: ImGuiIO)`; `inline fun <T> withFont(font: ImFont?, block: () -> T): T` (top-level in Fonts.kt).

- [ ] **Step 1: Implement `Fonts.kt`** (no unit test — requires a live ImGui context; compile is verified by the test task, behavior by the Task 11 visual pass):

```kotlin
package io.schemat.connector.fabric.client.ui.theme

import imgui.ImFont
import imgui.ImFontConfig
import imgui.ImGui
import imgui.ImGuiIO

/**
 * Font atlas faces. [load] must be called exactly once, from ImGuiManager.initIfNeeded,
 * BEFORE the single io.fonts.build() — our custom GL3 renderer is the sole owner of the
 * atlas texture, so there must be no second build/setTexID call site.
 *
 * All refs stay null in headless/unit-test contexts; use [withFont], which no-ops on null.
 */
object Fonts {
    private const val DIR = "/assets/schematioconnector/fonts"

    /** Inter Regular 18 — body/default (icons merged). */
    var BODY: ImFont? = null; private set
    /** Inter SemiBold 18 — buttons, table headers, emphasis (icons merged). */
    var SEMIBOLD: ImFont? = null; private set
    /** Inter SemiBold 20 — section headers. */
    var H2: ImFont? = null; private set
    /** Inter SemiBold 24 — panel titles / hero rows. */
    var H1: ImFont? = null; private set

    fun load(io: ImGuiIO) {
        val regular = res("$DIR/Inter-Regular.ttf")
        val semibold = res("$DIR/Inter-SemiBold.ttf")
        val icons = res("$DIR/fa-solid-900.ttf")

        BODY = io.fonts.addFontFromMemoryTTF(regular, 18f)
        mergeIcons(io, icons, 18f)
        SEMIBOLD = io.fonts.addFontFromMemoryTTF(semibold, 18f)
        mergeIcons(io, icons, 18f)
        H2 = io.fonts.addFontFromMemoryTTF(semibold, 20f)
        H1 = io.fonts.addFontFromMemoryTTF(semibold, 24f)
        io.fonts.build()
    }

    /** Merge FA glyphs (only [Icons.GLYPH_RANGES]) into the most recently added font. */
    private fun mergeIcons(io: ImGuiIO, bytes: ByteArray, size: Float) {
        val cfg = ImFontConfig()
        cfg.mergeMode = true
        cfg.pixelSnapH = true
        cfg.glyphMinAdvanceX = size // keeps icons monospace-ish so labels align
        io.fonts.addFontFromMemoryTTF(bytes, size, cfg, Icons.GLYPH_RANGES)
        cfg.destroy()
    }

    private fun res(path: String): ByteArray =
        Fonts::class.java.getResourceAsStream(path)?.readBytes()
            ?: error("Missing bundled font: $path")
}

/** Push [font] for [block]; no-op when null (headless tests, load failure). */
inline fun <T> withFont(font: ImFont?, block: () -> T): T {
    if (font != null) ImGui.pushFont(font)
    try {
        return block()
    } finally {
        if (font != null) ImGui.popFont()
    }
}
```

- [ ] **Step 2: Rewire `ImGuiManager`** — replace the whole `loadFonts` function (lines 82–87) with:

```kotlin
    private fun loadFonts(io: ImGuiIO) {
        io.fonts.clear()
        Fonts.load(io)
    }
```

Add import `io.schemat.connector.fabric.client.ui.theme.Fonts`. Delete the now-unused `FONT_PATH` and `BASE_FONT_SIZE` constants (lines 28–29).

- [ ] **Step 3: Compile + full test suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit (if approved)**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/Fonts.kt fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/framework/ImGuiManager.kt
git commit -m "feat(ui): four-face font atlas (Inter Regular/SemiBold 18/20/24) with merged FA icons"
```

---

### Task 5: ImGuiTheme — full data-driven color table + geometry

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/ImGuiTheme.kt` (full rewrite of the object)
- Test: create `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/theme/ImGuiThemeTest.kt`

**Interfaces:**
- Consumes: `ImGuiColors` incl. Task 1 additions.
- Produces: `ImGuiTheme.COLORS: Map<Int, ImVec4>` (every `ImGuiCol` slot), `ImGuiTheme.VAR2 / VAR1` lists, unchanged `apply()`/`unapply()`/`withStandardTable` signatures, new `fun windowTitleAccent()`.

- [ ] **Step 1: Write failing test** — `ImGuiThemeTest.kt`:

```kotlin
package io.schemat.connector.fabric.client.ui.theme

import imgui.flag.ImGuiCol
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.lang.reflect.Modifier

class ImGuiThemeTest {

    private fun allImGuiColSlots(): Set<Int> =
        ImGuiCol::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name != "COUNT" }
            .map { it.getInt(null) }
            .toSet()

    @Test
    fun `every ImGuiCol slot has an explicit color`() {
        val slots = allImGuiColSlots()
        val missing = slots - ImGuiTheme.COLORS.keys
        val extra = ImGuiTheme.COLORS.keys - slots
        assertTrue(missing.isEmpty(), "unmapped ImGuiCol slots: $missing")
        assertTrue(extra.isEmpty(), "unknown slots in COLORS: $extra")
    }

    @Test
    fun `docking empty bg stays fully transparent`() {
        val c = ImGuiTheme.COLORS.getValue(ImGuiCol.DockingEmptyBg)
        assertEquals(0f, c.w, 0.0001f, "DockingEmptyBg alpha must be 0 (pass-through central node)")
    }

    @Test
    fun `style var lists are non-empty and slot-unique`() {
        val slots = ImGuiTheme.VAR2.map { it.first } + ImGuiTheme.VAR1.map { it.first }
        assertTrue(slots.isNotEmpty())
        assertEquals(slots.size, slots.toSet().size, "duplicate style var slot")
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*ImGuiThemeTest*'`
Expected: FAIL — unresolved `COLORS`/`VAR2`/`VAR1`.

- [ ] **Step 3: Rewrite `ImGuiTheme.kt`** (keep package + `withStandardTable` semantics; `imgui.ImVec4`, `imgui.flag.ImGuiFocusedFlags` need importing):

```kotlin
package io.schemat.connector.fabric.client.ui.theme

import imgui.ImGui
import imgui.ImVec4
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiTableFlags

/**
 * Pushes the SchematioConnector design palette and spacing scale onto ImGui's style stacks.
 *
 * Call [apply] once before rendering a frame/window and [unapply] in the matching finally
 * block. Push counts equal [COLORS].size / [VAR2].size+[VAR1].size, so the pair always
 * balances — an imbalanced stack corrupts all subsequent ImGui rendering.
 *
 * EVERY ImGuiCol slot is mapped (reflection-enforced by ImGuiThemeTest) so no default
 * Dear-ImGui grey-blue ever renders. Accent rule: fuchsia only as interactive emphasis
 * (primary buttons, active underline, checks/sliders, selection) — never a surface.
 */
object ImGuiTheme {

    /** Every ImGuiCol slot → palette color. LinkedHashMap: push order is stable. */
    val COLORS: Map<Int, ImVec4> = linkedMapOf(
        ImGuiCol.Text                  to ImGuiColors.TEXT_PRIMARY,
        ImGuiCol.TextDisabled          to ImGuiColors.TEXT_MUTED,
        ImGuiCol.WindowBg              to ImGuiColors.BG,
        ImGuiCol.ChildBg               to ImGuiColors.SURFACE,
        ImGuiCol.PopupBg               to ImGuiColors.SURFACE_ALT,
        ImGuiCol.Border                to ImGuiColors.BORDER_SUBTLE,
        ImGuiCol.BorderShadow          to ImGuiColors.TRANSPARENT,
        ImGuiCol.FrameBg               to ImGuiColors.SURFACE_ALT,
        ImGuiCol.FrameBgHovered        to ImGuiColors.SURFACE_HOVER,
        ImGuiCol.FrameBgActive         to ImGuiColors.SURFACE_HOVER,
        ImGuiCol.TitleBg               to ImGuiColors.SURFACE,
        ImGuiCol.TitleBgActive         to ImGuiColors.SURFACE_RAISED,
        ImGuiCol.TitleBgCollapsed      to ImGuiColors.SURFACE,
        ImGuiCol.MenuBarBg             to ImGuiColors.SURFACE,
        ImGuiCol.ScrollbarBg           to ImGuiColors.TRANSPARENT,
        ImGuiCol.ScrollbarGrab         to ImGuiColors.BORDER,
        ImGuiCol.ScrollbarGrabHovered  to ImGuiColors.TEXT_FAINT,
        ImGuiCol.ScrollbarGrabActive   to ImGuiColors.TEXT_MUTED,
        ImGuiCol.CheckMark             to ImGuiColors.ACCENT,
        ImGuiCol.SliderGrab            to ImGuiColors.ACCENT,
        ImGuiCol.SliderGrabActive      to ImGuiColors.ACCENT_HOVER,
        ImGuiCol.Button                to ImGuiColors.SURFACE_ALT,
        ImGuiCol.ButtonHovered         to ImGuiColors.SURFACE_HOVER,
        ImGuiCol.ButtonActive          to ImGuiColors.ACCENT_DIM,
        ImGuiCol.Header                to ImGuiColors.ACCENT_DIM,
        ImGuiCol.HeaderHovered         to ImGuiColors.SURFACE_HOVER,
        ImGuiCol.HeaderActive          to ImGuiColors.ACCENT_DIM,
        ImGuiCol.Separator             to ImGuiColors.BORDER_SUBTLE,
        ImGuiCol.SeparatorHovered      to ImGuiColors.ACCENT_DIM,
        ImGuiCol.SeparatorActive       to ImGuiColors.ACCENT,
        ImGuiCol.ResizeGrip            to ImGuiColors.TRANSPARENT,
        ImGuiCol.ResizeGripHovered     to ImGuiColors.ACCENT_DIM,
        ImGuiCol.ResizeGripActive      to ImGuiColors.ACCENT,
        ImGuiCol.Tab                   to ImGuiColors.SURFACE,
        ImGuiCol.TabHovered            to ImGuiColors.SURFACE_HOVER,
        ImGuiCol.TabActive             to ImGuiColors.SURFACE_RAISED,
        ImGuiCol.TabUnfocused          to ImGuiColors.SURFACE,
        ImGuiCol.TabUnfocusedActive    to ImGuiColors.SURFACE_ALT,
        ImGuiCol.DockingPreview        to ImGuiColors.ACCENT_MUTED,
        ImGuiCol.DockingEmptyBg        to ImGuiColors.TRANSPARENT,
        ImGuiCol.PlotLines             to ImGuiColors.ACCENT,
        ImGuiCol.PlotLinesHovered      to ImGuiColors.ACCENT_HOVER,
        ImGuiCol.PlotHistogram         to ImGuiColors.ACCENT,
        ImGuiCol.PlotHistogramHovered  to ImGuiColors.ACCENT_HOVER,
        ImGuiCol.TableHeaderBg         to ImGuiColors.SURFACE_ALT,
        ImGuiCol.TableBorderStrong     to ImGuiColors.BORDER,
        ImGuiCol.TableBorderLight      to ImGuiColors.BORDER_SUBTLE,
        ImGuiCol.TableRowBg            to ImGuiColors.TRANSPARENT,
        ImGuiCol.TableRowBgAlt         to ImGuiColors.STRIPE,
        ImGuiCol.TextSelectedBg        to ImGuiColors.ACCENT_MUTED,
        ImGuiCol.DragDropTarget        to ImGuiColors.ACCENT,
        ImGuiCol.NavHighlight          to ImGuiColors.ACCENT,
        ImGuiCol.NavWindowingHighlight to ImGuiColors.ACCENT,
        ImGuiCol.NavWindowingDimBg     to ImGuiColors.SCRIM_SOFT,
        ImGuiCol.ModalWindowDimBg      to ImGuiColors.SCRIM_SOFT,
    )

    /** Two-component style vars (slot, x, y). */
    val VAR2: List<Triple<Int, Float, Float>> = listOf(
        Triple(ImGuiStyleVar.WindowPadding,    14f, 14f),
        Triple(ImGuiStyleVar.FramePadding,     10f,  7f),
        Triple(ImGuiStyleVar.ItemSpacing,       8f,  8f),
        Triple(ImGuiStyleVar.ItemInnerSpacing,  6f,  6f),
        Triple(ImGuiStyleVar.CellPadding,       8f,  5f),
    )

    /** One-component style vars (slot, value). */
    val VAR1: List<Pair<Int, Float>> = listOf(
        ImGuiStyleVar.WindowRounding    to 8f,
        ImGuiStyleVar.ChildRounding     to 6f,
        ImGuiStyleVar.FrameRounding     to 5f,
        ImGuiStyleVar.PopupRounding     to 6f,
        ImGuiStyleVar.ScrollbarRounding to 12f,
        ImGuiStyleVar.GrabRounding      to 5f,
        ImGuiStyleVar.TabRounding       to 5f,
        ImGuiStyleVar.ScrollbarSize     to 10f,
        ImGuiStyleVar.GrabMinSize       to 10f,
        ImGuiStyleVar.WindowBorderSize  to 1f,
        ImGuiStyleVar.ChildBorderSize   to 1f,
        ImGuiStyleVar.PopupBorderSize   to 1f,
        // Frame borders OFF globally (flat fills); text inputs re-enable locally
        // via Widgets.textField.
        ImGuiStyleVar.FrameBorderSize   to 0f,
    )

    fun apply() {
        for ((slot, c) in COLORS) ImGui.pushStyleColor(slot, c.x, c.y, c.z, c.w)
        for ((slot, x, y) in VAR2) ImGui.pushStyleVar(slot, x, y)
        for ((slot, v) in VAR1) ImGui.pushStyleVar(slot, v)
    }

    /** Pops exactly as many colors and vars as [apply] pushed. Call in a finally block. */
    fun unapply() {
        ImGui.popStyleVar(VAR2.size + VAR1.size)
        ImGui.popStyleColor(COLORS.size)
    }

    /**
     * 2px accent underline across the title bar of the current window — the brand cue
     * mirroring the site's active-tab inset shadow. Call immediately after a successful
     * ImGui.begin(). No-op when docked (the tab replaces the title bar) or unfocused.
     */
    fun windowTitleAccent() {
        if (ImGui.isWindowDocked() || !ImGui.isWindowFocused()) return
        val x = ImGui.getWindowPosX()
        val y = ImGui.getWindowPosY()
        val w = ImGui.getWindowWidth()
        val titleH = ImGui.getFrameHeight()
        val col = ImGui.getColorU32(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, 1f,
        )
        ImGui.getWindowDrawList().addRectFilled(x, y + titleH - 2f, x + w, y + titleH, col)
    }

    /**
     * The only approved table entry point for SchematioConnector ImGui UIs.
     * RowBg striping comes from TableRowBg/TableRowBgAlt; horizontal inner borders only.
     */
    inline fun withStandardTable(id: String, columns: Int, block: () -> Unit) {
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH or ImGuiTableFlags.ScrollY
        if (ImGui.beginTable(id, columns, flags)) {
            try {
                block()
            } finally {
                ImGui.endTable()
            }
        }
    }
}
```

Note: if the reflection test reports `missing`/`extra` slots (imgui-java 1.89.0's exact `ImGuiCol` field set is authoritative), add/remove entries until it's exact — sensible defaults: text-ish slots → TEXT_*, bg-ish → SURFACE_*, interactive-active → ACCENT variants.

- [ ] **Step 4: Run to verify pass, then full suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test`
Expected: PASS (ImGuiThemeTest + everything else).

- [ ] **Step 5: Commit (if approved)**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/ImGuiTheme.kt fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/theme/ImGuiThemeTest.kt
git commit -m "feat(ui): full ImGuiCol coverage + soft geometry; reflection-tested theme table"
```

---

### Task 6: Anim — hover fade helper

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/widgets/Anim.kt`
- Test: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/widgets/AnimTest.kt`

**Interfaces:**
- Produces: `Anim.peek(id: Int): Float`, `Anim.advance(id: Int, target: Boolean, dt: Float, speed: Float = 8f): Float`, `Anim.clear()`, `Anim.size(): Int`.

- [ ] **Step 1: Write failing test** — `AnimTest.kt`:

```kotlin
package io.schemat.connector.fabric.client.ui.widgets

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AnimTest {

    @BeforeEach
    fun reset() = Anim.clear()

    @Test
    fun `rises toward 1 while target true`() {
        val a = Anim.advance(1, true, dt = 0.05f) // 0.4 at speed 8
        val b = Anim.advance(1, true, dt = 0.05f) // 0.8
        assertEquals(0.4f, a, 0.001f)
        assertEquals(0.8f, b, 0.001f)
        assertEquals(1f, Anim.advance(1, true, dt = 1f), 0.001f) // clamped
    }

    @Test
    fun `falls toward 0 while target false and evicts at rest`() {
        Anim.advance(1, true, dt = 1f)            // -> 1
        Anim.advance(1, false, dt = 0.05f)        // -> 0.6
        assertEquals(0.6f, Anim.peek(1), 0.001f)
        Anim.advance(1, false, dt = 1f)           // -> 0, evicted
        assertEquals(0f, Anim.peek(1), 0.001f)
        assertEquals(0, Anim.size())
    }

    @Test
    fun `peek of unknown id is 0 and does not allocate`() {
        assertEquals(0f, Anim.peek(42), 0.001f)
        assertEquals(0, Anim.size())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*AnimTest*'`
Expected: FAIL — unresolved `Anim`.

- [ ] **Step 3: Implement `Anim.kt`:**

```kotlin
package io.schemat.connector.fabric.client.ui.widgets

/**
 * Per-widget hover-fade state for custom widgets (~125ms full fade at the default speed).
 *
 * Immediate-mode pattern: a widget reads [peek] (last frame's value) to pick its colors
 * BEFORE drawing, then calls [advance] with this frame's hover state AFTER drawing.
 * Entries are evicted once fully at rest at 0, so the map stays bounded by the number
 * of currently/recently hovered widgets. Render-thread only — no synchronization.
 */
object Anim {
    private val values = HashMap<Int, Float>()

    fun peek(id: Int): Float = values[id] ?: 0f

    fun advance(id: Int, target: Boolean, dt: Float, speed: Float = 8f): Float {
        val cur = values[id] ?: 0f
        val goal = if (target) 1f else 0f
        val step = (speed * dt).coerceAtLeast(0f)
        val next = (cur + (goal - cur).coerceIn(-step, step)).coerceIn(0f, 1f)
        if (next == 0f && !target) values.remove(id) else values[id] = next
        return next
    }

    fun clear() = values.clear()

    fun size(): Int = values.size
}
```

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*AnimTest*'`
Expected: PASS.

- [ ] **Step 5: Commit (if approved)**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/widgets/Anim.kt fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/widgets/AnimTest.kt
git commit -m "feat(ui): Anim hover-fade helper for custom widgets"
```

---

### Task 7: Widgets kit expansion

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/widgets/Widgets.kt`

**Interfaces:**
- Consumes: `ImGuiColors` (+`lerp`, Task 1), `Fonts`/`withFont` (Task 4), `Icons` (Task 2), `Anim` (Task 6).
- Produces (all in `Widgets`): `primaryButton(label, width = 0f): Boolean`, `secondaryButton(label, width = 0f): Boolean`, `ghostButton(label): Boolean`, `dangerButton(label, width = 0f): Boolean`, `iconButton(icon, tooltip = null): Boolean`, `h1(text)`, `h2(text)`, `sectionHeader(text)`, `badge(text, tone: Tone)`, `emptyState(icon, title, hint = null)`, `kvRow(label, value)`, `enum class Tone`. Existing `button(label, accent)` kept as a thin alias (panels keep compiling); `textField` gains its local frame border; `statusText` gains a leading icon.

- [ ] **Step 1: Implement.** Replace `Widgets.kt` content with (existing `tabBar` and `StatusKind` semantics preserved):

```kotlin
package io.schemat.connector.fabric.client.ui.widgets

import imgui.ImGui
import imgui.ImVec4
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiStyleVar
import imgui.type.ImString
import io.schemat.connector.fabric.client.ui.theme.Fonts
import io.schemat.connector.fabric.client.ui.theme.Icons
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.withFont

/**
 * The SchematioConnector ImGui widget kit — thin themed helpers over native Dear ImGui.
 *
 * All colors reference [ImGuiColors] — no raw hex or numeric ImVec4 literals here.
 * Buttons: primary (accent fill) for THE action of a view, secondary for everything
 * else, ghost for inline/low-emphasis, danger for destructive. Hover states on
 * primary/danger fade via [Anim]; stock widgets keep instant hover.
 */
object Widgets {

    enum class Tone { SUCCESS, WARNING, DANGER, INFO, NEUTRAL }

    // ------------------------------------------------------------------ buttons

    /** Accent-filled call-to-action. SemiBold white label, hover fade. */
    fun primaryButton(label: String, width: Float = 0f): Boolean =
        fadingButton(label, width, ImGuiColors.ACCENT, ImGuiColors.ACCENT_HOVER, ImGuiColors.ACCENT_DIM)

    /** Destructive action button. */
    fun dangerButton(label: String, width: Float = 0f): Boolean =
        fadingButton(
            label, width,
            ImGuiColors.DANGER,
            ImGuiColors.lerp(ImGuiColors.DANGER, ImGuiColors.TEXT_PRIMARY, 0.2f),
            ImGuiColors.lerp(ImGuiColors.DANGER, ImGuiColors.BG, 0.35f),
        )

    private fun fadingButton(label: String, width: Float, base: ImVec4, hover: ImVec4, active: ImVec4): Boolean {
        val id = ImGui.getID(label)
        val bg = ImGuiColors.lerp(base, hover, Anim.peek(id))
        ImGui.pushStyleColor(ImGuiCol.Button, bg.x, bg.y, bg.z, bg.w)
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, bg.x, bg.y, bg.z, bg.w)
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, active.x, active.y, active.z, active.w)
        ImGui.pushStyleColor(ImGuiCol.Text, 1f, 1f, 1f, 1f)
        val clicked = withFont(Fonts.SEMIBOLD) {
            if (width > 0f) ImGui.button(label, width, 0f) else ImGui.button(label)
        }
        ImGui.popStyleColor(4)
        Anim.advance(id, ImGui.isItemHovered(), ImGui.getIO().deltaTime)
        return clicked
    }

    /** Neutral surface button with a subtle border — the default choice. */
    fun secondaryButton(label: String, width: Float = 0f): Boolean {
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 1f)
        ImGui.pushStyleColor(
            ImGuiCol.Border,
            ImGuiColors.BORDER.x, ImGuiColors.BORDER.y, ImGuiColors.BORDER.z, ImGuiColors.BORDER.w,
        )
        val clicked = if (width > 0f) ImGui.button(label, width, 0f) else ImGui.button(label)
        ImGui.popStyleColor(1)
        ImGui.popStyleVar(1)
        return clicked
    }

    /** Frameless low-emphasis button: transparent at rest, faint surface on hover. */
    fun ghostButton(label: String): Boolean {
        ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f)
        ImGui.pushStyleColor(
            ImGuiCol.ButtonHovered,
            ImGuiColors.SURFACE_HOVER.x, ImGuiColors.SURFACE_HOVER.y,
            ImGuiColors.SURFACE_HOVER.z, ImGuiColors.SURFACE_HOVER.w,
        )
        ImGui.pushStyleColor(
            ImGuiCol.ButtonActive,
            ImGuiColors.ACCENT_DIM.x, ImGuiColors.ACCENT_DIM.y,
            ImGuiColors.ACCENT_DIM.z, ImGuiColors.ACCENT_DIM.w,
        )
        val clicked = ImGui.button(label)
        ImGui.popStyleColor(3)
        return clicked
    }

    /** Square frameless icon button with optional tooltip. */
    fun iconButton(icon: String, tooltip: String? = null): Boolean {
        val clicked = ghostButton(icon)
        if (tooltip != null && ImGui.isItemHovered()) ImGui.setTooltip(tooltip)
        return clicked
    }

    /**
     * Back-compat alias (pre-kit API): accent=true → [primaryButton], else [secondaryButton].
     * New code should call those directly.
     */
    fun button(label: String, accent: Boolean = false): Boolean =
        if (accent) primaryButton(label) else secondaryButton(label)

    // ------------------------------------------------------------------ typography

    /** Panel title / hero text (Inter SemiBold 24). */
    fun h1(text: String) = withFont(Fonts.H1) { ImGui.text(text) }

    /** Section heading (Inter SemiBold 20). */
    fun h2(text: String) = withFont(Fonts.H2) { ImGui.text(text) }

    /** H2 heading with breathing room above and below — use between panel sections. */
    fun sectionHeader(text: String) {
        ImGui.spacing()
        h2(text)
        ImGui.spacing()
    }

    /** Muted `label: value` row with the value column aligned at [valueX]. */
    fun kvRow(label: String, value: String, valueX: Float = 150f) {
        ImGui.textColored(
            ImGuiColors.TEXT_FAINT.x, ImGuiColors.TEXT_FAINT.y,
            ImGuiColors.TEXT_FAINT.z, ImGuiColors.TEXT_FAINT.w, label,
        )
        ImGui.sameLine(valueX)
        ImGui.text(value)
    }

    // ------------------------------------------------------------------ status & structure

    /** Rounded tinted pill (draw-list) — role/status chips. */
    fun badge(text: String, tone: Tone) {
        val c = toneColor(tone)
        val padX = 7f
        val padY = 2f
        val size = ImGui.calcTextSize(text)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val w = size.x + padX * 2
        val h = size.y + padY * 2
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + w, y + h, ImGui.getColorU32(c.x, c.y, c.z, 0.16f), h / 2f)
        dl.addRect(x, y, x + w, y + h, ImGui.getColorU32(c.x, c.y, c.z, 0.5f), h / 2f)
        dl.addText(x + padX, y + padY, ImGui.getColorU32(c.x, c.y, c.z, 1f), text)
        ImGui.dummy(w, h)
    }

    /** Centered icon + title (+ optional hint) filling the remaining region — zero states. */
    fun emptyState(icon: String, title: String, hint: String? = null) {
        val availX = ImGui.getContentRegionAvailX()
        val availY = ImGui.getContentRegionAvailY()
        val lineH = ImGui.getTextLineHeightWithSpacing()
        val blockH = lineH * (if (hint != null) 3 else 2)
        if (availY > blockH) ImGui.dummy(0f, (availY - blockH) / 2f)

        fun centered(text: String, c: ImVec4) {
            val w = ImGui.calcTextSize(text).x
            ImGui.setCursorPosX(ImGui.getCursorPosX() + ((availX - w) / 2f).coerceAtLeast(0f))
            ImGui.textColored(c.x, c.y, c.z, c.w, text)
        }
        centered(icon, ImGuiColors.TEXT_FAINT)
        withFont(Fonts.SEMIBOLD) { centered(title, ImGuiColors.TEXT_MUTED) }
        if (hint != null) centered(hint, ImGuiColors.TEXT_FAINT)
    }

    /** Status severity used by [statusText] (pre-kit name kept for call sites). */
    enum class StatusKind { SUCCESS, DANGER, WARNING, INFO }

    /** Colored status line with a matching leading icon. */
    fun statusText(text: String, kind: StatusKind) {
        val (c, icon) = when (kind) {
            StatusKind.SUCCESS -> ImGuiColors.SUCCESS to Icons.CHECK_CIRCLE
            StatusKind.DANGER  -> ImGuiColors.DANGER to Icons.XMARK_CIRCLE
            StatusKind.WARNING -> ImGuiColors.WARNING to Icons.WARNING
            StatusKind.INFO    -> ImGuiColors.INFO to Icons.INFO_CIRCLE
        }
        ImGui.textColored(c.x, c.y, c.z, c.w, "$icon  $text")
    }

    private fun toneColor(tone: Tone): ImVec4 = when (tone) {
        Tone.SUCCESS -> ImGuiColors.SUCCESS
        Tone.WARNING -> ImGuiColors.WARNING
        Tone.DANGER  -> ImGuiColors.DANGER
        Tone.INFO    -> ImGuiColors.INFO
        Tone.NEUTRAL -> ImGuiColors.TEXT_MUTED
    }

    // ------------------------------------------------------------------ inputs (pre-kit, kept)

    /**
     * Single-line text input. Inputs are the one control that keeps a visible frame
     * border (FrameBorderSize is 0 globally) — pushed locally here.
     */
    fun textField(label: String, state: ImString, hint: String? = null): Boolean {
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 1f)
        ImGui.pushStyleColor(
            ImGuiCol.Border,
            ImGuiColors.BORDER.x, ImGuiColors.BORDER.y, ImGuiColors.BORDER.z, ImGuiColors.BORDER.w,
        )
        val changed = if (hint != null) {
            ImGui.inputTextWithHint(label, hint, state)
        } else {
            ImGui.inputText(label, state)
        }
        ImGui.popStyleColor(1)
        ImGui.popStyleVar(1)
        return changed
    }

    /** Tab bar helper (unchanged semantics: end* only called after successful begin*). */
    fun tabBar(id: String, tabs: List<Pair<String, () -> Unit>>) {
        if (ImGui.beginTabBar(id)) {
            for ((title, content) in tabs) {
                if (ImGui.beginTabItem(title)) {
                    content()
                    ImGui.endTabItem()
                }
            }
            ImGui.endTabBar()
        }
    }
}
```

API note (imgui-java 1.89.0): if `getCursorScreenPosX/Y`, `getContentRegionAvailX/Y`, or `calcTextSize(...)` differ in signature, the vector-returning variants (`getCursorScreenPos(): ImVec2` etc.) are always present — adapt the call, keep the logic.

- [ ] **Step 2: Compile + full suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test`
Expected: BUILD SUCCESSFUL, all green (panels still compile via the `button` alias).

- [ ] **Step 3: Commit (if approved)**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/widgets/Widgets.kt
git commit -m "feat(ui): widget kit — primary/secondary/ghost/danger buttons, h1/h2, badges, empty states"
```

---

### Task 8: Toolbar — wordmark, icons, open-underline

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/framework/Toolbar.kt` (full rewrite below)

**Interfaces:**
- Consumes: `Icons`, `Fonts`/`withFont`, `ImGuiColors`, `Widgets.ghostButton` pattern (colors pushed inline here), `PanelManager` (unchanged).
- Produces: same public surface — `Toolbar.renderMenuBar()`.

- [ ] **Step 1: Rewrite `Toolbar.kt`:**

```kotlin
package io.schemat.connector.fabric.client.ui.framework

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiHoveredFlags
import io.schemat.connector.fabric.client.ui.theme.Fonts
import io.schemat.connector.fabric.client.ui.theme.Icons
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.withFont
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.CommunitiesPanel
import io.schemat.connector.fabric.client.ui.panels.MySchematicsPanel
import io.schemat.connector.fabric.client.ui.panels.QuickShareCreatePanel
import io.schemat.connector.fabric.client.ui.panels.SettingsPanel
import io.schemat.connector.fabric.client.ui.panels.SharesPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel

/**
 * The persistent Schematio toolbar, rendered INLINE as the [DockHost] top menu bar
 * (the caller wraps this in `beginMenuBar()`/`endMenuBar()`).
 *
 * Left: accent wordmark. Then icon+label buttons that TOGGLE each tool window via
 * [PanelManager] — transparent at rest, surface on hover, with a 2px accent underline
 * while their window is open (quieter than a filled accent button). "Version Control"
 * and "Flow" remain disabled placeholders.
 */
object Toolbar {

    fun renderMenuBar() {
        wordmark()
        ImGui.textDisabled("|")

        toolButton("Browse", Icons.SEARCH, BrowsePanel.id) { PanelManager.toggle(BrowsePanel) }
        toolButton("My Schematics", Icons.FOLDER, MySchematicsPanel.id) { PanelManager.toggle(MySchematicsPanel) }
        toolButton("Communities", Icons.USERS, CommunitiesPanel.id) { PanelManager.toggle(CommunitiesPanel) }
        toolButton("Quick Shares", Icons.SHARE, SharesPanel.id) { PanelManager.toggle(SharesPanel) }
        toolButton("Upload", Icons.UPLOAD, UploadWizardPanel.id) {
            if (PanelManager.isOpen(UploadWizardPanel.id)) PanelManager.close(UploadWizardPanel.id)
            else UploadWizardPanel.open()
        }
        toolButton("Quick Share", Icons.BOLT, QuickShareCreatePanel.id) {
            if (PanelManager.isOpen(QuickShareCreatePanel.id)) PanelManager.close(QuickShareCreatePanel.id)
            else QuickShareCreatePanel.show(null)
        }
        toolButton("Settings", Icons.GEAR, SettingsPanel.id) { PanelManager.toggle(SettingsPanel) }

        ImGui.textDisabled("|")

        placeholderButton("Version Control", Icons.CODE_BRANCH)
        placeholderButton("Flow", Icons.DIAGRAM)
    }

    /** Accent cube + SemiBold "Schematio" — the brand mark anchoring the bar. */
    private fun wordmark() {
        ImGui.textColored(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
            Icons.CUBE,
        )
        ImGui.sameLine(0f, 6f)
        withFont(Fonts.SEMIBOLD) { ImGui.text("Schematio") }
    }

    /**
     * Menu-bar tool button: transparent at rest so the bar reads as one surface;
     * a 2px accent underline (drawn under the item rect) marks the open state.
     */
    private fun toolButton(label: String, icon: String, windowId: String, open: () -> Unit) {
        ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f)
        ImGui.pushStyleColor(
            ImGuiCol.ButtonHovered,
            ImGuiColors.SURFACE_HOVER.x, ImGuiColors.SURFACE_HOVER.y,
            ImGuiColors.SURFACE_HOVER.z, ImGuiColors.SURFACE_HOVER.w,
        )
        ImGui.pushStyleColor(
            ImGuiCol.ButtonActive,
            ImGuiColors.ACCENT_DIM.x, ImGuiColors.ACCENT_DIM.y,
            ImGuiColors.ACCENT_DIM.z, ImGuiColors.ACCENT_DIM.w,
        )
        val clicked = ImGui.button("$icon  $label")
        ImGui.popStyleColor(3)

        if (PanelManager.isOpen(windowId)) {
            val minX = ImGui.getItemRectMinX()
            val maxX = ImGui.getItemRectMaxX()
            val maxY = ImGui.getItemRectMaxY()
            val col = ImGui.getColorU32(
                ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, 1f,
            )
            ImGui.getWindowDrawList().addRectFilled(minX, maxY - 2f, maxX, maxY, col)
        }
        if (clicked) open()
    }

    /** A greyed-out, no-op button advertising a tool that is not built yet. */
    private fun placeholderButton(label: String, icon: String) {
        ImGui.beginDisabled()
        ImGui.button("$icon  $label")
        ImGui.endDisabled()
        // AllowWhenDisabled: disabled items don't report hover by default, so the
        // "coming soon" tooltip would never show without the flag.
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("coming soon")
        }
    }
}
```

(Same API note as Task 7 for `getItemRectMinX`-style accessors vs `getItemRectMin(): ImVec2`.)

- [ ] **Step 2: Compile + full suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit (if approved)**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/framework/Toolbar.kt
git commit -m "feat(ui): toolbar wordmark + icon tools with accent open-underline"
```

---

### Task 9: ConfirmModal restyle

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/widgets/ConfirmModal.kt` (the `render()` body between `beginPopupModal` and `endPopup`, lines ~84–140)

**Interfaces:**
- Consumes: `Widgets.secondaryButton/primaryButton/dangerButton` (Task 7), `Icons`, `Fonts`/`withFont`.
- Produces: unchanged public API (`show`, `render`, `isOpen`).

- [ ] **Step 1: Restyle.** Replace the popup body (from the title `if (danger)` block through the confirm button, keeping `openPopup`/positioning/flags/`clearState` as-is) with:

```kotlin
        if (ImGui.beginPopupModal(POPUP_ID, flags)) {
            // Icon + H2 title row.
            val titleColor = if (danger) ImGuiColors.DANGER else ImGuiColors.TEXT_PRIMARY
            val titleIcon = if (danger) Icons.WARNING else Icons.INFO_CIRCLE
            ImGui.textColored(titleColor.x, titleColor.y, titleColor.z, titleColor.w, titleIcon)
            ImGui.sameLine(0f, 8f)
            withFont(Fonts.H2) {
                ImGui.textColored(titleColor.x, titleColor.y, titleColor.z, titleColor.w, title)
            }

            ImGui.spacing()
            ImGui.textWrapped(message)
            ImGui.spacing()
            ImGui.separator()
            ImGui.spacing()

            // Right-aligned action row: [Cancel] [Confirm].
            val btnW = 100f
            val total = btnW * 2 + ImGui.getStyle().itemSpacingX
            val startX = ImGui.getWindowWidth() - ImGui.getStyle().windowPaddingX - total
            if (startX > ImGui.getCursorPosX()) ImGui.setCursorPosX(startX)

            if (Widgets.secondaryButton("Cancel", width = btnW)) {
                clearState()
                ImGui.closeCurrentPopup()
            }
            ImGui.sameLine()
            val confirmed = if (danger) {
                Widgets.dangerButton(confirmLabel, width = btnW)
            } else {
                Widgets.primaryButton(confirmLabel, width = btnW)
            }
            if (confirmed) {
                val cb = onConfirm
                clearState()
                ImGui.closeCurrentPopup()
                cb?.invoke()
            }

            ImGui.endPopup()
        }
```

Add imports: `io.schemat.connector.fabric.client.ui.theme.Fonts`, `io.schemat.connector.fabric.client.ui.theme.Icons`, `io.schemat.connector.fabric.client.ui.theme.withFont`. Remove the now-unused `imgui.flag.ImGuiCol` import if nothing else in the file uses it.

- [ ] **Step 2: Compile + full suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit (if approved)**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/widgets/ConfirmModal.kt
git commit -m "feat(ui): confirm modal — icon + H2 title, right-aligned kit buttons"
```

---

### Task 10: Panel adoption — title accents + empty states (1:1 swaps only)

**Files:**
- Modify: the 11 panel files listed below (no layout changes).

**Interfaces:**
- Consumes: `ImGuiTheme.windowTitleAccent()` (Task 5), `Widgets.emptyState` + `Icons` (Tasks 2/7).

- [ ] **Step 1: Title accents.** Re-locate the begin sites with: `grep -rn 'ImGui.begin(' fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/`. For each of the 11 sites (pre-change lines): BrowsePanel.kt:43, MySchematicsPanel.kt:29, CommunitiesPanel.kt:53, SharesPanel.kt:55, SettingsPanel.kt:45, UploadWizardPanel.kt:199, SchematicDetailPanel.kt:135, CommunityDetailPanel.kt:273, PreviewComposerPanel.kt:96, QuickShareCreatePanel.kt:149, SchematicEditPanel.kt:118 —

For the `val expanded = ImGui.begin(...)` pattern, insert directly after:

```kotlin
        if (expanded) ImGuiTheme.windowTitleAccent()
```

For the `if (!ImGui.begin(...)) { ...; return }` pattern, insert directly after the closing brace of that `if`:

```kotlin
        ImGuiTheme.windowTitleAccent()
```

Add `import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme` where missing.

- [ ] **Step 2: Empty states.** Exact swaps (add `import io.schemat.connector.fabric.client.ui.theme.Icons` and `Widgets` import where missing):

`SharesPanel.kt:110`:
```kotlin
// before
ImGui.textDisabled("No quick shares — create one with New Share")
// after
Widgets.emptyState(Icons.SHARE, "No quick shares", "Create one with New Share")
```

`CommunityDetailPanel.kt:392`:
```kotlin
// before
ImGui.textDisabled("No members found.")
// after
Widgets.emptyState(Icons.USERS, "No members found")
```

`CommunityDetailPanel.kt:499`:
```kotlin
// before
ImGui.textDisabled("No schematics in this community yet.")
// after
Widgets.emptyState(Icons.CUBE, "No schematics yet", "Uploads tagged to this community will appear here")
```

`QuickShareCreatePanel.kt:181`:
```kotlin
// before
ImGui.textDisabled("No local sources found — put .litematic/.schem files in your schematics folder,")
// after
Widgets.emptyState(Icons.FOLDER, "No local sources found", "Put .litematic/.schem files in your schematics folder")
```
(If the original line continues onto a second `textDisabled` line, fold that text into the hint and delete it.)

Leave `SchematicEditPanel.kt:167` (`"No tags selected"`) and `PreviewComposerPanel.kt:159` (`"Preview unavailable (no GL texture)"`) as-is — they're inline captions inside dense layouts, not zero-state regions.

- [ ] **Step 3: Compile + full suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit (if approved)**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels
git commit -m "feat(ui): panel title accents + iconized empty states"
```

---

### Task 11: Full build + visual verification

- [ ] **Step 1: Full multi-version build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build`
Expected: BUILD SUCCESSFUL across all stonecutter versions (1.21.8–26.2) — the `//? if` preprocessed files are untouched by this work, so failures here mean an API-signature issue in new code.

- [ ] **Step 2: Visual pass** — Harrison already runs the client; otherwise `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:runClient`. Checklist:
  - Toolbar: wordmark renders (accent cube glyph — if you see `?`, the FA merge/glyph-range is wrong); icons on every tool; underline follows open panels.
  - Open each panel floating: rounded corners (8px), taller title bar, focused-window accent underline, no default-grey chrome anywhere.
  - Dock a panel to an edge: active tab styled, docking drag shows fuchsia translucent preview, **game still visible + clickable through the central area**.
  - Browse a long list: thin 10px rounded scrollbar, transparent track.
  - A table view (Quick Shares, Community members): SURFACE_ALT header, subtle row stripes, horizontal-only borders.
  - Confirm modal (e.g. delete flow): soft dim (game faintly visible), icon + H2 title, right-aligned buttons; danger variant red.
  - Typography: panel/section headers visibly heavier/larger; primary buttons SemiBold white on fuchsia with a smooth ~125ms hover fade.
  - Empty states: open Quick Shares with none — centered icon + title + hint.
  - Text input still shows its 1px border; keyboard entry works (charCallback path untouched).
  - Toggle overlay off: game fully clean (theme push/pop balanced — any residual tint means an imbalance).

- [ ] **Step 3: Report** — summarize results against this checklist; flag any visual misses for a follow-up polish round rather than improvising layout changes.
