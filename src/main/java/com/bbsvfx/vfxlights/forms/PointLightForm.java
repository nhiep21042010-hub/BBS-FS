package com.bbsvfx.vfxlights.forms;

import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * An omnidirectional lamp: a torch, a candle, a bare bulb, a spark.
 *
 * <p>Kept deliberately plain — it is the baseline every other light is judged against, and the one to
 * reach for when a scene needs a hundred cheap practicals rather than four good ones.</p>
 *
 * <p>{@link #sourceRadius} is the one concession to physical size: a real bulb is a small sphere, so its
 * shadows soften slightly with distance and its specular is a small disc rather than a mathematical
 * point. At radius 0 this degenerates to the classic game point light, which is sometimes exactly what an
 * effect wants.</p>
 */
public class PointLightForm extends LightForm
{
    /** Radius of the emitting sphere in blocks. 0 = idealised point. 0.25 ≈ Blender's point default —
     * a slightly soft shadow edge out of the box. */
    public final ValueFloat sourceRadius = new ValueFloat("source_radius", 0.25F, 0F, 16F);

    public PointLightForm()
    {
        super();

        this.add(this.sourceRadius);
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "point light";
    }

    @Override
    public String getFormId()
    {
        return "point_light";
    }
}
