package com.bbsvfx.bbsvfx.mixin.client;

import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Null-safe shouldRender: Iris's shadow pass iterates every entity in range through this, and a
 * single entity type without a client renderer NPEs the whole frame ("entityRenderer is null").
 * Log the offender once per type and skip it instead of crashing.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherNullMixin
{
    @Inject(method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z",
        at = @At("HEAD"), cancellable = true)
    private void bbsvfx$nullSafeShouldRender(Entity entity, net.minecraft.client.render.Frustum frustum,
        double x, double y, double z, CallbackInfoReturnable<Boolean> cir)
    {
        EntityRenderer<?> renderer = ((EntityRenderDispatcher) (Object) this).getRenderer(entity);

        if (renderer == null)
        {
            LogManager.getLogger("bbsvfx").warn("[shadow] entity without renderer (skipped): {} ({})",
                entity.getType().toString(), entity.getUuid());
            cir.setReturnValue(false);
        }
    }
}
