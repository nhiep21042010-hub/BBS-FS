package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bbsvfx.bbsvfx.DestructionSelection;
import com.bbsvfx.bbsvfx.forms.BeamForm;
import com.bbsvfx.bbsvfx.forms.CurveForm;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;
import com.bbsvfx.bbsvfx.forms.ExplosionForm;

import static com.bbsvfx.bbsvfx.BbsVfxAddon.MOD_ID;

/**
 * Client entry point ({@code client} entrypoint in {@code fabric.mod.json}). Registers the runtime
 * localized labels for the addon's settings group (see {@link BbsVfxStrings}), wires the client-side
 * pieces of the {@link CurveForm} (its 3D renderer and editor panel), and logs a line so you can
 * confirm the client side initialized.
 */
public class BbsVfxClient implements ClientModInitializer
{
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient()
    {
        BBSMod.events.register(new BbsVfxStrings());
        BbsVfxStrings.apply(BBSModClient.getL10n());

        /* Rebuild BBS's form picker whenever a module's toggle flips, so a disabled module's forms leave
         * the picker (each module gates its own picker entries on VfxModules.isEnabled). Client-only. */
        com.bbsvfx.bbsvfx.module.VfxModules.onToggleChanged = () -> BBSModClient.getFormCategories().setup();

        /* CurveForm client wiring (both are public static registries). The common-side type and the
         * "miscellaneous" picker entry are wired by mixins (BBSMod / ExtraFormSection). */
        FormUtilsClient.register(CurveForm.class, CurveFormRenderer::new);
        UIFormEditor.register(CurveForm.class, UICurveForm::new);

        FormUtilsClient.register(DestructionBoxForm.class, DestructionBoxFormRenderer::new);
        UIFormEditor.register(DestructionBoxForm.class, UIDestructionBoxForm::new);

        /* bbsvfx:explosion reuses the whole DestructionBox renderer (ExplosionForm is a subclass) with
         * its own explosion-shaped editor. The renderer is generic to DestructionBoxForm, and
         * FormUtilsClient looks it up by the exact form class, so ExplosionForm needs its own entry —
         * registered through raw types since the factory map is raw. */
        registerExplosionRenderer();
        UIFormEditor.register(ExplosionForm.class, UIExplosionForm::new);

        /* bbsvfx:beam — a standalone additive VFX form (no physics), rendered as closed-form emissive
         * geometry. Common type + picker entry are wired by the mixins. */
        FormUtilsClient.register(BeamForm.class, BeamFormRenderer::new);
        UIFormEditor.register(BeamForm.class, UIBeamForm::new);

        /* bbsvfx:dome — the expanding energy dome (climax of the beam sequence). */
        FormUtilsClient.register(com.bbsvfx.bbsvfx.forms.DomeForm.class, DomeFormRenderer::new);
        UIFormEditor.register(com.bbsvfx.bbsvfx.forms.DomeForm.class, UIDomeForm::new);

        /* bbsvfx:wind — a zonal ambient wind (scan territory, drive foliage sway + visible wind). */
        FormUtilsClient.register(com.bbsvfx.bbsvfx.forms.WindForm.class, WindFormRenderer::new);
        UIFormEditor.register(com.bbsvfx.bbsvfx.forms.WindForm.class, UIWindForm::new);

        /* Custom core shader for the Destruction Box GPU path (shatter displacement in the vertex shader).
         * Loaded from assets/bbsvfx/shaders/core/destruction_box.{json,vsh,fsh}. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "destruction_box"),
                net.minecraft.client.render.VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                program -> DestructionShader.PROGRAM = program));

        /* Tier-C ballistic debris shader (instanced cubes, closed-form flight in the VSH) — the LOD
         * path that lets 50-100k-block explosions render with zero per-frame CPU geometry work. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "destruction_ballistic"),
                net.minecraft.client.render.VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                program -> DestructionShader.BALLISTIC = program));

        /* Beam heat-haze (mirage) shader: re-samples the rendered scene with noise-displaced UVs. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "beam_heat"),
                net.minecraft.client.render.VertexFormats.POSITION_COLOR_TEXTURE,
                program -> BeamHeat.PROGRAM = program));

        /* Dome shell: screen-space raymarched hemisphere volume (reads scene depth, tints the world behind
         * it, glowing rim) — depth-correct compositing with no sorting/OIT. Fullscreen POSITION_TEXTURE. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "dome_volume"),
                net.minecraft.client.render.VertexFormats.POSITION_TEXTURE,
                program -> DomeVolume.PROGRAM = program));

        /* Ground cracks: screen-space magma fissures spreading over the ground from an epicentre. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "cracks_volume"),
                net.minecraft.client.render.VertexFormats.POSITION_TEXTURE,
                program -> CracksVolume.PROGRAM = program));

        /* Rising smoke: screen-space volumetric fractal haze over the crater. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "smoke_volume"),
                net.minecraft.client.render.VertexFormats.POSITION_TEXTURE,
                program -> SmokeVolume.PROGRAM = program));

        /* Beam column: screen-space raymarched emissive energy column (core + helix), depth-correct. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "beam_volume"),
                net.minecraft.client.render.VertexFormats.POSITION_TEXTURE,
                program -> BeamVolume.PROGRAM = program));

        /* Ambient wind: screen-space volumetric noise streaks flowing through the WindForm zone. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "wind_volume"),
                net.minecraft.client.render.VertexFormats.POSITION_TEXTURE,
                program -> WindVolume.PROGRAM = program));

        /* Explosion mushroom: a volumetric fractal-noise cloud with the fire as an emissive term inside it. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "explosion_smoke"),
                net.minecraft.client.render.VertexFormats.POSITION_TEXTURE,
                program -> ExplosionSmokeVolume.PROGRAM = program));

        /* Dome world-levelling shader: INSTANCED unit cubes (one draw, ~44 B/block), radial-front
         * settle+dissolve in the VSH — scales to a 100k+-block level with zero per-frame CPU geometry. */
        CoreShaderRegistrationCallback.EVENT.register(context ->
            context.register(net.minecraft.util.Identifier.of(MOD_ID, "dome_level_inst"),
                net.minecraft.client.render.VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                program -> DestructionShader.DOME = program));

        /* Follow-curve camera modifier: override the clip with its client logic (same factory, unique
         * link) and register its editor panel. The common clip type is registered by BBSModFormsMixin. */
        BBSMod.getFactoryCameraClips().register(new Link("bbsvfx", "follow_curve"),
            FollowCurveClientClip.class, new ClipFactoryData(Icons.ARC, 0x4ba3ff));
        UIClip.register(FollowCurveClientClip.class, UIFollowCurveClip::new);

        /* Own keyframe editors for the smear / lines pose channels (their ValueSmearPose/ValueLinesPose
         * use dedicated factory instances, keyed here to these editors). */
        UIKeyframeFactory.register(com.bbsvfx.bbsvfx.forms.BbsVfxPoseFactories.SMEAR, UISmearKeyframeFactory::new);
        UIKeyframeFactory.register(com.bbsvfx.bbsvfx.forms.BbsVfxPoseFactories.LINES, UILinesKeyframeFactory::new);

        /* Whole-form smear channel editor (non-model forms) — reuses the smear editor, wires the pose. */
        UIKeyframeFactory.register(com.bbsvfx.bbsvfx.forms.BbsVfxPoseFactories.FORM_SMEAR, UIFormSmearKeyframeFactory::new);

        /* Dropdown keyframe editor for the legacy blend_mode int channel (kept for back-compat). */
        UIKeyframeFactory.register(com.bbsvfx.bbsvfx.forms.BbsVfxKeyframeFactories.BLEND_MODE, UIBlendModeKeyframeFactory::new);

        /* Per-bone blend Pose channel editor (bone list + per-bone mode/strength). */
        UIKeyframeFactory.register(com.bbsvfx.bbsvfx.forms.BbsVfxPoseFactories.BLEND, UIBlendKeyframeFactory::new);

        /* Impact-frame camera modifier editor. The common clip type is registered by BBSModFormsMixin;
         * the clip's effect is a full-screen post pass (BbsVfxImpactShader), built in BBSShadersDissolveMixin. */
        UIClip.register(com.bbsvfx.bbsvfx.camera.ImpactClip.class, UIImpactClip::new);

        /* Draw the destruction-wand box selection as a wireframe. */
        WorldRenderEvents.LAST.register(this::renderSelection);

        /* Snapshot the world depth here (the world framebuffer is bound with complete depth) so the label
         * projection post pass works with AND without a shaderpack (Sodium leaves no depth in the main FB). */
        WorldRenderEvents.LAST.register(context -> com.bbsvfx.bbsvfx.client.BbsVfxProjectionShader.captureDepth());

        /* Snapshot the world depth for the form-blend post composite too (so blended forms are occluded by
         * world geometry), same reason — only captures when a blend form is queued this frame. */
        WorldRenderEvents.LAST.register(context -> com.bbsvfx.bbsvfx.client.BbsVfxFormBlend.captureDepth());

        /* Explosion smoke/dust: queued during the form pass, drawn here through the PARTICLE program —
         * the one pipeline every shaderpack blends softly (entity-translucent gets alpha-tested by packs). */
        WorldRenderEvents.LAST.register(BbsVfxExplosionFx::drawDeferred);

        /* Beam heat-haze: snapshot world depth + projection at LAST (complete under any renderer);
         * the draw itself happens post-composite in BBSRenderingImpactMixin (visible under Iris). */
        WorldRenderEvents.LAST.register(BeamHeat::captureDepth);

        /* Dome shell raymarch: snapshot world depth + projection at LAST; the fullscreen composite runs
         * post-composite in BBSRenderingImpactMixin. */
        WorldRenderEvents.LAST.register(DomeVolume::captureDepth);

        /* Ground cracks: snapshot world depth + projection at LAST; composite post-pack in the mixin. */
        WorldRenderEvents.LAST.register(CracksVolume::captureDepth);

        /* Rising smoke: snapshot world depth + projection at LAST; composite post-pack in the mixin. */
        WorldRenderEvents.LAST.register(SmokeVolume::captureDepth);

        /* Beam column raymarch: snapshot world depth + projection at LAST; composite post-pack in the mixin. */
        WorldRenderEvents.LAST.register(BeamVolume::captureDepth);

        /* Ambient wind volume: snapshot world depth + projection at LAST; composite post-pack in the mixin. */
        WorldRenderEvents.LAST.register(WindVolume::captureDepth);
        WorldRenderEvents.LAST.register(ExplosionSmokeVolume::captureDepth);

        /* Wind foliage watchdog: restore the client-cut foliage if the form stopped rendering (its model
         * block was broken / actor removed), so the zone never stays invisibly cut. */
        WorldRenderEvents.LAST.register(context -> WindFoliage.tick());

        /* OIT (order-independent transparency) for VFX layers: snapshot OPAQUE scene depth + projection at
         * AFTER_ENTITIES (before translucent water/clouds), so the deferred composite blends the dome OVER
         * clouds/water (proper transparency) while terrain/foliage/entities still occlude it. The
         * accumulate + composite themselves run post-composite in BBSRenderingImpactMixin. */
        WorldRenderEvents.AFTER_ENTITIES.register(BbsVfxOIT::captureDepth);

        LOG.info("BbsVfxClient.onInitializeClient — addon client initialized");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerExplosionRenderer()
    {
        FormUtilsClient.register((Class) ExplosionForm.class,
            (FormUtilsClient.IFormRendererFactory) form -> new DestructionBoxFormRenderer((DestructionBoxForm) form));
    }

    private void renderSelection(WorldRenderContext context)
    {
        if (!DestructionSelection.isComplete())
        {
            return;
        }

        BlockPos min = DestructionSelection.min();
        BlockPos max = DestructionSelection.max();
        Vec3d cam = context.camera().getPos();
        MatrixStack stack = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();

        if (stack == null || consumers == null)
        {
            return;
        }

        stack.push();
        stack.translate(-cam.x, -cam.y, -cam.z);

        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

        WorldRenderer.drawBox(stack, lines,
            min.getX(), min.getY(), min.getZ(),
            max.getX() + 1, max.getY() + 1, max.getZ() + 1,
            1F, 0.25F, 0.25F, 1F);

        if (consumers instanceof VertexConsumerProvider.Immediate immediate)
        {
            immediate.draw(RenderLayer.getLines());
        }

        stack.pop();
    }
}
