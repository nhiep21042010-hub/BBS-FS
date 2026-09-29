package com.bbsvfx.bbsvfx.client;

/**
 * A re-entrancy flag: something OUTSIDE the world render is re-rendering our forms into its own target
 * inside the current frame — VFX LIGHTS does exactly this four times per frame (shadow tiles, character /
 * group / category masks), with BBS's Iris flag forced off so the vanilla programs stay bound.
 *
 * <p>Without a marker those passes are indistinguishable from the main world pass, and they broke the wind:
 * under Iris the shadow tiles are baked FIRST in the frame, with a LIGHT-space matrix, so the wind form's
 * once-per-frame keys were spent there (the visible volume was queued from the light's view — the streaks
 * "turned with the camera") and the sway scan reconstructed a nonsense world origin.</p>
 *
 * <p>The rule for the core: while {@link #isActive()}, skip once-per-frame work and anything DEFERRED
 * (volumes composite later, in the main frame, where the foreign pass's matrices are meaningless). Plain
 * geometry keeps drawing — the foliage sway proxies stand in for cut blocks and must cast into the tiles.
 * The rule for addons: ANY new re-entrant render of BBS forms must be wrapped in
 * {@code enter(...)}/{@code exit()} in a try/finally.</p>
 *
 * <p>Render thread only — no synchronisation, deliberately. Lives in the main source set (not client) so
 * addons compiling against the hub see it from either environment; it has no client-only dependencies.</p>
 */
public final class BbsVfxForeignPass
{
    /** Nesting depth — a counter, not a boolean: a mask pass may sit inside a shadow bake. */
    private static int depth;

    /** Name of the innermost pass, for debugging/telemetry only. */
    private static String tag = "";

    private BbsVfxForeignPass()
    {}

    /** Open a foreign pass. ALWAYS pair with {@link #exit()} in a {@code finally}. */
    public static void enter(String name)
    {
        depth++;
        tag = name == null ? "" : name;
    }

    /** Close the innermost foreign pass. Clamped at zero, so an unbalanced call can't wedge the flag on. */
    public static void exit()
    {
        if (depth > 0)
        {
            depth--;
        }

        if (depth == 0)
        {
            tag = "";
        }
    }

    public static boolean isActive()
    {
        return depth > 0;
    }

    public static String tag()
    {
        return tag;
    }

    /** Escape hatch if an addon ever leaks a pass (a stuck flag would kill the wind for the session). */
    public static void reset()
    {
        depth = 0;
        tag = "";
    }
}
