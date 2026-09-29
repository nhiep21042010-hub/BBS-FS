package com.bbsvfx.bbsvfx.module;

/**
 * A VFX suite module — a separate mod (e.g. VFX LIGHTS) that plugs into the BBS VFX core hub.
 *
 * <p>A module ships a {@code "vfx-module"} Fabric entrypoint pointing to its implementation; the core
 * collects them all ({@link VfxModules}) and shows one on/off toggle per module in BBS's "VFX" settings
 * category. The module gates its own features on {@link VfxModules#isEnabled(String)} so that turning it
 * off costs nothing (Fabric cannot unload a mod at runtime — "disabled" means its hooks no-op).</p>
 */
public interface VfxModule
{
    /** Stable identifier, also the config/toggle key. Lowercase, e.g. {@code "vfxlights"}. */
    String id();

    /** Human-readable name shown on the settings row, e.g. {@code "VFX LIGHTS"}. */
    String displayName();

    /** Called when the user turns the module on (and once at startup if it is enabled). */
    default void onEnabled()
    {
    }

    /** Called when the user turns the module off. */
    default void onDisabled()
    {
    }
}
