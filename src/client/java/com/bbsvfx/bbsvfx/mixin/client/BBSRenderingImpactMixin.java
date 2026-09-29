package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.camera.BbsVfxImpactState;
import com.bbsvfx.bbsvfx.client.BbsVfxDissolveTexture;
import com.bbsvfx.bbsvfx.client.BbsVfxFontTexture;
import com.bbsvfx.bbsvfx.client.BbsVfxFormBlend;
import com.bbsvfx.bbsvfx.client.BbsVfxImpactShader;
import com.bbsvfx.bbsvfx.client.BbsVfxImpactSilhouette;
import com.bbsvfx.bbsvfx.client.BbsVfxLabelOverlay;
import com.bbsvfx.bbsvfx.client.BbsVfxProjectionShader;

/**
 * Runs the impact-frame post pass. The scene is post-processed at the end of the world render, on
 * whatever framebuffer is currently the render target — BBS reassigns {@code mc.getFramebuffer()} to its
 * own framebuffer while exporting / previewing at a custom size, and leaves it as the main framebuffer
 * otherwise, so {@code mc.getFramebuffer()} is always the right target (preview AND export). The pass is
 * a no-op unless an {@code ImpactClip} set the impact state this frame; it consumes and clears the state.
 */
@Mixin(value = BBSRendering.class, remap = false)
public abstract class BBSRenderingImpactMixin
{
    @Inject(method = "onWorldRenderEnd", at = @At("HEAD"))
    private static void bbsvfx$applyImpact(CallbackInfo ci)
    {
        /* Build any smear-dissolve texture variants requested during this frame's draws, now that the
         * world render is done (no active draw / pushed matrix) — building mid-draw crashed the stack. */
        BbsVfxDissolveTexture.processPending();
        com.bbsvfx.bbsvfx.client.BbsVfxAtlasDissolve.processPending();

        /* Build any custom-font textures requested by labels this frame (safe point, no active draw). */
        BbsVfxFontTexture.processPending();

        /* Order-independent transparency for VFX layers: accumulate + resolve the queued translucent
         * geometry over the scene — post-pack so a custom program is visible under Iris. */
        com.bbsvfx.bbsvfx.client.BbsVfxOIT.render(MinecraftClient.getInstance().getFramebuffer());

        /* Beam column: screen-space raymarched emissive energy column (the strike), depth-correct. */
        com.bbsvfx.bbsvfx.client.BeamVolume.render(MinecraftClient.getInstance().getFramebuffer());

        /* Ground cracks: screen-space magma fissures over the ground. Before the dome so the dome tints
         * the cracked ground too (it's the layer in front). */
        com.bbsvfx.bbsvfx.client.CracksVolume.render(MinecraftClient.getInstance().getFramebuffer());

        /* Dome shell: screen-space raymarched hemisphere volume. Reads the captured scene depth and tints
         * the world seen through it (depth-correct, no sorting). Post-pack so it's visible under Iris. */
        com.bbsvfx.bbsvfx.client.DomeVolume.render(MinecraftClient.getInstance().getFramebuffer());

        /* Rising smoke: volumetric fractal haze over the crater — the topmost atmospheric layer. */
        com.bbsvfx.bbsvfx.client.SmokeVolume.render(MinecraftClient.getInstance().getFramebuffer());

        /* Ambient wind: volumetric noise streaks flowing through the WindForm zone. */
        com.bbsvfx.bbsvfx.client.WindVolume.render(MinecraftClient.getInstance().getFramebuffer());
        com.bbsvfx.bbsvfx.client.ExplosionSmokeVolume.render(MinecraftClient.getInstance().getFramebuffer());

        /* Beam heat-haze refraction (mirage around the column) — distorts the scene FIRST, so labels /
         * impact stylisation composite on top of the refracted frame. */
        com.bbsvfx.bbsvfx.client.BeamHeat.render(MinecraftClient.getInstance().getFramebuffer());

        /* Cast any label projectors onto the scene (depth-reproject), before impact stylises the frame. */
        BbsVfxProjectionShader.render(MinecraftClient.getInstance().getFramebuffer());

        /* Re-draw flat labels deferred under a shaderpack (after the pack's shading: no glow + blend works). */
        BbsVfxLabelOverlay.render(MinecraftClient.getInstance().getFramebuffer());

        /* Composite forms whose blend needs the post path (shader modes, or any mode under a shader pack). */
        BbsVfxFormBlend.render(MinecraftClient.getInstance().getFramebuffer());

        /* Impact silhouette layer: render the captured actor forms in isolation (alpha = coverage) so the
         * impact composite can fill them flat over negative space. Only when the silhouette layer is on. */
        if (BbsVfxImpactState.active && BbsVfxImpactState.silhouette > 0.004F)
        {
            BbsVfxImpactSilhouette.render();
        }

        /* Consume-and-clear: the pass reads the state set by ImpactClip this frame, then clears it. */
        BbsVfxImpactShader.render(MinecraftClient.getInstance().getFramebuffer());
    }
}
