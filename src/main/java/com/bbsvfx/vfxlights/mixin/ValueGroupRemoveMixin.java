package com.bbsvfx.vfxlights.mixin;

import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.light.LightRegistry;

/**
 * A deleted light form must die NOW, not at the end of its persist-grace window. Persisted lights
 * linger by design (a culled lamp and a deleted one look identical to the registry), so the grace
 * window bridges camera pans — but deleting the form in the editor is explicit, and the only event
 * that distinguishes the two is this removal from the parent group.
 */
@Mixin(value = ValueGroup.class, remap = false)
public abstract class ValueGroupRemoveMixin
{
    @Inject(method = "remove", at = @At("RETURN"))
    private void vfxlights$formRemoved(BaseValue child, CallbackInfo ci)
    {
        if (child instanceof LightForm)
        {
            LightRegistry.clearPersisted(child);
        }
    }
}
