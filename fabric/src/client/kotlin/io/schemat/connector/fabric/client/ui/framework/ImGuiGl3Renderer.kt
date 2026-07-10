package io.schemat.connector.fabric.client.ui.framework

import imgui.ImDrawData
import imgui.ImGui
import imgui.ImVec4
import imgui.type.ImInt
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL33

/**
 * Minimal, Apple-GL-4.1-forward-compatible-core-profile-correct renderer for Dear ImGui draw data.
 *
 * WHY THIS EXISTS
 * ---------------
 * imgui-java 1.89.0's [imgui.gl3.ImGuiImplGl3.renderDrawData] is a straight port of
 * imgui_impl_opengl3.cpp. On Apple's forward-compatible GL 4.1 core profile (M-series, "4.1 Metal")
 * it reports GL_INVALID_OPERATION (0x502) DURING renderDrawData even with a fully clean GL state on
 * entry (verified by per-call glGetError diagnostics all 0x0 right up to the call). The backend's
 * extensive GL-state save/restore (glGetIntegerv(GL_POLYGON_MODE)+glPolygonMode(GL_FRONT_AND_BACK)
 * restore, glBindSampler save/restore, glDrawElementsBaseVertex path) interacts badly with Apple's
 * strict core profile, so nothing rasterizes (invisible overlay on all MC versions). The #300
 * host-state-reset mitigation did not help because the fault is INSIDE renderDrawData.
 *
 * This renderer replaces the GL3 DRAW only. imgui-java still owns the context, IO, input and the
 * font atlas (incl. the font-atlas GL texture created by ImGuiImplGl3.init()). We read the same raw
 * draw-data buffers imgui exposes and issue a tiny, conservative, core-profile-correct draw:
 *   - one #version 150 program (ortho proj from display size; sample font atlas * vertex color),
 *   - OUR OWN VAO/VBO/EBO,
 *   - per ImDrawCmd: glScissor (framebuffer pixels, Y-flipped) + glDrawElements with a byte offset
 *     into the EBO and a base-vertex emulated by re-uploading per cmd list (no glDrawElementsBaseVertex).
 *   - NO glPolygonMode, NO glBindSampler, NO state backup/restore — the host (ImGuiManager.endFrame)
 *     already bound FBO 0 + viewport and reset program/VAO. We leave clean state behind too.
 *
 * This is valid on every GL 3.2+ core profile (Windows/Linux included), so it is used on all versions.
 */
object ImGuiGl3Renderer {
    private var initialized = false
    private var program = 0
    private var uniformProjMtx = 0
    private var uniformTexture = 0
    private var vao = 0
    private var vbo = 0
    private var ebo = 0
    var fontTextureId = 0  // OUR GL texture for the font atlas (GL_LINEAR, GL_RGBA8, GL_CLAMP_TO_EDGE)
        private set

    // ImDrawVert layout (sizeOfImDrawVert == 20): pos.xy(float,@0) uv.xy(float,@8) col RGBA8(@16).
    private var vertexSize = 0
    private var indexSize = 0
    private var indexGlType = GL11.GL_UNSIGNED_SHORT

    private const val VERTEX_SHADER =
        "#version 150\n" +
        "in vec2 Position;\n" +
        "in vec2 UV;\n" +
        "in vec4 Color;\n" +
        "uniform mat4 ProjMtx;\n" +
        "out vec2 Frag_UV;\n" +
        "out vec4 Frag_Color;\n" +
        "void main() {\n" +
        "    Frag_UV = UV;\n" +
        "    Frag_Color = Color;\n" +
        "    gl_Position = ProjMtx * vec4(Position.xy, 0, 1);\n" +
        "}\n"

    private const val FRAGMENT_SHADER =
        "#version 150\n" +
        "in vec2 Frag_UV;\n" +
        "in vec4 Frag_Color;\n" +
        "uniform sampler2D Texture;\n" +
        "out vec4 Out_Color;\n" +
        "void main() {\n" +
        "    Out_Color = Frag_Color * texture(Texture, Frag_UV.st);\n" +
        "}\n"

    /** Build our shader/VAO/VBO/EBO once. Call AFTER ImGuiImplGl3.init() (font atlas texture must exist). */
    fun initIfNeeded() {
        if (initialized) return
        vertexSize = ImDrawData.sizeOfImDrawVert()
        indexSize = ImDrawData.sizeOfImDrawIdx()
        indexGlType = if (indexSize == 2) GL11.GL_UNSIGNED_SHORT else GL11.GL_UNSIGNED_INT

        val vert = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER, "vertex")
        val frag = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER, "fragment")
        program = GL20.glCreateProgram()
        GL20.glAttachShader(program, vert)
        GL20.glAttachShader(program, frag)
        GL20.glLinkProgram(program)
        // link failure is silent at runtime; overlay simply won't render
        GL20.glDetachShader(program, vert)
        GL20.glDetachShader(program, frag)
        GL20.glDeleteShader(vert)
        GL20.glDeleteShader(frag)

        uniformProjMtx = GL20.glGetUniformLocation(program, "ProjMtx")
        uniformTexture = GL20.glGetUniformLocation(program, "Texture")
        val attribPos = GL20.glGetAttribLocation(program, "Position")
        val attribUV = GL20.glGetAttribLocation(program, "UV")
        val attribColor = GL20.glGetAttribLocation(program, "Color")

        vao = GL30.glGenVertexArrays()
        vbo = GL15.glGenBuffers()
        ebo = GL15.glGenBuffers()

        GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo)
        GL20.glEnableVertexAttribArray(attribPos)
        GL20.glEnableVertexAttribArray(attribUV)
        GL20.glEnableVertexAttribArray(attribColor)
        // pos: 2 floats @0; uv: 2 floats @8; color: 4 unsigned bytes (normalized) @16.
        GL20.glVertexAttribPointer(attribPos, 2, GL11.GL_FLOAT, false, vertexSize, 0L)
        GL20.glVertexAttribPointer(attribUV, 2, GL11.GL_FLOAT, false, vertexSize, 8L)
        GL20.glVertexAttribPointer(attribColor, 4, GL11.GL_UNSIGNED_BYTE, true, vertexSize, 16L)
        GL30.glBindVertexArray(0)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)

        // Build OUR font atlas texture with explicit GL_LINEAR + GL_RGBA8 + GL_CLAMP_TO_EDGE.
        // We do NOT rely on imgui-java's ImGuiImplGl3.createFontsTexture() result because Apple's
        // GL 4.1 core-profile driver marks a texture "unloadable" (→ zero/black substitute) when
        // the min-filter is the GL default GL_NEAREST_MIPMAP_LINEAR but no mipmaps are present.
        // Although imgui-java does set GL_LINEAR, building our own is the only reliable path on
        // Apple: we fully own the parameters and can verify them. We call setTexID() so every
        // ImDrawCmd.textureId in the draw data references OUR texture.
        fontTextureId = buildFontTexture()

        initialized = true
    }

    /**
     * Upload the ImGui font atlas pixels to a new GL texture with parameters that Apple's
     * GL 4.1 core-profile driver accepts (GL_LINEAR min/mag, GL_CLAMP_TO_EDGE wrap, GL_RGBA8
     * internal, no mipmaps). Sets ImGui's IO texID to our texture so draw-cmd textureId values
     * match. Returns the GL texture name (> 0 on success, 0 on failure).
     */
    private fun buildFontTexture(): Int {
        val fontAtlas = ImGui.getIO().getFonts()
        val width = ImInt()
        val height = ImInt()
        // getTexDataAsRGBA32 builds the atlas bitmap if not already built.
        // Explicitly typed as ByteBuffer to avoid Kotlin overload ambiguity with glTexImage2D.
        val pixels: java.nio.ByteBuffer = fontAtlas.getTexDataAsRGBA32(width, height)

        val texId = GL11C.glGenTextures()
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texId)

        // Completeness: min/mag = GL_LINEAR (no mipmaps → GL_NEAREST_MIPMAP_LINEAR would make
        // the texture "unloadable" on Apple core profile and the driver substitutes black).
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR)
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR)
        // Clamp to edge: avoids border-color sampling artefacts at UV extremes.
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)

        // Normalize pixel-store state (MC may leave non-default values that scramble the upload).
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4)
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0)
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0)
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0)

        // GL_RGBA8 internal format (sized, unambiguous on core profile) + UNSIGNED_BYTE source.
        GL11C.glTexImage2D(
            GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8,
            width.get(), height.get(), 0,
            GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels
        )

        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0)

        // Tell ImGui: all draw-cmd textureId values will be this GL name from now on.
        // setTexID takes Long (ImTextureID is 64-bit in imgui-java).
        fontAtlas.setTexID(texId.toLong())

        return texId
    }

    private fun compileShader(type: Int, src: String, @Suppress("UNUSED_PARAMETER") label: String): Int {
        val handle = GL20.glCreateShader(type)
        GL20.glShaderSource(handle, src)
        GL20.glCompileShader(handle)
        return handle
    }

    /**
     * Render [drawData] into the currently-bound framebuffer. Caller (ImGuiManager.endFrame) must
     * have bound FBO 0 and the framebuffer-sized viewport. We set/clear only the GL state we need
     * and leave a clean baseline (program 0, VAO 0, blend disabled, scissor disabled).
     */
    fun render(drawData: ImDrawData) {
        if (!initialized) return
        val fbWidth = (drawData.displaySizeX * drawData.framebufferScaleX).toInt()
        val fbHeight = (drawData.displaySizeY * drawData.framebufferScaleY).toInt()
        if (fbWidth <= 0 || fbHeight <= 0) return
        if (drawData.cmdListsCount <= 0) return

        // ── Canonical Dear ImGui GL3 render state (set in full every frame because MC mutates GL state) ──
        GL11.glEnable(GL11.GL_BLEND)
        // Separate blend equations: color uses FUNC_ADD, alpha uses FUNC_ADD.
        // glBlendEquationSeparate lives in GL20 across all LWJGL versions (not GL14).
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD)
        // Separate blend funcs matching the official imgui_impl_opengl3.cpp:
        //   color = src.rgb * SRC_ALPHA   + dst.rgb * ONE_MINUS_SRC_ALPHA
        //   alpha = src.a   * ONE         + dst.a   * ONE_MINUS_SRC_ALPHA
        GL14.glBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,       GL11.GL_ONE_MINUS_SRC_ALPHA,
        )
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_DEPTH_TEST)   // <-- kills z-fighting between ImGui's layered quads
        GL11.glDisable(GL11.GL_STENCIL_TEST)
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        // Core-profile: GL_FRONT_AND_BACK is the only valid face enum; ensures fill even if MC left line/point mode.
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL)
        // Disable primitive restart so index values are never silently treated as restart tokens.
        GL11.glDisable(GL31.GL_PRIMITIVE_RESTART)

        GL11.glViewport(0, 0, fbWidth, fbHeight)

        // Orthographic projection from DisplayPos..DisplayPos+DisplaySize (top-left origin).
        val l = drawData.displayPosX
        val r = drawData.displayPosX + drawData.displaySizeX
        val t = drawData.displayPosY
        val b = drawData.displayPosY + drawData.displaySizeY
        val ortho = floatArrayOf(
            2.0f / (r - l), 0f, 0f, 0f,
            0f, 2.0f / (t - b), 0f, 0f,
            0f, 0f, -1.0f, 0f,
            (r + l) / (l - r), (t + b) / (b - t), 0f, 1.0f,
        )

        GL20.glUseProgram(program)
        // Bind sampler uniform to texture unit 0. Must be done AFTER glUseProgram.
        GL20.glUniform1i(uniformTexture, 0)
        GL20.glUniformMatrix4fv(uniformProjMtx, false, ortho)
        // Activate texture unit 0 once; keep it active throughout — per-cmd binds stay on unit 0.
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        // Clear any sampler object bound to unit 0. MC 26.x's GpuDevice/RenderSystem uses GL sampler
        // objects (GL 3.3+); a bound sampler OVERRIDES the texture's own filter params. If it specifies
        // a mipmap min-filter, our single-level font texture is "incomplete relative to the sampler" →
        // Apple marks it unloadable → black overlay. glBindSampler(0, 0) is a no-op when no sampler is
        // bound, and matches what the official Dear ImGui GL3 backend does in setupRenderState.
        GL33.glBindSampler(0, 0)
        GL30.glBindVertexArray(vao)

        val clipOffX = drawData.displayPosX
        val clipOffY = drawData.displayPosY
        val clipScaleX = drawData.framebufferScaleX
        val clipScaleY = drawData.framebufferScaleY

        val clip = ImVec4()
        for (n in 0 until drawData.cmdListsCount) {
            // Fetch vtxBuffer first; do NOT call getCmdListIdxBufferData yet — see comment below.
            val vtxBuffer = drawData.getCmdListVtxBufferData(n)

            // Re-upload this cmd list's vtx/idx. We emulate VtxOffset by re-binding per cmd list, so
            // glDrawElements (no BaseVertex) is correct: idx values are list-local, vtx buffer is this
            // list's. (ImDrawCmd.VtxOffset is 0 unless the BackendFlags RendererHasVtxOffset is set,
            // which we never set — so a plain glDrawElements with idx offset is exact.)
            //
            // CRITICAL: imgui-java's getCmdListVtxBufferData() and getCmdListIdxBufferData() SHARE a
            // single static ByteBuffer (ImDrawData.dataBuffer). Calling idx after vtx overwrites the
            // same Java object — both references point at the same buffer. We MUST bind+upload VBO
            // from vtxBuffer BEFORE calling getCmdListIdxBufferData(); otherwise glBufferData for
            // GL_ARRAY_BUFFER uploads index data (the last write to the shared buffer) instead of
            // vertex data. This was the root cause of the "flashing colors": wrong vertex data
            // (actually idx bytes) was landing in the VBO.
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo)
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vtxBuffer, GL15.GL_STREAM_DRAW)
            // Only now safe to call getCmdListIdxBufferData — it reuses the shared static buffer.
            val idxBuffer = drawData.getCmdListIdxBufferData(n)
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idxBuffer, GL15.GL_STREAM_DRAW)

            val cmdCount = drawData.getCmdListCmdBufferSize(n)
            for (cmdIdx in 0 until cmdCount) {
                drawData.getCmdListCmdBufferClipRect(clip, n, cmdIdx)
                val clipMinX = (clip.x - clipOffX) * clipScaleX
                val clipMinY = (clip.y - clipOffY) * clipScaleY
                val clipMaxX = (clip.z - clipOffX) * clipScaleX
                val clipMaxY = (clip.w - clipOffY) * clipScaleY
                if (clipMaxX <= clipMinX || clipMaxY <= clipMinY) continue

                // Y is inverted in GL: scissor origin is bottom-left.
                GL11.glScissor(
                    clipMinX.toInt(),
                    (fbHeight - clipMaxY).toInt(),
                    (clipMaxX - clipMinX).toInt(),
                    (clipMaxY - clipMinY).toInt(),
                )

                val textureId = drawData.getCmdListCmdBufferTextureId(n, cmdIdx)
                val elemCount = drawData.getCmdListCmdBufferElemCount(n, cmdIdx)
                val idxOffset = drawData.getCmdListCmdBufferIdxOffset(n, cmdIdx)
                // Re-activate unit 0 before each bind — Apple GL 4.1 core profile is strict about
                // the active texture unit matching the sampler unit at draw time.
                GL13.glActiveTexture(GL13.GL_TEXTURE0)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId.toInt())
                GL11.glDrawElements(
                    GL11.GL_TRIANGLES,
                    elemCount,
                    indexGlType,
                    idxOffset.toLong() * indexSize.toLong(),
                )
            }
        }

        // Leave a clean baseline (no save/restore — the host owns surrounding state).
        GL30.glBindVertexArray(0)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL20.glUseProgram(0)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glDisable(GL11.GL_BLEND)
    }

    fun shutdown() {
        if (!initialized) return
        if (fontTextureId != 0) {
            GL11C.glDeleteTextures(fontTextureId)
            // Clear imgui's reference so it doesn't try to use the deleted texture.
            runCatching { ImGui.getIO().getFonts().setTexID(0L) }
            fontTextureId = 0
        }
        if (vbo != 0) GL15.glDeleteBuffers(vbo)
        if (ebo != 0) GL15.glDeleteBuffers(ebo)
        if (vao != 0) GL30.glDeleteVertexArrays(vao)
        if (program != 0) GL20.glDeleteProgram(program)
        program = 0; vao = 0; vbo = 0; ebo = 0
        initialized = false
    }
}
