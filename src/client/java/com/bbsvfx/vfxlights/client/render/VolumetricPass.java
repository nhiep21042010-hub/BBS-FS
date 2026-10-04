package com.bbsvfx.vfxlights.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.vfxlights.client.shadow.ShadowAtlas;
import com.bbsvfx.vfxlights.light.Light;
import com.bbsvfx.vfxlights.light.LightRegistry;

import java.util.List;

/**
 * The light's presence in the air: a raymarch per volumetric lamp into a HALF-RES float buffer,
 * then a depth-aware bilateral upsample composited over the finished frame — and after the
 * shaderpack, which is exactly the recipe the big BBS VFX effects (smoke volume, dome, wind)
 * proved viable under Iris: the scene depth is there, and additive glow over the finished frame
 * is how luminous air behaves anyway.
 *
 * <p>The half-res + upsample split is what killed the grain: a fullscreen 32-step march needed a
 * per-frame re-randomised dither to hide its banding, and that living grain read as "шум" in
 * vanilla and as crawl ("дрожь") under a pack's TAA. A static dither at quarter pixel count,
 * averaged by the bilateral gather, is smooth by construction — and the saved fill rate pays for
 * a 64-step march.</p>
 *
 * <p>Runs in BOTH modes, unlike the surface compositor: with a pack the surfaces are lit inside the
 * pack, but no pack knows about our beams, so the air is always ours to draw.</p>
 */
public final class VolumetricPass
{
    /** Beams shaded per frame. Each costs a half-res 64-step march; film scenes use a few, not many. */
    /* Room for a dispersion fan: one parent plus six spectral children, plus the rest of the scene. */
    private static final int MAX_BEAMS = 14;

    private static long lastLog;
    private static ShaderProgram shader;
    /** The depth-aware upsample: half-res beam buffer (rgb glow, a = scene depth01) -> the frame. */
    private static ShaderProgram upShader;
    private static SimpleFramebuffer depthCopy;
    private static int depthWidth;
    private static int depthHeight;
    /** Vanilla only: the LIVE main depth (first-person hand included), copied at post time. */
    private static SimpleFramebuffer postDepthCopy;
    /** Half-res HDR beam accumulation target. No depth attachment: beams accumulate (RGB adds,
     * alpha adds the marched scene depth01 onto the 0-cleared buffer — the upsample reads it for
     * its bilateral weights and the vanilla hand mask). */
    private static int beamFbo = -1;
    private static int beamTex = -1;
    private static int beamWidth;
    private static int beamHeight;

    private VolumetricPass()
    {
    }

    public static void setShader(ShaderProgram program)
    {
        shader = program;
    }

    public static void setUpShader(ShaderProgram program)
    {
        upShader = program;
    }

    /* Snapshot taken at WorldRenderEvents.LAST — the one point where the world depth and matrices are
     * complete under Iris — and consumed by the draw at BBSRendering.onWorldRenderEnd, which runs
     * AFTER the pack's composite. Drawing at LAST proved it the measured way: full-screen diagnostic
     * red in vanilla, nothing at all under the pack — Iris's final blit was painting over us. This
     * snapshot-then-draw split is the exact recipe every deferred BBS VFX effect already uses. */
    private static final Matrix4f SNAP_INV_VIEW_PROJ = new Matrix4f();
    /** The FORWARD matrix too — the flare pass projects light positions INTO the screen. */
    private static final Matrix4f SNAP_VIEW_PROJ = new Matrix4f();
    /** Temp diagnostics: -Dvfxlights.beam.debug logs the half-res buffer's centre pixel per second;
     * -Dvfxlights.beam.nohandmask bypasses the vanilla hand mask (isolation test). */
    private static final boolean BEAM_DEBUG = System.getProperty("vfxlights.beam.debug") != null;
    private static final boolean NO_HAND_MASK = System.getProperty("vfxlights.beam.nohandmask") != null;
    private static long lastBeamDebug;
    private static Vec3d snapCamera = Vec3d.ZERO;
    /** The EFFECTIVE eye the depth was rendered from: snapCamera plus the view-bob translation
     * (bobView is translate+rotate baked into the projection — ±strideDistance blocks vertically).
     * The march must start its rays HERE, not at the un-bobbed camera, or the beam's contact with
     * geometry pumps with every step. */
    private static Vec3d snapEye = Vec3d.ZERO;
    private static boolean framePrepared;

    /* ★Deep copies of the lights the PACK backend was fed at frame start. The pack's SSBO ships
     * LAST frame's list (collection happens during the very pass the pack shades in), so the air
     * passes at frame END must draw the SAME state — beams from the LIVE list sat one frame ahead
     * of their own light pools and sampled shadow tiles rendered for a different lamp position:
     * invisible in stills, visible tearing and flicker the moment a lamp moves (drag, keyframes).
     * Vanilla and the unpatched fallback shade and beam from one list inside one frame and never
     * had the problem — exactly why "no shaders = perfect" was the bug's signature.
     * Copies, not references: the registry's pooled Light objects are reset and refilled with the
     * NEW frame's lamps long before the post stage runs. The copy pool is reused — no churn. */
    private static final java.util.List<Light> packSnapshot = new java.util.ArrayList<>();
    private static final java.util.List<Light> packSnapshotPool = new java.util.ArrayList<>();

    /**
     * Called from the pack branch of the frame-start handler, right after the SSBO upload, with the
     * exact list it shipped. Keeps only lamps an air pass can draw (beam, haze, dust or flare).
     */
    public static void snapshotPackLights(List<Light> lights)
    {
        packSnapshot.clear();

        int used = 0;

        for (Light light : lights)
        {
            if (!volumetricEligible(light) && !DustPass.eligible(light) && !FlarePass.eligible(light))
            {
                continue;
            }

            Light copy;

            if (used < packSnapshotPool.size())
            {
                copy = packSnapshotPool.get(used);
            }
            else
            {
                copy = new Light();
                packSnapshotPool.add(copy);
            }

            used++;
            copy.copyFrom(light);
            /* copyFrom skips the shadow assignment on purpose (per-render state); the beams sample
             * by it, so carry it across explicitly — it was just assigned by the shadow pass. */
            copy.shadowTile = light.shadowTile;
            copy.shadowMatrix.set(light.shadowMatrix);

            for (int slot = 0; slot < Light.ACTOR_SLOTS; slot++)
            {
                copy.actorTile[slot] = light.actorTile[slot];
                copy.actorNear[slot] = light.actorNear[slot];
                copy.actorFar[slot] = light.actorFar[slot];
                copy.actorTan[slot] = light.actorTan[slot];
                copy.actorMatrix[slot].set(light.actorMatrix[slot]);
            }
            packSnapshot.add(copy);
        }
    }

    /** The list the air passes must draw this frame: the pack snapshot under a patched pack (same
     * state its SSBO carries), the live registry everywhere else (one list per frame there). */
    private static List<Light> airPassLights()
    {
        boolean packPatched = com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse()
            && com.bbsvfx.vfxlights.client.iris.PackPatcher.isCurrentPackPatched();

        return packPatched ? packSnapshot : LightRegistry.getLights();
    }

    /** At WorldRenderEvents.LAST: capture depth and matrices for this frame's beams. */
    public static void render(WorldRenderContext context)
    {
        framePrepared = false;

        /* Master module gate: with VFX LIGHTS turned off in BBS's VFX settings, do no work. framePrepared
         * stays false, so renderPost() (the onWorldRenderEnd draw) early-returns too. */
        if (!com.bbsvfx.vfxlights.VfxLightsModule.isEnabled())
        {
            return;
        }

        if (shader == null)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer main = mc.getFramebuffer();
        /* Same source renderPost() will draw from — under a patched pack that is the frame-start
         * snapshot, so framePrepared reflects what will actually be drawn (a lamp that appeared
         * THIS frame starts beaming next frame, together with its light pool in the pack). */
        List<Light> lights = airPassLights();
        int drawn = 0;

        /* The surface compositor runs at the POST stage (onWorldRenderEnd) for VANILLA as well as any
         * unpatched pack — only a patched pack lights surfaces inside itself. Both need this snapshot,
         * and vanilla moved here because drawing surfaces at LAST was painted over by BBS's composite
         * whenever the HUD was up (the whole-world-black bug F1 masked). */
        /* isCurrentPackPatched() alone latches true once a patched pack was seen: with shaders then
         * turned OFF the flag stays set and the whole fallback (ambient included) goes dead. */
        boolean fallback = !com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse()
            || !com.bbsvfx.vfxlights.client.iris.PackPatcher.isCurrentPackPatched();

        for (Light light : lights)
        {
            if (volumetricEligible(light) || FlarePass.eligible(light) || DustPass.eligible(light)
                || fallback)
            {
                drawn++;
            }
        }

        if (drawn == 0)
        {
            return;
        }

        /* Depth is copied aside NOW — and from the CURRENTLY BOUND framebuffer, not from main: under
         * Iris at LAST the world's depth lives in Iris's own framebuffer, and main holds stale data.
         * Blitting main gave beams marching through garbage — mist instead of rays, columns through
         * ceilings. (The DomeVolume rule, proven under every renderer.) State is restored exactly;
         * rebinding main mid-pipeline is not ours to do here. */
        ensureDepthCopy(main.textureWidth, main.textureHeight);

        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        /* Under a pack the world depth lives in the pack's own bound buffer (prevDraw); in VANILLA it
         * is always in main. Preferring prevDraw for vanilla broke THIRD-PERSON: F5 leaves a different,
         * depth-less FBO bound at LAST (the entity-outline buffer the player model touches), so the
         * snapshot came back empty — every fragment read depth 1.0 and discarded, and the scene, whose
         * only light is our lamps, went fully black. Snapshot main for vanilla, prevDraw only for packs. */
        int source = com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse() && prevDraw != 0
            ? prevDraw : main.fbo;

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthCopy.fbo);
        GL30.glBlitFramebuffer(0, 0, main.textureWidth, main.textureHeight,
            0, 0, depthCopy.textureWidth, depthCopy.textureHeight,
            GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);

        Matrix4f viewProj = new Matrix4f(RenderSystem.getProjectionMatrix());

        /* proj × model-view stack — the EXACT matrix the world depth was rendered with, view
         * bobbing included (in 1.20.1 bobView multiplies the PROJECTION stack in renderWorld; the
         * model-view stack at LAST is bob-free — verified against the decompiled GameRenderer).
         * The march reconstructs world positions through this pair's inverse, so every marched
         * point lands exactly on the rendered surface, bob or no bob. A de-bobbed matrix instead
         * desyncs from the (bobbed) depth content by bob-angle × distance, and the world-space
         * haze visibly sways with every step (the "дымка качается" report). */
        viewProj.mul(context.matrixStack().peek().getPositionMatrix());
        SNAP_VIEW_PROJ.set(viewProj);
        SNAP_INV_VIEW_PROJ.set(viewProj).invert();
        snapCamera = context.camera().getPos();

        /* The bobbed EYE: bobView is not just rotation — it translates the eye by up to
         * ±strideDistance blocks (verified in the bytecode), so the depth was rendered from a
         * point that is NOT camera.getPos(). A march starting at the un-bobbed camera misses that
         * parallax and the beam's contact with geometry pumps with every step — the "всё равно
         * скачет" report. Recover the effective eye from the matrices themselves:
         * basicProj⁻¹ × viewProj = bobView-transform × camera-rotation (affine A), and the eye
         * that zeros the view is C − A.rot⁻¹·A.trans. With bob disabled A is identity and this
         * reduces to the plain camera. */
        Matrix4f basicInv = new Matrix4f(mc.gameRenderer.getBasicProjectionMatrix(
            ((com.bbsvfx.vfxlights.mixin.client.GameRendererAccessor) mc.gameRenderer)
                .vfxlights$getFov(context.camera(), context.tickDelta(), true))).invert();

        Matrix4f bobTransform = basicInv.mul(viewProj);
        org.joml.Vector3f bobT = new org.joml.Vector3f(
            bobTransform.m30(), bobTransform.m31(), bobTransform.m32());

        new org.joml.Matrix3f(bobTransform).invert().transform(bobT);
        snapEye = snapCamera.subtract(bobT.x, bobT.y, bobT.z);

        framePrepared = true;
    }

    /** At BBSRendering.onWorldRenderEnd: draw the beams over the finished frame, pack or no pack. */
    public static void renderPost()
    {
        if (!framePrepared || shader == null || upShader == null)
        {
            return;
        }

        framePrepared = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer main = mc.getFramebuffer();
        Vec3d cameraPos = snapCamera;

        /* Patched pack: the frame-start snapshot — the SAME per-light state its SSBO carries, so a
         * moving lamp's beam, motes and flare land exactly on its light pool and sample the shadow
         * tiles rendered for that state. Vanilla/fallback: the live registry, same as the light. */
        List<Light> lights = airPassLights();

        /* Every budgeted pass below (beams, dust, flares) cuts this list at a fixed cap — and the
         * registry's raw order is CHUNK-SECTION VISIT order, which reshuffles as the camera moves.
         * With more eligible lights than budget, the lamps sitting on the cut line blinked in and
         * out with every step of the camera or drag of a lamp. Rank by the lamp's brightness at
         * the camera instead: continuous under motion, so the cut line is stable. (No range cutoff
         * in the weight on purpose — a beam is visible from far outside its lamp's range sphere.) */
        List<Light> ordered = new java.util.ArrayList<>(lights);

        ordered.sort((a, b) -> Float.compare(beamWeight(b, cameraPos), beamWeight(a, cameraPos)));

        /* Save the VIEWPORT and SCISSOR box before we override them for our fullscreen quads. In
         * third-person BBS renders through a viewport that is NOT the full framebuffer; leaving our
         * full-frame viewport set afterwards made MC present a broken (black) frame. First-person's
         * entry viewport already IS full-frame, so restoring it there is a no-op. */
        int[] savedViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        int[] savedScissor = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, savedScissor);
        boolean savedScissorOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        /* Force full framebuffer and no scissor for our fullscreen quads, and raw-bind main below: after
         * BBS's raw-GL framebuffer swap the GlStateManager cache can lie about the bound target and clip
         * or misroute the draw (measured). */
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        RenderSystem.viewport(0, 0, main.textureWidth, main.textureHeight);
        RenderSystem.colorMask(true, true, true, true);

        /* The surface pools go FIRST (beams and flares belong on top). Drawn here for VANILLA and any
         * unpatched pack; only a patched pack skips it (it lit surfaces itself). The packInUse() half
         * of the gate matters: the patched flag latches after a patched pack was seen, and with
         * shaders off nothing would ever draw the fallback. */
        if (!com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse()
            || !com.bbsvfx.vfxlights.client.iris.PackPatcher.isCurrentPackPatched())
        {
            LightCompositor.renderFallback(SNAP_INV_VIEW_PROJ, SNAP_VIEW_PROJ, cameraPos,
                depthCopy.getDepthAttachment());
        }

        main.beginWrite(false);
        /* Raw bind — the cache lies after BBS's raw-GL framebuffer swap (see LightCompositor). */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.fbo);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        shader.addSampler("DepthSampler", depthCopy.getDepthAttachment());

        if (ShadowAtlas.getTexture() != -1)
        {
            shader.addSampler("ShadowAtlas", ShadowAtlas.getTexture());
        }

        if (ShadowAtlas.getColorTexture() != -1)
        {
            shader.addSampler("ShadowColor", ShadowAtlas.getColorTexture());
        }

        /* Bind BOTH samplers with RAW GL as well as the cache. After Iris's composite the
         * GlStateManager cache lies about what is really bound (Iris binds with raw GL), so
         * cache-routed binds get skipped while the real units hold Iris's leftovers — measured as
         * beams turning into structureless mist under the pack while vanilla showed clean God rays. */
        shader.bind();

        int depthLoc = GlUniform.getUniformLocation(shader.getGlRef(), "DepthSampler");
        int atlasLoc = GlUniform.getUniformLocation(shader.getGlRef(), "ShadowAtlas");
        int colorLoc = GlUniform.getUniformLocation(shader.getGlRef(), "ShadowColor");

        if (depthLoc >= 0)
        {
            GlUniform.uniform1(depthLoc, 0);
        }

        if (atlasLoc >= 0)
        {
            GlUniform.uniform1(atlasLoc, 1);
        }

        if (colorLoc >= 0)
        {
            GlUniform.uniform1(colorLoc, 2);
        }

        /* Unbind Iris's SAMPLER OBJECTS from our units. A sampler object overrides the texture's own
         * parameters — raw texture binds cannot dislodge it, which is why every earlier binding fix
         * changed nothing: with an Iris shadow-compare sampler left on the unit, reading a depth
         * texture returns comparison garbage. Vanilla never binds sampler objects, hence "rays in
         * vanilla, mist under the pack". */
        org.lwjgl.opengl.GL33.glBindSampler(0, 0);
        org.lwjgl.opengl.GL33.glBindSampler(1, 0);
        org.lwjgl.opengl.GL33.glBindSampler(2, 0);

        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(ShadowAtlas.getColorTexture(), 0));
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(ShadowAtlas.getTexture(), 0));
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthCopy.getDepthAttachment());

        /* Re-sync the cache to the reality we just created, so later cache-routed code is not fooled
         * the other way around. */
        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
        GlStateManager._bindTexture(Math.max(ShadowAtlas.getColorTexture(), 0));
        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(Math.max(ShadowAtlas.getTexture(), 0));
        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(depthCopy.getDepthAttachment());

        /* The MARCH reconstructs world positions through the SAME matrix the depth was rendered
         * with — bob included — so every marched point lands exactly on the rendered surface, and
         * starts its rays at the BOBBED eye (snapEye): bobView translates the eye by up to
         * ±strideDistance blocks, and a ray origin off by that parallax makes the beam's contact
         * with geometry pump with every step (see render()). */
        setMatrix("InvViewProj", SNAP_INV_VIEW_PROJ);
        setVec3("CameraPos", (float) snapEye.x, (float) snapEye.y, (float) snapEye.z);
        /* The shared sim-time clock — wall time here made the haze/smoke pattern non-deterministic
         * under scrubbing and offline export (the flicker walk got this right from day one). RAW,
         * not dispersionPhase(): the fBm drift has no period to align the caustic wrap to. */
        setFloat("GameTime", com.bbsvfx.vfxlights.light.Light.effectClock());

        /* The march renders at HALF resolution into its own float buffer (rgb = glow, a = marched
         * scene depth01): the quarter pixel count pays for a 64-step march, and the depth-aware
         * bilateral upsample below averages the static dither grain away — the per-frame
         * re-randomised grain this replaces is what read as "шум" and, under a pack's TAA, as
         * "дрожь". HDR (RGBA16F): a bright beam's glow overshoots 1.0 long before the composite
         * clamps it, and RGBA8 would crush exactly the high-intensity lamps the noise report named. */
        ensureBeamBuffer(main.textureWidth, main.textureHeight);

        if (beamFbo != -1)
        {
            marchAndUpsample(main, ordered, cameraPos);
        }

        /* Dust motes before the flares: world-space billboards, they obey the depth snapshot.
         * Same ordered list as the beams — their budgets must cut the same stable line. */
        DustPass.draw(ordered, SNAP_VIEW_PROJ, cameraPos,
            depthCopy.getDepthAttachment(), main.textureWidth, main.textureHeight,
            com.bbsvfx.vfxlights.light.Light.effectClock());

        /* Lens flares on top of EVERYTHING — a flare is the camera's artifact, it obeys nothing in
         * the scene. Same additive state, same depth snapshot for occlusion. */
        FlarePass.draw(ordered, SNAP_VIEW_PROJ, cameraPos,
            depthCopy.getDepthAttachment(), main.textureWidth, main.textureHeight);

        /* ★TOGGLE-force every state cache+GL back in sync. Anything that composites or presents after us
         * (MC's HUD, Framebuffer.draw()) sets its state through the GlStateManager CACHE; our raw-GL work
         * desynced that cache, so those setters can NO-OP and run on our leftover state. A plain set()
         * no-ops when the cache already holds the value even if the real GL differs, so toggle each one
         * (opposite, then target): the first call forces the cache off the stale belief, the second lands
         * cache AND GL on the value. */
        RenderSystem.disableDepthTest();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableCull();
        RenderSystem.depthMask(false);
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableBlend();
        RenderSystem.enableBlend();
        /* Resync the blend FUNCTION too, not just the enable bit above. defaultBlendFunc() alone is a
         * SINGLE cache-routed value that no-ops when the cache already reads "default" while real GL still
         * holds our raw glBlendFunc(ONE,ONE); two DIFFERENT cache-routed funcs first — the second is
         * guaranteed to differ from the cache, forcing a real glBlendFunc that resyncs cache<->GL — then
         * default lands both on MC's blend. Same two-value trick for the equation (our beams set it raw to
         * ADD). State hygiene for whatever composites after us; the third-person black frame itself was
         * MC's vignette overlay drawing with the wrong blend and is fixed in InGameHudMixin. */
        RenderSystem.blendFunc(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
        RenderSystem.blendFunc(GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ZERO);
        RenderSystem.defaultBlendFunc();
        GlStateManager._blendEquation(GL14.GL_MIN);
        GlStateManager._blendEquation(GL14.GL_FUNC_ADD);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.fbo);

        /* Hand the viewport and scissor back exactly as MC had them, so third-person presentation is not
         * left staring through our full-frame viewport. */
        RenderSystem.viewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        GL11.glScissor(savedScissor[0], savedScissor[1], savedScissor[2], savedScissor[3]);

        if (savedScissorOn)
        {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        else
        {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    /**
     * How much this lamp matters to the air passes — {@code importance()} without its range cutoff
     * (a beam or flare stays visible far outside the lamp's own range sphere, where importance()
     * flattens to an order-unstable zero). The +1 keeps lamps whose surface intensity is dialed to
     * zero but whose beam is on (a supported gaffer trick) ranked by distance instead of tying at 0.
     */
    private static float beamWeight(Light light, Vec3d camera)
    {
        double dx = light.x - camera.x;
        double dy = light.y - camera.y;
        double dz = light.z - camera.z;

        return (float) ((1D + light.intensity) / (1D + dx * dx + dy * dy + dz * dz));
    }

    private static boolean volumetricEligible(Light light)
    {
        if (light.volumetric <= 0.001F && light.haze <= 0.001F)
        {
            return false;
        }

        /* Beams for every light shape: cone, bulb, fireball, and area emitters approximated by a
         * sphere of their own size. Flat panels still want a slab-shaped model — later. Ambient has
         * no air presence at all. */
        return light.type == Light.Type.POINT
            || light.type == Light.Type.SPOT
            || light.type == Light.Type.AREA;
    }

    /** The half-res march per beam, then the depth-aware upsample composited into main. */
    private static void marchAndUpsample(Framebuffer main, List<Light> ordered, Vec3d cameraPos)
    {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, beamFbo);
        RenderSystem.viewport(0, 0, beamWidth, beamHeight);
        /* Clear alpha to 0 = "no beam marched here": every drawn beam ADDs its marched scene
         * depth01 into alpha (plain ONE/ONE add — the GL_MIN equation this replaced never made it
         * to the rasterizer, measured as alpha reading back 1.0+depth). Untouched pixels keep 0,
         * which the upsample's bilateral reads as "far from any real surface" — uniformly tiny
         * weights that normalise away — and which the hand mask rejects as a non-beam pixel. */
        GlStateManager._clearColor(0F, 0F, 0F, 0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        /* No depth attachment and no depth test here: beams ACCUMULATE. The vanilla hand mask moved
         * to the upsample (it reads the alpha-carried scene depth against the live buffer copy). */
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        int budget = MAX_BEAMS;

        for (Light light : ordered)
        {
            if (!volumetricEligible(light))
            {
                continue;
            }

            /* Scissor the march to the beam's projected range sphere — in HALF-RES pixels, matching
             * the viewport, and through the SAME live matrix the march reconstructs with. A beam
             * wholly off screen is skipped WITHOUT spending the beam budget, so on-screen beams are
             * never starved by ones the shot cannot see. */
            int fit = LightScreenRect.compute(light, SNAP_VIEW_PROJ, cameraPos, beamWidth, beamHeight);

            if (fit == LightScreenRect.OFFSCREEN)
            {
                continue;
            }

            if (budget-- <= 0)
            {
                continue;
            }

            if (fit == LightScreenRect.PARTIAL)
            {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(LightScreenRect.RECT[0], LightScreenRect.RECT[1],
                    LightScreenRect.RECT[2], LightScreenRect.RECT[3]);
            }
            else
            {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }

            drawBeam(light);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        /* Vanilla first-person hand mask: by now the LIVE main depth includes the hand (it renders
         * after LAST, which is why the depthCopy lacks it). Copy it aside — sampling main's depth
         * while drawing into main's colour would be a feedback loop. Packs manage their own hand. */
        boolean maskHand = !com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse() && !NO_HAND_MASK;

        if (maskHand)
        {
            ensurePostDepthCopy(main.textureWidth, main.textureHeight);

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, postDepthCopy.fbo);
            GL30.glBlitFramebuffer(0, 0, main.textureWidth, main.textureHeight,
                0, 0, postDepthCopy.textureWidth, postDepthCopy.textureHeight,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        }

        /* Temp diagnostics (beam.debug): what did the march actually produce, and (vanilla) what
         * does the hand-mask depth copy hold — read back the centre pixel of each. */
        if (BEAM_DEBUG && System.currentTimeMillis() - lastBeamDebug > 1000)
        {
            lastBeamDebug = System.currentTimeMillis();

            java.nio.FloatBuffer px = org.lwjgl.BufferUtils.createFloatBuffer(4);

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, beamFbo);
            GL11.glReadPixels(beamWidth / 2, beamHeight / 2, 1, 1,
                GL11.GL_RGBA, GL11.GL_FLOAT, px);

            String msg = "beam buffer center: r=" + px.get(0) + " g=" + px.get(1)
                + " b=" + px.get(2) + " a(sceneDepth)=" + px.get(3);

            if (postDepthCopy != null)
            {
                java.nio.FloatBuffer dz = org.lwjgl.BufferUtils.createFloatBuffer(1);

                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, postDepthCopy.fbo);
                GL11.glReadPixels(postDepthCopy.textureWidth / 2, postDepthCopy.textureHeight / 2,
                    1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, dz);
                msg += " | postDepth center=" + dz.get(0);
            }

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            org.slf4j.LoggerFactory.getLogger("vfxlights").info(msg + " | maskHand=" + maskHand);
        }

        /* Depth-aware bilateral upsample + additive composite over the finished frame. */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.fbo);
        RenderSystem.viewport(0, 0, main.textureWidth, main.textureHeight);

        int postTex = maskHand ? postDepthCopy.getDepthAttachment() : depthCopy.getDepthAttachment();

        upShader.addSampler("BeamSampler", beamTex);
        upShader.addSampler("DepthSampler", depthCopy.getDepthAttachment());
        upShader.addSampler("PostDepth", postTex);
        /* Raw GL binds, same Iris-cache reasoning as the march above. */
        upShader.bind();

        int beamLoc = GlUniform.getUniformLocation(upShader.getGlRef(), "BeamSampler");
        int upDepthLoc = GlUniform.getUniformLocation(upShader.getGlRef(), "DepthSampler");
        int postLoc = GlUniform.getUniformLocation(upShader.getGlRef(), "PostDepth");

        if (beamLoc >= 0)
        {
            GlUniform.uniform1(beamLoc, 0);
        }

        if (upDepthLoc >= 0)
        {
            GlUniform.uniform1(upDepthLoc, 1);
        }

        if (postLoc >= 0)
        {
            GlUniform.uniform1(postLoc, 2);
        }

        org.lwjgl.opengl.GL33.glBindSampler(0, 0);
        org.lwjgl.opengl.GL33.glBindSampler(1, 0);
        org.lwjgl.opengl.GL33.glBindSampler(2, 0);

        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, postTex);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthCopy.getDepthAttachment());
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, beamTex);

        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
        GlStateManager._bindTexture(postTex);
        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(depthCopy.getDepthAttachment());
        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(beamTex);

        setUpVec2("BeamTexel", 1F / beamWidth, 1F / beamHeight);
        setUpFloat("HandMask", maskHand ? 1F : 0F);

        RenderSystem.setShader(() -> upShader);

        /* Plain additive composite — undo the march's separate MIN-alpha equation first. */
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);

        BufferBuilder upBuffer = Tessellator.getInstance().getBuffer();

        upBuffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
        upBuffer.vertex(-1F, -1F, 0F).next();
        upBuffer.vertex(1F, -1F, 0F).next();
        upBuffer.vertex(1F, 1F, 0F).next();
        upBuffer.vertex(-1F, 1F, 0F).next();

        BufferRenderer.drawWithGlobalProgram(upBuffer.end());
    }

    private static void drawBeam(Light light)
    {
        /* Under a pack this frame's fresh Light objects carry tile -1 (maps were rendered at frame
         * start against last frame's objects); pick up the persisted assignment by identity. */
        com.bbsvfx.vfxlights.client.shadow.ShadowMapper.applyPersisted(light);

        /* The volumetric type index differs from the surface one: 2 here means "sphere emitter". */
        float type = light.type == Light.Type.SPOT ? 1F : (light.type == Light.Type.AREA ? 2F : 0F);

        setVec3("LightPos", (float) light.x, (float) light.y, (float) light.z);
        setVec3("LightColor", light.r, light.g, light.b);
        /* Beam strength is its own dial: no intensity multiplier, no clamp — a gaffer can run
         * a visible beam on a lamp whose surface light is dimmed all the way down. */
        setVec4("LightParams", light.volumetric * 0.25F,
            light.range, light.effectiveSourceRadius(), type);
        setVec4("LightDir", light.dirX, light.dirY, light.dirZ, light.cosOuter);

        /* .z carries the HAZE strength. For area lights .y is the emitter diameter used as the air
         * glow's core size; approximating every area shape by a sphere of its own extent keeps the
         * beam alive for Rect/Disc/Tube until a slab/line model lands. */
        float emitterDiameter = light.width;

        if (light.type == Light.Type.AREA)
        {
            switch (light.areaShape)
            {
                case RECT:
                    emitterDiameter = Math.max(light.width, light.height);
                    break;
                case TUBE:
                    emitterDiameter = Math.max(light.thickness, 0.02F);
                    break;
                case DISC:
                case SPHERE:
                default:
                    emitterDiameter = light.width;
                    break;
            }
        }

        /* AREA reuses the slots its own way: cosInner is meaningless for a panel, so .x flags
         * two-sidedness, and .w carries the spread (coneScaleX is always 1 there) — the volumetric
         * area branch aims the beam along the panel normal the way the surfaces do. */
        boolean area = light.type == Light.Type.AREA;

        setVec4("LightShape", area ? (light.twoSided ? 1F : 0F) : light.cosInner, emitterDiameter,
            light.haze * 0.25F, area ? light.spread : light.coneScaleX);
        /* Negative "across" flags a WIDE spot whose shadow lives in the cube, not the matrix tile. */
        float shadowAcross = light.type == Light.Type.SPOT
            && com.bbsvfx.vfxlights.client.shadow.ShadowMapper.usesCube(light)
            ? -ShadowAtlas.TILES_ACROSS : ShadowAtlas.TILES_ACROSS;

        setVec4("ShadowSlot", light.shadowTile, shadowAcross, ShadowAtlas.NEAR, 0F);
        /* Tile side in texels (Shadow quality) and the filter step — the beam's shadow taps are
         * measured in texels too, and it calls the same model functions the surfaces do. */
        setVec4("AtlasInfo", ShadowAtlas.TILE_SIZE,
            com.bbsvfx.vfxlights.VfxLightsAddon.shadowFilterStep(), 0F, 0F);
        /* The quality preset's volumetric leg: march samples per block, the step floor, and the
         * two caps (base / bright beam). */
        setVec4("VolQuality", com.bbsvfx.vfxlights.VfxLightsAddon.volStepsPerBlock(),
            com.bbsvfx.vfxlights.VfxLightsAddon.volMinSteps(),
            com.bbsvfx.vfxlights.VfxLightsAddon.volMaxSteps(),
            com.bbsvfx.vfxlights.VfxLightsAddon.volMaxStepsBright());
        setVec4("Dispersion", light.dispersion, light.dispersionScale, Light.dispersionPhase(), light.waterDist);
        setVec4("LightUp", light.upX, light.upY, light.upZ, light.coneScaleY);
        /* Rim is a surface effect, but a strong rim can saturate the LDR framebuffer enough that
         * the additive beam becomes invisible. Pass the rim dial through so the volumetric shader can
         * lift the beam slightly to stay readable. .x was translucency, which the volumetric shader
         * never read — it now carries the dust-motes dial instead. */
        setVec4("LightExtra", light.dust, light.iesProfile, light.waterY, light.rim);
        /* Same law as the surfaces: a physical lamp's beam and haze fall 1/d² windowed, or the air
         * glows past the wall the pool already died on. */
        setFloat("LightFalloff", (float) light.falloffMode);
        /* The beam must be cut by the actors the same way the surfaces are — tile -1 in a slot
         * skips that tap. Explicit uniform names: "ActorSlot" + slot would allocate per lamp
         * per frame on the render thread. */
        setVec4("ActorSlot0", light.actorTile[0], light.actorNear[0], light.actorFar[0], light.actorTan[0]);
        setVec4("ActorSlot1", light.actorTile[1], light.actorNear[1], light.actorFar[1], light.actorTan[1]);
        setVec4("ActorSlot2", light.actorTile[2], light.actorNear[2], light.actorFar[2], light.actorTan[2]);
        setVec4("ActorSlot3", light.actorTile[3], light.actorNear[3], light.actorFar[3], light.actorTan[3]);

        if (light.actorTile[0] >= 0)
        {
            setMatrix("ActorViewProj0", light.actorMatrix[0]);
        }

        if (light.actorTile[1] >= 0)
        {
            setMatrix("ActorViewProj1", light.actorMatrix[1]);
        }

        if (light.actorTile[2] >= 0)
        {
            setMatrix("ActorViewProj2", light.actorMatrix[2]);
        }

        if (light.actorTile[3] >= 0)
        {
            setMatrix("ActorViewProj3", light.actorMatrix[3]);
        }

        if (light.shadowTile >= 0 && light.type == Light.Type.SPOT)
        {
            setMatrix("LightViewProj", light.shadowMatrix);
        }

        RenderSystem.setShader(() -> shader);

        /* Force ADDITIVE blend with raw GL — the cache lies after BBS's raw-GL state, so a REPLACE blend
         * left in third-person made the beam overwrite the world with black (see LightCompositor).
         * ONE/ONE on both channels: RGB accumulates every beam's glow, alpha ADDs the marched scene
         * depth01 onto the 0-cleared buffer (single-beam pixels carry the exact depth; overlaps sum —
         * rare, and the GL_MIN equation that would keep the nearest never survived to the rasterizer). */
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();

        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
        buffer.vertex(-1F, -1F, 0F).next();
        buffer.vertex(1F, -1F, 0F).next();
        buffer.vertex(1F, 1F, 0F).next();
        buffer.vertex(-1F, 1F, 0F).next();

        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static void setMatrix(String name, Matrix4f value)
    {
        GlUniform uniform = shader.getUniform(name);

        if (uniform != null)
        {
            uniform.set(value);
        }
    }

    private static void setVec3(String name, float x, float y, float z)
    {
        GlUniform uniform = shader.getUniform(name);

        if (uniform != null)
        {
            uniform.set(x, y, z);
        }
    }

    private static void setVec4(String name, float x, float y, float z, float w)
    {
        GlUniform uniform = shader.getUniform(name);

        if (uniform != null)
        {
            uniform.set(x, y, z, w);
        }
    }

    private static void setFloat(String name, float value)
    {
        GlUniform uniform = shader.getUniform(name);

        if (uniform != null)
        {
            uniform.set(value);
        }
    }

    private static void setUpVec2(String name, float x, float y)
    {
        GlUniform uniform = upShader.getUniform(name);

        if (uniform != null)
        {
            uniform.set(x, y);
        }
    }

    private static void setUpFloat(String name, float value)
    {
        GlUniform uniform = upShader.getUniform(name);

        if (uniform != null)
        {
            uniform.set(value);
        }
    }

    private static void ensureDepthCopy(int width, int height)
    {
        if (depthCopy != null && depthWidth == width && depthHeight == height)
        {
            return;
        }

        if (depthCopy != null)
        {
            depthCopy.delete();
        }

        depthCopy = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
        depthWidth = width;
        depthHeight = height;
    }

    private static void ensurePostDepthCopy(int width, int height)
    {
        if (postDepthCopy != null && postDepthCopy.textureWidth == width
            && postDepthCopy.textureHeight == height)
        {
            return;
        }

        if (postDepthCopy != null)
        {
            postDepthCopy.delete();
        }

        postDepthCopy = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
    }

    /** Half-res RGBA16F color-only target the beams march into. Raw GL: SimpleFramebuffer has no
     * float-format switch, and RGBA8 would clamp bright beams long before the composite does. */
    private static void ensureBeamBuffer(int width, int height)
    {
        int w = Math.max(width / 2, 1);
        int h = Math.max(height / 2, 1);

        if (beamFbo != -1 && beamWidth == w && beamHeight == h)
        {
            return;
        }

        if (beamFbo != -1)
        {
            GL30.glDeleteFramebuffers(beamFbo);
            GL11.glDeleteTextures(beamTex);

            beamFbo = -1;
        }

        beamTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, beamTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA,
            GL11.GL_FLOAT, (java.nio.FloatBuffer) null);

        int prev = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        beamFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, beamFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, beamTex, 0);
        GL30.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);

        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
        {
            /* No float render target — beams are skipped this session rather than drawn clamped;
             * practically unreachable on any GL 3.2 driver MC 1.20 runs on. */
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
            GL30.glDeleteFramebuffers(beamFbo);
            GL11.glDeleteTextures(beamTex);

            beamFbo = -1;
            beamTex = -1;

            return;
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);

        beamWidth = w;
        beamHeight = h;
    }
}
