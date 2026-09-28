package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;

/**
 * Bridge from the film controller to the smear render. While a replay actor is being rendered,
 * holds its {@link Replay} + {@link IEntity} + the current film time, so the auto-arc smear can
 * re-evaluate that actor's pose at EARLIER ticks — {@code replay.properties.applyProperties(form, T)}
 * (limb swing) and {@code replay.keyframes.apply(tick, entity)} (root motion) — to lay each echo copy
 * along the bone's real motion arc.
 *
 * <p>Set per top-level actor render by {@code SmearReplayBridgeMixin} (HEAD) and cleared on RETURN.
 * Render-thread only; actors render sequentially with no nesting, so a single static slot is enough.</p>
 */
public final class SmearReplayState
{
    public static Replay replay;
    public static IEntity entity;
    public static int baseTick;
    public static float transition;
    /** Index of the current replay in the film's replay list (-1 if unknown); used by the impact silhouette
     *  target picker to match the selected actor. */
    public static int replayIndex = -1;

    private SmearReplayState()
    {}

    public static void begin(Replay replay, IEntity entity, int baseTick, float transition, int replayIndex)
    {
        SmearReplayState.replay = replay;
        SmearReplayState.entity = entity;
        SmearReplayState.baseTick = baseTick;
        SmearReplayState.transition = transition;
        SmearReplayState.replayIndex = replayIndex;
    }

    public static void end()
    {
        replay = null;
        entity = null;
        baseTick = 0;
        transition = 0F;
        replayIndex = -1;
    }

    /** Current film time in ticks (whole tick + partial) — the present moment to rewind back from. */
    public static float time()
    {
        return baseTick + transition;
    }

    /** True when the bridge currently holds this exact entity (the one being rendered). */
    public static boolean has(IEntity e)
    {
        return replay != null && entity == e;
    }
}
