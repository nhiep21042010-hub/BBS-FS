package com.bbsvfx.vfxlights.forms.values;

/**
 * How surfaces answer this light: the edge along a silhouette, the grazing sheen inside the highlight,
 * light through thin things, and the inked contour.
 *
 * <p>One property, one track — see {@link LightStruct}.</p>
 */
public class LightStyle extends LightStruct<LightStyle>
{
    private static final LightField[] SCHEMA =
    {
        new LightField("rim", 0F, 2F),
        new LightField("rim_width", 0F, 1F),
        new LightField("sheen", 0F, 2F),
        new LightField("translucency", 0F, 1F),
        /* Outline strengths keep their open top end: a gaffer pushes a contour far past "1". */
        new LightField("outline", 0F, Float.MAX_VALUE),
        new LightField("outline_width", 1F, 4F),
        new LightField("outline_blur", 0F, 1F),
        new LightField("outline_inner", 0F, Float.MAX_VALUE),
        new LightField("outline_target", 0F, 2F),
        new LightField("outline_blend", 0F, 2F)
    };

    /** Edge light along silhouettes, for the key/fill/rim setup. Zero = pure physics only. */
    public float rim;

    /** Width of the rim strip across the silhouette (0 = hairline, 1 = a wide strip). */
    public float rimWidth = 0.35F;

    /** Grazing-angle Fresnel inside the highlight: 1 = physics, 0 = off, 2 = hot. */
    public float sheen = 1F;

    /** Light bleeding through thin occluders — backlit leaves, fabric, banners. */
    public float translucency;

    /** Hardens the rim into an inked contour. Zero = the plain soft rim, unchanged. */
    public float outline;

    /** Contour line radius in screen pixels. */
    public float outlineWidth = 2F;

    /** Contour softness: 0 = a hard inked line, 1 = a wide feathered one. */
    public float outlineBlur = 0.3F;

    /** Interior contour: edge-detect lines INSIDE the geometry (creases, overlaps), not just the
     * silhouette. Zero = silhouette only. */
    public float outlineInner;

    /** What the contour draws on: 0 = world and models, 1 = models only, 2 = world only. */
    public int outlineTarget;

    /** Contour blend mode: 0 = add (default), 1 = screen, 2 = overlay. */
    public int outlineBlend;

    @Override
    public LightField[] schema()
    {
        return SCHEMA;
    }

    @Override
    public float[] fields()
    {
        return new float[] {this.rim, this.rimWidth, this.sheen, this.translucency,
            this.outline, this.outlineWidth, this.outlineBlur, this.outlineInner, this.outlineTarget,
            this.outlineBlend};
    }

    @Override
    public void setFields(float[] v)
    {
        this.rim = SCHEMA[0].clamp(v[0]);
        this.rimWidth = SCHEMA[1].clamp(v[1]);
        this.sheen = SCHEMA[2].clamp(v[2]);
        this.translucency = SCHEMA[3].clamp(v[3]);
        this.outline = SCHEMA[4].clamp(v[4]);
        this.outlineWidth = SCHEMA[5].clamp(v[5]);
        this.outlineBlur = SCHEMA[6].clamp(v[6]);

        /* Length-guarded: arrays saved by an older schema (films, presets) carry fewer dials. */
        if (v.length > 7)
        {
            this.outlineInner = SCHEMA[7].clamp(v[7]);
        }

        if (v.length > 8)
        {
            this.outlineTarget = (int) SCHEMA[8].clamp(v[8]);
        }

        if (v.length > 9)
        {
            this.outlineBlend = (int) SCHEMA[9].clamp(v[9]);
        }
    }

    @Override
    public LightStyle copy()
    {
        LightStyle copy = new LightStyle();

        copy.setFields(this.fields());

        return copy;
    }
}
