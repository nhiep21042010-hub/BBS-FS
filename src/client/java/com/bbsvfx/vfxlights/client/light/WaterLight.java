package com.bbsvfx.vfxlights.client.light;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import com.bbsvfx.vfxlights.light.Light;
import com.bbsvfx.vfxlights.light.LightRegistry;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Finds, per light per frame, where its beam ENTERS WATER — the one number the shaders need to make
 * water answer the light: everything past that distance absorbs red-first (Beer-Lambert down the
 * remaining path) and picks up the dancing caustic net. No parameter, no switch: aim a lamp into a
 * pool and the pool behaves.
 *
 * <p>Runs at world-render LAST, AFTER the prism and bounce passes on purpose — the list then already
 * contains their children, so a spectral fan diving into water gets the water treatment too.</p>
 */
public final class WaterLight
{
    private static final float TRACE_RANGE = 48F;

    /** Frames a water-entry raycast stays valid when the light has not moved. */
    private static final int CACHE_TTL = 4;

    private static int frame;

    private static final Map<Object, CacheEntry> CACHE = new HashMap<>();

    private static final class CacheEntry
    {
        double x, y, z, dirX, dirY, dirZ, range;
        float waterDist = -1F;
        float waterY;
        /** Smoothed surface estimate for the SUBMERGED branch (NaN = unset, snaps on first sight). */
        float smoothY = Float.NaN;
        int frame;
    }

    private WaterLight()
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

        frame++;

        List<Light> lights = LightRegistry.getLights();
        /* Snapshot: the reflected children appended below must not be re-processed. */
        int count = lights.size();

        for (int i = 0; i < count; i++)
        {
            Light light = lights.get(i);

            if (light.type == Light.Type.AMBIENT || light.intensity <= 0F)
            {
                continue;
            }

            /* The reflected child (waterDist -2) must pass through UNTOUCHED: it IS underwater by
               position, so the submerged branch below rewrote its waterDist to 0, and which branch
               won the frame depended on the list order — the shading model flipped 0 ↔ -2 and the
               child's tight pool on the pool floor flickered on every drag (the "ровный круг под
               источником" report). */
            if (light.reflected)
            {
                continue;
            }

            /* A lamp that is itself underwater: the whole throw is submerged. Works for every type,
             * bounce children included. */
            BlockPos lampPos = BlockPos.ofFloored(light.x, light.y, light.z);

            if (!world.getFluidState(lampPos).isEmpty())
            {
                light.waterDist = 0F;
                /* findWaterSurface is a BLOCK scan: mid-drag it jumps whole blocks (overhang
                 * edges, the 32-block fallback), and absorb = exp(-(waterY − y)·k) turns each
                 * jump into a full-field brightness pop — the underwater drag flicker. Smooth the
                 * value per lamp instead: the pop becomes a quick slide. */
                light.waterY = smoothWaterY(light, findWaterSurface(world, lampPos));

                continue;
            }

            /* Point light has no meaningful single direction — trace its downward throw instead. */
            Vec3d direction = light.type == Light.Type.POINT
                ? new Vec3d(0D, -1D, 0D)
                : new Vec3d(light.dirX, light.dirY, light.dirZ).normalize();

            /* A directional lamp that does not point into water at all can skip the raycast. */
            if (light.type != Light.Type.POINT && direction.y >= 0D)
            {
                continue;
            }

            CacheEntry cached = cacheFor(light);

            if (cached != null && frame - cached.frame < CACHE_TTL)
            {
                light.waterDist = cached.waterDist;
                light.waterY = cached.waterY;

                /* The reflection rides the LIVE pose every frame: submitted only on raycast
                 * frames, the persisted child held the TTL-old mirrored position while the
                 * parent drifted (idle sway of an actor lamp), then snapped back on the
                 * refresh frame — the ~15 Hz boil of the reflected net with a static camera. */
                submitReflected(light);

                continue;
            }

            Vec3d origin = new Vec3d(light.x, light.y, light.z);
            Vec3d start = origin.add(direction.multiply(0.05D));
            Vec3d end = origin.add(direction.multiply(Math.min(light.range, TRACE_RANGE)));
            BlockHitResult hit = null;

            /* Same ray discipline as the prism and the bounce: past the housing, through glass. */
            for (int attempt = 0; attempt < 4; attempt++)
            {
                hit = world.raycast(new RaycastContext(start, end,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, mc.player));

                if (hit.getType() != HitResult.Type.BLOCK)
                {
                    break;
                }

                boolean housing = origin.distanceTo(hit.getPos()) < 1.0D
                    && world.getFluidState(hit.getBlockPos()).isEmpty();
                boolean glass = GlassDispersion.tintOf(
                    world.getBlockState(hit.getBlockPos()).getBlock()) != null;

                if (!housing && !glass)
                {
                    break;
                }

                start = origin.add(direction.multiply(
                    exitDistance(origin, direction, hit) + 0.02D));
            }

            /* No water entry this frame (a miss, or a solid first). HOLD the last good entry for
               a while instead of dropping the water treatment for the frame: during a drag the
               identity cache misses every frame and the fresh raycast can clip a solid edge one
               frame and find water the next — the effect toggled per frame = the drag flicker
               (and the reflected child spawned/died with it). */
            if (hit == null || hit.getType() != HitResult.Type.BLOCK
                || world.getFluidState(hit.getBlockPos()).isEmpty())
            {
                if (cached != null && cached.waterDist >= 0F
                    && frame - cached.frame < CACHE_TTL * 4)
                {
                    light.waterDist = cached.waterDist;
                    light.waterY = cached.waterY;
                    submitReflected(light);
                }

                continue;
            }

            light.waterDist = (float) origin.distanceTo(hit.getPos());
            light.waterY = (float) hit.getPos().y;

            cache(light, light.waterDist, light.waterY);

            submitReflected(light);
        }

        pruneCache();
    }

    /**
     * The REFLECTED share: water is a mirror as well as a window. A virtual light mirrored about
     * the surface throws the dancing net UP — the pool-ceiling look. Spawned EVERY frame the
     * parent has a water entry: the raycast stays TTL-cached (the expensive part, and the mirror
     * plane does not move), but the child's pose mirrors the parent's CURRENT one — see the
     * cache-hit branch above for the snap this avoids.
     */
    private static void submitReflected(Light light)
    {
        /* ★TEMP bisect flag (-Dvfxlights.water.noreflect): no reflected child at all. */
        if (System.getProperty("vfxlights.water.noreflect") != null)
        {
            return;
        }

        /* Gate the mirror on a REAL air gap: a lamp bobbing within a quarter block of the surface
           flaps between the submerged branch (waterDist 0) and the raycast branch every frame —
           each flap spawns/kills this child, and its caustic net reads as a flickering circle
           around the source (the drag-in-water report). Below the gate the mirror contributes
           nothing measurable anyway. */
        if (light.waterDist < 0.25F)
        {
            return;
        }

        Light reflected = LightRegistry.submit(childKey("vfxwref", light));

        if (reflected != null)
        {
            reflected.type = light.type;
            reflected.x = light.x;
            reflected.y = 2D * light.waterY - light.y;
            reflected.z = light.z;
            reflected.dirX = light.dirX;
            reflected.dirY = -light.dirY;
            reflected.dirZ = light.dirZ;
            reflected.upX = light.upX;
            reflected.upY = -light.upY;
            reflected.upZ = light.upZ;
            reflected.r = light.r;
            reflected.g = light.g;
            reflected.b = light.b;
            /* A water surface reflects a modest share; the rest refracts down. */
            reflected.intensity = light.intensity * 0.3F;
            reflected.range = light.range;
            reflected.sourceRadius = Math.max(light.sourceRadius, 0.3F);
            reflected.cosOuter = light.cosOuter;
            reflected.cosInner = light.cosInner;
            reflected.areaShape = light.areaShape;
            reflected.width = light.width;
            reflected.height = light.height;
            reflected.thickness = light.thickness;
            reflected.twoSided = light.twoSided;
            reflected.spread = light.spread;
            reflected.barnTop = light.barnTop;
            reflected.barnBottom = light.barnBottom;
            reflected.barnLeft = light.barnLeft;
            reflected.barnRight = light.barnRight;
            reflected.barnSoftness = light.barnSoftness;
            reflected.shadows = false;
            reflected.volumetric = light.volumetric * 0.35F;
            reflected.affectBlocks = light.affectBlocks;
            reflected.affectEntities = light.affectEntities;
            reflected.waterDist = -2F;
            reflected.reflected = true;
        }
    }

    /**
     * Registry key of the ephemeral child: a (role, parent) pair. The parent half prefers the
     * lamp's SERIALISED form id (survives BBS recreating the parent's form mid-edit), then the
     * parent's registry key (a LampKey — value-stable), then a rounded position bucket for
     * anonymous effect lights. identityHashCode of the pooled {@link Light} was wrong here: the
     * registry pool re-issues those instances as the submit order shifts, so the key changed
     * under a standing lamp and the old child lived out its persist window right next to the
     * re-submitted one — the caustic pattern doubled. A pair, not a string: a toString-built key
     * would inherit whatever (possibly unstable) toString the parent key's class happens to have.
     */
    private static Object childKey(String role, Light light)
    {
        /* formId first (stable), then a rounded position bucket. The middle option used to be the
           parent's LampKey — but its host half (a stub/wrapper instance) is recreated per frame
           while dragging, so the child's key CONTENT changed every frame and the persist entry
           churned create→adopt→recreate — the drag flicker. The position bucket is stable while
           static and tracked by the position adoption while moving. */
        Object parent = light.formId != null && !light.formId.isEmpty() ? light.formId
            : Math.round(light.x * 2D) + ":" + Math.round(light.y * 2D) + ":" + Math.round(light.z * 2D);

        return java.util.Arrays.asList(role, parent);
    }

    /** Exact surface Y for a submerged lamp: the first air block above it. */
    private static float findWaterSurface(ClientWorld world, BlockPos lampPos)
    {
        for (int up = 1; up <= 32; up++)
        {
            if (world.getFluidState(lampPos.up(up)).isEmpty())
            {
                return lampPos.getY() + up;
            }
        }

        return lampPos.getY() + 32F;
    }

    /** Per-lamp low-pass on the block-scanned surface Y: a retrace is precise but discrete, and an
     *  integer jump pumps the whole underwater field. NaN (first sight) snaps. */
    private static float smoothWaterY(Light light, float target)
    {
        CacheEntry entry = CACHE.computeIfAbsent(keyOf(light), k -> new CacheEntry());

        if (Float.isNaN(entry.smoothY))
        {
            entry.smoothY = target;
        }
        else
        {
            entry.smoothY += (target - entry.smoothY) * 0.3F;
        }

        /* Keep the entry alive while the lamp stays submerged (pruneCache would drop it otherwise). */
        entry.frame = frame;

        return entry.smoothY;
    }

    /** Cache key: the lamp's IDENTITY (serialised form id, else its registry key), not the pose —
       a pose-keyed entry misses every frame of a drag and the fresh raycast can clip a solid edge
       one frame and find water the next (the drag flicker). Identity-keyed, the entry survives
       the drag; the pose check inside decides when a retrace is due. */
    private static Object keyOf(Light light)
    {
        if (light.formId != null && !light.formId.isEmpty())
        {
            return light.formId;
        }

        return light.key != null ? light.key
            : Math.round(light.x * 2D) + ":" + Math.round(light.y * 2D) + ":" + Math.round(light.z * 2D);
    }

    private static CacheEntry cacheFor(Light light)
    {
        CacheEntry entry = CACHE.get(keyOf(light));

        if (entry == null)
        {
            return null;
        }

        /* A moved/turned lamp needs a fresh ray: report the miss WITHOUT dropping the entry — the
           caller's no-water fallback still rides it for a few frames. Likewise an entry that only
           ever served the submerged branch (waterDist unset). */
        if (entry.waterDist < -0.5F
            || Math.abs(entry.x - light.x) > 0.05D
            || Math.abs(entry.y - light.y) > 0.05D
            || Math.abs(entry.z - light.z) > 0.05D
            || Math.abs(entry.dirX - light.dirX) > 0.01D
            || Math.abs(entry.dirY - light.dirY) > 0.01D
            || Math.abs(entry.dirZ - light.dirZ) > 0.01D
            || Math.abs(entry.range - light.range) > 0.05D)
        {
            return null;
        }

        return entry;
    }

    private static void cache(Light light, float waterDist, float waterY)
    {
        CacheEntry entry = CACHE.computeIfAbsent(keyOf(light), k -> new CacheEntry());

        entry.x = light.x;
        entry.y = light.y;
        entry.z = light.z;
        entry.dirX = light.dirX;
        entry.dirY = light.dirY;
        entry.dirZ = light.dirZ;
        entry.range = light.range;
        entry.waterDist = waterDist;
        entry.waterY = waterY;
        entry.frame = frame;
    }

    private static void pruneCache()
    {
        Iterator<Map.Entry<Object, CacheEntry>> it = CACHE.entrySet().iterator();

        while (it.hasNext())
        {
            Map.Entry<Object, CacheEntry> entry = it.next();

            /* Generous horizon: stale entries still serve the no-water fallback for a few frames. */
            if (frame - entry.getValue().frame > CACHE_TTL * 8)
            {
                it.remove();
            }
        }
    }

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
