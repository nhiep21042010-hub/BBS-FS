package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.sections.ExtraFormSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.BeamForm;
import com.bbsvfx.bbsvfx.forms.CurveForm;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;
import com.bbsvfx.bbsvfx.forms.DomeForm;
import com.bbsvfx.bbsvfx.forms.ExplosionForm;
import com.bbsvfx.bbsvfx.forms.WindForm;

import java.util.List;

/**
 * Adds the addon's form entries to BBS's "miscellaneous" (extra) form-picker section. Injected at
 * {@code TAIL} of {@code ExtraFormSection.initiate}; the first category returned by
 * {@code getCategories()} is the "extra" (miscellaneous) one, and {@code addForm} appends our forms so
 * they show up as creatable forms alongside Anchor, Block, Item, etc.
 */
@Mixin(ExtraFormSection.class)
public abstract class ExtraFormSectionMixin
{
    @Shadow public abstract List<FormCategory> getCategories();

    @Inject(method = "initiate", at = @At("TAIL"))
    private void bbsvfx$addForms(CallbackInfo ci)
    {
        this.getCategories().get(0).addForm(new CurveForm());
        this.getCategories().get(0).addForm(new DestructionBoxForm());
        this.getCategories().get(0).addForm(new ExplosionForm());
        this.getCategories().get(0).addForm(new BeamForm());
        this.getCategories().get(0).addForm(new DomeForm());
        this.getCategories().get(0).addForm(new WindForm());
    }
}
