package com.bbsvfx.vfxlights;

import com.bbsvfx.vfxlights.light.Light;
import com.bbsvfx.vfxlights.light.LightRegistry;

/**
 * Public API: how another mod puts light into a frame.
 *
 * <p><b>This exists because the interesting light is not placed by hand.</b> A gaffer positions lamps; an
 * explosion does not. Its fireball, its popcorn detonations, its burning debris are hundreds of sources
 * that appear and vanish inside a second, and the only thing that can describe them is the code producing
 * the effect. So the same registry that collects authored forms takes submissions from code, and neither
 * side is privileged.</p>
 *
 * <p><b>Submit every frame, for that frame only.</b> Nothing here persists — a light exists in the frame
 * it was submitted in and is gone afterwards. That sounds wasteful and is in fact the point: effects in
 * BBS VFX are closed-form functions of simulation time, so the light of an explosion at t=1.4s is computed
 * from the same formula as its fireball. Scrub backwards and the lighting is identical, because there is
 * no accumulated state to be wrong.</p>
 *
 * <p>Typical use, from an effect's per-frame render:</p>
 * <pre>{@code
 * float t = form.simTime();
 * float heat = (float) Math.exp(-t / 4.8F);
 *
 * VfxLights.sphere(x, y, z, radius * t, 1F, 0.75F * heat, 0.3F * heat, 40F * heat, 64F);
 * }</pre>
 *
 * <p>Call from the render thread. Every method returns the {@link Light} so callers can adjust anything
 * the convenience signature does not cover — or {@code null} if the frame is already full.</p>
 */
public final class VfxLights
{
    private VfxLights()
    {
    }

    /**
     * An omnidirectional source.
     *
     * @param range distance in blocks past which the light is culled
     */
    public static Light point(double x, double y, double z, float r, float g, float b,
        float intensity, float range)
    {
        Light light = LightRegistry.submit();

        if (light == null)
        {
            return null;
        }

        light.type = Light.Type.POINT;
        light.x = x;
        light.y = y;
        light.z = z;
        light.r = r;
        light.g = g;
        light.b = b;
        light.intensity = intensity;
        light.range = range;

        return light;
    }

    /**
     * A glowing ball of the given radius — the shape most VFX actually want.
     *
     * <p>A fireball is not a point: it is metres across, so its shadows are soft and its highlight is a
     * disc. Submitting it as a sphere area light gets that for free, and the softness tracks the radius
     * as the ball grows.</p>
     */
    public static Light sphere(double x, double y, double z, float radius, float r, float g, float b,
        float intensity, float range)
    {
        Light light = LightRegistry.submit();

        if (light == null)
        {
            return null;
        }

        light.type = Light.Type.AREA;
        light.areaShape = Light.AreaShape.SPHERE;
        light.x = x;
        light.y = y;
        light.z = z;
        light.width = Math.max(0.01F, radius * 2F);
        light.r = r;
        light.g = g;
        light.b = b;
        light.intensity = intensity;
        light.range = range;

        return light;
    }

    /** A directed cone. {@code angle} is the full outer angle in degrees. */
    public static Light spot(double x, double y, double z, float dirX, float dirY, float dirZ,
        float angle, float innerAngle, float r, float g, float b, float intensity, float range)
    {
        Light light = LightRegistry.submit();

        if (light == null)
        {
            return null;
        }

        light.type = Light.Type.SPOT;
        light.x = x;
        light.y = y;
        light.z = z;
        light.dirX = dirX;
        light.dirY = dirY;
        light.dirZ = dirZ;
        light.normaliseDirection();
        light.setCone(angle, innerAngle);
        light.r = r;
        light.g = g;
        light.b = b;
        light.intensity = intensity;
        light.range = range;

        return light;
    }

    /**
     * A flat rectangular emitter facing {@code dir}, sized {@code width} by {@code height}.
     *
     * <p>Useful beyond studio lamps: a glowing rune, a portal membrane and a lava surface are all
     * rectangles that should light the room with a soft, shaped falloff rather than a point's harshness.</p>
     */
    public static Light rect(double x, double y, double z, float dirX, float dirY, float dirZ,
        float width, float height, float r, float g, float b, float intensity, float range)
    {
        Light light = LightRegistry.submit();

        if (light == null)
        {
            return null;
        }

        light.type = Light.Type.AREA;
        light.areaShape = Light.AreaShape.RECT;
        light.x = x;
        light.y = y;
        light.z = z;
        light.dirX = dirX;
        light.dirY = dirY;
        light.dirZ = dirZ;
        light.normaliseDirection();
        light.width = width;
        light.height = height;
        light.r = r;
        light.g = g;
        light.b = b;
        light.intensity = intensity;
        light.range = range;

        return light;
    }

    /**
     * A tube emitter of the given length and thickness, running along {@code dir} — a beam's core, a
     * neon strip, a lightsaber.
     */
    public static Light tube(double x, double y, double z, float dirX, float dirY, float dirZ,
        float length, float thickness, float r, float g, float b, float intensity, float range)
    {
        Light light = LightRegistry.submit();

        if (light == null)
        {
            return null;
        }

        light.type = Light.Type.AREA;
        light.areaShape = Light.AreaShape.TUBE;
        light.x = x;
        light.y = y;
        light.z = z;
        light.dirX = dirX;
        light.dirY = dirY;
        light.dirZ = dirZ;
        light.normaliseDirection();
        light.width = length;
        light.thickness = thickness;
        light.r = r;
        light.g = g;
        light.b = b;
        light.intensity = intensity;
        light.range = range;

        return light;
    }

    /**
     * A bounded volume of directionless fill, radius {@code radius} around the given point.
     *
     * <p>What an explosion's afterglow or a magic aura wants: it lifts everything nearby without adding
     * another shadow to a shot that already has one from the key light.</p>
     */
    public static Light ambientZone(double x, double y, double z, float radius,
        float r, float g, float b, float intensity)
    {
        Light light = LightRegistry.submit();

        if (light == null)
        {
            return null;
        }

        light.type = Light.Type.AMBIENT;
        light.x = x;
        light.y = y;
        light.z = z;
        light.range = radius;
        light.r = r;
        light.g = g;
        light.b = b;
        light.intensity = intensity;
        light.shadows = false;

        return light;
    }

    /**
     * Raw submission for anything the convenience methods do not cover: fill in the returned light
     * yourself. Returns {@code null} when the frame is full.
     */
    public static Light submit()
    {
        return LightRegistry.submit();
    }

    /** How many lights are in the current frame — for a debug overlay or a budget check. */
    public static int getLightCount()
    {
        return LightRegistry.getCount();
    }
}
