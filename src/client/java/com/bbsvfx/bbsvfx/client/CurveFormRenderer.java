package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.block.BlockState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.bbsvfx.bbsvfx.forms.CurveForm;
import com.bbsvfx.bbsvfx.forms.CurvePoint;
import com.bbsvfx.bbsvfx.forms.CurveSpline;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link CurveForm} in the world: the Catmull-Rom spline as a coloured line, or — when the
 * form's {@code extrude} toggle is on — a solid tube swept along the spline (radius = width / 2).
 *
 * <p>The tube frame is built by parallel transport: a starting normal is carried along the spline,
 * rotated by the minimal rotation between consecutive tangents, so the tube does not twist. Geometry
 * is immediate-mode {@code POSITION_COLOR} (no texture), matching how BBS draws helper geometry.</p>
 */
public class CurveFormRenderer extends FormRenderer<CurveForm>
{
    private static final int TUBE_SIDES = 16;
    private static final float[] RING_COS = new float[TUBE_SIDES];
    private static final float[] RING_SIN = new float[TUBE_SIDES];

    static
    {
        for (int i = 0; i < TUBE_SIDES; i++)
        {
            float a = (float) (2 * Math.PI * i / TUBE_SIDES);

            RING_COS[i] = (float) Math.cos(a);
            RING_SIN[i] = (float) Math.sin(a);
        }
    }

    public CurveFormRenderer(CurveForm form)
    {
        super(form);
    }

    @Override
    public List<String> getBones()
    {
        /* One bone per control point — lets the pose editor / replay editor animate each point. */
        return this.form.boneNames();
    }

    /**
     * Register one matrix per control-point bone ({@code point_N}) so the replay editor's pose-track
     * gizmo can find and drag each point. BBS resolves the bone gizmo from the renderer's matrix cache
     * (see {@code BaseFilmController.renderAxes} / {@code getGizmoBoneCompositeMatrix}); a plain form
     * renderer registers only its root frame, so without this the curve points have no gizmo there.
     *
     * <p>Same key scheme as {@code ModelFormRenderer} ({@code combinePaths(prefix, bone)}). The point's
     * frame is built on the form transform: {@code origin()} = position only (used by PARENT/WORLD gizmo
     * space — the default — so the move arrows sit on the point, axis-aligned to the form); {@code
     * matrix()} = position + the point's own rotation (LOCAL space + the rotate handles).</p>
     */
    @Override
    public void collectMatrices(IEntity entity, MatrixStack stack, MatrixCache matrices, String prefix, float transition)
    {
        super.collectMatrices(entity, stack, matrices, prefix, transition);

        List<CurvePoint> points = this.form.points.getAllTyped();

        if (points.isEmpty())
        {
            return;
        }

        stack.push();
        this.applyTransforms(stack, false, transition);

        for (CurvePoint point : points)
        {
            Vector3f pos = this.form.pointPosition(point);
            Vector3f rot = this.form.pointRotation(point);

            stack.push();
            stack.translate(pos.x, pos.y, pos.z);

            Matrix4f origin = new Matrix4f(stack.peek().getPositionMatrix());

            /* Match Transform.createRotationMatrix order (ZYX), so LOCAL gizmo axes line up with how
             * the point's rotation is actually applied. */
            stack.multiply(new Quaternionf().rotationZYX(
                (float) Math.toRadians(rot.z), (float) Math.toRadians(rot.y), (float) Math.toRadians(rot.x)));

            Matrix4f matrix = new Matrix4f(stack.peek().getPositionMatrix());

            stack.pop();

            matrices.put(StringUtils.combinePaths(prefix, point.bone.get()), matrix, origin);
        }

        stack.pop();
    }

    /**
     * The form's own transform (translate / rotate / scale, including the keyframed "Transform" track)
     * as a matrix. The renderer bakes this into the curve via {@link #applyTransforms} (see
     * {@link #collectMatrices}), but {@code getMatrixForRenderWithRotation} — used by the follow-curve
     * camera — omits it, so the camera must fold it in to sit on the transformed curve, not the rest one.
     */
    public Matrix4f formTransformMatrix(float transition)
    {
        Matrix4f matrix = new Matrix4f();

        this.applyTransforms(matrix, transition);

        return matrix;
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        CurveFormThumbnailRenderer.render(context, this.form, x1, y1, x2 - x1, y2 - y1);
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        if (BBSRendering.isIrisShadowPass() || context.stencilMap != null)
        {
            return;
        }

        List<CurveSpline.Sample> samples = this.form.sampleRendered();

        if (samples.size() < 2)
        {
            return;
        }

        Matrix4f mat = context.stack.peek().getPositionMatrix();

        boolean solid = this.form.extrudeSolid.get();
        boolean blocks = this.form.extrudeBlocks.get() && this.form.extrudeBlock.get() != null;

        /* Two independent outputs; with neither on, just draw the guide line. */
        if (!solid && !blocks)
        {
            this.renderLine(mat, samples);
        }

        if (solid)
        {
            this.renderTube(mat, samples);
        }

        if (blocks)
        {
            this.renderBlocks(context, samples);
        }
    }

    /** Sweep a block along the spline, one every {@code spacing} units of arc length. */
    private void renderBlocks(FormRenderingContext context, List<CurveSpline.Sample> path)
    {
        BlockState state = this.form.extrudeBlock.get();
        BlockForm block = new BlockForm();

        block.blockState.set(state);

        float spacing = Math.max(0.05F, this.form.extrudeSpacing.get());
        float scale = this.form.extrudeScale.get();
        boolean align = this.form.extrudeAlign.get();
        boolean connect = this.form.extrudeConnect.get() && align;

        int last = path.size() - 1;

        if (last < 1)
        {
            return;
        }

        /* Placement points at `spacing` arc intervals (from the curve start), each with its curve
         * parameter t in [0,1] for per-point rotation. */
        List<Vector3f> pts = new ArrayList<>();
        List<Float> ts = new ArrayList<>();

        pts.add(new Vector3f(path.get(0).pos));
        ts.add(0F);

        float accumulated = 0F;

        for (int i = 0; i < last; i++)
        {
            Vector3f p0 = path.get(i).pos;
            Vector3f p1 = path.get(i + 1).pos;
            float segLen = new Vector3f(p1).sub(p0).length();

            if (segLen < 1e-6F)
            {
                continue;
            }

            accumulated += segLen;

            while (accumulated >= spacing)
            {
                accumulated -= spacing;

                float localT = 1F - accumulated / segLen;
                pts.add(new Vector3f(p0).lerp(p1, localT));
                ts.add((i + localT) / last);
            }
        }

        if (connect && pts.size() >= 2)
        {
            /* Chain: one block per segment between consecutive points — oriented along the chord and
             * stretched to its length, centred on the segment. Blocks share their endpoints, so they stay
             * connected around bends (only a small outer-corner wedge remains at sharp turns; smaller
             * spacing shrinks it). */
            for (int j = 0; j < pts.size() - 1; j++)
            {
                Vector3f a = pts.get(j);
                Vector3f b = pts.get(j + 1);
                Vector3f dir = new Vector3f(b).sub(a);
                float len = dir.length();

                if (len < 1e-6F)
                {
                    continue;
                }

                Vector3f mid = new Vector3f(a).add(b).mul(0.5F);
                Vector3f rot = this.form.rotationAt((ts.get(j) + ts.get(j + 1)) * 0.5F);

                placeBlockSpan(context, block, mid, dir, rot, scale, len);
            }
        }
        else
        {
            /* Separate cubes at each point (connect off, or not aligned). */
            for (int j = 0; j < pts.size(); j++)
            {
                Vector3f dir = j < pts.size() - 1
                    ? new Vector3f(pts.get(j + 1)).sub(pts.get(j))
                    : new Vector3f(pts.get(j)).sub(pts.get(Math.max(0, j - 1)));

                placeBlock(context, block, pts.get(j), dir, this.form.rotationAt(ts.get(j)), scale, align);
            }
        }
    }

    /** A single cube at {@code pos}, aligned to {@code dir} and uniformly scaled (connect-off path). */
    private static void placeBlock(FormRenderingContext context, BlockForm block, Vector3f pos, Vector3f dir, Vector3f rotDeg, float scale, boolean align)
    {
        MatrixStack stack = context.stack;

        stack.push();
        stack.translate(pos.x, pos.y, pos.z);

        applyAlignAndRotation(stack, dir, rotDeg, align);

        if (scale != 1F)
        {
            stack.scale(scale, scale, scale);
        }

        FormUtilsClient.render(block, context);
        stack.pop();
    }

    /** A block spanning one chord: centred at {@code mid}, oriented along {@code dir}, length {@code len}. */
    private static void placeBlockSpan(FormRenderingContext context, BlockForm block, Vector3f mid, Vector3f dir, Vector3f rotDeg, float crossScale, float len)
    {
        MatrixStack stack = context.stack;

        stack.push();
        stack.translate(mid.x, mid.y, mid.z);

        applyAlignAndRotation(stack, dir, rotDeg, true);

        /* Cross-section from scale; the model is centred on X/Z, so scaling Z (the tangent) by the chord
         * length grows the block symmetrically to span from one point to the next. */
        stack.scale(crossScale, crossScale, len);

        FormUtilsClient.render(block, context);
        stack.pop();
    }

    private static void applyAlignAndRotation(MatrixStack stack, Vector3f dir, Vector3f rotDeg, boolean align)
    {
        if (align && dir.lengthSquared() > 1e-12F)
        {
            Vector3f d = new Vector3f(dir).normalize();
            float yaw = (float) Math.atan2(d.x, d.z);
            float pitch = (float) Math.asin(Math.max(-1F, Math.min(1F, -d.y)));

            stack.multiply(new Quaternionf().rotateY(yaw).rotateX(pitch));
        }

        if (rotDeg.x != 0F || rotDeg.y != 0F || rotDeg.z != 0F)
        {
            stack.multiply(new Quaternionf().rotateXYZ(
                (float) Math.toRadians(rotDeg.x),
                (float) Math.toRadians(rotDeg.y),
                (float) Math.toRadians(rotDeg.z)));
        }
    }

    private void renderLine(Matrix4f mat, List<CurveSpline.Sample> path)
    {
        Color c = this.form.color.get();

        setupState();

        BufferBuilder builder = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.POSITION_COLOR);

        for (CurveSpline.Sample s : path)
        {
            com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(mat, s.pos.x, s.pos.y, s.pos.z).color(c.r, c.g, c.b, c.a));
        }

        BufferRenderer.drawWithGlobalProgram(builder.end());

        restoreState();
    }

    private void renderTube(Matrix4f mat, List<CurveSpline.Sample> path)
    {
        int n = path.size();
        Color c = this.form.color.get();
        float r = c.r, g = c.g, b = c.b, a = c.a;
        float widthMul = Math.max(0.001F, this.form.width.get());

        /* Per-sample positions and radii (per-point width, scaled by the global multiplier). */
        Vector3f[] pos = new Vector3f[n];
        float[] rad = new float[n];

        for (int i = 0; i < n; i++)
        {
            pos[i] = path.get(i).pos;
            rad[i] = Math.max(0.0005F, path.get(i).width * widthMul * 0.5F);
        }

        /* Per-point tangents. */
        Vector3f[] tan = new Vector3f[n];

        for (int i = 0; i < n; i++)
        {
            Vector3f t;

            if (i == 0)
            {
                t = new Vector3f(pos[1]).sub(pos[0]);
            }
            else if (i == n - 1)
            {
                t = new Vector3f(pos[i]).sub(pos[i - 1]);
            }
            else
            {
                t = new Vector3f(pos[i + 1]).sub(pos[i - 1]);
            }

            if (t.lengthSquared() < 1e-12F)
            {
                t.set(0, 0, 1);
            }
            else
            {
                t.normalize();
            }

            tan[i] = t;
        }

        /* Parallel-transport a normal along the spline (no twist). */
        Vector3f[] nrm = new Vector3f[n];
        Vector3f seed = new Vector3f(tan[0]).cross(0, 1, 0);

        if (seed.lengthSquared() < 1e-6F)
        {
            seed = new Vector3f(tan[0]).cross(1, 0, 0);
        }

        nrm[0] = seed.normalize();

        for (int i = 1; i < n; i++)
        {
            Vector3f axis = new Vector3f(tan[i - 1]).cross(tan[i]);
            Vector3f next;

            if (axis.lengthSquared() < 1e-9F)
            {
                next = new Vector3f(nrm[i - 1]);
            }
            else
            {
                axis.normalize();

                float dot = Math.max(-1F, Math.min(1F, tan[i - 1].dot(tan[i])));
                float angle = (float) Math.acos(dot);

                next = new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, angle).transform(new Vector3f(nrm[i - 1]));
            }

            /* Re-orthogonalise against the tangent and renormalise. */
            next.sub(new Vector3f(tan[i]).mul(next.dot(tan[i])));

            if (next.lengthSquared() < 1e-9F)
            {
                next = new Vector3f(nrm[i - 1]);
            }

            nrm[i] = next.normalize();
        }

        setupState();

        BufferBuilder builder = com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        Vector3f[] prev = ring(pos[0], tan[0], nrm[0], rad[0]);

        for (int i = 1; i < n; i++)
        {
            Vector3f[] cur = ring(pos[i], tan[i], nrm[i], rad[i]);

            for (int s = 0; s < TUBE_SIDES; s++)
            {
                int s2 = (s + 1) % TUBE_SIDES;
                Vector3f a0 = prev[s], a1 = prev[s2], b0 = cur[s], b1 = cur[s2];

                vertex(builder, mat, a0, r, g, b, a);
                vertex(builder, mat, a1, r, g, b, a);
                vertex(builder, mat, b1, r, g, b, a);

                vertex(builder, mat, a0, r, g, b, a);
                vertex(builder, mat, b1, r, g, b, a);
                vertex(builder, mat, b0, r, g, b, a);
            }

            prev = cur;
        }

        BufferRenderer.drawWithGlobalProgram(builder.end());

        restoreState();
    }

    /** One ring of {@link #TUBE_SIDES} points around the spline point, in the (normal, binormal) plane. */
    private static Vector3f[] ring(Vector3f center, Vector3f tangent, Vector3f normal, float radius)
    {
        Vector3f binormal = new Vector3f(tangent).cross(normal).normalize();
        Vector3f[] out = new Vector3f[TUBE_SIDES];

        for (int s = 0; s < TUBE_SIDES; s++)
        {
            Vector3f off = new Vector3f(normal).mul(RING_COS[s] * radius)
                .add(new Vector3f(binormal).mul(RING_SIN[s] * radius));

            out[s] = new Vector3f(center).add(off);
        }

        return out;
    }

    private static void vertex(BufferBuilder builder, Matrix4f mat, Vector3f v, float r, float g, float b, float a)
    {
        com.bbsvfx.bbsvfx.client.BbsVfxRenderCompat.next(builder.vertex(mat, v.x, v.y, v.z).color(r, g, b, a));
    }

    private static void setupState()
    {
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();

        /* Honour the active whole-form blend (set per top-level form by FormBlendStateMixin); plain
         * alpha otherwise. */
        if (!(BbsVfxBlendState.active && BbsVfxBlendGL.apply(BbsVfxBlendState.mode, BbsVfxBlendState.factor)))
        {
            RenderSystem.defaultBlendFunc();
        }

        RenderSystem.disableCull();
    }

    private static void restoreState()
    {
        /* Reset the equation in case a Darken/Lighten MIN/MAX was set; harmless when unchanged. */
        BbsVfxBlendGL.resetEquation();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
