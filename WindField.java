package com.bbsvfx.bbsvfx.client;

/**
 * The WindForm's flow field, shared by everything that reacts to the wind — the foliage sway and the flying
 * leaves — so the world bends and the debris travels along the SAME velocities the visible volume draws.
 *
 * <p>Coordinates are the form's LOCAL frame (world − rounded origin, the frame the sway proxies and the leaf
 * crowns already live in), with the vortex axis on local (0, y, 0).</p>
 *
 * <p><b>Directional</b> returns the handle's direction everywhere. <b>Vortex</b> is a Rankine-style tornado:
 * the tangential speed rises linearly inside the core and falls off as 1/r outside it, plus an inward suction
 * and an updraft concentrated near the funnel. That combination — spin + pull in + lift — is what makes
 * debris corkscrew into the funnel instead of just circling it.</p>
 */
public final class WindField
{
    /** Horizontal wind direction (x, z) and its relative strength (0..1+) at a local point. */
    public static final class Sample
    {
        public float x;
        public float z;
        public float up;
        /** Magnitude relative to the field's nominal strength — drives how hard foliage bends. */
        public float mag;
    }

    private WindField() {}

    /**
     * @param lx,lz     local horizontal position (relative to the form origin).
     * @param vortex    true for the tornado field, false for the uniform directional push.
     * @param dirX,dirZ normalized handle direction (used by the directional field).
     * @param coreR     funnel radius at the ground.
     * @param swirl     tangential speed scale.
     * @param suction   inward pull scale.
     * @param updraft   vertical lift scale.
     */
    public static void sample(float lx, float lz, boolean vortex,
        float dirX, float dirZ, float coreR, float swirl, float suction, float updraft, Sample out)
    {
        if (!vortex)
        {
            out.x = dirX;
            out.z = dirZ;
            out.up = 0F;
            out.mag = 1F;

            return;
        }

        float r = (float) Math.sqrt(lx * lx + lz * lz);

        if (r < 1.0E-4F)
        {
            /* On the axis the tangent is undefined — pure lift. */
            out.x = 0F;
            out.z = 0F;
            out.up = updraft;
            out.mag = 1F;

            return;
        }

        float core = Math.max(coreR, 0.5F);

        /* Rankine profile: solid-body rotation inside the core, 1/r outside. */
        float tan = r < core ? (r / core) : (core / r);
        /* Suction and lift fade with distance so far-away foliage is barely touched. */
        float near = core / Math.max(r, core);

        float ux = -lz / r, uz = lx / r;          // tangential (counter-clockwise)
        float rx = -lx / r, rz = -lz / r;         // inward (toward the axis)

        float vx = ux * swirl * tan + rx * suction * near;
        float vz = uz * swirl * tan + rz * suction * near;

        float len = (float) Math.sqrt(vx * vx + vz * vz);

        if (len > 1.0E-4F)
        {
            out.x = vx / len;
            out.z = vz / len;
        }
        else
        {
            out.x = 0F;
            out.z = 0F;
        }

        out.up = updraft * near;
        /* Normalised against the swirl so a tree at the core bends fully and one at the rim barely. */
        out.mag = swirl > 1.0E-4F ? Math.min(1.5F, len / swirl) : 0F;
    }
}
