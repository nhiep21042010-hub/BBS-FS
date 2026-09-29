package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import com.bbsvfx.bbsvfx.forms.DomeForm;

/**
 * Form editor wrapper for {@link DomeForm}: the {@link UIDomeFormPanel} plus the standard form panels.
 * The dome expands about the form origin, so the normal actor transform gizmo places and scales it.
 */
public class UIDomeForm extends UIForm<DomeForm>
{
    public UIDomeForm()
    {
        super();

        this.defaultPanel = new UIDomeFormPanel(this);

        this.registerPanel(this.defaultPanel, IKey.constant("Dome"), BbsVfxIcons.DOME);
        this.registerDefaultPanels();
    }
}
