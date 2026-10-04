package com.bbsvfx.vfxlights.forms;

import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * A directed cone: lantern, headlight, stage lamp, searchlight.
 *
 * <p>Aim comes from the form's own transform — the light points down its local +Z, so it can be attached
 * to a bone or an actor and simply follow. Two angles rather than one: light falls off between the inner
 * cone (fully lit) and the outer (fully dark), and the gap between them is what separates a hard theatrical
 * edge from a soft wash.</p>
 */
public class SpotLightForm extends LightForm
{
    /** Full outer cone angle in degrees — beyond this nothing is lit. */
    public final ValueFloat angle = new ValueFloat("angle", 45F, 1F, 179F);
    /** Full inner cone angle in degrees; between inner and outer the beam falls off. */
    public final ValueFloat innerAngle = new ValueFloat("inner_angle", 30F, 0F, 179F);
    /** Radius of the emitting surface, softening both the penumbra and the specular. 0.25 ≈ Blender's
     * point default — a slightly soft shadow edge out of the box. */
    public final ValueFloat sourceRadius = new ValueFloat("source_radius", 0.25F, 0F, 16F);

    public SpotLightForm()
    {
        super();

        this.add(this.angle);
        this.add(this.innerAngle);
        this.add(this.sourceRadius);
    }

    /** Inner angle clamped below the outer one — authoring them independently allows an inverted pair. */
    public float effectiveInnerAngle()
    {
        return Math.min(this.innerAngle.get(), this.angle.get());
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "spot light";
    }

    @Override
    public String getFormId()
    {
        return "spot_light";
    }
}
