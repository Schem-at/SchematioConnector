package io.schemat.connector.fabric.client.ui.framework

import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiConfigFlags
import imgui.glfw.ImGuiImplGlfw
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

object ImGuiManager {
    private val imGuiGlfw = ImGuiImplGlfw()
    private var initialized = false
    private var windowHandle = 0L

    /**
     * Unicode codepoints typed since the last drain, in order.
     *
     * imgui-java exposes no read API for typed characters, so the custom
     * [io.schemat.connector.fabric.client.ui.widgets.RichTextEditorWidget] (which does
     * not use an ImGui `InputText`) drains this each frame to receive text input.
     * Fed from [charCallback] in addition to the normal `ImGui.getIO().addInputCharacter`
     * path (via `imGuiGlfw.charCallback`); when no ImGui text control is active ImGui
     * simply ignores the buffered chars, so there is no conflict.
     */
    private val typedChars = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    private const val INI_FILENAME = "schematioconnector-imgui.ini"
    private const val FONT_PATH = "/assets/schematioconnector/fonts/Inter-Regular.ttf"
    private const val BASE_FONT_SIZE = 18f

    fun initIfNeeded() {
        if (initialized) return
        try {
            // 1.21.8: Window.getWindow() (plain getter); 1.21.9+: Window.handle() (record-style accessor).
            //? if <1.21.9 {
            /*val wh = Minecraft.getInstance().window.getWindow()
            *///?} else {
            val wh = Minecraft.getInstance().window.handle()
            //?}
            windowHandle = wh

            ImGui.createContext()
            val io: ImGuiIO = ImGui.getIO()
            io.iniFilename = INI_FILENAME
            io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)
            // Docking ON: enables the full-viewport DockHost + dockable tool windows
            // (Browse/Upload/QuickShare). The dock layout persists in INI_FILENAME so a
            // user's arrangement survives restarts. Multi-viewport (ViewportsEnable) stays
            // OFF: we render a single overlay into MC's framebuffer, not OS child windows.
            io.addConfigFlags(ImGuiConfigFlags.DockingEnable)

            loadFonts(io)

            imGuiGlfw.init(wh, false) // installCallbacks=false; mixins forward input (Task 4)

            // FIX (cursor-enter / mouseWindow): ImGuiImplGlfw.updateMouseData() guards cursor-pos
            // polling behind `data.mouseWindow != -1L`. With installCallbacks=false, ImGui never
            // installs a GLFWCursorEnterCallback, so mouseWindow stays -1 forever → addMousePosEvent
            // is never called → io.MousePos is always invalid → hit-testing misses every click.
            // Fix: (1) prime mouseWindow immediately so the very first frame gets a valid cursor pos;
            // (2) install our own cursor-enter callback to keep mouseWindow accurate as focus changes.
            imGuiGlfw.cursorEnterCallback(wh, true) // prime: cursor is in our window
            GLFW.glfwSetCursorEnterCallback(wh) { _, entered ->
                imGuiGlfw.cursorEnterCallback(wh, entered)
            }

            // Build OUR custom GL3 renderer — the sole owner of the font atlas texture.
            // ImGuiImplGl3 is NOT used: its init() builds its own incomplete font texture and calls
            // setTexID(itsTexture), overwriting our good texture → Apple GL driver marks it
            // "unloadable" (mipmap-incomplete at default GL_NEAREST_MIPMAP_LINEAR) → black overlay.
            // With ImGuiImplGl3 removed, buildFontTexture() is the ONLY setTexID call. Frame flow:
            //   imGuiGlfw.newFrame() + ImGui.newFrame()  → panels → ImGui.render() (core)
            //   → ImGuiGl3Renderer.render(ImGui.getDrawData())
            ImGuiGl3Renderer.initIfNeeded()

            initialized = true
        } catch (e: Throwable) {
            // swallow — game keeps running, overlay simply stays inactive
        }
    }

    private fun loadFonts(io: ImGuiIO) {
        val bytes = ImGuiManager::class.java.getResourceAsStream(FONT_PATH)
            ?.readBytes() ?: error("Missing bundled font: $FONT_PATH")
        io.fonts.addFontFromMemoryTTF(bytes, BASE_FONT_SIZE)
        io.fonts.build()
    }

    fun startFrame(focused: Boolean) {
        imGuiGlfw.newFrame()
        ImGui.newFrame()
        val io = ImGui.getIO()
        if (!focused) {
            // imGuiGlfw feeds polled cursor pos during newFrame; override AFTER it so ImGui ignores the mouse.
            io.setMousePos(-Float.MAX_VALUE, -Float.MAX_VALUE)
            for (i in 0 until 5) io.setMouseDown(i, false)
        } else {
            // FIX (cursor-enter / mouseWindow safety net): even with the cursor-enter callback primed,
            // directly read glfwGetCursorPos and push it as a safety net so ImGui ALWAYS has a valid
            // mouse position when the overlay is visible.  imGuiGlfw.newFrame() → updateMouseData()
            // only calls addMousePosEvent when data.mouseWindow != -1; if for any reason that flag
            // reverted (e.g., GLFW sent a leave event on macOS fullscreen toggle), this prevents a
            // stale/invalid MousePos. We push AFTER newFrame() so it takes precedence.
            if (windowHandle != 0L) {
                val mx = DoubleArray(1)
                val my = DoubleArray(1)
                GLFW.glfwGetCursorPos(windowHandle, mx, my)
                io.setMousePos(mx[0].toFloat(), my[0].toFloat())
            }
        }
    }

    fun endFrame() {
        ImGui.render()
        // Bind the default framebuffer (FBO 0 = screen) before drawing ImGui draw data.
        // On MC 26.x the entire frame renders into an offscreen FBO owned by GlCommandEncoder;
        // blitToScreen() copies it to FBO 0 but the encoder may leave a non-zero FBO bound
        // afterward. imgui-java's renderDrawData never binds an FBO itself, so we must ensure
        // FBO 0 is current. This is harmless on <26.x (FBO 0 is already bound during HUD phase).
        // On 26.x: Window.getWidth()/getHeight() return framebufferWidth/framebufferHeight
        // (verified from decompiled 26.1.2 Window.class).
        val w = Minecraft.getInstance().window

        // Reset host GL state before the immediate-mode ImGui draw. MC 26.x's GpuDevice/command-
        // encoder may leave a leftover program or VAO bound; we render under OUR OWN VAO/program so a
        // clean baseline (program 0, VAO 0) avoids inheriting host state. Harmless on <26.x.
        GL20.glUseProgram(0)
        GL30.glBindVertexArray(0)

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)

        GL11.glViewport(0, 0, w.width, w.height)

        // FIX (Task: 0x502 macOS core profile) — use our custom Apple-core-profile-correct renderer
        // INSTEAD of imGuiGl3.renderDrawData(...). imgui-java 1.89.0's backend throws
        // GL_INVALID_OPERATION (0x502) inside renderDrawData on Apple GL 4.1 core profile (its GL
        // state save/restore — glPolygonMode(GL_FRONT_AND_BACK) restore + glBindSampler + the
        // glDrawElementsBaseVertex path — is incompatible with Apple's strict forward-compat core
        // profile). Our renderer does a minimal, correct draw with no state backup/restore.
        ImGuiGl3Renderer.render(ImGui.getDrawData())
    }

    fun shutdown() {
        if (!initialized) return
        // Remove our cursor-enter callback to avoid a dangling GLFW native callback reference.
        if (windowHandle != 0L) GLFW.glfwSetCursorEnterCallback(windowHandle, null)?.free()
        ImGuiGl3Renderer.shutdown()
        imGuiGlfw.shutdown()
        ImGui.destroyContext()
        initialized = false
        windowHandle = 0L
    }

    // Input forwarders, called from Mouse/Keyboard mixins (Task 4).
    // @JvmStatic so Java mixin code can call ImGuiManager.mouseButtonCallback(...) directly
    // without going through ImGuiManager.INSTANCE, matching the @JvmStatic pattern on ImGuiOverlay.
    @JvmStatic fun mouseButtonCallback(window: Long, button: Int, action: Int, mods: Int) =
        imGuiGlfw.mouseButtonCallback(window, button, action, mods)
    @JvmStatic fun scrollCallback(window: Long, xOffset: Double, yOffset: Double) =
        imGuiGlfw.scrollCallback(window, xOffset, yOffset)
    @JvmStatic fun keyCallback(window: Long, key: Int, scancode: Int, action: Int, mods: Int) =
        imGuiGlfw.keyCallback(window, key, scancode, action, mods)
    @JvmStatic fun charCallback(window: Long, codepoint: Int) {
        // Keep feeding ImGui's own input buffer (drives any ImGui InputText)...
        imGuiGlfw.charCallback(window, codepoint)
        // ...and also queue it for the custom RichTextEditorWidget to drain.
        typedChars.add(codepoint)
    }

    /** Poll-all the typed-character queue (oldest first); clears it. Called by the focused editor each frame. */
    fun drainTypedChars(): List<Int> {
        if (typedChars.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        while (true) {
            val cp = typedChars.poll() ?: break
            out.add(cp)
        }
        return out
    }
    // Not wired to a mixin (installed directly in initIfNeeded via glfwSetCursorEnterCallback).
    // Exposed as @JvmStatic for completeness / testability.
    @JvmStatic fun cursorEnterCallback(window: Long, entered: Boolean) =
        imGuiGlfw.cursorEnterCallback(window, entered)
}
