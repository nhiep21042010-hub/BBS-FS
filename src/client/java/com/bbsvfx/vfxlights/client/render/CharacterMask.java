package com.bbsvfx.vfxlights.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.client.world.ClientWorld;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * A screen-space depth of ONLY the film's characters — actors and model blocks — rendered from the
 * camera. Cel shading reads it to shade characters and leave the world alone: a pixel is a character
 * when this depth is written AND is no further than the scene depth (so a character behind a wall does
 * not paint the wall).
 *
 * <p>Depth, not colour, because a form's own textures write arbitrary colours — depth coverage is
 * colour-independent and free. Same actor-render recipe the shadow pass proved under Iris (iris flag
 * off so the pack cannot rebind our target mid-draw).</p>
 */
public final class CharacterMask
{
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Model blocks within this many blocks of the camera are masked; actors are never range-culled. */
    private static final double MODEL_BLOCK_RANGE = 64.0;

    private static SimpleFramebuffer fbo;
    private static int width;
    private static int height;
    private static boolean present;
    /** True during {@link #capture}'s re-render — the gizmo gate reads it to skip double submissions. */
    private static boolean capturing;

    private CharacterMask()
    {
    }

    /** True while {@link #capture} re-renders forms into the mask — not a world render for gizmos. */
    public static boolean isCapturing()
    {
        return capturing;
    }

    /** GroupMask reuses this flag while it re-renders forms into its own per-light mask. */
    public static void setCapturing(boolean value)
    {
        capturing = value;
    }

    /** The character depth texture, or -1 before the first capture. */
    public static int getDepthTexture()
    {
        return fbo == null ? -1 : fbo.getDepthAttachment();
    }

    /** True when the last frame actually drew a character — lets the shaders skip the mask entirely. */
    public static boolean hasCharacters()
    {
        return present && fbo != null;
    }

    private static void ensureFbo(int w, int h)
    {
        if (fbo != null && width == w && height == h)
        {
            return;
        }

        if (fbo != null)
        {
            fbo.delete();
        }

        fbo = new SimpleFramebuffer(w, h, true, MinecraftClient.IS_SYSTEM_MAC);
        fbo.setClearColor(0F, 0F, 0F, 0F);
        width = w;
        height = h;
    }

    /** At WorldRenderEvents.LAST: draw the characters' depth from the camera. */
    public static void capture(WorldRenderContext context)
    {
        present = false;

        if (!com.bbsvfx.vfxlights.VfxLightsModule.isEnabled())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.world == null)
        {
            return;
        }

        Framebuffer main = mc.getFramebuffer();

        ensureFbo(main.textureWidth, main.textureHeight);

        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        fbo.clear(MinecraftClient.IS_SYSTEM_MAC);
        fbo.beginWrite(false);

        /* Depth only: colour is irrelevant, the form textures would just write noise. Depth test on so
         * the nearest character wins where two overlap. */
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();

        boolean previousIris = com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$getIris();

        com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$setIris(false);

        try
        {
            net.minecraft.util.math.Vec3d cam = context.camera().getPos();

            capturing = true;
            com.bbsvfx.bbsvfx.client.BbsVfxForeignPass.enter("charMask");
            drawCharacters((ClientWorld) mc.world, context.matrixStack(), cam.x, cam.y, cam.z,
                mc.getTickDelta());
        }
        catch (Throwable ignored)
        {
            /* A misbehaving renderer costs this frame's mask, not the frame. */
        }
        finally
        {
            capturing = false;
            com.bbsvfx.bbsvfx.client.BbsVfxForeignPass.exit();
            com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$setIris(previousIris);
        }

        RenderSystem.colorMask(true, true, true, true);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        main.beginWrite(false);
    }

    /**
     * The controllers to read actors from: films playing outside the editor, the outside-recording
     * recorder, plus the editor's own — the last only while BBS is actually drawing it.
     *
     * <p>BBS never tears {@code editorController} down: {@code UIFilmController.createEntities} builds
     * one and nothing nulls it when the film closes, so the field outlives the session that made it.
     * It reaches the world through exactly one path — {@code BBSRendering.renderCoolStuff} →
     * {@code currentScreen instanceof UIScreen} → {@code menu.renderInWorld} → the OPEN panel — so the
     * field's lifetime is not the right question; the dashboard being the current screen with the film
     * panel open is. Reading it ungated left a closed film's actors in every actor pass, and since BBS
     * no longer renders their bodies the result was a shadow with nothing casting it: leave a film,
     * place a lamp in the world, and a departed replay is still on the floor.</p>
     */
    public static java.util.List<mchorse.bbs_mod.film.BaseFilmController> controllers()
    {
        java.util.List<mchorse.bbs_mod.film.BaseFilmController> controllers = new java.util.ArrayList<>();

        try
        {
            controllers.addAll(((com.bbsvfx.vfxlights.mixin.client.FilmsAccessor) (Object)
                mchorse.bbs_mod.BBSModClient.getFilms()).vfxlights$getControllers());

            /* "Record outside" closes the screen and hands the film to a Recorder, which is a film
             * controller of its own — BBS draws it right beside the list above (Films.render). It was
             * never in this list, so its actors cast nothing; the closed editor's frozen copy of the
             * same film stood in for them by accident, and gating that copy correctly would have left
             * outside recording with no actor shadows at all. */
            mchorse.bbs_mod.film.Recorder recorder = mchorse.bbs_mod.BBSModClient.getFilms().getRecorder();

            if (recorder != null)
            {
                controllers.add(recorder);
            }

            mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard =
                mchorse.bbs_mod.BBSModClient.getDashboardIfCreated();

            if (dashboard != null
                && mchorse.bbs_mod.ui.framework.UIScreen.getCurrentMenu() == dashboard
                && dashboard.getPanels().panel instanceof mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel
                && filmPanel.getController().editorController != null)
            {
                controllers.add(filmPanel.getController().editorController);
            }
        }
        catch (Throwable ignored)
        {
        }

        return controllers;
    }

    private static void drawCharacters(ClientWorld world, MatrixStack matrices,
        double camX, double camY, double camZ, float tickDelta)
    {
        /* Film actors — never range-culled, they are the subject of the shot. */
        for (mchorse.bbs_mod.film.BaseFilmController controller : controllers())
        {
            for (mchorse.bbs_mod.forms.entities.IEntity actor : controller.getEntities().values())
            {
                mchorse.bbs_mod.forms.forms.Form form = actor == null ? null : actor.getForm();

                if (form == null)
                {
                    continue;
                }

                /* The film's own sub-tick time (0 while paused — the ShadowMapper pause-tremor
                 * lesson): the raw tickDelta sweeps a frozen stub between its recorded prev/current
                 * at the 20 Hz tick sawtooth while the visible actor stands still. */
                float transition = ((com.bbsvfx.vfxlights.mixin.client.BaseFilmControllerAccessor)
                    controller).vfxlights$getTransition(actor, tickDelta);

                try
                {
                    Matrix4f model = mchorse.bbs_mod.film.FilmMatrices.getMatrixForRenderWithRotation(
                        actor, camX, camY, camZ, transition);

                    matrices.push();
                    matrices.multiplyPositionMatrix(model);

                    mchorse.bbs_mod.forms.renderers.FormRenderingContext context =
                        new mchorse.bbs_mod.forms.renderers.FormRenderingContext()
                            .set(mchorse.bbs_mod.forms.renderers.FormRenderType.ENTITY, actor,
                                matrices, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, transition);

                    mchorse.bbs_mod.forms.FormUtilsClient.render(form, context);
                    matrices.pop();
                    present = true;
                }
                catch (Throwable ignored)
                {
                }
            }
        }

        /* Model blocks near the camera — posed characters set in the world. */
        double range = MODEL_BLOCK_RANGE;
        double rangeSq = range * range;
        int minChunkX = ((int) Math.floor(camX - range)) >> 4;
        int maxChunkX = ((int) Math.floor(camX + range)) >> 4;
        int minChunkZ = ((int) Math.floor(camZ - range)) >> 4;
        int maxChunkZ = ((int) Math.floor(camZ + range)) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
        {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
            {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);

                if (chunk == null)
                {
                    continue;
                }

                for (net.minecraft.block.entity.BlockEntity blockEntity : chunk.getBlockEntities().values())
                {
                    if (!(blockEntity instanceof mchorse.bbs_mod.blocks.entities.ModelBlockEntity modelBlock))
                    {
                        continue;
                    }

                    mchorse.bbs_mod.blocks.entities.ModelProperties properties = modelBlock.getProperties();
                    mchorse.bbs_mod.forms.forms.Form form = properties == null ? null : properties.getForm();

                    if (form == null || !properties.isEnabled())
                    {
                        continue;
                    }

                    BlockPos pos = modelBlock.getPos();
                    double dx = pos.getX() + 0.5 - camX;
                    double dy = pos.getY() + 0.5 - camY;
                    double dz = pos.getZ() + 0.5 - camZ;

                    if (dx * dx + dy * dy + dz * dz > rangeSq)
                    {
                        continue;
                    }

                    try
                    {
                        matrices.push();
                        matrices.translate(
                            pos.getX() + 0.5F - camX,
                            pos.getY() - camY,
                            pos.getZ() + 0.5F - camZ);
                        mchorse.bbs_mod.utils.MatrixStackUtils.applyTransform(matrices, properties.getTransform());

                        mchorse.bbs_mod.forms.renderers.FormRenderingContext context =
                            new mchorse.bbs_mod.forms.renderers.FormRenderingContext()
                                .set(mchorse.bbs_mod.forms.renderers.FormRenderType.MODEL_BLOCK,
                                    modelBlock.getEntity(), matrices, FULL_BRIGHT,
                                    OverlayTexture.DEFAULT_UV, tickDelta);

                        mchorse.bbs_mod.forms.FormUtilsClient.render(form, context);
                        matrices.pop();
                        present = true;
                    }
                    catch (Throwable ignored)
                    {
                    }
                }
            }
        }
    }
}
