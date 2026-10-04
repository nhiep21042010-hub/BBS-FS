package com.bbsvfx.vfxlights.client.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.bbsvfx.vfxlights.light.Light;

import java.util.List;

/**
 * Renders each shadow-casting light's occluders into the atlas.
 *
 * <p>A point light gets six tiles — one per cube face — and a spot or area light one. Faces are drawn as
 * six independent passes rather than with a geometry shader, both because layered rendering is beyond the
 * GL version Minecraft asks for and because a plain pass per face is far easier to reason about.</p>
 *
 * <p>Everything is drawn relative to a whole-block origin near the lamp (see {@link OccluderCache}); the
 * view matrix therefore works in that same local space. World coordinates in float precision put visible
 * z-fighting into shadow maps far from spawn, and it crawls as the camera moves.</p>
 */
public final class ShadowMapper
{
    /** Faces in the order the shader's cube lookup expects: +X, -X, +Y, -Y, +Z, -Z. */
    private static final Vector3f[] FACE_DIR =
    {
        new Vector3f(1F, 0F, 0F), new Vector3f(-1F, 0F, 0F),
        new Vector3f(0F, 1F, 0F), new Vector3f(0F, -1F, 0F),
        new Vector3f(0F, 0F, 1F), new Vector3f(0F, 0F, -1F)
    };

    private static final Vector3f[] FACE_UP =
    {
        new Vector3f(0F, -1F, 0F), new Vector3f(0F, -1F, 0F),
        new Vector3f(0F, 0F, 1F), new Vector3f(0F, 0F, -1F),
        new Vector3f(0F, -1F, 0F), new Vector3f(0F, -1F, 0F)
    };

    /** Occluders are lit uniformly — only their depth is kept, so the lightmap is irrelevant. */
    private static final int FULL_BRIGHT = LightmapTextureManager.pack(15, 15);

    private static int frame;

    /**
     * True while occluders are being drawn into the atlas. The entity pass re-enters the normal form
     * render path, and a light form rendered THERE must not submit itself to the registry — that both
     * mutates the list being iterated (a crash, observed) and would let a lamp seen only from another
     * lamp's viewpoint join the frame.
     */
    private static boolean active;

    /** The POSITION_COLOR program that writes glass tints (and their depth, in alpha) to the atlas. */
    private static ShaderProgram glassShader;

    public static void setGlassShader(ShaderProgram shader)
    {
        glassShader = shader;
    }

    private ShadowMapper()
    {
    }

    public static boolean isActive()
    {
        return active;
    }

    private static float tickDelta()
    {
        return MinecraftClient.getInstance().getTickDelta();
    }

    /**
     * Build every shadow map needed this frame.
     *
     * <p>Captures and restores the caller's framebuffer and viewport itself: with a shaderpack the
     * pass runs at frame start inside Iris's setup, and guessing what was bound there — instead of
     * asking GL — is how другие чужие проходы ломали чужой стейт.</p>
     */
    public static void renderAll(List<Light> lights)
    {
        int previousFbo = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);

        renderAll(lights, previousFbo, previousViewport);
    }

    private static void renderAll(List<Light> lights, int previousFbo, int[] previousViewport)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;

        if (world == null)
        {
            return;
        }

        boolean any = false;

        for (Light light : lights)
        {
            if (castsShadow(light))
            {
                any = true;

                break;
            }
        }

        if (!any)
        {
            return;
        }

        frame++;

        /* The frame's entity snapshot, built once and shared by every per-lamp scan (nearestActors,
         * dynamicFaceMask, the per-face entity pass): world.getEntities() is a full world
         * iteration, and at per-lamp — worse, per FACE per lamp — frequency it was the shadow
         * pass' quiet CPU line (7+ scans per shadowed lamp per frame). */
        FRAME_ENTITIES.clear();
        world.getEntities().forEach(FRAME_ENTITIES::add);

        /* Re-arm the static-rebake budgets for the frame (see renderLight). */
        rebakeBudget = MAX_STATIC_REBAKES;
        mandatoryBudget = MAX_STATIC_REBAKES;

        ShadowAtlas.ensure();
        ShadowAtlas.beginFrame();

        ShaderProgram shader = GameRenderer.getPositionProgram();

        if (shader == null)
        {
            return;
        }

        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.colorMask(false, false, false, false);

        /* Copy first: the entity pass re-enters form rendering, and even with collection suppressed a
         * frame boundary elsewhere could touch the live registry list mid-iteration. */
        List<Light> snapshot = new java.util.ArrayList<>(lights);

        /* Lease priority, ahead of the anti-starvation partition: when the atlas cannot host every
         * lamp (VRAM budget or plain capacity) the shadows go to the lamps the frame actually
         * SHOWS — nearest and largest first — not to registration order. Sticky leases make this
         * a hysteresis rather than a shuffle: an outranked lamp loses tiles only through real
         * staleness (2 frames out of shot), never through the sort itself. The PENDING partition
         * below is stable, so each partition keeps this importance order inside. */
        if (!NO_LEASE_PRIO && snapshot.size() > 1)
        {
            net.minecraft.util.math.Vec3d cam = mc.gameRenderer.getCamera().getPos();

            leaseCamX = cam.x;
            leaseCamY = cam.y;
            leaseCamZ = cam.z;
            snapshot.sort(LEASE_PRIO);
        }

        /* ★Anti-starvation: lights dropped by last frame's rebake budget go FIRST this frame.
         * Iterating in plain registry order let the same head-of-list lamps eat the 4-slot budget
         * every frame — with more perpetually-dirty lamps than slots (an animated rig, a crowd of
         * actors) the tail stayed unbaked FOREVER, i.e. visibly dark lamps, not a one-frame
         * warm-up. Relative order is otherwise kept, so existing leases (validated by ownership,
         * not by order) do not reshuffle. */
        if (!PENDING_IDS.isEmpty())
        {
            List<Light> ordered = new java.util.ArrayList<>(snapshot.size());

            for (Light light : snapshot)
            {
                if (PENDING_IDS.contains(idOf(light)))
                {
                    ordered.add(light);
                }
            }

            for (Light light : snapshot)
            {
                if (!PENDING_IDS.contains(idOf(light)))
                {
                    ordered.add(light);
                }
            }

            snapshot = ordered;
            PENDING_IDS.clear();
        }

        active = true;
        com.bbsvfx.bbsvfx.client.BbsVfxForeignPass.enter("shadow");

        try
        {
            for (Light light : snapshot)
            {
                light.shadowTile = -1;
                java.util.Arrays.fill(light.actorTile, -1);

                if (!castsShadow(light))
                {
                    continue;
                }

                renderLight(light, world, shader);
            }
        }
        finally
        {
            active = false;
            com.bbsvfx.bbsvfx.client.BbsVfxForeignPass.exit();
        }

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableCull();

        ShadowAtlas.end(previousFbo, previousViewport);
        OccluderCache.prune(frame, 60);

        if (DEBUG && frame % 300 == 0)
        {
            org.slf4j.LoggerFactory.getLogger("vfxlights").info(
                "shadow tiles: {} skipped / {} rendered since start"
                    + " (static rebakes {}, blits {}, pending {})",
                tilesSkipped, tilesRendered, staticRebaked, blitted, pending);
        }
    }

    private static boolean castsShadow(Light light)
    {
        return light.shadows && light.type != Light.Type.AMBIENT && light.intensity > 0.001F;
    }

    /** The frame's shared entity snapshot — built in renderAll, read by every per-lamp scan. */
    private static final java.util.List<Entity> FRAME_ENTITIES = new java.util.ArrayList<>(256);

    /* Lease priority state: the camera the current sort reads, stashed in fields so the comparator
     * itself can stay a constant (per-frame lambdas allocate on the render thread). */
    private static double leaseCamX;
    private static double leaseCamY;
    private static double leaseCamZ;

    /** Screen presence: angular size proxy — range over distance. Nearest and largest shadows win
     *  capacity fights; ties keep registry order (TimSort is stable). */
    private static final java.util.Comparator<Light> LEASE_PRIO = (a, b) ->
    {
        double dax = a.x - leaseCamX, day = a.y - leaseCamY, daz = a.z - leaseCamZ;
        double dbx = b.x - leaseCamX, dby = b.y - leaseCamY, dbz = b.z - leaseCamZ;
        double pa = a.range / Math.max(Math.sqrt(dax * dax + day * day + daz * daz), 1.0);
        double pb = b.range / Math.max(Math.sqrt(dbx * dbx + dby * dby + dbz * dbz), 1.0);

        return Double.compare(pb, pa);
    };

    /** Dev kill-switch (-Dvfxlights.shadow.noleaseprio): leases in plain registry order — the
     *  pre-priority behaviour. */
    private static final boolean NO_LEASE_PRIO = System.getProperty("vfxlights.shadow.noleaseprio") != null;

    /**
     * Last known tile + spot matrix per light id.
     *
     * <p>Exists for the volumetric pass under a shaderpack: there the maps are rendered at frame START
     * against LAST frame's light objects, after which the registry is cleared and refilled — so the
     * objects the volumetric pass sees at frame end all carry tile -1, and every beam silently lost
     * its shadow structure ("mist, not rays"; the vanilla path assigns tiles mid-frame and never hit
     * this). The assignment is persisted by id and re-applied to the fresh objects.</p>
     */
    private static final java.util.Map<Long, Persisted> PERSISTED = new java.util.HashMap<>();

    private static final class Persisted
    {
        int tile;
        final Matrix4f matrix = new Matrix4f();
        /* The actor-fitted maps ride the same persist: beams under a pack re-apply them by
         * identity. One entry per slot — a slot's tile is -1 when it carries no map. */
        final int[] actorTile = { -1, -1, -1, -1 };
        final float[] actorNear = new float[Light.ACTOR_SLOTS];
        final float[] actorFar = new float[Light.ACTOR_SLOTS];
        final float[] actorTan = new float[Light.ACTOR_SLOTS];
        final Matrix4f[] actorMatrix = { new Matrix4f(), new Matrix4f(), new Matrix4f(),
            new Matrix4f() };
        /* The world tick the actor tile was last rendered at (diagnostic). The tick-rate
         * hysteresis this was added for is REVERTED: a frozen map let the body lerp away from
         * it inside the tick and the mm-detail speckle crawled across the skin — measured as
         * the very shimmer it was meant to kill. This pass renders every frame, pose-matched. */
        long lastActorTick = -1;
        /* Sticky actor targets, one per slot: each fitted frustum keeps hugging the same actor
         * while it stays in range, instead of jumping to whoever passes nearest — a flip
         * silently stole the detail from the previous target. */
        final boolean[] hasTarget = new boolean[Light.ACTOR_SLOTS];
        final double[] targetX = new double[Light.ACTOR_SLOTS];
        final double[] targetY = new double[Light.ACTOR_SLOTS];
        final double[] targetZ = new double[Light.ACTOR_SLOTS];

        /* Deadzone-drag state of the fitted window (renderActorTile): the held aim and distance
         * the slot's frustum currently lives at. Bit-frozen while the target stays inside the
         * deadzone, dragged 1:1 past it — position-continuous, so no re-fit step exists to see.
         * Invalidated the moment a slot changes owners (assignActorTargets) — the state is the
         * previous actor's. */
        final boolean[] snapValid = new boolean[Light.ACTOR_SLOTS];
        final double[] snapX = new double[Light.ACTOR_SLOTS];
        final double[] snapY = new double[Light.ACTOR_SLOTS];
        final double[] snapZ = new double[Light.ACTOR_SLOTS];
        final double[] snapDist = new double[Light.ACTOR_SLOTS];

        /* ── Dirty-skip state: the exact inputs the tiles were LAST rendered with. ──
         * A tile whose lease survived, whose occluders did not rebuild, whose light did not move
         * and which has no dynamic caster in range still holds a correct map — re-rendering it is
         * the single biggest per-frame cost in the mod (6 full passes for every point lamp). */
        boolean valid;
        /** The occluder bake (OccluderCache.Entry.builtFrame) the static map was rendered with.
         * Compared by VALUE, not against the current frame: a rebake deferred by the budget must
         * stay dirty until it actually happens — the frame-scoped check would forget the world
         * changed the very next frame. */
        int occludersFrame = -1;
        /** The face mask dynamic casters were last drawn with — the blit restores a face one frame
         *  AFTER its last caster leaves, or the silhouette stays burned in (dynNow | dynLast). */
        int lastDynMask;
        /** The per-face bboxes the last dynamic overlay was clipped to — the restore blit copies
         *  exactly those texels back from the static layer instead of whole faces. Whole-tile
         *  rects when partial work was off (the silhouette could be anywhere on the face). */
        final int[] lastDynRects = new int[24];
        /** The STATIC LAYER holds this light's fresh static bake at {@link #tile}: the live tiles
         * can be restored from it with a blit while a dynamic caster keeps overwriting them.
         * False when the light was baked straight into the live tiles — a static-only scene never
         * allocates the layer at all (see ShadowAtlas.ensureStatic). */
        boolean staticValid;
        /** The last render had a dynamic caster — one redraw is owed AFTER it leaves, or its final
         * silhouette stays burned into the map. */
        boolean hadDynamic;
        int tiles;
        double x;
        double y;
        double z;
        float dirX;
        float dirY;
        float dirZ;
        float range;
        float cosOuter;
        boolean dispersed;
        boolean groupFilter;
        int groupsHash;

        /** Exact comparison on purpose: a keyframed-but-parked lamp holds bit-identical values,
         * while any real animation misses on the first float and forces the redraw it needs. */
        boolean sameState(Light light, int tileCount)
        {
            return this.tiles == tileCount
                && this.x == light.x && this.y == light.y && this.z == light.z
                && this.dirX == light.dirX && this.dirY == light.dirY && this.dirZ == light.dirZ
                && this.range == light.range
                && this.cosOuter == light.cosOuter
                && this.dispersed == (light.dispersion > 0.001F)
                && this.groupFilter == light.groupFilter
                && this.groupsHash == light.groups.hashCode();
        }

        void record(Light light, int tileCount, boolean dynamic)
        {
            this.valid = true;
            this.hadDynamic = dynamic;
            this.tiles = tileCount;
            this.x = light.x;
            this.y = light.y;
            this.z = light.z;
            this.dirX = light.dirX;
            this.dirY = light.dirY;
            this.dirZ = light.dirZ;
            this.range = light.range;
            this.cosOuter = light.cosOuter;
            this.dispersed = light.dispersion > 0.001F;
            this.groupFilter = light.groupFilter;
            this.groupsHash = light.groups.hashCode();
        }
    }

    /** Drop the persisted tile assignment for a light id whose occluders were pruned — without this
     * the map grew one entry per ephemeral shadow-casting light for the whole session. */
    static void forget(long lightId)
    {
        PERSISTED.remove(lightId);
        PENDING_IDS.remove(lightId);

        for (int slot = 0; slot < Light.ACTOR_SLOTS; slot++)
        {
            ShadowAtlas.release(actorId(lightId, slot));
        }
    }

    /** Re-apply the persisted shadow assignment to a fresh Light object with the same identity. */
    public static void applyPersisted(Light light)
    {
        if (light.shadowTile >= 0)
        {
            return;
        }

        Persisted saved = PERSISTED.get(idOf(light));

        if (saved != null)
        {
            light.shadowTile = saved.tile;
            light.shadowMatrix.set(saved.matrix);

            for (int slot = 0; slot < Light.ACTOR_SLOTS; slot++)
            {
                light.actorTile[slot] = saved.actorTile[slot];
                light.actorNear[slot] = saved.actorNear[slot];
                light.actorFar[slot] = saved.actorFar[slot];
                light.actorTan[slot] = saved.actorTan[slot];
                light.actorMatrix[slot].set(saved.actorMatrix[slot]);
            }
        }
    }

    private static long idOf(Light light)
    {
        return light.key == null ? System.identityHashCode(light) : light.key.hashCode();
    }

    /** The lease id of a light's actor-fitted tile for one slot — DIFFERENT ids from the base
     * run's and from each other's, so the coming and going of an actor target never disturbs
     * the main lease nor the other slots (see renderLight). Slot 0 keeps the original mix, so
     * leases taken before the slot change still line up. */
    private static long actorId(long lightId, int slot)
    {
        return lightId ^ (0x9E3779B97F4A7C15L * (slot + 1L));
    }

    /**
     * The rebake budget said "not this frame". If the atlas still holds OUR pixels — the lease
     * survived and points at the tiles we last rendered — keep showing last frame's map: a stale
     * shadow for the few frames the rig waits out the budget beats the shadow popping OFF and ON
     * (the many-lamps flicker). With the lease stolen or moved the tiles hold a FOREIGN map, and
     * unshadowed is the only honest answer.
     */
    private static void reuseStaleMap(Light light, Persisted saved, int first, boolean leaseKept)
    {
        /* ★TEMP diagnostic (same flag): a deferral frame freezes the actor map on the stale pose —
         * with fast animation that is a one-frame pose/map mismatch, i.e. the flicker candidate. */
        if (ACTOR_DEBUG)
        {
            org.slf4j.LoggerFactory.getLogger("vfxlights").info(
                "[vfxlights] actordbg f={} DEFERRED (leaseKept={}) — actor map frozen this frame",
                frame, leaseKept);
        }

        if (leaseKept && saved.valid && saved.tile == first)
        {
            /* The actor slots follow the main run's rule — "the atlas still holds OUR pixels":
             * renew each live slot's lease (the deferred frame renders nothing, so without the
             * renewal the tiles go stale and contention can hand them away). A slot whose tile
             * was already taken now holds a FOREIGN map: drop it, and the main map's word stands
             * until the next rendered frame re-fits the actor. */
            for (int slot = 0; slot < Light.ACTOR_SLOTS; slot++)
            {
                if (saved.actorTile[slot] >= 0
                    && ShadowAtlas.lease(actorId(idOf(light), slot), 1) != saved.actorTile[slot])
                {
                    saved.actorTile[slot] = -1;
                }
            }

            applyPersisted(light);
        }
    }

    /** Half-angle cosine below which a spot is "wide" — cos 60°, i.e. a full cone past 120°. */
    private static final float WIDE_SPOT_COS = 0.5F;

    /**
     * Whether this light's shadow lives in a six-face cube rather than a single projected tile.
     *
     * <p>A single perspective map dies on wide spots: the projection is capped at 170° while the lit
     * cone keeps going, and everything past the map's edge polls as "no data, lit" — measured as an
     * actor's shadow CUT by a straight vertical line mid-wall (the threshold law again). Past 120° the
     * map's edge texels are already stretched to mush anyway, so wide spots take the cube path; the
     * cone stays a cone in shading, only the shadow lookup changes.</p>
     */
    public static boolean usesCube(Light light)
    {
        return light.type != Light.Type.SPOT || light.cosOuter < WIDE_SPOT_COS;
    }

    /** Tiles skipped/redrawn this session — the dirty-skip's own health meter (shadow.debug). */
    private static int tilesSkipped;
    private static int tilesRendered;

    private static final boolean DEBUG = System.getProperty("vfxlights.shadow.debug") != null;

    /** Dev kill-switch (-Dvfxlights.shadow.noskip): re-render every tile every frame, the exact
     * pre-dirty-skip behaviour — one flag to bisect any staleness the skip conditions missed. */
    private static final boolean NO_SKIP = System.getProperty("vfxlights.shadow.noskip") != null;

    /** Dev kill-switch (-Dvfxlights.shadow.nooffset): write actor depth without the slope-scaled
     * polygon offset, to bisect shadow artefacts against the second-skin-layer fix. */
    private static final boolean NO_ACTOR_OFFSET = System.getProperty("vfxlights.shadow.nooffset") != null;

    /** Dev kill-switch (-Dvfxlights.shadow.noactor): never lease the actor-fitted tile (Blender-look). */
    private static final boolean NO_ACTOR_TILE = System.getProperty("vfxlights.shadow.noactor") != null;

    /** Dev kill-switch (-Dvfxlights.opt.nocull): never camera-cull a light's shadow work — the exact
     * pre-cull behaviour, to bisect artefacts against the cull. */
    private static final boolean NO_CULL = System.getProperty("vfxlights.opt.nocull") != null;

    /** Dev kill-switch (-Dvfxlights.opt.nostaticlive): the exact pre-split behaviour — a dynamic
     * caster anywhere in range costs a FULL redraw of every tile, static geometry included,
     * every frame. Kept whole so any artefact of the static/live split can be bisected away. */
    private static final boolean NO_STATIC_LIVE = System.getProperty("vfxlights.opt.nostaticlive") != null;

    /** Dev kill-switch (-Dvfxlights.opt.nobudget): lift the per-frame cap on static rebakes, so
     * every dirty light rebakes the frame it asks — the cold-start spike the budget smooths. */
    private static final boolean NO_BUDGET = System.getProperty("vfxlights.opt.nobudget") != null;

    /** Dev kill-switch (-Dvfxlights.shadow.nopartial): the dynamic overlay blits and redraws whole
     * faces again instead of the casters' projected bbox — the pre-partial-tile behaviour. */
    private static final boolean NO_PARTIAL = System.getProperty("vfxlights.shadow.nopartial") != null;

    /** Dev kill-switch (-Dvfxlights.shadow.actorstatic): the actor-fitted tile also draws the baked
     * static occluders into its narrow frustum — the pre-entities-only behaviour. */
    private static final boolean ACTOR_STATIC = System.getProperty("vfxlights.shadow.actorstatic") != null;

    /** ★TEMP diagnostic (-Dvfxlights.shadow.actordebug): per-frame actor-tile bake/defer lines for
     * the fast-animation flicker hunt — strip once the mechanism is proven. */
    private static final boolean ACTOR_DEBUG = System.getProperty("vfxlights.shadow.actordebug") != null;

    /** Dev kill-switch (-Dvfxlights.shadow.nodyncadence): dynamic overlays and actor-fitted tiles
     *  update EVERY frame again — the exact pre-cadence behaviour. */
    private static final boolean NO_DYNCADENCE = System.getProperty("vfxlights.shadow.nodyncadence") != null;

    /** Full static rebakes allowed per frame. Two pools: a MANDATORY one for lights with no
     * valid map at all (first bake, atlas growth — 32 fresh lamps then warm up over ~8 frames
     * instead of freezing one) and a regular one for re-bakes of lights already showing a
     * correct shadow, so a moved lamp cannot starve the newcomers. */
    private static final int MAX_STATIC_REBAKES = 4;
    private static int rebakeBudget;
    private static int mandatoryBudget;

    /** Ids of lights last frame's budget deferred (rendered unshadowed). They go first in the
     * next frame's renderAll — see the anti-starvation note there. */
    private static final java.util.Set<Long> PENDING_IDS = new java.util.HashSet<>();

    /** Split-path health meters, reported alongside tilesSkipped/tilesRendered (shadow.debug). */
    private static int staticRebaked;
    private static int blitted;
    private static int pending;

    /** Camera horizon for shadow work: REMOVED 2026-08-07 — a distance cap made far shadows pop off
     * while still visible ("улетаешь — тени пропадают"); only the behind-camera plane culls now. */

    /**
     * A light whose whole influence sphere sits behind the camera plane needs no shadow work this
     * frame — its lit area cannot be on screen. The lease is NOT touched on purpose: the tiles stay
     * owned (stolen only under real contention), so panning the camera back reuses the still-valid
     * map through the dirty-skip instead of paying a full rebake per pan.
     *
     * <p>★Forward is {@code (0,0,1).rotate(rotation)}, NOT the GL-conventional (0,0,-1): MC's camera
     * quaternion maps its +Z to the facing direction (vanilla computes horizontalPlane exactly this
     * way; yaw 0 = south = +Z). The wrong sign culled lights IN FRONT of the camera — shadows
     * vanished as soon as the light was past one range ahead.</p>
     */
    private static boolean isCulled(Light light)
    {
        net.minecraft.client.render.Camera camera =
            MinecraftClient.getInstance().gameRenderer.getCamera();
        net.minecraft.util.math.Vec3d cam = camera.getPos();

        double dx = light.x - cam.x, dy = light.y - cam.y, dz = light.z - cam.z;
        Vector3f forward = camera.getRotation().transform(new Vector3f(0F, 0F, 1F));

        /* Nearest point of the influence sphere still behind the camera plane → nothing it lights
         * can be on screen. */
        return dx * forward.x + dy * forward.y + dz * forward.z < -light.range;
    }

    private static void renderLight(Light light, ClientWorld world, ShaderProgram shader)
    {
        long id = idOf(light);

        /* Camera cull BEFORE any expensive work (actors, occluder bake, tile passes). The map stays
         * leased-but-untouched: an uncontended atlas keeps the pixels, so swinging the camera back
         * reuses them through the dirty-skip instead of rebaking. */
        if (!NO_CULL && isCulled(light))
        {
            applyPersisted(light);

            return;
        }

        /* Area lights take the full six-face cube, same as points. A single cone along the emitter
         * normal seemed enough — until a panel at character height threw near-horizontal shadows that
         * ended in an arc mid-floor: the edge of the cone's frustum. Emission may be one-sided, but
         * shadows land wherever the light reaches, and only a cube covers that. */
        boolean point = usesCube(light);
        int baseTiles = point ? 6 : 1;
        /* Actor-fitted shadows (Blender-look): with entities allowed, the nearest actors in range
         * each get a narrow frustum of their own — an ordinary atlas tile at millimetre density,
         * so the second skin layer reads (rim/outline/2nd-layer shadows). Up to ACTOR_SLOTS tiles
         * per lamp, one per candidate, leased per claimed slot in finishLight.
         * ★Leased as SEPARATE one-tile leases, not one run with the main tiles: an actor walking
         * in or out of range flipped the run 6↔7, the count change invalidated the whole lease,
         * and the re-lease elsewhere forced a FULL static rebake — with several lamps and an actor
         * around that churn rotated through the rebake budget and the rig's shadows blinked. The
         * base run's count never changes now, so actor traffic costs the actor tiles alone. */
        /* The persist entry WITHOUT creating one: a lamp that has none yet has no incumbents, and
         * a lamp the atlas is about to refuse (first < 0 below) must not grow the map for
         * nothing. Only the candidates' incumbent bonus reads it this early (nearestActors —
         * the anti-flicker hysteresis); finishLight does the computeIfAbsent as before. */
        Persisted sticky = PERSISTED.get(id);
        java.util.List<double[]> actors = !light.affectEntities || NO_ACTOR_TILE
            || !com.bbsvfx.vfxlights.VfxLightsAddon.qualityActorTiles()
            ? java.util.Collections.emptyList() : nearestActors(world, light, sticky);
        int first = ShadowAtlas.lease(id, baseTiles);
        boolean leaseKept = ShadowAtlas.lastLeaseWasExisting();

        if (first < 0)
        {
            /* Atlas full. This lamp renders unshadowed rather than stealing another's map. */
            if (DEBUG)
            {
                org.slf4j.LoggerFactory.getLogger("vfxlights").info(
                    "shadow map skipped: {} at [{}, {}, {}] (tiles={}, id={})",
                    light.type, light.x, light.y, light.z, baseTiles, id);
            }

            return;
        }

        OccluderCache.Entry occluders = OccluderCache.get(id, world,
            light.x, light.y, light.z, Math.min(light.range, 48F), frame);

        /* ★STATIC/LIVE SPLIT: the baked world (block occluders + world glass) is STATIC — it only
         * changes with the block bake, the lamp state or the lease — while entities, actors and
         * glass forms are DYNAMIC. Without the split one actor in range cost six FULL geometry
         * passes per lamp per frame (the mod's top per-frame cost on a filmed set): a dynamic
         * frame now re-copies the finished static bake (blit) and redraws only the dynamic pass.
         * The static half lives in a second atlas layer, allocated lazily — a scene with no
         * animated casters bakes straight into the live tiles and never pays the doubled VRAM
         * (the same idea IRLite ships as its static-layer atlas). */
        Persisted saved = PERSISTED.computeIfAbsent(id, (k) -> new Persisted());
        int dynMask = dynamicFaceMask(world, light);

        if (!point)
        {
            /* A spot has one tile — the mask collapses to "any dynamic". */
            dynMask = dynMask != 0 ? 1 : 0;
        }

        boolean hasDynamic = dynMask != 0;

        /* Empty BLOCK occluders is not "no shadow map": the entity/form pass below still casts, and
         * for a machinima the actor is usually the only caster that matters — a lamp over an empty
         * set must still shadow the character. drawOccluders/renderGlass no-op on empty data by
         * themselves, and the tile clears to far depth in beginTile, so the map comes out correct
         * whether or not any entity writes into it. Returning here left shadowTile at -1, which the
         * shaders read as "no map" = fully lit: the beam passed clean through the actor. */
        float far = Math.max(light.range, ShadowAtlas.NEAR + 0.15F);

        /* Light position in the buffer's local space. */
        float lx = (float) (light.x - occluders.originX);
        float ly = (float) (light.y - occluders.originY);
        float lz = (float) (light.z - occluders.originZ);

        Matrix4f[] views = new Matrix4f[baseTiles];
        Matrix4f[] projections = new Matrix4f[baseTiles];

        if (point)
        {
            /* Slightly MORE than 90° per face: the faces overlap past their seams, so a lookup that
             * lands near a seam finds valid data in EITHER face's interior texels. Exact 90° left
             * half-texel slivers at every seam where the neighbouring face answered "empty, lit" —
             * the stained-window white gashes, painted cyan by the face-debug that caught them.
             * The lookups compensate with the same 1.03 scale (VFX_FACE_SCALE). */
            Matrix4f projection = new Matrix4f().perspective(2F * (float) Math.atan(1.03D), 1F,
                ShadowAtlas.NEAR, far);

            for (int face = 0; face < 6; face++)
            {
                Vector3f dir = FACE_DIR[face];
                Vector3f up = FACE_UP[face];

                views[face] = new Matrix4f().lookAt(
                    lx, ly, lz,
                    lx + dir.x, ly + dir.y, lz + dir.z,
                    up.x, up.y, up.z);
                projections[face] = projection;
            }
        }
        else
        {
            /* Spot only — everything else took the cube path above.
             *
             * With dispersion on, the map must also cover the caustics' SPILL cone (~cos 0.35 wider):
             * lookups past the map's edge answer "lit", and the spilled rainbow painted straight over
             * the geometric shadow — the shadow visibly CUT along the map edge line. */
            float cosEdge = light.dispersion > 0.001F ? light.cosOuter - 0.35F : light.cosOuter;
            float angle = (float) Math.toDegrees(Math.acos(Math.max(-0.999F, Math.min(0.999F, cosEdge)))) * 2F;

            Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(Math.max(10F, Math.min(170F, angle))), 1F, ShadowAtlas.NEAR, far);

            Vector3f dir = new Vector3f(light.dirX, light.dirY, light.dirZ).normalize();
            Vector3f up = Math.abs(dir.y) > 0.99F ? new Vector3f(0F, 0F, 1F) : new Vector3f(0F, 1F, 0F);

            views[0] = new Matrix4f().lookAt(
                lx, ly, lz,
                lx + dir.x, ly + dir.y, lz + dir.z,
                up.x, up.y, up.z);
            projections[0] = projection;

            /* The composite samples this map with world coordinates, so fold the local origin back in. */
            light.shadowMatrix.set(projection).mul(views[0]).translate(
                -occluders.originX, -occluders.originY, -occluders.originZ);
        }

        /* The casters' projected bboxes for the partial-tile paths below: the blit and the overlay
         * redraw follow the silhouette's texels instead of the whole face. */
        int[] dynRects = computeDynRects(views, projections, occluders, baseTiles);

        if (NO_STATIC_LIVE)
        {
            /* Kill-switch path: the exact pre-split behaviour — any dynamic caster in range forces
             * a full redraw of every tile, static geometry included, every frame. */
            if (!NO_SKIP && leaseKept && saved.valid && saved.tile == first
                && occluders.builtFrame != frame
                && !hasDynamic && !saved.hadDynamic
                && saved.sameState(light, baseTiles))
            {
                light.shadowTile = first;
                light.shadowMatrix.set(saved.matrix);
                tilesSkipped++;

                return;
            }

            tilesRendered++;

            for (int i = 0; i < baseTiles; i++)
            {
                ShadowAtlas.beginTile(first + i);
                drawOccluders(occluders, views[i], projections[i], shader);
                renderEntities(world, views[i], projections[i], first + i,
                    occluders.originX, occluders.originY, occluders.originZ,
                    light, light.range, tickDelta(), point ? FACE_DIR[i] : null);
                reassertPassState(first + i);
                renderGlass(occluders, views[i], projections[i], first + i, far, false, true, true);
            }

            finishLight(light, world, occluders, shader, saved, actors, first, id, baseTiles,
                hasDynamic, dynMask, null);

            return;
        }

        /* What counts as a VALID map depends on the dynamic half: with an animated caster in range
         * the live tiles get overwritten every frame, so the STATIC LAYER must hold our bake to
         * restore them from; without one the live tiles alone suffice. Everything that feeds the
         * static half — lease, tile position, block bake, lamp state — forces a static rebake.
         * The state comparison counts the BASE tiles only: the actor-fitted tile comes and goes
         * with its target and must not drag a static rebake behind it. */
        boolean mapValid = hasDynamic ? saved.staticValid : saved.valid;
        boolean staticDirty = NO_SKIP || !leaseKept || saved.tile != first
            || occluders.builtFrame != saved.occludersFrame || !mapValid
            || (saved.hadDynamic && !saved.staticValid)
            || !saved.sameState(light, baseTiles);

        if (!staticDirty && !hasDynamic && !saved.hadDynamic)
        {
            /* Full skip: the live tiles still hold a correct map — static set-dressing lamps (most
             * of a lighting rig) cost a few distance checks per frame. */
            light.shadowTile = first;
            light.shadowMatrix.set(saved.matrix);
            tilesSkipped++;

            return;
        }

        if (!staticDirty && !hasDynamic)
        {
            /* The dynamic caster LEFT (hadDynamic): one blit hands the live tiles the clean static
             * bake back — its silhouette would otherwise stay burned into the map. Only the faces
             * it was ever drawn into need the restore, and only the texels its bbox ever covered;
             * the rest never stopped being correct. */
            ShadowAtlas.blitStaticToLive(first, baseTiles, saved.lastDynMask, saved.lastDynRects);
            blitted++;
            tilesRendered++;

            finishLight(light, world, occluders, shader, saved, actors, first, id, baseTiles,
                false, 0, null);

            return;
        }

        if (!staticDirty)
        {
            /* Dynamic cadence: this lamp's overlay updates every OTHER frame (parity of frame+id,
             * so a rig alternates instead of updating in lockstep) — EXCEPT lamps with a tracked
             * actor (slots claimed): there the alternating one-frame-stale silhouette reads as
             * the 30 Hz main-layer flicker on the body during fast animation (measured with the
             * second layer OFF). The actor lamps already pay the per-frame fitted bake, and their
             * dynamic overlay rides the partial-tile rects — full-rate here is the cheap case of
             * it. Casters near lamps without actor slots keep the cadence.
             * The live tiles already hold the composite from the last update — keeping them is one
             * frame of silhouette lag, not a freeze, and not the pose-detached shimmer the
             * tick-rate hysteresis showed. Deferred bookkeeping rides last frame's mask/rects:
             * they describe exactly the silhouette the tiles still hold. */
            if (!NO_DYNCADENCE && actors.isEmpty() && ((frame + (int) id) & 1) == 1)
            {
                finishLight(light, world, occluders, shader, saved, actors, first, id,
                    baseTiles, true, saved.lastDynMask, saved.lastDynRects);

                return;
            }

            /* Dynamic in range, static bake fresh: restore the live tiles from the static layer
             * and redraw ONLY the dynamic pass — and only on the faces a caster actually looks at
             * (plus last frame's: they still hold its silhouette). One actor in range no longer
             * costs all six entity/form passes per lamp per frame. The blit's per-face rect is the
             * union of both frames' bboxes: this frame's silhouette lands where the caster is,
             * last frame's restore erases where it WAS. */
            int[] merged = mergeRects(dynRects, dynMask, saved.lastDynRects, saved.lastDynMask,
                baseTiles);

            ShadowAtlas.blitStaticToLive(first, baseTiles, dynMask | saved.lastDynMask, merged);
            blitted++;
            tilesRendered++;

            renderDynamic(world, occluders, views, projections, first, baseTiles, light, far, dynMask,
                dynRects);

            finishLight(light, world, occluders, shader, saved, actors, first, id, baseTiles,
                true, dynMask, dynRects);

            return;
        }

        /* Full static (re)bake — the expensive path, capped per frame so a cold start (the first
         * bake of many lamps, or an atlas growth) spreads over several frames instead of spiking
         * one. Lights with NO valid map at all draw from the separate mandatory pool, so an
         * animated lamp hogging the regular budget cannot starve them. A light that fits neither
         * pool keeps its STALE map when the atlas still holds its pixels (see reuseStaleMap) or
         * renders unshadowed for the frame, and goes first next frame — but is never dropped from
         * the SSBO: dropping read clean on paper ("no shadow, no light"), and in an over-capacity
         * scene the overflow rotated every frame and the dropped lamps blinked (measured, packs
         * and vanilla alike). */
        boolean mandatory = !leaseKept || saved.tile != first || (!saved.valid && !saved.staticValid);

        if (!NO_BUDGET)
        {
            if (mandatory)
            {
                if (mandatoryBudget <= 0)
                {
                    PENDING_IDS.add(id);
                    pending++;
                    reuseStaleMap(light, saved, first, leaseKept);

                    return;
                }

                mandatoryBudget--;
            }
            else
            {
                if (rebakeBudget <= 0)
                {
                    PENDING_IDS.add(id);
                    pending++;
                    reuseStaleMap(light, saved, first, leaseKept);

                    return;
                }

                rebakeBudget--;
            }
        }

        staticRebaked++;
        tilesRendered++;

        /* Bake into the static layer when the live tiles will need restoring from it (a dynamic
         * caster in range) or the layer exists anyway; straight into the live tiles otherwise.
         * ★Never past a 16384² atlas: the layer DOUBLES the atlas' VRAM, and at the 24576 rung
         * (Ultra tiles) that is ~14 GiB — a 12 GiB card silently pages over PCIe and the frame
         * rate falls off a cliff (measured: Ultra 9 fps vs High 52). There the dynamic frame
         * pays the old full redraw instead — expensive, but not swap-expensive. */
        boolean toStaticLayer = (hasDynamic || ShadowAtlas.staticAllocated())
            && ShadowAtlas.TILES_ACROSS * ShadowAtlas.TILE_SIZE <= 16384;

        if (toStaticLayer)
        {
            ShadowAtlas.ensureStatic();

            /* The driver may have refused the double VRAM ask — bake straight into the live tiles
             * then (the pre-split behaviour: a dynamic frame costs a full redraw, still correct). */
            toStaticLayer = ShadowAtlas.staticAllocated();
        }

        if (toStaticLayer)
        {
            for (int i = 0; i < baseTiles; i++)
            {
                ShadowAtlas.beginStaticTile(first + i);
                drawOccluders(occluders, views[i], projections[i], shader);
                renderGlass(occluders, views[i], projections[i], first + i, far, true, true, false);
            }

            ShadowAtlas.blitStaticToLive(first, baseTiles);
        }
        else
        {
            for (int i = 0; i < baseTiles; i++)
            {
                ShadowAtlas.beginTile(first + i);
                drawOccluders(occluders, views[i], projections[i], shader);
                renderGlass(occluders, views[i], projections[i], first + i, far, false, true, false);
            }
        }

        saved.staticValid = toStaticLayer;

        if (hasDynamic)
        {
            renderDynamic(world, occluders, views, projections, first, baseTiles, light, far, dynMask,
                dynRects);
        }

        finishLight(light, world, occluders, shader, saved, actors, first, id, baseTiles,
            hasDynamic, dynMask, dynRects);
    }

    /**
     * The dynamic overlay onto the LIVE tiles, on top of the blitted static bake (no clear):
     * entities, actors and model blocks into the depth, then the glass FORMS into the colour map
     * (they move every frame, unlike the baked world glass). Only faces set in {@code faceMask}
     * are drawn — the mask comes from dynamicFaceMask's per-caster plane tests, and the entity
     * pass culls per caster against the same plane, so a face that IS drawn only pays the casters
     * that actually face it. {@code rects} (nullable) clips each drawn face to the casters'
     * projected bbox: the scissor rides every tile rebind inside the passes, vanilla layer
     * switches included, until the face is done.
     */
    private static void renderDynamic(ClientWorld world, OccluderCache.Entry occluders,
        Matrix4f[] views, Matrix4f[] projections, int first, int baseTiles, Light light, float far,
        int faceMask, int[] rects)
    {
        try
        {
            for (int i = 0; i < baseTiles; i++)
            {
                if ((faceMask & (1 << i)) == 0)
                {
                    continue;
                }

                if (rects != null)
                {
                    System.arraycopy(rects, i * 4, SUB_RECT, 0, 4);
                    ShadowAtlas.setSubRect(SUB_RECT);
                }

                reassertPassState(first + i);
                renderEntities(world, views[i], projections[i], first + i,
                    occluders.originX, occluders.originY, occluders.originZ,
                    light, light.range, tickDelta(), baseTiles == 6 ? FACE_DIR[i] : null);
                reassertPassState(first + i);
                renderGlass(occluders, views[i], projections[i], first + i, far, false, false, true);
            }
        }
        finally
        {
            ShadowAtlas.setSubRect(null);
        }
    }

    /** Per-face bbox slice handed to {@link ShadowAtlas#setSubRect} — reused, never allocated. */
    private static final int[] SUB_RECT = new int[4];

    /** This lamp's per-slot actor targets as candidate indices into the frame's list (-1 = the
     *  slot has no target) — filled by assignActorTargets, read by finishLight. Scratch, like
     *  SUB_RECT: the render thread is the only reader. */
    private static final int[] ACTOR_SLOT_TARGET = new int[Light.ACTOR_SLOTS];

    /**
     * The shared tail of every non-skipped path: assign the tiles, render the actor-fitted maps
     * (every frame — see renderActorTile) and persist the state the dirty checks compare against.
     * {@code stateTiles} is the tile count the dirty checks compare — the BASE tiles on the
     * split path, where the actor tiles' coming and going must not read as a state change.
     * {@code dynRects} (nullable) is this frame's caster bbox table, persisted for next frame's
     * restore blit; null means "no partial data" and stores whole-face rects instead.
     */
    private static void finishLight(Light light, ClientWorld world, OccluderCache.Entry occluders,
        ShaderProgram shader, Persisted saved, java.util.List<double[]> actors, int first,
        long id, int stateTiles, boolean hasDynamic, int dynMask, int[] dynRects)
    {
        light.shadowTile = first;
        java.util.Arrays.fill(light.actorTile, -1);

        if (!actors.isEmpty())
        {
            assignActorTargets(saved, actors, ACTOR_SLOT_TARGET);

            /* Render EVERY frame, matching the on-screen pose: a tick-rate hysteresis saved
             * fill rate but let the body lerp away from the frozen map inside the tick — the
             * mm-detail speckle then crawled across the skin, which read as the very shimmer
             * it was meant to kill (vanilla stays clean precisely because this pass tracks
             * the pose one-to-one). The frame-parity cadence tried the same trade one frame
             * shorter and the second layer still went soft (measured 2026-08-08): the mm
             * map is the one place staleness reads instantly. The dynamic OVERLAY keeps the
             * cadence; these tiles do not. */
            for (int slot = 0; slot < Light.ACTOR_SLOTS; slot++)
            {
                int candidate = ACTOR_SLOT_TARGET[slot];

                if (candidate < 0)
                {
                    continue;
                }

                /* One lease per claimed slot: a full atlas refuses THIS slot alone — the rest
                 * still render, and the refused slot simply stays at -1 ("no map", the main
                 * map's word stands there). */
                int actorLease = ShadowAtlas.lease(actorId(id, slot), 1);

                if (actorLease < 0)
                {
                    continue;
                }

                double[] target = actors.get(candidate);

                renderActorTile(world, occluders, shader, light, saved, slot, target[0], target[1],
                    target[2], actorLease);
            }
        }
        else
        {
            java.util.Arrays.fill(saved.hasTarget, false);
            java.util.Arrays.fill(saved.snapValid, false);
        }

        saved.tile = first;
        saved.matrix.set(light.shadowMatrix);

        for (int slot = 0; slot < Light.ACTOR_SLOTS; slot++)
        {
            saved.actorTile[slot] = light.actorTile[slot];
            saved.actorNear[slot] = light.actorNear[slot];
            saved.actorFar[slot] = light.actorFar[slot];
            saved.actorTan[slot] = light.actorTan[slot];
            saved.actorMatrix[slot].set(light.actorMatrix[slot]);
        }
        saved.occludersFrame = occluders.builtFrame;
        saved.lastDynMask = dynMask;

        /* The bbox table follows the mask: a frame with casters stores its projected rects (or
         * whole-face ones when partial work was off — next frame's restore must not clip a
         * silhouette the whole-face redraw could have painted anywhere). A frame WITHOUT casters
         * keeps the previous table: the restore owed after the last caster leaves reads exactly
         * the texels the last drawn silhouette covered. */
        if (hasDynamic)
        {
            if (dynRects != null)
            {
                System.arraycopy(dynRects, 0, saved.lastDynRects, 0, 24);
            }
            else
            {
                for (int f = 0; f < 6; f++)
                {
                    saved.lastDynRects[f * 4] = 0;
                    saved.lastDynRects[f * 4 + 1] = 0;
                    saved.lastDynRects[f * 4 + 2] = ShadowAtlas.TILE_SIZE;
                    saved.lastDynRects[f * 4 + 3] = ShadowAtlas.TILE_SIZE;
                }
            }
        }

        saved.record(light, stateTiles, hasDynamic);
    }

    /**
     * The up-to-ACTOR_SLOTS nearest actors (film actors, world entities, mannequin model blocks)
     * inside this light's range, as interpolated CENTRE positions {x, y, z, d², class, orderD²},
     * class-first then nearest BY THE ORDER KEY. Each candidate gets a fitted frustum of its own
     * (one tile per slot): a single shared one either FLIPPED between neighbours whenever the lamp
     * moved (mannequin ↔ player) or left the neighbours outside the narrow cone — and the loser
     * silently lost its second-layer shadows. Empty when nobody is in range: no actor, no extra
     * tiles. {@code saved} (nullable — a lamp without a persist entry yet has no incumbents) only
     * steers the order key's incumbent bonus (see orderKey); candidates are narrowed by the group
     * filter exactly the way the render passes narrow casters.
     */
    private static java.util.List<double[]> nearestActors(ClientWorld world, Light light,
        Persisted saved)
    {
        /* Candidacy reaches past the light's range by the body margin: the map exists for the
         * actor's SHELL, and a lamp whose light touches the head or a raised hand but falls a
         * block short of the mid-body centre still wants its second layer there — the old strict
         * range² test took the whole map away in exactly that spot ("свет рядом стоит, а 2 слоя
         * нет"; also the mid-animation dropouts). The main passes give casters the same margin
         * (renderBbsForms' actorReach). */
        double candReach = light.range + 1.2;
        double candReachSq = candReach * candReach;
        java.util.List<double[]> result = new java.util.ArrayList<>(Light.ACTOR_SLOTS + 1);
        float tickDelta = tickDelta();

        java.util.Set<Integer> allowedIds = !light.groupFilter || light.groups.isEmpty()
            ? null : allowedEntityIds(light.groups);

        for (Entity entity : FRAME_ENTITIES)
        {
            /* ★NO shouldRender(light…) here: it caps at (avgSide × 64)² — 64 blocks for a player,
             * LESS for small mobs — so a lamp reaching further silently lost the actor-fitted tile
             * and the second-layer shadows with it (the tester's "тени 2 слоя пропадают при
             * отдалении"). The light's own range is the only honest limit: the client world only
             * holds tracked entities anyway. */
            if (entity.isSpectator()
                || (allowedIds != null && !allowedIds.contains(entity.getId())))
            {
                continue;
            }

            double ex = MathHelper.lerp(tickDelta, entity.prevX, entity.getX());
            double ey = MathHelper.lerp(tickDelta, entity.prevY, entity.getY()) + entity.getHeight() * 0.5;
            double ez = MathHelper.lerp(tickDelta, entity.prevZ, entity.getZ());
            double dx = ex - light.x;
            double dy = ey - light.y;
            double dz = ez - light.z;
            double d2 = dx * dx + dy * dy + dz * dz;

            /* Degenerate-close (the lamp inside the candidate's own mesh, ~0.14² = 0.02): only a
             * truly buried lamp is refused — the 85° clamp and the pack-side feather degrade the
             * close band smoothly (renderActorTile), so a lamp on a stand NEXT to the actor keeps
             * its second layer instead of popping off at 0.4 (the "свет рядом стоит, а 2 слоя
             * нет" report). Let a further candidate win below it. */
            if (d2 > 0.02 && d2 <= candReachSq)
            {
                insertCandidate(result, ex, ey, ez, d2, 1.0, orderKey(saved, d2, ex, ey, ez));
            }
        }

        for (mchorse.bbs_mod.film.BaseFilmController controller :
            com.bbsvfx.vfxlights.client.render.CharacterMask.controllers())
        {
            for (java.util.Map.Entry<String, mchorse.bbs_mod.forms.entities.IEntity> entry
                : controller.getEntities().entrySet())
            {
                mchorse.bbs_mod.forms.entities.IEntity actor = entry.getValue();

                if (actor == null || actor.getForm() == null || (light.groupFilter
                    && !light.groups.isEmpty()
                    && !com.bbsvfx.vfxlights.client.light.ActorCategories.allowed(light.groups,
                        com.bbsvfx.vfxlights.client.light.ActorCategories.categoryOf(
                            controller, entry.getKey()))))
                {
                    continue;
                }

                /* Aim where the form is RENDERED: renderBbsForms applies the form's transform on
                 * top of the entity position, so a form offset by its transform (x=2 and the
                 * shadow vanishes) walks out of the tightly fitted cone. translate only — rotate
                 * and scale move the pivot by less than the fit radius, the cone has slack for it.
                 * The film's own sub-tick time (0 on pause — see renderBbsForms) keeps the aim
                 * glued to the same pose the tile renders. */
                float transition = ((com.bbsvfx.vfxlights.mixin.client.BaseFilmControllerAccessor)
                    controller).vfxlights$getTransition(actor, tickDelta);
                mchorse.bbs_mod.utils.pose.Transform actorTransform = actor.getForm().transform.get();
                double ex = MathHelper.lerp(transition, actor.getPrevX(), actor.getX())
                    + actorTransform.translate.x;
                /* Actors report feet; the frustum aims at mid-body (~0.9 up a humanoid). */
                double ey = MathHelper.lerp(transition, actor.getPrevY(), actor.getY()) + 0.9
                    + actorTransform.translate.y;
                double ez = MathHelper.lerp(transition, actor.getPrevZ(), actor.getZ())
                    + actorTransform.translate.z;
                double dx = ex - light.x;
                double dy = ey - light.y;
                double dz = ez - light.z;
                double d2 = dx * dx + dy * dy + dz * dz;

                if (d2 > 0.02 && d2 <= candReachSq)
                {
                    insertCandidate(result, ex, ey, ez, d2, 0.0, orderKey(saved, d2, ex, ey, ez));
                }
            }
        }

        /* Model blocks: a mannequin form is as much of an actor as a filmed one. Without these the
         * fitted map only ever targeted the player, and a character statue got its second-layer
         * shadows only while the player stood inside the narrow frustum with it. Same chunk walk
         * hasDynamicCaster does; blocks are lit regardless of the group filter, so they are always
         * eligible. The form pivot sits at the block — aim at mid-body. */
        int minChunkX = ((int) Math.floor(light.x - light.range)) >> 4;
        int maxChunkX = ((int) Math.floor(light.x + light.range)) >> 4;
        int minChunkZ = ((int) Math.floor(light.z - light.range)) >> 4;
        int maxChunkZ = ((int) Math.floor(light.z + light.range)) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
        {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
            {
                net.minecraft.world.chunk.WorldChunk chunk =
                    world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);

                if (chunk == null)
                {
                    continue;
                }

                for (net.minecraft.block.entity.BlockEntity blockEntity : chunk.getBlockEntities().values())
                {
                    if (!(blockEntity instanceof mchorse.bbs_mod.blocks.entities.ModelBlockEntity modelBlock))
                    {
                        continue;
                    }

                    mchorse.bbs_mod.blocks.entities.ModelProperties properties = modelBlock.getProperties();

                    if (properties == null || properties.getForm() == null || !properties.isEnabled())
                    {
                        continue;
                    }

                    net.minecraft.util.math.BlockPos pos = modelBlock.getPos();

                    /* Not an actor: the block this lamp's own form lives in, and any model block
                     * that IS a lamp. The nearest block to a light is usually its own fixture —
                     * fitting the frustum on it pointed the cone at the lamp's housing, and the
                     * "weird blob" was the fixture shadowing itself through its own actor map. */
                    if (pos.equals(light.sourceBlock)
                        || properties.getForm() instanceof com.bbsvfx.vfxlights.forms.LightForm)
                    {
                        continue;
                    }

                    /* Same render-side rule as the actors: the renderer applies the block's
                     * transform after the block offset, so the fit point must include it —
                     * a transformed form (x=2) otherwise renders outside the fitted cone and
                     * the actor map comes back empty (the vanishing second-layer shadows). */
                    mchorse.bbs_mod.utils.pose.Transform blockTransform = properties.getTransform();
                    double ex = pos.getX() + 0.5 + blockTransform.translate.x;
                    double ey = pos.getY() + 0.9 + blockTransform.translate.y;
                    double ez = pos.getZ() + 0.5 + blockTransform.translate.z;
                    double dx = ex - light.x;
                    double dy = ey - light.y;
                    double dz = ez - light.z;
                    double d2 = dx * dx + dy * dy + dz * dz;

                    if (d2 > 0.02 && d2 <= candReachSq)
                    {
                        insertCandidate(result, ex, ey, ez, d2, 0.0, orderKey(saved, d2, ex, ey, ez));
                    }
                }
            }
        }

        return result;
    }

    /** Insert {x, y, z, d², class, orderD²} keeping the list sorted (class first, then nearest by
     * the ORDER key) and capped at the slot count. Class 0 = film actors and model-block
     * mannequins — the subjects a lamp is placed for; class 1 = plain world entities (the player
     * is usually just the cameraman). The TRUE d² rides along untouched at [3] for whatever a
     * reader needs it for; the incumbent hysteresis lives only in the order key (see orderKey). */
    private static void insertCandidate(java.util.List<double[]> list, double x, double y, double z,
        double d2, double cls, double orderD2)
    {
        int i = 0;

        while (i < list.size() && (list.get(i)[4] < cls || (list.get(i)[4] == cls && list.get(i)[5] <= orderD2)))
        {
            i++;
        }

        if (i < Light.ACTOR_SLOTS)
        {
            list.add(i, new double[] { x, y, z, d2, cls, orderD2 });

            if (list.size() > Light.ACTOR_SLOTS)
            {
                list.remove(list.size() - 1);
            }
        }
    }

    /**
     * A candidate's ORDERING key: its true d² — or 0.8× of it when the candidate sits where one
     * of the saved slots' owners stood (matched by proximity, 0.75 blocks — the actor may have
     * moved). With one more candidate than slots, two actors at similar distances crossed d²
     * frame to frame and the list's last place flip-flopped between them: each got its second
     * layer every OTHER frame, the 30 Hz flicker the tester saw on two "random" models. The
     * incumbent bonus means a challenger must be CLEARLY nearer to push an owner out of the
     * capped list. 0.8 and not 0.95: wide enough that idle-pose sway and sub-tick drift never
     * cross it, narrow enough that a challenger a genuine step closer still wins — the same
     * hysteresis the slots themselves get in assignActorTargets. ORDERING ONLY: the d² stored at
     * [3] stays the true one, and an owner past candReach never enters the list at all — the
     * bonus is hysteresis, not immortality.
     */
    private static double orderKey(Persisted saved, double d2, double x, double y, double z)
    {
        if (saved != null)
        {
            for (int s = 0; s < Light.ACTOR_SLOTS; s++)
            {
                if (!saved.hasTarget[s])
                {
                    continue;
                }

                double dx = x - saved.targetX[s];
                double dy = y - saved.targetY[s];
                double dz = z - saved.targetZ[s];

                if (dx * dx + dy * dy + dz * dz <= 0.5625)
                {
                    return d2 * 0.8;
                }
            }
        }

        return d2;
    }

    /**
     * The sticky actor targets for the fitted frustums, one slot per actor — in TWO passes, so an
     * owner never loses its slot to a same-distance challenger:
     *
     * <p>Pass 1 — OWNERS keep their slots: a slot's owner still in range (matched by proximity,
     * 0.75 blocks — it may have moved) re-claims ITS slot before any free-slot grab and adopts
     * the candidate's fresh position. Pass 2 — the candidates nobody claimed take the remaining
     * free slots in list order (class-first, nearest-first), each becoming the owner of the slot
     * it takes. A slot nobody claims this frame loses its owner. The previous single pass walked
     * the candidate list and handed the first free slot to whoever sorted first — with one more
     * candidate than slots, two actors at similar distances crossed d² frame to frame and the
     * last slot alternated between them: each got its second layer every OTHER frame, the 30 Hz
     * flicker the tester saw on two "random" models. (The list's own boundary gets the matching
     * hysteresis — see orderKey.) {@code out} receives the claiming candidate's index per slot,
     * -1 where the slot has no target.
     */
    private static void assignActorTargets(Persisted saved, java.util.List<double[]> actors,
        int[] out)
    {
        java.util.Arrays.fill(out, -1);

        /* Pass 1: owners re-claim their own slots. */
        for (int s = 0; s < Light.ACTOR_SLOTS; s++)
        {
            if (!saved.hasTarget[s])
            {
                continue;
            }

            for (int c = 0; c < actors.size(); c++)
            {
                /* A candidate already serving an earlier slot is not up for another. */
                boolean taken = false;

                for (int earlier = 0; earlier < s; earlier++)
                {
                    if (out[earlier] == c)
                    {
                        taken = true;

                        break;
                    }
                }

                if (taken)
                {
                    continue;
                }

                double[] candidate = actors.get(c);
                double dx = candidate[0] - saved.targetX[s];
                double dy = candidate[1] - saved.targetY[s];
                double dz = candidate[2] - saved.targetZ[s];

                if (dx * dx + dy * dy + dz * dz <= 0.5625)
                {
                    /* The slot's owner is back in range — it keeps its frustum. */
                    out[s] = c;
                    saved.targetX[s] = candidate[0];
                    saved.targetY[s] = candidate[1];
                    saved.targetZ[s] = candidate[2];

                    break;
                }
            }
        }

        /* Pass 2: free slots adopt the remaining candidates, best first. */
        for (int c = 0; c < actors.size(); c++)
        {
            boolean claimed = false;

            for (int s = 0; s < Light.ACTOR_SLOTS; s++)
            {
                if (out[s] == c)
                {
                    claimed = true;

                    break;
                }
            }

            if (claimed)
            {
                continue;
            }

            int slot = -1;

            for (int s = 0; s < Light.ACTOR_SLOTS; s++)
            {
                if (out[s] == -1)
                {
                    slot = s;

                    break;
                }
            }

            if (slot < 0)
            {
                /* More candidates than slots: the rest go without a fitted frustum this
                 * frame — and, thanks to pass 1 and the order-key bonus, it is the SAME
                 * challenger every frame instead of a coin flip. */
                break;
            }

            double[] candidate = actors.get(c);

            out[slot] = c;
            saved.hasTarget[slot] = true;
            saved.targetX[slot] = candidate[0];
            saved.targetY[slot] = candidate[1];
            saved.targetZ[slot] = candidate[2];
            /* A new owner takes the slot: the held fit window belongs to the previous actor. */
            saved.snapValid[slot] = false;
        }

        for (int s = 0; s < Light.ACTOR_SLOTS; s++)
        {
            if (out[s] == -1)
            {
                saved.hasTarget[s] = false;
                saved.snapValid[s] = false;
            }
        }
    }

    /** Radius the actor frustum is fitted to — a standing humanoid plus hands on a swing. */
    private static final float ACTOR_FIT_RADIUS = 1.6F;

    /**
     * One actor-fitted shadow map (Blender-look): a narrow frustum hugging this slot's actor, so
     * the tile's texels land millimetres apart and the second skin layer's shadow survives on the
     * body — the detail the light-range frustum blurs into nothing. Occluders and entities are
     * drawn through the same passes as the main tiles; the glass tint pass is skipped because the
     * shaders only read this map's DEPTH (the darker-of-all-maps combine). The matrix, near and
     * far land on the light's slot for the backends; the tile is assigned by the caller.
     */
    private static void renderActorTile(ClientWorld world, OccluderCache.Entry occluders,
        ShaderProgram shader, Light light, Persisted saved, int slot, double ax, double ay, double az,
        int tile)
    {
        double dx = ax - light.x;
        double dy = ay - light.y;
        double dz = az - light.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < ACTOR_FIT_RADIUS * 0.1F)
        {
            /* Only a lamp truly buried inside the actor's own mesh is rejected (bone-attached
             * lamps, the "линии" garbage): at a tenth of the fit radius the body straddles the
             * near plane and the map is inside-out. Everything further out gets a map — the cone
             * is clamped to 85° below, the near plane clips what sits too close, and the pack-side
             * UV feather hands the uncovered edge back to the main map smoothly. The 0.25 cut used
             * to pop the second layer off for a lamp standing NEXT to the actor (0.4 blocks from
             * the fit point is a lamp on a stand, not inside the mesh). The main map shades the
             * actor wherever this still refuses. */
            return;
        }

        /* Depth window tight on the actor. The near floor is NOT the main map's 0.05: with a
         * close lamp it quantised DEPTH16 at the body to ~1.5mm, and the iso-quantum contours
         * read as fine lines on the skin. 0.3 keeps the quantum at ~0.3mm while still catching
         * anything standing just before the actor. The fit radius itself rides the second
         * layer's own preset: High tightens the cone (×~1.7 density on the body) and lets the
         * feather hand the rim back to the main map. */
        float fit = ACTOR_FIT_RADIUS * com.bbsvfx.vfxlights.VfxLightsAddon.actorFitScale();

        /* DEADZONE-DRAG on the fitted frustum. Every number below derives from the live actor
         * centre, so idle animation and walk bobbing breathed them ALL every frame — near/far/fov
         * (the texel size) and the aim — and at this map's millimetre density the breathing read
         * as the second layer CRAWLING over the skin ("тени плывут"). The texel-snap that fixed
         * the crawl made the window step instead, and the hysteresis that replaced it still had
         * ONE visible re-fit step per deadzone exit ("частота снизилась, но надо убить их
         * полностью"). So: no snap, no re-fit. While the target stays within a quarter of the
         * fit radius of the held aim (and the distance within 15% of the held one), the window
         * is BIT-FROZEN — idle sway reads exactly as still as the snap made it. Past the
         * boundary the window is dragged 1:1 with the target (the target stays pinned to the
         * deadzone edge): position-continuous in both directions, so there is no step to see —
         * not on exit (the drag starts at exactly the target's own speed) and not on settle
         * (it freezes wherever the motion stops). A slot that just changed owners adopts the
         * live pose outright (its held window belongs to the previous actor). The map still
         * RENDERS every frame (finishLight's rule) — the pose tracks one-to-one, only the
         * camera deadzones. */
        if (!saved.snapValid[slot])
        {
            saved.snapX[slot] = ax;
            saved.snapY[slot] = ay;
            saved.snapZ[slot] = az;
            saved.snapDist[slot] = dist;
            saved.snapValid[slot] = true;
        }
        else
        {
            double hx = ax - saved.snapX[slot];
            double hy = ay - saved.snapY[slot];
            double hz = az - saved.snapZ[slot];
            double drift = Math.sqrt(hx * hx + hy * hy + hz * hz);
            double deadzone = 0.25D * fit;

            if (drift > deadzone)
            {
                double pull = (drift - deadzone) / drift;

                saved.snapX[slot] += hx * pull;
                saved.snapY[slot] += hy * pull;
                saved.snapZ[slot] += hz * pull;
            }

            double dDist = dist - saved.snapDist[slot];
            double distDeadzone = 0.15D * fit;

            if (Math.abs(dDist) > distDeadzone)
            {
                saved.snapDist[slot] += Math.signum(dDist) * (Math.abs(dDist) - distDeadzone);
            }
        }

        double distQ = saved.snapDist[slot];

        float near = Math.max(0.3F, (float) distQ - fit * 1.5F);
        /* The far plane carries the lamp's RANGE (capped at 8 fit radii out): the second layer
         * shadows up to where the light itself dies, like the main map — the old dist+2r window
         * silently killed the actor's shadow two blocks past the body ("отодвинул свет — тени
         * пропали"). A TINY range keeps the old tight window, so the near/far never inverts. */
        float far = Math.max((float) distQ + fit * 2F,
            Math.min(light.range, (float) distQ + fit * 8F));
        /* Angular margin over the fit: 1.25, not 1.15 — the cone must hold the WHOLE swing
         * envelope, not the standing pose. A humanoid mid-swing reaches ±1.5-1.9 blocks off its
         * centre; 1.15 covered 1.84 (Medium) but only 1.38 under the High preset's 0.75 fit —
         * every wide pose or fast swing walked a limb OUT of the frustum and back, and the
         * second layer's detail popped in and out at the cone edge (the "тени мерцают при
         * быстрых движениях" report). 2.0 / 1.5 blocks of coverage after the change; the ~15%
         * texel density it costs is the price of the map not having a visible boundary at all. */
        float half = (float) Math.atan(fit / distQ) * 1.25F;

        /* Cap the cone at 85°, not 120°: past ~85° the perspective goes extreme and the uncovered
         * rim feathers out to the main map — a graceful degrade instead of a broken projection. */
        half = Math.max((float) Math.toRadians(5D), Math.min((float) Math.toRadians(85D), half));

        double qx = saved.snapX[slot];
        double qy = saved.snapY[slot];
        double qz = saved.snapZ[slot];

        Matrix4f projection = new Matrix4f().perspective(half * 2F, 1F, near, far);

        Vector3f dir = new Vector3f((float) (qx - light.x), (float) (qy - light.y), (float) (qz - light.z)).normalize();
        Vector3f up = Math.abs(dir.y) > 0.99F ? new Vector3f(0F, 0F, 1F) : new Vector3f(0F, 1F, 0F);
        float lx = (float) (light.x - occluders.originX);
        float ly = (float) (light.y - occluders.originY);
        float lz = (float) (light.z - occluders.originZ);

        Matrix4f view = new Matrix4f().lookAt(
            lx, ly, lz,
            lx + dir.x, ly + dir.y, lz + dir.z,
            up.x, up.y, up.z);

        ShadowAtlas.setSubRect(null);
        ShadowAtlas.beginTile(tile);

        /* Entities only: the narrow cone's job is the actor's own silhouette at millimetre depth,
         * and a static blocker's shadow in the cone is the MAIN map's job — the combine is
         * min(shade, actor), and the main map covers every static occluder this frustum sees.
         * Drawing the whole baked buffer here cost thousands of box vertices through a 1.6-block
         * cone per lamp per frame (the mod's top vertex cost on a set) for texels the combine
         * never lets win. -Dvfxlights.shadow.actorstatic restores the old behaviour. */
        if (ACTOR_STATIC)
        {
            drawOccluders(occluders, view, projection, shader);
        }

        /* No polygon offset in the narrow frustum (IterationRP's rule: zero render-side offset for
         * small geometry): at this density the ~1mm sink eats half a ~2mm fake second layer. */
        suppressActorOffset = true;

        try
        {
            renderEntities(world, view, projection, tile,
                occluders.originX, occluders.originY, occluders.originZ,
                light, light.range, tickDelta(), null);
        }
        finally
        {
            suppressActorOffset = false;
            applyActorDepthOffset();
        }

        reassertPassState(tile);

        light.actorTile[slot] = tile;
        light.actorNear[slot] = near;
        light.actorFar[slot] = far;
        light.actorTan[slot] = (float) Math.tan(half);
        light.actorMatrix[slot].set(projection).mul(view).translate(
            -occluders.originX, -occluders.originY, -occluders.originZ);

        /* ★TEMP diagnostic (-Dvfxlights.shadow.actordebug): one line per actor-tile render —
         * frames WITHOUT a line for a claimed slot are the deferrals/skips the flicker hunt is
         * after; dist/distQ show the snap steps; target shows the aim's per-frame travel. */
        if (ACTOR_DEBUG)
        {
            org.slf4j.LoggerFactory.getLogger("vfxlights").info(
                "[vfxlights] actordbg f={} slot={} tile={} dist={} distQ={} tgt=[{}, {}, {}]",
                frame, slot, tile,
                String.format("%.3f", dist), String.format("%.3f", distQ),
                String.format("%.3f", ax), String.format("%.3f", ay), String.format("%.3f", az));
        }

        if (DEBUG)
        {
            long now = System.currentTimeMillis();

            if (now - lastActorDebug > 1000L)
            {
                lastActorDebug = now;
                org.slf4j.LoggerFactory.getLogger("vfxlights").info(
                    "actor tile {} (slot {}): light [{}, {}, {}] target [{}, {}, {}] dist={} near={} far={}",
                    tile, slot, light.x, light.y, light.z, ax, ay, az,
                    String.format("%.2f", dist), String.format("%.2f", near), String.format("%.2f", far));
            }
        }
    }

    private static long lastActorDebug;

    /**
     * Whether anything ANIMATED can cast into this light's map right now: a world entity, a film
     * actor, a glass form (repainted per frame into the colour map) or a model block (its form can
     * carry keyframed transforms) inside the light's range. Distance checks only — the honest
     * signal for the dirty-skip, at a tiny fraction of a render pass. Conservative on purpose:
     * a positive here merely re-runs the redraw the pass always did.
     */
    /**
     * The dynamic-caster probe, as a 6-bit FACE mask: bit i is set when an animated caster (world
     * entity, film actor, glass form, model block) sits in front of cube face i's plane, with a
     * per-caster margin for its size. Faces cover ~93° (the 1.03 seam overlap), so the plane test
     * is conservative. The mask lets a dynamic frame blit and redraw ONLY the faces a caster
     * actually looks at — a single actor in range used to cost all six entity/form passes per lamp
     * per frame; with a crowd of lamps that was the frame. Bit 0 doubles as "the one tile" for a
     * spot, which takes no cube path.
     */
    /** This frame's dynamic casters as world-space spheres (x, y, z, radius — the margin the face
     *  test used), recorded by dynamicFaceMask and projected into per-face texel rects afterwards:
     *  a moving caster dirties only the texels its own silhouette projects to, so the restore blit
     *  and the overlay redraw follow that bbox instead of the whole face. Overflow is conservative
     *  — more casters than the scratch holds falls back to whole-face work for the frame. */
    private static final int MAX_CASTERS = 32;
    private static final double[][] casters = new double[MAX_CASTERS][4];
    private static int casterCount;
    private static boolean casterOverflow;

    private static void recordCaster(double x, double y, double z, double margin)
    {
        if (casterCount >= MAX_CASTERS)
        {
            casterOverflow = true;

            return;
        }

        casters[casterCount][0] = x;
        casters[casterCount][1] = y;
        casters[casterCount][2] = z;
        casters[casterCount][3] = margin;
        casterCount++;
    }

    /** Tile texels of padding around a projected caster bbox: pose interpolation can carry a caster
     *  a few centimetres between the frame its rect is drawn and the frame the restore blit reads. */
    private static final int RECT_PAD = 4;

    /** JOML scratch for the bbox projection — the hot path allocates nothing. */
    private static final org.joml.Vector4f rectCorner = new org.joml.Vector4f();

    /**
     * The dynamic casters' projected silhouette bbox per face — tile-local texels {x0,y0,x1,y1} ×
     * baseTiles — or null when partial work is off this frame (kill-switch, caster overflow, no
     * casters): whole-face behaviour, the pre-partial path. Only texels inside the bbox can hold a
     * moving silhouette, so the restore blit and the overlay redraw clip to it; a caster across the
     * near plane smears conservatively to the whole face, and a bbox covering ≥15/16 of the tile is
     * promoted to full to stop paying the clipping overhead where it saves nothing.
     */
    private static int[] computeDynRects(Matrix4f[] views, Matrix4f[] projections,
        OccluderCache.Entry occluders, int baseTiles)
    {
        if (NO_PARTIAL || casterOverflow || casterCount == 0)
        {
            return null;
        }

        int tile = ShadowAtlas.TILE_SIZE;
        int[] rects = new int[24];

        for (int f = 0; f < 6; f++)
        {
            rects[f * 4 + 2] = tile;
            rects[f * 4 + 3] = tile;
        }

        for (int f = 0; f < baseTiles; f++)
        {
            float minU = tile;
            float minV = tile;
            float maxU = 0;
            float maxV = 0;
            boolean full = false;

            for (int c = 0; c < casterCount && !full; c++)
            {
                double cx = casters[c][0] - occluders.originX;
                double cy = casters[c][1] - occluders.originY;
                double cz = casters[c][2] - occluders.originZ;
                double r = casters[c][3];

                for (int corner = 0; corner < 8; corner++)
                {
                    rectCorner.set(
                        (float) (cx + ((corner & 1) == 0 ? -r : r)),
                        (float) (cy + ((corner & 2) == 0 ? -r : r)),
                        (float) (cz + ((corner & 4) == 0 ? -r : r)),
                        1F);
                    views[f].transform(rectCorner);
                    projections[f].transform(rectCorner);

                    if (rectCorner.w < 0.001F)
                    {
                        /* Across or behind the near plane — the bbox smears the whole face. */
                        full = true;

                        break;
                    }

                    float u = (rectCorner.x / rectCorner.w * 0.5F + 0.5F) * tile;
                    float v = (rectCorner.y / rectCorner.w * 0.5F + 0.5F) * tile;

                    minU = Math.min(minU, u);
                    minV = Math.min(minV, v);
                    maxU = Math.max(maxU, u);
                    maxV = Math.max(maxV, v);
                }
            }

            if (!full)
            {
                int x0 = Math.max(0, (int) Math.floor(minU) - RECT_PAD);
                int y0 = Math.max(0, (int) Math.floor(minV) - RECT_PAD);
                int x1 = Math.min(tile, (int) Math.ceil(maxU) + RECT_PAD);
                int y1 = Math.min(tile, (int) Math.ceil(maxV) + RECT_PAD);

                if ((long) (x1 - x0) * (y1 - y0) < (long) tile * tile * 15 / 16)
                {
                    rects[f * 4] = x0;
                    rects[f * 4 + 1] = y0;
                    rects[f * 4 + 2] = x1;
                    rects[f * 4 + 3] = y1;
                }
            }
        }

        return rects;
    }

    /**
     * The union of two frames' per-face bboxes over their combined face mask, for the restore blit:
     * this frame's rect covers where the silhouette lands now, last frame's rect erases where it
     * was. Null in means "no partial data" (kill-switch, overflow) — null out, whole-face blit.
     */
    private static int[] mergeRects(int[] cur, int curMask, int[] saved, int savedMask, int baseTiles)
    {
        if (cur == null)
        {
            return null;
        }

        int tile = ShadowAtlas.TILE_SIZE;
        int[] out = new int[24];

        for (int f = 0; f < 6; f++)
        {
            out[f * 4 + 2] = tile;
            out[f * 4 + 3] = tile;
        }

        for (int f = 0; f < baseTiles; f++)
        {
            boolean hasCur = (curMask & (1 << f)) != 0;
            boolean hasSaved = saved != null && (savedMask & (1 << f)) != 0;

            if (hasCur && hasSaved)
            {
                out[f * 4] = Math.min(cur[f * 4], saved[f * 4]);
                out[f * 4 + 1] = Math.min(cur[f * 4 + 1], saved[f * 4 + 1]);
                out[f * 4 + 2] = Math.max(cur[f * 4 + 2], saved[f * 4 + 2]);
                out[f * 4 + 3] = Math.max(cur[f * 4 + 3], saved[f * 4 + 3]);
            }
            else if (hasCur)
            {
                System.arraycopy(cur, f * 4, out, f * 4, 4);
            }
            else if (hasSaved)
            {
                System.arraycopy(saved, f * 4, out, f * 4, 4);
            }
        }

        return out;
    }

    private static int dynamicFaceMask(ClientWorld world, Light light)
    {
        double lightX = light.x;
        double lightY = light.y;
        double lightZ = light.z;
        float range = light.range;
        double rangeSq = (double) range * range;
        int mask = 0;

        casterCount = 0;
        casterOverflow = false;

        for (Entity entity : FRAME_ENTITIES)
        {
            if (entity.isSpectator())
            {
                continue;
            }

            double dx = entity.getX() - lightX;
            double dy = entity.getY() - lightY;
            double dz = entity.getZ() - lightZ;

            if (dx * dx + dy * dy + dz * dz <= rangeSq)
            {
                double margin = Math.max(entity.getWidth(), entity.getHeight()) + 0.5;

                mask |= faceBits(dx, dy, dz, margin);
                recordCaster(entity.getX(), entity.getY(), entity.getZ(), margin);
            }
        }

        for (mchorse.bbs_mod.film.BaseFilmController controller :
            com.bbsvfx.vfxlights.client.render.CharacterMask.controllers())
        {
            for (mchorse.bbs_mod.forms.entities.IEntity actor : controller.getEntities().values())
            {
                if (actor == null || actor.getForm() == null)
                {
                    continue;
                }

                double dx = actor.getX() - lightX;
                double dy = actor.getY() - lightY;
                double dz = actor.getZ() - lightZ;

                /* Range + margin: the sway zone reaches past its host (see renderBbsForms). */
                double actorMargin = casterMargin(actor.getForm(), 1.6);
                double actorReach = range + actorMargin;

                if (dx * dx + dy * dy + dz * dz <= actorReach * actorReach)
                {
                    mask |= faceBits(dx, dy, dz, actorMargin);
                    recordCaster(actor.getX(), actor.getY(), actor.getZ(), actorMargin);
                }
            }
        }

        /* Glass forms redraw into the colour map each frame; one near the light forces the pass. */
        double glassReach = range + 2D;
        double glassReachSq = glassReach * glassReach;

        for (com.bbsvfx.vfxlights.client.light.GlassFormCollector.GlassBox box :
            com.bbsvfx.vfxlights.client.light.GlassFormCollector.getBoxes())
        {
            double dx = box.matrix.m30() - lightX;
            double dy = box.matrix.m31() - lightY;
            double dz = box.matrix.m32() - lightZ;

            if (dx * dx + dy * dy + dz * dz <= glassReachSq)
            {
                mask |= faceBits(dx, dy, dz, 1.5);
                recordCaster(box.matrix.m30(), box.matrix.m31(), box.matrix.m32(), 1.5);
            }
        }

        /* Model blocks: the chunk walk is the same one the render pass does, minus all the drawing
         * — and it is CACHED per frame now, shared with that pass (see modelBlocksInChunk). */
        int minChunkX = ((int) Math.floor(lightX - range)) >> 4;
        int maxChunkX = ((int) Math.floor(lightX + range)) >> 4;
        int minChunkZ = ((int) Math.floor(lightZ - range)) >> 4;
        int maxChunkZ = ((int) Math.floor(lightZ + range)) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
        {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
            {
                for (ModelBlockEntry entry : modelBlocksInChunk(world, chunkX, chunkZ))
                {
                    double dx = entry.x - lightX;
                    double dy = entry.y - lightY;
                    double dz = entry.z - lightZ;

                    /* A form can extend past its block both ways (keyframed transforms); a wind
                     * form's sway zone reaches its scan radius. */
                    double blockMargin = casterMargin(entry.props.getForm(), 2.0);
                    double blockReach = range + blockMargin;

                    if (dx * dx + dy * dy + dz * dz <= blockReach * blockReach)
                    {
                        mask |= faceBits(dx, dy, dz, blockMargin);
                        recordCaster(entry.x, entry.y, entry.z, blockMargin);
                    }
                }
            }
        }

        return mask;
    }

    /** The per-face plane test: a caster within {@code margin} behind the face's plane can still
     *  reach past the frustum's edge (its own extent, the 93° overlap), so it counts as inside. */
    private static int faceBits(double dx, double dy, double dz, double margin)
    {
        int bits = 0;

        for (int f = 0; f < 6; f++)
        {
            Vector3f dir = FACE_DIR[f];

            if (dx * dir.x + dy * dir.y + dz * dir.z > -margin)
            {
                bits |= 1 << f;
            }
        }

        return bits;
    }

    /** Same test for one direction — the per-caster cull inside a face's render pass. */
    private static boolean facesDir(double dx, double dy, double dz, double margin, Vector3f dir)
    {
        return dir == null || dx * dir.x + dy * dir.y + dz * dir.z > -margin;
    }

    /**
     * A wind form's sway proxies span its whole scan zone, so with the default caster margin (a
     * couple of blocks) the face mask covered only the faces toward its host — a lamp beside the
     * zone drew the proxies into one or two cube faces and the trees cast shadows nowhere else
     * (a lamp HIGH above got them via the bottom face). The zone's radius is the honest margin.
     */
    private static double casterMargin(mchorse.bbs_mod.forms.forms.Form form, double fallback)
    {
        if (form instanceof com.bbsvfx.bbsvfx.forms.WindForm wind && wind.sway.get())
        {
            return Math.max(fallback, wind.scanRadius.get());
        }

        return fallback;
    }

    /* ── Frame-scoped model-block cache. Both the dynamic probe (dynamicFaceMask) and the render
     * pass (renderBbsForms) walk the same chunks — once per lamp per FACE, ~42 chunk-map scans a
     * frame in a rig of a dozen lamps, and block-entity maps are not cheap to iterate. A chunk is
     * scanned once per FRAME now; the entry's world position (block centre + the form's transform
     * translate, the same value the culls measured) rides along so callers don't re-derive it. ── */
    private static final java.util.Map<Long, java.util.List<ModelBlockEntry>> MB_CACHE =
        new java.util.HashMap<>();
    private static int mbCacheFrame = -1;

    private static final class ModelBlockEntry
    {
        final mchorse.bbs_mod.blocks.entities.ModelBlockEntity block;
        final mchorse.bbs_mod.blocks.entities.ModelProperties props;
        final double x;
        final double y;
        final double z;

        ModelBlockEntry(mchorse.bbs_mod.blocks.entities.ModelBlockEntity block,
            mchorse.bbs_mod.blocks.entities.ModelProperties props, double x, double y, double z)
        {
            this.block = block;
            this.props = props;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static java.util.List<ModelBlockEntry> modelBlocksInChunk(ClientWorld world,
        int chunkX, int chunkZ)
    {
        if (mbCacheFrame != frame)
        {
            MB_CACHE.clear();
            mbCacheFrame = frame;
        }

        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

        return MB_CACHE.computeIfAbsent(key, (k) -> scanModelBlocks(world, chunkX, chunkZ));
    }

    private static java.util.List<ModelBlockEntry> scanModelBlocks(ClientWorld world,
        int chunkX, int chunkZ)
    {
        net.minecraft.world.chunk.WorldChunk chunk =
            world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);

        if (chunk == null)
        {
            return java.util.Collections.emptyList();
        }

        java.util.List<ModelBlockEntry> out = new java.util.ArrayList<>();

        for (net.minecraft.block.entity.BlockEntity blockEntity : chunk.getBlockEntities().values())
        {
            if (!(blockEntity instanceof mchorse.bbs_mod.blocks.entities.ModelBlockEntity modelBlock))
            {
                continue;
            }

            mchorse.bbs_mod.blocks.entities.ModelProperties properties = modelBlock.getProperties();

            if (properties == null || properties.getForm() == null || !properties.isEnabled())
            {
                continue;
            }

            net.minecraft.util.math.BlockPos pos = modelBlock.getPos();
            mchorse.bbs_mod.utils.pose.Transform t = properties.getTransform();

            out.add(new ModelBlockEntry(modelBlock, properties,
                pos.getX() + 0.5 + t.translate.x, pos.getY() + 0.5 + t.translate.y,
                pos.getZ() + 0.5 + t.translate.z));
        }

        return out;
    }

    /** Scratch buffer for the per-frame glass FORM boxes (world glass is baked in the cache). */
    private static VertexBuffer glassFormScratch;

    /**
     * The colour pass: glass casters into the atlas' COLOUR map, after the tile's depth is complete.
     *
     * <p>Depth test on (a pane hidden behind a wall tints nothing), depth write off, and two blends at
     * once: RGB multiplies (stacked panes compound their tints) while alpha takes the MINIMUM (the
     * NEAREST pane's depth wins — that is the depth shading compares receivers against).</p>
     *
     * <p>Two switches for the static/live split: {@code staticLayer} aims the pass at the static
     * atlas instead of the live one, and {@code includeWorld}/{@code includeForms} pick the caster
     * set — baked world glass is static, glass FORMS move every frame and belong to the dynamic
     * overlay.</p>
     */
    private static void renderGlass(OccluderCache.Entry occluders, Matrix4f view, Matrix4f projection,
        int tile, float far, boolean staticLayer, boolean includeWorld, boolean includeForms)
    {
        boolean world = includeWorld && !occluders.glassEmpty && occluders.glassBuffer != null;
        java.util.List<com.bbsvfx.vfxlights.client.light.GlassFormCollector.GlassBox> forms = includeForms
            ? com.bbsvfx.vfxlights.client.light.GlassFormCollector.getBoxes()
            : java.util.Collections.emptyList();

        if ((!world && forms.isEmpty()) || glassShader == null)
        {
            return;
        }

        /* Alpha carries LINEAR view-axis depth normalized by the range — the lookups multiply back. */
        if (glassShader.getUniform("Range") != null)
        {
            glassShader.getUniform("Range").set(far);
        }

        if (staticLayer)
        {
            ShadowAtlas.rebindStaticTile(tile);
        }
        else
        {
            ShadowAtlas.rebindTile(tile);
        }
        GL11.glDrawBuffer(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0);
        RenderSystem.colorMask(true, true, true, true);
        /* NO depth test, deliberately. Rays blocked by opaque geometry are extinguished by the DEPTH
         * map — their tint is irrelevant — while testing here carved frame-shaped HOLES into the tint
         * wherever something opaque stood between lamp and glass. */
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        /* Blend through the cache AND raw GL. The cache lied here (the recurring Iris lesson): it
         * thought blending was on while real GL had it off, so CLEAR glass — white, invisible under a
         * working multiply — REPLACED the stained tints instead: white pane-shaped gashes through the
         * pool, identical in both map dumps. Multiply is commutative, so with real blending on, clear
         * glass costs nothing. */
        RenderSystem.enableBlend();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        /* CULL raw as well — the one the saga was actually about: the cached disable no-opped while
         * real GL kept culling, and whole box FACES vanished by winding. A missing face projects as
         * a bar or corner notch INSIDE the glass silhouette — the classifier dump showed exactly
         * that: no-fragment holes shaped like single quads, angle-dependent, every colour. */
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(false);
        com.mojang.blaze3d.platform.GlStateManager._blendFuncSeparate(
            GL11.GL_ZERO, org.lwjgl.opengl.GL11.GL_SRC_COLOR, GL11.GL_ONE, GL11.GL_ONE);
        org.lwjgl.opengl.GL14.glBlendFuncSeparate(
            GL11.GL_ZERO, GL11.GL_SRC_COLOR, GL11.GL_ONE, GL11.GL_ONE);
        org.lwjgl.opengl.GL20.glBlendEquationSeparate(
            org.lwjgl.opengl.GL14.GL_FUNC_ADD, org.lwjgl.opengl.GL14.GL_MIN);

        if (world)
        {
            drawGlassImmediate(occluders, view, projection);
        }

        if (!forms.isEmpty())
        {
            drawGlassForms(forms, occluders, view, projection);
        }

        org.lwjgl.opengl.GL20.glBlendEquationSeparate(
            org.lwjgl.opengl.GL14.GL_FUNC_ADD, org.lwjgl.opengl.GL14.GL_FUNC_ADD);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        GL11.glDrawBuffer(GL11.GL_NONE);
        reassertPassState(tile, staticLayer);
    }

    /** World glass, immediate-mode: the baked VertexBuffer path dropped quads (proven by the probe —
     * a wash through the same state covers fully, while the buffer left block-shaped holes). */
    private static void drawGlassImmediate(OccluderCache.Entry occluders, Matrix4f view, Matrix4f projection)
    {
        if (occluders.glassPositions.isEmpty())
        {
            return;
        }

        net.minecraft.client.render.BufferBuilder builder =
            net.minecraft.client.render.Tessellator.getInstance().getBuffer();

        builder.begin(net.minecraft.client.render.VertexFormat.DrawMode.QUADS,
            net.minecraft.client.render.VertexFormats.POSITION_COLOR);

        for (int i = 0; i < occluders.glassPositions.size(); i++)
        {
            long packed = occluders.glassPositions.getLong(i);
            int tint = occluders.glassTints.getInt(i);
            float x0 = net.minecraft.util.math.BlockPos.unpackLongX(packed) - occluders.originX;
            float y0 = net.minecraft.util.math.BlockPos.unpackLongY(packed) - occluders.originY;
            float z0 = net.minecraft.util.math.BlockPos.unpackLongZ(packed) - occluders.originZ;

            OccluderCache.emitColoredBox(builder, x0 - 0.01F, y0 - 0.01F, z0 - 0.01F,
                x0 + 1.01F, y0 + 1.01F, z0 + 1.01F,
                (tint >> 16) & 0xFF, (tint >> 8) & 0xFF, tint & 0xFF);
        }

        if (glassFormScratch == null)
        {
            glassFormScratch = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }

        glassFormScratch.bind();
        glassFormScratch.upload(builder.end());
        VertexBuffer.unbind();
        draw(glassFormScratch, view, projection, glassShader);
    }

    /** Glass FORMS as their transformed boxes — rebuilt per tile, they move every frame. */
    private static void drawGlassForms(
        java.util.List<com.bbsvfx.vfxlights.client.light.GlassFormCollector.GlassBox> forms,
        OccluderCache.Entry occluders, Matrix4f view, Matrix4f projection)
    {
        net.minecraft.client.render.BufferBuilder builder =
            net.minecraft.client.render.Tessellator.getInstance().getBuffer();

        builder.begin(net.minecraft.client.render.VertexFormat.DrawMode.QUADS,
            net.minecraft.client.render.VertexFormats.POSITION_COLOR);

        org.joml.Vector4f corner = new org.joml.Vector4f();

        for (com.bbsvfx.vfxlights.client.light.GlassFormCollector.GlassBox box : forms)
        {
            int r = (int) (box.tintR * 255F);
            int g = (int) (box.tintG * 255F);
            int b = (int) (box.tintB * 255F);

            /* Local unit box corners through the form's matrix, into the tile's light-local space. */
            float[] xs = new float[8];
            float[] ys = new float[8];
            float[] zs = new float[8];

            for (int i = 0; i < 8; i++)
            {
                /* Slightly inflated, same reason as the world glass: raster cracks read as white. */
                corner.set((i & 1) == 0 ? -0.51F : 0.51F, (i & 2) == 0 ? -0.01F : 1.01F,
                    (i & 4) == 0 ? -0.51F : 0.51F, 1F);
                box.matrix.transform(corner);
                xs[i] = corner.x - occluders.originX;
                ys[i] = corner.y - occluders.originY;
                zs[i] = corner.z - occluders.originZ;
            }

            quad(builder, xs, ys, zs, 0, 1, 3, 2, r, g, b);
            quad(builder, xs, ys, zs, 4, 6, 7, 5, r, g, b);
            quad(builder, xs, ys, zs, 0, 4, 5, 1, r, g, b);
            quad(builder, xs, ys, zs, 2, 3, 7, 6, r, g, b);
            quad(builder, xs, ys, zs, 0, 2, 6, 4, r, g, b);
            quad(builder, xs, ys, zs, 1, 5, 7, 3, r, g, b);
        }

        if (glassFormScratch == null)
        {
            glassFormScratch = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }

        glassFormScratch.bind();
        glassFormScratch.upload(builder.end());
        VertexBuffer.unbind();
        draw(glassFormScratch, view, projection, glassShader);
    }

    private static void quad(net.minecraft.client.render.BufferBuilder builder,
        float[] xs, float[] ys, float[] zs, int a, int b, int c, int d, int red, int green, int blue)
    {
        builder.vertex(xs[a], ys[a], zs[a]).color(red, green, blue, 255).next();
        builder.vertex(xs[b], ys[b], zs[b]).color(red, green, blue, 255).next();
        builder.vertex(xs[c], ys[c], zs[c]).color(red, green, blue, 255).next();
        builder.vertex(xs[d], ys[d], zs[d]).color(red, green, blue, 255).next();
    }

    private static void draw(VertexBuffer buffer, Matrix4f view, Matrix4f projection, ShaderProgram shader)
    {
        buffer.bind();
        buffer.draw(view, projection, shader);
        VertexBuffer.unbind();
    }

    /** Both kinds of occluder geometry into the currently bound tile: solid boxes, then cutout. */
    private static void drawOccluders(OccluderCache.Entry occluders, Matrix4f view, Matrix4f projection,
        ShaderProgram shader)
    {
        if (occluders.boxes > 0 && occluders.buffer != null)
        {
            draw(occluders.buffer, view, projection, shader);
        }

        if (!occluders.cutoutEmpty && occluders.cutoutBuffer != null)
        {
            /* Real block geometry through the real cutout layer: its shader DISCARDS transparent
             * texels, so only depth survives colour masking AND the holes stay holes — a leaf block
             * shades like leaves, a door window lets light through. endDrawing() then disables the
             * depth test behind our back (the known vanilla habit), so it is re-asserted at once. */
            RenderLayer layer = RenderLayer.getCutoutMipped();

            layer.startDrawing();

            ShaderProgram cutoutShader = RenderSystem.getShader();

            if (cutoutShader != null)
            {
                occluders.cutoutBuffer.bind();
                occluders.cutoutBuffer.draw(view, projection, cutoutShader);
                VertexBuffer.unbind();
            }

            layer.endDrawing();

            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
        }
    }

    /**
     * Put the GL state back the way the shadow pass needs it, after the entity pass has been through.
     *
     * <p>★THE BUG THIS FIXES: vanilla {@code RenderLayer.endDrawing} DISABLES the depth test, and with
     * the depth test off GL writes no depth at all. The first cube face rendered fine and every later
     * one silently became empty — measured as face+X full, face−Y blank, in that order. Entity layers
     * can also rebind the main framebuffer mid-pass (item entities outside fabulous), so the tile is
     * re-aimed as well.</p>
     */
    private static void reassertPassState(int tile)
    {
        reassertPassState(tile, false);
    }

    /** {@link #reassertPassState(int)} with the target layer made explicit: the static bake draws
     * into the STATIC atlas, so re-aiming at the live one would strand the pass. */
    private static void reassertPassState(int tile, boolean staticLayer)
    {
        if (staticLayer)
        {
            ShadowAtlas.rebindStaticTile(tile);
        }
        else
        {
            ShadowAtlas.rebindTile(tile);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.colorMask(false, false, false, false);

        /* Belt and braces: the cache has lied to us before (the Iris depth-mask lesson) — assert the
         * real GL state too, not just GlStateManager's view of it. */
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);

        /* Vanilla endDrawing resets polygon offset behind us, same habit as the depth test — so the
         * actor bias is re-asserted with the rest of the pass state, not set once. */
        applyActorDepthOffset();
    }

    /**
     * True while actor/form depth is being written with a slope-scaled polygon offset (3.0, 4.0).
     *
     * <p>Kills the SECOND SKIN LAYER's self-shadow at the source: the outer layer floats ~1.5 cm off
     * the body — sub-texel at tile density — and on grazing angles it left unstable, ragged shadow
     * speckle on the actor's own skin that came and went with the camera. The offset sinks the shell
     * into the body by a few depth quanta ALONG THE LIGHT RAY, so an actor's silhouette merely steps
     * back imperceptibly while layer-on-skin acne cannot form. Blocks and glass are written without
     * it — their casters are not двухслойные and the bias would misplace contact shadows.</p>
     */
    private static boolean actorDepthOffset;

    /** True while the ACTOR-FITTED tile renders: the polygon offset is suppressed there — at that
     * density its ~1mm sink eats half of a ~2mm fake second layer (IterationRP's rule: small
     * geometry gets ZERO render-side offset). */
    private static boolean suppressActorOffset;

    private static void applyActorDepthOffset()
    {
        if (actorDepthOffset && !suppressActorOffset && !NO_ACTOR_OFFSET)
        {
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(3.0F, 4.0F);
        }
        else
        {
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(0F, 0F);
        }
    }

    /**
     * Draw nearby entities into the map that is currently bound.
     *
     * <p>Blocks alone are not enough — a scene where the set casts shadows and the actors do not reads as
     * broken immediately, and for a machinima tool the actor IS the subject. Entities go through the
     * normal entity renderer, so a player casts a player-shaped shadow rather than a crate.</p>
     *
     * <p>The colour these shaders write goes nowhere: the atlas has no colour attachment and the draw
     * buffer is {@code GL_NONE}. Only depth survives, which is all a shadow map is.</p>
     */
    private static void renderEntities(ClientWorld world, Matrix4f view, Matrix4f projection, int tile,
        double originX, double originY, double originZ, com.bbsvfx.vfxlights.light.Light light,
        float range, float tickDelta, Vector3f faceDir)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        VertexConsumerProvider.Immediate consumers = mc.getBufferBuilders().getEntityVertexConsumers();

        Matrix4f previousProjection = RenderSystem.getProjectionMatrix();
        VertexSorter previousSorter = RenderSystem.getVertexSorting();

        RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);

        /* Entities and BBS forms write depth with the actor bias on (see actorDepthOffset). */
        actorDepthOffset = true;
        applyActorDepthOffset();

        MatrixStack matrices = new MatrixStack();

        matrices.multiplyPositionMatrix(view);

        float rangeSq = range * range;
        double lightX = light.x;
        double lightY = light.y;
        double lightZ = light.z;
        /* Group filter: a limited lamp sees ONLY its approved actors — everything else is out of
         * the shadow map entirely (no cast, no shimmer from non-categories). */
        java.util.Set<Integer> allowedIds = !light.groupFilter || light.groups.isEmpty()
            ? null : allowedEntityIds(light.groups);

        for (Entity entity : FRAME_ENTITIES)
        {
            /* NO shouldRender(light…) — see nearestActors: its (avgSide × 64)² cap dropped in-range
             * casters from the map as the lamp moved away. Range alone decides. */
            if (entity.isSpectator()
                || (allowedIds != null && !allowedIds.contains(entity.getId())))
            {
                continue;
            }

            double ex = MathHelper.lerp(tickDelta, entity.prevX, entity.getX());
            double ey = MathHelper.lerp(tickDelta, entity.prevY, entity.getY());
            double ez = MathHelper.lerp(tickDelta, entity.prevZ, entity.getZ());
            double dx = ex - lightX;
            double dy = ey - lightY;
            double dz = ez - lightZ;

            if (dx * dx + dy * dy + dz * dz > rangeSq)
            {
                continue;
            }

            /* Per-face cull: a cube face's pass only pays the casters that actually FACE it —
             * one actor in range used to be dispatched into all six faces of every lamp. */
            if (!facesDir(dx, dy, dz, Math.max(entity.getWidth(), entity.getHeight()) + 0.5, faceDir))
            {
                continue;
            }

            try
            {
                dispatcher.render(entity, ex - originX, ey - originY, ez - originZ,
                    entity.getYaw(), tickDelta, matrices, consumers, FULL_BRIGHT);
            }
            catch (Throwable ignored)
            {
                /* A renderer that dislikes being called outside the world pass costs us one shadow,
                 * not the frame. */
            }
        }

        try
        {
            consumers.draw();

            renderBbsForms(world, matrices, tile, originX, originY, originZ, light, rangeSq, tickDelta,
                faceDir);
        }
        finally
        {
            /* Off again before glass/occluder work — the bias is for actors only. */
            actorDepthOffset = false;
            applyActorDepthOffset();

            RenderSystem.setProjectionMatrix(previousProjection, previousSorter);
        }
    }

    /** MC entity ids of actor-replays whose category the lamp allows; everything else stays out. */
    private static java.util.Set<Integer> allowedEntityIds(java.util.Set<String> groups)
    {
        java.util.Set<Integer> ids = new java.util.HashSet<>();

        for (mchorse.bbs_mod.film.BaseFilmController controller :
            com.bbsvfx.vfxlights.client.render.CharacterMask.controllers())
        {
            if (controller == null || controller.film == null)
            {
                continue;
            }

            java.util.Map<String, Integer> actors = controller.getActors();

            if (actors == null)
            {
                continue;
            }

            for (java.util.Map.Entry<String, Integer> entry : actors.entrySet())
            {
                for (mchorse.bbs_mod.film.replays.Replay replay : controller.film.replays.getList())
                {
                    if (entry.getKey().equals(replay.getId())
                        && com.bbsvfx.vfxlights.client.light.ActorCategories.allowed(groups,
                            mchorse.bbs_mod.film.replays.Replay.normalizeCategory(replay.category.get())))
                    {
                        ids.add(entry.getValue());

                        break;
                    }
                }
            }
        }

        return ids;
    }

    /**
     * Draw the BBS-side shadow casters — film actors AND model-block forms — into the map that is
     * currently bound.
     *
     * <p>Neither is a world entity, so the entity pass above never sees them: film actors are
     * {@code IEntity} puppets owned by a film controller, and a model block is a BLOCK ENTITY whose form
     * is drawn by its own renderer, invisible to both the entity pass and the block-occluder bake.
     * Without this pass the SUBJECT of every shot casts no shadow, and a volumetric beam passes clean
     * through a character standing in it: both reported as one bug, and they are one bug.</p>
     *
     * <p>Transforms are BBS's own — {@code getMatrixForRenderWithRotation} for actors, the model-block
     * renderer's {@code translate(+0.5, 0, +0.5)} + {@code applyTransform} for blocks — so the shadow
     * stands exactly where the rendered form does.</p>
     *
     * <p>Rendered with BBS's iris flag forced OFF: with a pack active, the pack-replaced program re-binds
     * the PACK's framebuffer on use and the form's depth lands there instead of the shadow tile (the
     * offscreen-replay lesson from BBS VFX).</p>
     */
    private static void renderBbsForms(ClientWorld world, MatrixStack matrices, int tile,
        double originX, double originY, double originZ, com.bbsvfx.vfxlights.light.Light light,
        float rangeSq, float tickDelta, Vector3f faceDir)
    {
        double lightX = light.x;
        double lightY = light.y;
        double lightZ = light.z;
        /* Films playing outside the editor plus the editor's own controller — the one list every
         * actor-reading pass shares (masks, dynamic-caster probe, this). Failures inside cost the
         * actors, never the model blocks below. */
        java.util.List<mchorse.bbs_mod.film.BaseFilmController> controllers =
            com.bbsvfx.vfxlights.client.render.CharacterMask.controllers();

        boolean previousIris = com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$getIris();

        com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$setIris(false);

        try
        {
            /* The vanilla entity flush above just ran RenderLayer.endDrawing — depth test is OFF and
             * the first form drawn here would write no depth at all (the same lesson as the cube faces:
             * rendered, counted, invisible). Reassert BEFORE the first form, not only after each. */
            reassertPassState(tile);

            for (mchorse.bbs_mod.film.BaseFilmController controller : controllers)
            {
                for (java.util.Map.Entry<String, mchorse.bbs_mod.forms.entities.IEntity> entry
                    : controller.getEntities().entrySet())
                {
                    mchorse.bbs_mod.forms.entities.IEntity actor = entry.getValue();
                    mchorse.bbs_mod.forms.forms.Form form = actor == null ? null : actor.getForm();

                    /* Group filter: a limited lamp's shadow map sees only its approved actors —
                     * the rest neither light nor cast from it. */
                    if (form == null || (light.groupFilter
                        && !com.bbsvfx.vfxlights.client.light.ActorCategories.allowed(
                            light.groups, com.bbsvfx.vfxlights.client.light.ActorCategories.categoryOf(
                                controller, entry.getKey()))))
                    {
                        continue;
                    }

                    /* Sample the caster at the film's OWN sub-tick time — the value the visible
                     * film render uses (BaseFilmController.getTransition: 0 while paused, the
                     * editor adds "while not playing"). The raw tickDelta kept sweeping a paused
                     * caster between its frozen recorded prev/current at the 20 Hz tick sawtooth
                     * and re-sampling its pose at fractional sub-tick time while the visible
                     * actor stood still — the pause/scrub light tremor. A playing film gets the
                     * tickDelta back unchanged, so playback is untouched. */
                    float transition = ((com.bbsvfx.vfxlights.mixin.client.BaseFilmControllerAccessor)
                        controller).vfxlights$getTransition(actor, tickDelta);

                    double ex = MathHelper.lerp(transition, actor.getPrevX(), actor.getX());
                    double ey = MathHelper.lerp(transition, actor.getPrevY(), actor.getY());
                    double ez = MathHelper.lerp(transition, actor.getPrevZ(), actor.getZ());
                    double dx = ex - lightX;
                    double dy = ey - lightY;
                    double dz = ez - lightZ;

                    /* The range test takes the caster's margin too: a wind form's sway zone reaches
                     * blocks past its host — a host outside the lamp's range used to be skipped
                     * outright, and the zone's trees cast no shadows into this map. */
                    double actorMargin = casterMargin(form, 1.6);
                    double actorReach = Math.sqrt(rangeSq) + actorMargin;

                    if (dx * dx + dy * dy + dz * dz > actorReach * actorReach)
                    {
                        continue;
                    }

                    if (!facesDir(dx, dy, dz, actorMargin, faceDir))
                    {
                        continue;
                    }

                    try
                    {
                        /* The parameters here are the ORIGIN to subtract, not a pre-subtracted offset —
                         * the function lerps the entity's own position and subtracts these itself.
                         * Passing ex-origin made it compute entity-(entity-origin)=origin: every actor
                         * drawn AT the tile origin, ~a light-range outside the frustum. Rendered,
                         * counted, invisible — measured via the NDC probe. */
                        Matrix4f model = mchorse.bbs_mod.film.FilmMatrices.getMatrixForRenderWithRotation(
                            actor, originX, originY, originZ, transition);

                        matrices.push();
                        matrices.multiplyPositionMatrix(model);

                        mchorse.bbs_mod.forms.renderers.FormRenderingContext context =
                            new mchorse.bbs_mod.forms.renderers.FormRenderingContext()
                                .set(mchorse.bbs_mod.forms.renderers.FormRenderType.ENTITY, actor,
                                    matrices, FULL_BRIGHT, net.minecraft.client.render.OverlayTexture.DEFAULT_UV,
                                    transition);

                        mchorse.bbs_mod.forms.FormUtilsClient.render(form, context);
                        matrices.pop();
                    }
                    catch (Throwable ignored)
                    {
                        /* One actor's renderer misbehaving outside the film pass costs that actor's
                         * shadow, not the frame. */
                    }

                    /* Form renderers flip layers and can rebind the framebuffer mid-draw; re-aim the
                     * tile before the next actor, or its depth lands somewhere else entirely. */
                    reassertPassState(tile);
                }
            }

            /* Model blocks: walk the chunks the light can reach and pick out BBS's block entities.
             * The world offers no global block-entity list; the chunk maps are the honest source. */
            int minChunkX = ((int) Math.floor(lightX - Math.sqrt(rangeSq))) >> 4;
            int maxChunkX = ((int) Math.floor(lightX + Math.sqrt(rangeSq))) >> 4;
            int minChunkZ = ((int) Math.floor(lightZ - Math.sqrt(rangeSq))) >> 4;
            int maxChunkZ = ((int) Math.floor(lightZ + Math.sqrt(rangeSq))) >> 4;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
            {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
                {
                    /* Cached per frame and shared with the dynamic probe — a rig's dozen lamps
                     * times six faces used to rescan these chunk maps ~40 times a frame. */
                    for (ModelBlockEntry entry : modelBlocksInChunk(world, chunkX, chunkZ))
                    {
                        mchorse.bbs_mod.forms.forms.Form form = entry.props.getForm();
                        double dx = entry.x - lightX;
                        double dy = entry.y - lightY;
                        double dz = entry.z - lightZ;

                        /* Range + margin (see the actor loop): the sway zone reaches past its block. */
                        double blockMargin = casterMargin(form, 2.0);
                        double blockReach = Math.sqrt(rangeSq) + blockMargin;

                        if (dx * dx + dy * dy + dz * dz > blockReach * blockReach)
                        {
                            continue;
                        }

                        if (!facesDir(dx, dy, dz, blockMargin, faceDir))
                        {
                            continue;
                        }

                        try
                        {
                            net.minecraft.util.math.BlockPos pos = entry.block.getPos();

                            matrices.push();
                            /* The model-block renderer's own sequence: block corner, +half on the
                             * horizontal axes, then the user's transform. */
                            matrices.translate(
                                pos.getX() + 0.5F - originX,
                                pos.getY() - originY,
                                pos.getZ() + 0.5F - originZ);
                            mchorse.bbs_mod.utils.MatrixStackUtils.applyTransform(matrices, entry.props.getTransform());

                            mchorse.bbs_mod.forms.renderers.FormRenderingContext context =
                                new mchorse.bbs_mod.forms.renderers.FormRenderingContext()
                                    .set(mchorse.bbs_mod.forms.renderers.FormRenderType.MODEL_BLOCK,
                                        entry.block.getEntity(), matrices, FULL_BRIGHT,
                                        net.minecraft.client.render.OverlayTexture.DEFAULT_UV, tickDelta);

                            mchorse.bbs_mod.forms.FormUtilsClient.render(form, context);
                            matrices.pop();
                        }
                        catch (Throwable ignored)
                        {
                            /* Same deal as the actors: one bad form costs its own shadow only. */
                        }

                        reassertPassState(tile);
                    }
                }
            }

            mchorse.bbs_mod.forms.FormUtilsClient.getProvider().draw();
        }
        finally
        {
            com.bbsvfx.vfxlights.mixin.client.BBSRenderingIrisAccessor.vfxlights$setIris(previousIris);

            /* The form path is free to flip blend/cull/masks; put the pass state back before the next
             * face is drawn (the RenderLayer.endDrawing lesson — it disables DEPTH TEST behind us). */
            reassertPassState(tile);
        }
    }
}
