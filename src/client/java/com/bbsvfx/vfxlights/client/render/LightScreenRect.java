package com.bbsvfx.vfxlights.client.render;

import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import com.bbsvfx.vfxlights.light.Light;

/**
 * Screen-space bounds of a light's sphere of influence, for scissoring the per-light fullscreen passes.
 *
 * <p>Both fallback backends draw ONE FULLSCREEN QUAD PER LIGHT — the surface composite and the
 * volumetric march. Every pixel outside the light's range is shaded anyway and contributes zero;
 * with a dozen lamps that is a dozen full frames of wasted fill (and the march is 32–48 depth taps
 * per pixel). Nothing a light does — falloff, caustics, dispersion spill, rim, SSS — reaches beyond
 * {@code range} of its position, so the sphere's projected rectangle bounds all of it.</p>
 *
 * <p><b>Conservative by construction.</b> The rect is the pixel AABB of the range-sphere's bounding
 * box projected through the frame's matrices. A projective map keeps segments straight, so a box
 * fully in front of the camera projects INSIDE the hull of its projected corners — the AABB can only
 * be too big, never too small. Every doubtful case answers "fullscreen": the camera inside the
 * sphere, any box corner at or behind the near plane (w ≤ 0, where the projection folds), ambient
 * lights (no falloff sphere at all). A rect that clamps to nothing reports OFFSCREEN and the light's
 * draw is skipped entirely — a lamp behind the camera costs zero fill.</p>
 */
public final class LightScreenRect
{
    public static final int OFFSCREEN = 0;
    public static final int PARTIAL = 1;
    public static final int FULLSCREEN = 2;

    /** Pixel rect (x, y, width, height) of the last {@link #PARTIAL} result. Render thread only. */
    public static final int[] RECT = new int[4];

    /** Safety margin in pixels, absorbing float rounding at the rect's edges. */
    private static final int PAD = 4;

    /** Dev kill-switch (-Dvfxlights.noscissor): every light answers FULLSCREEN — the exact
     * pre-scissor behaviour for both consumers (composite and march), to bisect clipping bugs. */
    private static final boolean DISABLED = System.getProperty("vfxlights.noscissor") != null;

    private static final Vector4f CORNER = new Vector4f();

    private LightScreenRect()
    {
    }

    /**
     * Classify {@code light}'s footprint on a {@code width}×{@code height} target under
     * {@code viewProj}, which (like every matrix in this codebase) maps CAMERA-RELATIVE world
     * coordinates to clip space. On {@link #PARTIAL}, {@link #RECT} holds the scissor box.
     */
    public static int compute(Light light, Matrix4f viewProj, Vec3d camera, int width, int height)
    {
        if (DISABLED)
        {
            return FULLSCREEN;
        }

        /* Ambient has no distance falloff sphere (hemisphere mode literally covers the world). */
        if (light.type == Light.Type.AMBIENT)
        {
            return FULLSCREEN;
        }

        float range = light.range;

        if (!(range > 0F))
        {
            return FULLSCREEN;
        }

        double cx = light.x - camera.x;
        double cy = light.y - camera.y;
        double cz = light.z - camera.z;

        /* Camera inside (or nearly inside) the sphere: everything on screen can be lit. */
        double inside = range * 1.05D;

        if (cx * cx + cy * cy + cz * cz <= inside * inside)
        {
            return FULLSCREEN;
        }

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < 8; i++)
        {
            CORNER.set(
                (float) (cx + ((i & 1) == 0 ? -range : range)),
                (float) (cy + ((i & 2) == 0 ? -range : range)),
                (float) (cz + ((i & 4) == 0 ? -range : range)), 1F);
            viewProj.transform(CORNER);

            /* w is affine in position, so a corner at/behind the near plane means part of the BOX
             * is too — the projection folds there and no 2D rect is trustworthy. */
            if (CORNER.w <= 1e-4F)
            {
                return FULLSCREEN;
            }

            float px = (CORNER.x / CORNER.w * 0.5F + 0.5F) * width;
            float py = (CORNER.y / CORNER.w * 0.5F + 0.5F) * height;

            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }

        int x0 = Math.max((int) Math.floor(minX) - PAD, 0);
        int y0 = Math.max((int) Math.floor(minY) - PAD, 0);
        int x1 = Math.min((int) Math.ceil(maxX) + PAD, width);
        int y1 = Math.min((int) Math.ceil(maxY) + PAD, height);

        if (x0 >= x1 || y0 >= y1)
        {
            return OFFSCREEN;
        }

        if (x0 == 0 && y0 == 0 && x1 == width && y1 == height)
        {
            return FULLSCREEN;
        }

        RECT[0] = x0;
        RECT[1] = y0;
        RECT[2] = x1 - x0;
        RECT[3] = y1 - y0;

        return PARTIAL;
    }
}
