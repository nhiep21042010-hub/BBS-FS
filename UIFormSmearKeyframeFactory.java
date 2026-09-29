package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Pose;
import com.bbsvfx.bbsvfx.forms.IFormSmear;

import java.util.List;

/**
 * Keyframe editor for the whole-form {@code smear} channel (non-model forms). Reuses the per-bone smear
 * editor (the smear controls are shown via {@code UIPoseKeyframeFactorySmearMixin}, keyed on
 * {@code instanceof UISmearKeyframeFactory}), but the base class only wires + populates the pose editor
 * for Model/Mob forms. Here we wire it to the keyframe value and seed the bone list with the single
 * sentinel "form" entry, then {@code fillGroups} auto-selects it — so the smear controls always edit the
 * sentinel (otherwise nothing is selected and toggling e.g. Arc writes nowhere).
 */
public class UIFormSmearKeyframeFactory extends UISmearKeyframeFactory
{
    public UIFormSmearKeyframeFactory(Keyframe<Pose> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        this.poseEditor.setPose(keyframe.getValue(), IFormSmear.SENTINEL);
        this.poseEditor.fillGroups(List.of(IFormSmear.SENTINEL), true);
    }
}
