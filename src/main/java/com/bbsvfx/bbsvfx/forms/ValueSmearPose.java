package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.utils.pose.Pose;

/**
 * A per-bone pose value backed by the dedicated {@link BbsVfxPoseFactories#SMEAR} factory, so the
 * {@code smear_frames} channel gets its own keyframe editor (smear controls) rather than the pose one.
 */
public class ValueSmearPose extends BaseKeyframeFactoryValue<Pose>
{
    public ValueSmearPose(String id, Pose value)
    {
        super(id, BbsVfxPoseFactories.SMEAR, value);
    }
}
