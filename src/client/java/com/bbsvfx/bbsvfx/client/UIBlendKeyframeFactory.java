package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Pose;

/**
 * Keyframe editor for the {@code blend} channel — the standard pose bone editor, with the per-bone blend
 * block AND the whole-model controls added by {@code UIPoseEditorBlendMixin} (both edit the same keyframed
 * pose, so everything lives in this one track).
 *
 * <p>The base class only wires the pose editor to the keyframe for Model/Mob forms; for every OTHER form
 * type (block, billboard, item, …) it leaves the pose editor unconnected, so the whole-model controls would
 * edit nothing. We connect it here so the whole-model blend works on all form types.</p>
 */
public class UIBlendKeyframeFactory extends UIPoseKeyframeFactory
{
    public UIBlendKeyframeFactory(Keyframe<Pose> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        if (this.poseEditor.getPose() == null)
        {
            this.poseEditor.setPose(keyframe.getValue(), "");
        }

        ((IBlendPoseEditor) this.poseEditor).bbsvfx$syncBlend();
    }
}
