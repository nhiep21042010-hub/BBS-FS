package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.sections.ExtraFormSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.forms.AmbientLightForm;
import com.bbsvfx.vfxlights.forms.AreaLightForm;
import com.bbsvfx.vfxlights.forms.PointLightForm;
import com.bbsvfx.vfxlights.forms.SpotLightForm;

import java.util.List;

/**
 * Adds the four lights to BBS's "miscellaneous" form-picker section so they can be created in the UI.
 *
 * <p>Order is deliberate: area first, because it is the one worth reaching for.</p>
 */
@Mixin(ExtraFormSection.class)
public abstract class FormPickerMixin
{
    @Shadow public abstract List<FormCategory> getCategories();

    @Inject(method = "initiate", at = @At("TAIL"))
    private void vfxlights$addForms(CallbackInfo ci)
    {
        /* Skip while the module is off: no dead light forms in the picker. The picker is rebuilt when the
         * toggle flips (VfxModules.onToggleChanged -> FormCategories.setup), so this re-runs and the forms
         * appear/disappear live. Existing lights already placed in a film are untouched — only inert. */
        if (!com.bbsvfx.vfxlights.VfxLightsModule.isEnabled())
        {
            return;
        }

        FormCategory category = this.getCategories().get(0);

        category.addForm(new AreaLightForm());
        category.addForm(new AmbientLightForm());
        category.addForm(new PointLightForm());
        category.addForm(new SpotLightForm());
    }
}
