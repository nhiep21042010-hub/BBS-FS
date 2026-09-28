package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.texture.Texture;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Heat-haze (mirage) pass for the beam: the air around the column REFRACTS the scene behind it, like
 * heat shimmer over a fire. Beams queue during the form pass; at {@code WorldRenderEvents.LAST} the
 * world depth + projection are snapshotted (the only point they're complete under Iris/Sodium); the
 * actual draw happens at {@code BBSRendering.onWorldRenderEnd} — AFTER a shaderpack's composite, on the
 * final frame in {@code mc.getFramebuffer()} — because a custom raw program at LAST writes past Iris's
 * MRT pipeline and the effect never shows. Camera-facing quads around each beam re-sample the frame
 * with noise-displaced UVs through the {@code beam_heat} core shader; occlusion is a MANUAL depth test
 * in the FSH against the captured depth (the post-time framebuffer has no world depth). The noise is
 * never visible itself — only the lens distortion it produces, which also shimmers the beam's edges.
 */
public final class BeamHeat
{
    public static ShaderProgram PROGRAM;

    private record Entry(Matrix4f mat, float height, float radius, float st, float strength)
    {}

    private static final List<Entry> QUEUE = new ArrayList<>();

    private static Framebuffer scratch;

    /* World depth + projection snapshotted at WorldRenderEvents.LAST. */
    private static int depthFbo = -1;
    private static int depthTex = -1;
    private static int depthW;
    private static int depthH;
    private static boolean depthReady;
    private static final Matrix4f PROJ = new Matrix4f();

    private BeamHeat()
    {}

    /** Called from the beam's form-pass render (world only — the editor preview has no post pass). */
    public static void queue(Matrix4f mat, float height, float radius, float st, float strength)
    {
        /* Deferred — never queue from a foreign pass (see {@link BbsVfxForeignPass}). */
        if (BbsVfxForeignPass.isActive())
        {
            return;
        }

        QUEUE.add(new Entry(mat, height, radius, st, strength));
    }

    /**
     * Snapshot the world depth (into our own texture) and the world projection at LAST — the bound FBO
     * holds the complete depth here regardless of renderer (same rule as BbsVfxFormBlend.captureDepth).
     */
    public static void captureDepth(WorldRenderContext context)
    {
        if (QUEUE.isEmpty())
        {
            return;
        }

        net.minecraft.client.gl.Framebuffer main = MinecraftClient.getInstance().getFramebuffer();

        if (main == null)
        {
            return;
        }

        int w = main.textureWidth;
        int h = main.textureHeight;

        if (w <= 0 || h <= 0)
        {
            return;
        }

        ensureDepth(w, h);

        int boundFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int src = boundFbo != 0 ? boundFbo : main.fbo;

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, boundFbo);

        PROJ.set(RenderSystem.getProjectionMatrix());
        depthReady = true;
    }

    /** The post-time draw, from BBSRenderingImpactMixin (after the pack's shading, final frame). */
    public static void render(net.minecraft.client.gl.Framebuffer main)
    {
        if (QUEUE.isEmpty())
        {
            return;
        }

        List<Entry> entries = new ArrayList<>(QUEUE);

        QUEUE.clear();

        boolean depth = depthReady;

        depthReady = false;

        if (PROGRAM == null || main == null || !depth)
        {
            return;
        }

        int w = main.textureWidth;
        int h = main.textureHeight;

        if (w <= 0 || h <= 0)
        {
            return;
        }

        ensureScratch(w, h);

        /* Final frame colour into the scratch (can't sample the attachment being written). */
        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scratch.id);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);

        main.beginWrite(true);

        /* A pack can leave real GL write masks off while the cache thinks they're on — reset BOTH
         * (the BbsVfxFormBlend "white screen" lesson). Occlusion is the manual FSH depth test. */
        RenderSystem.colorMask(true, true, true, true);
        GL11.glColorMask(true, true, true, true);
        GlStateManager._disableScissorTest();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, scratch.getMainTexture().id);
        RenderSystem.setShaderTexture(1, depthTex);
        RenderSystem.setShader(() -> PROGRAM);

        for (Entry e : entries)
        {
            PROGRAM.getUniformOrDefault("uProj").set(PROJ);
            PROGRAM.getUniformOrDefault("uTime").set(e.st);
            PROGRAM.getUniformOrDefault("uStrength").set(e.strength);

            BufferBuilder buffer = BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE);

            emitColumn(buffer, e);
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        }

        RenderSystem.enableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** Camera-facing haze column around the beam. Vertices are baked into VIEW space through the
     *  stored form matrix at build time (the VSH uses only the captured uProj — RenderSystem's
     *  matrices at post time belong to the GUI and would land the quads nowhere). */
    private static void emitColumn(BufferBuilder buffer, Entry e)
    {
        Matrix4f m = e.mat;
        Vector3f cam = new Matrix4f(m).invert().transformPosition(new Vector3f(0F, 0F, 0F));
        float dx = cam.x, dz = cam.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);

        if (len < 1e-4F)
        {
            dx = 0F;
            dz = 1F;
            len = 1F;
        }

        float rx = -dz / len, rz = dx / len;
        /* Nudge the plane toward the camera so it passes the depth test over the core's own
         * depth-prepass plane; parallax at that offset is invisible. */
        float px = dx / len * e.radius * 1.2F, pz = dz / len * e.radius * 1.2F;
        float hw = e.radius * 3.4F;
        int hSegs = Math.max(4, Math.min(24, (int) (e.height / 3F)));

        for (int i = 0; i < hSegs; i++)
        {
            float f0 = i / (float) hSegs, f1 = (i + 1) / (float) hSegs;
            float y0 = f0 * e.height, y1 = f1 * e.height;
            float a0 = vFade(f0), a1 = vFade(f1);

            /* uv.x = -1..1 across the column, uv.y = blocks along the beam (noise coords). */
            BbsVfxRenderCompat.next(buffer.vertex(m, px - rx * hw, y0, pz - rz * hw).color(1F, 1F, 1F, a0).texture(-1F, y0));
            BbsVfxRenderCompat.next(buffer.vertex(m, px - rx * hw, y1, pz - rz * hw).color(1F, 1F, 1F, a1).texture(-1F, y1));
            BbsVfxRenderCompat.next(buffer.vertex(m, px + rx * hw, y1, pz + rz * hw).color(1F, 1F, 1F, a1).texture(1F, y1));
            BbsVfxRenderCompat.next(buffer.vertex(m, px + rx * hw, y0, pz + rz * hw).color(1F, 1F, 1F, a0).texture(1F, y0));
        }
    }

    private static float vFade(float f)
    {
        float in = Math.min(1F, f / 0.06F);
        float out = Math.min(1F, (1F - f) / 0.15F);

        return in * out;
    }

    private static void ensureScratch(int w, int h)
    {
        if (scratch == null)
        {
            scratch = new Framebuffer();

            Texture color = new Texture();

            color.setSize(2, 2);
            /* LINEAR: the refraction offsets sample between pixels — nearest would shimmer harshly. */
            color.setFilter(GL11.GL_LINEAR);
            color.setWrap(GL13.GL_CLAMP_TO_EDGE);

            scratch.attach(color, GL30.GL_COLOR_ATTACHMENT0);
            scratch.unbind();
        }

        if (scratch.getMainTexture().width != w || scratch.getMainTexture().height != h)
        {
            scratch.resize(w, h);
        }
    }

    /** A standalone FBO + texture holding the snapshotted world depth (BbsVfxFormBlend's pattern). */
    private static void ensureDepth(int w, int h)
    {
        if (depthFbo == -1)
        {
            depthFbo = GL30.glGenFramebuffers();
        }

        if (depthTex == -1)
        {
            depthTex = GlStateManager._genTexture();
        }

        if (depthW != w || depthH != h)
        {
            depthW = w;
            depthH = h;

            GlStateManager._bindTexture(depthTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            GlStateManager._bindTexture(0);

            int prev = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        }
    }
}
