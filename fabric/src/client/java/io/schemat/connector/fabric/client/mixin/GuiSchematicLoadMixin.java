package io.schemat.connector.fabric.client.mixin;

import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import io.schemat.connector.fabric.client.integration.MixinBridge;
import me.fallenbreath.conditionalmixin.api.annotation.Condition;
import me.fallenbreath.conditionalmixin.api.annotation.Restriction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import java.util.Set;

/**
 * Adds two buttons to Litematica's "Load Schematics" file browser, placed in the bottom
 * button row just left of the right-aligned "Main Menu" button (mirroring how the target
 * lays out that row in {@code initGui}: left row at x=12, Main Menu at
 * {@code getScreenWidth() - width - 10}, all at {@code y = getScreenHeight() - 26}):
 *
 * <ul>
 *   <li><b>Schemat.io</b> - opens our Home screen (browse tab).</li>
 *   <li><b>Upload to schemat.io</b> - opens the upload wizard pre-seeded with the file
 *       currently selected in the browser. Validation happens at click time, Litematica
 *       style: no selection / directory selected / unsupported extension show an ERROR
 *       message via {@link InfoUtils} instead of opening the wizard.</li>
 * </ul>
 *
 * <p>The mixin extends {@link GuiSchematicBrowserBase} (the target's superclass) so the
 * protected {@code getListWidget()} and screen-size getters are directly callable.
 *
 * <p>Only applied when Litematica is installed (conditional-mixin {@code @Restriction}).
 * {@code initGui} is a MaLiLib method, not a Minecraft one, hence {@code remap = false}.
 */
@Restriction(require = @Condition("litematica"))
@Mixin(value = GuiSchematicLoad.class, remap = false)
public abstract class GuiSchematicLoadMixin extends GuiSchematicBrowserBase {

    private static final Set<String> SCHEMATIO_UPLOADABLE_EXTENSIONS =
        Set.of("litematic", "schem", "schematic");

    protected GuiSchematicLoadMixin(int maxWidth, int maxHeight) {
        super(maxWidth, maxHeight);
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void schematioconnector$addSchematioButtons(CallbackInfo ci) {
        String browseLabel = StringUtils.translate("schematioconnector.gui.button.open_browser");
        int browseWidth = this.getStringWidth(browseLabel) + 20;

        String mainMenuLabel = StringUtils.translate(
            GuiMainMenu.ButtonListenerChangeMenu.ButtonType.MAIN_MENU.getLabelKey());
        int mainMenuWidth = this.getStringWidth(mainMenuLabel) + 20;

        int y = this.getScreenHeight() - 26;
        int x = this.getScreenWidth() - 10 - mainMenuWidth - 4 - browseWidth;

        ButtonGeneric browseButton = new ButtonGeneric(x, y, browseWidth, 20, browseLabel,
            StringUtils.translate("schematioconnector.gui.button.open_browser.hover"));
        this.addButton(browseButton, (btn, mouseButton) -> MixinBridge.openBrowser());

        String uploadLabel = StringUtils.translate("schematioconnector.gui.button.upload_file");
        int uploadWidth = this.getStringWidth(uploadLabel) + 20;
        x = x - 4 - uploadWidth;

        ButtonGeneric uploadButton = new ButtonGeneric(x, y, uploadWidth, 20, uploadLabel,
            StringUtils.translate("schematioconnector.gui.button.upload_file.hover"));
        this.addButton(uploadButton, (btn, mouseButton) -> schematioconnector$uploadSelectedFile());
    }

    private void schematioconnector$uploadSelectedFile() {
        WidgetFileBrowserBase.DirectoryEntry entry = this.getListWidget().getLastSelectedEntry();

        if (entry == null || entry.getType() != WidgetFileBrowserBase.DirectoryEntryType.FILE) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.ERROR,
                "schematioconnector.message.error.no_file_selected");
            return;
        }

        String extension = entry.getName().contains(".")
            ? entry.getName().substring(entry.getName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "";
        if (!SCHEMATIO_UPLOADABLE_EXTENSIONS.contains(extension)) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.ERROR,
                "schematioconnector.message.error.unsupported_format", entry.getName());
            return;
        }

        MixinBridge.openUploadWizardForFile(entry.getFullPath().toString());
    }
}
