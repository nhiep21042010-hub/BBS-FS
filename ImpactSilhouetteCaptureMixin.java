package com.bbsvfx.bbsvfx.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.camera.BbsVfxImpactState;
import com.bbsvfx.bbsvfx.client.SmearReplayState;
import com.bbsvfx.bbsvfx.client.BbsVfxFormBlend;
import com.bbsvfx.bbsvfx.client.BbsVfxImpactSilhouette;

/**
 * Captures every top-level actor form's render while an {@link com.bbsvfx.bbsvfx.camera.ImpactClip} runs the
 * silhouette layer, so {@link BbsVfxImpactSilhouette} can re-render them in isolation after the world pass.
 * Unlike the blend capture this does NOT cancel the in-world draw — the impact composite overwrites the
 * frame anyway, and capturing the matrices is all we need.
 *
 * <p>Top-level is {@code getCurrentForm() == null}; skipped while picking and while either offscreen replay
 * is running (blend or our own).</p>
 */
@Mixin(value = FormUtilsClient.class, remap = false)
public abstract class ImpactSilhouetteCaptureMixin
{
    @Inject(
        method = "render(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/forms/renderers/FormRenderingContext;)V",
        at = @At("HEAD"))
    private static void bbsvfx$captureSilhouette(Form form, FormRenderingContext context, CallbackInfo ci)
    {
        if (!BbsVfxImpactState.active || BbsVfxImpactState.silhouette <= 0.004F)
        {
            return;
        }

        /* TEMP DIAG (unconditional for the hunt): every form render while the impact clip is
         * active, with the guard inputs — the "All Actors shows copies" hunt needs to see what
         * is rejected and why, not only what lands. */
        org.slf4j.LoggerFactory.getLogger("bbsvfx").info(
            "[impact-diag] render form={} replayNull={} replayIndex={} silTarget={} picking={} stencil={} subform={} irisShadow={}",
            form == null ? "null" : form.getClass().getSimpleName(),
            SmearReplayState.replay == null, SmearReplayState.replayIndex,
            BbsVfxImpactState.silTarget, context.isPicking(), context.stencilMap != null,
            FormUtilsClient.getCurrentForm() != null, BBSRendering.isIrisShadowPass());

        if (BbsVfxImpactSilhouette.replaying || BbsVfxFormBlend.replaying)
        {
            return;
        }

        /* Forms render AGAIN during the Iris shadow pass (shaderShadow is on by default) with the light's
         * ortho matrices — capturing that pass adds a sun-POV ghost to the coverage buffer. Main pass only. */
        if (BBSRendering.isIrisShadowPass())
        {
            return;
        }

        if (form == null || FormUtilsClient.getCurrentForm() != null)
        {
            return;
        }

        if (context.isPicking() || context.stencilMap != null)
        {
            return;
        }

        /* All Actors means all actors OF THE FILM: the replay bridge is only active inside
         * BaseFilmController.renderEntity, so without this origin check "all" also captured
         * world model blocks, player morphs, gun projectiles and held items — the unknown
         * geometry testers saw in the silhouette. "Actor N" needs no explicit check: its
         * replayIndex match already implies an active bridge. */
        if (SmearReplayState.replay == null)
        {
            return;
        }

        /* Target actor filter: -1 = all; otherwise only the actor whose replay index matches (resolved by
         * the SmearReplayState bridge for the current actor render). */
        if (BbsVfxImpactState.silTarget >= 0 && SmearReplayState.replayIndex != BbsVfxImpactState.silTarget)
        {
            return;
        }

        FormRenderer renderer = FormUtilsClient.getRenderer(form);

        if (renderer == null)
        {
            return;
        }

        /* TEMP DIAG (unconditional for the hunt): name every silhouette capture. */
        org.slf4j.LoggerFactory.getLogger("bbsvfx").info(
            "[impact-diag] capture form={} renderer={} replayIndex={} entity={}",
            form.getClass().getSimpleName(), renderer.getClass().getSimpleName(),
            SmearReplayState.replayIndex, context.entity);

        BbsVfxImpactSilhouette.defer(renderer, form, context.entity,
            new Matrix4f(RenderSystem.getProjectionMatrix()),
            new Matrix4f(RenderSystem.getModelViewMatrix()).mul(context.stack.peek().getPositionMatrix()),
            context.world != null ? new Matrix4f(context.world.peek().getPositionMatrix()) : null,
            context.light, context.color, context.transition);
    }
}
