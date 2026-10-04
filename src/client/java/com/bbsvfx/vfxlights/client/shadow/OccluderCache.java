package com.bbsvfx.vfxlights.client.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The geometry a light casts shadows from, baked into a vertex buffer per light.
 *
 * <p><b>The whole point is that blocks are not cubes.</b> A fence is a post and two rails; a slab is half
 * a block; a stair is two boxes. Their {@code VoxelShape} says so exactly, and rendering that shape into
 * the depth map is what makes a fence throw a fence-shaped shadow — light passing between the rails,
 * which is precisely what the screen-space attempt could never do, because a depth buffer records a
 * surface and knows nothing about the gaps behind it.</p>
 *
 * <p><b>Rebuilt rarely.</b> The buffer is keyed on the light's position and radius SNAPPED to whole
 * blocks, so a lamp drifting a few centimetres — or being animated on a curve — does not rebuild
 * anything. Only crossing into a new block, changing range, or a block changing nearby does.</p>
 *
 * <p>Positions are stored relative to a whole-block origin near the light. Feeding raw world
 * coordinates into a float vertex buffer costs precision far from spawn, and shadow maps show that loss
 * as crawling z-fighting.</p>
 */
public final class OccluderCache
{
    /** Hard cap on boxes per light, so one lamp in a dense build cannot stall the frame. */
    private static final int MAX_BOXES = 20000;
    /** Cap on cutout blocks per light — full block models are far heavier than boxes. */
    private static final int MAX_CUTOUTS = 16384;
    /** Cap on glass casters per light. 4096 overflowed in glass-heavy builds and the shell scan's
     * cutoff — a voxel SPHERE around the lamp — carved staircase arcs out of every window in the
     * colour map: past the arc, stained light turned white (the "вырезы" saga's true culprit). */
    private static final int MAX_GLASS = 20000;

    private static final Map<Long, Entry> CACHE = new HashMap<>();

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("vfxlights");

    private static final Direction[] DIRECTIONS = Direction.values();

    /** Neighbour probe for the sealed-block skip; client thread only, like the scratch lists. */
    private static final BlockPos.Mutable NEIGHBOUR = new BlockPos.Mutable();

    /** Cutout positions found during the shell scan of the current build, packed as longs. */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList cutoutScratch =
        new it.unimi.dsi.fastutil.longs.LongArrayList();

    /** Glass positions and their packed RGB tints, gathered by the same scan for the colour map. */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList glassScratch =
        new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static final it.unimi.dsi.fastutil.ints.IntArrayList glassTintScratch =
        new it.unimi.dsi.fastutil.ints.IntArrayList();

    private OccluderCache()
    {
    }

    /** One light's baked occluders. */
    public static class Entry
    {
        public VertexBuffer buffer;
        /**
         * Cutout-layer blocks (leaves, plants, bars, door windows), kept as REAL geometry with
         * texture, drawn with alpha test — so their shadows have the holes the boxes lost.
         */
        public VertexBuffer cutoutBuffer;
        public boolean cutoutEmpty = true;
        /** Glass casters as coloured cubes for the atlas' colour map — light passes, tinted. */
        public VertexBuffer glassBuffer;
        public boolean glassEmpty = true;
        /** The same glass, as raw data — drawn immediate-mode per tile (the buffer path dropped quads). */
        public final it.unimi.dsi.fastutil.longs.LongArrayList glassPositions =
            new it.unimi.dsi.fastutil.longs.LongArrayList();
        public final it.unimi.dsi.fastutil.ints.IntArrayList glassTints =
            new it.unimi.dsi.fastutil.ints.IntArrayList();
        /** Whole-block origin the geometry is relative to. */
        public int originX;
        public int originY;
        public int originZ;
        /** Key the buffer was built for: snapped centre and radius. */
        long key;
        /** Whether {@link #build} has completed for the current key. {@code buffer != null} is NOT
         * that: a lamp with no full cube nearby (mid-air, in leaves, among glass) legitimately builds
         * ZERO boxes and no buffer, and checking the buffer made every such lamp re-run the whole
         * O(r²) shell scan each frame — the cache did not work at all for exactly that class. */
        boolean built;
        /** Frame the last {@link #build} ran on — the shadow pass skips re-rendering tiles whose
         * static geometry did not rebuild (and whose light state held). */
        public int builtFrame = -1;
        int lastUsed;
        public boolean empty;
        /** How many boxes went into the buffer — diagnostic. */
        public int boxes;

        /** Sphere this entry covers, for deciding whether a block change affects it. */
        int centreX;
        int centreY;
        int centreZ;
        int radius;
    }

    /**
     * Rebuild any light whose occluders include this block.
     *
     * <p>Without this a mined block keeps casting: the cache key is the lamp's snapped position and
     * radius, which says nothing about what the world contains. Called from the block-change mixin.</p>
     */
    public static void invalidateAt(int x, int y, int z)
    {
        for (Entry entry : CACHE.values())
        {
            int dx = x - entry.centreX;
            int dy = y - entry.centreY;
            int dz = z - entry.centreZ;
            int reach = entry.radius + 1;

            if (dx * dx + dy * dy + dz * dz <= reach * reach)
            {
                /* Zeroing the key forces a rebuild on the next frame this light is drawn. */
                entry.key = 0L;
            }
        }
    }

    /**
     * Occluders for a light at the given position and radius, rebuilding only when the snapped position,
     * the radius, or the world content has changed.
     */
    public static Entry get(long lightId, World world, double x, double y, double z, float radius, int frame)
    {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        int r = (int) Math.ceil(radius);
        long key = key(bx, by, bz, r);

        Entry entry = CACHE.get(lightId);

        if (entry != null && entry.key == key && entry.built)
        {
            entry.lastUsed = frame;

            return entry;
        }

        if (entry == null)
        {
            entry = new Entry();
            CACHE.put(lightId, entry);
        }

        entry.key = key;
        entry.lastUsed = frame;
        entry.originX = bx;
        entry.originY = by;
        entry.originZ = bz;
        entry.centreX = bx;
        entry.centreY = by;
        entry.centreZ = bz;
        entry.radius = r;

        build(entry, world, bx, by, bz, r);
        entry.builtFrame = frame;

        return entry;
    }

    /** Set by the scan when it meets an UNLOADED chunk — the bake is then incomplete. */
    private static boolean sawUnloadedChunk;

    private static void build(Entry entry, World world, int bx, int by, int bz, int radius)
    {
        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int boxes = 0;

        sawUnloadedChunk = false;
        cutoutScratch.clear();
        glassScratch.clear();
        glassTintScratch.clear();
        builder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);

        /* ★Concentric shells outward from the lamp, NOT a plain triple loop. A linear scan fills the
         * budget in axis order, and when the cap lands mid-scan it silently discards one whole SIDE of
         * the sphere — measured in game as "the player casts but blocks don't": 25k candidates against
         * a 20k cap had eaten everything on the +X side of the lamp, fence included. Shells make the
         * cap shed the FARTHEST geometry instead, which is the part shadows miss least. */
        for (int shell = 0; shell <= radius; shell++)
        {
            int inner = shell - 1;

            for (int dx = -shell; dx <= shell; dx++)
            {
                for (int dz = -shell; dz <= shell; dz++)
                {
                    boolean rim = Math.abs(dx) == shell || Math.abs(dz) == shell;

                    for (int dy = -shell; dy <= shell; dy++)
                    {
                        /* Only positions NEW to this shell: interior columns contribute just their
                         * top and bottom caps. */
                        if (!rim && Math.abs(dy) <= inner)
                        {
                            continue;
                        }

                        if (dx * dx + dy * dy + dz * dz > radius * radius)
                        {
                            continue;
                        }

                        /* The box CAP no longer aborts the scan: when it filled mid-sweep it silently
                         * dropped every GLASS block past that shell — measured as coloured window
                         * cells missing from the colour map (white gashes through the stained pool).
                         * emitBlock keeps recording glass and cutouts; only box EMISSION stops. */
                        boxes = emitBlock(builder, world, pos, bx, by, bz, dx, dy, dz, boxes);
                    }
                }
            }
        }

        /* No silent caps: a budget that fills mid-scan clips every shadow past the filled radius,
         * and that must be a log line, not a mystery ring. */
        if (boxes >= MAX_BOXES || cutoutScratch.size() >= MAX_CUTOUTS || glassScratch.size() >= MAX_GLASS)
        {
            LOG.warn("Occluder budget hit for light near {} {} {} — boxes {}/{}, cutouts {}/{}, glass {}/{}; "
                    + "shadows are clipped beyond the radius the budget reached",
                bx, by, bz, boxes, MAX_BOXES, cutoutScratch.size(), MAX_CUTOUTS,
                glassScratch.size(), MAX_GLASS);
        }

        /* A light in a tree may have NO solid boxes and still plenty of cutout leaves — empty means
         * neither kind of geometry, not merely no boxes. */
        entry.boxes = boxes;
        entry.empty = boxes == 0 && cutoutScratch.isEmpty();

        if (boxes == 0)
        {
            /* Nothing to upload; ending an empty builder would still allocate a draw with no vertices. */
            builder.end().release();
        }
        else
        {
            BufferBuilder.BuiltBuffer built = builder.end();

            if (entry.buffer == null)
            {
                entry.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }

            entry.buffer.bind();
            entry.buffer.upload(built);
            VertexBuffer.unbind();
        }

        buildCutout(entry, world, bx, by, bz);
        buildGlass(entry, bx, by, bz);

        /* Marks the bake complete for this key even when it produced no geometry at all — the "lamp
         * in mid-air" case must be a cache HIT next frame, not a rescan. */
        entry.built = true;

        /* A bake that met an unloaded chunk is a PARTIAL truth — stained window columns went missing
         * along chunk borders on world join and STAYED missing, because only block CHANGES invalidate
         * the cache. Zeroing the key makes the next frame rebuild; the partial result still serves
         * this frame, so the light never blinks while the world streams in. */
        if (sawUnloadedChunk)
        {
            entry.key = 0L;
        }
    }

    /**
     * Bake the glass casters as coloured cubes. They carry no depth — the colour pass draws them with
     * depth writes off into the atlas' COLOUR map, where their tint multiplies whatever light passes.
     */
    private static void buildGlass(Entry entry, int bx, int by, int bz)
    {
        entry.glassEmpty = glassScratch.isEmpty();
        entry.glassPositions.clear();
        entry.glassTints.clear();
        entry.glassPositions.addAll(glassScratch);
        entry.glassTints.addAll(glassTintScratch);

        if (entry.glassEmpty)
        {
            return;
        }

        BufferBuilder builder = Tessellator.getInstance().getBuffer();

        builder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (int i = 0; i < glassScratch.size(); i++)
        {
            long packed = glassScratch.getLong(i);
            int tint = glassTintScratch.getInt(i);
            float x0 = BlockPos.unpackLongX(packed) - bx;
            float y0 = BlockPos.unpackLongY(packed) - by;
            float z0 = BlockPos.unpackLongZ(packed) - bz;
            int r = (tint >> 16) & 0xFF;
            int g = (tint >> 8) & 0xFF;
            int b = tint & 0xFF;

            /* INFLATED a hair: edge-on faces of exactly-touching boxes leave one-texel raster cracks
             * in the colour map, and a crack reads as "no glass" — thin WHITE streaks through the
             * stained pool (measured on the cathedral-window scene). Overlap merely doubles the tint
             * along a seam, which the eye forgives; a white gash it does not. */
            emitColoredBox(builder, x0 - 0.01F, y0 - 0.01F, z0 - 0.01F,
                x0 + 1.01F, y0 + 1.01F, z0 + 1.01F, r, g, b);
        }

        BufferBuilder.BuiltBuffer built = builder.end();

        if (entry.glassBuffer == null)
        {
            entry.glassBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }

        entry.glassBuffer.bind();
        entry.glassBuffer.upload(built);
        VertexBuffer.unbind();
    }

    /** Six coloured quads of an axis-aligned box. */
    public static void emitColoredBox(BufferBuilder builder, float x0, float y0, float z0,
        float x1, float y1, float z1, int r, int g, int b)
    {
        builder.vertex(x0, y0, z0).color(r, g, b, 255).next();
        builder.vertex(x1, y0, z0).color(r, g, b, 255).next();
        builder.vertex(x1, y1, z0).color(r, g, b, 255).next();
        builder.vertex(x0, y1, z0).color(r, g, b, 255).next();

        builder.vertex(x0, y0, z1).color(r, g, b, 255).next();
        builder.vertex(x0, y1, z1).color(r, g, b, 255).next();
        builder.vertex(x1, y1, z1).color(r, g, b, 255).next();
        builder.vertex(x1, y0, z1).color(r, g, b, 255).next();

        builder.vertex(x0, y0, z0).color(r, g, b, 255).next();
        builder.vertex(x0, y0, z1).color(r, g, b, 255).next();
        builder.vertex(x1, y0, z1).color(r, g, b, 255).next();
        builder.vertex(x1, y0, z0).color(r, g, b, 255).next();

        builder.vertex(x0, y1, z0).color(r, g, b, 255).next();
        builder.vertex(x1, y1, z0).color(r, g, b, 255).next();
        builder.vertex(x1, y1, z1).color(r, g, b, 255).next();
        builder.vertex(x0, y1, z1).color(r, g, b, 255).next();

        builder.vertex(x0, y0, z0).color(r, g, b, 255).next();
        builder.vertex(x0, y1, z0).color(r, g, b, 255).next();
        builder.vertex(x0, y1, z1).color(r, g, b, 255).next();
        builder.vertex(x0, y0, z1).color(r, g, b, 255).next();

        builder.vertex(x1, y0, z0).color(r, g, b, 255).next();
        builder.vertex(x1, y0, z1).color(r, g, b, 255).next();
        builder.vertex(x1, y1, z1).color(r, g, b, 255).next();
        builder.vertex(x1, y1, z0).color(r, g, b, 255).next();
    }

    /**
     * Bake the cutout blocks as their REAL models, texture and all.
     *
     * <p>The point of this second pass: a leaf block's shadow should be a leaf pattern, a door's should
     * have a window in it. The box path cannot express that — only the actual quads with their alpha
     * channel can, so these render through the block renderer at full fidelity and the shadow pass
     * draws them with alpha test on.</p>
     */
    private static void buildCutout(Entry entry, World world, int bx, int by, int bz)
    {
        entry.cutoutEmpty = cutoutScratch.isEmpty();

        if (entry.cutoutEmpty)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        MatrixStack matrices = new MatrixStack();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Random random = Random.create();

        builder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);

        for (int i = 0; i < cutoutScratch.size(); i++)
        {
            long packed = cutoutScratch.getLong(i);

            pos.set(BlockPos.unpackLongX(packed), BlockPos.unpackLongY(packed), BlockPos.unpackLongZ(packed));

            BlockState state = world.getBlockState(pos);

            matrices.push();
            matrices.translate(pos.getX() - bx, pos.getY() - by, pos.getZ() - bz);

            /* The block's own seed keeps random model rotations identical to the world render — the
             * same lesson the destruction proxies learned the visible way. */
            random.setSeed(state.getRenderingSeed(pos));

            try
            {
                mc.getBlockRenderManager().renderBlock(state, pos, world, matrices, builder, false, random);
            }
            catch (Throwable ignored)
            {
                /* One stubborn modded model loses its shadow, not the map. */
            }

            matrices.pop();
        }

        BufferBuilder.BuiltBuffer built = builder.end();

        if (entry.cutoutBuffer == null)
        {
            entry.cutoutBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }

        entry.cutoutBuffer.bind();
        entry.cutoutBuffer.upload(built);
        VertexBuffer.unbind();
    }

    /** Emit one block's shape, returning the updated box count. */
    private static int emitBlock(BufferBuilder builder, World world, BlockPos.Mutable pos,
        int bx, int by, int bz, int dx, int dy, int dz, int boxes)
    {
        pos.set(bx + dx, by + dy, bz + dz);

        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4))
        {
            sawUnloadedChunk = true;

            return boxes;
        }

        BlockState state = world.getBlockState(pos);

        if (state.isAir() || state.getRenderType() == BlockRenderType.INVISIBLE)
        {
            return boxes;
        }

        /* ★A block sealed on all six sides by opaque full cubes has no face any light can reach — it
         * cannot cast. Without this skip a lamp near terrain spends the budget on BURIED stone: the
         * shell scan is O(r³) through solid ground, the box cap lands at a radius well inside the
         * light's reach, and everything past that radius polls "no occluder = lit" — seen in game as
         * the shadow pattern CLIPPED to an inner circle of the spot with a bright shadow-free ring
         * around it (the same sphere-cutoff arc the glass cap once carved through stained pools).
         * Emitting only reachable surfaces keeps the scan O(r²) and the caps out of real scenes. */
        boolean sealed = true;

        for (Direction d : DIRECTIONS)
        {
            NEIGHBOUR.set(pos, d);

            if (!world.getBlockState(NEIGHBOUR).isOpaqueFullCube(world, NEIGHBOUR))
            {
                sealed = false;

                break;
            }
        }

        if (sealed)
        {
            return boxes;
        }

        /* Clear and stained glass do not occlude DEPTH: real glass transmits ~90% of the light, and a
         * window that shadows like a brick wall reads wrong immediately. Instead they go to the COLOUR
         * map, where stained glass tints what passes through it (clear glass writes white — a no-op).
         * TINTED glass is light-proof by design, so it keeps casting like a solid. */
        net.minecraft.block.Block block = state.getBlock();
        float[] glassTint = com.bbsvfx.vfxlights.client.light.GlassDispersion.tintOf(block);

        if (glassTint != null)
        {
            if (glassScratch.size() < MAX_GLASS)
            {
                glassScratch.add(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
                glassTintScratch.add(((int) (glassTint[0] * 255F) << 16)
                    | ((int) (glassTint[1] * 255F) << 8) | (int) (glassTint[2] * 255F));
            }

            return boxes;
        }

        /* Boxes are for FULL CUBES only; everything else casts from its real model.
         *
         * Two separate lessons converged here. Cutout-layer blocks (leaves, plants, bars, door
         * windows) boxed into solid shadows — that was the first split. Then the fence showed one
         * opening in its shadow where the model has two: vanilla's VoxelShape for a fence side is ONE
         * slab spanning both rails — the second gap exists only in the visual model, so NO shape-based
         * path can ever cast it. Shadows are about what the eye sees; only full cubes are honestly
         * described by a box, so only they get one. */
        RenderLayer layer = RenderLayers.getBlockLayer(state);
        boolean cutout = layer == RenderLayer.getCutout() || layer == RenderLayer.getCutoutMipped();
        VoxelShape culling = shapeOf(state, world, pos);
        boolean fullCube = culling != null && net.minecraft.block.Block.isShapeFullCube(culling);

        if (cutout || !fullCube)
        {
            if (cutoutScratch.size() < MAX_CUTOUTS)
            {
                cutoutScratch.add(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
            }

            return boxes;
        }

        VoxelShape shape = culling;

        if (shape == null || shape.isEmpty() || boxes >= MAX_BOXES)
        {
            return boxes;
        }

        for (net.minecraft.util.math.Box box : shape.getBoundingBoxes())
        {
            emitBox(builder,
                (float) (dx + box.minX), (float) (dy + box.minY), (float) (dz + box.minZ),
                (float) (dx + box.maxX), (float) (dy + box.maxY), (float) (dz + box.maxZ));

            if (++boxes >= MAX_BOXES)
            {
                return boxes;
            }
        }

        return boxes;
    }

    /**
     * The shape to cast from: culling first (full blocks, cheap and exact), then OUTLINE, then
     * collision as a last resort.
     *
     * <p>Outline before collision is load-bearing: the outline is the block's VISIBLE geometry, while
     * collision shapes lie for gameplay reasons — a fence's collision is a solid one-and-a-half-block
     * wall with the rail gaps filled in, and casting from it lost one of the fence's two openings in
     * the shadow (seen in game). Shadows are about what the eye sees, so the visible shape wins.</p>
     */
    private static VoxelShape shapeOf(BlockState state, World world, BlockPos pos)
    {
        try
        {
            VoxelShape shape = state.getCullingShape(world, pos);

            if (!shape.isEmpty())
            {
                return shape;
            }

            shape = state.getOutlineShape(world, pos);

            if (!shape.isEmpty())
            {
                return shape;
            }

            return state.getCollisionShape(world, pos);
        }
        catch (Throwable ignored)
        {
            /* Some modded blocks throw when asked for shapes outside a tick. Skipping one occluder is
             * better than losing the frame. */
            return null;
        }
    }

    /** Six quads of a box. Winding does not matter: the shadow pass draws with culling off. */
    private static void emitBox(BufferBuilder b, float x1, float y1, float z1, float x2, float y2, float z2)
    {
        b.vertex(x1, y1, z1).next();
        b.vertex(x1, y2, z1).next();
        b.vertex(x2, y2, z1).next();
        b.vertex(x2, y1, z1).next();

        b.vertex(x2, y1, z2).next();
        b.vertex(x2, y2, z2).next();
        b.vertex(x1, y2, z2).next();
        b.vertex(x1, y1, z2).next();

        b.vertex(x1, y1, z2).next();
        b.vertex(x1, y2, z2).next();
        b.vertex(x1, y2, z1).next();
        b.vertex(x1, y1, z1).next();

        b.vertex(x2, y1, z1).next();
        b.vertex(x2, y2, z1).next();
        b.vertex(x2, y2, z2).next();
        b.vertex(x2, y1, z2).next();

        b.vertex(x1, y2, z1).next();
        b.vertex(x1, y2, z2).next();
        b.vertex(x2, y2, z2).next();
        b.vertex(x2, y2, z1).next();

        b.vertex(x1, y1, z2).next();
        b.vertex(x1, y1, z1).next();
        b.vertex(x2, y1, z1).next();
        b.vertex(x2, y1, z2).next();
    }

    /** Forget lights that have not been drawn for a while, freeing their buffers. */
    public static void prune(int frame, int maxAge)
    {
        Iterator<Map.Entry<Long, Entry>> it = CACHE.entrySet().iterator();

        while (it.hasNext())
        {
            Map.Entry<Long, Entry> e = it.next();

            if (frame - e.getValue().lastUsed > maxAge)
            {
                VertexBuffer buffer = e.getValue().buffer;
                VertexBuffer cutout = e.getValue().cutoutBuffer;
                VertexBuffer glass = e.getValue().glassBuffer;

                if (buffer != null)
                {
                    RenderSystem.recordRenderCall(buffer::close);
                }

                if (cutout != null)
                {
                    RenderSystem.recordRenderCall(cutout::close);
                }

                /* The glass buffer leaked here for months: clear() closed it on world change, but a
                 * lamp that simply left the shot kept its stained-glass VRAM forever. */
                if (glass != null)
                {
                    RenderSystem.recordRenderCall(glass::close);
                }

                ShadowAtlas.release(e.getKey());
                ShadowMapper.forget(e.getKey());
                it.remove();
            }
        }
    }

    /** Drop everything — used when the player changes world. */
    public static void clear()
    {
        for (Entry entry : CACHE.values())
        {
            if (entry.buffer != null)
            {
                entry.buffer.close();
            }

            if (entry.cutoutBuffer != null)
            {
                entry.cutoutBuffer.close();
            }

            if (entry.glassBuffer != null)
            {
                entry.glassBuffer.close();
            }
        }

        CACHE.clear();
    }

    private static long key(int x, int y, int z, int radius)
    {
        long h = 1469598103934665603L;

        h = (h ^ x) * 1099511628211L;
        h = (h ^ y) * 1099511628211L;
        h = (h ^ z) * 1099511628211L;
        h = (h ^ radius) * 1099511628211L;

        return h;
    }
}
