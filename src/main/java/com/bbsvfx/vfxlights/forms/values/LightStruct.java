package com.bbsvfx.vfxlights.forms.values;

import mchorse.bbs_mod.data.IMapSerializable;
import mchorse.bbs_mod.data.types.MapType;

import java.util.Arrays;

/**
 * A group of light dials that travels the film as ONE keyframe.
 *
 * <p><b>Why group at all.</b> The film gives every visible form property its own track
 * ({@code FormProperties.create}), and a light has three dozen of them — functional, and a wall to
 * read. The dials that are always dialled together (the air, the surface response, the toon ramp, the
 * barn doors) become one property each, so the timeline shows the handful of things a gaffer actually
 * animates instead of every screw in the lamp.</p>
 *
 * <p><b>Why a schema instead of per-type code.</b> Serialisation, equality, interpolation and the
 * keyframe editor are all mechanical once a group can describe its dials: subclasses declare named
 * fields (so the shading code still reads {@code air.haze}, not {@code air.get(3)}) plus the two-line
 * bridge to a flat array, and everything else is written once here and in
 * {@link LightStructFactory}.</p>
 */
public abstract class LightStruct <T extends LightStruct<T>> implements IMapSerializable
{
    /** What this group holds, in the same order as {@link #fields()}. Constant per type. */
    public abstract LightField[] schema();

    /** The current values, in schema order. Freshly allocated — callers may keep it. */
    public abstract float[] fields();

    /** Take values in schema order; every field is clamped to its own limits. */
    public abstract void setFields(float[] values);

    public abstract T copy();

    /** Read a flag field: anything above the halfway point counts as on. */
    protected static boolean flag(float value)
    {
        return value > 0.5F;
    }

    protected static float flag(boolean value)
    {
        return value ? 1F : 0F;
    }

    @Override
    public void toData(MapType data)
    {
        LightField[] schema = this.schema();
        float[] values = this.fields();

        for (int i = 0; i < schema.length; i++)
        {
            data.putFloat(schema[i].key, values[i]);
        }
    }

    @Override
    public void fromData(MapType data)
    {
        LightField[] schema = this.schema();
        float[] values = this.fields();

        for (int i = 0; i < schema.length; i++)
        {
            if (data.has(schema[i].key))
            {
                values[i] = data.getFloat(schema[i].key);
            }
        }

        this.setFields(values);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj == this)
        {
            return true;
        }

        if (obj == null || obj.getClass() != this.getClass())
        {
            return false;
        }

        return Arrays.equals(this.fields(), ((LightStruct<?>) obj).fields());
    }

    @Override
    public int hashCode()
    {
        return Arrays.hashCode(this.fields());
    }
}
