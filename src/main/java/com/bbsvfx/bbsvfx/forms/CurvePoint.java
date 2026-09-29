package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * One control point of a {@link CurveForm}. Its <b>position</b> lives in the form's animatable
 * {@code pose} under the bone named by {@link #bone}, so the replay editor can keyframe each point.
 * The point itself only stores the stable bone name plus a non-animated per-point width.
 */
public class CurvePoint extends ValueGroup
{
    /** Stable name of this point's bone in the form's pose (where its animatable position is stored). */
    public final ValueString bone = new ValueString("bone", "");
    public final ValueFloat width = new ValueFloat("width", 0.2F, 0.001F, Float.POSITIVE_INFINITY);

    public CurvePoint(String id)
    {
        super(id);

        this.add(this.bone);
        this.add(this.width);
    }
}
