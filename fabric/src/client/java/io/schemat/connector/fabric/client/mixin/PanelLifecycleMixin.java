package io.schemat.connector.fabric.client.mixin;

import dev.harrison.panellib.framework.Overlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft draws a disconnect screen after CLIENT_STOPPING has freed ImGui. */
@Mixin(value = Overlay.class, remap = false)
public abstract class PanelLifecycleMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 1)
    private static void schematio$skipAfterShutdown(CallbackInfo ci) {
        if (!Minecraft.getInstance().isRunning()) ci.cancel();
    }
}
