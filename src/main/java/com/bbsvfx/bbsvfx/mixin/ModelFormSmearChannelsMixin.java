package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.pose.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.IModelSmearChannels;
import com.bbsvfx.bbsvfx.forms.ValueLinesPose;
import com.bbsvfx.bbsvfx.forms.ValueSmearPose;

/**
 * Adds the {@code smear_frames} and {@code motion_lines} per-bone keyframe channels to {@link ModelForm}
 * so smear and motion lines become their own pose-related tracks (with bone selection), separate from
 * the model's pose. Registered as child values so they serialise and keyframe like {@code pose}.
 */
@Mixin(ModelForm.class)
public abstract class ModelFormSmearChannelsMixin implements IModelSmearChannels
{
    @Unique
    private ValueSmearPose bbsvfx$smear;

    @Unique
    private ValueLinesPose bbsvfx$lines;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addChannels(CallbackInfo ci)
    {
        this.bbsvfx$smear = new ValueSmearPose("smear_frames", new Pose());
        this.bbsvfx$lines = new ValueLinesPose("motion_lines", new Pose());

        ((ValueGroup) (Object) this).add(this.bbsvfx$smear);
        ((ValueGroup) (Object) this).add(this.bbsvfx$lines);
    }

    @Override
    public BaseKeyframeFactoryValue<Pose> bbsvfx$smearPose()
    {
        return this.bbsvfx$smear;
    }

    @Override
    public BaseKeyframeFactoryValue<Pose> bbsvfx$linesPose()
    {
        return this.bbsvfx$lines;
    }
}
