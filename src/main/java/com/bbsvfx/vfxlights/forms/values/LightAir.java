package com.bbsvfx.vfxlights.forms.values;

/**
 * What the light does to the AIR between the lamp and the surface: the visible shaft, the fog it glows
 * into, the motes drifting in it, the prism colours, and the one bounce it throws back.
 *
 * <p>One property, one track. These dials are set as a mood ("this shot is foggy"), not one at a
 * time, so a single keyframe carrying all of them is closer to how they are used than seven.</p>
 */
public class LightAir extends LightStruct<LightAir>
{
    private static final LightField[] SCHEMA =
    {
        /* Beam and haze keep their open top end: a gaffer runs a shaft far past "1" on purpose. */
        new LightField("beam", 0F, Float.MAX_VALUE),
        new LightField("haze", 0F, Float.MAX_VALUE),
        new LightField("dust", 0F, 1F),
        new LightField("dust_size", 0.1F, 8F),
        new LightField("prism", 0F, 1F),
        new LightField("prism_scale", 0.1F, 4F),
        new LightField("bounce", 0F, 1F)
    };

    /** Strength of the beam visible in the air. Zero disables the volumetric pass for this lamp. */
    public float beam = 1F;

    /** Fog glowing around the lamp in every direction — a streetlamp in mist, not a directional shaft. */
    public float haze;

    /** Motes drifting and twinkling inside the beam — the projector-room look. */
    public float dust;

    /** Mote size as a multiplier over the base dust-orb radius (a few cm at 1). */
    public float dustSize = 1F;

    /** Spectral dispersion: rainbow caustics where the light lands, a spectral rim on its beam. */
    public float prism;

    /** Size of the caustic pattern's cells, in the light's angular space. 1 = default look. */
    public float prismScale = 1F;

    /** One virtual bounce: a soft fill born where the axis lands, coloured by the surface it struck. */
    public float bounce;

    @Override
    public LightField[] schema()
    {
        return SCHEMA;
    }

    @Override
    public float[] fields()
    {
        return new float[] {this.beam, this.haze, this.dust, this.dustSize, this.prism, this.prismScale, this.bounce};
    }

    @Override
    public void setFields(float[] v)
    {
        this.beam = SCHEMA[0].clamp(v[0]);
        this.haze = SCHEMA[1].clamp(v[1]);
        this.dust = SCHEMA[2].clamp(v[2]);
        this.dustSize = SCHEMA[3].clamp(v[3]);
        this.prism = SCHEMA[4].clamp(v[4]);
        this.prismScale = SCHEMA[5].clamp(v[5]);
        this.bounce = SCHEMA[6].clamp(v[6]);
    }

    @Override
    public LightAir copy()
    {
        LightAir copy = new LightAir();

        copy.setFields(this.fields());

        return copy;
    }
}
