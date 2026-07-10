package io.schemat.connector.fabric.client.mixin;

import imgui.ImGui;
import io.schemat.connector.fabric.client.ui.framework.ImGuiManager;
import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay;
import net.minecraft.client.MouseHandler;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonInfo;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts mouse input for the ImGui overlay.
 *
 * Confirmed Mojang-mapped signatures via javap on decompiled jars:
 *
 *   1.21.8: MouseHandler.onPress(long, int button, int action, int mods) — private, flat ints.
 *     Descriptor: (JIII)V
 *     Confirmed: javap -p on minecraft-clientonly-1.21.8-loom.mappings.1_21_8.layered+hash.2198-v2.jar
 *
 *   1.21.9–26.1: MouseHandler.onButton(long, MouseButtonInfo, @Action int) — private, record.
 *     Descriptor: (JLnet/minecraft/client/input/MouseButtonInfo;I)V
 *     Confirmed: javap -p on 1.21.9, 1.21.10, 1.21.11 loom-mapped jars.
 *
 *   MouseHandler.onScroll(long l, double d, double e) — universal (JDDV).
 *   MouseHandler.handleAccumulatedMovement() — universal, public, no-arg.
 *   MouseHandler.grabMouse() — universal, public, no-arg.
 */
@Mixin(MouseHandler.class)
public class MouseMixin {

    /**
     * Forward mouse-button events to ImGui and suppress MC handling while overlay is focused.
     *
     * 1.21.8: onPress(JIII)V — flat ints (window, button, action, mods).
     * 1.21.9+: onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V — record-based.
     */
    //? if <1.21.9 {
    /*@Inject(
        method = "onPress(JIII)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        // Passthrough: when ImGui doesn't want the mouse (cursor not over a window),
        // let the click reach the game instead of swallowing it.
        if (!ImGui.getIO().getWantCaptureMouse()) return;
        ImGuiManager.mouseButtonCallback(window, button, action, mods);
        ci.cancel();
    }
    *///?} else {
    @Inject(
        method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onButton(long window, MouseButtonInfo mouseButtonInfo, int action, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        // Passthrough: when ImGui doesn't want the mouse (cursor not over a window),
        // let the click reach the game instead of swallowing it.
        if (!ImGui.getIO().getWantCaptureMouse()) return;
        ImGuiManager.mouseButtonCallback(window, mouseButtonInfo.button(), action, mouseButtonInfo.modifiers());
        ci.cancel();
    }
    //?}

    /**
     * Forward scroll events to ImGui and suppress MC handling while overlay is focused.
     *
     * Confirmed: private void onScroll(long l, double d, double e)
     */
    @Inject(
        method = "onScroll(JDD)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        // Passthrough: scroll reaches the game (hotbar) unless the cursor is over a window.
        if (!ImGui.getIO().getWantCaptureMouse()) return;
        ImGuiManager.scrollCallback(window, xOffset, yOffset);
        ci.cancel();
    }

    /**
     * Block camera look-around while overlay is focused.
     *
     * Confirmed: public void handleAccumulatedMovement()
     * This public no-arg method calls the private turnPlayer(double) when mouse is grabbed.
     * Cancelling here prevents all mouse-look processing for the frame.
     */
    @Inject(
        method = "handleAccumulatedMovement()V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onHandleAccumulatedMovement(CallbackInfo ci) {
        if (ImGuiOverlay.isFocused()) ci.cancel();
    }

    /**
     * Prevent MC from capturing/hiding the cursor while overlay is focused.
     *
     * Confirmed: public void grabMouse()
     */
    @Inject(
        method = "grabMouse()V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onGrabMouse(CallbackInfo ci) {
        if (ImGuiOverlay.isFocused()) ci.cancel();
    }
}
