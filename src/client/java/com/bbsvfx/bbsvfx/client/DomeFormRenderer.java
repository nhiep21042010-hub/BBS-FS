package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.bbsvfx.bbsvfx.forms.BeamForm;
import com.bbsvfx.bbsvfx.forms.DomeForm;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link DomeForm} as an expanding EMISSIVE energy hemisphere in the form pass (through BBS's
 * provider, {@code entity-translucent-emissive}) so shaderpacks light + bloom it natively — the same
 * cross-pack approach the beam settled on. A fresnel RIM (grazing-angle brightness) glows at the
 * silhouette while the face is faint and see-through (shield-like); the surface churns with value-noise
 * turbulence; a blazing shockwave RING races out along the ground at the wavefront.
 *
 * <p>All a closed form of {@code simTime = progress × duration}; the radius bursts out via
 * {@link DomeForm#currentRadius()}. No per-frame simulation.</p>
 */
public class DomeFormRenderer extends FormRenderer<DomeForm>
{
    private static final Identifier WHITE_TEX = Identifier.of("bbsvfx", "dome/white");
    private static final Identifier DUST_TEX = Identifier.of("bbsvfx", "dome/dust");
    private static boolean whiteReady, dustReady;

    /** Fullbright lightmap so the pack treats the dome as an emissive (block = sky = 240). */
    private static final int FULL = 0xF000F0;

    /** Emissive luminance scale for the current draw: shaderpacks bloom the big bright surface hard, so
     *  dim the colour under a pack (bloom keys off luminance) while vanilla keeps the full brightness. */
    private static float lumScale = 1F;

    /** When true the vertex helpers emit POSITION_COLOR_TEXTURE (for the OIT accum buffer); otherwise the
     *  full entity format (colour/uv/overlay/light/up-normal) for the emissive provider. */
    private static boolean oitVertexMode;

    /** One translucent vertex — OIT (position/colour/uv) or full entity format, per {@link #oitVertexMode}. */
    private static void emitV(VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, float g, float b, float a, float u, float v)
    {
        if (oitVertexMode)
        {
            vc.vertex(m, x, y, z).color(r, g, b, a).texture(u, v);
        }
        else
        {
            vc.vertex(m, x, y, z).color(r, g, b, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(FULL).normal(0F, 1F, 0F);
        }

        BbsVfxRenderCompat.next(vc);
    }

    public DomeFormRenderer(DomeForm form)
    {
        super(form);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        /* Picker thumbnail: a filled dome arc in the dome's colours. */
        Color rim = this.form.rimColor.get();
        Color body = this.form.color.get();
        int cx = (x1 + x2) / 2;
        int cy = y2 - 2;
        int r = Math.min((x2 - x1) / 2, y2 - y1) - 2;

        context.batcher.box(cx - r, cy - r, cx + r, cy, body.getARGBColor() & 0x40FFFFFF);
        context.batcher.box(cx - r, cy - 2, cx + r, cy, rim.getARGBColor());
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        if (BBSRendering.isIrisShadowPass() || context.stencilMap != null)
        {
            return;
        }

        float radius = this.form.currentRadius();

        /* Tell the re-cut which form instance the SCENE actually draws (editor/scene keep separate
         * copies) AND the actor's true world origin — re-cut in a LATER session than the capture has no
         * statics left and reconstructs everything from this note (the explosion-rescan recipe). The
         * PREVIEW renders a dummy entity at ~0,0,0 and must not overwrite the note. */
        if (context.type != FormRenderType.PREVIEW && this.form.destruction.get() && context.entity != null)
        {
            org.joml.Vector3f ft = this.form.transform.get().translate;

            DestructionCapture.noteDomeForm(this.form,
                context.entity.getX() + ft.x, context.entity.getY() + ft.y, context.entity.getZ() + ft.z);
        }

        /* World levelling: the razed blocks render in the form pass (solid, depth-correct); they stay
         * intact until the expanding wavefront (currentRadius) sweeps past, regardless of dome opacity. */
        if (this.form.destruction.get() && !this.form.blocks.getList().isEmpty())
        {
            this.renderLevel(context, radius);
        }

        /* Beam "tail": the strike from the sky that leads into the dome (reuses the beam renderer). */
        if (this.form.beamTail.get())
        {
            this.renderBeamTail(context);
        }

        float opacity = this.form.opacity.get();
        float flash = flashIntensity(this.form);
        float burst = burstProgress(this.form);
        float deto = detonationProgress(this.form);
        boolean domeOn = opacity > 0.001F && radius > 0.02F;
        float pNow = this.form.progress.get();
        float impactNow = this.form.impactAt.get();
        boolean charge = this.form.beamTail.get()
            && pNow < impactNow + Math.max(0.001F, this.form.beamFade.get());

        if (!domeOn && flash <= 0.001F && burst < 0F && deto < 0F && !charge)
        {
            return;
        }

        float st = this.form.simTime();
        lumScale = BBSRendering.isIrisShadersEnabled() ? 0.5F : 1F;
        Matrix4f mat = context.stack.peek().getPositionMatrix();
        Vector3f cam = new Matrix4f(mat).invert().transformPosition(new Vector3f(0F, 0F, 0F));

        ensureWhiteTex();

        mchorse.bbs_mod.forms.CustomVertexConsumerProvider provider = mchorse.bbs_mod.forms.FormUtilsClient.getProvider();
        VertexConsumer vc = provider.getBuffer(RenderLayer.getEntityTranslucentEmissive(WHITE_TEX));

        if (domeOn)
        {
            /* SHELL = screen-space raymarched hemisphere VOLUME (railgun/nuke technique), NOT geometry.
             * DomeVolume reconstructs the dome per pixel from the captured scene depth and TINTS the world
             * seen through it (Beer-Lambert absorption per wall) with a glowing silhouette rim. Depth does
             * the compositing — clouds/water/terrain occlude or are tinted correctly, no sorting/OIT. The
             * editor preview has no post pass, so there we draw the old geometry shell as an approximation. */
            if (context.type != FormRenderType.PREVIEW)
            {
                Color tint = this.form.color.get();
                Color rimC = this.form.rimColor.get();

                DomeVolume.queue(mat, radius, this.form.heightScale.get(), opacity, this.form.fill.get(),
                    this.form.rimPower.get(), 1F, st,
                    tint.r, tint.g, tint.b, rimC.r, rimC.g, rimC.b);

                /* Ground cracks: glowing magma fissures spreading from the epicentre, reach follows the
                 * dome radius. Screen-space, depth-gated to the ground surface (CracksVolume). */
                if (this.form.cracks.get())
                {
                    Color cc = this.form.crackColor.get();

                    CracksVolume.queue(mat, radius * this.form.crackReach.get(), this.form.crackScale.get(),
                        1.2F, this.form.crackGlow.get(), this.form.crackDepth.get(), st, cc.r, cc.g, cc.b);
                }

                /* Volumetric smoke rising FROM THE CRACK LINES (fractal 3D density gated by the same crack
                 * field, depth-occluded). Bounded to the crack area. */
                if (this.form.smoke.get())
                {
                    Color sc = this.form.smokeColor.get();
                    float crackReach = radius * this.form.crackReach.get();

                    SmokeVolume.queue(mat, crackReach, this.form.smokeHeight.get(), this.form.smokeDensity.get(),
                        this.form.smokeScale.get(), this.form.smokeRise.get(),
                        this.form.crackScale.get(), crackReach, st, sc.r, sc.g, sc.b);
                }
            }
            else
            {
                drawShell(vc, mat, cam, this.form, radius, st, opacity);
            }

            /* The emissive accents stay as geometry through the provider (they don't self-overlap-tint;
             * additive glow composites fine and blooms under a pack). */
            if (this.form.lightning.get() > 0)
            {
                drawLightning(vc, mat, cam, this.form, radius, st, opacity);
            }

            if (this.form.baseRing.get())
            {
                drawBaseRing(vc, mat, this.form, radius, st, opacity);
            }

            if (this.form.dust.get() > 0)
            {
                ensureDustTex();

                drawDust(provider.getBuffer(RenderLayer.getEntityTranslucentEmissive(DUST_TEX)), mat, cam, this.form, radius, opacity);
            }
        }

        if (charge)
        {
            this.drawChargeCircle(vc, mat, cam, pNow, st);
        }

        if (deto >= 0F)
        {
            if (context.type == FormRenderType.PREVIEW)
            {
                drawDetonation(vc, mat, cam, this.form, deto);
            }
            else
            {
                /* Detonation shockwave = a bright white raymarch hemisphere (reuses DomeVolume, emissive
                 * uEmit, no tint absorption) — depth-correct against terrain, no translucent sorting grime. */
                float e = 1F - (1F - deto) * (1F - deto) * (1F - deto) * (1F - deto);
                float dradius = this.form.maxRadius.get() * 0.8F * e;

                if (dradius >= 0.05F)
                {
                    float fade = Math.min(1F, (1F - deto) * 1.5F);

                    DomeVolume.queue(mat, dradius, this.form.heightScale.get(), fade, 1.6F, 1.5F, 2.8F, st,
                        1F, 1F, 1F, 1F, 1F, 1F);
                }
            }
        }

        if (burst >= 0F)
        {
            ensureDustTex();

            VertexConsumer softVc = provider.getBuffer(RenderLayer.getEntityTranslucentEmissive(DUST_TEX));

            drawImpactBurst(vc, softVc, mat, cam, this.form, burst);
        }

        if (flash > 0.001F)
        {
            drawFlash(vc, mat, cam, flash);
        }

        provider.draw();
    }

    /* ---- beam "tail" lead-in (reuses the beam renderer, visual only) ---- */

    private BeamFormRenderer beamRenderer;
    private BeamForm beamForm;

    /** Progress span of the shot itself — the beam BLASTS from the sky circle to the ground this fast. */
    private static final float SHOT_TRAVEL = 0.03F;

    private void renderBeamTail(FormRenderingContext context)
    {
        float p = this.form.progress.get();
        float impact = this.form.impactAt.get();
        float fade = Math.max(0.001F, this.form.beamFade.get());

        /* The power-beat script: during the charge only the SKY CIRCLE exists — no standing beam. Just
         * before impactAt the beam FIRES: it blasts down from the circle over SHOT_TRAVEL and SLAMS the
         * ground exactly at impactAt (where the flash/burst/launch already trigger). */
        float shootStart = Math.max(0F, impact - SHOT_TRAVEL);

        if (p < shootStart)
        {
            return;   // charging — the beam doesn't exist yet
        }

        float bo;
        float radScale;
        float shotT;

        if (p < impact)
        {
            /* FIRING: the descending tip races to the ground. */
            shotT = (p - shootStart) / Math.max(1e-4F, impact - shootStart);
            bo = 1F;
            radScale = 1F;
        }
        else
        {
            float t = (p - impact) / fade;

            if (t >= 1F)
            {
                return;   // beam fully faded, the dome has taken over
            }

            /* LANDED: a brief swollen after-glow collapsing away in the lull. */
            shotT = 1F;
            bo = 1F - smooth(t);
            radScale = 1.2F * (1F - 0.5F * t);
        }

        if (this.beamForm == null)
        {
            this.beamForm = new BeamForm();
            this.beamRenderer = new BeamFormRenderer(this.beamForm);
        }

        this.beamForm.destruction.set(false);   // the dome owns destruction; the beam is visual only
        this.beamForm.direction.set(1);          // strike DOWN from the sky
        this.beamForm.height.set(this.form.beamHeight.get());
        this.beamForm.radius.set(this.form.beamRadius.get() * radScale);
        this.beamForm.duration.set(this.form.duration.get());
        /* Drive the beam's own descend as the SHOT: with impactAt=1 its descend = smooth(progress), so
         * feeding shotT as its progress extends the tip from the sky to the ground over the shot window. */
        this.beamForm.impactAt.set(1F);
        this.beamForm.progress.set(shotT);
        this.beamForm.opacity.set(bo);

        /* Same package → protected render3D is reachable; draws + flushes the beam at our transform. */
        this.beamRenderer.render3D(context);
    }

    /* ---- charge-up magic circle (the anime charge: spinning glyph, accelerating, crackling) ---- */

    /**
     * The CHARGE visual: a flat magic circle appears at the strike point, SPINS with hard acceleration
     * (frantic right before the fire), counter-rotating spoke sets, and lightning arcs crackle from the
     * rim up toward the beam — intensity ramping to the fire moment. On fire it flashes outward and dies.
     */
    private void drawChargeCircle(VertexConsumer vc, Matrix4f m, Vector3f cam, float p, float st)
    {
        float impact = this.form.impactAt.get();
        float fade = Math.max(0.001F, this.form.beamFade.get());
        float ch, alpha, expand = 1F;

        if (p <= impact)
        {
            /* The charge runs from the very START of the timeline (progress 0) to the fire. */
            ch = p / Math.max(0.001F, impact);
            alpha = Math.min(1F, ch * 14F);   // snaps in
        }
        else
        {
            float t = (p - impact) / fade;

            if (t >= 1F)
            {
                return;
            }

            /* Fired: the circle flashes outward and dies with the beam. */
            ch = 1F;
            alpha = 1F - smooth(t);
            expand = 1F + 0.6F * t;
        }

        Color rimC = this.form.rimColor.get();
        float r = mix(rimC.r, 1F, 0.5F) * lumScale;
        float g = mix(rimC.g, 1F, 0.5F) * lumScale;
        float b = mix(rimC.b, 1F, 0.5F) * lumScale;
        /* The glyph must scale with the DOME (the arena) — tied only to the thin beam it's a speck at
         * a 160-block max radius, invisible from any camera that frames the field. */
        float R = Math.max(Math.max(3F, this.form.beamRadius.get() * 5F), this.form.maxRadius.get() * 0.2F) * expand;
        /* The charge happens at the beam's FIRING END — the TOP, in the sky (the beam strikes down
         * through the circle). On the ground it read as "the whole charge is buried". */
        float y = this.form.beamHeight.get();
        /* Accelerating spin: slow at first, FRANTIC right before the fire (theta ~ ch^3). */
        float theta = 25.13F * ch * ch * ch + st * 0.6F;
        int seg = 48;
        float lw = Math.max(0.09F, R * 0.02F);   // stroke widths scale with the glyph

        /* Outer + inner rings (crisp glyph bands, both windings). */
        annulus(vc, m, R * 0.90F, R, y, r, g, b, alpha * 0.9F, seg);
        annulus(vc, m, R * 0.55F, R * 0.61F, y, r, g, b, alpha * 0.7F, seg);

        /* Rotating spokes (outer set) + counter-rotating inner set — the "gears" of the glyph. */
        for (int i = 0; i < 8; i++)
        {
            float a = theta + (i / 8F) * 6.2831853F;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);

            flatQuad(vc, m, ca * R * 0.62F, sa * R * 0.62F, ca * R * 0.89F, sa * R * 0.89F, lw, y, r, g, b, alpha * 0.85F);
        }

        for (int i = 0; i < 6; i++)
        {
            float a = -theta * 1.5F + (i / 6F) * 6.2831853F;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);

            flatQuad(vc, m, ca * R * 0.2F, sa * R * 0.2F, ca * R * 0.53F, sa * R * 0.53F, lw * 0.8F, y, r, g, b, alpha * 0.7F);
        }

        /* Crackling arcs from the rim up toward the beam — more + brighter as the charge builds. */
        int arcs = (int) (ch * ch * 7F);
        int epoch = (int) Math.floor(st * 11F);
        float flick = (float) Math.sin(Math.PI * (st * 11F - epoch));
        float boltCore = Math.max(0.045F, R * 0.012F);
        float boltGlow = boltCore * 2.6F;
        float[] p0 = new float[3], p1 = new float[3];

        for (int i = 0; i < arcs; i++)
        {
            if (hash01(i, epoch * 17 + 3) > 0.6F)
            {
                continue;
            }

            float baseA = hash01(i, epoch * 7 + 1) * 6.2831853F;
            float dropH = R * (0.2F + hash01(i, epoch * 5 + 2) * 0.4F);
            float aA = alpha * flick * (0.5F + 0.5F * ch);
            int K = 7;

            p0[0] = (float) Math.cos(baseA) * R * 0.95F;
            p0[1] = y;
            p0[2] = (float) Math.sin(baseA) * R * 0.95F;

            /* Arcs GATHER INWARD from the rim to the beam axis (energy converging into the fire point),
             * sagging slightly below the circle plane toward the beam. */
            for (int k = 1; k <= K; k++)
            {
                float t = k / (float) K;
                float jit = R * 0.22F * (1F - t * 0.5F);
                float jx = (hash01(i * 31 + k, epoch) - 0.5F) * jit;
                float jz = (hash01(i * 57 + k, epoch + 4) - 0.5F) * jit;

                p1[0] = (float) Math.cos(baseA) * R * 0.95F * (1F - t) + jx;
                p1[1] = y - dropH * t;
                p1[2] = (float) Math.sin(baseA) * R * 0.95F * (1F - t) + jz;

                emitBoltSeg(vc, m, cam, p0, p1, r, g, b, aA * (1F - t * 0.4F), boltCore, boltGlow);

                p0[0] = p1[0]; p0[1] = p1[1]; p0[2] = p1[2];
            }
        }
    }

    /** Flat annulus band on the ground (both windings, crisp edges — a glyph ring). */
    private static void annulus(VertexConsumer vc, Matrix4f m, float r0, float r1, float y,
        float r, float g, float b, float a, int seg)
    {
        for (int j = 0; j < seg; j++)
        {
            float a0 = (j / (float) seg) * 6.2831853F;
            float a1 = ((j + 1) / (float) seg) * 6.2831853F;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            ringVert(vc, m, c0 * r0, y, s0 * r0, r, g, b, a);
            ringVert(vc, m, c0 * r1, y, s0 * r1, r, g, b, a);
            ringVert(vc, m, c1 * r1, y, s1 * r1, r, g, b, a);
            ringVert(vc, m, c1 * r0, y, s1 * r0, r, g, b, a);

            ringVert(vc, m, c1 * r0, y, s1 * r0, r, g, b, a);
            ringVert(vc, m, c1 * r1, y, s1 * r1, r, g, b, a);
            ringVert(vc, m, c0 * r1, y, s0 * r1, r, g, b, a);
            ringVert(vc, m, c0 * r0, y, s0 * r0, r, g, b, a);
        }
    }

    /** Thin flat rectangle on the ground from (x0,z0) to (x1,z1) (both windings — a glyph spoke). */
    private static void flatQuad(VertexConsumer vc, Matrix4f m, float x0, float z0, float x1, float z1,
        float half, float y, float r, float g, float b, float a)
    {
        float dx = x1 - x0, dz = z1 - z0;
        float l = (float) Math.sqrt(dx * dx + dz * dz);

        if (l < 1e-4F)
        {
            return;
        }

        float px = -dz / l * half, pz = dx / l * half;

        ringVert(vc, m, x0 - px, y, z0 - pz, r, g, b, a);
        ringVert(vc, m, x1 - px, y, z1 - pz, r, g, b, a);
        ringVert(vc, m, x1 + px, y, z1 + pz, r, g, b, a);
        ringVert(vc, m, x0 + px, y, z0 + pz, r, g, b, a);

        ringVert(vc, m, x0 + px, y, z0 + pz, r, g, b, a);
        ringVert(vc, m, x1 + px, y, z1 + pz, r, g, b, a);
        ringVert(vc, m, x1 - px, y, z1 - pz, r, g, b, a);
        ringVert(vc, m, x0 - px, y, z0 - pz, r, g, b, a);
    }

    /* ---- energy hemisphere shell ---- */

    private static void drawShell(VertexConsumer vc, Matrix4f m, Vector3f cam, DomeForm form,
        float radius, float st, float opacity)
    {
        int seg = form.segments.get();
        int ringsN = form.ringsRes.get();
        float hs = form.heightScale.get();
        float rimPow = form.rimPower.get();
        float fill = form.fill.get();
        float turb = form.turbulence.get();
        float flow = st * form.swirlSpeed.get();

        Color bodyC = form.color.get();
        Color rimC = form.rimColor.get();

        /* Grid of quads over azimuth (a) × elevation (e ∈ [0, π/2]). */
        for (int i = 0; i < ringsN; i++)
        {
            float e0 = (i / (float) ringsN) * (float) (Math.PI / 2.0);
            float e1 = ((i + 1) / (float) ringsN) * (float) (Math.PI / 2.0);

            for (int j = 0; j < seg; j++)
            {
                float a0 = (j / (float) seg) * (float) (Math.PI * 2.0);
                float a1 = ((j + 1) / (float) seg) * (float) (Math.PI * 2.0);

                /* Front face. */
                emitVert(vc, m, cam, form, radius, hs, turb, flow, rimPow, fill, bodyC, rimC, opacity, e0, a0);
                emitVert(vc, m, cam, form, radius, hs, turb, flow, rimPow, fill, bodyC, rimC, opacity, e1, a0);
                emitVert(vc, m, cam, form, radius, hs, turb, flow, rimPow, fill, bodyC, rimC, opacity, e1, a1);
                emitVert(vc, m, cam, form, radius, hs, turb, flow, rimPow, fill, bodyC, rimC, opacity, e0, a1);

                /* Back face (reverse winding) — the shell is see-through, we want both sides + cull-proof. */
                emitVert(vc, m, cam, form, radius, hs, turb, flow, rimPow, fill, bodyC, rimC, opacity, e0, a1);
                emitVert(vc, m, cam, form, radius, hs, turb, flow, rimPow, fill, bodyC, rimC, opacity, e1, a1);
                emitVert(vc, m, cam, form, radius, hs, turb, flow, rimPow, fill, bodyC, rimC, opacity, e1, a0);
                emitVert(vc, m, cam, form, radius, hs, turb, flow, rimPow, fill, bodyC, rimC, opacity, e0, a0);
            }
        }
    }

    /** One shell vertex: position on the (turbulence-displaced) hemisphere, coloured by the fresnel term. */
    private static void emitVert(VertexConsumer vc, Matrix4f m, Vector3f cam, DomeForm form,
        float radius, float hs, float turb, float flow, float rimPow, float fill,
        Color bodyC, Color rimC, float opacity, float e, float a)
    {
        float ce = (float) Math.cos(e), se = (float) Math.sin(e);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);

        /* Outward unit direction on a unit hemisphere. */
        float nx = ce * ca, ny = se, nz = ce * sa;

        /* Turbulence: churn the radius by flowing value noise (never rendered as a texture, only warps). */
        float disp = 1F + turb * (noise3(a * 1.7F + flow, e * 2.3F - flow * 0.6F, 3.1F) - 0.5F) * 2F;
        float rr = radius * disp;

        float px = nx * rr, py = ny * rr * hs, pz = nz * rr;

        /* Fresnel: grazing angle (silhouette rim) → bright; face-on → faint. */
        float vx = cam.x - px, vy = cam.y - py, vz = cam.z - pz;
        float vlen = (float) Math.sqrt(vx * vx + vy * vy + vz * vz) + 1e-5F;
        float ndv = Math.abs((vx * nx + vy * ny * hs + vz * nz) / vlen);
        float fres = (float) Math.pow(1F - Math.min(1F, ndv), rimPow);

        /* Body carries the ABSORPTION TINT (shown across the whole shell → it can DARKEN/tint the world).
         * The bright rim is a THIN additive silhouette band on top (sharpened fresnel) — NOT a lerp toward
         * white, which (viewed from inside a big dome, where the grazing angle is everywhere) would white
         * out the entire shell and make it impossible to darken anything. */
        float rimBand = fres * fres;
        float r = bodyC.r + rimC.r * rimBand;
        float g = bodyC.g + rimC.g * rimBand;
        float b = bodyC.b + rimC.b * rimBand;

        /* Optical density (Beer–Lambert), so the dome TINTS the world behind it instead of showing crisp
         * clouds through a thin skin. The view ray's chord through the shell grows ~1/ndv toward the
         * silhouette → dense, near-opaque rim; face-on keeps a solid tint floor. `fill` is the absorption
         * strength (a real density knob). Both shell walls accumulate, so the through-dome tint doubles. */
        float path = 1F / Math.max(0.16F, ndv);
        float od = (1.0F + fill * 4F) * path;
        float alpha = opacity * (1F - (float) Math.exp(-od));

        if (oitVertexMode)
        {
            vc.vertex(m, px, py, pz).color(r * lumScale, g * lumScale, b * lumScale, alpha).texture(0.5F, 0.5F);
        }
        else
        {
            vc.vertex(m, px, py, pz).color(r * lumScale, g * lumScale, b * lumScale, alpha).texture(0.5F, 0.5F)
                .overlay(OverlayTexture.DEFAULT_UV).light(FULL).normal(nx, ny, nz);
        }

        BbsVfxRenderCompat.next(vc);
    }

    /* ---- white-out flash at the dome's birth ---- */

    /** Flash intensity: a FLASHBANG — instant blowout AT impact, then a hard quadratic decay (not a soft
     *  fade), so the white-out reads as a punch. */
    private static float flashIntensity(DomeForm form)
    {
        float fa = form.flash.get();

        if (fa <= 0F)
        {
            return 0F;
        }

        float t = (form.progress.get() - form.impactAt.get()) / Math.max(0.001F, form.flashFade.get());

        if (t < 0F || t >= 1F)
        {
            return 0F;
        }

        float d = 1F - t;

        return Math.min(1F, fa * 1.3F) * d * d;   // slight overshoot + sharp decay
    }

    /** Detonation window (progress) of the flash-dome punch at the dome's birth. */
    private static final float DETO_DUR = 0.22F;

    /** 0..1 through the detonation punch (fires when the DOME MANIFESTS, after the lull), or -1 inactive. */
    private static float detonationProgress(DomeForm form)
    {
        float t = (form.progress.get() - form.domeStart()) / DETO_DUR;

        return (t < 0F || t >= 1F) ? -1F : t;
    }

    /** The DETONATION flash-dome: a blinding near-white hemisphere that SNAPS out fast and blows away —
     *  the "boom" of the beam landing, distinct from the slow persistent energy shell. */
    private static void drawDetonation(VertexConsumer vc, Matrix4f m, Vector3f cam, DomeForm form, float dp)
    {
        /* SNAP to near-full size almost instantly (4th-power ease), so it's a BIG bright dome the moment
         * the beam lands — not a slowly-growing bubble that's only big once it's already faded. */
        float e = 1F - (1F - dp) * (1F - dp) * (1F - dp) * (1F - dp);
        float radius = form.maxRadius.get() * 0.8F * e;

        if (radius < 0.05F)
        {
            return;
        }

        float hs = form.heightScale.get();
        float fade = Math.min(1F, (1F - dp) * 1.5F);        // hold bright, then drop
        /* A blast should BLOOM under a pack — dim it far less than the persistent shell. */
        float blastLum = Math.max(0.8F, lumScale);
        float r = blastLum, g = blastLum, b = blastLum;
        int seg = Math.max(20, form.segments.get() / 2);
        int ringsN = Math.max(8, form.ringsRes.get() / 2);
        float[] p = new float[3], n = new float[3];

        for (int i = 0; i < ringsN; i++)
        {
            float e0 = (i / (float) ringsN) * 1.5707963F, e1 = ((i + 1) / (float) ringsN) * 1.5707963F;

            for (int j = 0; j < seg; j++)
            {
                float a0 = (j / (float) seg) * 6.2831853F, a1 = ((j + 1) / (float) seg) * 6.2831853F;

                detoVert(vc, m, cam, radius, hs, r, g, b, fade, e0, a0, p, n);
                detoVert(vc, m, cam, radius, hs, r, g, b, fade, e1, a0, p, n);
                detoVert(vc, m, cam, radius, hs, r, g, b, fade, e1, a1, p, n);
                detoVert(vc, m, cam, radius, hs, r, g, b, fade, e0, a1, p, n);

                detoVert(vc, m, cam, radius, hs, r, g, b, fade, e0, a1, p, n);
                detoVert(vc, m, cam, radius, hs, r, g, b, fade, e1, a1, p, n);
                detoVert(vc, m, cam, radius, hs, r, g, b, fade, e1, a0, p, n);
                detoVert(vc, m, cam, radius, hs, r, g, b, fade, e0, a0, p, n);
            }
        }
    }

    private static void detoVert(VertexConsumer vc, Matrix4f m, Vector3f cam, float radius, float hs,
        float r, float g, float b, float fade, float e, float a, float[] p, float[] n)
    {
        float ce = (float) Math.cos(e), se = (float) Math.sin(e);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);

        n[0] = ce * ca; n[1] = se; n[2] = ce * sa;
        p[0] = n[0] * radius; p[1] = n[1] * radius * hs; p[2] = n[2] * radius;

        /* Mostly solid blast, a touch brighter at the grazing rim. */
        float vx = cam.x - p[0], vy = cam.y - p[1], vz = cam.z - p[2];
        float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz) + 1e-5F;
        float ndv = Math.abs((vx * n[0] + vy * n[1] * hs + vz * n[2]) / vl);
        float alpha = fade * (0.75F + 0.25F * (1F - ndv));   // mostly solid blast, brighter at the rim

        vc.vertex(m, p[0], p[1], p[2]).color(r, g, b, alpha).texture(0.5F, 0.5F)
            .overlay(OverlayTexture.DEFAULT_UV).light(FULL).normal(n[0], n[1], n[2]);
        BbsVfxRenderCompat.next(vc);
    }

    /** Burst window duration (progress) of the built-in impact mini-explosion. */
    private static final float BURST_DUR = 0.24F;

    /** 0..1 through the impact-burst window, or -1 when inactive (before impact / after / disabled). */
    private static float burstProgress(DomeForm form)
    {
        if (!form.impactBurst.get())
        {
            return -1F;
        }

        float t = (form.progress.get() - form.impactAt.get()) / BURST_DUR;

        return (t < 0F || t >= 1F) ? -1F : t;
    }

    /** Built-in impact "punch": a blinding ground-zero CORE, a flat shockwave DISC racing out, radial
     *  SPEED-LINES (anime impact lines), and EJECTA (debris/embers hurled up-and-out, arcing down). Reads
     *  as FORCE — the strike smashing the world — not just a pretty dome. Soft billboards go on softVc. */
    private static void drawImpactBurst(VertexConsumer vc, VertexConsumer softVc, Matrix4f m, Vector3f cam, DomeForm form, float bp)
    {
        float e = 1F - (1F - bp) * (1F - bp);   // ease-out quad: SNAPS out fast then settles
        float fade = (1F - bp) * (1F - bp);     // bright leading edge, hard fall-off
        float maxR = form.maxRadius.get();
        Color rimC = form.rimColor.get();
        float r = mix(rimC.r, 1F, 0.6F) * lumScale;
        float g = mix(rimC.g, 1F, 0.6F) * lumScale;
        float b = mix(rimC.b, 1F, 0.6F) * lumScale;

        /* 1. Ground-zero CORE — a blinding point where the beam struck (instant, blows away fast). */
        float coreFade = (1F - bp) * (1F - bp) * (1F - bp);
        float coreW = Math.max(0.85F, lumScale);
        float coreSz = maxR * 0.14F * (0.7F + 0.6F * bp);

        emitDustQuad(softVc, m, cam, 0F, 1F + coreSz * 0.4F, 0F, coreSz, coreW, coreW, coreW, coreFade);

        /* 2. Flat shockwave DISC racing out along the ground (surface plane = local y 1). */
        float rr = maxR * 0.6F * e;
        float width = Math.max(0.5F, rr * 0.22F);
        float inner = Math.max(0F, rr - width);
        float y = 1.06F;
        int seg = Math.max(24, form.segments.get());
        float a = fade;

        for (int j = 0; j < seg; j++)
        {
            float a0 = (j / (float) seg) * 6.2831853F;
            float a1 = ((j + 1) / (float) seg) * 6.2831853F;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            ringVert(vc, m, c0 * inner, y, s0 * inner, r, g, b, 0F);
            ringVert(vc, m, c0 * rr, y, s0 * rr, r, g, b, a);
            ringVert(vc, m, c1 * rr, y, s1 * rr, r, g, b, a);
            ringVert(vc, m, c1 * inner, y, s1 * inner, r, g, b, 0F);

            ringVert(vc, m, c1 * inner, y, s1 * inner, r, g, b, 0F);
            ringVert(vc, m, c1 * rr, y, s1 * rr, r, g, b, a);
            ringVert(vc, m, c0 * rr, y, s0 * rr, r, g, b, a);
            ringVert(vc, m, c0 * inner, y, s0 * inner, r, g, b, 0F);
        }

        /* 3. SPEED-LINES: long thin FLAT radial streaks shooting out fast (the anime impact-line hit cue). */
        int lines = 60;
        float reach = maxR * 0.8F;
        float[] p0 = new float[3], p1 = new float[3];

        for (int k = 0; k < lines; k++)
        {
            float ang = (k / (float) lines) * 6.2831853F + hash01(k, 7) * 0.3F;
            float d = reach * (0.5F + 0.6F * hash01(k, 4)) * e;
            float ly = 1.4F + hash01(k, 5) * 1.2F;   // just above the surface = flat speed-lines
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            float len = 2.5F + hash01(k, 8) * 4F;
            float la = fade * (0.6F + 0.4F * hash01(k, 6));

            p0[0] = ca * Math.max(0F, d - len); p0[1] = ly; p0[2] = sa * Math.max(0F, d - len);
            p1[0] = ca * d; p1[1] = ly; p1[2] = sa * d;

            emitBoltSeg(vc, m, cam, p0, p1, r, g, b, la, 0.04F, 0.1F);
        }

        /* 4. EJECTA — matter hurled up and out from ground zero, arcing back down (this sells "smashed"). */
        int chunks = 46;
        float er = mix(rimC.r, 1F, 0.35F) * lumScale;   // warm lit debris
        float eg = mix(rimC.g, 0.55F, 0.35F) * lumScale;
        float eb = mix(rimC.b, 0.3F, 0.35F) * lumScale;

        for (int k = 0; k < chunks; k++)
        {
            float ang = hash01(k, 11) * 6.2831853F;
            float outSpd = maxR * (0.25F + 0.5F * hash01(k, 12));
            float upSpd = 10F + hash01(k, 13) * 12F;
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            float out = outSpd * bp;
            float cy = upSpd * bp - 26F * bp * bp;   // parabola up then down

            if (cy < -1F)
            {
                continue;   // already slammed back down
            }

            float sz = 0.5F + 0.7F * hash01(k, 14);
            float ca2 = fade * (0.7F + 0.3F * hash01(k, 15));

            emitDustQuad(softVc, m, cam, ca * out, 1F + Math.max(0F, cy), sa * out, sz, er, eg, eb, ca2);
        }
    }

    /** A screen-filling white quad pinned just in front of the camera (nearest depth → covers the view),
     *  the blinding white-out before the dome resolves. Camera basis recovered from the form matrix. */
    private static void drawFlash(VertexConsumer vc, Matrix4f m, Vector3f cam, float intensity)
    {
        Matrix4f inv = new Matrix4f(m).invert();
        Vector3f fwd = inv.transformDirection(new Vector3f(0F, 0F, -1F)).normalize();
        Vector3f rgt = inv.transformDirection(new Vector3f(1F, 0F, 0F)).normalize();
        Vector3f upv = inv.transformDirection(new Vector3f(0F, 1F, 0F)).normalize();

        float dist = 1.2F, sz = 2.8F;
        float cx = cam.x + fwd.x * dist, cy = cam.y + fwd.y * dist, cz = cam.z + fwd.z * dist;
        float rx = rgt.x * sz, ry = rgt.y * sz, rz = rgt.z * sz;
        float ux = upv.x * sz, uy = upv.y * sz, uz = upv.z * sz;
        float w = lumScale, a = Math.min(1F, intensity);

        boltVert(vc, m, cx - rx - ux, cy - ry - uy, cz - rz - uz, w, w, w, a);
        boltVert(vc, m, cx - rx + ux, cy - ry + uy, cz - rz + uz, w, w, w, a);
        boltVert(vc, m, cx + rx + ux, cy + ry + uy, cz + rz + uz, w, w, w, a);
        boltVert(vc, m, cx + rx - ux, cy + ry - uy, cz + rz - uz, w, w, w, a);
    }

    /* ---- dust haze on the expanding wavefront ---- */

    /**
     * Soft camera-facing dust motes kicked up along the wavefront: a disc of sample points, each spawning
     * a mote when the expanding front (currentRadius) sweeps past its distance, then rising, drifting out
     * and fading over a trailing span. Deterministic in the radius, dome-tinted energised dust.
     */
    private static void drawDust(VertexConsumer vc, Matrix4f m, Vector3f cam, DomeForm form, float radius, float opacity)
    {
        int count = form.dust.get();
        float maxR = form.maxRadius.get();
        float span = Math.max(1F, form.clearSpan.get() * 1.8F);
        Color body = form.color.get();
        float dr = mix(body.r, 0.55F, 0.45F) * lumScale;
        float dg = mix(body.g, 0.5F, 0.45F) * lumScale;
        float db = mix(body.b, 0.6F, 0.45F) * lumScale;

        for (int j = 0; j < count; j++)
        {
            float a = hash01(j, 1) * 6.2831853F;
            float rr = maxR * (float) Math.sqrt(hash01(j, 2));   // uniform over the disc
            float e = radius - rr;

            if (e <= 0F || e > span)
            {
                continue;
            }

            float p = e / span;
            float y = 1F + p * (1.4F + hash01(j, 3) * 3.2F);
            float rr2 = rr + p * 0.7F;
            float px = (float) Math.cos(a) * rr2, pz = (float) Math.sin(a) * rr2;
            float size = (0.7F + hash01(j, 4) * 1.3F) * (0.5F + p);
            float al = opacity * 0.5F * (float) Math.sin(Math.PI * p);

            if (al <= 0.003F)
            {
                continue;
            }

            emitDustQuad(vc, m, cam, px, y, pz, size, dr, dg, db, al);
        }
    }

    private static void emitDustQuad(VertexConsumer vc, Matrix4f m, Vector3f cam, float px, float py, float pz,
        float half, float r, float g, float b, float a)
    {
        float vx = px - cam.x, vy = py - cam.y, vz = pz - cam.z;
        float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);

        if (vl < 1e-4F)
        {
            return;
        }

        vx /= vl; vy /= vl; vz /= vl;

        /* right = normalize(worldUp × view); up = view × right — a camera-facing billboard. */
        float rx = vz, ry = 0F, rz = -vx;   // (0,1,0) × view
        float rl = (float) Math.sqrt(rx * rx + rz * rz);

        if (rl < 1e-4F)
        {
            rx = 1F; rz = 0F; rl = 1F;
        }

        rx = rx / rl * half; ry = 0F; rz = rz / rl * half;

        float ux = vy * rz - vz * ry;
        float uy = vz * rx - vx * rz;
        float uz = vx * ry - vy * rx;   // view × right (already scaled by half via r)

        dustVert(vc, m, px - rx - ux, py - ry - uy, pz - rz - uz, r, g, b, a, 0F, 0F);
        dustVert(vc, m, px - rx + ux, py - ry + uy, pz - rz + uz, r, g, b, a, 0F, 1F);
        dustVert(vc, m, px + rx + ux, py + ry + uy, pz + rz + uz, r, g, b, a, 1F, 1F);
        dustVert(vc, m, px + rx - ux, py + ry - uy, pz + rz - uz, r, g, b, a, 1F, 0F);
    }

    private static void dustVert(VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, float g, float b, float a, float u, float v)
    {
        emitV(vc, m, x, y, z, r, g, b, a, u, v);
    }

    /* ---- electric arcs crawling over the surface ---- */

    /**
     * Flickering lightning bolts that crawl up the dome surface. Deterministic in {@code st} (scrub-exact):
     * time is quantised into "strike" epochs; each bolt is randomly active per epoch, its jagged path
     * reseeded each strike, its brightness a sine pulse over the epoch. Drawn as thin camera-facing bright
     * ribbons just outside the shell.
     */
    private static void drawLightning(VertexConsumer vc, Matrix4f m, Vector3f cam, DomeForm form,
        float radius, float st, float opacity)
    {
        int n = form.lightning.get();
        float hs = form.heightScale.get();
        float rate = 7F;
        int epoch = (int) Math.floor(st * rate);
        float frac = st * rate - epoch;
        float pulse = (float) Math.sin(Math.PI * frac);   // fade in/out across the strike

        if (pulse <= 0.01F)
        {
            return;
        }

        Color rimC = form.rimColor.get();
        float r = mix(rimC.r, 1F, 0.7F) * lumScale;
        float g = mix(rimC.g, 1F, 0.7F) * lumScale;
        float b = mix(rimC.b, 1F, 0.7F) * lumScale;
        /* Thin bright core with a soft faint glow to each side (fades at the edges) — reads as an arc,
         * not a solid band. Small absolute widths so it stays hair-thin on a big dome. */
        float core = 0.05F;
        float glow = 0.22F;
        int K = 26;
        float[] p0 = new float[3], p1 = new float[3];

        for (int i = 0; i < n; i++)
        {
            if (hash01(i, epoch * 13 + 7) > 0.55F)
            {
                continue;   // this bolt idle this strike
            }

            float baseA = (i / (float) n) * 6.2831853F + hash01(i, 1) * 0.6F;
            float topE = (0.4F + 0.5F * hash01(i, epoch * 7 + 3)) * 1.5707963F;
            float intensity = pulse * (0.6F + 0.4F * hash01(i, epoch * 5 + 2)) * opacity;

            jaggedPoint(i, 0, epoch, baseA, topE, radius, hs, p0);

            for (int k = 1; k <= K; k++)
            {
                float t = k / (float) K;

                jaggedPoint(i, k, epoch, baseA, topE, radius, hs, p1);

                float aA = intensity * (1F - t * 0.55F);   // taper toward the tip

                emitBoltSeg(vc, m, cam, p0, p1, r, g, b, aA, core, glow);

                p0[0] = p1[0]; p0[1] = p1[1]; p0[2] = p1[2];
            }
        }
    }

    /** A jagged point k/K along bolt i on the dome surface: base meridian + sharp 2-octave jitter in
     *  azimuth, elevation and radius (reseeded per strike epoch) so the path zig-zags like an arc. */
    private static void jaggedPoint(int i, int k, int epoch, float baseA, float topE, float radius, float hs, float[] out)
    {
        int K = 26;
        float t = k / (float) K;
        float env = (float) Math.sin(Math.PI * Math.min(1F, t)) * 0.6F + 0.4F;   // less jitter at the ends

        float aj = ((hash01(i * 131 + k * 7, epoch) - 0.5F) * 0.28F
                 + (hash01(i * 263 + k * 13, epoch + 5) - 0.5F) * 0.14F) * env;
        float ej = ((hash01(i * 71 + k * 5, epoch + 9) - 0.5F) * 0.20F) * env;
        float rj = ((hash01(i * 197 + k * 11, epoch + 3) - 0.5F) * 0.9F) * env;

        float a = baseA + aj;
        float e = topE * t + ej;
        float R = radius * 1.01F + rj;

        surfacePoint(a, Math.max(0F, e), R, hs, out);
    }

    private static void surfacePoint(float a, float e, float R, float hs, float[] out)
    {
        float ce = (float) Math.cos(e), se = (float) Math.sin(e);

        out[0] = ce * (float) Math.cos(a) * R;
        out[1] = se * R * hs;
        out[2] = ce * (float) Math.sin(a) * R;
    }

    /** One camera-facing segment: a bright thin core flanked by faint glow that fades to 0 at the edges
     *  (two soft quads either side of the centre line), so a bolt reads as a glowing arc not a slab. */
    private static void emitBoltSeg(VertexConsumer vc, Matrix4f m, Vector3f cam, float[] p0, float[] p1,
        float r, float g, float b, float a, float core, float glow)
    {
        float dx = p1[0] - p0[0], dy = p1[1] - p0[1], dz = p1[2] - p0[2];
        float mx = (p0[0] + p1[0]) * 0.5F, my = (p0[1] + p1[1]) * 0.5F, mz = (p0[2] + p1[2]) * 0.5F;
        float vx = cam.x - mx, vy = cam.y - my, vz = cam.z - mz;

        /* side = normalize(dir × view) — perpendicular to the segment, facing the camera. */
        float sx = dy * vz - dz * vy;
        float sy = dz * vx - dx * vz;
        float sz = dx * vy - dy * vx;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);

        if (sl < 1e-5F)
        {
            return;
        }

        sx /= sl; sy /= sl; sz /= sl;

        float cx = sx * core, cy = sy * core, cz = sz * core;
        float gx = sx * glow, gy = sy * glow, gz = sz * glow;

        /* left glow (edge a=0 → core a) */
        boltVert(vc, m, p0[0] - gx, p0[1] - gy, p0[2] - gz, r, g, b, 0F);
        boltVert(vc, m, p1[0] - gx, p1[1] - gy, p1[2] - gz, r, g, b, 0F);
        boltVert(vc, m, p1[0] - cx, p1[1] - cy, p1[2] - cz, r, g, b, a);
        boltVert(vc, m, p0[0] - cx, p0[1] - cy, p0[2] - cz, r, g, b, a);

        /* bright core band */
        boltVert(vc, m, p0[0] - cx, p0[1] - cy, p0[2] - cz, r, g, b, a);
        boltVert(vc, m, p1[0] - cx, p1[1] - cy, p1[2] - cz, r, g, b, a);
        boltVert(vc, m, p1[0] + cx, p1[1] + cy, p1[2] + cz, r, g, b, a);
        boltVert(vc, m, p0[0] + cx, p0[1] + cy, p0[2] + cz, r, g, b, a);

        /* right glow (core a → edge a=0) */
        boltVert(vc, m, p0[0] + cx, p0[1] + cy, p0[2] + cz, r, g, b, a);
        boltVert(vc, m, p1[0] + cx, p1[1] + cy, p1[2] + cz, r, g, b, a);
        boltVert(vc, m, p1[0] + gx, p1[1] + gy, p1[2] + gz, r, g, b, 0F);
        boltVert(vc, m, p0[0] + gx, p0[1] + gy, p0[2] + gz, r, g, b, 0F);
    }

    private static void boltVert(VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, float g, float b, float a)
    {
        emitV(vc, m, x, y, z, r, g, b, a, 0.5F, 0.5F);
    }

    private static float mix(float a, float b, float t)
    {
        return a + (b - a) * t;
    }

    /* ---- ground shockwave ring at the wavefront ---- */

    private static void drawBaseRing(VertexConsumer vc, Matrix4f m, DomeForm form, float radius, float st, float opacity)
    {
        int seg = form.segments.get();
        float width = form.ringWidth.get();
        float inner = Math.max(0F, radius - width);
        float outer = radius + width * 0.4F;
        float y = 1.05F;   // surface plane is local y 1 (origin sits inside the top ground block)

        Color rimC = form.rimColor.get();
        float r = rimC.r, g = rimC.g, b = rimC.b;
        float a = opacity;

        for (int j = 0; j < seg; j++)
        {
            float a0 = (j / (float) seg) * (float) (Math.PI * 2.0);
            float a1 = ((j + 1) / (float) seg) * (float) (Math.PI * 2.0);
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            /* Bright crest at the ring centre, fading to both edges (soft band). Flat on the ground →
             * emit both windings so ground-plane back-face culling can't hide it. */
            ringVert(vc, m, c0 * inner, y, s0 * inner, r, g, b, 0F);
            ringVert(vc, m, c0 * radius, y, s0 * radius, r, g, b, a);
            ringVert(vc, m, c1 * radius, y, s1 * radius, r, g, b, a);
            ringVert(vc, m, c1 * inner, y, s1 * inner, r, g, b, 0F);

            ringVert(vc, m, c1 * inner, y, s1 * inner, r, g, b, 0F);
            ringVert(vc, m, c1 * radius, y, s1 * radius, r, g, b, a);
            ringVert(vc, m, c0 * radius, y, s0 * radius, r, g, b, a);
            ringVert(vc, m, c0 * inner, y, s0 * inner, r, g, b, 0F);

            ringVert(vc, m, c0 * radius, y, s0 * radius, r, g, b, a);
            ringVert(vc, m, c0 * outer, y, s0 * outer, r, g, b, 0F);
            ringVert(vc, m, c1 * outer, y, s1 * outer, r, g, b, 0F);
            ringVert(vc, m, c1 * radius, y, s1 * radius, r, g, b, a);

            ringVert(vc, m, c1 * radius, y, s1 * radius, r, g, b, a);
            ringVert(vc, m, c1 * outer, y, s1 * outer, r, g, b, 0F);
            ringVert(vc, m, c0 * outer, y, s0 * outer, r, g, b, 0F);
            ringVert(vc, m, c0 * radius, y, s0 * radius, r, g, b, a);
        }
    }

    private static void ringVert(VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, float g, float b, float a)
    {
        emitV(vc, m, x, y, z, r * lumScale, g * lumScale, b * lumScale, a, 0.5F, 0.5F);
    }

    /* ---- helpers ---- */

    /** Cheap 3D value noise (0..1) from smooth-hashed lattice — used only to displace geometry. */
    private static float noise3(float x, float y, float z)
    {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        float xf = x - xi, yf = y - yi, zf = z - zi;
        float u = xf * xf * (3F - 2F * xf), v = yf * yf * (3F - 2F * yf), w = zf * zf * (3F - 2F * zf);

        float c000 = h(xi, yi, zi), c100 = h(xi + 1, yi, zi);
        float c010 = h(xi, yi + 1, zi), c110 = h(xi + 1, yi + 1, zi);
        float c001 = h(xi, yi, zi + 1), c101 = h(xi + 1, yi, zi + 1);
        float c011 = h(xi, yi + 1, zi + 1), c111 = h(xi + 1, yi + 1, zi + 1);

        float x00 = c000 + (c100 - c000) * u, x10 = c010 + (c110 - c010) * u;
        float x01 = c001 + (c101 - c001) * u, x11 = c011 + (c111 - c011) * u;
        float y0 = x00 + (x10 - x00) * v, y1 = x01 + (x11 - x01) * v;

        return y0 + (y1 - y0) * w;
    }

    private static float h(int x, int y, int z)
    {
        int i = x * 374761393 + y * 668265263 + z * 1274126177;

        i = (i ^ (i >>> 13)) * 1274126177;

        return ((i ^ (i >>> 16)) & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    /* ---- world levelling: blocks swept away as the wavefront passes ---- */

    /** Trivial no-physics bakes for the razed-block VAO, cached per block-list content stamp. */
    private static final Map<Long, DestructionPhysics.Bake> LEVEL_BAKES = new HashMap<>();

    /** GPU levelling VAOs (INSTANCED unit cubes, animated in the dome_level_inst VSH), cached per stamp.
     *  LRU of a few fields — the editor copy and the scene actor can carry DIFFERENT stamps in the same
     *  frame; a single-slot cache would delete+rebuild a 200k VBO every frame (the re-cut lag). */
    private static final java.util.LinkedHashMap<Long, DomeLevelVAO> LEVEL_GPU = new java.util.LinkedHashMap<>(8, 0.75F, true);

    private static final int LEVEL_GPU_MAX = 4;

    private void renderLevel(FormRenderingContext context, float radius)
    {
        List<DestructionBlock> blocks = this.form.blocks.getAllTyped();

        if (blocks.isEmpty())
        {
            return;
        }

        long stamp = levelStamp(blocks);

        /* GPU PATH (default): geometry is uploaded ONCE; the radial-front settle+dissolve runs in the
         * vertex shader from the currentRadius uniform — no per-frame CPU pose loop / buffer re-upload, so
         * a 100k+-block level stays cheap. (Disabled under Iris: a custom core program isn't pack-lit;
         * fall back to the CPU path there.) */
        if (DestructionShader.DOME != null && DomeLevelVAO.isSupported() && !BBSRendering.isIrisShadersEnabled())
        {
            DomeLevelVAO vao = LEVEL_GPU.get(stamp);

            if (vao == null || !vao.isBuilt())
            {
                /* Evict the least-recently-used field once over budget (never the ones in use this frame). */
                while (LEVEL_GPU.size() >= LEVEL_GPU_MAX)
                {
                    java.util.Iterator<DomeLevelVAO> it = LEVEL_GPU.values().iterator();

                    it.next().delete();
                    it.remove();
                }

                vao = new DomeLevelVAO();
                vao.build(blocks);
                LEVEL_GPU.put(stamp, vao);
            }

            vao.render(context.stack, radius, this.form.clearSpan.get(), launchFactor(this.form), context.light, OverlayTexture.DEFAULT_UV);

            return;
        }

        /* IRIS LOD: a custom core program renders invisible under a pack, and animating the FULL field on
         * the CPU lags at a large radius. So the field is radial BANDS: only the 1-few bands the front is
         * CURRENTLY crossing animate on the CPU (pack-lit) — blocks fly UP + vaporise as the front sweeps
         * them; bands ahead of the front are build-once pack-lit STATIC shells (zero per-frame CPU); bands
         * behind are gone. Cost is bounded by the ring width, not the radius. Compromise vs vanilla: no
         * simultaneous launch/hang — the leap rides the passing front. */
        if (BBSRendering.isIrisShadersEnabled())
        {
            this.renderLevelIris(context, stamp, blocks, radius);

            return;
        }

        /* CPU FALLBACK (vanilla without instancing support): whole-field per-frame poses. */
        DestructionPhysics.Bake bake = bakeFor(stamp, blocks);
        DestructionPhysVAO vao = DestructionPhysVAO.of(bake, blocks);

        if (vao == null)
        {
            return;
        }

        float[] pose = new float[bake.units * 7];
        float[] scale = new float[bake.units];

        this.computeLevelPoses(pose, scale, bake, blocks, radius, launchFactor(this.form));
        vao.render(pose, scale, bake.units, context.stack, 1F, 1F, 1F, 1F, context.light, OverlayTexture.DEFAULT_UV);
    }

    /* ---- Iris levelling: per-CLUSTER static VBO moved by a MATRIX (vanilla program → pack-lit) ---- */

    private static final class IrisChunks
    {
        DestructionStaticVAO[] vaos;
        List<DestructionBlock>[] subs;
        boolean[] tried;
        float[] cx, cy, cz, cd;   // cluster centre + horizontal distance
        int[] hash;

        void delete()
        {
            if (this.vaos == null)
            {
                return;
            }

            for (DestructionStaticVAO vao : this.vaos)
            {
                if (vao != null)
                {
                    vao.delete();
                }
            }
        }
    }

    private static final java.util.LinkedHashMap<Long, IrisChunks> IRIS_CHUNKS = new java.util.LinkedHashMap<>(4, 0.75F, true);

    /**
     * IRIS levelling with FULL vanilla motion, no per-frame CPU vertex work: the field is split into
     * CLUSTERS, each baked ONCE into a static VBO. Every frame each cluster is drawn with its own MATRIX
     * (launch translate + rigid tumble about its centre + dissolve scale) through the vanilla entity
     * program — so the pack lights it AND it moves exactly like the vanilla instanced path (clusters leap,
     * hang, tumble, vaporise). Cost = one draw per live cluster, bounded by an adaptive cluster size.
     */
    @SuppressWarnings("unchecked")
    private void renderLevelIris(FormRenderingContext context, long stamp, List<DestructionBlock> blocks, float radius)
    {
        IrisChunks ch = IRIS_CHUNKS.get(stamp);

        if (ch == null)
        {
            while (IRIS_CHUNKS.size() >= 2)
            {
                java.util.Iterator<IrisChunks> it = IRIS_CHUNKS.values().iterator();

                it.next().delete();
                it.remove();
            }

            /* Adaptive cluster size: keep the cluster (= draw-call) count bounded on a huge radius while
             * staying fine on a normal one. Smaller than before — big clusters tumbled as huge slabs and
             * read badly under shaders; a lower cap keeps them chunk-sized. */
            int cs = Math.max(3, Math.min(8, Math.round(this.form.maxRadius.get() / 12F)));
            java.util.HashMap<Long, Integer> cell = new java.util.HashMap<>();
            List<List<DestructionBlock>> lists = new ArrayList<>();

            for (DestructionBlock b : blocks)
            {
                long key = ((long) Math.floorDiv(b.x.get(), cs) & 0x1FFFFF)
                    | (((long) Math.floorDiv(b.y.get(), cs) & 0x1FFFFF) << 21)
                    | (((long) Math.floorDiv(b.z.get(), cs) & 0x1FFFFF) << 42);
                Integer idx = cell.get(key);

                if (idx == null)
                {
                    idx = lists.size();
                    cell.put(key, idx);
                    lists.add(new ArrayList<>());
                }

                lists.get(idx).add(b);
            }

            int n = lists.size();

            ch = new IrisChunks();
            ch.vaos = new DestructionStaticVAO[n];
            ch.subs = new List[n];
            ch.tried = new boolean[n];
            ch.cx = new float[n]; ch.cy = new float[n]; ch.cz = new float[n]; ch.cd = new float[n];
            ch.hash = new int[n];

            for (int i = 0; i < n; i++)
            {
                List<DestructionBlock> sub = lists.get(i);

                ch.subs[i] = sub;

                float sx = 0F, sy = 0F, sz = 0F;

                for (DestructionBlock b : sub)
                {
                    sx += b.x.get() + 0.5F; sy += b.y.get() + 0.5F; sz += b.z.get() + 0.5F;
                }

                ch.cx[i] = sx / sub.size(); ch.cy[i] = sy / sub.size(); ch.cz[i] = sz / sub.size();
                ch.cd[i] = (float) Math.sqrt(ch.cx[i] * ch.cx[i] + ch.cz[i] * ch.cz[i]);
                ch.hash[i] = Float.floatToIntBits(ch.cx[i]) * 73856093 ^ Float.floatToIntBits(ch.cy[i]) * 19349663 ^ Float.floatToIntBits(ch.cz[i]) * 83492791;
            }

            IRIS_CHUNKS.put(stamp, ch);
        }

        float lt = launchFactor(this.form);
        float riseE = 1F - (1F - lt) * (1F - lt);
        float span = Math.max(0.5F, this.form.clearSpan.get());
        float dw = span + 8F;   // wider dissolve window so the front doesn't "pop" clusters out
        int built = 0;

        for (int i = 0; i < ch.subs.length; i++)
        {
            float e = radius - ch.cd[i];
            float dp = e <= 0F ? 0F : Math.min(1F, e / dw);

            if (dp >= 1F)
            {
                continue;   // vaporised — gone
            }

            float diss = smooth(dp);

            /* Cluster launch + tumble (same recipe as the vanilla VSH), driven by the global launch;
             * as the dome front reaches it, the cluster DRIFTS UP into the light + FADES + shrinks. */
            int h = ch.hash[i];
            float launchY = (1.5F + hash01(h, 1) * 3F) * riseE + diss * (2F + hash01(h, 8) * 3F);
            float outA = hash01(h, 2) * 6.2831853F;
            float outD = (0.5F + hash01(h, 3)) * lt;
            float lxo = (float) Math.cos(outA) * outD, lzo = (float) Math.sin(outA) * outD;
            float ang = lt * (hash01(h, 4) * 2F - 1F) * 4F + diss * 2F;
            float ax = hash01(h, 5) - 0.5F, ay = hash01(h, 6) - 0.5F, az = hash01(h, 7) - 0.5F;
            float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);

            if (al < 1e-4F)
            {
                ax = 0F; ay = 1F; az = 0F; al = 1F;
            }

            ax /= al; ay /= al; az /= al;

            /* Dissolve by SCALE ONLY — the cluster stays fully OPAQUE and simply shrinks to nothing as the
             * front sweeps past (no alpha fade → no translucent "see-through glass slab" under shaders). */
            float s = 1F - diss;
            float alpha = 1F;

            if (ch.vaos[i] == null && !ch.tried[i])
            {
                if (built >= 2)
                {
                    continue;   // amortise the one-time build across frames
                }

                ch.tried[i] = true;
                built++;

                int[] idx = new int[ch.subs[i].size()];

                for (int k = 0; k < idx.length; k++)
                {
                    idx[k] = k;
                }

                ch.vaos[i] = DestructionStaticVAO.create(ch.subs[i], idx);
            }

            if (ch.vaos[i] == null)
            {
                continue;
            }

            context.stack.push();
            context.stack.translate(ch.cx[i] + lxo, ch.cy[i] + launchY, ch.cz[i] + lzo);
            context.stack.multiply(new Quaternionf().fromAxisAngleRad(ax, ay, az, ang));
            context.stack.scale(s, s, s);
            context.stack.translate(-ch.cx[i], -ch.cy[i], -ch.cz[i]);
            ch.vaos[i].render(context.stack, 1F, 1F, 1F, alpha, context.light, OverlayTexture.DEFAULT_UV);
            context.stack.pop();
        }
    }

    /** 0 before the beam fires, then ramps over the remaining timeline as chunks launch and drift. */
    private static float launchFactor(DomeForm form)
    {
        if (!form.beamTail.get())
        {
            return 0F;
        }

        float impact = form.impactAt.get();
        float t = (form.progress.get() - impact) / Math.max(0.001F, 1F - impact);

        return t < 0F ? 0F : (t > 1F ? 1F : t);
    }

    /**
     * Per-UNIT pose (CPU fallback, Iris). Blocks launch up in CLUSTERS (shared launch + varied tumble),
     * drift, then dissolve UP into the light as the dome front sweeps past — mirrors dome_level_inst.vsh.
     */
    private void computeLevelPoses(float[] pose, float[] scale, DestructionPhysics.Bake bake, List<DestructionBlock> blocks, float radius, float lt)
    {
        float span = Math.max(0.5F, this.form.clearSpan.get());
        float riseE = 1F - (1F - lt) * (1F - lt);

        for (int u = 0; u < bake.units; u++)
        {
            int bi = bake.unitBlock[u];
            DestructionBlock b = blocks.get(bi);
            int oct = bake.unitOctant[u];
            float sx = oct < 0 ? 0F : ((oct & 1) == 0 ? -1F : 1F);
            float sy = oct < 0 ? 0F : ((oct & 2) == 0 ? -1F : 1F);
            float sz = oct < 0 ? 0F : ((oct & 4) == 0 ? -1F : 1F);
            float bcx = b.x.get() + 0.5F, bcy = b.y.get() + 0.5F, bcz = b.z.get() + 0.5F;
            float d = (float) Math.sqrt(bcx * bcx + bcz * bcz);
            float e = radius - d;
            int o = u * 7;

            /* CLUSTER (3-block cells) — chunks share a launch + tumble, varied per cluster. */
            int ckey = Math.floorDiv(b.x.get(), 3) * 73856093 ^ Math.floorDiv(b.y.get(), 3) * 19349663 ^ Math.floorDiv(b.z.get(), 3) * 83492791;
            float upH = 1.5F + hash01(ckey, 1) * 3F;
            float launchY = upH * riseE;
            float outA = hash01(ckey, 2) * 6.2831853F;
            float outD = (0.5F + hash01(ckey, 3)) * lt;
            float lx = (float) Math.cos(outA) * outD, lz = (float) Math.sin(outA) * outD;
            float cang = lt * (hash01(ckey, 4) * 2F - 1F) * 4F;
            float cax = hash01(ckey, 5) - 0.5F, cay = hash01(ckey, 6) - 0.5F, caz = hash01(ckey, 7) - 0.5F;
            float cal = (float) Math.sqrt(cax * cax + cay * cay + caz * caz);

            if (cal < 1e-4F)
            {
                cax = 0F; cay = 1F; caz = 0F; cal = 1F;
            }

            cax /= cal; cay /= cal; caz /= cal;

            float cs = (float) Math.sin(cang * 0.5F), ccw = (float) Math.cos(cang * 0.5F);

            if (e <= 0F)
            {
                /* Launched + tumbling, not yet reached by the front. */
                pose[o] = bcx + lx + sx * 0.25F; pose[o + 1] = bcy + launchY + sy * 0.25F; pose[o + 2] = bcz + lz + sz * 0.25F;
                pose[o + 3] = cax * cs; pose[o + 4] = cay * cs; pose[o + 5] = caz * cs; pose[o + 6] = ccw;
                scale[u] = 1F;

                continue;
            }

            float p = Math.min(1F, e / span);
            float sp = smooth(p);

            /* Consumed by the front: from the launched position, drift UP into the light + dissolve. */
            float x = bcx + lx + (hash01(bi, 21) - 0.5F) * 0.6F * sp;
            float z = bcz + lz + (hash01(bi, 23) - 0.5F) * 0.6F * sp;
            float y = bcy + launchY + sp * (1F + hash01(bi, 22) * 2F);
            float off = 0.25F + 0.28F * sp;

            pose[o] = x + sx * off;
            pose[o + 1] = y + sy * off;
            pose[o + 2] = z + sz * off;
            scale[u] = 1F - sp;

            pose[o + 3] = cax * cs; pose[o + 4] = cay * cs; pose[o + 5] = caz * cs; pose[o + 6] = ccw;
        }
    }

    /** Build (or fetch) the raze bake: a fraction of full blocks shatter into 8 octants, the rest stay
     *  whole chunks (keeps debris down). Cached per content stamp. */
    private static DestructionPhysics.Bake bakeFor(long stamp, List<DestructionBlock> blocks)
    {
        DestructionPhysics.Bake bake = LEVEL_BAKES.get(stamp);

        if (bake != null && bake.count == blocks.size())
        {
            return bake;
        }

        if (LEVEL_BAKES.size() > 32)
        {
            LEVEL_BAKES.clear();
        }

        int n = blocks.size();
        float shatterFrac = 0.3F;
        int units = 0;

        for (int i = 0; i < n; i++)
        {
            units += hash01(i, 40) < shatterFrac ? 8 : 1;
        }

        int[] unitBlock = new int[units];
        byte[] unitOctant = new byte[units];
        int k = 0;

        for (int i = 0; i < n; i++)
        {
            if (hash01(i, 40) < shatterFrac)
            {
                for (int oct = 0; oct < 8; oct++)
                {
                    unitBlock[k] = i;
                    unitOctant[k] = (byte) oct;
                    k++;
                }
            }
            else
            {
                unitBlock[k] = i;
                unitOctant[k] = -1;
                k++;
            }
        }

        bake = DestructionPhysics.customUnitBake(n, unitBlock, unitOctant);
        LEVEL_BAKES.put(stamp, bake);

        return bake;
    }

    private static long levelStamp(List<DestructionBlock> blocks)
    {
        long h = blocks.size();
        int step = Math.max(1, blocks.size() / 16);

        for (int i = 0; i < blocks.size(); i += step)
        {
            DestructionBlock b = blocks.get(i);

            h = h * 1099511628211L + (b.x.get() * 31L + b.y.get()) * 31L + b.z.get();
        }

        return h;
    }

    private static float hash01(int a, int b)
    {
        int i = a * 374761393 + b * 668265263;

        i = (i ^ (i >>> 13)) * 1274126177;

        return ((i ^ (i >>> 16)) & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    private static float smooth(float t)
    {
        t = t < 0F ? 0F : (t > 1F ? 1F : t);

        return t * t * (3F - 2F * t);
    }

    private static void ensureWhiteTex()
    {
        if (whiteReady)
        {
            return;
        }

        NativeImage img = new NativeImage(2, 2, false);

        for (int y = 0; y < 2; y++)
        {
            for (int x = 0; x < 2; x++)
            {
                img.setColor(x, y, 0xFFFFFFFF);
            }
        }

        NativeImageBackedTexture t = new NativeImageBackedTexture(img);

        t.setFilter(true, false);
        MinecraftClient.getInstance().getTextureManager().registerTexture(WHITE_TEX, t);
        whiteReady = true;
    }

    /** Soft round dust sprite: white RGB, radial alpha falloff (a solid-ish core so it survives the pack
     *  alpha test, fading to 0 at the rim so motes read as soft puffs not squares). */
    private static void ensureDustTex()
    {
        if (dustReady)
        {
            return;
        }

        int s = 32;
        NativeImage img = new NativeImage(s, s, false);

        for (int y = 0; y < s; y++)
        {
            for (int x = 0; x < s; x++)
            {
                float dx = (x + 0.5F) / s - 0.5F, dy = (y + 0.5F) / s - 0.5F;
                float d = (float) Math.sqrt(dx * dx + dy * dy) * 2F;   // 0 centre .. 1 edge
                float aa = Math.max(0F, 1F - d);

                aa = aa * aa * (3F - 2F * aa);   // smootherstep falloff
                int av = (int) (aa * 255F);

                img.setColor(x, y, (av << 24) | 0x00FFFFFF);
            }
        }

        NativeImageBackedTexture t = new NativeImageBackedTexture(img);

        t.setFilter(true, false);
        MinecraftClient.getInstance().getTextureManager().registerTexture(DUST_TEX, t);
        dustReady = true;
    }
}
