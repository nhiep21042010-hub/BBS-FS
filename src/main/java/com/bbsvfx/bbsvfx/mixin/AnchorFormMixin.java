package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a persistent {@code xavin_tracker} flag to every {@link AnchorForm}. Since {@code Form} is a
 * {@link ValueGroup}, adding the value to the group makes it (de)serialize with the form for free —
 * the "Tracker" toggle in the anchor's options writes it, and the form renderer reads it to decide
 * whether to export the anchor's world transform to After Effects.
 *
 * <p>The value is hidden ({@code invisible()}) so it does not show up as an animatable track.</p>
 */
@Mixin(AnchorForm.class)
public abstract class AnchorFormMixin
{
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addTrackerValue(CallbackInfo ci)
    {
        /* LEGACY DATA KEY: "xavin_tracker" predates the xavin → bbsvfx rename. It is stored inside
         * saved forms, so it must stay as-is or old scenes/films would silently lose the flag. */
        ValueBoolean tracker = new ValueBoolean("xavin_tracker", false);

        tracker.invisible();
        ((ValueGroup) (Object) this).add(tracker);
    }
}
