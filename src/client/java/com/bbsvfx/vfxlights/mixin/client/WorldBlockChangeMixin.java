package com.bbsvfx.vfxlights.mixin.client;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.bbsvfx.vfxlights.client.shadow.OccluderCache;

/**
 * Tells the shadow system when the world changes underneath it.
 *
 * <p>Occluder buffers are keyed on where the lamp is, not on what is around it — that is what makes them
 * cheap enough to keep. The cost of that choice is exactly this: something has to say when the contents
 * changed, or a mined block goes on casting its shadow forever.</p>
 */
@Mixin(World.class)
public abstract class WorldBlockChangeMixin
{
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
        at = @At("RETURN"))
    private void vfxlights$blockChanged(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
        CallbackInfoReturnable<Boolean> cir)
    {
        /* CLIENT world only. On an integrated server this fires on the server thread too, and touching
         * the render thread's cache from there corrupts it silently — a HashMap damaged mid-put loses
         * entries without ever throwing, which presents as "blocks just stopped casting". */
        World self = (World) (Object) this;

        if (self.isClient && cir.getReturnValue())
        {
            OccluderCache.invalidateAt(pos.getX(), pos.getY(), pos.getZ());

            /* A broken block takes any lamp sitting IN it down too — kill persisted lights stamped
             * with this block, or they keep glowing for the rest of their grace window. */
            if (state.isAir())
            {
                com.bbsvfx.vfxlights.light.LightRegistry.clearPersistedAtBlock(pos);
            }
        }
    }
}
