package com.bbsvfx.bbsvfx.camera;

import mchorse.bbs_mod.camera.clips.modifiers.EntityClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

/**
 * Camera modifier that rides the camera along a {@link com.bbsvfx.bbsvfx.forms.CurveForm} actor in the
 * scene. The target actor is picked via the inherited {@code selector}; the camera is placed at the
 * arc-length offset {@link #progress} (0..1) along that curve. {@code progress} is a keyframe channel,
 * so you animate the travel over the clip just like a path clip — e.g. 0 at the start, 0.5 mid, 1 end.
 * The inherited {@code offset} {@code ValuePoint} nudges the resulting position.
 *
 * <p>Common (data) side — the actual camera work lives in {@code FollowCurveClientClip}.</p>
 */
public class FollowCurveClip extends EntityClip
{
    /** Keyframed arc-length offset along the curve, 0 (start) .. 1 (end). */
    public final KeyframeChannel<Double> progress = new KeyframeChannel<>("progress", KeyframeFactories.DOUBLE);
    /** Orient the camera along the curve tangent (rails); off = position only. */
    public final ValueBoolean align = new ValueBoolean("align", true);

    public FollowCurveClip()
    {
        super();

        this.add(this.progress);
        this.add(this.align);
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        /* No-op on the common side; FollowCurveClientClip overrides with the real camera logic. */
    }

    @Override
    protected Clip create()
    {
        return new FollowCurveClip();
    }
}
