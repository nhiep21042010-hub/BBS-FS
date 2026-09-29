package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.framework.UIContext;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.bbsvfx.bbsvfx.forms.WindForm;

/**
 * Renders a {@link WindForm}'s visible ambient wind as a screen-space VOLUME ({@link WindVolume}) — silky
 * fractal streaks flowing through the zone along the direction handle. The zone rides the form matrix
 * (origin/basis extracted from {@code context.stack}, like the dome volumes), so there is no world-origin
 * reconstruction at all — the technique that made the earlier billboard streaks swim with the camera.
 *
 * <p>Emission is gated to WORLD passes (a placed model block / a film actor): the same form also renders
 * as an ITEM (hand, hotbar) several times per frame with player-anchored matrices. A once-per-frame guard
 * dedupes BBS's multiple world passes so the volume isn't queued (= composited) more than once.</p>
 */
public class WindFormRenderer extends FormRenderer<WindForm>
{
    /** Wind layer thickness above the form base, blocks (a slider later if needed). */
    private static final float HEIGHT = 9F;

    /** Last emitted flow time — the once-per-frame guard against duplicate world passes. */
    private double lastFlow = Double.NaN;

    /** Same guard for the flying leaves (they draw immediately, so extra passes = overdraw). */
    private double lastLeafFlow = Double.NaN;

    /** ★Sub-block correction. The proxies/crowns are stored as INTEGER offsets from {@code round(origin)},
     * but they are drawn from the form's EXACT position, so a form standing at y = 65.4 rendered the whole
     * foliage 0.4 blocks too high — grass visibly floating off the ground. Computed in the main pass and
     * reused in the shadow pass, which has no usable origin of its own. */
    private float fixX, fixY, fixZ;

    public WindFormRenderer(WindForm form)
    {
        super(form);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        /* Cheap picker thumbnail: three slanted streaks suggesting a gust. */
        int y = (y1 + y2) / 2;

        context.batcher.box(x1 + 4, y - 6, x2 - 8, y - 4, 0x80FFFFFF);
        context.batcher.box(x1 + 10, y, x2 - 4, y + 2, 0xB0FFFFFF);
        context.batcher.box(x1 + 6, y + 6, x2 - 10, y + 8, 0x60FFFFFF);
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        if (!this.form.scanned.get())
        {
            return;
        }

        /* The Iris SHADOW pass re-renders forms with the light's matrices. We still want the foliage proxies
         * there (they replace real geometry, so they must cast shadows) — but NOT the visible wind (it isn't
         * a shadow caster) and NOT the scan (the origin from the light matrices is meaningless).
         *
         * A FOREIGN pass (an addon re-rendering the forms into its own shadow tile / mask — see
         * {@link BbsVfxForeignPass}) wants exactly the same treatment, and for the same reason: its
         * matrices are the light's, not the camera's. Under Iris it even runs FIRST in the frame, so
         * without this it stole the once-per-frame keys below and the wind swung with the camera. */
        boolean shadow = BBSRendering.isIrisShadowPass() || BbsVfxForeignPass.isActive();

        /* World passes only — the ITEM passes (hand/hotbar) carry player-anchored matrices, and the editor
         * PREVIEW has no post pass for the volume to composite in. (The shadow pass has its own type.) */
        if (!shadow && context.type != FormRenderType.MODEL_BLOCK && context.type != FormRenderType.ENTITY)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.world == null)
        {
            return;
        }

        /* Continuous flow time (no keyframe needed for the wind to stream); wrapped to keep float precision
         * healthy in the shader noise. Constant within a frame -> the once-per-frame dedupe key. */
        double tFlow = (mc.world.getTime() + mc.getTickDelta()) / 20.0;

        /* Wind direction from the handle, in the form's local ground basis (≈ world for an unrotated actor —
         * actor rotation is a later polish). Falls back to +X while the point sits on the origin. */
        Vector3f dir = this.form.point();
        float len = (float) Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float wu = len > 1.0E-4F ? dir.x / len : 1F;
        float wv = len > 1.0E-4F ? dir.z / len : 0F;

        /* STORM: a directional wind that SURGES and shifts rather than blowing flat. One gust envelope drives
         * every consumer — streaks, foliage and leaves breathe together, which is what sells a storm over a
         * constant push.
         *
         * ★★Modulate INTEGRATED TIME, never the rate. Both the shader (advection = uTime·uSpeed) and the sway
         * (sin(t·freq)) build their phase as time × rate, so scaling the rate at t ≈ hundreds of seconds
         * swings the phase by tens of radians per frame — the pattern jumps back and forth and reads as
         * flicker, not wind. Feeding a pre-integrated clock G(t) = ∫g instead keeps the phase monotonic and
         * merely speeds it up and slows it down. g stays > 0, so G never runs backwards. */
        float gust = 1F;
        double flowTime = tFlow;

        if (this.form.isStorm())
        {
            double t = tFlow;
            final double a1 = 0.35, w1 = 0.21;
            final double a2 = 0.20, w2 = 0.47, p2 = 1.7;

            gust = (float) (1.0 + a1 * Math.sin(w1 * t) + a2 * Math.sin(w2 * t + p2));

            /* G(t) = ∫₀ᵗ g — the closed form of the sines above. */
            flowTime = t - (a1 / w1) * Math.cos(w1 * t) - (a2 / w2) * Math.cos(w2 * t + p2);

            /* The direction wanders a little with the gusts (slow — this rotates the noise domain). */
            float wob = (float) Math.toRadians(7.0 * Math.sin(t * 0.13 + 2.1));
            float cs = (float) Math.cos(wob), sn = (float) Math.sin(wob);
            float nu = wu * cs - wv * sn;

            wv = wu * sn + wv * cs;
            wu = nu;
        }

        /* Visible wind volume (streaks + dust) — once per frame, never in the shadow pass. */
        if (!shadow && this.form.opacity.get() > 0.001F && tFlow != this.lastFlow
            && (this.form.streaks.get() > 0.001F || this.form.dust.get() > 0.001F))
        {
            this.lastFlow = tFlow;

            mchorse.bbs_mod.utils.colors.Color col = this.form.color.get();
            boolean vortex = this.form.isVortex();

            /* The vortex is a TALL funnel; the directional field is a thin ground slab. The swirl drives the
             * vortex's rotation speed, the plain strength drives the directional flow. */
            float height = vortex ? this.form.funnelHeight.get() : HEIGHT;
            /* Base rate only — the gusting lives in flowTime, not here (see the phase note above). */
            float speed = vortex
                ? Math.max(0.5F, this.form.swirl.get())
                : Math.max(0.5F, this.form.strength.get());

            /* ★Opacity is per BLOCK of marched path, so the coefficient must match how far the ray travels
             * through the field. The directional slab is ~9 blocks thick; the funnel is 40+, so reusing 0.30
             * saturated alpha to 1 within a few steps — the hollow wall and the helical bands were there but
             * clipped to flat white. */
            float densCoef = vortex ? 0.13F : 0.30F;
            float dustCoef = vortex ? 0.12F : 0.30F;

            WindVolume.queue(new Matrix4f(context.stack.peek().getPositionMatrix()),
                this.form.scanRadius.get(), height,
                densCoef * this.form.streaks.get() * this.form.opacity.get(),
                dustCoef * this.form.dust.get() * this.form.opacity.get(),
                1F, speed, wu, wv,
                (float) (flowTime % 3600.0), col.r, col.g, col.b,
                vortex ? 1F : 0F, this.form.coreRadius.get(), this.form.funnelFlare.get());
        }

        boolean wantsSway = this.form.sway.get();
        boolean wantsLeaves = this.form.leaves.get() > 0.001F && this.form.opacity.get() > 0.001F;

        /* Both the sway scan and the flying-leaf spawn need the zone's WORLD origin. Compute it ONCE, in the
         * main pass only (the shadow pass has the light's matrices). {@code modelView*stack} maps the local
         * origin to VIEW space (camera-relative AND rotated by the camera); undo the view rotation and add
         * the camera pos to get WORLD. ★Verified CONSTANT under camera translation AND rotation; plain
         * {@code cam + stack·0} swam on rotation and re-scanned every nudge (the jerk). */
        int radius = Math.round(this.form.scanRadius.get());
        double ox = 0, oy = 0, oz = 0;

        if (!shadow && (wantsSway || wantsLeaves))
        {
            net.minecraft.client.render.Camera camera = mc.gameRenderer.getCamera();
            net.minecraft.util.math.Vec3d cam = camera.getPos();
            Vector3f rel = new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix())
                .mul(context.stack.peek().getPositionMatrix())
                .transformPosition(new Vector3f(0F, 0F, 0F));
            new org.joml.Matrix4f()
                .rotateX((float) Math.toRadians(camera.getPitch()))
                .rotateY((float) Math.toRadians(camera.getYaw() + 180F))
                .invert()
                .transformPosition(rel);

            ox = cam.x + rel.x;
            oy = cam.y + rel.y;
            oz = cam.z + rel.z;

            /* Cancel the rounding the CAPTURE applied. ★Must use the scan's own integer origin, not
             * round(current origin): the reconstructed origin drifts between rescans, and rounding it every
             * frame flipped the correction by a whole block near a .5 boundary — the foliage jittered.
             * With the scan's value the result is exactly the world position, drift and all. */
            this.fixX = (float) (WindFoliage.scanCx() - ox);
            this.fixY = (float) (WindFoliage.scanCy() - oy);
            this.fixZ = (float) (WindFoliage.scanCz() - oz);
        }

        /* FOLIAGE SWAY (Approach A) — NON-DESTRUCTIVE, client-only: the zone's foliage is removed from the
         * client render (server/disk untouched) and swaying proxies stand in. Sway toggle: on = client-cut +
         * proxies; off = restore. Scan only in the main pass; proxies render in BOTH so they cast shadows. */
        if (wantsSway)
        {
            if (!shadow)
            {
                WindFoliage.ensure(this.form, ox, oy, oz, radius);
            }

            ExplosionFoliageVAO vao = ExplosionFoliageVAO.of(WindFoliage.proxyBlocks());

            if (vao != null)
            {
                boolean vx = this.form.isVortex();

                /* ★The wind's FORCE must drive the foliage too, not just the streak advection: `swayAmount` is
                 * the peak angle at nominal force, and the force scales both that angle and the flutter rate.
                 * Nominal = each driver's default (strength 6 / swirl 12) so the stock look is unchanged. */
                float driver = vx ? this.form.swirl.get() : this.form.strength.get();
                float windScale = Math.max(0F, driver / (vx ? 12F : 6F));

                /* ★Amplitude may follow the gust instantly (only PHASE is fragile), but it must SATURATE.
                 * A hard 75° ceiling let a strong wind lay a trunk almost flat: the proxy swung out of the
                 * hole its real (client-cut) blocks left behind, so the tree read as ripped off the ground.
                 * Soft saturation keeps raising the bend with strength while never tipping a trunk over. */
                float raw = this.form.swayAmount.get() * windScale * gust;
                float cap = 28F;
                float swayDeg = cap * (1F - (float) Math.exp(-raw / cap));
                float rate = Math.max(0.25F, Math.min(3F, windScale));

                context.stack.push();
                context.stack.translate(this.fixX, this.fixY, this.fixZ);

                vao.renderWind(context.stack, flowTime, wu, wv, swayDeg,
                    1F, 1F, 1F, 1F, context.light, context.overlay,
                    vx, this.form.coreRadius.get(), this.form.swirl.get(),
                    this.form.suction.get(), this.form.updraft.get(), rate);

                context.stack.pop();
            }
        }
        else if (!shadow && WindFoliage.isActive())
        {
            WindFoliage.restore();
        }

        /* FLYING LEAVES — sprites shed from the zone's crowns, drifting downwind. Form-relative (via the
         * stack), once per frame, main pass only. */
        if (wantsLeaves && !shadow && tFlow != this.lastLeafFlow)
        {
            this.lastLeafFlow = tFlow;

            /* No gust factor here either: the leaves ride flowTime, so their whole flight speeds up and eases
             * with the gusts instead of teleporting when the multiplier changes mid-flight. */
            float leafScale = Math.max(0.05F, this.form.strength.get() / 6F);

            /* WindLeaves applies its OWN scan-origin correction (its crown scan has separate hysteresis). */
            WindLeaves.render(context.stack, ox, oy, oz, wu, wv, flowTime,
                this.form.leaves.get(), this.form.opacity.get(), radius,
                this.form.isVortex(), this.form.coreRadius.get(), this.form.swirl.get(),
                this.form.suction.get(), this.form.updraft.get(), leafScale);
        }
    }
}
