package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.texture.Texture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.bbsvfx.client.BbsVfxProjectionState.Projector;

/**
 * Casts each captured label "projector" onto the scene as a slide projector. Runs at the end of the world
 * render (after an external shaderpack's shading — works under Iris/OptiFine, unlike a mid-world custom
 * shader). For every projector: reconstruct each pixel's view-space position from the depth buffer,
 * transform it into the projector's clip space, and where it lands inside the projector frustum sample the
 * text texture and composite it over the scene.
 *
 * <p>The world depth is snapshotted earlier, at {@code WorldRenderEvents.LAST} ({@link #captureDepth}),
 * where the bound framebuffer holds the complete world depth — without a shaderpack Sodium renders the
 * terrain into its own buffer and copies only colour into the main framebuffer, so by this post pass the
 * main depth is empty; grabbing it at LAST is what makes projection work both with and without shaders.
 * The scene colour is blitted into a scratch texture here per projector (can't sample + write the same
 * attachment).</p>
 */
public final class BbsVfxProjectionShader
{
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("bbsvfx");

    private static ShaderProgram program;
    private static boolean buildTried;
    private static boolean depthReady;

    private static Framebuffer scratch;
    private static int depthTex = -1;
    private static int handDepthTex = -1;
    private static int handFbo = -1;
    private static int depthW;
    private static int depthH;

    private BbsVfxProjectionShader()
    {}

    public static void build()
    {
        if (program != null)
        {
            program.close();
            program = null;
        }

        try
        {
            program = new ShaderProgram(factory(), "projection", VertexFormats.POSITION_TEXTURE);
        }
        catch (Exception e)
        {
            LOG.error("[projection] shader program build failed", e);
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
     * Snapshots the world depth into our own depth texture. Called at {@code WorldRenderEvents.LAST}, where
     * the currently-bound framebuffer holds the COMPLETE world depth regardless of renderer — crucially this
     * is the only reliable place to get it without a shaderpack (Sodium renders terrain into its own buffer
     * and copies only colour into the main framebuffer, so by the post pass the main depth is empty).
     */
    public static void captureDepth()
    {
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

        ensureScratch(w, h);

        int boundFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        /* Depth source: the currently-bound world FBO when there is one (Sodium/Iris render there and
         * only copy colour out), else the MAIN framebuffer — on 1.21 the binding at LAST can already be
         * the default backbuffer (0), whose depth is empty. */
        int srcFbo = boundFbo != 0 ? boundFbo : main.fbo;

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, srcFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scratch.id);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, boundFbo);

        depthReady = true;
    }

    public static void render(net.minecraft.client.gl.Framebuffer main)
    {
        if (BbsVfxProjectionState.isEmpty())
        {
            return;
        }

        if (program == null && !buildTried)
        {
            buildTried = true;
            build();
        }

        if (program == null || main == null || !depthReady)
        {
            BbsVfxProjectionState.reset();
            return;
        }

        int w = main.textureWidth;
        int h = main.textureHeight;

        if (w <= 0 || h <= 0)
        {
            BbsVfxProjectionState.reset();
            return;
        }

        Framebuffer fb = ensureScratch(w, h);

        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        /* First-person hand occlusion: the hand is drawn INSIDE renderWorld after a depth clear, so by
         * this post pass the main depth holds hand-only depth (background = 1.0). Snapshot it — the
         * shader skips pixels where it is closer than the world snapshot (i.e. covered by the hand).
         * With no hand pass the main depth equals the world snapshot and nothing is masked. */
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, handFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);

        for (Projector projector : BbsVfxProjectionState.list())
        {
            /* Current scene colour into the scratch colour texture (so projectors stack). */
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.id);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);

            main.beginWrite(true);

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.setShader(() -> program);
            RenderSystem.setShaderTexture(0, fb.getMainTexture().id);
            RenderSystem.setShaderTexture(1, depthTex);
            RenderSystem.setShaderTexture(2, projector.textureId());
            RenderSystem.setShaderTexture(3, handDepthTex);

            program.getUniformOrDefault("InvCameraVP").set(projector.invCameraVP());
            program.getUniformOrDefault("ProjectorVP").set(projector.projectorVP());
            program.getUniformOrDefault("ProjForward").set(projector.fx(), projector.fy(), projector.fz());
            program.getUniformOrDefault("BlendMode").set(projector.blendMode());
            program.getUniformOrDefault("Fade").set(projector.fade());
            program.getUniformOrDefault("Tint").set(projector.r(), projector.g(), projector.b(), projector.a());

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
            com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(-1F, -1F, 0F).texture(0F, 0F));
            com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(1F, -1F, 0F).texture(1F, 0F));
            com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(1F, 1F, 0F).texture(1F, 1F));
            com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(-1F, 1F, 0F).texture(0F, 1F));
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();

        /* Restore active texture unit 0 (this pass sampled through Sampler2). See BbsVfxImpactShader. */
        com.mojang.blaze3d.platform.GlStateManager._activeTexture(GL13.GL_TEXTURE0);

        BbsVfxProjectionState.reset();
    }

    private static Framebuffer ensureScratch(int w, int h)
    {
        if (scratch == null)
        {
            scratch = new Framebuffer();

            Texture texture = new Texture();

            texture.setSize(2, 2);
            texture.setFilter(GL11.GL_NEAREST);
            texture.setWrap(GL13.GL_CLAMP_TO_EDGE);

            scratch.attach(texture, GL30.GL_COLOR_ATTACHMENT0);
            scratch.unbind();
        }

        if (scratch.getMainTexture().width != w || scratch.getMainTexture().height != h)
        {
            scratch.resize(w, h);
        }

        ensureDepth(w, h);

        return scratch;
    }

    /** Our own depth texture, attached to the scratch FBO so the scene depth can be blitted into it. */
    private static void ensureDepth(int w, int h)
    {
        if (depthTex == -1)
        {
            depthTex = GlStateManager._genTexture();
        }

        if (handDepthTex == -1)
        {
            handDepthTex = GlStateManager._genTexture();
        }

        if (handFbo == -1)
        {
            handFbo = GL30.glGenFramebuffers();
        }

        if (depthW != w || depthH != h)
        {
            depthW = w;
            depthH = h;

            allocDepthTexture(depthTex, w, h);
            allocDepthTexture(handDepthTex, w, h);

            int prev = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scratch.id);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, handFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, handDepthTex, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        }
    }

    private static void allocDepthTexture(int id, int w, int h)
    {
        GlStateManager._bindTexture(id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GlStateManager._bindTexture(0);
    }
}
