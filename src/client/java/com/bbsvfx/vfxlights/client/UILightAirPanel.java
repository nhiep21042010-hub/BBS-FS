package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.forms.values.LightAir;

/**
 * What the light does to the AIR between the lamp and the surface: the visible shaft, the fog it glows
 * into, the motes drifting in it, the prism colours, and the one bounce it throws back.
 *
 * <p>Type-agnostic on purpose — every one of these dials lives on {@link LightForm}, so one panel
 * serves the point, spot and area lights. The ambient light gets no such tab at all: it has no
 * direction and no beam, and the whole set would be dead dials.</p>
 */
public class UILightAirPanel <T extends LightForm> extends UILightPanelBase<T>
{
    public final UITrackpad volumetric;
    public final UITrackpad haze;
    public final UISliderTrackpad dust;
    public final UITrackpad dustSize;
    public final UISliderTrackpad dispersion;
    public final UITrackpad dispersionScale;
    public final UISliderTrackpad bounce;

    public UILightAirPanel(UIForm editor)
    {
        super(editor);

        this.volumetric = new UITrackpad(this.dial((f) -> f.air, (v) -> this.form.air.getOriginalValue().beam = v.floatValue()));
        this.volumetric.limit(0F).values(0.05F);
        this.volumetric.tooltip(LightKeys.BEAM_TOOLTIP);

        this.haze = new UITrackpad(this.dial((f) -> f.air, (v) -> this.form.air.getOriginalValue().haze = v.floatValue()));
        this.haze.limit(0F).values(0.05F);
        this.haze.tooltip(LightKeys.HAZE_TOOLTIP);

        this.dust = new UISliderTrackpad(this.dial((f) -> f.air, (v) -> this.form.air.getOriginalValue().dust = v.floatValue()));
        this.dust.limit(0F, 1F).values(0.02F);
        this.dust.tooltip(LightKeys.DUST_TOOLTIP);

        this.dustSize = new UITrackpad(this.dial((f) -> f.air, (v) -> this.form.air.getOriginalValue().dustSize = v.floatValue()));
        this.dustSize.limit(0.1F, 8F).values(0.05F);
        this.dustSize.tooltip(LightKeys.MOTE_SIZE_TOOLTIP);

        this.dispersion = new UISliderTrackpad(this.dial((f) -> f.air, (v) -> this.form.air.getOriginalValue().prism = v.floatValue()));
        this.dispersion.limit(0F, 1F).values(0.02F);
        this.dispersion.tooltip(LightKeys.PRISM_TOOLTIP);

        this.dispersionScale = new UITrackpad(this.dial((f) -> f.air, (v) -> this.form.air.getOriginalValue().prismScale = v.floatValue()));
        this.dispersionScale.limit(0.1F, 4F).values(0.05F);
        this.dispersionScale.tooltip(LightKeys.PRISM_SCALE_TOOLTIP);

        this.bounce = new UISliderTrackpad(this.dial((f) -> f.air, (v) -> this.form.air.getOriginalValue().bounce = v.floatValue()));
        this.bounce.limit(0F, 1F).values(0.02F);
        this.bounce.tooltip(LightKeys.BOUNCE_TOOLTIP);

        UILightSection air = this.section(LightKeys.SECTION_AIR);

        air.row(row(LightKeys.BEAM, this.volumetric));
        air.row(row(LightKeys.HAZE, this.haze));
        air.row(row(LightKeys.DUST, this.dust));
        air.row(() -> this.form.air.getOriginalValue().dust > 0F, row(LightKeys.MOTE_SIZE, this.dustSize));
        air.row(row(LightKeys.PRISM, this.dispersion));
        air.row(() -> this.form.air.getOriginalValue().prism > 0F, row(LightKeys.PRISM_SCALE, this.dispersionScale));
        air.row(row(LightKeys.BOUNCE, this.bounce));

        this.options.add(air.section);
    }

    @Override
    public void startEdit(T form)
    {
        LightAir air = form.air.getOriginalValue();

        this.volumetric.setValue(air.beam);
        this.haze.setValue(air.haze);
        this.dust.setValue(air.dust);
        this.dustSize.setValue(air.dustSize);
        this.dispersion.setValue(air.prism);
        this.dispersionScale.setValue(air.prismScale);
        this.bounce.setValue(air.bounce);

        super.startEdit(form);
    }
}
