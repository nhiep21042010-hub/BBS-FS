package com.bbsvfx.bbsvfx.client;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import physx.PxTopLevelFunctions;
import physx.common.PxDefaultAllocator;
import physx.common.PxDefaultCpuDispatcher;
import physx.common.PxDefaultErrorCallback;
import physx.common.PxFoundation;
import physx.common.PxIDENTITYEnum;
import physx.common.PxQuat;
import physx.common.PxTolerancesScale;
import physx.common.PxTransform;
import physx.common.PxVec3;
import physx.extensions.PxRigidBodyExt;
import physx.geometry.PxBoxGeometry;
import physx.physics.PxFilterData;
import physx.physics.PxMaterial;
import physx.physics.PxPhysics;
import physx.physics.PxRigidBodyFlagEnum;
import physx.physics.PxRigidDynamic;
import physx.physics.PxRigidStatic;
import physx.physics.PxScene;
import physx.physics.PxSceneDesc;
import physx.physics.PxSceneFlagEnum;
import physx.physics.PxSceneFlags;
import physx.physics.PxShape;
import physx.physics.PxShapeFlagEnum;
import physx.physics.PxShapeFlags;
import physx.physics.PxSimulationFilterShader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PhysX bake for the Destruction Box physics mode: every captured block becomes a 1×1×1 rigid body,
 * the collapse is simulated ONCE with a fixed step, and every step's positions+rotations are cached.
 * The renderer then scrubs the cache with {@code destruction} (0..1 → sim time), which keeps the film
 * scrub-safe (any tick, both directions, identical frames) — physics itself only ever runs forward.
 *
 * <p><b>Global content-keyed cache + async worker.</b> BBS re-copies a form to its entity on edits, so
 * renderer instances (and any per-renderer cache) die constantly — bakes are therefore cached STATICALLY
 * by a content key (blocks + params, see the renderer's key builder) and survive form copies. Simulation
 * runs on a single worker thread so the render thread never stalls; while a bake is pending the renderer
 * draws the structure at rest (or its previous bake). The bake itself touches NO Minecraft state — the
 * renderer snapshots everything (velocities, world collider boxes) into a {@link BakeInput} on the render
 * thread, because world access is not thread-safe.</p>
 *
 * <p>GOTCHA (cost a debug round in the smoke test): shapes MUST get
 * {@code PxShapeFlags(eSIMULATION_SHAPE)} and {@code setSimulationFilterData(new PxFilterData(1,1,0,0))}
 * — with the default (zeroed) filter data bodies fall straight through each other.</p>
 */
public class DestructionPhysics
{
    /** Bake sample rate. The renderer lerps between steps, so 30 Hz reads smooth at any playback speed. */
    private static final float SAMPLE_HZ = 30F;

    /** Memory guard: cap a single bake's float count (~320 MB); the rate drops for huge structure×duration. */
    private static final long MAX_FLOATS = 80_000_000L;

    /** Memory guard for the whole cache: evict old bakes past this total (~240 MB). */
    private static final long MAX_CACHE_FLOATS = 60_000_000L;

    private static final int FLOATS_PER_BLOCK = 7; // pos xyz + quat xyzw

    /** Bumped by the UI "Rebake" button — part of the bake key, forces a re-simulation (e.g. after world edits). */
    public static int rebakeNonce;

    private static PxFoundation foundation;
    private static PxPhysics physics;
    private static PxDefaultCpuDispatcher dispatcher;
    private static PxSimulationFilterShader filterShader;
    private static boolean initTried;
    private static boolean initOk;

    /** Finished bakes by content key, LRU. Guarded by {@code CACHE} itself. */
    private static final LinkedHashMap<Long, Bake> CACHE = new LinkedHashMap<>(16, 0.75F, true);

    /** Last render-thread access per key — entries still being DRAWN must never be evicted, even over
     * budget, or hot keys ping-pong into an infinite rebake loop (bake → evict → miss → bake ...). */
    private static final Map<Long, Long> LAST_USED = new ConcurrentHashMap<>();

    /** Keys currently being simulated on the worker. */
    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor((r) ->
    {
        Thread thread = new Thread(r, "bbsvfx-destruction-physics");

        thread.setDaemon(true);

        return thread;
    });

    /** Human-readable reason of the last init/bake failure, shown by the physics UI; null = healthy. */
    public static volatile String lastError;

    /** True when PhysX init was attempted and FAILED (missing natives / VC++ runtime and the like). */
    public static boolean failed()
    {
        return initTried && !initOk;
    }

    /** Whether the native PhysX library is present and initialised (a failure logs once and is final). */
    public static boolean available()
    {
        if (!initTried)
        {
            initTried = true;

            try
            {
                int version = PxTopLevelFunctions.getPHYSICS_VERSION();

                /* PhysX allows ONE PxFoundation per process. When Physics Mod is installed it creates
                 * its foundation+physics first (client mod init, long before our lazy first use) and our
                 * CreateFoundation would return null — so ADOPT its public static instances instead;
                 * multiple scenes per PxPhysics are a supported PhysX pattern and each mod keeps its own. */
                if (!adoptPhysicsModInstance())
                {
                    foundation = PxTopLevelFunctions.CreateFoundation(version, new PxDefaultAllocator(), new PxDefaultErrorCallback());
                    physics = PxTopLevelFunctions.CreatePhysics(version, foundation, new PxTolerancesScale());
                }

                dispatcher = PxTopLevelFunctions.DefaultCpuDispatcherCreate(Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
                filterShader = PxTopLevelFunctions.DefaultFilterShader();

                /* Joints (fracture clusters) silently misbehave without the extensions library. On the
                 * shared instance a second init may throw/return false — ignore, extensions are loaded. */
                try
                {
                    PxTopLevelFunctions.InitExtensions(physics);
                }
                catch (Throwable ignored)
                {
                }

                initOk = true;

                System.out.println("[bbsvfx] PhysX " + (version >> 24) + "." + ((version >> 16) & 0xff) + "." + ((version >> 8) & 0xff) + " initialised");
            }
            catch (Throwable e)
            {
                lastError = String.valueOf(e);
                System.err.println("[bbsvfx] PhysX unavailable, destruction physics mode disabled: " + e);
            }
        }

        return initOk;
    }

    /**
     * Shares Physics Mod's PhysX foundation + physics (its public statics on
     * {@code net.diebuddies.physics.StarterClient}) when that mod is present and initialised. Their
     * public physx API is signature-compatible with ours (verified vs physics-mod 3.0.18), so the shared
     * objects work whichever mod's physx classes win the classpath. Reflection — no compile dependency.
     */
    private static boolean adoptPhysicsModInstance()
    {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("physicsmod"))
        {
            return false;
        }

        try
        {
            Class<?> starter = Class.forName("net.diebuddies.physics.StarterClient");
            Object theirFoundation = starter.getField("foundation").get(null);
            Object theirPhysics = starter.getField("physics").get(null);

            if (theirFoundation == null || theirPhysics == null)
            {
                return false;
            }

            foundation = (physx.common.PxFoundation) theirFoundation;
            physics = (physx.physics.PxPhysics) theirPhysics;

            System.out.println("[bbsvfx] Sharing Physics Mod's PhysX foundation/physics instance");

            return true;
        }
        catch (Throwable e)
        {
            System.err.println("[bbsvfx] Could not adopt Physics Mod's PhysX instance, creating our own: " + e);

            return false;
        }
    }

    /**
     * A no-physics bake: one whole-block unit per block (no shatter), no step data. Used by the beam
     * devour, which reuses {@link DestructionPhysVAO} for the block-cube mesh but supplies its own poses
     * (a scripted suction) to {@link DestructionPhysVAO#render}. Cache the returned instance per block
     * list — the VAO cache is keyed by the bake identity.
     */
    public static Bake trivialUnitBake(int count)
    {
        int[] unitBlock = new int[count];
        byte[] unitOctant = new byte[count];

        for (int i = 0; i < count; i++)
        {
            unitBlock[i] = i;
            unitOctant[i] = -1;
        }

        return new Bake(count, count, unitBlock, unitOctant, 0, 0F, new float[0]);
    }

    /**
     * A no-physics bake with a caller-supplied unit layout ({@code unitBlock[u]} = source block index,
     * {@code unitOctant[u]} = -1 whole block / 0..7 sub-cube octant). The beam devour uses this to
     * SHATTER each full block into 8 octant units so they break apart instead of vanishing whole. No
     * step data — poses come from the caller.
     */
    public static Bake customUnitBake(int count, int[] unitBlock, byte[] unitOctant)
    {
        return new Bake(count, unitBlock.length, unitBlock, unitOctant, 0, 0F, new float[0]);
    }

    /** The finished bake for {@code key}, or null (not baked / still pending). */
    public static Bake cached(long key)
    {
        LAST_USED.put(key, System.currentTimeMillis());

        synchronized (CACHE)
        {
            return CACHE.get(key);
        }
    }

    public static boolean isPending(long key)
    {
        return PENDING.contains(key);
    }

    /** Queue an async bake for {@code key}; no-op if that key is already baked or in flight. */
    public static void request(long key, BakeInput input)
    {
        if (!available() || input.count == 0 || cached(key) != null || !PENDING.add(key))
        {
            return;
        }

        WORKER.submit(() ->
        {
            try
            {
                /* Persistent bakes: a matching file from a previous session (the key is built from
                 * stable hashes) means frame-exact playback without re-simulating. */
                Bake bake = loadBake(key);

                if (bake == null)
                {
                    bake = simulate(input);

                    if (bake != null)
                    {
                        saveBake(key, bake);
                    }
                }

                if (bake != null)
                {
                    lastError = null;

                    synchronized (CACHE)
                    {
                        CACHE.put(key, bake);

                        long total = 0;

                        for (Bake cachedBake : CACHE.values())
                        {
                            total += cachedBake.floats();
                        }

                        long now = System.currentTimeMillis();
                        var it = CACHE.entrySet().iterator();

                        while (total > MAX_CACHE_FLOATS && CACHE.size() > 1 && it.hasNext())
                        {
                            var entry = it.next();

                            /* Never evict what the renderer read recently — a hot key that gets evicted
                             * immediately misses again and re-bakes forever. Overshooting the budget on
                             * hot entries is the lesser evil. */
                            Long used = LAST_USED.get(entry.getKey());

                            if (entry.getValue() == bake || (used != null && now - used < 10_000L))
                            {
                                continue;
                            }

                            total -= entry.getValue().floats();
                            it.remove();
                            LAST_USED.remove(entry.getKey());
                        }
                    }
                }
            }
            catch (Throwable e)
            {
                lastError = String.valueOf(e);
                System.err.println("[bbsvfx] Destruction physics bake failed: " + e);
            }
            finally
            {
                PENDING.remove(key);
            }
        });
    }

    /**
     * Everything the simulation needs, snapshotted on the render thread (the worker must not touch
     * the form or the world). Block rest positions are the block coords; velocities are per block.
     */
    public static class BakeInput
    {
        public int count;
        public int[] rest;        // count*3, block coords (cube spans [x, x+1])
        public float[] linVel;    // count*3, blocks/s
        public float[] angVel;    // count*3, rad/s
        public float groundY = Float.NaN;  // top of the static ground slab; NaN = no ground plane
        public float groundCx, groundCz;   // slab centre
        public float[] worldBoxes = new float[0]; // n*6: centre xyz + half extents xyz, structure-local
        public float gravity;
        public float friction;
        public float restitution;
        public float duration;    // cap, seconds; the sim stops early once everything sleeps

        /**
         * Optional ANIMATED gravity, one value per sim second-fraction (sampled by the renderer from
         * the replay's keyframed phys_gravity channel, mapped over the destruction 0→1 ramp). When set,
         * the scene gravity is updated per step INSIDE the one bake — a keyframed gravity flip plays
         * smoothly instead of re-simulating (which visibly jerked the structure at the key).
         */
        public float[] gravityCurve;

        /**
         * Optional staged release: per-block sim time (seconds) at which the block turns from KINEMATIC
         * (immovable wall) into a dynamic body and receives its launch velocity. Null = everything
         * dynamic from t=0 (instant collapse). {@code Float.POSITIVE_INFINITY} = never released by time
         * (only support loss can release it).
         */
        public float[] releaseTimes;

        /**
         * Optional support integrity: per-block anchor flags (base blocks / blocks on real ground).
         * When set, every release triggers a connectivity check — still-kinematic blocks with no
         * neighbour chain to an anchor are released with NO launch velocity (they just fall).
         */
        public boolean[] anchors;

        /**
         * Optional fracture clusters: per-block cluster id; adjacent blocks sharing an id get a
         * breakable fixed joint (break force = {@link #clusterBreak}, 0 = unbreakable), so debris
         * flies as chunks that split on hard impacts. Null = no clustering.
         */
        public int[] clusterIds;
        public float clusterBreak;

        /**
         * Optional sub-block shatter: per-block flag; a shattered block is simulated as 8 glued
         * half-size sectors (2×2×2) whose glue (break force {@link #shatterBreak}) snaps on the blast
         * or a hard impact — the block itself crumbles. Null = no shattering.
         */
        public boolean[] shattered;
        public float shatterBreak;

        /**
         * Optional global wind (the explosion form): an expanding FRONT from the epicenter — when it
         * passes a body (rest distance / {@link #windFrontSpeed}) the body gets a radial acceleration
         * of {@link #windStrength} decaying over {@link #windDecay} seconds — plus a constant ambient
         * acceleration ({@link #windAmbX}, {@link #windAmbZ}). 0 strength + 0 ambient = off.
         */
        public float windStrength;
        public float windFrontSpeed = 25F;
        public float windDecay = 0.8F;
        public float windAmbX, windAmbZ;
        public float epicX, epicY, epicZ;
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Persistent bakes: <gameDir>/config/bbs/destruction_bakes/<key>.dbake — a simulation baked
     * once is byte-identical forever, so final renders match the preview frame-exact across game
     * restarts. Loaded/saved on the worker thread only.                                          */
    /* ---------------------------------------------------------------------------------------- */

    private static final int FILE_MAGIC = 0x58444231; // "XDB1"

    /** Cap on the bakes folder (bytes); oldest files beyond it are deleted after each save. */
    private static final long MAX_DISK_BYTES = 2_000_000_000L;

    private static java.io.File bakesDir()
    {
        java.io.File dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
            .resolve("config").resolve("bbs").resolve("destruction_bakes").toFile();

        dir.mkdirs();

        return dir;
    }

    private static java.io.File bakeFile(long key)
    {
        return new java.io.File(bakesDir(), Long.toHexString(key) + ".dbake");
    }

    /** Wipe all persisted bakes (the UI Rebake button — e.g. after editing the world). */
    public static void clearPersisted()
    {
        java.io.File[] files = bakesDir().listFiles();

        if (files != null)
        {
            for (java.io.File file : files)
            {
                file.delete();
            }
        }
    }

    private static Bake loadBake(long key)
    {
        java.io.File file = bakeFile(key);

        if (!file.isFile())
        {
            return null;
        }

        long start = System.nanoTime();

        try (java.io.DataInputStream input = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(file), 1 << 16)))
        {
            if (input.readInt() != FILE_MAGIC)
            {
                throw new java.io.IOException("bad magic");
            }

            int count = input.readInt();
            int units = input.readInt();
            int steps = input.readInt();
            float duration = input.readFloat();
            int[] unitBlock = new int[units];
            byte[] unitOctant = new byte[units];

            for (int u = 0; u < units; u++)
            {
                unitBlock[u] = input.readInt();
            }

            input.readFully(unitOctant);

            int floats = input.readInt();
            byte[] raw = new byte[floats * 4];

            input.readFully(raw);

            float[] data = new float[floats];

            java.nio.ByteBuffer.wrap(raw).asFloatBuffer().get(data);

            System.out.println("[bbsvfx] Destruction physics bake loaded from disk: " + count + " blocks (" + units + " units), "
                + steps + " steps, " + ((System.nanoTime() - start) / 1_000_000) + " ms");

            return new Bake(count, units, unitBlock, unitOctant, steps, duration, data);
        }
        catch (Exception e)
        {
            System.err.println("[bbsvfx] Destruction physics: unreadable bake file " + file.getName() + " (" + e + "), re-simulating");
            file.delete();

            return null;
        }
    }

    private static void saveBake(long key, Bake bake)
    {
        java.io.File file = bakeFile(key);

        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(file), 1 << 16)))
        {
            out.writeInt(FILE_MAGIC);
            out.writeInt(bake.count);
            out.writeInt(bake.units);
            out.writeInt(bake.steps);
            out.writeFloat(bake.duration);

            for (int u = 0; u < bake.units; u++)
            {
                out.writeInt(bake.unitBlock[u]);
            }

            out.write(bake.unitOctant);

            float[] data = bake.rawData();
            byte[] raw = new byte[data.length * 4];

            java.nio.ByteBuffer.wrap(raw).asFloatBuffer().put(data);
            out.writeInt(data.length);
            out.write(raw);
        }
        catch (Exception e)
        {
            System.err.println("[bbsvfx] Destruction physics: failed to persist bake (" + e + ")");
            file.delete();

            return;
        }

        /* Folder budget: drop the oldest bakes past the cap (never the one just written). */
        java.io.File[] files = bakesDir().listFiles((d, name) -> name.endsWith(".dbake"));

        if (files == null)
        {
            return;
        }

        long total = 0;

        for (java.io.File f : files)
        {
            total += f.length();
        }

        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(java.io.File::lastModified));

        for (java.io.File f : files)
        {
            if (total <= MAX_DISK_BYTES)
            {
                break;
            }

            if (f.equals(file))
            {
                continue;
            }

            total -= f.length();
            f.delete();
        }
    }

    /** Blocking simulation, worker thread only. */
    private static Bake simulate(BakeInput in)
    {
        long start = System.nanoTime();

        int count = in.count;
        float duration = Math.max(0.5F, in.duration);

        /* Unit mapping: a normal block = 1 body; a shattered block = 8 sector bodies. All the per-body
         * arrays (bodies/released/bake data) are per UNIT; block-level logic (wave, support, clusters)
         * maps through firstUnit/unitBlock. */
        int[] firstUnit = new int[count];
        int units = 0;

        for (int i = 0; i < count; i++)
        {
            firstUnit[i] = units;
            units += in.shattered != null && in.shattered[i] ? 8 : 1;
        }

        int[] unitBlock = new int[units];
        byte[] unitOctant = new byte[units];

        for (int i = 0, u = 0; i < count; i++)
        {
            if (in.shattered != null && in.shattered[i])
            {
                for (byte o = 0; o < 8; o++)
                {
                    unitBlock[u] = i;
                    unitOctant[u++] = o;
                }
            }
            else
            {
                unitBlock[u] = i;
                unitOctant[u++] = -1;
            }
        }

        /* Adaptive rate so structure size × duration can't blow the memory guard. */
        float hz = SAMPLE_HZ;
        long floatsAt30 = (long) (duration * SAMPLE_HZ + 2) * units * FLOATS_PER_BLOCK;

        if (floatsAt30 > MAX_FLOATS)
        {
            hz = MAX_FLOATS / ((float) units * FLOATS_PER_BLOCK * duration);
            hz = Math.max(5F, hz);
        }

        int steps = Math.max(2, (int) (duration * hz) + 1);
        float dt = duration / (steps - 1);

        PxScene scene = null;
        PxMaterial material = null;
        PxShape blockShape = null;
        PxShape sectorShape = null;
        PxRigidDynamic[] bodies = new PxRigidDynamic[units];
        java.util.List<PxRigidStatic> statics = new java.util.ArrayList<>();
        java.util.List<physx.extensions.PxFixedJoint> joints = new java.util.ArrayList<>();

        PxVec3 tmpVec = new PxVec3();
        PxTransform tmpPose = new PxTransform(PxIDENTITYEnum.PxIdentity);
        PxSceneDesc sceneDesc = null;
        PxSceneFlags sceneFlags = null;
        PxShapeFlags shapeFlags = null;
        PxFilterData filterData = null;
        PxBoxGeometry blockGeom = null;
        PxBoxGeometry sectorGeom = null;

        try
        {
            tmpVec.setX(0F);
            tmpVec.setY(-in.gravity);
            tmpVec.setZ(0F);

            sceneDesc = new PxSceneDesc(physics.getTolerancesScale());
            sceneDesc.setGravity(tmpVec);
            sceneDesc.setCpuDispatcher(dispatcher);
            sceneDesc.setFilterShader(filterShader);
            /* Stabilization keeps a big pile from micro-jittering forever — without it thousands of
             * touching boxes stay just above the sleep threshold and the early-stop never fires. */
            /* CCD: wind-boosted debris crosses 1+ block per 30Hz step and TUNNELS through 1-block-thin
             * walls/floors without it (tester report: "blocks pass through obstacles"). */
            sceneFlags = new PxSceneFlags(PxSceneFlagEnum.eENABLE_ENHANCED_DETERMINISM.value
                | PxSceneFlagEnum.eENABLE_STABILIZATION.value | PxSceneFlagEnum.eENABLE_CCD.value);
            sceneDesc.setFlags(sceneFlags);
            scene = physics.createScene(sceneDesc);

            material = physics.createMaterial(in.friction, in.friction, in.restitution);
            shapeFlags = new PxShapeFlags((byte) (PxShapeFlagEnum.eSCENE_QUERY_SHAPE.value | PxShapeFlagEnum.eSIMULATION_SHAPE.value));
            filterData = new PxFilterData(1, 1, 0, 0);

            /* All blocks are unit cubes → ONE shared (non-exclusive) shape for every body (plus one
             * half-size shape for shatter sectors). */
            blockGeom = new PxBoxGeometry(0.5F, 0.5F, 0.5F);
            blockShape = physics.createShape(blockGeom, material, false, shapeFlags);
            blockShape.setSimulationFilterData(filterData);
            sectorGeom = new PxBoxGeometry(0.25F, 0.25F, 0.25F);
            sectorShape = physics.createShape(sectorGeom, material, false, shapeFlags);
            sectorShape.setSimulationFilterData(filterData);

            if (!Float.isNaN(in.groundY))
            {
                addStaticBox(scene, statics, shapeFlags, filterData, material, tmpVec, tmpPose,
                    in.groundCx, in.groundY - 1F, in.groundCz, 500F, 1F, 500F);
            }

            for (int b = 0; b < in.worldBoxes.length; b += 6)
            {
                addStaticBox(scene, statics, shapeFlags, filterData, material, tmpVec, tmpPose,
                    in.worldBoxes[b], in.worldBoxes[b + 1], in.worldBoxes[b + 2],
                    in.worldBoxes[b + 3], in.worldBoxes[b + 4], in.worldBoxes[b + 5]);
            }

            boolean[] released = new boolean[units];
            int releasedCount = 0;

            /* Support integrity: neighbour graph + BFS scratch (see supportCheck), all BLOCK-level. The
             * graph also feeds the cluster joints. */
            int[] neighbours = in.anchors != null || in.clusterIds != null ? buildNeighbours(in) : null;
            boolean[] reached = in.anchors != null ? new boolean[count] : null;
            int[] queue = in.anchors != null ? new int[count] : null;
            boolean supportDirty = in.anchors != null;

            for (int u = 0; u < units; u++)
            {
                int i = unitBlock[u];
                int o = unitOctant[u];

                if (o < 0)
                {
                    tmpVec.setX(in.rest[i * 3] + 0.5F);
                    tmpVec.setY(in.rest[i * 3 + 1] + 0.5F);
                    tmpVec.setZ(in.rest[i * 3 + 2] + 0.5F);
                }
                else
                {
                    tmpVec.setX(in.rest[i * 3] + ((o & 1) == 0 ? 0.25F : 0.75F));
                    tmpVec.setY(in.rest[i * 3 + 1] + ((o & 2) == 0 ? 0.25F : 0.75F));
                    tmpVec.setZ(in.rest[i * 3 + 2] + ((o & 4) == 0 ? 0.25F : 0.75F));
                }

                tmpPose.setP(tmpVec);

                PxRigidDynamic body = physics.createRigidDynamic(tmpPose);

                body.attachShape(o < 0 ? blockShape : sectorShape);
                PxRigidBodyExt.updateMassAndInertia(body, 1F);
                /* Swept (continuous) collision for fast debris — pairs with eENABLE_CCD on the scene. */
                body.setRigidBodyFlag(PxRigidBodyFlagEnum.eENABLE_CCD, true);

                /* Mild damping + a raised sleep threshold: debris settles instead of trembling, so the
                 * all-asleep early stop actually triggers on big piles. */
                body.setLinearDamping(0.05F);
                body.setAngularDamping(0.15F);
                body.setSleepThreshold(0.05F);

                if (in.releaseTimes != null && in.releaseTimes[i] > 0F)
                {
                    /* Staged: an unreleased block is a KINEMATIC wall — immovable, but debris collides
                     * with it. Velocity comes at release. */
                    body.setRigidBodyFlag(PxRigidBodyFlagEnum.eKINEMATIC, true);
                }
                else
                {
                    launch(body, in, i, tmpVec);
                    released[u] = true;
                    releasedCount++;
                }

                scene.addActor(body);
                bodies[u] = body;
            }

            if (in.shattered != null)
            {
                /* Sector glue: 12 face-adjacent octant pairs per shattered block (octants differing in
                 * exactly one axis bit), breakable joints at the shared face. */
                float breakForce = in.shatterBreak > 0F ? in.shatterBreak : Float.MAX_VALUE;
                PxTransform frame0 = new PxTransform(PxIDENTITYEnum.PxIdentity);
                PxTransform frame1 = new PxTransform(PxIDENTITYEnum.PxIdentity);

                try
                {
                    for (int i = 0; i < count; i++)
                    {
                        if (!in.shattered[i])
                        {
                            continue;
                        }

                        for (int o = 0; o < 8; o++)
                        {
                            for (int bit = 0; bit < 3; bit++)
                            {
                                if ((o & (1 << bit)) != 0)
                                {
                                    continue;
                                }

                                int o2 = o | (1 << bit);
                                float dx = bit == 0 ? 0.25F : 0F;
                                float dy = bit == 1 ? 0.25F : 0F;
                                float dz = bit == 2 ? 0.25F : 0F;

                                tmpVec.setX(dx);
                                tmpVec.setY(dy);
                                tmpVec.setZ(dz);
                                frame0.setP(tmpVec);
                                tmpVec.setX(-dx);
                                tmpVec.setY(-dy);
                                tmpVec.setZ(-dz);
                                frame1.setP(tmpVec);

                                physx.extensions.PxFixedJoint joint = PxTopLevelFunctions.FixedJointCreate(physics,
                                    bodies[firstUnit[i] + o], frame0, bodies[firstUnit[i] + o2], frame1);

                                joint.setBreakForce(breakForce, breakForce);
                                joints.add(joint);
                            }
                        }
                    }
                }
                finally
                {
                    frame0.destroy();
                    frame1.destroy();
                }
            }

            if (in.clusterIds != null)
            {
                /* Fracture clusters: breakable fixed joints between adjacent same-cluster blocks, the
                 * joint frame at the shared face centre. PhysX breaks them itself past the threshold. */
                float breakForce = in.clusterBreak > 0F ? in.clusterBreak : Float.MAX_VALUE;
                PxTransform frame0 = new PxTransform(PxIDENTITYEnum.PxIdentity);
                PxTransform frame1 = new PxTransform(PxIDENTITYEnum.PxIdentity);

                try
                {
                    for (int i = 0; i < count; i++)
                    {
                        /* Shattered blocks live by their own sector glue — keep them out of clusters. */
                        if (in.shattered != null && in.shattered[i])
                        {
                            continue;
                        }

                        for (int k = 0; k < 6; k++)
                        {
                            int j = neighbours[i * 6 + k];

                            if (j <= i || in.clusterIds[i] != in.clusterIds[j] || (in.shattered != null && in.shattered[j]))
                            {
                                continue;
                            }

                            float dx = (in.rest[j * 3] - in.rest[i * 3]) * 0.5F;
                            float dy = (in.rest[j * 3 + 1] - in.rest[i * 3 + 1]) * 0.5F;
                            float dz = (in.rest[j * 3 + 2] - in.rest[i * 3 + 2]) * 0.5F;

                            tmpVec.setX(dx);
                            tmpVec.setY(dy);
                            tmpVec.setZ(dz);
                            frame0.setP(tmpVec);
                            tmpVec.setX(-dx);
                            tmpVec.setY(-dy);
                            tmpVec.setZ(-dz);
                            frame1.setP(tmpVec);

                            physx.extensions.PxFixedJoint joint = PxTopLevelFunctions.FixedJointCreate(physics,
                                bodies[firstUnit[i]], frame0, bodies[firstUnit[j]], frame1);

                            joint.setBreakForce(breakForce, breakForce);
                            joints.add(joint);
                        }
                    }
                }
                finally
                {
                    frame0.destroy();
                    frame1.destroy();
                }
            }

            float[] data = new float[steps * units * FLOATS_PER_BLOCK];

            /* Step 0 = rest (recorded before any simulate), then march the fixed step. The sim stops
             * EARLY once every body sleeps (all debris has landed and settled), so destruction=1 is
             * always the fully settled end state — duration is just the cap for never-settling scenes. */
            record(bodies, data, 0);

            /* Global wind: per-unit front-arrival time + REST radial direction (deterministic and
             * cheap — the pulse is short, debris barely changes bearing while it lasts). */
            boolean wind = in.windStrength != 0F || in.windAmbX != 0F || in.windAmbZ != 0F;
            float[] windPass = null;
            float[] windDir = null;
            float windOver = 0F;

            if (wind)
            {
                windPass = new float[units];
                windDir = new float[units * 3];

                for (int u = 0; u < units; u++)
                {
                    int b = unitBlock[u];
                    float dx = in.rest[b * 3] + 0.5F - in.epicX;
                    float dy = in.rest[b * 3 + 1] + 0.5F - in.epicY;
                    float dz = in.rest[b * 3 + 2] + 0.5F - in.epicZ;
                    float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

                    windPass[u] = in.windFrontSpeed > 1e-4F ? d / in.windFrontSpeed : 0F;
                    windOver = Math.max(windOver, windPass[u]);

                    if (d > 1e-4F)
                    {
                        windDir[u * 3] = dx / d;
                        windDir[u * 3 + 1] = dy / d;
                        windDir[u * 3 + 2] = dz / d;
                    }
                    else
                    {
                        windDir[u * 3 + 1] = 1F;
                    }
                }

                /* No early sleep-stop until the front has swept everything and its pulse died down —
                 * the front WAKES settled debris, so stopping before it is done would freeze mid-gust. */
                windOver += 3F * Math.max(0.05F, in.windDecay);
            }

            int baked = 1;
            float currentGravity = in.gravity;

            for (int step = 1; step < steps; step++)
            {
                boolean releasedThisStep = false;

                if (in.releaseTimes != null && releasedCount < units)
                {
                    /* Staged release: the wave front has reached these blocks — go dynamic + launch. */
                    float t = step * dt;

                    for (int u = 0; u < units; u++)
                    {
                        if (!released[u] && in.releaseTimes[unitBlock[u]] <= t)
                        {
                            bodies[u].setRigidBodyFlag(PxRigidBodyFlagEnum.eKINEMATIC, false);
                            launch(bodies[u], in, unitBlock[u], tmpVec);
                            bodies[u].wakeUp();
                            released[u] = true;
                            releasedCount++;
                            releasedThisStep = true;
                        }
                    }
                }

                if (in.anchors != null && (supportDirty || releasedThisStep))
                {
                    /* Something came loose — everything without a neighbour chain to an anchor falls. */
                    releasedCount += supportCheck(in, bodies, released, firstUnit, neighbours, reached, queue);
                    supportDirty = false;
                }

                if (in.gravityCurve != null)
                {
                    /* Animated gravity: sample the curve at this step's sim-time fraction; on change,
                     * update the scene and WAKE everything — setGravity does not wake sleeping bodies,
                     * so a settled pile would ignore the flip and just hang. */
                    float g = in.gravityCurve[Math.min(in.gravityCurve.length - 1,
                        (int) ((step / (float) (steps - 1)) * (in.gravityCurve.length - 1)))];

                    if (Math.abs(g - currentGravity) > 1e-4F)
                    {
                        currentGravity = g;
                        tmpVec.setX(0F);
                        tmpVec.setY(-g);
                        tmpVec.setZ(0F);
                        scene.setGravity(tmpVec);

                        for (int u = 0; u < units; u++)
                        {
                            /* wakeUp is invalid on kinematic (unreleased) bodies. */
                            if (released[u])
                            {
                                bodies[u].wakeUp();
                            }
                        }
                    }
                }

                if (wind)
                {
                    /* The front's radial shove (decaying after it passes) + the constant ambient drift.
                     * ACCELERATION mode = mass-independent. The pulse WAKES bodies (a blast front shakes
                     * an already-settled pile — physically right); the ambient alone never does, so the
                     * early-stop sleep check still terminates. */
                    float t = step * dt;

                    for (int u = 0; u < units; u++)
                    {
                        if (!released[u])
                        {
                            continue;
                        }

                        float fx = in.windAmbX, fy = 0F, fz = in.windAmbZ;
                        boolean wake = false;

                        if (in.windStrength != 0F)
                        {
                            float tw = t - windPass[u];

                            if (tw >= 0F)
                            {
                                float mag = in.windStrength * (float) Math.exp(-tw / Math.max(0.05F, in.windDecay));

                                fx += windDir[u * 3] * mag;
                                fy += windDir[u * 3 + 1] * mag;
                                fz += windDir[u * 3 + 2] * mag;
                                wake = mag > 0.5F;
                            }
                        }

                        if (fx != 0F || fy != 0F || fz != 0F)
                        {
                            tmpVec.setX(fx);
                            tmpVec.setY(fy);
                            tmpVec.setZ(fz);
                            bodies[u].addForce(tmpVec, physx.physics.PxForceModeEnum.eACCELERATION, wake);
                        }
                    }
                }

                scene.simulate(dt);
                scene.fetchResults(true);
                record(bodies, data, step);
                baked++;

                /* No early stop with animated gravity, while the wave is still releasing blocks, or
                 * before the wind front has finished sweeping (it wakes settled debris). */
                if (in.gravityCurve == null && releasedCount >= units && step % 5 == 0
                    && (!wind || step * dt > windOver) && allAsleep(bodies))
                {
                    break;
                }
            }

            if (baked < steps)
            {
                data = java.util.Arrays.copyOf(data, baked * units * FLOATS_PER_BLOCK);
            }

            System.out.println("[bbsvfx] Destruction physics baked: " + count + " blocks (" + units + " units), " + baked + "/" + steps + " steps @ " + Math.round(hz)
                + " Hz (settled at " + String.format("%.1f", (baked - 1) * dt) + "s), " + (statics.size() - (Float.isNaN(in.groundY) ? 0 : 1))
                + " world colliders, " + joints.size() + " joints, " + ((System.nanoTime() - start) / 1_000_000) + " ms");

            return new Bake(count, units, unitBlock, unitOctant, baked, (baked - 1) * dt, data);
        }
        finally
        {
            /* Joints must go BEFORE the bodies they reference. */
            for (physx.extensions.PxFixedJoint joint : joints)
            {
                joint.release();
            }

            for (PxRigidDynamic body : bodies)
            {
                if (body != null)
                {
                    body.release();
                }
            }

            for (PxRigidStatic collider : statics)
            {
                collider.release();
            }

            if (blockShape != null) blockShape.release();
            if (sectorShape != null) sectorShape.release();
            if (material != null) material.release();
            if (scene != null) scene.release();
            if (blockGeom != null) blockGeom.destroy();
            if (sectorGeom != null) sectorGeom.destroy();
            if (filterData != null) filterData.destroy();
            if (shapeFlags != null) shapeFlags.destroy();
            if (sceneFlags != null) sceneFlags.destroy();
            if (sceneDesc != null) sceneDesc.destroy();
            tmpPose.destroy();
            tmpVec.destroy();
        }
    }

    private static void addStaticBox(PxScene scene, java.util.List<PxRigidStatic> statics, PxShapeFlags shapeFlags,
        PxFilterData filterData, PxMaterial material, PxVec3 tmpVec, PxTransform tmpPose,
        float cx, float cy, float cz, float hx, float hy, float hz)
    {
        tmpVec.setX(cx);
        tmpVec.setY(cy);
        tmpVec.setZ(cz);
        tmpPose.setP(tmpVec);

        PxBoxGeometry geom = new PxBoxGeometry(hx, hy, hz);
        PxShape shape = physics.createShape(geom, material, true, shapeFlags);

        shape.setSimulationFilterData(filterData);
        geom.destroy();

        PxRigidStatic body = physics.createRigidStatic(tmpPose);

        body.attachShape(shape);
        scene.addActor(body);
        statics.add(body);
    }

    /** 6-neighbour indices per block (count*6, -1 = no captured neighbour there). */
    private static int[] buildNeighbours(BakeInput in)
    {
        int count = in.count;
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap index = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(count);

        index.defaultReturnValue(-1);

        for (int i = 0; i < count; i++)
        {
            index.put(net.minecraft.util.math.BlockPos.asLong(in.rest[i * 3], in.rest[i * 3 + 1], in.rest[i * 3 + 2]), i);
        }

        int[] offsets = {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};
        int[] neighbours = new int[count * 6];

        for (int i = 0; i < count; i++)
        {
            for (int k = 0; k < 6; k++)
            {
                neighbours[i * 6 + k] = index.get(net.minecraft.util.math.BlockPos.asLong(
                    in.rest[i * 3] + offsets[k * 3],
                    in.rest[i * 3 + 1] + offsets[k * 3 + 1],
                    in.rest[i * 3 + 2] + offsets[k * 3 + 2]));
            }
        }

        return neighbours;
    }

    /**
     * Support connectivity: BFS from still-intact anchors through still-intact neighbours (BLOCK level;
     * a block is intact while its first unit is unreleased — a block's units always release together);
     * every intact block NOT reached lost its path to the ground — release its units with NO launch
     * velocity (they just fall and the physics does the rest). Returns how many UNITS were released.
     */
    private static int supportCheck(BakeInput in, PxRigidDynamic[] bodies, boolean[] released, int[] firstUnit, int[] neighbours, boolean[] reached, int[] queue)
    {
        int count = in.count;

        java.util.Arrays.fill(reached, false);

        int head = 0, tail = 0;

        for (int i = 0; i < count; i++)
        {
            if (!released[firstUnit[i]] && in.anchors[i])
            {
                reached[i] = true;
                queue[tail++] = i;
            }
        }

        while (head < tail)
        {
            int cur = queue[head++];

            for (int k = 0; k < 6; k++)
            {
                int nb = neighbours[cur * 6 + k];

                if (nb >= 0 && !released[firstUnit[nb]] && !reached[nb])
                {
                    reached[nb] = true;
                    queue[tail++] = nb;
                }
            }
        }

        int releasedNow = 0;

        for (int i = 0; i < count; i++)
        {
            if (released[firstUnit[i]] || reached[i])
            {
                continue;
            }

            int unitsOfBlock = in.shattered != null && in.shattered[i] ? 8 : 1;

            for (int u = firstUnit[i]; u < firstUnit[i] + unitsOfBlock; u++)
            {
                bodies[u].setRigidBodyFlag(PxRigidBodyFlagEnum.eKINEMATIC, false);
                bodies[u].wakeUp();
                released[u] = true;
                releasedNow++;
            }
        }

        return releasedNow;
    }

    /** Give a (newly dynamic) body its launch velocities. */
    private static void launch(PxRigidDynamic body, BakeInput in, int i, PxVec3 tmpVec)
    {
        float vx = in.linVel[i * 3], vy = in.linVel[i * 3 + 1], vz = in.linVel[i * 3 + 2];

        if (vx * vx + vy * vy + vz * vz > 1e-8F)
        {
            tmpVec.setX(vx);
            tmpVec.setY(vy);
            tmpVec.setZ(vz);
            body.setLinearVelocity(tmpVec);
        }

        float ax = in.angVel[i * 3], ay = in.angVel[i * 3 + 1], az = in.angVel[i * 3 + 2];

        if (ax * ax + ay * ay + az * az > 1e-8F)
        {
            tmpVec.setX(ax);
            tmpVec.setY(ay);
            tmpVec.setZ(az);
            body.setAngularVelocity(tmpVec);
        }
    }

    private static boolean allAsleep(PxRigidDynamic[] bodies)
    {
        for (PxRigidDynamic body : bodies)
        {
            if (!body.isSleeping())
            {
                return false;
            }
        }

        return true;
    }

    private static void record(PxRigidDynamic[] bodies, float[] data, int step)
    {
        int base = step * bodies.length * FLOATS_PER_BLOCK;

        for (int i = 0; i < bodies.length; i++)
        {
            PxTransform pose = bodies[i].getGlobalPose();
            PxVec3 p = pose.getP();
            PxQuat q = pose.getQ();
            int o = base + i * FLOATS_PER_BLOCK;

            data[o] = p.getX();
            data[o + 1] = p.getY();
            data[o + 2] = p.getZ();
            data[o + 3] = q.getX();
            data[o + 4] = q.getY();
            data[o + 5] = q.getZ();
            data[o + 6] = q.getW();
        }
    }

    /**
     * A finished bake: unit-centre positions + rotations per step (a unit = a whole block, or one of
     * the 8 sectors of a shattered block — see {@link #unitBlock}/{@link #unitOctant}). {@link #sample}
     * lerps/slerps between the two neighbouring steps, so scrubbing is smooth at any speed.
     */
    public static class Bake
    {
        /** Source BLOCK count (for compatibility checks against the form's block list). */
        public final int count;
        /** Simulated body count: blocks + 7 extra per shattered block. */
        public final int units;
        /** Per unit: source block index. */
        public final int[] unitBlock;
        /** Per unit: -1 = the whole block, 0..7 = sector octant (bit 1 = +x half, 2 = +y, 4 = +z). */
        public final byte[] unitOctant;
        public final int steps;
        public final float duration;
        private final float[] data;

        private Bake(int count, int units, int[] unitBlock, byte[] unitOctant, int steps, float duration, float[] data)
        {
            this.count = count;
            this.units = units;
            this.unitBlock = unitBlock;
            this.unitOctant = unitOctant;
            this.steps = steps;
            this.duration = duration;
            this.data = data;
        }

        public int floats()
        {
            return this.data.length;
        }

        /** The raw step data, for the on-disk persistence only. */
        float[] rawData()
        {
            return this.data;
        }

        /** Sample unit {@code index} at {@code t01} (= destruction 0..1) into {@code outPos} (centre) + {@code outRot}. */
        public void sample(int index, float t01, Vector3f outPos, Quaternionf outRot)
        {
            float t = Math.min(Math.max(t01, 0F), 1F) * (this.steps - 1);
            int k = (int) t;
            float frac = t - k;

            int a = (k * this.units + index) * FLOATS_PER_BLOCK;

            if (frac <= 0F || k >= this.steps - 1)
            {
                outPos.set(this.data[a], this.data[a + 1], this.data[a + 2]);
                outRot.set(this.data[a + 3], this.data[a + 4], this.data[a + 5], this.data[a + 6]);

                return;
            }

            int b = a + this.units * FLOATS_PER_BLOCK;

            outPos.set(
                this.data[a] + (this.data[b] - this.data[a]) * frac,
                this.data[a + 1] + (this.data[b + 1] - this.data[a + 1]) * frac,
                this.data[a + 2] + (this.data[b + 2] - this.data[a + 2]) * frac
            );
            outRot.set(this.data[a + 3], this.data[a + 4], this.data[a + 5], this.data[a + 6]);
            outRot.slerp(new Quaternionf(this.data[b + 3], this.data[b + 4], this.data[b + 5], this.data[b + 6]), frac);
        }
    }
}
