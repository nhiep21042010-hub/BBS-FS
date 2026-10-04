package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.client.BBSRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.client.render.VolumetricPass;

/**
 * Draws the volumetric beams at the end of the world render — AFTER a shaderpack's composite, which is
 * the only place a custom fullscreen pass survives under Iris (drawing at Fabric's LAST got painted
 * over by the pack's final blit; measured, not assumed). Same hook every deferred BBS VFX volume uses,
 * and {@code mc.getFramebuffer()} here is always the right target: BBS reassigns it during film export
 * and preview, so the beams ride into rendered footage for free.
 */
@Mixin(value = BBSRendering.class, remap = false)
public abstract class BBSRenderingMixin
{
    @Inject(method = "onWorldRenderEnd", at = @At("HEAD"))
    private static void vfxlights$drawBeams(CallbackInfo ci)
    {
        VolumetricPass.renderPost();
    }
}
