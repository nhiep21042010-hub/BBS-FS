package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.utils.pose.Pose;

/**
 * Duck interface mixed onto BBS's {@code ModelForm} exposing two extra per-bone keyframe channels —
 * {@code smear_frames} and {@code motion_lines} ({@link ValueSmearPose} / {@link ValueLinesPose}, each
 * with its own keyframe factory so it shows as a separate pose-related track with its own editor and
 * animates independently of the model's pose). The per-bone smear/line data lives in those poses'
 * {@code PoseTransform}s (via {@code PoseTransformSmearMixin}).
 */
public interface IModelSmearChannels
{
    BaseKeyframeFactoryValue<Pose> bbsvfx$smearPose();

    BaseKeyframeFactoryValue<Pose> bbsvfx$linesPose();
}
