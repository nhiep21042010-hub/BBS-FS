package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.settings.values.mc.ValueBlockState;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import net.minecraft.block.Blocks;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A 3D curve form: an editable Catmull-Rom spline through user-placed control points, rendered in the
 * world as a coloured line, a solid extruded tube, or a swept block. Lives in BBS's "miscellaneous"
 * form section; registered from the addon (see {@code com.bbsvfx.bbsvfx}).
 *
 * <p>Each control point's <b>position</b> is stored in the form's animatable {@link #pose} under a
 * stable bone name, so the replay editor can keyframe every point. The {@link CurvePoint} list holds
 * the bone names and per-point widths and defines the point order.</p>
 */
public class CurveForm extends Form
{
    /** Animatable per-point positions: one bone per control point (keyframed by the replay editor). */
    public final ValuePose pose = new ValuePose("pose", new Pose());
    public final CurvePointList points = new CurvePointList("points");
    public final ValueColor color = new ValueColor("color", new Color(1F, 1F, 0F, 1F));
    /** Global thickness multiplier applied on top of the per-point widths. */
    public final ValueFloat width = new ValueFloat("width", 1F, 0.001F, Float.POSITIVE_INFINITY);
    public final ValueBoolean closed = new ValueBoolean("closed", false);
    public final ValueInt resolution = new ValueInt("resolution", 16, 1, 256);

    /** Two independent extrusion outputs: a solid-colour tube and/or a swept block. */
    public final ValueBoolean extrudeSolid = new ValueBoolean("extrude_solid", false);
    public final ValueBoolean extrudeBlocks = new ValueBoolean("extrude_blocks", false);
    public final ValueBlockState extrudeBlock = new ValueBlockState("extrude_block");
    public final ValueFloat extrudeSpacing = new ValueFloat("extrude_spacing", 1F, 0.05F, Float.POSITIVE_INFINITY);
    public final ValueFloat extrudeScale = new ValueFloat("extrude_scale", 1F, 0.01F, Float.POSITIVE_INFINITY);
    public final ValueBoolean extrudeAlign = new ValueBoolean("extrude_align", true);
    /** Stretch each block along the tangent to span {@link #extrudeSpacing}, so blocks connect (no gaps). */
    public final ValueBoolean extrudeConnect = new ValueBoolean("extrude_connect", true);

    /** Taper: scales the width from {@link #taperStart} to {@link #taperEnd} along the curve. */
    public final ValueBoolean taperEnabled = new ValueBoolean("taper_enabled", false);
    public final ValueFloat taperStart = new ValueFloat("taper_start", 1F, 0F, 1F);
    public final ValueFloat taperEnd = new ValueFloat("taper_end", 0F, 0F, 1F);
    /** Taper easing: 0 linear, 1 ease-in, 2 ease-out, 3 smooth. */
    public final ValueInt taperCurve = new ValueInt("taper_curve", 0, 0, 3);

    /** Trim: render only the portion of the curve between these (animatable) 0..1 fractions. */
    public final ValueFloat trimStart = new ValueFloat("trim_start", 0F, 0F, 1F);
    public final ValueFloat trimEnd = new ValueFloat("trim_end", 1F, 0F, 1F);

    public CurveForm()
    {
        super();

        this.add(this.pose);
        this.add(this.points);
        this.add(this.color);
        this.add(this.width);
        this.add(this.closed);
        this.add(this.resolution);
        this.add(this.extrudeSolid);
        this.add(this.extrudeBlocks);
        this.add(this.extrudeBlock);
        this.add(this.extrudeSpacing);
        this.add(this.extrudeScale);
        this.add(this.extrudeAlign);
        this.add(this.extrudeConnect);
        this.add(this.taperEnabled);
        this.add(this.taperStart);
        this.add(this.taperEnd);
        this.add(this.taperCurve);
        this.add(this.trimStart);
        this.add(this.trimEnd);

        this.extrudeBlock.set(Blocks.GRASS_BLOCK.getDefaultState());

        /* Starting preset: a 3-point arc so a fresh curve has visible geometry and a non-empty
         * preview. Safe to seed here — the value tree is replaced when loading saved data. */
        this.addPoint(-1F, 0F, 0F, 0.2F);
        this.addPoint(0F, 0.6F, 0F, 0.2F);
        this.addPoint(1F, 0F, 0F, 0.2F);
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "3d curve";
    }

    /* Show "3d curve" everywhere instead of the raw type id ("bbsvfx:curve"). Serialization uses the
     * form factory's type lookup, not this, so overriding the display id is safe. */
    @Override
    public String getFormId()
    {
        return "3d curve";
    }

    /** Add a control point at the given local position; its position is stored as a fresh pose bone. */
    public CurvePoint addPoint(float x, float y, float z, float width)
    {
        String bone = this.freshBoneName();
        CurvePoint point = this.points.addRaw(bone, width);

        PoseTransform transform = new PoseTransform();

        transform.translate.set(x, y, z);
        this.pose.get().transforms.put(bone, transform);

        return point;
    }

    public void removePoint(int index)
    {
        List<CurvePoint> pts = this.points.getAllTyped();

        if (index < 0 || index >= pts.size())
        {
            return;
        }

        this.pose.get().transforms.remove(pts.get(index).bone.get());
        this.points.removeAt(index);
    }

    /** Current (possibly animated) local position of a point, read from its pose bone. */
    public Vector3f pointPosition(CurvePoint point)
    {
        PoseTransform transform = this.pose.get().transforms.get(point.bone.get());

        return transform == null ? new Vector3f() : new Vector3f(transform.translate);
    }

    /** Set a point's base position on its pose bone (creating the bone if missing). */
    public void setPointPosition(CurvePoint point, float x, float y, float z)
    {
        this.ensureBone(point).translate.set(x, y, z);
    }

    /** Current (possibly animated) local rotation (degrees) of a point, from its pose bone. */
    public Vector3f pointRotation(CurvePoint point)
    {
        PoseTransform transform = this.pose.get().transforms.get(point.bone.get());

        return transform == null ? new Vector3f() : new Vector3f(transform.rotate);
    }

    /** Set a point's base rotation (degrees) on its pose bone (creating the bone if missing). */
    public void setPointRotation(CurvePoint point, float x, float y, float z)
    {
        this.ensureBone(point).rotate.set(x, y, z);
    }

    /**
     * The pose-bone transform backing a point (created if missing). Its {@code translate} is the point's
     * position and {@code rotate} its rotation — surfaced so the editor can bind a transform/gizmo widget
     * straight onto the live point data.
     */
    public PoseTransform pointTransformOf(CurvePoint point)
    {
        return this.ensureBone(point);
    }

    private PoseTransform ensureBone(CurvePoint point)
    {
        PoseTransform transform = this.pose.get().transforms.get(point.bone.get());

        if (transform == null)
        {
            transform = new PoseTransform();
            this.pose.get().transforms.put(point.bone.get(), transform);
        }

        return transform;
    }

    /** A pose-bone name not currently used by any point ("point_0", "point_1", ...). */
    private String freshBoneName()
    {
        Set<String> used = new HashSet<>();

        for (CurvePoint point : this.points.getAllTyped())
        {
            used.add(point.bone.get());
        }

        int i = 0;

        while (used.contains("point_" + i))
        {
            i++;
        }

        return "point_" + i;
    }

    /** Control-point positions in form-local space (from the pose), in order. */
    public List<Vector3f> controlPoints()
    {
        List<Vector3f> out = new ArrayList<>();

        for (CurvePoint point : this.points.getAllTyped())
        {
            out.add(this.pointPosition(point));
        }

        return out;
    }

    /** Per-control-point widths, parallel to {@link #controlPoints()}. */
    public float[] controlWidths()
    {
        List<CurvePoint> pts = this.points.getAllTyped();
        float[] widths = new float[pts.size()];

        for (int i = 0; i < pts.size(); i++)
        {
            widths[i] = pts.get(i).width.get();
        }

        return widths;
    }

    /** Stable pose-bone names, one per point — surfaced to the pose editor via the renderer. */
    public List<String> boneNames()
    {
        List<String> bones = new ArrayList<>();

        for (CurvePoint point : this.points.getAllTyped())
        {
            bones.add(point.bone.get());
        }

        return bones;
    }

    /** Interpolated per-point rotation (degrees) at curve parameter t in [0,1], for block alignment. */
    public Vector3f rotationAt(float t)
    {
        List<CurvePoint> pts = this.points.getAllTyped();

        if (pts.isEmpty())
        {
            return new Vector3f();
        }

        if (pts.size() == 1)
        {
            return this.pointRotation(pts.get(0));
        }

        float clamped = Math.max(0F, Math.min(1F, t));
        float scaled = clamped * (pts.size() - 1);
        int i = Math.min(pts.size() - 2, (int) Math.floor(scaled));
        float f = scaled - i;

        return this.pointRotation(pts.get(i)).lerp(this.pointRotation(pts.get(i + 1)), f);
    }

    /**
     * Arc-length sample: at fraction {@code f} in [0,1] of the curve's total length, write the
     * form-local position and the (normalised) tangent there. Constant-speed parameterisation, used by
     * the camera follow-curve modifier and the actor follow so travel speed does not depend on point
     * spacing.
     *
     * <p>Both outputs are evaluated <b>analytically</b> from the Catmull-Rom spline (position) and its
     * derivative (tangent), not from the densified polyline — a polyline gives a piecewise-constant
     * tangent and piecewise-linear position, which read as jerky steps in a following camera/actor.
     * A finely densified arc-length table only maps {@code f} to the smooth curve parameter; the
     * returned values come from the smooth curve, so motion is C1-continuous.</p>
     */
    public void arcPoint(float f, Vector3f outPos, Vector3f outTangent)
    {
        List<Vector3f> cp = this.controlPoints();
        int n = cp.size();

        if (n == 0)
        {
            outPos.set(0F, 0F, 0F);
            outTangent.set(0F, 0F, 1F);

            return;
        }

        if (n == 1)
        {
            outPos.set(cp.get(0));
            outTangent.set(0F, 0F, 1F);

            return;
        }

        boolean loop = this.closed.get();
        int segments = loop ? n : n - 1;
        /* Sub-steps per span purely for the arc-length lookup; denser => more accurate constant speed. */
        int sub = Math.max(8, this.resolution.get());
        int count = segments * sub + 1;

        Vector3f[] pts = new Vector3f[count];
        /* Continuous global curve parameter g in [0, segments] at each densified sample (g = span + u),
         * monotonic and continuous across span boundaries — so we can map an arc-length hit straight
         * back to (span, u) for the analytic evaluation. */
        float[] g = new float[count];
        float[] cum = new float[count];
        int k = 0;

        for (int seg = 0; seg < segments; seg++)
        {
            for (int i = 0; i < sub; i++)
            {
                float u = i / (float) sub;

                pts[k] = CurveSpline.point(cp, seg, u, loop);
                g[k] = seg + u;
                k++;
            }
        }

        pts[k] = CurveSpline.point(cp, segments - 1, 1F, loop);
        g[k] = segments;

        for (int i = 1; i < count; i++)
        {
            cum[i] = cum[i - 1] + pts[i].distance(pts[i - 1]);
        }

        float total = cum[count - 1];

        if (total < 1e-6F)
        {
            outPos.set(pts[0]);
            outTangent.set(0F, 0F, 1F);

            return;
        }

        float target = Math.max(0F, Math.min(1F, f)) * total;
        int i = 1;

        while (i < count - 1 && cum[i] < target)
        {
            i++;
        }

        float segLen = cum[i] - cum[i - 1];
        float t = segLen > 1e-6F ? (target - cum[i - 1]) / segLen : 0F;
        float gTarget = g[i - 1] + (g[i] - g[i - 1]) * t;

        int span = Math.min(segments - 1, (int) Math.floor(gTarget));
        float u = gTarget - span;

        outPos.set(CurveSpline.point(cp, span, u, loop));

        Vector3f tangent = CurveSpline.tangent(cp, span, u, loop);

        if (tangent.lengthSquared() < 1e-9F)
        {
            outTangent.set(0F, 0F, 1F);
        }
        else
        {
            outTangent.set(tangent.normalize());
        }
    }

    /** Densified spline samples (position + per-point width, with taper applied) in form-local space. */
    public List<CurveSpline.Sample> sample()
    {
        List<CurveSpline.Sample> samples = CurveSpline.build(
            this.controlPoints(), this.controlWidths(), this.resolution.get(), this.closed.get());

        if (this.taperEnabled.get())
        {
            int n = samples.size();

            for (int i = 0; i < n; i++)
            {
                float t = n > 1 ? i / (float) (n - 1) : 0F;

                samples.get(i).width *= this.taperFactor(t);
            }
        }

        return samples;
    }

    /** Samples to actually render: {@link #sample()} cut to the (animatable) trim range. */
    public List<CurveSpline.Sample> sampleRendered()
    {
        return trim(this.sample(), this.trimStart.get(), this.trimEnd.get());
    }

    /** Taper width multiplier at curve parameter t in [0,1]. */
    private float taperFactor(float t)
    {
        float s = this.taperStart.get();
        float e = this.taperEnd.get();

        return s + (e - s) * taperEase(Math.max(0F, Math.min(1F, t)), this.taperCurve.get());
    }

    private static float taperEase(float t, int mode)
    {
        return switch (mode)
        {
            case 1 -> t * t;                    // ease in
            case 2 -> 1F - (1F - t) * (1F - t); // ease out
            case 3 -> t * t * (3F - 2F * t);    // smooth
            default -> t;                       // linear
        };
    }

    /** Cut a sample polyline to [ts, te] (0..1), interpolating new samples at the boundaries. */
    private static List<CurveSpline.Sample> trim(List<CurveSpline.Sample> full, float ts, float te)
    {
        ts = Math.max(0F, Math.min(1F, ts));
        te = Math.max(ts, Math.min(1F, te));

        int total = full.size() - 1;

        if (total < 1 || (ts <= 0F && te >= 1F))
        {
            return full;
        }

        float startF = ts * total;
        float endF = te * total;

        List<CurveSpline.Sample> out = new ArrayList<>();

        out.add(sampleAt(full, startF));

        for (int i = (int) Math.ceil(startF); i <= (int) Math.floor(endF); i++)
        {
            if (i > startF && i < endF)
            {
                out.add(full.get(i));
            }
        }

        out.add(sampleAt(full, endF));

        return out;
    }

    /** Linearly interpolated sample at fractional index f. */
    private static CurveSpline.Sample sampleAt(List<CurveSpline.Sample> full, float f)
    {
        int i = Math.max(0, Math.min(full.size() - 2, (int) Math.floor(f)));
        float t = f - i;
        CurveSpline.Sample a = full.get(i);
        CurveSpline.Sample b = full.get(i + 1);

        return new CurveSpline.Sample(new Vector3f(a.pos).lerp(b.pos, t), a.width + (b.width - a.width) * t);
    }
}
