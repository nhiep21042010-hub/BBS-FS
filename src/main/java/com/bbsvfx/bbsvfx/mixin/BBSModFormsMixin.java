package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.camera.FollowCurveClip;
import com.bbsvfx.bbsvfx.camera.ImpactClip;
import com.bbsvfx.bbsvfx.forms.BeamForm;
import com.bbsvfx.bbsvfx.forms.CurveForm;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;
import com.bbsvfx.bbsvfx.forms.DomeForm;
import com.bbsvfx.bbsvfx.forms.ExplosionForm;
import com.bbsvfx.bbsvfx.forms.WindForm;

/**
 * Registers the addon's common-side types with BBS's factories so they (de)serialize: the
 * {@link CurveForm} form type and the {@link FollowCurveClip} camera modifier. Injected at
 * {@code TAIL} of {@code BBSMod.onInitialize}, after BBS has built its {@code FormArchitect} and clip
 * factory; both are reached through public getters. Type ids are namespaced under {@code bbsvfx:*}.
 *
 * <p>The client-side renderer, editor panels and the client clip override are registered separately
 * in {@code BbsVfxClient}; the "miscellaneous" form-picker entry by {@code ExtraFormSectionMixin}.</p>
 */
@Mixin(BBSMod.class)
public abstract class BBSModFormsMixin
{
    @Inject(method = "onInitialize", at = @At("TAIL"))
    private void bbsvfx$registerTypes(CallbackInfo ci)
    {
        BBSMod.getForms().register(new Link("bbsvfx", "curve"), CurveForm.class, null);
        BBSMod.getForms().register(new Link("bbsvfx", "destruction_box"), DestructionBoxForm.class, null);
        BBSMod.getForms().register(new Link("bbsvfx", "explosion"), ExplosionForm.class, null);
        BBSMod.getForms().register(new Link("bbsvfx", "beam"), BeamForm.class, null);
        BBSMod.getForms().register(new Link("bbsvfx", "dome"), DomeForm.class, null);
        BBSMod.getForms().register(new Link("bbsvfx", "wind"), WindForm.class, null);

        BBSMod.getFactoryCameraClips().register(new Link("bbsvfx", "follow_curve"),
            FollowCurveClip.class, new ClipFactoryData(Icons.ARC, 0x4ba3ff));

        BBSMod.getFactoryCameraClips().register(new Link("bbsvfx", "impact"),
            ImpactClip.class, new ClipFactoryData(Icons.FOUR_STAR, 0xff5a3c));
    }
}
