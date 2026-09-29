package com.bbsvfx.bbsvfx.mixin.client;

import com.bbsvfx.bbsvfx.client.ILabelPanelSync;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries "a form is now being edited" down to the addon's own panel widgets. The label panel used to
 * take this off its own {@code startEdit} override, which BBS 2.6 removed; the base class still
 * declares the method, and that is the only place a mixin can attach to.
 */
@Mixin(value = UIFormPanel.class, remap = false)
public abstract class UIFormPanelStartEditMixin
{
    @Inject(method = "startEdit", at = @At("TAIL"))
    private void bbsvfx$syncAddonWidgets(Form form, CallbackInfo ci)
    {
        if (this instanceof ILabelPanelSync sync && form instanceof LabelForm label)
        {
            sync.bbsvfx$syncLabelPanel(label);
        }
    }
}
