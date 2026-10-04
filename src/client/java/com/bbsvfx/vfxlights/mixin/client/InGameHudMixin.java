package com.bbsvfx.vfxlights.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes the third-person black frame.
 *
 * <p>MC's vignette overlay is a fullscreen quad drawn over the finished scene with a MULTIPLY blend
 * ({@code ZERO, ONE_MINUS_SRC_COLOR}) so it only darkens the screen edges. It sets that blend through a
 * cache-routed {@code RenderSystem.blendFuncSeparate}; our light passes change the real GL blend with
 * raw GL calls that desync GlStateManager's cache, so in the third-person path the vignette ends up
 * drawing with the standard alpha blend instead — stamping its black-centred texture OPAQUE over the
 * whole scene, i.e. a black frame. (First person is spared because renderHand resyncs the blend before
 * the HUD; third person has no hand. RenderDoc-confirmed: third-person vignette draw = alpha blend,
 * first-person = multiply.) Forcing the blend back to multiply at the vignette — at HEAD and immediately
 * before the draw — did not take, so the correct real-GL fix could not be landed from the addon side.</p>
 *
 * <p>We therefore skip the vanilla vignette outright. It is a subtle HUD effect (edge darkening by
 * brightness, red world-border warning) that cinematic lighting work generally does not want in shot,
 * and dropping it removes the only thing that turned the third-person frame black.</p>
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin
{
    @Inject(method = "renderVignetteOverlay", at = @At("HEAD"), cancellable = true)
    private void vfxlights$skipVignette(DrawContext context, Entity entity, CallbackInfo ci)
    {
        /* Only when the module is on: the black frame is caused by OUR light passes desyncing the blend
         * cache. With VFX LIGHTS off, nothing corrupts state, so leave MC's vignette untouched. */
        if (com.bbsvfx.vfxlights.VfxLightsModule.isEnabled())
        {
            ci.cancel();
        }
    }
}
