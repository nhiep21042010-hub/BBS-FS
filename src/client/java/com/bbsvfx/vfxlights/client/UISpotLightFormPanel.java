package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.forms.SpotLightForm;

/** Editor for the spot light: the two cone angles and the size of the emitting surface. */
public class UISpotLightFormPanel extends UILightFormPanel<SpotLightForm> implements LightGuideDrag.GuideSync
{
    public final UITrackpad angle;
    public final UITrackpad innerAngle;
    public final UITrackpad sourceRadius;

    public UISpotLightFormPanel(UIForm editor)
    {
        super(editor);

        this.angle = new UITrackpad(this.dial((v) -> this.form.angle.set(v.floatValue())));
        this.angle.degrees().limit(1F, 179F);
        this.angle.tooltip(LightKeys.OUTER_ANGLE_TOOLTIP);

        this.innerAngle = new UITrackpad(this.dial((v) -> this.form.innerAngle.set(v.floatValue())));
        this.innerAngle.degrees().limit(0F, 179F);
        this.innerAngle.tooltip(LightKeys.INNER_ANGLE_TOOLTIP);

        this.sourceRadius = new UITrackpad(this.dial((v) -> this.form.sourceRadius.set(v.floatValue())));
        this.sourceRadius.limit(0F, 16F).values(0.01F);
        this.sourceRadius.tooltip(LightKeys.BULB_SIZE_TOOLTIP);

        UILightSection cone = this.section(LightKeys.SECTION_CONE);

        cone.row(row(LightKeys.OUTER_ANGLE, this.angle));
        cone.row(row(LightKeys.INNER_ANGLE, this.innerAngle));
        cone.row(row(LightKeys.BULB_SIZE, this.sourceRadius));

        this.buildLightTab(cone);
    }

    @Override
    public void startEdit(SpotLightForm form)
    {
        /* The gizmo drag syncs its trackpads back into whichever panel is showing the lamp. */
        LightGuideDrag.bindPanel(this);

        this.angle.setValue(form.angle.get());
        this.innerAngle.setValue(form.innerAngle.get());
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

        this.range.setValue(this.form.range.get());
        this.angle.setValue(this.form.angle.get());
        this.innerAngle.setValue(this.form.innerAngle.get());
    }
}
