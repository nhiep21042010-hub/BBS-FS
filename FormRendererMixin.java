package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.BbsVfxAeTracker;

/**
 * Captures the world transform of tracked anchors during a video export. {@code FormRenderer.render}
 * maintains a {@code context.world} matrix stack alongside the view stack; right after the form's own
 * transform is applied (just after {@code render3D}), that stack holds the form's full world matrix —
 * including the bone (e.g. head) it is attached to. For a tracked {@link AnchorForm} we hand that
 * matrix to {@link BbsVfxAeTracker} for the current frame.
 *
 * <p>Gated to the export world pass only: an active capture session, not the UI/picking/shadow passes.</p>
 */
@Mixin(targets = "mchorse.bbs_mod.forms.renderers.FormRenderer")
public abstract class FormRendererMixin
{
    @Shadow protected Form form;

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/forms/renderers/FormRenderer;render3D(Lmchorse/bbs_mod/forms/renderers/FormRenderingContext;)V",
            shift = At.Shift.AFTER
        )
    )
    private void bbsvfx$captureAnchor(FormRenderingContext context, CallbackInfo ci)
    {
        if (!BbsVfxAeTracker.isCapturing()
            || context.world == null
            || context.ui
            || context.stencilMap != null
            || BBSRendering.isIrisShadowPass()
            || !(this.form instanceof AnchorForm))
        {
            return;
        }

        BaseValue value = this.form.get("xavin_tracker");

        if (!(value instanceof ValueBoolean tracker) || !tracker.get())
        {
            return;
        }

        Matrix4f world = new Matrix4f(context.world.peek().getPositionMatrix());

        BbsVfxAeTracker.stageAnchor(this.form, this.form.name.get(), world);
    }
}
