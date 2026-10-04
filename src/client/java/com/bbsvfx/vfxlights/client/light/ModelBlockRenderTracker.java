package com.bbsvfx.vfxlights.client.light;

import net.minecraft.util.math.BlockPos;

/**
 * Which model block is being rendered right now, if any. Set by the ModelBlockEntityRenderer mixin
 * at the head of the block's render and cleared at return; the light collector stamps it onto the
 * light so a block break can kill EXACTLY its lamps (position matching by radius was fuzzy: a lamp
 * head two blocks above its block never cleared, a bigger radius would kill neighbours).
 */
public final class ModelBlockRenderTracker
{
    private static BlockPos current;

    private ModelBlockRenderTracker()
    {
    }

    public static void set(BlockPos pos)
    {
        current = pos;
    }

    public static BlockPos get()
    {
        return current;
    }

    public static void clear()
    {
        current = null;
    }
}
