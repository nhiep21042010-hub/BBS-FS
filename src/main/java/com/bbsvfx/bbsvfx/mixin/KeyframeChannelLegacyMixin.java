package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Back-compat for the smear/lines track rename: films saved before the rename store their channel
 * {@code type} as the old factory ids ({@code xavin_smear} / {@code xavin_lines}), which no longer
 * resolve in {@code KeyframeFactories.FACTORIES} — {@link KeyframeChannel#fromData} would then get a
 * null factory and NPE while loading the film. Remap the old ids to the new ones ({@code smear_frames}
 * / {@code motion_lines}) at the factory lookup so old films keep loading. Save is unaffected: it
 * writes the key via {@code CollectionUtils.getKey(FACTORIES, factory)}, and only the NEW ids are
 * registered, so a re-saved film gets the new id.
 */
@Mixin(value = KeyframeChannel.class, remap = false)
public abstract class KeyframeChannelLegacyMixin
{
    @ModifyArg(
        method = "fromData",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),
        index = 0)
    private Object bbsvfx$remapLegacyType(Object key)
    {
        if ("xavin_smear".equals(key))
        {
            return "smear_frames";
        }

        if ("xavin_lines".equals(key))
        {
            return "motion_lines";
        }

        return key;
    }
}
