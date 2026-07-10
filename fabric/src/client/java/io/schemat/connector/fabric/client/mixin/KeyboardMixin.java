package io.schemat.connector.fabric.client.mixin;

import imgui.ImGui;
import io.schemat.connector.fabric.client.ui.framework.ImGuiManager;
import io.schemat.connector.fabric.client.ui.framework.ImGuiOverlay;
import net.minecraft.client.KeyboardHandler;
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
//?}
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts keyboard input for the ImGui overlay.
 *
 * Confirmed Mojang-mapped signatures via javap on decompiled jars:
 *
 *   1.21.8 keyPress: public void keyPress(long, int key, int scancode, int action, int mods)
 *     Descriptor: (JIIII)V  (public on 1.21.8)
 *     Confirmed: javap -p on minecraft-clientonly-1.21.8-loom.mappings.1_21_8.layered+hash.2198-v2.jar
 *
 *   1.21.8 charTyped: private void charTyped(long, int codepoint, int mods)
 *     Descriptor: (JII)V
 *     Confirmed: javap -p on minecraft-clientonly-1.21.8-loom.mappings.1_21_8.layered+hash.2198-v2.jar
 *
 *   1.21.9–26.1 keyPress: private void keyPress(long, @Action int action, KeyEvent keyEvent)
 *     Descriptor: (JILnet/minecraft/client/input/KeyEvent;)V
 *
 *   1.21.9–26.1 charTyped: private void charTyped(long, CharacterEvent characterEvent)
 *     Descriptor: (JLnet/minecraft/client/input/CharacterEvent;)V
 *     (26.1 CharacterEvent is 1-arg vs 2-arg on 1.21.9-.11; .codepoint() accessor is the same.)
 */
@Mixin(KeyboardHandler.class)
public class KeyboardMixin {

    /**
     * Escape key: when the overlay is focused, close the topmost panel (instead of letting
     * MC open the pause menu). We handle this BEFORE forwarding to ImGui so that Escape is
     * consumed entirely and MC never sees it.
     *
     * Works on both 1.21.8 (JIIII) and 1.21.9+ (JILKeyEvent;I) because we match the
     * version-split injection selectors below — but for Escape we inject into the shared
     * version-appropriate method and check the key ourselves.
     */
    //? if <1.21.9 {
    /*@Inject(
        method = "keyPress(JIIII)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onEscape_legacy(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        if (key == GLFW.GLFW_KEY_ESCAPE && (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT)) {
            ImGuiOverlay.onEscape();
            ci.cancel();
        }
    }
    *///?} else {
    @Inject(
        method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onEscape(long window, int action, KeyEvent keyEvent, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE && (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT)) {
            ImGuiOverlay.onEscape();
            ci.cancel();
        }
    }
    //?}

    /**
     * Forward key events to ImGui and suppress MC handling while overlay is focused.
     *
     * 1.21.8: keyPress(JIIII)V — flat ints (window, key, scancode, action, mods).
     * 1.21.9+: keyPress(JILnet/minecraft/client/input/KeyEvent;)V — record-based.
     */
    //? if <1.21.9 {
    /*@Inject(
        method = "keyPress(JIIII)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    public void schematio$onKeyPress(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        // Passthrough: game hotkeys/movement keep working unless an ImGui text field
        // is focused (then ImGui wants the keyboard).
        if (!ImGui.getIO().getWantCaptureKeyboard()) return;
        ImGuiManager.keyCallback(window, key, scancode, action, mods);
        ci.cancel();
    }
    *///?} else {
    @Inject(
        method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onKeyPress(long window, int action, KeyEvent keyEvent, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        // Passthrough: game hotkeys/movement keep working unless an ImGui text field
        // is focused (then ImGui wants the keyboard).
        if (!ImGui.getIO().getWantCaptureKeyboard()) return;
        ImGuiManager.keyCallback(window, keyEvent.key(), keyEvent.scancode(), action, keyEvent.modifiers());
        ci.cancel();
    }
    //?}

    /**
     * Forward char-typed events to ImGui and suppress MC handling while overlay is focused.
     *
     * 1.21.8: charTyped(JII)V — flat ints (window, codepoint, mods).
     * 1.21.9+: charTyped(JLnet/minecraft/client/input/CharacterEvent;)V — record-based.
     */
    //? if <1.21.9 {
    /*@Inject(
        method = "charTyped(JII)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onCharTyped(long window, int codepoint, int mods, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        if (!ImGui.getIO().getWantCaptureKeyboard()) return;
        ImGuiManager.charCallback(window, codepoint);
        ci.cancel();
    }
    *///?} else {
    @Inject(
        method = "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void schematio$onCharTyped(long window, CharacterEvent characterEvent, CallbackInfo ci) {
        if (!ImGuiOverlay.isFocused()) return;
        if (!ImGui.getIO().getWantCaptureKeyboard()) return;
        ImGuiManager.charCallback(window, characterEvent.codepoint());
        ci.cancel();
    }
    //?}
}
