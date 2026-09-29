package com.bbsvfx.bbsvfx.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import org.lwjgl.opengl.GL14;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendGL;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendState;

/**
 * Applies the active whole-form blend ({@link BbsVfxBlendState}, set per top-level form by
 * {@code FormBlendStateMixin}) to a model draw. BBS calls {@code RenderSystem.defaultBlendFunc()}
 * immediately before {@code model.render(...)} in {@code renderModel}, which would clobber any blend func
 * set from outside — so we redirect exactly that call and substitute the mode's GL state instead (Stage 1,
 * fixed-function). The matching {@code disableBlend()} is redirected too, to reset the blend equation to
 * {@code FUNC_ADD} (Darken/Lighten use MIN/MAX, which {@code disableBlend} does not reset, so it would
 * otherwise leak into the model's items / later body parts).
 *
 * <p>{@code com.mojang.blaze3d} is not obfuscated, so the blaze3d injection targets match by literal name
 * under {@code remap = false}.</p>
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelBlendMixin
{
    @Unique
    private boolean bbsvfx$blendApplied;

    @Redirect(
        method = "renderModel",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;defaultBlendFunc()V"))
    private void bbsvfx$applyBlend()
    {
        this.bbsvfx$blendApplied = false;

        if (BbsVfxBlendState.active && BbsVfxBlendGL.apply(BbsVfxBlendState.mode, BbsVfxBlendState.factor))
        {
            this.bbsvfx$blendApplied = true;
            return;
        }

        RenderSystem.defaultBlendFunc();
    }

    @Redirect(
        method = "renderModel",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;disableBlend()V"))
    private void bbsvfx$restoreBlend()
    {
        if (this.bbsvfx$blendApplied)
        {
            GlStateManager._blendEquation(GL14.GL_FUNC_ADD);
            this.bbsvfx$blendApplied = false;
        }

        RenderSystem.disableBlend();
    }
}
