package com.bbsvfx.vfxlights.forms;

import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;

/**
 * Directionless fill: light that arrives from everywhere at once, lifting shadows without adding a
 * second shadow of its own.
 *
 * <p>This is the other half of how a scene is actually lit. A key light shapes the subject; ambient
 * decides how deep the shadows go, and therefore whether a shot reads as noir or as daylight. Minecraft
 * gives one global ambient value for the whole world, which cannot be aimed at a room, an actor or a
 * moment.</p>
 *
 * <p><b>Two modes, because "ambient" means two different things.</b> {@link Mode#ZONE} is a bounded volume
 * of fill — put a soft glow inside a cave, under a table, around an actor. {@link Mode#HEMISPHERE} is the
 * classic sky/ground model: one colour arriving from above, another bouncing up from below, blended by
 * the surface normal. The second is nearly free and makes exteriors sit right; the first is the one a
 * gaffer reaches for. An image-based (environment map) mode is the natural third, deferred until the
 * shading backend exists to hang it on.</p>
 */
public class AmbientLightForm extends LightForm
{
    /** Ordinal is serialised — append only, never reorder. */
    public enum Mode
    {
        /** A bounded volume of even fill light. */
        ZONE,
        /** Sky colour from above, ground bounce from below, blended by surface normal. */
        HEMISPHERE;

        public static Mode byIndex(int index)
        {
            Mode[] values = values();

            return values[Math.max(0, Math.min(values.length - 1, index))];
        }
    }

    /** Volume shape for {@link Mode#ZONE}. */
    public enum Volume
    {
        SPHERE,
        BOX;

        public static Volume byIndex(int index)
        {
            Volume[] values = values();

            return values[Math.max(0, Math.min(values.length - 1, index))];
        }
    }

    public final ValueInt mode = new ValueInt("mode", Mode.ZONE.ordinal(), 0, Mode.values().length - 1);
    public final ValueInt volume = new ValueInt("volume", Volume.SPHERE.ordinal(), 0, Volume.values().length - 1);

    /** Half-extents of the BOX volume, in blocks. The SPHERE volume uses {@code range} as its radius. */
    public final ValueFloat sizeX = new ValueFloat("size_x", 8F, 0.1F, 256F);
    public final ValueFloat sizeY = new ValueFloat("size_y", 4F, 0.1F, 256F);
    public final ValueFloat sizeZ = new ValueFloat("size_z", 8F, 0.1F, 256F);

    /**
     * Fraction of the volume over which the fill fades out at the boundary, 0..1. A hard edge would be
     * visible as a seam crawling across the floor as the camera moves — this is not an aesthetic option
     * so much as the thing that makes a bounded ambient usable at all.
     */
    public final ValueFloat edgeFalloff = new ValueFloat("edge_falloff", 0.35F, 0F, 1F);

    /** Colour arriving from below in {@link Mode#HEMISPHERE}; the inherited colour is the sky half. */
    public final ValueColor groundColor = new ValueColor("ground_color", new Color(0.25F, 0.22F, 0.18F, 1F));

    /**
     * How much ambient occlusion darkens this fill, 0..1. Ambient that ignores occlusion flattens a scene
     * into a matte painting — creases and corners stop reading as geometry.
     */
    public final ValueFloat occlusion = new ValueFloat("occlusion", 1F, 0F, 1F);

    public AmbientLightForm()
    {
        super();

        /* Ambient light has no source to cast from, so shadows are meaningless here — the shadowing that
         * matters for fill is occlusion, above. Keep the inherited value off so no backend tries. */
        this.shadows.set(false);

        this.add(this.mode);
        this.add(this.volume);
        this.add(this.sizeX);
        this.add(this.sizeY);
        this.add(this.sizeZ);
        this.add(this.edgeFalloff);
        this.add(this.groundColor);
        this.add(this.occlusion);
    }

    public Mode getMode()
    {
        return Mode.byIndex(this.mode.get());
    }

    public Volume getVolume()
    {
        return Volume.byIndex(this.volume.get());
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "ambient light";
    }

    @Override
    public String getFormId()
    {
        return "ambient_light";
    }
}
