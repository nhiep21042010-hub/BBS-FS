package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.utils.pose.Pose;

/**
 * A per-bone pose value backed by the dedicated {@link BbsVfxPoseFactories#LINES} factory, so the
 * {@code motion_lines} channel gets its own keyframe editor (line transform + controls).
 */
public class ValueLinesPose extends BaseKeyframeFactoryValue<Pose>
{
    public ValueLinesPose(String id, Pose value)
    {
        super(id, BbsVfxPoseFactories.LINES, value);
    }
}
