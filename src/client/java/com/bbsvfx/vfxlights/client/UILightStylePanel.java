package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.forms.values.LightStyle;
import com.bbsvfx.vfxlights.forms.values.LightToon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How surfaces ANSWER this light, and what the camera makes of it: the edge along a silhouette, the
 * inked contour, light through thin things, the two-tone toon ramp, and the lens flare.
 *
 * <p>Seasoning, not the meal — which is why it is a tab of its own rather than eight more rows under
 * the exposure controls.</p>
 */
public class UILightStylePanel <T extends LightForm> extends UILightPanelBase<T>
{
    /** Flare styles, in the ordinal order the form serialises. Append only. */
    private static final List<IKey> FLARE_STYLES = Arrays.asList(
        LightKeys.FLARE_STAR, LightKeys.FLARE_ANAMORPHIC, LightKeys.FLARE_CLEAN, LightKeys.FLARE_JJ,
        LightKeys.FLARE_SUN, LightKeys.FLARE_SEARCHLIGHT, LightKeys.FLARE_TACTICAL,
        LightKeys.FLARE_VINTAGE, LightKeys.FLARE_BOKEH);

    /** Outline targets, in the ordinal order the form serialises (0 both, 1 models, 2 world). */
    private static final List<IKey> OUTLINE_TARGETS = Arrays.asList(
        LightKeys.OUTLINE_TARGET_BOTH, LightKeys.OUTLINE_TARGET_MODELS, LightKeys.OUTLINE_TARGET_WORLD);

    /** Outline blend modes, in the ordinal order the form serialises (0 add, 1 screen, 2 overlay). */
    private static final List<IKey> OUTLINE_BLENDS = Arrays.asList(
        LightKeys.OUTLINE_BLEND_ADD, LightKeys.OUTLINE_BLEND_SCREEN, LightKeys.OUTLINE_BLEND_OVERLAY);

    public final UISliderTrackpad rim;
    public final UISliderTrackpad rimWidth;
    public final UISliderTrackpad specRim;
    public final UISliderTrackpad translucency;
    public final UITrackpad outline;
    public final UITrackpad outlineWidth;
    public final UISliderTrackpad outlineBlur;
    public final UITrackpad outlineInner;
    public final UIButton outlineTarget;
    public final UIButton outlineBlend;

    public final UIToggle cel;
    public final UISliderTrackpad celSoftness;
    public final UISliderTrackpad celShadowShift;
    public final UISliderTrackpad celShadowLevel;

    public final UITrackpad flare;
    public final UIButton flareStyle;

    public UILightStylePanel(UIForm editor)
    {
        super(editor);

        this.rim = new UISliderTrackpad(this.dial((f) -> f.style, (v) -> this.form.style.getOriginalValue().rim = v.floatValue()));
        this.rim.limit(0F, 2F).values(0.02F);
        this.rim.tooltip(LightKeys.RIM_TOOLTIP);

        this.rimWidth = new UISliderTrackpad(this.dial((f) -> f.style, (v) -> this.form.style.getOriginalValue().rimWidth = v.floatValue()));
        this.rimWidth.limit(0F, 1F).values(0.02F);
        this.rimWidth.tooltip(LightKeys.RIM_WIDTH_TOOLTIP);

        this.specRim = new UISliderTrackpad(this.dial((f) -> f.style, (v) -> this.form.style.getOriginalValue().sheen = v.floatValue()));
        this.specRim.limit(0F, 2F).values(0.02F);
        this.specRim.tooltip(LightKeys.GRAZING_SHEEN_TOOLTIP);

        this.translucency = new UISliderTrackpad(this.dial((f) -> f.style, (v) -> this.form.style.getOriginalValue().translucency = v.floatValue()));
        this.translucency.limit(0F, 1F).values(0.02F);
        this.translucency.tooltip(LightKeys.TRANSLUCENCY_TOOLTIP);

        this.outline = new UITrackpad(this.dial((f) -> f.style, (v) -> this.form.style.getOriginalValue().outline = v.floatValue()));
        this.outline.limit(0F).values(0.02F);
        this.outline.tooltip(LightKeys.OUTLINE_TOOLTIP);

        this.outlineWidth = new UITrackpad(this.dial((f) -> f.style, (v) -> this.form.style.getOriginalValue().outlineWidth = v.floatValue()));
        this.outlineWidth.limit(1F, 4F).values(0.5F);
        this.outlineWidth.tooltip(LightKeys.OUTLINE_WIDTH_TOOLTIP);

        this.outlineBlur = new UISliderTrackpad(this.dial((f) -> f.style, (v) -> this.form.style.getOriginalValue().outlineBlur = v.floatValue()));
        this.outlineBlur.limit(0F, 1F).values(0.02F);
        this.outlineBlur.tooltip(LightKeys.OUTLINE_BLUR_TOOLTIP);

        this.outlineInner = new UITrackpad(this.dial((f) -> f.style, (v) -> this.form.style.getOriginalValue().outlineInner = v.floatValue()));
        this.outlineInner.limit(0F).values(0.02F);
        this.outlineInner.tooltip(LightKeys.OUTLINE_INNER_TOOLTIP);

        this.outlineTarget = new UIButton(OUTLINE_TARGETS.get(0), (b) -> this.openOutlineTargets());
        this.outlineTarget.tooltip(LightKeys.OUTLINE_TARGET_TOOLTIP);

        this.outlineBlend = new UIButton(OUTLINE_BLENDS.get(0), (b) -> this.openOutlineBlends());
        this.outlineBlend.tooltip(LightKeys.OUTLINE_BLEND_TOOLTIP);

        this.cel = new UIToggle(LightKeys.TOON_SHADING, this.dial((f) -> f.toon, (t) -> this.form.toon.getOriginalValue().enabled = t.getValue()));
        this.cel.tooltip(LightKeys.TOON_SHADING_TOOLTIP);

        this.celSoftness = new UISliderTrackpad(this.dial((f) -> f.toon, (v) -> this.form.toon.getOriginalValue().softness = v.floatValue()));
        this.celSoftness.limit(0F, 0.5F).values(0.01F);
        this.celSoftness.tooltip(LightKeys.EDGE_SOFTNESS_TOOLTIP);

        this.celShadowShift = new UISliderTrackpad(this.dial((f) -> f.toon, (v) -> this.form.toon.getOriginalValue().shadowTint = v.floatValue()));
        this.celShadowShift.limit(0F, 1F).values(0.02F);
        this.celShadowShift.tooltip(LightKeys.SHADOW_TINT_TOOLTIP);

        this.celShadowLevel = new UISliderTrackpad(this.dial((f) -> f.toon, (v) -> this.form.toon.getOriginalValue().shadowLevel = v.floatValue()));
        this.celShadowLevel.limit(0F, 1F).values(0.02F);
        this.celShadowLevel.tooltip(LightKeys.SHADOW_BRIGHTNESS_TOOLTIP);

        this.flare = new UITrackpad(this.dial((v) -> this.form.flare.set(v.floatValue())));
        this.flare.limit(0F, 1F).values(0.02F);
        this.flare.tooltip(LightKeys.LENS_FLARE_TOOLTIP);

        this.flareStyle = new UIButton(FLARE_STYLES.get(0), (b) -> this.openFlareStyles());
        this.flareStyle.tooltip(LightKeys.FLARE_STYLE_TOOLTIP);

        UILightSection surfaces = this.section(LightKeys.SECTION_SURFACES);

        surfaces.row(row(LightKeys.RIM, this.rim));
        surfaces.row(() -> this.form.style.getOriginalValue().rim > 0F, row(LightKeys.RIM_WIDTH, this.rimWidth));
        surfaces.row(row(LightKeys.TRANSLUCENCY, this.translucency));
        surfaces.row(advancedOr(() -> this.form.style.getOriginalValue().sheen != 1F), row(LightKeys.GRAZING_SHEEN, this.specRim));
        surfaces.row(row(LightKeys.OUTLINE, this.outline));
        surfaces.row(() -> this.form.style.getOriginalValue().outline > 0F, row(LightKeys.OUTLINE_WIDTH, this.outlineWidth));
        surfaces.row(() -> this.form.style.getOriginalValue().outline > 0F, row(LightKeys.OUTLINE_BLUR, this.outlineBlur));
        surfaces.row(row(LightKeys.OUTLINE_INNER, this.outlineInner));
        surfaces.row(() -> this.form.style.getOriginalValue().outline > 0F
            || this.form.style.getOriginalValue().outlineInner > 0F, row(LightKeys.OUTLINE_TARGET, this.outlineTarget));
        surfaces.row(() -> this.form.style.getOriginalValue().outline > 0F
            || this.form.style.getOriginalValue().outlineInner > 0F, row(LightKeys.OUTLINE_BLEND, this.outlineBlend));

        UILightSection toon = this.section(LightKeys.SECTION_TOON);

        toon.row(this.cel);
        toon.row(() -> this.form.toon.getOriginalValue().enabled, row(LightKeys.EDGE_SOFTNESS, this.celSoftness));
        toon.row(() -> this.form.toon.getOriginalValue().enabled, row(LightKeys.SHADOW_TINT, this.celShadowShift));
        toon.row(() -> this.form.toon.getOriginalValue().enabled, row(LightKeys.SHADOW_BRIGHTNESS, this.celShadowLevel));

        UILightSection flare = this.section(LightKeys.SECTION_FLARE);

        flare.row(row(LightKeys.LENS_FLARE, this.flare));
        flare.row(() -> this.form.flare.get() > 0F, row(LightKeys.FLARE_STYLE, this.flareStyle));

        this.options.add(surfaces.section, toon.section, flare.section);
    }

    private void openFlareStyles()
    {
        if (this.form == null)
        {
            return;
        }

        /* Nine styles: a UICirculate meant eight clicks to reach the last one, blind. The list works
         * in resolved names, so it is built (and matched) against the language in force right now. */
        List<String> names = new ArrayList<>();

        for (IKey style : FLARE_STYLES)
        {
            names.add(style.get());
        }

        UIListOverlayPanel list = new UIListOverlayPanel(LightKeys.FLARE_STYLE_TITLE, (label) ->
        {
            int index = names.indexOf(label);

            if (index >= 0)
            {
                this.form.flareStyle.set(index);
                this.markCustom();
                this.flareStyle.label = FLARE_STYLES.get(index);
            }
        });

        list.addValues(names);
        list.setValue(styleLabel(this.form.flareStyle.get()).get());

        UIOverlay.addOverlay(this.getContext(), list, 200, 0.6F);
    }

    private static IKey styleLabel(int index)
    {
        return FLARE_STYLES.get(Math.max(0, Math.min(FLARE_STYLES.size() - 1, index)));
    }

    private void openOutlineTargets()
    {
        if (this.form == null)
        {
            return;
        }

        java.util.function.Consumer<Number> setter = this.dial((f) -> f.style,
            (v) -> this.form.style.getOriginalValue().outlineTarget = v.intValue());
        List<String> names = new ArrayList<>();

        for (IKey target : OUTLINE_TARGETS)
        {
            names.add(target.get());
        }

        UIListOverlayPanel list = new UIListOverlayPanel(LightKeys.OUTLINE_TARGET_TITLE, (label) ->
        {
            int index = names.indexOf(label);

            if (index >= 0)
            {
                setter.accept(index);
                this.outlineTarget.label = OUTLINE_TARGETS.get(index);
            }
        });

        list.addValues(names);
        list.setValue(OUTLINE_TARGETS.get(Math.max(0, Math.min(2, this.form.style.getOriginalValue().outlineTarget))).get());

        UIOverlay.addOverlay(this.getContext(), list, 200, 0.6F);
    }

    private void openOutlineBlends()
    {
        if (this.form == null)
        {
            return;
        }

        java.util.function.Consumer<Number> setter = this.dial((f) -> f.style,
            (v) -> this.form.style.getOriginalValue().outlineBlend = v.intValue());
        List<String> names = new ArrayList<>();

        for (IKey blend : OUTLINE_BLENDS)
        {
            names.add(blend.get());
        }

        UIListOverlayPanel list = new UIListOverlayPanel(LightKeys.OUTLINE_BLEND_TITLE, (label) ->
        {
            int index = names.indexOf(label);

            if (index >= 0)
            {
                setter.accept(index);
                this.outlineBlend.label = OUTLINE_BLENDS.get(index);
            }
        });

        list.addValues(names);
        list.setValue(OUTLINE_BLENDS.get(Math.max(0, Math.min(2, this.form.style.getOriginalValue().outlineBlend))).get());

        UIOverlay.addOverlay(this.getContext(), list, 200, 0.6F);
    }

    @Override
    public void startEdit(T form)
    {
        LightStyle style = form.style.getOriginalValue();
        LightToon toon = form.toon.getOriginalValue();

        this.rim.setValue(style.rim);
        this.rimWidth.setValue(style.rimWidth);
        this.specRim.setValue(style.sheen);
        this.translucency.setValue(style.translucency);
        this.outline.setValue(style.outline);
        this.outlineWidth.setValue(style.outlineWidth);
        this.outlineBlur.setValue(style.outlineBlur);
        this.outlineInner.setValue(style.outlineInner);
        this.outlineTarget.label = OUTLINE_TARGETS.get(Math.max(0, Math.min(2, style.outlineTarget)));
        this.outlineBlend.label = OUTLINE_BLENDS.get(Math.max(0, Math.min(2, style.outlineBlend)));
        this.cel.setValue(toon.enabled);
        this.celSoftness.setValue(toon.softness);
        this.celShadowShift.setValue(toon.shadowTint);
        this.celShadowLevel.setValue(toon.shadowLevel);
        this.flare.setValue(form.flare.get());
        this.flareStyle.label = styleLabel(form.flareStyle.get());

        super.startEdit(form);
    }
}
