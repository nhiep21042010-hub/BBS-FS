package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.renderers.FramebufferFormRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendGL;

/**
 * Applies the active whole-form blend ({@code BbsVfxBlendState}) to a Framebuffer form, which draws its
 * quad directly after {@code RenderSystem.defaultBlendFunc()} — redirect that call to the blend mode's GL
 * state.
 */
@Mixin(value = FramebufferFormRenderer.class, remap = false)
public abstract class FramebufferBlendMixin
{
    @Redirect(
        method = "renderQuad",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;defaultBlendFunc()V"))
    private void bbsvfx$blend()
    {
        BbsVfxBlendGL.applyActiveOrDefault();
    }
}
