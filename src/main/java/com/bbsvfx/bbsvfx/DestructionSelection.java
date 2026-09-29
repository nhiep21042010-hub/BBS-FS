package com.bbsvfx.bbsvfx;

import net.minecraft.util.math.BlockPos;

/**
 * Client-side block selection made with the destruction wand: two corners of the box to capture.
 * Set from {@code BbsVfxMain}'s use callback (client only), drawn by {@code BbsVfxClient}'s world render
 * hook, and read by the capture step (stage 3).
 */
public final class DestructionSelection
{
    public static BlockPos pos1;
    public static BlockPos pos2;

    private DestructionSelection()
    {}

    public static boolean isComplete()
    {
        return pos1 != null && pos2 != null;
    }

    public static void clear()
    {
        pos1 = null;
        pos2 = null;
    }

    public static BlockPos min()
    {
        return new BlockPos(
            Math.min(pos1.getX(), pos2.getX()),
            Math.min(pos1.getY(), pos2.getY()),
            Math.min(pos1.getZ(), pos2.getZ()));
    }

    public static BlockPos max()
    {
        return new BlockPos(
            Math.max(pos1.getX(), pos2.getX()),
            Math.max(pos1.getY(), pos2.getY()),
            Math.max(pos1.getZ(), pos2.getZ()));
    }
}
