package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.actions.ActionManager;
import mchorse.bbs_mod.actions.DamageControl;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes BBS's private per-world {@link DamageControl} map. The Destruction Box capture needs it to
 * temporarily suspend damage control around its world cut, so that BBS doesn't record (and later
 * restore) the removed blocks when a film playback stops — which would otherwise make the cut house
 * reappear next to the captured structure actor.
 */
@Mixin(ActionManager.class)
public interface ActionManagerAccessor
{
    @Accessor("dc")
    Map<ServerWorld, DamageControl> getDamageControls();
}
