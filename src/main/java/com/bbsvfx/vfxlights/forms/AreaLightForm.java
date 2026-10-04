package com.bbsvfx.vfxlights.forms;

import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import com.bbsvfx.vfxlights.forms.values.LightBarn;
import com.bbsvfx.vfxlights.forms.values.LightFactories;

/**
 * A light with physical SIZE — the reason this addon exists.
 *
 * <p>Points and spots are infinitely small, and that single fact is what makes game lighting look like
 * game lighting: razor-sharp shadow edges and a pinprick specular highlight. A real lamp has a surface.
 * Its shadows soften with distance from the occluder, and its reflection in anything glossy takes the
 * SHAPE of the source — the long vertical streak down a sword blade is a softbox, not a dot.</p>
 *
 * <p><b>Softness is not a slider here.</b> Penumbra follows from {@link #width}/{@link #height} and the
 * geometry, the way it does in the world. Other implementations expose an abstract "shadow size" knob
 * precisely because their sources have no extent; ours does, so the knob would be a second, contradictory
 * source of truth.</p>
 *
 * <p>The shading maths this is built for is LTC (linearly transformed cosines): it evaluates a polygonal
 * source analytically, giving correct diffuse AND specular in constant time, and it is what Unreal and
 * Unity use for area lights. The alternative — a representative-point approximation — is cheaper but
 * breaks down at grazing angles and close range, which is exactly where a film camera lives.</p>
 */
public class AreaLightForm extends LightForm
{
    /** Emitter geometry. Ordinal is what gets serialised, so entries may be appended but never reordered. */
    public enum Shape
    {
        /** Softbox / panel / window. The workhorse. */
        RECT,
        /** Ring light, beauty dish, round practical. */
        DISC,
        /** Fluorescent tube, neon, strip light — a line source with radius. */
        TUBE,
        /** A glowing ball: fireball, explosion core, magic orb. */
        SPHERE;

        public static Shape byIndex(int index)
        {
            Shape[] values = values();

            return values[Math.max(0, Math.min(values.length - 1, index))];
        }
    }

    public final ValueInt shape = new ValueInt("shape", Shape.RECT.ordinal(), 0, Shape.values().length - 1);

    /** Emitter width in blocks (RECT), or diameter for DISC/SPHERE, or length for TUBE. */
    public final ValueFloat width = new ValueFloat("width", 2F, 0.01F, 128F);
    /** Emitter height in blocks. RECT only; the round shapes take their size from {@link #width}. */
    public final ValueFloat height = new ValueFloat("height", 1F, 0.01F, 128F);
    /** Tube thickness — a strip light is a cylinder, not a zero-width line. */
    public final ValueFloat thickness = new ValueFloat("thickness", 0.1F, 0.001F, 16F);

    /** Emit from the back face too. A window lights both rooms; a softbox does not. */
    public final ValueBoolean twoSided = new ValueBoolean("two_sided", false);

    /**
     * How wide the emitted cone is, 0..1: 1 is a bare lambertian panel, lower values narrow the beam the
     * way a honeycomb grid or a fresnel lens does.
     */
    public final ValueFloat spread = new ValueFloat("spread", 1F, 0.01F, 1F);

    /**
     * ★Barn doors: the four hinged flaps on a studio lamp, used to keep light off things you do not
     * want lit, plus how soft their cut is. There is no game-lighting equivalent, which is the point.
     *
     * <p>Grouped into one property, and therefore one film track: the flaps are swung as a set — the
     * shot needs the light off the wall, and which of the four does it is a detail of the same
     * decision.</p>
     */
    public final BaseKeyframeFactoryValue<LightBarn> barn = LightFactories.barn();

    /** Draw the emitter itself as a visible glowing surface, so the lamp appears in shot. */
    public final ValueBoolean showSource = new ValueBoolean("show_source", true);

    public AreaLightForm()
    {
        super();

        this.add(this.shape);
        this.add(this.width);
        this.add(this.height);
        this.add(this.thickness);
        this.add(this.twoSided);
        this.add(this.spread);
        this.add(this.barn);
        this.add(this.showSource);

        /* Kept for saves, but the glowing-emitter feature it drove is gone (a4ad307) — an invisible
         * value still deserialises and still costs nothing on the timeline. */
        this.showSource.invisible();
    }

    public Shape getShape()
    {
        return Shape.byIndex(this.shape.get());
    }

    /** Emitter surface area in square blocks — drives how bright the source reads for its intensity. */
    public float surfaceArea()
    {
        float w = this.width.get();

        switch (this.getShape())
        {
            case RECT:
                return w * this.height.get();
            case DISC:
                return (float) Math.PI * (w * 0.5F) * (w * 0.5F);
            case TUBE:
                return (float) Math.PI * this.thickness.get() * w;
            case SPHERE:
            default:
                return (float) Math.PI * w * w;
        }
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "area light";
    }

    @Override
    public String getFormId()
    {
        return "area_light";
    }
}
