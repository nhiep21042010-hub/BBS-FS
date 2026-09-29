package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Pose;

/**
 * Keyframe editor for the {@code smear_frames} channel — the standard pose bone editor, but the
 * Smear-section mixin shows the smear controls (vector + copies/fade/dissolve) here instead of in the
 * model's pose track.
 */
public class UISmearKeyframeFactory extends UIPoseKeyframeFactory
{
    public UISmearKeyframeFactory(Keyframe<Pose> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);
    }
}
