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
 * Screen-space raymarched energy BEAM column — a fullscreen additive raymarch through a warm-core / orange-
 * rim energy field with helix ribbons, depth-occluded (railgun/nuke technique, same deferred infra as
 * {@link DomeVolume}/{@link SmokeVolume}). Replaces the beam's self-overlapping translucent core geometry
 * with a clean depth-correct volume. The column base + axis + radial basis come from the beam's draw matrix.
 */
public final class BeamVolume
{
    public static ShaderProgram PROGRAM;

    private record Entry(Matrix4f mat, float len, float radius, float softness, float opacity, float emit,
        int helixOn, int helixCount, float helixRadius, float helixTurns, float helixThickness, float helixSpin,
        float st, float colR, float colG, float colB, float rimR, float rimG, float rimB)
    {}

    private static final List<Entry> QUEUE = new ArrayList<>();

    private static Framebuffer scratch;

    private static int depthFbo = -1;
    private static int depthTex = -1;
    private static int depthW;
    private static int depthH;
    private static boolean depthReady;
    private static final Matrix4f PROJ = new Matrix4f();

    private BeamVolume()
    {}

    public static void queue(Matrix4f mat, float len, float radius, float softness, float opacity, float emit,
        boolean helixOn, int helixCount, float helixRadius, float helixTurns, float helixThickness, float helixSpin,
        float st, float colR, float colG, float colB, float rimR, float rimG, float rimB)
    {
        /* Deferred — never queue from a foreign pass (see {@link BbsVfxForeignPass}). */
        if (BbsVfxForeignPass.isActive())
        {
            return;
        }

        QUEUE.add(new Entry(new Matrix4f(mat), len, radius, softness, opacity, emit,
            helixOn ? 1 : 0, helixCount, helixRadius, helixTurns, helixThickness, helixSpin,
            st, colR, colG, colB, rimR, rimG, rimB));
    }

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

        Matrix4f invProj = new Matrix4f(PROJ).invert();

        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        for (Entry e : entries)
        {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scratch.id);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);

            main.beginWrite(true);

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

            Vector3f base = new Vector3f(e.mat.m30(), e.mat.m31(), e.mat.m32());
            Vector3f axis = new Vector3f(e.mat.m10(), e.mat.m11(), e.mat.m12()).normalize();
            Vector3f tanU = new Vector3f(e.mat.m00(), e.mat.m01(), e.mat.m02()).normalize();
            Vector3f tanV = new Vector3f(e.mat.m20(), e.mat.m21(), e.mat.m22()).normalize();

            PROGRAM.getUniformOrDefault("uInvProj").set(invProj);
            PROGRAM.getUniformOrDefault("uBase").set(base.x, base.y, base.z);
            PROGRAM.getUniformOrDefault("uAxis").set(axis.x, axis.y, axis.z);
            PROGRAM.getUniformOrDefault("uTanU").set(tanU.x, tanU.y, tanU.z);
            PROGRAM.getUniformOrDefault("uTanV").set(tanV.x, tanV.y, tanV.z);
            PROGRAM.getUniformOrDefault("uLen").set(e.len);
            PROGRAM.getUniformOrDefault("uRadius").set(e.radius);
            PROGRAM.getUniformOrDefault("uSoftness").set(Math.max(0.05F, e.softness));
            PROGRAM.getUniformOrDefault("uOpacity").set(e.opacity);
            PROGRAM.getUniformOrDefault("uEmit").set(e.emit);
            PROGRAM.getUniformOrDefault("uHelixOn").set(e.helixOn);
            PROGRAM.getUniformOrDefault("uHelixCount").set(e.helixCount);
            PROGRAM.getUniformOrDefault("uHelixRadius").set(e.helixRadius);
            PROGRAM.getUniformOrDefault("uHelixTurns").set(e.helixTurns);
            PROGRAM.getUniformOrDefault("uHelixThickness").set(e.helixThickness);
            PROGRAM.getUniformOrDefault("uHelixSpin").set(e.helixSpin);
            PROGRAM.getUniformOrDefault("uTime").set(e.st);
            PROGRAM.getUniformOrDefault("uColor").set(e.colR, e.colG, e.colB);
            PROGRAM.getUniformOrDefault("uRim").set(e.rimR, e.rimG, e.rimB);

            BufferBuilder buffer = BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);

            BbsVfxRenderCompat.next(buffer.vertex(-1F, -1F, 0F).texture(0F, 0F));
            BbsVfxRenderCompat.next(buffer.vertex(1F, -1F, 0F).texture(1F, 0F));
            BbsVfxRenderCompat.next(buffer.vertex(1F, 1F, 0F).texture(1F, 1F));
            BbsVfxRenderCompat.next(buffer.vertex(-1F, 1F, 0F).texture(0F, 1F));
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        }

        RenderSystem.enableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    private static void ensureScratch(int w, int h)
    {
        if (scratch == null)
        {
            scratch = new Framebuffer();

            Texture color = new Texture();

            color.setSize(2, 2);
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
