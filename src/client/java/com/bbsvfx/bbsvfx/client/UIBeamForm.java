package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import com.bbsvfx.bbsvfx.forms.BeamForm;

/**
 * Form editor wrapper for {@link BeamForm}: registers the {@link UIBeamFormPanel} plus the standard
 * form panels (transform, etc.). No custom gizmo — the beam rises along the form's own +Y axis, so the
 * normal actor transform gizmo already aims and scales it.
 */
public class UIBeamForm extends UIForm<BeamForm>
{
    public UIBeamForm()
    {
        super();

        this.defaultPanel = new UIBeamFormPanel(this);

        this.registerPanel(this.defaultPanel, IKey.constant("Beam"), BbsVfxIcons.BEAM);
        this.registerDefaultPanels();
    }
}
