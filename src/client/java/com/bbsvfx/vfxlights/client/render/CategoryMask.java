package com.bbsvfx.vfxlights.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.vfxlights.light.Light;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;

/**
 * The PACK backend's half of the replay-category (groups) filter: a screen-space map saying WHICH
 * category of actor, if any, covers each pixel — the category registry's index in the red channel,
 * 0 where no actor is drawn.
 *
 * <p>The vanilla composite shades one light per pass and can bind a per-light mask (see GroupMask);
 * the pack shades EVERY light in a single pass, so per-pixel it needs the category itself, and each
 * lamp carries its allowed set as a bitmask in the SSBO (slot [17].x, written by
 * {@code PackLightUploader} from the registry built here).</p>
 *
 * <p><b>Why stencil.</b> The index is a per-ACTOR constant, and a form's own shader writes whatever
 * colours it likes — no core-GL blend mode replaces the fragment colour with a draw-level constant,
 * and logic ops (which could) were removed from the core profile. The stencil buffer is the one
 * per-fragment slot the actor's shader cannot touch: pass 1 stamps the index there with the usual
 * nearest-wins depth, pass 2 resolves it into the R8 texture with one flat quad per used index.</p>
 *
 * <p><b>24 categories, not 32.</b> The SSBO is float-based and GLSL 120 packs (Complementary) have
 * no uints to decode raw bits into, so the bitmask rides a float as an exact integer — and exact
 * integers stop at 2^24. Categories past the cap clamp into index 24 (they share its bit).</p>
 *
 * <p>Captured at LAST, like CharacterMask: the pack shades next frame with the previous frame's
 * light list, and this map is consumed with the same one-frame latency, so lights and categories
 * always come from the same frame. Skipped entirely unless a patched pack is active (the fallback
 * composite uses GroupMask instead).</p>
 */
public final class CategoryMask
{
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Exact float integers end at 2^24, so 24 bits is all the bitmask can carry. */
    private static final int MAX_CATEGORIES = 24;
    /** The no-filter bitmask: every category bit set. Kept as a float literal for the SSBO. */
    private static final float ALL_GROUPS = 16777215.0F;

    /** This frame's category registry: name -> 1-based index, insertion-ordered. Read by the uploader. */
    private static final java.util.Map<String, Integer> categories = new java.util.LinkedHashMap<>();

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("vfxlights");

    private static int fbo = -1;
    private static int colorTex = -1;
    private static int depthStencilTex = -1;
    private static int width;
    private static int height;
    private static boolean incompleteLogged;

    private CategoryMask()
    {
    }

    /** The R8 category-index texture, or -1 before the first capture. Bound as vfxGroupTex. */
    public static int getColorTexture()
    {
        return colorTex;
    }

    /**
     * The lamp's allowed-category bitmask as an exact float integer for SSBO slot [17].x: bit
     * (index - 1) set per group found in the frame's registry. Empty groups = every bit (no filter);
     * groups with no actors this frame simply set no bit. Categories clamped at the cap share bit 23.
     */
    public static float groupBits(Light light)
    {
        if (!light.groupFilter || light.groups.isEmpty())
        {
            return ALL_GROUPS;
        }

        int bits = 0;

        for (String group : light.groups)
        {
            Integer index = categories.get(group);

            if (index != null)
            {
                bits |= 1 << (index - 1);
            }
        }

        return (float) bits;
    }

    private static void deleteFbo()
    {
        if (fbo != -1)
        {
            GL30.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(colorTex);
            GL11.glDeleteTextures(depthStencilTex);
            fbo = -1;
            colorTex = -1;
            depthStencilTex = -1;
        }
    }

    private static void ensureFbo(int w, int h)
    {
        if (fbo != -1 && width == w && height == h)
        {
            return;
        }

        deleteFbo();

        width = w;
        height = h;

        colorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, w, h, 0,
            GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);

        depthStencilTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthStencilTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, w, h, 0,
            GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (java.nio.ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, colorTex, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
            GL11.GL_TEXTURE_2D, depthStencilTex, 0);

        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE
            && !incompleteLogged)
        {
            incompleteLogged = true;
            LOG.warn("Category mask framebuffer incomplete; the groups filter is off for the pack");
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** At WorldRenderEvents.LAST: stamp this frame's actor category map for the patched pack. */
    public static void capture(WorldRenderContext context)
    {
        categories.clear();

        if (!com.bbsvfx.vfxlights.VfxLightsModule.isEnabled())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.world == null)
        {
            return;
        }

        /* Only a patched pack consumes the map; vanilla and unpatched packs use GroupMask instead. */
        if (!com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse()
            || !com.bbsvfx.vfxlights.client.iris.PackPatcher.isCurrentPackPatched())
        {
            return;
        }

        Framebuffer main = mc.getFramebuffer();

        ensureFbo(main.textureWidth, main.textureHeight);

        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        IntBuffer viewport = BufferUtils.createIntBuffer(4);

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, width, height);

        /* Clear needs the channels writable: color mask on, depth mask on, stencil mask full. */
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        GL11.glStencilMask(0xFF);
        GL11.glClearColor(0F, 0F, 0F, 0F);
        GL11.glClearDepth(1.0);
        GL11.glClearStencil(0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

        boolean previousIris = com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$getIris();

        com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$setIris(false);

        /* This re-renders the same forms the world render did; the collector's gate keeps them out
         * of the light registry — a lamp seen here is not a new light. */
        CharacterMask.setCapturing(true);
        com.bbsvfx.bbsvfx.client.BbsVfxForeignPass.enter("catMask");

        try
        {
            /* Pass 1 — the stamp. Depth does nearest-wins among actors; colour stays off, the index
             * goes to stencil. UNCATEGORISED actors are drawn too (reserved ref 25): an actor
             * without a category must still occlude the ones behind it, and must read differently
             * from a block, or group filters can never exclude it. */
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);

            stampActors((ClientWorld) mc.world, context.matrixStack(), context.camera().getPos(),
                mc.getTickDelta());
        }
        catch (Throwable ignored)
        {
            /* A misbehaving renderer costs this frame's map, not the frame. */
        }
        finally
        {
            CharacterMask.setCapturing(false);
            com.bbsvfx.bbsvfx.client.BbsVfxForeignPass.exit();
        }

        /* Pass 2 — resolve stencil into the R8 colour, one flat quad per used index. The vanilla
         * position_colour program is fogless and applies ColorModulator, so with white modulator the
         * written value is exactly index/255. The iris flag must stay OFF through this pass too:
         * under a pack the pack-replaced program rebinds the PACK's framebuffer, and the resolve
         * quads land there instead of our R8 — the mask reads all zeros and every group filter
         * silently no-ops. */
        float[] shaderColor = RenderSystem.getShaderColor();
        Matrix4f prevProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        MatrixStack modelView = RenderSystem.getModelViewStack();

        modelView.push();
        modelView.loadIdentity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().identity(), VertexSorter.BY_DISTANCE);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.colorMask(true, true, true, true);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        int used = Math.min(categories.size(), MAX_CATEGORIES);

        for (int i = 1; i <= used; i++)
        {
            float indexColor = i / 255.0F;

            GL11.glStencilFunc(GL11.GL_EQUAL, i, 0xFF);

            BufferBuilder buffer = Tessellator.getInstance().getBuffer();

            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            buffer.vertex(-1F, -1F, 0F).color(indexColor, 0F, 0F, 1F).next();
            buffer.vertex(1F, -1F, 0F).color(indexColor, 0F, 0F, 1F).next();
            buffer.vertex(1F, 1F, 0F).color(indexColor, 0F, 0F, 1F).next();
            buffer.vertex(-1F, 1F, 0F).color(indexColor, 0F, 0F, 1F).next();

            BufferRenderer.drawWithGlobalProgram(buffer.end());
        }

        /* The reserved uncategorised index, so filtered lamps can tell "actor with no category"
         * from "block" (both were stencil 0 before). */
        {
            float indexColor = 25 / 255.0F;

            GL11.glStencilFunc(GL11.GL_EQUAL, 25, 0xFF);

            BufferBuilder buffer = Tessellator.getInstance().getBuffer();

            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            buffer.vertex(-1F, -1F, 0F).color(indexColor, 0F, 0F, 1F).next();
            buffer.vertex(1F, -1F, 0F).color(indexColor, 0F, 0F, 1F).next();
            buffer.vertex(1F, 1F, 0F).color(indexColor, 0F, 0F, 1F).next();
            buffer.vertex(-1F, 1F, 0F).color(indexColor, 0F, 0F, 1F).next();

            BufferRenderer.drawWithGlobalProgram(buffer.end());
        }

        GL11.glDisable(GL11.GL_STENCIL_TEST);

        modelView.pop();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(prevProjection, VertexSorter.BY_DISTANCE);
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);

        com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$setIris(previousIris);

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);

        GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        main.beginWrite(false);
    }

    private static void stampActors(ClientWorld world, MatrixStack matrices, Vec3d cam, float tickDelta)
    {
        for (mchorse.bbs_mod.film.BaseFilmController controller : CharacterMask.controllers())
        {
            mchorse.bbs_mod.film.Film film = controller.film;

            if (film == null)
            {
                continue;
            }

            List<mchorse.bbs_mod.film.replays.Replay> replays = film.replays.getList();

            /* Form replays, keyed by replay id. Actor replays skip this path — their visible
             * body is the real MC entity, resolved through getActors() below. */
            for (Map.Entry<String, mchorse.bbs_mod.forms.entities.IEntity> entry
                : controller.getEntities().entrySet())
            {
                mchorse.bbs_mod.film.replays.Replay replay =
                    com.bbsvfx.vfxlights.client.light.ActorCategories.replayById(controller, entry.getKey());

                if (replay == null)
                {
                    continue;
                }

                if (replay.actor.get())
                {
                    continue;
                }

                /* The film's own sub-tick time (0 while paused — the ShadowMapper pause-tremor
                 * lesson), or the frozen stub's stamp sweeps at the 20 Hz tick sawtooth. */
                mchorse.bbs_mod.forms.entities.IEntity stub = entry.getValue();
                float transition = ((com.bbsvfx.vfxlights.mixin.client.BaseFilmControllerAccessor)
                    controller).vfxlights$getTransition(stub, tickDelta);

                stamp(stub, categoryIndex(replay), matrices, cam, transition);
            }

            /* Actor replays: replay id -> the real MC entity (its morph carries the form). */
            Map<String, Integer> actors = controller.getActors();

            if (actors == null)
            {
                continue;
            }

            for (Map.Entry<String, Integer> entry : actors.entrySet())
            {
                mchorse.bbs_mod.film.replays.Replay replay = replayById(replays, entry.getKey());

                if (replay == null)
                {
                    continue;
                }

                net.minecraft.entity.Entity entity = world.getEntityById(entry.getValue());

                if (entity == null)
                {
                    continue;
                }

                stamp(new mchorse.bbs_mod.forms.entities.MCEntity(entity), categoryIndex(replay),
                    matrices, cam, tickDelta);
            }
        }
    }

    /**
     * The actor's 1-based category index, registering the category on first sight. UNCATEGORISED
     * actors get the reserved NO_CATEGORY index: stencil 0 is "block or background", and without a
     * distinct index an uncategorised actor was indistinguishable from a block — group-filtered
     * lamps kept lighting it (the "filter does nothing" bug).
     */
    private static final int NO_CATEGORY = 25;

    private static int categoryIndex(mchorse.bbs_mod.film.replays.Replay replay)
    {
        String name = mchorse.bbs_mod.film.replays.Replay.normalizeCategory(replay.category.get());

        if (name.isEmpty())
        {
            return NO_CATEGORY;
        }

        Integer index = categories.get(name);

        if (index == null)
        {
            /* Past the cap everything clamps into the last index and shares its bit. */
            index = Math.min(categories.size() + 1, MAX_CATEGORIES);
            categories.put(name, index);
        }

        return index;
    }

    private static mchorse.bbs_mod.film.replays.Replay replayById(
        List<mchorse.bbs_mod.film.replays.Replay> replays, String id)
    {
        for (mchorse.bbs_mod.film.replays.Replay replay : replays)
        {
            if (replay.getId().equals(id))
            {
                return replay;
            }
        }

        return null;
    }

    private static void stamp(mchorse.bbs_mod.forms.entities.IEntity actor, int category,
        MatrixStack matrices, Vec3d cam, float tickDelta)
    {
        mchorse.bbs_mod.forms.forms.Form form = actor.getForm();

        if (form == null)
        {
            return;
        }

        try
        {
            GL11.glStencilFunc(GL11.GL_ALWAYS, category, 0xFF);

            Matrix4f model = mchorse.bbs_mod.film.FilmMatrices.getMatrixForRenderWithRotation(
                actor, cam.x, cam.y, cam.z, tickDelta);

            matrices.push();
            matrices.multiplyPositionMatrix(model);

            mchorse.bbs_mod.forms.renderers.FormRenderingContext context =
                new mchorse.bbs_mod.forms.renderers.FormRenderingContext()
                    .set(mchorse.bbs_mod.forms.renderers.FormRenderType.ENTITY, actor,
                        matrices, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, tickDelta);

            mchorse.bbs_mod.forms.FormUtilsClient.render(form, context);
            matrices.pop();
        }
        catch (Throwable ignored)
        {
        }
    }
}
