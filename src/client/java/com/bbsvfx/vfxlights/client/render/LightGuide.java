package com.bbsvfx.vfxlights.client.render;

import net.minecraft.client.render.BufferBuilder;
import org.joml.Matrix4f;

/**
 * Geometry for the light gizmos: everything is plain TRIANGLES — never GL_LINES.
 *
 * <p><b>Why triangles only.</b> Wide lines are a lie in a core-profile world: most drivers clamp
 * {@code lineWidth} to 1, and several Iris shaderpacks leave line primitives at the mercy of state the
 * pack last touched. A ring drawn as a flat triangle ribbon and a line drawn as a thin box are ordinary
 * geometry — every driver rasterises them the same way, and the vanilla {@code position_color} shader
 * (no normals, no UV, no lightmap) gives a pack nothing to transform. This is the approach IRLite
 * (qualet, MIT) proved survives every shaderpack; the constants are its.</p>
 */
public final class LightGuide
{
    /** Enough segments that a ring reads as round at working distance. */
    public static final int SEGMENTS = 48;

    private LightGuide()
    {
    }

    /** Wire thickness scales with the size it describes, clamped so it stays a hairline, not a wall.
     * ~1.4&times; the original IRLite constants — the hairlines read too thin at editor distance. */
    public static float wireThickness(float radius)
    {
        return clamp(radius * 0.0035F, 0.003F, 0.013F);
    }

    /** Grab handles are drawn fatter than the visible wire: picking a 2-pixel hairline is misery.
     * Kept at the ORIGINAL wire constants — the wires got thicker, the handles were already fat. */
    public static float grabThickness(float radius)
    {
        return Math.max(clamp(radius * 0.0025F, 0.002F, 0.009F) * 6F, radius * 0.015F);
    }

    public static float clamp(float value, float min, float max)
    {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * A ring of {@code radius} as a flat triangle ribbon (inner/outer edge at ± half the thickness),
     * centred at the given point on the plane spanned by the two unit axes. Axes rather than a plane
     * enum so the same routine draws the three orthogonal rings of a sphere and the mouth of a cone.
     */
    public static void ring(BufferBuilder b, Matrix4f m, float cx, float cy, float cz, float radius, float thickness,
        float ax, float ay, float az, float bx, float by, float bz,
        float r, float g, float bl, float a)
    {
        if (radius <= 1.0E-4F)
        {
            return;
        }

        float half = thickness * 0.5F;
        float rIn = Math.max(radius - half, 0F);
        float rOut = radius + half;

        for (int i = 0; i < SEGMENTS; i++)
        {
            double a0 = i / (double) SEGMENTS * Math.PI * 2D;
            double a1 = (i + 1) / (double) SEGMENTS * Math.PI * 2D;

            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);

            float ix0 = cx + (ax * c0 + bx * s0) * rIn;
            float iy0 = cy + (ay * c0 + by * s0) * rIn;
            float iz0 = cz + (az * c0 + bz * s0) * rIn;
            float ox0 = cx + (ax * c0 + bx * s0) * rOut;
            float oy0 = cy + (ay * c0 + by * s0) * rOut;
            float oz0 = cz + (az * c0 + bz * s0) * rOut;
            float ix1 = cx + (ax * c1 + bx * s1) * rIn;
            float iy1 = cy + (ay * c1 + by * s1) * rIn;
            float iz1 = cz + (az * c1 + bz * s1) * rIn;
            float ox1 = cx + (ax * c1 + bx * s1) * rOut;
            float oy1 = cy + (ay * c1 + by * s1) * rOut;
            float oz1 = cz + (az * c1 + bz * s1) * rOut;

            vertex(b, m, ix0, iy0, iz0, r, g, bl, a);
            vertex(b, m, ox0, oy0, oz0, r, g, bl, a);
            vertex(b, m, ox1, oy1, oz1, r, g, bl, a);
            vertex(b, m, ix0, iy0, iz0, r, g, bl, a);
            vertex(b, m, ox1, oy1, oz1, r, g, bl, a);
            vertex(b, m, ix1, iy1, iz1, r, g, bl, a);
        }
    }

    /** A ring on the XY plane — the mouth of a cone pointing down +Z. */
    public static void ringZ(BufferBuilder b, Matrix4f m, float cx, float cy, float cz, float radius, float thickness,
        float r, float g, float bl, float a)
    {
        ring(b, m, cx, cy, cz, radius, thickness, 1F, 0F, 0F, 0F, 1F, 0F, r, g, bl, a);
    }

    /**
     * A filled disc on the XY plane: a ribbon whose thickness swallows its own radius. Used as the fat
     * grab cap for range dragging — a handle you can actually hit.
     */
    public static void discZ(BufferBuilder b, Matrix4f m, float cx, float cy, float cz, float radius,
        float r, float g, float bl, float a)
    {
        ringZ(b, m, cx, cy, cz, radius * 0.5F, radius, r, g, bl, a);
    }

    /**
     * A straight line as a thin box ({@code thickness} square cross-section). Visible from every angle,
     * unlike a camera-facing ribbon, and rasterised identically by every driver.
     */
    public static void line(BufferBuilder b, Matrix4f m, float x1, float y1, float z1,
        float x2, float y2, float z2, float thickness, float r, float g, float bl, float a)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length < 1.0E-6F)
        {
            return;
        }

        dx /= length;
        dy /= length;
        dz /= length;

        /* Any vector not parallel to the direction seeds the cross product. */
        float ux;
        float uy;
        float uz;

        if (Math.abs(dy) < 0.99F)
        {
            /* cross(dir, +Y) */
            ux = dz;
            uy = 0F;
            uz = -dx;
        }
        else
        {
            /* cross(dir, +X) */
            ux = 0F;
            uy = -dz;
            uz = dy;
        }

        float uLen = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        float half = thickness * 0.5F / uLen;

        ux *= half;
        uy *= half;
        uz *= half;

        /* v = cross(dir, u) — already unit-length before scaling since dir ⊥ u. */
        float vx = (dy * uz - dz * uy);
        float vy = (dz * ux - dx * uz);
        float vz = (dx * uy - dy * ux);

        /* Side walls. */
        quad(b, m, x1 + ux, y1 + uy, z1 + uz, x2 + ux, y2 + uy, z2 + uz, x2 - ux, y2 - uy, z2 - uz, x1 - ux, y1 - uy, z1 - uz, r, g, bl, a);
        quad(b, m, x1 + vx, y1 + vy, z1 + vz, x2 + vx, y2 + vy, z2 + vz, x2 - vx, y2 - vy, z2 - vz, x1 - vx, y1 - vy, z1 - vz, r, g, bl, a);

        /* End caps. */
        quad(b, m, x1 + ux, y1 + uy, z1 + uz, x1 + vx, y1 + vy, z1 + vz, x1 - ux, y1 - uy, z1 - uz, x1 - vx, y1 - vy, z1 - vz, r, g, bl, a);
        quad(b, m, x2 + ux, y2 + uy, z2 + uz, x2 + vx, y2 + vy, z2 + vz, x2 - ux, y2 - uy, z2 - uz, x2 - vx, y2 - vy, z2 - vz, r, g, bl, a);
    }

    /** Two triangles — culling is off for guide draws, so winding is irrelevant. */
    private static void quad(BufferBuilder b, Matrix4f m,
        float ax, float ay, float az, float bx, float by, float bz,
        float cx, float cy, float cz, float dx, float dy, float dz,
        float r, float g, float bl, float a)
    {
        vertex(b, m, ax, ay, az, r, g, bl, a);
        vertex(b, m, bx, by, bz, r, g, bl, a);
        vertex(b, m, cx, cy, cz, r, g, bl, a);
        vertex(b, m, ax, ay, az, r, g, bl, a);
        vertex(b, m, cx, cy, cz, r, g, bl, a);
        vertex(b, m, dx, dy, dz, r, g, bl, a);
    }

    private static void vertex(BufferBuilder b, Matrix4f m, float x, float y, float z,
        float r, float g, float bl, float a)
    {
        b.vertex(m, x, y, z).color(r, g, bl, a).next();
    }
}
