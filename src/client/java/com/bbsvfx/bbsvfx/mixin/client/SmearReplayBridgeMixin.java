package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.SmearReplayState;

/**
 * Publishes the current replay actor + film time into {@link SmearReplayState} for the whole duration
 * of that actor's render, so the auto-arc smear can re-evaluate the actor's pose at past ticks.
 *
 * <p>Hooked on the instance {@code renderEntity(WorldRenderContext, Replay, IEntity)} — the per-actor
 * seam used by BOTH world playback and the editor preview ({@code FilmEditorController.renderEntity}
 * calls {@code super}). Because rendering is continuous even when the timeline is paused, the time it
 * captures is well-defined on pause too, which is what makes the rewound arc deterministic there.</p>
 */
@Mixin(BaseFilmController.class)
public abstract class SmearReplayBridgeMixin
{
    @Shadow
    public abstract int getTick();

    @Shadow
    protected abstract float getTransition(IEntity entity, float transition);

    @Shadow
    @Final
    public Film film;

    @Inject(method = "renderEntity(Lnet/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext;Lmchorse/bbs_mod/film/replays/Replay;Lmchorse/bbs_mod/forms/entities/IEntity;)V", at = @At("HEAD"))
    private void bbsvfx$smearBridgeBegin(WorldRenderContext context, Replay replay, IEntity entity, CallbackInfo ci)
    {
        int baseTick = replay.getTick(this.getTick());
        float delta = this.getTransition(entity, com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.tickDelta(context));
        int index = this.film != null ? this.film.replays.getList().indexOf(replay) : -1;

        SmearReplayState.begin(replay, entity, baseTick, delta, index);
    }

    @Inject(method = "renderEntity(Lnet/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext;Lmchorse/bbs_mod/film/replays/Replay;Lmchorse/bbs_mod/forms/entities/IEntity;)V", at = @At("RETURN"))
    private void bbsvfx$smearBridgeEnd(WorldRenderContext context, Replay replay, IEntity entity, CallbackInfo ci)
    {
        SmearReplayState.end();
    }
}
