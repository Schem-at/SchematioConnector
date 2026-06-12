package io.schemat.connector.fabric.client.mixin;

import java.util.List;
import java.util.Set;

import me.fallenbreath.conditionalmixin.api.mixin.RestrictiveMixinConfigPlugin;

/**
 * Mixin config plugin that honors conditional-mixin's {@code @Restriction} annotations:
 * mixins annotated with {@code @Restriction(require = @Condition("litematica"))} are
 * silently skipped when Litematica is not installed.
 */
public class SchematioMixinConfigPlugin extends RestrictiveMixinConfigPlugin {

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        // No dynamically-added mixins; everything is declared in the mixin config json.
        return null;
    }
}
