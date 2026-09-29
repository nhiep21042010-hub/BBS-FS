package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.pose.Pose;
import com.bbsvfx.bbsvfx.forms.ValueBlendPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a whole-form compositing blend mode to every {@link Form} (except {@link LabelForm}, which has its
 * own). The form's pixels composite against the scene with a {@link com.bbsvfx.bbsvfx.forms.BlendMode}
 * instead of normal alpha. Values:
 *
 * <ul>
 *   <li>{@code blend} — a Pose channel (the single visible track): per-bone blend mode + strength, with a
 *       bone list in its editor. Used when {@code blend_whole} is off.</li>
 *   <li>{@code blend_whole} — toggle (default on): apply ONE mode to the whole model (the simple case)
 *       vs. per selected bone.</li>
 *   <li>{@code blend_mode} / {@code blend_factor} — the whole-model mode + strength (invisible: edited via
 *       the panel / track editor, not separate tracks). The render reads these when {@code blend_whole}.</li>
 * </ul>
 *
 * <p>The render application is per-renderer for the whole-model path (model / consumer / curve / direct),
 * and a per-bone offscreen pass when {@code blend_whole} is off.</p>
 */
@Mixin(Form.class)
public abstract class BlendFormMixin
{
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addBlend(CallbackInfo ci)
    {
        /* LabelForm already has its own blend mode (the label overhaul, stored under the same "blend_mode"
         * id), so skip it here to avoid colliding / double-blending — labels composite their own text. */
        if ((Object) this instanceof LabelForm)
        {
            return;
        }

        ValueGroup self = (ValueGroup) (Object) this;

        /* The single visible track: a Pose channel carrying BOTH the per-bone blend (mode + factor per bone,
         * via PoseTransformBlendMixin) AND the whole-model blend (in a reserved sentinel entry,
         * {@link com.bbsvfx.bbsvfx.forms.IBlendBone#WHOLE_BONE}). Keeping everything in this one keyframed
         * channel means edits apply to the rendered entity each frame (immediately), unlike a plain form
         * value which only syncs on entity recreation. */
        self.add(new ValueBlendPose("blend", new Pose()));
    }
}
