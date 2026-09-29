package com.bbsvfx.bbsvfx.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;
import com.bbsvfx.bbsvfx.forms.DestructionBlockList;
import com.bbsvfx.bbsvfx.forms.WindForm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The NON-DESTRUCTIVE foliage sway for a directly-placed {@link WindForm} (Approach A). It removes the
 * zone's foliage from the CLIENT render only ({@code ClientWorld.setBlockState}) — the integrated server and
 * disk are never touched — and draws swaying proxies ({@link ExplosionFoliageVAO#renderWind}) in its place.
 *
 * <p>Because nothing is written server-side there is zero risk of permanent loss and no film lifecycle is
 * needed: a crash, reload or leave simply restores the foliage (the server still has it). If the server
 * re-sends a section (relight, a neighbour change) the real blocks reappear on the client; {@link #ensure}
 * re-applies the client cut every frame, so it self-heals. The captured proxy blocks live in a TRANSIENT
 * list (not the serialized form), re-scanned when the zone moves.</p>
 *
 * <p>One active zone (the common case). Restore is a plain client re-place of the snapshot.</p>
 */
public final class WindFoliage
{
    private static final DestructionBlockList PROXY = new DestructionBlockList("wind_proxy");
    private static final List<BlockPos> CUT_POS = new ArrayList<>();
    private static final List<BlockState> CUT_OLD = new ArrayList<>();

    private static boolean scanned;
    /** The INTEGER origin the proxies were stored against ({@code block − scanC}). The renderer needs exactly
     *  this to cancel the rounding — using {@code round(current origin)} instead made the correction jump a
     *  whole block whenever the drifting origin crossed a .5 boundary, which read as jitter. */
    private static int scanCx, scanCy, scanCz;
    /** The origin the current zone was scanned at (double, so a form sitting EXACTLY on a block boundary
     * doesn't flip its floor and re-scan every frame — the residual jerk). Re-scan only on a real move. */
    private static double sx, sy, sz;
    private static int sRadius;
    /** Frames since the form last drove {@link #ensure}. When the form is removed (its model block broken)
     * render3D stops firing, so nothing would restore the cut — this watchdog does. */
    private static int unseen;

    private WindFoliage()
    {}

    /**
     * Per-frame watchdog (registered on a world-render event). If the zone is cut but the form has not
     * rendered for a while — its model block was broken / the actor removed — put the foliage back, or it
     * would stay invisibly cut. The delay tolerates brief frustum culling / off-screen frames.
     */
    public static void tick()
    {
        if (isActive() && ++unseen > 40)
        {
            restore();
        }
    }

    /** True while a zone's foliage is client-cut (so the renderer knows to restore when sway turns off). */
    public static boolean isActive()
    {
        return !CUT_POS.isEmpty();
    }

    /** The integer origin the proxies are stored against — see {@link #scanCx}. */
    public static int scanCx()
    {
        return scanCx;
    }

    public static int scanCy()
    {
        return scanCy;
    }

    public static int scanCz()
    {
        return scanCz;
    }

    /** The captured foliage as proxy blocks for the VAO (empty until a scan runs). */
    public static List<DestructionBlock> proxyBlocks()
    {
        return PROXY.getAllTyped();
    }

    /**
     * Keep the zone's foliage removed from the CLIENT render and the proxy list current. Renderer-driven.
     * Scans once per zone (records proxies + snapshots the blocks), then re-applies the client cut to any
     * block the server has re-sent. Client-only: never edits the server world.
     */
    public static void ensure(WindForm form, double ox, double oy, double oz, int radius)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;

        if (world == null)
        {
            return;
        }

        unseen = 0;   // the form is rendering — reset the removal watchdog

        /* Re-scan only on a REAL move (> 1 block) or a radius change — never on sub-block jitter. */
        if (scanned && (radius != sRadius
            || Math.abs(ox - sx) > 1.0 || Math.abs(oy - sy) > 1.0 || Math.abs(oz - sz) > 1.0))
        {
            replaceClient(world, CUT_POS, CUT_OLD);
            reset();
        }

        int cx = (int) Math.round(ox), cy = (int) Math.round(oy), cz = (int) Math.round(oz);

        if (!scanned)
        {
            scanCx = cx;
            scanCy = cy;
            scanCz = cz;
            sx = ox;
            sy = oy;
            sz = oz;
            sRadius = radius;

            int rSq = radius * radius;

            /* Collect candidates FIRST, cut second: {@code isFoliage} accepts anything tagged LOGS, and
             * players build with logs — a log cabin next to the zone used to sway like a tree. A log only
             * belongs to a TREE if its connected log body reaches actual LEAVES, which a building never
             * does, so the decision needs the whole candidate set before anything is removed. */
            List<BlockPos> cand = new ArrayList<>();
            List<BlockState> candState = new ArrayList<>();
            Set<Long> logs = new HashSet<>();
            Set<Long> leaves = new HashSet<>();

            for (int x = cx - radius; x <= cx + radius; x++)
            {
                for (int z = cz - radius; z <= cz + radius; z++)
                {
                    int ddx = x - cx, ddz = z - cz;

                    if (ddx * ddx + ddz * ddz > rSq || !world.getChunkManager().isChunkLoaded(x >> 4, z >> 4))
                    {
                        continue;
                    }

                    int top = Math.max(world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z), cy + 48);

                    for (int y = Math.max(world.getBottomY(), cy - 8); y < top; y++)
                    {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = world.getBlockState(pos);

                        if (state.isAir() || !DestructionCapture.isFoliage(state))
                        {
                            continue;
                        }

                        cand.add(pos);
                        candState.add(state);

                        if (state.isIn(BlockTags.LOGS))
                        {
                            logs.add(pos.asLong());
                        }
                        else if (state.isIn(BlockTags.LEAVES))
                        {
                            leaves.add(pos.asLong());
                        }
                    }
                }
            }

            Set<Long> treeLogs = treeLogs(logs, leaves);

            for (int i = 0; i < cand.size(); i++)
            {
                BlockPos pos = cand.get(i);
                BlockState state = candState.get(i);

                /* Plants and leaves always sway; a log only if it is part of a real tree. */
                if (state.isIn(BlockTags.LOGS) && !treeLogs.contains(pos.asLong()))
                {
                    continue;
                }

                int tint = mc.getBlockColors().getColor(state, world, pos, 0);
                int light = DestructionCapture.captureLight(world, pos);

                PROXY.addBlock(pos.getX() - cx, pos.getY() - cy, pos.getZ() - cz, state, tint, light);
                CUT_POS.add(pos);
                CUT_OLD.add(state);
                world.setBlockState(pos, fillFor(state), 2);
            }

            scanned = true;

            return;
        }

        /* Self-heal: re-apply the client cut to anything the server re-sent as a real block. */
        for (int i = 0; i < CUT_POS.size(); i++)
        {
            BlockPos pos = CUT_POS.get(i);
            BlockState fill = fillFor(CUT_OLD.get(i));

            if (world.getBlockState(pos) != fill)
            {
                world.setBlockState(pos, fill, 2);
            }
        }
    }

    /** Shared with the explosion/beam/dome cuts — see {@link DestructionCapture#fillFor}. */
    private static BlockState fillFor(BlockState cut)
    {
        return DestructionCapture.fillFor(cut);
    }

    /**
     * Split the captured logs into TREES and BUILDINGS. Logs are flood-filled into connected bodies
     * (26-connectivity, the same rule the proxy VAO uses to keep a trunk rigid); a body counts as a tree if
     * any of its logs touches a captured LEAF. A player's log cabin, fence or bridge has no leaves growing
     * out of it, so it is left standing while the forest around it sways.
     *
     * @return the subset of {@code logs} that belongs to trees.
     */
    private static Set<Long> treeLogs(Set<Long> logs, Set<Long> leaves)
    {
        Set<Long> tree = new HashSet<>();

        if (leaves.isEmpty() || logs.isEmpty())
        {
            return tree;
        }

        Set<Long> seen = new HashSet<>();
        List<Long> body = new ArrayList<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();

        for (Long start : logs)
        {
            if (!seen.add(start))
            {
                continue;
            }

            body.clear();
            queue.clear();
            queue.add(start);
            body.add(start);

            boolean leafy = false;

            while (!queue.isEmpty())
            {
                long cur = queue.poll();
                int bx = BlockPos.unpackLongX(cur), by = BlockPos.unpackLongY(cur), bz = BlockPos.unpackLongZ(cur);

                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        for (int dz = -1; dz <= 1; dz++)
                        {
                            if (dx == 0 && dy == 0 && dz == 0)
                            {
                                continue;
                            }

                            long nb = BlockPos.asLong(bx + dx, by + dy, bz + dz);

                            if (leaves.contains(nb))
                            {
                                leafy = true;
                            }
                            else if (logs.contains(nb) && seen.add(nb))
                            {
                                queue.add(nb);
                                body.add(nb);
                            }
                        }
                    }
                }
            }

            if (leafy)
            {
                tree.addAll(body);
            }
        }

        return tree;
    }

    /** Put the foliage back (client only) and clear the zone — the next {@link #ensure} re-scans. */
    public static void restore()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.world != null)
        {
            replaceClient(mc.world, CUT_POS, CUT_OLD);
        }

        reset();
    }

    private static void replaceClient(ClientWorld world, List<BlockPos> pos, List<BlockState> old)
    {
        for (int i = 0; i < pos.size(); i++)
        {
            /* Only put a plant back where OUR fill is still standing (air, or water for the underwater
             * cut) — never overwrite something the world has since put there. */
            if (world.getBlockState(pos.get(i)) == fillFor(old.get(i)))
            {
                world.setBlockState(pos.get(i), old.get(i), 2);
            }
        }
    }

    private static void reset()
    {
        PROXY.clearBlocks();
        CUT_POS.clear();
        CUT_OLD.clear();
        scanned = false;
    }
}
