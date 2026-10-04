package com.bbsvfx.vfxlights;

import com.bbsvfx.bbsvfx.module.VfxModule;
import com.bbsvfx.bbsvfx.module.VfxModules;

/**
 * VFX LIGHTS as a module of the BBS VFX hub. Registered through the {@code "vfx-module"} entrypoint;
 * the hub shows its on/off toggle in the "VFX" settings category, and the mod's features gate on
 * {@link com.bbsvfx.bbsvfx.module.VfxModules#isEnabled(String)} with this id.
 */
public class VfxLightsModule implements VfxModule
{
    /** Shared id used both here and by every feature gate; keep in sync with the mod id. */
    public static final String ID = "vfxlights";

    /** Master gate every feature (render passes, mixins, form registration) checks. Off ⇒ the mod no-ops. */
    public static boolean isEnabled()
    {
        return VfxModules.isEnabled(ID);
    }

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public String displayName()
    {
        return "VFX LIGHTS";
    }
}
