package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.utils.pose.Pose;

/**
 * Shared state driving the per-bone smear: {@code SmearRenderMixin} renders the model several times
 * (echo), setting {@link #fraction} per copy, and {@code ModelFormRendererPoseMixin} offsets each
 * smeared bone's pose translate by {@code smearVector * fraction} inside {@code getPose()}. The
 * {@link #target} guards against smearing nested body-part models during the same render.
 *
 * <p>{@link #dissolve} (this copy's dissolve amount) is read by {@code TextureManagerDissolveMixin},
 * which swaps the bound texture for a noise-holed variant ({@link BbsVfxDissolveTexture}) so the dissolve
 * works under external shader packs too.</p>
 */
public final class SmearRenderState
{
    public static boolean active;
    /**
     * Set by {@code SmearRenderMixin} around the crisp + copy passes: tells the motion-line hook to DEFER its
     * draw (snapshot the matrix, draw nothing yet) so the smear can render the lines after all copies — on top,
     * binding the arc. Cleared before that deferred draw.
     */
    public static boolean linesPending;
    public static float fraction;
    public static float stretch;
    public static float dissolve;
    public static Object target;

    /**
     * Auto-arc copy: the pose is already the historical (time-rewound) pose, so the per-bone vector
     * offset/stretch must be SKIPPED; {@link #hide} lists the bones to alpha-hide so only the smeared
     * bone(s) appear in the trail. Read in {@code ModelFormRendererPoseMixin}.
     */
    public static boolean arc;
    public static java.util.Set<String> hide;

    /**
     * Per-copy deformation (arc mode): {@link #arcStretch} = how much each shown bone is stretched along
     * its length and squashed across (volume-preserving); {@link #arcTaper} = 0..1 position toward the
     * tail (older copy) → shrink + abstraction. Applied to the smeared bones in {@code ModelFormRendererPoseMixin}.
     */
    public static float arcStretch;
    public static float arcTaper;

    /** The present (un-rewound) pose — to read each bone's swing direction (rotTk − rotNow) for the stretch axis. */
    public static Pose presentPose;

    private SmearRenderState()
    {}

    public static void begin(Object renderer, float fraction, float stretch, float dissolve)
    {
        active = true;
        target = renderer;
        SmearRenderState.fraction = fraction;
        SmearRenderState.stretch = stretch;
        SmearRenderState.dissolve = dissolve;
    }

    /** An arc echo copy: historical pose already applied; just fade + dissolve + deform + isolate {@code hide}. */
    public static void beginArc(Object renderer, float dissolve, java.util.Set<String> hide, float arcStretch, float arcTaper, Pose presentPose)
    {
        active = true;
        arc = true;
        target = renderer;
        SmearRenderState.dissolve = dissolve;
        SmearRenderState.hide = hide;
        SmearRenderState.arcStretch = arcStretch;
        SmearRenderState.arcTaper = arcTaper;
        SmearRenderState.presentPose = presentPose;
        fraction = 0F;
        stretch = 0F;
    }

    public static void end()
    {
        active = false;
        arc = false;
        target = null;
        hide = null;
        fraction = 0F;
        stretch = 0F;
        dissolve = 0F;
        arcStretch = 0F;
        arcTaper = 0F;
        presentPose = null;
    }
}
