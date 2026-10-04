package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Color;
import com.bbsvfx.vfxlights.forms.AmbientLightForm;
import com.bbsvfx.vfxlights.forms.LightForm;

/**
 * Editor for ambient fill: which model of ambience, over what volume, and how hard its edge is.
 *
 * <p>An ambient has no shadow map, no beam, no direction and no specular, so it gets neither the Air
 * nor the Style tab, and neither the built-in looks (which stamp exactly those dead dials) nor the
 * falloff switch. What survives is the exposure block — temperature included, since
 * {@code effectiveColor} applies it — the volume, and the affect flags, which both backends honour.</p>
 */
public class UIAmbientLightFormPanel extends UILightFormPanel<AmbientLightForm> implements LightGuideDrag.GuideSync
{
    public final UICirculate mode;
    public final UICirculate volume;
    public final UITrackpad sizeX;
    public final UITrackpad sizeY;
    public final UITrackpad sizeZ;
    public final UISliderTrackpad edgeFalloff;
    public final UIColor groundColor;
    public final UISliderTrackpad occlusion;

    public UIAmbientLightFormPanel(UIForm editor)
    {
        super(editor);

        this.mode = new UICirculate(this.dial((b) -> this.form.mode.set(b.getValue())));
        this.mode.addLabel(LightKeys.MODE_ZONE);
        this.mode.addLabel(LightKeys.MODE_HEMISPHERE);
        this.mode.tooltip(LightKeys.MODE_TOOLTIP);

        this.volume = new UICirculate(this.dial((b) -> this.form.volume.set(b.getValue())));
        this.volume.addLabel(LightKeys.VOLUME_SPHERE);
        this.volume.addLabel(LightKeys.VOLUME_BOX);
        this.volume.tooltip(LightKeys.VOLUME_TOOLTIP);

        this.sizeX = new UITrackpad(this.dial((v) -> this.form.sizeX.set(v.floatValue())));
        this.sizeX.limit(0.1F, 256F).values(0.5F);
        this.sizeY = new UITrackpad(this.dial((v) -> this.form.sizeY.set(v.floatValue())));
        this.sizeY.limit(0.1F, 256F).values(0.5F);
        this.sizeZ = new UITrackpad(this.dial((v) -> this.form.sizeZ.set(v.floatValue())));
        this.sizeZ.limit(0.1F, 256F).values(0.5F);

        this.edgeFalloff = new UISliderTrackpad(this.dial((v) -> this.form.edgeFalloff.set(v.floatValue())));
        this.edgeFalloff.limit(0F, 1F).values(0.01F);
        this.edgeFalloff.tooltip(LightKeys.EDGE_FALLOFF_TOOLTIP);

        this.groundColor = new UIColor(this.dial((v) -> this.form.groundColor.set(Color.rgba(v)))).withAlpha();
        this.groundColor.tooltip(LightKeys.GROUND_BOUNCE_TOOLTIP);

        this.occlusion = new UISliderTrackpad(this.dial((v) -> this.form.occlusion.set(v.floatValue())));
        this.occlusion.limit(0F, 1F).values(0.01F);
        this.occlusion.tooltip(LightKeys.OCCLUSION_TOOLTIP);

        UILightSection ambience = this.section(LightKeys.SECTION_AMBIENCE);

        ambience.row(this.mode);
        ambience.row(() -> this.form.getMode() == AmbientLightForm.Mode.HEMISPHERE,
            row(LightKeys.GROUND_BOUNCE, this.groundColor));
        ambience.row(advancedOr(() -> this.form.occlusion.get() != 1F), row(LightKeys.OCCLUSION, this.occlusion));

        UILightSection zone = this.section(LightKeys.SECTION_VOLUME);

        zone.row(() -> this.form.getMode() == AmbientLightForm.Mode.ZONE, this.volume);
        zone.row(this::isBox, UI.label(LightKeys.BOX_SIZE));
        zone.row(this::isBox, UI.row(this.sizeX, this.sizeY, this.sizeZ));
        zone.row(() -> this.form.getMode() == AmbientLightForm.Mode.ZONE, row(LightKeys.EDGE_FALLOFF, this.edgeFalloff));

        this.buildLightTab(ambience, zone);
    }

    private boolean isBox()
    {
        return this.form.getMode() == AmbientLightForm.Mode.ZONE
            && this.form.getVolume() == AmbientLightForm.Volume.BOX;
    }

    /** The looks stamp shadows, rim and cel — every one of them dead on an ambient. */
    @Override
    protected boolean supportsPresets()
    {
        return false;
    }

    /** The shadow mapper skips this type outright. */
    @Override
    protected boolean supportsShadows()
    {
        return false;
    }

    @Override
    protected boolean supportsFalloffToggle()
    {
        return false;
    }

    @Override
    public void startEdit(AmbientLightForm form)
    {
        /* The gizmo drag syncs its trackpads back into whichever panel is showing the lamp. */
        LightGuideDrag.bindPanel(this);

        this.mode.setValue(form.mode.get());
        this.volume.setValue(form.volume.get());
        this.sizeX.setValue(form.sizeX.get());
        this.sizeY.setValue(form.sizeY.get());
        this.sizeZ.setValue(form.sizeZ.get());
        this.edgeFalloff.setValue(form.edgeFalloff.get());
        /* ARGB, like every other picker here: Color.getRGBAColor shifts by 8 + alpha (operator
         * precedence in BBS), so the swatch came back a colour nobody picked. */
        this.groundColor.setColor(form.groundColor.get().getARGBColor());
        this.occlusion.setValue(form.occlusion.get());

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
    }
}
