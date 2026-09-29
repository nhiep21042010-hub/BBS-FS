package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.utils.pose.Pose;

/**
 * Whole-form (transform-based) smear, added to every {@link mchorse.bbs_mod.forms.forms.Form} except
 * ModelForm (its own per-bone smear), CurveForm, DestructionBoxForm and the particle forms. All params
 * live in a single sentinel {@link mchorse.bbs_mod.utils.pose.PoseTransform} entry ({@link #SENTINEL}) of
 * the one {@code smear} channel — so it's ONE animatable track. The render reads that sentinel's
 * {@link ISmearBone} fields and re-poses the whole form at earlier film times.
 */
public interface IFormSmear
{
    /** The single sentinel bone name holding the whole-form smear params (the form has no real bones). */
    String SENTINEL = "form";

    /** The {@code smear} channel pose (sentinel entry inside), or null on the excluded forms. */
    Pose bbsvfx$formSmearPose();
}
