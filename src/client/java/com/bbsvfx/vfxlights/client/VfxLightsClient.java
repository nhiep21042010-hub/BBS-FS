package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import com.bbsvfx.vfxlights.forms.values.LightFactories;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import com.bbsvfx.vfxlights.client.iris.IrisCompat;
import com.bbsvfx.vfxlights.client.iris.PackLightUploader;
import com.bbsvfx.vfxlights.client.light.FormLightCollector;
import com.bbsvfx.vfxlights.client.light.GlassDispersion;
import com.bbsvfx.vfxlights.client.shadow.ShadowMapper;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import com.bbsvfx.vfxlights.client.render.LightCompositor;
import com.bbsvfx.vfxlights.client.render.VolumetricPass;
import com.bbsvfx.vfxlights.light.LightRegistry;

import java.io.IOException;
import com.bbsvfx.vfxlights.forms.AmbientLightForm;
import com.bbsvfx.vfxlights.forms.AreaLightForm;
import com.bbsvfx.vfxlights.forms.PointLightForm;
import com.bbsvfx.vfxlights.forms.SpotLightForm;

/**
 * Client entry point: binds each light form to its renderer and its editor — the two client-side halves
 * of form registration, the common-side type registration living in {@code FormsMixin}.
 */
public class VfxLightsClient implements ClientModInitializer
{
    /**
     * The shared GLSL model (vfxlights_model.glsl) writes the shadow atlas' near plane and its
     * DEFAULT tile size as literals — the near plane never moves, and the tile size is delivered per
     * frame but falls back to that literal if a backend ever forgets to stamp it. The price of a
     * constant living in two languages is silent drift, so: read the bundled model source at startup
     * and compare. A mismatch logs an ERROR loudly instead of shipping shadows that sample the wrong
     * texels.
     */
    private static void verifyModelConstants()
    {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("vfxlights");

        try (java.io.InputStream in = VfxLightsClient.class
            .getResourceAsStream("/assets/vfxlights/shaders/include/vfxlights_model.glsl"))
        {
            if (in == null)
            {
                log.error("[vfxlights] vfxlights_model.glsl missing from the jar — shaders cannot load");

                return;
            }

            String source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            checkConstant(log, source, "VFX_TILE",
                com.bbsvfx.vfxlights.client.shadow.ShadowAtlas.DEFAULT_TILE_SIZE);
            checkConstant(log, source, "VFX_NEAR", com.bbsvfx.vfxlights.client.shadow.ShadowAtlas.NEAR);
        }
        catch (Throwable t)
        {
            log.error("[vfxlights] could not verify shader model constants", t);
        }
    }

    private static void checkConstant(org.slf4j.Logger log, String source, String name, float expected)
    {
        /* "const" optional: VFX_TILE is a plain global now, stamped per frame by each backend. */
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?:const\\s+)?float\\s+" + name + "\\s*=\\s*([0-9.]+)").matcher(source);

        if (!matcher.find())
        {
            log.error("[vfxlights] {} not found in vfxlights_model.glsl — did the constant move?", name);
        }
        else if (Math.abs(Float.parseFloat(matcher.group(1)) - expected) > 0.0001F)
        {
            log.error("[vfxlights] {} drifted: GLSL says {}, Java says {} — shadows will sample wrong",
                name, matcher.group(1), expected);
        }
    }

    private static void trackFace(String key, int color, mchorse.bbs_mod.ui.utils.icons.Icon icon)
    {
        mchorse.bbs_mod.film.replays.tracks.TrackStyle.register(key, icon, color);
    }

    @Override
    public void onInitializeClient()
    {
        verifyModelConstants();

        /* Track faces on the film timeline. BBS 2.7 keeps colour and icon per track name in TrackStyle
         * (public register), so no mixin into the editor is needed: one call per light track. Exposure
         * warm, air cool, style green, the toon ramp pink. */
        trackFace("intensity", 0xffcc55, mchorse.bbs_mod.ui.utils.icons.Icons.LIGHT);
        trackFace("range", 0xffcc55, mchorse.bbs_mod.ui.utils.icons.Icons.SPHERE);
        trackFace("temperature", 0xff9955, mchorse.bbs_mod.ui.utils.icons.Icons.DROP);
        trackFace("flicker", 0xff9955, mchorse.bbs_mod.ui.utils.icons.Icons.STOPWATCH);
        trackFace("shadows", 0x8899aa, mchorse.bbs_mod.ui.utils.icons.Icons.INVISIBLE);
        trackFace("shadow_softness", 0x8899aa, mchorse.bbs_mod.ui.utils.icons.Icons.DROP);
        trackFace("air", 0x66ccff, mchorse.bbs_mod.ui.utils.icons.Icons.SPRAY);
        trackFace("style", 0x88dd88, mchorse.bbs_mod.ui.utils.icons.Icons.SHAPES);
        trackFace("toon", 0xff88cc, mchorse.bbs_mod.ui.utils.icons.Icons.BRUSH);
        trackFace("barn", 0xccaa66, mchorse.bbs_mod.ui.utils.icons.Icons.SLAB);
        trackFace("source_radius", 0xffdd88, LightIcons.POINT);
        trackFace("angle", 0xffdd88, LightIcons.SPOT);
        trackFace("inner_angle", 0xffdd88, LightIcons.SPOT);
        trackFace("width", 0xffdd88, LightIcons.AREA);
        trackFace("height", 0xffdd88, LightIcons.AREA);
        trackFace("spread", 0xffdd88, mchorse.bbs_mod.ui.utils.icons.Icons.FRUSTUM);
        trackFace("flare", 0xffee99, mchorse.bbs_mod.ui.utils.icons.Icons.TRI_STAR);


        /* One gathering pass per frame. START is before anything in the world draws, so every light —
         * authored form or effect submission — lands in the frame it belongs to.
         *
         * With a shaderpack active, LAST FRAME's list is shipped to the patched pack shaders first —
         * pack surface shading runs during the very pass that collects this frame's lights, so the pack
         * always lights with a one-frame-old list. Imperceptible, and it keeps collection at render
         * time, which is what makes bone-attached lamps exact. */
        WorldRenderEvents.START.register((context) ->
        {
            PerfProfiler.frame();

            /* A film that ended took its lamps with it. Read the departure before anything consumes
             * the list — the shadow pass and the pack upload below both run off the frame that just
             * ended, and a dead film's light has no business in either. */
            com.bbsvfx.vfxlights.client.light.FilmLightTracker.beginFrame();

            /* The frame's true view matrix — bobbing and all — for un-projecting model-block lights. */
            if (context.matrixStack() != null)
            {
                FormLightCollector.captureView(context.matrixStack().peek().getPositionMatrix());
            }

            /* The effect clock for dispersion/volumetric noise, in seconds of WORLD time. The project
             * rule is closed-form effects from sim time — scrub back and the caustics repeat exactly —
             * and the wall clock this used to read broke that for scrubbing and offline export. The
             * modulo keeps float precision (and matches the old wrap-per-83-minutes behaviour); the
             * caustic consumers wrap it again at their own aligned period in Light.dispersionPhase. */
            net.minecraft.client.world.ClientWorld world = net.minecraft.client.MinecraftClient.getInstance().world;

            if (world != null)
            {
                com.bbsvfx.vfxlights.light.Light.updateEffectClock(
                    (world.getTime() % 100_000L + context.tickDelta()) / 20F);
            }

            boolean enabled = com.bbsvfx.vfxlights.VfxLightsModule.isEnabled();

            if (IrisCompat.packInUse())
            {
                if (enabled)
                {
                    /* Shadow maps first, so the tiles and matrices assigned here ride up in the same
                     * upload. The pass restores whatever GL state Iris had. */
                    if (!PerfProfiler.skip(PerfProfiler.Feature.SHADOWS))
                    {
                        PerfProfiler.begin(PerfProfiler.Section.SHADOWS);
                        ShadowMapper.renderAll(LightRegistry.getLights());
                        PerfProfiler.end(PerfProfiler.Section.SHADOWS);
                    }
                    /* The actor-category map rides the same START slot: at LAST (mid Iris pipeline)
                     * its stencil/resolve state blackened the pack's frame. One frame of lag, same
                     * as the light upload itself. */
                }

                /* Uploaded even with the module OFF: the patch is already spliced into the loaded
                 * pack and cannot be un-spliced without a reload, so the only way "off" can mean
                 * dark is a zero light count in the SSBO (the upload itself zeroes it). */
                PerfProfiler.begin(PerfProfiler.Section.PACK_UPLOAD);
                PackLightUploader.upload(
                    PerfProfiler.skip(PerfProfiler.Feature.PACK_LIGHTS)
                        ? java.util.Collections.emptyList()
                        : LightRegistry.getLights(),
                    context.camera().getPos(),
                    context.matrixStack().peek().getPositionMatrix(), context.projectionMatrix());
                PerfProfiler.end(PerfProfiler.Section.PACK_UPLOAD);

                if (enabled)
                {
                    /* The air passes at frame end must draw the SAME per-light state this upload
                     * just shipped — drawing the live list put every moving lamp's beam one frame
                     * ahead of its light pool ("мигает при движении", pack only; vanilla shades
                     * and beams from one list and never tore). */
                    VolumetricPass.snapshotPackLights(LightRegistry.getLights());

                    if (!PerfProfiler.skip(PerfProfiler.Feature.MASKS))
                    {
                        PerfProfiler.begin(PerfProfiler.Section.MASKS);
                        com.bbsvfx.vfxlights.client.render.CategoryMask.capture(context);

                        /* The cel/character mask rides the same slot for a PATCHED pack: the pack
                         * samples it DURING this frame's render, and the LAST capture it used to get
                         * was a frame old — the cel edge trailed every camera or actor move. */
                        if (com.bbsvfx.vfxlights.client.iris.PackPatcher.isCurrentPackPatched())
                        {
                            com.bbsvfx.vfxlights.client.render.CharacterMask.capture(context);
                        }

                        PerfProfiler.end(PerfProfiler.Section.MASKS);
                    }
                }
            }

            PerfProfiler.begin(PerfProfiler.Section.REGISTRY);
            LightRegistry.beginFrame();
            com.bbsvfx.vfxlights.client.light.GlassFormCollector.beginFrame();
            com.bbsvfx.vfxlights.client.light.DisabledLightSweeper.sweep();
            PerfProfiler.end(PerfProfiler.Section.REGISTRY);
        });

        /* ...and one compositing pass at the end, once the world image exists to be lit. The beams go
         * after it: air glow belongs on top of everything, pack or no pack. Glass dispersion runs
         * FIRST (registration order is execution order): it clamps parent beams at the glass and
         * spawns the spectral fan, and both compositors below must already see the result. The pack
         * consumes the same list at next frame's START, so it inherits the fan for free. */
        WorldRenderEvents.LAST.register((context) ->
        {
            PerfProfiler.begin(PerfProfiler.Section.EFFECTS);
            GlassDispersion.process(context);
            com.bbsvfx.vfxlights.client.light.BounceLight.process(context);
            /* Water AFTER prism and bounce: their children are in the list by then, and a spectral
             * fan diving into a pool should get the water treatment too. */
            com.bbsvfx.vfxlights.client.light.WaterLight.process(context);
            PerfProfiler.end(PerfProfiler.Section.EFFECTS);
        });
        WorldRenderEvents.LAST.register((context) ->
        {
            if (!PerfProfiler.skip(PerfProfiler.Feature.COMPOSITE))
            {
                PerfProfiler.begin(PerfProfiler.Section.COMPOSITE);
                LightCompositor.render(context);
                PerfProfiler.end(PerfProfiler.Section.COMPOSITE);
            }
        });
        WorldRenderEvents.LAST.register((context) ->
        {
            if (!PerfProfiler.skip(PerfProfiler.Feature.VOLUMETRIC))
            {
                PerfProfiler.begin(PerfProfiler.Section.VOLUMETRIC);
                VolumetricPass.render(context);
                PerfProfiler.end(PerfProfiler.Section.VOLUMETRIC);
            }
        });
        /* Gizmo wireframes, recorded by the light form renderers during entity rendering and drawn
         * deferred here — never mid-draw (the "Pose stack not empty" lesson). With the dashboard open
         * the world renders into BBS's off-screen texture, so GizmoPass only snapshots the matrices
         * here and the actual draw happens in the UI pass over the preview blit (UIFilmControllerMixin
         * → GizmoPass.renderInUI). Without a dashboard it draws here, at AFTER_TRANSLUCENT rather than
         * LAST: clouds render after this hook and write no depth, so drawing at LAST put every
         * wireframe on top of the cloud layer (the "gizmo through clouds" report); here the clouds
         * blend over them like over any other geometry. */
        WorldRenderEvents.AFTER_TRANSLUCENT.register(com.bbsvfx.vfxlights.client.render.GizmoPass::renderAndClear);
        /* Character depth for cel shading — drawn from the camera so the lighting can shade actors
         * two-tone and leave the world alone. For the FALLBACK compositor it is captured here at
         * LAST (consumed at onWorldRenderEnd, same frame). A PATCHED pack samples the mask DURING
         * the world render instead, so its capture moved to the frame-START handler — re-capturing
         * here would only reintroduce the one-frame lag for next frame's pack pass. */
        WorldRenderEvents.LAST.register((context) ->
        {
            if (!com.bbsvfx.vfxlights.client.iris.IrisCompat.packInUse()
                || !com.bbsvfx.vfxlights.client.iris.PackPatcher.isCurrentPackPatched())
            {
                if (!PerfProfiler.skip(PerfProfiler.Feature.MASKS))
                {
                    PerfProfiler.begin(PerfProfiler.Section.MASKS);
                    com.bbsvfx.vfxlights.client.render.CharacterMask.capture(context);
                    PerfProfiler.end(PerfProfiler.Section.MASKS);
                }
            }
        });
        /* Approved-actor depth for group-filtered lamps — same hook (valid world matrices), one
         * depth slot per filtered lamp; the composite binds them at renderPost. */
        WorldRenderEvents.LAST.register((context) ->
        {
            if (!PerfProfiler.skip(PerfProfiler.Feature.MASKS))
            {
                PerfProfiler.begin(PerfProfiler.Section.MASKS);
                com.bbsvfx.vfxlights.client.render.GroupMask.capture(context);
                PerfProfiler.end(PerfProfiler.Section.MASKS);
            }
        });
        /* The frame's closing timestamp probe: after every measured LAST pass, so the total GPU
         * span covers the whole world render, Iris's internal pack passes included. */
        WorldRenderEvents.LAST.register((context) -> PerfProfiler.endFrame());
        /* Actor category map for the PACK backend's group filter — the pack shades every lamp in
         * one pass, so it gets the category per pixel plus a bitmask per lamp instead of a mask.
         * Captured from the START handler above (same slot as the light upload): at LAST, mid
         * Iris pipeline, its stencil/resolve state blackened the pack's frame. */

        CoreShaderRegistrationCallback.EVENT.register((context) ->
        {
            try
            {
                context.register(new Identifier("vfxlights", "light_composite"), VertexFormats.POSITION,
                    LightCompositor::setShader);
                context.register(new Identifier("vfxlights", "light_volumetric"), VertexFormats.POSITION,
                    VolumetricPass::setShader);
                context.register(new Identifier("vfxlights", "light_volumetric_up"), VertexFormats.POSITION,
                    VolumetricPass::setUpShader);
                context.register(new Identifier("vfxlights", "light_glass"), VertexFormats.POSITION_COLOR,
                    ShadowMapper::setGlassShader);
                context.register(new Identifier("vfxlights", "light_flare"), VertexFormats.POSITION,
                    com.bbsvfx.vfxlights.client.render.FlarePass::setShader);
                context.register(new Identifier("vfxlights", "light_dust"), VertexFormats.POSITION_TEXTURE,
                    com.bbsvfx.vfxlights.client.render.DustPass::setShader);
            }
            catch (IOException e)
            {
                throw new RuntimeException("VFX LIGHTS could not load its lighting shader", e);
            }
        });

        FormUtilsClient.register(AreaLightForm.class, AreaLightFormRenderer::new);
        FormUtilsClient.register(AmbientLightForm.class, AmbientLightFormRenderer::new);
        FormUtilsClient.register(PointLightForm.class, PointLightFormRenderer::new);
        FormUtilsClient.register(SpotLightForm.class, SpotLightFormRenderer::new);

        /* Strings first: the editors below take their titles from LightKeys, and applying the table
         * once here means the keys are filled in before anything asks for them. The subscriber keeps
         * them following BBS's language switcher afterwards. */
        BBSMod.events.register(new LightKeys());
        LightKeys.apply(BBSModClient.getL10n());

        /* Editors for the grouped light tracks: click a keyframe on Air / Style / Toon / Barn doors
         * and its dials appear here. Labels are index-aligned with each group's schema. */
        UIKeyframeFactory.register(LightFactories.AIR, (keyframe, editor) ->
            new UILightStructKeyframeFactory<>(keyframe, editor, new IKey[]
            {
                LightKeys.BEAM, LightKeys.HAZE, LightKeys.DUST, LightKeys.MOTE_SIZE,
                LightKeys.PRISM, LightKeys.PRISM_SCALE, LightKeys.BOUNCE
            }));
        UIKeyframeFactory.register(LightFactories.STYLE, (keyframe, editor) ->
            new UILightStructKeyframeFactory<>(keyframe, editor, new IKey[]
            {
                LightKeys.RIM, LightKeys.RIM_WIDTH, LightKeys.GRAZING_SHEEN, LightKeys.TRANSLUCENCY,
                LightKeys.OUTLINE, LightKeys.OUTLINE_WIDTH, LightKeys.OUTLINE_BLUR,
                LightKeys.OUTLINE_INNER, LightKeys.OUTLINE_TARGET, LightKeys.OUTLINE_BLEND
            }));
        UIKeyframeFactory.register(LightFactories.TOON, (keyframe, editor) ->
            new UILightStructKeyframeFactory<>(keyframe, editor, new IKey[]
            {
                LightKeys.TOON_SHADING, LightKeys.EDGE_SOFTNESS,
                LightKeys.SHADOW_TINT, LightKeys.SHADOW_BRIGHTNESS
            }));
        UIKeyframeFactory.register(LightFactories.BARN, (keyframe, editor) ->
            new UILightStructKeyframeFactory<>(keyframe, editor, new IKey[]
            {
                LightKeys.BARN_TOP, LightKeys.BARN_BOTTOM, LightKeys.BARN_LEFT,
                LightKeys.BARN_RIGHT, LightKeys.SOFTNESS
            }));

        UIFormEditor.register(AreaLightForm.class, () -> new UILightForm<AreaLightForm>(
            UIAreaLightFormPanel::new, LightKeys.AREA_LIGHT, LightIcons.AREA, true));
        UIFormEditor.register(AmbientLightForm.class, () -> new UILightForm<AmbientLightForm>(
            UIAmbientLightFormPanel::new, LightKeys.AMBIENT_LIGHT, LightIcons.AMBIENT, false));
        UIFormEditor.register(PointLightForm.class, () -> new UILightForm<PointLightForm>(
            UIPointLightFormPanel::new, LightKeys.POINT_LIGHT, LightIcons.POINT, true));
        UIFormEditor.register(SpotLightForm.class, () -> new UILightForm<SpotLightForm>(
            UISpotLightFormPanel::new, LightKeys.SPOT_LIGHT, LightIcons.SPOT, true));
    }
}
