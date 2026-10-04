package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.client.renderer.ModelBlockEntityRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.client.light.ModelBlockRenderTracker;

/**
 * Brackets every model block's world render with the block's position, so the light collector can
 * stamp the source block onto any lamp form inside the model (see {@link ModelBlockRenderTracker}).
 */
@Mixin(value = ModelBlockEntityRenderer.class, remap = false)
public abstract class ModelBlockEntityRenderMixin
{
    @Inject(method = "render", at = @At("HEAD"))
    private void vfxlights$trackBlock(ModelBlockEntity entity, float tickDelta, MatrixStack matrices,
        VertexConsumerProvider vertexConsumers, int light, int overlay, CallbackInfo ci)
    {
        ModelBlockRenderTracker.set(entity.getPos());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void vfxlights$untrackBlock(ModelBlockEntity entity, float tickDelta, MatrixStack matrices,
        VertexConsumerProvider vertexConsumers, int light, int overlay, CallbackInfo ci)
    {
        ModelBlockRenderTracker.clear();
    }
}
