package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.ISmearHolder;
import com.bbsvfx.bbsvfx.forms.SmearProperties;

/**
 * Attaches {@link SmearProperties} to every {@link Form} so any actor can be smeared, without
 * subclassing each form type. The group is registered as a child value ({@code xavin_smear}) so it
 * serialises with the form and shows up as keyframable properties.
 */
@Mixin(Form.class)
public abstract class SmearFormMixin implements ISmearHolder
{
    @Unique
    private SmearProperties bbsvfx$smearProps;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$initSmear(CallbackInfo ci)
    {
        /* LEGACY VALUE ID: "xavin_smear" predates the xavin → bbsvfx rename; it is the group id under
         * which the smear properties serialize in saved forms, so it must stay as-is. */
        this.bbsvfx$smearProps = new SmearProperties("xavin_smear");
        ((ValueGroup) (Object) this).add(this.bbsvfx$smearProps);
    }

    @Override
    public SmearProperties bbsvfx$smear()
    {
        return this.bbsvfx$smearProps;
    }
}
