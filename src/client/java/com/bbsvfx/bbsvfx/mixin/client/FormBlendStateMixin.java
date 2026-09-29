package com.bbsvfx.bbsvfx.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL14;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendState;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendBoneState;
import com.bbsvfx.bbsvfx.client.BbsVfxFormBlend;
import com.bbsvfx.bbsvfx.forms.BlendMode;
import com.bbsvfx.bbsvfx.forms.IBlendBone;
import com.bbsvfx.bbsvfx.forms.ValueBlendPose;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routes a top-level form's whole-form blend to one of three handlings:
 *
 * <ul>
 *   <li><b>Whole-model, in-world fixed-function (Stage 1)</b> — fixed-function modes, no shader pack:
 *       publish {@link BbsVfxBlendState} so the per-renderer hooks set the GL blend during the in-world draw.</li>
 *   <li><b>Whole-model, deferred post-composite (Stage 2)</b> — shader-only modes or any mode under a pack:
 *       capture + cancel the in-world draw; {@link BbsVfxFormBlend} composites after the world pass.</li>
 *   <li><b>Per-bone (whole toggle off, model forms)</b> — read the {@code blend} Pose channel: hide the
 *       blended bones in-world (so the rest render normally) and defer one offscreen composite per blend
 *       group (a mode shared by a set of bones), each isolating only its bones.</li>
 * </ul>
 *
 * <p>Top-level is {@code getCurrentForm() == null}; skipped while picking and while {@link BbsVfxFormBlend#replaying}.</p>
 */
@Mixin(value = FormUtilsClient.class, remap = false)
public abstract class FormBlendStateMixin
{

    @Inject(
        method = "render(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/forms/renderers/FormRenderingContext;)V",
        at = @At("HEAD"),
        cancellable = true)
    private static void bbsvfx$beginBlend(Form form, FormRenderingContext context, CallbackInfo ci)
    {
        if (BbsVfxFormBlend.replaying || com.bbsvfx.bbsvfx.client.BbsVfxImpactSilhouette.replaying
            || form == null || FormUtilsClient.getCurrentForm() != null)
        {
            return;
        }

        if (context.isPicking() || context.stencilMap != null)
        {
            return;
        }

        /* The Iris shadow pass re-renders forms with the light's ortho matrices (shaderShadow default on).
         * Deferring that capture composites a sun-POV ghost; cancelling it would also cut the form out of
         * the shadow map. Let the shadow pass draw normally and only route the main camera pass. */
        if (BBSRendering.isIrisShadowPass())
        {
            return;
        }

        Pose pose = form.get("blend") instanceof ValueBlendPose vp ? vp.get() : null;

        /* Whole-model blend is stored in a reserved sentinel entry of the same keyframed pose (so it applies
         * immediately, like the per-bone data). A non-Normal mode there = whole-model on. */
        int mode = 0;
        float factor = 1F;

        PoseTransform wp = pose != null ? pose.transforms.get(IBlendBone.WHOLE_BONE) : null;

        if (wp != null)
        {
            mode = ((IBlendBone) wp).bbsvfx$blendMode();
            factor = ((IBlendBone) wp).bbsvfx$blendFactor();
        }

        if (mode != BlendMode.NORMAL.ordinal() && factor > 0.004F)
        {
            boolean shaderMode = !BlendMode.byIndex(mode).fixedFunction;

            if (shaderMode || BBSRendering.isIrisShadersEnabled())
            {
                FormRenderer renderer = FormUtilsClient.getRenderer(form);

                if (renderer != null)
                {
                    BbsVfxFormBlend.defer(renderer, form, context.entity,
                        new Matrix4f(RenderSystem.getProjectionMatrix()),
                        new Matrix4f(RenderSystem.getModelViewMatrix()).mul(context.stack.peek().getPositionMatrix()),
                        context.world != null ? new Matrix4f(context.world.peek().getPositionMatrix()) : null,
                        context.light, context.color, context.transition, mode, factor);

                    ci.cancel();
                }
            }
            else
            {
                BbsVfxBlendState.begin(mode, factor);
            }

            return;
        }

        /* Otherwise per-bone (model forms only — needs bones / the getPose hide hook). */
        if (form instanceof ModelForm)
        {
            bbsvfx$perBone(form, pose, context);
        }
    }

    /**
     * Sets up the per-bone blend: groups the blended bones from the {@code blend} channel by (mode, factor),
     * hides them in-world (so the unblended ones render normally) and queues an offscreen pass per group.
     * Returns true if per-bone blend was queued (the caller then leaves the in-world draw running).
     */
    private static boolean bbsvfx$perBone(Form form, Pose pose, FormRenderingContext context)
    {
        if (pose == null)
        {
            return false;
        }

        Map<Long, BbsVfxFormBlend.Group> groups = new HashMap<>();
        Set<String> blended = new HashSet<>();

        for (Map.Entry<String, PoseTransform> entry : pose.transforms.entrySet())
        {
            if (entry.getKey().equals(IBlendBone.WHOLE_BONE))
            {
                continue;
            }

            IBlendBone bone = (IBlendBone) entry.getValue();
            int mode = bone.bbsvfx$blendMode();
            float factor = bone.bbsvfx$blendFactor();

            if (mode == BlendMode.NORMAL.ordinal() || factor <= 0.004F)
            {
                continue;
            }

            blended.add(entry.getKey());

            long key = ((long) mode << 32) | Float.floatToIntBits(factor);
            BbsVfxFormBlend.Group group = groups.computeIfAbsent(key, k -> new BbsVfxFormBlend.Group(mode, factor, new HashSet<>()));
            group.bones().add(entry.getKey());
        }

        if (blended.isEmpty())
        {
            return false;
        }

        FormRenderer renderer = FormUtilsClient.getRenderer(form);

        if (renderer == null)
        {
            return false;
        }

        /* Hide the blended bones during this in-world render (the offscreen passes show them). */
        BbsVfxBlendBoneState.begin(blended);

        BbsVfxFormBlend.deferPerBone(renderer, form, context.entity,
            new Matrix4f(RenderSystem.getProjectionMatrix()),
            new Matrix4f(RenderSystem.getModelViewMatrix()).mul(context.stack.peek().getPositionMatrix()),
            context.world != null ? new Matrix4f(context.world.peek().getPositionMatrix()) : null,
            context.light, context.color, context.transition,
            FormUtilsClient.getBones(form), new ArrayList<>(groups.values()));

        return true;
    }

    @Inject(
        method = "render(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/forms/renderers/FormRenderingContext;)V",
        at = @At("RETURN"))
    private static void bbsvfx$endBlend(Form form, FormRenderingContext context, CallbackInfo ci)
    {
        if (BbsVfxFormBlend.replaying || com.bbsvfx.bbsvfx.client.BbsVfxImpactSilhouette.replaying
            || FormUtilsClient.getCurrentForm() != null)
        {
            return;
        }

        /* Stop hiding the per-bone blended bones once this form's in-world draw is done. */
        BbsVfxBlendBoneState.end();

        if (BbsVfxBlendState.active)
        {
            GlStateManager._blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.defaultBlendFunc();
            BbsVfxBlendState.end();
        }
    }
}
