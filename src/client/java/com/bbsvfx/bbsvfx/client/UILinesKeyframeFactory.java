package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Pose;

/**
 * Keyframe editor for the {@code motion_lines} channel — the standard pose bone editor (its per-bone
 * {@code transform} is the line UIPropTransform), plus the line controls (count / thickness / spread)
 * shown by the Smear-section mixin.
 */
public class UILinesKeyframeFactory extends UIPoseKeyframeFactory
{
    public UILinesKeyframeFactory(Keyframe<Pose> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);
    }
}
