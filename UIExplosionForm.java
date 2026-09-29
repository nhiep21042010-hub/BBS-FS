package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * Form editor for {@code bbsvfx:explosion}: the Destruction Box editor's gizmo machinery (the epicenter
 * gizmo is always active — an explosion is always the physics sim) with the explosion-shaped panel.
 */
public class UIExplosionForm extends UIDestructionBoxForm
{
    @Override
    protected UIDestructionBoxFormPanel bbsvfx$createPanel()
    {
        return new UIExplosionFormPanel(this);
    }

    @Override
    protected IKey bbsvfx$panelTitle()
    {
        return IKey.constant("Explosion");
    }

    @Override
    protected Icon bbsvfx$panelIcon()
    {
        return BbsVfxIcons.EXPLOSION;
    }
}
