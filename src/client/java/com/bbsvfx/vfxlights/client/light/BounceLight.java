package com.bbsvfx.vfxlights.client.light;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import com.bbsvfx.vfxlights.light.Light;
import com.bbsvfx.vfxlights.light.LightRegistry;

import java.util.List;

/**
 * One virtual bounce per lamp: where the light's axis lands, a soft wide fill is born, coloured by
 * the surface it struck. A white spot into a red curtain glows the room red — the thing a viewer
 * reads as "global illumination" and a gaffer fakes with one extra soft source, which is exactly what
 * this is. One bounce from one point, not GI; the film trick, honestly priced.
 *
 * <p>The bounce child is an ordinary anonymous POINT light: no shadows (real bounce light is soft —
 * six more shadow maps would buy acne, not realism), no volumetrics, no dispersion, no recursion.
 * Runs at world-render LAST before the compositors, same as the prism pass; the pack consumes the
 * list next frame.</p>
 */
public final class BounceLight
{
    /** How far along the axis a surface is looked for, in blocks. */
    private static final float TRACE_RANGE = 48F;

    private BounceLight()
    {
    }

    public static void process(WorldRenderContext context)
    {
        ClientWorld world = context.world();
        MinecraftClient mc = MinecraftClient.getInstance();

        if (world == null || mc.player == null)
        {
            return;
        }

        List<Light> lights = LightRegistry.getLights();
        int count = lights.size();

        for (int i = 0; i < count; i++)
        {
            Light light = lights.get(i);
            boolean directional = light.type == Light.Type.SPOT || light.type == Light.Type.AREA;

            if (!directional || light.bounce <= 0.001F || light.intensity <= 0F)
            {
                continue;
            }

            Vec3d origin = new Vec3d(light.x, light.y, light.z);
            Vec3d direction = new Vec3d(light.dirX, light.dirY, light.dirZ).normalize();
            Vec3d start = origin.add(direction.multiply(0.05D));
            Vec3d end = origin.add(direction.multiply(Math.min(light.range, TRACE_RANGE)));
            BlockHitResult hit = null;

            /* Walk past the lamp's own housing AND any glass — glass transmits, the bounce surface is
             * the first solid the beam actually lands on. Same ray discipline as the prism pass. */
            for (int attempt = 0; attempt < 4; attempt++)
            {
                hit = world.raycast(new RaycastContext(start, end,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));

                if (hit.getType() != HitResult.Type.BLOCK)
                {
                    break;
                }

                boolean housing = origin.distanceTo(hit.getPos()) < 1.0D;
                boolean glass = GlassDispersion.tintOf(world.getBlockState(hit.getBlockPos()).getBlock()) != null;

                if (!housing && !glass)
                {
                    break;
                }

                start = origin.add(direction.multiply(
                    exitDistance(origin, direction, hit) + 0.02D));
            }

            if (hit == null || hit.getType() != HitResult.Type.BLOCK)
            {
                continue;
            }

            double distance = origin.distanceTo(hit.getPos());

            if (distance >= light.range)
            {
                continue;
            }

            /* The surface's own colour, from the map palette — coarse, but grass bounces green and
             * sand bounces warm, which is all the eye asks of a fill. */
            BlockState state = world.getBlockState(hit.getBlockPos());
            int surface = state.getMapColor(world, hit.getBlockPos()).color;
            float surfaceR = ((surface >> 16) & 0xFF) / 255F;
            float surfaceG = ((surface >> 8) & 0xFF) / 255F;
            float surfaceB = (surface & 0xFF) / 255F;

            /* How much of the lamp actually arrives at the surface — the same normalised window the
             * surface shading uses (fill²), so the bounce dims with the throw like the pool it feeds
             * on. The old quasi-inverse-square locked the bounce in a bubble around the lamp. */
            float window = 1F - (float) distance / light.range;
            float arriving = window * window;

            Light child = LightRegistry.submit(childKey("vfxbounce", light));

            if (child == null)
            {
                continue;
            }

            Vec3d normal = Vec3d.of(hit.getSide().getVector());
            Vec3d position = hit.getPos().add(normal.multiply(0.4D));

            child.type = Light.Type.POINT;
            child.x = position.x;
            child.y = position.y;
            child.z = position.z;
            child.r = light.r * surfaceR;
            child.g = light.g * surfaceG;
            child.b = light.b * surfaceB;
            child.intensity = light.intensity * arriving * light.bounce * 1.2F;
            child.range = Math.max(6F, light.range * 0.5F);
            /* A broad source radius: bounce light is the softest light there is. */
            child.sourceRadius = 1.4F;
            child.shadows = false;
            child.volumetric = 0F;
            child.affectBlocks = light.affectBlocks;
            child.affectEntities = light.affectEntities;
        }
    }

    /**
     * Registry key of the bounce child: a (role, parent) pair, the same rule the prism and water
     * children follow — the lamp's SERIALISED form id first (survives BBS recreating the parent's
     * form mid-edit), then the parent's registry key (a LampKey — value-stable), then a rounded
     * position bucket for anonymous effect lights. identityHashCode of the pooled {@link Light}
     * ghosted the old child next to the new one for the whole persist window — the drag flicker.
     */
    private static Object childKey(String role, Light light)
    {
        /* formId first (stable), then a rounded position bucket — never the parent's LampKey: its
           host half (stub/wrapper instance) is recreated per frame during drags, churning the
           child's persist entry every frame (the drag flicker). See WaterLight.childKey. */
        Object parent = light.formId != null && !light.formId.isEmpty() ? light.formId
            : Math.round(light.x * 2D) + ":" + Math.round(light.y * 2D) + ":" + Math.round(light.z * 2D);

        return java.util.Arrays.asList(role, parent);
    }

    /** Ray parameter at which the ray leaves the hit block's unit AABB — shared logic with the prism. */
    private static double exitDistance(Vec3d origin, Vec3d direction, BlockHitResult hit)
    {
        double exit = Double.MAX_VALUE;

        for (int axis = 0; axis < 3; axis++)
        {
            double d = axis == 0 ? direction.x : (axis == 1 ? direction.y : direction.z);

            if (Math.abs(d) < 1.0E-6D)
            {
                continue;
            }

            double o = axis == 0 ? origin.x : (axis == 1 ? origin.y : origin.z);
            double lo = axis == 0 ? hit.getBlockPos().getX()
                : (axis == 1 ? hit.getBlockPos().getY() : hit.getBlockPos().getZ());
            double far = Math.max((lo - o) / d, (lo + 1D - o) / d);

            exit = Math.min(exit, far);
        }

        return exit == Double.MAX_VALUE ? 1.75D : Math.max(exit, 0.05D);
    }
}
