package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UILabelFormPanel;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Color;
import org.spongepowered.asm.mixin.Mixin;
import com.bbsvfx.bbsvfx.client.BbsVfxFontManager;
import com.bbsvfx.bbsvfx.client.ILabelPanelSync;
import com.bbsvfx.bbsvfx.client.BbsVfxSection;
import com.bbsvfx.bbsvfx.client.BbsVfxUI;
import com.bbsvfx.bbsvfx.forms.BlendMode;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.ILabelExtras;

/**
 * Adds the addon label-overhaul controls to BBS's {@link UILabelFormPanel}. Stage 0/1: a tracking
 * (letter spacing) trackpad. Controls are appended to the panel's {@code options} list in the
 * constructor and synced from the form in {@code startEdit}.
 */
@Mixin(value = UILabelFormPanel.class, remap = false)
public abstract class UILabelFormPanelMixin implements ILabelPanelSync
{
    @Unique
    private UITrackpad bbsvfx$tracking;

    @Unique
    private UITrackpad bbsvfx$strokeWidth;

    @Unique
    private UIColor bbsvfx$strokeColor;

    @Unique
    private UIToggle bbsvfx$strokeOnly;

    @Unique
    private UIButton bbsvfx$blendMode;

    @Unique
    private UIButton bbsvfx$font;

    @Unique
    private UIButton bbsvfx$fontFolder;

    @Unique
    private UITrackpad bbsvfx$fontSize;

    @Unique
    private UIToggle bbsvfx$projection;

    @Unique
    private UITrackpad bbsvfx$projRange;

    @Unique
    private UITrackpad bbsvfx$projFade;

    @Unique
    private UIToggle bbsvfx$gradient;

    @Unique
    private UIColor bbsvfx$gradientStart;

    @Unique
    private UIColor bbsvfx$gradientEnd;

    @Unique
    private UITrackpad bbsvfx$gradientAngle;

    @Unique
    private LabelForm bbsvfx$form;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addControls(UIForm editor, CallbackInfo ci)
    {
        this.bbsvfx$tracking = new UITrackpad((v) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$tracking().set(v.floatValue());
            }
        });
        this.bbsvfx$tracking.limit(-10, 10).values(0.1F, 0.01F, 0.5F).increment(0.1F);

        this.bbsvfx$strokeWidth = new UITrackpad((v) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$strokeWidth().set(v.floatValue());
            }
        });
        this.bbsvfx$strokeWidth.limit(0, 5).values(0.1F, 0.01F, 0.5F).increment(0.1F);

        this.bbsvfx$strokeColor = new UIColor((c) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$strokeColor().set(Color.rgba(c));
            }
        }).withAlpha();

        this.bbsvfx$strokeOnly = new UIToggle(IKey.constant("Stroke only"), (b) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$strokeOnly().set(b.getValue());
            }
        });

        this.bbsvfx$blendMode = new UIButton(IKey.constant("Blend mode"), (b) -> bbsvfx$openBlendPicker());

        this.bbsvfx$font = new UIButton(IKey.constant("Font"), (b) -> bbsvfx$openFontPicker());

        this.bbsvfx$fontFolder = new UIButton(IKey.constant("Open fonts folder"), (b) -> bbsvfx$openFontsFolder());

        this.bbsvfx$fontSize = new UITrackpad((v) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$fontSize().set(v.floatValue());
            }
        });
        this.bbsvfx$fontSize.limit(1, 512).values(1F).increment(1F);

        this.bbsvfx$projection = new UIToggle(IKey.constant("Text projection"), (b) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$projection().set(b.getValue());
            }
        });

        this.bbsvfx$projRange = new UITrackpad((v) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$projRange().set(v.floatValue());
            }
        });
        this.bbsvfx$projRange.limit(0.1, 1024).values(0.5F).increment(1F);

        this.bbsvfx$projFade = new UITrackpad((v) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$projFade().set(v.floatValue());
            }
        });
        this.bbsvfx$projFade.limit(0, 1).values(0.05F, 0.01F, 0.1F).increment(0.05F);

        this.bbsvfx$gradient = new UIToggle(IKey.constant("Gradient"), (b) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$gradient().set(b.getValue());
            }
        });

        this.bbsvfx$gradientStart = new UIColor((c) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$gradientStart().set(Color.rgba(c));
            }
        }).withAlpha();

        this.bbsvfx$gradientEnd = new UIColor((c) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$gradientEnd().set(Color.rgba(c));
            }
        }).withAlpha();

        this.bbsvfx$gradientAngle = new UITrackpad((v) ->
        {
            if (this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$gradientAngle().set(v.floatValue());
            }
        });
        this.bbsvfx$gradientAngle.limit(0, 360).values(1F).increment(5F);

        /* Insert the controls right after the stock colour picker, grouped into collapsible sections:
         * outline + gradient (the colour-style controls) first, then blend, font/tracking, projection. */
        BbsVfxSection outline = BbsVfxUI.section(IKey.constant("Outline"),
            UI.label(IKey.constant("Stroke width")), this.bbsvfx$strokeWidth,
            UI.label(IKey.constant("Stroke color")), this.bbsvfx$strokeColor,
            this.bbsvfx$strokeOnly);

        BbsVfxSection gradient = BbsVfxUI.section(IKey.constant("Gradient"),
            this.bbsvfx$gradient,
            UI.label(IKey.constant("Gradient start color")), this.bbsvfx$gradientStart,
            UI.label(IKey.constant("Gradient end color")), this.bbsvfx$gradientEnd,
            UI.label(IKey.constant("Gradient angle")), this.bbsvfx$gradientAngle);

        BbsVfxSection blend = BbsVfxUI.section(IKey.constant("Blend mode"), this.bbsvfx$blendMode);

        BbsVfxSection font = BbsVfxUI.section(IKey.constant("Font"),
            this.bbsvfx$font, this.bbsvfx$fontFolder,
            UI.label(IKey.constant("Font size")), this.bbsvfx$fontSize,
            UI.label(IKey.constant("Tracking")), this.bbsvfx$tracking);

        BbsVfxSection projection = BbsVfxUI.section(IKey.constant("Text projection"),
            this.bbsvfx$projection,
            UI.label(IKey.constant("Projector range")), this.bbsvfx$projRange,
            UI.label(IKey.constant("Projection edge fade")), this.bbsvfx$projFade);

        UIElement options = ((UIFormPanel) (Object) this).options;
        IUIElement prev = ((UILabelFormPanel) (Object) this).color;

        for (IUIElement section : new IUIElement[]{outline, gradient, blend, font, projection})
        {
            options.addAfter(prev, section);
            prev = section;
        }
    }

    @Unique
    private void bbsvfx$openFontsFolder()
    {
        try
        {
            java.nio.file.Path dir = BbsVfxFontManager.fontsDir();

            java.nio.file.Files.createDirectories(dir);

            net.minecraft.util.Util.getOperatingSystem().open(dir.toFile());
        }
        catch (Exception ignored)
        {
        }
    }

    @Unique
    private void bbsvfx$openFontPicker()
    {
        BbsVfxFontManager.rescan();

        UIListOverlayPanel overlay = new UIListOverlayPanel(IKey.constant("Font"), (value) ->
        {
            if (this.bbsvfx$form != null)
            {
                String stored = value.equals(BbsVfxFontManager.DEFAULT) ? "" : value;

                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$font().set(stored);
                this.bbsvfx$font.label = IKey.constant(value);
            }
        });

        overlay.addValues(BbsVfxFontManager.names());

        if (this.bbsvfx$form != null)
        {
            String cur = ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$font().get();

            overlay.setValue(cur == null || cur.isEmpty() ? BbsVfxFontManager.DEFAULT : cur);
        }

        UIOverlay.addOverlay(((UIFormPanel) (Object) this).getContext(), overlay);
    }

    @Unique
    private void bbsvfx$openBlendPicker()
    {
        UIListOverlayPanel overlay = new UIListOverlayPanel(IKey.constant("Blend mode"), (value) ->
        {
            int index = BlendMode.DISPLAY_NAMES.indexOf(value);

            if (index >= 0 && this.bbsvfx$form != null)
            {
                ((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$blendMode().set(index);
                this.bbsvfx$blendMode.label = IKey.constant(value);
            }
        });

        overlay.addValues(BlendMode.DISPLAY_NAMES);

        if (this.bbsvfx$form != null)
        {
            overlay.setValue(BlendMode.byIndex(((ILabelExtras) (Object) this.bbsvfx$form).bbsvfx$blendMode().get()).display);
        }

        UIOverlay.addOverlay(((UIFormPanel) (Object) this).getContext(), overlay);
    }

    @Override
    public void bbsvfx$syncLabelPanel(LabelForm form)
    {
        this.bbsvfx$form = form;

        ILabelExtras extras = (ILabelExtras) (Object) form;

        this.bbsvfx$tracking.setValue(extras.bbsvfx$tracking().get());
        this.bbsvfx$strokeWidth.setValue(extras.bbsvfx$strokeWidth().get());
        this.bbsvfx$strokeColor.setColor(extras.bbsvfx$strokeColor().get().getARGBColor());
        this.bbsvfx$strokeOnly.setValue(extras.bbsvfx$strokeOnly().get());
        this.bbsvfx$blendMode.label = IKey.constant(BlendMode.byIndex(extras.bbsvfx$blendMode().get()).display);

        String curFont = extras.bbsvfx$font().get();

        this.bbsvfx$font.label = IKey.constant(curFont == null || curFont.isEmpty() ? BbsVfxFontManager.DEFAULT : curFont);
        this.bbsvfx$fontSize.setValue(extras.bbsvfx$fontSize().get());

        this.bbsvfx$projection.setValue(extras.bbsvfx$projection().get());
        this.bbsvfx$projRange.setValue(extras.bbsvfx$projRange().get());
        this.bbsvfx$projFade.setValue(extras.bbsvfx$projFade().get());

        this.bbsvfx$gradient.setValue(extras.bbsvfx$gradient().get());
        this.bbsvfx$gradientStart.setColor(extras.bbsvfx$gradientStart().get().getARGBColor());
        this.bbsvfx$gradientEnd.setColor(extras.bbsvfx$gradientEnd().get().getARGBColor());
        this.bbsvfx$gradientAngle.setValue(extras.bbsvfx$gradientAngle().get());
    }

    @Inject(method = "finishEdit", at = @At("TAIL"))
    private void bbsvfx$finishEdit(CallbackInfo ci)
    {
        this.bbsvfx$strokeColor.picker.removeFromParent();
        this.bbsvfx$gradientStart.picker.removeFromParent();
        this.bbsvfx$gradientEnd.picker.removeFromParent();
    }
}
