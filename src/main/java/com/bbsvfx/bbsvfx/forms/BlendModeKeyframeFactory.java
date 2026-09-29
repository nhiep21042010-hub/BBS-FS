package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.IntegerKeyframeFactory;

/**
 * Keyframe factory for the {@code blend_mode} channel: stores an int (a {@link BlendMode} index) like the
 * stock integer factory, but interpolates as a STEP (holds the left keyframe's value) — a blend mode is a
 * discrete category, so it must never tween through intermediate indices (which would flash other modes
 * mid-transition). A distinct factory instance also gives the channel its own dropdown keyframe editor
 * ({@code UIBlendModeKeyframeFactory}) instead of the numeric trackpad.
 */
public class BlendModeKeyframeFactory extends IntegerKeyframeFactory
{
    @Override
    public Integer interpolate(Keyframe<Integer> preA, Keyframe<Integer> a, Keyframe<Integer> b, Keyframe<Integer> postB, IInterp interpolation, float x)
    {
        return a.getValue();
    }

    @Override
    public Integer interpolate(Integer preA, Integer a, Integer b, Integer postB, IInterp interpolation, float x)
    {
        return a;
    }
}
