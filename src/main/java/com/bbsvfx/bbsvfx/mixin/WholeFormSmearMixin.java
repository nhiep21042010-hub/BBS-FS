package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.ParticleForm;
import mchorse.bbs_mod.forms.forms.VanillaParticleForm;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.CurveForm;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;
import com.bbsvfx.bbsvfx.forms.IFormSmear;
import com.bbsvfx.bbsvfx.forms.ValueFormSmearPose;

/**
 * Adds ONE whole-form smear keyframe channel ({@code smear}) to every {@link Form} EXCEPT the ones with
 * their own per-bone smear ({@link ModelForm}) or where it makes no sense (curve / destruction / particles).
 * All params live in a single sentinel {@link PoseTransform} ({@link IFormSmear#SENTINEL}); the arc render
 * re-poses the whole form at earlier film times, smearing its transform. Mirrors the single-track {@code blend}.
 */
@Mixin(Form.class)
public abstract class WholeFormSmearMixin implements IFormSmear
{
    @Unique
    private ValueFormSmearPose bbsvfx$formSmear;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addFormSmear(CallbackInfo ci)
    {
        Object self = this;

        /* TrailForm excluded too: a trail accumulates a position history, so re-rendering it per echo
         * copy at rewound times corrupts that history and the trail wildly distorts. */
        if (self instanceof ModelForm || self instanceof CurveForm || self instanceof DestructionBoxForm
            || self instanceof ParticleForm || self instanceof VanillaParticleForm
            || self instanceof mchorse.bbs_mod.forms.forms.TrailForm)
        {
            return;
        }

        /* Seed the single sentinel entry so the editor has something to edit (the form has no real bones). */
        Pose pose = new Pose();
        pose.getOrCreate(IFormSmear.SENTINEL);

        this.bbsvfx$formSmear = new ValueFormSmearPose("smear", pose);
        ((ValueGroup) self).add(this.bbsvfx$formSmear);
    }

    @Override
    public Pose bbsvfx$formSmearPose()
    {
        return this.bbsvfx$formSmear == null ? null : this.bbsvfx$formSmear.get();
    }
}
