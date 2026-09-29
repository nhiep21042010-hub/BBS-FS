package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the animatable follow-curve travel {@code follow_offset} (0..1 along the curve) to every
 * {@link Form}. It is a top-level form value, hence keyframable in the replay editor like transform /
 * pose. The enable/target/align config lives on the {@link mchorse.bbs_mod.film.replays.Replay} (see
 * {@code ReplayFollowMixin}); the drive itself is in {@code FollowCurveControllerMixin}.
 */
@Mixin(Form.class)
public abstract class FollowCurveFormMixin
{
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addFollowCurve(CallbackInfo ci)
    {
        ValueGroup self = (ValueGroup) (Object) this;

        /* Only the animatable travel lives on the form (so it keyframes in the replay editor next to
         * transform/pose — collectPropertyPaths lists all visible form values); the enable/target/align
         * config lives on the Replay (see ReplayFollowMixin). */
        self.add(new ValueFloat("follow_offset", 0F, 0F, 1F));
    }
}
