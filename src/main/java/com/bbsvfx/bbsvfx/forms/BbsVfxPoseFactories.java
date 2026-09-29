package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.utils.keyframes.factories.PoseKeyframeFactory;

/**
 * Distinct {@link PoseKeyframeFactory} instances for the smear / lines channels. The UI keyframe-factory
 * registry is keyed by the data factory, so giving these channels their own factory instances (even
 * though they serialise/interpolate identically to a pose) lets them get their OWN keyframe editors
 * instead of sharing the pose editor.
 */
public final class BbsVfxPoseFactories
{
    public static final PoseKeyframeFactory SMEAR = new PoseKeyframeFactory();
    public static final PoseKeyframeFactory LINES = new PoseKeyframeFactory();
    public static final PoseKeyframeFactory BLEND = new PoseKeyframeFactory();
    /** Whole-form (transform-based) smear channel for non-model forms — one track, single sentinel entry. */
    public static final PoseKeyframeFactory FORM_SMEAR = new PoseKeyframeFactory();

    private BbsVfxPoseFactories()
    {}
}
