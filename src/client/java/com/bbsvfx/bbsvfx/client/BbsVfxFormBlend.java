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
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2 of the whole-form blend: a post-composite that works under external shader packs and supports the
 * shader-only blend modes (Overlay/Difference/…) which fixed-function GL can't express.
 *
 * <p>A form that needs the post path (a shader mode, or any mode while a pack is active) is captured by
 * {@code FormBlendStateMixin} and its in-world draw cancelled. Here — at {@code onWorldRenderEnd}, after the
 * pack's shading — each is re-rendered in isolation into an offscreen colour buffer ({@link #formFbo}; rgb +
 * coverage in alpha), then composited over the scene with the {@code form_blend} shader (reads scene + form,
 * applies the {@link com.bbsvfx.bbsvfx.forms.BlendMode} formula). Built lazily the impact way.</p>
 *
 * <p>Milestone 1: the form is self-occluded (its own depth) but NOT occluded by world geometry — most actors
 * are in front, world-depth occlusion is a later pass.</p>
 */
public final class BbsVfxFormBlend
{
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("bbsvfx");

    /** Set while re-rendering a captured form, so the capture mixin doesn't recurse on its sub-forms. */
    public static volatile boolean replaying;

    private static ShaderProgram program;
    private static boolean buildTried;

    private static Framebuffer formFbo;
    private static int depthTex = -1;
    private static int depthW;
    private static int depthH;

    private static Framebuffer sceneCopy;

    /** Scene depth snapshotted at WorldRenderEvents.LAST, blitted into the form FBO so forms are occluded
     * by world geometry (not just self-occluded). */
    private static int sceneDepthTex = -1;
    private static int sceneDepthFbo = -1;
    private static int sceneDepthW;
    private static int sceneDepthH;
    private static boolean depthReady;

    /** SmearReplayState snapshot taken at defer time — the deferred replay restores it so the smear
     *  frames render inside the blend composite too (the bridge is long cleared by post-world time). */
    private record Bridge(mchorse.bbs_mod.film.replays.Replay replay, int tick, float transition, int index)
    {}

    private static Bridge captureBridge(IEntity entity)
    {
        return SmearReplayState.has(entity)
            ? new Bridge(SmearReplayState.replay, SmearReplayState.baseTick, SmearReplayState.transition, SmearReplayState.replayIndex)
            : null;
    }

    /** {@code withSmear}: only the WHOLE-MODEL deferred path re-renders the smear inside the replay —
     *  a per-bone group replay must render ONLY its isolated bones (the in-world pass already drew the
     *  smear); re-running the smear there intersects the two hide sets and composites dissolve-holed
     *  copy fragments over the actor (the "body parts vanish" bug). */
    private record Deferred(FormRenderer renderer, Form form, IEntity entity, Matrix4f proj, Matrix4f model, Matrix4f world,
        int light, int color, float transition, int mode, float factor, Bridge bridge, boolean withSmear)
    {}

    /** One per-bone blend group: a mode/strength shared by a set of bones, isolated and composited. */
    public record Group(int mode, float factor, java.util.Set<String> bones)
    {}

    private record PerBone(FormRenderer renderer, Form form, IEntity entity, Matrix4f proj, Matrix4f model, Matrix4f world,
        int light, int color, float transition, java.util.List<String> allBones, java.util.List<Group> groups)
    {}

    private static final List<Deferred> LIST = new ArrayList<>();
    private static final List<PerBone> PER_BONE = new ArrayList<>();

    private BbsVfxFormBlend()
    {}

    /** KNOWN ISSUE (1.21.1): re-rendering the smear inside the whole-model replay loses model parts —
     *  root cause still open. Until fixed, 1.21 replays render without the smear (the pre-smear-in-blend
     *  behaviour: model composites intact, the smear just isn't part of the blend); 1.20.x keeps it. */
    private static final boolean SMEAR_IN_REPLAY = !net.fabricmc.loader.api.FabricLoader.getInstance()
        .getModContainer("minecraft").map((mod) -> mod.getMetadata().getVersion().getFriendlyString().startsWith("1.21")).orElse(false);

    public static void defer(FormRenderer renderer, Form form, IEntity entity, Matrix4f proj, Matrix4f model, Matrix4f world,
        int light, int color, float transition, int mode, float factor)
    {
        LIST.add(new Deferred(renderer, form, entity, proj, model, world, light, color, transition, mode, factor, captureBridge(entity), SMEAR_IN_REPLAY));
    }

    public static void deferPerBone(FormRenderer renderer, Form form, IEntity entity, Matrix4f proj, Matrix4f model, Matrix4f world,
        int light, int color, float transition, java.util.List<String> allBones, java.util.List<Group> groups)
    {
        PER_BONE.add(new PerBone(renderer, form, entity, proj, model, world, light, color, transition, allBones, groups));
    }

    public static boolean hasWork()
    {
        return !LIST.isEmpty() || !PER_BONE.isEmpty();
    }

    /**
     * Snapshots the world depth into our own depth texture, at {@code WorldRenderEvents.LAST}, where the
     * bound framebuffer holds the COMPLETE world depth regardless of renderer (without a shaderpack Sodium
     * copies only colour into the main framebuffer, so by the post pass the main depth is empty) — same
     * reason as {@code BbsVfxProjectionShader.captureDepth}. Only runs when there's a blend form to occlude.
     */
    public static void captureDepth()
    {
        if (!hasWork())
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

        ensureSceneDepth(w, h);

        int boundFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        /* Depth source: the currently-bound world FBO when there is one, else the MAIN framebuffer — on
         * 1.21 the binding at LAST can already be the default backbuffer (0) whose depth is EMPTY (the
         * same guard BbsVfxProjectionShader.captureDepth got in the 1.21 port; missing here it seeded the
         * replay FBO with garbage depth and the depth test cut the composited model into fragments). */
        int srcFbo = boundFbo != 0 ? boundFbo : main.fbo;

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, srcFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneDepthFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, boundFbo);

        depthReady = true;
    }

    private static void build()
    {
        try
        {
            program = new ShaderProgram(factory(), "form_blend", VertexFormats.POSITION_TEXTURE);
        }
        catch (Exception e)
        {
            LOG.error("[form-blend] shader program build failed", e);
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

    public static void render(net.minecraft.client.gl.Framebuffer main)
    {
        if (!hasWork())
        {
            return;
        }

        if (program == null && !buildTried)
        {
            buildTried = true;
            build();
        }

        if (program == null || main == null)
        {
            LIST.clear();
            PER_BONE.clear();
            return;
        }

        int w = main.textureWidth;
        int h = main.textureHeight;

        if (w <= 0 || h <= 0)
        {
            LIST.clear();
            PER_BONE.clear();
            return;
        }

        ensureFbos(w, h);

        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        for (Deferred d : LIST)
        {
            bbsvfx$renderForm(d, w, h);
            bbsvfx$composite(d, main, w, h, prevDraw, prevRead);
        }

        /* Per-bone: one offscreen pass per blend group, showing only that group's bones (hide the rest). */
        for (PerBone pb : PER_BONE)
        {
            for (Group g : pb.groups)
            {
                java.util.Set<String> hide = new java.util.HashSet<>(pb.allBones);
                hide.removeAll(g.bones);

                BbsVfxBlendBoneState.begin(hide);

                Deferred d = new Deferred(pb.renderer, pb.form, pb.entity, pb.proj, pb.model, pb.world, pb.light, pb.color, pb.transition, g.mode, g.factor, null, false);

                try
                {
                    bbsvfx$renderForm(d, w, h);
                    bbsvfx$composite(d, main, w, h, prevDraw, prevRead);
                }
                finally
                {
                    BbsVfxBlendBoneState.end();
                }
            }
        }

        RenderSystem.setProjectionMatrix(oldProj, VertexSorter.BY_DISTANCE);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        /* Restore active texture unit 0 — the composite sampled through Sampler1, leaving the active unit
         * at 1; the desync corrupts the next frame's first actor's skin filter on some drivers. */
        com.mojang.blaze3d.platform.GlStateManager._activeTexture(GL13.GL_TEXTURE0);

        LIST.clear();
        PER_BONE.clear();
        depthReady = false;
    }

    /** Re-render one captured form in isolation into {@link #formFbo} (transparent clear, self-depth). */
    private static void bbsvfx$renderForm(Deferred d, int w, int h)
    {
        /* Seed the form FBO's depth with the world depth (so the form is occluded by world geometry), then
         * clear only colour. Without a captured depth, fall back to self-occlusion (clear depth too). */
        if (depthReady)
        {
            int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneDepthFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, formFbo.id);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        }

        formFbo.bind();
        GL11.glViewport(0, 0, w, h);

        /* A shader pack can leave the REAL GL write masks off after its composite passes (Iris leaves
         * depthMask=false) while GlStateManager's cache still thinks they're on — then the cached calls
         * below no-op, the clear silently does nothing and every fragment fails the depth test (the
         * impact-silhouette "white screen" bug). Reset via BOTH the cache and raw GL. */
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        GlStateManager._disableScissorTest();
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        RenderSystem.clearColor(0F, 0F, 0F, 0F);
        GL11.glClear(depthReady ? GL11.GL_COLOR_BUFFER_BIT : (GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT));

        RenderSystem.setProjectionMatrix(d.proj, VertexSorter.BY_DISTANCE);
        modelViewIdentity();

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        replaying = true;

        /* Same trap as the impact silhouette: with iris on, BBS's model VAO uses the IRIS attribute
         * layout while the bound program either steals the pack's framebuffer (pack-replaced vanilla) or
         * mismatches the layout (BBS core program). Replay down the consistent no-pack path. */
        boolean prevIris = com.bbsvfx.bbsvfx.mixin.client.BBSRenderingIrisAccessor.bbsvfx$getIris();

        com.bbsvfx.bbsvfx.mixin.client.BBSRenderingIrisAccessor.bbsvfx$setIris(false);

        try
        {
            MatrixStack stack = new MatrixStack();
            stack.multiplyPositionMatrix(d.model);

            MatrixStack world = new MatrixStack();

            if (d.world != null)
            {
                world.multiplyPositionMatrix(d.world);
            }

            FormRenderingContext ctx = new FormRenderingContext();

            ctx.type = FormRenderType.ENTITY;
            ctx.entity = d.entity;
            ctx.stack = stack;
            ctx.world = world;
            ctx.light = d.light;
            /* NOT 0 — packed (0,0) is the HURT overlay (red tint); default = no overlay. */
            ctx.overlay = net.minecraft.client.render.OverlayTexture.DEFAULT_UV;
            ctx.color = d.color;
            ctx.transition = d.transition;
            ctx.stencilMap = null;

            /* Whole-model: through FormUtilsClient.render so the smear redirect runs — blend + smear on
             * the same actor composites the smear copies too (bridge restored for the arc). Per-bone
             * groups render their isolated bones directly — the in-world pass already drew the smear. */
            if (d.withSmear)
            {
                if (d.bridge != null)
                {
                    SmearReplayState.begin(d.bridge.replay(), d.entity, d.bridge.tick(), d.bridge.transition(), d.bridge.index());
                }

                mchorse.bbs_mod.forms.FormUtilsClient.render(d.form, ctx);
            }
            else
            {
                d.renderer.render(ctx);
            }
        }
        catch (Throwable e)
        {
            bbsvfx$logReplayError("blend", e);
        }
        finally
        {
            SmearReplayState.end();
            SmearRenderState.end();
            SmearRenderState.linesPending = false;
            replaying = false;
            com.bbsvfx.bbsvfx.mixin.client.BBSRenderingIrisAccessor.bbsvfx$setIris(prevIris);
        }

        modelViewPop();
    }

    /** Rate-limited log for exceptions swallowed by the replay — a silent catch here once hid the
     *  root cause of a rendering bug for a whole debugging session. */
    private static long lastReplayErrorLog;

    static void bbsvfx$logReplayError(String where, Throwable e)
    {
        long now = System.currentTimeMillis();

        if (now - lastReplayErrorLog > 1000L)
        {
            lastReplayErrorLog = now;
            System.err.println("[bbsvfx] offscreen " + where + " replay threw: " + e);
            e.printStackTrace();
        }
    }

    /** Composite the isolated form ({@link #formFbo}) over the scene with the blend shader. */
    private static void bbsvfx$composite(Deferred d, net.minecraft.client.gl.Framebuffer main, int w, int h, int prevDraw, int prevRead)
    {
        /* Scene colour into a copy we can sample while writing to the main buffer. */
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneCopy.id);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);

        main.beginWrite(true);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> program);
        RenderSystem.setShaderTexture(0, formFbo.getMainTexture().id);
        RenderSystem.setShaderTexture(1, sceneCopy.getMainTexture().id);

        program.getUniformOrDefault("BlendMode").set(d.mode);
        program.getUniformOrDefault("Factor").set(d.factor);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(-1F, -1F, 0F).texture(0F, 0F));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(1F, -1F, 0F).texture(1F, 0F));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(1F, 1F, 0F).texture(1F, 1F));
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(buffer.vertex(-1F, 1F, 0F).texture(0F, 1F));
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static void modelViewIdentity()
    {
        BbsVfxRenderCompat.pushIdentityModelView();
    }

    private static void modelViewPop()
    {
        BbsVfxRenderCompat.popModelView();
    }

    private static void ensureFbos(int w, int h)
    {
        if (formFbo == null)
        {
            formFbo = new Framebuffer();

            Texture color = new Texture();

            color.setSize(2, 2);
            color.setFilter(GL11.GL_NEAREST);
            color.setWrap(GL13.GL_CLAMP_TO_EDGE);

            formFbo.attach(color, GL30.GL_COLOR_ATTACHMENT0);
            formFbo.unbind();
        }

        if (formFbo.getMainTexture().width != w || formFbo.getMainTexture().height != h)
        {
            formFbo.resize(w, h);
        }

        ensureFormDepth(w, h);

        if (sceneCopy == null)
        {
            sceneCopy = new Framebuffer();

            Texture color = new Texture();

            color.setSize(2, 2);
            color.setFilter(GL11.GL_NEAREST);
            color.setWrap(GL13.GL_CLAMP_TO_EDGE);

            sceneCopy.attach(color, GL30.GL_COLOR_ATTACHMENT0);
            sceneCopy.unbind();
        }

        if (sceneCopy.getMainTexture().width != w || sceneCopy.getMainTexture().height != h)
        {
            sceneCopy.resize(w, h);
        }
    }

    /** A depth texture attached to {@link #formFbo} so the form self-occludes (cleared each form). */
    private static void ensureFormDepth(int w, int h)
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

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, formFbo.id);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        }
    }

    /** A standalone FBO holding the snapshotted scene depth, blittable into the form FBO. */
    private static void ensureSceneDepth(int w, int h)
    {
        if (sceneDepthFbo == -1)
        {
            sceneDepthFbo = GL30.glGenFramebuffers();
        }

        if (sceneDepthTex == -1)
        {
            sceneDepthTex = GlStateManager._genTexture();
        }

        if (sceneDepthW != w || sceneDepthH != h)
        {
            sceneDepthW = w;
            sceneDepthH = h;

            GlStateManager._bindTexture(sceneDepthTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            GlStateManager._bindTexture(0);

            int prev = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneDepthFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, sceneDepthTex, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        }
    }
}
