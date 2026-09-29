package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UIGeneralFormPanel;

import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import org.spongepowered.asm.mixin.Mixin;
import com.bbsvfx.bbsvfx.client.BbsVfxSection;
import com.bbsvfx.bbsvfx.client.BbsVfxUI;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the "Tracker" toggle to the form's general options panel, surfaced only for {@link AnchorForm}
 * (marks the anchor for After Effects export, drives {@code xavin_tracker}). The whole-form smear lives
 * in its own single {@code smear} keyframe track (UIFormSmearKeyframeFactory), not here.
 *
 * <p>The base {@code form}/{@code options} live on the superclass {@link UIFormPanel}; rather than shadow
 * inherited members we read the current form from {@code startEdit}'s argument (stashed) and reach the
 * public {@code options} container through a cast.</p>
 */
@Mixin(UIGeneralFormPanel.class)
public abstract class UIGeneralFormPanelMixin
{
    @Unique
    private UIToggle bbsvfx$tracker;

    @Unique
    private BbsVfxSection bbsvfx$trackerSection;

    @Unique
    private Form bbsvfx$form;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addTrackerToggle(CallbackInfo ci)
    {
        this.bbsvfx$tracker = new UIToggle(IKey.constant("Tracker"), (b) ->
        {
            BaseValue value = this.bbsvfx$form == null ? null : this.bbsvfx$form.get("xavin_tracker");

            if (value instanceof ValueBoolean tracker)
            {
                tracker.set(b.getValue());
            }
        });

        this.bbsvfx$trackerSection = BbsVfxUI.section(IKey.constant("Tracker"), this.bbsvfx$tracker);

        ((UIFormPanel) (Object) this).options.add(this.bbsvfx$trackerSection);
    }

    @Inject(method = "startEdit", at = @At("TAIL"))
    private void bbsvfx$syncTrackerToggle(Form form, CallbackInfo ci)
    {
        this.bbsvfx$form = form;

        if (form instanceof AnchorForm)
        {
            BaseValue value = form.get("xavin_tracker");

            this.bbsvfx$tracker.setValue(value instanceof ValueBoolean tracker && tracker.get());
            this.bbsvfx$trackerSection.setVisible(true);
        }
        else
        {
            this.bbsvfx$trackerSection.setVisible(false);
        }
    }
}
