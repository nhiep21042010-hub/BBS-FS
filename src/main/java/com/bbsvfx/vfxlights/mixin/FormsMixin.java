package com.bbsvfx.vfxlights.mixin;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.resources.Link;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.VfxLightsAddon;
import com.bbsvfx.vfxlights.forms.AmbientLightForm;
import com.bbsvfx.vfxlights.forms.AreaLightForm;
import com.bbsvfx.vfxlights.forms.PointLightForm;
import com.bbsvfx.vfxlights.forms.SpotLightForm;

/**
 * Makes the light form TYPES known to BBS's form factory, so saved films can load them. Injected at
 * {@code TAIL} of {@code BBSMod.onInitialize}, once BBS has built its form architect.
 *
 * <p>Registration is split across three places by BBS's design: the type here, the picker entry in
 * {@code FormPickerMixin}, and the renderer/editor bindings in the client entry point. A form registered
 * here but nowhere else loads from disk yet cannot be created; registered in the picker but not here, it
 * is created fine and then fails to deserialise on the next load.</p>
 */
@Mixin(BBSMod.class)
public abstract class FormsMixin
{
    @Inject(method = "onInitialize", at = @At("TAIL"))
    private void vfxlights$registerTypes(CallbackInfo ci)
    {
        BBSMod.getForms().register(link("point_light"), PointLightForm.class, null);
        BBSMod.getForms().register(link("spot_light"), SpotLightForm.class, null);
        BBSMod.getForms().register(link("area_light"), AreaLightForm.class, null);
        BBSMod.getForms().register(link("ambient_light"), AmbientLightForm.class, null);
    }

    private static Link link(String id)
    {
        return new Link(VfxLightsAddon.MOD_ID, id);
    }
}
