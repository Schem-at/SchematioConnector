package io.schemat.connector.fabric.client.mixin;

//? if >=26.1 {
/*import com.mojang.blaze3d.TracyFrameCapture;
import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;

/**
 * On >=26.1: injects at HEAD of RenderSystem.flipFrame to draw ImGui onto FBO 0
 * before the buffer swap / present.
 *
 * Why HEAD on flipFrame (not INVOKE inside runTick):
 *   All three prior INVOKE-inside-runTick attempts ("endFrame", "blitToScreen",
 *   "flipFrame") reported "Scanned 0 target(s)" because those call sites live inside
 *   lambdas or inlined helper bodies, not in runTick's own bytecode — Mixin's INVOKE
 *   scanner only sees the owning method's opcodes.
 *
 *   Targeting flipFrame's OWN HEAD bypasses that entirely: the method is a real
 *   invokestatic target (confirmed via javap of minecraft-merged-deobf-26.1.2.jar),
 *   so the HEAD inject always resolves to exactly one point.
 *
 * Frame order at HEAD of flipFrame:
 *   In Minecraft.runTick, mainRenderTarget is already blitted to FBO 0 before
 *   flipFrame is called (offset 266 → blit, offset 331 → flipFrame). At HEAD, FBO 0
 *   holds the finished frame and the GL swap has NOT happened yet. Binding FBO 0 +
 *   drawing ImGui here puts the overlay on screen before present.
 *
 * Verified signature (javap -p minecraft-merged-deobf-26.1.2.jar):
 *   public static void flipFrame(com.mojang.blaze3d.TracyFrameCapture)
 *   — STATIC, single param, NOT overloaded → name-only method = "flipFrame" is safe.
 *
 * On <26.1: this class has NO injectors. RenderSystem is a real class present on all
 *   versions, so @Mixin(RenderSystem.class) is always a valid (safe) inert target.
 *   HudRenderCallback handles rendering on those versions (registered in SchematioClientMod).
 */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {

    //? if >=26.1 {
    /*@Inject(
        method = "flipFrame",
        at = @At("HEAD"),
        require = 1
    )
    private static void schemat_renderImGuiBeforePresent(TracyFrameCapture frameCapture, CallbackInfo ci) {
        // Guard: skip if a vanilla Screen is open (it handles its own rendering).
        if (Minecraft.getInstance().screen != null) return;
        // ImGuiManager.endFrame() binds FBO 0 + sets the window viewport before
        // renderDrawData, so ImGui geometry lands on the default framebuffer.
        ImGuiOverlay.render();
    }
    *///?}
}
