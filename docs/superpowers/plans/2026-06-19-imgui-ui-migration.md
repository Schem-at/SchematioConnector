# ImGui UI Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the SchematioConnector Fabric client UI (vanilla `Screen`/`GuiGraphics`) with a pure-overlay Dear ImGui (imgui-java) UI that preserves the existing design language, as the foundation for the upcoming in-game schematic version-control feature.

**Architecture:** A single `ImGuiManager` initializes Dear ImGui against MC's GLFW window and renders each frame via a `GameRendererMixin` hook (pure overlay; no MC `Screen`). Input mixins block the game from mouse/keyboard while the overlay is focused and feed those events to ImGui. Each former `Screen` becomes a plain `Panel` that issues ImGui calls and consumes the unchanged `core/` API. The design language is ported from `Theme.kt` into an `ImGuiTheme`.

**Tech Stack:** Kotlin 2.0.21, Java 21, Fabric (MC 1.21.11, **official Mojang mappings** via `loom.officialMojangMappings()`), Fabric Loom, `io.github.spair:imgui-java` (binding + lwjgl3 + natives), LWJGL3 (provided by MC), JOML.

**Reference implementation:** `Leg0shii/ParkourCalculatorMod`, module `loader-fabric-1.21.10` (same author, same MC family). Its `fabric/imgui/ImGuiImpl.java`, `fabric/mixin/{GameRenderer,Mouse,Keyboard,MinecraftClient}Mixin.java` are the proven template for the integration logic. ⚠️ **The reference uses Yarn mappings; THIS project uses Mojang mappings** — every class/method name from the reference (and every code block below originally drafted in Yarn) MUST be translated to its Mojang name and compile-verified. See the mappings table in Global Constraints.

## Global Constraints

- **JDK 21 required:** `JAVA_HOME` MUST point at JDK 21 for every Gradle invocation (Gradle 8.14, Kotlin 2.0.21). Prefix build commands accordingly.
- **Mojang mappings** (MC 1.21.11, `loom.officialMojangMappings()`). The code blocks in Phase 1 were drafted from a Yarn reference — translate every name to Mojang and **compile-verify** (the reference's exact intermediary/method names are NOT authoritative for this project). Confirmed/expected translations (verify each against the linked MC sources via the compiler; do not trust this table blindly):
  | Yarn (reference) | Mojang (this project) |
  |---|---|
  | `net.minecraft.client.MinecraftClient` | `net.minecraft.client.Minecraft` |
  | `MinecraftClient.getInstance().currentScreen` | `Minecraft.getInstance().screen` |
  | `window.handle` (property) | `window.handle()` (method, on `com.mojang.blaze3d.platform.Window`) |
  | `net.minecraft.client.Mouse` | `net.minecraft.client.MouseHandler` |
  | `net.minecraft.client.Keyboard` | `net.minecraft.client.KeyboardHandler` |
  | `Mouse.lockCursor()` / `unlockCursor()` | `MouseHandler.grabMouse()` / `releaseMouse()` |
  | `RenderTickCounter` | `DeltaTracker` |
  | render hook target `GuiRenderer.incrementFrame()` | **uncertain in Mojang — prefer `TAIL` of `GameRenderer.render`** and verify |
  | `MouseInput` / `KeyInput` record params | verify these types/accessors exist under Mojang 1.21.11; the input-handler method signatures must be read from the actual decompiled `MouseHandler`/`KeyboardHandler` |
  The implementer for each mixin task MUST locate the real Mojang method name + descriptor (inspect linked/decompiled MC sources or use `loom genSources`) and confirm the mixin applies by compiling. A `@Inject` that fails to find its target throws at load time.
- **Client source set only:** all ImGui code lives under `fabric/src/client/kotlin/...` (and Java mixins under `fabric/src/client/java/...` if Kotlin mixins are not configured — see Task 3). `core/` and `bukkit/` are untouched.
- **Loom `include` is non-transitive:** every imgui-java jar — binding, lwjgl3 backend, AND each native classifier — MUST be listed explicitly in an `include implementation(...)`. A missing native compiles in dev and crashes only in a built jar. (Root cause of the v1.2.0 Jackson/JWT prod crash.)
- **Design language is fixed** by `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/theme/Theme.kt`. Accent `#db45f0`; backgrounds `#0a0a0c`/`#15151b`/`#1c1c24`; borders `#2a2a33`/`#1e1e26`; text `#ffffff`/`#b4b4c0`/`#8a8a96`/`#5e5e68`; status SUCCESS `#34d399` / DANGER `#f87171` / WARNING `#fbbf24` / INFO `#7ea8ff`; spacing `XS=4 SM=6 MD=8 LG=12 XL=16 XXL=24`; 1px borders, minimal rounding.
- **No inline color literals** in panel code once `ImGuiTheme` exists — all colors via `ImGuiTheme` (enforced by a Gradle check, Task 12).
- **Behavior parity:** ported panels must call the same `core/` API (`CachedSchematioApi`, models) and reproduce the existing screens' behavior 1:1. The current screen source files ARE the behavioral spec for each port.
- **Commit after every task.** Branch: `feature/imgui-ui-migration` (already created).

**Note on testing immediate-mode UI:** ImGui rendering cannot be meaningfully unit-tested. Tasks split into two kinds: **logic tasks** (pure functions / state — ARGB conversion, `PanelManager`, the Gradle check) use real TDD with failing tests first; **rendering tasks** use a concrete **manual in-game verification gate** with explicit observable pass criteria. Both are first-class; do not skip the manual gates.

---

## PHASE 1 — Integration spike (prove ImGui runs in the built jar)

### Task 1: Add imgui-java dependencies with explicit native bundling

**Files:**
- Modify: `gradle.properties` (add `imgui_version`)
- Modify: `fabric/build.gradle` or `fabric/build.gradle.kts` (dependencies block)

**Interfaces:**
- Produces: imgui-java binding, lwjgl3 backend, and natives on the client classpath AND bundled into the remapped jar via `include`.

- [ ] **Step 1: Pin the version.** Add to `gradle.properties`:

```properties
# Dear ImGui (imgui-java). 1.89.0 targets LWJGL 3.3.x, matching MC 1.21.11's bundled LWJGL.
# If the font atlas renders as garbage at runtime, keep the GL UNPACK normalization in ImGuiManager
# (Task 2) — older 1.86.x needs it; 1.89.x is generally safe but the normalization is harmless.
imgui_version=1.89.0
```

> Pin note: confirm 1.89.0 resolves against the LWJGL version MC 1.21.11 ships. If it fails to load natives at runtime, fall back to `1.86.11` (the reference's proven version) and keep the UNPACK normalization. This is the one value the spike validates.

- [ ] **Step 2: Add dependencies.** In `fabric/build.gradle(.kts)` dependencies block (Groovy shown; adapt to `.kts` if applicable):

```groovy
dependencies {
    // ... existing deps ...

    // Dear ImGui — client overlay UI. Loom `include` is NON-TRANSITIVE: binding, backend,
    // and EVERY native classifier must be listed explicitly or the built jar crashes at runtime.
    include implementation("io.github.spair:imgui-java-binding:${project.imgui_version}")
    include implementation("io.github.spair:imgui-java-lwjgl3:${project.imgui_version}")
    include implementation("io.github.spair:imgui-java-natives-windows:${project.imgui_version}")
    include implementation("io.github.spair:imgui-java-natives-linux:${project.imgui_version}")
    include implementation("io.github.spair:imgui-java-natives-macos:${project.imgui_version}")
}
```

- [ ] **Step 3: Verify resolution (dev).**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:dependencies --configuration runtimeClasspath | grep imgui`
Expected: all five `io.github.spair:imgui-java-*:1.89.0` artifacts listed.

- [ ] **Step 4: Verify natives are bundled in the built jar (the real gate).**

Run:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:remapJar
unzip -l fabric/build/libs/*.jar | grep -Ei 'imgui.*\.(so|dll|dylib)|imgui/api/ImGui'
```
Expected: at least one of each native (`.dll`, `.so`, `.dylib`) AND `imgui/ImGui.class` present inside the jar. If natives are missing, the `include` lines are wrong — fix before proceeding.

- [ ] **Step 5: Commit.**

```bash
git add gradle.properties fabric/build.gradle
git commit -m "build(fabric): add imgui-java with explicit native bundling"
```

---

### Task 2: ImGuiManager — context lifecycle, frame, fonts

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/imgui/ImGuiManager.kt`
- Create (resource): `fabric/src/client/resources/assets/schematioconnector/fonts/Inter-Regular.ttf` (bundled TTF; see Step 2)

**Interfaces:**
- Produces:
  - `ImGuiManager.initIfNeeded()` — idempotent; creates context, configures IO, loads fonts, inits GLFW+GL3 backends against MC's window handle.
  - `ImGuiManager.startFrame()` — `imGuiGlfw.newFrame(); ImGui.newFrame()` (+ off-screen mouse override when not focused).
  - `ImGuiManager.endFrame()` — `ImGui.render(); imGuiGl3.renderDrawData(ImGui.getDrawData())`.
  - `ImGuiManager.shutdown()` — disposes backends + context.
  - Static callback forwarders: `mouseButtonCallback`, `scrollCallback`, `keyCallback`, `charCallback` (delegate to `imGuiGlfw`).
- Consumes: MC window handle via `MinecraftClient.getInstance().window.handle` (Yarn: `Window.getHandle()`).

- [ ] **Step 1: Implement ImGuiManager.** (Adapted from reference `ImGuiImpl.java`.)

```kotlin
package io.schemat.connector.fabric.client.imgui

import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiConfigFlags
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import net.minecraft.client.MinecraftClient
import org.lwjgl.opengl.GL11C

object ImGuiManager {
    private val imGuiGlfw = ImGuiImplGlfw()
    private val imGuiGl3 = ImGuiImplGl3()
    private var initialized = false

    private const val INI_FILENAME = "schematioconnector-imgui.ini"
    private const val FONT_PATH = "/assets/schematioconnector/fonts/Inter-Regular.ttf"
    private const val BASE_FONT_SIZE = 18f

    fun initIfNeeded() {
        if (initialized) return
        val windowHandle = MinecraftClient.getInstance().window.handle

        ImGui.createContext()
        val io: ImGuiIO = ImGui.getIO()
        io.iniFilename = INI_FILENAME
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)
        // Docking/viewports intentionally OFF for now (overlay simplicity). Revisit for version-control UI.

        loadFonts(io)

        imGuiGlfw.init(windowHandle, false) // installCallbacks=false; mixins forward input (Task 4)
        // Some imgui-java native builds skip GL_UNPACK_* reset; MC's leftover pixel-store state can
        // scramble the font-atlas upload. Normalize to GL defaults before GL3 init uploads the atlas.
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4)
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0)
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0)
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0)
        imGuiGl3.init()

        initialized = true
    }

    private fun loadFonts(io: ImGuiIO) {
        val fontConfig = imgui.ImFontConfig()
        val bytes = ImGuiManager::class.java.getResourceAsStream(FONT_PATH)
            ?.readBytes() ?: error("Missing bundled font: $FONT_PATH")
        io.fonts.addFontFromMemoryTTF(bytes, BASE_FONT_SIZE)
        io.fonts.build()
        fontConfig.destroy()
    }

    fun startFrame(focused: Boolean) {
        imGuiGlfw.newFrame()
        ImGui.newFrame()
        if (!focused) {
            // imGuiGlfw feeds polled cursor pos during newFrame; override AFTER it so ImGui ignores the mouse.
            val io = ImGui.getIO()
            io.setMousePos(-Float.MAX_VALUE, -Float.MAX_VALUE)
            for (i in 0 until 5) io.setMouseDown(i, false)
        }
    }

    fun endFrame() {
        ImGui.render()
        imGuiGl3.renderDrawData(ImGui.getDrawData())
    }

    fun shutdown() {
        if (!initialized) return
        imGuiGl3.shutdown()
        imGuiGlfw.shutdown()
        ImGui.destroyContext()
        initialized = false
    }

    // Input forwarders, called from Mouse/Keyboard mixins (Task 4).
    fun mouseButtonCallback(window: Long, button: Int, action: Int, mods: Int) =
        imGuiGlfw.mouseButtonCallback(window, button, action, mods)
    fun scrollCallback(window: Long, xOffset: Double, yOffset: Double) =
        imGuiGlfw.scrollCallback(window, xOffset, yOffset)
    fun keyCallback(window: Long, key: Int, scancode: Int, action: Int, mods: Int) =
        imGuiGlfw.keyCallback(window, key, scancode, action, mods)
    fun charCallback(window: Long, codepoint: Int) =
        imGuiGlfw.charCallback(window, codepoint)
}
```

- [ ] **Step 2: Add the bundled font.** Place a permissively-licensed TTF at `fabric/src/client/resources/assets/schematioconnector/fonts/Inter-Regular.ttf` (Inter — OFL). Commit the binary with the task.

- [ ] **Step 3: Compile check.**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:compileClientKotlin`
Expected: BUILD SUCCESSFUL. (No runtime test yet — exercised in Task 5.)

- [ ] **Step 4: Commit.**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/imgui/ImGuiManager.kt \
        fabric/src/client/resources/assets/schematioconnector/fonts/Inter-Regular.ttf
git commit -m "feat(fabric): add ImGuiManager lifecycle + font loading"
```

---

### Task 3: Render hook via GameRendererMixin

**Files:**
- Create: `fabric/src/client/java/io/schemat/connector/fabric/client/mixin/GameRendererMixin.java`
- Create or modify: `fabric/src/client/resources/schematioconnector.client.mixins.json`
- Modify: `fabric/src/main/resources/fabric.mod.json` (register client mixin config if not already)

**Interfaces:**
- Consumes: `ImGuiOverlay.render()` (Task 5) — call site that runs all open panels between `startFrame`/`endFrame`.
- Produces: a per-frame call to `ImGuiOverlay.render()` after the HUD rasterizes, only when no MC screen is open (pure overlay).

> **Mixins are Java here.** The project's client source is Kotlin; mixins are simplest in Java. Create a `fabric/src/client/java/...` tree. If a client mixin JSON already exists, add to it instead of creating a second one.

- [ ] **Step 1: Create the mixin.** (Reference: `GameRendererMixin.java`, no-screen path.)

```java
package io.schemat.connector.fabric.client.mixin;

import io.schemat.connector.fabric.client.imgui.ImGuiOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    // After the GUI/HUD has rasterized this frame, draw ImGui on top. Pure overlay: only when no
    // MC Screen is open (we never use Screens for our UI).
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;incrementFrame()V",
            shift = At.Shift.AFTER
        )
    )
    private void schematio$onAfterGuiRendered(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ImGuiOverlay.render();
    }
}
```

> If the `GuiRenderer.incrementFrame()` target does not resolve under 1.21.11 Yarn, fall back to injecting at `TAIL` of `GameRenderer.render`. Verify the exact intermediary name with the reference module's compiled output.

- [ ] **Step 2: Register the mixin.** `fabric/src/client/resources/schematioconnector.client.mixins.json`:

```json
{
  "required": true,
  "package": "io.schemat.connector.fabric.client.mixin",
  "compatibilityLevel": "JAVA_21",
  "client": [
    "GameRendererMixin"
  ],
  "injectors": { "defaultRequire": 1 }
}
```

Ensure `fabric.mod.json` lists `"schematioconnector.client.mixins.json"` under `"mixins"` with `"environment": "client"`.

- [ ] **Step 3: Compile check.**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:compileClientJava :fabric:compileClientKotlin`
Expected: BUILD SUCCESSFUL (references `ImGuiOverlay`, created Task 5; if building this task in isolation, create an empty `ImGuiOverlay.render()` stub first, then flesh out in Task 5).

- [ ] **Step 4: Commit.**

```bash
git add fabric/src/client/java/io/schemat/connector/fabric/client/mixin/GameRendererMixin.java \
        fabric/src/client/resources/schematioconnector.client.mixins.json \
        fabric/src/main/resources/fabric.mod.json
git commit -m "feat(fabric): GameRendererMixin render hook for ImGui overlay"
```

---

### Task 4: Input capture mixins (mouse, keyboard, cursor lock)

**Files:**
- Create: `fabric/src/client/java/io/schemat/connector/fabric/client/mixin/MouseMixin.java`
- Create: `fabric/src/client/java/io/schemat/connector/fabric/client/mixin/KeyboardMixin.java`
- Create: `fabric/src/client/java/io/schemat/connector/fabric/client/mixin/MinecraftClientMixin.java`
- Modify: `schematioconnector.client.mixins.json` (register the three)

**Interfaces:**
- Consumes: `ImGuiOverlay.isFocused()` (Task 5) — global gate; `ImGuiManager.*Callback(...)` (Task 2).
- Produces: while focused, mouse/keyboard events are forwarded to ImGui and cancelled for MC; cursor stays free.

> **MC 1.21.x signatures** use `MouseInput`/`KeyInput` records. Confirm exact param names against the reference module; `input.button()`, `input.scancode()`, `input.modifiers()` are the accessors used there.

- [ ] **Step 1: MouseMixin.**

```java
package io.schemat.connector.fabric.client.mixin;

import io.schemat.connector.fabric.client.imgui.ImGuiManager;
import io.schemat.connector.fabric.client.imgui.ImGuiOverlay;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void schematio$onMouseButton(long window, MouseInput input, int action, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        ImGuiManager.INSTANCE.mouseButtonCallback(window, input.button(), action, input.modifiers());
        ci.cancel();
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void schematio$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        ImGuiManager.INSTANCE.scrollCallback(window, horizontal, vertical);
        ci.cancel();
    }

    // Stop camera look while overlay is focused.
    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void schematio$onUpdateMouse(CallbackInfo ci) {
        if (ImGuiOverlay.isFocused()) ci.cancel();
    }
}
```

> `ImGuiManager.INSTANCE` is the Kotlin `object` accessor from Java. Confirm scroll method name (`onMouseScroll`) against the reference.

- [ ] **Step 2: KeyboardMixin.**

```java
package io.schemat.connector.fabric.client.mixin;

import io.schemat.connector.fabric.client.imgui.ImGuiManager;
import io.schemat.connector.fabric.client.imgui.ImGuiOverlay;
import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void schematio$onKey(long window, int action, KeyInput input, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        ImGuiManager.INSTANCE.keyCallback(window, input.key(), input.scancode(), action, input.modifiers());
        ci.cancel();
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void schematio$onChar(long window, int codepoint, int modifiers, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        ImGuiManager.INSTANCE.charCallback(window, codepoint);
        ci.cancel();
    }
}
```

> Confirm `onChar` signature and `KeyInput.key()` accessor against the reference / 1.21.11 mappings.

- [ ] **Step 3: MinecraftClientMixin** — keep cursor free while focused.

```java
package io.schemat.connector.fabric.client.mixin;

import io.schemat.connector.fabric.client.imgui.ImGuiOverlay;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "lockCursor", at = @At("HEAD"), cancellable = true)
    private void schematio$onLockCursor(CallbackInfo ci) {
        if (ImGuiOverlay.isFocused()) ci.cancel();
    }
}
```

- [ ] **Step 4: Register all three** in `schematioconnector.client.mixins.json` `"client"` array: `"MouseMixin"`, `"KeyboardMixin"`, `"MinecraftClientMixin"`.

- [ ] **Step 5: Compile check.**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:compileClientJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add fabric/src/client/java/io/schemat/connector/fabric/client/mixin/*.java \
        fabric/src/client/resources/schematioconnector.client.mixins.json
git commit -m "feat(fabric): input-capture mixins for ImGui overlay focus"
```

---

### Task 5: ImGuiOverlay + keybind toggle + hello-window (spike gate)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/imgui/ImGuiOverlay.kt`
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/keybind/Keybinds.kt`

**Interfaces:**
- Produces:
  - `ImGuiOverlay.isFocused(): Boolean`
  - `ImGuiOverlay.toggle()` — flips focus; frees/locks cursor.
  - `ImGuiOverlay.render()` — `initIfNeeded()`, then if focused (or any panel open) `startFrame` → demo window → `endFrame`.
- Consumes: `ImGuiManager` (Task 2), called from `GameRendererMixin` (Task 3) and the input mixins (Task 4).

- [ ] **Step 1: Implement ImGuiOverlay (with temporary demo window).**

```kotlin
package io.schemat.connector.fabric.client.imgui

import imgui.ImGui
import net.minecraft.client.MinecraftClient

object ImGuiOverlay {
    @Volatile private var focused = false

    fun isFocused(): Boolean = focused

    fun toggle() {
        focused = !focused
        val mc = MinecraftClient.getInstance()
        if (focused) mc.mouse.unlockCursor() else mc.mouse.lockCursor()
    }

    fun render() {
        ImGuiManager.initIfNeeded()
        if (!focused) return // Phase 1: only render while focused. PanelManager replaces this in Phase 2.
        ImGuiManager.startFrame(focused)
        // TEMPORARY spike window — deleted in Phase 2.
        if (ImGui.begin("SchematioConnector — ImGui spike")) {
            ImGui.text("ImGui is alive.")
            ImGui.text(String.format("FPS: %.1f", ImGui.getIO().framerate))
        }
        ImGui.end()
        ImGuiManager.endFrame()
    }
}
```

> Confirm `mc.mouse.unlockCursor()` / `lockCursor()` Yarn names for 1.21.11; the reference toggles cursor via the same `Mouse` API.

- [ ] **Step 2: Add a toggle keybind.** In `Keybinds.kt`, register a new keybind (e.g. `imgui`, default unbound or `RIGHT_SHIFT`) and in `tick()` call `ImGuiOverlay.toggle()` on press. Match the existing registration style in that file (do not invent a new pattern).

- [ ] **Step 3: MANUAL VERIFICATION GATE (the spike).**

Run the client: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:runClient`
Then, in-game:
1. Press the toggle key → the "SchematioConnector — ImGui spike" window appears, styled with the bundled font, FPS counter ticking.
2. Cursor is free; you can drag the ImGui window and click inside it.
3. While the window is up, mouse clicks do NOT break/place blocks and mouse movement does NOT turn the camera.
4. Press the toggle key again → window disappears, cursor re-locks, camera control returns.

- [ ] **Step 4: MANUAL VERIFICATION GATE (built jar — natives).** Confirm the natives load outside dev:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:remapJar
```
Install the produced `fabric/build/libs/*.jar` into a real 1.21.11 Fabric instance and repeat Step 3. Pass criteria: overlay appears with no `UnsatisfiedLinkError`/missing-native crash. (This is the v1.2.0-class regression guard.)

- [ ] **Step 5: Commit.**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/imgui/ImGuiOverlay.kt \
        fabric/src/client/kotlin/io/schemat/connector/fabric/client/keybind/Keybinds.kt
git commit -m "feat(fabric): ImGui overlay toggle + spike window (Phase 1 gate passed)"
```

**PHASE 1 EXIT CRITERIA:** Both manual gates pass. The imgui-java version, the render-hook injection target, the input-mixin signatures, and the cursor API are now CONFIRMED. Phases 2–6 build on these confirmed facts; fix any code below that referenced an unconfirmed name to match what Phase 1 proved.

---

## PHASE 2 — Theme + widget + panel foundation

### Task 6: ImGuiTheme color model (TDD)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/imgui/ImGuiColors.kt`
- Create: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/imgui/ImGuiColorsTest.kt`

**Interfaces:**
- Produces: `fun argbToImVec4(argb: Int): ImVec4` and named color constants mirroring `Theme.kt`.

- [ ] **Step 1: Failing test.**

```kotlin
package io.schemat.connector.fabric.client.ui.imgui

import imgui.ImVec4
import kotlin.test.Test
import kotlin.test.assertEquals

class ImGuiColorsTest {
    private fun assertVec(expected: FloatArray, v: ImVec4) {
        assertEquals(expected[0], v.x, 0.001f); assertEquals(expected[1], v.y, 0.001f)
        assertEquals(expected[2], v.z, 0.001f); assertEquals(expected[3], v.w, 0.001f)
    }

    @Test fun opaqueWhite() = assertVec(floatArrayOf(1f,1f,1f,1f), argbToImVec4(0xFFFFFFFF.toInt()))

    @Test fun accentFuchsia() = // #db45f0 fully opaque
        assertVec(floatArrayOf(0xDB/255f, 0x45/255f, 0xF0/255f, 1f), argbToImVec4(0xFFDB45F0.toInt()))

    @Test fun halfAlpha() = assertVec(floatArrayOf(0f,0f,0f,0x80/255f), argbToImVec4(0x80000000.toInt()))
}
```

- [ ] **Step 2: Run — verify it fails.**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*ImGuiColorsTest*'`
Expected: FAIL — `argbToImVec4` unresolved.

- [ ] **Step 3: Implement.**

```kotlin
package io.schemat.connector.fabric.client.ui.imgui

import imgui.ImVec4

fun argbToImVec4(argb: Int): ImVec4 {
    val a = (argb ushr 24 and 0xFF) / 255f
    val r = (argb ushr 16 and 0xFF) / 255f
    val g = (argb ushr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return ImVec4(r, g, b, a)
}

object ImGuiColors {
    // Ported verbatim from Theme.kt — single source of truth for ImGui colors.
    val ACCENT = argbToImVec4(0xFFDB45F0.toInt())
    val ACCENT_HOVER = argbToImVec4(0xFFE978FA.toInt())
    val ACCENT_DIM = argbToImVec4(0xFF7A2E88.toInt())
    val BG = argbToImVec4(0xFF0A0A0C.toInt())
    val SURFACE = argbToImVec4(0xFF15151B.toInt())
    val SURFACE_2 = argbToImVec4(0xFF1C1C24.toInt())
    val SURFACE_HOVER = argbToImVec4(0xFF24242E.toInt())
    val BORDER = argbToImVec4(0xFF2A2A33.toInt())
    val BORDER_SUBTLE = argbToImVec4(0xFF1E1E26.toInt())
    val TEXT = argbToImVec4(0xFFFFFFFF.toInt())
    val TEXT_SECONDARY = argbToImVec4(0xFFB4B4C0.toInt())
    val TEXT_MUTED = argbToImVec4(0xFF8A8A96.toInt())
    val TEXT_FAINT = argbToImVec4(0xFF5E5E68.toInt())
    val SUCCESS = argbToImVec4(0xFF34D399.toInt())
    val DANGER = argbToImVec4(0xFFF87171.toInt())
    val WARNING = argbToImVec4(0xFFFBBF24.toInt())
    val INFO = argbToImVec4(0xFF7EA8FF.toInt())
}
```

> Cross-check every constant against the live `Theme.kt` values at implementation time; copy exact ARGB ints from that file rather than this plan if they differ.

- [ ] **Step 4: Run — verify pass.**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:test --tests '*ImGuiColorsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/imgui/ImGuiColors.kt \
        fabric/src/test/kotlin/io/schemat/connector/fabric/client/ui/imgui/ImGuiColorsTest.kt
git commit -m "feat(fabric): ImGui color palette ported from Theme.kt (TDD)"
```

---

### Task 7: ImGuiTheme — push/pop style + colors

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/imgui/ImGuiTheme.kt`

**Interfaces:**
- Produces:
  - `ImGuiTheme.apply()` — pushes all `ImGuiCol` colors + `ImGuiStyleVar` spacing/rounding/border to match the design language. Returns the number of pushed color/var pairs (so callers can pop precisely), OR provide matching `pop()`.
  - `ImGuiTheme.withStandardTable(id, columns, block)` — the ONLY entry point for tables (mirrors ParkourCalculator's `beginStandard*Table` discipline).

- [ ] **Step 1: Implement.**

```kotlin
package io.schemat.connector.fabric.client.ui.imgui

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiTableFlags

object ImGuiTheme {
    private var pushedColors = 0
    private var pushedVars = 0

    fun apply() {
        fun col(idx: Int, c: imgui.ImVec4) { ImGui.pushStyleColor(idx, c.x, c.y, c.z, c.w); pushedColors++ }
        fun varv(idx: Int, x: Float, y: Float) { ImGui.pushStyleVar(idx, x, y); pushedVars++ }
        fun var1(idx: Int, v: Float) { ImGui.pushStyleVar(idx, v); pushedVars++ }

        pushedColors = 0; pushedVars = 0
        col(ImGuiCol.WindowBg, ImGuiColors.BG)
        col(ImGuiCol.ChildBg, ImGuiColors.SURFACE)
        col(ImGuiCol.PopupBg, ImGuiColors.SURFACE_2)
        col(ImGuiCol.Border, ImGuiColors.BORDER)
        col(ImGuiCol.FrameBg, ImGuiColors.SURFACE_2)
        col(ImGuiCol.FrameBgHovered, ImGuiColors.SURFACE_HOVER)
        col(ImGuiCol.FrameBgActive, ImGuiColors.SURFACE_HOVER)
        col(ImGuiCol.Button, ImGuiColors.SURFACE_2)
        col(ImGuiCol.ButtonHovered, ImGuiColors.SURFACE_HOVER)
        col(ImGuiCol.ButtonActive, ImGuiColors.ACCENT_DIM)
        col(ImGuiCol.Header, ImGuiColors.ACCENT_DIM)
        col(ImGuiCol.HeaderHovered, ImGuiColors.ACCENT_HOVER)
        col(ImGuiCol.HeaderActive, ImGuiColors.ACCENT)
        col(ImGuiCol.Tab, ImGuiColors.SURFACE)
        col(ImGuiCol.TabHovered, ImGuiColors.ACCENT_HOVER)
        col(ImGuiCol.Text, ImGuiColors.TEXT)
        col(ImGuiCol.TextDisabled, ImGuiColors.TEXT_MUTED)
        col(ImGuiCol.CheckMark, ImGuiColors.ACCENT)

        // Spacing scale from Theme.kt (MD=8, LG=12). 1px borders, minimal rounding (flat look).
        varv(ImGuiStyleVar.WindowPadding, 12f, 12f)
        varv(ImGuiStyleVar.FramePadding, 8f, 6f)
        varv(ImGuiStyleVar.ItemSpacing, 8f, 6f)
        var1(ImGuiStyleVar.WindowRounding, 4f)
        var1(ImGuiStyleVar.FrameRounding, 3f)
        var1(ImGuiStyleVar.WindowBorderSize, 1f)
        var1(ImGuiStyleVar.FrameBorderSize, 1f)
    }

    fun unapply() {
        ImGui.popStyleVar(pushedVars)
        ImGui.popStyleColor(pushedColors)
    }

    inline fun withStandardTable(id: String, columns: Int, block: () -> Unit) {
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH or ImGuiTableFlags.ScrollY
        if (ImGui.beginTable(id, columns, flags)) {
            try { block() } finally { ImGui.endTable() }
        }
    }
}
```

- [ ] **Step 2: Compile check.** Run `:fabric:compileClientKotlin`. Expected: SUCCESS.
- [ ] **Step 3: Commit.** `git commit -m "feat(fabric): ImGuiTheme push/pop styling + standard table"`

---

### Task 8: Wire theme into the overlay frame

**Files:**
- Modify: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/imgui/ImGuiOverlay.kt`

- [ ] **Step 1:** In `render()`, wrap panel drawing with `ImGuiTheme.apply()` / `ImGuiTheme.unapply()` around the `startFrame`/panel/`endFrame` body (apply after `startFrame`, unapply before `endFrame`).
- [ ] **Step 2: MANUAL GATE.** `runClient`, toggle overlay — the spike window now shows the dark background, fuchsia accents on active elements, 1px borders. Compare side-by-side with a current vanilla screen screenshot; palette must match.
- [ ] **Step 3: Commit.** `git commit -m "feat(fabric): apply ImGuiTheme to overlay frame"`

---

### Task 9: PanelManager (TDD for state)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/imgui/Panel.kt`
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/imgui/PanelManager.kt`
- Create: `fabric/src/test/kotlin/io/schemat/connector/fabric/client/imgui/PanelManagerTest.kt`

**Interfaces:**
- Produces:
  - `interface Panel { val id: String; fun render() }`
  - `PanelManager.open(panel)`, `.close(id)`, `.toggle(panel)`, `.isOpen(id)`, `.anyOpen()`, `.openPanels(): List<Panel>`
- Consumes: nothing external (pure state); `renderAll()` calls each open panel's `render()`.

- [ ] **Step 1: Failing test.**

```kotlin
package io.schemat.connector.fabric.client.imgui

import kotlin.test.*

class PanelManagerTest {
    private class FakePanel(override val id: String) : Panel { var rendered = 0; override fun render() { rendered++ } }

    @BeforeTest fun reset() = PanelManager.closeAll()

    @Test fun opensAndReportsOpen() {
        val p = FakePanel("browser"); PanelManager.open(p)
        assertTrue(PanelManager.isOpen("browser")); assertTrue(PanelManager.anyOpen())
    }
    @Test fun toggleClosesWhenOpen() {
        val p = FakePanel("browser"); PanelManager.open(p); PanelManager.toggle(p)
        assertFalse(PanelManager.isOpen("browser"))
    }
    @Test fun opensAreDeduplicatedById() {
        PanelManager.open(FakePanel("browser")); PanelManager.open(FakePanel("browser"))
        assertEquals(1, PanelManager.openPanels().count { it.id == "browser" })
    }
}
```

- [ ] **Step 2: Run — fails** (`:fabric:test --tests '*PanelManagerTest*'`).
- [ ] **Step 3: Implement** `Panel` interface and `PanelManager` (a `LinkedHashMap<String, Panel>` preserving z-order; `renderAll()` iterates values calling `render()`; `closeAll()` clears).
- [ ] **Step 4: Run — passes.**
- [ ] **Step 5:** Replace the temporary `focused`-only logic in `ImGuiOverlay.render()` so it renders `PanelManager.renderAll()` and `isFocused()` returns `PanelManager.anyOpen()` (or keeps the explicit focus flag if overlay-HUD-without-focus is later needed; for now focus == any panel open). Delete the temporary spike window.
- [ ] **Step 6: Commit.** `git commit -m "feat(fabric): PanelManager registry + overlay integration (TDD)"`

---

### Task 10: Widgets helpers (FlatButton/TabBar/TextField equivalents)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/imgui/Widgets.kt`

**Interfaces:**
- Produces thin helpers over native ImGui that bake in the design language, replacing the old foundation widgets:
  - `Widgets.button(label: String, accent: Boolean = false): Boolean`
  - `Widgets.textField(label: String, state: ImString, hint: String? = null): Boolean`
  - `Widgets.tabBar(id: String, tabs: List<Pair<String, () -> Unit>>)`
  - `Widgets.statusText(text: String, kind: StatusKind)` (maps to SUCCESS/DANGER/WARNING/INFO colors)

- [ ] **Step 1: Implement** each helper using `ImGui.button`, `ImGui.inputTextWithHint`, `ImGui.beginTabBar/beginTabItem`, `ImGui.textColored`. Accent button: push `ImGuiCol.Button = ACCENT` around the call.
- [ ] **Step 2: Compile check.** Expected: SUCCESS.
- [ ] **Step 3: Commit.** `git commit -m "feat(fabric): ImGui Widgets helpers (button/tabbar/textfield/status)"`

---

### Task 11: Bundle font asset & verify atlas (manual)

**Files:** (font already added in Task 2) — this task verifies rendering quality.

- [ ] **Step 1: MANUAL GATE.** `runClient`, open a panel with `Widgets.button` and `Widgets.textField`. Confirm: glyphs are crisp (no garbage — proves the UNPACK fix), accent button is fuchsia, text field accepts typing with the input mixins. If glyphs are garbage, the GL UNPACK normalization (Task 2 Step 1) is required / version must drop to 1.86.11.
- [ ] **Step 2: Commit** (if any tweak): `git commit -m "fix(fabric): font atlas rendering verified"`

---

### Task 12: Gradle theme-discipline check

**Files:**
- Modify: `fabric/build.gradle(.kts)` (register a `checkThemeDiscipline` task; wire into `check`)
- Create: `fabric/src/test/resources/theme-check/Bad.kt.fixture`, `Good.kt.fixture`

**Interfaces:**
- Produces: a Gradle task that fails the build if panel source under `ui/imgui/panels/` contains raw 8-digit hex literals or `ImVec4(` numeric constructors outside the `ui/imgui` theme package.

- [ ] **Step 1: Implement the check** (adapted from ParkourCalculator's `tableStyleCheck`): scan `.kt` files under the panels dir, regex for `0x[0-9A-Fa-f]{8}` and `ImVec4\(` , collect violations, throw `GradleException` listing `file:line` if any. Exempt the theme package (`ui/imgui/ImGuiColors.kt`, `ImGuiTheme.kt`).
- [ ] **Step 2: Verify** against fixtures: run the task pointed at `Bad.kt.fixture` (expect failure listing the line) and `Good.kt.fixture` (expect pass). Document the command in the task.
- [ ] **Step 3: Wire** `check.dependsOn checkThemeDiscipline`.
- [ ] **Step 4: Commit.** `git commit -m "build(fabric): theme-discipline check (no inline colors in panels)"`

**PHASE 2 EXIT CRITERIA:** Styled overlay with working `PanelManager`, themed widgets matching the palette, font crisp, theme-discipline check green. Foundation ready for porting.

---

## PHASE 3 — Port read-only panels

> For each port: **read the current screen source first** (it is the behavioral spec), then reproduce its data fetching (same `core/` API calls) and layout in ImGui. Each panel is `class XPanel : Panel`. Manual gate compares the panel against the old screen for parity.

### Task 13: BrowserPanel (replaces HomeScreen + tabs)

**Files:**
- Create: `fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/imgui/panels/BrowserPanel.kt`
- Read (spec): `.../ui/HomeScreen.kt`, `.../ui/tabs/{BrowseTab,SettingsTab,CommunitiesTab,QuickSharesTab}.kt`

**Interfaces:**
- Consumes: `CachedSchematioApi` (same instance/accessor the current tabs use), `PanelManager`, `Widgets`, `ImGuiTheme`.
- Produces: `BrowserPanel(api): Panel` with `id = "browser"`.

- [ ] **Step 1:** Implement `render()` using `ImGui.begin("Schematio")`, `Widgets.tabBar("home", listOf("Browse" to ::renderBrowse, "My Schematics" to ::renderMine, "Communities" to ::renderCommunities, "Quick Shares" to ::renderQuickShares, "Settings" to ::renderSettings))`. Each `render*` reproduces the corresponding current tab's content, calling the SAME `core/` API methods the existing `TabContent` impls call (copy the call sites verbatim; only the drawing changes). Use `ImGuiTheme.withStandardTable` for list rendering. Reuse the existing async/loading model the tabs use (e.g. futures/state in `SchematicListModels.kt`); render `LoadingSpinner` state via an ImGui spinner helper.
- [ ] **Step 2:** Repoint the existing `browser` keybind: in `Keybinds.tick()`, replace `MinecraftClient.setScreen(HomeScreen(...))` with `PanelManager.toggle(BrowserPanel(api))`.
- [ ] **Step 3: MANUAL GATE.** `runClient`, open browser, switch all tabs. Parity check vs old `HomeScreen`: same lists load, same data shown, pagination/filter behaves, settings reflect/persist.
- [ ] **Step 4: Commit.** `git commit -m "feat(fabric): BrowserPanel replaces HomeScreen (ImGui)"`

### Task 14: SchematicDetailPanel (replaces SchematicDetailScreen)

**Files:** Create `.../panels/SchematicDetailPanel.kt`; Read `.../ui/SchematicDetailScreen.kt`, `.../foundation/PreviewDraw.kt`.

- [ ] **Step 1:** Implement `SchematicDetailPanel(api, schematicId): Panel`. Reproduce detail fields, metadata, action buttons (download/open/edit) calling the same API. Open from `BrowserPanel` row click via `PanelManager.open(SchematicDetailPanel(...))`.
- [ ] **Step 2 (PreviewDraw adapter):** Decide the thumbnail path. **Default:** render the preview to an offscreen MC framebuffer/texture and display via `ImGui.image(textureId, w, h)`. If `PreviewDraw` is pure 2D primitives, reimplement with `ImGui.getWindowDrawList()` instead. Document which path was taken in a code comment.
- [ ] **Step 3: MANUAL GATE.** Open a schematic's detail; parity vs old screen incl. preview thumbnail visible and correct.
- [ ] **Step 4: Commit.** `git commit -m "feat(fabric): SchematicDetailPanel + PreviewDraw adapter"`

### Task 15: CommunityDetailPanel (replaces CommunityDetailScreen)

**Files:** Create `.../panels/CommunityDetailPanel.kt`; Read `.../ui/CommunityDetailScreen.kt`.

- [ ] **Step 1:** Implement, reproducing community view (members/schematics lists, join/leave actions) on the same API. Open from the Communities tab.
- [ ] **Step 2: MANUAL GATE.** Parity vs old screen.
- [ ] **Step 3: Commit.** `git commit -m "feat(fabric): CommunityDetailPanel (ImGui)"`

**PHASE 3 EXIT CRITERIA:** Browse → inspect schematic → inspect community works end to end via ImGui, matching old behavior.

---

## PHASE 4 — Port write flows

### Task 16: ConfirmModal + TagSelector popup (foundation for write flows)

**Files:** Create `.../panels/ConfirmModal.kt`, `.../panels/TagSelectorPopup.kt`; Read `.../foundation/ConfirmDialogScreen.kt`, `.../ui/TagSelectorScreen.kt`.

- [ ] **Step 1:** Implement as ImGui popups (`ImGui.openPopup` / `beginPopupModal`). `ConfirmModal.show(message, onConfirm)`; `TagSelectorPopup` renders selectable tags returning the chosen set. These are called inline by other panels, not registered as top-level panels.
- [ ] **Step 2: MANUAL GATE.** Trigger a confirm dialog and a tag selection from a test button; parity vs old dialogs.
- [ ] **Step 3: Commit.** `git commit -m "feat(fabric): ConfirmModal + TagSelector popups (ImGui)"`

### Task 17: UploadWizardPanel (replaces UploadWizardScreen)

**Files:** Create `.../panels/UploadWizardPanel.kt`; Read `.../ui/UploadWizardScreen.kt`.

- [ ] **Step 1:** Implement the multi-step wizard with an ImGui stepper (step state + Next/Back buttons), reproducing each step's fields and validation, the file selection, and the final upload call (same API). Reuse `TagSelectorPopup` for tags and `Widgets.textField` for inputs.
- [ ] **Step 2:** Repoint the `upload` keybind to `PanelManager.toggle(UploadWizardPanel(...))`.
- [ ] **Step 3: MANUAL GATE.** Complete a full upload through all steps; verify the upload succeeds and matches old wizard behavior (validation, error banners).
- [ ] **Step 4: Commit.** `git commit -m "feat(fabric): UploadWizardPanel (ImGui)"`

### Task 18: SchematicEditPanel (replaces SchematicEditScreen)

**Files:** Create `.../panels/SchematicEditPanel.kt`; Read `.../ui/SchematicEditScreen.kt`.

> The description field here uses plain `Widgets.textField`/multiline FOR NOW; the rich editor lands in Phase 5 and replaces this field.

- [ ] **Step 1:** Implement edit form (title, tags, visibility, plain multiline description), saving via the same API. Open from `SchematicDetailPanel`'s edit button.
- [ ] **Step 2: MANUAL GATE.** Edit and save a schematic; changes persist; parity vs old screen (minus rich text).
- [ ] **Step 3: Commit.** `git commit -m "feat(fabric): SchematicEditPanel (ImGui, plain description)"`

### Task 19: QuickShareCreatePanel (replaces QuickShareCreateScreen)

**Files:** Create `.../panels/QuickShareCreatePanel.kt`; Read `.../ui/QuickShareCreateScreen.kt`.

- [ ] **Step 1:** Implement quick-share creation form + submit (same API). Repoint the `quickShare` keybind to `PanelManager.toggle(QuickShareCreatePanel(...))`.
- [ ] **Step 2: MANUAL GATE.** Create a quick share; parity vs old screen.
- [ ] **Step 3: Commit.** `git commit -m "feat(fabric): QuickShareCreatePanel (ImGui)"`

**PHASE 4 EXIT CRITERIA:** Full CRUD parity — upload, edit, quick-share, confirm, tag-select all work via ImGui.

---

## PHASE 5 — Custom rich-text widget

> Highest-risk, isolated. If cost overruns, fall back to markdown source + live preview (plain `inputTextMultiline` + a rendered preposition pane) WITHOUT touching other panels — only `SchematicEditPanel`'s description field swaps.

### Task 20: RichTextModel (TDD)

**Files:** Create `.../ui/imgui/richtext/RichTextModel.kt`; Test `.../richtext/RichTextModelTest.kt`.

**Interfaces:**
- Produces: a document model — runs of text with style spans (bold/italic/color/link), serialize/deserialize to/from the API's stored description format.

- [ ] **Step 1: Failing tests** for: parse stored format → model; model → stored format round-trips; inserting a char at a caret offset preserves spans; toggling bold over a selection splits/merges runs. (Write concrete assertions on span boundaries.)
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** the model (immutable runs + edit operations returning new state).
- [ ] **Step 4: Run — passes.**
- [ ] **Step 5: Commit.** `git commit -m "feat(fabric): rich-text document model (TDD)"`

### Task 21: RichTextWidget (rendering + input)

**Files:** Create `.../ui/imgui/richtext/RichTextWidget.kt`; Read `.../foundation/{RichDescriptionEditor,RichTextEditor}.kt` (behavioral spec).

- [ ] **Step 1:** Implement an immediate-mode editable widget: render runs with `ImGui.getWindowDrawList()` (per-run color/style), draw caret, handle click-to-place-caret, selection drag, keyboard input (char insert, backspace/delete, arrows), and a small toolbar (bold/italic/link) operating on `RichTextModel`. Use the input already routed by the Phase 1 mixins.
- [ ] **Step 2:** Swap `SchematicEditPanel`'s description field to `RichTextWidget`.
- [ ] **Step 3: MANUAL GATE.** Type, select, bold a range, add a link, save, reopen — formatting persists and matches the old `RichDescriptionEditor` behavior. Verify the v1.2.4 fix is preserved: backspace at end-of-text does not crash.
- [ ] **Step 4: Commit.** `git commit -m "feat(fabric): RichTextWidget WYSIWYG editor"`

**PHASE 5 EXIT CRITERIA:** Rich descriptions edit/render/persist in ImGui with no crash at text boundaries.

---

## PHASE 6 — Cutover & cleanup

### Task 22: Repoint all remaining entry points

**Files:** Modify `.../keybind/Keybinds.kt`; Modify Litematica/WorldEdit integration bridges (search for `setScreen` and old screen class references).

- [ ] **Step 1:** Grep for every `setScreen(` and old screen class usage:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline help >/dev/null; grep -rn "setScreen\|HomeScreen\|UploadWizardScreen\|SchematicDetailScreen\|SchematicEditScreen\|CommunityDetailScreen\|QuickShareCreateScreen\|TagSelectorScreen\|ConfirmDialogScreen" fabric/src`
Expected: only the integration bridges + keybinds remain; repoint each to the corresponding `PanelManager.open/toggle(...)`.
- [ ] **Step 2: MANUAL GATE.** Every entry point (each keybind, Litematica button, WorldEdit hook) opens the correct ImGui panel.
- [ ] **Step 3: Commit.** `git commit -m "refactor(fabric): repoint all UI entry points to PanelManager"`

### Task 23: Delete vanilla UI

**Files:** Delete the old screen + foundation + tab + compat files now that nothing references them.

- [ ] **Step 1:** Delete: `ui/HomeScreen.kt`, `ui/UploadWizardScreen.kt`, `ui/SchematicDetailScreen.kt`, `ui/SchematicEditScreen.kt`, `ui/CommunityDetailScreen.kt`, `ui/QuickShareCreateScreen.kt`, `ui/TagSelectorScreen.kt`, `ui/foundation/ConfirmDialogScreen.kt`, `ui/foundation/{FlatButton,TabBarWidget,ThemedTextField,RichDescriptionEditor,RichTextEditor,LoadingSpinner,NoticeBanner,PreviewDraw}.kt`, `ui/compat/Draw.kt`, `ui/tabs/*.kt` — only those with zero remaining references (verify with grep first).
- [ ] **Step 2:** Build everything: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build`. Expected: BUILD SUCCESSFUL (no unresolved references; theme-discipline check green; tests pass).
- [ ] **Step 3: MANUAL GATE (full regression).** `runClient` + built-jar install: exercise every panel and entry point once more. No vanilla screens remain.
- [ ] **Step 4: Commit.** `git commit -m "refactor(fabric): remove vanilla Screen UI after ImGui cutover"`

**PHASE 6 EXIT CRITERIA:** No vanilla `Screen` UI remains; full build + all tests green; full in-game regression passes from a built jar. Ready to build version-control UI natively in ImGui.

---

## Self-review notes

- **Spec coverage:** Section 1 (deps/natives) → Task 1; Section 2 (overlay engine: manager/hook/input) → Tasks 2–5; Section 3 (theme/fonts) → Tasks 2,6,7,8,11,12; Section 4 (panel ports incl. PreviewDraw + RichText) → Tasks 9,10,13–21; Section 5 (phasing) → phase structure; Section 6 (testing/risks) → TDD tasks + manual gates + built-jar gates (Task 5/23) + RichText fallback (Phase 5). All covered.
- **Phase-1-confirmed values** (imgui version, injection target, mixin signatures, cursor API) are flagged inline as confirm-points, not silent placeholders — required because the spec defers them to the spike.
- **Type consistency:** `ImGuiOverlay.isFocused()`, `ImGuiManager.*Callback`, `PanelManager.open/toggle/anyOpen`, `Panel.id/render`, `ImGuiTheme.apply/unapply/withStandardTable`, `argbToImVec4`/`ImGuiColors` used consistently across tasks.
