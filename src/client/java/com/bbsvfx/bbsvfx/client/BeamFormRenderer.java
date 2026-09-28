package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.colors.Color;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.bbsvfx.bbsvfx.forms.BeamForm;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link BeamForm} as additive emissive geometry rising along the form-local +Y axis: a soft
 * layered core column, wrapping double-helix ribbons, stacked halo rings that rise and expand, rising
 * dash sparks, and a ground glow. Everything is a closed form of the beam's {@code simTime} (=
 * progress × duration), so scrubbing is exact and there is no per-frame simulation.
 *
 * <p>In the world the beam is <b>drawn deferred</b> at {@link WorldRenderEvents#LAST} — after clouds —
 * so clouds and geometry sort against it correctly instead of always drawing over it (BBS renders forms
 * before clouds). Depth-<i>tested</i> (world geometry still occludes it) but never depth-<i>written</i>
 * (overlapping additive layers can't z-fight, and the soft glow doesn't cut holes in the sky). In the
 * editor PREVIEW there are no clouds, so it draws inline. No custom core shader in the world pass →
 * Iris-safe, packs bloom the bright bands themselves.</p>
 */
public class BeamFormRenderer extends FormRenderer<BeamForm>
{
    /** Trivial no-physics bakes for the devour block-cube VAO, cached per block-list content stamp
     *  (the VAO is cached by bake identity — rebuilding a fresh bake each frame would churn the VBO). */
    private static final Map<Long, DestructionPhysics.Bake> DEVOUR_BAKES = new HashMap<>();

    /** Procedural flowing-energy texture for the core (vertical filaments + tileable turbulence). */
    private static final Identifier CORE_TEX = Identifier.of("bbsvfx", "beam/core");
    /** Flat white texture for the untextured (glow/ring/strand/dash/star) geometry on the emissive layer. */
    private static final Identifier WHITE_TEX = Identifier.of("bbsvfx", "beam/white");
    private static boolean coreTexReady, whiteTexReady;

    /** Fullbright lightmap so the pack lights the beam as an emissive (block=sky=240). */
    private static final int FULL = 0xF000F0;

    public BeamFormRenderer(BeamForm form)
    {
        super(form);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        /* Cheap picker thumbnail: a bright vertical bar in the beam's colours. */
        Color c = this.form.color.get();
        Color rim = this.form.rimColor.get();
        int cx = (x1 + x2) / 2;

        context.batcher.box(cx - 6, y1, cx + 6, y2, rim.getARGBColor() & 0x40FFFFFF);
        context.batcher.box(cx - 2, y1, cx + 2, y2, c.getARGBColor());
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        if (BBSRendering.isIrisShadowPass() || context.stencilMap != null)
        {
            return;
        }

        float st = this.form.simTime();

        /* Terrain devour: the carved blocks render in the form pass (solid, depth-correct) — at rest
         * they FILL the crater (it reads intact) until the beam's tip reaches the ground, then they
         * rise, spiral in to the axis and are consumed by the beam. Drawn regardless of beam opacity. */
        if (this.form.destruction.get() && !this.form.blocks.getList().isEmpty())
        {
            this.renderDevour(context, st);
        }

        float opacity = this.form.opacity.get();

        if (opacity <= 0.001F)
        {
            return;
        }

        float duration = this.form.duration.get();
        float radius = this.form.radius.get();
        float height = this.form.height.get();

        Matrix4f mat = context.stack.peek().getPositionMatrix();

        /* Direction: erupt UP from the ground, or strike DOWN from the sky. The DOWN beam is the same
         * geometry flipped about the top (translate up by height, scale Y by -1): the local lance TIP
         * lands at the descending bottom edge, so it reads as a beam stabbing down; its bright top stays
         * pinned in the sky. The column grows to full length as the tip reaches the ground at impactAt. */
        float spanLen;
        Matrix4f drawMat;
        boolean groundOn;

        if (this.form.direction.get() == 1)
        {
            float impactAt = Math.max(0.001F, this.form.impactAt.get());
            float descend = smooth(Math.min(1F, st / Math.max(0.001F, impactAt * duration)));

            spanLen = height * descend;
            drawMat = new Matrix4f(mat).translate(0F, height, 0F).scale(1F, -1F, 1F);
            groundOn = descend >= 0.999F;
        }
        else
        {
            float build = smooth(Math.min(1F, st / Math.max(0.001F, duration * 0.12F)));

            spanLen = height * build;
            drawMat = mat;
            groundOn = true;
        }

        if (spanLen <= 0.05F || radius <= 0F)
        {
            return;
        }

        Vector3f camBeam = new Matrix4f(drawMat).invert().transformPosition(new Vector3f(0F, 0F, 0F));

        /* Draw the beam EMISSIVE in the form pass through BBS's provider: Iris swaps the entity-
         * translucent-emissive shader for the pack's gbuffers_entities, so the pack lights + BLOOMS the
         * beam natively (consistent across shaderpacks, not washed-out like additive drawn after the
         * pack's bloom). Vanilla keeps it bright. Untextured geometry on a white texture, the flowing
         * core on its own texture. Emit triangles → accumulated into the layer's quads. */
        ensureWhiteTex();
        accN = 0;

        boolean preview = context.type == mchorse.bbs_mod.forms.renderers.FormRenderType.PREVIEW;

        /* Depth prepass first so clouds/geometry sort behind the beam (it renders translucent, no depth). */
        drawCoreDepthPrepass(drawMat, camBeam, spanLen, radius);

        mchorse.bbs_mod.forms.CustomVertexConsumerProvider provider = mchorse.bbs_mod.forms.FormUtilsClient.getProvider();
        VertexConsumer wvc = provider.getBuffer(RenderLayer.getEntityTranslucentEmissive(WHITE_TEX));

        /* The core column glow + helix are the self-overlapping translucent part → drawn as a depth-correct
         * RAYMARCH (BeamVolume) in the world; the preview (no post pass) keeps the geometry. Rings, dashes,
         * strand ribbons, star flare and ground stay geometry either way. */
        buildTriangles(wvc, drawMat, camBeam, this.form, spanLen, radius, st, opacity, preview);

        if (groundOn)
        {
            drawGround(wvc, mat, this.form, radius, opacity);
        }

        drawImpactFlash(wvc, mat, this.form, st);

        if (preview)
        {
            /* The core is a SMOOTH gradient (white tex) — the noise DISPLACES the core geometry. */
            drawTexturedCore(wvc, drawMat, camBeam, this.form, spanLen, radius, st, opacity);
        }

        provider.draw();

        if (!preview)
        {
            Color bc = this.form.color.get();
            Color br = this.form.rimColor.get();

            BeamVolume.queue(new Matrix4f(drawMat), spanLen, radius, this.form.softness.get(), opacity, 1.6F,
                this.form.helix.get(), this.form.helixCount.get(), this.form.helixRadius.get(),
                this.form.helixTurns.get(), this.form.helixThickness.get(), this.form.helixSpin.get(),
                st, bc.r, bc.g, bc.b, br.r, br.g, br.b);
        }

        /* Heat-haze refraction around the column — queued for WorldRenderEvents.LAST (it refracts the
         * FINISHED frame, incl. the beam's own edges); the editor PREVIEW has no world pass, skip. */
        if (context.type != mchorse.bbs_mod.forms.renderers.FormRenderType.PREVIEW)
        {
            BeamHeat.queue(new Matrix4f(drawMat), spanLen, radius, st, opacity);
        }
    }

    /* ---- terrain devour (carved blocks rising / sucked into the beam) ---- */

    private void renderDevour(FormRenderingContext context, float st)
    {
        List<DestructionBlock> blocks = this.form.blocks.getAllTyped();
        int n = blocks.size();

        if (n == 0)
        {
            return;
        }

        DestructionPhysics.Bake bake = bakeFor(devourStamp(blocks), blocks);
        DestructionPhysVAO vao = DestructionPhysVAO.of(bake, blocks);

        if (vao == null)
        {
            return;
        }

        float[] pose = new float[bake.units * 7];
        float[] scale = new float[bake.units];

        this.computeDevourPoses(pose, scale, bake, blocks, st);
        vao.render(pose, scale, bake.units, context.stack, 1F, 1F, 1F, 1F, context.light, OverlayTexture.DEFAULT_UV);
    }

    /**
     * Per-UNIT pose (centre xyz + quat). A unit is a whole block or a sub-cube octant. At REST the
     * octants sit at their quarter positions (filling the crater intact). When the expanding
     * consume-radius reaches a unit, it BREAKS APART — the octants fly out from the block centre, rise,
     * spiral in to the axis and tumble, each culled at its own staggered time so the block dissolves
     * piece by piece (расщепляет) instead of vanishing whole. The beam's brightness swallows them near
     * the axis.
     */
    private void computeDevourPoses(float[] pose, float[] scale, DestructionPhysics.Bake bake, List<DestructionBlock> blocks, float st)
    {
        float duration = this.form.duration.get();
        float impactTime = this.form.impactAt.get() * duration;
        /* Quick outward blast sweep from the epicentre (an explosion, not a slow crawl). */
        float sweep = 0.4F;
        float radius = Math.max(0.5F, this.form.destructRadius.get());
        float height = this.form.height.get();
        float upV = height * 0.55F;      // blast up-speed (blocks/s)
        float scatterV = 3.5F;           // horizontal blast scatter (blocks/s)
        float escapeFrac = 0.45F;        // this fraction fly up and away, never sucked in
        float shatterSpread = 1.6F;
        /* Sucked blocks DISSOLVE as they near the beam surface (this radius), so they're consumed on
         * approach instead of piling up on the axis into columns that hide the beam. */
        float consumeR = Math.max(1.6F, this.form.radius.get() * 1.6F);

        for (int u = 0; u < bake.units; u++)
        {
            int bi = bake.unitBlock[u];
            DestructionBlock b = blocks.get(bi);
            int oct = bake.unitOctant[u];
            float sx = oct < 0 ? 0F : ((oct & 1) == 0 ? -1F : 1F);
            float sy = oct < 0 ? 0F : ((oct & 2) == 0 ? -1F : 1F);
            float sz = oct < 0 ? 0F : ((oct & 4) == 0 ? -1F : 1F);
            float bcx0 = b.x.get() + 0.5F, bcy0 = b.y.get() + 0.5F, bcz0 = b.z.get() + 0.5F;
            float d = (float) Math.sqrt(bcx0 * bcx0 + bcz0 * bcz0);
            float tLift = impactTime + sweep * Math.min(1F, d / radius);
            float e = st - tLift;
            int o = u * 7;

            if (e <= 0F)
            {
                /* Still WHOLE and at rest — octants at their quarter positions form the intact block. */
                pose[o] = bcx0 + sx * 0.25F; pose[o + 1] = bcy0 + sy * 0.25F; pose[o + 2] = bcz0 + sz * 0.25F;
                pose[o + 3] = 0F; pose[o + 4] = 0F; pose[o + 5] = 0F; pose[o + 6] = 1F;
                scale[u] = 1F;

                continue;
            }

            /* Per-BLOCK randoms (shared by its octants) — the explosion throws each block up in a
             * random direction; then some are SUCKED into the beam, others keep flying up and away. */
            float rUp = 0.55F + 0.9F * hash01(bi, 31);
            float dirX = hash01(bi, 32) - 0.5F, dirZ = hash01(bi, 33) - 0.5F;
            boolean escape = hash01(bi, 30) < escapeFrac;
            float y = bcy0 + upV * rUp * e;
            float x, z, sc, shatter;

            if (escape)
            {
                /* Flies up and out; fades away fairly soon (never reaches the beam, no sky clutter). */
                x = bcx0 + dirX * scatterV * e;
                z = bcz0 + dirZ * scatterV * e;
                sc = 1F - smooth(clamp((e - 0.7F) / 0.8F));
                shatter = clamp((e - 0.4F) / 0.8F);
            }
            else
            {
                /* Blasts out briefly, then is drawn HORIZONTALLY to the beam axis — at whatever height
                 * it has risen to, so blocks stream into the column ALONG its length, not to one point.
                 * DISSOLVES as it nears the beam surface (consumeR), so it's eaten on approach instead
                 * of piling on the axis into a column that hides the beam. */
                float blastDur = 0.28F + 0.2F * hash01(bi, 34);
                float suck = smooth(clamp((e - blastDur) / 0.6F));
                float bx = bcx0 + dirX * scatterV * Math.min(e, blastDur);
                float bz = bcz0 + dirZ * scatterV * Math.min(e, blastDur);

                x = bx * (1F - suck);
                z = bz * (1F - suck);

                /* Swirl the approach into a VORTEX — rotate the pulled-in position around the axis as it
                 * closes, so blocks spiral into the beam like a whirlpool instead of flying straight in. */
                float swirl = suck * 3.5F;
                float cs = (float) Math.cos(swirl), sn = (float) Math.sin(swirl);
                float rx = x * cs - z * sn;

                z = x * sn + z * cs;
                x = rx;

                float dist = (float) Math.sqrt(x * x + z * z);

                sc = smooth(clamp((dist - consumeR) / 2.5F));
                shatter = suck;
            }

            /* Octants burst apart as the block breaks up (shatter grows). */
            float off = 0.25F + shatterSpread * shatter;

            pose[o] = x + sx * off;
            pose[o + 1] = y + sy * off;
            pose[o + 2] = z + sz * off;
            scale[u] = clamp(sc);

            /* Random tumble from the start (random rotation), spinning faster as it breaks up. */
            float ax = hash01(u, 11) - 0.5F, ay = hash01(u, 12) - 0.5F, az = hash01(u, 13) - 0.5F;
            float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);

            if (al < 1e-4F)
            {
                ax = 0F; ay = 1F; az = 0F; al = 1F;
            }

            ax /= al; ay /= al; az /= al;

            float ang = e * (1.5F + hash01(u, 14) * 4F) * (0.4F + shatter);
            float s = (float) Math.sin(ang * 0.5F), cw = (float) Math.cos(ang * 0.5F);

            pose[o + 3] = ax * s; pose[o + 4] = ay * s; pose[o + 5] = az * s; pose[o + 6] = cw;
        }
    }

    private static float clamp(float v)
    {
        return v < 0F ? 0F : (v > 1F ? 1F : v);
    }

    /** Build (or fetch) the devour bake: every FULL block shatters into 8 octant units; non-full blocks
     *  (slabs/stairs/…) stay one whole unit (8 half-models would overlap badly). Cached per content. */
    private static DestructionPhysics.Bake bakeFor(long stamp, List<DestructionBlock> blocks)
    {
        DestructionPhysics.Bake bake = DEVOUR_BAKES.get(stamp);

        if (bake != null && bake.count == blocks.size())
        {
            return bake;
        }

        if (DEVOUR_BAKES.size() > 32)
        {
            DEVOUR_BAKES.clear();
        }

        int n = blocks.size();
        /* MIX: only a fraction of blocks shatter into 8 octant shards; the rest fly / get sucked as
         * WHOLE chunks (one unit). Keeps the debris count down (8 octants each was far too much) and
         * reads better — big tumbling chunks among smaller shards. Clean full cubes slice into true
         * sub-cubes; grass-like blocks fall back to half-scale chunks per octant. */
        float shatterFrac = 0.4F;
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
        DEVOUR_BAKES.put(stamp, bake);

        return bake;
    }

    /** Content stamp for the block list (rebuild the bake/VAO only when the carved set changes). */
    private static long devourStamp(List<DestructionBlock> blocks)
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

    /* ---- geometry ---- */

    /** All the additive triangle geometry (everything except the line strands) into one buffer. */
    private static void buildTriangles(VertexConsumer b, Matrix4f mat, Vector3f cam, BeamForm form,
        float curH, float radius, float st, float opacity, boolean coreGeom)
    {
        Color core = form.color.get();
        Color rim = form.rimColor.get();
        float cr = core.r, cg = core.g, cb = core.b;
        float rr = rim.r, rg = rim.g, rb = rim.b;
        /* Push the core toward white so the very centre reads blinding (packs bloom it). */
        float wr = mix(cr, 1F, 0.45F), wg = mix(cg, 1F, 0.45F), wb = mix(cb, 1F, 0.45F);
        float soft = form.softness.get();

        /* Core column glow + helix — the SELF-OVERLAPPING translucent part. In the world these are drawn as
         * a depth-correct raymarch (BeamVolume); only the editor preview (no post pass) draws them here. */
        if (coreGeom)
        {
            /* FAKE BLOOM for the no-shader look: a very wide, very faint halo. */
            if (soft > 0.01F)
            {
                float haloW = radius * (3.5F + 4F * soft);

                beamLayer(b, mat, cam, curH, haloW, rr, rg, rb, 0.05F * soft * opacity, 10, 14, 0.6F);
            }

            beamLayer(b, mat, cam, curH, radius * 3.0F, rr, rg, rb, 0.16F * opacity, 8, 14, 1.1F);
            beamLayer(b, mat, cam, curH, radius * 1.7F, mix(cr, rr, 0.4F), mix(cg, rg, 0.4F), mix(cb, rb, 0.4F), 0.34F * opacity, 10, 16, 1.6F);

            if (form.helix.get())
            {
                helices(b, mat, cam, form, curH, radius, st, rr, rg, rb, 0.55F * opacity);
            }
        }

        haloRings(b, mat, cam, form, curH, radius, st, rr, rg, rb, 0.50F * opacity);
        dashes(b, mat, cam, form, curH, radius, st, wr, wg, wb, 0.70F * opacity);
        strandRibbons(b, mat, cam, form, curH, radius, st, wr, wg, wb, 0.6F * opacity);

        /* A SUBTLE glint at the sky end (user disliked a big star) — small soft 4-point sparkle. */
        float starY = form.direction.get() == 1 ? 0F : curH;

        drawStarFlare(b, mat, cam, starY, radius, st, wr, wg, wb, rr, rg, rb, 0.4F * opacity);
    }

    /**
     * A small, SOFT 4-point sparkle at the beam's sky end (kept subtle — the user disliked a prominent
     * star). Camera-facing, additive, slowly rotating.
     */
    private static void drawStarFlare(net.minecraft.client.render.VertexConsumer b, Matrix4f m, Vector3f cam, float py, float radius,
        float st, float wr, float wg, float wb, float rr, float rg, float rb, float peak)
    {
        Vector3f p = new Vector3f(0F, py, 0F);
        Vector3f view = new Vector3f(cam).sub(p);

        if (view.lengthSquared() < 1e-6F)
        {
            return;
        }

        view.normalize();

        Vector3f right = new Vector3f(0F, 1F, 0F).cross(view);

        if (right.lengthSquared() < 1e-6F)
        {
            right.set(1F, 0F, 0F);
        }

        right.normalize();

        Vector3f up = new Vector3f(view).cross(right).normalize();

        int spikes = 4;
        float rot = st * 0.4F;
        float baseW = radius * 0.18F;
        float cw = radius * 0.6F;

        /* Small central glow. */
        for (int k = 0; k < 4; k++)
        {
            float a0 = k * 1.5707964F + rot;
            float a1 = (k + 1) * 1.5707964F + rot;
            Vector3f d0 = dir(right, up, a0, cw);
            Vector3f d1 = dir(right, up, a1, cw);

            v(b, m, p.x, p.y, p.z, wr, wg, wb, peak);
            v(b, m, p.x + d0.x, p.y + d0.y, p.z + d0.z, rr, rg, rb, 0F);
            v(b, m, p.x + d1.x, p.y + d1.y, p.z + d1.z, rr, rg, rb, 0F);
        }

        /* Four short soft spikes. */
        for (int k = 0; k < spikes; k++)
        {
            float ang = k * 6.2831853F / spikes + rot;
            float len = radius * 2F;
            Vector3f d = dir(right, up, ang, len);
            Vector3f perp = dir(right, up, ang + 1.5707964F, baseW);

            v(b, m, p.x + perp.x, p.y + perp.y, p.z + perp.z, wr, wg, wb, peak * 0.5F);
            v(b, m, p.x + d.x, p.y + d.y, p.z + d.z, wr, wg, wb, 0F);
            v(b, m, p.x - perp.x, p.y - perp.y, p.z - perp.z, wr, wg, wb, peak * 0.5F);
        }
    }

    private static Vector3f dir(Vector3f right, Vector3f up, float ang, float len)
    {
        float c = (float) Math.cos(ang), s = (float) Math.sin(ang);

        return new Vector3f(right).mul(c * len).add(new Vector3f(up).mul(s * len));
    }

    /**
     * The ground glow disc — drawn on its own with a polygon offset so it wins the depth fight against
     * the terrain it lies on (otherwise it z-fights / vanishes in the world, though it shows fine in the
     * editor preview where there is no terrain). Still depth-tested, so a wall in front occludes it.
     */
    private static void drawGround(VertexConsumer b, Matrix4f mat, BeamForm form, float radius, float opacity)
    {
        float gr = form.groundRadius.get();

        if (gr <= 0F)
        {
            return;
        }

        Color rim = form.rimColor.get();
        int seg = 44;
        float y = 0.05F;
        float peak = 0.6F * opacity;

        for (int j = 0; j < seg; j++)
        {
            float a0 = 6.2831853F * j / seg, a1 = 6.2831853F * (j + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            v(b, mat, 0F, y, 0F, rim.r, rim.g, rim.b, peak);
            v(b, mat, c0 * gr, y, s0 * gr, rim.r, rim.g, rim.b, 0F);
            v(b, mat, c1 * gr, y, s1 * gr, rim.r, rim.g, rim.b, 0F);
        }
    }

    /**
     * The impact BLAST: a bright central flash + an expanding shockwave ring on the ground, fired the
     * moment the beam's tip lands ({@code impactAt}) and gone in ~0.5 s. Flat on the ground (un-flipped
     * matrix), additive, no depth test. Only when the beam is devouring.
     */
    private static void drawImpactFlash(VertexConsumer b, Matrix4f m, BeamForm form, float st)
    {
        if (!form.destruction.get())
        {
            return;
        }

        float t = st - form.impactAt.get() * form.duration.get();
        float dur = 0.5F;

        if (t < 0F || t > dur)
        {
            return;
        }

        float f = t / dur;
        float destructR = Math.max(1F, form.destructRadius.get());
        float op = form.opacity.get();
        Color core = form.color.get(), rim = form.rimColor.get();
        float wr = mix(core.r, 1F, 0.6F), wg = mix(core.g, 1F, 0.6F), wb = mix(core.b, 1F, 0.6F);
        float y = 0.06F;
        int seg = 48;

        /* Central flash — a bright pop fading fast. */
        float discR = destructR * (0.4F + 0.6F * f);
        float discA = (1F - f) * (1F - f) * 0.8F * op;

        for (int j = 0; j < seg; j++)
        {
            float a0 = 6.2831853F * j / seg, a1 = 6.2831853F * (j + 1) / seg;

            v(b, m, 0F, y, 0F, wr, wg, wb, discA);
            v(b, m, (float) Math.cos(a0) * discR, y, (float) Math.sin(a0) * discR, rim.r, rim.g, rim.b, 0F);
            v(b, m, (float) Math.cos(a1) * discR, y, (float) Math.sin(a1) * discR, rim.r, rim.g, rim.b, 0F);
        }

        /* Expanding shockwave ring. */
        float ringR = destructR * 1.7F * f;
        float ringW = destructR * 0.18F;
        float ringA = (1F - f) * 0.85F * op;
        float rin = Math.max(0F, ringR - ringW), rout = ringR + ringW;

        for (int j = 0; j < seg; j++)
        {
            float a0 = 6.2831853F * j / seg, a1 = 6.2831853F * (j + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            v(b, m, c0 * rin, y, s0 * rin, wr, wg, wb, 0F);
            v(b, m, c0 * ringR, y, s0 * ringR, wr, wg, wb, ringA);
            v(b, m, c1 * ringR, y, s1 * ringR, wr, wg, wb, ringA);
            v(b, m, c0 * rin, y, s0 * rin, wr, wg, wb, 0F);
            v(b, m, c1 * ringR, y, s1 * ringR, wr, wg, wb, ringA);
            v(b, m, c1 * rin, y, s1 * rin, wr, wg, wb, 0F);

            v(b, m, c0 * ringR, y, s0 * ringR, wr, wg, wb, ringA);
            v(b, m, c0 * rout, y, s0 * rout, rim.r, rim.g, rim.b, 0F);
            v(b, m, c1 * rout, y, s1 * rout, rim.r, rim.g, rim.b, 0F);
            v(b, m, c0 * ringR, y, s0 * ringR, wr, wg, wb, ringA);
            v(b, m, c1 * rout, y, s1 * rout, rim.r, rim.g, rim.b, 0F);
            v(b, m, c1 * ringR, y, s1 * ringR, wr, wg, wb, ringA);
        }
    }

    /**
     * The bright CORE as a TEXTURED, SCROLLING billboard — a procedural noise texture (vertical energy
     * filaments + turbulence) panned along the column over time, so the energy visibly STREAMS and
     * churns instead of reading as a smooth glowstick (the #1 "living energy" fix). Camera-facing,
     * additive; the horizontal cosine profile + length fade come from the vertex colour. Own draw call
     * (POSITION_COLOR_TEXTURE) inside the additive pass.
     */
    /**
     * Invisible DEPTH prepass of the solid core column (cutout program, colour writes off) so clouds and
     * geometry sort BEHIND the beam. The beam itself renders translucent-emissive (no depth write), so
     * without this both vanilla and pack clouds — composited after the form pass — drew IN FRONT of it.
     */
    private static void drawCoreDepthPrepass(Matrix4f m, Vector3f cam, float curH, float radius)
    {
        ensureWhiteTex();

        RenderLayer layer = RenderLayer.getEntityCutoutNoCull(WHITE_TEX);

        layer.startDrawing();
        RenderSystem.colorMask(false, false, false, false);
        /* Push the depth wall AWAY so the (coplanar) translucent core sits clearly in front of it and
         * doesn't z-fight into a dithered diagonal hatch; clouds are far behind either way. */
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(1F, 4F);

        BufferBuilder buf = BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);

        int hSegs = Math.max(6, Math.min(48, (int) (curH * 1.5F)));
        float hw = radius * 0.65F;

        for (int h = 0; h < hSegs; h++)
        {
            float fy0 = h / (float) hSegs, fy1 = (h + 1) / (float) hSegs;
            float y0 = fy0 * curH, y1 = fy1 * curH;
            float w0 = hw * widthTaper(fy0), w1 = hw * widthTaper(fy1);
            Vector3f d0 = cylRight(cam, 0F, y0, 0F), d1 = cylRight(cam, 0F, y1, 0F);

            dv(buf, m, d0.x * -w0, y0, d0.z * -w0);
            dv(buf, m, d1.x * -w1, y1, d1.z * -w1);
            dv(buf, m, d1.x * w1, y1, d1.z * w1);
            dv(buf, m, d0.x * w0, y0, d0.z * w0);
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.polygonOffset(0F, 0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.colorMask(true, true, true, true);
        layer.endDrawing();
    }

    private static void dv(BufferBuilder b, Matrix4f m, float x, float y, float z)
    {
        BbsVfxRenderCompat.next(b.vertex(m, x, y, z).color(1F, 1F, 1F, 1F).texture(0.5F, 0.5F)
            .overlay(OverlayTexture.DEFAULT_UV).light(FULL).normal(0F, 1F, 0F));
    }

    private static void drawTexturedCore(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, Vector3f cam,
        BeamForm form, float curH, float radius, float st, float opacity)
    {
        Color core = form.color.get();
        float r = mix(core.r, 1F, 0.72F), g = mix(core.g, 1F, 0.72F), bl = mix(core.b, 1F, 0.72F);
        float peak = 0.95F * opacity;
        int strips = 12;
        int hSegs = Math.max(10, Math.min(60, (int) (curH * 2F)));
        /* Wider + softer falloff so the bright core MELTS into the glow instead of showing a clean-edged
         * cone silhouette (the alpha-blended emissive core read as too sharp-edged vs the old additive). */
        float hw = radius * 1.5F;

        for (int h = 0; h < hSegs; h++)
        {
            float fy0 = h / (float) hSegs, fy1 = (h + 1) / (float) hSegs;
            float y0 = fy0 * curH, y1 = fy1 * curH;
            /* DISPLACEMENT MAP behaviour: the noise itself is NEVER rendered — the core is a smooth
             * gradient — the noise only pushes the left/right edges in/out independently, so the core's
             * BODY visibly boils/writhes while its surface stays clean. */
            float base0 = hw * widthTaper(fy0), base1 = hw * widthTaper(fy1);
            float lE0 = -base0 * edgeFac(y0, st, 0F), rE0 = base0 * edgeFac(y0, st, 100F);
            float lE1 = -base1 * edgeFac(y1, st, 0F), rE1 = base1 * edgeFac(y1, st, 100F);
            float l0 = peak * lenFade(fy0), l1 = peak * lenFade(fy1);
            Vector3f d0 = cylRight(cam, 0F, y0, 0F);
            Vector3f d1 = cylRight(cam, 0F, y1, 0F);

            for (int s = 0; s < strips; s++)
            {
                float u0 = -1F + 2F * s / strips, u1 = -1F + 2F * (s + 1) / strips;
                float p0 = profile(u0, 0.8F), p1 = profile(u1, 0.8F);
                /* map u in [-1,1] onto the displaced left/right edges — internal strips ride the warp too */
                float o0b = edgeMix(lE0, rE0, u0), o1b = edgeMix(lE0, rE0, u1);
                float o0t = edgeMix(lE1, rE1, u0), o1t = edgeMix(lE1, rE1, u1);
                float blx = d0.x * o0b, blz = d0.z * o0b;
                float brx = d0.x * o1b, brz = d0.z * o1b;
                float tlx = d1.x * o0t, tlz = d1.z * o0t;
                float trx = d1.x * o1t, trz = d1.z * o1t;

                v(vc, m, blx, y0, blz, r, g, bl, l0 * p0);
                v(vc, m, tlx, y1, tlz, r, g, bl, l1 * p0);
                v(vc, m, trx, y1, trz, r, g, bl, l1 * p1);

                v(vc, m, blx, y0, blz, r, g, bl, l0 * p0);
                v(vc, m, trx, y1, trz, r, g, bl, l1 * p1);
                v(vc, m, brx, y0, brz, r, g, bl, l0 * p1);
            }
        }
    }

    /** Per-edge width multiplier — organic value-noise fbm flowing along the beam (never rendered,
     *  only displaces the geometry: 0.55..1.45). */
    private static float edgeFac(float y, float st, float seed)
    {
        float x = y * 0.9F - st * 2.4F + seed;
        float n = vnoise(x) * 0.55F + vnoise(x * 2.3F + 17.7F) * 0.30F + vnoise(x * 5.1F + 41.3F) * 0.15F;

        return 0.55F + 0.9F * n;
    }

    /** 1D value noise (smooth hash interpolation), 0..1. */
    private static float vnoise(float x)
    {
        int i = (int) Math.floor(x);
        float f = x - i;
        float t = f * f * (3F - 2F * f);

        return hash(i) + (hash(i + 1) - hash(i)) * t;
    }

    private static float hash(int i)
    {
        i = i * 374761393 + 668265263;
        i = (i ^ (i >>> 13)) * 1274126177;

        return ((i ^ (i >>> 16)) & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    /** Linear map of u in [-1,1] between the (already displaced) left and right edge offsets. */
    private static float edgeMix(float left, float right, float u)
    {
        return left + (right - left) * (u + 1F) * 0.5F;
    }

    /** Build (once) the flowing-core texture: vertical bright filaments modulated by tileable turbulence. */
    private static void ensureCoreTex()
    {
        if (coreTexReady)
        {
            return;
        }

        int w = 64, h = 128;
        NativeImage img = new NativeImage(w, h, false);
        float[][] streaks = {{0.5F, 0.10F, 1.0F}, {0.32F, 0.06F, 0.7F}, {0.68F, 0.06F, 0.7F},
            {0.18F, 0.05F, 0.5F}, {0.82F, 0.05F, 0.5F}, {0.42F, 0.03F, 0.4F}, {0.6F, 0.03F, 0.4F}};

        for (int y = 0; y < h; y++)
        {
            float fy = y / (float) h;

            for (int x = 0; x < w; x++)
            {
                float u = x / (float) w;
                float strand = 0F;

                for (float[] sk : streaks)
                {
                    float dd = (u - sk[0]) / sk[1];

                    strand += sk[2] * (float) Math.exp(-0.5F * dd * dd);
                }

                /* Tileable vertical turbulence (integer y-cycles wrap seamlessly). */
                float n = 0.5F
                    + 0.22F * (float) Math.sin(6.2831853F * fy * 3F + u * 11F)
                    + 0.16F * (float) Math.sin(6.2831853F * fy * 7F - u * 6F)
                    + 0.12F * (float) Math.sin(6.2831853F * fy * 13F + u * 3F);
                /* Brightness FLOOR (0.42) so the core never goes to a black column on packs that don't
                 * fullbright the emissive texture, with contrasty bright filaments up to 1.0 on top. */
                float b = 0.42F + 0.58F * Math.max(0F, Math.min(1F, strand * (0.4F + 0.8F * n)));
                int val = (int) (b * 255F);

                img.setColor(x, y, 0xFF000000 | val << 16 | val << 8 | val);
            }
        }

        NativeImageBackedTexture tex = new NativeImageBackedTexture(img);

        tex.setFilter(true, false);
        MinecraftClient.getInstance().getTextureManager().registerTexture(CORE_TEX, tex);
        coreTexReady = true;
    }

    private static void ensureWhiteTex()
    {
        if (whiteTexReady)
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
        whiteTexReady = true;
    }

    /* ---- emissive emit (entity-translucent-emissive layer, QUADS) — packs bloom it ---- */

    /** One emissive vertex (entity format: colour, uv, overlay, fullbright light, up normal). */
    private static void ev(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, float x, float y, float z,
        float r, float g, float bl, float a, float u, float v)
    {
        vc.vertex(m, x, y, z).color(r, g, bl, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(FULL).normal(0F, 1F, 0F);
        BbsVfxRenderCompat.next(vc);
    }

    /**
     * One tessellated, camera-facing column layer from base to {@code curH}. Grid of hSegs (height) ×
     * strips (width); a cosine profile across the width gives soft round edges, a width taper + length
     * fade near the top makes a pointed, dissolving lance tip.
     */
    private static void beamLayer(net.minecraft.client.render.VertexConsumer b, Matrix4f m, Vector3f cam, float curH, float hw,
        float r, float g, float bl, float peak, int strips, int hSegs, float profilePow)
    {
        for (int h = 0; h < hSegs; h++)
        {
            float fy0 = h / (float) hSegs;
            float fy1 = (h + 1) / (float) hSegs;
            float y0 = fy0 * curH, y1 = fy1 * curH;
            float w0 = hw * widthTaper(fy0), w1 = hw * widthTaper(fy1);
            float l0 = peak * lenFade(fy0), l1 = peak * lenFade(fy1);
            Vector3f d0 = cylRight(cam, 0F, y0, 0F);
            Vector3f d1 = cylRight(cam, 0F, y1, 0F);

            for (int s = 0; s < strips; s++)
            {
                float u0 = -1F + 2F * s / strips;
                float u1 = -1F + 2F * (s + 1) / strips;
                float p0 = profile(u0, profilePow);
                float p1 = profile(u1, profilePow);

                float blx = d0.x * w0 * u0, blz = d0.z * w0 * u0;
                float brx = d0.x * w0 * u1, brz = d0.z * w0 * u1;
                float tlx = d1.x * w1 * u0, tlz = d1.z * w1 * u0;
                float trx = d1.x * w1 * u1, trz = d1.z * w1 * u1;

                v(b, m, blx, y0, blz, r, g, bl, l0 * p0);
                v(b, m, tlx, y1, tlz, r, g, bl, l1 * p0);
                v(b, m, trx, y1, trz, r, g, bl, l1 * p1);

                v(b, m, blx, y0, blz, r, g, bl, l0 * p0);
                v(b, m, trx, y1, trz, r, g, bl, l1 * p1);
                v(b, m, brx, y0, brz, r, g, bl, l0 * p1);
            }
        }
    }

    /* ---- Double-helix ribbons (glowing tubes) ---- */

    /**
     * The wrapping strands, drawn as thin PARALLEL-TRANSPORT TUBES rather than camera-facing ribbons —
     * a billboard ribbon collapses to zero width (and reverses) twice per turn where the helix tangent
     * points at the camera, which read as the "zig-zag / diamonds". A solid 6-sided tube never pinches
     * or flips, so the spiral stays smooth from every angle. Additive, so packs bloom it into a glow.
     */
    private static void helices(net.minecraft.client.render.VertexConsumer b, Matrix4f m, Vector3f cam, BeamForm form, float curH,
        float radius, float st, float r, float g, float bl, float peak)
    {
        int count = form.helixCount.get();

        if (count <= 0)
        {
            return;
        }

        float rH = radius * form.helixRadius.get();
        float turns = form.helixTurns.get();
        float baseR = form.helixThickness.get() * 0.5F;
        float soft = form.softness.get();
        float spin = st * form.helixSpin.get() * 6.2831853F;
        int seg = Math.max(24, Math.min(300, (int) (turns * 20F) + 16));
        int sides = 6;

        /* Geometric edge blur: a bright core tube + an expanded faint shell. The additive pass has no
         * AA, so a lone hard-edged tube reads "sharp" in the world versus the editor's filtered preview. */
        float[] shellMul = soft > 0.01F ? new float[] {1F, 1F + 1.6F * soft} : new float[] {1F};
        float[] shellAlpha = soft > 0.01F ? new float[] {0.55F, 0.25F} : new float[] {0.7F};

        for (int k = 0; k < count; k++)
        {
            float phase = k * 6.2831853F / count;
            Vector3f[] p = new Vector3f[seg + 1];

            for (int i = 0; i <= seg; i++)
            {
                float fy = i / (float) seg;
                float y = fy * curH;
                float ang = fy * turns * 6.2831853F + phase + spin;

                p[i] = new Vector3f((float) Math.cos(ang) * rH, y, (float) Math.sin(ang) * rH);
            }

            /* Per-point tangents. */
            Vector3f[] tan = new Vector3f[seg + 1];

            for (int i = 0; i <= seg; i++)
            {
                Vector3f t;

                if (i == 0)
                {
                    t = new Vector3f(p[1]).sub(p[0]);
                }
                else if (i == seg)
                {
                    t = new Vector3f(p[seg]).sub(p[seg - 1]);
                }
                else
                {
                    t = new Vector3f(p[i + 1]).sub(p[i - 1]);
                }

                if (t.lengthSquared() < 1e-9F)
                {
                    t.set(0F, 1F, 0F);
                }
                else
                {
                    t.normalize();
                }

                tan[i] = t;
            }

            /* Parallel-transport a normal so the tube doesn't twist. */
            Vector3f[] nrm = new Vector3f[seg + 1];
            Vector3f seed = new Vector3f(tan[0]).cross(0F, 1F, 0F);

            if (seed.lengthSquared() < 1e-6F)
            {
                seed = new Vector3f(tan[0]).cross(1F, 0F, 0F);
            }

            nrm[0] = seed.normalize();

            for (int i = 1; i <= seg; i++)
            {
                Vector3f axis = new Vector3f(tan[i - 1]).cross(tan[i]);
                Vector3f nx;

                if (axis.lengthSquared() < 1e-9F)
                {
                    nx = new Vector3f(nrm[i - 1]);
                }
                else
                {
                    axis.normalize();

                    float dot = Math.max(-1F, Math.min(1F, tan[i - 1].dot(tan[i])));
                    float angle = (float) Math.acos(dot);

                    nx = new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, angle).transform(new Vector3f(nrm[i - 1]));
                }

                nx.sub(new Vector3f(tan[i]).mul(nx.dot(tan[i])));

                if (nx.lengthSquared() < 1e-9F)
                {
                    nx = new Vector3f(nrm[i - 1]);
                }

                nrm[i] = nx.normalize();
            }

            for (int shell = 0; shell < shellMul.length; shell++)
            {
                float tubeR = baseR * shellMul[shell];
                float aMul = shellAlpha[shell];
                Vector3f[] prevRing = tubeRing(p[0], tan[0], nrm[0], tubeR, sides);

                for (int i = 1; i <= seg; i++)
                {
                    Vector3f[] cur = tubeRing(p[i], tan[i], nrm[i], tubeR, sides);
                    float aP = peak * aMul * lenFade((i - 1) / (float) seg);
                    float aC = peak * aMul * lenFade(i / (float) seg);

                    for (int s = 0; s < sides; s++)
                    {
                        int s2 = (s + 1) % sides;
                        Vector3f a0 = prevRing[s], a1 = prevRing[s2], b0 = cur[s], b1 = cur[s2];

                        v(b, m, a0.x, a0.y, a0.z, r, g, bl, aP);
                        v(b, m, a1.x, a1.y, a1.z, r, g, bl, aP);
                        v(b, m, b1.x, b1.y, b1.z, r, g, bl, aC);

                        v(b, m, a0.x, a0.y, a0.z, r, g, bl, aP);
                        v(b, m, b1.x, b1.y, b1.z, r, g, bl, aC);
                        v(b, m, b0.x, b0.y, b0.z, r, g, bl, aC);
                    }

                    prevRing = cur;
                }
            }
        }
    }

    /** One ring of {@code sides} points around a tube centre, in the (normal, binormal) plane. */
    private static Vector3f[] tubeRing(Vector3f center, Vector3f tan, Vector3f nrm, float rad, int sides)
    {
        Vector3f binormal = new Vector3f(tan).cross(nrm).normalize();
        Vector3f[] out = new Vector3f[sides];

        for (int s = 0; s < sides; s++)
        {
            float a = 6.2831853F * s / sides;
            float c = (float) Math.cos(a), si = (float) Math.sin(a);

            out[s] = new Vector3f(center)
                .add(new Vector3f(nrm).mul(c * rad))
                .add(new Vector3f(binormal).mul(si * rad));
        }

        return out;
    }

    /* ---- Halo rings ---- */

    private static void haloRings(net.minecraft.client.render.VertexConsumer b, Matrix4f m, Vector3f cam, BeamForm form, float curH,
        float radius, float st, float r, float g, float bl, float peak)
    {
        int count = form.rings.get();

        if (count <= 0)
        {
            return;
        }

        /* Big, TILTED, portal-like rings (anime ref): much wider than the beam, stacked and CROSSING
         * (alternating tilt) as they rise and expand — the signature halo. */
        float rMax = radius * form.ringMax.get() * 3F;
        float rMin = radius * 1.2F;
        float rise = Math.max(0.01F, form.ringRise.get());
        float soft = form.softness.get();
        float halfW = form.ringThickness.get() * (1.6F + 1.0F * soft);
        float period = curH / rise;

        for (int i = 0; i < count; i++)
        {
            float ph = st / period + i / (float) count;
            float f = ph - (float) Math.floor(ph);
            float y = f * curH;
            float rad = rMin + (rMax - rMin) * f;
            float a = peak * Math.min(1F, f / 0.1F) * Math.max(0F, 1F - Math.max(0F, f - 0.7F) / 0.3F);
            float tilt = (i % 2 == 0 ? 0.32F : -0.32F);

            if (a > 0.001F)
            {
                ringBand(b, m, cam, y, rad, halfW, tilt, r, g, bl, a / (0.9F + 0.6F * soft));
            }
        }
    }

    /**
     * A halo ring as a CAMERA-FACING soft band around the circle (bright centre line, alpha 0 at both
     * edges). A flat XZ annulus collapses to a jagged 1-px sawtooth when seen edge-on; this band always
     * shows its width to the viewer, so the ring reads as a soft glowing loop from every angle. The
     * width direction is carried with sign continuity so the band doesn't twist where the circle's
     * tangent passes through the view direction.
     */
    private static void ringBand(net.minecraft.client.render.VertexConsumer b, Matrix4f m, Vector3f cam, float y, float rad,
        float halfW, float tilt, float r, float g, float bl, float a)
    {
        int seg = 72;
        float sT = (float) Math.sin(tilt), cT = (float) Math.cos(tilt);
        Vector3f prevP = null, prevW = null;

        for (int i = 0; i <= seg; i++)
        {
            float ang = 6.2831853F * i / seg;
            float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
            float lx = c * rad, lz = s * rad;
            /* Tilt the ring plane about the X axis → a portal-like tilted ellipse. */
            Vector3f p = new Vector3f(lx, y + lz * sT, lz * cT);
            /* Width ⊥ (tilted-circle tangent, view direction). */
            Vector3f t = new Vector3f(-s * rad, c * rad * sT, c * rad * cT);
            Vector3f view = new Vector3f(cam).sub(p);
            Vector3f w = t.cross(view);

            if (w.lengthSquared() < 1e-9F)
            {
                w.set(0F, 1F, 0F);
            }

            w.normalize().mul(halfW);

            if (prevW != null && w.dot(prevW) < 0F)
            {
                w.negate();
            }

            if (prevP != null)
            {
                /* Two strips: edge (α0) → centre (α) → edge (α0). */
                v(b, m, prevP.x - prevW.x, prevP.y - prevW.y, prevP.z - prevW.z, r, g, bl, 0F);
                v(b, m, prevP.x, prevP.y, prevP.z, r, g, bl, a);
                v(b, m, p.x, p.y, p.z, r, g, bl, a);
                v(b, m, prevP.x - prevW.x, prevP.y - prevW.y, prevP.z - prevW.z, r, g, bl, 0F);
                v(b, m, p.x, p.y, p.z, r, g, bl, a);
                v(b, m, p.x - w.x, p.y - w.y, p.z - w.z, r, g, bl, 0F);

                v(b, m, prevP.x, prevP.y, prevP.z, r, g, bl, a);
                v(b, m, prevP.x + prevW.x, prevP.y + prevW.y, prevP.z + prevW.z, r, g, bl, 0F);
                v(b, m, p.x + w.x, p.y + w.y, p.z + w.z, r, g, bl, 0F);
                v(b, m, prevP.x, prevP.y, prevP.z, r, g, bl, a);
                v(b, m, p.x + w.x, p.y + w.y, p.z + w.z, r, g, bl, 0F);
                v(b, m, p.x, p.y, p.z, r, g, bl, a);
            }

            prevP = p;
            prevW = w;
        }
    }

    /* ---- Rising dash sparks ---- */

    private static void dashes(net.minecraft.client.render.VertexConsumer b, Matrix4f m, Vector3f cam, BeamForm form, float curH,
        float radius, float st, float r, float g, float bl, float peak)
    {
        int count = form.dashes.get();

        if (count <= 0)
        {
            return;
        }

        float speed = form.dashSpeed.get();
        float soft = form.softness.get();
        /* Dense FINE glitter (anime light-dust) — twice the count, much smaller sparks. */
        int total = count * 2;

        for (int i = 0; i < total; i++)
        {
            float h1 = hash01(i, 1), h2 = hash01(i, 2), h3 = hash01(i, 3), h4 = hash01(i, 4);
            float ang = h1 * 6.2831853F;
            float rr = radius * (0.15F + 1.1F * h2);
            float ph = st * speed * (0.7F + 0.6F * h4) + h3;
            float f = ph - (float) Math.floor(ph);
            float y = f * curH;
            float px = (float) Math.cos(ang) * rr, pz = (float) Math.sin(ang) * rr;
            float hw = radius * 0.05F * (1F + 0.3F * soft);
            float hh = radius * 0.11F * (0.6F + h4) * (1F + 0.2F * soft);
            float a = peak * Math.min(1F, f / 0.06F) * Math.max(0F, 1F - f);

            if (a <= 0.001F)
            {
                continue;
            }

            Vector3f right = cylRight(cam, px, y, pz);
            float rx = right.x * hw, rz = right.z * hw;

            /* Vertically-elongated soft diamond (bright centre, transparent tips) — a rising spark
             * dash. Four triangles fanned from the centre so the edges fade cleanly (no hard quad). */
            float ty0 = y - hh, ty1 = y + hh;
            float lx = px - rx, lz = pz - rz, rx2 = px + rx, rz2 = pz + rz;

            v(b, m, px, y, pz, r, g, bl, a);
            v(b, m, px, ty1, pz, r, g, bl, 0F);
            v(b, m, lx, y, lz, r, g, bl, 0F);

            v(b, m, px, y, pz, r, g, bl, a);
            v(b, m, lx, y, lz, r, g, bl, 0F);
            v(b, m, px, ty0, pz, r, g, bl, 0F);

            v(b, m, px, y, pz, r, g, bl, a);
            v(b, m, px, ty0, pz, r, g, bl, 0F);
            v(b, m, rx2, y, rz2, r, g, bl, 0F);

            v(b, m, px, y, pz, r, g, bl, a);
            v(b, m, rx2, y, rz2, r, g, bl, 0F);
            v(b, m, px, ty1, pz, r, g, bl, 0F);
        }
    }

    /* ---- Inner strands (lines) ---- */

    /**
     * Thin wavering inner strands, as SOFT camera-facing ribbons (not 1-px lines) so they read blurred
     * like the rest of the beam — hard lines don't antialias and looked sharp in-world versus the
     * antialiased editor preview. Each segment is a two-strip quad (edge α0 → centre → edge α0). Killed
     * over the bottom 20% so they don't converge into bright spikes at the base.
     */
    private static void strandRibbons(net.minecraft.client.render.VertexConsumer b, Matrix4f m, Vector3f cam, BeamForm form,
        float curH, float radius, float st, float r, float g, float bl, float peak)
    {
        int count = form.strands.get();

        if (count <= 0)
        {
            return;
        }

        int seg = Math.max(10, Math.min(90, (int) (curH * 1.6F)));
        float soft = form.softness.get();
        float halfW = Math.max(0.02F, radius * 0.05F * (1F + soft));

        for (int k = 0; k < count; k++)
        {
            float phase = hash01(k, 7) * 6.2831853F;
            /* Gentler wave + tighter radius → the strands read as near-vertical BLADE-strands bundled in
             * the core, not loose loops. */
            float freq = 2F + hash01(k, 8) * 3F;
            float amp = radius * (0.1F + 0.35F * hash01(k, 9));
            float flowPh = hash01(k, 10) * 6.2831853F;
            Vector3f prev = null, prevR = null;
            float prevA = 0F;

            for (int i = 0; i <= seg; i++)
            {
                float fy = i / (float) seg;
                float y = fy * curH;
                float ang = fy * freq + phase + st * 3F;
                float rad = amp * (0.4F + fy);
                float px = (float) Math.cos(ang) * rad;
                float pz = (float) Math.sin(ang) * rad;
                float baseFade = fy < 0.2F ? 0F : (fy < 0.35F ? (fy - 0.2F) / 0.15F : 1F);
                /* Bright ENERGY PULSES travelling along the strand (flows down/up over time). */
                float flow = 0.35F + 0.65F * Math.max(0F, (float) Math.sin(fy * 9F - st * 6F + flowPh));
                float a = peak * baseFade * lenFade(fy) * flow;
                Vector3f cur = new Vector3f(px, y, pz);
                Vector3f rr = cylRight(cam, px, y, pz).mul(halfW);

                if (prev != null && (prevA > 0F || a > 0F))
                {
                    /* Left half (edge α0 → centre). */
                    v(b, m, prev.x - prevR.x, prev.y, prev.z - prevR.z, r, g, bl, 0F);
                    v(b, m, prev.x, prev.y, prev.z, r, g, bl, prevA);
                    v(b, m, cur.x, cur.y, cur.z, r, g, bl, a);
                    v(b, m, prev.x - prevR.x, prev.y, prev.z - prevR.z, r, g, bl, 0F);
                    v(b, m, cur.x, cur.y, cur.z, r, g, bl, a);
                    v(b, m, cur.x - rr.x, cur.y, cur.z - rr.z, r, g, bl, 0F);

                    /* Right half (centre → edge α0). */
                    v(b, m, prev.x, prev.y, prev.z, r, g, bl, prevA);
                    v(b, m, prev.x + prevR.x, prev.y, prev.z + prevR.z, r, g, bl, 0F);
                    v(b, m, cur.x + rr.x, cur.y, cur.z + rr.z, r, g, bl, 0F);
                    v(b, m, prev.x, prev.y, prev.z, r, g, bl, prevA);
                    v(b, m, cur.x + rr.x, cur.y, cur.z + rr.z, r, g, bl, 0F);
                    v(b, m, cur.x, cur.y, cur.z, r, g, bl, a);
                }

                prev = cur;
                prevR = rr;
                prevA = a;
            }
        }
    }

    /* ---- helpers ---- */

    /** Horizontal right axis for a Y-axis cylindrical billboard at a form-local point. */
    private static Vector3f cylRight(Vector3f cam, float px, float py, float pz)
    {
        float vx = cam.x - px, vz = cam.z - pz;
        Vector3f right = new Vector3f(vz, 0F, -vx);

        if (right.lengthSquared() < 1e-8F)
        {
            return new Vector3f(1F, 0F, 0F);
        }

        return right.normalize();
    }

    /** Width along the column height: uniform, with only a gentle round-off at the very end (no sharp
     *  lance tip — the user disliked the pointed end). The soft fade comes from {@link #lenFade}. */
    private static float widthTaper(float fy)
    {
        return fy < 0.9F ? 1F : 1F - (fy - 0.9F) / 0.1F * 0.25F;
    }

    /** Alpha fade along the column: quick fade-in at the base, fade-out near the tip. */
    private static float lenFade(float fy)
    {
        if (fy < 0.05F)
        {
            return fy / 0.05F;
        }

        if (fy > 0.8F)
        {
            return Math.max(0F, 1F - (fy - 0.8F) / 0.2F);
        }

        return 1F;
    }

    /** Cosine profile across a width span (u in [-1,1]): 1 at centre, 0 at the edges. */
    private static float profile(float u, float pow)
    {
        return (float) Math.pow(Math.max(0F, Math.cos(u * Math.PI / 2F)), pow);
    }

    private static float smooth(float x)
    {
        x = Math.max(0F, Math.min(1F, x));

        return x * x * (3F - 2F * x);
    }

    private static float mix(float a, float b, float t)
    {
        return a + (b - a) * t;
    }

    /* Triangle→quad accumulator: the emissive RenderLayer is QUADS, but the geometry emits triangles,
     * so buffer 3 verts and flush them as a degenerate quad (4th = 3rd). x,y,z,r,g,b,a,u,v per vert. */
    private static final float[] ACC = new float[3 * 9];
    private static int accN;

    private static void v(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, float g, float bl, float a)
    {
        v(vc, m, x, y, z, r, g, bl, a, 0.5F, 0.5F);
    }

    private static void v(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, float g, float bl, float a, float u, float vv)
    {
        int o = accN * 9;

        ACC[o] = x; ACC[o + 1] = y; ACC[o + 2] = z;
        ACC[o + 3] = r; ACC[o + 4] = g; ACC[o + 5] = bl; ACC[o + 6] = a;
        ACC[o + 7] = u; ACC[o + 8] = vv;
        accN++;

        if (accN == 3)
        {
            for (int i = 0; i < 3; i++)
            {
                int p = i * 9;

                ev(vc, m, ACC[p], ACC[p + 1], ACC[p + 2], ACC[p + 3], ACC[p + 4], ACC[p + 5], ACC[p + 6], ACC[p + 7], ACC[p + 8]);
            }

            int p = 2 * 9;

            ev(vc, m, ACC[p], ACC[p + 1], ACC[p + 2], ACC[p + 3], ACC[p + 4], ACC[p + 5], ACC[p + 6], ACC[p + 7], ACC[p + 8]);
            accN = 0;
        }
    }

    private static float hash01(int i, int salt)
    {
        int x = (i ^ 0x9e3779b9) * 0x85ebca6b + salt * 0x165667b1;

        x ^= x >>> 13;
        x *= 0x27d4eb2d;
        x ^= x >>> 15;

        return (x & 0xFFFF) / (float) 0xFFFF;
    }
}
