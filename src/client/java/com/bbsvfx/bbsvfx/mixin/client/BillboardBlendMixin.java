package com.bbsvfx.bbsvfx.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.forms.renderers.BillboardFormRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendGL;

/**
 * Applies the active whole-form blend ({@code BbsVfxBlendState}) to a Billboard form, which draws its quad
 * directly (no consumer provider) after {@code RenderSystem.defaultBlendFunc()} — redirect that call to
 * the blend mode's GL state. blaze3d is unobfuscated, so the target matches literally under remap=false.
 */
@Mixin(value = BillboardFormRenderer.class, remap = false)
public abstract class BillboardBlendMixin
{
    @Redirect(
        method = "renderQuad",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;defaultBlendFunc()V"))
    private void bbsvfx$blend()
    {
        BbsVfxBlendGL.applyActiveOrDefault();
    }
}
