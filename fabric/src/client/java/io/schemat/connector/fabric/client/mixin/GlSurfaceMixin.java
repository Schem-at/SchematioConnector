package io.schemat.connector.fabric.client.mixin;

//? if >=26.2 {
/*import com.mojang.blaze3d.opengl.GlSurface;
import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?} else {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * 26.2 replacement for {@link RenderSystemMixin}'s flipFrame hook: the GpuSurface
 * rework removed RenderSystem.flipFrame; presentation now goes through
 * Minecraft.renderFrame -&gt; GpuSurface.blitFromTexture (frame blitted to the
 * window's default framebuffer on the GL backend) -&gt; GpuSurface.present -&gt;
 * GlSurface.present (glfwSwapBuffers; bytecode-verified against the 26.2 jar).
 *
 * Injecting at HEAD of GlSurface.present() is the exact analog of 26.1's HEAD of
 * flipFrame: FBO 0 already holds the finished frame and the swap has NOT happened,
 * so drawing ImGui here puts the overlay on screen before present. GlSurface is the
 * GL backend implementation, which matches ImGuiManager's raw-GL rendering; on a
 * non-GL backend the class never loads and this mixin is simply never applied.
 *
 * On &lt;26.2 this class has NO injectors and targets RenderSystem as a safe inert
 * anchor (same pattern as RenderSystemMixin on &lt;26.1).
 */
//? if >=26.2 {
/*@Mixin(GlSurface.class)
*///?} else {
@Mixin(RenderSystem.class)
//?}
public class GlSurfaceMixin {

    //? if >=26.2 {
    /*@Inject(
        method = "present",
        at = @At("HEAD"),
        require = 1
    )
    private void schemat_renderImGuiBeforePresent(CallbackInfo ci) {
        // Guard: skip if a vanilla Screen is open (it handles its own rendering).
        // 26.2 moved screen management from Minecraft onto Gui.
        if (Minecraft.getInstance().gui.screen() != null) return;
        // ImGuiManager.endFrame() binds FBO 0 + sets the window viewport before
        // renderDrawData, so ImGui geometry lands on the default framebuffer.
        ImGuiOverlay.render();
    }
    *///?}
}
