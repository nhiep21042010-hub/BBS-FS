package com.bbsvfx.vfxlights.forms.values;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;

import java.util.function.Supplier;

/**
 * Keyframe factory for a grouped light property — one instance per group, all sharing this code.
 *
 * <p>Interpolation runs PER DIAL, the way {@code ShapeKeysKeyframeFactory} interpolates each shape key
 * rather than the map as a lump: the group is a bookkeeping unit, not a physical one, and two dials
 * inside it have no reason to move together beyond sharing a curve. Fields declared as steps jump at
 * the keyframe instead — a toon lamp is never half-toon.</p>
 */
public class LightStructFactory <T extends LightStruct<T>> implements IKeyframeFactory<T>
{
    private final Supplier<T> empty;

    public LightStructFactory(Supplier<T> empty)
    {
        this.empty = empty;
    }

    @Override
    public T fromData(BaseType data)
    {
        T value = this.empty.get();

        if (data != null && data.isMap())
        {
            value.fromData(data.asMap());
        }

        return value;
    }

    @Override
    public BaseType toData(T value)
    {
        MapType data = new MapType();

        value.toData(data);

        return data;
    }

    @Override
    public T createEmpty()
    {
        /* "Empty" is the group at its defaults, not at zero: a keyframe dropped on the beam track
         * should give the lamp its normal beam, not switch the air off. */
        return this.empty.get();
    }

    @Override
    public T copy(T value)
    {
        return value == null ? this.empty.get() : value.copy();
    }

    @Override
    public T interpolate(T preA, T a, T b, T postB, IInterp interpolation, float x)
    {
        T value = this.empty.get();

        if (a == null || b == null)
        {
            return a == null ? (b == null ? value : b.copy()) : a.copy();
        }

        LightField[] schema = value.schema();
        float[] fa = a.fields();
        float[] fb = b.fields();
        float[] fpreA = preA == null ? fa : preA.fields();
        float[] fpostB = postB == null ? fb : postB.fields();
        float[] out = new float[schema.length];

        for (int i = 0; i < schema.length; i++)
        {
            out[i] = schema[i].step
                ? fa[i]
                : (float) interpolation.interpolate(IInterp.context.set(fpreA[i], fa[i], fb[i], fpostB[i], x));
        }

        value.setFields(out);

        return value;
    }
}
