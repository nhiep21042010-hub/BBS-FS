package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.utils.pose.Pose;

/**
 * A per-bone pose value backed by the dedicated {@link BbsVfxPoseFactories#BLEND} factory, so the
 * {@code blend_mode} channel gets its own keyframe editor (bone list + per-bone blend mode + the
 * whole-model toggle) instead of the plain pose one. The per-bone blend data lives in {@code PoseTransform}
 * ({@code PoseTransformBlendMixin}).
 */
public class ValueBlendPose extends BaseKeyframeFactoryValue<Pose>
{
    public ValueBlendPose(String id, Pose value)
    {
        super(id, BbsVfxPoseFactories.BLEND, value);
    }
}
