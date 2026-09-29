package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.forms.FormUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Files the {@code follow_offset} form property under the replay editor's "Pose-related tracks" tab
 * (next to transform / pose) by treating it as a pose property. {@code isPoseProperty} drives only the
 * UI track categorisation (POSE tab = pose properties, MODEL tab = the rest); the actual pose-bone
 * application uses {@code PerLimbService.isPoseBoneChannel}, so this does not change how the value is
 * applied — like {@code transform} / {@code shape_keys}, it is a non-bone track shown in the pose tab.
 */
@Mixin(FormUtils.class)
public abstract class FormUtilsPoseMixin
{
    @Inject(method = "isPoseProperty", at = @At("HEAD"), cancellable = true)
    private static void bbsvfx$followOffsetIsPose(String name, CallbackInfoReturnable<Boolean> cir)
    {
        /* NOTE: "blend" is intentionally NOT here — it must show in the MODEL category tab (which exists for
         * every form type) so the whole-model blend is reachable on non-model forms too; the POSE tab is
         * empty/hidden for them. Its editor (UIBlendKeyframeFactory) works regardless of tab. */
        if (name != null && (name.startsWith("follow_offset") || name.equals("smear_frames") || name.equals("motion_lines")))
        {
            cir.setReturnValue(true);
        }
    }
}
