package com.bbsvfx.vfxlights.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.vfxlights.light.Light;
import com.bbsvfx.vfxlights.light.LightRegistry;

import java.util.List;
import java.util.Map;

/**
 * A screen-space depth of ONLY the actors a group-filtered lamp is allowed to touch — the film's
 * replays whose category sits in the lamp's {@link Light#groups}, rendered from the camera.
 *
 * <p>Same recipe as {@link CharacterMask} (depth only, actors reached through the film controllers),
 * with two deliberate differences: model blocks are NOT drawn (groups do not apply to them — a lamp
 * without a filter lights them as usual, a filtered one not at all), and actor replays resolve
 * through {@code getActors()} to the real MC entity, whose morph carries the form.</p>
 *
 * <p><b>Captured at LAST, consumed at renderPost.</b> The surface composite draws after BBS's own
 * final composite, where the world matrices are no longer guaranteed — so the masks are rendered at
 * the same hook CharacterMask uses, one depth slot per filtered lamp, and the composite just binds
 * the slot its lamp got. Cleared to far depth, so an unmarked pixel reads "not approved".</p>
 */
public final class GroupMask
{
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Filtered lamps masked per frame: each keeps its own depth slot until the composite draws. */
    private static final int MAX_FILTERED = 4;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("vfxlights");
    /** Set once the filter cap warned — one log, not per-light spam. */
    private static boolean capLogged;

    private static final SimpleFramebuffer[] slots = new SimpleFramebuffer[MAX_FILTERED];
    private static int width;
    private static int height;
    /** This frame's light -> mask slot, filled at LAST and consumed by the composite at renderPost. */
    private static final java.util.Map<Light, Integer> byLight = new java.util.HashMap<>();

    private GroupMask()
    {
    }

    /** The depth texture captured for {@code light} this frame, or -1 (it then draws unfiltered). */
    public static int slotFor(Light light)
    {
        Integer slot = byLight.get(light);

        return slot == null ? -1 : slots[slot].getDepthAttachment();
    }

    private static void ensureSize(int w, int h)
    {
        if (width == w && height == h)
        {
            return;
        }

        for (int i = 0; i < MAX_FILTERED; i++)
        {
            if (slots[i] != null)
            {
                slots[i].delete();
                slots[i] = null;
            }
        }

        width = w;
        height = h;
    }

    private static SimpleFramebuffer slot(int i)
    {
        if (slots[i] == null)
        {
            slots[i] = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
            slots[i].setClearColor(0F, 0F, 0F, 0F);
        }

        return slots[i];
    }

    /** At WorldRenderEvents.LAST: draw each group-filtered lamp's approved-actor depth from the camera. */
    public static void capture(WorldRenderContext context)
    {
        byLight.clear();

        if (!com.bbsvfx.vfxlights.VfxLightsModule.isEnabled())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.world == null)
        {
            return;
        }

        /* Only the fallback composite consumes the masks; a patched pack lights surfaces itself. */
        if (com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse()
            && com.bbsvfx.vfxlights.client.iris.PackPatcher.isCurrentPackPatched())
        {
            return;
        }

        Vec3d cam = context.camera().getPos();
        List<Light> lights = LightRegistry.getLights(cam.x, cam.y, cam.z, LightCompositor.MAX_SHADED);
        boolean any = false;

        for (Light light : lights)
        {
            if (filtered(light))
            {
                any = true;

                break;
            }
        }

        if (!any)
        {
            return;
        }

        Framebuffer main = mc.getFramebuffer();

        ensureSize(main.textureWidth, main.textureHeight);

        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        boolean previousIris = com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$getIris();

        com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$setIris(false);

        /* This re-renders the same forms the world render did; the collector's gate keeps them out
         * of the light registry — a lamp seen here is not a new light. */
        CharacterMask.setCapturing(true);
        com.bbsvfx.bbsvfx.client.BbsVfxForeignPass.enter("groupMask");

        int used = 0;

        try
        {
            /* Depth only: colour is irrelevant. Depth test on so the nearest approved actor wins. */
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();

            for (Light light : lights)
            {
                if (!filtered(light))
                {
                    continue;
                }

                if (used >= MAX_FILTERED)
                {
                    if (!capLogged)
                    {
                        capLogged = true;
                        LOG.warn("More than {} group-filtered lights in a frame; the rest draw unfiltered",
                            MAX_FILTERED);
                    }

                    break;
                }

                SimpleFramebuffer fbo = slot(used);

                fbo.clear(MinecraftClient.IS_SYSTEM_MAC);
                fbo.beginWrite(false);

                drawActors(light, (ClientWorld) mc.world, context.matrixStack(), cam,
                    mc.getTickDelta());

                byLight.put(light, used);
                used++;
            }
        }
        catch (Throwable ignored)
        {
            /* A misbehaving renderer costs this frame's masks, not the frame. */
        }
        finally
        {
            CharacterMask.setCapturing(false);
            com.bbsvfx.bbsvfx.client.BbsVfxForeignPass.exit();
            com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$setIris(previousIris);
        }

        RenderSystem.colorMask(true, true, true, true);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        main.beginWrite(false);
    }

    private static void drawActors(Light light, ClientWorld world, MatrixStack matrices,
        Vec3d cam, float tickDelta)
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

                if (replay.actor.get() || !allowed(light, replay))
                {
                    continue;
                }

                /* The film's own sub-tick time (0 while paused — the ShadowMapper pause-tremor
                 * lesson), or the frozen stub's mask sweeps at the 20 Hz tick sawtooth. */
                mchorse.bbs_mod.forms.entities.IEntity stub = entry.getValue();
                float transition = ((com.bbsvfx.vfxlights.mixin.client.BaseFilmControllerAccessor)
                    controller).vfxlights$getTransition(stub, tickDelta);

                drawForm(stub, matrices, cam, transition);
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

                if (replay == null || !allowed(light, replay))
                {
                    continue;
                }

                net.minecraft.entity.Entity entity = world.getEntityById(entry.getValue());

                if (entity == null)
                {
                    continue;
                }

                drawForm(new mchorse.bbs_mod.forms.entities.MCEntity(entity), matrices, cam, tickDelta);
            }
        }
    }

    /**
     * The one condition for "this lamp needs a mask slot", shared with the compositor's own check.
     * The {@code groupFilter} toggle exists precisely to keep a saved group selection WITHOUT
     * filtering — lamps with the toggle off used to eat the {@value #MAX_FILTERED} slots anyway,
     * starving lamps whose filter was actually on (those then drew unfiltered).
     */
    private static boolean filtered(Light light)
    {
        return light.groupFilter && !light.groups.isEmpty();
    }

    private static boolean allowed(Light light, mchorse.bbs_mod.film.replays.Replay replay)
    {
        return light.groups.contains(
            mchorse.bbs_mod.film.replays.Replay.normalizeCategory(replay.category.get()));
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

    private static void drawForm(mchorse.bbs_mod.forms.entities.IEntity actor, MatrixStack matrices,
        Vec3d cam, float tickDelta)
    {
        mchorse.bbs_mod.forms.forms.Form form = actor.getForm();

        if (form == null)
        {
            return;
        }

        try
        {
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
