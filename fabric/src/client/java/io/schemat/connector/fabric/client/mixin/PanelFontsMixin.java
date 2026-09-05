package io.schemat.connector.fabric.client.mixin;

import dev.harrison.panellib.theme.Fonts;
import imgui.ImFont;
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Compatibility fix for panel-lib 0.1.1 with imgui-java 1.89.0. */
@Mixin(value = Fonts.class, remap = false)
public abstract class PanelFontsMixin {
    @Redirect(method = "load", at = @At(value = "INVOKE",
        target = "Limgui/ImFontAtlas;addFontFromMemoryTTF([BF)Limgui/ImFont;"), require = 4)
    private ImFont schematio$copyJavaFontData(ImFontAtlas atlas, byte[] data, float size) {
        // The two-argument JNI overload transfers a temporary Java array pointer
        // to ImGui, which later frees it at shutdown. Explicit non-ownership makes
        // ImGui copy the bytes into its own allocation during AddFont instead.
        ImFontConfig config = new ImFontConfig();
        try {
            config.setFontDataOwnedByAtlas(false);
            return atlas.addFontFromMemoryTTF(data, size, config);
        } finally {
            config.destroy();
        }
    }
}
