package com.bbsvfx.vfxlights.forms.values;

/**
 * One dial inside a grouped light property: how it is stored, what it may hold, and whether it may be
 * interpolated at all.
 *
 * <p>The schema is what lets a group serialise, compare, interpolate and draw its editor without any
 * per-type code — see {@link LightStruct}.</p>
 */
public final class LightField
{
    /** Key this dial is stored under inside the group's map. */
    public final String key;

    public final float min;
    public final float max;

    /** A flag rather than a number: it jumps at the keyframe instead of ramping between two. */
    public final boolean step;

    public LightField(String key, float min, float max)
    {
        this(key, min, max, false);
    }

    public LightField(String key, float min, float max, boolean step)
    {
        this.key = key;
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public float clamp(float value)
    {
        return value < this.min ? this.min : (value > this.max ? this.max : value);
    }
}
