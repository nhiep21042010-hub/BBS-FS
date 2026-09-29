package com.bbsvfx.bbsvfx.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.IArcTrailDrawer;
import com.bbsvfx.bbsvfx.client.SmearRenderState;
import com.bbsvfx.bbsvfx.client.BbsVfxArcTrail;
import com.bbsvfx.bbsvfx.client.BbsVfxTextureColor;
import com.bbsvfx.bbsvfx.forms.IModelSmearChannels;
import com.bbsvfx.bbsvfx.forms.ISmearBone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws smear "motion lines": thin streaks trailing each bone of the {@code motion_lines} channel.
 *
 * <p>The per-bone line look (enable / count / thickness / spread) and a UIPropTransform
 * (offset = translate, rotation = rotate, scale) are read straight from the {@code motion_lines}
 * channel pose, so the lines are independent of the model's pose. The streak direction/length is the
 * bone's visual smear — the difference between its world position on the farthest echo copy and on the
 * crisp pass — with the channel rotation applied on top; if the bone doesn't smear, the lines fall back
 * to the channel rotation alone (purely manual lines). Lines render once, on the crisp pass.</p>
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererLinesMixin implements IArcTrailDrawer, com.bbsvfx.bbsvfx.client.IMotionLineDrawer
{
    @Shadow
    private MatrixCache bones;

    @Shadow
    public abstract Form getForm();

    @Shadow
    public abstract ModelInstance getModel();

    @Shadow
    private mchorse.bbs_mod.cubic.animation.IAnimator animator;

    @Shadow
    public abstract Pose getPose();

    @Shadow
    private void captureMatrices(ModelInstance model)
    {
        throw new AssertionError();
    }

    /** The actor's resolved texture (form override, else the model's base) — for texture-tinted lines. */
    @Unique
    private Link bbsvfx$textureLink()
    {
        Link link = this.getForm() instanceof ModelForm form ? form.texture.get() : null;

        if (link == null)
        {
            ModelInstance model = this.getModel();

            if (model != null)
            {
                link = model.getTexture();
            }
        }

        return link;
    }

    @Unique
    private static boolean bbsvfx$trailLogged;

    @Unique
    private Map<String, Vector3f> bbsvfx$offsets;

    @Unique
    private Pose bbsvfx$linesPose()
    {
        return this.getForm() instanceof IModelSmearChannels channels ? channels.bbsvfx$linesPose().get() : null;
    }

    @Inject(method = "render3D", at = @At("TAIL"))
    private void bbsvfx$motionLines(FormRenderingContext context, CallbackInfo ci)
    {
        if (context.stencilMap != null)
        {
            return;
        }

        /* Arc motion-trail: while echo copies render, record each shown (non-hidden) bone's world position
         * so the connecting ribbon can be drawn through them after the loop. */
        if (SmearRenderState.active && SmearRenderState.arc && BbsVfxArcTrail.active)
        {
            java.util.Set<String> hidden = SmearRenderState.hide;

            for (Map.Entry<String, MatrixCacheEntry> entry : this.bones.entrySet())
            {
                /* Only the shown (arc-smeared) bones source a trail. */
                if (hidden != null && hidden.contains(entry.getKey()))
                {
                    continue;
                }

                /* Trace the bone's deepest CHILD (down to the hand) — the real moving tip. The bone's own
                 * pivot (e.g. the shoulder) barely moves; following the actual hierarchy avoids guessing
                 * which local axis is the limb's length (model-dependent, the earlier bug). The fan apex is
                 * the bone's own origin (pivot). */
                MatrixCacheEntry self = entry.getValue();

                if (self != null && self.matrix() != null)
                {
                    BbsVfxArcTrail.setPivot(entry.getKey(), self.matrix().getTranslation(new Vector3f()));
                }

                String tip = this.bbsvfx$tipBone(entry.getKey());
                MatrixCacheEntry tipEntry = tip.equals(entry.getKey()) ? self : this.bones.get(tip);

                if (tipEntry != null && tipEntry.matrix() != null)
                {
                    BbsVfxArcTrail.add(entry.getKey(), tipEntry.matrix().getTranslation(new Vector3f()));
                }
            }

            return;
        }
    }

    /**
     * Modernized motion lines: thin "speed line" streaks that follow the bone's REAL motion arc. For each
     * lined bone we sample its tip's world position at several earlier film times (time-rewind, like the
     * smear) and draw streaks along that curved path. Done at {@code renderBodyParts} TAIL because the
     * sampling ({@link #collectMatrices}) overwrites the bone cache that the body parts just used.
     */
    @Inject(method = "renderBodyParts", at = @At("TAIL"))
    private void bbsvfx$arcLines(FormRenderingContext context, CallbackInfo ci)
    {
        if (context.stencilMap != null || SmearRenderState.active)
        {
            return;
        }

        if (this.bbsvfx$linesPose() == null || !com.bbsvfx.bbsvfx.client.SmearReplayState.has(context.entity))
        {
            return;
        }

        /* Snapshot the correctly-posed render matrix (base · form transform · PI) while it's live. */
        Matrix4f mat = new Matrix4f(context.stack.peek().getPositionMatrix());

        /* When the smear is drawing copies, defer: it will call bbsvfx$drawDeferredLines AFTER all copies so the
         * lines read on top of them (binding the arc). Otherwise draw inline now. */
        if (SmearRenderState.linesPending)
        {
            this.bbsvfx$lineMat = mat;
            return;
        }

        this.bbsvfx$drawMotionLines(mat, context.entity, context.getTransition());
    }

    @Override
    public void bbsvfx$drawDeferredLines()
    {
        if (this.bbsvfx$lineMat == null)
        {
            return;
        }

        Matrix4f mat = this.bbsvfx$lineMat;
        this.bbsvfx$lineMat = null;

        if (this.bbsvfx$linesPose() == null || com.bbsvfx.bbsvfx.client.SmearReplayState.replay == null)
        {
            return;
        }

        this.bbsvfx$drawMotionLines(mat, com.bbsvfx.bbsvfx.client.SmearReplayState.entity, com.bbsvfx.bbsvfx.client.SmearReplayState.transition);
    }

    @Unique
    private Matrix4f bbsvfx$lineMat;

    /** Sample + draw every lined bone's motion streaks through {@code mat}, then restore the present pose. */
    @Unique
    private void bbsvfx$drawMotionLines(Matrix4f mat, mchorse.bbs_mod.forms.entities.IEntity entity, float transition)
    {
        Pose lines = this.bbsvfx$linesPose();

        if (lines == null)
        {
            return;
        }

        mchorse.bbs_mod.film.replays.Replay replay = com.bbsvfx.bbsvfx.client.SmearReplayState.replay;

        if (replay == null)
        {
            return;
        }

        float now = com.bbsvfx.bbsvfx.client.SmearReplayState.time();
        Form form = this.getForm();

        ShaderProgram previous = RenderSystem.getShader();
        boolean drew = false;

        for (Map.Entry<String, PoseTransform> entry : lines.transforms.entrySet())
        {
            ISmearBone line = (ISmearBone) entry.getValue();

            if (line.bbsvfx$smearLines() <= 0F)
            {
                continue;
            }

            /* Mode is encoded in the enable value: 2 = Manual (a fan you aim by hand via the channel rotation),
             * else Auto (arc-following volumetric streaks). */
            if (line.bbsvfx$smearLines() >= 1.5F)
            {
                Vector3f pos = this.bbsvfx$boneAtNow(entity, transition, replay, form, now, entry.getKey());

                if (pos != null)
                {
                    this.bbsvfx$drawManualLines(mat, pos, entry.getValue(), line);
                    drew = true;
                }

                continue;
            }

            int count = line.bbsvfx$smearLinesCount() > 0F ? Math.max(1, Math.round(line.bbsvfx$smearLinesCount())) : 7;
            Vector3f[][] arcs = this.bbsvfx$sampleLimb(entity, transition, replay, form, now, entry.getKey(), count, line);

            if (arcs != null)
            {
                this.bbsvfx$drawLimbLines(mat, arcs, entry.getValue(), line);
                drew = true;
            }
        }

        /* Re-pose the model back to the present (the sampling left it at the oldest tick). */
        replay.properties.applyProperties(form, now);
        ModelInstance model = this.getModel();

        if (model != null && this.animator != null)
        {
            model.model.resetPose();
            this.animator.applyActions(entity, model, transition);
            model.model.applyPose(this.getPose());
            model.form = this.getForm();
            mchorse.bbs_mod.cubic.ik.ModelIKRuntime.apply(model, null, null);
        }

        this.bones.clear();

        if (drew)
        {
            RenderSystem.setShader(() -> previous);
        }
    }

    /** Re-pose the model at 'now' and read the tip bone's position (for Manual mode, which needs no arc). */
    @Unique
    private Vector3f bbsvfx$boneAtNow(mchorse.bbs_mod.forms.entities.IEntity entity, float transition, mchorse.bbs_mod.film.replays.Replay replay, Form form, float now, String key)
    {
        ModelInstance model = this.getModel();

        if (model == null || this.animator == null || model.model == null)
        {
            return null;
        }

        replay.properties.applyProperties(form, now);
        model.model.resetPose();
        this.animator.applyActions(entity, model, transition);
        model.model.applyPose(this.getPose());
        model.form = this.getForm();
        mchorse.bbs_mod.cubic.ik.ModelIKRuntime.apply(model, null, null);
        this.captureMatrices(model);

        MatrixCacheEntry e = this.bones.get(this.bbsvfx$tipBone(key));
        Vector3f pos = e != null && e.matrix() != null ? e.matrix().getTranslation(new Vector3f()) : null;

        this.bones.clear();

        return pos;
    }

    /**
     * Re-pose the model at N earlier film times (time-rewind, deterministic, works paused) and read the tip's
     * swept path — the master arc the streaks follow — plus the limb base position at 'now' (so the draw step
     * can scatter origins along the limb). Returns {@code {tipArc[samples], {baseNow}}} (sample 0 = now), or
     * null if nothing usable.
     */
    @Unique
    private Vector3f[][] bbsvfx$sampleLimb(mchorse.bbs_mod.forms.entities.IEntity entity, float transition, mchorse.bbs_mod.film.replays.Replay replay, Form form, float now, String key, int count, ISmearBone line)
    {
        ModelInstance model = this.getModel();

        if (model == null || this.animator == null || model.model == null)
        {
            return null;
        }

        String end = this.bbsvfx$tipBone(key);
        String base = model.model.getParentGroupKey(key);

        if (base == null)
        {
            base = key;
        }

        int samples = 10;
        float timeTicks = line.bbsvfx$smearTime() > 0F ? line.bbsvfx$smearTime() : 4F;
        /* Don't look back past the film start (tk < 0 clamps to 0 and piles samples on the rest pose). */
        float window = Math.min(timeTicks, now);

        if (window < 0.05F)
        {
            return null;
        }

        float reach = line.bbsvfx$smearLinesOffsetX();
        Vector3f[] tip = new Vector3f[samples];
        Vector3f baseNow = null;

        for (int j = 0; j < samples; j++)
        {
            float f = (float) j / (samples - 1);
            float tk = now - f * window;

            replay.properties.applyProperties(form, tk);
            model.model.resetPose();
            this.animator.applyActions(entity, model, transition);
            model.model.applyPose(this.getPose());
            model.form = this.getForm();
            mchorse.bbs_mod.cubic.ik.ModelIKRuntime.apply(model, null, null);
            this.captureMatrices(model);

            MatrixCacheEntry be = this.bones.get(base);
            MatrixCacheEntry ee = this.bones.get(end);
            Vector3f bp = be != null && be.matrix() != null ? be.matrix().getTranslation(new Vector3f()) : null;
            Vector3f ep = ee != null && ee.matrix() != null ? ee.matrix().getTranslation(new Vector3f()) : null;

            this.bones.clear();

            if (ep == null)
            {
                tip[j] = j > 0 ? tip[j - 1] : null;
                continue;
            }

            /* Push the tip end out along the limb onto the visible hand (Reach, else an auto guess). */
            Vector3f endAnchor = new Vector3f(ep);

            if (bp != null)
            {
                Vector3f limb = new Vector3f(ep).sub(bp);
                float len = limb.length();

                if (len > 1e-4F)
                {
                    float ext = reach > 0F ? reach : Math.min(len * 0.6F, 0.3F);
                    endAnchor.add(new Vector3f(limb).div(len).mul(ext));
                }
            }

            tip[j] = endAnchor;

            if (j == 0)
            {
                baseNow = bp != null ? bp : new Vector3f(ep);
            }
        }

        if (tip[0] == null)
        {
            return null;
        }

        return new Vector3f[][] { tip, { baseNow != null ? baseNow : tip[0] } };
    }

    @Unique
    private void bbsvfx$captureOffsets(Pose lines)
    {
        if (this.bbsvfx$offsets == null)
        {
            this.bbsvfx$offsets = new HashMap<>();
        }

        for (Map.Entry<String, PoseTransform> entry : lines.transforms.entrySet())
        {
            if (((ISmearBone) entry.getValue()).bbsvfx$smearLines() <= 0F)
            {
                continue;
            }

            MatrixCacheEntry bone = this.bones.get(entry.getKey());

            if (bone != null)
            {
                this.bbsvfx$offsets.put(entry.getKey(), bone.matrix().getTranslation(new Vector3f()));
            }
        }
    }

    /**
     * Draw {@code count} motion streaks scattered through the limb's VOLUME, all following the tip's swept
     * master arc. Each line gets a random origin (random fraction along the limb + a random angle/radius around
     * its cross-section, so some sit in front of the limb and some behind), the master arc shape translated
     * there, a length tied to the tip's current speed (short → long → short through the swing) with per-line
     * random variation, drawn as a flat camera-facing ribbon tapering to a point at the tail.
     */
    @Unique
    private void bbsvfx$drawLimbLines(Matrix4f mat, Vector3f[][] data, PoseTransform transform, ISmearBone line)
    {
        Vector3f[] tip = data[0];
        Vector3f baseNow = data[1][0];
        int n = tip.length;

        if (tip[0] == null || tip[n - 1] == null)
        {
            return;
        }

        Vector3f head = tip[0];
        float span = head.distance(tip[n - 1]);
        float recent = head.distance(tip[Math.min(3, n - 1)]);

        if (span < 1e-4F || recent < 5e-3F)
        {
            return;
        }

        int count = line.bbsvfx$smearLinesCount() > 0F ? Math.max(1, Math.round(line.bbsvfx$smearLinesCount())) : 9;
        float thickness = line.bbsvfx$smearLinesWidth() > 0F ? line.bbsvfx$smearLinesWidth() : 0.035F;
        /* How far off the limb centreline the origins scatter (front / back / sides). */
        float volume = line.bbsvfx$smearLinesSpread() > 0F ? line.bbsvfx$smearLinesSpread() : 0.14F;
        /* User controls (repurposed offset fields): Y = length multiplier (0 = default), Z = blunt flag. */
        float lengthMul = line.bbsvfx$smearLinesOffsetY() > 0F ? line.bbsvfx$smearLinesOffsetY() : 1F;
        boolean blunt = line.bbsvfx$smearLinesOffsetZ() > 0F;

        float cr = transform.color.r;
        float cg = transform.color.g;
        float cb = transform.color.b;
        float ca = transform.color.a;

        if (line.bbsvfx$smearLinesTexture() > 0F)
        {
            int argb = BbsVfxTextureColor.average(this.bbsvfx$textureLink());
            cr = ((argb >> 16) & 0xFF) / 255F;
            cg = ((argb >> 8) & 0xFF) / 255F;
            cb = (argb & 0xFF) / 255F;
        }

        /* Limb cross-section frame at 'now' (two axes perpendicular to the limb) — origins scatter in it. */
        Vector3f limbDir = new Vector3f(head).sub(baseNow);
        limbDir = limbDir.lengthSquared() < 1e-8F ? new Vector3f(0F, 1F, 0F) : limbDir.normalize();
        Vector3f pu = new Vector3f(limbDir).cross(0F, 1F, 0F);
        pu = pu.lengthSquared() < 1e-6F ? new Vector3f(1F, 0F, 0F) : pu.normalize();
        Vector3f pv = new Vector3f(limbDir).cross(pu).normalize();

        /* Camera position in model space (origin of camera-relative world) → flat camera-facing ribbons. */
        Vector3f cam = new Matrix4f(mat).invert().transformPosition(new Vector3f());

        BufferBuilder builder;

        /* Depth TEST + WRITE on so world geometry occludes the streaks AND they occlude things drawn after
         * (mobs) instead of bleeding through; blend for the tail fade. */
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        builder = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        Vector3f[] p = new Vector3f[n];

        for (int s = 0; s < count; s++)
        {
            /* Random origin: a fraction along the limb + an offset around its cross-section. */
            float u = 0.05F + 0.95F * bbsvfx$hash(s, 17);
            double ang = bbsvfx$hash(s, 3) * Math.PI * 2.0;
            float r = volume * (0.2F + 0.8F * bbsvfx$hash(s, 5));
            float ox = (float) (r * Math.cos(ang));
            float oy = (float) (r * Math.sin(ang));
            Vector3f origin = new Vector3f(baseNow).lerp(head, u).add(pu.x * ox + pv.x * oy, pu.y * ox + pv.y * oy, pu.z * ox + pv.z * oy);

            /* Length tracks the tip speed (shared, so even off-tip lines stay visible), varied per line, and
             * hard-capped so a fast frame can't fling absurdly long streaks across the screen. */
            float lenJitter = 0.4F + 0.7F * bbsvfx$hash(s, 7);
            float target = Math.min(recent * 4F * lenJitter, 0.7F) * lengthMul;
            float scale = target / span;

            for (int i = 0; i < n; i++)
            {
                p[i] = new Vector3f(tip[i]).sub(head).mul(scale).add(origin);
            }

            this.bbsvfx$ribbonStrip(builder, mat, cam, p, thickness, blunt, cr, cg, cb, ca);
        }

        BufferRenderer.drawWithGlobalProgram(builder.end());
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** Emit a flat camera-facing ribbon along the points {@code p}: needle (tapering to a point at the tail)
     *  unless {@code blunt}, in which case width stays uniform; alpha always fades to the tail. */
    @Unique
    private void bbsvfx$ribbonStrip(BufferBuilder builder, Matrix4f mat, Vector3f cam, Vector3f[] p, float thickness, boolean blunt, float cr, float cg, float cb, float ca)
    {
        int n = p.length;

        /* Per-VERTEX width directions (averaged tangent of the two adjacent segments) so the ribbon edges join
         * smoothly across a bend — a per-segment width flips direction at curves and visibly kinks. */
        Vector3f[] w = new Vector3f[n];

        for (int i = 0; i < n; i++)
        {
            Vector3f tan = new Vector3f();

            if (i > 0)
            {
                Vector3f in = new Vector3f(p[i]).sub(p[i - 1]);

                if (in.lengthSquared() > 1e-12F)
                {
                    tan.add(in.normalize());
                }
            }

            if (i < n - 1)
            {
                Vector3f out = new Vector3f(p[i + 1]).sub(p[i]);

                if (out.lengthSquared() > 1e-12F)
                {
                    tan.add(out.normalize());
                }
            }

            Vector3f wi = tan.cross(new Vector3f(p[i]).sub(cam));
            w[i] = wi.lengthSquared() < 1e-12F ? new Vector3f(1F, 0F, 0F) : wi.normalize();
        }

        for (int i = 0; i < n - 1; i++)
        {
            float t0 = (float) i / (n - 1);
            float t1 = (float) (i + 1) / (n - 1);

            float h0 = blunt ? thickness * 0.5F : thickness * 0.5F * (1F - t0);
            float h1 = blunt ? thickness * 0.5F : thickness * 0.5F * (1F - t1);
            float a0 = ca * (1F - t0 * 0.85F);
            float a1 = ca * (1F - t1 * 0.85F);

            bbsvfx$vert(builder, mat, p[i], w[i], h0, cr, cg, cb, a0, 1);
            bbsvfx$vert(builder, mat, p[i], w[i], h0, cr, cg, cb, a0, -1);
            bbsvfx$vert(builder, mat, p[i + 1], w[i + 1], h1, cr, cg, cb, a1, -1);
            bbsvfx$vert(builder, mat, p[i + 1], w[i + 1], h1, cr, cg, cb, a1, 1);
        }
    }

    /** Emit one ribbon vertex: point ± half-width along the (camera-facing) width vector. */
    @Unique
    private static void bbsvfx$vert(BufferBuilder builder, Matrix4f mat, Vector3f point, Vector3f w, float half, float r, float g, float b, float a, int sign)
    {
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(mat, point.x + w.x * half * sign, point.y + w.y * half * sign, point.z + w.z * half * sign).color(r, g, b, a));
    }

    /** Manual mode: a hand-aimed fan of straight streaks from the bone, oriented by the channel rotation. */
    @Unique
    private void bbsvfx$drawManualLines(Matrix4f mat, Vector3f pos, PoseTransform transform, ISmearBone line)
    {
        MatrixStack ms = new MatrixStack();
        ms.peek().getPositionMatrix().mul(mat);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        this.bbsvfx$drawLines(ms, pos, new Vector3f(), transform, line);
    }

    @Unique
    private void bbsvfx$drawLines(MatrixStack stack, Vector3f pos, Vector3f delta, PoseTransform transform, ISmearBone line)
    {
        float motion = delta.length();
        boolean hasMotion = motion >= 0.02F;

        /* Direction/length: along the visual smear when the bone moves, else a manual default streak
         * (oriented entirely by the channel rotation). Length follows the shared Length control. */
        Vector3f dir = hasMotion ? new Vector3f(delta).div(motion) : new Vector3f(1F, 0F, 0F);
        float length = hasMotion ? motion : (line.bbsvfx$smearLinesOffsetY() > 0F ? line.bbsvfx$smearLinesOffsetY() : 0.3F);

        Quaternionf align = new Quaternionf().rotationTo(new Vector3f(1F, 0F, 0F), dir);

        /* Per-bone controls (0 = default). */
        int count = line.bbsvfx$smearLinesCount() > 0F ? Math.max(1, Math.round(line.bbsvfx$smearLinesCount())) : 4;
        float thickness = line.bbsvfx$smearLinesWidth() > 0F ? line.bbsvfx$smearLinesWidth() : 0.02F;
        float spread = line.bbsvfx$smearLinesSpread() > 0F ? line.bbsvfx$smearLinesSpread() : Math.max(length * 0.08F, 0.02F);

        /* Colour: the bone's pose colour (the editor's Color picker, animated with the pose), or the
         * actor's texture average when the Texture toggle is on. Opacity always stays with the picker. */
        Color color = transform.color;
        float cr = color.r;
        float cg = color.g;
        float cb = color.b;
        float ca = color.a;

        if (line.bbsvfx$smearLinesTexture() > 0F)
        {
            int argb = BbsVfxTextureColor.average(this.bbsvfx$textureLink());

            cr = ((argb >> 16) & 0xFF) / 255F;
            cg = ((argb >> 8) & 0xFF) / 255F;
            cb = (argb & 0xFF) / 255F;
        }

        /* Channel UIPropTransform: translate = offset, rotate = orientation (euler ZYX), scale = size. */
        Vector3f off = transform.translate;
        Vector3f rot = transform.rotate;
        Vector3f scale = transform.scale;

        stack.push();
        stack.translate(pos.x + off.x, pos.y + off.y, pos.z + off.z);
        stack.multiply(new Quaternionf().rotationZYX(rot.z, rot.y, rot.x));
        stack.scale(scale.x, scale.y, scale.z);

        for (int i = 0; i < count; i++)
        {
            float oy = (bbsvfx$hash(i, 1) * 2F - 1F) * spread;
            float oz = (bbsvfx$hash(i, 2) * 2F - 1F) * spread;
            float len = length * (0.6F + 0.4F * bbsvfx$hash(i, 3));

            stack.push();
            stack.multiply(align);
            stack.translate(0F, oy, oz);
            Draw.renderBox(stack, 0F, -thickness, -thickness, len, thickness * 2F, thickness * 2F, cr, cg, cb, ca);
            stack.pop();
        }

        stack.pop();
    }

    /**
     * Draws the arc motion-trail: a translucent tube through the world positions each shown bone passed
     * through during the echo copies (recorded in {@link BbsVfxArcTrail}), tinted with the actor's texture
     * colour. Triggered by the smear render after the copy loop. The list runs farthest → nearest, so the
     * ribbon tapers thinner toward the tail.
     */
    @Override
    public void bbsvfx$drawArcTrail(FormRenderingContext context)
    {
        if (BbsVfxArcTrail.points.isEmpty())
        {
            return;
        }

        int argb = BbsVfxTextureColor.average(this.bbsvfx$textureLink());
        /* Trails read best BRIGHTER than the source (sells the motion); the raw texture average is invisible
         * on a dark character. Lift each channel toward white. */
        float cr = Math.min(1F, ((argb >> 16) & 0xFF) / 255F + 0.45F);
        float cg = Math.min(1F, ((argb >> 8) & 0xFF) / 255F + 0.45F);
        float cb = Math.min(1F, (argb & 0xFF) / 255F + 0.45F);
        float ca = 0.4F;

        if (!bbsvfx$trailLogged && !BbsVfxArcTrail.points.isEmpty())
        {
            List<Vector3f> first = BbsVfxArcTrail.points.values().iterator().next();

            if (first.size() >= 2)
            {
                float span = first.get(0).distance(first.get(first.size() - 1));

                if (span > 0.05F)
                {
                    bbsvfx$trailLogged = true;
                    org.apache.logging.log4j.LogManager.getLogger("bbsvfx").info("[smear-arc] trail: bones={} pts={} span={}",
                        BbsVfxArcTrail.points.size(), first.size(), span);
                }
            }
        }

        boolean any = false;

        for (List<Vector3f> pts : BbsVfxArcTrail.points.values())
        {
            if (pts.size() >= 2)
            {
                any = true;
                break;
            }
        }

        if (!any)
        {
            return;
        }

        /* Solid filled tube (fillBoxTo), NOT renderBox — renderBox only draws the 12 edges (a wireframe).
         * Depth test off so the trail always reads on top (it traces inside the dark limb otherwise). */
        ShaderProgram previous = RenderSystem.getShader();
        BufferBuilder builder;

        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        builder = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        /* Filled FAN: apex = the bone pivot (shoulder), edges = the hand-tip arc. Each (pivot, tipA, tipB)
         * triangle fills one slice of the swept sector — together they cover the whole area the limb swept,
         * binding the fanned copies (a thin line along the tips can't fill that area). */
        for (Map.Entry<String, List<Vector3f>> entry : BbsVfxArcTrail.points.entrySet())
        {
            List<Vector3f> pts = entry.getValue();
            Vector3f pivot = BbsVfxArcTrail.pivots.get(entry.getKey());

            if (pts.size() < 2 || pivot == null)
            {
                continue;
            }

            for (int i = 0; i < pts.size() - 1; i++)
            {
                Vector3f a = pts.get(i);
                Vector3f b = pts.get(i + 1);

                /* fillQuad as a triangle: (pivot, a, b, b) → one real tri (pivot, a, b). */
                Draw.fillQuad(builder, context.stack,
                    pivot.x, pivot.y, pivot.z,
                    a.x, a.y, a.z,
                    b.x, b.y, b.z,
                    b.x, b.y, b.z,
                    cr, cg, cb, ca);
            }
        }

        BufferRenderer.drawWithGlobalProgram(builder.end());
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();

        if (previous != null)
        {
            RenderSystem.setShader(() -> previous);
        }
    }

    /** Walk down the bone hierarchy to the deepest descendant (the hand/tip), which sweeps the real arc. */
    @Unique
    private String bbsvfx$tipBone(String bone)
    {
        if (this.getModel() == null || this.getModel().model == null)
        {
            return bone;
        }

        String current = bone;

        for (int depth = 0; depth < 8; depth++)
        {
            java.util.Collection<String> children = this.getModel().model.getDirectChildrenKeys(current);

            if (children == null || children.isEmpty())
            {
                break;
            }

            current = children.iterator().next();
        }

        return current;
    }

    @Unique
    private static float bbsvfx$hash(int i, int salt)
    {
        int h = (i * 73856093) ^ (salt * 19349663);

        h ^= h >>> 13;
        h *= 0x85ebca6b;
        h ^= h >>> 16;

        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }
}
