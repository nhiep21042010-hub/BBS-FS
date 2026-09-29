package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stores the per-actor "follow curve" configuration on the {@link Replay} (so it lives in the Replay
 * settings panel): whether following is on, which curve actor to follow (its replay index), and
 * whether to align to the tangent. The animatable travel itself is {@code follow_offset}, a form
 * property (added by {@code FollowCurveFormMixin}) so it keyframes in the replay editor. Read by
 * {@code FollowCurveControllerMixin}; configured by {@code UIReplayPropertiesPanelMixin}.
 */
@Mixin(Replay.class)
public abstract class ReplayFollowMixin
{
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addFollow(String id, CallbackInfo ci)
    {
        ValueGroup self = (ValueGroup) (Object) this;

        /* LEGACY DATA KEYS: the "xavin_follow_*" ids predate the xavin → bbsvfx rename. They are
         * serialized into saved replays, so they must stay as-is for old scenes/films to load. */
        self.add(new ValueBoolean("xavin_follow_enabled", false));
        self.add(new ValueInt("xavin_follow_target", -1));
        self.add(new ValueBoolean("xavin_follow_align", true));
    }
}
