package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.CurveForm;

/**
 * Surfaces a {@link CurveForm}'s control-point bones in the pose keyframe editor. BBS's
 * {@code UIPoseKeyframeFactory} only fills the bone list for {@code ModelForm} / {@code MobForm}; for
 * any other form it leaves the editor empty. This adds a {@code CurveForm} branch — mirroring the
 * MobForm path — so each point ({@code point_0}, {@code point_1}, ...) becomes an animatable pose bone
 * in the replay editor.
 */
@Mixin(UIPoseKeyframeFactory.class)
public abstract class UIPoseKeyframeFactoryMixin
{
    @Shadow public UIPoseKeyframeFactory.UIPoseFactoryEditor poseEditor;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$fillCurveBones(Keyframe keyframe, UIKeyframes editor, CallbackInfo ci)
    {
        UIKeyframeSheet sheet = editor.getGraph().getSheet(keyframe);

        if (FormUtils.getForm(sheet.property) instanceof CurveForm curve)
        {
            this.poseEditor.setPose((Pose) keyframe.getValue(), "");
            this.poseEditor.fillGroups(FormUtilsClient.getRenderer(curve).getBones(), false);
        }
    }
}
