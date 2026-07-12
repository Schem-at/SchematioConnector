package io.schemat.connector.fabric.client.mixin;

//? if <26.2 {
import com.mojang.blaze3d.TracyFrameCapture;
import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
//? if >=1.21.9 && <26.1 {
import com.mojang.blaze3d.platform.Window;
//?}

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Draws the ImGui overlay at HEAD of {@code RenderSystem.flipFrame} on every version that
 * still has flipFrame (all MC {@code < 26.2}). On {@code >= 26.2} flipFrame is gone (GpuSurface
 * present rework) and the equivalent hook lives in {@link GlSurfaceMixin}.
 *
 * WHY flipFrame HEAD (not HudRenderCallback):
 *   flipFrame's body is {@code pollEvents(); Tesselator.clear(); glfwSwapBuffers(...); ...} —
 *   the GL buffer swap happens INSIDE flipFrame. At its HEAD the caller has already blitted
 *   the main render target to FBO 0 (the default framebuffer) and the swap has NOT happened
 *   yet. So FBO 0 holds the finished frame; binding FBO 0 + drawing ImGui here puts the overlay
 *   on the presented frame.
 *
 *   Crucially this is PIPELINE-AGNOSTIC. The old path (Fabric HudRenderCallback) fired during
 *   the HUD phase, when MC's mainRenderTarget — not FBO 0 — is bound, on the assumption that
 *   "FBO 0 is already current during HUD". That holds only in vanilla. Under Sodium/Iris the
 *   world is composited through their own shader framebuffers and reaches FBO 0 outside the HUD
 *   phase, so ImGui drew onto an empty (black) FBO 0 and the game never showed through the
 *   passthrough dockspace → a black screen. flipFrame HEAD sits AFTER MC's own blit-to-screen,
 *   so it is correct regardless of how the world got onto FBO 0.
 *
 * flipFrame is never overloaded, so {@code method = "flipFrame"} (name-only) resolves to exactly
 * one target on every version. Its descriptor drifts, so the handler is version-gated:
 *   - {@code <1.21.9}          : flipFrame(long, TracyFrameCapture)
 *   - {@code >=1.21.9 && <26.1} : flipFrame(Window, TracyFrameCapture)
 *   - {@code >=26.1 && <26.2}   : flipFrame(TracyFrameCapture)
 * (buildAllVersions compiles every variant, so a wrong descriptor fails the build, not silently.)
 *
 * On {@code >=26.2} this class has NO injectors; {@code @Mixin(RenderSystem.class)} stays a valid
 * inert target (RenderSystem exists on all versions), and {@link GlSurfaceMixin} does the work.
 */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {

    //? if <1.21.9 {
    /*@Inject(method = "flipFrame", at = @At("HEAD"), require = 1)
    private static void schemat_renderImGuiBeforePresent(long window, TracyFrameCapture frameCapture, CallbackInfo ci) {
        // Skip while a vanilla Screen is open (it handles its own rendering + cursor).
        if (Minecraft.getInstance().screen != null) return;
        // ImGuiManager.endFrame() binds FBO 0 + sets the window viewport before renderDrawData.
        ImGuiOverlay.render();
    }
    *///?}

    //? if >=1.21.9 && <26.1 {
    @Inject(method = "flipFrame", at = @At("HEAD"), require = 1)
    private static void schemat_renderImGuiBeforePresent(Window window, TracyFrameCapture frameCapture, CallbackInfo ci) {
        // Skip while a vanilla Screen is open (it handles its own rendering + cursor).
        if (Minecraft.getInstance().screen != null) return;
        // ImGuiManager.endFrame() binds FBO 0 + sets the window viewport before renderDrawData.
        ImGuiOverlay.render();
    }
    //?}

    //? if >=26.1 && <26.2 {
    /*@Inject(method = "flipFrame", at = @At("HEAD"), require = 1)
    private static void schemat_renderImGuiBeforePresent(TracyFrameCapture frameCapture, CallbackInfo ci) {
        // Skip while a vanilla Screen is open (it handles its own rendering + cursor).
        if (Minecraft.getInstance().screen != null) return;
        // ImGuiManager.endFrame() binds FBO 0 + sets the window viewport before renderDrawData.
        ImGuiOverlay.render();
    }
    *///?}
}
