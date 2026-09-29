package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.core.ValueList;

/**
 * Ordered list of {@link CurvePoint}s for a {@link CurveForm}. Holds per-point width and the stable
 * bone name; the animatable position lives in the form's pose (see {@link CurveForm}).
 */
public class CurvePointList extends ValueList<CurvePoint>
{
    public CurvePointList(String id)
    {
        super(id);
    }

    @Override
    protected CurvePoint create(String id)
    {
        return new CurvePoint(id);
    }

    /** Append a point bound to the given pose bone name and width. Position is set on the pose. */
    public CurvePoint addRaw(String bone, float width)
    {
        CurvePoint point = this.create(String.valueOf(this.getList().size()));

        point.bone.set(bone);
        point.width.set(width);
        this.add(point);

        return point;
    }

    public void removeAt(int index)
    {
        if (index >= 0 && index < this.getAllTyped().size())
        {
            this.getAllTyped().remove(index);
            this.sync();
        }
    }
}
