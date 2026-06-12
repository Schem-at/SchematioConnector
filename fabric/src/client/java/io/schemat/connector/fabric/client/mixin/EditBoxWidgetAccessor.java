package io.schemat.connector.fabric.client.mixin;

import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link MultiLineEditBox} exposes no public selection or cursor API - only
 * {@code getValue}/{@code setValue}. The selection lives in the private
 * {@link MultilineTextField} model, whose {@code getSelected()}/{@code insertText(String)}
 * are public; this accessor unlocks it so the description editor's B/I/U/S toolbar can
 * wrap the current selection in markup markers.
 */
@Mixin(MultiLineEditBox.class)
public interface EditBoxWidgetAccessor {

    @Accessor("textField")
    MultilineTextField schematioGetEditBox();
}
