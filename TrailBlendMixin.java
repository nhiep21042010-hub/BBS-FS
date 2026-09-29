package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.renderers.TrailFormRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendGL;

/**
 * Applies the active whole-form blend ({@code BbsVfxBlendState}) to a Trail form, which draws directly
 * after {@code RenderSystem.defaultBlendFunc()} — redirect that call to the blend mode's GL state.
 */
@Mixin(value = TrailFormRenderer.class, remap = false)
public abstract class TrailBlendMixin
{
    @Redirect(
        method = "render3D",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;defaultBlendFunc()V"))
    private void bbsvfx$blend()
    {
        BbsVfxBlendGL.applyActiveOrDefault();
    }
}
