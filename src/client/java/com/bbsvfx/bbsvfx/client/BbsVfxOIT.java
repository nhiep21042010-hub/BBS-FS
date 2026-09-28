package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import java.util.ArrayList;
import java.util.List;

/**
 * Weighted-Blended Order-Independent Transparency (McGuire &amp; Bavoil 2013) for the addon's VFX layers.
 * Translucent geometry accumulates into two float targets — accum (Σ premultiplied colour × weight) and
 * reveal (∏ 1−alpha) — then resolves, so any number of translucent layers blend correctly in ANY order
 * with NO back-to-front sorting (which MC can't do for our custom geometry, nor correctly for
 * intersecting layers at all).
 *
 * <p>DEFERRED: a VFX form {@link #queue}s an emitter during its render pass; the OPAQUE scene depth +
 * projection are snapshotted at {@code WorldRenderEvents.AFTER_ENTITIES} (before translucent water/clouds);
 * the accumulate + composite run at {@code onWorldRenderEnd} — AFTER a shaderpack's composite, where a
 * custom core program is visible (a raw program during the world pass is invisible under Iris). Because the
 * captured depth excludes clouds/water, the composite alpha-blends the VFX OVER them (proper transparency,
 * no binary silhouette mask) while terrain/foliage/entities still occlude it. Single-pass MRT (accum @0,
 * reveal @1) with independent per-target blending via {@code ARB_draw_buffers_blend} (GL 3.2-core has no
 * core {@code glBlendFunci}).</p>
 */
public final class BbsVfxOIT
{
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("bbsvfx");

    private static ShaderProgram accum;
    private static ShaderProgram composite;
    private static boolean buildTried;

    private static int fbo = -1;
    private static int accumTex = -1;
    private static int revealTex = -1;
    private static int depthTex = -1;
    private static int w, h;

    private static final List<Runnable> QUEUE = new ArrayList<>();
    private static final Matrix4f PROJ = new Matrix4f();
    private static boolean depthReady;

    /** True while a dome/beam was compositing on the previous frame — arms the depth capture even before
     *  this frame's form has queued (BBS may render the form after the AFTER_ENTITIES capture point). */
    private static boolean lastFrameHadWork;

    private BbsVfxOIT()
    {}

    public static boolean available()
    {
        ensureBuilt();

        return accum != null && composite != null
            && (GL.getCapabilities().OpenGL40 || GL.getCapabilities().GL_ARB_draw_buffers_blend);
    }

    /** A VFX form registers an emitter (called at composite time) that sets its texture and draws its
     *  translucent geometry (POSITION_COLOR_TEXTURE, positions baked to VIEW space) with the accum shader. */
    public static void queue(Runnable emitter)
    {
        /* Deferred — the emitter runs at composite time with the MAIN frame's state, so an emitter
         * registered from a foreign pass draws the form a second time, in the wrong place. The foreign
         * pass loses this form's translucent layer, which is what it wants: a beam/dome haze is not a
         * shadow caster and not a mask silhouette. See {@link BbsVfxForeignPass}. */
        if (BbsVfxForeignPass.isActive())
        {
            return;
        }

        QUEUE.add(emitter);
    }

    public static boolean hasQueued()
    {
        return !QUEUE.isEmpty();
    }

    private static void ensureBuilt()
    {
        if (buildTried)
        {
            return;
        }

        buildTried = true;

        try
        {
            accum = new ShaderProgram(factory(), "oit_accum", VertexFormats.POSITION_COLOR_TEXTURE);
            composite = new ShaderProgram(factory(), "oit_composite", VertexFormats.POSITION_TEXTURE);
        }
        catch (Exception e)
        {
            LOG.error("[oit] shader build failed", e);
        }
    }

    private static ResourceFactory factory()
    {
        return (id) ->
        {
            ResourceFactory manager = MinecraftClient.getInstance().getResourceManager();

            if (id.getPath().contains("/core/"))
            {
                return manager.getResource(Identifier.of("bbs", id.getPath()));
            }

            return manager.getResource(id);
        };
    }

    /**
     * Snapshot the OPAQUE scene depth (into the OIT fbo's depth) and the world projection at
     * {@code WorldRenderEvents.AFTER_ENTITIES} — after solid terrain, cutout foliage and entities, but
     * BEFORE translucent water and clouds are drawn. This is what makes the VFX transparency correct: the
     * dome depth-tests against terrain/foliage/entities only (they occlude it), while clouds/water are NOT
     * in this depth, so the deferred composite alpha-blends the dome OVER them — they show through the thin
     * shell and are tinted by the dense shell, instead of being cut out by a binary silhouette mask.
     *
     * <p>The currently-bound draw FBO holds this opaque depth under any renderer (vanilla main FB, or the
     * Iris/Sodium gbuffer). Captured unconditionally while OIT work is live (this frame's form may queue
     * either before or after this event, so we can't gate on the queue alone).</p>
     */
    public static void captureDepth(WorldRenderContext ctx)
    {
        if (!available() || (QUEUE.isEmpty() && !lastFrameHadWork))
        {
            return;
        }

        net.minecraft.client.gl.Framebuffer main = MinecraftClient.getInstance().getFramebuffer();

        if (main == null)
        {
            return;
        }

        int mw = main.textureWidth, mh = main.textureHeight;

        if (mw <= 0 || mh <= 0)
        {
            return;
        }

        ensure(mw, mh);

        int bound = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int src = bound != 0 ? bound : main.fbo;

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GL30.glBlitFramebuffer(0, 0, mw, mh, 0, 0, mw, mh, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bound);

        PROJ.set(RenderSystem.getProjectionMatrix());
        depthReady = true;
    }

    /** Accumulate the queued VFX and resolve over the scene — at {@code onWorldRenderEnd}, post-pack. */
    public static void render(net.minecraft.client.gl.Framebuffer main)
    {
        if (QUEUE.isEmpty())
        {
            lastFrameHadWork = false;

            return;
        }

        /* Work exists this frame — keep the depth capture armed for the next frame too (covers the case
         * where BBS renders the form after the AFTER_ENTITIES capture point). */
        lastFrameHadWork = true;

        List<Runnable> emitters = new ArrayList<>(QUEUE);

        QUEUE.clear();

        boolean depth = depthReady;

        depthReady = false;

        if (!available() || main == null || !depth)
        {
            return;
        }

        int mw = main.textureWidth, mh = main.textureHeight;

        if (mw <= 0 || mh <= 0)
        {
            return;
        }

        ensure(mw, mh);

        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        GL11.glViewport(0, 0, mw, mh);

        /* Clear only the colour targets — keep the captured scene depth. */
        RenderSystem.colorMask(true, true, true, true);
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {0F, 0F, 0F, 0F});
        GL30.glClearBufferfv(GL11.GL_COLOR, 1, new float[] {1F, 1F, 1F, 1F});

        RenderSystem.enableBlend();

        if (GL.getCapabilities().OpenGL40)
        {
            GL40.glBlendFunci(0, GL11.GL_ONE, GL11.GL_ONE);
            GL40.glBlendFunci(1, GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        else
        {
            ARBDrawBuffersBlend.glBlendFunciARB(0, GL11.GL_ONE, GL11.GL_ONE);
            ARBDrawBuffersBlend.glBlendFunciARB(1, GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        /* The geometry is baked to view space (through the form matrix); use the captured world projection
         * with an identity model-view (RenderSystem's matrices at post belong to the GUI). */
        RenderSystem.setProjectionMatrix(PROJ, VertexSorter.BY_DISTANCE);
        BbsVfxRenderCompat.pushIdentityModelView();
        RenderSystem.setShader(() -> accum);

        for (Runnable emitter : emitters)
        {
            emitter.run();
        }

        BbsVfxRenderCompat.popModelView();
        RenderSystem.setProjectionMatrix(oldProj, VertexSorter.BY_DISTANCE);

        /* Resolve over the scene. */
        main.beginWrite(false);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_SRC_ALPHA);
        RenderSystem.setShaderTexture(0, accumTex);
        RenderSystem.setShaderTexture(1, revealTex);
        RenderSystem.setShader(() -> composite);

        BufferBuilder buffer = BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);

        BbsVfxRenderCompat.next(buffer.vertex(-1F, -1F, 0F).texture(0F, 0F));
        BbsVfxRenderCompat.next(buffer.vertex(1F, -1F, 0F).texture(1F, 0F));
        BbsVfxRenderCompat.next(buffer.vertex(1F, 1F, 0F).texture(1F, 1F));
        BbsVfxRenderCompat.next(buffer.vertex(-1F, 1F, 0F).texture(0F, 1F));
        BufferRenderer.drawWithGlobalProgram(buffer.end());

        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    private static void ensure(int nw, int nh)
    {
        if (fbo == -1)
        {
            fbo = GL30.glGenFramebuffers();
            accumTex = GlStateManager._genTexture();
            revealTex = GlStateManager._genTexture();
            depthTex = GlStateManager._genTexture();
        }

        if (w == nw && h == nh)
        {
            return;
        }

        w = nw;
        h = nh;

        tex(accumTex, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, nw, nh);
        tex(revealTex, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT, nw, nh);

        GlStateManager._bindTexture(depthTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, nw, nh, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._bindTexture(0);

        int prev = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, accumTex, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, revealTex, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
        {
            LOG.error("[oit] framebuffer incomplete: {}", status);
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
    }

    private static void tex(int id, int internal, int format, int type, int nw, int nh)
    {
        GlStateManager._bindTexture(id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, nw, nh, 0, format, type, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GlStateManager._bindTexture(0);
    }
}
