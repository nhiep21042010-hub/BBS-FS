package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.base.BaseValueNumber;
import mchorse.bbs_mod.utils.MathUtils;

/**
 * The {@code blend_mode} form value: an int ({@link BlendMode} index) backed by the dedicated
 * {@link BbsVfxKeyframeFactories#BLEND_MODE} factory, so its keyframe channel opens the dropdown editor
 * (and steps between modes) instead of the numeric integer track. Behaves like {@code ValueInt}
 * otherwise (clamped, serialises as an int).
 */
public class ValueBlendMode extends BaseValueNumber<Integer>
{
    public ValueBlendMode(String id, int defaultValue, int min, int max)
    {
        super(id, BbsVfxKeyframeFactories.BLEND_MODE, defaultValue, min, max);
    }

    @Override
    protected Integer clamp(Integer value)
    {
        return MathUtils.clamp(value, this.min, this.max);
    }

    @Override
    public void setNumber(double value)
    {
        this.set((int) Math.round(value));
    }
}
