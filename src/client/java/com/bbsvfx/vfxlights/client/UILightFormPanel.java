package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.utils.colors.Color;
import com.bbsvfx.vfxlights.forms.LightForm;

/**
 * The tab a gaffer lives in: what the lamp is, how bright, how far, what shape it has and whether it
 * throws shadows. Everything that is seasoning — the air, the stylised looks — is a tab away.
 *
 * <p><b>Order is fixed here, not by the subclass.</b> A light reads top-down the way it is decided:
 * the LOOK it starts from, then its exposure, then the shape of this particular lamp, then shadows,
 * then what it is allowed to touch. Subclasses hand their shape section to
 * {@link #buildLightTab(UILightSection...)} rather than adding it themselves, which is how the area
 * light's colour stopped being the fifth thing on the panel.</p>
 */
public abstract class UILightFormPanel <T extends LightForm> extends UILightPanelBase<T>
{
    public final UIToggle advancedToggle;

    public final UIButton preset;

    public final UIColor color;
    public final UITrackpad intensity;
    public final UITrackpad range;
    public final UIToggle falloffPhysical;
    public final UISliderTrackpad flicker;
    public final UITrackpad flickerSpeed;
    public final UIToggle useTemperature;
    public final UITrackpad temperature;

    public final UIToggle shadows;
    public final UITrackpad shadowSoftness;
    public final UICirculate iesProfile;

    public final UIToggle affectBlocks;
    public final UIToggle affectEntities;

    public UILightFormPanel(UIForm editor)
    {
        super(editor);

        this.advancedToggle = new UIToggle(LightKeys.ADVANCED, (t) -> this.setAdvanced(t.getValue()));
        this.advancedToggle.tooltip(LightKeys.ADVANCED_TOOLTIP);
        this.advancedToggle.setValue(isAdvanced());

        this.preset = new UIButton(LightKeys.PRESET_CUSTOM, (b) -> this.openPresets());
        this.preset.tooltip(LightKeys.PRESET_TOOLTIP);

        /* The picker speaks ARGB (its callback is getARGBColor, its setColor reads ARGB). Reading the
         * callback int as RGBA put the alpha in the BLUE byte, so a warm colour came back a fraction
         * transparent — "one tone darker" on re-entry. set(int) reads ARGB; getARGBColor writes it. */
        this.color = new UIColor(this.dial((v) -> this.form.color.set(new Color().set(v)))).withAlpha();
        this.color.tooltip(LightKeys.COLOUR_TOOLTIP);

        this.intensity = new UITrackpad(this.dial((v) -> this.form.intensity.set(v.floatValue())));
        this.intensity.limit(0F, 50F).values(0.05F);
        this.intensity.tooltip(LightKeys.INTENSITY_TOOLTIP);

        this.range = new UITrackpad(this.dial((v) -> this.form.range.set(v.floatValue())));
        this.range.limit(0.1F, 256F).values(0.5F);
        this.range.tooltip(LightKeys.RANGE_TOOLTIP);

        this.falloffPhysical = new UIToggle(LightKeys.FALLOFF,
            this.dial((t) -> this.form.falloffPhysical.set(t.getValue())));
        this.falloffPhysical.tooltip(LightKeys.FALLOFF_TOOLTIP);

        this.flicker = new UISliderTrackpad(this.dial((v) -> this.form.flicker.set(v.floatValue())));
        this.flicker.limit(0F, 1F).values(0.02F);
        this.flicker.tooltip(LightKeys.FLICKER_TOOLTIP);
        this.flickerSpeed = new UITrackpad(this.dial((v) -> this.form.flickerSpeed.set(v.floatValue())));
        this.flickerSpeed.limit(0.05F, 20F).values(0.05F);
        this.flickerSpeed.tooltip(LightKeys.FLICKER_TEMPO_TOOLTIP);

        this.useTemperature = new UIToggle(LightKeys.USE_TEMPERATURE,
            this.dial((t) -> this.form.useTemperature.set(t.getValue())));
        this.useTemperature.tooltip(LightKeys.USE_TEMPERATURE_TOOLTIP);

        /* Kelvin steps of 50 — finer than anyone can see, coarser than a per-unit drag. */
        this.temperature = new UITrackpad(this.dial((v) -> this.form.temperature.set(v.floatValue())));
        this.temperature.limit(1000F, 15000F).values(50F);
        this.temperature.tooltip(LightKeys.TEMPERATURE_TOOLTIP);

        this.shadows = new UIToggle(LightKeys.CAST_SHADOWS, this.dial((t) -> this.form.shadows.set(t.getValue())));
        this.shadows.tooltip(LightKeys.CAST_SHADOWS_TOOLTIP);

        this.shadowSoftness = new UITrackpad(this.dial((v) -> this.form.shadowSoftness.set(v.floatValue())));
        this.shadowSoftness.limit(0F, 2F).values(0.02F);
        this.shadowSoftness.tooltip(LightKeys.SOFTNESS_TOOLTIP);

        this.iesProfile = new UICirculate(this.dial((b) -> this.form.iesProfile.set(b.getValue())));
        this.iesProfile.addLabel(LightKeys.IES_NONE);
        this.iesProfile.addLabel(LightKeys.IES_DOWNLIGHT);
        this.iesProfile.addLabel(LightKeys.IES_BATWING);
        this.iesProfile.addLabel(LightKeys.IES_RING);
        this.iesProfile.tooltip(LightKeys.BEAM_PROFILE_TOOLTIP);

        this.affectBlocks = new UIToggle(LightKeys.LIGHT_BLOCKS, this.dial((t) -> this.form.affectBlocks.set(t.getValue())));
        this.affectBlocks.tooltip(LightKeys.LIGHT_BLOCKS_TOOLTIP);
        this.affectEntities = new UIToggle(LightKeys.LIGHT_ENTITIES, this.dial((t) -> this.form.affectEntities.set(t.getValue())));
        this.affectEntities.tooltip(LightKeys.LIGHT_ENTITIES_TOOLTIP);
    }

    /* Per-type answers */

    /** Whether the built-in looks mean anything here (they stamp shadows, rim, cel — an ambient has none). */
    protected boolean supportsPresets()
    {
        return true;
    }

    /** Whether this light has a shadow map at all. */
    protected boolean supportsShadows()
    {
        return true;
    }

    /** Area lights are always physical (analytic form factors), so the switch would be a dead dial. */
    protected boolean supportsFalloffToggle()
    {
        return true;
    }

    /**
     * Assemble the tab. Call at the END of a subclass constructor, handing over the sections that
     * describe THIS lamp's shape — they are placed after the exposure block, not before it.
     */
    protected void buildLightTab(UILightSection... shape)
    {
        if (this.supportsPresets())
        {
            UILightSection look = this.section(LightKeys.SECTION_LOOK);

            /* The header names it, the button shows which look is on — no "Look: Look" row. */
            look.row(this.preset);

            this.options.add(look.section);
        }

        UILightSection light = this.section(LightKeys.SECTION_LIGHT);

        light.row(row(LightKeys.COLOUR, this.color));
        light.row(row(LightKeys.INTENSITY, this.intensity));
        light.row(row(LightKeys.RANGE, this.range));

        if (this.supportsFalloffToggle())
        {
            light.row(advancedOr(() -> this.form.falloffPhysical.get()), this.falloffPhysical);
        }

        light.row(advancedOr(() -> this.form.flicker.get() > 0F), row(LightKeys.FLICKER, this.flicker));
        light.row(() -> this.form.flicker.get() > 0F, row(LightKeys.FLICKER_TEMPO, this.flickerSpeed));
        light.row(this.useTemperature);
        light.row(() -> this.form.useTemperature.get(), row(LightKeys.TEMPERATURE, this.temperature));
        light.row(advancedOr(() -> this.form.iesProfile.get() != 0), row(LightKeys.BEAM_PROFILE, this.iesProfile));

        this.options.add(light.section);

        for (UILightSection section : shape)
        {
            this.options.add(section.section);
        }

        if (this.supportsShadows())
        {
            UILightSection shadowing = this.section(LightKeys.SECTION_SHADOWS);

            shadowing.row(this.shadows);
            shadowing.row(() -> this.form.shadows.get(), row(LightKeys.SOFTNESS, this.shadowSoftness));

            this.options.add(shadowing.section);
        }

        UILightSection affects = this.section(LightKeys.SECTION_AFFECTS);

        affects.row(this.affectBlocks);
        affects.row(this.affectEntities);

        this.options.add(affects.section);

        /* The Simple/Advanced switch sits above everything: it is a statement about the panel. */
        this.options.prepend(this.advancedToggle);
    }

    /** Update the picker's name — called when a dial elsewhere in the editor drops the look to custom. */
    public void refreshPreset()
    {
        if (this.form == null)
        {
            return;
        }

        LightPresets.Preset stamped = LightPresets.byId(this.form.preset.get());

        /* Resolved here rather than kept as a key: the "*" has to ride along with the name, and it
         * still follows the language because the label is rebuilt on every startEdit. */
        this.preset.label = stamped == null
            ? LightKeys.PRESET_CUSTOM
            : IKey.constant(stamped.label.get() + (this.form.presetTweaked.get() ? " *" : ""));
    }

    private void openPresets()
    {
        if (this.form == null)
        {
            return;
        }

        /* A list overlay rather than a UICirculate: eight looks meant eight clicks to reach the last
         * one, with no way to see what you were cycling through. BBS picks models and textures the
         * same way. */
        UIListOverlayPanel list = new UIListOverlayPanel(LightKeys.SECTION_LOOK, (label) ->
        {
            LightPresets.Preset picked = LightPresets.byLabel(label);

            if (picked != null)
            {
                LightPresets.apply(this.form, picked.id);
                /* Stamping writes the values directly, no dial fires — push the look the same way. */
                com.bbsvfx.vfxlights.client.light.FormLightCollector.refreshFromForm(this.form);
                this.refreshEditor();
            }
        });

        list.addValues(LightPresets.labels());

        LightPresets.Preset stamped = LightPresets.byId(this.form.preset.get());

        if (stamped != null)
        {
            list.setValue(stamped.label.get());
        }

        UIOverlay.addOverlay(this.getContext(), list, 200, 0.6F);
    }

    @Override
    public void startEdit(T form)
    {
        this.advancedToggle.setValue(isAdvanced());

        this.color.setColor(form.color.get().getARGBColor());
        this.intensity.setValue(form.intensity.get());
        this.range.setValue(form.range.get());
        this.falloffPhysical.setValue(form.falloffPhysical.get());
        this.flicker.setValue(form.flicker.get());
        this.flickerSpeed.setValue(form.flickerSpeed.get());
        this.useTemperature.setValue(form.useTemperature.get());
        this.temperature.setValue(form.temperature.get());
        this.shadows.setValue(form.shadows.get());
        this.shadowSoftness.setValue(form.shadowSoftness.get());
        this.iesProfile.setValue(form.iesProfile.get());
        this.affectBlocks.setValue(form.affectBlocks.get());
        this.affectEntities.setValue(form.affectEntities.get());

        /* After the widgets, before the sections refresh: the preset name is read from the form. */
        super.startEdit(form);

        this.refreshPreset();
    }
}
