package com.bbsvfx.bbsvfx.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.bbsvfx.bbsvfx.client.IArcTrailDrawer;
import com.bbsvfx.bbsvfx.client.SmearRenderState;
import com.bbsvfx.bbsvfx.client.SmearReplayState;
import com.bbsvfx.bbsvfx.client.BbsVfxArcTrail;
import com.bbsvfx.bbsvfx.forms.ISmearBone;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-bone smear render hook (WrapOperation, so it coexists with other mods hooking the same call,
 * e.g. IRLights). Wraps the single {@code renderer.render(context)} call inside
 * {@link FormUtilsClient#render}: when a model form has bones carrying a smear vector (set per bone in
 * the pose editor, animated with the pose), the model is drawn several times — each copy displacing
 * only the smeared bones along their vector (via {@code ModelFormRendererPoseMixin}) with fading
 * opacity — then the crisp model on top. Non-model forms and picking passes render once.
 */
@Mixin(value = FormUtilsClient.class, remap = false)
public abstract class SmearRenderMixin
{

    @WrapOperation(
        method = "render(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/forms/renderers/FormRenderingContext;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/forms/renderers/FormRenderer;render(Lmchorse/bbs_mod/forms/renderers/FormRenderingContext;)V"))
    private static void bbsvfx$smear(FormRenderer renderer, FormRenderingContext context, Operation<Void> wrapped, @Local(argsOnly = true) Form form)
    {
        /* Impact-silhouette replay gets the CRISP model only: the coverage buffer fills every pass
         * it receives with one flat colour, so running the smear redirect there (intended as "trail
         * in the silhouette") printed the crisp model plus up to 16 dense copies, 4 multiples and
         * the motion lines as ~20 flat character fills — "very many copies though there is one
         * actor", and disembodied limbs on a per-bone smear. The canonical impact frame is a single
         * clean silhouette; the live scene keeps its smear untouched. */
        if (context.stencilMap != null || SmearRenderState.active
            || com.bbsvfx.bbsvfx.client.BbsVfxImpactSilhouette.replaying)
        {
            wrapped.call(renderer, context);
            return;
        }

        /* Non-model forms (block / item / billboard / label / extruded / trail / framebuffer / mob): the
         * WHOLE-form smear, driven by the single `smear` channel sentinel — re-pose the whole form at past
         * film times (rewinds its transform). No per-bone pose, no isolation. */
        if (!(renderer instanceof ModelFormRenderer model))
        {
            if (form instanceof com.bbsvfx.bbsvfx.forms.IFormSmear fs)
            {
                Pose fp = fs.bbsvfx$formSmearPose();

                /* The sentinel is seeded when the form is built, but a form read back from saved data
                 * gets its whole transform map replaced, and BBS 2.6 no longer creates an entry just
                 * because someone read one. A missing sentinel is simply "no smear configured". */
                ISmearBone smear = fp == null ? null
                    : (ISmearBone) fp.get(com.bbsvfx.bbsvfx.forms.IFormSmear.SENTINEL);

                if (smear != null)
                {
                    /* Arc (auto) needs the replay bridge to re-pose at past times. */
                    if (smear.bbsvfx$smearArc() > 0F && SmearReplayState.has(context.entity))
                    {
                        bbsvfx$renderFormArc(wrapped, renderer, context, form, smear);
                        return;
                    }

                    /* Manual vector smear (no bridge needed) — offset the whole form's transform. */
                    if (smear.bbsvfx$smearArc() <= 0F && smear.bbsvfx$smearManual() > 0F)
                    {
                        bbsvfx$renderFormVector(wrapped, renderer, context, form, smear);
                        return;
                    }
                }
            }

            wrapped.call(renderer, context);
            return;
        }

        Pose pose = model.getPose();

        /* Classify smeared bones by mode (one bone = one mode): arc (auto) vs manual vector. Different
         * bones can use different modes in the same frame (arm = arc, head = vector). */
        Set<String> arcBones = new HashSet<>();
        Set<String> vectorBones = new HashSet<>();

        for (Map.Entry<String, PoseTransform> entry : pose.transforms.entrySet())
        {
            ISmearBone s = (ISmearBone) entry.getValue();

            if (s.bbsvfx$smearArc() > 0F)
            {
                arcBones.add(entry.getKey());
            }
            else if (s.bbsvfx$smearManual() > 0F)
            {
                vectorBones.add(entry.getKey());
            }
        }

        boolean doArc = !arcBones.isEmpty() && SmearReplayState.has(context.entity);
        boolean doVector = !vectorBones.isEmpty();

        if (!doArc && !doVector)
        {
            wrapped.call(renderer, context);
            return;
        }

        int original = context.color;
        Set<String> allBones = new HashSet<>(model.getBones());

        /* The whole multi-pass render is exception-guarded: SmearRenderState is STATIC, so an exception
         * mid-copy (e.g. inside a deferred blend/silhouette replay whose catch swallows it) would leak an
         * active hide set into every following render — bones alpha-hidden, body parts skipped, visible
         * as flicker / vanishing model parts. */
        try
        {
            /* Crisp model once (writes depth); then each pass adds its isolated copies on top. The motion lines
             * defer (linesPending) so they draw AFTER all copies — on top, binding the arc. */
            SmearRenderState.linesPending = true;
            context.color = original;
            wrapped.call(renderer, context);

            if (doArc)
            {
                Set<String> hide = new HashSet<>(allBones);
                hide.removeAll(bbsvfx$withDescendants(model, arcBones));
                bbsvfx$arcCopies(wrapped, renderer, model, context, form, pose, arcBones, hide, original);
            }

            if (doVector)
            {
                Set<String> hide = new HashSet<>(allBones);
                hide.removeAll(bbsvfx$withDescendants(model, vectorBones));
                bbsvfx$vectorCopies(wrapped, renderer, model, context, pose, vectorBones, hide, original);
            }
        }
        finally
        {
            SmearRenderState.end();
            SmearRenderState.linesPending = false;
            context.color = original;
        }

        /* Now lay the motion lines over the finished copies. */
        ((com.bbsvfx.bbsvfx.client.IMotionLineDrawer) model).bbsvfx$drawDeferredLines();
    }

    /** Manual vector smear copies for the given bones (isolated): each copy offsets only these bones along
     *  their smear vector, fading. Drawn on top of the crisp model; copies write depth (occlude entities). */
    private static void bbsvfx$vectorCopies(Operation<Void> wrapped, FormRenderer renderer, ModelFormRenderer model, FormRenderingContext context, Pose pose, Set<String> vectorBones, Set<String> hide, int original)
    {
        ISmearBone lead = (ISmearBone) pose.transforms.get(vectorBones.iterator().next());
        int count = Math.max(1, Math.round(lead.bbsvfx$smearCount() > 0F ? lead.bbsvfx$smearCount() : 4F));
        float falloff = Math.max(lead.bbsvfx$smearFalloff() > 0F ? lead.bbsvfx$smearFalloff() : 0.6F, 0.001F);
        float dissolveAmount = lead.bbsvfx$smearDissolve();

        for (int k = count; k >= 1; k--)
        {
            float fraction = (float) k / count;
            float alpha = (float) Math.pow(falloff, k);

            SmearRenderState.begin(model, -fraction, 1.5F, fraction * dissolveAmount);
            SmearRenderState.hide = hide;
            context.color = bbsvfx$withAlpha(original, alpha);
            wrapped.call(renderer, context);
            SmearRenderState.end();
        }
    }

    /**
     * Arc smear: draw the model several times, each copy posed at an earlier film time (limb swing
     * source). The historical pose is re-evaluated by {@code replay.properties.applyProperties(form, T)}
     * — the same call the controller uses each frame, just at {@code T = now - fraction * timeTicks}.
     * Only the arc-smeared bone(s) stay visible per copy (the rest are alpha-hidden), so just that limb
     * trails. After the copies, the present pose is re-applied and the crisp model drawn on top.
     */
    private static void bbsvfx$arcCopies(Operation<Void> wrapped, FormRenderer renderer, ModelFormRenderer model, FormRenderingContext context, Form form, Pose pose, Set<String> arcBones, Set<String> hide, int original)
    {
        ISmearBone lead = (ISmearBone) pose.transforms.get(arcBones.iterator().next());
        int count = Math.max(1, Math.round(lead.bbsvfx$smearCount() > 0F ? lead.bbsvfx$smearCount() : 4F));
        /* Real smear-frame "multiples" are SOLID, distinct copies (not a faded motion-blur streak). So
         * fade defaults to 0 = fully opaque; the slider only optionally thins the copies toward the tail. */
        float fade = Math.max(0F, Math.min(1F, lead.bbsvfx$smearFalloff()));
        float dissolveAmount = lead.bbsvfx$smearDissolve();
        float timeTicks = lead.bbsvfx$smearTime() > 0F ? lead.bbsvfx$smearTime() : 4F;
        /* Deformation strength (length stretch + squash); 0 = OFF (no stretch — e.g. for a head turn,
         * which only wants plain multiples, not a vertical pyramid). Limbs set it > 0. */
        float baseStretch = Math.max(0F, lead.bbsvfx$smearStretch());

        Replay replay = SmearReplayState.replay;
        float now = SmearReplayState.time();

        /* MOTION PRE-CHECK (the "smear lags everyone" fix): a film keeps the smear track on for long
         * stretches while the limb only swings briefly — when NOTHING moved over the trail window, every
         * copy lands exactly under the crisp model (the animator isn't rewound, only keyframes are), i.e.
         * ~20 full model re-renders per frame that are entirely invisible. Skip them. */
        if (!bbsvfx$rewindMoves(form, replay, model, now, timeTicks))
        {
            replay.properties.applyProperties(form, now);

            return;
        }

        RenderSystem.depthMask(false);

        /* Motion-trail/fan parked — connection now comes from dense overlapping copies (SMEAR-style),
         * not separate geometry.
         * BbsVfxArcTrail.begin(); */

        /* OVERLAP BAND: many faint, deformed copies finely sampled along the trajectory. With enough
         * density their stretched blades overlap into a CONTINUOUS smear (no gaps between the multiples).
         * Drawn first (behind), faint; the crisp multiples then sit on top. */
        int density = Math.round(lead.bbsvfx$smearDensity() > 0F ? lead.bbsvfx$smearDensity() : 16F);
        float bandOpacity = lead.bbsvfx$smearOpacity() > 0F ? lead.bbsvfx$smearOpacity() : 0.14F;

        for (int j = density; j >= 1; j--)
        {
            float f = (float) j / density;
            float tk = now - f * timeTicks;
            float copyStretch = baseStretch * f;

            replay.properties.applyProperties(form, tk);

            Matrix4f root = bbsvfx$rootBegin(context, replay, tk, now);
            SmearRenderState.beginArc(model, f * dissolveAmount, hide, copyStretch, f, pose);
            context.color = bbsvfx$withAlpha(bbsvfx$desaturate(original, 0.6F * f), bandOpacity);
            wrapped.call(renderer, context);
            SmearRenderState.end();
            bbsvfx$rootEnd(context, root);
        }

        /* Crisp multiples WRITE depth (depthMask on) so they occlude mobs/entities behind them — they sit
         * at past poses, away from the crisp body, so they don't punch it. Only the faint band is off. */
        RenderSystem.depthMask(true);

        /* Farthest copy first → nearest painted last, on top. Copies stay SOLID (alpha 1) unless the
         * fade slider thins them toward the tail — distinct "multiples", not a translucent blur. */
        for (int k = count; k >= 1; k--)
        {
            float f = (float) k / count;
            float tk = now - f * timeTicks;
            float alpha = 1F - fade * f;
            /* Deformation grows steadily toward the tail (stable per frame): near copies barely change,
             * far copies stretch + squash + wash out toward grey into abstract smears. */
            float copyStretch = baseStretch * f;

            replay.properties.applyProperties(form, tk);

            Matrix4f root = bbsvfx$rootBegin(context, replay, tk, now);
            SmearRenderState.beginArc(model, f * dissolveAmount, hide, copyStretch, f, pose);
            context.color = bbsvfx$withAlpha(bbsvfx$desaturate(original, 0.6F * f), alpha);
            wrapped.call(renderer, context);
            SmearRenderState.end();
            bbsvfx$rootEnd(context, root);
        }

        /* Motion-trail parked.
         * ((IArcTrailDrawer) model).bbsvfx$drawArcTrail(context);
         * BbsVfxArcTrail.end(); */


        /* Restore the present frame on the form (the next frame's controller re-applies it anyway). */
        replay.properties.applyProperties(form, now);
        context.color = original;
    }

    /**
     * True when rewinding by the trail window actually changes something visible: the actor's root
     * x/y/z keyframes, or ANY bone of the keyframed pose (an ancestor's rotation moves the shown
     * subtree in world space, so the check can't be limited to the smeared bones). Leaves the form
     * posed at {@code now - timeTicks} when it returns true (the copy loops re-pose per copy anyway);
     * the caller restores {@code now} on false.
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean bbsvfx$rewindMoves(Form form, Replay replay, ModelFormRenderer model, float now, float timeTicks)
    {
        float far = now - timeTicks;

        if (Math.abs(replay.keyframes.x.interpolate(far).doubleValue() - replay.keyframes.x.interpolate(now).doubleValue()) > 1e-4
            || Math.abs(replay.keyframes.y.interpolate(far).doubleValue() - replay.keyframes.y.interpolate(now).doubleValue()) > 1e-4
            || Math.abs(replay.keyframes.z.interpolate(far).doubleValue() - replay.keyframes.z.interpolate(now).doubleValue()) > 1e-4)
        {
            return true;
        }

        Pose present = model.getPose();

        replay.properties.applyProperties(form, far);

        Pose rewound = model.getPose();

        return !rewound.transforms.equals(present.transforms);
    }

    /**
     * Whole-form arc smear (non-model forms): re-render the form several times posed at earlier film times
     * (the time-rewind rewinds the form's transform, so a transform-animated form trails along its motion).
     * A dense faint overlap band fills the gaps, the crisp multiples sit on top. No bone isolation and no
     * geometric deformation (the whole form is one piece) — connection comes from the overlap.
     */
    private static void bbsvfx$renderFormArc(Operation<Void> wrapped, FormRenderer renderer, FormRenderingContext context, Form form, ISmearBone smear)
    {
        int count = Math.max(1, Math.round(smear.bbsvfx$smearCount() > 0F ? smear.bbsvfx$smearCount() : 4F));
        float fade = Math.max(0F, Math.min(1F, smear.bbsvfx$smearFalloff()));
        float dissolveAmount = smear.bbsvfx$smearDissolve();
        float timeTicks = smear.bbsvfx$smearTime() > 0F ? smear.bbsvfx$smearTime() : 4F;
        int density = Math.round(smear.bbsvfx$smearDensity() > 0F ? smear.bbsvfx$smearDensity() : 16F);
        float bandOpacity = smear.bbsvfx$smearOpacity() > 0F ? smear.bbsvfx$smearOpacity() : 0.14F;
        int original = context.color;

        Replay replay = SmearReplayState.replay;
        float now = SmearReplayState.time();

        /* Crisp current form first (writes depth), then copies on top with depth-write off. */
        context.color = original;
        wrapped.call(renderer, context);

        RenderSystem.depthMask(false);

        /* Overlap band — many faint copies finely sampled along the trajectory. */
        for (int j = density; j >= 1; j--)
        {
            float f = (float) j / density;
            float tk = now - f * timeTicks;

            replay.properties.applyProperties(form, tk);
            Matrix4f root = bbsvfx$rootBegin(context, replay, tk, now);
            SmearRenderState.beginArc(renderer, f * dissolveAmount, null, 0F, f, null);
            context.color = bbsvfx$withAlpha(bbsvfx$desaturate(original, 0.6F * f), bandOpacity);
            wrapped.call(renderer, context);
            SmearRenderState.end();
            bbsvfx$rootEnd(context, root);
        }

        /* Crisp multiples on top — these DO write depth (depthMask on) so they occlude entities/mobs behind
         * them, unlike the faint overlap band (a single whole form, so its copies don't punch the crisp). */
        RenderSystem.depthMask(true);

        for (int k = count; k >= 1; k--)
        {
            float f = (float) k / count;
            float tk = now - f * timeTicks;
            float alpha = 1F - fade * f;

            replay.properties.applyProperties(form, tk);
            Matrix4f root = bbsvfx$rootBegin(context, replay, tk, now);
            SmearRenderState.beginArc(renderer, f * dissolveAmount, null, 0F, f, null);
            context.color = bbsvfx$withAlpha(bbsvfx$desaturate(original, 0.6F * f), alpha);
            wrapped.call(renderer, context);
            SmearRenderState.end();
            bbsvfx$rootEnd(context, root);
        }

        replay.properties.applyProperties(form, now);
        context.color = original;
    }

    /**
     * Whole-form manual VECTOR smear (non-model forms, no arc): render the form several times, each copy
     * offsetting the form's whole transform along the smear vector — a fading trail, the first-impl look
     * but for the whole form. No time-rewind / bridge needed.
     */
    private static void bbsvfx$renderFormVector(Operation<Void> wrapped, FormRenderer renderer, FormRenderingContext context, Form form, ISmearBone smear)
    {
        int count = Math.max(1, Math.round(smear.bbsvfx$smearCount() > 0F ? smear.bbsvfx$smearCount() : 4F));
        float falloff = Math.max(smear.bbsvfx$smearFalloff() > 0F ? smear.bbsvfx$smearFalloff() : 0.6F, 0.001F);
        float dissolveAmount = smear.bbsvfx$smearDissolve();
        float sx = smear.bbsvfx$smearX();
        float sy = smear.bbsvfx$smearY();
        float sz = smear.bbsvfx$smearZ();
        int original = context.color;

        org.joml.Vector3f translate = form.transform.get().translate;
        float ox = translate.x;
        float oy = translate.y;
        float oz = translate.z;

        /* Trailing copies first (behind, fading), then the crisp form on top — same order as the per-bone
         * vector smear. Default depth (copies occlude entities; they're offset from the crisp form). */
        for (int k = count; k >= 1; k--)
        {
            float fraction = (float) k / count;
            float alpha = (float) Math.pow(falloff, k);

            translate.set(ox - sx * fraction, oy - sy * fraction, oz - sz * fraction);
            SmearRenderState.beginArc(renderer, fraction * dissolveAmount, null, 0F, fraction, null);
            context.color = bbsvfx$withAlpha(original, alpha);
            wrapped.call(renderer, context);
            SmearRenderState.end();
        }

        translate.set(ox, oy, oz);
        context.color = original;
        wrapped.call(renderer, context);
    }

    /**
     * Root-motion: offset the actor's whole world position to where it was at {@code tk} (it moves via the
     * replay's x/y/z keyframes, applied to the entity matrix OUTSIDE the form render — so the smear has to
     * shift the stack itself). The world delta is rotated into view space (camera rotation) and prepended
     * to the stack matrix; returns the original matrix to restore after the copy. Null = actor didn't move.
     */
    private static Matrix4f bbsvfx$rootBegin(FormRenderingContext context, Replay replay, float tk, float now)
    {
        double dx = replay.keyframes.x.interpolate(tk).doubleValue() - replay.keyframes.x.interpolate(now).doubleValue();
        double dy = replay.keyframes.y.interpolate(tk).doubleValue() - replay.keyframes.y.interpolate(now).doubleValue();
        double dz = replay.keyframes.z.interpolate(tk).doubleValue() - replay.keyframes.z.interpolate(now).doubleValue();

        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < 1e-5)
        {
            return null;
        }

        org.joml.Vector3f delta = new org.joml.Vector3f((float) dx, (float) dy, (float) dz);
        org.joml.Quaternionf camRot = net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera().getRotation();

        new org.joml.Quaternionf(camRot).conjugate().transform(delta);

        Matrix4f m = context.stack.peek().getPositionMatrix();
        Matrix4f saved = new Matrix4f(m);

        m.set(new Matrix4f().translation(delta).mul(m));

        return saved;
    }

    private static void bbsvfx$rootEnd(FormRenderingContext context, Matrix4f saved)
    {
        if (saved != null)
        {
            context.stack.peek().getPositionMatrix().set(saved);
        }
    }

    /**
     * Expand a set of smeared bones to include all their DESCENDANTS, so smearing a bone shows its whole
     * sub-tree (smear the root bone → the whole body; smear an upper arm → the whole arm). Without this the
     * isolation hid the children and a "whole body" smear showed nothing.
     */
    private static Set<String> bbsvfx$withDescendants(ModelFormRenderer model, Set<String> bones)
    {
        Set<String> result = new HashSet<>(bones);

        if (model.getModel() == null || model.getModel().model == null)
        {
            return result;
        }

        java.util.Deque<String> stack = new java.util.ArrayDeque<>(bones);

        while (!stack.isEmpty())
        {
            java.util.Collection<String> children = model.getModel().model.getDirectChildrenKeys(stack.pop());

            if (children != null)
            {
                for (String child : children)
                {
                    if (result.add(child))
                    {
                        stack.push(child);
                    }
                }
            }
        }

        return result;
    }

    private static ISmearBone bbsvfx$leadSmear(Pose pose)
    {
        for (PoseTransform pt : pose.transforms.values())
        {
            ISmearBone smear = (ISmearBone) pt;

            if (smear.bbsvfx$hasSmear())
            {
                return smear;
            }
        }

        return null;
    }

    private static int bbsvfx$withAlpha(int color, float alpha)
    {
        int a = Math.min(255, Math.max(0, Math.round((color >>> 24) * alpha)));

        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Push the colour toward its grey luminance by {@code amount} — far copies lose colour/detail (abstraction). */
    private static int bbsvfx$desaturate(int color, float amount)
    {
        if (amount <= 0F)
        {
            return color;
        }

        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int grey = Math.round(0.299F * r + 0.587F * g + 0.114F * b);

        r = Math.round(r + (grey - r) * amount);
        g = Math.round(g + (grey - g) * amount);
        b = Math.round(b + (grey - b) * amount);

        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
