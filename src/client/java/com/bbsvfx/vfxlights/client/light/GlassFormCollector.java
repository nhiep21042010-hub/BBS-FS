package com.bbsvfx.vfxlights.client.light;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Glass rendered as a BBS FORM — a block form in a replay, a glass prop on a model block — collected
 * per frame so the prism pass can refract through it too.
 *
 * <p>The world raycast cannot see these: they are render-time puppets, not blocks. But a film wants
 * its prism ANIMATED — a rotating pane sweeping the fan across the set — so the same trick as the
 * lights applies: the one moment their true transform exists is when they render, and this collector
 * (fed by a HEAD hook on the block form's renderer) captures the matrix there, bone chains and all.
 * The prism pass then intersects beams with these boxes alongside the world's glass.</p>
 *
 * <p>The local box is {@code [-0.5, 0, -0.5]..[0.5, 1, 0.5]}: the renderer applies its −0.5 centring
 * AFTER the captured matrix, so the cube sits around the form's origin at floor level.</p>
 */
public final class GlassFormCollector
{
    /** One glass form's oriented box for this frame, with both directions of its transform. */
    public static final class GlassBox
    {
        public final Matrix4f matrix = new Matrix4f();
        public final Matrix4f inverse = new Matrix4f();
        public float tintR = 1F;
        public float tintG = 1F;
        public float tintB = 1F;
    }

    private static final List<GlassBox> BOXES = new ArrayList<>();
    private static final Set<Object> SEEN = new HashSet<>();

    private GlassFormCollector()
    {
    }

    /** Drop last frame's boxes. Called alongside the light registry's beginFrame. */
    public static void beginFrame()
    {
        BOXES.clear();
        SEEN.clear();
    }

    /** Every glass form seen this frame. Valid until the next {@link #beginFrame()}. */
    public static List<GlassBox> getBoxes()
    {
        return BOXES;
    }

    /** Called from the block form renderer's HEAD for every rendered block form. */
    public static void collect(BlockForm form, FormRenderingContext context)
    {
        if (form == null || context == null || !form.visible.get())
        {
            return;
        }

        BlockState state = form.blockState.get();
        float[] tint = state == null ? null : GlassDispersion.tintOf(state.getBlock());

        if (tint == null)
        {
            return;
        }

        /* The same gates as the light collector: no shadow-pass re-renders, no UI preview, no picking
         * pass, no Iris sun pass — each of those renders with matrices that are nowhere in the world. */
        if (com.bbsvfx.vfxlights.client.shadow.ShadowMapper.isActive())
        {
            return;
        }

        if (context.ui || context.type == FormRenderType.PREVIEW || context.stencilMap != null
            || context.isPicking())
        {
            return;
        }

        try
        {
            if (BBSRendering.isIrisShadowPass())
            {
                return;
            }
        }
        catch (Throwable ignored)
        {
            /* Iris absent — nothing to guard against. */
        }

        if (!SEEN.add(form))
        {
            return;
        }

        boolean modelBlock = context.type == FormRenderType.MODEL_BLOCK;
        Matrix4f matrix = (!modelBlock && context.world != null ? context.world : context.stack)
            .peek().getPositionMatrix();
        GlassBox box = new GlassBox();

        if (modelBlock)
        {
            /* Block-entity stacks are VIEW space; undo the frame's actual view matrix and re-add the
             * camera — the same recipe, and the same bobbing lesson, as the light collector. */
            if (!FormLightCollector.hasInverseView())
            {
                return;
            }

            Vec3d cameraPos = net.minecraft.client.MinecraftClient.getInstance()
                .gameRenderer.getCamera().getPos();

            box.matrix.translation((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z)
                .mul(FormLightCollector.inverseView())
                .mul(matrix);
        }
        else
        {
            box.matrix.set(matrix);
        }

        box.inverse.set(box.matrix).invert();
        box.tintR = tint[0];
        box.tintG = tint[1];
        box.tintB = tint[2];
        BOXES.add(box);
    }

    /**
     * Nearest intersection of the ray with a glass form's box, or {@code null}.
     *
     * <p>The test runs in each box's LOCAL space (so any scale or rotation is free), but the returned
     * distance is measured in WORLD space — local ray parameters are not comparable across differently
     * scaled boxes, which is exactly the mistake a slab test invites.</p>
     */
    public static FormHit intersect(Vec3d origin, Vec3d direction, double maxDistance)
    {
        FormHit best = null;

        for (GlassBox box : BOXES)
        {
            Vector4f localOrigin = box.inverse.transform(
                new Vector4f((float) origin.x, (float) origin.y, (float) origin.z, 1F));
            Vector4f localDir = box.inverse.transform(
                new Vector4f((float) direction.x, (float) direction.y, (float) direction.z, 0F));

            /* Slab test against the unit box around the form origin. */
            double tEnter = -Double.MAX_VALUE;
            double tExit = Double.MAX_VALUE;
            int enterAxis = -1;
            boolean enterPositive = false;

            for (int axis = 0; axis < 3; axis++)
            {
                double o = axis == 0 ? localOrigin.x : (axis == 1 ? localOrigin.y : localOrigin.z);
                double d = axis == 0 ? localDir.x : (axis == 1 ? localDir.y : localDir.z);
                double lo = axis == 1 ? 0D : -0.5D;
                double hi = axis == 1 ? 1D : 0.5D;

                if (Math.abs(d) < 1.0E-7D)
                {
                    if (o < lo || o > hi)
                    {
                        tEnter = Double.MAX_VALUE;
                        break;
                    }

                    continue;
                }

                double t0 = (lo - o) / d;
                double t1 = (hi - o) / d;
                boolean positive = t0 > t1;

                if (positive)
                {
                    double swap = t0;

                    t0 = t1;
                    t1 = swap;
                }

                if (t0 > tEnter)
                {
                    tEnter = t0;
                    enterAxis = axis;
                    enterPositive = positive;
                }

                tExit = Math.min(tExit, t1);
            }

            if (enterAxis < 0 || tEnter >= tExit || tEnter <= 0.001D || tEnter == Double.MAX_VALUE)
            {
                continue;
            }

            /* Entry point and face normal back into world space; distance measured there. */
            Vector4f localHit = new Vector4f(
                localOrigin.x + localDir.x * (float) tEnter,
                localOrigin.y + localDir.y * (float) tEnter,
                localOrigin.z + localDir.z * (float) tEnter, 1F);
            Vector4f worldHit = box.matrix.transform(new Vector4f(localHit));
            Vec3d hitPos = new Vec3d(worldHit.x, worldHit.y, worldHit.z);
            double distance = origin.distanceTo(hitPos);

            if (distance > maxDistance || (best != null && distance >= best.distance))
            {
                continue;
            }

            Vector4f localNormal = new Vector4f(
                enterAxis == 0 ? (enterPositive ? 1F : -1F) : 0F,
                enterAxis == 1 ? (enterPositive ? 1F : -1F) : 0F,
                enterAxis == 2 ? (enterPositive ? 1F : -1F) : 0F, 0F);
            Vector4f worldNormal = box.matrix.transform(localNormal);
            Vec3d normal = new Vec3d(worldNormal.x, worldNormal.y, worldNormal.z).normalize();

            /* The normal must face the light. */
            if (normal.dotProduct(direction) > 0D)
            {
                normal = normal.multiply(-1D);
            }

            best = new FormHit(hitPos, normal, distance, new float[] { box.tintR, box.tintG, box.tintB });
        }

        return best;
    }

    public record FormHit(Vec3d position, Vec3d normal, double distance, float[] tint)
    {
    }
}
