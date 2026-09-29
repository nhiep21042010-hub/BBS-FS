package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendGL;
import com.bbsvfx.bbsvfx.client.BbsVfxBlendState;

/**
 * Applies the active whole-form blend ({@link BbsVfxBlendState}) to every render layer drawn through BBS's
 * {@code CustomVertexConsumerProvider}. {@code drawLayer} runs per layer at draw time — after the layer's
 * {@code startDrawing} has reset the blend func — so this is the universal seam for all consumer-based
 * forms (Block, Item, Mob, and a model's held items). When no form blend is active (the common case) it's
 * a one-boolean no-op. The blend equation is reset once per top-level form render by
 * {@code FormBlendStateMixin}.
 */
@Mixin(value = CustomVertexConsumerProvider.class, remap = false)
public abstract class ConsumerBlendMixin
{
    @Inject(method = "drawLayer", at = @At("TAIL"))
    private static void bbsvfx$blend(RenderLayer layer, CallbackInfo ci)
    {
        if (BbsVfxBlendState.active)
        {
            BbsVfxBlendGL.apply(BbsVfxBlendState.mode, BbsVfxBlendState.factor);
        }
    }
}
