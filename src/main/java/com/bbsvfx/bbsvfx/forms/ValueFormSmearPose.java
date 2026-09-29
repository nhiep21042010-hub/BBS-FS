package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.utils.pose.Pose;

/**
 * The single whole-form smear keyframe channel ({@code smear}) for non-model forms, backed by the
 * dedicated {@link BbsVfxPoseFactories#FORM_SMEAR} factory so it gets its own editor. All the smear
 * parameters live in a single sentinel {@link mchorse.bbs_mod.utils.pose.PoseTransform} entry (the form
 * has no bones), reusing the per-bone smear fields woven into PoseTransform — so it's ONE animatable track.
 */
public class ValueFormSmearPose extends BaseKeyframeFactoryValue<Pose>
{
    public ValueFormSmearPose(String id, Pose value)
    {
        super(id, BbsVfxPoseFactories.FORM_SMEAR, value);
    }
}
