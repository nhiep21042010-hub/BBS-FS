package com.bbsvfx.vfxlights.forms.values;

/**
 * The two-tone anime ramp this lamp shades with: whether it is on, how hard the terminator is, and
 * what the shadow side is left looking like.
 *
 * <p>One property, one track — see {@link LightStruct}. The on/off dial is a STEP field: a toon lamp
 * that is half-toon between two keyframes would be a smooth ramp, which is the one thing cel shading
 * exists to avoid.</p>
 */
public class LightToon extends LightStruct<LightToon>
{
    private static final LightField[] SCHEMA =
    {
        new LightField("enabled", 0F, 1F, true),
        new LightField("softness", 0F, 0.5F),
        new LightField("shadow_tint", 0F, 1F),
        new LightField("shadow_level", 0F, 1F)
    };

    /** Two-tone toon shading for this lamp: flat lit side, flat shadow side. */
    public boolean enabled;

    /** Width of the lit-to-shadow transition: 0 = razor, up. */
    public float softness = 0.03F;

    /** How far the shadow tone is pushed cold (0 = same hue dimmed, 1 = full blue). */
    public float shadowTint = 0.6F;

    /** Brightness of the shadow tone (0 = black shadow, up = lifted cool fill). */
    public float shadowLevel = 0.22F;

    @Override
    public LightField[] schema()
    {
        return SCHEMA;
    }

    @Override
    public float[] fields()
    {
        return new float[] {flag(this.enabled), this.softness, this.shadowTint, this.shadowLevel};
    }

    @Override
    public void setFields(float[] v)
    {
        this.enabled = flag(v[0]);
        this.softness = SCHEMA[1].clamp(v[1]);
        this.shadowTint = SCHEMA[2].clamp(v[2]);
        this.shadowLevel = SCHEMA[3].clamp(v[3]);
    }

    @Override
    public LightToon copy()
    {
        LightToon copy = new LightToon();

        copy.setFields(this.fields());

        return copy;
    }
}
