package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.actions.DamageControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.DestructionCapture;

/**
 * SELF-HEAL after BBS's damage control: the film-stop restore puts back the pre-play state of every
 * block that changed during playback — server-side foliage churn (e.g. a leaf-`distance` recalc wave
 * from a rescan restore, or orphaned-leaf decay) can slip into the recording OUTSIDE our suspension
 * and get resurrected as REAL blocks under the swaying proxies ("duplicate trees after playback").
 * After each restore the foliage cut verifier re-runs and re-cuts anything captured that reappeared.
 */
@Mixin(value = DamageControl.class, remap = false)
public class DamageControlFoliageMixin
{
    @Inject(method = "restore", at = @At("TAIL"))
    private void bbsvfx$healAfterRestore(CallbackInfo ci)
    {
        DestructionCapture.onDamageRestored();
    }
}
