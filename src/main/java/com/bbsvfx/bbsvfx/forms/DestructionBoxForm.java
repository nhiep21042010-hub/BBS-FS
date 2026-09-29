package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import net.minecraft.block.Blocks;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

/**
 * A captured block structure that "shatters": every block is drawn at its original position plus a
 * displacement that grows with {@link #destruction} (0 = intact, 1 = fully scattered). The scatter is
 * a blend of a global blast direction, a radial push from the structure centre, and a per-block
 * deterministic random offset — so animating {@code destruction} plays a believable collapse.
 *
 * <p>v1: position-only scatter (per-block tumble/gravity come later). Blocks are captured into
 * {@link #blocks}; for now a debug fill is available via {@link #fillTestStructure()}.</p>
 */
public class DestructionBoxForm extends Form
{
    public final DestructionBlockList blocks = new DestructionBlockList("blocks");

    /** 0 = intact, 1 = fully scattered. The animatable driver. */
    public final ValueFloat destruction = new ValueFloat("destruction", 0F, 0F, 1F);

    /** Global blast direction (degrees) and how hard it pushes. */
    public final ValueFloat dirYaw = new ValueFloat("dir_yaw", 0F);
    public final ValueFloat dirPitch = new ValueFloat("dir_pitch", 0F);
    public final ValueFloat dirStrength = new ValueFloat("dir_strength", 0F, 0F, Float.POSITIVE_INFINITY);

    /** Outward push from the structure centre. */
    public final ValueFloat radialStrength = new ValueFloat("radial_strength", 2F, 0F, Float.POSITIVE_INFINITY);

    /** Per-block random scatter amount + seed (deterministic, so blocks do not jitter per frame). */
    public final ValueFloat randomAmount = new ValueFloat("random_amount", 1F, 0F, Float.POSITIVE_INFINITY);
    public final ValueInt seed = new ValueInt("seed", 0);

    /** Per-block random tumble strength (turns at full destruction). */
    public final ValueFloat rotationAmount = new ValueFloat("rotation_amount", 1F, 0F, Float.POSITIVE_INFINITY);

    /**
     * Attractor point in the structure's local space. It does two things: gives each block a flight
     * direction (toward it, or away when {@link #pointAway}) and orders the shatter "front" so the
     * structure comes apart progressively from the point outward instead of all at once.
     */
    public final ValueFloat pointX = new ValueFloat("point_x", 0F);
    public final ValueFloat pointY = new ValueFloat("point_y", 0F);
    public final ValueFloat pointZ = new ValueFloat("point_z", 0F);

    /** Pull toward (or away from) the point. 0 = no directional pull (point still orders the wave). */
    public final ValueFloat pointStrength = new ValueFloat("point_strength", 0F, 0F, Float.POSITIVE_INFINITY);

    /** Wave spread: 0 = every block moves together, →1 = strictly sequential from the point outward. */
    public final ValueFloat stagger = new ValueFloat("stagger", 0F, 0F, 1F);

    /**
     * Destruction mode: false = normal (Direction/Radial/Random uniform blast), true = point
     * destruction (pull toward/away the point + staggered wave, plus Random). The two are independent;
     * Rotation (tumble) applies in both.
     */
    public final ValueBoolean pointMode = new ValueBoolean("point_mode", false);

    /** Flight direction relative to the point: false = toward it, true = away from it. */
    public final ValueBoolean pointAway = new ValueBoolean("point_away", false);

    /** Wave order: false = blocks nearest the point leave first, true = farthest leave first. */
    public final ValueBoolean invertOrder = new ValueBoolean("invert_order", false);

    /**
     * Physics mode (third mode, PhysX): the blocks become rigid bodies, the collapse is simulated once
     * with a fixed step and cached, and {@link #destruction} scrubs 0..1 through the baked sim — so
     * keyframing/scrubbing works exactly like the parametric modes. Direction/Radial/Random sliders act
     * as initial velocities (blocks/s), Rotation as initial tumble speed.
     */
    public final ValueBoolean physicsMode = new ValueBoolean("physics_mode", false);

    /**
     * Cap on the simulated physics time (seconds). The bake stops EARLY once all debris has settled —
     * {@code destruction} 0..1 spans that settled stretch — so this only cuts off scenes that never
     * settle (free fall into a drop with no ground).
     */
    public final ValueFloat physDuration = new ValueFloat("phys_duration", 10F, 0.5F, 60F);

    /** Gravity (blocks/s²). MC falling blocks accelerate at ~16, so that's the default. NEGATIVE = anti-gravity
     * (debris floats up); keyframing this animates gravity across the sim (handled inside one bake). */
    public final ValueFloat physGravity = new ValueFloat("phys_gravity", 16F, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);

    /** Rigid-body material: friction (0..2) and restitution/bounciness (0..1). */
    public final ValueFloat physFriction = new ValueFloat("phys_friction", 0.6F, 0F, 2F);
    public final ValueFloat physBounciness = new ValueFloat("phys_bounciness", 0.1F, 0F, 1F);

    /** Add a static ground plane at the bottom of the structure so debris piles up instead of falling forever. */
    public final ValueBoolean physGround = new ValueBoolean("phys_ground", true);

    /**
     * Collide with the real world: at bake time the solid blocks around the actor (within
     * {@link #physWorldMargin} blocks of the structure) become static colliders, so debris lands on the
     * actual terrain. Only applies when the form renders as a scene actor (the form-editor preview has
     * no world placement and falls back to the ground plane).
     */
    public final ValueBoolean physWorld = new ValueBoolean("phys_world", true);

    /** How far beyond the structure bounds the world is scanned for colliders (blocks). 0 = auto (estimated from the launch speed). */
    public final ValueFloat physWorldMargin = new ValueFloat("phys_world_margin", 0F, 0F, 64F);

    /**
     * Explosion (physics mode): an impulse AWAY from the point (the same point/gizmo as point
     * destruction = the epicenter), fading quadratically to zero at {@link #physExplosionRadius}.
     * {@link #physExplosionCone} shapes it: 0 = spherical blast, 1 = fully aimed along the Direction
     * yaw/pitch (a "shaped charge"). 0 strength = off.
     */
    public final ValueFloat physExplosionStrength = new ValueFloat("phys_explosion", 0F, 0F, Float.POSITIVE_INFINITY);
    public final ValueFloat physExplosionRadius = new ValueFloat("phys_explosion_radius", 12F, 1F, Float.POSITIVE_INFINITY);
    public final ValueFloat physExplosionCone = new ValueFloat("phys_explosion_cone", 0F, 0F, 1F);

    /**
     * Staged release (physics mode): blocks start KINEMATIC (an immovable, collidable wall) and go
     * dynamic as a wave travels outward from the point (the epicenter) — the structure comes apart
     * progressively instead of all dropping at t=0. Launch velocities apply at each block's RELEASE
     * moment. {@link #physWaveTime} = seconds for the front to reach the farthest block;
     * {@link #invertOrder} (shared with point mode) flips the order (farthest first).
     */
    public final ValueBoolean physWave = new ValueBoolean("phys_wave", false);
    public final ValueFloat physWaveTime = new ValueFloat("phys_wave_time", 1.5F, 0F, Float.POSITIVE_INFINITY);

    /**
     * Support integrity (physics mode): an intact (kinematic) block stays put only while a chain of
     * neighbours connects it to an ANCHOR (the structure's base / blocks resting on real ground).
     * Knock a hole out (explosion radius, or the wave) and everything that lost its path to the
     * ground collapses by itself — chain destruction. Without the wave, the initial damage is the
     * explosion radius; with neither explosion nor wave nothing ever starts falling.
     */
    public final ValueBoolean physSupport = new ValueBoolean("phys_support", false);

    /**
     * Fracture clusters (physics mode): blocks are glued into chunks of up to this many blocks
     * (deterministic from the seed) with breakable fixed joints — debris flies as larger fragments
     * that SPLIT on hard impacts. 1 = off (every block on its own).
     */
    public final ValueInt physClusterSize = new ValueInt("phys_cluster_size", 1, 1, 8);

    /** Joint break force: lower = fragments crumble on any touch, higher = chunks survive landings. 0 = unbreakable. */
    public final ValueFloat physClusterStrength = new ValueFloat("phys_cluster_strength", 400F, 0F, Float.POSITIVE_INFINITY);

    /**
     * Sub-block shatter (physics mode): blocks within this fraction of the explosion radius are
     * pre-split into 8 glued sectors (2×2×2) whose glue breaks on the blast/impact — the blocks
     * themselves crumble into pieces near the epicenter. 0 = off. Capped (fragments are 8 bodies each).
     */
    public final ValueFloat physShatter = new ValueFloat("phys_shatter", 0F, 0F, 1F);

    /** Sector glue break force (sectors are ~8× lighter than blocks, so this is lower than Cluster strength). */
    public final ValueFloat physShatterStrength = new ValueFloat("phys_shatter_strength", 150F, 0F, Float.POSITIVE_INFINITY);

    /**
     * LOD cap on the PhysX bodies (tier A): only the N blocks NEAREST the point (epicenter) are
     * simulated; the rest fly on cheap analytic ballistics (launch velocity + gravity, GPU-instanced,
     * landing on the structure's base plane) — that's how 50-100k-block scans stay real-time. 0 = no
     * cap, everything simulated (the classic Destruction Box behaviour).
     */
    public final ValueInt physMaxBodies = new ValueInt("phys_max_bodies", 0, 0, 200_000);

    public DestructionBoxForm()
    {
        super();

        this.add(this.blocks);
        this.add(this.destruction);
        this.add(this.dirYaw);
        this.add(this.dirPitch);
        this.add(this.dirStrength);
        this.add(this.radialStrength);
        this.add(this.randomAmount);
        this.add(this.seed);
        this.add(this.rotationAmount);
        this.add(this.pointX);
        this.add(this.pointY);
        this.add(this.pointZ);
        this.add(this.pointStrength);
        this.add(this.stagger);
        this.add(this.pointMode);
        this.add(this.pointAway);
        this.add(this.invertOrder);
        this.add(this.physicsMode);
        this.add(this.physDuration);
        this.add(this.physGravity);
        this.add(this.physFriction);
        this.add(this.physBounciness);
        this.add(this.physGround);
        this.add(this.physWorld);
        this.add(this.physWorldMargin);
        this.add(this.physExplosionStrength);
        this.add(this.physExplosionRadius);
        this.add(this.physExplosionCone);
        this.add(this.physWave);
        this.add(this.physWaveTime);
        this.add(this.physSupport);
        this.add(this.physClusterSize);
        this.add(this.physClusterStrength);
        this.add(this.physShatter);
        this.add(this.physShatterStrength);
        this.add(this.physMaxBodies);
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "Destruction box";
    }

    /** Centre of the captured blocks' bounding box (local space). */
    public Vector3f center()
    {
        List<DestructionBlock> all = this.blocks.getAllTyped();

        if (all.isEmpty())
        {
            return new Vector3f();
        }

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        for (DestructionBlock block : all)
        {
            minX = Math.min(minX, block.x.get());
            minY = Math.min(minY, block.y.get());
            minZ = Math.min(minZ, block.z.get());
            maxX = Math.max(maxX, block.x.get());
            maxY = Math.max(maxY, block.y.get());
            maxZ = Math.max(maxZ, block.z.get());
        }

        return new Vector3f((minX + maxX) * 0.5F, (minY + maxY) * 0.5F, (minZ + maxZ) * 0.5F);
    }

    /** The attractor point in local space. */
    public Vector3f point()
    {
        return new Vector3f(this.pointX.get(), this.pointY.get(), this.pointZ.get());
    }

    /** Global blast direction as a unit vector (fed to the GPU shatter shader as a uniform). */
    public Vector3f blastDir()
    {
        return dirFromYawPitch(this.dirYaw.get(), this.dirPitch.get());
    }

    /** Baked per-block random unit vector (GPU attribute) — depends on the current seed. */
    public Vector3f bakedRandomUnit(int index)
    {
        return randomUnit(this.seed.get(), index);
    }

    /** Baked per-block tumble axis (xyz) + magnitude (w) — mirrors {@link #blockSpin}; depends on the seed. */
    public Vector4f bakedSpin(int index)
    {
        int h = (this.seed.get() ^ 0x9e3779b9) * 73856093 ^ index * 83492791;
        Vector3f axis = new Vector3f(hashFloat(h, 4), hashFloat(h, 5), hashFloat(h, 6));

        if (axis.lengthSquared() < 1e-6F)
        {
            axis.set(0F, 1F, 0F);
        }
        else
        {
            axis.normalize();
        }

        return new Vector4f(axis.x, axis.y, axis.z, hashFloat(h, 7) * 0.5F + 0.5F);
    }

    /** Largest distance from any block to {@code point} — used to normalise the wave ordering. */
    public float maxDistanceToPoint(Vector3f point)
    {
        float max = 0F;

        for (DestructionBlock block : this.blocks.getAllTyped())
        {
            max = Math.max(max, block.position().distance(point));
        }

        return max;
    }

    /**
     * Per-block progress of the shatter front (0 = still intact, 1 = fully displaced). With
     * {@link #stagger} at 0 this is just the global {@link #destruction}; raising it makes blocks
     * closer to the point (or farther, when {@link #invertOrder}) start moving earlier, while every
     * block still reaches 1 by the time {@code destruction} hits 1.
     */
    public float localProgress(Vector3f origPos, Vector3f point, float maxDist)
    {
        float d = this.destruction.get();

        if (!this.pointMode.get())
        {
            return d;
        }

        float s = Math.min(Math.max(this.stagger.get(), 0F), 0.999F);

        if (s <= 0F)
        {
            return d;
        }

        float t = maxDist > 1e-6F ? origPos.distance(point) / maxDist : 0F;
        float order = this.invertOrder.get() ? 1F - t : t;
        float u = Math.min(Math.max((d - order * s) / (1F - s), 0F), 1F);

        /* Smootherstep each block's window (quintic, zero 1st & 2nd derivative at both ends): without
         * easing the clamp gives a velocity kink, so staggered blocks pop into motion and freeze
         * abruptly — looks torn. */
        return u * u * u * (u * (u * 6F - 15F) + 10F);
    }

    /**
     * Displaced render position of block {@code index}. {@code d} is the block's {@link #localProgress}
     * (so a staggered block stays put until its part of the wave arrives); {@code point} feeds the
     * directional pull.
     */
    public Vector3f displacedPosition(int index, Vector3f origPos, Vector3f center, Vector3f point, float d)
    {
        if (d <= 0F)
        {
            return new Vector3f(origPos);
        }

        Vector3f disp = new Vector3f();

        if (this.pointMode.get())
        {
            float pointS = this.pointStrength.get();

            if (pointS != 0F)
            {
                /* Smooth convergent field (point - origPos), NOT a normalised direction: adjacent
                 * blocks move coherently and there is no direction flip near the point — the
                 * normalised version diverged around the point and made the scatter look torn.
                 * pointStrength is a fraction of the way to the point (1 = lands on it). */
                Vector3f pull = new Vector3f(point).sub(origPos).mul(pointS);

                if (this.pointAway.get())
                {
                    pull.negate();
                }

                disp.add(pull);
            }
        }
        else
        {
            /* Normal destruction: global blast direction + outward radial push. */
            float dirS = this.dirStrength.get();

            if (dirS != 0F)
            {
                disp.add(dirFromYawPitch(this.dirYaw.get(), this.dirPitch.get()).mul(dirS));
            }

            float radS = this.radialStrength.get();

            if (radS != 0F)
            {
                Vector3f radial = new Vector3f(origPos).sub(center);

                if (radial.lengthSquared() > 1e-6F)
                {
                    disp.add(radial.normalize().mul(radS));
                }
            }
        }

        /* Random scatter applies in both modes. */
        float ran = this.randomAmount.get();

        if (ran != 0F)
        {
            disp.add(randomUnit(this.seed.get(), index).mul(ran));
        }

        disp.mul(d);

        return new Vector3f(origPos).add(disp);
    }

    /**
     * Initial linear velocity for physics mode (blocks/s) — the same Direction + Radial + Random recipe
     * the parametric {@link #displacedPosition} uses as displacement, reinterpreted as a launch velocity.
     */
    public Vector3f initialVelocity(int index, Vector3f origPos, Vector3f center)
    {
        Vector3f v = new Vector3f();
        float dirS = this.dirStrength.get();

        if (dirS != 0F)
        {
            v.add(dirFromYawPitch(this.dirYaw.get(), this.dirPitch.get()).mul(dirS));
        }

        float radS = this.radialStrength.get();

        if (radS != 0F)
        {
            Vector3f radial = new Vector3f(origPos).sub(center);

            if (radial.lengthSquared() > 1e-6F)
            {
                v.add(radial.normalize().mul(radS));
            }
        }

        float ran = this.randomAmount.get();

        if (ran != 0F)
        {
            v.add(randomUnit(this.seed.get(), index).mul(ran));
        }

        float explosion = this.physExplosionStrength.get();

        if (explosion != 0F)
        {
            /* Blast away from the epicenter, quadratic falloff to the radius; the cone lerps the
             * radial direction toward the Direction yaw/pitch for a shaped/directed blast. */
            Vector3f fromPoint = new Vector3f(origPos).add(0.5F, 0.5F, 0.5F).sub(this.point());
            float dist = fromPoint.length();
            float radius = Math.max(1F, this.physExplosionRadius.get());
            float falloff = Math.max(0F, 1F - dist / radius);

            if (falloff > 0F)
            {
                Vector3f dir = dist > 1e-4F ? fromPoint.div(dist) : dirFromYawPitch(this.dirYaw.get(), this.dirPitch.get());
                float cone = Math.min(Math.max(this.physExplosionCone.get(), 0F), 1F);

                if (cone > 0F)
                {
                    dir.lerp(dirFromYawPitch(this.dirYaw.get(), this.dirPitch.get()), cone);

                    if (dir.lengthSquared() < 1e-6F)
                    {
                        dir.set(dirFromYawPitch(this.dirYaw.get(), this.dirPitch.get()));
                    }
                    else
                    {
                        dir.normalize();
                    }
                }

                v.add(dir.mul(explosion * falloff * falloff));
            }
        }

        return v;
    }

    /** Initial angular velocity for physics mode (rad/s): the baked tumble axis scaled by Rotation. */
    public Vector3f initialAngularVelocity(int index)
    {
        float amount = this.rotationAmount.get();

        if (amount <= 0F)
        {
            return new Vector3f();
        }

        Vector4f spin = this.bakedSpin(index);

        return new Vector3f(spin.x, spin.y, spin.z).mul(amount * spin.w * (float) Math.PI);
    }

    /** Deterministic per-block tumble (around the block centre), scaled by {@code d} (local progress). */
    public Quaternionf blockSpin(int index, float d)
    {
        float amount = this.rotationAmount.get();

        if (amount <= 0F || d <= 0F)
        {
            return new Quaternionf();
        }

        int h = (this.seed.get() ^ 0x9e3779b9) * 73856093 ^ index * 83492791;
        Vector3f axis = new Vector3f(hashFloat(h, 4), hashFloat(h, 5), hashFloat(h, 6));

        if (axis.lengthSquared() < 1e-6F)
        {
            axis.set(0F, 1F, 0F);
        }
        else
        {
            axis.normalize();
        }

        float magnitude = hashFloat(h, 7) * 0.5F + 0.5F;
        float angle = d * amount * magnitude * (float) (Math.PI * 2D);

        return new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, angle);
    }

    /**
     * Debug: fill a solid stone cube of {@code size}³ blocks so the shatter can be tested — and stressed
     * for performance — without the wand. A solid cube (not a shell) is the honest large-structure case:
     * size 20 = 8k blocks, 30 = 27k, 40 = 64k.
     */
    public void fillTestStructure(int size)
    {
        this.blocks.clearBlocks();

        int s = Math.max(1, size);

        for (int x = 0; x < s; x++)
        {
            for (int y = 0; y < s; y++)
            {
                for (int z = 0; z < s; z++)
                {
                    this.blocks.addBlock(x, y, z, Blocks.STONE.getDefaultState());
                }
            }
        }
    }

    private static Vector3f dirFromYawPitch(float yawDeg, float pitchDeg)
    {
        float yaw = (float) Math.toRadians(yawDeg);
        float pitch = (float) Math.toRadians(pitchDeg);

        return new Vector3f(
            (float) (Math.sin(yaw) * Math.cos(pitch)),
            (float) Math.sin(pitch),
            (float) (Math.cos(yaw) * Math.cos(pitch))
        );
    }

    /** Deterministic unit vector per (seed, block index) — fixed across frames so blocks do not jitter. */
    private static Vector3f randomUnit(int seed, int index)
    {
        int h = seed * 73856093 ^ index * 19349663;
        Vector3f v = new Vector3f(hashFloat(h, 1), hashFloat(h, 2), hashFloat(h, 3));

        if (v.lengthSquared() < 1e-6F)
        {
            return new Vector3f(0F, 1F, 0F);
        }

        return v.normalize();
    }

    private static float hashFloat(int h, int salt)
    {
        int x = h * 0x27d4eb2d + salt * 0x165667b1;

        x ^= x >>> 15;
        x *= 0x85ebca6b;
        x ^= x >>> 13;

        return (x & 0xFFFFFF) / (float) 0xFFFFFF * 2F - 1F;
    }
}
