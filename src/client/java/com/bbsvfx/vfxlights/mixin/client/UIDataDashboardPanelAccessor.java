package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the dashboard panel's open data object (the film, for UIFilmPanel) so the
 * disabled-replay light sweeper can walk the film being edited without a UI event.
 */
@Mixin(value = UIDataDashboardPanel.class, remap = false)
public interface UIDataDashboardPanelAccessor
{
    @Accessor("data")
    ValueGroup vfxlights$getData();
}
