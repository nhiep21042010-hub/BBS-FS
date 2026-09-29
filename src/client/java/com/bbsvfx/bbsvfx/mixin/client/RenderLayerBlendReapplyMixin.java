package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21-only timing fix for the blend hooks. On 1.20.x BBS fires {@code drawLayer} (which runs our
 * hijack callbacks + the blend mixins) AFTER the layer's {@code startDrawing}, so the GL blend state
 * they set survives into the actual draw. The 1.21 branch injects at {@code RenderLayer.draw} HEAD —
 * BEFORE {@code startDrawing} — whose state setup then RESETS our blend (labels/forms silently lost
 * their blend modes). Re-fire {@code drawLayer} right after {@code startDrawing}; the callbacks are
 * idempotent GL state sets, so double-firing on 1.21 is harmless.
 *
 * <p>{@code require = 0} + the 1.21-only {@code draw(BuiltBuffer)} signature: on 1.20.x the target
 * does not exist and the whole injector silently skips.</p>
 */
@Mixin(RenderLayer.class)
public class RenderLayerBlendReapplyMixin
{
    @Inject(
        method = "draw(Lnet/minecraft/client/render/BuiltBuffer;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayer;startDrawing()V", shift = At.Shift.AFTER),
        require = 0
    )
    private void bbsvfx$reapplyBlendAfterStartDrawing(CallbackInfo ci)
    {
        CustomVertexConsumerProvider.drawLayer((RenderLayer) (Object) this);
    }
}
