package io.schemat.connector.fabric.client.mixin;

import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import io.schemat.connector.fabric.client.integration.MixinBridge;
import me.fallenbreath.conditionalmixin.api.annotation.Condition;
import me.fallenbreath.conditionalmixin.api.annotation.Restriction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an "Upload to schemat.io" button to Litematica's "Schematic Placements" list,
 * placed in the bottom button row just left of the right-aligned "Main Menu" button
 * (the target's {@code initGui} puts the left row at x=12 and Main Menu at
 * {@code getScreenWidth() - width - 10}, all at {@code y = getScreenHeight() - 26}).
 *
 * <p>Clicking opens the upload wizard pre-seeded with the currently selected placement
 * (by its index in the placement manager's list - the bridge enumerates placements in
 * the same order). With no selection the wizard opens on its normal source picker.
 *
 * <p>Only applied when Litematica is installed (conditional-mixin {@code @Restriction}).
 * {@code initGui} is a MaLiLib method, not a Minecraft one, hence {@code remap = false}.
 */
@Restriction(require = @Condition("litematica"))
@Mixin(value = GuiSchematicPlacementsList.class, remap = false)
public abstract class GuiSchematicPlacementsListMixin {

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void schematioconnector$addUploadButton(CallbackInfo ci) {
        GuiBase self = (GuiBase) (Object) this;
        SchematicPlacementManager manager = ((GuiSchematicPlacementsList) (Object) this).manager;

        String label = StringUtils.translate("schematioconnector.gui.button.upload_placement");
        int width = self.getStringWidth(label) + 20;

        String mainMenuLabel = StringUtils.translate(
            GuiMainMenu.ButtonListenerChangeMenu.ButtonType.MAIN_MENU.getLabelKey());
        int mainMenuWidth = self.getStringWidth(mainMenuLabel) + 20;

        int x = self.getScreenWidth() - 10 - mainMenuWidth - 4 - width;
        int y = self.getScreenHeight() - 26;

        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label,
            StringUtils.translate("schematioconnector.gui.button.upload_placement.hover"));
        self.addButton(button, (btn, mouseButton) -> {
            SchematicPlacement selected = manager.getSelectedSchematicPlacement();
            int index = selected == null ? -1 : manager.getAllSchematicsPlacements().indexOf(selected);
            MixinBridge.openUploadWizardForPlacement(index);
        });
    }
}
