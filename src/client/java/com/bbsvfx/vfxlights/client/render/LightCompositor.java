package com.bbsvfx.vfxlights.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.vfxlights.client.shadow.ShadowAtlas;
import com.bbsvfx.vfxlights.client.shadow.ShadowMapper;
import com.bbsvfx.vfxlights.light.Light;
import com.bbsvfx.vfxlights.light.LightRegistry;

import java.util.List;

/**
 * Draws the gathered lights over the finished world image, without a shaderpack.
 *
 * <p><b>Why this backend exists at all.</b> The good-looking path is patching a shaderpack, which buys
 * real shadows and correct mixing with the pack's PBR — and works for nobody who does not use one. A
 * public mod that does nothing for half its users is a public mod with bad reviews, so this pass comes
 * first: it works everywhere, and it is what proves the light is landing in the right place before any
 * of the harder machinery is built.</p>
 *
 * <p><b>What it can and cannot do.</b> There is no G-buffer to read, so the surface normal is recovered
 * from the derivatives of the depth buffer and the albedo is taken from the already-lit frame. Lighting
 * is therefore modulating what is on screen rather than shading raw material — good enough for a lamp or
 * an explosion flash, and honestly not good enough for a key light on a face. No shadows: an unshadowed
 * lamp lights through walls, so the falloff is kept tight.</p>
 *
 * <p><b>One pass per light, scissored.</b> Minecraft's core-shader uniforms have no comfortable array
 * support, and a pass each keeps the shader trivial. Each pass is clipped to the light's screen-space
 * bounds ({@link LightScreenRect}), so a small lamp costs a small rectangle rather than the whole
 * frame, and an off-screen lamp costs nothing at all.</p>
 */
public final class LightCompositor
{
    /** Lights shaded per frame here. Beyond this the cost is a draw call each, so the budget is modest. */
    static final int MAX_SHADED = 32;

    /** Dev probe: paint gate values instead of light (see light_composite.fsh). Set VFXLIGHTS_PROBE=1. */
    public static final float PROBE =
        System.getenv("VFXLIGHTS_PROBE") != null || Boolean.getBoolean("vfxlights.probe") ? 1F : 0F;

    /** ★TEMP diagnostic (-Dvfxlights.shadow.gatedebug): paint the actor-map gates (R faceW,
     *  G feather, B reach) — the fast-animation flicker hunt. */
    private static final boolean GATE_DEBUG = System.getProperty("vfxlights.shadow.gatedebug") != null;

    /** ★TEMP diagnostic (-Dvfxlights.light.debug): paint the raw diffuse contribution before the
     *  composite math — the "tints instead of lighting in darkness" localiser. */
    private static final boolean LIGHT_DEBUG = System.getProperty("vfxlights.light.debug") != null;

    private static ShaderProgram shader;
    private static SimpleFramebuffer scratch;
    private static int scratchWidth;
    private static int scratchHeight;

    /* Vanilla first-person hand: the LIVE main depth (hand included) copied aside at composite
     * time, because DepthSampler's snapshot is pre-hand by design. The shader compares the two
     * and drops pixels the hand covers — the old GL LEQUAL mask was paper-correct but let the
     * hand through in practice; this one runs on data a debug view can paint. */
    private static SimpleFramebuffer handDepth;
    private static int handDepthWidth;
    private static int handDepthHeight;

    /** ★TEMP diagnostic (-Dvfxlights.hand.debug): paint the live-depth hand mask (R = hand-covered,
     *  dark G = cleared world, B = other). */
    private static final boolean HAND_DEBUG = System.getProperty("vfxlights.hand.debug") != null;

    /* The texture id the current frame's HandDepth sampler must read (the live-depth copy in
     * vanilla, the world snapshot under packs). Carried to drawLight for the hard bind — the
     * addSampler path provably binds nothing past unit 1 in this pipeline (the ShadowAtlas
     * lesson), so unit 6 gets the same raw-GL treatment. */
    private static int handDepthTexForFrame = -1;

    private LightCompositor()
    {
    }

    /** Called by the core-shader registration callback once the program is loaded. */
    public static void setShader(ShaderProgram program)
    {
        shader = program;
    }

    /**
     * The UNIVERSAL FALLBACK: surface light pools over the FINISHED frame of a pack we have no patch
     * for. Runs from the volumetric post stage (after the pack's composites — drawing any earlier is
     * painted over, the Iris final-blit lesson). Degraded on purpose: the light lands after the pack's
     * tonemap and knows nothing of its materials — but a public mod that goes dark under half the
     * packs is worse than one that lights them plainly. Colour comes from the main buffer, depth from
     * the volumetric snapshot (main's own depth is stale under Iris at this point).
     */
    public static void renderFallback(Matrix4f invViewProj, Matrix4f viewProj, Vec3d cameraPos, int depthTexture)
    {
        if (shader == null)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer main = mc.getFramebuffer();
        List<Light> lights = LightRegistry.getLights(cameraPos.x, cameraPos.y, cameraPos.z, MAX_SHADED);

        if (lights.isEmpty())
        {
            return;
        }

        ensureScratch(main.textureWidth, main.textureHeight);

        /* Same framebuffer courtesy as the vanilla path: restore whatever was bound so the pack's own
         * composite is not redirected into main (the HUD-visible black-world bug). */
        int prevFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        /* Colour only — depth arrives as the snapshot texture. */
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scratch.fbo);
        GL30.glBlitFramebuffer(0, 0, main.textureWidth, main.textureHeight,
            0, 0, scratch.textureWidth, scratch.textureHeight,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        /* Vanilla first-person hand: the LIVE main depth (hand included — MC cleared the world
         * depth before renderHand, so live = 1.0 across the world and NEAR at the hand). The
         * shader rejects every pixel whose live depth is post-clear content (< 0.9999). FIRST
         * PERSON ONLY: in F5 nothing clears the depth, the live buffer holds the whole world and
         * the rule would erase every lamp. */
        boolean maskHand = !com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse()
            && mc.options.getPerspective().isFirstPerson();

        if (maskHand)
        {
            ensureHandDepth(main.textureWidth, main.textureHeight);

            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, handDepth.fbo);
            GL30.glBlitFramebuffer(0, 0, main.textureWidth, main.textureHeight,
                0, 0, handDepth.textureWidth, handDepth.textureHeight,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        }

        main.beginWrite(false);

        /* ★RAW bind, not just beginWrite. BBS binds ITS framebuffer with raw GL for the third-person
         * camera, so GlStateManager's cache believes main is already current and beginWrite no-ops —
         * our draw then lands in BBS's offscreen buffer and main comes back byte-identical (measured:
         * light applied in first-person, zero change in third-person, same fbo id, same viewport). Force
         * the bind through raw GL so the draw actually reaches main. Same for the render states below:
         * a cache that lies about depth-test/cull leaves them as BBS set them. */
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.fbo);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, true);
        GL11.glEnable(GL11.GL_BLEND);

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        shader.addSampler("DiffuseSampler", scratch.getColorAttachment());
        shader.addSampler("DepthSampler", depthTexture);
        handDepthTexForFrame = maskHand ? handDepth.getDepthAttachment() : depthTexture;
        shader.addSampler("HandDepth", handDepthTexForFrame);

        /* Character depth for cel: cleared to 1.0, so with no actors it disables cel by itself. Fall
         * back to the scene depth only before the first capture (first frame), where it never runs. */
        int charMask = com.bbsvfx.vfxlights.client.render.CharacterMask.getDepthTexture();

        shader.addSampler("CharMask", charMask > 0 ? charMask : depthTexture);

        if (ShadowAtlas.getTexture() != -1)
        {
            shader.addSampler("ShadowAtlas", ShadowAtlas.getTexture());
        }

        if (ShadowAtlas.getColorTexture() != -1)
        {
            shader.addSampler("ShadowColor", ShadowAtlas.getColorTexture());
        }

        setMatrix("InvViewProj", invViewProj);
        setVec3("CameraPos", (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        setFloat("HandMask", maskHand ? 1F : 0F);
        setFloat("HandDebug", HAND_DEBUG ? 1F : 0F);

        /* ★Mask the first-person HAND (vanilla only). We draw AFTER the hand, but our reconstruction
         * uses the pre-hand world-depth snapshot, so at the hand's pixels we lit the geometry BEHIND it
         * and added that onto the hand — "свет проходит через руку". MC clears the depth buffer before
         * drawing the hand, so the live main depth here is 1.0 across the whole world and only NEAR at
         * the hand. Depth-testing our quad (window depth 0.5) LEQUAL against it lets the light land on
         * the world (1.0 >= 0.5) and rejects the hand (nearer than 0.5). Raw GL: the cache thinks the
         * test is off (disableDepthTest above), so a RenderSystem call would no-op. Packs manage their
         * own hand, so this is vanilla-only. The shader-side HandDepth compare above is the same test
         * on visible data — both stay: the GL test costs nothing when it works. */
        if (maskHand)
        {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
        }

        for (Light light : lights)
        {
            /* Scissor the fullscreen quad down to the light's projected sphere: a table lamp costs
             * a small rectangle of fill instead of the whole frame, and a lamp entirely off screen
             * costs nothing. The rect is conservative (see LightScreenRect) — pixels outside it are
             * ones this light could not have touched. */
            int fit = LightScreenRect.compute(light, viewProj, cameraPos,
                main.textureWidth, main.textureHeight);

            if (fit == LightScreenRect.OFFSCREEN)
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

            /* Under a pack this frame's fresh Light objects carry tile -1; the persisted assignment
             * has the real one (the data-lifecycle lesson). */
            ShadowMapper.applyPersisted(light);

            /* Replay-category filter: this lamp lights only actors of its groups. The mask was
             * captured at LAST (the world matrices are no longer valid here); a lamp that got no
             * slot — over the per-frame cap — draws unfiltered. */
            boolean groupFiltered = false;

            if (light.groupFilter && !light.groups.isEmpty())
            {
                int mask = GroupMask.slotFor(light);

                if (mask > 0)
                {
                    groupFiltered = true;
                    shader.addSampler("GroupMaskTex", mask);
                }
            }

            if (!groupFiltered)
            {
                /* Dummy binding: the sampler must point at a valid texture even with the flag off. */
                shader.addSampler("GroupMaskTex", depthTexture);
            }

            drawLight(light, groupFiltered);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        if (maskHand)
        {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
    }

    /**
     * At WorldRenderEvents.LAST, VANILLA only: render this frame's shadow maps so the atlas is ready.
     *
     * <p>The actual surface composite is NOT drawn here any more. Drawing at LAST is painted over by
     * BBS's own final composite whenever the HUD is visible — the whole world went black, and F1
     * (hud-hidden, a different BBS path) masked it. The volumetric pass already learned this and moved
     * to {@code BBSRendering.onWorldRenderEnd}; the surface pool now rides the SAME late hook through
     * {@link #renderFallback}, which VolumetricPass.renderPost calls for vanilla as well as unpatched
     * packs. Here we only prepare the shadow atlas (and under a pack, nothing — the patch lights it).</p>
     */
    public static void render(WorldRenderContext context)
    {
        if (!com.bbsvfx.vfxlights.VfxLightsModule.isEnabled()
            || shader == null || com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse())
        {
            return;
        }

        Vec3d cameraPos = context.camera().getPos();
        List<Light> lights = LightRegistry.getLights(cameraPos.x, cameraPos.y, cameraPos.z, MAX_SHADED);

        if (lights.isEmpty())
        {
            return;
        }

        /* Shadow maps into the atlas; renderFallback samples them at onWorldRenderEnd. */
        ShadowMapper.renderAll(lights);
    }

    /**
     * Bind the shadow atlas onto texture unit 2 by hand — cache AND raw GL — and aim the sampler uniform
     * at it while the program is current.
     *
     * <p>Measured, not assumed: with only {@code addSampler} the composite ran with NOTHING on unit 2
     * (bound 0, expected the atlas) even though units 0 and 1 bound fine through the same path. Whatever
     * the plumbing reason, forcing the binding at both levels sidesteps it — the same
     * cache-versus-real-GL lesson this codebase already paid for once under Iris.</p>
     */
    private static void bindShadowAtlasHard()
    {
        int atlas = ShadowAtlas.getTexture();

        if (atlas == -1)
        {
            return;
        }

        /* The program must be current for glUniform1i to land in it. */
        shader.bind();

        int location = GlUniform.getUniformLocation(shader.getGlRef(), "ShadowAtlas");

        if (location >= 0)
        {
            GlUniform.uniform1(location, 2);
        }

        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
        GlStateManager._bindTexture(atlas);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);

        /* The colour map rides on unit 3, same hard-bind treatment. */
        int color = ShadowAtlas.getColorTexture();

        if (color != -1)
        {
            int colorLocation = GlUniform.getUniformLocation(shader.getGlRef(), "ShadowColor");

            if (colorLocation >= 0)
            {
                GlUniform.uniform1(colorLocation, 3);
            }

            GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE3);
            GlStateManager._bindTexture(color);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
        }

        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
    }

    /** Same raw-GL treatment as {@link #bindShadowAtlasHard()}: HandDepth lives on unit 6 (last in
     *  the json list — never insert samplers in the middle, the units are positional). */
    private static void bindHandDepthHard()
    {
        if (handDepthTexForFrame <= 0)
        {
            return;
        }

        shader.bind();

        int location = GlUniform.getUniformLocation(shader.getGlRef(), "HandDepth");

        if (location >= 0)
        {
            GlUniform.uniform1(location, 6);
        }

        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE6);
        GlStateManager._bindTexture(handDepthTexForFrame);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, handDepthTexForFrame);
        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
    }

    private static void drawLight(Light light, boolean groupFiltered)
    {
        if (light.intensity <= 0.001F)
        {
            return;
        }

        boolean area = light.type == Light.Type.AREA;
        boolean ambient = light.type == Light.Type.AMBIENT;
        boolean tube = area && light.areaShape == Light.AreaShape.TUBE;

        setVec3("LightPos", (float) light.x, (float) light.y, (float) light.z);
        setVec3("LightColor", light.r, light.g, light.b);
        setVec4("LightParams", light.intensity, light.range, light.effectiveSourceRadius(), typeIndex(light));
        setVec4("LightDir", light.dirX, light.dirY, light.dirZ, light.cosOuter);

        /* The slots mean different things per type — the shader decodes by the type and shape ids.
         * Ambient reuses the area slots for its volume and hemisphere data. */
        if (ambient)
        {
            float mode = light.hemisphere ? 2F : (light.boxVolume ? 1F : 0F);

            setVec4("LightShape", light.edgeFalloff, light.sizeX, light.sizeY, mode);
            setVec4("LightUp", light.upX, light.upY, light.upZ, light.sizeZ);
            setVec4("LightBarn", light.groundR, light.groundG, light.groundB, light.occlusion);
        }
        else
        {
            setVec4("LightShape",
                area ? light.barnSoftness : light.cosInner,
                area ? light.width : light.coneScaleX,
                tube ? light.thickness : (area ? light.height : light.coneScaleY),
                light.areaShape.ordinal() + (area && light.twoSided ? 10F : 0F));
            setVec4("LightUp", light.upX, light.upY, light.upZ, light.spread);
            setVec4("LightBarn", light.barnTop, light.barnBottom, light.barnLeft, light.barnRight);
        }

        /* Where this light's shadow map lives. Tile -1 means it has none — either shadows are off for
         * this lamp, or the atlas was full — and the shader then treats everything as lit. */
        /* Negative "across" flags a WIDE spot whose shadow lives in the cube, not the matrix tile. */
        float across = light.type == Light.Type.SPOT && ShadowMapper.usesCube(light)
            ? -ShadowAtlas.TILES_ACROSS : ShadowAtlas.TILES_ACROSS;

        /* .w is the RAW softness dial: the shared model multiplies it by the emitter's own size
         * (thickness/2 for a tube, half-extents for panels, source radius for point/spot) — the
         * penumbra rule lives in ONE place, vfxlights_model.glsl, for both backends. */
        setVec4("ShadowSlot", light.shadowTile, across, ShadowAtlas.NEAR, light.shadowSoft);
        /* The two global shadow dials. .x = tile side in texels (Shadow quality) — the shared model's
         * filter radii are all written in texels, so this is what turns a sharper atlas into a
         * tighter filter; .y = the filter step, which sets the tap counts and the kernel width. */
        setVec4("AtlasInfo", ShadowAtlas.TILE_SIZE,
            com.bbsvfx.vfxlights.VfxLightsAddon.shadowFilterStep(), 0F, 0F);
        /* ★TEMP bisect flag (-Dvfxlights.water.off): water treatment fully off (wd = -3 sentinel). */
        float waterDist = System.getProperty("vfxlights.water.off") != null ? -3F : light.waterDist;

        setVec4("Dispersion", light.dispersion, light.dispersionScale, Light.dispersionPhase(), waterDist);
        setVec4("LightExtra", light.translucency, light.iesProfile, light.waterY, light.rim);
        setVec4("LightCel", light.cel ? 1F : 0F, light.celSoftness, light.celShadowShift, light.celShadowLevel);
        setVec4("LightAffect", (light.affectBlocks ? 1F : 0F) + (light.affectEntities ? 2F : 0F), PROBE, light.outline, light.outlineWidth);
        setVec4("LightContour", light.outlineBlur, light.rimWidth, groupFiltered ? 1F : 0F,
            groupFiltered && com.bbsvfx.vfxlights.client.render.CharacterMask.hasCharacters() ? 1F : 0F);
        /* Interior contour strength + target (0 = world and models, 1 = models, 2 = world);
         * .z = the character mask exists at all (the target gate reads it), .w = blend mode
         * (0 = add, 1 = screen, 2 = overlay). */
        setVec4("LightOutlineX", light.outlineInner, light.outlineTarget,
            com.bbsvfx.vfxlights.client.render.CharacterMask.hasCharacters() ? 1F : 0F,
            light.outlineBlend);
        setFloat("LightFalloff", (float) light.falloffMode);
        /* ★TEMP diagnostic (-Dvfxlights.shadow.gatedebug): see the GateDebug block in main(). */
        setFloat("GateDebug", GATE_DEBUG ? 1F : 0F);
        /* ★TEMP diagnostic (-Dvfxlights.light.debug): see the LightDebug block in main(). */
        setFloat("LightDebug", LIGHT_DEBUG ? 1F : 0F);
        /* ★TEMP diagnostic (-Dvfxlights.water.debug): paints R.shade (shadows x water factor). */
        setFloat("WaterDebug", System.getProperty("vfxlights.water.debug") != null ? 1F : 0F);
        setFloat("SpecRim", light.specRim);

        /* Actor-fitted maps (Blender-look), one per actor slot: tile -1 = the slot carries no map,
         * the shader then keeps the main map's word. .w = the frustum's half-angle tangent, so the
         * shader sizes its bias in texels. Explicit names, not "ActorSlot" + slot — the composite
         * runs per lamp per frame and the concatenation would allocate on the render thread. */
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

        /* Only the spot renders through a single matrix; point and area read the cube by arithmetic. */
        if (light.shadowTile >= 0 && light.type == Light.Type.SPOT)
        {
            setMatrix("LightViewProj", light.shadowMatrix);
        }

        RenderSystem.setShader(() -> shader);

        bindShadowAtlasHard();
        bindHandDepthHard();

        /* ★Force ADDITIVE blend with RAW GL right before the draw. The GlStateManager cache lies after
         * BBS's raw-GL state changes, so RenderSystem.enableBlend/blendFunc can no-op and leave whatever
         * blend BBS last set. In third-person that left a REPLACE-style blend, so our shader output
         * (albedo x contribution, ~0 where the pool is dim) OVERWROTE the world with black — the whole
         * scene vanished the instant a lamp drew. Additive can only brighten; forcing it fixes it. */
        GL11.glEnable(GL11.GL_BLEND);
        org.lwjgl.opengl.GL14.glBlendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();

        /* Two triangles covering clip space. The vertex stage passes these straight through. */
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
        buffer.vertex(-1F, -1F, 0F).next();
        buffer.vertex(1F, -1F, 0F).next();
        buffer.vertex(1F, 1F, 0F).next();
        buffer.vertex(-1F, 1F, 0F).next();

        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static float typeIndex(Light light)
    {
        switch (light.type)
        {
            case SPOT:
                return 1F;
            case AREA:
                return 2F;
            case AMBIENT:
                return 3F;
            case POINT:
            default:
                return 0F;
        }
    }

    private static void setMatrix(String name, Matrix4f value)
    {
        if (shader.getUniform(name) != null)
        {
            shader.getUniform(name).set(value);
        }
    }

    private static void setVec3(String name, float x, float y, float z)
    {
        if (shader.getUniform(name) != null)
        {
            shader.getUniform(name).set(x, y, z);
        }
    }

    private static void setVec4(String name, float x, float y, float z, float w)
    {
        if (shader.getUniform(name) != null)
        {
            shader.getUniform(name).set(x, y, z, w);
        }
    }

    private static void setFloat(String name, float x)
    {
        if (shader.getUniform(name) != null)
        {
            shader.getUniform(name).set(x);
        }
    }

    private static void ensureScratch(int width, int height)
    {
        if (scratch != null && scratchWidth == width && scratchHeight == height)
        {
            return;
        }

        if (scratch != null)
        {
            scratch.delete();
        }

        scratch = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
        scratchWidth = width;
        scratchHeight = height;
    }

    private static void ensureHandDepth(int width, int height)
    {
        if (handDepth != null && handDepthWidth == width && handDepthHeight == height)
        {
            return;
        }

        if (handDepth != null)
        {
            handDepth.delete();
        }

        handDepth = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
        handDepthWidth = width;
        handDepthHeight = height;
    }
}
