package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.SmearRenderState;

/**
 * Per-bone smear copies isolate the smeared bones by alpha-hiding the rest of the MODEL — but body-part
 * attachments (eyes, scarf, held items ...) are nested forms rendered at their bone's matrix, which the
 * bone alpha does not touch, so every echo copy re-drew ALL attachments (tester report: smear on
 * right_arm duplicated the eye/scarf attachments). Skip a part during a copy pass unless its bone is in
 * the smeared (shown) subtree — attachments on the smeared bone still ride the copies, like the bone's
 * own geometry. Root-attached parts (empty bone) are never part of a smeared subtree, so they skip too.
 */
@Mixin(value = FormRenderer.class, remap = false)
public abstract class FormRendererBodyPartSmearMixin
{
    @Inject(method = "renderBodyPart", at = @At("HEAD"), cancellable = true)
    private void bbsvfx$skipHiddenBoneParts(BodyPart part, FormRenderingContext context, CallbackInfo ci)
    {
        if (!SmearRenderState.active || (Object) this != SmearRenderState.target)
        {
            return;
        }

        /* A TRAIL form is stateful (it accumulates a position history) — re-rendering it per echo copy
         * at rewound poses feeds that history garbage and the trail wildly distorts (tester report).
         * Trails render in the crisp pass only, whatever bone they sit on. */
        if (part.getForm() instanceof mchorse.bbs_mod.forms.forms.TrailForm)
        {
            ci.cancel();

            return;
        }

        if (SmearRenderState.hide == null)
        {
            return;
        }

        String bone = part.bone.get();

        if (bone == null || bone.isEmpty() || SmearRenderState.hide.contains(bone))
        {
            ci.cancel();
        }
    }
}
