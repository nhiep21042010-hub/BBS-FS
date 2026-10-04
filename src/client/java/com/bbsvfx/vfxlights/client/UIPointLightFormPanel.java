package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.forms.PointLightForm;

/** Editor for the point light: a bulb with a size. */
public class UIPointLightFormPanel extends UILightFormPanel<PointLightForm> implements LightGuideDrag.GuideSync
{
    public final UITrackpad sourceRadius;

    public UIPointLightFormPanel(UIForm editor)
    {
        super(editor);

        this.sourceRadius = new UITrackpad(this.dial((v) -> this.form.sourceRadius.set(v.floatValue())));
        this.sourceRadius.limit(0F, 16F).values(0.01F);
        this.sourceRadius.tooltip(LightKeys.BULB_SIZE_TOOLTIP);

        UILightSection bulb = this.section(LightKeys.SECTION_BULB);

        bulb.row(row(LightKeys.BULB_SIZE, this.sourceRadius));

        this.buildLightTab(bulb);
    }

    @Override
    public void startEdit(PointLightForm form)
    {
        /* The gizmo drag syncs its trackpads back into whichever panel is showing the lamp. */
        LightGuideDrag.bindPanel(this);

        this.sourceRadius.setValue(form.sourceRadius.get());

        super.startEdit(form);
    }

    /** A gizmo drag wrote the form: catch the trackpads up (setValue fires no callback — safe). */
    @Override
    public void syncShape(LightForm form)
    {
        if (form != this.form)
        {
            return;
        }

        this.sourceRadius.setValue(this.form.sourceRadius.get());
    }
}
