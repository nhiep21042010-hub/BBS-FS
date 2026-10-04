package com.bbsvfx.vfxlights.client.iris;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The only place the rest of the mod asks about Iris.
 *
 * <p>Every Iris class reference lives in {@link IrisHooks}, which is loaded lazily and only after
 * {@code isModLoaded} says it is safe — referencing Iris types from a class that always loads would
 * crash every user who runs without Iris, which for a public mod is half of them.</p>
 */
public final class IrisCompat
{
    private static final boolean IRIS_PRESENT = FabricLoader.getInstance().isModLoaded("iris");

    private IrisCompat()
    {
    }

    /** True when a shaderpack is actually active — not merely Iris installed. */
    public static boolean packInUse()
    {
        return IRIS_PRESENT && IrisHooks.packInUse();
    }

    /** Name of the selected pack, or empty when none. */
    public static String packName()
    {
        return IRIS_PRESENT ? IrisHooks.packName() : "";
    }
}
