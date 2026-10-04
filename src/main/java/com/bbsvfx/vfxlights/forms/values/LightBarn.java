package com.bbsvfx.vfxlights.forms.values;

/**
 * The four flaps a gaffer swings in to keep an area light off the background, and how soft their edge
 * is.
 *
 * <p>One property, one track — see {@link LightStruct}. Barn doors are trimmed as a set: the shot
 * needs the light off the wall, and which flaps do it is a detail of the same decision.</p>
 */
public class LightBarn extends LightStruct<LightBarn>
{
    private static final LightField[] SCHEMA =
    {
        new LightField("top", 0F, 1F),
        new LightField("bottom", 0F, 1F),
        new LightField("left", 0F, 1F),
        new LightField("right", 0F, 1F),
        new LightField("softness", 0F, 8F)
    };

    /** How far the light is trimmed off past each edge, in the lamp's own tangent space. */
    public float top;
    public float bottom;
    public float left;
    public float right;

    /** How soft the trimmed edge is. */
    public float softness = 0.25F;

    @Override
    public LightField[] schema()
    {
        return SCHEMA;
    }

    @Override
    public float[] fields()
    {
        return new float[] {this.top, this.bottom, this.left, this.right, this.softness};
    }

    @Override
    public void setFields(float[] v)
    {
        this.top = SCHEMA[0].clamp(v[0]);
        this.bottom = SCHEMA[1].clamp(v[1]);
        this.left = SCHEMA[2].clamp(v[2]);
        this.right = SCHEMA[3].clamp(v[3]);
        this.softness = SCHEMA[4].clamp(v[4]);
    }

    @Override
    public LightBarn copy()
    {
        LightBarn copy = new LightBarn();

        copy.setFields(this.fields());

        return copy;
    }
}
