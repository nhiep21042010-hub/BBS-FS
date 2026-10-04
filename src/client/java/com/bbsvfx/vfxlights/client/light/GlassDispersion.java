package com.bbsvfx.vfxlights.client.light;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.block.StainedGlassPaneBlock;
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
 * Dispersion through MINECRAFT GLASS: a dispersive spot whose beam hits a glass block splits, behind
 * the glass, into a fan of spectral beams — Snell's law per wavelength, the prism the player builds
 * out of the blocks they already have.
 *
 * <p><b>The physics, and the licence taken with it.</b> Refraction angles are honest Snell: each
 * wavelength gets its own index (a Cauchy-like spread), so violet bends harder than red and the fan's
 * order and its growth with the angle of incidence are real — hit the glass square-on and the fan
 * closes, graze it and the fan opens. Two liberties, both deliberate: the per-wavelength index spread
 * and the fan gain are exaggerated (real crown glass disperses ~2° and would read as nothing), and the
 * beams keep their refracted directions after exit rather than bending back parallel — a glass CUBE's
 * parallel faces would cancel the fan entirely, which is physically true and cinematically useless.
 * Film first: the look of a prism, the controls of a lamp.</p>
 *
 * <p><b>Mechanics.</b> Runs at world-render LAST, before the compositors read the registry: the parent
 * beam is CLAMPED to end at the glass, and the children are submitted as ordinary anonymous spot
 * lights — surface pools, volumetric beams and the pack upload all pick them up through the pipeline
 * that already exists. Children are keyed, so a double-fired render event cannot duplicate them; they
 * carry no dispersion themselves, so a child hitting more glass does not recurse.</p>
 */
public final class GlassDispersion
{
    /** Spectral fan resolution. Six beams read as a continuous rainbow once they overlap in the air. */
    private static final int WAVES = 6;
    /** Base refractive index and its red-to-violet spread — exaggerated, see the class comment. */
    private static final float IOR_BASE = 1.45F;
    private static final float IOR_SPREAD = 0.14F;
    /** Angular exaggeration of the fan around its mean direction. */
    private static final float FAN_GAIN = 4.0F;
    /** How far a beam is traced looking for glass, in blocks. */
    private static final float TRACE_RANGE = 48F;

    private GlassDispersion()
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
        /* Snapshot the count: children are appended past it and must not be re-examined. */
        int count = lights.size();

        for (int i = 0; i < count; i++)
        {
            Light light = lights.get(i);

            if ((light.type != Light.Type.SPOT && light.type != Light.Type.AREA)
                || light.dispersion <= 0.001F || light.intensity <= 0F)
            {
                continue;
            }

            Vec3d origin = new Vec3d(light.x, light.y, light.z);
            Vec3d direction = new Vec3d(light.dirX, light.dirY, light.dirZ).normalize();
            Vec3d start = origin.add(direction.multiply(0.05D));
            Vec3d end = origin.add(direction.multiply(Math.min(light.range, TRACE_RANGE)));
            BlockHitResult hit = null;

            /* The lamp's own housing is not an obstacle: a form light sits ON a model block, and the
             * ray's first hit was that very block 0.1 blocks out (measured — the fan never fired).
             * Step past anything within the first block of the origin — past the block's WHOLE AABB:
             * stepping just past the entry face restarts the ray INSIDE the block, and vanilla raycast
             * tests the starting block too, so it re-hit the same housing until the attempts ran out
             * (also measured). */
            for (int attempt = 0; attempt < 3; attempt++)
            {
                hit = world.raycast(new RaycastContext(start, end,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));

                if (hit.getType() != HitResult.Type.BLOCK || origin.distanceTo(hit.getPos()) >= 1.0D)
                {
                    break;
                }

                start = origin.add(direction.multiply(exitDistance(origin, direction, hit) + 0.02D));
            }

            /* Two kinds of glass compete: real world blocks (found by the raycast) and glass FORMS —
             * animated panes in replays, props on model blocks — collected at render time. Nearest
             * glass wins, and an opaque world block in front of a form blocks it honestly. */
            double worldDistance = hit.getType() == HitResult.Type.BLOCK
                ? origin.distanceTo(hit.getPos()) : Double.MAX_VALUE;
            float[] worldTint = null;

            if (hit.getType() == HitResult.Type.BLOCK)
            {
                BlockState state = world.getBlockState(hit.getBlockPos());

                worldTint = tintOf(state.getBlock());
            }

            GlassFormCollector.FormHit formHit = GlassFormCollector.intersect(origin, direction,
                Math.min(Math.min(light.range, TRACE_RANGE), worldDistance));

            Vec3d hitPosition;
            Vec3d normal;
            float[] glassTint;
            double distance;

            if (formHit != null)
            {
                hitPosition = formHit.position();
                normal = formHit.normal();
                glassTint = formHit.tint();
                distance = formHit.distance();
            }
            else if (worldTint != null)
            {
                hitPosition = hit.getPos();
                normal = Vec3d.of(hit.getSide().getVector());
                glassTint = worldTint;
                distance = worldDistance;
            }
            else
            {
                continue;
            }

            double cosIncident = -direction.dotProduct(normal);

            if (cosIncident < 0.001D)
            {
                continue;
            }

            float parentRange = light.range;

            /* The white beam ends AT the prism — its light became the fan. */
            light.range = (float) Math.min(light.range, distance + 0.2D);

            Vec3d meanRefracted = refract(direction, normal, IOR_BASE + IOR_SPREAD * 0.5D, cosIncident);

            for (int w = 0; w < WAVES; w++)
            {
                float t = w / (float) (WAVES - 1);
                Vec3d refracted = refract(direction, normal, IOR_BASE + IOR_SPREAD * t, cosIncident);
                /* Exaggerate the fan around its mean — the honest 2-3 degrees would read as nothing. */
                Vec3d childDir = meanRefracted
                    .add(refracted.subtract(meanRefracted).multiply(FAN_GAIN)).normalize();

                /* Keyed per parent and wavelength: a double-fired render event dedups instead of
                 * doubling the fan. */
                Light child = LightRegistry.submit(childKey("vfxdisp:" + w, light));

                if (child == null)
                {
                    continue;
                }

                /* Born on the far side of the pane, travelling in its own spectral direction. */
                Vec3d exit = hitPosition.add(childDir.multiply(0.75D));
                float[] spectral = spectrum(t);

                child.type = Light.Type.SPOT;
                child.x = exit.x;
                child.y = exit.y;
                child.z = exit.z;
                child.dirX = (float) childDir.x;
                child.dirY = (float) childDir.y;
                child.dirZ = (float) childDir.z;

                /* Up axis: perpendicular to the beam, lying in the fan's plane — the stripe reads
                 * elongated along the fan rather than as a round dot. */
                Vec3d up = normal.subtract(childDir.multiply(normal.dotProduct(childDir)));

                if (up.lengthSquared() < 1.0E-6D)
                {
                    up = Math.abs(childDir.y) > 0.9D
                        ? new Vec3d(1D, 0D, 0D).crossProduct(childDir) : new Vec3d(0D, 1D, 0D).crossProduct(childDir);
                }

                up = up.normalize();
                child.upX = (float) up.x;
                child.upY = (float) up.y;
                child.upZ = (float) up.z;

                child.r = spectral[0] * glassTint[0];
                child.g = spectral[1] * glassTint[1];
                child.b = spectral[2] * glassTint[2];
                /* Brighter than an even split: a spectral band carries a third of white's luminance,
                 * and the fan must READ next to the parent's pool, not whisper. */
                child.intensity = light.intensity * 1.6F;
                child.range = Math.max(4F, parentRange - (float) distance);
                child.sourceRadius = 0.05F;
                child.setCone(11F, 4F);
                /* One shadowed child in the middle of the fan, so the prism itself casts a shadow. */
                child.shadows = w == WAVES / 2;
                child.volumetric = light.volumetric * 2.0F;
                child.affectBlocks = light.affectBlocks;
                child.affectEntities = light.affectEntities;
            }

            /* Bypass share: the part of the beam that passes through the prism WITHOUT dispersing.
             * On the reference shots the white beam is still visible behind the glass, not extinguished. */
            Vec3d bypassStart = hitPosition.add(direction.multiply(0.75D));
            Light bypass = LightRegistry.submit(childKey("vfxbypass", light));

            if (bypass != null)
            {
                bypass.type = Light.Type.SPOT;
                bypass.x = bypassStart.x;
                bypass.y = bypassStart.y;
                bypass.z = bypassStart.z;
                bypass.dirX = light.dirX;
                bypass.dirY = light.dirY;
                bypass.dirZ = light.dirZ;
                bypass.upX = light.upX;
                bypass.upY = light.upY;
                bypass.upZ = light.upZ;
                bypass.r = light.r;
                bypass.g = light.g;
                bypass.b = light.b;
                bypass.intensity = light.intensity * 0.2F;
                bypass.range = Math.max(4F, parentRange - (float) distance);
                bypass.sourceRadius = light.sourceRadius;
                bypass.cosOuter = light.cosOuter;
                bypass.cosInner = light.cosInner;
                bypass.shadows = false;
                bypass.volumetric = light.volumetric * 0.5F;
                bypass.affectBlocks = light.affectBlocks;
                bypass.affectEntities = light.affectEntities;
            }
        }
    }

    /**
     * Registry key of an ephemeral child: a (role, parent) pair. The parent half prefers the
     * lamp's SERIALISED form id (survives BBS recreating the parent's form mid-edit), then the
     * parent's registry key (a LampKey — value-stable), then a rounded position bucket for
     * anonymous effect lights. identityHashCode of the pooled {@link Light} was wrong here: the
     * registry pool re-issues those instances as the submit order shifts, so the key changed
     * under a standing lamp and the old child lived out its persist window right next to the
     * re-submitted one — the fan doubled. A pair, not a string: a toString-built key would
     * inherit whatever (possibly unstable) toString the parent key's class happens to have.
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

    /** Ray parameter (blocks from {@code origin}) at which the ray leaves the hit block's unit AABB. */
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

    /** Snell's law in vector form, entering the denser medium; cannot totally-internally-reflect. */
    private static Vec3d refract(Vec3d incident, Vec3d normal, double ior, double cosIncident)
    {
        double eta = 1D / ior;
        double k = 1D - eta * eta * (1D - cosIncident * cosIncident);

        return incident.multiply(eta)
            .add(normal.multiply(eta * cosIncident - Math.sqrt(Math.max(k, 0D)))).normalize();
    }

    /** The glass tint this block applies, or {@code null} if the block is not glass at all. */
    public static float[] tintOf(Block block)
    {
        if (block == Blocks.GLASS || block == Blocks.GLASS_PANE)
        {
            return new float[] { 1F, 1F, 1F };
        }

        if (block instanceof StainedGlassBlock stained)
        {
            return vivid(stained.getColor().getColorComponents());
        }

        if (block instanceof StainedGlassPaneBlock stainedPane)
        {
            return vivid(stainedPane.getColor().getColorComponents());
        }

        return null;
    }

    /**
     * The colour LIGHT takes through stained glass, not the muddy diffuse dye. A pane transmits a
     * narrow spectral band, so the light that comes through is far more saturated and brighter than
     * the block's surface colour — Minecraft's dye components are muted (green carries big red and
     * blue channels and reads grey), which made our pools pastel next to a shaderpack's vivid ones.
     * Push saturation away from luminance, then lift the dominant channel so it reads as coloured
     * LIGHT rather than a dim tint.
     */
    private static float[] vivid(float[] c)
    {
        float lum = c[0] * 0.3F + c[1] * 0.59F + c[2] * 0.11F;
        float sat = 1.8F;
        float r = lum + (c[0] - lum) * sat;
        float g = lum + (c[1] - lum) * sat;
        float b = lum + (c[2] - lum) * sat;

        r = Math.max(0F, r);
        g = Math.max(0F, g);
        b = Math.max(0F, b);

        float mx = Math.max(r, Math.max(g, b));

        if (mx > 1e-4F)
        {
            float scale = 0.88F / mx;

            r *= scale;
            g *= scale;
            b *= scale;
        }

        return new float[] { Math.min(1F, r), Math.min(1F, g), Math.min(1F, b) };
    }

    /** The same hue-wheel spectrum the caustic shaders use: 0 = red, 1 = violet, saturated. */
    private static float[] spectrum(float t)
    {
        float h = t * 0.78F;

        return new float[] { hueChannel(h), hueChannel(h + 0.6667F), hueChannel(h + 0.3333F) };
    }

    private static float hueChannel(float h)
    {
        float f = h - (float) Math.floor(h);

        return Math.max(0F, Math.min(1F, Math.abs(f * 6F - 3F) - 1F));
    }
}
