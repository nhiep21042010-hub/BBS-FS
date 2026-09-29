package com.bbsvfx.bbsvfx.forms;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Catmull-Rom spline sampling for {@link CurveForm}. The spline passes through every control point;
 * each segment is subdivided into {@code resolution} steps. Position uses the Catmull-Rom basis;
 * per-point width is carried along by linear interpolation (so each control point can have its own
 * thickness — per-point extrusion).
 */
public final class CurveSpline
{
    private CurveSpline()
    {}

    /** One densified spline sample: world-local position plus the (taperable) per-point width there. */
    public static final class Sample
    {
        public final Vector3f pos;
        public float width;

        public Sample(Vector3f pos, float width)
        {
            this.pos = pos;
            this.width = width;
        }
    }

    /**
     * Build the densified polyline (positions + widths) through the control points.
     *
     * @param positions  control-point positions (>= 2)
     * @param widths     per-control-point widths (same length as positions)
     * @param resolution sub-segments per span (>= 1)
     * @param closed     whether the curve loops back to the first point
     */
    public static List<Sample> build(List<Vector3f> positions, float[] widths, int resolution, boolean closed)
    {
        List<Sample> out = new ArrayList<>();
        int n = positions.size();

        if (n < 2)
        {
            if (n == 1)
            {
                out.add(new Sample(new Vector3f(positions.get(0)), widths[0]));
            }

            return out;
        }

        int res = Math.max(1, resolution);
        int segments = closed ? n : n - 1;

        for (int s = 0; s < segments; s++)
        {
            int i0 = wrap(s - 1, n, closed);
            int i1 = wrap(s, n, closed);
            int i2 = wrap(s + 1, n, closed);
            int i3 = wrap(s + 2, n, closed);

            Vector3f p0 = positions.get(i0), p1 = positions.get(i1), p2 = positions.get(i2), p3 = positions.get(i3);
            float w1 = widths[i1], w2 = widths[i2];

            for (int i = 0; i < res; i++)
            {
                float t = i / (float) res;

                out.add(new Sample(catmullRom(p0, p1, p2, p3, t), w1 + (w2 - w1) * t));
            }
        }

        int last = closed ? 0 : n - 1;

        out.add(new Sample(new Vector3f(positions.get(last)), widths[last]));

        return out;
    }

    /**
     * Analytic curve position at span {@code s} (one of the control-point spans) and local parameter
     * {@code u} in [0,1]. Lets callers evaluate the smooth curve directly (instead of a densified
     * polyline), so a camera/actor following the curve gets a continuous, jitter-free position.
     */
    public static Vector3f point(List<Vector3f> positions, int s, float u, boolean closed)
    {
        int n = positions.size();
        Vector3f p0 = positions.get(wrap(s - 1, n, closed));
        Vector3f p1 = positions.get(wrap(s, n, closed));
        Vector3f p2 = positions.get(wrap(s + 1, n, closed));
        Vector3f p3 = positions.get(wrap(s + 2, n, closed));

        return catmullRom(p0, p1, p2, p3, u);
    }

    /**
     * Analytic (un-normalised) curve tangent — the Catmull-Rom derivative — at span {@code s} and local
     * parameter {@code u}. Continuous across spans (C1), so orientation aimed down it does not step.
     */
    public static Vector3f tangent(List<Vector3f> positions, int s, float u, boolean closed)
    {
        int n = positions.size();
        Vector3f p0 = positions.get(wrap(s - 1, n, closed));
        Vector3f p1 = positions.get(wrap(s, n, closed));
        Vector3f p2 = positions.get(wrap(s + 1, n, closed));
        Vector3f p3 = positions.get(wrap(s + 2, n, closed));

        return catmullRomTangent(p0, p1, p2, p3, u);
    }

    private static int wrap(int i, int n, boolean closed)
    {
        if (closed)
        {
            return ((i % n) + n) % n;
        }

        return Math.max(0, Math.min(n - 1, i));
    }

    public static Vector3f catmullRom(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t)
    {
        float t2 = t * t;
        float t3 = t2 * t;

        return new Vector3f(
            0.5F * ((2F * p1.x) + (-p0.x + p2.x) * t + (2F * p0.x - 5F * p1.x + 4F * p2.x - p3.x) * t2 + (-p0.x + 3F * p1.x - 3F * p2.x + p3.x) * t3),
            0.5F * ((2F * p1.y) + (-p0.y + p2.y) * t + (2F * p0.y - 5F * p1.y + 4F * p2.y - p3.y) * t2 + (-p0.y + 3F * p1.y - 3F * p2.y + p3.y) * t3),
            0.5F * ((2F * p1.z) + (-p0.z + p2.z) * t + (2F * p0.z - 5F * p1.z + 4F * p2.z - p3.z) * t2 + (-p0.z + 3F * p1.z - 3F * p2.z + p3.z) * t3)
        );
    }

    /** Derivative of {@link #catmullRom} with respect to {@code t} (the un-normalised tangent). */
    public static Vector3f catmullRomTangent(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t)
    {
        float t2 = t * t;

        return new Vector3f(
            0.5F * ((-p0.x + p2.x) + 2F * (2F * p0.x - 5F * p1.x + 4F * p2.x - p3.x) * t + 3F * (-p0.x + 3F * p1.x - 3F * p2.x + p3.x) * t2),
            0.5F * ((-p0.y + p2.y) + 2F * (2F * p0.y - 5F * p1.y + 4F * p2.y - p3.y) * t + 3F * (-p0.y + 3F * p1.y - 3F * p2.y + p3.y) * t2),
            0.5F * ((-p0.z + p2.z) + 2F * (2F * p0.z - 5F * p1.z + 4F * p2.z - p3.z) * t + 3F * (-p0.z + 3F * p1.z - 3F * p2.z + p3.z) * t2)
        );
    }
}
