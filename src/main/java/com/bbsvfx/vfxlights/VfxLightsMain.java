package com.bbsvfx.vfxlights;

import net.fabricmc.api.ModInitializer;
import com.bbsvfx.vfxlights.forms.values.LightFactories;

/**
 * Common entry point. Lighting is entirely a client-side concern, so this exists to satisfy the loader
 * and to hold anything that must happen before the registries freeze.
 */
public class VfxLightsMain implements ModInitializer
{
    @Override
    public void onInitialize()
    {
        /* The grouped light properties must be in BBS's factory table before any film is read: a
         * keyframe channel stores its factory by KEY and silently loses its track if the lookup
         * fails. Building a light form registers them too, but a film can be loaded first. */
        LightFactories.register();
    }
}
