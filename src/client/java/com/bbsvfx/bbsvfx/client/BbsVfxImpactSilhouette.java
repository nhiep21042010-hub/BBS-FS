package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.texture.Texture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Impact-frame silhouette pass (redesign S1). When an {@link com.bbsvfx.bbsvfx.camera.ImpactClip} runs the
 * silhouette layer, every top-level actor form is captured during the world render
 * ({@code ImpactSilhouetteCaptureMixin}) and re-rendered here, in isolation, into one offscreen colour
 * buffer — the alpha channel becomes the combined actor COVERAGE (actors mutually occlude via a shared
 * depth buffer; the world background is dropped). The impact post shader then reads this coverage and
 * fills it with the flat silhouette colour over a flat negative-space background.
 *
 * <p>Reuses the offscreen replay mechanism of {@link BbsVfxFormBlend} (same matrices / FormRenderingContext
 * rebuild), but accumulates all actors into a single buffer and needs no per-form composite (the fill is
 * done in {@code impact.fsh}).</p>
 */
public final class BbsVfxImpactSilhouette
{
    /** Set while re-rendering captured forms, so the capture mixin doesn't recurse on sub-forms. */
    public static volatile boolean replaying;

    private static Framebuffer fbo;
    private static int depthTex = -1;
    private static int depthW;
    private static int depthH;

    /** The SmearReplayState bridge snapshot (nullable replay) so the replay can re-run the arc smear —
     *  the bridge is per-actor-render and long cleared by the time this post pass runs. */
    private record Cap(FormRenderer renderer, Form form, IEntity entity, Matrix4f proj, Matrix4f model,
        Matrix4f world, int light, int color, float transition,
        mchorse.bbs_mod.film.replays.Replay bridgeReplay, int bridgeTick, float bridgeTransition, int bridgeIndex)
    {}

    private static final List<Cap> LIST = new ArrayList<>();

    private BbsVfxImpactSilhouette()
    {}

    public static void defer(FormRenderer renderer, Form form, IEntity entity, Matrix4f proj, Matrix4f model,
        Matrix4f world, int light, int color, float transition)
    {
        boolean bridged = SmearReplayState.has(entity);

        LIST.add(new Cap(renderer, form, entity, proj, model, world, light, color, transition,
            bridged ? SmearReplayState.replay : null,
            bridged ? SmearReplayState.baseTick : 0,
            bridged ? SmearReplayState.transition : 0F,
            bridged ? SmearReplayState.replayIndex : -1));
    }

    /** The coverage texture (alpha = actor coverage), or -1 if not built yet. */
    public static int textureId()
    {
        return fbo != null ? fbo.getMainTexture().id : -1;
    }

    public static boolean ready()
    {
        return fbo != null;
    }

    /**
     * Render all captured actor forms into the silhouette buffer (transparent clear, alpha = coverage;
     * actors mutually occlude via the shared depth). Always clears, so an empty capture list yields a fully
     * transparent buffer (= pure negative space, no subject). Called at {@code onWorldRenderEnd} before the
     * impact composite.
     */
    public static void render()
    {
        net.minecraft.client.gl.Framebuffer main = MinecraftClient.getInstance().getFramebuffer();

        if (main == null)
        {
            LIST.clear();
            return;
        }

        int w = main.textureWidth;
        int h = main.textureHeight;

        if (w <= 0 || h <= 0)
        {
            LIST.clear();
            return;
        }

        ensureFbo(w, h);

        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        fbo.bind();
        GL11.glViewport(0, 0, w, h);

        /* A shader pack can leave the REAL GL write masks off after its composite passes (Iris leaves
         * depthMask=false) while GlStateManager's cache still thinks they're on — the clear then silently
         * does nothing and every fragment fails the depth test against stale garbage (the "white screen
         * under shaders" bug). Reset via BOTH the cache (keeps GlStateManager coherent) and raw GL (the
         * pack changed the real state behind the cache's back, so the cached call alone no-ops). */
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        GlStateManager._disableScissorTest();
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        RenderSystem.clearColor(0F, 0F, 0F, 0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        replaying = true;

        /* Replay as if NO shader pack were active ("white screen" bug): with iris on, BBS's model VAO
         * sets up the IRIS vertex-attribute layout while the program is either the pack-replaced vanilla
         * one (which re-binds the PACK's framebuffer on use, stealing the draw) or BBS's own core program
         * (whose attribute layout then mismatches — alpha reads 0, everything discards). The plain no-pack
         * path is consistent end-to-end and packs can't touch it. */
        boolean prevIris = com.bbsvfx.bbsvfx.mixin.client.BBSRenderingIrisAccessor.bbsvfx$getIris();

        com.bbsvfx.bbsvfx.mixin.client.BBSRenderingIrisAccessor.bbsvfx$setIris(false);

        try
        {
            for (Cap c : LIST)
            {
                RenderSystem.setProjectionMatrix(c.proj, VertexSorter.BY_DISTANCE);
                modelViewIdentity();

                try
                {
                    MatrixStack stack = new MatrixStack();
                    stack.multiplyPositionMatrix(c.model);

                    MatrixStack world = new MatrixStack();

                    if (c.world != null)
                    {
                        world.multiplyPositionMatrix(c.world);
                    }

                    FormRenderingContext ctx = new FormRenderingContext();

                    ctx.type = FormRenderType.ENTITY;
                    ctx.entity = c.entity;
                    ctx.stack = stack;
                    ctx.world = world;
                    ctx.light = c.light;
                    /* NOT 0 — packed (0,0) is the HURT overlay (red tint); default = no overlay. */
                    ctx.overlay = net.minecraft.client.render.OverlayTexture.DEFAULT_UV;
                    ctx.color = c.color;
                    ctx.transition = c.transition;
                    ctx.stencilMap = null;

                    /* Through FormUtilsClient.render (NOT renderer.render directly) so the smear-frame
                     * redirect runs and the trail shows up in the silhouette too; restore the captured
                     * SmearReplayState bridge — the arc smear re-poses through it. */
                    if (c.bridgeReplay != null)
                    {
                        SmearReplayState.begin(c.bridgeReplay, c.entity, c.bridgeTick, c.bridgeTransition, c.bridgeIndex);
                    }

                    mchorse.bbs_mod.forms.FormUtilsClient.render(c.form, ctx);
                }
                catch (Throwable e)
                {
                    BbsVfxFormBlend.bbsvfx$logReplayError("silhouette", e);
                }
                finally
                {
                    SmearReplayState.end();
                    SmearRenderState.end();
                    SmearRenderState.linesPending = false;
                    modelViewPop();
                }
            }
        }
        finally
        {
            replaying = false;
            com.bbsvfx.bbsvfx.mixin.client.BBSRenderingIrisAccessor.bbsvfx$setIris(prevIris);
        }

        RenderSystem.setProjectionMatrix(oldProj, VertexSorter.BY_DISTANCE);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        LIST.clear();
    }

    private static void modelViewIdentity()
    {
        BbsVfxRenderCompat.pushIdentityModelView();
    }

    private static void modelViewPop()
    {
        BbsVfxRenderCompat.popModelView();
    }

    private static void ensureFbo(int w, int h)
    {
        if (fbo == null)
        {
            fbo = new Framebuffer();

            Texture color = new Texture();

            color.setSize(2, 2);
            color.setFilter(GL11.GL_NEAREST);
            color.setWrap(GL13.GL_CLAMP_TO_EDGE);

            fbo.attach(color, GL30.GL_COLOR_ATTACHMENT0);
            fbo.unbind();
        }

        if (fbo.getMainTexture().width != w || fbo.getMainTexture().height != h)
        {
            fbo.resize(w, h);
        }

        ensureDepth(w, h);
    }

    /** A depth texture attached to the silhouette FBO so actors mutually occlude (cleared each frame). */
    private static void ensureDepth(int w, int h)
    {
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

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo.id);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        }
    }
}
