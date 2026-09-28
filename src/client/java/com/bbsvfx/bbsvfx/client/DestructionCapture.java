package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.DamageControl;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.bbsvfx.bbsvfx.DestructionSelection;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;
import com.bbsvfx.bbsvfx.forms.ExplosionForm;
import com.bbsvfx.bbsvfx.mixin.ActionManagerAccessor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stage 3: turns the wand selection into a Destruction Box. Reads the selected blocks from the client
 * world into a {@link DestructionBoxForm}, adds it as a film actor positioned at the box origin (so it
 * appears exactly where it was cut), then removes the real blocks (set to air) on the integrated
 * server. The cut is remembered so {@link #undo()} can restore it.
 *
 * <p>Single-player only (uses the integrated server for the world edit).</p>
 */
public final class DestructionCapture
{
    private static List<BlockPos> lastCutPos;
    private static List<BlockState> lastCutState;
    private static RegistryKey<World> lastWorld;

    private DestructionCapture()
    {}

    /** Deterministic 0..1 noise from a block position (same recipe as the form's per-block scatter). */
    private static float hash01(int x, int y, int z)
    {
        long h = x * 374761393L + y * 668265263L + z * 2147483647L;

        h = (h ^ (h >>> 13)) * 1274126177L;

        return ((h ^ (h >>> 16)) & 0xffffff) / (float) 0x1000000;
    }

    public static void capture(Film film)
    {
        if (film == null || !DestructionSelection.isComplete())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;

        if (world == null)
        {
            return;
        }

        BlockPos min = DestructionSelection.min();
        BlockPos max = DestructionSelection.max();

        DestructionBoxForm form = new DestructionBoxForm();
        List<BlockPos> cut = new ArrayList<>();
        List<BlockState> old = new ArrayList<>();

        /* Edge feather ("Capture edge roughness" VFX setting): blocks near the selection FACES are
         * skipped with a noise probability rising toward the boundary, so the cut hole and the captured
         * structure get an organic ragged outline instead of a perfect rectangle. 0 = exact box.
         * Deterministic per position — recapturing the same box gives the same shape. */
        float rough = com.bbsvfx.bbsvfx.BbsVfxAddon.destructionRoughness == null
            ? 0F
            : com.bbsvfx.bbsvfx.BbsVfxAddon.destructionRoughness.get() / 100F;
        int feather = rough <= 0F ? 0 : 1 + Math.round(rough * 3F);

        for (int x = min.getX(); x <= max.getX(); x++)
        {
            for (int y = min.getY(); y <= max.getY(); y++)
            {
                for (int z = min.getZ(); z <= max.getZ(); z++)
                {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);

                    if (state.isAir())
                    {
                        continue;
                    }

                    if (feather > 0)
                    {
                        int d = Math.min(
                            Math.min(Math.min(x - min.getX(), max.getX() - x), Math.min(y - min.getY(), max.getY() - y)),
                            Math.min(z - min.getZ(), max.getZ() - z));

                        if (d < feather && hash01(x, y, z) > (d + 0.5F) / feather)
                        {
                            continue;
                        }
                    }

                    /* Resolve the biome tint (tintIndex 0) here, while the block is still in the world with
                     * its biome; -1 for untinted blocks. Applied at bake so grass/leaves/water keep colour.
                     * Light is sampled PRE-cut too — per-block lighting instead of one flat sample. */
                    int tint = mc.getBlockColors().getColor(state, world, pos, 0);
                    int light = captureLight(world, pos);

                    form.blocks.addBlock(x - min.getX(), y - min.getY(), z - min.getZ(), state, tint, light);
                    cut.add(pos);
                    old.add(state);
                }
            }
        }

        if (form.blocks.getList().isEmpty())
        {
            return;
        }

        /* Add the structure as an actor at the box origin (in-place). */
        Replay replay = film.replays.addReplay();

        replay.category.set("");
        replay.form.set(form);
        replay.keyframes.x.insert(0F, (double) min.getX());
        replay.keyframes.y.insert(0F, (double) min.getY());
        replay.keyframes.z.insert(0F, (double) min.getZ());

        /* Cut the real blocks on the integrated server, remembering them for undo. */
        MinecraftServer server = mc.getServer();

        if (server != null)
        {
            ServerWorld serverWorld = server.getWorld(world.getRegistryKey());

            if (serverWorld != null)
            {
                server.execute(() -> withDamageControlSuspended(() ->
                {
                    /* Fill by ORIGINAL state: cut seagrass/kelp/waterlogged blocks leave WATER, not an air
                     * bubble in the middle of the sea. */
                    for (int i = 0; i < cut.size(); i++)
                    {
                        serverWorld.setBlockState(cut.get(i), fillFor(old.get(i)), 2);
                    }
                }));

                lastCutPos = cut;
                lastCutState = old;
                lastWorld = world.getRegistryKey();
            }
        }

        /* Selection consumed — drop the wand box so the red wireframe disappears. */
        DestructionSelection.clear();
    }

    /**
     * Phase 0 of {@code bbsvfx:explosion}: instead of the wand box, scan a SPHERE of the real world into
     * an {@link ExplosionForm}. The wand selection defines the region — its centre is the epicenter and
     * the scan radius is half of its largest side — so "box the area, get a ball of debris blasting out
     * from the middle". Otherwise identical to {@link #capture(Film)}: adds the actor in place, cuts the
     * real blocks (undoable), and the inherited PhysX bake makes scrubbing Destruction play the blast.
     */
    public static void captureExplosion(Film film)
    {
        if (film == null || !DestructionSelection.isComplete())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;

        if (world == null)
        {
            return;
        }

        BlockPos min = DestructionSelection.min();
        BlockPos max = DestructionSelection.max();

        /* Epicenter = centre of the selection (world space, block-centre aligned); radius = half of the
         * largest side, so the ball spans the box's longest dimension. */
        float ecx = (min.getX() + max.getX() + 1) * 0.5F;
        float ecy = (min.getY() + max.getY() + 1) * 0.5F;
        float ecz = (min.getZ() + max.getZ() + 1) * 0.5F;
        float radius = Math.max(1F, Math.max(
            max.getX() - min.getX() + 1,
            Math.max(max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1)) * 0.5F);
        float radiusSq = radius * radius;

        ExplosionForm form = new ExplosionForm();
        boolean hemisphere = form.scanHemisphere.get();
        List<BlockPos> cut = new ArrayList<>();
        List<BlockState> old = new ArrayList<>();

        /* Track the captured mass' local bounds so the epicenter lands on its BOTTOM-centre (not the box
         * centre, which sits in the empty air above ground-level terrain — that pushed everything down). */
        int lMinX = Integer.MAX_VALUE, lMinY = Integer.MAX_VALUE, lMinZ = Integer.MAX_VALUE;
        int lMaxX = Integer.MIN_VALUE, lMaxZ = Integer.MIN_VALUE;

        for (int x = min.getX(); x <= max.getX(); x++)
        {
            for (int y = min.getY(); y <= max.getY(); y++)
            {
                for (int z = min.getZ(); z <= max.getZ(); z++)
                {
                    float dx = x + 0.5F - ecx;
                    float dy = y + 0.5F - ecy;
                    float dz = z + 0.5F - ecz;

                    if (dx * dx + dy * dy + dz * dz > radiusSq)
                    {
                        continue;
                    }

                    if (hemisphere && y + 0.5F > ecy)
                    {
                        continue;
                    }

                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);

                    if (state.isAir())
                    {
                        continue;
                    }

                    int tint = mc.getBlockColors().getColor(state, world, pos, 0);
                    int light = captureLight(world, pos);
                    int lx = x - min.getX(), ly = y - min.getY(), lz = z - min.getZ();

                    form.blocks.addBlock(lx, ly, lz, state, tint, light);
                    cut.add(pos);
                    old.add(state);

                    lMinX = Math.min(lMinX, lx); lMinY = Math.min(lMinY, ly); lMinZ = Math.min(lMinZ, lz);
                    lMaxX = Math.max(lMaxX, lx); lMaxZ = Math.max(lMaxZ, lz);
                }
            }
        }

        if (form.blocks.getList().isEmpty())
        {
            return;
        }

        /* Epicenter = bottom-centre of the captured mass (local space): every block sits at or above it,
         * so the radial blast ejects up-and-out. */
        float px = (lMinX + lMaxX + 1) * 0.5F;
        float py = lMinY;
        float pz = (lMinZ + lMaxZ + 1) * 0.5F;

        /* Blast radius = farthest block from the epicenter × 1.5 so the whole mass sits WELL inside the
         * falloff — it is QUADRATIC, so at radius = maxDist the outer half of the pile gets almost no
         * impulse and just drops. */
        float maxDist = 1F;

        for (com.bbsvfx.bbsvfx.forms.DestructionBlock block : form.blocks.getAllTyped())
        {
            float bx = block.x.get() + 0.5F - px;
            float by = block.y.get() + 0.5F - py;
            float bz = block.z.get() + 0.5F - pz;

            maxDist = Math.max(maxDist, (float) Math.sqrt(bx * bx + by * by + bz * bz));
        }

        form.pointX.set(px);
        form.pointY.set(py);
        form.pointZ.set(pz);
        form.scanRadius.set(radius);
        form.physExplosionRadius.set(maxDist * 1.5F);

        /* Environment bend: also capture the FOLIAGE in bendRadius around the epicenter (outside the
         * blast sphere) — logs+leaves+small plants, cut with the same undo, re-rendered as bending
         * proxy units by the form. A cylinder: heightmap-topped columns within the radius. */
        /* Auto-scale with the blast: a big explosion should rock the forest far past the crater —
         * take the larger of the SCAN slider and ~2.2x the captured mass' extent. The sway falloff
         * reach follows the actual scan. */
        float bendR = Math.min(320F, Math.max(form.bendScanRadius.get(), maxDist * 2.2F));

        form.bendScanRadius.set(bendR);
        form.bendRadius.set(bendR);

        scanFoliageInto(world, mc, form, min.getX(), min.getY(), min.getZ(),
            min.getX() + px, min.getY() + py, min.getZ() + pz,
            ecx, ecy, ecz, radiusSq, bendR, cut, old);

        Replay replay = film.replays.addReplay();

        replay.category.set("");
        replay.form.set(form);
        replay.keyframes.x.insert(0F, (double) min.getX());
        replay.keyframes.y.insert(0F, (double) min.getY());
        replay.keyframes.z.insert(0F, (double) min.getZ());

        MinecraftServer server = mc.getServer();

        if (server != null)
        {
            ServerWorld serverWorld = server.getWorld(world.getRegistryKey());

            if (serverWorld != null)
            {
                server.execute(() -> withDamageControlSuspended(() ->
                {
                    /* Fill by ORIGINAL state: cut seagrass/kelp/waterlogged blocks leave WATER, not an air
                     * bubble in the middle of the sea. */
                    for (int i = 0; i < cut.size(); i++)
                    {
                        serverWorld.setBlockState(cut.get(i), fillFor(old.get(i)), 2);
                    }
                }));

                lastCutPos = cut;
                lastCutState = old;
                lastWorld = world.getRegistryKey();
            }
        }

        DestructionSelection.clear();
    }

    /**
     * Scan the foliage cylinder around the epicenter into {@code form.foliage} (form-local coords are
     * relative to {@code originX/Y/Z}), collecting the cut into {@code cut}/{@code old}. Blocks inside
     * the blast sphere ({@code blastRadiusSq} around {@code ecx/ecy/ecz}, world coords) are skipped —
     * they are debris; pass 0 to skip nothing (rescan: the crater is already air).
     */
    private static void scanFoliageInto(World world, MinecraftClient mc, ExplosionForm form,
        int originX, int originY, int originZ, float epicWorldX, float epicWorldY, float epicWorldZ,
        float ecx, float ecy, float ecz, float blastRadiusSq, float bendR,
        List<BlockPos> cut, List<BlockState> old)
    {
        if (bendR <= 1F)
        {
            return;
        }

        int cx = Math.round(epicWorldX), cy = Math.round(epicWorldY), cz = Math.round(epicWorldZ);
        int r = Math.round(bendR);
        int cols = 0, loadedCols = 0, found = 0;

        for (int x = cx - r; x <= cx + r; x++)
        {
            for (int z = cz - r; z <= cz + r; z++)
            {
                int ddx = x - cx, ddz = z - cz;

                if (ddx * ddx + ddz * ddz > r * r)
                {
                    continue;
                }

                cols++;

                if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4))
                {
                    continue;
                }

                loadedCols++;

                /* The heightmap is TRANSIENT during a big restore sync — a stale-low top TRUNCATED the
                 * scan and left real treetops standing over proxied trunks (the "duplicate trees").
                 * Never trust it below a generous fixed ceiling. */
                int top = Math.max(world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z),
                    cy + 64);

                for (int y = Math.max(world.getBottomY(), cy - 12); y < top; y++)
                {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);

                    if (state.isAir() || !isFoliage(state))
                    {
                        continue;
                    }

                    if (blastRadiusSq > 0F)
                    {
                        float sdx = x + 0.5F - ecx, sdy = y + 0.5F - ecy, sdz = z + 0.5F - ecz;

                        if (sdx * sdx + sdy * sdy + sdz * sdz <= blastRadiusSq)
                        {
                            continue;
                        }
                    }

                    int tint = mc.getBlockColors().getColor(state, world, pos, 0);
                    int light = captureLight(world, pos);

                    form.foliage.addBlock(x - originX, y - originY, z - originZ, state, tint, light);
                    cut.add(pos);
                    old.add(state);
                    found++;
                }
            }
        }

        /* TREE CLOSURE: the cylinder edge SPLITS boundary trees — trunk captured (proxied), crown left
         * REAL; the orphaned crown then leaf-decays, the decay gets recorded during film play, and the
         * stop-restore resurrects it under the swaying proxy ("duplicate trees after playback").
         * Flood-expand the capture along CONNECTED foliage past the radius (bounded margin) so a tree
         * is always captured whole or not at all. */
        it.unimi.dsi.fastutil.longs.LongOpenHashSet captured =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet(cut.size() * 2);

        for (BlockPos pos : cut)
        {
            captured.add(pos.asLong());
        }

        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>(cut);
        float maxR2 = (r + 16F) * (r + 16F);
        int grown = 0;

        while (!queue.isEmpty())
        {
            BlockPos pos = queue.poll();

            for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values())
            {
                BlockPos next = pos.offset(dir);

                if (captured.contains(next.asLong()))
                {
                    continue;
                }

                float ddx = next.getX() + 0.5F - cx, ddz = next.getZ() + 0.5F - cz;

                if (ddx * ddx + ddz * ddz > maxR2)
                {
                    continue;
                }

                BlockState state = world.getBlockState(next);

                /* Grow only through TREE material — ground plants are single blocks anyway. */
                if (state.isAir()
                    || !(state.isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                        || state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)))
                {
                    continue;
                }

                int tint = mc.getBlockColors().getColor(state, world, next, 0);
                int light = captureLight(world, next);

                form.foliage.addBlock(next.getX() - originX, next.getY() - originY, next.getZ() - originZ,
                    state, tint, light);
                cut.add(next);
                old.add(state);
                captured.add(next.asLong());
                queue.add(next);
                grown++;
            }
        }

        System.out.println("[bbsvfx] foliage scan r=" + r + " @ " + cx + "," + cy + "," + cz + ": "
            + found + " blocks + " + grown + " tree-closure, " + loadedCols + "/" + cols + " columns loaded");
    }

    /* The actor origin of each rendered explosion, noted by the renderer every frame — the FORM
     * EDITOR's own origin matrix is preview-space (identity), which sent the first rescan's restore
     * to world ~0,0,0 and wiped the foliage. */
    private static final java.util.WeakHashMap<ExplosionForm, double[]> ORIGINS = new java.util.WeakHashMap<>();
    private static volatile double[] lastOrigin;
    private static volatile ExplosionForm lastForm;

    /** Called by the renderer with the actor's world origin (entity + form transform). */
    public static void noteOrigin(ExplosionForm form, double ox, double oy, double oz)
    {
        double[] origin = {ox, oy, oz};

        synchronized (ORIGINS)
        {
            ORIGINS.put(form, origin);
        }

        lastOrigin = origin;
        lastForm = form;
    }

    /* ---- Beam devour: create a devouring beam actor + carve a bowl from the wand selection ---- */

    /**
     * Turn the wand selection into a devouring {@link com.bbsvfx.bbsvfx.forms.BeamForm} actor (the
     * "Capture Beam" icon), mirroring {@link #captureExplosion}: the selection's horizontal footprint
     * is the crater radius and its height the crater depth. Carves a bowl (deepest at the centre) from
     * each column's surface down, stores the cut blocks on the form (rendered later as rising /
     * sucked-in devour debris) and removes them from the world (client + server, undoable). The beam
     * rises from the impact point.
     */
    public static void captureBeam(Film film)
    {
        if (film == null || !DestructionSelection.isComplete())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;
        MinecraftServer server = mc.getServer();

        if (world == null || server == null)
        {
            return;
        }

        ServerWorld serverWorld = server.getWorld(world.getRegistryKey());

        if (serverWorld == null)
        {
            return;
        }

        BlockPos min = DestructionSelection.min();
        BlockPos max = DestructionSelection.max();

        int ox = (int) Math.floor((min.getX() + max.getX() + 1) * 0.5F);
        int oz = (int) Math.floor((min.getZ() + max.getZ() + 1) * 0.5F);
        float radius = Math.max(1F, Math.max(max.getX() - min.getX() + 1, max.getZ() - min.getZ() + 1) * 0.5F);
        float depth = Math.min(32F, Math.max(1F, max.getY() - min.getY() + 1));
        /* Origin at the CRATER FLOOR (centre-bottom of the bowl), not the surface — so the beam's base /
         * landing tip reaches the dug-out floor instead of floating in the air above it. */
        int oy = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, ox, oz) - Math.round(depth);

        com.bbsvfx.bbsvfx.forms.BeamForm form = new com.bbsvfx.bbsvfx.forms.BeamForm();

        form.destruction.set(true);
        form.destructRadius.set(radius);
        form.destructDepth.set(depth);

        List<BlockPos> cut = new ArrayList<>();
        List<BlockState> old = new ArrayList<>();

        int ri = (int) Math.ceil(radius);
        float r2 = radius * radius;

        for (int dx = -ri; dx <= ri; dx++)
        {
            for (int dz = -ri; dz <= ri; dz++)
            {
                float d2 = dx * dx + dz * dz;

                if (d2 > r2)
                {
                    continue;
                }

                int wx = ox + dx, wz = oz + dz;
                float dn = radius > 0F ? (float) Math.sqrt(d2) / radius : 0F;
                /* Bowl: deepest at the centre, tapering to 0 at the rim. */
                int col = Math.round(depth * (1F - dn * dn));

                if (col <= 0)
                {
                    continue;
                }

                int surf = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, wx, wz) - 1;

                for (int y = surf; y > surf - col; y--)
                {
                    BlockPos pos = new BlockPos(wx, y, wz);
                    BlockState state = world.getBlockState(pos);

                    if (state.isAir())
                    {
                        continue;
                    }

                    int tint = mc.getBlockColors().getColor(state, world, pos, 0);
                    int light = captureLight(world, pos);

                    form.blocks.addBlock(wx - ox, y - oy, wz - oz, state, tint, light);
                    cut.add(pos);
                    old.add(state);
                }
            }
        }

        if (cut.isEmpty())
        {
            return;
        }

        Replay replay = film.replays.addReplay();

        replay.category.set("");
        replay.form.set(form);
        replay.keyframes.x.insert(0F, (double) ox);
        replay.keyframes.y.insert(0F, (double) oy);
        replay.keyframes.z.insert(0F, (double) oz);

        /* Cut the CLIENT world too (the server cut applies but its block-update packets don't reach the
         * editor client — BBS keeps the scene world pristine), so the crater shows immediately. Fill by the
         * ORIGINAL state so an underwater carve leaves water, not an air bubble. */
        for (int i = 0; i < cut.size(); i++)
        {
            world.setBlockState(cut.get(i), fillFor(old.get(i)), 2);
        }

        server.execute(() -> withDamageControlSuspended(() ->
        {
            for (int i = 0; i < cut.size(); i++)
            {
                serverWorld.setBlockState(cut.get(i), fillFor(old.get(i)), 2);
            }
        }));

        lastCutPos = cut;
        lastCutState = old;
        lastWorld = world.getRegistryKey();

        DestructionSelection.clear();
    }

    /* ---- Dome levelling: create an expanding-dome actor + raze everything in radius above the plane ---- */

    /**
     * Turn the wand selection into a world-levelling {@link com.bbsvfx.bbsvfx.forms.DomeForm} actor (the
     * "Capture Dome" icon): the selection's horizontal footprint is the dome radius, its BOTTOM Y the
     * flat plane to raze down to, its TOP Y how high to sweep (over structures). Every non-air block
     * within the radius and above the plane is stored on the form (rendered as swept-away debris when
     * the wavefront passes) and removed from the world (client + server, undoable). The dome expands
     * from the plane centre.
     */
    public static void captureDome(Film film)
    {
        if (film == null || !DestructionSelection.isComplete())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;
        MinecraftServer server = mc.getServer();

        if (world == null || server == null)
        {
            return;
        }

        ServerWorld serverWorld = server.getWorld(world.getRegistryKey());

        if (serverWorld == null)
        {
            return;
        }

        BlockPos min = DestructionSelection.min();
        BlockPos max = DestructionSelection.max();

        /* The wand only marks the SPOT (centre). The destruction extent is the dome's own radius, not
         * the selection — so a small mark still levels a wide circle, and the animated front (keyframed
         * radius/progress) eats the world out to that radius, independent of the selection. */
        int ox = (int) Math.floor((min.getX() + max.getX() + 1) * 0.5F);
        int oz = (int) Math.floor((min.getZ() + max.getZ() + 1) * 0.5F);

        com.bbsvfx.bbsvfx.forms.DomeForm form = new com.bbsvfx.bbsvfx.forms.DomeForm();

        form.destruction.set(true);
        form.maxRadius.set(DOME_EXTENT_DEFAULT);

        Replay replay = film.replays.addReplay();

        replay.category.set("");
        replay.form.set(form);
        replay.keyframes.x.insert(0F, (double) ox);
        replay.keyframes.z.insert(0F, (double) oz);

        razeDomeCircle(mc, world, serverWorld, server, form, ox, oz, replay, false);

        lastDomeForm = form;
        lastDomeReplay = replay;
        lastDomeOx = ox;
        lastDomeOz = oz;

        DestructionSelection.clear();
    }

    /** Generous default dome radius for a fresh levelling sequence (blocks) — the wand doesn't size it. */
    private static final float DOME_EXTENT_DEFAULT = 40F;

    private static com.bbsvfx.bbsvfx.forms.DomeForm lastDomeForm;
    private static Replay lastDomeReplay;
    private static int lastDomeOx, lastDomeOz;

    /** The DomeForm instance the SCENE renderer actually draws (noted each frame) — the one Re-cut must
     *  fill, since the editor and scene keep separate copies of the form. */
    private static volatile com.bbsvfx.bbsvfx.forms.DomeForm renderedDomeForm;

    /** The dome actor's WORLD origin, noted at render time — re-cut must work in a LATER session than
     *  the capture (the lastDome* statics are gone after a restart), so the origin comes from here. */
    private static volatile double[] lastDomeOrigin;

    public static void noteDomeForm(com.bbsvfx.bbsvfx.forms.DomeForm form, double ox, double oy, double oz)
    {
        renderedDomeForm = form;
        lastDomeOrigin = new double[] {ox, oy, oz};
    }

    /**
     * Raze the circle of the dome's {@code max_radius} at (ox, oz): flatten everything above the lowest
     * surface in that circle, storing the cut blocks on the form (rendered as they dissolve when the
     * front passes) and cutting the world (client + server). Sets the actor's Y origin to the ground.
     * Blocks the animated front never reaches simply render intact — no holes. Reused by re-cut.
     */
    private static void razeDomeCircle(MinecraftClient mc, World world, ServerWorld serverWorld,
        MinecraftServer server, com.bbsvfx.bbsvfx.forms.DomeForm form, int ox, int oz, Replay replay, boolean append)
    {
        float radius = Math.max(1F, form.maxRadius.get());
        int ri = (int) Math.ceil(radius);
        float r2 = radius * radius;

        /* Flatten to the CENTRE's surface (NOT the global lowest surface in the circle): flattening to
         * the min would raze every column down to the deepest point in the radius, so a single tall
         * mountain / valley blows the block count to hundreds of thousands (the crash). Raze only what
         * sits ABOVE the centre plane; MAX_RAZE caps a tall spike, MAX_BLOCKS is a hard safety ceiling. */
        int centerSurf = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, ox, oz) - 1;
        int scour = 1;              // scour one layer below the plane so dead-flat ground still reacts
        int floorY = centerSurf - scour;
        int oy = centerSurf;        // dome base sits on the centre ground surface

        final int MAX_RAZE = 48;    // max blocks razed per column (bounds tall spikes)
        final int MAX_BLOCKS = 220000;

        form.blocks.clearBlocks();

        List<BlockPos> cut = new ArrayList<>();
        List<BlockState> old = new ArrayList<>();
        boolean capped = false;

        for (int dx = -ri; dx <= ri && !capped; dx++)
        {
            for (int dz = -ri; dz <= ri; dz++)
            {
                if (dx * dx + dz * dz > r2)
                {
                    continue;
                }

                int wx = ox + dx, wz = oz + dz;
                int surf = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, wx, wz) - 1;
                int top = Math.min(surf, floorY + MAX_RAZE);   // cap the column height

                for (int y = top; y > floorY; y--)
                {
                    BlockPos pos = new BlockPos(wx, y, wz);
                    BlockState state = world.getBlockState(pos);

                    if (state.isAir())
                    {
                        continue;
                    }

                    int tint = mc.getBlockColors().getColor(state, world, pos, 0);
                    int light = captureLight(world, pos);

                    form.blocks.addBlock(wx - ox, y - oy, wz - oz, state, tint, light);
                    cut.add(pos);
                    old.add(state);
                }

                if (cut.size() >= MAX_BLOCKS)
                {
                    capped = true;
                    org.slf4j.LoggerFactory.getLogger("bbsvfx").warn("[dome-capture] block cap hit ({}) at radius {} — reduce Max radius to level more", MAX_BLOCKS, radius);

                    break;
                }
            }
        }

        if (replay != null)
        {
            replay.keyframes.y.insert(0F, (double) oy);
        }

        /* Fill by the ORIGINAL state so an underwater raze leaves water, not an air bubble. */
        for (int i = 0; i < cut.size(); i++)
        {
            world.setBlockState(cut.get(i), fillFor(old.get(i)), 2);
        }

        server.execute(() -> withDamageControlSuspended(() ->
        {
            for (int i = 0; i < cut.size(); i++)
            {
                serverWorld.setBlockState(cut.get(i), fillFor(old.get(i)), 2);
            }
        }));

        if (append && lastCutPos != null && lastWorld == world.getRegistryKey())
        {
            lastCutPos.addAll(cut);
            lastCutState.addAll(old);
        }
        else
        {
            lastCutPos = cut;
            lastCutState = old;
            lastWorld = world.getRegistryKey();
        }
    }

    /**
     * Re-cut the last levelling dome to its CURRENT {@code max_radius} (the "Re-cut" button): restores the
     * previous cut (client + server) so the world is whole, then razes the circle afresh at the new
     * radius. Lets you spawn once, tune / animate the radius, and re-apply the destruction to match —
     * independent of the original wand mark.
     */
    public static void recutDome(com.bbsvfx.bbsvfx.forms.DomeForm edited)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;
        MinecraftServer server = mc.getServer();

        if (world == null || server == null)
        {
            return;
        }

        ServerWorld serverWorld = server.getWorld(world.getRegistryKey());

        if (serverWorld == null)
        {
            return;
        }

        /* The scene re-copies the form from the EDITED form (the panel's this.form) every frame, so the
         * blocks must land on THAT instance. Fall back to the rendered instance when there's no editor. */
        com.bbsvfx.bbsvfx.forms.DomeForm target = edited != null ? edited : renderedDomeForm;

        /* Origin: this session's capture when available, else the render-time note — re-cut must work in
         * a LATER session than the capture (the lastDome* statics die with the client). */
        double[] origin = lastDomeOrigin;
        int ox, oy, oz;

        if (lastDomeReplay != null)
        {
            ox = lastDomeOx;
            oz = lastDomeOz;
            oy = origin != null ? (int) Math.round(origin[1]) : Integer.MIN_VALUE;
        }
        else if (origin != null)
        {
            ox = (int) Math.round(origin[0]);
            oy = (int) Math.round(origin[1]);
            oz = (int) Math.round(origin[2]);
        }
        else
        {
            System.err.println("[bbsvfx] dome re-cut: actor origin unknown yet — let the actor render once");

            return;
        }

        if (target == null)
        {
            return;
        }

        /* Mirror onto the replay form too so the cut survives the editor closing (same-session only). */
        if (lastDomeReplay != null && lastDomeReplay.form.get() instanceof com.bbsvfx.bbsvfx.forms.DomeForm rf && rf != target)
        {
            rf.maxRadius.set(target.maxRadius.get());
        }

        /* Restore the previous cut from the FORM'S OWN stored blocks (session-independent — the block
         * list is serialized with the film), so the fresh scan sees a whole world. */
        if (oy != Integer.MIN_VALUE && !target.blocks.getList().isEmpty())
        {
            List<com.bbsvfx.bbsvfx.forms.DestructionBlock> stored = target.blocks.getAllTyped();
            List<BlockPos> rp = new ArrayList<>(stored.size());
            List<BlockState> rs = new ArrayList<>(stored.size());

            for (com.bbsvfx.bbsvfx.forms.DestructionBlock sb : stored)
            {
                rp.add(new BlockPos(ox + sb.x.get(), oy + sb.y.get(), oz + sb.z.get()));
                rs.add(sb.blockState());
            }

            for (int i = 0; i < rp.size(); i++)
            {
                world.setBlockState(rp.get(i), rs.get(i), 2);
            }

            server.execute(() -> withDamageControlSuspended(() ->
            {
                for (int i = 0; i < rp.size(); i++)
                {
                    serverWorld.setBlockState(rp.get(i), rs.get(i), 2);
                }
            }));

            lastCutPos = null;
            lastCutState = null;
        }

        razeDomeCircle(mc, world, serverWorld, server, target, ox, oz, lastDomeReplay, false);
        lastDomeForm = target;
        lastDomeOx = ox;
        lastDomeOz = oz;
    }

    /**
     * Re-run the foliage scan of an EXISTING explosion actor at the form's CURRENT bend radius (the
     * "Rescan foliage" button): the captured foliage is restored to the world first, the list cleared,
     * then the cylinder is scanned and cut afresh. New cuts join the undo of the last capture when one
     * is still tracked. The actor origin comes from the render-time note (see {@link #noteOrigin}).
     */
    public static void rescanFoliage(ExplosionForm uiForm)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;
        MinecraftServer server = mc.getServer();

        if (world == null || server == null)
        {
            return;
        }

        /* The BUTTON hands us the EDITOR'S COPY of the form; the renderer draws the replay's ORIGINAL
         * (physSrc) — writing foliage into the copy changed nothing on screen. Operate on the RENDERED
         * instance (the one the renderer noted) and mirror the result into the copy so the editor's
         * state stays consistent. */
        double[] origin;

        synchronized (ORIGINS)
        {
            origin = ORIGINS.get(uiForm);
        }

        final ExplosionForm scanTarget = origin != null || lastForm == null ? uiForm : lastForm;

        if (origin == null)
        {
            origin = lastOrigin;
        }

        if (origin == null)
        {
            System.err.println("[bbsvfx] foliage rescan: actor origin unknown yet — let the actor render once");

            return;
        }

        final ExplosionForm mirrorForm = scanTarget != uiForm ? uiForm : null;

        if (!RESCAN_RUNNING.compareAndSet(false, true))
        {
            System.err.println("[bbsvfx] foliage rescan is already running — ignored");

            return;
        }

        /* The user set the SCAN range on the EDITOR'S copy — carry it onto the rendered form; the
         * sway falloff reach follows the scan. */
        final float radius = Math.min(320F, uiForm.bendScanRadius.get());

        scanTarget.bendScanRadius.set(radius);
        scanTarget.bendRadius.set(radius);

        int originX = (int) Math.round(origin[0]);
        int originY = (int) Math.round(origin[1]);
        int originZ = (int) Math.round(origin[2]);

        ServerWorld serverWorld = server.getWorld(world.getRegistryKey());

        if (serverWorld == null)
        {
            return;
        }

        /* Restore the current foliage synchronously-ish (server thread), THEN scan on the next client
         * tick so the restored blocks are visible to the scan. Simpler: restore + scan in one server
         * pass is impossible (scan reads the CLIENT world) — so restore, then defer the scan+cut by
         * executing it after the server flush via the client executor. */
        List<DestructionBlock> foliage = new java.util.ArrayList<>(scanTarget.foliage.getAllTyped());

        scanTarget.foliage.clearBlocks();

        server.execute(() -> withDamageControlSuspended(() ->
        {
            for (DestructionBlock block : foliage)
            {
                BlockState place = block.blockState();

                /* PERSISTENT leaves + flag 2 (no neighbour updates): the natural restore used to kick
                 * a server-side leaf-`distance` RECALC WAVE on the following ticks — tens of thousands
                 * of setBlockState calls OUTSIDE our damage-control suspension. Recorded during film
                 * play, the wave got resurrected at the film stop under the proxies ("duplicate trees
                 * after playback"). Persistent leaves neither recalc nor decay; the restore is
                 * transient anyway (re-cut seconds later). */
                if (place.contains(net.minecraft.block.LeavesBlock.PERSISTENT))
                {
                    place = place.with(net.minecraft.block.LeavesBlock.PERSISTENT, true);
                }

                serverWorld.setBlockState(new BlockPos(originX + block.x.get(), originY + block.y.get(),
                    originZ + block.z.get()), place, 2);
            }
        }));

        /* Give the restore time to run on the server AND sync to the client (a render-thread
         * mc.execute runs IMMEDIATELY — the first version scanned a pre-restore world and captured
         * nothing, wiping the foliage). A short off-thread delay, then back onto the render thread.
         * A big restore (40k+ blocks) can outlive one delay — an EMPTY scan is retried a few times
         * before giving up and rolling back. */
        rescanAttempt(mc, world, server, serverWorld, scanTarget, mirrorForm, foliage,
            originX, originY, originZ, radius, 1);
    }

    /* Overlapping rescans interleave their restore/scan/cut server passes — the source of
     * "duplicate trees" (a captured block left standing = real block + proxy on top). */
    private static final java.util.concurrent.atomic.AtomicBoolean RESCAN_RUNNING =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The CUT VERIFIER: ~2 s after a rescan settles, re-check every captured position on the client —
     * anything still standing (restore/cut race, whatever the cause) is cut again, so a captured
     * block can never coexist with its real-world original (the "duplicate tree").
     */
    private static void verifyCut(MinecraftClient mc, World world, MinecraftServer server,
        ServerWorld serverWorld, ExplosionForm scanTarget, int originX, int originY, int originZ)
    {
        verifyCutRound(mc, world, server, serverWorld, scanTarget, originX, originY, originZ, 1);
    }

    private static void verifyCutRound(MinecraftClient mc, World world, MinecraftServer server,
        ServerWorld serverWorld, ExplosionForm scanTarget, int originX, int originY, int originZ, int round)
    {
        Thread delay = new Thread(() ->
        {
            try
            {
                Thread.sleep(3000L);
            }
            catch (InterruptedException ignored)
            {
            }

            mc.execute(() ->
            {
                List<BlockPos> recut = new java.util.ArrayList<>();

                for (DestructionBlock block : scanTarget.foliage.getAllTyped())
                {
                    BlockPos pos = new BlockPos(originX + block.x.get(), originY + block.y.get(),
                        originZ + block.z.get());
                    BlockState state = world.getBlockState(pos);

                    /* By BLOCK, not state identity — leaf `distance` gets recalculated on placement,
                     * so a standing restored leaf never identity-matches its captured state. */
                    if (!state.isAir() && state.getBlock() == block.blockState().getBlock())
                    {
                        recut.add(pos);
                    }
                }

                if (!recut.isEmpty())
                {
                    server.execute(() -> withDamageControlSuspended(() ->
                    {
                        BlockState air = Blocks.AIR.getDefaultState();

                        for (BlockPos pos : recut)
                        {
                            serverWorld.setBlockState(pos, air, 2);
                        }
                    }));
                }

                System.out.println("[bbsvfx] foliage cut verifier round " + round + ": " + recut.size()
                    + " captured blocks still standing" + (recut.isEmpty() ? " — clean" : " — re-cut"));

                /* The big cut may still be mid-flight on the server — keep checking until clean. */
                if (!recut.isEmpty() && round < 4)
                {
                    verifyCutRound(mc, world, server, serverWorld, scanTarget,
                        originX, originY, originZ, round + 1);
                }
            });
        }, "bbsvfx-foliage-verify-" + round);

        delay.setDaemon(true);
        delay.start();
    }

    private static void rescanAttempt(MinecraftClient mc, World world, MinecraftServer server,
        ServerWorld serverWorld, ExplosionForm scanTarget, ExplosionForm mirrorForm,
        List<DestructionBlock> foliage, int originX, int originY, int originZ, float radius, int attempt)
    {
        Thread delay = new Thread(() ->
        {
            try
            {
                Thread.sleep(attempt == 1 ? 400L : 600L);
            }
            catch (InterruptedException ignored)
            {
            }

            mc.execute(() ->
            {
                /* READINESS GATE: don't scan until the restore is actually VISIBLE on the client —
                 * sample the restored positions and wait for ≥98% to match. The count thresholds
                 * (empty / 70%) kept letting partially-synced worlds through, which truncated the
                 * capture and left real tree parts standing under the proxies (duplicates). */
                if (!foliage.isEmpty() && attempt < 15)
                {
                    int sample = 0, present = 0;
                    int step = Math.max(1, foliage.size() / 200);

                    for (int i = 0; i < foliage.size(); i += step)
                    {
                        DestructionBlock block = foliage.get(i);
                        BlockPos pos = new BlockPos(originX + block.x.get(), originY + block.y.get(),
                            originZ + block.z.get());

                        sample++;

                        /* Compare by BLOCK, not state identity: the server recalculates leaf
                         * `distance` on placement, so a restored leaf never identity-matches. */
                        if (world.getBlockState(pos).getBlock() == block.blockState().getBlock())
                        {
                            present++;
                        }
                    }

                    if (present < sample * 98 / 100)
                    {
                        System.out.println("[bbsvfx] foliage rescan attempt " + attempt + ": restore "
                            + present + "/" + sample + " sampled — waiting for sync");
                        rescanAttempt(mc, world, server, serverWorld, scanTarget, mirrorForm, foliage,
                            originX, originY, originZ, radius, attempt + 1);

                        return;
                    }
                }

                List<BlockPos> cut = new java.util.ArrayList<>();
                List<BlockState> old = new java.util.ArrayList<>();
                float ex = originX + scanTarget.pointX.get();
                float ey = originY + scanTarget.pointY.get();
                float ez = originZ + scanTarget.pointZ.get();

                scanFoliageInto(world, mc, scanTarget, originX, originY, originZ, ex, ey, ez,
                    0F, 0F, 0F, 0F, radius, cut, old);

                /* A big restore may be only PARTIALLY synced to the client when we scan (a second
                 * rescan right after a 74k-block one caught 42k) — anything clearly below the previous
                 * capture is treated as not-ready and retried, not accepted. */
                int found = scanTarget.foliage.getList().size();

                if (found < foliage.size() * 7 / 10 && attempt < 4)
                {
                    scanTarget.foliage.clearBlocks();
                    System.out.println("[bbsvfx] foliage rescan attempt " + attempt + " found " + found
                        + " of ~" + foliage.size() + " — restore still syncing, retrying");
                    rescanAttempt(mc, world, server, serverWorld, scanTarget, mirrorForm, foliage,
                        originX, originY, originZ, radius, attempt + 1);

                    return;
                }

                if (scanTarget.foliage.getList().isEmpty() && !foliage.isEmpty())
                {

                    /* Still nothing — ROLL BACK to the previous capture instead of losing it: re-add
                     * the old set and cut it again at its known positions. */
                    for (DestructionBlock block : foliage)
                    {
                        scanTarget.foliage.addBlock(block.x.get(), block.y.get(), block.z.get(),
                            block.blockState(), block.tint.get(), block.light.get());
                        cut.add(new BlockPos(originX + block.x.get(), originY + block.y.get(), originZ + block.z.get()));
                    }

                    System.err.println("[bbsvfx] foliage rescan found nothing — kept the previous capture");
                }

                server.execute(() -> withDamageControlSuspended(() ->
                {
                    for (int i = 0; i < cut.size(); i++)
                    {
                        serverWorld.setBlockState(cut.get(i), fillFor(old.get(i)), 2);
                    }
                }));

                /* Best effort: keep undo able to restore the new cut too. */
                if (lastCutPos != null && lastWorld == world.getRegistryKey())
                {
                    lastCutPos.addAll(cut);
                    lastCutState.addAll(old);
                }

                /* Mirror the fresh set into the editor's copy, so its state matches the rendered form. */
                if (mirrorForm != null)
                {
                    mirrorForm.foliage.clearBlocks();

                    for (DestructionBlock block : scanTarget.foliage.getAllTyped())
                    {
                        mirrorForm.foliage.addBlock(block.x.get(), block.y.get(), block.z.get(),
                            block.blockState(), block.tint.get(), block.light.get());
                    }

                    mirrorForm.bendScanRadius.set(scanTarget.bendScanRadius.get());
                    mirrorForm.bendRadius.set(scanTarget.bendRadius.get());
                }

                System.out.println("[bbsvfx] foliage rescan: " + scanTarget.foliage.getList().size()
                    + " blocks at r=" + Math.round(scanTarget.bendRadius.get()));

                /* Self-heal any restore/cut race: no captured block may stay standing in the world. */
                verifyCut(mc, world, server, serverWorld, scanTarget, originX, originY, originZ);
                RESCAN_RUNNING.set(false);
            });
        }, "bbsvfx-foliage-rescan-" + attempt);

        delay.setDaemon(true);
        delay.start();
    }

    /**
     * After BBS restores the play-time damage (film stop), re-run the cut verifier on the last noted
     * explosion — any captured foliage the restore resurrected gets cut again. Self-healing no matter
     * which path let the change slip into the recording.
     */
    public static void onDamageRestored()
    {
        ExplosionForm form = lastForm;
        double[] origin = lastOrigin;
        MinecraftClient mc = MinecraftClient.getInstance();

        if (form == null || origin == null || mc.world == null || mc.getServer() == null
            || form.foliage.getList().isEmpty())
        {
            return;
        }

        ServerWorld serverWorld = mc.getServer().getWorld(mc.world.getRegistryKey());

        if (serverWorld == null)
        {
            return;
        }

        verifyCut(mc, mc.world, mc.getServer(), serverWorld, form,
            (int) Math.round(origin[0]), (int) Math.round(origin[1]), (int) Math.round(origin[2]));
    }

    /**
     * Capture-time light for a block: {@code getLightmapCoordinates} INSIDE an opaque block is 0
     * (everything rendered black) — take the brightest of the six neighbour cells (the lit face,
     * vanilla block rendering does the same per face); a fully BURIED block falls back to its
     * column's surface light, so crater debris flying out into the open reads sun-lit.
     */
    static int captureLight(World world, BlockPos pos)
    {
        int bestBlock = 0, bestSky = 0;

        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values())
        {
            int l = net.minecraft.client.render.WorldRenderer.getLightmapCoordinates(world, pos.offset(dir));

            bestBlock = Math.max(bestBlock, l & 0xFFFF);
            bestSky = Math.max(bestSky, (l >> 16) & 0xFFFF);
        }

        if (bestBlock == 0 && bestSky == 0)
        {
            int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ());
            int l = net.minecraft.client.render.WorldRenderer.getLightmapCoordinates(world,
                new BlockPos(pos.getX(), top, pos.getZ()));

            bestBlock = l & 0xFFFF;
            bestSky = (l >> 16) & 0xFFFF;
        }

        return (bestSky << 16) | bestBlock;
    }

    /** Foliage the bend system captures: tree wood + leaves + the small ground plants. */
    /**
     * What to leave where a captured block was cut out. ★Seagrass, kelp and anything waterlogged CARRY
     * water: replacing them with plain air punches dry bubbles into the sea. Put the fluid back instead, so
     * an underwater cut is invisible.
     */
    public static BlockState fillFor(BlockState cut)
    {
        return cut.getFluidState().isEmpty()
            ? Blocks.AIR.getDefaultState()
            : Blocks.WATER.getDefaultState();
    }

    public static boolean isFoliage(BlockState state)
    {
        if (state.isIn(net.minecraft.registry.tag.BlockTags.LOGS)
            || state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES))
        {
            return true;
        }

        net.minecraft.block.Block block = state.getBlock();

        return block instanceof net.minecraft.block.PlantBlock
            || block instanceof net.minecraft.block.SugarCaneBlock;
    }

    /**
     * Runs a world edit on the server thread with BBS damage control suspended, so the change is not
     * recorded and later reverted when a film playback stops. Must be called on the server thread.
     */
    public static void withDamageControlSuspended(Runnable edit)
    {
        Collection<DamageControl> controls = new ArrayList<>(
            ((ActionManagerAccessor) (Object) BBSMod.getActions()).getDamageControls().values());
        boolean[] previous = new boolean[controls.size()];
        int i = 0;

        for (DamageControl control : controls)
        {
            previous[i++] = control.enable;
            control.enable = false;
        }

        try
        {
            edit.run();
        }
        finally
        {
            i = 0;

            for (DamageControl control : controls)
            {
                control.enable = previous[i++];
            }
        }
    }

    /** Restore the blocks removed by the last capture. */
    public static void undo()
    {
        if (lastCutPos == null || lastWorld == null)
        {
            return;
        }

        MinecraftServer server = MinecraftClient.getInstance().getServer();

        if (server == null)
        {
            return;
        }

        ServerWorld serverWorld = server.getWorld(lastWorld);

        if (serverWorld == null)
        {
            return;
        }

        List<BlockPos> positions = lastCutPos;
        List<BlockState> states = lastCutState;

        server.execute(() -> withDamageControlSuspended(() ->
        {
            for (int i = 0; i < positions.size(); i++)
            {
                serverWorld.setBlockState(positions.get(i), states.get(i), 2);
            }
        }));

        lastCutPos = null;
        lastCutState = null;
        lastWorld = null;
    }
}
