package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.renderers.ExtrudedFormRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendGL;

/**
 * Applies the active whole-form blend ({@code BbsVfxBlendState}) to an Extruded (image) form, which draws
 * directly after {@code RenderSystem.defaultBlendFunc()} — redirect that call to the blend mode's GL state.
 */
@Mixin(value = ExtrudedFormRenderer.class, remap = false)
public abstract class ExtrudedBlendMixin
{
    @Redirect(
        method = "renderModel",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;defaultBlendFunc()V"))
    private void bbsvfx$blend()
    {
        BbsVfxBlendGL.applyActiveOrDefault();
    }
}
